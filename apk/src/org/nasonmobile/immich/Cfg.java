package org.nasonmobile.immich;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.security.SecureRandom;
import java.util.TimeZone;

/** Percorsi e impostazioni dell'app. Tutto vive nella cartella privata dell'app (files/). */
final class Cfg {
    static final String TAG = "ImmichServer";

    final Context ctx;
    final SharedPreferences prefs;

    final File files;
    final File rootfs;      // userland Debian
    final File home;        // dati e configurazione di Immich
    final File appRoot;     // pacchetto dell'app (server, web, node)
    final File pgdata;      // cluster PostgreSQL
    final File valkey;      // dati Valkey
    final File library;     // foto e video (è /data per Immich)
    final File run;         // pid file e socket
    final File logs;
    final File config;
    final File fake;        // finti file /proc
    final File shm;         // /dev/shm
    final File prootTmp;

    Cfg(Context c) {
        ctx = c.getApplicationContext();
        prefs = ctx.getSharedPreferences("immich", Context.MODE_PRIVATE);
        files = ctx.getFilesDir();
        rootfs = new File(files, "rootfs");
        home = new File(files, "immich");
        appRoot = new File(home, "app");
        pgdata = new File(home, "postgres");
        valkey = new File(home, "valkey");
        library = new File(home, "library");
        run = new File(home, "run");
        logs = new File(home, "logs");
        config = new File(home, "config");
        fake = new File(files, "fake");
        shm = new File(files, "shm");
        prootTmp = new File(files, "proot-tmp");
    }

    File nativeDir() {
        return new File(ctx.getApplicationInfo().nativeLibraryDir);
    }

    /** cartella del pacchetto Immich attualmente installato */
    File pack() {
        return new File(appRoot, "pack");
    }

    File setupLog() {
        return new File(logs, "setup.log");
    }

    // --- impostazioni ------------------------------------------------------

    int port() {
        return prefs.getInt("port", 2283);
    }

    boolean autostart() {
        return prefs.getBoolean("autostart", false);
    }

    void setAutostart(boolean v) {
        prefs.edit().putBoolean("autostart", v).apply();
    }

    /** true se l'utente ha avviato il server e non l'ha fermato: serve a riavviarlo dopo un aggiornamento dell'app */
    boolean shouldRun() {
        return prefs.getBoolean("should_run", false);
    }

    void setShouldRun(boolean v) {
        prefs.edit().putBoolean("should_run", v).apply();
    }

    boolean noSeccomp() {
        return prefs.getBoolean("proot_no_seccomp", false);
    }

    void setNoSeccomp(boolean v) {
        prefs.edit().putBoolean("proot_no_seccomp", v).apply();
    }

    /** password del database: casuale, solo A-Za-z0-9, generata una volta */
    String dbPassword() {
        String p = prefs.getString("db_password", null);
        if (p == null) {
            String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
            SecureRandom r = new SecureRandom();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 28; i++) sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
            p = sb.toString();
            prefs.edit().putString("db_password", p).apply();
        }
        return p;
    }

    String tz() {
        return TimeZone.getDefault().getID();
    }

    long totalMemMb() {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.totalMem / (1024 * 1024);
    }

    /** shared_buffers di Postgres, scalato sulla RAM del telefono */
    String pgSharedBuffers() {
        long m = totalMemMb();
        return m >= 7000 ? "256MB" : m >= 5000 ? "192MB" : "128MB";
    }

    int nodeHeapMb() {
        return totalMemMb() >= 7000 ? 1536 : 1024;
    }

    int threads() {
        return Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
    }
}
