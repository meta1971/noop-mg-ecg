package com.noop.mgecg;

// FILE VERSION 0.64.0 (16 Nov 2026): two PREVIEW cards on the Wellbeing page, both descriptive and sample-gated, neither states a link or gives advice (decode log addendum 232.5: the evening RMSSD against steps link is NOT established,
// per-evening Spearman -0.33 / -0.18 / -0.22 on 11-12 evenings).
//   WORK-DAY LOAD: for each weekday, the steps counted between 06:00 and 18:00, beside the RMSSD of the night that followed. Days split at the median of the steps; the median RMSSD of each half is printed.
//   OVERNIGHT GAP (off by default, opt-in): the hours between a meal the person logged and bed, beside that night's RMSSD, split the same way. No meal is ever suggested.
// The split and the gate are pure Java (tested off the phone); the loaders read the day logger database.

import android.content.Context;
import android.database.Cursor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

final class Pair64 {
    private Pair64() {}

    static final int MIN_N = 20, MIN_HALF = 8;

    static final class Split { int n, nLo, nHi; double cut, yLo = Double.NaN, yHi = Double.NaN; boolean enough; String head = "", body = ""; }

    static double median(List<Double> v) { if (v.isEmpty()) return Double.NaN; List<Double> s = new ArrayList<>(v); java.util.Collections.sort(s); int m = s.size(); return m % 2 == 1 ? s.get(m / 2) : 0.5 * (s.get(m / 2 - 1) + s.get(m / 2)); }

    /** x = exposure per day, y = the next night's RMSSD (ms). xName e.g. "steps (thousands)", unit text for the exposure in the sentence. */
    static Split split(double[] x, double[] y, String lowName, String highName, String xFmtUnit, double xScale) {
        Split S = new Split(); int n = Math.min(x.length, y.length); List<double[]> p = new ArrayList<>();
        for (int i = 0; i < n; i++) if (!Double.isNaN(x[i]) && !Double.isNaN(y[i])) p.add(new double[] { x[i], y[i] });
        S.n = p.size(); List<Double> xs = new ArrayList<>(); for (double[] q : p) xs.add(q[0]); S.cut = median(xs);
        List<Double> lo = new ArrayList<>(), hi = new ArrayList<>(); for (double[] q : p) { if (q[0] < S.cut) lo.add(q[1]); else if (q[0] > S.cut) hi.add(q[1]); }
        S.nLo = lo.size(); S.nHi = hi.size(); S.yLo = median(lo); S.yHi = median(hi);
        S.enough = S.n >= MIN_N && S.nLo >= MIN_HALF && S.nHi >= MIN_HALF;
        if (!S.enough) { S.head = "Still gathering"; S.body = String.format(Locale.UK, "%d of %d days with both numbers so far (and at least %d on each side of the middle). Nothing is shown until then, so chance does not read as a pattern.", S.n, MIN_N, MIN_HALF); return S; }
        S.head = "A description of your own days";
        S.body = String.format(Locale.UK, "%s days (%d, under %.1f%s): the night's RMSSD had a middle value of %.0f ms. %s days (%d, over it): %.0f ms. This describes your days only. It does not show that one leads to the other, and %d days can differ by chance.",
            lowName, S.nLo, S.cut * xScale, xFmtUnit, S.yLo, highName, S.nHi, S.yHi, S.n);
        return S;
    }

    // ------------------------------------------------------------------ loaders (day logger database; call off the main thread)
    /** [steps in the 06:00-18:00 window (minus nothing: counted inside the minutes), next-night RMSSD] for each weekday of the last `days` days; NaN where missing. */
    static double[][] workDays(Context c, int days) {
        List<double[]> out = new ArrayList<>();
        try {
            Calendar cal = Calendar.getInstance(); cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0);
            long today0 = cal.getTimeInMillis() / 1000L; long from = today0 - (long) days * 86400L - 86400L;
            List<long[]> mins = new ArrayList<>();
            Cursor q = ActivityStore.db(c).rawQuery("SELECT ts, steps0, steps1 FROM r18_min WHERE ts >= ? ORDER BY ts", new String[] { String.valueOf(from) });
            try { while (q.moveToNext()) mins.add(new long[] { q.getLong(0), q.getLong(1), q.getLong(2) }); } finally { q.close(); }
            List<TodayModel.NightStat> ns = DayLog.nightStats(c);
            for (int d = days; d >= 1; d--) {
                Calendar day = Calendar.getInstance(); day.setTimeInMillis(today0 * 1000L); day.add(Calendar.DAY_OF_YEAR, -d);
                int dow = day.get(Calendar.DAY_OF_WEEK); if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) continue;
                long a = day.getTimeInMillis() / 1000L + 6 * 3600L, b = a + 12 * 3600L; long steps = 0; int cnt = 0;
                for (long[] m : mins) if (m[0] >= a && m[0] < b) { steps += (m[2] - m[1]) & 0xFFFFL; cnt++; }
                if (cnt < 480) continue;      // under 8 of the 12 hours recorded: the day is not counted
                double rm = Double.NaN; for (TodayModel.NightStat n : ns) if (n.bed >= b && n.bed < b + 12 * 3600L && !Double.isNaN(n.rmssd)) { rm = n.rmssd; break; }
                out.add(new double[] { steps, rm });
            }
        } catch (Throwable ignored) { }
        double[][] r = new double[2][out.size()]; for (int i = 0; i < out.size(); i++) { r[0][i] = out.get(i)[0]; r[1][i] = out.get(i)[1]; }
        return r;
    }

    /** [hours from the last logged meal to bed, that night's RMSSD] for each night with a meal logged 1 to 8 hours before bed; meals = epoch ms values. */
    static double[][] mealGaps(Context c, List<Long> meals) {
        List<double[]> out = new ArrayList<>();
        try {
            for (TodayModel.NightStat n : DayLog.nightStats(c)) {
                if (Double.isNaN(n.rmssd)) continue; long bedMs = n.bed * 1000L; long best = -1;
                for (long m : meals) if (m <= bedMs && bedMs - m <= 8L * 3600000L && m > best) best = m;
                if (best < 0) continue; double gap = (bedMs - best) / 3600000.0; if (gap < 1.0) continue; out.add(new double[] { gap, n.rmssd });
            }
        } catch (Throwable ignored) { }
        double[][] r = new double[2][out.size()]; for (int i = 0; i < out.size(); i++) { r[0][i] = out.get(i)[0]; r[1][i] = out.get(i)[1]; }
        return r;
    }
}
