package com.noop.mgecg;

// FILE VERSION 0.60.0 (15 Nov 2026): NEW. The wind-down reminder of the wellbeing page. Opt-in, and only while a bed plan is saved: one notification every evening, 45 minutes before the plan time (23:00 -> 22:15).
// The alarm goes to DayLogReceiver (no manifest change) and is set again after a restart or an update by DayLogBootReceiver. Nothing is sent anywhere; the setting lives in SharedPreferences "wellbeing" (wind_on).
// Without the exact-alarm permission (Android 12 and up) the alarm is inexact and may arrive a few minutes late; clearing the plan or switching the reminder off cancels it.

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.TimeZone;

final class WellbeingReminder {
    private WellbeingReminder() {}

    static final String A_FIRE = "com.noop.mgecg.WIND_DOWN", CH = "wind_down";
    static final int ID = 4120, REQ = 7120, REQ_OPEN = 7121, PERM_REQ = 4120;

    static boolean isAction(String a) { return A_FIRE.equals(a); }

    private static SharedPreferences sp(Context c) { return c.getApplicationContext().getSharedPreferences(WellbeingActivity.PREFS, Context.MODE_PRIVATE); }

    /** The plan's bed time in minutes after midnight, or -1 when there is no plan. */
    static int planBed(Context c) { return sp(c).getInt("plan_bed_min", -1); }

    /** On only while a plan exists. */
    static boolean on(Context c) { return sp(c).getBoolean("wind_on", false) && planBed(c) >= 0; }

    static void setOn(Context c, boolean on) {
        sp(c).edit().putBoolean("wind_on", on).commit();
        schedule(c);
    }

    /** Sets (or clears) the one alarm for the next evening. Safe to call at any time. */
    static void schedule(Context c0) {
        try {
            Context c = c0.getApplicationContext();
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            PendingIntent pi = PendingIntent.getBroadcast(c, REQ, new Intent(c, DayLogReceiver.class).setAction(A_FIRE), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            if (!on(c)) { am.cancel(pi); return; }
            long at = WellbeingExtra.nextFire(System.currentTimeMillis(), TimeZone.getDefault(), WellbeingExtra.windMin(planBed(c)));
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (Throwable ignored) { }
    }

    /** Called by DayLogReceiver. */
    static void onAction(Context c0, String action) {
        Context c = c0.getApplicationContext();
        try {
            if (A_FIRE.equals(action)) {
                if (on(c)) post(c);
                schedule(c);
            }
        } catch (Throwable ignored) { }
    }

    static void post(Context c) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) nm.createNotificationChannel(new NotificationChannel(CH, "Wind-down reminder", NotificationManager.IMPORTANCE_DEFAULT));
            PendingIntent open = PendingIntent.getActivity(c, REQ_OPEN, new Intent(c, WellbeingActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            int bed = planBed(c);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CH) : new Notification.Builder(c);
            b.setSmallIcon(android.R.drawable.ic_menu_recent_history).setContentTitle("Wind-down time")
                .setContentText(bed >= 0 ? "Your plan: in bed by " + WellbeingModel.clock(bed) + ". Tap for a calm minute." : "Tap for a calm minute.")
                .setContentIntent(open).setAutoCancel(true);
            nm.notify(ID, b.build());
        } catch (Throwable ignored) { }
    }
}
