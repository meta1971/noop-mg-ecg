package com.noop.mgecg;

// FILE VERSION 0.52.0 (10 Nov 2026): picks the live trace's between-beat smoothing from the live noise (item 3 of the 0.52.0 graph build). Pure Java, tested off the phone.
// Input: the live 8-25 Hz noise between the beats (uV rms, LiveBands.rms()[1], measured BEFORE the smoothing, so there is no feedback). Held data (decode log 147): 70 % of free readings are at or below 27 uV,
// 20 % above 40 uV; the guided finger tests are above 40 uV in 55 %. Tiers: 0 quiet (slow Gaussian sigma 8 ms: crisper), 1 fair (12 ms: the old default), 2 noisy (20 ms: the clean view).
// The noise is averaged over about 4 s, with hysteresis (up above 30 / 44, down below 24 / 36) and a 5 s dwell, so the texture of the trace does not change back and forth. NaN keeps the tier.

final class NoiseTier {
    static final double[] SIGMA_MS = { 8.0, 12.0, 20.0 };
    static final double UP0 = 30.0, UP1 = 44.0, DOWN1 = 24.0, DOWN2 = 36.0, TAU_S = 4.0; static final long DWELL_MS = 5000L;
    private int tier = 1; private long lastChangeMs = Long.MIN_VALUE / 2, lastMs; private double ema = Double.NaN;      // the noise is averaged over about 4 s before the tiers look at it

    void reset() { tier = 1; lastChangeMs = Long.MIN_VALUE / 2; ema = Double.NaN; lastMs = 0; }
    int tier() { return tier; }
    double sigmaMs() { return SIGMA_MS[tier]; }

    /** Returns the tier after looking at this noise value at time nowMs (ms). */
    int update(double noiseUv, long nowMs) {
        if (Double.isNaN(noiseUv)) return tier;
        if (Double.isNaN(ema)) ema = noiseUv; else { double dt = Math.max(0.0, Math.min(30.0, (nowMs - lastMs) / 1000.0)); ema += (noiseUv - ema) * (1.0 - Math.exp(-dt / TAU_S)); }
        lastMs = nowMs;
        if (nowMs - lastChangeMs < DWELL_MS) return tier;
        int t = tier;
        if (t == 0 && ema > UP0) t = 1;
        else if (t == 1 && ema > UP1) t = 2;
        else if (t == 1 && ema < DOWN1) t = 0;
        else if (t == 2 && ema < DOWN2) t = 1;
        if (t != tier) { tier = t; lastChangeMs = nowMs; }
        return tier;
    }
}
