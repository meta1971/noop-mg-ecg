package com.noop.mgecg;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * After a restart or an app update the strap connection is gone, and Android does not let an app open itself to reconnect from the
 * background. So this posts one clear alert; tapping it opens the app and reconnects. It does nothing when the day logger is switched off.
 */
public class DayLogBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        String a = i == null ? null : i.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || "android.intent.action.QUICKBOOT_POWERON".equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            KeepAlive.afterRestart(c);
        }
    }
}
