package org.nasonmobile.immich;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

/**
 * Un telefono usato come server resta in carica H24: tenerlo sempre al 100% stressa la batteria. Android
 * non lascia a un'app normale (senza root) il controllo della ricarica: né i Pixel né i telefoni con
 * Android "di serie" hanno un'API pubblica per fermare o riavviare la carica dall'esterno. Quello che
 * un'app PUÒ fare è accorgersene e avvisare: un promemoria per scollegare sopra una soglia e per
 * ricollegare sotto un'altra, in modo da tenere la batteria in un giro (per esempio) 30-80% invece che
 * sempre al 100%. Lo scollegare/ricollegare resta manuale, fatto dalla persona.
 */
final class BatteryGuard {
    private static final String CHANNEL = "battery";
    private static final int NID = 2;

    private static final int NONE = 0, HIGH = 1, LOW = 2;
    private static volatile int lastKind = NONE;

    private BatteryGuard() {
    }

    /** letto una volta, comodo per mostrare lo stato attuale nel menu senza registrare un receiver */
    static Intent current(Context ctx) {
        return ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    static int percent(Intent battery) {
        if (battery == null) return -1;
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        return level < 0 || scale <= 0 ? -1 : Math.round(level * 100f / scale);
    }

    static boolean charging(Intent battery) {
        return battery != null && battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
    }

    /** "78% · in carica" per il menu */
    static String statusText(Context ctx) {
        Intent b = current(ctx);
        int pct = percent(b);
        if (pct < 0) return "unknown";
        return pct + "% " + (charging(b) ? "· charging" : "· on battery");
    }

    /**
     * Chiamato a ogni ACTION_BATTERY_CHANGED (il servizio lo registra finché il server gira). Manda un
     * avviso solo quando si entra in una delle due soglie (non a ogni punto percentuale) e lo ritira da
     * solo quando la condizione rientra.
     */
    static void onBatteryChanged(Cfg c, Intent battery) {
        if (!c.prefs.getBoolean("battery_guard_enabled", false)) {
            if (lastKind != NONE) {
                cancel(c.ctx);
                lastKind = NONE;
            }
            return;
        }
        int pct = percent(battery);
        if (pct < 0) return;
        boolean chg = charging(battery);
        int high = c.prefs.getInt("battery_guard_high", 80);
        int low = c.prefs.getInt("battery_guard_low", 30);

        int kind = chg && pct >= high ? HIGH : !chg && pct <= low ? LOW : NONE;
        if (kind == lastKind) return; // niente di nuovo: non si rimanda lo stesso avviso
        lastKind = kind;
        if (kind == NONE) {
            cancel(c.ctx);
        } else {
            notify(c.ctx, pct, kind == HIGH);
        }
    }

    private static void notify(Context ctx, int pct, boolean high) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Battery", NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(ctx, 2, new Intent(ctx, MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT);
        String title = "Battery at " + pct + "%";
        String text = high ? "Unplug the charger: sitting at 100% all the time wears the battery out."
            : "Plug the charger back in: the battery is getting too low.";
        int small = ctx.getResources().getIdentifier("ic_stat", "drawable", ctx.getPackageName());
        Notification n = new Notification.Builder(ctx, CHANNEL)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(small != 0 ? small : ctx.getApplicationInfo().icon)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build();
        nm.notify(NID, n);
    }

    private static void cancel(Context ctx) {
        ((NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NID);
    }
}
