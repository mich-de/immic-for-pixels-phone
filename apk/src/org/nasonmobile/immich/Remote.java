package org.nasonmobile.immich;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Pagina web per configurare il server da un altro dispositivo della stessa rete (PC, tablet, un altro telefono),
 * senza toccare il telefono che fa da server: stato, avvio/arresto/riavvio, le stesse impostazioni dell'app, gli
 * aggiornamenti e i log. Spenta di default (interruttore "Remote configuration" nell'app).
 *
 * Un piccolo server HTTP su una porta sua (2284, accanto ai 2283 di Immich), nel processo dell'app: vive finché vive
 * il processo, cioè di sicuro mentre il server Immich gira (servizio in primo piano). Accesso con una password
 * generata dall'app e mostrata solo sul telefono; dopo il login un cookie di sessione (HttpOnly, SameSite=Strict).
 * Tentativi sbagliati: dopo 5 la pagina si blocca per un tempo che raddoppia (fino a 15 minuti). Le richieste che
 * cambiano qualcosa vogliono anche l'intestazione X-Immich-Panel, che un altro sito non può mandare (CSRF).
 * HTTP in chiaro come Immich stesso: pensata per la rete di casa o una VPN (Tailscale, WireGuard), non per Internet.
 */
final class Remote {
    static final int PORT_DEFAULT = 2284;
    private static final long SESSION_MS = 30L * 24 * 3600 * 1000;
    private static final int MAX_SESSIONS = 20;
    private static final int MAX_BODY = 32 * 1024;
    private static final int MAX_LINE = 8192;
    private static final String COOKIE = "imsid";

    /** cresce a ogni modifica fatta dalla pagina: MainActivity si ricostruisce per mostrare i valori nuovi */
    static volatile long rev;

    private static ServerSocket socket;
    private static ExecutorService pool;
    private static int boundPort;
    private static volatile String lastError = "";

    private static final Map<String, Long> sessions = new HashMap<>();
    private static int failures;
    private static long lockedUntil;

    private Remote() {
    }

    // ------------------------------------------------------------------ impostazioni modificabili

    /** un'impostazione dell'app che la pagina può cambiare: booleana, o intera fra valori ammessi */
    private static final class Opt {
        final String key;
        final boolean bool;
        final boolean defB;
        final int defI;
        final int[] allowed;

        Opt(String key, boolean def) {
            this.key = key;
            this.bool = true;
            this.defB = def;
            this.defI = 0;
            this.allowed = null;
        }

        Opt(String key, int def, int... allowed) {
            this.key = key;
            this.bool = false;
            this.defB = false;
            this.defI = def;
            this.allowed = allowed;
        }
    }

    /** gli stessi valori dei menu e degli interruttori di MainActivity */
    private static final Opt[] OPTS = {
        new Opt("autostart", false),
        new Opt("update_check", true),
        new Opt("export_enabled", false),
        new Opt("export_all_users", false),
        new Opt("export_keep_days", 7, 0, 3, 5, 7, 30),
        new Opt("export_cap_gb", 10, 0, 5, 10, 20, 50),
        new Opt("battery_guard_enabled", false),
        new Opt("battery_guard_high", 80, 60, 70, 80, 90),
        new Opt("battery_guard_low", 30, 20, 30, 40, 50),
        new Opt("prune_days", 0, 0, 2, 3, 5, 7),
        new Opt("prune_emergency", false),
        new Opt("missing_cleanup", false),
        new Opt("proot_no_seccomp", false),
    };

    private static Opt opt(String key) {
        for (Opt o : OPTS) {
            if (o.key.equals(key)) return o;
        }
        return null;
    }

    // ------------------------------------------------------------------ acceso / spento

    static boolean enabled(Cfg c) {
        return c.prefs.getBoolean("remote_enabled", false);
    }

    static int port(Cfg c) {
        return c.prefs.getInt("remote_port", PORT_DEFAULT);
    }

    static String password(Cfg c) {
        String p = c.prefs.getString("remote_password", "");
        if (p.isEmpty()) {
            p = newPassword();
            c.prefs.edit().putString("remote_password", p).apply();
        }
        return p;
    }

    /** password nuova: chi aveva fatto il login deve rifarlo */
    static String regeneratePassword(Cfg c) {
        String p = newPassword();
        c.prefs.edit().putString("remote_password", p).apply();
        synchronized (sessions) {
            sessions.clear();
            failures = 0;
            lockedUntil = 0;
        }
        return p;
    }

    /** 12 caratteri senza quelli che si confondono (0/o, 1/l/i), a gruppi di 4: ~59 bit */
    private static String newPassword() {
        String a = "abcdefghjkmnpqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            if (i > 0 && i % 4 == 0) sb.append('-');
            sb.append(a.charAt(r.nextInt(a.length())));
        }
        return sb.toString();
    }

    /** accende o spegne il server della pagina secondo l'impostazione; da chiamare quando cambia e all'avvio */
    static synchronized void sync(Context ctx) {
        Cfg c = new Cfg(ctx);
        boolean want = enabled(c);
        if (want && socket != null && !socket.isClosed() && boundPort == port(c)) return;
        stopLocked();
        lastError = "";
        if (!want) return;
        final Context app = ctx.getApplicationContext();
        try {
            final ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(port(c)), 16);
            socket = ss;
            boundPort = port(c);
            pool = Executors.newFixedThreadPool(4);
            final ExecutorService p = pool;
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    acceptLoop(app, ss, p);
                }
            }, "immich-remote");
            t.setDaemon(true);
            t.start();
        } catch (IOException e) {
            lastError = "Port " + port(c) + " is not available: " + e.getMessage();
        }
    }

    private static void stopLocked() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // già chiuso
            }
            socket = null;
        }
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
    }

    private static void acceptLoop(final Context ctx, ServerSocket ss, ExecutorService p) {
        while (!ss.isClosed()) {
            final Socket s;
            try {
                s = ss.accept();
            } catch (IOException e) {
                return; // chiuso da stopLocked()
            }
            try {
                s.setSoTimeout(20_000);
                p.execute(new Runnable() {
                    @Override
                    public void run() {
                        handle(ctx, s);
                    }
                });
            } catch (IOException | RejectedExecutionException e) {
                try {
                    s.close();
                } catch (IOException ignored) {
                    // niente da fare
                }
            }
        }
    }

    /** testo per la card dell'app: indirizzi e password, o perché non parte */
    static String describe(Cfg c) {
        if (!enabled(c)) return "Off.";
        if (!lastError.isEmpty()) return lastError;
        StringBuilder sb = new StringBuilder("From a device on the same network, open:");
        List<String> ips = Util.ipv4();
        if (ips.isEmpty()) sb.append("\nhttp://<this phone's IP>:").append(port(c)).append(" (no network right now)");
        for (String ip : ips) sb.append("\nhttp://").append(ip.substring(ip.indexOf(' ') + 1)).append(':').append(port(c));
        sb.append("\nPassword: ").append(password(c));
        return sb.toString();
    }

    // ------------------------------------------------------------------ HTTP

    private static final class Req {
        String method;
        String path;
        String ip;
        Map<String, String> headers = new HashMap<>();
        Map<String, String> query = new HashMap<>();
        Map<String, String> form = new HashMap<>();
    }

    private static final class Resp {
        final int code;
        final String type;
        final byte[] body;
        final List<String> headers = new ArrayList<>();

        Resp(int code, String type, String body) {
            this.code = code;
            this.type = type;
            this.body = body.getBytes(StandardCharsets.UTF_8);
        }

        Resp header(String h) {
            headers.add(h);
            return this;
        }
    }

    private static Resp json(int code, JSONObject j) {
        return new Resp(code, "application/json; charset=utf-8", j.toString());
    }

    private static Resp error(int code, String msg) {
        JSONObject j = new JSONObject();
        try {
            j.put("error", msg);
        } catch (JSONException ignored) {
            // impossibile con una stringa
        }
        return json(code, j);
    }

    private static void handle(Context ctx, Socket s) {
        try (Socket sock = s) {
            InputStream in = new BufferedInputStream(sock.getInputStream());
            Resp r;
            Req q = new Req();
            q.ip = sock.getInetAddress() == null ? "?" : sock.getInetAddress().getHostAddress();
            try {
                r = parse(in, q) ? route(ctx, q) : error(400, "bad request");
            } catch (TooBig e) {
                r = error(413, "request too large");
            } catch (Exception e) {
                r = error(500, String.valueOf(e.getMessage()));
            }
            write(sock.getOutputStream(), r);
        } catch (IOException ignored) {
            // il browser ha chiuso la connessione
        }
    }

    private static final class TooBig extends IOException {
    }

    private static boolean parse(InputStream in, Req q) throws IOException {
        String line = readLine(in);
        if (line == null) return false;
        String[] parts = line.split(" ");
        if (parts.length != 3 || !parts[2].startsWith("HTTP/")) return false;
        q.method = parts[0];
        String target = parts[1];
        int total = 0;
        for (; ; ) {
            String h = readLine(in);
            if (h == null) return false;
            if (h.isEmpty()) break;
            total += h.length();
            if (total > 4 * MAX_LINE) throw new TooBig();
            int i = h.indexOf(':');
            if (i > 0) q.headers.put(h.substring(0, i).trim().toLowerCase(Locale.US), h.substring(i + 1).trim());
        }
        int qi = target.indexOf('?');
        q.path = qi >= 0 ? target.substring(0, qi) : target;
        if (qi >= 0) parseForm(target.substring(qi + 1), q.query);
        String cl = q.headers.get("content-length");
        if (cl != null) {
            int n;
            try {
                n = Integer.parseInt(cl.trim());
            } catch (NumberFormatException e) {
                return false;
            }
            if (n < 0) return false;
            if (n > MAX_BODY) throw new TooBig();
            byte[] b = new byte[n];
            int off = 0;
            while (off < n) {
                int k = in.read(b, off, n - off);
                if (k < 0) return false;
                off += k;
            }
            parseForm(new String(b, StandardCharsets.UTF_8), q.form);
        }
        return true;
    }

    /** una riga terminata da \n (il \r si toglie); null se lo stream finisce prima */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                String s = new String(b.toByteArray(), StandardCharsets.UTF_8);
                return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
            }
            if (b.size() >= MAX_LINE) throw new TooBig();
            b.write(c);
        }
        return null;
    }

    /** application/x-www-form-urlencoded (anche la query string) */
    static void parseForm(String s, Map<String, String> out) {
        if (s == null || s.isEmpty()) return;
        for (String pair : s.split("&")) {
            if (pair.isEmpty()) continue;
            int i = pair.indexOf('=');
            try {
                String k = URLDecoder.decode(i >= 0 ? pair.substring(0, i) : pair, "UTF-8");
                String v = i >= 0 ? URLDecoder.decode(pair.substring(i + 1), "UTF-8") : "";
                out.put(k, v);
            } catch (IllegalArgumentException | java.io.UnsupportedEncodingException ignored) {
                // coppia malformata: si salta
            }
        }
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 409: return "Conflict";
            case 413: return "Payload Too Large";
            case 429: return "Too Many Requests";
            default: return "Error";
        }
    }

    private static void write(OutputStream os, Resp r) throws IOException {
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(r.code).append(' ').append(reason(r.code)).append("\r\n");
        h.append("Content-Type: ").append(r.type).append("\r\n");
        h.append("Content-Length: ").append(r.body.length).append("\r\n");
        h.append("Cache-Control: no-store\r\n");
        h.append("X-Content-Type-Options: nosniff\r\n");
        h.append("X-Frame-Options: DENY\r\n");
        h.append("Referrer-Policy: no-referrer\r\n");
        h.append("Content-Security-Policy: default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; "
            + "connect-src 'self'; img-src 'self' data:; form-action 'none'; frame-ancestors 'none'; base-uri 'none'\r\n");
        for (String x : r.headers) h.append(x).append("\r\n");
        h.append("Connection: close\r\n\r\n");
        os.write(h.toString().getBytes(StandardCharsets.UTF_8));
        os.write(r.body);
        os.flush();
    }

    // ------------------------------------------------------------------ percorsi

    private static Resp route(Context ctx, Req q) throws Exception {
        Cfg c = new Cfg(ctx);
        boolean get = "GET".equals(q.method) || "HEAD".equals(q.method);
        boolean post = "POST".equals(q.method);
        if (q.path.equals("/") || q.path.equals("/index.html")) {
            if (!get) return error(405, "method not allowed");
            return new Resp(200, "text/html; charset=utf-8", RemotePage.HTML);
        }
        if (q.path.equals("/favicon.ico")) return new Resp(204, "text/plain", "");
        if (!q.path.startsWith("/api/")) return error(404, "not found");
        if (!get && !post) return error(405, "method not allowed");
        // un altro sito può far partire un POST verso questo indirizzo ma non aggiungere intestazioni sue (CSRF)
        if (post && !"1".equals(q.headers.get("x-immich-panel"))) return error(403, "missing X-Immich-Panel header");

        if (q.path.equals("/api/login")) return post ? login(c, q) : error(405, "use POST");
        if (!authed(q)) return error(401, "login");
        switch (q.path) {
            case "/api/state":
                return get ? json(200, state(c)) : error(405, "use GET");
            case "/api/logout":
                if (!post) return error(405, "use POST");
                synchronized (sessions) {
                    sessions.remove(cookie(q));
                }
                return json(200, new JSONObject()).header("Set-Cookie: " + COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict");
            case "/api/settings":
                return post ? settings(c, q.form) : error(405, "use POST");
            case "/api/action":
                return post ? action(c, q.form.get("action")) : error(405, "use POST");
            case "/api/log":
                return get ? log(c, q.query.get("name")) : error(405, "use GET");
            default:
                return error(404, "not found");
        }
    }

    private static String cookie(Req q) {
        String h = q.headers.get("cookie");
        if (h == null) return "";
        for (String part : h.split(";")) {
            String p = part.trim();
            if (p.startsWith(COOKIE + "=")) return p.substring(COOKIE.length() + 1);
        }
        return "";
    }

    private static boolean authed(Req q) {
        String t = cookie(q);
        if (t.isEmpty()) return false;
        synchronized (sessions) {
            Long exp = sessions.get(t);
            if (exp == null) return false;
            if (exp < System.currentTimeMillis()) {
                sessions.remove(t);
                return false;
            }
            return true;
        }
    }

    private static Resp login(Cfg c, Req q) {
        long now = System.currentTimeMillis();
        String given = q.form.get("password");
        given = given == null ? "" : given.trim().toLowerCase(Locale.US);
        byte[] want = password(c).getBytes(StandardCharsets.UTF_8);
        synchronized (sessions) {
            if (now < lockedUntil) {
                return error(429, "Too many wrong passwords: try again in " + ((lockedUntil - now) / 1000 + 1) + " s");
            }
            if (!MessageDigest.isEqual(want, given.getBytes(StandardCharsets.UTF_8))) {
                failures++;
                if (failures >= 5) lockedUntil = now + Math.min(15 * 60_000L, 30_000L << Math.min(5, failures - 5));
                Stack.I.note(c, "remote page: wrong password from " + q.ip);
                return error(401, "Wrong password");
            }
            failures = 0;
            Iterator<Map.Entry<String, Long>> it = sessions.entrySet().iterator();
            while (it.hasNext()) {
                if (it.next().getValue() < now) it.remove();
            }
            if (sessions.size() >= MAX_SESSIONS) {
                String oldest = null;
                long min = Long.MAX_VALUE;
                for (Map.Entry<String, Long> e : sessions.entrySet()) {
                    if (e.getValue() < min) {
                        min = e.getValue();
                        oldest = e.getKey();
                    }
                }
                sessions.remove(oldest);
            }
            byte[] b = new byte[32];
            new SecureRandom().nextBytes(b);
            StringBuilder t = new StringBuilder();
            for (byte x : b) t.append(String.format(Locale.US, "%02x", x));
            sessions.put(t.toString(), now + SESSION_MS);
            Stack.I.note(c, "remote page: login from " + q.ip);
            return json(200, new JSONObject()).header("Set-Cookie: " + COOKIE + "=" + t + "; Path=/; Max-Age="
                + (SESSION_MS / 1000) + "; HttpOnly; SameSite=Strict");
        }
    }

    // ------------------------------------------------------------------ stato

    /** le funzioni di stato delle altre classi non devono mai rompere la pagina */
    private interface Text {
        String get() throws Exception;
    }

    private static String safe(Text t) {
        try {
            String s = t.get();
            return s == null ? "" : s;
        } catch (Exception e) {
            return "(" + e.getMessage() + ")";
        }
    }

    private static JSONObject state(final Cfg c) throws JSONException {
        JSONObject j = new JSONObject();
        Stack.State s = Stack.I.state();
        j.put("state", s.name());
        j.put("detail", Stack.I.detail());
        j.put("progress", Stack.I.progress());
        JSONArray urls = new JSONArray();
        for (String ip : Util.ipv4()) urls.put("http://" + ip.substring(ip.indexOf(' ') + 1) + ":" + c.port());
        j.put("urls", urls);
        j.put("version", safe(new Text() {
            public String get() {
                return Updater.current(c);
            }
        }));
        j.put("storage", safe(new Text() {
            public String get() {
                return Health.spaceText(c.files);
            }
        }));
        j.put("storage_level", Health.spaceLevel(c.files));
        j.put("battery", safe(new Text() {
            public String get() {
                return BatteryGuard.statusText(c.ctx);
            }
        }));

        JSONObject u = new JSONObject();
        u.put("status", Updater.status());
        Updater.Release r = Updater.available();
        if (r != null) {
            u.put("available", r.tag);
            u.put("title", r.title());
            u.put("size_mb", r.size >> 20);
        }
        u.put("busy", Updater.busy());
        j.put("update", u);

        JSONObject st = new JSONObject();
        st.put("export", safe(new Text() {
            public String get() {
                return Exporter.status();
            }
        }));
        st.put("prune", safe(new Text() {
            public String get() {
                return Pruner.status();
            }
        }));
        st.put("prune_days", safe(new Text() {
            public String get() {
                return Pruner.effectiveDaysNote(c);
            }
        }));
        st.put("missing", safe(new Text() {
            public String get() {
                return MissingCleaner.status(c);
            }
        }));
        st.put("dcim", safe(new Text() {
            public String get() {
                return DcimMode.status(c);
            }
        }));
        st.put("backup", safe(new Text() {
            public String get() {
                return Backup.status();
            }
        }));
        j.put("status", st);

        JSONObject set = new JSONObject();
        for (Opt o : OPTS) {
            if (o.bool) set.put(o.key, c.prefs.getBoolean(o.key, o.defB));
            else set.put(o.key, c.prefs.getInt(o.key, o.defI));
        }
        set.put("dcim_originals", c.dcimWanted());
        set.put("prune_api_key_set", !ImmichApi.key(c).isEmpty());
        j.put("settings", set);
        return j;
    }

    // ------------------------------------------------------------------ modifiche

    private static Resp settings(Cfg c, Map<String, String> form) throws JSONException {
        JSONArray notes = new JSONArray();
        // prima si controlla tutto, poi si scrive: o tutte le modifiche o nessuna
        Map<String, Object> apply = new LinkedHashMap<>();
        Boolean dcim = null;
        String apiKey = null;
        for (Map.Entry<String, String> e : form.entrySet()) {
            String k = e.getKey(), v = e.getValue().trim();
            if (k.equals("dcim_originals")) {
                dcim = parseBool(v);
                if (dcim == null) return error(400, k + ": expected true or false");
                continue;
            }
            if (k.equals("prune_api_key")) {
                apiKey = v;
                continue;
            }
            Opt o = opt(k);
            if (o == null) return error(400, "unknown setting: " + k);
            if (o.bool) {
                Boolean b = parseBool(v);
                if (b == null) return error(400, k + ": expected true or false");
                apply.put(k, b);
            } else {
                int n;
                try {
                    n = Integer.parseInt(v);
                } catch (NumberFormatException ex) {
                    return error(400, k + ": expected a number");
                }
                boolean ok = false;
                for (int a : o.allowed) ok |= a == n;
                if (!ok) return error(400, k + ": value not allowed");
                apply.put(k, n);
            }
        }
        if (Boolean.TRUE.equals(dcim) && !c.dcimWanted()
            && c.ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            return error(409, "Originals in DCIM need the Storage permission: turn it on once in the app on the phone");
        }

        SharedPreferences.Editor ed = c.prefs.edit();
        for (Map.Entry<String, Object> e : apply.entrySet()) {
            if (e.getValue() instanceof Boolean) ed.putBoolean(e.getKey(), (Boolean) e.getValue());
            else ed.putInt(e.getKey(), (Integer) e.getValue());
        }
        ed.apply();
        if (apply.containsKey("proot_no_seccomp")) notes.put("proot without seccomp applies at the next server start.");
        if (apiKey != null) {
            c.prefs.edit().putString("prune_api_key", apiKey).apply();
            notes.put(apiKey.isEmpty() ? "API key removed." : "API key saved. Test: " + Pruner.testApiKey(c, apiKey));
        }
        if (dcim != null && dcim != c.dcimWanted()) {
            DcimMode.onSwitch(c, dcim);
            Stack.State s = Stack.I.state();
            if (s == Stack.State.IDLE || s == Stack.State.ERROR) {
                notes.put("Originals in DCIM: applies at the next server start.");
            } else {
                restart(c.ctx);
                notes.put(dcim ? "Restarting the server and moving the originals to DCIM (a few minutes)." : "Restarting the server.");
            }
        }
        rev++;
        JSONObject j = new JSONObject();
        j.put("notes", notes);
        return json(200, j);
    }

    private static Boolean parseBool(String v) {
        if (v.equals("true") || v.equals("1") || v.equals("on")) return Boolean.TRUE;
        if (v.equals("false") || v.equals("0") || v.equals("off")) return Boolean.FALSE;
        return null;
    }

    private static Resp action(final Cfg c, String a) throws JSONException {
        if (a == null) a = "";
        final Context ctx = c.ctx;
        Stack.State s = Stack.I.state();
        String msg;
        switch (a) {
            case "start":
                if (s != Stack.State.IDLE && s != Stack.State.ERROR) return error(409, "The server is already " + s.name().toLowerCase(Locale.US));
                ctx.startForegroundService(new Intent(ctx, ServerService.class).setAction(ServerService.ACTION_START));
                msg = "Starting.";
                break;
            case "stop":
                if (s == Stack.State.IDLE) return error(409, "The server is already stopped");
                stop(ctx);
                msg = "Stopping.";
                break;
            case "restart":
                restart(ctx);
                msg = "Restarting: a minute or two.";
                break;
            case "update_check":
                msg = Updater.checkNow(c, false);
                break;
            case "update_install":
                if (Updater.busy()) return error(409, "An update is already downloading");
                if (Updater.available() == null) Updater.checkNow(c, false);
                final Updater.Release r = Updater.available();
                if (r == null) return error(409, Updater.status());
                if (!ctx.getPackageManager().canRequestPackageInstalls()) {
                    return error(409, "Allow \"Install unknown apps\" for Immich Server on the phone once (Settings → Apps)");
                }
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        Updater.downloadAndInstall(ctx, r);
                    }
                }, "immich-update").start();
                msg = "Downloading " + r.tag + ". When it's done, the phone shows a notification: tap it and confirm "
                    + "Android's \"Update\" window there (Android never installs an app without a tap on the phone).";
                break;
            case "missing_cleanup_now":
                if (s != Stack.State.RUNNING) return error(409, "Start the server first");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        MissingCleaner.runNow(Stack.I, c);
                    }
                }, "immich-missing").start();
                msg = "Cleanup started: the result appears below in a minute.";
                break;
            default:
                return error(400, "unknown action: " + a);
        }
        JSONObject j = new JSONObject();
        j.put("message", msg);
        return json(200, j);
    }

    /**
     * Come lo Stop della notifica. Con il server acceso l'app ha un servizio in primo piano e può parlare al suo
     * servizio; se Android rifiuta (app in background) si ferma lo Stack direttamente, come farebbe il servizio.
     */
    private static void stop(Context ctx) {
        try {
            ctx.startService(new Intent(ctx, ServerService.class).setAction(ServerService.ACTION_STOP));
        } catch (IllegalStateException e) {
            new Cfg(ctx).setShouldRun(false);
            Stack.I.stop(null);
        }
    }

    /** ferma e, appena è fermo del tutto, riavvia (come MainActivity.restartServer, ma da un thread) */
    static void restart(final Context ctx) {
        Stack.State s0 = Stack.I.state();
        if (s0 == Stack.State.IDLE || s0 == Stack.State.ERROR) {
            ctx.startForegroundService(new Intent(ctx, ServerService.class).setAction(ServerService.ACTION_START));
            return;
        }
        stop(ctx);
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 180; i++) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    Stack.State s = Stack.I.state();
                    if (s == Stack.State.IDLE || s == Stack.State.ERROR) {
                        ctx.startForegroundService(new Intent(ctx, ServerService.class).setAction(ServerService.ACTION_START));
                        return;
                    }
                }
            }
        }, "immich-remote-restart").start();
    }

    private static Resp log(Cfg c, String name) {
        String[] ok = {"setup", "postgres", "valkey", "immich"};
        for (String n : ok) {
            if (n.equals(name)) return new Resp(200, "text/plain; charset=utf-8", Util.tail(new File(c.logs, n + ".log"), 40_000));
        }
        return error(400, "unknown log");
    }
}
