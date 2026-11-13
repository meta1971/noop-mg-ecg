package com.noop.mgecg;

// FILE VERSION 0.54.0 (13 Nov 2026): NEW. All-day activity, not workouts: how long you are on your feet through the day, the stretches you stay still while awake, the hour-by-day map, your weekday habit and the
// daily rhythm of your active minutes. Built on what the Activity screen already reads (ActivityDetector epochs, ActivityStore summaries), plus the strap's sleep state so that sleep is never counted as sitting.
//   moving   : minutes the detector calls ACTIVE (heart rate above 10 % of reserve with wrist movement, or clear wrist movement), awake only
//   still    : awake minutes that are not moving (REST, or raised heart rate with almost no movement)
//   a still stretch is a run of still minutes with no gap in the record; it ends when you move, fall asleep or the record has a hole
//   rhythm   : the hourly profile of active minutes (average of the finished days) fitted with one 24 h cosine (RhythmFit). It is NOT the CosinorAge number: that needs a movement value for every minute (ENMO),
//              and this strap gives raw movement only during ECG sessions (its per-second figure is the largest step between two acceleration samples, "dyn_g", not ENMO), so no biological age is shown.
// Pure parts (stats, habit, rhythm) have no Android and are tested off the phone.

import android.content.Context;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import static com.noop.mgecg.HealthViews.*;

final class ActivityPlus {
    private ActivityPlus() {}

    static final int ACTIVE_HOUR_MIN = 10;          // an hour counts as an active hour with at least this many moving minutes
    static final int GOOD_DAY_MIN = 600;            // a finished day counts for habit and rhythm with at least 10 h logged

    // ------------------------------------------------------------------ one day, pure
    static final class DayStats {
        int validMin, asleepMin, awakeMin, movingMin, stillMin;
        int longestStillMin, stillStretches60, activeHours;
        int stepsRaw;
        int firstMovingMin = -1, lastMovingMin = -1;       // minutes after the day start
        final int[] movingByHour = new int[24];            // by hour since the day start (04:00)
        final int[] stepsByHour = new int[24];
        double stillShare() { return awakeMin > 0 ? stillMin / (double) awakeMin : Double.NaN; }
    }

    /** eps ascending; asleep[m] = the strap said asleep in minute m after dayStart (may be null). */
    static DayStats stats(List<ActivityDetector.Epoch> eps, long dayStart, boolean[] asleep) {
        DayStats s = new DayStats();
        if (eps == null) return s;
        int run = 0; long prevStart = Long.MIN_VALUE;
        for (ActivityDetector.Epoch e : eps) {
            int mi = (int) ((e.start - dayStart) / 60L);
            if (e.kind == ActivityDetector.NO_DATA || mi < 0) { run = close(s, run); prevStart = Long.MIN_VALUE; continue; }
            s.validMin++;
            boolean sl = asleep != null && mi < asleep.length && asleep[mi];
            if (sl) { s.asleepMin++; run = close(s, run); prevStart = e.start; continue; }
            s.awakeMin++;
            int h = Math.max(0, Math.min(23, mi / 60));
            s.stepsByHour[h] += e.steps; s.stepsRaw += e.steps;
            if (e.kind == ActivityDetector.ACTIVE) {
                s.movingMin++; s.movingByHour[h]++;
                if (s.firstMovingMin < 0) s.firstMovingMin = mi;
                s.lastMovingMin = mi;
                run = close(s, run);
            } else {
                s.stillMin++;
                if (prevStart != Long.MIN_VALUE && e.start - prevStart != 60L) run = close(s, run);
                run++;
            }
            prevStart = e.start;
        }
        close(s, run);
        for (int h = 0; h < 24; h++) if (s.movingByHour[h] >= ACTIVE_HOUR_MIN) s.activeHours++;
        return s;
    }

    private static int close(DayStats s, int run) {
        if (run > 0) { s.longestStillMin = Math.max(s.longestStillMin, run); if (run >= 60) s.stillStretches60++; }
        return 0;
    }

