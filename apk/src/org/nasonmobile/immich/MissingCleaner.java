package org.nasonmobile.immich;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Pulizia delle foto "non più trovate". Ogni notte alle 3 Immich controlla quali originali registrati nel suo database
 * non esistono più sul disco (Amministrazione → Manutenzione → Report di integrità → File mancanti): succede quando
 * gli originali stanno in DCIM/Immich (DcimMode) e un'altra app li cancella, per esempio "Libera spazio" di Google Foto.
 * Una volta al giorno, dopo le 4, se ce ne sono, l'app fa quello che fa il pulsante "Elimina tutti" di quella pagina:
 * il lavoro di Immich integrity-missing-files-delete-all, che sposta quelle foto nel cestino di Immich (recuperabili
 * per 30 giorni, poi Immich le toglie da solo).
 *
 * Non tocca nulla se le cartelle degli originali non sono leggibili (permesso Memoria tolto, memoria non montata: le
 * foto sembrerebbero tutte mancanti) né se ne mancano troppe insieme (più di 20 e più del 10%): meglio guardare prima.
 */
final class MissingCleaner {
    private static final int MIN_LIMIT = 20;
    private static volatile String status = "";

    private MissingCleaner() {
    }

    static String status(Cfg c) {
        if (!c.prefs.getBoolean("missing_cleanup", false)) return "Spenta.";
        return status.isEmpty() ? "Accesa: controlla ogni giorno dopo le 4." : "Ultima: " + status;
    }

    /** dal ciclo di Stack: lavora al massimo una volta al giorno, dopo il controllo notturno di Immich */
    static void runIfDue(Stack stack, Cfg c) {
        if (!c.prefs.getBoolean("missing_cleanup", false)) return;
        Calendar now = Calendar.getInstance();
        if (now.get(Calendar.HOUR_OF_DAY) < 4) return;
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now.getTime());
        if (today.equals(c.prefs.getString("missing_cleanup_day", ""))) return;
        c.prefs.edit().putString("missing_cleanup_day", today).apply();
        runNow(stack, c);
    }

    static synchronized String runNow(Stack stack, Cfg c) {
        String when = new SimpleDateFormat("dd/MM HH:mm", Locale.ITALY).format(new Date());
        status = when + ", " + doRun(stack, c);
        stack.note(c, "pulizia delle foto senza file: " + status);
        return status;
    }

    private static String doRun(Stack stack, Cfg c) {
        if (stack.state() != Stack.State.RUNNING) return "server non in esecuzione";
        if (ImmichApi.key(c).isEmpty()) return "serve la chiave API di Immich (sezione \"Elimina l'originale anche da Immich\")";
        if (c.dcimActive() && !(readable(c.dcimLibrary()) && readable(c.dcimUpload()))) {
            return "cartelle degli originali non leggibili (permesso Memoria?): nessuna pulizia";
        }
        int missing;
        int total;
        try {
            String out = stack.query(c, "SELECT (SELECT count(*) FROM integrity_report WHERE type='missing_file' "
                + "AND \"assetId\" IS NOT NULL) || ' ' || (SELECT count(*) FROM asset WHERE \"deletedAt\" IS NULL)").trim();
            String[] p = out.split("\\s+");
            missing = Integer.parseInt(p[0]);
            total = Integer.parseInt(p[1]);
        } catch (Exception e) {
            return "non riesco a leggere il report di Immich: " + e.getMessage();
        }
        if (missing == 0) return "nessuna foto senza file";
        int limit = Math.max(MIN_LIMIT, total / 10);
        if (missing > limit) {
            return "mancano " + missing + " file su " + total + ": troppi insieme, pulizia sospesa. Controlla in Immich "
                + "(Amministrazione → Manutenzione → Report di integrità → File mancanti)";
        }
        try {
            ImmichApi.Reply r = ImmichApi.call(c, "POST", "/jobs", "{\"name\":\"integrity-missing-files-delete-all\"}");
            if (!r.ok()) return "Immich ha rifiutato la pulizia: " + r.problem();
        } catch (IOException e) {
            return "Immich non risponde: " + e.getMessage();
        }
        return missing + (missing == 1 ? " foto senza file spostata" : " foto senza file spostate") + " nel cestino di Immich";
    }

    /** cartella montata e leggibile, con il file di controllo che Immich vi tiene (.immich) */
    private static boolean readable(File d) {
        return d.list() != null && new File(d, ".immich").isFile();
    }
}
