package com.noop.mgecg;

// FILE VERSION 0.49.0 (9 Nov 2026): the screen moved to TrendsScreen; this file keeps the night helpers (allNights, series, stats, weekSummary) and the smooth curve used by several screens.
// FILE VERSION 0.12.0 (26 Oct): trends over 7, 30 and 90 nights. Research only.

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import static com.noop.mgecg.HealthViews.*;

/**
 * One point per night for recovery, resting heart rate, overnight HRV, sleep, breathing, skin temperature and blood oxygen, over the
 * last 7, 30 or 90 nights, with your usual range (average plus or minus one spread of the nights shown) shaded. Each night's recovery
 * is scored against the nights before it, exactly as on the Today screen. Estimates from this strap's own records, not a medical record.
 */
final class Trends {
    private Trends() {}

    static final String[] NAME = {"Recovery", "Resting HR", "HRV", "Sleep", "Breathing", "Skin temp", "Blood oxygen"};
    static final String[] UNIT = {"%", "bpm", "ms", "h", "per min", "\u00b0C", "%"};
    static final int[] DEC = {0, 0, 0, 1, 1, 1, 0};
    static final int[] COL = {0xFF5CE5A0, 0xFFFF7A93, 0xFF4CB4FF, 0xFF9AA0F0, 0xFF2EE0B8, 0xFFF2B35B, 0xFF8E93E8};
    static final int[] RANGE_N = {7, 30, 90};
    static final String[] RANGE_LABEL = {"7 nights", "30 nights", "90 nights"};
    private static int metric = 0, range = 1;

    /** Earlier nights plus the latest one, oldest first. */
    static List<TodayModel.NightStat> allNights(HealthModel.Data d) {
        List<TodayModel.NightStat> all = new ArrayList<>(d.nights);
        if (d.day != null && d.day.night != null) {
            boolean have = false;
            for (TodayModel.NightStat s : all) if (Math.abs(s.bed - d.day.night.bedTs) < 3600L) have = true;
            if (!have) all.add(TodayModel.statOf(d.day.night, d.day.spo2));
        }
        Collections.sort(all, new Comparator<TodayModel.NightStat>() { @Override public int compare(TodayModel.NightStat a, TodayModel.NightStat b) { return Long.compare(a.bed, b.bed); } });
        return all;
    }

    /** One number per night for the metric (NaN where the night has none). */
    static double[] series(List<TodayModel.NightStat> all, int m) {
        double[] v = new double[all.size()];
        for (int i = 0; i < v.length; i++) {
            TodayModel.NightStat s = all.get(i);
            double x = Double.NaN;
            switch (m) {
                case 0: { double[] r = TodayModel.recovery(s, all.subList(0, i)); x = r == null ? Double.NaN : r[0]; break; }
                case 1: x = s.hrLow5; break;
                case 2: x = s.rmssd > 0 ? s.rmssd : Double.NaN; break;
                case 3: x = s.tstH; break;
                case 4: x = s.br; break;
                case 5: x = s.skin; break;
                default: x = s.spo2Median > 0 ? s.spo2Median : Double.NaN;
            }
            v[i] = x;
        }
        return v;
    }

    /** Mean, spread (sample SD) and count of the finite values. */
    static double[] stats(double[] v, int from, int to) {
        double s = 0; int n = 0;
        for (int i = from; i < to; i++) if (!Double.isNaN(v[i])) { s += v[i]; n++; }
        if (n == 0) return new double[] {Double.NaN, Double.NaN, 0};
        double mean = s / n, q = 0;
        for (int i = from; i < to; i++) if (!Double.isNaN(v[i])) q += (v[i] - mean) * (v[i] - mean);
        return new double[] {mean, n > 1 ? Math.sqrt(q / (n - 1)) : 0.0, n};
    }

    static String fmt(int m, double x) {
        return Double.isNaN(x) ? "\u2014" : String.format(Locale.UK, "%." + DEC[m] + "f", x) + (UNIT[m].isEmpty() ? "" : " " + UNIT[m]);
    }

