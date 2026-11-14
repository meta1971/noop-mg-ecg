package com.noop.mgecg;

// FILE VERSION 0.56.0 (14 Nov 2026): NEW. The phone-side half of HEART WATCH (AlertEngine is the rule). It gathers what the strap has stored: the nights (night_stats), the 5-minute pulse-regularity windows of the last 48 h
// (rhythm_win, written by IrregularWatch) and the last quiet ten minutes of heart rate (r18), runs AlertEngine, keeps the answer for the Today ring, and posts a notification for a CHECK of the night signs or of the heart rate at rest.
// A CHECK of the pulse rhythm is NOT posted here: IrregularWatch posts its own "Irregular pulse pattern" notification for it. Research only, not a medical device.

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class AlertWatch {
    private AlertWatch() {}

    static final String PREFS = "heartwatch", CHANNEL = "heart_watch", TODAY_PREFS = "today";
    static final int ID_HR = 4411, ID_DRIFT = 4412;
    static final long NOTE_GAP_S = 12L * 3600L;
    private static final int KEEP_NIGHTS = 40;

    private static SharedPreferences sp(Context c) { return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    static boolean on(Context c) { return c.getApplicationContext().getSharedPreferences(TODAY_PREFS, Context.MODE_PRIVATE).getBoolean("n_heartwatch", true); }
    static void setOn(Context c, boolean v) { c.getApplicationContext().getSharedPreferences(TODAY_PREFS, Context.MODE_PRIVATE).edit().putBoolean("n_heartwatch", v).apply(); }

    // ------------------------------------------------------------------ gathering
    /** rhythm_win rows {start, nr x1000, n, hr, flags} from the last 48 hours; empty when the table does not exist yet. */
    static List<long[]> rhythmRows(Context c, long nowTs) {
        List<long[]> out = new ArrayList<long[]>();
        try {
            Cursor cu = DayLog.db(c).rawQuery("SELECT start, nr, n, hr, flags FROM rhythm_win WHERE start >= ? ORDER BY start", new String[] { String.valueOf(nowTs - AlertEngine.SPAN_S) });
            try { while (cu.moveToNext()) out.add(new long[] { cu.getLong(0), cu.getLong(1), cu.getLong(2), cu.getLong(3), cu.getLong(4) }); } finally { cu.close(); }
        } catch (Throwable ignored) { }
        return out;
    }

    /** {median heart rate, newest record unix second} of the last quiet ten minutes, or {NaN, 0}. Quiet = at least 500 records in the last 600 s, 90 % of them with the band's STILL or SLEEP state, movement under 0.05 g in at most 10 %,
     *  no activity class above 0, and a heart rate in at least 70 % of the seconds (the rule of MG_alert_proto.py, part C). */
    static double[] restHr(Context c) {
        try {
            SQLiteDatabase d = DayLog.db(c);
            long newest = 0;
            Cursor q = d.rawQuery("SELECT MAX(ts) FROM r18", null);
            try { if (q.moveToFirst()) newest = q.getLong(0); } finally { q.close(); }
            if (newest <= 0) return new double[] { Double.NaN, 0 };
            q = d.rawQuery("SELECT rec FROM r18 WHERE ts > ? AND ts <= ? ORDER BY ts", new String[] { String.valueOf(newest - 600L), String.valueOf(newest) });
            int tot = 0, quiet = 0, mo = 0; boolean act = false; List<Double> hr = new ArrayList<Double>();
            try {
                while (q.moveToNext()) {
                    byte[] f = q.getBlob(0); if (!R18Codec.isR18(f)) continue;
                    tot++;
                    int st = R18Codec.state(f); if (st == 1 || st == 2) quiet++;
                    float dyn = R18Codec.f32(f, 41); if (dyn >= 0.05f) mo++;
                    if (R18Codec.u8(f, 63) > 0) act = true;
                    int h = R18Codec.hr(f); if (h > 30 && (byte) R18Codec.u8(f, 108) != -128) hr.add((double) h);
                }
            } finally { q.close(); }
            if (tot < 500 || quiet < 0.9 * tot || mo > 0.10 * tot || act || hr.size() < 0.7 * tot) return new double[] { Double.NaN, 0 };
            double[] a = new double[hr.size()]; for (int i = 0; i < a.length; i++) a[i] = hr.get(i);
            java.util.Arrays.sort(a); int n = a.length;
            return new double[] { n % 2 == 1 ? a[n / 2] : 0.5 * (a[n / 2 - 1] + a[n / 2]), newest };
        } catch (Throwable t) { return new double[] { Double.NaN, 0 }; }
    }

    /** The Today path: nights = HealthModel.Data.nights (the newest included). Reads two small tables; call off the main thread. */
    static AlertEngine.Result current(Context c, List<TodayModel.NightStat> nights) {
        long now = System.currentTimeMillis() / 1000L;
        double[] rh = restHr(c);
        return AlertEngine.compute(nights, now, rhythmRows(c, now), rh[0], (long) rh[1]);
    }

    // ------------------------------------------------------------------ the night cache (background path)
    private static String enc(double x) { return Double.isNaN(x) ? "" : String.valueOf(x); }
    private static double dec(String s) { return s.isEmpty() ? Double.NaN : Double.parseDouble(s); }

    private static List<TodayModel.NightStat> readCache(SharedPreferences p) {
        List<TodayModel.NightStat> out = new ArrayList<TodayModel.NightStat>();
        for (String ln : p.getString("nights", "").split("\n")) {
            String[] q = ln.split(",", -1); if (q.length != 7) continue;
            try {
                TodayModel.NightStat s = new TodayModel.NightStat();
                s.bed = Long.parseLong(q[0]); s.tstH = dec(q[1]); s.hrLow5 = dec(q[2]); s.rmssd = dec(q[3]); s.skin = dec(q[4]); s.br = dec(q[5]); s.spo2Median = Integer.parseInt(q[6]);
                out.add(s);
            } catch (Exception ignored) { }
        }
        return out;
    }

    /** Recomputes the night list once the newest sleep has ended 20 minutes ago and is not the one cached (about once a night). Slow (it reads the stored nights): background thread only. */
    private static void refreshNights(Context c, SharedPreferences p, long now) {
        long maxSleep = 0;
        Cursor q = DayLog.db(c).rawQuery("SELECT MAX(ts) FROM r18 WHERE state = 2", null);
        try { if (q.moveToFirst()) maxSleep = q.getLong(0); } finally { q.close(); }
        if (maxSleep <= 0 || now - maxSleep < 1200L) return;
        boolean empty = p.getString("nights", "").isEmpty();
        if (!empty && maxSleep == p.getLong("nc_sleep", 0L) && now - p.getLong("nc_at", 0L) < 24L * 3600L) return;
        List<TodayModel.NightStat> ns = DayLog.nightStats(c);
        SharedPreferences tp = c.getApplicationContext().getSharedPreferences(TODAY_PREFS, Context.MODE_PRIVATE);
        TodayModel.Day d = DayLog.today(c, tp.getInt("maxhr", 167), tp.getFloat("goal", 8.0f));
        if (d != null && d.night != null) {
            TodayModel.NightStat s = TodayModel.statOf(d.night, d.spo2);
            for (int i = ns.size() - 1; i >= 0; i--) if (Math.abs(ns.get(i).bed - s.bed) < 6L * 3600L) ns.remove(i);       // the same night seen twice
            ns.add(s);
        }
        Collections.sort(ns, new Comparator<TodayModel.NightStat>() { @Override public int compare(TodayModel.NightStat a, TodayModel.NightStat b) { return Long.compare(a.bed, b.bed); } });
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, ns.size() - KEEP_NIGHTS); i < ns.size(); i++) {
            TodayModel.NightStat s = ns.get(i);
            sb.append(s.bed).append(',').append(enc(s.tstH)).append(',').append(enc(s.hrLow5)).append(',').append(enc(s.rmssd)).append(',').append(enc(s.skin)).append(',').append(enc(s.br)).append(',').append(s.spo2Median).append('\n');
        }
        p.edit().putString("nights", sb.toString()).putLong("nc_sleep", maxSleep).putLong("nc_at", now).apply();
    }

    /** Called from Daily.tick's thread (at most every ten minutes). Keeps the latest answer and posts the notifications. */
    static void tick(Context c0) {
        try {
            Context c = c0.getApplicationContext(); SharedPreferences p = sp(c);
            long now = System.currentTimeMillis() / 1000L;
            try { refreshNights(c, p, now); } catch (Throwable ignored) { }
            AlertEngine.Result r = current(c, readCache(p));
            p.edit().putInt("tier", r.tier).putFloat("score", Double.isNaN(r.score) ? -1f : (float) r.score).putLong("at", now).putString("cause", r.cause).putString("headline", r.headline).apply();
            if (!on(c)) return;
            if (r.rest.tier == AlertEngine.CHECK) once(c, p, "hr", ID_HR, now, r.restHr >= AlertEngine.HI_CHECK ? "High heart rate at rest" : "Low heart rate", r.rest.line + " If you feel unwell, get help. Otherwise rest and look again in half an hour.");
            if (r.drift.tier == AlertEngine.CHECK) once(c, p, "drift", ID_DRIFT, now, "Heart Watch: several night signs are unusual", r.drift.line + " Take it easy and watch how you feel. Not a diagnosis.");
        } catch (Throwable ignored) { }
    }

    private static void once(Context c, SharedPreferences p, String key, int id, long now, String title, String text) {
        if (now - p.getLong("note_" + key, 0L) < NOTE_GAP_S) return;
        p.edit().putLong("note_" + key, now).apply();
        post(c, id, title, text);
    }

    private static void post(Context c, int id, String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "Heart Watch", NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("Unusual heart rate at rest or several unusual night signs at once");
                nm.createNotificationChannel(ch);
            }
            Intent open = new Intent(c, HealthActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
            b.setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(pi).setAutoCancel(true);
            nm.notify(id, b.build());
        } catch (Throwable ignored) { }
    }

    /** Light-weight line for other screens: the last tier the background check stored, or null. */
    static String lastLine(Context c) {
        SharedPreferences p = sp(c);
        long at = p.getLong("at", 0L);
        if (at <= 0L) return null;
        int t = p.getInt("tier", AlertEngine.NONE);
        return String.format(Locale.UK, "Heart Watch: %s", t == AlertEngine.CHECK ? "check" : t == AlertEngine.WATCH ? "watch" : t == AlertEngine.GREEN ? "nothing unusual" : "learning");
    }
}
