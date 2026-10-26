package com.noop.mgecg;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The daily alarm: set tomorrow's alarm first (so a failure today cannot stop the series), then start the backup service. */
public class BackupReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (i == null || !Backup.ACTION_RUN.equals(i.getAction())) return;
        Backup.schedule(c);
        if (Backup.enabled(c)) Backup.startNow(c, "alarm");
    }
}