    /** Minutes of the day (index = minutes after dayStart, 1500 long) in which the strap's sleep state was asleep for at least half of the seconds. Call off the main thread. */
    static boolean[] asleepMinutes(Context c, long dayStart, long dayEnd) {
        boolean[] out = new boolean[1500];
        boolean[] seen = new boolean[1500];
        try {
            android.database.sqlite.SQLiteDatabase d = ActivityStore.db(c);
            String[] a = {String.valueOf(dayStart), String.valueOf(dayEnd)};
            Cursor q = d.rawQuery("SELECT (ts-" + dayStart + ")/60, SUM(CASE WHEN state=2 THEN 1 ELSE 0 END), COUNT(*) FROM r18 WHERE ts >= ? AND ts < ? GROUP BY 1", a);
            while (q.moveToNext()) { int m = q.getInt(0); if (m < 0 || m >= 1500) continue; out[m] = 2 * q.getInt(1) >= q.getInt(2); seen[m] = true; }
            q.close();
            q = d.rawQuery("SELECT (ts-" + dayStart + ")/60, state FROM r18_min WHERE ts >= ? AND ts < ?", a);
            while (q.moveToNext()) { int m = q.getInt(0); if (m < 0 || m >= 1500 || seen[m]) continue; out[m] = q.getInt(1) == 2; }
            q.close();
        } catch (Throwable ignored) { }
        return out;
    }

    // ------------------------------------------------------------------ many days, pure
    static final class Habit {
        int days;
        double meanActive = Double.NaN, cvActive = Double.NaN;
        final double[] weekdayMean = new double[7];          // Monday first; NaN when that weekday has no finished day
        final int[] weekdayN = new int[7];
        double firstHour = Double.NaN, lastHour = Double.NaN;  // median clock hour (0-24) of the first and last hour with an active hour
        double meanActiveHours = Double.NaN;
        final double[] clockProfile = new double[24];         // mean moving minutes by CLOCK hour
        RhythmFit.Result rhythm = new RhythmFit.Result();
    }

    static int weekdayIndex(long dayStart) {
        Calendar g = Calendar.getInstance();
        g.setTimeInMillis((dayStart + 6 * 3600L) * 1000L);
        int w = g.get(Calendar.DAY_OF_WEEK);               // Sunday = 1
        return (w + 5) % 7;                                // Monday = 0
    }

    /** Finished days only (the caller leaves today out); a day needs GOOD_DAY_MIN minutes logged. */
    static Habit habit(List<ActivityStore.Summary> days) {
        Habit h = new Habit();
        Arrays.fill(h.weekdayMean, Double.NaN);
        double[] wsum = new double[7];
        List<Double> act = new ArrayList<Double>(), firsts = new ArrayList<Double>(), lasts = new ArrayList<Double>();
        double[] prof = new double[24];
        double ahSum = 0;
        for (ActivityStore.Summary s : days) {
            if (s == null || !s.ok || s.validMin < GOOD_DAY_MIN) continue;
            h.days++;
            act.add((double) s.activeMin);
            int w = weekdayIndex(s.dayStart); wsum[w] += s.activeMin; h.weekdayN[w]++;
            int first = -1, last = -1, ah = 0;
            for (int i = 0; i < 24; i++) {
                prof[(i + 4) % 24] += s.hourly[i];
                if (s.hourly[i] >= ACTIVE_HOUR_MIN) { ah++; if (first < 0) first = i; last = i; }
            }
            ahSum += ah;
            if (first >= 0) { firsts.add((double) ((first + 4) % 24)); lasts.add((double) ((last + 4) % 24 + 1)); }
        }
        if (h.days == 0) { Arrays.fill(h.clockProfile, Double.NaN); return h; }
        double m = 0; for (double v : act) m += v; m /= act.size();
        double q = 0; for (double v : act) q += (v - m) * (v - m);
        h.meanActive = m;
        h.cvActive = act.size() >= 3 && m > 0 ? Math.sqrt(q / (act.size() - 1)) / m : Double.NaN;
        for (int w = 0; w < 7; w++) if (h.weekdayN[w] > 0) h.weekdayMean[w] = wsum[w] / h.weekdayN[w];
        h.meanActiveHours = ahSum / h.days;
        h.firstHour = median(firsts); h.lastHour = median(lasts);
        for (int i = 0; i < 24; i++) h.clockProfile[i] = prof[i] / h.days;
        h.rhythm = RhythmFit.fitHourly(h.clockProfile);
        return h;
    }

