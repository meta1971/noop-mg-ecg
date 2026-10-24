package com.noop.mgecg;

// FILE VERSION 0.2.0 (23 Oct 2026): loads days from the day log (per-second rows and, for old days, the one-minute rows kept after compaction),
// keeps the Activity settings and your edits, caches finished days, compacts old days safely (archive first, then keep one row a minute),
// and exports a day for tuning. Research only.

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

final class ActivityStore {
    private ActivityStore() {}

    static final String PREFS = "activity";
    static final String CACHE_VERSION = "2";
    static final String EXPORT_FOLDER = "MGactivity";

    // ---- SQL, kept as constants so they can be checked against a real SQLite file off the phone
    static final String SQL_CREATE_MIN = "CREATE TABLE IF NOT EXISTS r18_min (ts INTEGER PRIMARY KEY, n INTEGER, hr REAL, hr_min INTEGER, hr_max INTEGER, dyn REAL, "
            + "steps0 INTEGER, steps1 INTEGER, act INTEGER, state INTEGER, skin REAL)";
    static final String SQL_DAY_SECONDS = "SELECT ts, hr, steps, act, state, skin, rec FROM r18 WHERE ts >= ? AND ts < ? ORDER BY ts";
    static final String SQL_DAY_MINUTES = "SELECT ts, n, hr, hr_min, hr_max, dyn, steps0, steps1, act, state, skin FROM r18_min WHERE ts >= ? AND ts < ? ORDER BY ts";
    static final String SQL_INSERT_MIN = "INSERT OR REPLACE INTO r18_min (ts, n, hr, hr_min, hr_max, dyn, steps0, steps1, act, state, skin) VALUES (?,?,?,?,?,?,?,?,?,?,?)";
    static final String SQL_DELETE_DAY = "DELETE FROM r18 WHERE ts >= ? AND ts < ?";
    static final String SQL_OLDEST = "SELECT MIN(ts) FROM r18";
    static final String SQL_OLDEST_MIN = "SELECT MIN(ts) FROM r18_min";
    static final String SQL_NEWEST = "SELECT MAX(ts) FROM r18";

    // ================================================================ types

    static final class Settings {
        int age = 53;
        double restOverride = Double.NaN;      // NaN = automatic
        int activeGoalMin = 240;
        boolean autoCompact = false;           // off until you turn it on: compaction deletes per-second rows (after archiving them)
        int keepDays = 60;
    }

    static final class DayData {
        long dayStart, dayEnd, newest;
        boolean hasData;
        int dismissed;
        ActivityDetector.Result r;
    }

    /** What the long views need from one day. */
    static final class Summary {
        long dayStart;
        boolean ok, known;                     // ok = has data; known = worked out (false = not read yet)
        int validMin, activeMin, lightMin, moderateMin, hardMin, bouts, longestMin, elevatedStillMin;
        double load, rest;
        int[] hourly = new int[24];            // active minutes per hour since 04:00
    }

    // ================================================================ settings

