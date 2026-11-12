package com.noop.mgecg;

// FILE VERSION 0.53.7 (12 Nov 2026): what opens when a ring of the ECG page is tapped (decode log 180). HEART RATE: the heart rate by time of day over the last 14 days (morning, daytime, evening, asleep), still and awake,
// a 24-hour chart with today on it. HRV: the ECG readings by time of day and the strap's own resting 5-minute windows by clock hour, plus the night values. The third ring: the quality of the last readings.
// Rules (all stated on the screens): a 5-minute stretch counts as "still" when awake with under 5 steps a minute (the stress model's bins, StressModel); a resting HRV window is 5 minutes with at least 90 % of its seconds
// without an activity class, at most 25 steps, at most 10 % of seconds with the strap's movement bit, a 90th-percentile acceleration step under 0.02 g, optical quality 255 in at least 95 % of seconds, the pulse lock lost in at most 5 %,
// and at least 100 successive beat differences kept by the night rule (SleepNight.rmssd: under 12 % change, under 2.5 s apart). Tested on the 12 Nov export: without the extra guards the daytime windows read 40 ms against 14 at night (movement bit rho +0.70). Bands: morning 05-11, daytime 11-17, evening 17-23, night 23-05 local.

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

final class RingDetail {
    private RingDetail() {}

    static final String[] BAND = { "Morning", "Daytime", "Evening", "Night" };
    static final String[] SPAN = { "05\u201311", "11\u201317", "17\u201323", "23\u201305" };
    static final int DAYS = 14, MIN_BIN_SEC = 150, MIN_HOUR_N = 3;
    static final double QUIET_SPM = 5.0; static final int RULES = 2;      // RULES: bump when the resting-window rules change; the stored windows are then rebuilt
    private static final int GREEN = HealthViews.GREEN, GOLD = HealthViews.GOLD, BLUE = HealthViews.BLUE, ROSE = HealthViews.ROSE, VIOLET = HealthViews.VIOLET, INK = HealthViews.INK, MUT = HealthViews.MUT;
    private static final int TRACK = 0xFF161B22, GRID = 0x22FFFFFF, GREY = 0xFF8A93A0;

    static int bandOf(int hour) { return hour >= 5 && hour < 11 ? 0 : hour >= 11 && hour < 17 ? 1 : hour >= 17 && hour < 23 ? 2 : 3; }

    // ------------------------------------------------------------------ statistics (pure)
    static final class Stat { int n; double median = Double.NaN, p25 = Double.NaN, p75 = Double.NaN, lo = Double.NaN, hi = Double.NaN; }

    private static double pct(double[] s, double p) { double x = p * (s.length - 1); int i = (int) Math.floor(x); int j = Math.min(s.length - 1, i + 1); return s[i] + (x - i) * (s[j] - s[i]); }

    static Stat stat(List<Double> v) {
        Stat s = new Stat(); if (v == null || v.isEmpty()) return s;
        double[] a = new double[v.size()]; for (int i = 0; i < a.length; i++) a[i] = v.get(i); Arrays.sort(a);
        s.n = a.length; s.median = pct(a, 0.5); s.p25 = pct(a, 0.25); s.p75 = pct(a, 0.75); s.lo = a[0]; s.hi = a[a.length - 1]; return s;
    }

