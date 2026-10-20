package org.nasonmobile.immich;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Elimina PER SEMPRE gli originali da Immich un po' di giorni dopo che sono stati copiati nella galleria
 * (vedi Exporter), per non tenere due copie sul telefono a lungo: dopo, l'unica copia che resta è quella che
 * Google Foto ha caricato (in qualità "Risparmio spazio" sul Pixel 5, non l'originale).
 *
 * Spento di serie. Richiede una chiave API di Immich con il permesso "Elimina risorse" (asset.delete), che
 * l'utente genera lui stesso in Immich (Account → Chiavi API) e incolla nell'app: senza, questa classe non fa nulla.
 * Elimina solo risorse che Exporter ha già copiato con successo nella galleria (registro in export/exported.tsv):
 * non tocca mai una risorsa che non è mai finita in DCIM/Immich, saltata perché il formato non è supportato o senza
 * accesso al file.
 *
 * Usa l'API HTTP di Immich (DELETE /api/assets, force=true), non SQL diretto: è Immich stesso a occuparsi di
 * miniature, coda di elaborazione e file su disco (evento AssetDeleteAll → job AssetEmptyTrash → AssetDelete con
 * deleteOnDisk=true), invece di reimplementare la sua logica interna.
 */
final class Pruner {
    private static volatile String status = "";
    /** cutoff usato quando "Mai" (0) + l'interruttore d'emergenza + spazio critico: 1 giorno, non "subito" */
    private static final int EMERGENCY_DAYS = 1;
    /** quante risorse "Mai" può toccare in UN giro: non svuota mesi di arretrato alla prima vera emergenza */
    private static final int EMERGENCY_CAP = 200;
    private static final String CHANNEL = "prune_emergency";
    private static final int NID = 4; // ServerService=1, BatteryGuard=2, Updater=3

    private Pruner() {
    }

    static String status() {
        return status.isEmpty() ? "Off." : status;
    }

    /**
     * Quanti giorni usare davvero, in base allo spazio libero: non supera mai quello scelto dall'utente, scende
     * solo se lo spazio peggiora, e a "Mai" (0) resta un no-op a meno che l'interruttore d'emergenza sia acceso E
     * lo spazio sia davvero critico (non solo "in esaurimento"). Pura logica, provabile sul PC senza File reali:
     * spaceLevel è quello di Health.spaceLevel (-1 ok, 0 in esaurimento, 1 critico).
     */
    static int effectiveDaysFromLevel(int userDays, int spaceLevel, boolean emergencyOn) {
        if (userDays > 0) {
            if (spaceLevel == 1) return 1;
            if (spaceLevel == 0) return Math.max(1, userDays / 2);
            return userDays;
        }
        return (spaceLevel == 1 && emergencyOn) ? EMERGENCY_DAYS : 0;
    }

    static int effectiveDays(Cfg c) {
        return effectiveDaysFromLevel(c.prefs.getInt("prune_days", 0), Health.spaceLevel(c.files),
            c.prefs.getBoolean("prune_emergency", false));
    }

    /** "" se il taglio non è ridotto rispetto a quello scelto (o la funzione è spenta): altrimenti lo spiega */
    static String effectiveDaysNote(Cfg c) {
        int userDays = c.prefs.getInt("prune_days", 0);
        if (c.prefs.getString("prune_api_key", "").isEmpty()) return "";
        int days = effectiveDays(c);
        if (days == userDays) return "";
        String lvl = Health.spaceLevel(c.files) == 1 ? "critically low" : "running low";
        return userDays == 0
            ? "Storage is " + lvl + ": emergency deletion is active even though you set \"Never\" (" + days + " day"
                + (days == 1 ? "" : "s") + ")."
            : "Storage is " + lvl + ": shrunk from " + userDays + " to " + days + " day" + (days == 1 ? "" : "s") + ".";
    }

    private static File exportedFile(Cfg c) {
        return new File(new File(c.home, "export"), "exported.tsv");
    }

    private static File prunedFile(Cfg c) {
        return new File(new File(c.home, "export"), "pruned.tsv");
    }

    /** Chiamato da Exporter subito dopo aver copiato con successo una risorsa nella galleria. */
    static void markExported(Cfg c, String assetId, long whenMs) throws IOException {
        Util.mkdirs(exportedFile(c).getParentFile());
        try (FileWriter w = new FileWriter(exportedFile(c), true)) {
            w.write(assetId + "\t" + whenMs + "\n");
        }
    }

    private static Set<String> loadPruned(Cfg c) {
        Set<String> s = new HashSet<>();
        File f = prunedFile(c);
        if (f.isFile()) {
            try {
                for (String l : Util.read(f).split("\n")) {
                    if (!l.isEmpty()) s.add(l);
                }
            } catch (IOException ignored) {
                // ripartirà da capo, non grave
            }
        }
        return s;
    }

    /**
     * Un giro: legge quali risorse sono state esportate abbastanza tempo fa e non ancora eliminate, e le elimina
     * da Immich tramite la sua API. Non lancia eccezioni: riporta lo stato.
     */
    static synchronized void runOnce(Cfg c) {
        try {
            doRun(c);
        } catch (Throwable t) {
            Log.w(Cfg.TAG, "Immich pruning: " + t);
            status = "Deleting from Immich: error — " + t.getMessage();
        }
    }

    private static void doRun(Cfg c) throws Exception {
        int userDays = c.prefs.getInt("prune_days", 0);
        int days = effectiveDays(c);
        boolean emergency = userDays <= 0 && days > 0; // "Mai" ma l'emergenza l'ha acceso per questo giro
        if (!emergency) clearEmergencyNotified(c); // fuori dall'emergenza: un prossimo episodio avviserà di nuovo
        String apiKey = c.prefs.getString("prune_api_key", "");
        if (days <= 0 || apiKey.isEmpty()) return; // spento

        File exp = exportedFile(c);
        if (!exp.isFile()) {
            status = "No asset copied to the gallery yet: nothing to delete.";
            return;
        }
        long cutoff = System.currentTimeMillis() - days * 86_400_000L;
        Set<String> pruned = loadPruned(c);
        List<String> due = new ArrayList<>();
        for (String line : Util.read(exp).split("\n")) {
            String[] p = line.split("\t");
            if (p.length != 2) continue;
            if (pruned.contains(p[0])) continue;
            try {
                if (Long.parseLong(p[1]) <= cutoff) due.add(p[0]);
            } catch (NumberFormatException ignored) {
                // riga corrotta: la si ignora
            }
        }
        if (due.isEmpty()) {
            status = "Nothing to delete from Immich for now.";
            return;
        }
        // emergenza = stavano protette da "Mai": non si svuota tutto l'arretrato al primo giro, solo le più vecchie
        if (emergency && due.size() > EMERGENCY_CAP) due = due.subList(0, EMERGENCY_CAP);

        int ok = 0;
        for (int i = 0; i < due.size(); i += 50) { // a lotti, come consiglia l'API
            List<String> batch = due.subList(i, Math.min(i + 50, due.size()));
            String err = deleteBatch(c, apiKey, batch);
            if (err != null) {
                status = "Deleting from Immich: " + err + " (" + ok + " deleted before the error)";
                appendPruned(c, batch.subList(0, 0)); // niente da segnare: il lotto è fallito
                return;
            }
            appendPruned(c, batch);
            ok += batch.size();
        }
        status = "Permanently deleted " + ok + " assets from Immich (copied at least " + days + " days ago).";
        if (emergency && ok > 0 && !c.prefs.getBoolean("prune_emergency_notified", false)) {
            notifyEmergency(c, ok);
            c.prefs.edit().putBoolean("prune_emergency_notified", true).apply();
        }
    }

    private static void clearEmergencyNotified(Cfg c) {
        if (c.prefs.getBoolean("prune_emergency_notified", false)) {
            c.prefs.edit().putBoolean("prune_emergency_notified", false).apply();
        }
    }

    private static void notifyEmergency(Cfg c, int count) {
        NotificationManager nm = (NotificationManager) c.ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Emergency cleanup", NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(c.ctx, NID, new Intent(c.ctx, MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT);
        int small = c.ctx.getResources().getIdentifier("ic_stat", "drawable", c.ctx.getPackageName());
        Notification n = new Notification.Builder(c.ctx, CHANNEL)
            .setContentTitle("Freed space: deleted " + count + " originals from Immich")
            .setContentText("Storage was critically low. Only originals already safely in the gallery were deleted, "
                + "permanently, even though you had set \"Never\". Turn it off in the app if you don't want this.")
            .setStyle(new Notification.BigTextStyle().bigText("Storage was critically low, so the emergency cleanup you "
                + "turned on deleted " + count + " originals from Immich, permanently. It only ever touches originals "
                + "already safely copied to the gallery, oldest first. Turn off \"Emergency: also delete originals...\" "
                + "in the app if you don't want this to happen again."))
            .setSmallIcon(small != 0 ? small : c.ctx.getApplicationInfo().icon)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build();
        nm.notify(NID, n);
    }

    private static void appendPruned(Cfg c, List<String> ids) throws IOException {
        if (ids.isEmpty()) return;
        Util.mkdirs(prunedFile(c).getParentFile());
        try (FileWriter w = new FileWriter(prunedFile(c), true)) {
            for (String id : ids) w.write(id + "\n");
        }
    }

    /** @return null se riuscito, altrimenti un messaggio d'errore leggibile */
    private static String deleteBatch(Cfg c, String apiKey, List<String> ids) {
        HttpURLConnection h = null;
        try {
            JSONArray arr = new JSONArray();
            for (String id : ids) arr.put(id);
            JSONObject body = new JSONObject().put("ids", arr).put("force", true);

            h = (HttpURLConnection) new URL("http://127.0.0.1:" + c.port() + "/api/assets").openConnection();
            h.setRequestMethod("DELETE");
            h.setRequestProperty("Content-Type", "application/json");
            h.setRequestProperty("x-api-key", apiKey);
            h.setDoOutput(true);
            h.setConnectTimeout(5000);
            h.setReadTimeout(60_000);
            try (OutputStream os = h.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int rc = h.getResponseCode();
            if (rc == 204 || rc == 200) return null;
            String detail = readBody(rc >= 400 ? h.getErrorStream() : h.getInputStream());
            if (rc == 401) return "API key not valid or expired";
            if (rc == 403) return "the API key lacks the asset.delete permission";
            return "response " + rc + (detail.isEmpty() ? "" : ": " + detail);
        } catch (Exception e) {
            return e.getMessage();
        } finally {
            if (h != null) h.disconnect();
        }
    }

    private static String readBody(InputStream in) {
        if (in == null) return "";
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null && sb.length() < 300) sb.append(l);
            return sb.toString();
        } catch (IOException e) {
            return "";
        }
    }

    /** Prova la chiave: un giro qualsiasi che richieda autenticazione, senza toccare nessuna risorsa. */
    static String testApiKey(Cfg c, String apiKey) {
        HttpURLConnection h = null;
        try {
            h = (HttpURLConnection) new URL("http://127.0.0.1:" + c.port() + "/api/users/me").openConnection();
            h.setRequestProperty("x-api-key", apiKey);
            h.setConnectTimeout(5000);
            h.setReadTimeout(10_000);
            int rc = h.getResponseCode();
            if (rc == 200) {
                String body = readBody(h.getInputStream());
                JSONObject j = new JSONObject(body);
                return "ok: valid key for user " + j.optString("email", "?");
            }
            if (rc == 401) return "API key not valid";
            return "unexpected response: " + rc;
        } catch (Exception e) {
            return "error: " + e.getMessage();
        } finally {
            if (h != null) h.disconnect();
        }
    }
}
