package com.noop.mgecg;

// FILE VERSION 0.41.0 (5 Nov 2026): live noise by band, measured between the beats (each beat masked -100..+400 ms), from the front-end output of Live500.
// Bands (Hz): 0.5-5 (drift), 8-25 (the strap's QRS band), 25-48, 48-52 (mains), 52-100. Causal second-order band-passes designed with scipy at 500 Hz.
// Decode log 115 / 116: the 8-25 Hz noise relative to the R wave separated the noisy result 6 readings. Pure Java (no Android), tested off the phone.

final class LiveBands {
    private LiveBands() {}

    static final int NBANDS = 5;
    static final String[] NAMES = { "0.5-5 Hz", "8-25 Hz", "25-48 Hz", "50 Hz mains", "52-100 Hz" };
    private static final double[][][] SOS = {
        {{0.0007685116546701622, 0.0015370233093403244, 0.0007685116546701622, 1.0, -1.927769300441802, 0.9309891328156048}, {1.0, -2.0, 1.0, 1.0, -1.9915249934528598, 0.9915715224109389}},
        {{0.009884183769069532, 0.019768367538139063, 0.009884183769069532, 1.0, -1.738070250524083, 0.8074498208819249}, {1.0, -2.0, 1.0, 1.0, -1.9031643937077067, 0.9155541480081073}},
        {{0.017260450615417294, 0.03452090123083459, 0.017260450615417294, 1.0, -1.5129050720902244, 0.7791227151250903}, {1.0, -2.0, 1.0, 1.0, -1.7449179958672982, 0.8529231888814803}},
        {{0.0006098547187172977, 0.0012197094374345953, 0.0006098547187172977, 1.0, -1.5685816307007312, 0.9642413838690725}, {1.0, -2.0, 1.0, 1.0, -1.6110035772714708, 0.965921705610354}},
        {{0.06297370587483148, 0.12594741174966295, 0.06297370587483148, 1.0, -0.6355673718202239, 0.6078225801601362}, {1.0, -2.0, 1.0, 1.0, -1.285231042666616, 0.7032146937213777}}
    };
    private static final int RING = 8192;                 // 16 s of squared band outputs
    private static final int PRE = 50, POST = 200, MINGAP = 60, KEEP = 24;

    private static final Object LOCK = new Object();
    private static final double[][][] st = new double[NBANDS][2][2];      // [band][section][z1, z2]
    private static final float[][] sq = new float[NBANDS][RING];
    private static final boolean[] okRing = new boolean[RING];
    private static long n;                                  // samples seen (same index as Live500's absolute index)
    private static long lastApex = Long.MIN_VALUE;
    private static final double[][] gaps = new double[KEEP][NBANDS];    // mean squares per kept gap
    private static int gapCount;
    private static double sumZ; private static int nZ;

    static void reset() {
        synchronized (LOCK) {
            for (double[][] b : st) for (double[] s : b) { s[0] = 0; s[1] = 0; }
            java.util.Arrays.fill(okRing, false); n = 0; lastApex = Long.MIN_VALUE; gapCount = 0;
        }
    }

    /** One front-end output sample (call in step with Live500.append, index = its absolute index). valid = false for a missing / railed sample. */
    static void sample(long gi, double y, boolean valid) {
        synchronized (LOCK) {
            int p = (int) (gi & (RING - 1));
            double x = valid ? y : 0.0;
            for (int b = 0; b < NBANDS; b++) {
                double v = x;
                for (int s = 0; s < 2; s++) {
                    double[] c = SOS[b][s]; double[] z = st[b][s];
                    double out = c[0] * v + z[0];
                    z[0] = c[1] * v - c[4] * out + z[1];
                    z[1] = c[2] * v - c[5] * out;
                    v = out;
                }
                sq[b][p] = (float) (v * v);
            }
            okRing[p] = valid;
            n = gi + 1;
        }
    }

    /** A beat was found: detectorPeak is the absolute index of the detector peak (about 18 samples after the R apex). */
    static void beat(long detectorPeak) {
        synchronized (LOCK) {
            long apex = detectorPeak - 18;
            if (lastApex != Long.MIN_VALUE) {
                long a = lastApex + POST, c = apex - PRE;      // the quiet stretch between the two beats
                if (c - a >= MINGAP && n - a < RING - 64) {
                    double[] acc = new double[NBANDS]; int cnt = 0;
                    for (long g = a; g < c; g++) { int p = (int) (g & (RING - 1)); if (!okRing[p]) { cnt = -1; break; } for (int b = 0; b < NBANDS; b++) acc[b] += sq[b][p]; cnt++; }
                    if (cnt >= MINGAP) { double[] dst = gaps[gapCount % KEEP]; for (int b = 0; b < NBANDS; b++) dst[b] = acc[b] / cnt; gapCount++; }
                }
            }
            lastApex = apex;
        }
    }

    /** Median over the last gaps of the rms (uV) per band; NaN until 3 gaps exist. */
    static double[] rms() {
        synchronized (LOCK) {
            double[] r = new double[NBANDS]; int m = Math.min(gapCount, KEEP);
            if (m < 3) { java.util.Arrays.fill(r, Double.NaN); return r; }
            for (int b = 0; b < NBANDS; b++) { double[] v = new double[m]; for (int i = 0; i < m; i++) v[i] = Math.sqrt(gaps[i][b]); java.util.Arrays.sort(v); r[b] = (m % 2 == 1) ? v[m / 2] : 0.5 * (v[m / 2 - 1] + v[m / 2]); }
            return r;
        }
    }

    static int gaps() { synchronized (LOCK) { return gapCount; } }

    /** Plain-language hint from the bands (uV), given the QRS height in uV; null when nothing stands out. */
    static String hint(double[] b, double rUv) {
        if (b == null || Double.isNaN(b[1])) return null;
        if (b[3] > 10 && b[3] > 0.45 * b[1]) return "50 Hz hum: move away from chargers and mains cables";
        if (b[0] > 70 && b[0] > 1.8 * b[1]) return "Slow drift: keep the forearm still and supported";
        if (b[1] > 27) return "Broadband noise (muscle or vibration): relax the hand, rest the forearm, keep still";
        return null;
    }
}
