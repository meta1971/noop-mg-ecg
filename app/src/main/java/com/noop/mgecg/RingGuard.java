package com.noop.mgecg;

// FILE VERSION 0.57.0 (15 Nov 2026): phone-buzz guard. A ringing or buzzing phone near the strap shows up in the strap's accelerometer as a narrow line (reading 1794765254: 11 bursts of the ring at 10 to 12 Hz after aliasing in the
// 100 Hz stream, 2 to 10 mg, against a broadband hand movement of 100 mg or more) and, in that reading, went with extra random-sign baseline steps in the ECG. The motion check LiveMotion uses (0.027 g) cannot see 10 mg.
// Rule (per R21 frame = 1 s, 100 samples x 3 axes): remove mean and trend per axis, power per whole-hertz bin 5 to 49 Hz summed over the axes; baseline per bin = median of the last 60 unflagged frames (first 8 frames are the warm-up);
// a frame is VIBRATION when the best 3-bin ratio to baseline is at least 8, the excess is at least 2 mg RMS, and at most 6 bins are above 4 x baseline (a hand movement lifts every bin; a ring lifts a few).
// Not tied to 11 Hz: any narrow line counts. Held data (this one reading): 17 of 34 seconds flagged inside the ring part (121 to 152 s), 0 of 55 in 60 to 115 s, 1 of 60 in 0 to 60 s, 1 of 9 in the last 9 s.
// A single flagged second is weak evidence (other held readings flag 2 to 3 % of their seconds as isolated hits at 6 or 14 to 17 Hz, never more than 2 in a row); a ring is a run. So the BANNER and the EPISODES need at least 3 flagged
// seconds within 8 s (episodes: gaps of 3 s or less, 3 or more flagged seconds). Detection only: nothing here edits the ECG. Pure Java (no Android).

final class RingGuard {
    private RingGuard() {}

    static final double RATIO = 8.0, MIN_EXCESS_MG = 2.0, BROAD_RATIO = 4.0;
    static final int B0 = 5, B1 = 49, NB = B1 - B0 + 1, HIST = 60, WARM = 8, MAX_BROAD = 6, MAXT = 600;
    static final long ACTIVE_MS = 2600L;
    static final String RULE = "bins5-49Hz_ratio8_excess2mg_broad6_hist60_v0.57.0";

    private static final double[][] CO = new double[NB][100], SI = new double[NB][100];
    static {
        for (int k = 0; k < NB; k++) for (int n = 0; n < 100; n++) { double a = 2.0 * Math.PI * (B0 + k) * n / 100.0; CO[k][n] = Math.cos(a); SI[k][n] = Math.sin(a); }
    }

    private static final Object L = new Object();
    private static final double[][] hist = new double[HIST][NB];
    private static int histN, histHead, frames, flagged, longestRun, run;
    private static long startMs, lastVibMs;
    private static final double[] times = new double[MAXT];
    private static int timesN;
    private static double lastRatio, lastExcessMg; private static int lastBinHz;
    private static final long[] r3 = new long[3]; private static int r3n;      // the last three flagged frames (ms)

    /** Called at the start of a reading. */
    static void reset(long nowMs) {
        synchronized (L) { histN = 0; histHead = 0; frames = 0; flagged = 0; longestRun = 0; run = 0; startMs = nowMs; lastVibMs = 0; timesN = 0; r3n = 0; lastRatio = 0; lastExcessMg = 0; lastBinHz = 0; }
    }

    private static int i16(byte[] f, int o) { return (short) ((f[o] & 0xFF) | ((f[o + 1] & 0xFF) << 8)); }

    /** One R21 frame (1,244 bytes, layout as LiveMotion). Returns true when this second is flagged as vibration. */
    static boolean feed(byte[] f, long nowMs) {
        if (f == null || f.length != 1244) return false;
        double[][] a = new double[3][100];
        for (int k = 0; k < 100; k++) { a[0][k] = i16(f, 28 + 2 * k) / 4096.0; a[1][k] = i16(f, 228 + 2 * k) / 4096.0; a[2][k] = i16(f, 428 + 2 * k) / 4096.0; }
        return feedAxes(a, nowMs);
    }

