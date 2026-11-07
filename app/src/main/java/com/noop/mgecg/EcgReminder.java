package com.noop.mgecg;

// FILE VERSION 0.43.9 (7 Nov 2026): the alarm ALWAYS fires at its time (the 3-hour skip after a reading is removed; it silenced the 13:01 test on 7 Nov). 0.43.8 (7 Nov 2026): the buzz is the byte-verified MG command (MainActivity.buzzStrap: opcode 19, payload 01 47 152 0 x 8 loop), one short buzz every 4 s until the double-tap; the guessed methods are gone. 0.43.7 (7 Nov 2026): the MG buzz repeats every 6 s until a live DOUBLE_TAP event (id 14) from the strap, "Stop buzz" or Snooze in the note, or the cap (1, 3, 5 or 10 min, default 5); the strap link is reopened and the buzz resent when it drops. 0.43.6 (6 Nov 2026): the alarm buzzes the MG, not the phone (MainActivity.buzzStrap; opens the strap link first when it is down, retries for about 30 s; the phone buzz is a separate option, off), Monday to Friday and Saturday-Sunday have their own times (setSlot), buzz length 1 / 3 / 5. 0.43.5: the reminder BUZZES the phone (own vibrator on the alarm stream, six pulses) and its note is high priority and silent; the Today page sets a morning and an evening time (setSimple). 0.42.0 (5 Nov 2026): reading reminders with their own times for work days and days off (and a one-tap "today is a work day / day off" override). Decode log 50.6 items 1 and 2:
// morning 08:00-10:00 and evening 19:00-21:00 on the same day were the planned Run E; shifts differ, so every time can be changed or switched off.
// The alarm goes to DayLogReceiver (no manifest change). Since 0.43.9 an alarm is never skipped because of an earlier reading.

import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.TimePicker;

import java.io.File;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import static com.noop.mgecg.ReminderTime.*;

final class EcgReminder {
    private EcgReminder() {}

    static final String A_FIRE = "com.noop.mgecg.ECG_REMIND", A_SNOOZE = "com.noop.mgecg.ECG_SNOOZE", CH = "ecg_reminder", CH2 = "ecg_alarm_buzz", A_STOP = "com.noop.mgecg.ECG_STOP_BUZZ";
    static final int ID = 4108, REQ = 7102, REQ_SNOOZE = 7103;
    static final String[] SLOT = { "Work day, morning", "Work day, evening", "Day off, morning", "Day off, evening" };

    static boolean isAction(String a) { return A_FIRE.equals(a) || A_SNOOZE.equals(a) || A_STOP.equals(a); }

    // ------------------------------------------------------------------ settings
    static Cfg load(Context c) {
        SharedPreferences p = c.getApplicationContext().getSharedPreferences("ecg_reminder", Context.MODE_PRIVATE);
        Cfg g = new Cfg(); g.on = p.getBoolean("on", false); g.workMask = p.getInt("mask", 31);
        for (int i = 0; i < 4; i++) g.t[i] = p.getInt("t" + i, g.t[i]);
        g.overrideDay = p.getInt("ovday", 0); g.overrideType = p.getInt("ovtype", 0);
        return g;
    }
    static void save(Context c, Cfg g) {
        SharedPreferences.Editor e = c.getApplicationContext().getSharedPreferences("ecg_reminder", Context.MODE_PRIVATE).edit().putBoolean("on", g.on).putInt("mask", g.workMask).putInt("ovday", g.overrideDay).putInt("ovtype", g.overrideType);
        for (int i = 0; i < 4; i++) e.putInt("t" + i, g.t[i]);
        e.apply();
    }

    /** The Today page: slot 0 / 1 = Monday to Friday morning / evening, 2 / 3 = Saturday and Sunday morning / evening; minutes after midnight or -1 for off. Monday to Friday count as work days here. */
    static void setSlot(Context c, int slot, int minutes) {
        Cfg g = load(c); g.t[slot] = minutes; g.workMask = 31; g.overrideDay = 0; g.overrideType = 0;
        g.on = g.t[0] >= 0 || g.t[1] >= 0 || g.t[2] >= 0 || g.t[3] >= 0;
        save(c, g); schedule(c);
    }