    private static double median(List<Double> v) {
        if (v.isEmpty()) return Double.NaN;
        List<Double> s = new ArrayList<Double>(v); Collections.sort(s);
        int k = s.size(); return k % 2 == 1 ? s.get(k / 2) : 0.5 * (s.get(k / 2 - 1) + s.get(k / 2));
    }

    // ------------------------------------------------------------------ text helpers
    static String hm(int min) { return min < 60 ? min + " min" : (min / 60) + " h " + String.format(Locale.UK, "%02d", min % 60) + " min"; }
    /** Minutes as h:mm for a ring (4:05). */
    static String hmClock(int min) { return (min / 60) + ":" + String.format(Locale.UK, "%02d", min % 60); }
    static String clockH(double h) { if (Double.isNaN(h)) return "-"; int t = (int) Math.round(h * 60.0) % 1440; return String.format(Locale.UK, "%02d:%02d", t / 60, t % 60); }
    static String atMinute(long dayStart, int minAfter) { return new SimpleDateFormat("HH:mm", Locale.UK).format(new Date((dayStart + minAfter * 60L) * 1000L)); }
    static final String[] WD = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};

    // ------------------------------------------------------------------ cards
    private static LinearLayout card(Context c, String title) {
        LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(glass(c, 22));
        int p = Math.round(dp(c, 16)); l.setPadding(p, p, p, Math.round(dp(c, 18)));
        TextView t = HeartGraph.label(c, title, 11, MUT, 400); t.setLetterSpacing(0.16f); l.addView(t);
        return l;
    }

    private static TextView text(Context c, String s, float sp, int color) { TextView t = HeartGraph.label(c, s, sp, color, 400); t.setLineSpacing(0, 1.25f); return t; }

    private static void kv(Context c, LinearLayout l, String k, String v) {
        LinearLayout r = new LinearLayout(c); r.setOrientation(LinearLayout.HORIZONTAL);
        r.addView(HeartGraph.label(c, k, 13, INK, 400), new LinearLayout.LayoutParams(0, -2, 1f));
        r.addView(HeartGraph.label(c, v, 13, INK, 600));
        l.addView(r, HeartGraph.lp(c, -1, -2, 8));
    }

    /** The viewed day: on your feet, still stretches, by the hour. */
    static LinearLayout dayCard(Context c, DayStats s, long dayStart, boolean today) {
        LinearLayout l = card(c, today ? "ON YOUR FEET TODAY" : "ON YOUR FEET THIS DAY");
        if (s == null || s.awakeMin < 30) { l.addView(text(c, "Not enough awake time logged to say.", 13, MUT), HeartGraph.lp(c, -1, -2, 8)); return l; }
        LinearLayout big = new LinearLayout(c); big.setOrientation(LinearLayout.HORIZONTAL);
        big.addView(HeartGraph.label(c, hm(s.movingMin), 30, INK, 200));
        big.addView(HeartGraph.label(c, "  moving of " + hm(s.awakeMin) + " awake and logged", 12, MUT, 400), HeartGraph.lp(c, -2, -2, 14));
        l.addView(big, HeartGraph.lp(c, -1, -2, 6));
        l.addView(new HourBars(c, s.movingByHour, 60, BLUE), HeartGraph.lp(c, -1, Math.round(dp(c, 96)), 10));
        l.addView(text(c, "Moving minutes by hour, from 04:00. The tallest bar is a full hour.", 11, MUT), HeartGraph.lp(c, -1, -2, 4));
        kv(c, l, "Still while awake", hm(s.stillMin) + (Double.isNaN(s.stillShare()) ? "" : String.format(Locale.UK, " (%.0f %%)", 100.0 * s.stillShare())));
        kv(c, l, "Longest still stretch", s.longestStillMin == 0 ? "none" : hm(s.longestStillMin));
        kv(c, l, "Still stretches of an hour or more", String.valueOf(s.stillStretches60));
        kv(c, l, "Active hours (" + ACTIVE_HOUR_MIN + " min or more moving)", String.valueOf(s.activeHours));
        if (s.firstMovingMin >= 0) kv(c, l, "First to last moving minute", atMinute(dayStart, s.firstMovingMin) + " to " + atMinute(dayStart, s.lastMovingMin));
        if (s.stepsRaw > 0) {
            l.addView(new HourBars(c, s.stepsByHour, 0, TEAL), HeartGraph.lp(c, -1, Math.round(dp(c, 70)), 14));
            l.addView(text(c, "Steps by hour (the strap's own counter, " + String.format(Locale.UK, "%,d", s.stepsRaw) + " for the day).", 11, MUT), HeartGraph.lp(c, -1, -2, 4));
        }
        l.addView(text(c, "Sleep is left out, so a night is never counted as sitting. Still means no sustained wrist movement; it does not say you were sitting.", 11, MUT), HeartGraph.lp(c, -1, -2, 8));
        return l;
    }

    static LinearLayout habitCard(Context c, Habit h, int goalMin) {
        LinearLayout l = card(c, "YOUR USUAL DAYS");
        if (h == null || h.days < 3) { l.addView(text(c, "Needs 3 finished days with at least 10 hours logged. So far: " + (h == null ? 0 : h.days) + ".", 13, MUT), HeartGraph.lp(c, -1, -2, 8)); return l; }
        l.addView(new WeekdayBars(c, h.weekdayMean, goalMin), HeartGraph.lp(c, -1, Math.round(dp(c, 120)), 10));
        l.addView(text(c, "Average moving time by weekday over " + h.days + " finished days.", 11, MUT), HeartGraph.lp(c, -1, -2, 4));
        kv(c, l, "Average a day", hm((int) Math.round(h.meanActive)));
        if (!Double.isNaN(h.cvActive)) kv(c, l, "Day-to-day spread", String.format(Locale.UK, "%.0f %% of the average", 100.0 * h.cvActive));
        kv(c, l, "Active hours a day", String.format(Locale.UK, "%.1f", h.meanActiveHours));
        if (!Double.isNaN(h.firstHour)) kv(c, l, "Usual active day", clockH(h.firstHour) + " to " + clockH(h.lastHour));
        return l;
    }

    static LinearLayout rhythmCard(Context c, Habit h) {
        LinearLayout l = card(c, "RHYTHM OF YOUR ACTIVE MINUTES");
        if (h == null || h.days < 5 || !h.rhythm.ok) { l.addView(text(c, "Needs 5 finished days with at least 10 hours logged. So far: " + (h == null ? 0 : h.days) + ".", 13, MUT), HeartGraph.lp(c, -1, -2, 8)); return l; }
        RhythmFit.Result r = h.rhythm;
        int[] bars = new int[24];
        for (int i = 0; i < 24; i++) { double v = h.clockProfile[(i + 4) % 24]; bars[i] = (int) Math.round(Double.isNaN(v) ? 0 : v); }
        l.addView(new HourBars(c, bars, 60, VIOLET), HeartGraph.lp(c, -1, Math.round(dp(c, 90)), 10));
        l.addView(text(c, "Average moving minutes by hour, from 04:00, over " + h.days + " finished days.", 11, MUT), HeartGraph.lp(c, -1, -2, 4));
        kv(c, l, "Peak of the 24 h curve", clockH(r.peakH));
        kv(c, l, "Swing (amplitude)", String.format(Locale.UK, "%.1f min an hour", r.amp));
        kv(c, l, "Level (mesor)", String.format(Locale.UK, "%.1f min an hour", r.mesor));
        kv(c, l, "How well one 24 h wave fits", String.format(Locale.UK, "R² %.2f", r.r2));
        l.addView(text(c, "A single cosine is a poor fit for a working day with a break in the middle, so read the peak and the swing as a summary, not a shape. This is the method of CosinorAge (arXiv 2509.01089) applied to active minutes, not to the movement value it uses, so there is no biological-age figure here.", 11, MUT), HeartGraph.lp(c, -1, -2, 8));
        return l;
    }

    static LinearLayout mapCard(Context c, List<ActivityStore.Summary> days, int nDays) {
        LinearLayout l = card(c, "LAST " + nDays + " DAYS BY THE HOUR");
        int n = Math.min(nDays, days.size());
        int[][] cells = new int[n][24]; long[] ds = new long[n];
        int real = 0;
        for (int r = 0; r < n; r++) {
            ActivityStore.Summary s = days.get(days.size() - n + r);
            ds[r] = s.dayStart;
            for (int h = 0; h < 24; h++) cells[r][h] = s.ok ? s.hourly[h] : -1;
            if (s.ok) real++;
        }
        if (real == 0) { l.addView(text(c, "Reading your history…", 13, MUT), HeartGraph.lp(c, -1, -2, 8)); return l; }
        l.addView(new DayHeat(c, cells, ds), HeartGraph.lp(c, -1, Math.round(dp(c, 14 + n * 9)), 10));
        l.addView(text(c, "One row a day, oldest at the top. Columns are the hours from 04:00. Brighter is more moving minutes in that hour; dark grey is no record.", 11, MUT), HeartGraph.lp(c, -1, -2, 6));
        return l;
    }

    // ------------------------------------------------------------------ canvas views
    /** 24 bars, hour 0 = 04:00. fixedMax 0 = scale to the tallest bar. */
    static final class HourBars extends View {
        private final int[] v; private final int max, color;
        HourBars(Context c, int[] v, int fixedMax, int color) {
            super(c); this.v = v; this.color = color;
            int m = fixedMax;
            if (m <= 0) { for (int x : v) m = Math.max(m, x); }
            this.max = Math.max(1, m);
        }
        @Override protected void onDraw(Canvas cv) {
            Context c = getContext();
            float w = getWidth(), h = getHeight(), labelH = dp(c, 14), plotH = h - labelH, gap = dp(c, 2);
            float bw = (w - gap * 23) / 24f;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            Paint lab = paint(MUT); lab.setTextSize(dp(c, 9.5f)); lab.setTextAlign(Paint.Align.CENTER); lab.setTypeface(font(c, 400));
            for (int i = 0; i < 24; i++) {
                float x = i * (bw + gap);
                p.setColor(0x14FFFFFF);
                cv.drawRoundRect(new RectF(x, 0, x + bw, plotH), dp(c, 2), dp(c, 2), p);
                float f = Math.min(1f, v[i] / (float) max);
                if (f > 0f) { p.setColor(color); float top = plotH - Math.max(dp(c, 2), plotH * f); cv.drawRoundRect(new RectF(x, top, x + bw, plotH), dp(c, 2), dp(c, 2), p); }
                if (i % 4 == 0) cv.drawText(String.format(Locale.UK, "%02d", (i + 4) % 24), x + bw / 2f, h - dp(c, 2), lab);
            }
        }
    }

    static final class WeekdayBars extends View {
        private final double[] v; private final int goal;
        WeekdayBars(Context c, double[] v, int goal) { super(c); this.v = v; this.goal = goal; }
        @Override protected void onDraw(Canvas cv) {
            Context c = getContext();
            double max = Math.max(30, goal);
            for (double x : v) if (!Double.isNaN(x)) max = Math.max(max, x);
            float w = getWidth(), h = getHeight(), labelH = dp(c, 30), plotH = h - labelH, gap = dp(c, 8);
            float bw = (w - gap * 6) / 7f;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            Paint lab = paint(MUT); lab.setTextSize(dp(c, 10.5f)); lab.setTextAlign(Paint.Align.CENTER); lab.setTypeface(font(c, 400));
            Paint val = paint(INK); val.setTextSize(dp(c, 10.5f)); val.setTextAlign(Paint.Align.CENTER); val.setTypeface(font(c, 400));
            for (int i = 0; i < 7; i++) {
                float x = i * (bw + gap);
                p.setColor(0x14FFFFFF); cv.drawRoundRect(new RectF(x, 0, x + bw, plotH), dp(c, 4), dp(c, 4), p);
                if (!Double.isNaN(v[i])) {
                    float f = (float) (v[i] / max);
                    p.setColor(BLUE); float top = plotH - Math.max(dp(c, 3), plotH * f);
                    cv.drawRoundRect(new RectF(x, top, x + bw, plotH), dp(c, 4), dp(c, 4), p);
                    cv.drawText(String.format(Locale.UK, "%d:%02d", (int) (v[i] / 60), (int) Math.round(v[i]) % 60), x + bw / 2f, plotH + dp(c, 12), val);
                }
                cv.drawText(WD[i], x + bw / 2f, h - dp(c, 2), lab);
            }
        }
    }

    /** Rows = days (oldest first), columns = hours since 04:00; -1 = no record. */
    static final class DayHeat extends View {
        private final int[][] cells; private final long[] ds;
        DayHeat(Context c, int[][] cells, long[] ds) { super(c); this.cells = cells; this.ds = ds; }
        @Override protected void onDraw(Canvas cv) {
            Context c = getContext();
            int n = cells.length; float w = getWidth(), top = dp(c, 14), left = dp(c, 30), gap = dp(c, 1);
            float cw = (w - left - gap * 23) / 24f, rh = dp(c, 8);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            Paint lab = paint(MUT); lab.setTextSize(dp(c, 9)); lab.setTextAlign(Paint.Align.CENTER); lab.setTypeface(font(c, 400));
            for (int h = 0; h < 24; h += 4) cv.drawText(String.format(Locale.UK, "%02d", (h + 4) % 24), left + h * (cw + gap) + cw / 2f, dp(c, 10), lab);
            lab.setTextAlign(Paint.Align.LEFT);
            SimpleDateFormat f = new SimpleDateFormat("d MMM", Locale.UK);
            for (int r = 0; r < n; r++) {
                float y = top + r * (rh + gap);
                if (r % 7 == 0 || r == n - 1) cv.drawText(f.format(new Date((ds[r] + 6 * 3600L) * 1000L)), 0, y + rh, lab);
                for (int h = 0; h < 24; h++) {
                    int v = cells[r][h];
                    p.setColor(v < 0 ? 0x10FFFFFF : alpha(BLUE, 0.10f + 0.90f * Math.min(1f, v / 60f)));
                    float x = left + h * (cw + gap);
                    cv.drawRoundRect(new RectF(x, y, x + cw, y + rh), dp(c, 1.5f), dp(c, 1.5f), p);
                }
            }
        }
    }

    // ------------------------------------------------------------------ the strip of data trust (Today and Activity)
    static final class TrustStrip extends View {
        private final TrustData.Day day;
        TrustStrip(Context c, TrustData.Day day) { super(c); this.day = day; }
        @Override protected void onDraw(Canvas cv) {
            Context c = getContext();
            float w = getWidth(), h = getHeight(), gap = dp(c, 2), labelH = dp(c, 13), plotH = h - labelH;
            float bw = (w - gap * 23) / 24f;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            Paint lab = paint(MUT); lab.setTextSize(dp(c, 9.5f)); lab.setTextAlign(Paint.Align.CENTER); lab.setTypeface(font(c, 400));
            for (int i = 0; i < 24; i++) {
                float x = i * (bw + gap);
                boolean beyond = day.partial && i > day.lastHour;
                int k = day.cls[i];
                p.setColor(beyond ? 0x0AFFFFFF : k == TrustData.LIVE ? GREEN : k == TrustData.FILLED ? BLUE : k == TrustData.MIXED ? VIOLET : 0x24FFFFFF);
                cv.drawRoundRect(new RectF(x, 0, x + bw, plotH), dp(c, 3), dp(c, 3), p);
                if (day.pack[i]) { p.setColor(GOLD); cv.drawRoundRect(new RectF(x, plotH - dp(c, 4), x + bw, plotH), dp(c, 2), dp(c, 2), p); }
                if (i % 4 == 0) cv.drawText(String.format(Locale.UK, "%02d", (i + 4) % 24), x + bw / 2f, h - dp(c, 1), lab);
            }
        }
    }

    /** One card for the last `days.size()` days of the data-trust strip, plus the newest history pulls. */
    static LinearLayout trustCard(Context c, List<TrustData.Day> days, List<String> ingests) {
        LinearLayout l = card(c, "WHAT TO TRUST IN YOUR DATA");
        trustBody(c, l, days, ingests);
        return l;
    }

    /** The same content without a card around it (for an opened panel). */
    static void trustBody(Context c, LinearLayout l, List<TrustData.Day> days, List<String> ingests) {
        if (days == null || days.isEmpty()) { l.addView(text(c, "Nothing logged yet.", 13, MUT), HeartGraph.lp(c, -1, -2, 8)); return; }
        SimpleDateFormat f = new SimpleDateFormat("EEE d MMM", Locale.UK);
        for (TrustData.Day d : days) {
            l.addView(HeartGraph.label(c, f.format(new Date((d.dayStart + 6 * 3600L) * 1000L)), 12, INK, 600), HeartGraph.lp(c, -1, -2, 12));
            l.addView(new TrustStrip(c, d), HeartGraph.lp(c, -1, Math.round(dp(c, 36)), 4));
            l.addView(text(c, d.summary(), 11.5f, MUT), HeartGraph.lp(c, -1, -2, 3));
            String fr = d.ranges(TrustData.FILLED);
            if (!fr.isEmpty()) l.addView(text(c, "Filled from the strap's history: " + fr, 11.5f, BLUE), HeartGraph.lp(c, -1, -2, 2));
            String gr = d.ranges(TrustData.GAP);
            if (!gr.isEmpty()) l.addView(text(c, "No record: " + gr, 11.5f, MUT), HeartGraph.lp(c, -1, -2, 2));
            String pr = d.packRanges();
            if (!pr.isEmpty()) l.addView(text(c, "Battery pack on: " + pr, 11.5f, GOLD), HeartGraph.lp(c, -1, -2, 2));
        }
        LinearLayout key = new LinearLayout(c); key.setOrientation(LinearLayout.HORIZONTAL);
        String[] kn = {"in range", "filled later", "mixed", "no record", "pack"}; int[] kc = {GREEN, BLUE, VIOLET, 0xFF5A6370, GOLD};
        for (int i = 0; i < kn.length; i++) key.addView(HeartGraph.label(c, "\u25cf " + kn[i] + "  ", 10.5f, kc[i], 400));
        l.addView(key, HeartGraph.lp(c, -1, -2, 12));
        if (ingests != null && !ingests.isEmpty()) {
            l.addView(HeartGraph.label(c, "HISTORY PULLED", 10.5f, MUT, 400), HeartGraph.lp(c, -1, -2, 14));
            for (String s : ingests) l.addView(text(c, s, 11.5f, MUT), HeartGraph.lp(c, -1, -2, 2));
        }
        l.addView(text(c, "In range = the phone also heard the strap live in that hour. Filled later = the strap was away and the record came from its stored history, with its own clock stamps, so the times are right but the hour was not watched live. The battery pack is read from the strap's own flag.", 11, MUT), HeartGraph.lp(c, -1, -2, 10));
    }
}
