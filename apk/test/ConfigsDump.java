package org.nasonmobile.immich;

import java.io.File;
import java.io.FileWriter;
import java.io.Writer;
import java.util.List;

/**
 * Banco di prova sul PC: scrive i file di configurazione e stampa i comandi esattamente come li
 * produce l'app (classe Configs), per riusarli in una sessione proot vera.
 *   java ConfigsDump <cartella-config> <password-db>
 *   java ConfigsDump cmd <initdb|postgres|valkey|immich|role|dbexists|dbcreate|select1> [password]
 * Un argomento per riga, separati da NUL non serve: si stampa un argomento per riga.
 */
public final class ConfigsDump {
    private static void w(File dir, String name, String content) throws Exception {
        try (Writer x = new FileWriter(new File(dir, name))) {
            x.write(content);
        }
    }

    public static void main(String[] a) throws Exception {
        if (a[0].equals("cmd")) {
            List<String> c;
            String pw = a.length > 2 ? a[2] : "x";
            switch (a[1]) {
                case "initdb": c = Configs.initdb("17"); break;
                case "postgres": c = Configs.postgresRun("17"); break;
                case "valkey": c = Configs.valkeyRun(); break;
                case "immich": c = Configs.immichRun(); break;
                case "select1": c = Configs.psql("17", "SELECT 1"); break;
                case "role": c = Configs.psql("17", Configs.roleSql(pw)); break;
                case "dbexists": c = Configs.psql("17", Configs.DB_EXISTS_SQL); break;
                case "dbcreate": c = Configs.psql("17", Configs.DB_CREATE_SQL); break;
                default: throw new IllegalArgumentException(a[1]);
            }
            for (String s : c) System.out.println(s);
            return;
        }
        File dir = new File(a[0]);
        dir.mkdirs();
        w(dir, "postgres-immich.conf", Configs.postgres("256MB"));
        w(dir, "valkey.conf", Configs.valkey());
        w(dir, "server.env", Configs.serverEnv(2283, a[1], "Europe/Rome", 4, 1536));
        System.out.println("configurazioni scritte in " + dir);
    }
}
