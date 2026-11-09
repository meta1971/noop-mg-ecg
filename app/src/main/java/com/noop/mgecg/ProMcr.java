package com.noop.mgecg;

// FILE VERSION 0.45.0 (8 Nov 2026): MCR v1, movement and contact recovery (decode log 137.2), a Java port for the 500 Hz report analysis and the replay.
// 0 gap fill, 1 median-cascade baseline, 2 step detector with its disturbance window, 4 beat scoring against the personal template (rho), rejection of
// detections under rho 0.6, merging of split pairs. Pure Java (no Android classes), so it runs off the phone. Step 3 (the IMU warning) is not here:
// the app has no live motion stream during a reading (R18 accelerometer floats arrive only in history pulls). Step 5 (what is used for what) lives in
// ProAnalysis: HRV and the AF screen take the validated beats only; averages take rho 0.95 or more, outside the disturbance windows, local noise at most 1.5 x the quiet noise.

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class ProMcr {
    private ProMcr() {}
    static final int FS = 500;
    static final int MED1 = 101, MED2 = 301;            // 0.2 s and 0.6 s windows at 500 Hz (odd)
    static final double STEP_UV = 1200.0;               // a step: the 4 Hz low-passed trace moves by more than 1.2 mV in 100 ms
    static final int STEP_SPAN = 50, STEP_GAP = 250;    // 100 ms span, steps at least 0.5 s apart
    static final int D_BEFORE = 250, D_AFTER = 2000;    // disturbance window: 0.5 s before to 4 s after a step
    static final double RHO_REJECT = 0.6;               // detections below this score are not beats
    static final int HALF_WIN = 60;                     // template window +-120 ms

    // ------------------------------------------------------------------ 1 baseline
    /** Sliding median, odd window w, edges padded with the edge value. NaN must not be present (gap-fill first). */
    static double[] medfilt(double[] x, int w) {
        int n = x.length, h = w / 2; double[] out = new double[n]; if (n == 0) return out;
        double[] win = new double[w];
        for (int j = 0; j < w; j++) win[j] = x[Math.max(0, Math.min(n - 1, j - h))];
        Arrays.sort(win); out[0] = win[h];
        for (int i = 1; i < n; i++) {
            double rem = x[Math.max(0, Math.min(n - 1, i - 1 - h))], add = x[Math.max(0, Math.min(n - 1, i + h))];
            int r = Arrays.binarySearch(win, rem);
            if (add > rem) { int j = r; while (j + 1 < w && win[j + 1] < add) { win[j] = win[j + 1]; j++; } win[j] = add; }
            else { int j = r; while (j - 1 >= 0 && win[j - 1] > add) { win[j] = win[j - 1]; j--; } win[j] = add; }
            out[i] = win[h];
        }
        return out;
    }

    /** b = median(median(x, 0.2 s), 0.6 s). */
    static double[] baseline(double[] x) { return medfilt(medfilt(x, MED1), MED2); }

    /** The MCR trace: x minus the cascade baseline, then the 50 Hz notch (the same notch as the report's own trace). */
    static double[] trace(double[] x) {
        double[] b = baseline(x), y = new double[x.length];
        for (int i = 0; i < y.length; i++) y[i] = x[i] - b[i];
        return ProDsp.filtfilt(y, new double[][] { ProDsp.notch(50, 35, FS) });
    }

    // ------------------------------------------------------------------ 0 gap fill
    /** Replaces NaN runs by linear interpolation (edges held) and returns the usable mask: false on every NaN sample and for 0.5 s after each NaN run. x is changed in place. */
    static boolean[] gapFill(double[] x) {
        int n = x.length; boolean[] ok = new boolean[n]; Arrays.fill(ok, true);
        int i = 0;
        while (i < n) {
            if (!Double.isNaN(x[i])) { i++; continue; }
            int j = i; while (j < n && Double.isNaN(x[j])) j++;
            double a = i > 0 ? x[i - 1] : (j < n ? x[j] : 0.0), b = j < n ? x[j] : a;
            for (int k = i; k < j; k++) { double f = (k - i + 1) / (double) (j - i + 1); x[k] = a + (b - a) * f; ok[k] = false; }
            for (int k = j; k < Math.min(n, j + 250); k++) ok[k] = false;
            i = j;
        }
        return ok;
    }

    // ------------------------------------------------------------------ 2 step detector
    /** Sample indices (centre of the 100 ms span) of steps of the raw trace; at least 0.5 s apart. */
    static int[] steps(double[] x) {
        int n = x.length; if (n < 4 * FS) return new int[0];
        double[] s = ProDsp.filtfilt(x, ProDsp.butterLow(2, 4, FS));
        List<Integer> out = new ArrayList<>(); int i = 0;
        while (i + STEP_SPAN < n) {
            double d = Math.abs(s[i + STEP_SPAN] - s[i]);
            if (d <= STEP_UV) { i++; continue; }
            int best = i; double bd = d; int j = i;
            while (j + STEP_SPAN < n && Math.abs(s[j + STEP_SPAN] - s[j]) > STEP_UV) { double dj = Math.abs(s[j + STEP_SPAN] - s[j]); if (dj > bd) { bd = dj; best = j; } j++; }
            int c = best + STEP_SPAN / 2;
            if (out.isEmpty() || c - out.get(out.size() - 1) >= STEP_GAP) out.add(c);
            i = Math.max(j, best + STEP_GAP);
        }
        int[] r = new int[out.size()]; for (int k = 0; k < r.length; k++) r[k] = out.get(k); return r;
    }

    /** Disturbance mask D: from 0.5 s before to 4 s after each step. */
    static boolean[] disturbed(int n, int[] steps) {
        boolean[] d = new boolean[n];
        for (int st : steps) for (int k = Math.max(0, st - D_BEFORE); k < Math.min(n, st + D_AFTER); k++) d[k] = true;
        return d;
    }

    // ------------------------------------------------------------------ 4 beats
    /** Normalised cross-correlation of two equal-length windows. */
    static double ncc(double[] a, double[] b) {
        int n = a.length; double ma = 0, mb = 0; for (int i = 0; i < n; i++) { ma += a[i]; mb += b[i]; } ma /= n; mb /= n;
        double sab = 0, saa = 0, sbb = 0; for (int i = 0; i < n; i++) { double u = a[i] - ma, v = b[i] - mb; sab += u * v; saa += u * u; sbb += v * v; }
        return saa > 0 && sbb > 0 ? sab / Math.sqrt(saa * sbb) : 0.0;
    }

    /** Window of 2 x HALF_WIN + 1 samples around p, or null when it does not fit. */
    static double[] window(double[] y, int p) {
        if (p - HALF_WIN < 0 || p + HALF_WIN >= y.length) return null;
        return Arrays.copyOfRange(y, p - HALF_WIN, p + HALF_WIN + 1);
    }

    /** The personal template of a reading: the per-sample median of the windows of all detections. */
    static double[] medianTemplate(double[] y, int[] pk) {
        List<double[]> w = new ArrayList<>(); for (int p : pk) { double[] v = window(y, p); if (v != null) w.add(v); }
        if (w.size() < 5) return null;
        double[] t = new double[2 * HALF_WIN + 1], col = new double[w.size()];
        for (int j = 0; j < t.length; j++) { for (int k = 0; k < col.length; k++) col[k] = w.get(k)[j]; t[j] = ProDsp.median(col); }
        return t;
    }

    static final class Valid {
        int[] idx = new int[0]; double[] rho = new double[0]; int rejected, merged, checked;
    }

    /**
     * Scores every detection against the template (rho), drops those under 0.6, then merges split pairs: when either of two successive intervals is under 0.65 x the local median
     * and the two sum to 0.8 to 1.2 x it, the MIDDLE detection (the spurious one) is dropped. tpl == null: the median template of this run. Detections whose window does not fit keep rho NaN and stay.
     */
    static Valid validate(double[] y, int[] pk, double[] tpl) {
        Valid v = new Valid(); v.checked = pk.length;
        if (tpl == null) tpl = medianTemplate(y, pk);
        List<Integer> keep = new ArrayList<>(); List<Double> rho = new ArrayList<>();
        for (int p : pk) {
            double[] w = window(y, p); double r = Double.NaN;
            if (w != null && tpl != null) { r = ncc(w, tpl); if (r < RHO_REJECT) { v.rejected++; continue; } }
            keep.add(p); rho.add(r);
        }
        // merge split pairs (drop the middle detection)
        boolean again = true; int guard = 0;
        while (again && guard++ < 50) {
            again = false; int m = keep.size();
            if (m < 5) break;
            for (int i = 1; i + 1 < m; i++) {
                double r1 = keep.get(i) - keep.get(i - 1), r2 = keep.get(i + 1) - keep.get(i);
                double[] nb = new double[Math.min(m - 1, 5)]; int c = 0;
                for (int j = Math.max(0, i - 3); j < Math.min(m - 1, i + 3) && c < nb.length; j++) { if (j == i - 1 || j == i) continue; nb[c++] = keep.get(j + 1) - keep.get(j); }
                if (c < 2) continue;
                double med = ProDsp.median(Arrays.copyOf(nb, c));
                if ((r1 < 0.65 * med || r2 < 0.65 * med) && r1 + r2 >= 0.8 * med && r1 + r2 <= 1.2 * med) { keep.remove(i); rho.remove(i); v.merged++; again = true; break; }
            }
        }
        v.idx = new int[keep.size()]; v.rho = new double[keep.size()];
        for (int i = 0; i < v.idx.length; i++) { v.idx[i] = keep.get(i); v.rho[i] = rho.get(i); }
        return v;
    }

    /** Parabolic apex of the maximum of y within +-2 samples of p; returns the sub-sample position. */
    static double apex(double[] y, int p) {
        int l = Math.max(1, p - 2), h = Math.min(y.length - 2, p + 2); if (h < l) return p;
        int bi = l; for (int i = l; i <= h; i++) if (y[i] > y[bi]) bi = i;
        double A = y[bi - 1], B = y[bi], C = y[bi + 1], D = A - 2 * B + C;
        return bi + (D != 0 ? 0.5 * (A - C) / D : 0.0);
    }
}
