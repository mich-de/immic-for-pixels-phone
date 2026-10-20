package org.nasonmobile.immich;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Accesso ai pacchetti grossi (rootfs Debian, pacchetto Immich). Un file con lo stesso nome nella
 * cartella esterna dell'app ha la precedenza sull'asset dentro l'APK: permette di aggiornare il
 * pacchetto con "adb push" senza ricompilare l'APK.
 */
final class Assets {
    private Assets() {
    }

    static File external(Context c, String name) {
        File d = c.getExternalFilesDir(null);
        return d == null ? null : new File(d, name);
    }

    static boolean exists(Context c, String name) {
        File f = external(c, name);
        if (f != null && f.isFile()) return true;
        try (InputStream in = c.getAssets().open(name, AssetManager.ACCESS_STREAMING)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    static InputStream open(Context c, String name) throws IOException {
        File f = external(c, name);
        if (f != null && f.isFile()) return new FileInputStream(f);
        try {
            return c.getAssets().open(name, AssetManager.ACCESS_STREAMING);
        } catch (FileNotFoundException e) {
            throw new FileNotFoundException("missing " + name + " (neither in the APK nor in " + f + ")");
        }
    }

    /** dimensione in byte, o -1 se non nota (asset compresso) */
    static long size(Context c, String name) {
        File f = external(c, name);
        if (f != null && f.isFile()) return f.length();
        try (AssetFileDescriptor fd = c.getAssets().openFd(name)) {
            return fd.getLength();
        } catch (IOException e) {
            return -1;
        }
    }

    static String text(Context c, String name) throws IOException {
        try (InputStream in = open(c, name)) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return new String(bo.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