    private static SharedPreferences sp(Context c) { return c.getApplicationContext().getSharedPreferences("ecg_reminder", Context.MODE_PRIVATE); }
    static boolean buzzMgOn(Context c) { return sp(c).getBoolean("buzz_mg", true); }
    static boolean buzzPhoneOn(Context c) { return sp(c).getBoolean("buzz_phone", false); }
    static int buzzReps(Context c) { return sp(c).getInt("buzz_reps", 3); }
    static int buzzMethod(Context c) { return Math.max(0, Math.min(2, sp(c).getInt("buzz_method", 0))); }

    private static volatile boolean buzzing; private static volatile long buzzStart; private static volatile int buzzGen; private static volatile int buzzUsed;
    static int buzzMaxMin(Context c) { return sp(c).getInt("buzz_max_min", 5); }

    /** Keeps buzzing the MG, one short buzz every 4 s, until the strap is double-tapped (live event 14), Stop buzz or Snooze is pressed, or the cap is reached. The strap link is opened first when it is down and the buzz is sent again when it comes back. */
    static void buzzMg(final Context c0) {
        final Context c = c0.getApplicationContext();
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        final int gen = ++buzzGen; buzzing = true; buzzStart = System.currentTimeMillis();
        final long cap = buzzStart + buzzMaxMin(c) * 60000L;
        final int[] fails = { 0 }; final boolean[] everSent = { false };
        new Runnable() { @Override public void run() {
            try {
                if (gen != buzzGen || !buzzing) return;                              // stopped, or replaced by a newer alarm
                if (System.currentTimeMillis() > cap) { stopBuzz(c, false); return; }
                boolean sent = MainActivity.linkRunning() && MainActivity.buzzStrap(1);
                if (sent) everSent[0] = true;
                else { if (fails[0] == 0) MainActivity.startLinkHost(c); if (++fails[0] == 8 && !everSent[0]) notReached(c); }
                h.postDelayed(this, 4000L);
            } catch (Throwable ignored) { }
        } }.run();
    }

