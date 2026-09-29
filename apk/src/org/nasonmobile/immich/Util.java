package org.nasonmobile.immich;

import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Piccole utilità di file/rete. */
final class Util {
    private Util() {
    }

    /** Implementazione delle operazioni tar con android.system.Os (supporta anche lo sticky bit). */
    static final Tar.Ops ANDROID_OPS = new Tar.Ops() {
        @Override
        public void symlink(String target, File link) throws IOException {
            try {
                Os.symlink(target, link.getPath());
            } catch (android.system.ErrnoException e) {
                throw new IOException("symlink " + link + ": " + e.getMessage(), e);
            }
        }

        @Override
        public void hardlink(File existing, File link) throws IOException {
            try {
                Os.link(existing.getPath(), link.getPath());
            } catch (android.system.ErrnoException e) {
                throw new IOException("link " + link + ": " + e.getMessage(), e);
            }
        }

        @Override
        public void chmod(File f, int mode) throws IOException {
            try {
                Os.chmod(f.getPath(), mode);
            } catch (android.system.ErrnoException e) {
                throw new IOException("chmod " + f + ": " + e.getMessage(), e);
            }
        }
    };

    static void mkdirs(File... dirs) throws IOException {
        for (File d : dirs) {
            if (!d.isDirectory() && !d.mkdirs() && !d.isDirectory()) throw new IOException("cannot create " + d);
        }
    }

    static boolean isSymlink(File f) {
        return Files.isSymbolicLink(f.toPath());
    }

    static void deleteRecursive(File f) {
        if (!isSymlink(f) && !f.exists()) return;
        if (!isSymlink(f) && f.isDirectory()) {
            f.setReadable(true, true);
            f.setWritable(true, true);
            f.setExecutable(true, true);
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) deleteRecursive(k);
            }
        }
        f.delete();
    }

    static void touch(File f) throws IOException {
        mkdirs(f.getParentFile());
        try (OutputStream os = new FileOutputStream(f)) {
            os.write('\n');
        }
    }

    static void write(File f, String content) throws IOException {
        mkdirs(f.getParentFile());
        try (OutputStream os = new FileOutputStream(f)) {
            os.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Scrive solo se il contenuto è cambiato (evita di sporcare mtime e di riscrivere inutilmente). */
    static void writeIfChanged(File f, String content) throws IOException {
        if (f.isFile() && read(f).equals(content)) return;
        write(f, content);
    }

    static String read(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static void copy(InputStream in, File dest) throws IOException {
        mkdirs(dest.getParentFile());
        try (OutputStream os = new FileOutputStream(dest)) {
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = in.read(b)) > 0) os.write(b, 0, n);
        } finally {
            in.close();
        }
    }

    /** Ultimi {@code max} byte del file, da una riga intera. */
    static String tail(File f, int max) {
        if (!f.isFile()) return "";
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long len = raf.length();
            long start = Math.max(0, len - max);
            raf.seek(start);
            byte[] b = new byte[(int) (len - start)];
            raf.readFully(b);
            String s = new String(b, StandardCharsets.UTF_8);
            if (start > 0) {
                int nl = s.indexOf('\n');
                if (nl >= 0) s = s.substring(nl + 1);
            }
            return s;
        } catch (IOException e) {
            return "(log not readable: " + e.getMessage() + ")";
        }
    }

    /** Se il log supera la soglia lo ruota (una sola copia). */
    static void rotate(File log, long maxBytes) {
        if (log.isFile() && log.length() > maxBytes) {
            File old = new File(log.getPath() + ".1");
            old.delete();
            log.renameTo(old);
        }
    }

    /** Indirizzi IPv4 non-loopback, "wlan0 192.168.1.20". */
    static List<String> ipv4() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) out.add(ni.getName() + " " + a.getHostAddress());
                }
            }
        } catch (Exception ignored) {
            // nessuna rete
        }
        return out;
    }

    static int pidOf(Process p) {
        try {
            return (int) p.pid(); // Android 13+
        } catch (Throwable ignored) {
            // metodo assente o non implementato: si prova con la reflection
        }
        try {
            java.lang.reflect.Field f = p.getClass().getDeclaredField("pid");
            f.setAccessible(true);
            return f.getInt(p);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** pid del processo padre (dentro proot è il proot che lo traccia), da /proc/PID/status */
    static int ppidOf(int pid) {
        try {
            for (String l : read(new File("/proc/" + pid + "/status")).split("\n")) {
                if (l.startsWith("PPid:")) return Integer.parseInt(l.substring(5).trim());
            }
        } catch (Exception ignored) {
            // processo già terminato
        }
        return -1;
    }

    /** Il processo esiste e la sua riga di comando contiene {@code expect}? (evita di segnalare un pid riusato) */
    static boolean pidMatches(int pid, String expect) {
        if (pid <= 1) return false;
        try {
            String cmd = read(new File("/proc/" + pid + "/cmdline")).replace('\0', ' ');
            return cmd.contains(expect);
        } catch (IOException e) {
            return false;
        }
    }

    static int readPid(File pidFile) {
        try {
            String s = read(pidFile);
            int nl = s.indexOf('\n');
            return Integer.parseInt((nl >= 0 ? s.substring(0, nl) : s).trim());
        } catch (Exception e) {
            return -1;
        }
    }

    static void signal(int pid, int sig) {
        try {
            Os.kill(pid, sig);
        } catch (android.system.ErrnoException ignored) {
            // già terminato
        }
    }

    static final int SIGINT = OsConstants.SIGINT;
    static final int SIGTERM = OsConstants.SIGTERM;
    static final int SIGQUIT = OsConstants.SIGQUIT;
    /** proot lo gestisce come SIGQUIT (uccide i figli ed esce), ma non è bloccato nella maschera ereditata dall'app */
    static final int SIGABRT = OsConstants.SIGABRT;
}
