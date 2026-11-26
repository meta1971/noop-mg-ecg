package com.noop.mgecg;

// FILE VERSION 0.92.0 (25 Nov 2026): the maths of the interactive strip viewer (StripActivity / StripView): which part of the reading is on screen, pan, pinch zoom, and the numbers shown for it.
// Pure Java, no Android classes, so it runs in the plain-Java tests.
final class StripViewport {
    static final double MIN_SPAN = 1.0, MAX_SPAN = 30.0;
    final double total;           // seconds of signal on the grid
    double t0, span;              // first visible second, visible seconds

    StripViewport(double totalSeconds, double span0, double t00) {
        total = Math.max(0.0, totalSeconds);
        span = span0; t0 = t00;
        clamp();
    }

    private void clamp() {
        double lo = Math.min(MIN_SPAN, total), hi = Math.min(MAX_SPAN, total);
        if (span < lo) span = lo;
        if (span > hi) span = hi;
        if (t0 > total - span) t0 = total - span;
        if (t0 < 0) t0 = 0;
    }

    /** Move the window by dt seconds (positive = later). */
    void pan(double dt) { t0 += dt; clamp(); }

    /** factor above 1 zooms in. focusFrac (0..1) is the point of the window that stays under the fingers. */
    void zoom(double factor, double focusFrac) {
        if (factor <= 0 || Double.isNaN(factor)) return;
        double f = Math.max(0, Math.min(1, focusFrac)), tf = t0 + f * span;
        span = span / factor;
        clamp();
        t0 = tf - f * span;
        clamp();
    }

    void set(double t00, double span0) { t0 = t00; span = span0; clamp(); }

    double timeAt(float x, float w) { return w <= 0 ? t0 : t0 + (double) x / w * span; }
    float xAt(double t, float w) { return (float) ((t - t0) / span * w); }

    // ------------------------------------------------------------------ numbers for the window

    /** {beats inside, heart rate from the median gap between neighbouring beats (NaN with fewer than 2 gaps), median gap in ms}. A gap outside 300 to 2000 ms is not a heartbeat gap and is left out. */
    static double[] stats(int[] beats, double fs, double a, double b) {
        int n = 0;
        java.util.ArrayList<Double> rr = new java.util.ArrayList<>();
        for (int i = 0; i < beats.length; i++) {
            double t = beats[i] / fs;
            if (t < a || t >= b) continue;
            n++;
            if (i > 0) {
                double prev = beats[i - 1] / fs, g = (t - prev) * 1000.0;
                if (prev >= a && g >= 300 && g <= 2000) rr.add(g);
            }
        }
        if (rr.size() < 2) return new double[] { n, Double.NaN, Double.NaN };
        java.util.Collections.sort(rr);
        int m = rr.size() / 2;
        double med = rr.size() % 2 == 1 ? rr.get(m) : (rr.get(m - 1) + rr.get(m)) / 2.0;
        return new double[] { n, 60000.0 / med, med };
    }

    /** Index into beats of the beat nearest to time t within tol seconds, or -1. */
    static int nearestBeat(int[] beats, double fs, double t, double tol) {
        int best = -1; double bd = tol;
        for (int i = 0; i < beats.length; i++) {
            double d = Math.abs(beats[i] / fs - t);
            if (d <= bd) { bd = d; best = i; }
        }
        return best;
    }

    /** Gap in ms from the beat before beat i to beat i, or NaN when i is 0 or the gap is not between 300 and 2000 ms. */
    static double rrBefore(int[] beats, int i, double fs) {
        if (i <= 0 || i >= beats.length) return Double.NaN;
        double g = (beats[i] - beats[i - 1]) * 1000.0 / fs;
        return g >= 300 && g <= 2000 ? g : Double.NaN;
    }

    /** Second at which to open the viewer: the start of the first stretch of 6 clean seconds (strap quality 3, signal present); 0 when there is none. */
    static double firstClean(byte[] quality, double[] mv, double fs) {
        int need = (int) (6 * fs), run = 0;
        for (int i = 0; i < mv.length; i++) {
            boolean ok = quality[i] == 3 && !Double.isNaN(mv[i]);
            run = ok ? run + 1 : 0;
            if (run >= need) return (i - need + 1) / fs;
        }
        return 0.0;
    }

    /** Most beats pointing down (a bigger dip than peak within 40 ms of the marked beat, at least 4 beats judged) = the recording came in upside down. */
    static boolean looksInverted(int[] beats, double[] mv, double fs) {
        int down = 0, n = 0, w = (int) (0.04 * fs);
        for (int b : beats) {
            if (b - w < 0 || b + w >= mv.length) continue;
            double hi = -1e9, lo = 1e9;
            for (int i = b - w; i <= b + w; i++) { double v = mv[i]; if (Double.isNaN(v)) continue; if (v > hi) hi = v; if (v < lo) lo = v; }
            if (hi < -1e8) continue;
            n++;
            if (Math.abs(lo) > Math.abs(hi)) down++;
        }
        return n >= 4 && down * 2 > n;
    }

    static String clock(double t) {
        int s = (int) Math.floor(t + 1e-9);
        int tenth = (int) Math.floor((t - s) * 10 + 1e-9);
        return s / 60 + ":" + (s % 60 < 10 ? "0" : "") + (s % 60) + "." + tenth;
    }
}
