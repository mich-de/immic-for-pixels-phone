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
        return mb >= 1024 ? String.format(Locale.US, "%.1f GB", mb / 1024.0) : mb + " MB";
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
            case 1: return "CRITICAL: only " + size + " free on the phone — Postgres and the copies may stall";
            case 0: return "running low: " + size + " free on the phone";
            default: return size + " free on the phone";
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
        return v == 0 ? "disabled (ok)" : v == 1 ? "ACTIVE (Android may kill the server)" : "not set (usually active)";
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

    // --- DNS privato --------------------------------------------------------
    //
    // Alcuni router (es. il "Controllo accessi"/parental control di certi modem) rispondono al telefono con un
    // indirizzo finto per i nomi a dominio esterni (github.com, google.com, ...), invece di bloccare la connessione
    // apertamente: da qui in giù internet funziona (un indirizzo IP diretto risponde), ma qualunque nome a dominio no,
    // controllo degli aggiornamenti compreso. Il DNS privato (Impostazioni → Rete e internet → DNS privato) chiede
    // invece i nomi a dominio via TLS direttamente a un resolver pubblico (porta 853, non la 53 del router): se è il
    // router a manomettere le risposte, questo di solito basta ad aggirarlo. Richiede WRITE_SECURE_SETTINGS, già
    // dichiarato nel manifest e concesso allo stesso modo delle restrizioni sui processi figli qui sopra.

    private static final String DNS_MODE_KEY = "private_dns_mode";
    private static final String DNS_HOST_KEY = "private_dns_specifier";
    static final String PUBLIC_DNS_HOST = "dns.google";

    /** host attuale se il DNS privato è acceso in modalità "hostname", altrimenti null */
    static String privateDnsHost(Context c) {
        String mode = Settings.Global.getString(c.getContentResolver(), DNS_MODE_KEY);
        return "hostname".equals(mode) ? Settings.Global.getString(c.getContentResolver(), DNS_HOST_KEY) : null;
    }

    static boolean usingPublicDns(Context c) {
        return PUBLIC_DNS_HOST.equals(privateDnsHost(c));
    }

    static String privateDnsText(Context c) {
        String host = privateDnsHost(c);
        return host != null ? "on, via " + host : "automatic (uses the Wi-Fi network's own DNS when it allows it)";
    }

    /** chiede i nomi a dominio direttamente a "host" (es. "dns.google") invece che al router */
    static boolean setPublicDns(Context c, String host) {
        try {
            return Settings.Global.putString(c.getContentResolver(), DNS_HOST_KEY, host)
                && Settings.Global.putString(c.getContentResolver(), DNS_MODE_KEY, "hostname");
        } catch (SecurityException e) {
            return false;
        }
    }

    /** torna al DNS della rete Wi-Fi (quello del router) */
    static boolean clearPrivateDns(Context c) {
        try {
            return Settings.Global.putString(c.getContentResolver(), DNS_MODE_KEY, "opportunistic");
        } catch (SecurityException e) {
            return false;
        }
    }

    static String dnsAdbCommands(Context c) {
        String p = c.getPackageName();
        return "adb shell pm grant " + p + " android.permission.WRITE_SECURE_SETTINGS\n"
            + "adb shell settings put global " + DNS_HOST_KEY + " " + PUBLIC_DNS_HOST + "\n"
            + "adb shell settings put global " + DNS_MODE_KEY + " hostname";
    }
}
