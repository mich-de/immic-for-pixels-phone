package org.nasonmobile.immich;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;
import android.system.Os;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tutto lo stack del server: installazione al primo avvio, poi PostgreSQL + Valkey + Immich, ciascuno
 * in una sessione proot separata (con il proprio utente Debian), con riavvio automatico e arresto pulito.
 */
final class Stack {
    enum State { IDLE, INSTALLING, STARTING, RUNNING, STOPPING, ERROR }

    interface Listener {
        void changed();
    }

    static final Stack I = new Stack();

    private volatile State state = State.IDLE;
    private volatile String detail = "Stopped";
    private volatile int progress = -1;
    private volatile Listener listener;
    private volatile boolean stopping;
    private volatile Process setupProc;
    private Thread controller;
    private Thread exportThread;
    private volatile boolean exportNow;
    private final List<Svc> svcs = new CopyOnWriteArrayList<>();

    private Stack() {
    }

    State state() {
        return state;
    }

    String detail() {
        return detail;
    }

    /** 0..100 durante le estrazioni, -1 altrimenti */
    int progress() {
        return progress;
    }

    void setListener(Listener l) {
        listener = l;
    }

    boolean busy() {
        Thread t = controller;
        return t != null && t.isAlive();
    }

    private void set(State s, String d) {
        state = s;
        detail = d;
        progress = -1;
        fire();
    }

    private void setProgress(int pct, String d) {
        progress = pct;
        detail = d;
        fire();
    }

    private void fire() {
        Listener l = listener;
        if (l != null) l.changed();
    }

    /** per i lavori fatti durante la preparazione, prima dei servizi (DcimMode) */
    void installing(int pct, String msg) {
        if (state != State.INSTALLING) set(State.INSTALLING, msg);
        setProgress(pct, msg);
    }

    boolean isStopping() {
        return stopping;
    }

    void note(Cfg c, String msg) {
        log(c, msg);
    }

    // ------------------------------------------------------------------ start / stop

    synchronized void start(Context ctx) {
        if (state == State.STOPPING) return;
        if (busy() && !stopping) return;
        final Cfg c = new Cfg(ctx);
        stopping = false;
        controller = new Thread(new Runnable() {
            @Override
            public void run() {
                runAll(c);
            }
        }, "immich-controller");
        controller.start();
    }

