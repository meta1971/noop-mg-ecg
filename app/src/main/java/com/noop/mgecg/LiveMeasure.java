package com.noop.mgecg;

// FILE VERSION 0.41.0 (5 Nov 2026): the averaged beat and its measurements while the reading runs (QRS duration, S / ST dip, R, ST, QT), from the beat windows kept by Live500.
// Uses the same EcgSegments.measure as the report (QRS duration median error 8 ms on the PhysioNet QT Database). Research only. Pure Java.

import java.util.ArrayList;
import java.util.List;

final class LiveMeasure {
    private LiveMeasure() {}

    static final double GATE = EcgIntervals.SHAPE_GATE;        // the report's shape gate
    static final int PRE = Live500.PRE_W, LEN = Live500.PRE_W + Live500.POST_W;

    static final class Out {
        int offered, used;                       // beat windows offered, beats that matched the typical shape
        double[] avg;                            // averaged beat, uV, baseline = PR level, R up (LEN samples, R at PRE)
        double rUv = Double.NaN, qrsMs = Double.NaN, qrsHwMs = Double.NaN, qrsOnMs = Double.NaN, qrsOffMs = Double.NaN;
        double sDipUv = Double.NaN, sDipMs = Double.NaN;        // lowest point 14-28 ms after the R apex against the PR level (decode log 109.1)
        double postDipUv = Double.NaN, postDipMs = Double.NaN;  // lowest point 20-120 ms after R (the broad S / ST dip)
        double expectedSDipUv = Double.NaN;      // 0.176 x R - 194 (decode log 109.1, this wrist and strap, 44 readings)
        double st60Uv = Double.NaN, qtMs = Double.NaN, tAmpUv = Double.NaN, tPeakMs = Double.NaN, hr = Double.NaN, noiseUv = Double.NaN;
        boolean qrsOk, qtOk, valid;
        String note = "";
    }

    /** The window moved by sh samples (a positive sh takes later samples), the ends repeat the edge value. */
    private static double[] shift(double[] w, int sh) {
        if (sh == 0) return w; double[] o = new double[w.length];
        for (int j = 0; j < w.length; j++) { int k = j + sh; o[j] = w[Math.max(0, Math.min(w.length - 1, k))]; } return o;
    }

    private static double median(double[] a) { double[] s = a.clone(); java.util.Arrays.sort(s); int n = s.length; return n == 0 ? Double.NaN : (n % 2 == 1 ? s[n / 2] : 0.5 * (s[n / 2 - 1] + s[n / 2])); }

    private static double corr(double[] a, double[] b, int from, int to) {
        double ma = 0, mb = 0; int n = to - from; for (int i = from; i < to; i++) { ma += a[i]; mb += b[i]; } ma /= n; mb /= n;
        double sab = 0, saa = 0, sbb = 0; for (int i = from; i < to; i++) { double x = a[i] - ma, y = b[i] - mb; sab += x * y; saa += x * x; sbb += y * y; }
        return (saa > 0 && sbb > 0) ? sab / Math.sqrt(saa * sbb) : 0.0;
    }