    /** a[axis][100] in g. The detector itself (tested off the phone against the Python prototype). */
    static boolean feedAxes(double[][] a, long nowMs) {
        double[] p = new double[NB];
        for (int c = 0; c < 3; c++) {
            double mean = 0; for (int n = 0; n < 100; n++) mean += a[c][n]; mean /= 100.0;
            double sxk = 0, skk = 0; for (int n = 0; n < 100; n++) { double k = n - 49.5, x = a[c][n] - mean; sxk += k * x; skk += k * k; }
            double b = sxk / skk; double[] x = new double[100];
            for (int n = 0; n < 100; n++) x[n] = a[c][n] - mean - b * (n - 49.5);
            for (int k = 0; k < NB; k++) {
                double re = 0, im = 0; for (int n = 0; n < 100; n++) { re += x[n] * CO[k][n]; im += x[n] * SI[k][n]; }
                p[k] += (re * re + im * im) / 10000.0 * 1e6;      // mg^2 (g^2 x 1e6)
            }
        }
        synchronized (L) {
            frames++;
            if (histN < WARM) { push(p); return false; }
            double[] base = new double[NB]; double[] col = new double[Math.min(histN, HIST)];
            for (int k = 0; k < NB; k++) { for (int i = 0; i < col.length; i++) col[i] = hist[i][k]; java.util.Arrays.sort(col); int m = col.length; base[k] = (m % 2 == 1 ? col[m / 2] : 0.5 * (col[m / 2 - 1] + col[m / 2])) + 1e-9; }
            double[] r = new double[NB]; int broad = 0;
            for (int k = 0; k < NB; k++) { r[k] = p[k] / base[k]; if (r[k] > BROAD_RATIO) broad++; }
            double best = -1; int j = 0;
            for (int k = 0; k < NB; k++) {
                double s = r[k] + (k > 0 ? r[k - 1] : 0) + (k < NB - 1 ? r[k + 1] : 0); s /= 3.0;      // zero-padded edges, as numpy 'same'
                if (s > best) { best = s; j = k; }
            }
            double ps = 0, bs = 0; for (int k = Math.max(0, j - 1); k <= Math.min(NB - 1, j + 1); k++) { ps += p[k]; bs += base[k]; }
            double exc = Math.sqrt(Math.max(0.0, 2.0 * (ps - bs)));
            boolean v = best >= RATIO && exc >= MIN_EXCESS_MG && broad <= MAX_BROAD;
            lastRatio = best; lastExcessMg = exc; lastBinHz = B0 + j;
            if (v) {
                flagged++; run++; if (run > longestRun) longestRun = run; lastVibMs = nowMs;
                if (r3n < 3) r3[r3n++] = nowMs; else { r3[0] = r3[1]; r3[1] = r3[2]; r3[2] = nowMs; }
                if (timesN < MAXT) times[timesN++] = Math.max(0.0, (nowMs - startMs) / 1000.0);
            } else { run = 0; push(p); }
            return v;
        }
    }

    private static void push(double[] p) { System.arraycopy(p, 0, hist[histHead], 0, NB); histHead = (histHead + 1) % HIST; if (histN < HIST) histN++; }

    /** The banner: the last flagged second is less than 2.6 s old and it is the third flagged second within 8 s. */
    static boolean active(long nowMs) { synchronized (L) { return r3n == 3 && nowMs - lastVibMs < ACTIVE_MS && r3[2] - r3[0] <= 8000L; } }
    static int frames() { synchronized (L) { return frames; } }
    static int flaggedSeconds() { synchronized (L) { return flagged; } }
    static int longestRunSeconds() { synchronized (L) { return longestRun; } }
    static int lineHz() { synchronized (L) { return lastBinHz; } }

    /** Seconds from the reading start (frame arrival, about 1 s after its samples) of each flagged frame, ';' between, at most 600. */
    static String timesCsv() {
        synchronized (L) { StringBuilder sb = new StringBuilder(); for (int i = 0; i < timesN; i++) { if (i > 0) sb.append(';'); sb.append(String.format(java.util.Locale.US, "%.0f", times[i])); } return sb.toString(); }
    }

    /** Episodes ('start-end' seconds from the reading start, ';' between): runs of 3 or more flagged seconds with gaps of 3 s or less. */
    static String episodesCsv() {
        synchronized (L) {
            StringBuilder sb = new StringBuilder(); int i = 0;
            while (i < timesN) {
                int j = i; while (j + 1 < timesN && times[j + 1] - times[j] <= 3.0) j++;
                if (j - i + 1 >= 3) { if (sb.length() > 0) sb.append(';'); sb.append(String.format(java.util.Locale.US, "%.0f-%.0f", times[i], times[j])); }
                i = j + 1;
            }
            return sb.toString();
        }
    }
    static int episodes() { String e = episodesCsv(); if (e.isEmpty()) return 0; int n = 1; for (int i = 0; i < e.length(); i++) if (e.charAt(i) == ';') n++; return n; }
}
