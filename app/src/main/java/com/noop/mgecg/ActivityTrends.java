package com.noop.mgecg;

// FILE VERSION 0.1.0 (23 Oct 2026): long-view maths for the Activity screen. Pure Java (no Android) so it is tested off the phone.
// Ideas adapted from Noop Aura's published methods (acute:chronic load, contribution heat grid, personal day shape); written independently here.
// Research only. Not medical advice.

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.List;

final class ActivityTrends {
    private ActivityTrends() {}

    // ================================================================ workload: last 7 days against your usual 6 weeks

    static final class LoadStatus {
        double atl = Double.NaN, ctl = Double.NaN, ratio = Double.NaN;
        int loggedIn42, loggedIn7;
        boolean calibrating = true;
        String word = "calibrating", sentence = "";
    }

    /**
     * dailyLoad: oldest first, up to 42 entries, NaN for a day that was not logged (or logged under 10 hours).
     * ATL = mean load of the logged days among the last 7, CTL = mean over the logged days among the last 42.
     * Needs 14 logged days in the 42 and 4 in the last 7 before it says anything.
     */
    static LoadStatus loadStatus(double[] dailyLoad) {
        LoadStatus s = new LoadStatus();
        int n = dailyLoad.length;
        double a = 0, c = 0;
        int na = 0, nc = 0;
        for (int i = Math.max(0, n - 42); i < n; i++) {
            if (Double.isNaN(dailyLoad[i])) continue;
            c += dailyLoad[i]; nc++;
            if (i >= n - 7) { a += dailyLoad[i]; na++; }
        }
        s.loggedIn42 = nc; s.loggedIn7 = na;
        if (na > 0) s.atl = a / na;
        if (nc > 0) s.ctl = c / nc;
        if (nc < 14 || na < 4 || !(s.ctl > 0)) {
            s.sentence = "Needs about 14 logged days (at least 10 hours each) before it can compare your last week with your usual. So far: " + nc + " in the last 6 weeks.";
            return s;
        }
        s.calibrating = false;
        s.ratio = s.atl / s.ctl;
        if (s.ratio < 0.8) s.word = "lighter than usual";
        else if (s.ratio < 1.0) s.word = "a little below usual";
        else if (s.ratio <= 1.3) s.word = "in your usual range";
        else s.word = "heavier than usual";
        s.sentence = String.format(java.util.Locale.UK, "Your last 7 days averaged %.0f load a day against %.0f over the last 6 weeks (%.2f\u00d7).", s.atl, s.ctl, s.ratio);
        return s;
    }

    // ================================================================ heat grid, 13 weeks, Monday first

    static final class Cell {
        long dayStart = -1;           // -1 = a pad cell (before the first day or after today)
        int activeMin = -1;           // -1 = no data
        int level;                    // 0 no data, 1..4
    }

    static final class Grid {
        int weeks;
        Cell[][] cols;                // [week][weekday 0=Mon..6=Sun]
        double scale;                 // active minutes that count as the darkest level
    }

    /** days: dayStart (unix seconds) -> active minutes, or -1 for no data, oldest first and consecutive. todayStart: the dayStart of today. */
    static Grid heatGrid(long[] dayStart, int[] activeMin, long todayStart, int weeks) {
        Grid g = new Grid();
        g.weeks = weeks;
        g.cols = new Cell[weeks][7];
        for (int w = 0; w < weeks; w++) for (int d = 0; d < 7; d++) g.cols[w][d] = new Cell();
        // scale = 90th percentile of the active days, floored at 60 min
        List<Integer> act = new ArrayList<Integer>();
        for (int v : activeMin) if (v > 0) act.add(v);
        double scale = 60;
        if (!act.isEmpty()) { Collections.sort(act); scale = Math.max(60, act.get((int) Math.round(0.9 * (act.size() - 1)))); }
        g.scale = scale;
        int todayWd = weekdayMon0(todayStart);
        // column weeks-1 holds this week; its row todayWd is today
        for (int i = 0; i < dayStart.length; i++) {
            long daysBack = Math.round((todayStart - dayStart[i]) / 86400.0);
            int idx = (weeks - 1) * 7 + todayWd - (int) daysBack;       // position counted from the first cell
            if (idx < 0 || idx >= weeks * 7) continue;
            Cell c = g.cols[idx / 7][idx % 7];
            c.dayStart = dayStart[i];
            c.activeMin = activeMin[i];
            c.level = activeMin[i] < 0 ? 0 : activeMin[i] == 0 ? 1 : Math.min(4, 1 + (int) Math.floor(3.0 * Math.min(1.0, activeMin[i] / scale) + 1e-9));
        }
        return g;
    }

