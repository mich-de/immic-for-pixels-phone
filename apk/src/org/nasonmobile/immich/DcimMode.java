package org.nasonmobile.immich;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Originali di Immich direttamente nella memoria condivisa, dove Galleria e Google Foto li vedono senza copie:
 * <pre>
 *   /data/upload  → DCIM/.immich-upload   caricamenti appena arrivati (cartella nascosta: Android non la indicizza)
 *   /data/library → DCIM/Immich           library/&lt;etichetta&gt;/&lt;nome originale&gt;, dal modello di archiviazione
 * </pre>
 * Immich sposta ogni foto da upload a library dopo averne letto i metadati: le due cartelle stanno sulla stessa
 * memoria, quindi è una rinomina e Google Foto vede solo file completi (su memorie diverse Immich copierebbe il file
 * direttamente col nome finale). Tutti i file di un utente finiscono in una cartella sola perché Google Foto fa il
 * backup cartella per cartella. Miniature, video convertiti, database e backup restano nella cartella privata.
 *
 * Il prezzo: da lì anche altre app possono cancellare gli originali, per esempio "Libera spazio" di Google Foto
 * dopo il backup. Immich se ne accorge nel controllo notturno dei file mancanti e MissingCleaner li toglie.
 *
 * Le foto già caricate si spostano a server fermo, prima dei servizi (prepare). Il lavoro è ripetibile e una volta
 * iniziato si finisce comunque, anche se nel frattempo l'interruttore viene spento: a metà una parte degli originali
 * sarebbe fuori dal posto in cui Immich la cerca. Finito, il marcatore Cfg.dcimMarker() rende permanenti i
 * collegamenti; spegnere l'interruttore dopo ferma solo lo spostamento delle foto nuove in DCIM/Immich.
 */
final class DcimMode {
    private static volatile String status = "";

    private DcimMode() {
    }

    static String status(Cfg c) {
        if (!status.isEmpty()) return status;
        if (c.dcimActive()) {
            return c.dcimWanted() ? "On: the originals are in " + c.dcimLibrary() + "/<user>."
                : "Off: new photos stay in the hidden folder DCIM/.immich-upload (the ones already in DCIM/Immich stay there).";
        }
        return c.dcimWanted() ? "Pending: the originals move at the next server start." : "Off.";
    }

    private static File inProgress(Cfg c) {
        return new File(c.home, "dcim-in-corso");
    }

    /** l'app può scrivere nella memoria condivisa? (serve il permesso Memoria) */
    static boolean canUse(Cfg c) {
        try {
            Util.mkdirs(c.dcimUpload());
            Util.mkdirs(c.dcimLibrary());
            File t = new File(c.dcimUpload(), ".probe-" + System.nanoTime());
            try (FileOutputStream o = new FileOutputStream(t)) {
                o.write('1');
            }
            return t.delete();
        } catch (Exception e) {
            return false;
        }
    }

    /** A server fermo, prima dei servizi: porta gli originali dove li vuole l'interruttore. */
    static void prepare(Stack stack, Cfg c) throws Exception {
        if (c.dcimActive()) {
            if (!canUse(c)) {
                throw new IOException("the originals are in DCIM/Immich but the app can no longer read them: grant the Storage permission again "
                    + "(Settings → Apps → Immich Server → Permissions) and restart the server");
            }
            status = "";
            return;
        }
        boolean resume = inProgress(c).exists();
        if (!c.dcimWanted() && !resume) return;
        if (!canUse(c)) {
            if (resume) {
                throw new IOException("moving the originals to DCIM is half done and the Storage permission is missing: grant it again "
                    + "(Settings → Apps → Immich Server → Permissions) and restart the server");
            }
            status = "Storage permission missing: the originals stay in the private folder. Grant it and restart the server.";
            stack.note(c, "originals in DCIM: " + status);
            return;
        }

        Util.touch(inProgress(c));
        stack.note(c, "originals in DCIM: " + (resume ? "resuming the move" : "moving the originals to shared storage"));
        // le copie temporanee per Google Foto non servono più, e dentro DCIM/Immich sarebbero file estranei per Immich
        Exporter.cleanup(c, true);
        File nomedia = new File(c.dcimUpload(), ".nomedia");
        if (!nomedia.exists()) Util.touch(nomedia);

        File[] sources = {new File(c.library, "library"), new File(c.library, "upload")};
        File[] targets = {c.dcimLibrary(), c.dcimUpload()};
        long[] total = new long[2];
        for (File s : sources) count(s, total);
        Moved n = new Moved(total[0], total[1]);
        for (int i = 0; i < sources.length; i++) move(stack, c, sources[i], targets[i], n);

        Util.touch(c.dcimMarker());
        inProgress(c).delete();
        // dopo l'avvio Immich deve portare in DCIM/Immich anche le foto già caricate (vedi afterStart)
        c.prefs.edit().putBoolean("dcim_migrate_existing", true).apply();
        status = "";
        stack.note(c, "originals in DCIM: moved " + n.files + " files (" + (n.bytes >> 20) + " MB)");
    }

