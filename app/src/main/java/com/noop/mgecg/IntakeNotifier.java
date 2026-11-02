package com.noop.mgecg;

// FILE VERSION 0.38.0 (1 Nov 2026): one-tap intake logging from the notification shade (Drink, Carb meal, Caffeine) with the time, kept as day-logger markers (alcohol, carb_meal, caffeine) so evenings, nights
// and ECG readings can be lined up with what you actually had and when. Off by default; switched on from the HRV screen. Nothing leaves the phone.

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.drawable.Icon;
import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

final class IntakeNotifier {
    private IntakeNotifier() {}

    static final String CH = "intake_log", A_DRINK = "com.noop.mgecg.INTAKE_DRINK", A_CARB = "com.noop.mgecg.INTAKE_CARB", A_CAFF = "com.noop.mgecg.INTAKE_CAFFEINE";
    static final int ID = 4107;

    static boolean on(Context c) { return c.getApplicationContext().getSharedPreferences("intake", Context.MODE_PRIVATE).getBoolean("on", false); }
    static void set(Context c, boolean v) { c.getApplicationContext().getSharedPreferences("intake", Context.MODE_PRIVATE).edit().putBoolean("on", v).apply(); refresh(c); }

    static String kindOf(String action) { return A_DRINK.equals(action) ? "alcohol" : A_CARB.equals(action) ? "carb_meal" : A_CAFF.equals(action) ? "caffeine" : null; }
    static String labelOf(String kind) { return "alcohol".equals(kind) ? "Drink" : "carb_meal".equals(kind) ? "Carb meal" : "caffeine".equals(kind) ? "Caffeine" : kind; }

    /** Today's counts and last times of the three intake kinds, from the markers (local midnight to now). */
    static String todayLine(Context c) {
        try {
            Calendar cal = Calendar.getInstance(); cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0); long t0 = cal.getTimeInMillis();
            StringBuilder sb = new StringBuilder("Today: "); SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.UK); boolean any = false;
            for (String k : new String[] { "alcohol", "carb_meal", "caffeine" }) {
                Cursor cu = DayLog.db(c).rawQuery("SELECT COUNT(*), MAX(ms) FROM events WHERE kind='marker' AND text=? AND ms >= ?", new String[] { k, String.valueOf(t0) });
                try { if (cu.moveToFirst() && cu.getInt(0) > 0) { if (any) sb.append("; "); sb.append(labelOf(k)).append(" ").append(cu.getInt(0)).append(" (last ").append(f.format(new Date(cu.getLong(1)))).append(")"); any = true; } } finally { cu.close(); }
            }
            return any ? sb.toString() : "Nothing logged today.";
        } catch (Throwable t) { return "Nothing logged today."; }
    }

    private static PendingIntent pi(Context c, String action, int code) {
        Intent i = new Intent(c, DayLogReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Shows or removes the notification according to the setting. */
    static void refresh(Context c) {
        try {
            Context a = c.getApplicationContext(); NotificationManager nm = (NotificationManager) a.getSystemService(Context.NOTIFICATION_SERVICE); if (nm == null) return;
            if (!on(a)) { nm.cancel(ID); return; }
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) { NotificationChannel ch = new NotificationChannel(CH, "Intake log", NotificationManager.IMPORTANCE_LOW); ch.setShowBadge(false); nm.createNotificationChannel(ch); }
            Notification.Builder b = new Notification.Builder(a, CH).setSmallIcon(android.R.drawable.ic_menu_edit).setContentTitle("Log what you had").setContentText(todayLine(a)).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
                .addAction(new Notification.Action.Builder(Icon.createWithResource(a, android.R.drawable.ic_menu_add), "Drink", pi(a, A_DRINK, 21)).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(a, android.R.drawable.ic_menu_add), "Carb meal", pi(a, A_CARB, 22)).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(a, android.R.drawable.ic_menu_add), "Caffeine", pi(a, A_CAFF, 23)).build());
            nm.notify(ID, b.build());
        } catch (Throwable ignored) { }
    }

    /** Called by the receiver when a button is tapped: writes the marker with the current time and refreshes the text. */
    static void onAction(Context c, String action) {
        String k = kindOf(action); if (k == null) return;
        DayLog.addEvent(c, "marker", k); refresh(c);
    }
}