    /** Pure: windows as kept by Live500 (R up, baseline at the PR level), median R-R in seconds. */
    static Out compute(List<double[]> wins, double rrS) {
        Out o = new Out();
        o.offered = wins == null ? 0 : wins.size();
        if (o.offered < 6) { o.note = "collecting beats"; return o; }
        double[] tm = new double[LEN], col = new double[o.offered];
        for (int j = 0; j < LEN; j++) { for (int g = 0; g < col.length; g++) col[g] = wins.get(g)[j]; tm[j] = median(col); }
        List<double[]> kept = new ArrayList<>();
        for (int pass = 0; pass < 2; pass++) {                       // align every beat to the template within +-16 ms (two passes), as the averaged beat of decode log 50.4
            List<double[]> al = new ArrayList<>();
            for (double[] w : wins) {
                int bs = 0; double bc = -2;
                for (int sh = -8; sh <= 8; sh++) { double[] x = shift(w, sh); double cc = corr(x, tm, PRE - 30, PRE + 40); if (cc > bc) { bc = cc; bs = sh; } }
                al.add(shift(w, bs));
            }
            for (int j = 0; j < LEN; j++) { for (int g = 0; g < col.length; g++) col[g] = al.get(g)[j]; tm[j] = median(col); }
            if (pass == 1) { for (double[] w : al) { if (corr(w, tm, PRE - 50, PRE + 225) >= GATE) kept.add(w); } }
            else { wins = al; }
        }
        o.used = kept.size();
        if (kept.size() < 6) { o.note = "few beats matched the typical shape"; return o; }
        double[] avg = new double[LEN]; for (double[] w : kept) for (int j = 0; j < LEN; j++) avg[j] += w[j] / kept.size();
        o.avg = avg;
        double[] a1 = new double[LEN], a2 = new double[LEN]; int n1 = 0, n2 = 0;
        for (int g = 0; g < kept.size(); g++) { double[] w = kept.get(g); if (g % 2 == 0) { for (int j = 0; j < LEN; j++) a1[j] += w[j]; n1++; } else { for (int j = 0; j < LEN; j++) a2[j] += w[j]; n2++; } }
        double[] diff = new double[LEN]; for (int j = 0; j < LEN; j++) diff[j] = a1[j] / Math.max(1, n1) - a2[j] / Math.max(1, n2);
        double m = 0; for (double d : diff) m += d; m /= LEN; double v = 0; for (double d : diff) v += (d - m) * (d - m);
        o.noiseUv = Math.sqrt(v / (LEN - 1)) / 2.0;
        int ri = PRE; for (int i = PRE - 12; i <= PRE + 12; i++) if (avg[i] > avg[ri]) ri = i;
        o.rUv = avg[ri];
        int a = ri + 7, b = ri + 14;                                  // +14 to +28 ms at 2 ms per sample
        double mn = 1e18; int mi = a; for (int i = a; i <= b; i++) if (avg[i] < mn) { mn = avg[i]; mi = i; }
        o.sDipUv = mn; o.sDipMs = (mi - ri) * 2.0;
        mn = 1e18; mi = ri + 10; for (int i = ri + 10; i <= ri + 60 && i < LEN; i++) if (avg[i] < mn) { mn = avg[i]; mi = i; }
        o.postDipUv = mn; o.postDipMs = (mi - ri) * 2.0;
        o.expectedSDipUv = 0.176 * o.rUv - 194.0;
        if (rrS > 0.3) {
            o.hr = 60.0 / rrS;
            try {
                EcgSegments.Result r = EcgSegments.measure(kept, rrS, o.noiseUv, PRE);
                if (r != null && r.valid) {
                    o.valid = true; o.qrsMs = r.qrsMs; o.qrsHwMs = r.qrsHwMs; o.qrsOnMs = r.qrsOnMs; o.qrsOffMs = r.qrsOffMs; o.qrsOk = r.qrsOk;
                    o.st60Uv = r.st60Uv; o.qtMs = r.qtMs; o.qtOk = r.qtOk; o.tAmpUv = r.tAmpUv; o.tPeakMs = r.tPeakMs;
                    if (!Double.isNaN(r.rUv)) o.rUv = r.rUv;
                } else if (r != null) o.note = r.note;
            } catch (RuntimeException e) { o.note = "measure failed"; }
        }
        return o;
    }

    // ---- background runner (the UI asks; the work runs off the main thread, at most one at a time) ----
    private static volatile Out last;
    private static volatile boolean busy;
    private static volatile int lastBeats = -1;
    private static volatile long lastReqMs;
    private static final java.util.concurrent.ExecutorService EXEC = java.util.concurrent.Executors.newSingleThreadExecutor();

    static Out last() { return last; }
    static void clear() { last = null; lastBeats = -1; }

    /** Starts a measurement when new beats arrived since the last one (call about once a second from the UI thread). */
    static void request() {
        if (busy || System.currentTimeMillis() - lastReqMs < 3000L) return;
        final int bc = Live500.beatCount();
        if (bc == lastBeats || bc < 8) return;
        busy = true; lastReqMs = System.currentTimeMillis();
        EXEC.execute(new Runnable() { @Override public void run() {
            try { Out o = compute(Live500.windowsCopy(), Live500.medianRrS()); last = o; lastBeats = bc; } catch (Throwable ignored) { } finally { busy = false; }
        } });
    }
}