    static SharedPreferences sp(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static Settings settings(Context c) {
        SharedPreferences p = sp(c);
        Settings s = new Settings();
        s.age = p.getInt("age", 53);
        float ro = p.getFloat("rest_override", Float.NaN);
        s.restOverride = Float.isNaN(ro) ? Double.NaN : ro;
        s.activeGoalMin = p.getInt("goal_min", 240);
        s.autoCompact = p.getBoolean("auto_compact", false);
        s.keepDays = p.getInt("keep_days", 60);
        return s;
    }

    static void save(Context c, Settings s) {
        SharedPreferences.Editor e = sp(c).edit();
        e.putInt("age", s.age);
        e.putFloat("rest_override", Double.isNaN(s.restOverride) ? Float.NaN : (float) s.restOverride);
        e.putInt("goal_min", s.activeGoalMin);
        e.putBoolean("auto_compact", s.autoCompact);
        e.putInt("keep_days", s.keepDays);
        e.apply();
    }

    /** Detector parameters: the typed resting HR if there is one, else the median of recent days' estimates, else automatic. */
    static ActivityDetector.Params params(Context c, Settings s) {
        ActivityDetector.Params p = new ActivityDetector.Params();
        p.age = s.age;
        if (!Double.isNaN(s.restOverride)) p.restHr = s.restOverride;
        else {
            double b = baseline(c);
            if (!Double.isNaN(b)) p.restHr = b;
        }
        return p;
    }

    /** Fingerprint of everything that changes a day's numbers, so cached days are redone when a setting changes. */
    static String fingerprint(ActivityDetector.Params p) {
        return CACHE_VERSION + ":" + p.age + ":" + (Double.isNaN(p.restHr) ? "auto" : String.valueOf(Math.round(p.restHr)));
    }

    // ================================================================ resting HR baseline

    /** Median of up to 7 recent daily resting-HR estimates (needs 3), or NaN. */
    static double baseline(Context c) {
        Map<String, ?> all = sp(c).getAll();
        List<Long> keys = new ArrayList<Long>();
        for (String k : all.keySet()) if (k.startsWith("rest_")) { try { keys.add(Long.parseLong(k.substring(5))); } catch (Exception ignored) { } }
        if (keys.size() < 3) return Double.NaN;
        Collections.sort(keys, Collections.reverseOrder());
        List<Double> v = new ArrayList<Double>();
        for (int i = 0; i < Math.min(7, keys.size()); i++) {
            Object o = all.get("rest_" + keys.get(i));
            if (o instanceof Float) v.add(((Float) o).doubleValue());
        }
        if (v.size() < 3) return Double.NaN;
        Collections.sort(v);
        return v.get(v.size() / 2);
    }

    static void rememberRest(Context c, long dayStart, double rest) {
        if (Double.isNaN(rest)) return;
        sp(c).edit().putFloat("rest_" + dayStart, (float) rest).apply();
    }

    // ================================================================ days

    /** Local 04:00 starts, the same day boundary the Today screen uses. */
    static long shiftDay(long dayStart, int days) {
        GregorianCalendar g = new GregorianCalendar();
        g.setTimeInMillis(dayStart * 1000L);
        g.add(GregorianCalendar.DAY_OF_MONTH, days);
        return g.getTimeInMillis() / 1000L;
    }

    private static volatile boolean minTableReady;

    static SQLiteDatabase db(Context c) {
        SQLiteDatabase d = DayLog.db(c);
        if (!minTableReady) {
            try { d.execSQL(SQL_CREATE_MIN); minTableReady = true; } catch (Throwable ignored) { }
        }
        return d;
    }

    static long newestTs(Context c) {
        long newest = 0;
        try {
            Cursor q = db(c).rawQuery(SQL_NEWEST, null);
            if (q.moveToFirst()) newest = q.getLong(0);
            q.close();
        } catch (Throwable ignored) { }
        return newest;
    }

    static long oldestTs(Context c) {
        long o = Long.MAX_VALUE;
        try {
            SQLiteDatabase d = db(c);
            Cursor q = d.rawQuery(SQL_OLDEST, null);
            if (q.moveToFirst() && !q.isNull(0)) o = Math.min(o, q.getLong(0));
            q.close();
            q = d.rawQuery(SQL_OLDEST_MIN, null);
            if (q.moveToFirst() && !q.isNull(0)) o = Math.min(o, q.getLong(0));
            q.close();
        } catch (Throwable ignored) { }
        return o == Long.MAX_VALUE ? 0 : o;
    }

    /** Minutes since the newest logged second, or -1 when nothing has been logged. */
    static long staleMinutes(Context c) {
        long n = newestTs(c);
        return n <= 0 ? -1 : Math.max(0, (System.currentTimeMillis() / 1000L - n) / 60L);
    }

    static DayData loadDay(Context c, long dayStart, ActivityDetector.Params p) {
        DayData out = new DayData();
        out.dayStart = dayStart;
        out.dayEnd = shiftDay(dayStart, 1);
        out.newest = newestTs(c);
        long[] ts = new long[0]; int[] hr = new int[0], st = new int[0]; double[] dyn = new double[0];
        try {
            SQLiteDatabase d = db(c);
            String[] args = {String.valueOf(dayStart), String.valueOf(out.dayEnd)};
            // 1) old days that were compacted: one row a minute, expanded back to seconds
            List<ActivityTrends.MinuteRow> mins = new ArrayList<ActivityTrends.MinuteRow>();
            Cursor q = d.rawQuery(SQL_DAY_MINUTES, args);
            while (q.moveToNext()) {
                ActivityTrends.MinuteRow m = new ActivityTrends.MinuteRow();
                m.ts = q.getLong(0); m.n = q.getInt(1);
                m.hr = q.isNull(2) ? Double.NaN : q.getDouble(2);
                m.hrMin = q.getInt(3); m.hrMax = q.getInt(4);
                m.dyn = q.isNull(5) ? Double.NaN : q.getDouble(5);
                m.steps0 = q.getInt(6); m.steps1 = q.getInt(7); m.act = q.getInt(8); m.state = q.getInt(9);
                m.skin = q.isNull(10) ? Double.NaN : q.getDouble(10);
                mins.add(m);
            }
            q.close();
            ActivityTrends.Seconds ex = mins.isEmpty() ? null : ActivityTrends.expand(mins);
            // 2) per-second rows
            long[] t2 = new long[4096]; int[] h2 = new int[4096], s2 = new int[4096]; double[] d2 = new double[4096];
            int n = 0;
            q = d.rawQuery(SQL_DAY_SECONDS, args);
            long last = -1;
            while (q.moveToNext()) {
                long t = q.getLong(0);
                if (t == last) continue;
                last = t;
                if (n == t2.length) { t2 = Arrays.copyOf(t2, n * 2); h2 = Arrays.copyOf(h2, n * 2); s2 = Arrays.copyOf(s2, n * 2); d2 = Arrays.copyOf(d2, n * 2); }
                t2[n] = t;
                h2[n] = q.isNull(1) ? 0 : q.getInt(1);
                s2[n] = q.isNull(2) ? 0 : q.getInt(2);
                byte[] rec = q.isNull(6) ? null : q.getBlob(6);
                double dv = (rec != null && rec.length == 124) ? R18Codec.f32(rec, 41) : Double.NaN;
                d2[n] = (Double.isNaN(dv) || Double.isInfinite(dv)) ? Double.NaN : dv;
                n++;
            }
            q.close();
            // 3) merge: per-second rows win where both exist
            if (ex == null) { ts = Arrays.copyOf(t2, n); hr = Arrays.copyOf(h2, n); st = Arrays.copyOf(s2, n); dyn = Arrays.copyOf(d2, n); }
            else if (n == 0) { ts = ex.ts; hr = ex.hr; st = ex.steps; dyn = ex.dyn; }
            else {
                long lo = t2[0], hi = t2[n - 1];
                int keep = 0;
                for (int i = 0; i < ex.ts.length; i++) if (ex.ts[i] < lo || ex.ts[i] > hi) keep++;
                final long[] tsm = new long[keep + n];
                int[] hrm = new int[keep + n], stm = new int[keep + n];
                double[] dym = new double[keep + n];
                int k = 0;
                for (int i = 0; i < ex.ts.length; i++) if (ex.ts[i] < lo || ex.ts[i] > hi) { tsm[k] = ex.ts[i]; hrm[k] = ex.hr[i]; stm[k] = ex.steps[i]; dym[k] = ex.dyn[i]; k++; }
                for (int i = 0; i < n; i++) { tsm[k] = t2[i]; hrm[k] = h2[i]; stm[k] = s2[i]; dym[k] = d2[i]; k++; }
                Integer[] idx = new Integer[tsm.length];
                for (int i = 0; i < idx.length; i++) idx[i] = i;
                Arrays.sort(idx, new java.util.Comparator<Integer>() { @Override public int compare(Integer a, Integer b) { return Long.compare(tsm[a], tsm[b]); } });
                ts = new long[tsm.length]; hr = new int[tsm.length]; st = new int[tsm.length]; dyn = new double[tsm.length];
                for (int i = 0; i < idx.length; i++) { ts[i] = tsm[idx[i]]; hr[i] = hrm[idx[i]]; st[i] = stm[idx[i]]; dyn[i] = dym[idx[i]]; }
            }
        } catch (Throwable ignored) { }
        out.hasData = ts.length > 60;
        if (!out.hasData) { out.r = new ActivityDetector.Result(); return out; }
        out.r = ActivityDetector.analyse(ts, hr, dyn, st, p);
        if (out.r.restEstimated && out.newest >= out.dayEnd) rememberRest(c, dayStart, out.r.restHr);   // finished days only
        out.dismissed = ActivityTrends.applyAnnotations(out.r, annotations(c));
        return out;
    }

    // ================================================================ your edits (rename, not activity)

    static List<ActivityTrends.Ann> annotations(Context c) {
        List<ActivityTrends.Ann> out = new ArrayList<ActivityTrends.Ann>();
        String v = sp(c).getString("ann", "");
        if (v.isEmpty()) return out;
        for (String line : v.split("\n")) {
            String[] a = line.split("\\|", 4);
            if (a.length < 3) continue;
            try {
                ActivityTrends.Ann x = new ActivityTrends.Ann();
                x.start = Long.parseLong(a[0]); x.end = Long.parseLong(a[1]); x.dismissed = "1".equals(a[2]);
                x.label = a.length > 3 ? a[3] : "";
                out.add(x);
            } catch (Exception ignored) { }
        }
        return out;
    }

    /** Saves (or replaces) the edit for this period. label may be empty; dismissed removes it from the totals. Clears that day's cached summary. */
    static void annotate(Context c, long start, long end, boolean dismissed, String label) {
        List<ActivityTrends.Ann> all = annotations(c);
        List<ActivityTrends.Ann> keep = new ArrayList<ActivityTrends.Ann>();
        for (ActivityTrends.Ann a : all) if (ActivityTrends.match(Collections.singletonList(a), start, end) == null) keep.add(a);
        if (dismissed || (label != null && !label.trim().isEmpty())) {
            ActivityTrends.Ann n = new ActivityTrends.Ann();
            n.start = start; n.end = end; n.dismissed = dismissed;
            n.label = label == null ? "" : label.trim().replace("|", "/").replace("\n", " ");
            keep.add(n);
        }
        while (keep.size() > 400) keep.remove(0);
        StringBuilder b = new StringBuilder();
        for (ActivityTrends.Ann a : keep) b.append(a.start).append('|').append(a.end).append('|').append(a.dismissed ? 1 : 0).append('|').append(a.label).append('\n');
        sp(c).edit().putString("ann", b.toString()).remove("sum_" + TodayModel.dayStartOf(start)).apply();
    }

    /** Brings back every period you dismissed on this day, and clears that day's cached summary. */
    static void restoreDismissed(Context c, long from, long to) {
        List<ActivityTrends.Ann> keep = new ArrayList<ActivityTrends.Ann>();
        for (ActivityTrends.Ann a : annotations(c)) if (!(a.dismissed && a.start >= from && a.start < to)) keep.add(a);
        StringBuilder b = new StringBuilder();
        for (ActivityTrends.Ann a : keep) b.append(a.start).append('|').append(a.end).append('|').append(a.dismissed ? 1 : 0).append('|').append(a.label).append('\n');
        sp(c).edit().putString("ann", b.toString()).remove("sum_" + TodayModel.dayStartOf(from)).apply();
    }

    // ================================================================ summaries, cache, history

    static Summary summaryOf(DayData d) {
        Summary s = new Summary();
        s.dayStart = d.dayStart;
        s.known = true;
        if (d.r == null || !d.hasData) return s;
        s.ok = true;
        s.validMin = d.r.validMin; s.activeMin = d.r.activeMin;
        s.lightMin = d.r.levelMin[ActivityDetector.L_LIGHT]; s.moderateMin = d.r.levelMin[ActivityDetector.L_MODERATE]; s.hardMin = d.r.levelMin[ActivityDetector.L_HARD];
        s.bouts = d.r.bouts.size(); s.longestMin = d.r.longestBoutMin; s.elevatedStillMin = d.r.elevatedStillMin;
        s.load = d.r.load; s.rest = d.r.restHr;
        for (ActivityDetector.Epoch e : d.r.epochs) {
            if (e.kind != ActivityDetector.ACTIVE) continue;
            int h = (int) ((e.start - d.dayStart) / 3600L);
            if (h >= 0 && h < 24) s.hourly[h]++;
        }
        return s;
    }

    static Summary cached(Context c, long dayStart, String fp) {
        String v = sp(c).getString("sum_" + dayStart, null);
        if (v == null) return null;
        String[] a = v.split("\\|");
        if (a.length < 2 || !a[0].equals(fp)) return null;
        Summary s = new Summary();
        s.dayStart = dayStart; s.known = true;
        if (a[1].equals("none")) return s;
        if (a.length < 13) return null;
        try {
            s.ok = true;
            s.validMin = Integer.parseInt(a[1]); s.activeMin = Integer.parseInt(a[2]); s.lightMin = Integer.parseInt(a[3]);
            s.moderateMin = Integer.parseInt(a[4]); s.hardMin = Integer.parseInt(a[5]); s.bouts = Integer.parseInt(a[6]);
            s.longestMin = Integer.parseInt(a[7]); s.elevatedStillMin = Integer.parseInt(a[8]);
            s.load = Double.parseDouble(a[9]); s.rest = Double.parseDouble(a[10]);
            String[] h = a[11].split(",");
            for (int i = 0; i < 24 && i < h.length; i++) s.hourly[i] = Integer.parseInt(h[i]);
            return s;
        } catch (Exception e) { return null; }
    }

    static void putCache(Context c, Summary s, String fp) {
        if (s == null) return;
        if (!s.ok) { sp(c).edit().putString("sum_" + s.dayStart, fp + "|none").apply(); return; }
        StringBuilder h = new StringBuilder();
        for (int i = 0; i < 24; i++) h.append(i == 0 ? "" : ",").append(s.hourly[i]);
        String v = fp + "|" + s.validMin + "|" + s.activeMin + "|" + s.lightMin + "|" + s.moderateMin + "|" + s.hardMin + "|" + s.bouts + "|"
                + s.longestMin + "|" + s.elevatedStillMin + "|" + s.load + "|" + s.rest + "|" + h + "|end";
        sp(c).edit().putString("sum_" + s.dayStart, v).apply();
    }

    /** One day for the long views: from the cache when the day is over, else computed. */
    static Summary summaryFor(Context c, long dayStart, ActivityDetector.Params p, long newest) {
        long end = shiftDay(dayStart, 1);
        boolean finished = newest >= end;
        String fp = fingerprint(p);
        if (finished) { Summary s = cached(c, dayStart, fp); if (s != null) return s; }
        Summary s = summaryOf(loadDay(c, dayStart, p));
        if (finished) putCache(c, s, fp);
        return s;
    }

    static final class History {
        final List<Summary> days = new ArrayList<Summary>();   // oldest first, ending with todayStart
        int missing;                                            // finished days not read yet (budget ran out)
    }

    /**
     * nDays ending today. Finished days come from the cache; up to `budget` uncached ones are read now (each takes a second or two on a phone),
     * the rest are reported as missing so the screen can say it is still building. Days before the first logged second cost nothing.
     * `today` (already loaded by the caller) is inserted as the last entry.
     */
    static History history(Context c, ActivityDetector.Params p, long todayStart, Summary today, int nDays, int budget) {
        History h = new History();
        long newest = newestTs(c), oldest = oldestTs(c);
        String fp = fingerprint(p);
        for (int k = nDays - 1; k >= 0; k--) {
            long ds = shiftDay(todayStart, -k);
            if (k == 0 && today != null) { h.days.add(today); continue; }
            Summary s = new Summary();
            s.dayStart = ds;
            long end = shiftDay(ds, 1);
            if (oldest == 0 || end <= oldest) { s.known = true; h.days.add(s); continue; }            // before anything was logged
            Summary cs = (newest >= end) ? cached(c, ds, fp) : null;
            if (cs != null) { h.days.add(cs); continue; }
            if (budget > 0) {
                s = summaryFor(c, ds, p, newest);
                if (newest >= end) budget--;
                h.days.add(s);
            } else { h.missing++; h.days.add(s); }
        }
        return h;
    }

    // ================================================================ storage

    static long dbBytes(Context c) {
        long b = 0;
        try {
            File f = c.getDatabasePath("daylog.db");
            b += f.length();
            b += new File(f.getPath() + "-wal").length();
        } catch (Throwable ignored) { }
        return b;
    }

    static long archiveBytes(Context c) {
        long b = 0;
        try {
            File[] fs = archiveDir(c).listFiles();
            if (fs != null) for (File f : fs) b += f.length();
        } catch (Throwable ignored) { }
        return b;
    }

    static File archiveDir(Context c) {
        File base = c.getExternalFilesDir(null);
        if (base == null) base = c.getFilesDir();
        File d = new File(base, "archive");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static final class CompactResult {
        int days, minuteRows;
        long secondsDeleted, archiveBytes;
        String error = "";
    }

    interface Progress { void on(String text); }

    /**
     * Keeps the last keepDays days at full detail. Older whole days are first written to r18_YYYYMMDD.csv.gz in the app's archive folder
     * (the app's own CSV layout, readable in Python), and only after that file is complete are the per-second rows replaced by one row a minute.
     * Does at most maxDays days per call, oldest first, in one small transaction per day. Never touches ECG files or other tables.
     */
    static CompactResult compact(Context c, int keepDays, int maxDays, Progress pr) {
        CompactResult res = new CompactResult();
        try {
            SQLiteDatabase d = db(c);
            long cutoff = shiftDay(TodayModel.dayStartOf(System.currentTimeMillis() / 1000L), -Math.max(14, keepDays));
            for (int done = 0; done < maxDays; done++) {
                long oldest = 0;
                Cursor q = d.rawQuery(SQL_OLDEST, null);
                if (q.moveToFirst() && !q.isNull(0)) oldest = q.getLong(0);
                q.close();
                if (oldest <= 0 || oldest >= cutoff) break;
                long ds = TodayModel.dayStartOf(oldest);
                long de = shiftDay(ds, 1);
                if (de > cutoff) break;                                         // never a partial day inside the kept window
                if (pr != null) pr.on("Compacting " + new SimpleDateFormat("EEE d MMM yyyy", Locale.UK).format(new Date((ds + 6 * 3600L) * 1000L)) + "\u2026");
                int n = 0;
                long[] ts = new long[4096]; int[] hr = new int[4096], st = new int[4096], act = new int[4096], state = new int[4096];
                double[] dyn = new double[4096], skin = new double[4096];
                File arch = new File(archiveDir(c), "r18_" + new SimpleDateFormat("yyyyMMdd", Locale.UK).format(new Date((ds + 6 * 3600L) * 1000L)) + ".csv.gz");
                File tmp = new File(arch.getPath() + ".tmp");
                Writer w = new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(tmp), 1 << 16), StandardCharsets.UTF_8);
                try {
                    w.write(R18Codec.HEADER);
                    w.write('\n');
                    q = d.rawQuery(SQL_DAY_SECONDS, new String[] {String.valueOf(ds), String.valueOf(de)});
                    while (q.moveToNext()) {
                        if (n == ts.length) {
                            int m = n * 2;
                            ts = Arrays.copyOf(ts, m); hr = Arrays.copyOf(hr, m); st = Arrays.copyOf(st, m); act = Arrays.copyOf(act, m); state = Arrays.copyOf(state, m);
                            dyn = Arrays.copyOf(dyn, m); skin = Arrays.copyOf(skin, m);
                        }
                        ts[n] = q.getLong(0);
                        hr[n] = q.isNull(1) ? 0 : q.getInt(1);
                        st[n] = q.isNull(2) ? 0 : q.getInt(2);
                        act[n] = q.isNull(3) ? 0 : q.getInt(3);
                        state[n] = q.isNull(4) ? 0 : q.getInt(4);
                        skin[n] = q.isNull(5) ? Double.NaN : q.getDouble(5);
                        byte[] rec = q.isNull(6) ? null : q.getBlob(6);
                        double dv = Double.NaN;
                        if (rec != null && rec.length == 124) {
                            dv = R18Codec.f32(rec, 41);
                            if (Double.isInfinite(dv)) dv = Double.NaN;
                            w.write(R18Codec.csv(rec));
                            w.write('\n');
                        }
                        dyn[n] = dv;
                        n++;
                    }
                    q.close();
                } finally {
                    w.close();
                }
                if (n == 0) { tmp.delete(); break; }
                if (arch.exists()) arch.delete();
                if (!tmp.renameTo(arch)) { res.error = "could not write the archive file"; tmp.delete(); break; }
                List<ActivityTrends.MinuteRow> mins = ActivityTrends.decimate(Arrays.copyOf(ts, n), Arrays.copyOf(hr, n), Arrays.copyOf(dyn, n),
                        Arrays.copyOf(st, n), Arrays.copyOf(act, n), Arrays.copyOf(state, n), Arrays.copyOf(skin, n));
                d.beginTransaction();
                try {
                    for (ActivityTrends.MinuteRow m : mins) {
                        d.execSQL(SQL_INSERT_MIN, new Object[] {m.ts, m.n, Double.isNaN(m.hr) ? null : (Object) m.hr, m.hrMin, m.hrMax,
                                Double.isNaN(m.dyn) ? null : (Object) m.dyn, m.steps0, m.steps1, m.act, m.state, Double.isNaN(m.skin) ? null : (Object) m.skin});
                    }
                    d.execSQL(SQL_DELETE_DAY, new Object[] {ds, de});
                    d.setTransactionSuccessful();
                } finally {
                    d.endTransaction();
                }
                res.days++; res.minuteRows += mins.size(); res.secondsDeleted += n; res.archiveBytes += arch.length();
                try { Thread.sleep(150); } catch (InterruptedException ignored) { }          // let the logger write between days
            }
        } catch (Throwable t) {
            res.error = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
        }
        if (res.days > 0) sp(c).edit().putLong("last_compact", System.currentTimeMillis()).apply();
        return res;
    }

