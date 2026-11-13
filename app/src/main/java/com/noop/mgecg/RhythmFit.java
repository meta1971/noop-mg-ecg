package com.noop.mgecg;

// FILE VERSION 0.54.0 (13 Nov 2026): NEW. One-component cosinor fit of a 24 h rhythm (period 1440 minutes): y = MESOR + A cos(2 pi t / 1440 + phi).
// The sign convention is the CosinorAge package's: acrophase phi in radians, shifted to (-2 pi, 0], peak time = -phi / (2 pi) x 24 h. Pure Java.

final class RhythmFit {
    private RhythmFit() {}

    static final class Result {
        boolean ok;
        int n;
        double mesor = Double.NaN, amp = Double.NaN, phi = Double.NaN, peakH = Double.NaN, r2 = Double.NaN;
        double coverage = Double.NaN;                   // share of the minutes of the span that had a value
    }

    /** y: one value per minute from local midnight of day 0 (NaN = none). Fits all days together. */
    static Result fitMinutes(double[] y, int fromMin, int toMin) {
        int n = 0;
        double[][] a = new double[3][4];
        final double w = 2.0 * Math.PI / 1440.0;
        for (int i = fromMin; i < toMin && i < y.length; i++) {
            double v = y[i]; if (Double.isNaN(v)) continue;
            double c = Math.cos(w * i), s = Math.sin(w * i);
            double[] x = {1.0, c, s};
            for (int p = 0; p < 3; p++) { for (int q = 0; q < 3; q++) a[p][q] += x[p] * x[q]; a[p][3] += x[p] * v; }
            n++;
        }
        Result r = new Result();
        r.n = n;
        int span = Math.max(1, Math.min(toMin, y.length) - fromMin);
        r.coverage = n / (double) span;
        if (n < 360) return r;
        double[] b = solve(a);
        if (b == null) return r;
        r.mesor = b[0];
        r.amp = Math.hypot(b[1], b[2]);
        double phi = Math.atan2(-b[2], b[1]);
        if (phi > 0) phi -= 2.0 * Math.PI;
        r.phi = phi;
        r.peakH = (-phi / (2.0 * Math.PI)) * 24.0;
        if (r.peakH >= 24.0) r.peakH -= 24.0;
        double mean = 0; for (int i = fromMin; i < toMin && i < y.length; i++) if (!Double.isNaN(y[i])) mean += y[i];
        mean /= n;
        double sst = 0, sse = 0;
        for (int i = fromMin; i < toMin && i < y.length; i++) {
            double v = y[i]; if (Double.isNaN(v)) continue;
            double f = b[0] + b[1] * Math.cos(w * i) + b[2] * Math.sin(w * i);
            sst += (v - mean) * (v - mean); sse += (v - f) * (v - f);
        }
        r.r2 = sst > 0 ? 1.0 - sse / sst : Double.NaN;
        r.ok = true;
        return r;
    }

    /** A 24-value profile (one value per hour, NaN = none), fitted at the hour middles. */
    static Result fitHourly(double[] prof) {
        int n = 0; double[][] a = new double[3][4];
        final double w = 2.0 * Math.PI / 24.0;
        for (int h = 0; h < 24 && h < prof.length; h++) {
            double v = prof[h]; if (Double.isNaN(v)) continue;
            double t = h + 0.5; double[] x = {1.0, Math.cos(w * t), Math.sin(w * t)};
            for (int p = 0; p < 3; p++) { for (int q = 0; q < 3; q++) a[p][q] += x[p] * x[q]; a[p][3] += x[p] * v; }
            n++;
        }
        Result r = new Result(); r.n = n; r.coverage = n / 24.0;
        if (n < 12) return r;
        double[] b = solve(a); if (b == null) return r;
        r.mesor = b[0]; r.amp = Math.hypot(b[1], b[2]);
        double phi = Math.atan2(-b[2], b[1]); if (phi > 0) phi -= 2.0 * Math.PI;
        r.phi = phi; r.peakH = (-phi / (2.0 * Math.PI)) * 24.0; if (r.peakH >= 24.0) r.peakH -= 24.0;
        double mean = 0; for (int h = 0; h < 24; h++) if (!Double.isNaN(prof[h])) mean += prof[h]; mean /= n;
        double sst = 0, sse = 0;
        for (int h = 0; h < 24; h++) { if (Double.isNaN(prof[h])) continue; double t = h + 0.5; double f = b[0] + b[1] * Math.cos(w * t) + b[2] * Math.sin(w * t); sst += (prof[h] - mean) * (prof[h] - mean); sse += (prof[h] - f) * (prof[h] - f); }
        r.r2 = sst > 0 ? 1.0 - sse / sst : Double.NaN;
        r.ok = true;
        return r;
    }

    /** Circular standard deviation of clock hours, in hours (NaN with fewer than 3 values). */
    static double circularSdHours(double[] hours, int n) {
        if (n < 3) return Double.NaN;
        double sc = 0, ss = 0;
        for (int i = 0; i < n; i++) { double ang = hours[i] / 24.0 * 2.0 * Math.PI; sc += Math.cos(ang); ss += Math.sin(ang); }
        double R = Math.hypot(sc / n, ss / n);
        if (R <= 1e-9) return 12.0;
        return Math.sqrt(-2.0 * Math.log(Math.min(1.0, R))) / (2.0 * Math.PI) * 24.0;
    }

    private static double[] solve(double[][] m) {
        for (int c = 0; c < 3; c++) {
            int p = c; for (int r = c + 1; r < 3; r++) if (Math.abs(m[r][c]) > Math.abs(m[p][c])) p = r;
            if (Math.abs(m[p][c]) < 1e-9) return null;
            double[] t = m[c]; m[c] = m[p]; m[p] = t;
            for (int r = 0; r < 3; r++) { if (r == c) continue; double f = m[r][c] / m[c][c]; for (int k = c; k < 4; k++) m[r][k] -= f * m[c][k]; }
        }
        return new double[] { m[0][3] / m[0][0], m[1][3] / m[1][1], m[2][3] / m[2][2] };
    }
}
