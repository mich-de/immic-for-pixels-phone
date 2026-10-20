package org.nasonmobile.immich;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Esito dell'installatore di Android per un aggiornamento scaricato da Updater. */
public class UpdateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Updater.onStatus(context, intent);
    }
}