    /** 0 = Monday ... 6 = Sunday, in the phone's time zone, for the instant (dayStart is 04:00 local, so +6 h is safely inside that date). */
    static int weekdayMon0(long dayStart) {
        GregorianCalendar c = new GregorianCalendar();
        c.setTimeInMillis((dayStart + 6 * 3600L) * 1000L);
        int w = c.get(GregorianCalendar.DAY_OF_WEEK);           // Sunday = 1
        return (w + 5) % 7;
    }

    // ================================================================ your usual day shape

    /**
     * hourly: one array of 24 active-minute counts per finished day (index = hours since 04:00). Days with under 60 active minutes are skipped.
     * Each day is turned into a cumulative share of its own total BEFORE days are combined, and hours are combined with the MEDIAN, so one huge day
     * cannot redefine a normal afternoon. Returns the 24 cumulative fractions (non-decreasing, last = 1), or null with fewer than minDays usable days.
     */
    static double[] usualShape(List<int[]> hourly, int minDays) {
        List<double[]> cum = new ArrayList<double[]>();
        for (int[] h : hourly) {
            if (h == null || h.length != 24) continue;
            int tot = 0;
            for (int v : h) tot += v;
            if (tot < 60) continue;
            double[] c = new double[24];
            double run = 0;
            for (int i = 0; i < 24; i++) { run += h[i]; c[i] = run / tot; }
            cum.add(c);
        }
        if (cum.size() < minDays) return null;
        double[] out = new double[24];
        for (int i = 0; i < 24; i++) {
            double[] col = new double[cum.size()];
            for (int k = 0; k < col.length; k++) col[k] = cum.get(k)[i];
            Arrays.sort(col);
            out[i] = col.length % 2 == 1 ? col[col.length / 2] : 0.5 * (col[col.length / 2 - 1] + col[col.length / 2]);
            if (i > 0) out[i] = Math.max(out[i], out[i - 1]);
        }
        out[23] = 1.0;
        return out;
    }

    /** Expected total active minutes for today given the minutes so far at hour index h (0..23) and the usual shape. NaN when too little of the day is behind us. */
    static double projectedTotal(double[] shape, int hourIndex, double fractionOfHour, int soFarMin) {
        if (shape == null || hourIndex < 0 || hourIndex > 23) return Double.NaN;
        double prev = hourIndex == 0 ? 0 : shape[hourIndex - 1];
        double now = prev + (shape[hourIndex] - prev) * Math.max(0, Math.min(1, fractionOfHour));
        if (now < 0.15) return Double.NaN;
        return soFarMin / now;
    }

    // ================================================================ annotations (rename / not activity)

    static final class Ann {
        long start, end;
        boolean dismissed;
        String label = "";
    }

    /** The annotation that overlaps at least half of the span, or null. */
    static Ann match(List<Ann> anns, long start, long end) {
        Ann best = null;
        double bestOv = 0;
        long len = Math.max(1, end - start);
        for (Ann a : anns) {
            long ov = Math.min(end, a.end) - Math.max(start, a.start);
            if (ov <= 0) continue;
            double f = ov / (double) len;
            if (f >= 0.5 && f > bestOv) { best = a; bestOv = f; }
        }
        return best;
    }