    private static final class Moved {
        final long totalFiles;
        final long totalBytes;
        long files;
        long bytes;

        Moved(long totalFiles, long totalBytes) {
            this.totalFiles = totalFiles;
            this.totalBytes = totalBytes;
        }
    }

    private static void count(File f, long[] acc) {
        File[] kids = f.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) {
                count(k, acc);
            } else {
                acc[0]++;
                acc[1] += k.length();
            }
        }
    }

    /**
     * Sposta l'albero src dentro dst, file per file: copia in un file nascosto (Android non lo indicizza, Google Foto
     * non lo vede), controlla la dimensione, rinomina al nome vero, cancella l'originale. Un file già presente con la
     * stessa dimensione vuol dire giro precedente interrotto dopo la copia: si cancella solo l'originale.
     */
    private static void move(Stack stack, Cfg c, File src, File dst, Moved n) throws Exception {
        File[] kids = src.listFiles();
        if (kids == null) return;
        Util.mkdirs(dst);
        for (File k : kids) {
            if (stack.isStopping()) throw new InterruptedException();
            File out = new File(dst, k.getName());
            if (k.isDirectory()) {
                move(stack, c, k, out, n);
                k.delete(); // solo se vuota
                continue;
            }
            long size = k.length();
            if (!(out.isFile() && out.length() == size)) {
                File part = new File(dst, "." + k.getName() + ".part");
                copy(k, part);
                if (part.length() != size) {
                    part.delete();
                    throw new IOException("incomplete copy of " + k);
                }
                part.setLastModified(k.lastModified());
                if (out.exists() && !out.delete()) throw new IOException("cannot replace " + out);
                if (!part.renameTo(out)) throw new IOException("cannot rename " + part);
            }
            if (!k.delete()) throw new IOException("cannot remove " + k + " after copying it");
            n.files++;
            n.bytes += size;
            if (n.files % 20 == 0 || n.files == n.totalFiles) {
                int pct = n.totalBytes > 0 ? (int) (n.bytes * 100 / n.totalBytes) : 100;
                stack.installing(pct, "Moving the originals to DCIM: " + n.files + " of " + n.totalFiles + " files ("
                    + (n.bytes >> 20) + " of " + (n.totalBytes >> 20) + " MB)…");
            }
        }
    }

    private static void copy(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] b = new byte[1 << 20];
            int r;
            while ((r = in.read(b)) > 0) out.write(b, 0, r);
            ((FileOutputStream) out).getFD().sync();
        }
    }

    /**
     * Con Immich in funzione: le foto caricate prima (o mentre l'interruttore era spento) le sposta Immich stesso con
     * il lavoro "Migrazione modello archiviazione" (Amministrazione → Processi); lo avvia l'app con la chiave API.
     */
    static void afterStart(Cfg c) {
        if (!c.dcimActive() || !c.dcimWanted() || !c.prefs.getBoolean("dcim_migrate_existing", false)) return;
        if (ImmichApi.key(c).isEmpty()) {
            status = "To move the photos already uploaded to DCIM/Immich too: in Immich Administration → Jobs → "
                + "Storage template migration → Start (or save the API key below).";
            return;
        }
        try {
            ImmichApi.Reply r = ImmichApi.call(c, "PUT", "/jobs/storageTemplateMigration", "{\"command\":\"start\",\"force\":false}");
            if (r.ok()) {
                c.prefs.edit().putBoolean("dcim_migrate_existing", false).apply();
                status = "";
            } else {
                status = "Migration of the photos already uploaded not started: " + r.problem();
            }
        } catch (IOException e) {
            status = "Migration of the photos already uploaded not started: " + e.getMessage();
        }
    }

    /** acceso l'interruttore dopo averlo spento: le foto arrivate nel frattempo vanno portate in DCIM/Immich */
    static void onSwitch(Cfg c, boolean on) {
        c.prefs.edit().putBoolean("dcim_originals", on).putBoolean("dcim_migrate_existing", on).apply();
        status = "";
    }
}
