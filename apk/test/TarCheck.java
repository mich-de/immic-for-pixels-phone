package org.nasonmobile.immich;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * Prova sul PC dell'estrattore tar con archivi veri:
 *   java TarCheck <archivio.tar.gz> <cartella-destinazione> <strip>
 * Stampa il conteggio per tipo, da confrontare con "tar -tvf".
 */
public final class TarCheck {
    public static void main(String[] a) throws Exception {
        File src = new File(a[0]);
        File dest = new File(a[1]);
        int strip = Integer.parseInt(a[2]);
        long t0 = System.currentTimeMillis();
        try (InputStream in = new FileInputStream(src)) {
            Tar.Result r = Tar.extract(in, dest, strip, Tar.NIO, null);
            System.out.println("estratto in " + (System.currentTimeMillis() - t0) + " ms: " + r);
        }
    }
}