    /** Called when the Activity screen opens: compacts a few days at most, at most twice a day, only when you have switched it on. */
    static void maybeCompact(Context c, Settings s) {
        if (!s.autoCompact) return;
        long last = sp(c).getLong("last_compact", 0L);
        if (System.currentTimeMillis() - last < 12L * 3600L * 1000L) return;
        compact(c, s.keepDays, 3, null);
        sp(c).edit().putLong("last_compact", System.currentTimeMillis()).apply();
    }

    /** Hands the freed pages back to the file system. Slow on a large file and needs free space about the size of the database; manual only. */
    static String vacuum(Context c) {
        try {
            long before = dbBytes(c);
            db(c).execSQL("VACUUM");
            long after = dbBytes(c);
            return "Database " + mb(before) + " \u2192 " + mb(after);
        } catch (Throwable t) {
            return "Could not compact the file: " + t.getClass().getSimpleName();
        }
    }

    static String mb(long bytes) { return bytes >= 1024L * 1024L * 1024L ? String.format(Locale.UK, "%.2f GB", bytes / 1073741824.0) : String.format(Locale.UK, "%.0f MB", bytes / 1048576.0); }

    // ================================================================ export for tuning

    /** Writes two CSV files (minute by minute, and the periods with your edits) into Download/MGactivity and returns a message. */
    static String exportDay(Context c, DayData d) {
        try {
            if (d == null || d.r == null || !d.hasData) return "Nothing to export for this day.";
            SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd", Locale.UK);
            String stamp = df.format(new Date((d.dayStart + 6 * 3600L) * 1000L));
            StringBuilder e = new StringBuilder("unix,time,hr_median,hr_p90,motion_g,pct_reserve,kind,level,steps,valid_seconds\n");
            SimpleDateFormat tf = new SimpleDateFormat("HH:mm", Locale.UK);
            for (ActivityDetector.Epoch x : d.r.epochs) {
                if (Double.isNaN(x.hr)) continue;
                e.append(x.start).append(',').append(tf.format(new Date(x.start * 1000L))).append(',').append(String.format(Locale.US, "%.1f,%.1f,%.4f,%.4f", x.hr, x.hrP90, x.motion, x.pct))
                        .append(',').append(new String[] {"no_data", "rest", "active", "raised_still"}[x.kind]).append(',').append(x.level).append(',').append(x.steps).append(',').append(x.validSeconds).append('\n');
            }
            StringBuilder p = new StringBuilder("start_unix,end_unix,start,end,minutes,active_min,avg_hr,peak_hr,pct_reserve,motion_g,steps_per_min,load,label\n");
            for (ActivityDetector.Bout b : d.r.bouts)
                p.append(b.start).append(',').append(b.end).append(',').append(tf.format(new Date(b.start * 1000L))).append(',').append(tf.format(new Date(b.end * 1000L))).append(',')
                        .append(b.durationMin).append(',').append(b.activeMin).append(',').append(String.format(Locale.US, "%.1f,%.1f,%.3f,%.3f,%.1f,%.1f", b.avgHr, b.peakHr, b.avgPct, b.motion, b.stepsPerMin, b.load))
                        .append(',').append('"').append(b.label.replace("\"", "'")).append("\"\n");
            p.append("# resting_hr,").append(String.format(Locale.US, "%.1f", d.r.restHr)).append(",hr_max,").append(String.format(Locale.US, "%.1f", d.r.hrMax)).append(",dismissed,").append(d.dismissed).append('\n');
            writeDownload(c, "activity_" + stamp + "_minutes.csv", e.toString());
            writeDownload(c, "activity_" + stamp + "_periods.csv", p.toString());
            return "Saved activity_" + stamp + "_minutes.csv and _periods.csv in Download/" + EXPORT_FOLDER;
        } catch (Throwable t) {
            return "Export failed: " + t.getClass().getSimpleName() + (t.getMessage() == null ? "" : " (" + t.getMessage() + ")");
        }
    }

