package com.noop.mgecg;

// FILE VERSION 0.19.0 (27 Oct 2026)

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Every 15 minutes: note that the alarm ran, read why the process last ended, start the logger service if it is not running, check for stale data, arm the next alarm. */
public class LoggerAlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        final PendingResult pr = goAsync();
        final Context app = c.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    LoggerAlarm.schedule(app);          // 0.19.4: arm the NEXT alarm first, so nothing below can break the chain (the 10:40 alarm on 27 Oct never came)
                    DayLogService.bump(app, "alarm");
                    app.getSharedPreferences("linkdiag", Context.MODE_PRIVATE).edit().putLong("alarm_ms", System.currentTimeMillis()).putBoolean("alarm_alive", DayLogService.alive).apply();
                    ProcExit.check(app);
                    if (DayLog.enabled(app)) {
                        if (!DayLogService.alive) { DayLogService.bump(app, "alarm_restart"); DayLogService.start(app); }
                        else DayLogService.tryHost(app);
                        KeepAlive.checkStale(app);
                    }
                } catch (Throwable ignored) {
                } finally {
                    pr.finish();
                }
            }
        }, "logger-alarm").start();
    }
}