    private static int hourOf(long t, TimeZone tz) { Calendar c = Calendar.getInstance(tz, Locale.UK); c.setTimeInMillis(t * 1000L); return c.get(Calendar.HOUR_OF_DAY); }
    private static double hourFrac(long t, TimeZone tz) { Calendar c = Calendar.getInstance(tz, Locale.UK); c.setTimeInMillis(t * 1000L); return c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60.0; }
    static long localMidnight(long t, TimeZone tz) { Calendar c = Calendar.getInstance(tz, Locale.UK); c.setTimeInMillis(t * 1000L); c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0); return c.getTimeInMillis() / 1000L; }

    private static List<List<Double>> lists(int n) { List<List<Double>> l = new ArrayList<List<Double>>(); for (int i = 0; i < n; i++) l.add(new ArrayList<Double>()); return l; }
    private static Stat[] stats(List<List<Double>> l) { Stat[] s = new Stat[l.size()]; for (int i = 0; i < s.length; i++) s[i] = stat(l.get(i)); return s; }
    private static double[] fill(int n) { double[] a = new double[n]; Arrays.fill(a, Double.NaN); return a; }

    // ------------------------------------------------------------------ heart rate by time of day (pure)
    static final class Hr {
        Stat[] still = new Stat[4], awake = new Stat[4]; Stat asleep = new Stat(), lowest = new Stat();
        final double[] stillMed = fill(24), stillLo = fill(24), stillHi = fill(24), awakeMed = fill(24), today = fill(24);
        int days, stillBins, todayBins;
    }

    /** bins: the stress model's 5-minute bins; only bins of the last [days] days with at least MIN_BIN_SEC seconds of heart rate count. nightsLow: each night's lowest 5-minute heart rate. */
    static Hr hr(List<StressModel.Bin> bins, long now, TimeZone tz, int days, double[] nightsLow) {
        Hr r = new Hr(); long from = now - days * 86400L, today0 = localMidnight(now, tz);
        List<List<Double>> still = lists(4), awake = lists(4), sH = lists(24), aH = lists(24), tH = lists(24); List<Double> sleep = new ArrayList<Double>(); java.util.HashSet<Long> dd = new java.util.HashSet<Long>();
        for (StressModel.Bin b : bins) {
            if (b.t < from || b.sec < MIN_BIN_SEC || Float.isNaN(b.hr) || b.hr < 30 || b.hr > 220) continue;
            int h = hourOf(b.t, tz), band = bandOf(h); dd.add(localMidnight(b.t, tz));
            if (!b.awake) { sleep.add((double) b.hr); continue; }
            awake.get(band).add((double) b.hr); aH.get(h).add((double) b.hr);
            if (b.spm < QUIET_SPM) { still.get(band).add((double) b.hr); sH.get(h).add((double) b.hr); r.stillBins++; if (b.t >= today0) { tH.get(h).add((double) b.hr); r.todayBins++; } }      // today's dots are still time too, so they sit on the same line
        }
        r.days = dd.size(); r.still = stats(still); r.awake = stats(awake); r.asleep = stat(sleep);
        for (int h = 0; h < 24; h++) {
            if (sH.get(h).size() >= MIN_HOUR_N) { Stat s = stat(sH.get(h)); r.stillMed[h] = s.median; r.stillLo[h] = s.p25; r.stillHi[h] = s.p75; }
            if (aH.get(h).size() >= MIN_HOUR_N) r.awakeMed[h] = stat(aH.get(h)).median;
            if (!tH.get(h).isEmpty()) r.today[h] = stat(tH.get(h)).median;
        }
        List<Double> nl = new ArrayList<Double>(); if (nightsLow != null) for (double v : nightsLow) if (!Double.isNaN(v) && v > 30) nl.add(v); r.lowest = stat(nl);
        return r;
    }

    // ------------------------------------------------------------------ HRV by time of day (pure)
    static final class Pt { long t; double v; String grade = "?", tag = ""; }
    static final class Hrv {
        Stat[] ecg = new Stat[4], strap = new Stat[4]; Stat night = new Stat();
        final double[] strapMed = fill(24), strapLo = fill(24), strapHi = fill(24); final int[] strapN = new int[24];
        final List<Pt> pts = new ArrayList<Pt>(); int ecgUsed, windows;
    }

    /** readings: ECG readings with a numeric HRV; grades A and B feed the band numbers, every reading is a dot. windows: resting strap windows {t, rmssd, hr, differences}. nights: each night's optical RMSSD. */
    static Hrv hrv(List<Pt> readings, List<double[]> windows, double[] nights, TimeZone tz, long now, int days) {
        Hrv r = new Hrv(); long from = now - days * 86400L; List<List<Double>> e = lists(4), s = lists(4), sh = lists(24);
        if (readings != null) for (Pt p : readings) {
            if (Double.isNaN(p.v) || p.v <= 0) continue; r.pts.add(p);
            if (p.t >= from && ("A".equals(p.grade) || "B".equals(p.grade))) { e.get(bandOf(hourOf(p.t, tz))).add(p.v); r.ecgUsed++; }
        }
        if (windows != null) for (double[] w : windows) {
            if ((long) w[0] < from || Double.isNaN(w[1]) || w[1] <= 0) continue; int h = hourOf((long) w[0], tz); s.get(bandOf(h)).add(w[1]); sh.get(h).add(w[1]); r.windows++;
        }
        r.ecg = stats(e); r.strap = stats(s);
        for (int h = 0; h < 24; h++) { r.strapN[h] = sh.get(h).size(); if (sh.get(h).size() >= MIN_HOUR_N) { Stat x = stat(sh.get(h)); r.strapMed[h] = x.median; r.strapLo[h] = x.p25; r.strapHi[h] = x.p75; } }
        List<Double> nl = new ArrayList<Double>(); if (nights != null) for (double v : nights) if (!Double.isNaN(v) && v > 0) nl.add(v); r.night = stat(nl);
        return r;
    }

    // ------------------------------------------------------------------ resting HRV windows from the day log (pure core + database)
    /** Feed the seconds that carry beat intervals, in time order; closed 5-minute windows that were resting and have enough clean differences come out as {t, rmssd, hr, differences}. */
    static final class WinAgg {
        final List<double[]> out = new ArrayList<double[]>();
        private long cur = -1; private int sec, act0, move, optq, lock, first = -1, last, hrN; private double hrSum; private final List<double[]> rr = new ArrayList<double[]>(); private final List<Double> acc = new ArrayList<Double>();
        void feed(long ts, int hr, int steps, int act, int[] rrMs, int n, int moveBit, int optQuality, int lockByte, float accel) {
            long w = ts / 300L * 300L; if (w != cur) { close(); cur = w; }
            sec++; if (act == 0) act0++; if ((moveBit & 1) != 0) move++; if (optQuality == 255) optq++; if (lockByte > 0) lock++; if (!Float.isNaN(accel) && !Float.isInfinite(accel)) acc.add((double) accel); else acc.add(9.0);
            if (first < 0) first = steps & 0xFFFF; last = steps & 0xFFFF; if (hr >= 30 && hr <= 220) { hrSum += hr; hrN++; }
            for (int k = 0; k < n; k++) if (rrMs[k] > 300 && rrMs[k] < 2000) rr.add(new double[] { ts + k * 0.01, rrMs[k] });
        }
        void finish() { close(); cur = -1; }
        private void close() {
            if (cur >= 0 && sec > 0) {
                int steps = (last - first) & 0xFFFF; double[] a = new double[acc.size()]; for (int i = 0; i < a.length; i++) a[i] = acc.get(i); Arrays.sort(a); double p90 = a.length == 0 ? 9.0 : pct(a, 0.9);
                boolean rest = act0 >= 0.9 * sec && steps <= 25 && move <= 0.10 * sec && p90 < 0.02 && optq >= 0.95 * sec && lock <= 0.05 * sec;
                if (rest && rr.size() > 100) { double rm = SleepNight.rmssd(rr, 0, rr.size(), 100); if (!Double.isNaN(rm)) out.add(new double[] { cur, rm, hrN > 0 ? hrSum / hrN : Double.NaN, rr.size() }); }
            }
            sec = 0; act0 = 0; move = 0; optq = 0; lock = 0; first = -1; last = 0; hrN = 0; hrSum = 0; rr.clear(); acc.clear();
        }
    }

    static void extendHrvBins(SQLiteDatabase db, long newest) {
        db.execSQL("CREATE TABLE IF NOT EXISTS hrv_bin (t INTEGER PRIMARY KEY, rmssd REAL, hr REAL, nd INTEGER)");
        db.execSQL("CREATE TABLE IF NOT EXISTS hrv_state (k TEXT PRIMARY KEY, v INTEGER)");
        long windowStart = newest - DAYS * 86400L, upto = 0, rules = 0; Cursor m = db.rawQuery("SELECT k, v FROM hrv_state", null);
        try { while (m.moveToNext()) { if ("upto".equals(m.getString(0))) upto = m.getLong(1); else if ("rules".equals(m.getString(0))) rules = m.getLong(1); } } finally { m.close(); }
        if (rules != RULES) { db.execSQL("DELETE FROM hrv_bin"); db.execSQL("DELETE FROM hrv_state"); upto = 0; }
        long from = Math.max(windowStart, upto > 0 ? upto - 300L : 0L), open = newest / 300L * 300L; WinAgg agg = new WinAgg();
        Cursor q = db.rawQuery("SELECT ts, hr, steps, act, substr(rec,24,1), substr(rec,25,8), substr(rec,41,1), substr(rec,42,4), substr(rec,82,1), substr(rec,106,1) FROM r18 WHERE ts >= ? AND ts < ? AND substr(rec,24,1) != x'00' ORDER BY ts", new String[] { String.valueOf(from), String.valueOf(open) });
        try {
            int[] rr = new int[4];
            while (q.moveToNext()) {
                byte[] c = q.getBlob(4), w = q.getBlob(5); if (c == null || c.length < 1 || w == null) continue; int n = Math.min(c[0] & 0xFF, Math.min(4, w.length / 2));
                for (int k = 0; k < n; k++) rr[k] = (w[2 * k] & 0xFF) | ((w[2 * k + 1] & 0xFF) << 8);
                byte[] oq = q.getBlob(6), ac = q.getBlob(7), mv = q.getBlob(8), lk = q.getBlob(9); float accel = Float.NaN;
                if (ac != null && ac.length >= 4) accel = Float.intBitsToFloat((ac[0] & 0xFF) | ((ac[1] & 0xFF) << 8) | ((ac[2] & 0xFF) << 16) | ((ac[3] & 0xFF) << 24));
                agg.feed(q.getLong(0), q.getInt(1), q.getInt(2), q.getInt(3), rr, n, mv != null && mv.length > 0 ? mv[0] & 0xFF : 0, oq != null && oq.length > 0 ? oq[0] & 0xFF : 0, lk != null && lk.length > 0 ? lk[0] & 0xFF : 0, accel);
            }
        } finally { q.close(); }
        agg.finish();
        db.beginTransaction();
        try {
            db.execSQL("DELETE FROM hrv_bin WHERE t >= ?", new Object[] { from });
            for (double[] w : agg.out) db.execSQL("INSERT OR REPLACE INTO hrv_bin (t, rmssd, hr, nd) VALUES (?,?,?,?)", new Object[] { (long) w[0], w[1], Double.isNaN(w[2]) ? null : (Object) Double.valueOf(w[2]), (long) w[3] });
            db.execSQL("DELETE FROM hrv_bin WHERE t < ?", new Object[] { windowStart - 9L * 86400L });
            db.execSQL("INSERT OR REPLACE INTO hrv_state (k, v) VALUES ('upto', ?)", new Object[] { open });
            db.execSQL("INSERT OR REPLACE INTO hrv_state (k, v) VALUES ('rules', ?)", new Object[] { (long) RULES });
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    static List<double[]> loadHrvBins(SQLiteDatabase db, long since) {
        List<double[]> out = new ArrayList<double[]>(); Cursor q = db.rawQuery("SELECT t, rmssd, hr, nd FROM hrv_bin WHERE t >= ? ORDER BY t", new String[] { String.valueOf(since) });
        try { while (q.moveToNext()) out.add(new double[] { q.getLong(0), q.getDouble(1), q.isNull(2) ? Double.NaN : q.getDouble(2), q.getLong(3) }); } finally { q.close(); }
        return out;
    }

    // ------------------------------------------------------------------ loading everything for a sheet (call off the main thread)
    static final class Data {
        Hr hr; Hrv hrv; List<Pt> readings = new ArrayList<Pt>(); long now; String error = "";
        // the latest reading
        long lastT; double lastHr = Double.NaN, lastHrv = Double.NaN; String lastGrade = "?";
        List<String[]> quality = new ArrayList<String[]>();      // date, grade, noise, HR, HRV
    }

    private static double num(String s) { try { return Double.parseDouble(s.trim()); } catch (Throwable t) { return Double.NaN; } }

    static Data load(Context c, List<NewLookActivity.Rep> reps, File dir) {
        Data d = new Data(); TimeZone tz = TimeZone.getDefault(); d.now = System.currentTimeMillis() / 1000L;
        try {
            for (NewLookActivity.Rep r : reps) {
                Pt p = new Pt(); p.t = r.timeMs / 1000L; p.v = num(r.hrv); p.grade = r.grade == null || r.grade.isEmpty() ? "?" : r.grade.substring(0, 1);
                try { long st = ProReportMaker.unixOf(r.file); if (st > 0) { p.t = st; Map<String, String> nt = ReadingNotes.forReading(dir, st); if (nt != null && nt.get("tag") != null) p.tag = nt.get("tag"); } } catch (Throwable ignored) { }
                d.readings.add(p);
            }
            if (!reps.isEmpty()) { NewLookActivity.Rep r0 = reps.get(0); d.lastT = d.readings.get(0).t; d.lastHr = num(r0.hr); d.lastHrv = num(r0.hrv); d.lastGrade = d.readings.get(0).grade; }
            SimpleDateFormat df = new SimpleDateFormat("d MMM HH:mm", Locale.UK);
            for (int i = 0; i < Math.min(14, reps.size()); i++) {
                NewLookActivity.Rep r = reps.get(i); String noise = "-";
                try { Map<String, String> nt = ReadingNotes.forReading(dir, d.readings.get(i).t); if (nt != null && nt.get("noise_8_25") != null) noise = nt.get("noise_8_25"); } catch (Throwable ignored) { }
                d.quality.add(new String[] { df.format(new Date(d.readings.get(i).t * 1000L)), r.grade, noise, r.hr, r.hrv });
            }
        } catch (Throwable t) { d.error = "readings: " + t; }
        try {
            SQLiteDatabase db = DayLog.db(c); long newest = 0; Cursor m = db.rawQuery("SELECT MAX(ts) FROM r18", null);
            try { if (m.moveToFirst() && !m.isNull(0)) newest = m.getLong(0); } finally { m.close(); }
            if (newest <= 0) newest = d.now;
            List<TodayModel.NightStat> nights = DayLog.nightStats(c); double[] low = new double[nights.size()], rm = new double[nights.size()];
            for (int i = 0; i < low.length; i++) { low[i] = nights.get(i).hrLow5; rm[i] = nights.get(i).rmssd; }
            List<StressModel.Bin> bins = new ArrayList<StressModel.Bin>(); try { StressModel.Result sr = StressModel.load(db, newest, nights); bins = sr.bins; } catch (Throwable t) { d.error += " stress bins: " + t; }
            d.hr = hr(bins, newest, tz, DAYS, low);
            List<double[]> wins = new ArrayList<double[]>(); try { extendHrvBins(db, newest); wins = loadHrvBins(db, newest - DAYS * 86400L); } catch (Throwable t) { d.error += " hrv windows: " + t; }
            d.hrv = hrv(d.readings, wins, rm, tz, newest, DAYS);
        } catch (Throwable t) { d.error += " day log: " + t; }
        if (d.hr == null) d.hr = hr(new ArrayList<StressModel.Bin>(), d.now, TimeZone.getDefault(), DAYS, null);
        if (d.hrv == null) d.hrv = hrv(d.readings, null, null, TimeZone.getDefault(), d.now, DAYS);
        return d;
    }

    // ------------------------------------------------------------------ text (pure)
    private static String f0(double v) { return Double.isNaN(v) ? "-" : String.valueOf(Math.round(v)); }
    private static String hhmm(long t) { return new SimpleDateFormat("HH:mm", Locale.UK).format(new Date(t * 1000L)); }
    static String bandRange(Stat s) { return s.n == 0 ? "-" : f0(s.p25) + "\u2013" + f0(s.p75); }

    /** One plain sentence on how the still heart rate differs between the parts of the day. */
    static String hrSentence(Hr h) {
        List<String> p = new ArrayList<String>(); for (int b = 0; b < 3; b++) if (h.still[b].n >= 6) p.add(f0(h.still[b].median) + " bpm in the " + BAND[b].toLowerCase(Locale.UK));
        if (p.size() < 2) return "Not enough still time in each part of the day yet (a part needs half an hour of still, awake time). It fills in as you wear the strap.";
        double lo = 1e9, hi = -1e9; int bl = -1, bh = -1; for (int b = 0; b < 3; b++) if (h.still[b].n >= 6) { if (h.still[b].median < lo) { lo = h.still[b].median; bl = b; } if (h.still[b].median > hi) { hi = h.still[b].median; bh = b; } }
        StringBuilder s = new StringBuilder("While still and awake your heart rate is "); for (int i = 0; i < p.size(); i++) s.append(i == 0 ? "" : i == p.size() - 1 ? " and " : ", ").append(p.get(i)).append("");
        s.append(". "); s.append(Math.round(hi - lo) >= 1 ? "It is lowest in the " + BAND[bl].toLowerCase(Locale.UK) + " and highest in the " + BAND[bh].toLowerCase(Locale.UK) + ", " + Math.round(hi - lo) + " bpm apart." : "It barely changes through the day.");
        return s.toString();
    }

    static String hrvSentence(Hrv h) {
        List<String> p = new ArrayList<String>(); for (int b = 0; b < 3; b++) if (h.ecg[b].n >= 2) p.add(f0(h.ecg[b].median) + " ms in the " + BAND[b].toLowerCase(Locale.UK) + " (" + h.ecg[b].n + ")");
        if (p.size() < 2) return "ECG readings so far: too few in each part of the day to compare (grade A or B, at least 2 each). Take readings at different times, seated, the same way.";
        return "Your ECG HRV (grade A and B readings, RMSSD) is " + join(p) + ".";
    }
    private static String join(List<String> p) { StringBuilder s = new StringBuilder(); for (int i = 0; i < p.size(); i++) s.append(i == 0 ? "" : i == p.size() - 1 ? " and " : ", ").append(p.get(i)); return s.toString(); }

    // ------------------------------------------------------------------ views
    private static TextView tx(Context c, String s, float sp, int col, boolean bold) { TextView t = new TextView(c); t.setText(s); t.setTextSize(sp); t.setTextColor(col); if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD); t.setLineSpacing(0, 1.2f); return t; }
    private static LinearLayout.LayoutParams lp(Context c, int w, int h, int topDp) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h); p.topMargin = (int) (topDp * c.getResources().getDisplayMetrics().density); return p; }
    private static LinearLayout col(Context c) { LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.VERTICAL); return l; }

    private static void bandRow(LinearLayout box, Context c, String name, String span, String main, String sub) {
        LinearLayout r = new LinearLayout(c); r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout l = col(c); l.addView(tx(c, name, 14, INK, true)); l.addView(tx(c, span, 11, MUT, false)); r.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout rt = col(c); TextView m = tx(c, main, 16, INK, true); m.setGravity(Gravity.END); rt.addView(m); TextView s = tx(c, sub, 11, MUT, false); s.setGravity(Gravity.END); rt.addView(s);
        r.addView(rt, new LinearLayout.LayoutParams(-2, -2)); box.addView(r, lp(c, -1, -2, 8));
    }

    private static void head(LinearLayout box, Context c, String s) { box.addView(tx(c, s.toUpperCase(Locale.UK), 11, MUT, true), lp(c, -1, -2, 16)); }

    static View hrView(Context c, Data d) {
        LinearLayout b = col(c); Hr h = d.hr; String err = d.error.isEmpty() ? "" : "\n(" + d.error.trim() + ")";
        if (!Double.isNaN(d.lastHr)) {
            int band = bandOf(hourOf(d.lastT, TimeZone.getDefault())); Stat st = h.still[band]; String cmp = st.n >= 6 ? " Your usual still heart rate in the " + BAND[band].toLowerCase(Locale.UK) + ": " + f0(st.median) + " bpm (middle half " + bandRange(st) + ")." : "";
            b.addView(tx(c, "Last reading, " + hhmm(d.lastT) + ": " + f0(d.lastHr) + " bpm." + cmp, 14, INK, false));
        }
        b.addView(tx(c, hrSentence(h), 13, INK, false), lp(c, -1, -2, 8));
        head(b, c, "By time of day, last " + DAYS + " days (" + h.days + " with data)");
        for (int k = 0; k < 3; k++) bandRow(b, c, BAND[k], SPAN[k], h.still[k].n == 0 ? "-" : f0(h.still[k].median) + " bpm", h.still[k].n == 0 ? "no still time yet" : "still, middle half " + bandRange(h.still[k]) + " \u00b7 awake overall " + f0(h.awake[k].median) + " \u00b7 " + (h.still[k].n * 5) + " min");
        bandRow(b, c, "Asleep", "all nights", h.asleep.n == 0 ? "-" : f0(h.asleep.median) + " bpm", h.asleep.n == 0 ? "no sleep yet" : "middle half " + bandRange(h.asleep) + " \u00b7 lowest 5 min of a night " + f0(h.lowest.median));
        head(b, c, "Heart rate by hour");
        b.addView(new HourChart(c, h.stillMed, h.stillLo, h.stillHi, h.awakeMed, h.today, BLUE, "bpm"), lp(c, -1, (int) (190 * c.getResources().getDisplayMetrics().density), 6));
        b.addView(tx(c, "Blue line: median while still and awake, band: middle half. Grey line: median awake at any movement. Dots: today, still time only.", 11, MUT, false), lp(c, -1, -2, 4));
        head(b, c, "How to read it");
        b.addView(tx(c, "Still means a 5-minute stretch awake with under 5 steps a minute. Movement, caffeine, meals, heat, illness and poor sleep all raise it, so compare the same kind of time. Hours with fewer than " + MIN_HOUR_N + " stretches are left blank." + err, 12, MUT, false));
        return b;
    }

    static View hrvView(Context c, Data d) {
        LinearLayout b = col(c); Hrv h = d.hrv; String err = d.error.isEmpty() ? "" : "\n(" + d.error.trim() + ")";
        if (!Double.isNaN(d.lastHrv)) {
            int band = bandOf(hourOf(d.lastT, TimeZone.getDefault())); Stat st = h.ecg[band]; String cmp = st.n >= 2 ? " Your " + BAND[band].toLowerCase(Locale.UK) + " ECG readings: median " + f0(st.median) + " ms (" + st.n + " readings, range " + f0(st.lo) + "\u2013" + f0(st.hi) + ")." : "";
            b.addView(tx(c, "Last reading, " + hhmm(d.lastT) + ": HRV " + f0(d.lastHrv) + " ms, grade " + d.lastGrade + "." + cmp, 14, INK, false));
        }
        b.addView(tx(c, hrvSentence(h), 13, INK, false), lp(c, -1, -2, 8));
        head(b, c, "ECG readings by time of day");
        for (int k = 0; k < 3; k++) bandRow(b, c, BAND[k], SPAN[k], h.ecg[k].n == 0 ? "-" : f0(h.ecg[k].median) + " ms", h.ecg[k].n == 0 ? "no grade A or B reading" : h.ecg[k].n + " readings \u00b7 range " + f0(h.ecg[k].lo) + "\u2013" + f0(h.ecg[k].hi));
        b.addView(new DotChart(c, h.pts, h.ecg), lp(c, -1, (int) (190 * c.getResources().getDisplayMetrics().density), 8));
        b.addView(tx(c, "Each dot is a reading at the time of day it was taken (green grade A or B, amber C, grey D). Bars: median of the grade A and B readings in that part of the day.", 11, MUT, false), lp(c, -1, -2, 4));
        head(b, c, "Strap at rest by clock hour, last " + DAYS + " days");
        if (h.windows == 0) b.addView(tx(c, "No resting 5-minute windows yet. The first count reads the last 14 days of beat intervals and fills in once the strap has recorded still time.", 12, MUT, false), lp(c, -1, -2, 6));
        else {
            for (int k = 0; k < 4; k++) bandRow(b, c, BAND[k], SPAN[k], h.strap[k].n == 0 ? "-" : f0(h.strap[k].median) + " ms", h.strap[k].n == 0 ? "no resting window" : h.strap[k].n + " windows \u00b7 middle half " + bandRange(h.strap[k]));
            b.addView(new HourChart(c, h.strapMed, h.strapLo, h.strapHi, fill(24), fill(24), VIOLET, "ms"), lp(c, -1, (int) (190 * c.getResources().getDisplayMetrics().density), 8));
            b.addView(tx(c, "Violet line: median RMSSD of the resting 5-minute windows in that hour, band: middle half.", 11, MUT, false), lp(c, -1, -2, 4));
        }
        StringBuilder ratio = new StringBuilder(); for (int k = 0; k < 3; k++) if (h.ecg[k].n >= 3 && h.strap[k].n >= 10 && h.ecg[k].median > 0) ratio.append(ratio.length() == 0 ? "" : ", ").append(BAND[k].toLowerCase(Locale.UK)).append(" ").append(String.format(Locale.UK, "%.1f", h.strap[k].median / h.ecg[k].median)).append("x");
        if (ratio.length() > 0) b.addView(tx(c, "Strap divided by ECG in the same part of the day: " + ratio + ". Above about 1.3 the strap is reading high there (wrist movement and weak optical signal add jitter): use it for trend, not for the value.", 12, GOLD, false), lp(c, -1, -2, 8));
        bandRow(b, c, "Whole night", "strap, last nights", h.night.n == 0 ? "-" : f0(h.night.median) + " ms", h.night.n == 0 ? "no night yet" : h.night.n + " nights \u00b7 range " + f0(h.night.lo) + "\u2013" + f0(h.night.hi));
        head(b, c, "How to read it");
        b.addView(tx(c, "The ECG number is RMSSD from a short seated reading; the strap number is RMSSD from its optical beat intervals in 5-minute windows with at most 25 steps, so the two are different measures: compare each with itself. HRV falls with movement, stress, alcohol, illness and a late meal, and is highest in deep sleep. Optical HRV overstates with any wrist movement." + err, 12, MUT, false));
        return b;
    }

    static View qualityView(Context c, Data d) {
        LinearLayout b = col(c);
        b.addView(tx(c, "Grade is the report's own quality mark. Noise is the strap's own 8\u201325 Hz microvolts between beats (lower is better; usual 15\u201327 on this strap).", 13, INK, false));
        head(b, c, "Last readings");
        if (d.quality.isEmpty()) b.addView(tx(c, "No readings yet.", 13, MUT, false), lp(c, -1, -2, 6));
        for (String[] q : d.quality) bandRow(b, c, q[0], "HR " + q[3] + " \u00b7 HRV " + q[4], "grade " + q[1], "noise " + q[2] + " uV");
        return b;
    }

    // ------------------------------------------------------------------ charts
    /** 24 hours across: a median line with a middle-half band, an optional second line and today's points. */
    static final class HourChart extends View {
        private final double[] med, lo, hi, alt, today; private final int col; private final String unit; private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); private final float dp;
        HourChart(Context c, double[] med, double[] lo, double[] hi, double[] alt, double[] today, int col, String unit) { super(c); this.med = med; this.lo = lo; this.hi = hi; this.alt = alt; this.today = today; this.col = col; this.unit = unit; dp = c.getResources().getDisplayMetrics().density; }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), left = 30 * dp, top = 8 * dp, right = w - 6 * dp, bottom = h - 18 * dp, cw = right - left, ch = bottom - top;
            p.setStyle(Paint.Style.FILL); p.setColor(TRACK); cv.drawRoundRect(new RectF(left, top, right, bottom), 6 * dp, 6 * dp, p);
            double mn = 1e9, mx = -1e9; int n = 0; double[][] all = { med, lo, hi, alt, today };
            for (double[] a : all) for (double v : a) if (!Double.isNaN(v)) { mn = Math.min(mn, v); mx = Math.max(mx, v); n++; }
            if (n < 2) { p.setColor(GREY); p.setTextSize(11 * dp); p.setTextAlign(Paint.Align.CENTER); cv.drawText("not enough data yet", w / 2f, h / 2f, p); return; }
            mn = Math.floor(mn - 2); mx = Math.ceil(mx + 2); if (mx - mn < 8) { double m = (mx + mn) / 2; mn = m - 4; mx = m + 4; }
            for (int b = 0; b < 3; b++) { int h0 = new int[] { 5, 11, 17 }[b], h1 = h0 + 6; p.setColor(b % 2 == 0 ? 0x0CFFFFFF : 0x00000000); cv.drawRect(left + cw * h0 / 24f, top, left + cw * h1 / 24f, bottom, p); }
            p.setColor(GRID); p.setStrokeWidth(1f * dp); for (int g = 0; g <= 8; g++) { float x = left + cw * g / 8f; cv.drawLine(x, top, x, bottom, p); }
            p.setColor(GREY); p.setTextSize(9.5f * dp); p.setTextAlign(Paint.Align.RIGHT); cv.drawText(String.valueOf((int) mx), left - 4 * dp, top + 9 * dp, p); cv.drawText(String.valueOf((int) mn), left - 4 * dp, bottom, p);
            p.setTextAlign(Paint.Align.CENTER); for (int g = 0; g <= 8; g += 2) cv.drawText(String.format(Locale.UK, "%02d", g * 3 % 24 == 0 && g > 0 ? 24 : g * 3), left + cw * g / 8f, h - 4 * dp, p);
            // middle-half band between the first and last hour, one polygon per run of known hours
            p.setStyle(Paint.Style.FILL); p.setColor((col & 0x00FFFFFF) | 0x33000000);
            int i = 0; while (i < 24) {
                if (Double.isNaN(lo[i]) || Double.isNaN(hi[i])) { i++; continue; } int j = i; while (j + 1 < 24 && !Double.isNaN(lo[j + 1]) && !Double.isNaN(hi[j + 1])) j++;
                Path pa = new Path(); for (int k = i; k <= j; k++) { float x = left + cw * (k + 0.5f) / 24f, y = (float) (bottom - ch * (hi[k] - mn) / (mx - mn)); if (k == i) pa.moveTo(x, y); else pa.lineTo(x, y); }
                for (int k = j; k >= i; k--) pa.lineTo(left + cw * (k + 0.5f) / 24f, (float) (bottom - ch * (lo[k] - mn) / (mx - mn))); pa.close(); cv.drawPath(pa, p); i = j + 1;
            }
            line(cv, alt, mn, mx, left, cw, bottom, ch, GREY, 1.5f); line(cv, med, mn, mx, left, cw, bottom, ch, col, 2.5f);
            p.setStyle(Paint.Style.FILL); p.setColor(0xFFFFFFFF);
            for (int k = 0; k < 24; k++) if (!Double.isNaN(today[k])) cv.drawCircle(left + cw * (k + 0.5f) / 24f, (float) (bottom - ch * (today[k] - mn) / (mx - mn)), 3.2f * dp, p);
        }
        private void line(Canvas cv, double[] v, double mn, double mx, float left, float cw, float bottom, float ch, int c, float wdp) {
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(wdp * dp); p.setColor(c); p.setStrokeJoin(Paint.Join.ROUND); Path pa = new Path(); boolean pen = false;
            for (int k = 0; k < 24; k++) { if (Double.isNaN(v[k])) { pen = false; continue; } float x = left + cw * (k + 0.5f) / 24f, y = (float) (bottom - ch * (v[k] - mn) / (mx - mn)); if (!pen) { pa.moveTo(x, y); pen = true; } else pa.lineTo(x, y); }
            cv.drawPath(pa, p); p.setStyle(Paint.Style.FILL);
        }
    }

    /** ECG readings as dots against the clock (0-24 h), with the median of the grade A and B readings of each part of the day as a bar. */
    static final class DotChart extends View {
        private final List<Pt> pts; private final Stat[] bands; private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); private final float dp;
        DotChart(Context c, List<Pt> pts, Stat[] bands) { super(c); this.pts = pts; this.bands = bands; dp = c.getResources().getDisplayMetrics().density; }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), left = 30 * dp, top = 8 * dp, right = w - 6 * dp, bottom = h - 18 * dp, cw = right - left, ch = bottom - top; TimeZone tz = TimeZone.getDefault();
            p.setStyle(Paint.Style.FILL); p.setColor(TRACK); cv.drawRoundRect(new RectF(left, top, right, bottom), 6 * dp, 6 * dp, p);
            double mx = 0; int n = 0; for (Pt q : pts) { mx = Math.max(mx, q.v); n++; }
            if (n < 2) { p.setColor(GREY); p.setTextSize(11 * dp); p.setTextAlign(Paint.Align.CENTER); cv.drawText("not enough readings yet", w / 2f, h / 2f, p); return; }
            mx = Math.ceil(mx * 1.1 / 5.0) * 5.0; if (mx < 20) mx = 20;
            p.setColor(GRID); p.setStrokeWidth(1f * dp); for (int g = 0; g <= 8; g++) { float x = left + cw * g / 8f; cv.drawLine(x, top, x, bottom, p); }
            p.setColor(GREY); p.setTextSize(9.5f * dp); p.setTextAlign(Paint.Align.RIGHT); cv.drawText(String.valueOf((int) mx), left - 4 * dp, top + 9 * dp, p); cv.drawText("0", left - 4 * dp, bottom, p);
            p.setTextAlign(Paint.Align.CENTER); for (int g = 0; g <= 8; g += 2) cv.drawText(String.format(Locale.UK, "%02d", g * 3), left + cw * g / 8f, h - 4 * dp, p);
            int[][] span = { { 5, 11 }, { 11, 17 }, { 17, 23 } }; p.setStrokeWidth(3f * dp); p.setColor(0xCCFFFFFF);
            for (int b = 0; b < 3; b++) if (bands[b].n >= 2) { float y = (float) (bottom - ch * bands[b].median / mx); cv.drawLine(left + cw * span[b][0] / 24f, y, left + cw * span[b][1] / 24f, y, p); }
            p.setStyle(Paint.Style.FILL);
            for (Pt q : pts) { p.setColor("A".equals(q.grade) || "B".equals(q.grade) ? GREEN : "C".equals(q.grade) ? GOLD : GREY); p.setAlpha(200); float x = left + cw * (float) (hourFrac(q.t, tz) / 24.0), y = (float) (bottom - ch * Math.min(q.v, mx) / mx); cv.drawCircle(x, y, 3.4f * dp, p); }
            p.setAlpha(255);
        }
    }
}