    private static void writeDownload(Context c, String name, String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = c.getContentResolver();
            String rel = "Download/" + EXPORT_FOLDER + "/";
            try {
                cr.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?", new String[] {rel, name});
            } catch (Throwable ignore) { }
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            android.net.Uri u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (u == null) throw new Exception("could not create " + name);
            OutputStream os = cr.openOutputStream(u);
            if (os == null) throw new Exception("could not open " + name);
            try { os.write(bytes); } finally { os.close(); }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(u, done, null, null);
            return;
        }
        File dir = c.getExternalFilesDir(null);
        if (dir == null) throw new Exception("no storage folder");
        FileOutputStream os = new FileOutputStream(new File(dir, name));
        try { os.write(bytes); } finally { os.close(); }
    }

    // ================================================================ Home glance

    private static volatile long glanceAt;
    private static volatile DayData glanceDay;

    /** Today so far, cached for 90 seconds. Call from a background thread. */
    static synchronized DayData glanceDay(Context c) {
        long now = System.currentTimeMillis();
        if (glanceDay != null && now - glanceAt < 90000L) return glanceDay;
        Settings s = settings(c);
        glanceDay = loadDay(c, TodayModel.dayStartOf(now / 1000L), params(c, s));
        glanceAt = now;
        return glanceDay;
    }
}
