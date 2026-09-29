package org.nasonmobile.immich;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.PowerManager;
import android.provider.Settings;

import java.io.File;
import java.util.Locale;

/** Impostazioni di Android che decidono se il server sopravvive in background. */
final class Health {
    /** "Disattiva restrizioni processi figli" (Android 12+): senza, Android uccide PostgreSQL & co. */
    static final String PHANTOM_KEY = "settings_enable_monitor_phantom_procs";

    /** sotto: la copia in galleria si ferma da sola (Exporter), ma vale la pena avvisare */
    static final long LOW_SPACE_MB = 2048;
    /** sotto: rischio concreto per Postgres e per il backup sul telefono */
    static final long CRIT_SPACE_MB = 500;

    private Health() {
    }

    /** MB liberi nella partizione dei dati dell'app (dove stanno Postgres, Valkey e la libreria) */
    static long freeMb(File dataDir) {
        return dataDir.getUsableSpace() / (1024 * 1024);
    }

    private static String sizeText(long mb) {
        return mb >= 1024 ? String.format(Locale.ITALY, "%.1f GB", mb / 1024.0) : mb + " MB";
    }

    /** -1 tutto ok, 0 in esaurimento, 1 critico: comodo per scegliere il colore in MainActivity */
    static int spaceLevel(File dataDir) {
        long mb = freeMb(dataDir);
        return mb < CRIT_SPACE_MB ? 1 : mb < LOW_SPACE_MB ? 0 : -1;
    }

    static String spaceText(File dataDir) {
        long mb = freeMb(dataDir);
        String size = sizeText(mb);
        switch (spaceLevel(dataDir)) {
            case 1: return "CRITICO: solo " + size + " liberi sul telefono — Postgres e le copie possono bloccarsi";
            case 0: return "in esaurimento: " + size + " liberi sul telefono";
            default: return size + " liberi sul telefono";
        }
    }

    /** 1 = restrizioni attive, 0 = disattivate, -1 = non impostata (Android: attive) */
    static int phantomRestrictions(Context c) {
        String v = Settings.Global.getString(c.getContentResolver(), PHANTOM_KEY);
        if (v == null) return -1;
        v = v.trim();
        return v.equalsIgnoreCase("false") || v.equals("0") ? 0 : 1;
    }

    static boolean canWriteSecure(Context c) {
        return c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED;
    }

    static boolean setPhantomRestrictions(Context c, boolean on) {
        try {
            return Settings.Global.putString(c.getContentResolver(), PHANTOM_KEY, on ? "true" : "false");
        } catch (SecurityException e) {
            return false;
        }
    }

    static boolean ignoringBattery(Context c) {
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
    }

    static String phantomText(Context c) {
        int v = phantomRestrictions(c);
        return v == 0 ? "disattivate (ok)" : v == 1 ? "ATTIVE (Android può uccidere il server)" : "non impostate (di norma attive)";
    }

    /** comandi adb da lanciare una volta dal PC */
    static String adbCommands(Context c) {
        String p = c.getPackageName();
        return "adb shell pm grant " + p + " android.permission.WRITE_SECURE_SETTINGS\n"
            + "adb shell settings put global " + PHANTOM_KEY + " false\n"
            + "adb shell device_config set_sync_disabled_for_tests persistent\n"
            + "adb shell device_config put activity_manager max_phantom_processes 2147483647\n"
            + "adb shell dumpsys deviceidle whitelist +" + p;
    }
}
