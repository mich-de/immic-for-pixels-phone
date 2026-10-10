package org.nasonmobile.immich;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Aggiornamenti dell'app: una volta al giorno chiede a GitHub l'ultimo rilascio (lo pubblica da solo il workflow
 * .github/workflows/immich.yml a ogni versione nuova di Immich) e, se è più nuovo di quello installato, manda una
 * notifica. Su richiesta scarica l'APK direttamente in una sessione dell'installatore di Android (niente file
 * temporaneo da 300 MB), ne controlla lo sha256 pubblicato da GitHub e lo passa ad Android, che mostra la sua finestra
 * "Aggiorna": l'unico tocco richiesto. Android accetta solo un APK firmato con la stessa chiave di quello installato.
 * Niente installazione silenziosa, di proposito: un server che si aggiorna (e si ferma) senza che nessuno guardi è
 * peggio di un avviso.
 */
final class Updater {
    static final String REPO = "mich-de/immic-for-pixels-phone";
    private static final String CHANNEL = "updates";
    private static final int NID = 3;
    private static final long HOUR_MS = 3_600_000L;
    private static final long DAY_MS = 24 * HOUR_MS;
    private static final String UA = "immich-server-android";

    /** l'ultimo rilascio su GitHub */
    static final class Release {
        String tag;          // "v3.3.1": la versione di Immich dentro l'APK
        String apkUrl;
        long size;
        String sha256;       // GitHub lo pubblica per ogni file di un rilascio
        long uploadedAt;
        /** stessa versione di Immich ma un altro APK, caricato dopo l'installazione di questo: rilascio rifatto */
        boolean rebuild;

        String title() {
            return rebuild ? "Update Immich Server (new build of " + tag + ")" : "Update to Immich " + tag;
        }
    }

    private static volatile String status = "";
    private static volatile Release available;
    private static volatile boolean busy;
    private static volatile Intent pendingConfirm;
    /** solo per provare il flusso da adb (update_pretend_installed): finge installata una versione più vecchia */
    static volatile String pretendInstalled = "";

    private Updater() {
    }

    /** esito dell'ultimo controllo o del download in corso ("" se in questo processo non c'è ancora stato) */
    static String status() {
        return status;
    }

    /** l'aggiornamento trovato dall'ultimo controllo, o null */
    static Release available() {
        return available;
    }

    static boolean busy() {
        return busy;
    }

    /** un solo download alla volta (doppio tocco sul pulsante) */
    private static synchronized boolean claim() {
        if (busy) return false;
        busy = true;
        return true;
    }

    /**
     * Versione di Immich installata: quella del pacchetto estratto (riga immich= di VERSION) o, se più nuova, quella
     * dell'APK (il nome di versione è la versione di Immich dentro): subito dopo un aggiornamento dell'app il pacchetto
     * nuovo si estrae solo al riavvio del server.
     */
    static String current(Cfg c) {
        if (!pretendInstalled.isEmpty()) return pretendInstalled;
        String pack = "";
        try {
            for (String l : Util.read(new File(c.pack(), "VERSION")).split("\n")) {
                if (l.startsWith("immich=")) pack = l.substring(7).trim();
            }
        } catch (IOException ignored) {
            // pacchetto non ancora estratto
        }
        String apk = "";
        try {
            String v = c.ctx.getPackageManager().getPackageInfo(c.ctx.getPackageName(), 0).versionName;
            if (v != null) apk = v;
        } catch (PackageManager.NameNotFoundException ignored) {
            // impossibile: è questo pacchetto
        }
        String v = compare(apk, pack) > 0 ? apk : pack;
        return v.isEmpty() || v.startsWith("v") ? v : "v" + v;
    }

    private static long installedAt(Cfg c) {
        try {
            return c.ctx.getPackageManager().getPackageInfo(c.ctx.getPackageName(), 0).lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    /** sha256 dell'APK installato (Android lo conserva identico): calcolato una volta per installazione, ~2 s */
    private static String installedSha(Cfg c) {
        String key = installedAt(c) + ":";
        String cached = c.prefs.getString("update_apk_sha", "");
        if (cached.startsWith(key)) return cached.substring(key.length());
        try (InputStream in = new FileInputStream(c.ctx.getApplicationInfo().sourceDir)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = in.read(b)) > 0) md.update(b, 0, n);
            String h = hex(md.digest());
            c.prefs.edit().putString("update_apk_sha", key + h).apply();
            return h;
        } catch (Exception e) {
            return "";
        }
    }

