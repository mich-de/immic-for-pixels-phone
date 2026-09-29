package org.nasonmobile.immich;

import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Copia gli originali di Immich (quelli che stanno in {@code files/immich/library/upload}, nella memoria
 * privata dell'app) in una cartella normale del telefono, visibile con qualunque app "Gestione file" o
 * collegando il telefono al PC: {@link #DIR}. Serve come backup "sul telefono", in aggiunta a quello
 * documentato nel README (via adb, sul PC) e alla copia temporanea in Galleria (Exporter), che invece
 * l'app elimina da sola dopo un po'.
 *
 * Manuale (nessuno schedulario): si avvia dal pulsante "Copia ora sul telefono". È incrementale e si può
 * interrompere e rilanciare: salta i file già presenti a destinazione con la stessa dimensione, non copia
 * di nuovo tutto da capo. Non elimina mai nulla a destinazione, nemmeno se l'originale non c'è più su
 * Immich: un backup non deve cancellare da solo.
 */
final class Backup {
    /** dentro la cartella pubblica del telefono, es. /storage/emulated/0/ImmichBackup */
    static final String DIR = "ImmichBackup";

    private static volatile String status = "";
    private static volatile boolean running;
    private static volatile boolean stopRequested;

    private Backup() {
    }

    static String status() {
        return status.isEmpty() ? "Not started yet." : status;
    }

    static boolean running() {
        return running;
    }

    /** Chiesto dal pulsante "Ferma": il giro in corso si interrompe al file successivo, senza lasciare copie a metà. */
    static void requestStop() {
        stopRequested = true;
    }

    static File destDir() {
        return new File(Environment.getExternalStorageDirectory(), DIR);
    }

    /** Un giro completo: cammina tra gli originali e copia quello che manca o è cambiato. Non lancia eccezioni. */
    static synchronized void runOnce(Cfg c) {
        if (running) return;
        running = true;
        stopRequested = false;
        long t0 = System.currentTimeMillis();
        Counts n = new Counts();
        try {
            File dst = destDir();
            // con gli originali in DCIM (DcimMode) stanno in due posti: arrivati di recente e già sistemati da Immich
            File[][] pairs = c.dcimActive()
                ? new File[][]{{c.dcimUpload(), dst}, {c.dcimLibrary(), new File(dst, "library")}}
                : new File[][]{{new File(c.library, "upload"), dst}};
            if (!pairs[0][0].isDirectory() && !pairs[pairs.length - 1][0].isDirectory()) {
                status = "Nothing to copy: the Immich library is empty.";
                return;
            }
            Util.mkdirs(dst);
            status = "Preparing…";
            for (File[] p : pairs) {
                if (p[0].isDirectory() && !stopRequested && !n.lowSpace) walk(p[0], p[1], n);
            }
            long secs = Math.max(1, (System.currentTimeMillis() - t0) / 1000);
            if (stopRequested) {
                status = "Stopped: " + n.copied + " files copied (" + mb(n.bytes) + "), " + n.skipped + " already there. "
                    + "Press again to continue from where it stopped.";
            } else if (n.lowSpace) {
                status = "Paused: the phone is almost out of space. " + n.copied + " files copied (" + mb(n.bytes) + "). "
                    + "Free some space and press again to continue.";
            } else {
                status = "Done in " + secs + " s: " + n.copied + " files copied (" + mb(n.bytes) + "), " + n.skipped
                    + " already there" + (n.failed > 0 ? ", " + n.failed + " failed (see the log)" : "") + ". In "
                    + destDir().getPath() + ".";
            }
        } catch (Exception e) {
            Log.w(Cfg.TAG, "phone backup: " + e);
            status = "Error: " + e.getMessage() + " (" + n.copied + " files copied before the error)";
        } finally {
            running = false;
        }
    }

    private static final class Counts {
        int copied, skipped, failed;
        long bytes;
        boolean lowSpace;
    }

    private static void walk(File src, File dst, Counts n) throws IOException {
        File[] kids = src.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (stopRequested || n.lowSpace) return;
            File out = new File(dst, k.getName());
            if (k.isDirectory()) {
                Util.mkdirs(out);
                walk(k, out, n);
            } else if (k.isFile()) {
                if (Health.spaceLevel(dst) >= 1) { // critico: meglio fermarsi che riempire il telefono
                    n.lowSpace = true;
                    return;
                }
                if (out.isFile() && out.length() == k.length()) {
                    n.skipped++;
                    continue;
                }
                status = "Copying file " + (n.copied + n.skipped + 1) + " — " + k.getName();
                try {
                    copyFile(k, out);
                    n.copied++;
                    n.bytes += k.length();
                } catch (IOException e) {
                    Log.w(Cfg.TAG, "backup, file not copied (" + k + "): " + e);
                    n.failed++;
                }
            }
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        File tmp = new File(dst.getPath() + ".part");
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(tmp)) {
            byte[] b = new byte[1 << 16];
            int r;
            while ((r = in.read(b)) > 0) out.write(b, 0, r);
        }
        if (!tmp.renameTo(dst)) {
            tmp.delete();
            throw new IOException("cannot move " + tmp + " to " + dst);
        }
    }

    private static String mb(long bytes) {
        return bytes >= (1L << 30) ? String.format(java.util.Locale.US, "%.1f GB", bytes / (double) (1L << 30))
            : (bytes >> 20) + " MB";
    }
}