    void stop(final Runnable done) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                doStop();
                if (done != null) done.run();
            }
        }, "immich-stop").start();
    }

    private synchronized void doStop() {
        stopping = true;
        set(State.STOPPING, "Stopping…");
        Thread t = controller;
        if (t != null) t.interrupt();
        Thread et = exportThread;
        if (et != null) et.interrupt();
        Process sp = setupProc;
        if (sp != null) killProot(sp);
        List<Svc> rev = new ArrayList<>(svcs);
        Collections.reverse(rev);
        for (Svc s : rev) s.terminate();
        svcs.clear();
        if (t != null) {
            try {
                t.join(5000);
            } catch (InterruptedException ignored) {
                // pazienza
            }
        }
        set(State.IDLE, "Stopped");
    }

    /**
     * SIGABRT fa terminare proot insieme a tutti i suoi figli (SIGTERM viene ignorato da proot; SIGQUIT sarebbe
     * uguale, ma nei processi lanciati da un'app Android è bloccato nella maschera dei segnali ereditata).
     */
    private static void killProot(Process p) {
        int pid = Util.pidOf(p);
        if (pid > 0) Util.signal(pid, Util.SIGABRT);
        p.destroy();
    }

    private void runAll(Cfg c) {
        try {
            Util.mkdirs(c.home, c.appRoot, c.logs, c.config, c.run, c.pgdata, c.valkey, c.library);
            Util.rotate(c.setupLog(), 4 << 20);
            log(c, "=== start " + new java.util.Date() + " ===");
            ensureSetup(c);
            if (stopping) return;
            startServices(c);
            monitor(c);
        } catch (InterruptedException e) {
            // arresto richiesto
        } catch (Throwable t) {
            if (stopping) return;
            log(c, "ERROR: " + Log.getStackTraceString(t));
            for (Svc s : svcs) s.terminate();
            svcs.clear();
            set(State.ERROR, "Error: " + t.getMessage());
        }
    }

    // ------------------------------------------------------------------ installazione

    static boolean installed(Cfg c) {
        return new File(c.rootfs, ".extracted-ok").exists()
            && new File(c.rootfs, "etc/immich-guest-ready").exists()
            && new File(c.pgdata, ".initdb-ok").exists();
    }

    private void ensureSetup(Cfg c) throws Exception {
        checkNative(c);

        // 1. sistema Debian
        File marker = new File(c.rootfs, ".extracted-ok");
        if (!marker.exists()) {
            set(State.INSTALLING, "Extracting the Debian system…");
            extractAsset(c, "rootfs.tar.gz", c.rootfs, 1, "Extracting the Debian system");
            Util.touch(marker);
        }
        if (stopping) throw new InterruptedException();

        // 2. pacchetto Immich (server, interfaccia web, Node.js)
        installPack(c);
        if (stopping) throw new InterruptedException();

        // 3. programmi dentro Debian (PostgreSQL, Valkey, ffmpeg...); si rifà anche quando l'APK porta una
        //    preparazione più recente di quella installata (GUEST_LEVEL in guest-setup.sh) o quando un pacchetto
        //    Immich nuovo porta .deb diversi da quelli installati (ffmpeg, VectorChord: riga DEBS= del marcatore)
        prepareRootfs(c);
        smokeTest(c); // meglio scoprire subito se proot non funziona, prima di 30 minuti di apt
        String pgm = pgMajor(c);
        File ready = new File(c.rootfs, "etc/immich-guest-ready");
        String script = Assets.text(c.ctx, "guest/guest-setup.sh");
        boolean fresh = !ready.exists();
        String installed = fresh ? "" : Util.read(ready);
        boolean newer = !fresh && guestLevel(installed) < guestLevel(script);
        String debs = packDebs(c);
        boolean debsChanged = !fresh && !debs.equals(guestDebs(installed));
        if (debsChanged && !newer) {
            String before = guestDebs(installed);
            log(c, "the Immich package brings different .debs (before: " + (before.isEmpty() ? "not recorded" : before)
                + "; now: " + debs + "): redoing the Debian setup");
        }
        if (fresh || newer || debsChanged) {
            set(State.INSTALLING, fresh ? "Installing PostgreSQL, Valkey and ffmpeg (10-30 minutes, needs Internet)…"
                : "Updating the programs of the Debian system (a few minutes, needs Internet)…");
            Util.write(new File(c.config, "guest-setup.sh"), script);
            Map<String, String> env = new LinkedHashMap<>();
            env.put("PG_MAJOR", pgm);
            int rc = runGuest(c, "root", binds(c), env, Arrays.asList("/bin/bash", "/config/guest-setup.sh", "setup"), null);
            if (rc != 0 && fresh) throw new IOException("installing the programs failed (code " + rc + "): see the log");
            // un aggiornamento fallito (per esempio senza rete) non deve lasciare fermo un server che funzionava
            if (rc != 0) log(c, "updating the programs failed (code " + rc + "): starting with the previous ones, retrying at the next start");
        }
        if (stopping) throw new InterruptedException();

        // 4. cluster PostgreSQL (il marcatore .initdb-ok si scrive solo a fine lavoro: un initdb interrotto si rifà da capo)
        if (!new File(c.pgdata, ".initdb-ok").exists()) {
            set(State.INSTALLING, "Creating the database…");
            File[] partial = c.pgdata.listFiles();
            if (partial != null && partial.length > 0) {
                log(c, "incomplete PostgreSQL cluster: deleting it and starting over");
                for (File f : partial) Util.deleteRecursive(f);
            }
            Map<String, String> env = new LinkedHashMap<>();
            env.put("LANG", "en_US.UTF-8");
            int rc = runGuest(c, "postgres", binds(c, "pg"), env, Configs.initdb(pgm), null);
            if (rc != 0) throw new IOException("initdb failed (code " + rc + ")");
            // le nostre impostazioni stanno in un file a parte, incluso dal principale
            File conf = new File(c.pgdata, "postgresql.conf");
            try (FileWriter w = new FileWriter(conf, true)) {
                w.write(Configs.POSTGRES_INCLUDE);
            }
            Util.touch(new File(c.pgdata, ".initdb-ok"));
        }
        if (stopping) throw new InterruptedException();

        // 5. originali nella memoria condivisa, se richiesto (a server fermo: vedi DcimMode)
        DcimMode.prepare(this, c);
    }

    /** GUEST_LEVEL=n in guest-setup.sh o in etc/immich-guest-ready (le installazioni senza la riga sono al livello 1) */
    static int guestLevel(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)^GUEST_LEVEL=(\\d+)\\s*$").matcher(text);
        return m.find() ? Integer.parseInt(m.group(1)) : 1;
    }

    /** riga DEBS= di etc/immich-guest-ready: i .deb installati ("" per le preparazioni che non la scrivevano) */
    static String guestDebs(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)^DEBS=(.*)$").matcher(text);
        return m.find() ? m.group(1).trim() : "";
    }

    /** i .deb del pacchetto Immich installato, in ordine e separati da uno spazio: lo stesso formato della riga DEBS= */
    static String packDebs(Cfg c) {
        String[] names = new File(c.pack(), "deb").list();
        List<String> debs = new ArrayList<>();
        if (names != null) {
            for (String n : names) {
                if (n.endsWith(".deb")) debs.add(n);
            }
        }
        Collections.sort(debs);
        StringBuilder sb = new StringBuilder();
        for (String d : debs) sb.append(sb.length() == 0 ? "" : " ").append(d);
        return sb.toString();
    }

    private void checkNative(Cfg c) throws IOException {
        for (String n : new String[]{"libproot.so", "libproot-loader.so", "libtalloc.so", "libandroid-shmem.so"}) {
            File f = new File(c.nativeDir(), n);
            if (!f.isFile()) {
                throw new IOException("missing " + n + " in " + c.nativeDir() + ": is this APK for an arm64 device?");
            }
        }
    }

    private void extractAsset(Cfg c, String name, File dest, int strip, final String label) throws IOException {
        final long total = Assets.size(c.ctx, name);
        Util.deleteRecursive(dest);
        Util.mkdirs(dest);
        try (InputStream in = Assets.open(c.ctx, name)) {
            Tar.Result r = Tar.extract(in, dest, strip, Util.ANDROID_OPS, new Tar.Progress() {
                private int last = -1;

                @Override
                public void bytes(long done) {
                    int pct = total > 0 ? (int) Math.min(99, done * 100 / total) : -1;
                    if (pct != last) {
                        last = pct;
                        setProgress(pct, label + (pct >= 0 ? " " + pct + "%" : "…"));
                    }
                }
            });
            log(c, label + ": " + r);
        }
    }

    private static String packId(Cfg c) {
        File ext = Assets.external(c.ctx, "immich-pack.tar.gz");
        long mod = ext != null && ext.isFile() ? ext.lastModified() : 0;
        return Assets.size(c.ctx, "immich-pack.tar.gz") + ":" + mod;
    }

    private void installPack(Cfg c) throws IOException {
        String id = packId(c);
        File marker = new File(c.pack(), ".extracted-ok");
        if (marker.exists()) {
            // già installato: si rifà solo se l'APK (o adb push) porta un pacchetto diverso
            boolean offered = Assets.exists(c.ctx, "immich-pack.tar.gz");
            if (!offered || id.equals(c.prefs.getString("pack_id", ""))) return;
        }
        set(State.INSTALLING, "Extracting Immich…");
        File tmp = new File(c.appRoot, "pack.new");
        extractAsset(c, "immich-pack.tar.gz", tmp, 1, "Extracting Immich");
        Util.deleteRecursive(c.pack());
        if (!tmp.renameTo(c.pack())) throw new IOException("cannot activate the new Immich package");
        Util.touch(marker);
        c.prefs.edit().putString("pack_id", id).apply();
    }

    private Map<String, String> packInfo(Cfg c) {
        Map<String, String> m = new HashMap<>();
        try {
            for (String l : Util.read(new File(c.pack(), "VERSION")).split("\n")) {
                int i = l.indexOf('=');
                if (i > 0) m.put(l.substring(0, i).trim(), l.substring(i + 1).trim());
            }
        } catch (IOException ignored) {
            // pacchetto non ancora estratto
        }
        return m;
    }

    private String pgMajor(Cfg c) {
        String v = packInfo(c).get("pg_major");
        return v == null ? "17" : v;
    }

    /** resolv.conf, hosts, punti di mount e /tmp dentro la userland */
    private void prepareRootfs(Cfg c) throws IOException {
        // cartella per le chiavi di memoria condivisa SysV: il percorso è scritto dentro libandroid-shmem
        // (vedi apk/build.sh) e deve esistere, altrimenti PostgreSQL si blocca
        Util.mkdirs(new File(c.ctx.getApplicationInfo().dataDir, "t"));
        for (String d : new String[]{"run/immich", "logs", "config", "opt/immich", "pgdata", "valkey", "data", "tmp", "var/tmp"}) {
            Util.mkdirs(new File(c.rootfs, d));
        }
        for (String d : new String[]{"tmp", "var/tmp"}) {
            try {
                Os.chmod(new File(c.rootfs, d).getPath(), 01777);
            } catch (android.system.ErrnoException ignored) {
                // non fatale
            }
        }
        StringBuilder dns = new StringBuilder("nameserver 8.8.8.8\nnameserver 8.8.4.4\n");
        for (String s : systemDns(c)) dns.append("nameserver ").append(s).append('\n');
        File resolv = new File(c.rootfs, "etc/resolv.conf");
        Files.deleteIfExists(resolv.toPath());
        Util.write(resolv, dns.toString());
        File hosts = new File(c.rootfs, "etc/hosts");
        Files.deleteIfExists(hosts.toPath());
        Util.write(hosts, "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n");
    }

    private List<String> systemDns(Cfg c) {
        List<String> out = new ArrayList<>();
        try {
            ConnectivityManager cm = (ConnectivityManager) c.ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            if (n != null) {
                LinkProperties lp = cm.getLinkProperties(n);
                if (lp != null) {
                    for (InetAddress a : lp.getDnsServers()) {
                        if (a instanceof Inet4Address) out.add(a.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {
            // useremo solo i DNS pubblici
        }
        return out;
    }

    // ------------------------------------------------------------------ servizi

    private Map<File, String> binds(Cfg c, String... extra) {
        Map<File, String> m = new LinkedHashMap<>();
        m.put(c.run, "/run/immich");
        m.put(c.logs, "/logs");
        m.put(c.config, "/config");
        m.put(c.pack(), "/opt/immich");
        for (String e : extra) {
            if (e.equals("pg")) m.put(c.pgdata, "/pgdata");
            else if (e.equals("valkey")) m.put(c.valkey, "/valkey");
            else if (e.equals("lib")) {
                m.put(c.library, "/data");
                if (c.dcimActive()) {
                    // originali nella memoria condivisa (DcimMode): dentro /data, proot fa vincere il collegamento più lungo
                    m.put(c.dcimUpload(), "/data/upload");
                    m.put(c.dcimLibrary(), "/data/library");
                }
            }
        }
        return m;
    }

    private void writeConfigs(Cfg c) throws IOException {
        Util.writeIfChanged(new File(c.config, "postgres-immich.conf"), Configs.postgres(c.pgSharedBuffers()));
        Util.writeIfChanged(new File(c.config, "valkey.conf"), Configs.valkey());
        Util.writeIfChanged(new File(c.config, "server.env"),
            Configs.serverEnv(c.port(), c.dbPassword(), c.tz(), c.threads(), c.nodeHeapMb()));
    }

    private void startServices(Cfg c) throws Exception {
        String pgm = pgMajor(c);
        prepareRootfs(c);
        writeConfigs(c);

        // PostgreSQL
        set(State.STARTING, "Starting PostgreSQL…");
        File pidfile = new File(c.pgdata, "postmaster.pid");
        cleanStalePid(pidfile, "postgres");
        Map<String, String> pgEnv = new LinkedHashMap<>();
        pgEnv.put("LANG", "en_US.UTF-8");
        Svc pg = new Svc(c, "postgres", "postgres", binds(c, "pg"), pgEnv, Util.SIGINT, pidfile, "postgres", 60_000,
            Configs.postgresRun(pgm));
        launch(pg);
        waitTcp(5432, 180_000, "PostgreSQL", pg);
        ensureDbRole(c, pgm, pg);

        // Valkey
        if (stopping) throw new InterruptedException();
        set(State.STARTING, "Starting Valkey…");
        File vpid = new File(c.run, "valkey.pid");
        cleanStalePid(vpid, "valkey");
        Svc vk = new Svc(c, "valkey", "valkey", binds(c, "valkey"), new LinkedHashMap<String, String>(), Util.SIGTERM,
            vpid, "valkey", 15_000, Configs.valkeyRun());
        launch(vk);
        waitTcp(6379, 60_000, "Valkey", vk);

        // Immich
        if (stopping) throw new InterruptedException();
        // originali in DCIM: il modello di archiviazione segue l'interruttore (Immich legge la configurazione all'avvio)
        if (c.dcimActive()) applyStorageTemplate(c, pgm);
        set(State.STARTING, "Starting Immich (the first time takes a few minutes)…");
        Svc im = startImmich(c);
        waitPing(c, im);

        // Solo la prima volta: niente machine learning (troppo pesante, e il servizio non esiste su questo telefono)
        File mlMarker = new File(c.pgdata, ".ml-off-done"); // nella cartella del DB: se il DB si azzera, si rifà
        if (!mlMarker.exists()) {
            set(State.STARTING, "Turning off machine learning…");
            // database appena creato da Immich: prima dell'avvio la tabella della configurazione non c'era
            if (c.dcimActive()) applyStorageTemplate(c, pgm);
            if (runGuestTimed(c, "postgres", binds(c, "pg"), new LinkedHashMap<String, String>(),
                Configs.psql(pgm, "immich", Configs.ML_OFF_SQL), null, 60_000, true) == 0) {
                Util.touch(mlMarker);
                log(c, "machine learning turned off: restarting Immich to apply it");
                svcs.remove(im);
                im.terminate();
                im = startImmich(c);
                waitPing(c, im);
            }
        }
        set(State.RUNNING, "Running");
        startExporter(c);
    }

    /** Ogni 5 minuti (se attivo) o su richiesta copia le foto nuove nella galleria, vedi Exporter. */
    private void startExporter(final Cfg c) {
        Thread old = exportThread;
        if (old != null && old.isAlive()) return;
        exportThread = new Thread(new Runnable() {
            @Override
            public void run() {
                long last = 0;
                long lastPrune = 0;
                while (!stopping) {
                    long now = System.currentTimeMillis();
                    // con gli originali in DCIM non si copia nulla, ma si annota quando arrivano là (vedi Exporter)
                    boolean on = c.prefs.getBoolean("export_enabled", false) || c.dcimActive();
                    boolean due = on && now - last > 5 * 60_000L;
                    if ((due || exportNow) && state == State.RUNNING) {
                        exportNow = false;
                        last = now;
                        Exporter.runOnce(Stack.this, c);
                    }
                    // eliminazione da Immich: gira per conto suo, non serve la copia automatica attiva (vedi Pruner)
                    if (now - lastPrune > 5 * 60_000L && state == State.RUNNING) {
                        lastPrune = now;
                        DcimMode.afterStart(c);
                        Pruner.runOnce(c);
                        MissingCleaner.runIfDue(Stack.this, c);
                    }
                    try {
                        Thread.sleep(4000);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        }, "immich-exporter");
        exportThread.setDaemon(true);
        exportThread.start();
    }

    void exportNow() {
        exportNow = true;
    }

    /** Query di sola lettura sul database di Immich: l'output è testo (usa row_to_json per avere una riga JSON per record). */
    String query(Cfg c, String sql) throws Exception {
        StringBuilder out = new StringBuilder();
        int rc = runGuestTimed(c, "postgres", binds(c, "pg"), new LinkedHashMap<String, String>(),
            Configs.psql(pgMajor(c), "immich", sql), out, 120_000, false);
        if (rc != 0) throw new IOException("query failed (code " + rc + "): " + out.toString().trim());
        return out.toString();
    }

    /** modello di archiviazione di Immich come vuole l'interruttore degli originali in DCIM (vedi Configs, DcimMode) */
    private void applyStorageTemplate(Cfg c, String pgm) throws Exception {
        int rc = runGuestTimed(c, "postgres", binds(c, "pg"), new LinkedHashMap<String, String>(),
            Configs.psql(pgm, "immich", Configs.storageTemplateSql(c.dcimWanted())), null, 60_000, true);
        if (rc != 0) log(c, "storage template not set (code " + rc + ")");
    }

    private Svc startImmich(Cfg c) {
        File ipid = new File(c.run, "immich.pid");
        cleanStalePid(ipid, "immich");
        Svc im = new Svc(c, "immich", "immich", binds(c, "lib"), new LinkedHashMap<String, String>(), Util.SIGTERM,
            ipid, "immich", 30_000, Configs.immichRun());
        launch(im);
        return im;
    }

    private void waitPing(Cfg c, Svc im) throws Exception {
        long end = System.currentTimeMillis() + 20 * 60_000L;
        while (!ping(c)) {
            if (stopping) throw new InterruptedException();
            if (im.failed != null) throw new IOException(im.failed);
            if (System.currentTimeMillis() > end) throw new IOException("Immich is not answering after 20 minutes: see the Immich log");
            Thread.sleep(2000);
        }
    }

    private void launch(Svc s) {
        svcs.add(s);
        s.begin();
    }

    private void cleanStalePid(File f, String expect) {
        int pid = Util.readPid(f);
        if (pid > 0 && !Util.pidMatches(pid, expect)) f.delete();
    }

    private void monitor(Cfg c) throws InterruptedException {
        int bad = 0;
        while (!stopping) {
            Thread.sleep(10_000);
            for (Svc s : svcs) {
                if (s.failed != null) {
                    set(State.ERROR, s.failed);
                    return;
                }
            }
            if (ping(c)) {
                bad = 0;
                if (state != State.RUNNING) set(State.RUNNING, "Running");
            } else if (++bad >= 6 && state == State.RUNNING) {
                set(State.STARTING, "Immich is not answering, retrying…");
            }
        }
    }

    private boolean ping(Cfg c) {
        HttpURLConnection h = null;
        try {
            h = (HttpURLConnection) new URL("http://127.0.0.1:" + c.port() + "/api/server/ping").openConnection();
            h.setConnectTimeout(1500);
            h.setReadTimeout(3000);
            return h.getResponseCode() == 200;
        } catch (IOException e) {
            return false;
        } finally {
            if (h != null) h.disconnect();
        }
    }

    private void waitTcp(int port, long timeoutMs, String label, Svc svc) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (stopping) throw new InterruptedException();
            if (svc.failed != null) throw new IOException(svc.failed);
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress("127.0.0.1", port), 500);
                return;
            } catch (IOException ignored) {
                // non ancora
            } finally {
                s.close();
            }
            Thread.sleep(1000);
        }
        throw new IOException(label + " is not answering on port " + port + " after " + timeoutMs / 1000 + " s");
    }

    /** ruolo e database di Immich (idempotente) */
    private void ensureDbRole(Cfg c, String pgm, Svc pg) throws Exception {
        // PostgreSQL accetta connessioni TCP prima di essere davvero pronto: riprova finché risponde
        long end = System.currentTimeMillis() + 120_000;
        while (true) {
            if (stopping) throw new InterruptedException();
            if (pg.failed != null) throw new IOException(pg.failed);
            if (sql(c, pgm, "SELECT 1", null, false) == 0) break;
            if (System.currentTimeMillis() > end) throw new IOException("PostgreSQL is not ready");
            Thread.sleep(2000);
        }
        if (sql(c, pgm, Configs.roleSql(c.dbPassword()), null, true) != 0) {
            throw new IOException("creating the 'immich' role failed (see the log)");
        }
        StringBuilder out = new StringBuilder();
        if (sql(c, pgm, Configs.DB_EXISTS_SQL, out, true) != 0) {
            throw new IOException("reading the databases failed (see the log)");
        }
        if (!out.toString().trim().equals("1") && sql(c, pgm, Configs.DB_CREATE_SQL, null, true) != 0) {
            throw new IOException("creating the database failed (see the log)");
        }
    }

    private int sql(Cfg c, String pgm, String stmt, StringBuilder out, boolean verbose) throws Exception {
        return runGuestTimed(c, "postgres", binds(c, "pg"), new LinkedHashMap<String, String>(),
            Configs.psql(pgm, stmt), out, 60_000, verbose);
    }

    /**
     * proot riesce a eseguire un programma nel sistema Debian? Se si blocca o fallisce, riprova senza
     * l'accelerazione seccomp (su alcuni Android è la causa dei blocchi) e ricorda la scelta.
     */
    private void smokeTest(Cfg c) throws Exception {
        List<String> cmd = Arrays.asList("/usr/bin/true");
        StringBuilder o1 = new StringBuilder();
        int rc = runGuestTimed(c, "root", binds(c), new LinkedHashMap<String, String>(), cmd, o1, 45_000, true);
        if (rc == 0) return;
        String first = o1.toString().trim();
        if (!c.noSeccomp()) {
            log(c, "proot test failed: retrying without seccomp (PROOT_NO_SECCOMP=1)");
            c.setNoSeccomp(true);
            StringBuilder o2 = new StringBuilder();
            rc = runGuestTimed(c, "root", binds(c), new LinkedHashMap<String, String>(), cmd, o2, 45_000, true);
            if (rc == 0) {
                log(c, "ok: it works without seccomp, remembering that (proot will be a bit slower)");
                return;
            }
            c.setNoSeccomp(false);
            first = first + "\n" + o2.toString().trim();
        }
        String why = rc == TIMED_OUT ? "it hangs" : "code " + rc;
        throw new IOException("proot cannot run programs in the Debian system (" + why + "). "
            + (first.isEmpty() ? "" : first.substring(0, Math.min(first.length(), 300))));
    }

    /** Ripara: ferma tutto e cancella solo il sistema Debian e il pacchetto dell'app. Database e foto restano. */
    void repair(final Cfg c, final Runnable done) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                doStop();
                Util.deleteRecursive(c.rootfs);
                Util.deleteRecursive(c.pack());
                Util.deleteRecursive(new File(c.appRoot, "pack.new"));
                c.prefs.edit().remove("pack_id").apply();
                if (done != null) done.run();
            }
        }, "immich-repair").start();
    }

    // ------------------------------------------------------------------ esecuzione nel guest

    private int runGuest(Cfg c, String user, Map<File, String> binds, Map<String, String> env, List<String> argv,
                         StringBuilder capture) throws Exception {
        return runGuest(c, user, binds, env, argv, capture, true);
    }

    /** Esegue un programma nel sistema Debian e attende; l'output va nel log di installazione. */
    private int runGuest(Cfg c, String user, Map<File, String> binds, Map<String, String> env, List<String> argv,
                         StringBuilder capture, boolean verbose) throws Exception {
        ProcessBuilder pb = new ProotCmd(c).builder(user, binds, env, argv);
        pb.redirectErrorStream(true);
        if (verbose) log(c, "$ [" + user + "] " + join(argv));
        Process p = pb.start();
        setupProc = p;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (verbose) appendLine(c.setupLog(), line);
                if (capture != null) capture.append(line).append('\n');
            }
        }
        int rc = p.waitFor();
        setupProc = null;
        if (verbose) log(c, "  -> code " + rc);
        return rc;
    }

    static final int TIMED_OUT = -999;

    /**
     * Come runGuest, ma con un tempo massimo: se proot si blocca lo chiude (SIGABRT) e restituisce
     * TIMED_OUT. L'output passa da un file, così un blocco non può bloccare anche noi.
     */
    private int runGuestTimed(Cfg c, String user, Map<File, String> binds, Map<String, String> env, List<String> argv,
                              StringBuilder capture, long timeoutMs, boolean verbose) throws Exception {
        Util.mkdirs(c.prootTmp);
        File tmp = File.createTempFile("run", ".out", c.prootTmp);
        try {
            ProcessBuilder pb = new ProotCmd(c).builder(user, binds, env, argv);
            pb.redirectErrorStream(true);
            pb.redirectOutput(tmp);
            if (verbose) log(c, "$ [" + user + "] " + join(argv));
            Process p = pb.start();
            boolean finished = p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            int rc;
            if (finished) {
                rc = p.exitValue();
            } else {
                killProot(p);
                p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                rc = TIMED_OUT;
            }
            String out = Util.read(tmp);
            if (capture != null) capture.append(out);
            if (verbose || rc != 0) {
                log(c, "  -> " + (rc == TIMED_OUT ? "stuck (timed out)" : "code " + rc)
                    + (out.trim().isEmpty() ? "" : ": " + out.trim()));
            }
            return rc;
        } finally {
            tmp.delete();
        }
    }

    /** comando leggibile per i log e la diagnosi, senza la password del database (i log si incollano nelle segnalazioni) */
    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) {
            if (sb.length() > 0) sb.append(' ');
            s = s.replaceAll("PASSWORD '[^']*'", "PASSWORD '***'");
            sb.append(s.length() > 160 ? s.substring(0, 160) + "…" : s);
        }
        return sb.toString();
    }

    private void log(Cfg c, String msg) {
        Log.i(Cfg.TAG, msg);
        try {
            Util.mkdirs(c.logs);
            appendLine(c.setupLog(), msg);
        } catch (IOException ignored) {
            // il log è solo di aiuto
        }
    }

    private static void appendLine(File f, String s) throws IOException {
        try (FileWriter w = new FileWriter(f, true)) {
            w.write(s);
            w.write('\n');
        }
    }

    // ------------------------------------------------------------------ un servizio supervisionato

    private final class Svc implements Runnable {
        final Cfg c;
        final String name;
        final String user;
        final Map<File, String> binds;
        final Map<String, String> env;
        final int sig;
        final File pidFile;
        final String pidMatch;
        final long stopMs;
        final List<String> argv;
        final File logFile;
        volatile Process proc;
        volatile boolean stop;
        volatile String failed;
        Thread th;

        Svc(Cfg c, String name, String user, Map<File, String> binds, Map<String, String> env, int sig, File pidFile,
            String pidMatch, long stopMs, List<String> argv) {
            this.c = c;
            this.name = name;
            this.user = user;
            this.binds = binds;
            this.env = env;
            this.sig = sig;
            this.pidFile = pidFile;
            this.pidMatch = pidMatch;
            this.stopMs = stopMs;
            this.argv = argv;
            this.logFile = new File(c.logs, name + ".log");
        }

        void begin() {
            th = new Thread(this, "svc-" + name);
            th.start();
        }

        @Override
        public void run() {
            int backoff = 2;
            int fastFails = 0;
            while (!stop) {
                long t0 = System.currentTimeMillis();
                try {
                    Util.rotate(logFile, 8 << 20);
                    ProcessBuilder pb = new ProotCmd(c).builder(user, binds, env, argv);
                    pb.redirectErrorStream(true);
                    pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
                    say("--- start " + name + " ---");
                    proc = pb.start();
                    int rc = proc.waitFor();
                    if (stop) break;
                    say("--- " + name + " exited with code " + rc + " ---");
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    say("--- error " + name + ": " + e + " ---");
                }
                if (System.currentTimeMillis() - t0 < 30_000) {
                    fastFails++;
                } else {
                    fastFails = 0;
                    backoff = 2;
                }
                if (fastFails >= 6) {
                    failed = name + " stops right after every start: see its log";
                    say(failed);
                    return;
                }
                try {
                    Thread.sleep(backoff * 1000L);
                } catch (InterruptedException e) {
                    break;
                }
                backoff = Math.min(backoff * 2, 60);
            }
        }

        private void say(String s) {
            Log.i(Cfg.TAG, name + ": " + s);
            try {
                appendLine(logFile, s);
            } catch (IOException ignored) {
                // solo log
            }
        }

        /** segnala il servizio (pid vero dentro proot), poi proot stesso se non si ferma */
        void terminate() {
            stop = true;
            int pid = Util.readPid(pidFile);
            boolean valid = pid > 0 && Util.pidMatches(pid, pidMatch);
            if (valid) Util.signal(pid, sig);
            else if (pid > 0) say("pid " + pid + " not recognized as " + pidMatch + " (not signalling it)");
            Thread t = th;
            if (t != null) {
                try {
                    t.join(stopMs);
                } catch (InterruptedException ignored) {
                    // continua
                }
            }
            Process p = proc;
            if (t != null && t.isAlive() && p != null) {
                say("did not stop in time: closing proot");
                killProot(p);
                if (t.isAlive() && valid) { // pidOf() non ha funzionato: proot è il padre del servizio
                    int pp = Util.ppidOf(pid);
                    if (pp > 1) Util.signal(pp, Util.SIGABRT);
                }
                try {
                    t.join(5000);
                } catch (InterruptedException ignored) {
                    // continua
                }
            }
        }
    }

    // ------------------------------------------------------------------ diagnostica

    /** Rapporto di prova: cosa c'è, cosa parte. Da incollare quando qualcosa non va. */
    String diagnose(Cfg c) {
        StringBuilder sb = new StringBuilder();
        sb.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" · Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("ABI: ").append(Arrays.toString(Build.SUPPORTED_ABIS)).append(" · RAM ").append(c.totalMemMb())
            .append(" MB · kernel ").append(System.getProperty("os.version")).append('\n');
        sb.append("Free space: ").append(c.files.getUsableSpace() / (1024 * 1024)).append(" MB\n");
        sb.append("Child process restrictions: ").append(Health.phantomText(c.ctx)).append('\n');
        sb.append("Exempt from battery optimization: ").append(Health.ignoringBattery(c.ctx) ? "yes" : "NO").append('\n');
        sb.append("Addresses: ").append(Util.ipv4()).append("\n\n");

        for (String n : new String[]{"libproot.so", "libproot-loader.so", "libtalloc.so", "libandroid-shmem.so"}) {
            File f = new File(c.nativeDir(), n);
            sb.append(f.isFile() ? "ok      " : "MISSING ").append(n).append(f.isFile() ? " (" + f.length() + " bytes)" : "").append('\n');
        }
        sb.append("Debian extracted: ").append(new File(c.rootfs, ".extracted-ok").exists() ? "yes" : "no").append('\n');
        sb.append("Immich package: ").append(packInfo(c)).append('\n');
        File ready = new File(c.rootfs, "etc/immich-guest-ready");
        String level = "";
        try {
            if (ready.exists()) level = " (level " + guestLevel(Util.read(ready)) + ")";
        } catch (IOException ignored) {
            // solo informazione
        }
        sb.append("Programs installed: ").append(ready.exists() ? "yes" + level : "no").append('\n');
        sb.append("Database created: ").append(new File(c.pgdata, "PG_VERSION").exists() ? "yes" : "no").append("\n\n");

        try {
            Process p = new ProcessBuilder(new File(c.nativeDir(), "libproot.so").getPath(), "--version")
                .redirectErrorStream(true).start();
            sb.append("proot --version:\n").append(readAll(p)).append('\n');
        } catch (Exception e) {
            sb.append("proot does not start: ").append(e).append('\n');
        }

        if (new File(c.rootfs, ".extracted-ok").exists()) {
            try {
                prepareRootfs(c);
                Map<File, String> b = binds(c);
                String[][] tests = {
                    {"uname", "-a"},
                    {"id"},
                    {"ls", "/opt/immich"},
                    {"/opt/immich/node/bin/node", "--version"},
                    // elaborazione media come la fa Immich: miniature (libvips), video (ffmpeg), metadati (exiftool)
                    {"/opt/immich/node/bin/node", "-e", "const s=require('/opt/immich/server/node_modules/sharp');"
                        + "s({create:{width:2000,height:1333,channels:3,background:'#3b4ba8'}}).jpeg().toBuffer()"
                        + ".then(b=>s(b).resize(400).webp().toBuffer())"
                        + ".then(o=>console.log('sharp ok: jpeg 2000x1333 -> webp 400px,',o.length,'bytes'))"},
                    {"ffmpeg", "-v", "error", "-f", "lavfi", "-i", "testsrc=duration=2:size=640x360:rate=24",
                        "-c:v", "libx264", "-preset", "ultrafast", "-f", "null", "-"},
                    // video HDR (10 bit, BT.2020/PQ): Immich li converte con tonemapx, che c'è solo nell'ffmpeg di Jellyfin
                    {"ffmpeg", "-v", "error", "-f", "lavfi", "-i", "testsrc2=duration=1:size=640x360:rate=24", "-vf",
                        "format=yuv420p10le,setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc,"
                            + "tonemapx=tonemap=hable:desat=0:p=bt709:t=bt709:m=bt709:r=pc:peak=100:format=yuv420p",
                        "-f", "null", "-"},
                    {"/opt/immich/node/bin/node", "-e", "require('/opt/immich/server/node_modules/exiftool-vendored')"
                        + ".exiftool.version().then(v=>{console.log('exiftool',v);process.exit(0)})"},
                };
                sb.append("proot without seccomp: ").append(c.noSeccomp() ? "yes" : "no").append('\n');
                for (String[] t : tests) {
                    StringBuilder out = new StringBuilder();
                    int rc = runGuestTimed(c, "root", b, new LinkedHashMap<String, String>(), Arrays.asList(t), out, 25_000, false);
                    sb.append("$ ").append(join(Arrays.asList(t))).append("  ->  ")
                        .append(rc == TIMED_OUT ? "STUCK" : String.valueOf(rc)).append('\n').append(out);
                }
            } catch (Exception e) {
                sb.append("test in the Debian system failed: ").append(e).append('\n');
            }
        }
        try {
            Util.write(new File(c.logs, "diagnostics.txt"), sb.toString()); // per leggerla anche da adb
        } catch (IOException ignored) {
            // solo comodità
        }
        return sb.toString();
    }

    private static String readAll(Process p) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append('\n');
        }
        return sb.toString();
    }
}
