package com.noop.mgecg;

// FILE VERSION 0.54.0 (13 Nov 2026): NEW. "Enough" stop rule for a free ECG reading: it ends when it has the data, not at a fixed time.
// It stops when ALL hold: 60 s of the strap's quality-3 seconds are banked, at least 64 clean beats are in, the strap has given its verdict (progress 100 with a result code), and either the strap's HRV
// field has arrived or WAIT_HRV_S seconds have passed since the verdict. Across 128 completed readings the strap's HRV was valid within 10 s of the progress-100 record in 57 %, within 20 s in 69 %,
// within 60 s in 84 %, and never in 5. A hard cap stays (HARD_CAP_MS). Pure Java.

final class ReadingEnd {
    private ReadingEnd() {}

    static final int Q3_SEC = 60, CLEAN_BEATS = 64;
    static final long WAIT_HRV_S = 30L, MIN_MS = 45000L, HARD_CAP_MS = 300000L;

    /** 0 = keep going; 1 = stop, enough data; 2 = stop, time cap (the caller says so). */
    static int decide(long elapsedMs, int q3Sec, int cleanBeats, long verdictMs, long hrvMs, long nowMs) {
        if (elapsedMs >= HARD_CAP_MS) return 2;
        if (elapsedMs < MIN_MS) return 0;
        if (q3Sec < Q3_SEC || cleanBeats < CLEAN_BEATS) return 0;
        if (verdictMs <= 0L) return 0;
        if (hrvMs > 0L) return 1;
        return (nowMs - verdictMs) >= WAIT_HRV_S * 1000L ? 1 : 0;
    }

    /** One line for the status text while the rule waits. */
    static String waiting(int q3Sec, int cleanBeats, long verdictMs, long hrvMs, long nowMs) {
        if (q3Sec < Q3_SEC) return "good signal " + q3Sec + " of " + Q3_SEC + " s";
        if (cleanBeats < CLEAN_BEATS) return "clean beats " + cleanBeats + " of " + CLEAN_BEATS;
        if (verdictMs <= 0L) return "waiting for the strap's own verdict";
        if (hrvMs <= 0L) return "waiting for the strap's HRV (" + Math.max(0L, WAIT_HRV_S - (nowMs - verdictMs) / 1000L) + " s at most)";
        return "enough";
    }
}