    /** Removes the dismissed periods from the day's totals (what they contributed is taken back out), and applies renames. Returns how many were dismissed. */
    static int applyAnnotations(ActivityDetector.Result r, List<Ann> anns) {
        if (anns == null || anns.isEmpty()) return 0;
        int dismissed = 0;
        List<ActivityDetector.Bout> keep = new ArrayList<ActivityDetector.Bout>();
        for (ActivityDetector.Bout b : r.bouts) {
            Ann a = match(anns, b.start, b.end);
            if (a != null && a.dismissed) {
                dismissed++;
                r.activeMin = Math.max(0, r.activeMin - b.activeMin);
                for (int k = 0; k < 4; k++) r.levelMin[k] = Math.max(0, r.levelMin[k] - b.levelMin[k]);
                r.load = Math.max(0, r.load - b.load);
                continue;
            }
            if (a != null && a.label != null && !a.label.isEmpty()) b.label = a.label;
            keep.add(b);
        }
        r.bouts = keep;
        r.boutMin = 0;
        r.longestBoutMin = 0;
        for (ActivityDetector.Bout b : keep) { r.boutMin += b.durationMin; r.longestBoutMin = Math.max(r.longestBoutMin, b.durationMin); }
        return dismissed;
    }

    // ================================================================ compaction: seconds -> minutes -> seconds

    /** One minute of the per-second log, kept after the seconds are archived. */
    static final class MinuteRow {
        long ts;                      // minute start, unix seconds
        int n, hrMin, hrMax, steps0, steps1, act, state;
        double hr = Double.NaN, dyn = Double.NaN, skin = Double.NaN;
    }

    /** Arrays are per-second and ascending in ts. hr 0 = none, dyn NaN = none, skin NaN = none. */
    static List<MinuteRow> decimate(long[] ts, int[] hr, double[] dyn, int[] steps, int[] act, int[] state, double[] skin) {
        List<MinuteRow> out = new ArrayList<MinuteRow>();
        int i = 0, n = ts.length;
        while (i < n) {
            long m = ts[i] - ts[i] % 60;
            int j = i;
            MinuteRow r = new MinuteRow();
            r.ts = m;
            r.hrMin = Integer.MAX_VALUE;
            double hs = 0, ds = 0, ss = 0;
            int hc = 0, dc = 0, sc = 0;
            int[] stateCount = new int[4];
            r.steps0 = steps[i];
            while (j < n && ts[j] - ts[j] % 60 == m) {
                r.n++;
                if (hr[j] >= 35 && hr[j] <= 220) { hs += hr[j]; hc++; r.hrMin = Math.min(r.hrMin, hr[j]); r.hrMax = Math.max(r.hrMax, hr[j]); }
                if (!Double.isNaN(dyn[j])) { ds += dyn[j]; dc++; }
                if (!Double.isNaN(skin[j])) { ss += skin[j]; sc++; }
                r.act = Math.max(r.act, act[j]);
                stateCount[Math.max(0, Math.min(3, state[j]))]++;
                r.steps1 = steps[j];
                j++;
            }
            if (hc > 0) r.hr = hs / hc; else r.hrMin = 0;
            if (dc > 0) r.dyn = ds / dc;
            if (sc > 0) r.skin = ss / sc;
            int best = 0;
            for (int k = 1; k < 4; k++) if (stateCount[k] > stateCount[best]) best = k;
            r.state = best;
            out.add(r);
            i = j;
        }
        return out;
    }

    /** Turns minute rows back into per-second arrays the detector can read. Every second of a minute carries the minute's mean heart rate and motion. */
    static final class Seconds {
        long[] ts; int[] hr, steps; double[] dyn;
    }

    static Seconds expand(List<MinuteRow> rows) {
        int total = 0;
        for (MinuteRow r : rows) total += Math.max(1, Math.min(60, r.n));
        Seconds s = new Seconds();
        s.ts = new long[total]; s.hr = new int[total]; s.steps = new int[total]; s.dyn = new double[total];
        int k = 0;
        for (MinuteRow r : rows) {
            int c = Math.max(1, Math.min(60, r.n));
            for (int q = 0; q < c; q++) {
                s.ts[k] = r.ts + q;
                s.hr[k] = Double.isNaN(r.hr) ? 0 : (int) Math.round(r.hr);
                s.dyn[k] = r.dyn;
                s.steps[k] = (q == c - 1) ? r.steps1 : r.steps0;
                k++;
            }
        }
        return s;
    }
}
