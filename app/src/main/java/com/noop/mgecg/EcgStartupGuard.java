package com.noop.mgecg;

// FILE VERSION 0.7.0 (20 Oct): start-up guard for the live ECG graph

/**
 * The strap's live ECG stream (R17) opens every reading with a start-up artefact: a first frame of 49 samples that swings
 * about -4.6 to +5.1 mV, then a frame of 100 zeros, then a decaying settle of up to about 1.3 mV (seen in all 11 readings of 20 Oct).
 * Drawn as it is, the trace shoots off the top of the screen and the auto-scale window is stretched by the spike for the
 * next six seconds. For the DISPLAY only (stored data and the analysis are untouched) the first 150 samples are drawn as a flat
 * line and the next 300 are limited to +-1.5 mV.
 */
final class EcgStartupGuard {
    private EcgStartupGuard() {}

    static final int BLANK_SAMPLES = 150;        // 49 spike samples + 100 zero samples, with a little margin
    static final int CLAMP_UNTIL = 450;          // settle period: 4.5 s from the start
    static final int CLAMP_UV = 1500;

    /** index = 0-based number of this sample since the reading started. */
    static int filter(long index, int value) {
        if (index < BLANK_SAMPLES) return 0;
        if (index < CLAMP_UNTIL) return Math.max(-CLAMP_UV, Math.min(CLAMP_UV, value));
        return value;
    }
}