    private static final Pattern VER = Pattern.compile("(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?");

    /** confronto numerico di "v3.3.1" e "3.2.4": >0 se a è più nuova */
    static int compare(String a, String b) {
        int[] x = parse(a), y = parse(b);
        for (int i = 0; i < 3; i++) {
            if (x[i] != y[i]) return Integer.compare(x[i], y[i]);
        }
        return 0;
    }

    private static int[] parse(String v) {
        int[] r = new int[3];
        Matcher m = VER.matcher(v == null ? "" : v);
        if (m.find()) {
            for (int i = 0; i < 3; i++) r[i] = m.group(i + 1) == null ? 0 : Integer.parseInt(m.group(i + 1));
        }
        return r;
    }

    private static Release fetchLatest() throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL("https://api.github.com/repos/" + REPO + "/releases/latest").openConnection();
        try {
            h.setRequestProperty("Accept", "application/vnd.github+json");
            h.setRequestProperty("User-Agent", UA);
            h.setConnectTimeout(15_000);
            h.setReadTimeout(30_000);
            int rc = h.getResponseCode();
            if (rc != 200) throw new IOException("GitHub answered " + rc);
            JSONObject j = new JSONObject(read(h.getInputStream()));
            Release r = new Release();
            r.tag = j.getString("tag_name");
            JSONArray assets = j.getJSONArray("assets");
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (!"immich-server.apk".equals(a.optString("name"))) continue;
                r.apkUrl = a.getString("browser_download_url");
                r.size = a.optLong("size", 0);
                String d = a.optString("digest", "");
                if (d.startsWith("sha256:")) r.sha256 = d.substring(7);
                try {
                    r.uploadedAt = Instant.parse(a.optString("updated_at")).toEpochMilli();
                } catch (RuntimeException ignored) {
                    // senza data niente confronto delle ricompilazioni: resta quello delle versioni
                }
            }
            if (r.apkUrl == null) throw new IOException("the latest release has no immich-server.apk");
            return r;
        } finally {
            h.disconnect();
        }
    }

    /** dal ciclo di Stack e all'apertura dell'app: al massimo un controllo al giorno, se l'utente non li ha spenti */
    static void checkIfDue(Cfg c, boolean notify) {
        if (!c.prefs.getBoolean("update_check", true)) return;
        if (System.currentTimeMillis() - c.prefs.getLong("update_checked_at", 0) < DAY_MS) return;
        checkNow(c, notify);
    }

    /** controlla subito; con notify manda la notifica (una sola volta per ogni APK pubblicato) */
    static synchronized String checkNow(Cfg c, boolean notify) {
        long now = System.currentTimeMillis();
        String when = new SimpleDateFormat("MMM d HH:mm", Locale.US).format(new Date(now));
        try {
            Release r = fetchLatest();
            c.prefs.edit().putLong("update_checked_at", now).apply();
            int cmp = compare(r.tag, current(c));
            // stessa versione di Immich: è una ricompilazione se l'APK pubblicato è un altro (sha256) ed è stato caricato
            // dopo l'installazione di questo (così un APK compilato a mano e installato dopo non se lo vede proporre)
            r.rebuild = cmp == 0 && r.sha256 != null && r.uploadedAt > installedAt(c)
                && !r.sha256.equalsIgnoreCase(installedSha(c));
            if (cmp > 0 || r.rebuild) {
                available = r;
                status = (r.rebuild ? "A new build of Immich " + r.tag + " is available (app fixes)" : "Immich " + r.tag
                    + " is available") + ". Checked " + when + ".";
                String key = r.tag + "@" + r.uploadedAt;
                if (notify && !key.equals(c.prefs.getString("update_notified", ""))) {
                    notify(c.ctx, r.rebuild ? "A new build of Immich Server is available" : "Immich " + r.tag + " is available",
                        "Tap to download and install it.", new Intent(c.ctx, MainActivity.class).putExtra("update_install", true), 3);
                    c.prefs.edit().putString("update_notified", key).apply();
                }
            } else {
                available = null;
                status = "Up to date. Checked " + when + ".";
            }
        } catch (Exception e) {
            // niente rete o GitHub non risponde: si riprova fra un'ora invece che domani
            c.prefs.edit().putLong("update_checked_at", now - DAY_MS + HOUR_MS).apply();
            status = "Could not check for updates (" + when + "): " + e.getMessage();
        }
        return status;
    }

    private static void notify(Context ctx, String title, String text, Intent open, int requestCode) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent pi = PendingIntent.getActivity(ctx, requestCode, open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT);
        int small = ctx.getResources().getIdentifier("ic_stat", "drawable", ctx.getPackageName());
        Notification n = new Notification.Builder(ctx, CHANNEL)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(small != 0 ? small : ctx.getApplicationInfo().icon)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build();
        nm.notify(NID, n);
    }

    static void cancelNotification(Context ctx) {
        ((NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NID);
    }

    /**
     * Scarica l'APK del rilascio dentro una sessione dell'installatore (lo stato mostra l'avanzamento), controlla lo
     * sha256 e conferma la sessione: Android risponde a UpdateReceiver con la sua finestra "Aggiorna" da mostrare (vedi
     * onStatus). Da un thread in background. Installata la versione nuova, Android chiude l'app (server compreso, come
     * "adb install -r") e BootReceiver (MY_PACKAGE_REPLACED) fa ripartire il server.
     */
    static void downloadAndInstall(Context ctx, Release r) {
        if (!claim()) return;
        PackageInstaller.Session s = null;
        boolean committed = false;
        try {
            status = "Downloading Immich " + r.tag + "…";
            PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            sp.setAppPackageName(ctx.getPackageName());
            if (r.size > 0) sp.setSize(r.size);
            s = pi.openSession(pi.createSession(sp));

            HttpURLConnection h = (HttpURLConnection) new URL(r.apkUrl).openConnection();
            h.setRequestProperty("User-Agent", UA);
            h.setConnectTimeout(15_000);
            h.setReadTimeout(60_000);
            long total = r.size > 0 ? r.size : h.getContentLengthLong();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long done = 0;
            try (InputStream in = h.getInputStream(); OutputStream out = s.openWrite("immich-server.apk", 0, total)) {
                byte[] b = new byte[1 << 16];
                int n;
                long shown = 0;
                while ((n = in.read(b)) > 0) {
                    out.write(b, 0, n);
                    md.update(b, 0, n);
                    done += n;
                    if (done - shown >= (4 << 20)) {
                        shown = done;
                        status = "Downloading Immich " + r.tag + ": " + (done >> 20) + " of " + (total >> 20) + " MB…";
                    }
                }
                s.fsync(out);
            } finally {
                h.disconnect();
            }
            if (total > 0 && done != total) throw new IOException("incomplete download (" + done + " of " + total + " bytes)");
            if (r.sha256 != null && !hex(md.digest()).equalsIgnoreCase(r.sha256)) {
                throw new IOException("the download doesn't match its published checksum");
            }
            status = "Downloaded: Android is checking the app…";
            Intent cb = new Intent(ctx, UpdateReceiver.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            s.commit(PendingIntent.getBroadcast(ctx, 4, cb, flags).getIntentSender());
            committed = true;
        } catch (Exception e) {
            status = "Update failed: " + e.getMessage();
        } finally {
            if (s != null) {
                if (!committed) s.abandon();
                s.close();
            }
            busy = false;
        }
    }

    /**
     * Esito dell'installatore (da UpdateReceiver). Prima di installare Android vuole la conferma della persona: la sua
     * finestra la apre MainActivity (takeConfirm) se è in primo piano, altrimenti una notifica che la riporta lì.
     */
    static void onStatus(Context ctx, Intent in) {
        int st = in.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            pendingConfirm = in.getParcelableExtra(Intent.EXTRA_INTENT);
            status = "Downloaded: confirm the update in Android's window.";
            if (!MainActivity.resumed) {
                notify(ctx, "Immich Server update ready", "Tap to install it.", new Intent(ctx, MainActivity.class), 5);
            }
            return;
        }
        cancelNotification(ctx);
        if (st == PackageInstaller.STATUS_SUCCESS) {
            status = "Updated.";
        } else if (st == PackageInstaller.STATUS_FAILURE_ABORTED) {
            status = "Update cancelled.";
        } else {
            String msg = in.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            status = "Update not installed" + (msg == null ? "." : ": " + msg);
        }
    }

    /** la finestra di conferma di Android ancora da mostrare, una volta sola */
    static Intent takeConfirm() {
        Intent i = pendingConfirm;
        pendingConfirm = null;
        return i;
    }

    private static String read(InputStream in) throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append('\n');
            return sb.toString();
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x));
        return sb.toString();
    }
}
