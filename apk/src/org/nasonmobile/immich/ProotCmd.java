package org.nasonmobile.immich;

import android.system.Os;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Costruisce le righe di comando di proot per eseguire programmi dentro la userland Debian.
 * Ricalca quello che fa proot-distro su Termux (opzioni di proot, bind, finti file /proc),
 * senza usarlo: qui c'è solo il proot incorporato nell'APK.
 */
final class ProotCmd {
    static final String KERNEL_RELEASE = "6.17.0-immich";
    static final String KERNEL_VERSION = "#1 SMP PREEMPT_DYNAMIC Fri, 10 Oct 2025 00:00:00 +0000";
    static final String GUEST_PATH = Configs.GUEST_PATH;

    static final class User {
        int uid;
        int gid;
        String home = "/root";
    }

    private final Cfg c;

    ProotCmd(Cfg c) {
        this.c = c;
    }

    File proot() {
        return new File(c.nativeDir(), "libproot.so");
    }

    File loader() {
        return new File(c.nativeDir(), "libproot-loader.so");
    }

    /** uid/gid/home di un utente della userland, da etc/passwd */
    User user(String name) throws IOException {
        User u = new User();
        if ("root".equals(name)) return u;
        try (BufferedReader r = new BufferedReader(new FileReader(new File(c.rootfs, "etc/passwd")))) {
            String l;
            while ((l = r.readLine()) != null) {
                String[] p = l.split(":");
                if (p.length >= 6 && p[0].equals(name)) {
                    u.uid = Integer.parseInt(p[2]);
                    u.gid = Integer.parseInt(p[3]);
                    u.home = p[5];
                    return u;
                }
            }
        }
        throw new IOException("l'utente '" + name + "' non esiste nel sistema Debian (etc/passwd)");
    }

    /**
     * @param user  utente (finto) con cui gira il programma
     * @param binds cartelle del telefono da rendere visibili nel sistema Debian: host -> percorso nel guest
     * @param env   variabili d'ambiente aggiuntive per il programma
     * @param argv  programma e argomenti (percorsi del guest)
     */
    ProcessBuilder builder(String user, Map<File, String> binds, Map<String, String> env, List<String> argv)
        throws IOException {
        User u = user(user);
        List<String> a = new ArrayList<>();
        a.add(proot().getPath());
        a.add("--kill-on-exit");
        a.add("--link2symlink");
        a.add("--sysvipc"); // PostgreSQL vuole la memoria condivisa SysV, che Android non ha
        a.add("-L");
        a.add("--kernel-release=\\Linux\\localhost\\" + KERNEL_RELEASE + "\\" + KERNEL_VERSION
            + "\\aarch64\\localdomain\\-1\\");
        a.add("--change-id=" + u.uid + ":" + u.gid);
        a.add("--rootfs=" + c.rootfs.getPath());
        a.add("--cwd=/");
        a.add("--bind=/dev");
        a.add("--bind=/proc");
        a.add("--bind=/sys");
        a.add("--bind=/dev/urandom:/dev/random");
        if (!lexists("/dev/fd")) a.add("--bind=/proc/self/fd:/dev/fd");
        String[] std = {"stdin", "stdout", "stderr"};
        for (int i = 0; i < 3; i++) {
            if (!lexists("/dev/" + std[i]) && new File("/proc/self/fd/" + i).exists()) {
                a.add("--bind=/proc/self/fd/" + i + ":/dev/" + std[i]);
            }
        }
        for (Map.Entry<File, String> e : fakeProc().entrySet()) {
            a.add("--bind=" + e.getKey().getPath() + ":" + e.getValue());
        }
        a.add("--bind=" + c.shm.getPath() + ":/dev/shm");
        for (Map.Entry<File, String> e : binds.entrySet()) {
            a.add("--bind=" + e.getKey().getPath() + ":" + e.getValue());
        }

        // programma nel guest, con ambiente pulito (come proot-distro: env -i)
        a.add("/usr/bin/env");
        a.add("-i");
        Map<String, String> ge = new LinkedHashMap<>();
        ge.put("HOME", u.home);
        ge.put("USER", user);
        ge.put("LOGNAME", user);
        ge.put("SHELL", "/bin/bash");
        ge.put("PATH", GUEST_PATH);
        ge.put("LANG", "C.UTF-8");
        ge.put("TMPDIR", "/tmp");
        ge.put("TZ", c.tz());
        ge.putAll(env);
        for (Map.Entry<String, String> e : ge.entrySet()) a.add(e.getKey() + "=" + e.getValue());
        a.addAll(argv);

        ProcessBuilder pb = new ProcessBuilder(a);
        Map<String, String> pe = pb.environment();
        pe.put("PROOT_LOADER", loader().getPath());
        pe.put("PROOT_TMP_DIR", c.prootTmp.getPath());
        String ld = pe.get("LD_LIBRARY_PATH");
        pe.put("LD_LIBRARY_PATH", c.nativeDir().getPath() + (ld == null || ld.isEmpty() ? "" : ":" + ld));
        // La memoria condivisa SysV la emula proot stesso: libandroid-shmem va in deadlock quando lo stesso
        // processo chiede due volte la stessa chiave (succede subito, nelle prove di initdb).
        pe.put("PROOT_DONT_SHARE_LIBANDROID_SHMEM", "1");
        if (c.noSeccomp()) pe.put("PROOT_NO_SECCOMP", "1");
        return pb;
    }

