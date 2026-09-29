package org.nasonmobile.immich;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Estrattore tar.gz minimale, senza dipendenze (ustar, GNU long names, pax).
 * Serve perché Android non ha un tar affidabile e i nostri archivi contengono
 * migliaia di symlink e hard link (node_modules di pnpm, rootfs Debian).
 */
final class Tar {

    /** Operazioni che dipendono dalla piattaforma (su Android: android.system.Os). */
    interface Ops {
        void symlink(String target, File link) throws IOException;

        void hardlink(File existing, File link) throws IOException;

        void chmod(File f, int mode) throws IOException;
    }

    interface Progress {
        /** byte compressi letti finora */
        void bytes(long done);
    }

    /** Implementazione con java.nio, usata dai test sul PC. */
    static final Ops NIO = new Ops() {
        @Override
        public void symlink(String target, File link) throws IOException {
            Files.createSymbolicLink(link.toPath(), Paths.get(target));
        }

        @Override
        public void hardlink(File existing, File link) throws IOException {
            Files.createLink(link.toPath(), existing.toPath());
        }

        @Override
        public void chmod(File f, int mode) throws IOException {
            Set<PosixFilePermission> p = new HashSet<>();
            PosixFilePermission[] all = {
                PosixFilePermission.OTHERS_EXECUTE, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_READ,
                PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_READ,
                PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_READ};
            for (int i = 0; i < 9; i++) {
                if ((mode & (1 << i)) != 0) p.add(all[i]);
            }
            Files.setPosixFilePermissions(f.toPath(), p);
        }
    };

    static final class Result {
        long files, dirs, symlinks, hardlinks, hardlinksCopied, skipped;

        @Override
        public String toString() {
            return files + " file, " + dirs + " cartelle, " + symlinks + " symlink, " + hardlinks + " hardlink ("
                + hardlinksCopied + " copiati), " + skipped + " ignorati";
        }
    }

    private static final int BLOCK = 512;

    private Tar() {
    }

