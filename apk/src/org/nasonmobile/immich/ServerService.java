package org.nasonmobile.immich;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;

import java.util.List;

/**
 * Servizio in primo piano: tiene vivo il processo (notifica fissa) e i wake lock, e pilota lo Stack.
 * Va tenuto in primo piano, altrimenti Android ferma PostgreSQL e Immich in background.
 */
public class ServerService extends Service {
    static final String ACTION_START = "org.nasonmobile.immich.START";
    static final String ACTION_STOP = "org.nasonmobile.immich.STOP";
    private static final String CHANNEL = "server";
    private static final int NID = 1;

    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;
    private BroadcastReceiver batteryReceiver;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            new Cfg(this).setShouldRun(false);
            Stack.I.stop(new Runnable() {
                @Override
                public void run() {
                    releaseLocks();
                    unregisterBattery();
                    stopForeground(true);
                    stopSelf();
                }
            });
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)) new Cfg(this).setShouldRun(true);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Server", NotificationManager.IMPORTANCE_LOW));
        startForeground(NID, build());
        acquireLocks();
        registerBattery();

        final NotificationManager fnm = nm;
        Stack.I.setListener(new Stack.Listener() {
            @Override
            public void changed() {
                fnm.notify(NID, build());
                if (Stack.I.state() == Stack.State.ERROR) {
                    // niente da tenere acceso: la notifica con l'errore resta, ma senza wake lock né primo piano
                    releaseLocks();
                    stopForeground(false);
                    stopSelf();
                }
            }
        });
        Stack.I.start(getApplicationContext());
        return START_STICKY;
    }

    private Notification build() {
        Cfg c = new Cfg(this);
        String text = Stack.I.detail();
        if (Stack.I.state() == Stack.State.RUNNING) {
            List<String> ips = Util.ipv4();
            String ip = ips.isEmpty() ? "127.0.0.1" : ips.get(0).substring(ips.get(0).indexOf(' ') + 1);
            text = "In esecuzione · http://" + ip + ":" + c.port();
            int lvl = Health.spaceLevel(c.files);
            if (lvl == 1) text += " · SPAZIO QUASI FINITO";
            else if (lvl == 0) text += " · spazio in esaurimento";
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
            new Intent(this, ServerService.class).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT);
        int small = getResources().getIdentifier("ic_stat", "drawable", getPackageName());
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
            .setContentTitle("Immich Server")
            .setContentText(text)
            .setSmallIcon(small != 0 ? small : getApplicationInfo().icon)
            .setContentIntent(open);
        if (Stack.I.state() == Stack.State.ERROR) {
            b.setContentText("Errore: " + Stack.I.detail()).setOngoing(false).setAutoCancel(true);
        } else {
            b.setOngoing(true).addAction(new Notification.Action.Builder(0, "Ferma", stop).build());
        }
        return b.build();
    }

    private void acquireLocks() {
        if (wake == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "immich:server");
            wake.setReferenceCounted(false);
        }
        if (!wake.isHeld()) wake.acquire();
        if (wifi == null) {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "immich:wifi");
            wifi.setReferenceCounted(false);
        }
        if (!wifi.isHeld()) wifi.acquire();
    }

    private void releaseLocks() {
        if (wake != null && wake.isHeld()) wake.release();
        if (wifi != null && wifi.isHeld()) wifi.release();
    }

    /**
     * Solo mentre il server gira: avvisa se si resta sempre in carica al 100% (vedi BatteryGuard). Il primo
     * ACTION_BATTERY_CHANGED arriva subito dopo la registrazione (è un intent "sticky"), senza aspettare un
     * evento vero.
     */
    private void registerBattery() {
        if (batteryReceiver != null) return;
        final Cfg c = new Cfg(this);
        batteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                BatteryGuard.onBatteryChanged(c, intent);
            }
        };
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    private void unregisterBattery() {
        if (batteryReceiver == null) return;
        try {
            unregisterReceiver(batteryReceiver);
        } catch (IllegalArgumentException ignored) {
            // già tolto
        }
        batteryReceiver = null;
    }

    @Override
    public void onDestroy() {
        Stack.I.setListener(null);
        releaseLocks();
        unregisterBattery();
        super.onDestroy();
    }
}
