package com.noop.mgecg;

// FILE VERSION 0.52.0 (10 Nov 2026): a steady clock for the scrolling graph (item 4a of the 0.52.0 graph build). Pure Java, tested off the phone.
// The old graph read System.currentTimeMillis() inside onDraw, so the scroll step jittered with the moment the draw happened (whole-pixel steps, 5-6 ms of time per pixel). The screen-refresh time that
// Choreographer hands to each frame is exactly one refresh period apart; this class keeps a slow average of (wall clock - frame time) and returns the wall time of a frame from its frame time.

final class FrameClock {
    private double offsetMs; private boolean init;
    static final double EMA = 0.02, JUMP_MS = 250.0;

    void reset() { init = false; }

    /** frameNs: the refresh time of this frame (nanoseconds, the Choreographer clock); wallNowMs: System.currentTimeMillis() now. Returns the wall-clock time of the frame in ms. */
    double wallMs(long frameNs, long wallNowMs) {
        double f = frameNs / 1e6, o = wallNowMs - f;
        if (!init || Math.abs(o - offsetMs) > JUMP_MS) { offsetMs = o; init = true; }      // first frame, or the wall clock was stepped
        else offsetMs += (o - offsetMs) * EMA;
        return f + offsetMs;
    }
}