    private static boolean lexists(String p) {
        return Files.exists(Paths.get(p), LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Android nega a un'app la lettura di alcuni file /proc (stat, loadavg, uptime, vmstat, version):
     * li sostituiamo con copie plausibili, come fa proot-distro.
     */
    private Map<File, String> fakeProc() throws IOException {
        Util.mkdirs(c.fake, c.shm, c.prootTmp);
        try {
            Os.chmod(c.shm.getPath(), 01777);
        } catch (android.system.ErrnoException ignored) {
            // non fatale
        }
        int cpus = Math.max(1, Runtime.getRuntime().availableProcessors());
        StringBuilder stat = new StringBuilder("cpu  1957 0 2877 93280 262 342 254 87 0 0\n");
        for (int i = 0; i < cpus; i++) stat.append("cpu").append(i).append(" 245 0 360 11660 32 42 31 10 0 0\n");
        stat.append("intr 127541 0\nctxt 140223\nbtime ").append(System.currentTimeMillis() / 1000 - 3600)
            .append("\nprocesses 772\nprocs_running 2\nprocs_blocked 0\nsoftirq 75663 0 5903 6 25375 10774 0 243 11685 0 21677\n");

        String vmstat = "nr_free_pages 1743136\nnr_inactive_anon 179281\nnr_active_anon 7183\nnr_inactive_file 22858\n"
            + "nr_active_file 51328\nnr_unevictable 642\nnr_dirty 0\nnr_writeback 0\nnr_file_pages 253569\n"
            + "nr_anon_pages 7723\nnr_mapped 8905\nnr_shmem 178741\npgpgin 890508\npgpgout 0\npswpin 0\npswpout 0\n"
            + "pgfault 176973\npgmajfault 488\n";

        Map<File, String> m = new LinkedHashMap<>();
        fake(m, "proc/loadavg", "/proc/loadavg", "0.12 0.07 0.02 2/165 765\n");
        fake(m, "proc/stat", "/proc/stat", stat.toString());
        fake(m, "proc/uptime", "/proc/uptime", "124.08 932.80\n");
        fake(m, "proc/version", "/proc/version",
            "Linux version " + KERNEL_RELEASE + " (immich@localhost) (gcc (Debian 14.2.0-19) 14.2.0) " + KERNEL_VERSION + "\n");
        fake(m, "proc/vmstat", "/proc/vmstat", vmstat);
        fake(m, "proc/sys/kernel/cap_last_cap", "/proc/sys/kernel/cap_last_cap", "40\n");
        fake(m, "proc/sys/fs/inotify/max_user_watches", "/proc/sys/fs/inotify/max_user_watches", "4096\n");
        fake(m, "proc/sys/kernel/overflowuid", "/proc/sys/kernel/overflowuid", "65534\n");
        fake(m, "proc/sys/kernel/overflowgid", "/proc/sys/kernel/overflowgid", "65534\n");
        return m;
    }

    private void fake(Map<File, String> m, String rel, String guestPath, String content) throws IOException {
        File f = new File(c.fake, rel);
        Util.writeIfChanged(f, content);
        m.put(f, guestPath);
    }
}
