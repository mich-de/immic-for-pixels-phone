package org.nasonmobile.immich;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Configurazioni e comandi dei tre servizi, senza dipendenze da Android: li usa l'app e li usa anche
 * il banco di prova sul PC (apk/test), così quello che si prova è esattamente quello che gira.
 */
final class Configs {
    static final String GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";

    private Configs() {
    }

    static String pgBin(String pgMajor) {
        return "/usr/lib/postgresql/" + pgMajor + "/bin";
    }

    /** impostazioni PostgreSQL, incluse da postgresql.conf */
    static String postgres(String sharedBuffers) {
        return "listen_addresses = '127.0.0.1'\n"
            + "port = 5432\n"
            + "unix_socket_directories = '/run/immich'\n"
            + "max_connections = 40\n"
            + "shared_buffers = " + sharedBuffers + "\n"
            + "effective_cache_size = 1GB\n"
            + "work_mem = 8MB\n"
            + "maintenance_work_mem = 128MB\n"
            + "max_wal_size = 2GB\n"
            + "wal_compression = on\n"
            + "checkpoint_timeout = 15min\n"
            + "random_page_cost = 1.1\n"
            + "effective_io_concurrency = 64\n"
            + "dynamic_shared_memory_type = mmap\n"
            + "shared_preload_libraries = 'vchord.so'\n"
            + "search_path = '\"$user\", public'\n"
            + "autovacuum_vacuum_scale_factor = 0.1\n"
            + "autovacuum_analyze_scale_factor = 0.05\n"
            + "autovacuum_vacuum_cost_limit = 1000\n";
    }

    static final String POSTGRES_INCLUDE = "\ninclude_if_exists = '/config/postgres-immich.conf'\n";

    static String valkey() {
        return "bind 127.0.0.1\n"
            + "port 6379\n"
            + "daemonize no\n"
            + "dir /valkey\n"
            + "pidfile /run/immich/valkey.pid\n"
            + "save 900 1\n"
            + "save 300 100\n"
            // Qui sta la coda dei lavori di Immich. Android chiude l'app anche di forza (pure "adb install -r"): con i
            // soli snapshot si perdevano i lavori degli ultimi minuti, per esempio i metadati di un video appena caricato.
            + "appendonly yes\n"
            + "appendfsync everysec\n"
            + "maxmemory 256mb\n"
            + "maxmemory-policy noeviction\n";
    }

    /** variabili d'ambiente del server Immich (file "sourced" dallo script di avvio) */
    static String serverEnv(int port, String dbPassword, String tz, int threads, int heapMb) {
        String[][] kv = {
            {"NODE_ENV", "production"},
            {"IMMICH_ENV", "production"},
            {"IMMICH_BUILD_DATA", "/opt/immich/build"},
            {"IMMICH_HOST", "0.0.0.0"},
            {"IMMICH_PORT", String.valueOf(port)},
            {"DB_HOSTNAME", "127.0.0.1"},
            {"DB_PORT", "5432"},
            {"DB_USERNAME", "immich"},
            {"DB_PASSWORD", dbPassword},
            {"DB_DATABASE_NAME", "immich"},
            {"REDIS_HOSTNAME", "127.0.0.1"},
            {"REDIS_PORT", "6379"},
            {"TZ", tz},
            {"UV_THREADPOOL_SIZE", String.valueOf(threads)},
            {"NODE_OPTIONS", "--max-old-space-size=" + heapMb},
            {"PATH", "/opt/immich/node/bin:" + GUEST_PATH},
        };
        StringBuilder sb = new StringBuilder();
        for (String[] e : kv) sb.append(e[0]).append("='").append(e[1]).append("'\n");
        return sb.toString();
    }

    static List<String> initdb(String pgMajor) {
        return Arrays.asList(pgBin(pgMajor) + "/initdb", "-D", "/pgdata", "-U", "postgres", "--encoding=UTF8",
            "--locale=en_US.UTF-8", "--data-checksums", "--auth-local=trust", "--auth-host=scram-sha-256");
    }

    static List<String> postgresRun(String pgMajor) {
        return Arrays.asList(pgBin(pgMajor) + "/postgres", "-D", "/pgdata", "-c", "config_file=/pgdata/postgresql.conf");
    }

    static List<String> valkeyRun() {
        return Arrays.asList("/usr/bin/valkey-server", "/config/valkey.conf");
    }

    static List<String> immichRun() {
        return Arrays.asList("/bin/bash", "-c",
            "echo $$ > /run/immich/immich.pid; set -a; . /config/server.env; set +a; "
                + "cd /opt/immich/server && exec nice -n 5 /opt/immich/node/bin/node dist/main.js");
    }

    /** psql verso il socket Unix, come utente postgres (autenticazione trust), sul database "postgres" */
    static List<String> psql(String pgMajor, String statement) {
        return psql(pgMajor, "postgres", statement);
    }

    static List<String> psql(String pgMajor, String database, String statement) {
        List<String> c = new ArrayList<>(Arrays.asList(pgBin(pgMajor) + "/psql", "-h", "/run/immich", "-U", "postgres",
            "-d", database, "-X", "-q", "-v", "ON_ERROR_STOP=1", "-tA"));
        c.add("-c");
        c.add(statement);
        return c;
    }

    static String roleSql(String password) {
        return "DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='immich') THEN "
            + "CREATE ROLE immich LOGIN SUPERUSER PASSWORD '" + password + "'; "
            + "ELSE ALTER ROLE immich WITH LOGIN SUPERUSER PASSWORD '" + password + "'; END IF; END $$;";
    }

    /**
     * Disattiva il machine learning (sul telefono è troppo pesante e il servizio non c'è): lo si scrive nella
     * configurazione di sistema di Immich, unendola a quella esistente. Si applica al riavvio del server.
     */
    static final String ML_OFF_SQL =
        "INSERT INTO system_metadata (key, value) VALUES ('system-config', '{\"machineLearning\":{\"enabled\":false}}'::jsonb) "
            + "ON CONFLICT (key) DO UPDATE SET value = system_metadata.value || jsonb_build_object('machineLearning', "
            + "COALESCE(system_metadata.value->'machineLearning', '{}'::jsonb) || '{\"enabled\":false}'::jsonb)";

    static final String DB_EXISTS_SQL = "SELECT 1 FROM pg_database WHERE datname='immich'";
    static final String DB_CREATE_SQL = "CREATE DATABASE immich OWNER immich";
}
