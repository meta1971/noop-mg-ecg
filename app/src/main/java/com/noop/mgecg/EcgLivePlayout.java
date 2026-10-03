package com.noop.mgecg;

// FILE VERSION 0.2.3 (3 Oct): smooth live playout

/**
 * The strap sends the live ECG as one frame of 100 samples every second (measured: 208 frames in 207 s, gaps 1.00 s
 * +-0.03, never two together). Drawing them as they arrive moves the trace one sixth of the screen at once, once a
 * second. This class holds the frames in a small buffer and plays the samples out at a steady 100 per second, so the
 * trace scrolls continuously, at the cost of about 0.6 s of delay.
 *
 *   push(v)         a sample arrived (call 100 times when a frame arrives)
 *   advance(dt, s)  called every screen refresh with the elapsed seconds; releases the samples that are due to s
 *   fraction()      0..1, how far the trace has moved toward the next sample; use it to shift the drawing by
 *                   fraction() samples for sub-sample smooth motion
 *
 * Pure Java, no Android. Everything runs on the UI thread, so no locking.
 */
public final class EcgLivePlayout {

    public interface Sink {
        void sample(float value);
    }

    public static final int FS = 100;
    static final int TARGET = 70;            // mean number of samples waiting: the sawtooth runs about 20 to 120
    static final int START_AT = 40;          // at the very start, wait for this many before moving
    static final int SKIP_ABOVE = 300;       // far behind (app was paused): jump forward instead of fast-forwarding
    static final double SPEED_MIN = 0.9, SPEED_MAX = 1.1;
    static final double EMA_TAU_S = 4.0;

    private final float[] ring = new float[1024];
    private long rawCount;                   // samples received
    private long played;                     // samples released
    private double credit;                   // fractional samples due
    private boolean buffering = true;
    private double emaQueue = TARGET;
    private int underruns;

    public void reset() {
        rawCount = 0;
        played = 0;
        credit = 0;
        buffering = true;
        emaQueue = TARGET;
        underruns = 0;
    }

    public void push(float v) {
        ring[(int) (rawCount % ring.length)] = v;
        rawCount++;
    }

    public int queued() {
        return (int) (rawCount - played);
    }

    public double fraction() {
        return credit;
    }

    /** Smoothed delay behind live, in seconds (steady enough to show on screen). */
    public double lagSeconds() {
        return emaQueue / FS;
    }

    public int underruns() {
        return underruns;
    }

    /** Releases the samples that are due after dt seconds. Returns how many were released. */
    public int advance(double dt, Sink sink) {
        if (dt <= 0 || dt > 1.0) dt = Math.min(Math.max(dt, 0), 0.05);   // a stalled frame must not release a burst
        int q = queued();
        if (q > SKIP_ABOVE) {                                            // far behind: drop to the target, keep the latest
            played = rawCount - TARGET;
            credit = 0;
            q = TARGET;
        }
        double a = 1.0 - Math.exp(-dt / EMA_TAU_S);
        emaQueue += (q - emaQueue) * a;
        if (buffering) {
            if (q < START_AT) return 0;
            buffering = false;
        }
        double speed = 1.0 + (emaQueue - TARGET) / 300.0;                // slow nudge so the average delay stays put
        speed = Math.max(SPEED_MIN, Math.min(SPEED_MAX, speed));
        if (q < 20) speed *= Math.max(0.25, q / 20.0);                   // nearly empty: slow down instead of stopping
        credit += dt * FS * speed;
        int n = 0;
        while (credit >= 1.0) {
            if (rawCount - played <= 0) {                                // ran dry: hold still until the next frame
                credit = 0;
                underruns++;
                break;
            }
            sink.sample(ring[(int) (played % ring.length)]);
            played++;
            credit -= 1.0;
            n++;
        }
        return n;
    }
}