    static void stopBuzz(Context c, boolean tapped) {
        boolean was = buzzing; buzzing = false; buzzGen++;
        try { NotificationManager nm = (NotificationManager) c.getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE); if (nm != null && was && tapped) nm.cancel(ID); } catch (Throwable ignored) { }      // a buzz that ran to its cap leaves the note
    }

    /** MainActivity: a live DOUBLE_TAP event from the strap. Ends a buzz that has run for at least 1.5 s (so the tap that ends it is not the knock of the alarm starting). */
    static void onStrapTap(Context c) { if (buzzing && System.currentTimeMillis() - buzzStart > 1500L) stopBuzz(c, true); }

    private static void notReached(Context c) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE); if (nm == null) return;
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CH2) : new Notification.Builder(c);
            b.setSmallIcon(android.R.drawable.ic_menu_recent_history).setContentTitle("Your MG could not be reached").setContentText("No buzz was sent. Open the app so it can reconnect to the strap.").setAutoCancel(true);
            nm.notify(ID + 1, b.build());
        } catch (Throwable ignored) { }
    }

    /** true when exact alarm times are allowed (below Android 12 always). */
    static boolean exactOk(Context c) {
        try { if (Build.VERSION.SDK_INT < 31) return true; AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE); return am != null && am.canScheduleExactAlarms(); } catch (Throwable t) { return true; }
    }

    /** Six pulses (about 6 s) on the phone's own vibrator, alarm usage, so it also buzzes in silent mode. */
    static void buzz(Context c) {
        try {
            android.os.Vibrator v = (android.os.Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            long[] p = { 0, 700, 350, 700, 350, 700, 350, 700, 350, 700, 350, 700 };
            v.vibrate(android.os.VibrationEffect.createWaveform(p, -1), new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ alarm
    /** Sets (or clears) the one alarm for the next reminder. Safe to call at any time. */
    static void schedule(Context c0) {
        try {
            Context c = c0.getApplicationContext(); AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE); if (am == null) return;
            PendingIntent pi = PendingIntent.getBroadcast(c, REQ, new Intent(c, DayLogReceiver.class).setAction(A_FIRE), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            long[] n = next(System.currentTimeMillis(), TimeZone.getDefault(), load(c));
            if (n == null) { am.cancel(pi); return; }
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, n[0], pi); else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, n[0], pi);
        } catch (Throwable ignored) { }
    }

    /** Called by DayLogReceiver. */
    static void onAction(Context c0, String action) {
        Context c = c0.getApplicationContext();
        try {
            if (A_STOP.equals(action)) { stopBuzz(c, false); NotificationManager nm0 = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE); if (nm0 != null) nm0.cancel(ID); return; }
            if (A_SNOOZE.equals(action)) {
                stopBuzz(c, false);
                NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE); if (nm != null) nm.cancel(ID);
                AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE); if (am == null) return;
                PendingIntent pi = PendingIntent.getBroadcast(c, REQ_SNOOZE, new Intent(c, DayLogReceiver.class).setAction(A_FIRE).putExtra("snoozed", true), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                long at = System.currentTimeMillis() + 30L * 60000L;
                if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi); else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                return;
            }
            if (A_FIRE.equals(action)) {
                Cfg g = load(c);
                if (g.on) post(c, false);
                schedule(c);
            }
        } catch (Throwable ignored) { }
    }

    static void post(Context c, boolean test) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE); if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH2) == null) { NotificationChannel ch = new NotificationChannel(CH2, "ECG alarms (buzz)", NotificationManager.IMPORTANCE_HIGH); ch.setSound(null, null); ch.enableVibration(false); nm.createNotificationChannel(ch); }      // the buzz comes from buzz() so it never doubles
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT).putExtra("stay", true).putExtra("start_ecg", true);
        PendingIntent take = PendingIntent.getActivity(c, 7104, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getBroadcast(c, 7106, new Intent(c, DayLogReceiver.class).setAction(A_STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent snooze = PendingIntent.getBroadcast(c, 7105, new Intent(c, DayLogReceiver.class).setAction(A_SNOOZE), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String part = hour < 12 ? "morning" : "evening";
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CH2) : new Notification.Builder(c);
        b.setSmallIcon(android.R.drawable.ic_menu_recent_history).setContentTitle(test ? "Test: time for an ECG reading" : "Time for your " + part + " ECG reading")
            .setContentText(buzzMgOn(c) ? "Your MG buzzes until you double-tap it. Then sit at the table, forearm supported, two fingertips on the clasp." : "Sit at the table, forearm supported, two fingertips on the clasp. 45 s is enough.").setAutoCancel(true).setContentIntent(take)
            .addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(c, android.R.drawable.ic_menu_edit), "Take ECG", take).build())
            .addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(c, android.R.drawable.ic_menu_recent_history), "Snooze 30 min", snooze).build())
            .addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(c, android.R.drawable.ic_menu_close_clear_cancel), "Stop buzz", stop).build());
        nm.notify(ID, b.build());
        if (buzzPhoneOn(c)) buzz(c);                       // the phone only when asked for
        if (buzzMgOn(c)) buzzMg(c);
    }

    // ------------------------------------------------------------------ settings screen (from Home)
    static void showDialog(final Activity a) {
        final Cfg g = load(a);
        final LinearLayout col = new LinearLayout(a); col.setOrientation(LinearLayout.VERTICAL); int p = Math.round(14 * a.getResources().getDisplayMetrics().density); col.setPadding(p, p, p, p);
        final AlertDialog[] dlg = new AlertDialog[1];
        final Runnable[] rebuild = new Runnable[1];
        rebuild[0] = new Runnable() { @Override public void run() {
            col.removeAllViews();
            col.addView(note(a, "Each time can be changed or switched off. Work days and days off have their own times; the override is for today only. An alarm always goes off at its time.", 12));
            col.addView(row(a, "Reminders", g.on ? "On" : "Off", new View.OnClickListener() { @Override public void onClick(View v) {
                g.on = !g.on; save(a, g); schedule(a); if (g.on && Build.VERSION.SDK_INT >= 33) { try { a.requestPermissions(new String[] { "android.permission.POST_NOTIFICATIONS" }, 4108); } catch (Throwable ignored) { } } rebuild[0].run(); } }));
            Calendar today = Calendar.getInstance(); final int ymd = ymd(today);
            String ov = g.overrideDay == ymd && g.overrideType == 1 ? "Work day" : g.overrideDay == ymd && g.overrideType == 2 ? "Day off" : "Automatic (" + (workDay(g, today) ? "work day" : "day off") + ")";
            col.addView(row(a, "Today counts as", ov, new View.OnClickListener() { @Override public void onClick(View v) {
                new AlertDialog.Builder(a).setTitle("Today counts as").setItems(new String[] { "Automatic", "Work day", "Day off" }, new android.content.DialogInterface.OnClickListener() { @Override public void onClick(android.content.DialogInterface d, int w) {
                    g.overrideDay = w == 0 ? 0 : ymd; g.overrideType = w; save(a, g); schedule(a); rebuild[0].run(); } }).show(); } }));
            StringBuilder wd = new StringBuilder(); for (int i = 0; i < 7; i++) if ((g.workMask & (1 << i)) != 0) wd.append(wd.length() > 0 ? " " : "").append(EcgStreak.DOW[i]);
            col.addView(row(a, "Work days", wd.length() == 0 ? "none" : wd.toString(), new View.OnClickListener() { @Override public void onClick(View v) {
                final boolean[] chk = new boolean[7]; for (int i = 0; i < 7; i++) chk[i] = (g.workMask & (1 << i)) != 0;
                new AlertDialog.Builder(a).setTitle("Work days").setMultiChoiceItems(EcgStreak.DOW, chk, new android.content.DialogInterface.OnMultiChoiceClickListener() { @Override public void onClick(android.content.DialogInterface d, int w, boolean on) { chk[w] = on; } })
                    .setPositiveButton("Save", new android.content.DialogInterface.OnClickListener() { @Override public void onClick(android.content.DialogInterface d, int w) { int m = 0; for (int i = 0; i < 7; i++) if (chk[i]) m |= 1 << i; g.workMask = m; save(a, g); schedule(a); rebuild[0].run(); } }).setNegativeButton("Cancel", null).show(); } }));
            for (int s = 0; s < 4; s++) {
                final int slot = s;
                col.addView(row(a, SLOT[s], hhmm(g.t[s]), new View.OnClickListener() { @Override public void onClick(View v) {
                    new AlertDialog.Builder(a).setTitle(SLOT[slot]).setItems(new String[] { "Change the time", g.t[slot] < 0 ? "Switch on" : "Switch off" }, new android.content.DialogInterface.OnClickListener() { @Override public void onClick(android.content.DialogInterface d, int w) {
                        if (w == 1) { g.t[slot] = g.t[slot] < 0 ? (slot % 2 == 0 ? 8 * 60 + 30 : 19 * 60 + 30) : -1; save(a, g); schedule(a); rebuild[0].run(); return; }
                        int cur = g.t[slot] < 0 ? (slot % 2 == 0 ? 8 * 60 + 30 : 19 * 60 + 30) : g.t[slot];
                        new TimePickerDialog(a, new TimePickerDialog.OnTimeSetListener() { @Override public void onTimeSet(TimePicker tp, int h, int m) { g.t[slot] = h * 60 + m; save(a, g); schedule(a); rebuild[0].run(); } }, cur / 60, cur % 60, true).show(); } }).show(); } }));
            }
            long[] n = next(System.currentTimeMillis(), TimeZone.getDefault(), g);
            col.addView(note(a, n == null ? "Next reminder: none" : "Next reminder: " + new java.text.SimpleDateFormat("EEE d MMM HH:mm", Locale.UK).format(new java.util.Date(n[0])) + "  (" + SLOT[(int) n[1]] + ")", 13));
            col.addView(row(a, "Send a test reminder now", "Test", new View.OnClickListener() { @Override public void onClick(View v) {
                if (Build.VERSION.SDK_INT >= 33) { try { a.requestPermissions(new String[] { "android.permission.POST_NOTIFICATIONS" }, 4108); } catch (Throwable ignored) { } }
                post(a.getApplicationContext(), true); } }));
        } };
        rebuild[0].run();
        ScrollView sv = new ScrollView(a); sv.addView(col);
        dlg[0] = new AlertDialog.Builder(a).setTitle("Reading reminders").setView(sv).setPositiveButton("Done", null).create(); dlg[0].show();
    }

    private static TextView note(Activity a, String s, int sp) { TextView t = new TextView(a); t.setText(s); t.setTextSize(sp); t.setPadding(0, 6, 0, 10); return t; }
    private static LinearLayout row(Activity a, String label, String value, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(a); r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL); int p = Math.round(10 * a.getResources().getDisplayMetrics().density); r.setPadding(0, p, 0, p);
        TextView l = new TextView(a); l.setText(label); l.setTextSize(15); r.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView v = new TextView(a); v.setText(value + "  \u25be"); v.setTextSize(15); v.setTypeface(null, android.graphics.Typeface.BOLD); r.addView(v, new LinearLayout.LayoutParams(-2, -2));
        r.setOnClickListener(click); r.setContentDescription(label + ", " + value + ", double tap to change"); return r;
    }
}
