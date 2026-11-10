package com.noop.mgecg;

// FILE VERSION 0.52.0 (10 Nov 2026): the vertical scale of the live 500 Hz graph (items 1 and 2 of the 0.52.0 graph build). Pure Java (no Android), tested off the phone.
// Held data (decode log 147): the R wave has a median of 954 uV (5-95 %: 590-1,761) and 640 uV before 09:00 UTC; with the old fixed floors (top 0.8 mV, bottom -0.4 mV) it filled 49-68 % of the plot height.
// The baseline moves in steps of 445 uV (95th percentile between seconds) in readings with arm movement, which made the old auto scale hunt.
//   FIT TO BEAT: the floors follow the live QRS height (top 1.10 x R, at least 0.40 mV; bottom -0.22 x R, at least -0.15 mV; headroom 1.12 x the window maximum, 1.20 x its minimum), so the R wave fills about 70 % of the height
//   (synthetic: R 950 uV 55 % -> 70 %, R 640 uV 43 % -> 70 %; the old floors were top 0.8 mV, bottom -0.4 mV, headroom 1.25 / 1.4).
//   HOLD: when the baseline moves by more than max(0.25 mV, 0.35 x R) between the seconds of the window, the scale may only OPEN for 4 s (a target never closes), so a slipping hand does not make the picture breathe.
// Display only: the stored data, the reports and the analysis never see this scale.

final class LiveScale {
    float yMin = -0.5f, yMax = 1.0f, tgtMin = -0.5f, tgtMax = 1.0f;     // millivolts; y* is what is drawn, tgt* where it is heading
    long holdUntilMs;
    private long lastEaseMs;

    static final float TOP_MIN = 0.40f, BOT_MIN = 0.15f, TOP_R = 1.10f, BOT_R = 0.22f, HEAD_HI = 1.12f, HEAD_LO = 1.20f, DEAD_BAND = 0.12f;
    static final float UNREST_MIN_MV = 0.25f, UNREST_R = 0.35f; static final long HOLD_MS = 4000L;

    void reset() { yMin = tgtMin = -0.5f; yMax = tgtMax = 1.0f; holdUntilMs = 0; lastEaseMs = 0; }

    boolean holding(long nowMs) { return nowMs < holdUntilMs; }

    /** Baseline movement of the window: range of the 1 s medians (mV) over seconds with at least 200 valid samples; 0 when fewer than 3 seconds qualify. v in microvolts (NaN = no sample), t in ms. */
    static float unrestMv(float[] v, double[] t, int m, float[] tmp) {
        if (m < 750) return 0f;
        double t0 = t[0]; float lo = Float.POSITIVE_INFINITY, hi = Float.NEGATIVE_INFINITY; int nb = 0, i = 0;
        while (i < m) {
            long sec = (long) ((t[i] - t0) / 1000.0); int k = 0, j = i;
            while (j < m && (long) ((t[j] - t0) / 1000.0) == sec) { if (!Float.isNaN(v[j]) && k < tmp.length) tmp[k++] = v[j]; j++; }
            if (k >= 200) { java.util.Arrays.sort(tmp, 0, k); float md = tmp[k / 2]; if (md < lo) lo = md; if (md > hi) hi = md; nb++; }
            i = j;
        }
        return nb < 3 ? 0f : (hi - lo) / 1000f;
    }

    /** hiMv / loMv: the 99.7th / 0.3rd percentile of the window; qrsMv: the robust QRS height (0 or NaN = unknown); unrest: unrestMv(). Called about every 300 ms. */
    void update(long nowMs, float hiMv, float loMv, float qrsMv, float unrest) {
        boolean haveR = qrsMv > 0.05f && !Float.isNaN(qrsMv);
        float floorTop = haveR ? Math.max(TOP_MIN, TOP_R * qrsMv) : 0.8f, floorBot = haveR ? -Math.max(BOT_MIN, BOT_R * qrsMv) : -0.4f;
        float wantMax = Math.max(floorTop, hiMv * HEAD_HI), wantMin = Math.min(floorBot, loMv * HEAD_LO);
        float thr = Math.max(UNREST_MIN_MV, haveR ? UNREST_R * qrsMv : UNREST_MIN_MV);
        if (unrest > thr) holdUntilMs = nowMs + HOLD_MS;
        boolean hold = nowMs < holdUntilMs; float span0 = Math.max(0.5f, tgtMax - tgtMin);
        if (wantMax > tgtMax || (!hold && wantMax < tgtMax - DEAD_BAND * span0)) tgtMax = wantMax;
        if (wantMin < tgtMin || (!hold && wantMin > tgtMin + DEAD_BAND * span0)) tgtMin = wantMin;
    }

    /** Moves the drawn scale toward the targets: opens within about 0.12 s, closes over about 1.6 s. */
    void ease(long nowMs) {
        float dt = lastEaseMs == 0 ? 0.016f : Math.max(0.001f, Math.min(0.1f, (nowMs - lastEaseMs) / 1000f)); lastEaseMs = nowMs;
        float kOpen = 1f - (float) Math.exp(-dt / 0.12f), kClose = 1f - (float) Math.exp(-dt / 1.6f);
        yMax += (tgtMax - yMax) * (tgtMax > yMax ? kOpen : kClose);
        yMin += (tgtMin - yMin) * (tgtMin < yMin ? kOpen : kClose);
    }
}
