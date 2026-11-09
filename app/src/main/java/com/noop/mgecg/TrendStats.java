package com.noop.mgecg;

// FILE VERSION 0.49.0 (9 Nov 2026): the engine of the Trends screen. Every number is judged against THIS person's own usual (robust median and spread of their own earlier values), never against a population or another device:
// different devices give different absolute results for the same night or reading, but each follows a trend that can be analysed. Pure Java (no Android classes), so it can be tested off the phone.

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class TrendStats {
    private TrendStats() {}

    /** Result of one series. NaN where a figure does not exist (too few points). */
    static final class Result {
        int n;                         // finite points in the display window
        int nBase;                     // points behind the personal usual
        double base = Double.NaN;      // the usual: median of the person's earlier values
        double spread = Double.NaN;    // the usual spread: 1.4826 x median absolute deviation, floored
        double lo = Double.NaN, hi = Double.NaN;   // usual range = base -/+ spread
        double last = Double.NaN;      // the newest value
        double lastDay = Double.NaN;   // its time (days)
        double recent = Double.NaN;    // median of the values of the last RECENT_DAYS days (the newest value when none)
        double delta = Double.NaN;     // recent - base
        double z = Double.NaN;         // delta / spread
        int status = 0;                // -2 well below, -1 below, 0 within the usual, 1 above, 2 well above
        double slopePerWeek = Double.NaN;   // Theil-Sen slope over the display window, per 7 days
        double p = Double.NaN;         // two-sided p of the Mann-Kendall trend test
        int trend = 0;                 // -1 falling, 0 steady, 1 rising (needs 6 or more points, p under 0.10 and a slope worth saying)
        double min = Double.NaN, max = Double.NaN;   // lowest and highest value in the window
        boolean enough;                // a usual could be made (5 or more earlier values)
    }

    static final double RECENT_DAYS = 3.0;
    static final int MIN_BASE = 5;

    static double median(double[] a, int n) {
        if (n == 0) return Double.NaN; double[] b = Arrays.copyOf(a, n); Arrays.sort(b); return n % 2 == 1 ? b[n / 2] : 0.5 * (b[n / 2 - 1] + b[n / 2]);
    }

    /** Standard normal cumulative distribution (Abramowitz and Stegun 7.1.26). */
    static double phi(double x) {
        double t = 1.0 / (1.0 + 0.2316419 * Math.abs(x)), d = 0.3989423 * Math.exp(-x * x / 2.0);
        double p = d * t * (0.3193815 + t * (-0.3565638 + t * (1.781478 + t * (-1.821256 + t * 1.330274))));
        return x > 0 ? 1.0 - p : p;
    }

    /**
     * @param day       time of each value in days (any origin), ascending
     * @param v         the values (NaN allowed)
     * @param winDays   the display window: the last winDays days before the newest value
     * @param baseDays  how far back the personal usual may reach (before the recent days)
     * @param minSpread the smallest spread worth believing (the unit of the metric), so a very steady person does not get a hairline band
     * @param slopeUnit the smallest slope per week worth calling a trend
     */
    static Result analyse(double[] day, double[] v, double winDays, double baseDays, double minSpread, double slopeUnit) {
        Result r = new Result(); int N = day.length; if (N == 0) return r;
        int last = -1; for (int i = N - 1; i >= 0; i--) if (!Double.isNaN(v[i])) { last = i; break; }
        if (last < 0) return r;
        double tEnd = day[last]; r.last = v[last]; r.lastDay = tEnd;
        // display window
        List<Integer> win = new ArrayList<>();
        for (int i = 0; i <= last; i++) if (!Double.isNaN(v[i]) && day[i] >= tEnd - winDays) win.add(i);
        r.n = win.size();
        if (r.n > 0) { r.min = 1e300; r.max = -1e300; for (int i : win) { r.min = Math.min(r.min, v[i]); r.max = Math.max(r.max, v[i]); } }
        // recent values and the personal usual (earlier values only, so a new shift is not absorbed into the usual)
        double[] rec = new double[N]; int nr = 0; double[] base = new double[N]; int nb = 0;
        for (int i = 0; i <= last; i++) {
            if (Double.isNaN(v[i])) continue;
            if (day[i] > tEnd - RECENT_DAYS) rec[nr++] = v[i];
            else if (day[i] >= tEnd - RECENT_DAYS - baseDays) base[nb++] = v[i];
        }
        r.recent = nr > 0 ? median(rec, nr) : r.last; r.nBase = nb;
        if (nb >= MIN_BASE) {
            r.enough = true; r.base = median(base, nb);
            double[] dev = new double[nb]; for (int i = 0; i < nb; i++) dev[i] = Math.abs(base[i] - r.base);
            double mad = median(dev, nb); r.spread = Math.max(1.4826 * mad, minSpread);
            r.lo = r.base - r.spread; r.hi = r.base + r.spread;
            r.delta = r.recent - r.base; r.z = r.delta / r.spread;
            double az = Math.abs(r.z); int s = az >= 2.0 ? 2 : az >= 1.0 ? 1 : 0; r.status = r.z < 0 ? -s : s;
        }
        // trend over the display window: Theil-Sen slope and the Mann-Kendall test
        if (r.n >= 6 && day[win.get(win.size() - 1)] - day[win.get(0)] >= winDays / 3.0) {
            int m = win.size(); double[] sl = new double[m * (m - 1) / 2]; int k = 0; long S = 0;
            for (int a = 0; a < m; a++) for (int b = a + 1; b < m; b++) {
                double dt = day[win.get(b)] - day[win.get(a)], dv = v[win.get(b)] - v[win.get(a)];
                if (dt > 0) { sl[k++] = dv / dt; S += dv > 0 ? 1 : dv < 0 ? -1 : 0; }
            }
            if (k > 0) {
                r.slopePerWeek = 7.0 * median(sl, k);
                double var = m * (m - 1.0) * (2.0 * m + 5.0) / 18.0, z = S == 0 ? 0.0 : (S > 0 ? (S - 1.0) : (S + 1.0)) / Math.sqrt(var);
                r.p = 2.0 * (1.0 - phi(Math.abs(z)));
                if (r.p < 0.10 && Math.abs(r.slopePerWeek) >= slopeUnit) r.trend = r.slopePerWeek > 0 ? 1 : -1;
            }
        }
        return r;
    }
}
