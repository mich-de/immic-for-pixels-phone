package org.nasonmobile.immich;

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

    private Pruner() {
    }

    static String status() {
        return status.isEmpty() ? "Non attivo." : status;
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
            Log.w(Cfg.TAG, "pulizia Immich: " + t);
            status = "Eliminazione da Immich: errore — " + t.getMessage();
        }
    }

    private static void doRun(Cfg c) throws Exception {
        int days = c.prefs.getInt("prune_days", 0);
        String apiKey = c.prefs.getString("prune_api_key", "");
        if (days <= 0 || apiKey.isEmpty()) return; // spento

        File exp = exportedFile(c);
        if (!exp.isFile()) {
            status = "Nessuna risorsa ancora copiata nella galleria: niente da eliminare.";
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
            status = "Nessuna risorsa da eliminare da Immich per ora.";
            return;
        }

        int ok = 0;
        for (int i = 0; i < due.size(); i += 50) { // a lotti, come consiglia l'API
            List<String> batch = due.subList(i, Math.min(i + 50, due.size()));
            String err = deleteBatch(c, apiKey, batch);
            if (err != null) {
                status = "Eliminazione da Immich: " + err + " (" + ok + " eliminate prima dell'errore)";
                appendPruned(c, batch.subList(0, 0)); // niente da segnare: il lotto è fallito
                return;
            }
            appendPruned(c, batch);
            ok += batch.size();
        }
        status = "Eliminate per sempre da Immich " + ok + " risorse (copiate da almeno " + days + " giorni).";
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
            if (rc == 401) return "chiave API non valida o scaduta";
            if (rc == 403) return "la chiave API non ha il permesso \"Elimina risorse\" (asset.delete)";
            return "risposta " + rc + (detail.isEmpty() ? "" : ": " + detail);
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
                return "ok: chiave valida per l'utente " + j.optString("email", "?");
            }
            if (rc == 401) return "chiave API non valida";
            return "risposta inattesa: " + rc;
        } catch (Exception e) {
            return "errore: " + e.getMessage();
        } finally {
            if (h != null) h.disconnect();
        }
    }
}
