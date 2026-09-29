package org.nasonmobile.immich;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Chiamate all'API di Immich sul telefono stesso, con la chiave API salvata nell'app (quella di Pruner). */
final class ImmichApi {
    static final class Reply {
        final int code;
        final String body;

        Reply(int code, String body) {
            this.code = code;
            this.body = body;
        }

        boolean ok() {
            return code >= 200 && code < 300;
        }

        /** messaggio leggibile per lo stato mostrato nell'app */
        String problem() {
            if (code == 401) return "chiave API non valida o scaduta";
            if (code == 403) return "la chiave API non ha il permesso necessario (serve \"all\" o quello del lavoro)";
            return "risposta " + code + (body.isEmpty() ? "" : ": " + body);
        }
    }

    private ImmichApi() {
    }

    static String key(Cfg c) {
        return c.prefs.getString("prune_api_key", "").trim();
    }

    /** @param json corpo della richiesta, o null */
    static Reply call(Cfg c, String method, String path, String json) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL("http://127.0.0.1:" + c.port() + "/api" + path).openConnection();
        try {
            h.setRequestMethod(method);
            h.setRequestProperty("x-api-key", key(c));
            h.setConnectTimeout(5000);
            h.setReadTimeout(60_000);
            if (json != null) {
                h.setRequestProperty("Content-Type", "application/json");
                h.setDoOutput(true);
                try (OutputStream os = h.getOutputStream()) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }
            }
            int rc = h.getResponseCode();
            return new Reply(rc, read(rc >= 400 ? h.getErrorStream() : h.getInputStream()));
        } finally {
            h.disconnect();
        }
    }

    private static String read(InputStream in) {
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
}