    /** Sleep over the last seven nights: average, debt against the goal, and how steady the bedtime is. Pure, so it can be tested. */
    static String weekSummary(List<TodayModel.NightStat> all, double goalH) {
        int n = all.size();
        if (n < 2) return "Needs two nights of records.";
        int from = Math.max(0, n - 7);
        double sum = 0, debt = 0;
        int k = 0;
        double[] bedMin = new double[n - from];
        for (int i = from; i < n; i++) {
            TodayModel.NightStat s = all.get(i);
            sum += s.tstH; k++;
            debt += Math.max(0.0, goalH - s.tstH);
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.setTimeInMillis(s.bed * 1000L);
            double m = c.get(java.util.Calendar.HOUR_OF_DAY) * 60.0 + c.get(java.util.Calendar.MINUTE);
            bedMin[i - from] = (m - 12 * 60 + 1440) % 1440;            // minutes after noon, so 23:30 and 00:30 sit an hour apart
        }
        double mean = 0; for (double x : bedMin) mean += x; mean /= bedMin.length;
        double q = 0; for (double x : bedMin) q += (x - mean) * (x - mean);
        double sd = bedMin.length > 1 ? Math.sqrt(q / (bedMin.length - 1)) : 0;
        String steady = sd <= 30 ? "steady" : sd <= 60 ? "fairly steady" : "irregular";
        return String.format(Locale.UK, "Average %s over %d nights. Sleep debt against the %.0f h goal: %s. Bedtime spread about %d min (%s).",
                HealthModel.hm(sum / k), k, goalH, HealthModel.hm(debt), Math.round(sd), steady);
    }

    // ------------------------------------------------------------------ screen
    /** 0.49.0: the screen lives in TrendsScreen (personal baselines, nights and ECG readings side by side). */
    static void show(final Activity a, final HealthModel.Data d) { TrendsScreen.show(a, d); }

    /** Slopes (y per x) of a monotone cubic through the points: no overshoot between neighbours. */
    static float[] tangents(float[] xs, float[] ys, int n) {
        float[] m = new float[n]; if (n < 2) return m;
        float[] d = new float[n - 1]; for (int i = 0; i < n - 1; i++) d[i] = (ys[i + 1] - ys[i]) / Math.max(1e-6f, xs[i + 1] - xs[i]);
        m[0] = d[0]; m[n - 1] = d[n - 2];
        for (int i = 1; i < n - 1; i++) m[i] = (d[i - 1] * d[i] <= 0f) ? 0f : 0.5f * (d[i - 1] + d[i]);
        for (int i = 0; i < n - 1; i++) {
            if (d[i] == 0f) { m[i] = 0f; m[i + 1] = 0f; continue; }
            float a = m[i] / d[i], b = m[i + 1] / d[i], q = a * a + b * b;
            if (q > 9f) { float t = 3f / (float) Math.sqrt(q); m[i] = t * a * d[i]; m[i + 1] = t * b * d[i]; }
        }
        return m;
    }

    /** Adds a smooth monotone-cubic curve through the points to the path (starts with moveTo). */
    static void smoothPath(android.graphics.Path p, float[] xs, float[] ys, int n) {
        if (n <= 0) return; p.moveTo(xs[0], ys[0]); if (n == 1) { p.lineTo(xs[0] + 0.1f, ys[0]); return; }
        if (n == 2) { p.lineTo(xs[1], ys[1]); return; }
        float[] m = tangents(xs, ys, n);
        for (int i = 0; i < n - 1; i++) { float h = xs[i + 1] - xs[i]; p.cubicTo(xs[i] + h / 3f, ys[i] + m[i] * h / 3f, xs[i + 1] - h / 3f, ys[i + 1] - m[i + 1] * h / 3f, xs[i + 1], ys[i + 1]); }
    }
}
