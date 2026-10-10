package org.nasonmobile.immich;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Riavvia il server: all'accensione del telefono se l'utente ha attivato l'avvio automatico, e dopo un aggiornamento
 * dell'app (che la ferma di forza) se il server era in funzione.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Cfg c = new Cfg(context);
        String a = intent.getAction();
        boolean start = Intent.ACTION_BOOT_COMPLETED.equals(a) ? c.autostart()
            : Intent.ACTION_MY_PACKAGE_REPLACED.equals(a) && c.shouldRun();
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            // aggiornata: via l'avviso dell'aggiornamento, e il prossimo controllo si fa subito (vedi Updater)
            Updater.cancelNotification(context);
            c.prefs.edit().remove("update_checked_at").apply();
        }
        if (start) {
            context.startForegroundService(new Intent(context, ServerService.class).setAction(ServerService.ACTION_START));
        }
    }
}
