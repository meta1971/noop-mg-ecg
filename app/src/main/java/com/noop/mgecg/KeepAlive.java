package com.noop.mgecg;

// FILE VERSION 0.1.0 (23 Oct 2026): keeps the all-day logger honest. Three things, all user-visible and none of them silent:
//   1. battery-optimisation exemption helpers (a one-way prompt, shown only while the logger is on and the app is not exempt)
//   2. an alert when strap data has stopped arriving while the logger is meant to be on (watchdog in DayLogService)
//   3. an alert after a reboot or an app update, because Android stops the strap connection then and an app cannot reconnect to a
//      strap in the background on its own; tapping the alert opens the app and reconnects
// Research only.

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

final class KeepAlive {
    private KeepAlive() {}

    static final String CHANNEL = "daylog_alert";
    static final int ID = 4102;
    /** Intent extra that MainActivity (through NewLookActivity) turns into a reconnect. */
    static final String EXTRA_RESUME = "resume_logger";
    /** Minutes without a new strap second before the alert appears. */
    static final long STALE_MIN = 20;

    static boolean batteryExempt(Context c) {
        try {
            if (Build.VERSION.SDK_INT < 23) return true;
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
        } catch (Throwable t) {
            return true;       // unknown: do not nag
        }
    }

    /** The system dialog that asks to exempt this app; falls back to the general battery settings list. */
    static Intent batteryIntent(Context c) {
        Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + c.getPackageName()));
        if (i.resolveActivity(c.getPackageManager()) == null) i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
        return i;
    }

    /** True while the logger is meant to be on but the app is still subject to battery optimisation. */
    static boolean shouldOfferExemption(Context c) {
        return DayLog.enabled(c) && !batteryExempt(c);
    }

    static void ensureChannel(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "MG logger alerts", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("Tells you when the day logger has stopped receiving strap data");
        nm.createNotificationChannel(ch);
    }

    static final String QUIET_CHANNEL = "daylog_quiet";
    /** 0.19.3: out of range for this long (link not good) before the quiet note appears. The strap keeps about three weeks of data, so being away is normal. */
    static final long OUT_OF_RANGE_NOTE_MIN = 180;

    static void ensureQuietChannel(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(QUIET_CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(QUIET_CHANNEL, "MG logger notes (silent)", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Silent notes: the strap is out of range, or no history was pulled for a while");
        nm.createNotificationChannel(ch);
    }

    /** Silent and only once: no sound, no vibration, updates in place. */
    static void postQuiet(Context c, String title, String text) {
        try {
            ensureQuietChannel(c);
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            Intent open = new Intent(c, MainActivity.class).putExtra(EXTRA_RESUME, true).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(c, 7, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(c, QUIET_CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(pi)
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(true)
                    .build();
            nm.notify(ID, n);
        } catch (Throwable ignored) { }
    }

    static void post(Context c, String title, String text) {
        try {
            ensureChannel(c);
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            Intent open = new Intent(c, MainActivity.class).putExtra(EXTRA_RESUME, true).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(c, 7, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(c, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify(ID, n);
        } catch (Throwable ignored) { }
    }

    static void cancel(Context c) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(ID);
        } catch (Throwable ignored) { }
    }

    /** Called every few minutes by the service watchdog. Clears the alert as soon as data flows again. */
    static void checkStale(Context c) {
        try {
            if (!DayLog.enabled(c)) { cancel(c); return; }
            long m = ActivityStore.staleMinutes(c);
            // 0.15.2: stored seconds arrive with each history pull, about every 30 minutes, so a live link legitimately shows data 25 minutes old.
            // With a good link warn only after 75 minutes; otherwise as before.
            boolean linkGood = false;
            try { linkGood = MainActivity.linkState(c).level == LinkState.GOOD; } catch (Throwable ignored) { }
            // 0.19.3: being out of range is normal when the phone stays behind. The strap keeps its data (about three weeks of it) and the logger pulls it when the
            // strap is back in range, so a missing link raises only a silent note, after 3 hours. A good link without pulls still raises one after 75 minutes.
            if (linkGood && m >= 75) {
                postQuiet(c, "Day logger: no strap data for " + (m >= 120 ? (m / 60) + " h" : m + " min"),
                        "The strap is connected but no history has been pulled for a while. Open the app, or use Pull now in the notification.");
            } else if (!linkGood && m >= OUT_OF_RANGE_NOTE_MIN) {
                postQuiet(c, "Day logger: strap out of range for " + (m / 60) + " h",
                        "Nothing is lost: the strap keeps its data (about three weeks) and the logger pulls it by itself when the strap is back in range. Tap to reconnect now.");
            } else if (m >= 0) {
                cancel(c);
            }
        } catch (Throwable ignored) { }
    }

    static void afterRestart(Context c) {
        if (!DayLog.enabled(c)) return;
        DayLogService.ensure(c);          // 0.15.1: start the foreground service again; it is what keeps the app (and the link) alive
        post(c, "Day logger paused", "The phone restarted (or the app was updated), which ends the strap connection. Tap to reconnect and carry on logging.");
    }
}
