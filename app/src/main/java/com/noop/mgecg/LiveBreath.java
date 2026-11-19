package com.noop.mgecg;

// FILE VERSION 0.71.2 (19 Nov 2026): breathing rate for the live graph, shown as one dimmed cell in the header band (never over the trace).
// Method: the accepted beats of the last 60 s (Live500.acceptedBeatSec), R-R of each beat pair kept when it lies within 0.65 to 1.6 x the median R-R, put on a 4 Hz grid by linear interpolation,
// linear trend removed, Hann window, power at 0.14 to 0.46 Hz (8 to 28 breaths a minute) in steps of 0.005 Hz; the highest peak counts when it is at least 20 x the median power 0.04 to 0.12 Hz either side of it
// (false-peak rate on synthetic R-R noise, white and 1/f, measured off the phone: see the test in addendum 323), and it is shown only after two estimates a second apart agree within 2 a minute.
// INFERRED, NOT MEASURED: it reads the R-R modulation at the breathing frequency (respiratory sinus arrhythmia). It has NOT been checked against a breathing reference; a weak or irregular
// modulation, breaths slower than 8 a minute, or a restless reading give "--". Display only: nothing is stored and nothing else uses it. Pure Java (no Android), tested off the phone.

import java.util.Arrays;

final class LiveBreath {
    private LiveBreath() {}

    static final double WIN_S = 60.0, GRID_HZ = 4.0, F_LO = 0.14, F_HI = 0.46, F_STEP = 0.005, MIN_PROM = 20.0, NEAR = 0.04, FAR = 0.12, SPEC_LO = F_LO - FAR, SPEC_HI = F_HI + FAR;
    static final int MIN_BEATS = 30;

    static final class Est { double perMin, prom; int beats; double spanS; }

    private static double median(double[] x, int n) { double[] c = Arrays.copyOf(x, n); Arrays.sort(c); return n == 0 ? Double.NaN : (n % 2 == 1 ? c[n / 2] : 0.5 * (c[n / 2 - 1] + c[n / 2])); }

    /** beatSec: accepted beat times in seconds, ascending (n used). Null when the last 60 s hold no clear breathing peak. */
    static Est estimate(double[] beatSec, int n) {
        if (beatSec == null || n < MIN_BEATS) return null;
        double end = beatSec[n - 1], start = end - WIN_S; int i0 = 0; while (i0 < n && beatSec[i0] < start) i0++;
        int m = n - i0; if (m < MIN_BEATS) return null;
        double[] d = new double[m - 1]; for (int j = 0; j < m - 1; j++) d[j] = beatSec[i0 + j + 1] - beatSec[i0 + j];
        double med = median(d, m - 1); if (!(med > 0.3 && med < 2.0)) return null;
        double[] tv = new double[m - 1], rv = new double[m - 1]; int nv = 0;
        for (int j = 0; j < m - 1; j++) if (d[j] >= 0.65 * med && d[j] <= 1.6 * med && d[j] > 0.3 && d[j] < 2.0) { tv[nv] = beatSec[i0 + j + 1]; rv[nv] = d[j]; nv++; }
        if (nv < MIN_BEATS - 2 || tv[nv - 1] - tv[0] < 40.0) return null;
        int N = (int) Math.floor((tv[nv - 1] - tv[0]) * GRID_HZ) + 1; double[] x = new double[N]; int gaps = 0, a = 0;
        for (int i = 0; i < N; i++) {
            double t = tv[0] + i / GRID_HZ; while (a < nv - 2 && tv[a + 1] < t) a++;
            double t0 = tv[a], t1 = tv[a + 1], f = t1 > t0 ? (t - t0) / (t1 - t0) : 0.0; f = Math.max(0.0, Math.min(1.0, f));
            x[i] = rv[a] + f * (rv[a + 1] - rv[a]); if (t1 - t0 > 4.0) gaps++;
        }
        if (gaps > 0.12 * N) return null;
        double sx = 0, sy = 0, sxx = 0, sxy = 0; for (int i = 0; i < N; i++) { sx += i; sy += x[i]; sxx += (double) i * i; sxy += i * x[i]; }
        double den = N * sxx - sx * sx, sl = den != 0 ? (N * sxy - sx * sy) / den : 0.0, ic = (sy - sl * sx) / N;
        for (int i = 0; i < N; i++) x[i] = (x[i] - (ic + sl * i)) * (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (N - 1)));
        int nf = (int) Math.round((SPEC_HI - SPEC_LO) / F_STEP) + 1; double[] pw = new double[nf];
        for (int k = 0; k < nf; k++) {
            double f = SPEC_LO + k * F_STEP, w = 2 * Math.PI * f / GRID_HZ, re = 0, im = 0;
            for (int i = 0; i < N; i++) { re += x[i] * Math.cos(w * i); im -= x[i] * Math.sin(w * i); }
            pw[k] = re * re + im * im;
        }
        int k0 = (int) Math.round((F_LO - SPEC_LO) / F_STEP), k1 = (int) Math.round((F_HI - SPEC_LO) / F_STEP), pk = k0; for (int k = k0 + 1; k <= k1; k++) if (pw[k] > pw[pk]) pk = k;
        if (pk <= k0 + 1 || pk >= k1 - 1) return null;                                  // a peak at the band edge is a trend, not breathing
        int ka = (int) Math.round(NEAR / F_STEP), kb = (int) Math.round(FAR / F_STEP); double[] fl = new double[2 * (kb - ka + 1)]; int nfl = 0;
        for (int q = ka; q <= kb; q++) { if (pk - q >= 0) fl[nfl++] = pw[pk - q]; if (pk + q < nf) fl[nfl++] = pw[pk + q]; }
        double md = median(fl, nfl); if (!(md > 0) || nfl < 20) return null;           // the floor beside the peak (0.04 to 0.12 Hz either side), not the whole band
        double prom = pw[pk] / md; if (prom < MIN_PROM) return null;
        double l = Math.log(Math.max(pw[pk - 1], 1e-300)), c = Math.log(pw[pk]), r = Math.log(Math.max(pw[pk + 1], 1e-300)), dd = l - 2 * c + r;
        double off = dd != 0 ? 0.5 * (l - r) / dd : 0.0; off = Math.max(-1.0, Math.min(1.0, off));
        Est e = new Est(); e.perMin = 60.0 * (SPEC_LO + (pk + off) * F_STEP); e.prom = prom; e.beats = nv; e.spanS = tv[nv - 1] - tv[0]; return e;
    }

    /** What the header shows: the estimate once two estimates a second apart agree within 2 a minute, then smoothed; "--" (NaN) after 8 s without a clear peak. Call about once a second. */
    static final class Tracker {
        private double shown = Double.NaN, prev = Double.NaN; private int miss, jump;
        void reset() { shown = Double.NaN; prev = Double.NaN; miss = 0; jump = 0; }
        double shown() { return shown; }
        double update(double[] beatSec, int n) {
            Est e = estimate(beatSec, n);
            if (e == null) { prev = Double.NaN; if (++miss >= 8) { shown = Double.NaN; jump = 0; } return shown; }
            miss = 0;
            if (Double.isNaN(shown)) { if (!Double.isNaN(prev) && Math.abs(e.perMin - prev) <= 2.0) shown = 0.5 * (e.perMin + prev); prev = e.perMin; return shown; }
            if (Math.abs(e.perMin - shown) > 5.0) { if (++jump >= 5) { shown = e.perMin; jump = 0; } return shown; }
            jump = 0; shown = 0.7 * shown + 0.3 * e.perMin; prev = e.perMin; return shown;
        }
    }
}
