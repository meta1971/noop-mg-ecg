package com.noop.mgecg;

// FILE VERSION 0.53.8 (12 Nov 2026): the arithmetic of the full-screen stress graphs (StressFullActivity), kept free of Android so it can be tested off the phone: the visible time window (zoom about a point,
// pan, limits), tick positions on the local clock, y-axis ticks, the bin under a point, the per-day totals.

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

final class ChartMath {
    private ChartMath() {}

    static final double MIN_SPAN = 1200.0;           // 20 minutes: four 5-minute bins

    /** Window [x0, x1] after zooming by [factor] (above 1 zooms in) about the time [focusT]; kept inside [minX, maxX] and between MIN_SPAN and the whole extent. */
    static double[] zoom(double x0, double x1, double focusT, double factor, double minX, double maxX) {
        double span = x1 - x0, ns = span / Math.max(0.05, factor), full = Math.max(MIN_SPAN, maxX - minX);
        ns = Math.max(MIN_SPAN, Math.min(full, ns));
        double fr = span > 0 ? (focusT - x0) / span : 0.5, n0 = focusT - fr * ns;
        return fit(n0, n0 + ns, minX, maxX);
    }

    /** Window moved by [dt] seconds, kept inside the extent. */
    static double[] pan(double x0, double x1, double dt, double minX, double maxX) { return fit(x0 + dt, x1 + dt, minX, maxX); }

    static double[] fit(double x0, double x1, double minX, double maxX) {
        double span = x1 - x0;
        if (span >= maxX - minX) return new double[] { minX, maxX };
        if (x0 < minX) { x0 = minX; x1 = minX + span; }
        if (x1 > maxX) { x1 = maxX; x0 = maxX - span; }
        return new double[] { x0, x1 };
    }

    /** Window of [hours] hours ending at [endT], kept inside the extent. */
    static double[] ending(double endT, double hours, double minX, double maxX) { return fit(endT - hours * 3600.0, endT, minX, maxX); }

    /** Round tick values between lo and hi, about [target] of them (1, 2 or 5 times a power of ten). */
    static double[] niceTicks(double lo, double hi, int target) {
        if (!(hi > lo)) return new double[] { lo };
        double raw = (hi - lo) / Math.max(1, target), pow = Math.pow(10, Math.floor(Math.log10(raw))), f = raw / pow, step = (f < 1.5 ? 1 : f < 3.5 ? 2 : f < 7.5 ? 5 : 10) * pow;
        double first = Math.ceil(lo / step) * step; List<Double> v = new ArrayList<Double>(); for (double t = first; t <= hi + 1e-9; t += step) v.add(t);
        double[] a = new double[v.size()]; for (int i = 0; i < a.length; i++) a[i] = v.get(i); return a;
    }

    static final double[] STEPS = { 300, 600, 900, 1800, 3600, 7200, 10800, 21600, 43200, 86400, 172800 };

    /** Ticks for the window on the LOCAL clock: {time, 1 if it is a local midnight}. The step is the smallest of 5 min to 2 days that gives at most [maxTicks] ticks. */
    static List<double[]> timeTicks(double x0, double x1, TimeZone tz, int maxTicks) {
        double span = x1 - x0, step = STEPS[STEPS.length - 1]; for (double s : STEPS) if (span / s <= maxTicks) { step = s; break; }
        List<double[]> out = new ArrayList<double[]>(); double off = tz.getOffset((long) (x0 * 1000.0)) / 1000.0;
        for (double t = Math.ceil((x0 + off) / step) * step - off; t <= x1 + 1e-6; t += step) {
            double o2 = tz.getOffset((long) (t * 1000.0)) / 1000.0; double local = t + o2; boolean midnight = Math.abs(local - Math.round(local / 86400.0) * 86400.0) < 1.0;
            out.add(new double[] { t, midnight ? 1 : 0 });
        }
        return out;
    }

    static String tickLabel(double t, boolean midnight, double step, TimeZone tz) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(midnight ? "EEE d MMM" : step >= 86400 ? "EEE d" : "HH:mm", Locale.UK); f.setTimeZone(tz); return f.format(new java.util.Date((long) (t * 1000.0)));
    }

    /** Index of the first bin that ends after [t] (bins are sorted by start time and last BIN_S seconds). */
    static int firstBin(List<StressModel.Bin> bins, double t) {
        int lo = 0, hi = bins.size(); while (lo < hi) { int m = (lo + hi) >>> 1; if (bins.get(m).t + StressModel.BIN_S < t) lo = m + 1; else hi = m; } return lo;
    }

    /** The bin that contains [t], or the nearest one within one bin, or -1. */
    static int binAt(List<StressModel.Bin> bins, double t) {
        int i = firstBin(bins, t); int best = -1; double bd = StressModel.BIN_S + 1.0;
        for (int k = Math.max(0, i - 1); k <= Math.min(bins.size() - 1, i + 1); k++) { StressModel.Bin b = bins.get(k); double d = t >= b.t && t < b.t + StressModel.BIN_S ? 0.0 : Math.min(Math.abs(t - b.t), Math.abs(t - (b.t + StressModel.BIN_S))); if (d < bd) { bd = d; best = k; } }
        return best;
    }

    static final class Day { long day0; int low, med, high, scored; double mean = Double.NaN; int moving; }

    /** Minutes at each level per local day (days with at least one scored bin), oldest first. */
    static List<Day> days(List<StressModel.Bin> bins, TimeZone tz) {
        List<Day> out = new ArrayList<Day>(); Day cur = null; long sum = 0;
        for (StressModel.Bin b : bins) {
            if (b.score < 0) continue; double off = tz.getOffset(b.t * 1000L) / 1000.0; long d0 = (long) (Math.floor((b.t + off) / 86400.0) * 86400.0 - off);
            if (cur == null || cur.day0 != d0) { if (cur != null) cur.mean = sum / (double) cur.scored; cur = new Day(); cur.day0 = d0; out.add(cur); sum = 0; }
            int m = StressModel.BIN_S / 60; if (b.score < 20) cur.low += m; else if (b.score < 50) cur.med += m; else cur.high += m; if (b.band != 0) cur.moving += m; cur.scored++; sum += b.score;
        }
        if (cur != null) cur.mean = sum / (double) cur.scored; return out;
    }
}