    /**
     * Estrae un .tar.gz in {@code dest}, togliendo i primi {@code strip} componenti dei percorsi.
     */
    static Result extract(InputStream raw, File dest, int strip, Ops ops, final Progress prog) throws IOException {
        final long[] read = {0};
        InputStream counting = new FilterInputStream(raw) {
            @Override
            public int read() throws IOException {
                int b = super.read();
                if (b >= 0) tick(1);
                return b;
            }

            @Override
            public int read(byte[] b, int o, int l) throws IOException {
                int n = super.read(b, o, l);
                if (n > 0) tick(n);
                return n;
            }

            private void tick(int n) {
                read[0] += n;
                if (prog != null) prog.bytes(read[0]);
            }
        };
        InputStream in = new GZIPInputStream(new BufferedInputStream(counting, 1 << 16), 1 << 16);

        Result res = new Result();
        Map<File, Integer> dirModes = new LinkedHashMap<>();
        Set<String> madeDirs = new HashSet<>();
        byte[] hdr = new byte[BLOCK];
        byte[] buf = new byte[1 << 16];
        String longName = null;
        String longLink = null;
        Map<String, String> pax = null;

        while (readBlock(in, hdr)) {
            if (isZero(hdr)) break;

            long size = number(hdr, 124, 12);
            int mode = (int) number(hdr, 100, 8);
            char type = hdr[156] == 0 ? '0' : (char) hdr[156];

            if (type == 'L' || type == 'K') { // nome/link lungo GNU
                String s = new String(readBytes(in, size), StandardCharsets.UTF_8);
                s = trimNul(s);
                if (type == 'L') longName = s;
                else longLink = s;
                skipPadding(in, size);
                continue;
            }
            if (type == 'x') { // header pax per il prossimo entry
                pax = parsePax(readBytes(in, size));
                skipPadding(in, size);
                continue;
            }
            if (type == 'g') { // header pax globale: ignorato
                skipFully(in, size + padding(size));
                continue;
            }

            String name = cstr(hdr, 0, 100);
            String link = cstr(hdr, 157, 100);
            boolean ustar = hdr[257] == 'u' && hdr[258] == 's' && hdr[259] == 't' && hdr[260] == 'a' && hdr[261] == 'r';
            if (ustar) {
                String prefix = cstr(hdr, 345, 155);
                if (!prefix.isEmpty()) name = prefix + "/" + name;
            }
            if (longName != null) name = longName;
            if (longLink != null) link = longLink;
            if (pax != null) {
                if (pax.containsKey("path")) name = pax.get("path");
                if (pax.containsKey("linkpath")) link = pax.get("linkpath");
                if (pax.containsKey("size")) size = Long.parseLong(pax.get("size"));
            }
            longName = null;
            longLink = null;
            pax = null;

            String rel = map(name, strip);
            if (rel == null) { // la cartella radice stessa
                skipFully(in, size + padding(size));
                continue;
            }
            File f = new File(dest, rel);

            switch (type) {
                case '5': // cartella
                    mkdirs(f, madeDirs);
                    dirModes.put(f, mode);
                    res.dirs++;
                    break;
                case '0':
                case '7': // file regolare
                    mkdirs(f.getParentFile(), madeDirs);
                    deleteIfExists(f);
                    try (OutputStream os = new BufferedOutputStream(new FileOutputStream(f), 1 << 16)) {
                        long left = size;
                        while (left > 0) {
                            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                            if (n < 0) throw new IOException("tar troncato: " + rel);
                            os.write(buf, 0, n);
                            left -= n;
                        }
                    }
                    ops.chmod(f, (mode & 07777) | 0600);
                    skipFully(in, padding(size));
                    res.files++;
                    break;
                case '2': // symlink
                    mkdirs(f.getParentFile(), madeDirs);
                    deleteIfExists(f);
                    ops.symlink(link, f);
                    res.symlinks++;
                    skipFully(in, size + padding(size));
                    break;
                case '1': { // hard link
                    mkdirs(f.getParentFile(), madeDirs);
                    deleteIfExists(f);
                    String targetRel = map(link, strip);
                    if (targetRel == null) throw new IOException("hardlink senza destinazione: " + rel);
                    File target = new File(dest, targetRel);
                    try {
                        ops.hardlink(target, f);
                    } catch (IOException e) { // Android può vietare link(): copia il contenuto
                        Files.copy(target.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        ops.chmod(f, (mode & 07777) | 0600);
                        res.hardlinksCopied++;
                    }
                    res.hardlinks++;
                    skipFully(in, size + padding(size));
                    break;
                }
                default: // device, fifo, ...: non servono (e non si possono creare)
                    res.skipped++;
                    skipFully(in, size + padding(size));
                    break;
            }
        }

        // i permessi delle cartelle vanno applicati alla fine (dal basso), altrimenti
        // una cartella 0555 impedirebbe di scriverci dentro
        ArrayList<Map.Entry<File, Integer>> list = new ArrayList<>(dirModes.entrySet());
        for (int i = list.size() - 1; i >= 0; i--) {
            ops.chmod(list.get(i).getKey(), (list.get(i).getValue() & 07777) | 0700);
        }
        // svuota il resto dello stream (padding di fine archivio) per contare tutti i byte
        while (in.read(buf) >= 0) {
            // niente
        }
        return res;
    }

    // ---------------------------------------------------------------- utilità

    private static boolean readBlock(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) {
                if (off == 0) return false;
                throw new IOException("tar troncato (blocco parziale)");
            }
            off += n;
        }
        return true;
    }

    private static byte[] readBytes(InputStream in, long size) throws IOException {
        if (size > 16 * 1024 * 1024) throw new IOException("header tar troppo grande");
        byte[] b = new byte[(int) size];
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) throw new IOException("tar troncato");
            off += n;
        }
        return b;
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        byte[] tmp = null;
        while (n > 0) {
            long s = in.skip(n);
            if (s <= 0) {
                if (tmp == null) tmp = new byte[8192];
                int r = in.read(tmp, 0, (int) Math.min(tmp.length, n));
                if (r < 0) throw new IOException("tar troncato");
                s = r;
            }
            n -= s;
        }
    }

    private static long padding(long size) {
        return (BLOCK - (size % BLOCK)) % BLOCK;
    }

    private static void skipPadding(InputStream in, long size) throws IOException {
        skipFully(in, padding(size));
    }

    private static boolean isZero(byte[] b) {
        for (byte x : b) {
            if (x != 0) return false;
        }
        return true;
    }

    private static String cstr(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }

    private static String trimNul(String s) {
        int i = s.indexOf('\0');
        return i >= 0 ? s.substring(0, i) : s;
    }

    /** numero ottale (o base-256 GNU) di un campo dell'header */
    private static long number(byte[] b, int off, int len) {
        if ((b[off] & 0x80) != 0) {
            long v = b[off] & 0x7f;
            for (int i = 1; i < len; i++) v = (v << 8) | (b[off + i] & 0xff);
            return v;
        }
        long v = 0;
        int i = off;
        int end = off + len;
        while (i < end && (b[i] == ' ' || b[i] == 0)) i++;
        for (; i < end && b[i] >= '0' && b[i] <= '7'; i++) v = (v << 3) + (b[i] - '0');
        return v;
    }

    /** record pax: "<len> <key>=<value>\n" ripetuti */
    private static Map<String, String> parsePax(byte[] data) {
        Map<String, String> m = new HashMap<>();
        int pos = 0;
        while (pos < data.length) {
            int sp = pos;
            while (sp < data.length && data[sp] != ' ') sp++;
            if (sp >= data.length) break;
            int len;
            try {
                len = Integer.parseInt(new String(data, pos, sp - pos, StandardCharsets.US_ASCII));
            } catch (NumberFormatException e) {
                break;
            }
            if (len <= 0 || pos + len > data.length) break;
            String rec = new String(data, sp + 1, pos + len - sp - 2, StandardCharsets.UTF_8); // senza '\n'
            int eq = rec.indexOf('=');
            if (eq > 0) m.put(rec.substring(0, eq), rec.substring(eq + 1));
            pos += len;
        }
        return m;
    }

    /** toglie "strip" componenti, scarta "." e rifiuta ".." */
    private static String map(String name, int strip) throws IOException {
        ArrayList<String> out = new ArrayList<>();
        int skipped = 0;
        for (String p : name.split("/")) {
            if (p.isEmpty() || p.equals(".")) continue;
            if (p.equals("..")) throw new IOException("percorso non sicuro nel tar: " + name);
            if (skipped < strip) {
                skipped++;
                continue;
            }
            out.add(p);
        }
        return out.isEmpty() ? null : String.join("/", out);
    }

    private static void mkdirs(File d, Set<String> made) throws IOException {
        if (d == null) return;
        String key = d.getPath();
        if (made.contains(key)) return;
        if (!d.isDirectory() && !d.mkdirs() && !d.isDirectory()) throw new IOException("impossibile creare " + d);
        made.add(key);
    }

    private static void deleteIfExists(File f) throws IOException {
        // Files.deleteIfExists elimina il symlink stesso, non ciò a cui punta
        Files.deleteIfExists(f.toPath());
    }
}
