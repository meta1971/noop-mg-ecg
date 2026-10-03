package com.noop.mgecg;

// FILE VERSION 0.2.2 (3 Oct): live graph auto-scale

import java.util.Arrays;

/**
 * Chooses the vertical window of the live ECG graph. WHOOP's fixed window (-1 to +2 mV) suits the 1 mV R waves of a
 * good contact, but at 0.45 mV the peaks fill only 15% of the box. This keeps the grid calibrated (small box 0.1 mV,
 * large box 0.5 mV) and moves only the window, so the label always tells the true scale.
 *
 * Pure Java, no Android. update() is cheap enough to call every 25 samples (0.25 s at 100 Hz).
 */
public final class EcgAutoScale {

    public static final float DEFAULT_MIN = -1000f, DEFAULT_MAX = 2000f;   // WHOOP's own window, uV
    public static final float MIN_RANGE = 800f, MAX_RANGE = 3000f;
    static final int MIN_SAMPLES = 200;                                   // 2 s at 100 Hz before the window moves
    static final float ABOVE = 1.45f, BELOW = 0.55f;                      // window = peak x (1.45 above, 0.55 below baseline)

    public float min = DEFAULT_MIN, max = DEFAULT_MAX;

    public void reset() {
        min = DEFAULT_MIN;
        max = DEFAULT_MAX;
    }

    /** vals[0..n) are the samples currently on screen (any order), in microvolts. */
    public void update(float[] vals, int n) {
        if (n < MIN_SAMPLES) return;
        float[] s = Arrays.copyOf(vals, n);
        Arrays.sort(s);
        float med = s[n / 2];
        // robust peak: the 99th percentile away from the baseline (a 6 s window holds about 7 R waves, so p99 lands on them
        // while a single spike of movement does not)
        float pPos = s[Math.min(n - 1, (int) (0.99 * (n - 1)))] - med;
        float pNeg = med - s[Math.max(0, (int) (0.01 * (n - 1)))];
        boolean inverted = pNeg > 1.3f * pPos;
        float peak = Math.max(pPos, pNeg);
        if (peak < 1f) return;

        float top, bottom;
        if (inverted) {
            top = med + BELOW * peak;
            bottom = med - ABOVE * peak;
        } else {
            top = med + ABOVE * peak;
            bottom = med - BELOW * peak;
        }
        top = (float) Math.ceil(top / 100f) * 100f;
        bottom = (float) Math.floor(bottom / 100f) * 100f;
        float range = top - bottom;
        if (range < MIN_RANGE) {                       // keep noise from being blown up: widen around the middle
            float mid = (top + bottom) / 2f;
            top = (float) Math.ceil((mid + MIN_RANGE / 2f) / 100f) * 100f;
            bottom = top - MIN_RANGE;
        } else if (range > MAX_RANGE) {
            top = bottom + MAX_RANGE;
        }

        // grow fast so a peak never clips for long, shrink slowly so the picture does not breathe
        float kTop = top > max ? 0.6f : 0.08f;
        float kBottom = bottom < min ? 0.6f : 0.08f;
        max += (top - max) * kTop;
        min += (bottom - min) * kBottom;
    }
}
