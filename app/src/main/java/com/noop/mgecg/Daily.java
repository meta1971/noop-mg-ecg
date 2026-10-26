package com.noop.mgecg;

// FILE VERSION 0.12.0 (26 Oct): the daily notifications: a morning summary once a night, and a strap-battery alert. Research only.

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;

import java.util.Calendar;
import java.util.Locale;

/**
 * Two things a daily strap app should do without being opened. Called from the day-logger tick; does its work on its own thread and
 * at most once every ten minutes.
 *  - MORNING SUMMARY: once per night, after the strap's sleep state has ended (at least ten minutes ago), between 05:00 and 14:00 local
 *    time and only for a night of three hours or more: recovery, sleep against the goal, lowest five-minute heart rate and the plan.
 *  - BATTERY: when the strap reports 15% or less, once, until it has been charged above 30% again.
 * Both can be switched off in the You tab. Estimates, not a medical record.
 */
final class Daily {
    private Daily() {}

    static final String CHANNEL = "daily";
    static final int ID_MORNING = 4201, ID_BATTERY = 4202;
    static final String PREFS = "today";                       // the same file the You tab writes to
    private static long lastRunMs = 0L;

    static void tick(final Context c) {
        long now = System.currentTimeMillis();
        synchronized (Daily.class) {
            if (now - lastRunMs < 10L * 60L * 1000L) return;
            lastRunMs = now;
        }
        final Context app = c.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                try { morning(app); } catch (Throwable ignored) { }
                try { battery(app); } catch (Throwable ignored) { }
            }
        }, "daily").start();
    }

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    // ------------------------------------------------------------------ morning summary
    private static void morning(Context c) {
        SharedPreferences p = sp(c);
        if (!p.getBoolean("n_morning", true)) return;
        long now = System.currentTimeMillis(), nowSec = now / 1000L;
        if (now < p.getLong("morning_retry_ms", 0L)) return;
        Calendar cal = Calendar.getInstance();
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        if (hour < 5 || hour >= 14) return;
        long lastSleep = 0L;
        Cursor q = DayLog.db(c).rawQuery("SELECT MAX(ts) FROM r18 WHERE state = 2", null);
        try { if (q.moveToFirst()) lastSleep = q.getLong(0); } finally { q.close(); }
        if (lastSleep <= 0L || nowSec - lastSleep < 600L || nowSec - lastSleep > 12L * 3600L) return;
        if (Math.abs(lastSleep - p.getLong("morning_last_sleep", 0L)) < 4L * 3600L) return;     // this night was already announced
        int maxHr = p.getInt("maxhr", 167);
        double goal = p.getFloat("goal", 8.0f);
        TodayModel.Day d = DayLog.today(c, maxHr, goal);
        SleepNight.Night n = d == null ? null : d.night;
        if (n == null || n.tstH < 3.0 || n.endTs <= 0L || nowSec - n.endTs > 12L * 3600L) {
            p.edit().putLong("morning_retry_ms", now + 30L * 60L * 1000L).apply();
            return;
        }
        post(c, ID_MORNING, "Morning summary", summaryText(d, goal));
        p.edit().putLong("morning_last_sleep", lastSleep).apply();
    }

    /** One short block of text: recovery, sleep, lowest heart rate, HRV, and the plan. Pure, so it can be tested off the phone. */
    static String summaryText(TodayModel.Day d, double goalH) {
        SleepNight.Night n = d.night;
        StringBuilder s = new StringBuilder();
        if (Double.isNaN(d.recovery)) s.append("Recovery shows after ").append(TodayModel.MIN_BASELINE).append(" nights (").append(d.baselineNights).append(" so far). ");
        else s.append("Recovery ").append(Math.round(d.recovery)).append("%. ");
        s.append("Slept ").append(HealthModel.hm(n.tstH)).append(" (goal ").append(Math.round(goalH)).append(" h).");
        if (!Double.isNaN(n.hrLow5)) s.append(" Lowest five-minute heart rate ").append(Math.round(n.hrLow5)).append(".");
        if (!Double.isNaN(n.rmssd) && n.rmssd > 0) s.append(" HRV ").append(Math.round(n.rmssd)).append(" ms (optical).");
        s.append("\n").append(DailyViews.plan(d.recovery));
        return s.toString();
    }

    // ------------------------------------------------------------------ strap battery
    private static void battery(Context c) {
        SharedPreferences p = sp(c);
        if (!p.getBoolean("n_battery", true)) return;
        int pct = -1;
        long at = 0L;
        Cursor q = DayLog.db(c).rawQuery("SELECT text, ms FROM events WHERE kind = 'battery' ORDER BY id DESC LIMIT 1", null);
        try { if (q.moveToFirst()) { try { pct = Integer.parseInt(q.getString(0).trim()); } catch (Exception ignored) { } at = q.getLong(1); } } finally { q.close(); }
        long now = System.currentTimeMillis();
        if (pct < 0 || now - at > 3L * 3600L * 1000L) return;                       // no recent reading: say nothing
        if (pct >= 30) { if (p.getBoolean("bat_alerted", false)) p.edit().putBoolean("bat_alerted", false).apply(); return; }
        if (pct <= 15 && !p.getBoolean("bat_alerted", false)) {
            post(c, ID_BATTERY, "Strap battery " + pct + "%", "Charge the strap soon so the all-day log carries on without a gap.");
            p.edit().putBoolean("bat_alerted", true).apply();
        }
    }

    // ------------------------------------------------------------------ notification
    private static void post(Context c, int id, String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "Daily summary and strap", NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("Your morning numbers and a low strap-battery warning");
                nm.createNotificationChannel(ch);
            }
            Intent open = new Intent(c, HealthActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(c, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_info_details)
                    .setContentTitle(title)
                    .setContentText(text.indexOf('\n') > 0 ? text.substring(0, text.indexOf('\n')) : text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify(id, n);
        } catch (Throwable ignored) { }
    }
}
