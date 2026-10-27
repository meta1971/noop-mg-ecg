package com.noop.mgecg;

// FILE VERSION 0.19.0 (27 Oct 2026): an alarm every 15 minutes that brings the logger back if Android ended the process (it cannot help after a force stop).

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

final class LoggerAlarm {
    private LoggerAlarm() {}

    static final long EVERY_MS = 15L * 60L * 1000L;

    static void schedule(Context c) { scheduleIn(c, EVERY_MS); }

    /** One alarm at a time (same PendingIntent): a quick one after the app was swiped away, the regular one otherwise. */
    static void scheduleIn(Context c, long ms) {
        try {
            if (!DayLog.enabled(c)) return;
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            PendingIntent pi = PendingIntent.getBroadcast(c, 7101, new Intent(c, LoggerAlarmReceiver.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            long at = System.currentTimeMillis() + ms;
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (Throwable ignored) { }
    }
}
