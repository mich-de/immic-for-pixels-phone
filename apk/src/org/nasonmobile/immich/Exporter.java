package org.nasonmobile.immich;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;
import android.webkit.MimeTypeMap;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * Porta le foto e i video di Immich dove Google Foto può fare il backup: la galleria del telefono (DCIM/Immich).
 * Gli originali di Immich stanno nella memoria privata dell'app, che né la Galleria né Google Foto possono leggere,
 * quindi una copia in memoria condivisa è inevitabile. Per non tenere due copie per sempre la copia è TEMPORANEA:
 * dopo il tempo scelto (o quando raggiunge il tetto di spazio) l'app la elimina; l'originale resta in Immich e,
 * se Google Foto l'ha già caricata, resta anche nel cloud. Google Foto non permette di sapere quando ha finito di
 * caricare: per questo si usa un tempo, non un evento. Se vuoi liberare prima, "Libera spazio" di Google Foto elimina
 * le copie già caricate (l'app se ne accorge e riprende a copiare).
 *
 * Si scrive con MediaStore, che non richiede permessi per i file creati dall'app e li rende subito visibili.
 * Si copiano solo le risorse nuove (chiave: data di creazione + id); per default solo quelle dell'amministratore
 * più vecchio (il proprietario del telefono), non quelle di eventuali altri utenti, e mai quelle "bloccate".
 */
final class Exporter {
    static final String DIR = "DCIM/Immich";
    private static final int BATCH = 100;
    private static final long DAY_MS = 86_400_000L;
    private static final Object STAGED_LOCK = new Object();

    private static volatile String status = "";
    private static volatile boolean running;

    /** una copia in galleria ancora da eliminare */
    private static final class Staged {
        String uri;
        long ts;
        long size;
    }

    private Exporter() {
    }

    static String status() {
        return status.isEmpty() ? "Nessuna copia eseguita finora." : status;
    }

    // ------------------------------------------------------------------ giro di copia

    /** Un giro: elimina le copie scadute, poi chiede al database le risorse nuove e le copia. Non lancia eccezioni. */
    static synchronized void runOnce(Stack stack, Cfg c) {
        if (running) return;
        running = true;
        try {
            doRun(stack, c);
        } catch (InterruptedException e) {
            status = "Copia interrotta.";
        } catch (Throwable t) {
            Log.w(Cfg.TAG, "esportazione: " + t);
            status = "Copia nella galleria: errore — " + t.getMessage();
        } finally {
            running = false;
        }
    }

    private static void doRun(Stack stack, Cfg c) throws Exception {
        if (Build.VERSION.SDK_INT < 29) throw new IOException("serve Android 10 o successivo");
        cleanup(c, false);

        File dir = new File(c.home, "export");
        Util.mkdirs(dir);
        File wmFile = new File(dir, "watermark.txt");
        File doneFile = new File(dir, "done.txt");

        String wm = "1970-01-01 00:00:00+00";
        String lastId = "00000000-0000-0000-0000-000000000000";
        if (wmFile.isFile()) {
            String[] p = Util.read(wmFile).trim().split("\t");
            if (p.length == 2 && p[0].matches("[0-9 :.+-]+") && p[1].matches("[0-9a-f-]{36}")) {
                wm = p[0];
                lastId = p[1];
            }
        }
        Set<String> done = new HashSet<>();
        if (doneFile.isFile()) {
            for (String l : Util.read(doneFile).split("\n")) {
                if (!l.isEmpty()) done.add(l);
            }
        }

        boolean allUsers = c.prefs.getBoolean("export_all_users", false);
        int capGb = c.prefs.getInt("export_cap_gb", 10);
        long cap = capGb * (1L << 30); // 0 = nessun tetto
        long staged = stagedBytes(c);
        int copied = 0;
        int skipped = 0;
        boolean capped = false;
        outer:
        while (true) {
            List<JSONObject> rows = parse(stack.query(c, sqlNew(wm, lastId, allUsers)));
            if (rows.isEmpty()) break;
            for (JSONObject r : rows) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                String id = r.getString("id");
                if (!done.contains(id)) {
                    File src = source(c, r);
                    long size = src == null ? 0 : src.length();
                    // tetto alle copie in attesa: almeno una si copia sempre, anche se da sola supera il tetto
                    if (cap > 0 && staged > 0 && staged + size > cap) {
                        capped = true;
                        break outer;
                    }
                    status = "Copio nella galleria: " + (copied + skipped + 1) + " — " + r.optString("name");
                    Uri u = src == null ? null : copyOne(c, r, src);
                    if (u != null) {
                        long now = System.currentTimeMillis();
                        addStaged(c, u, size, now);
                        Pruner.markExported(c, id, now); // solo ORA sappiamo che questa risorsa è davvero in galleria
                        staged += size;
                        copied++;
                    } else {
                        skipped++;
                    }
                    done.add(id);
                    try (FileWriter w = new FileWriter(doneFile, true)) {
                        w.write(id + "\n");
                    }
                }
                wm = r.getString("created");
                lastId = id;
            }
            Util.write(wmFile, wm + "\t" + lastId + "\n");
            if (rows.size() < BATCH) break;
        }
        if (capped) Util.write(wmFile, wm + "\t" + lastId + "\n"); // riprende da qui

        String when = new SimpleDateFormat("HH:mm", Locale.ITALY).format(new Date());
        status = capped
            ? "In pausa alle " + when + ": raggiunto il tetto di " + capGb + " GB di copie in galleria. Riprende quando le "
                + "copie vecchie vengono eliminate (o se alzi il tetto)."
            : "Ultimo controllo alle " + when + ": " + copied + " copiati ora, " + done.size() + " in totale"
                + (skipped > 0 ? " (" + skipped + " saltati: file mancante o formato non supportato)" : "") + ".";
    }

    private static String sqlNew(String wm, String lastId, boolean allUsers) {
        return "SELECT row_to_json(t) FROM (SELECT a.id, a.\"originalPath\" AS path, a.\"originalFileName\" AS name, a.type, "
            + "to_char(a.\"fileCreatedAt\" AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') AS taken, "
            + "to_char(a.\"createdAt\" AT TIME ZONE 'UTC','YYYY-MM-DD HH24:MI:SS.US') || '+00' AS created "
            + "FROM asset a WHERE a.\"deletedAt\" IS NULL AND a.type IN ('IMAGE','VIDEO') "
            + "AND a.visibility IN ('timeline','archive') "
            + (allUsers ? "" : "AND a.\"ownerId\" = (SELECT u.id FROM \"user\" u WHERE u.\"isAdmin\" "
                + "AND u.\"deletedAt\" IS NULL ORDER BY u.\"createdAt\" LIMIT 1) ")
            + "AND (a.\"createdAt\", a.id) > ('" + wm + "'::timestamptz, '" + lastId + "'::uuid) "
            + "ORDER BY a.\"createdAt\", a.id LIMIT " + BATCH + ") t";
    }

    private static List<JSONObject> parse(String out) throws Exception {
        List<JSONObject> rows = new ArrayList<>();
        for (String line : out.split("\n")) {
            line = line.trim();
            if (line.startsWith("{")) rows.add(new JSONObject(line));
        }
        return rows;
    }

    /** il file originale di Immich, o null se manca o sta fuori dalla libreria (libreria esterna: non gestita) */
    private static File source(Cfg c, JSONObject r) throws Exception {
        String path = r.getString("path"); // percorso dentro Debian: /data/upload/...
        if (!path.startsWith("/data/")) return null;
        File src = new File(c.library, path.substring("/data/".length()));
        return src.isFile() ? src : null;
    }

    private static Uri copyOne(Cfg c, JSONObject r, File src) throws Exception {
        String name = r.optString("name", src.getName());
        boolean video = "VIDEO".equals(r.optString("type"));
        String mime = mime(name, video);
        if (mime == null) return null;
        return insert(c.ctx, src, name, mime, takenMillis(r.optString("taken")));
    }

    private static long takenMillis(String iso) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = f.parse(iso);
            return d == null ? 0 : d.getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    private static String mime(String name, boolean video) {
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if (m == null) {
            switch (ext) {
                case "heic": m = "image/heic"; break;
                case "heif": m = "image/heif"; break;
                case "avif": m = "image/avif"; break;
                case "dng": m = "image/x-adobe-dng"; break;
                case "mov": m = "video/quicktime"; break;
                case "3gp": m = "video/3gpp"; break;
                default: return null;
            }
        }
        return m.startsWith("video/") == video ? m : null;
    }

    /** Crea il file in DCIM/Immich tramite MediaStore e restituisce il suo URI. */
    private static Uri insert(Context ctx, File src, String name, String mime, long takenMs) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        boolean video = mime.startsWith("video/");
        Uri collection = video
            ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, DIR);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        if (takenMs > 0) v.put(MediaStore.MediaColumns.DATE_TAKEN, takenMs); // suggerimento: la scansione può sostituirlo
        Uri uri = cr.insert(collection, v);
        if (uri == null) throw new IOException("MediaStore ha rifiutato " + name);
        try (InputStream in = new FileInputStream(src); OutputStream out = cr.openOutputStream(uri)) {
            if (out == null) throw new IOException("impossibile scrivere " + name);
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        } catch (IOException e) {
            cr.delete(uri, null, null);
            throw e;
        }
        ContentValues fin = new ContentValues();
        fin.put(MediaStore.MediaColumns.IS_PENDING, 0);
        // qui MediaProvider scansiona il file: la data di scatto la legge dai metadati (EXIF, container video). Per i file
        // senza metadati (screenshot, PNG) il sistema usa la data della copia: non si può impostare dall'esterno.
        cr.update(uri, fin, null, null);
        return uri;
    }

    // ------------------------------------------------------------------ copie temporanee

    private static File stagedFile(Cfg c) {
        return new File(new File(c.home, "export"), "staged.tsv");
    }

    private static List<Staged> loadStaged(Cfg c) {
        List<Staged> l = new ArrayList<>();
        File f = stagedFile(c);
        if (!f.isFile()) return l;
        try {
            for (String line : Util.read(f).split("\n")) {
                String[] p = line.split("\t");
                if (p.length != 3) continue;
                Staged s = new Staged();
                s.uri = p[0];
                s.ts = Long.parseLong(p[1]);
                s.size = Long.parseLong(p[2]);
                l.add(s);
            }
        } catch (Exception e) {
            Log.w(Cfg.TAG, "staged.tsv illeggibile: " + e);
        }
        return l;
    }

    private static void saveStaged(Cfg c, List<Staged> l) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Staged s : l) sb.append(s.uri).append('\t').append(s.ts).append('\t').append(s.size).append('\n');
        Util.write(stagedFile(c), sb.toString());
    }

    private static void addStaged(Cfg c, Uri uri, long size, long ts) throws IOException {
        synchronized (STAGED_LOCK) {
            Util.mkdirs(stagedFile(c).getParentFile());
            try (FileWriter w = new FileWriter(stagedFile(c), true)) {
                w.write(uri + "\t" + ts + "\t" + size + "\n");
            }
        }
    }

    private static long stagedBytes(Cfg c) {
        long sum = 0;
        synchronized (STAGED_LOCK) {
            for (Staged s : loadStaged(c)) sum += s.size;
        }
        return sum;
    }

    /** "3 file, 12 MB" — quello che è in galleria e non è ancora stato eliminato dall'app */
    static String stagedInfo(Cfg c) {
        int n = 0;
        long sum = 0;
        synchronized (STAGED_LOCK) {
            for (Staged s : loadStaged(c)) {
                n++;
                sum += s.size;
            }
        }
        int days = c.prefs.getInt("export_keep_days", 7);
        return "Copie in galleria ora: " + n + " file, " + (sum >> 20) + " MB"
            + (days > 0 ? " (eliminate dopo " + days + " giorni)." : " (non vengono eliminate).");
    }

    private static boolean exists(ContentResolver cr, String uri) {
        try (Cursor q = cr.query(Uri.parse(uri), new String[]{MediaStore.MediaColumns._ID}, null, null, null)) {
            return q != null && q.getCount() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Elimina le copie in galleria scadute ({@code all} = tutte). Toglie dall'elenco anche quelle che non ci sono più
     * (per esempio eliminate da "Libera spazio" di Google Foto). Non tocca mai Immich: gli originali restano al loro posto.
     * Restituisce quante ne ha eliminate.
     */
    static int cleanup(Cfg c, boolean all) {
        ContentResolver cr = c.ctx.getContentResolver();
        int days = c.prefs.getInt("export_keep_days", 7);
        long cutoff = System.currentTimeMillis() - days * DAY_MS;
        int removed = 0;
        synchronized (STAGED_LOCK) {
            List<Staged> keep = new ArrayList<>();
            for (Staged s : loadStaged(c)) {
                if (all || (days > 0 && s.ts < cutoff)) {
                    try {
                        removed += cr.delete(Uri.parse(s.uri), null, null);
                    } catch (Exception e) {
                        Log.w(Cfg.TAG, "copia non eliminata (" + s.uri + "): " + e);
                    }
                } else if (exists(cr, s.uri)) {
                    keep.add(s);
                }
            }
            try {
                saveStaged(c, keep);
            } catch (IOException e) {
                Log.w(Cfg.TAG, "staged.tsv non salvato: " + e);
            }
        }
        if (removed > 0) Log.i(Cfg.TAG, "copie in galleria eliminate: " + removed);
        return removed;
    }

    /** Dimentica cosa è già stato copiato: la prossima copia riparte da tutte le foto (dopo aver eliminato le copie). */
    static void resetState(Cfg c) {
        File dir = new File(c.home, "export");
        new File(dir, "done.txt").delete();
        new File(dir, "watermark.txt").delete();
    }

    // ------------------------------------------------------------------ prove

    /**
     * Prova a secco: fa la stessa query e gli stessi controlli della copia vera (file presente, formato riconosciuto)
     * ma non scrive nulla e non riporta nomi: solo i conteggi.
     */
    static String dryRun(Stack stack, Cfg c) throws Exception {
        boolean allUsers = c.prefs.getBoolean("export_all_users", false);
        List<JSONObject> rows = parse(stack.query(c, sqlNew("1970-01-01 00:00:00+00", "00000000-0000-0000-0000-000000000000", allUsers)));
        int ok = 0;
        int missing = 0;
        int unsupported = 0;
        for (JSONObject r : rows) {
            File src = source(c, r);
            if (src == null) missing++;
            else if (mime(r.optString("name", src.getName()), "VIDEO".equals(r.optString("type"))) == null) unsupported++;
            else ok++;
        }
        return rows.size() + " risorse trovate (al massimo " + BATCH + " per giro): " + ok + " copiabili, " + missing
            + " senza file, " + unsupported + " con formato non supportato";
    }

    /** Immagine di prova (senza toccare il server): serve a vedere la cartella in Galleria e in Google Foto. */
    static String testImage(Cfg c) throws IOException {
        return testImage(c, System.currentTimeMillis(), System.currentTimeMillis());
    }

    /** {@code stagedTs}: da quando conta la copia per la scadenza (parametro per poter provare l'eliminazione) */
    static String testImage(Cfg c, long takenMs, long stagedTs) throws IOException {
        if (Build.VERSION.SDK_INT < 29) throw new IOException("serve Android 10 o successivo");
        Context ctx = c.ctx;
        Bitmap bmp = Bitmap.createBitmap(1600, 1000, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 1600, 1000, Color.parseColor("#3B4BA8"), Color.parseColor("#E0A030"), Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, 1600, 1000, p);
        p.setShader(null);
        p.setColor(Color.WHITE);
        p.setTextSize(110);
        p.setTextAlign(Paint.Align.CENTER);
        cv.drawText("Immich Server", 800, 470, p);
        p.setTextSize(56);
        String stamp = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ITALY).format(new Date());
        cv.drawText("immagine di prova — " + stamp, 800, 570, p);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, bo);
        File tmp = File.createTempFile("prova", ".jpg", ctx.getCacheDir());
        try {
            Util.copy(new java.io.ByteArrayInputStream(bo.toByteArray()), tmp);
            String name = "Immich-prova-" + new SimpleDateFormat("HHmmss", Locale.US).format(new Date()) + ".jpg";
            Uri u = insert(ctx, tmp, name, "image/jpeg", takenMs);
            addStaged(c, u, tmp.length(), stagedTs); // anche la prova è una copia temporanea: sparisce da sola
            return name;
        } finally {
            tmp.delete();
        }
    }
}
