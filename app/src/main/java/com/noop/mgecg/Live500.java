package com.noop.mgecg;

// FILE VERSION 0.17.0 (27 Oct): the 500 Hz live graph data. Pure Java (no Android), tested off the phone against scipy.

/**
 * Turns the live raw ECG frames (type 43, layout 16, command 126) into a display trace.
 * A frame is 500 samples of 3 bytes: b0 bit 7 finger contact, bit 6 fast recovery (discard), bits 1:0 and b1 b2 = signed 18-bit count.
 * The scale is the design value 0.0621 uV per count (its absolute accuracy is OPEN, see the decode log). The strap's stored and live R16 are the
 * same record (91 of 91 and 183 of 183 byte for byte equal), so this is exactly what the report analyses, only filtered for the eye:
 * a causal 0.67 Hz high-pass, a 50 Hz notch (UK mains) and a 40 Hz low-pass. The reports keep their own zero-phase filter; this one bends the ST
 * segment a little and is for the live picture only.
 * Pacing: the samples of a frame are placed on the strap's own time axis (record time plus the sub-second, one record per second) and shown
 * BUFFER_MS plus the smoothed delivery delay after it, so the trace scrolls steadily (about 0.65 s behind the heart) whatever the arrival jitter.
 */
final class Live500 {
    private Live500() {}

    static final double UV_PER_COUNT = 0.0621;
    static final int FS = 500;
    static final int CAP = 4096;                 // about 8 s
    static final int RAIL = 126900;              // the clamp is at +-126,976 counts
    static final double BUFFER_MS = 500.0;
    static final double EMA = 0.05;                // slow: the delay follows the arrival times without jumping
    static final double MAX_PULL_MS = 20.0;        // at most this much correction of the timeline per frame

    /** Second-order section, transposed direct form II. */
    static final class Biquad {
        final double b0, b1, b2, a1, a2;
        double z1, z2;
        Biquad(double b0, double b1, double b2, double a1, double a2) { this.b0 = b0; this.b1 = b1; this.b2 = b2; this.a1 = a1; this.a2 = a2; }
        double step(double x) { double y = b0 * x + z1; z1 = b1 * x - a1 * y + z2; z2 = b2 * x - a2 * y; return y; }
    }

    /** High-pass 0.67 Hz, notch 50 Hz, low-pass 40 Hz (4th order), as designed with scipy at fs = 500 Hz. */
    static final class Chain {
        final Biquad[] s = new Biquad[] {
            new Biquad(0.9940642234526351, -1.9881284469052702, 0.9940642234526351, -1.988093213151696, 0.9881636806588452),
            new Biquad(0.9875889380903247, -1.5979524687435611, 0.9875889380903247, -1.5979524687435611, 0.9751778761806493),
            new Biquad(0.002234891698082326, 0.004469783396164652, 0.002234891698082326, -1.2128120926202186, 0.3840041622865537),
            new Biquad(1.0, 2.0, 1.0, -1.479798894397217, 0.6886769530538618)
        };
        double step(double x) { for (Biquad q : s) x = q.step(x); return x; }
        void clear() { for (Biquad q : s) { q.z1 = 0; q.z2 = 0; } }
        /** Settles the filter on a constant input so the trace starts at zero instead of ringing. */
        void settle(double x0) { clear(); for (int i = 0; i < 1500; i++) step(x0); }
    }

    private static final Object LOCK = new Object();
    private static final Chain chain = new Chain();
    private static final float[] val = new float[CAP];         // microvolts after the filter, NaN = no valid signal
    private static final double[] due = new double[CAP];       // wall clock ms at which the sample is due on screen
    private static int head, count;
    private static boolean primed;
    private static boolean haveOffset;
    private static double offsetMs;                            // smoothed (arrival minus record time) in ms
    private static long frames, lastFrameMs;
    private static long lastCounter = -1;
    private static int invalidInFrame;
    private static double nextDue = Double.NaN;                // due time the next sample should get so the trace is continuous

    static void reset() {
        synchronized (LOCK) { head = 0; count = 0; primed = false; haveOffset = false; frames = 0; lastFrameMs = 0; lastCounter = -1; nextDue = Double.NaN; chain.clear(); }
    }

    static long frames() { synchronized (LOCK) { return frames; } }

    /** True while frames keep arriving (the last one less than 3 s ago). */
    static boolean isLive(long nowMs) { synchronized (LOCK) { return frames > 0 && nowMs - lastFrameMs < 3000; } }

    /** Smoothed delay of the trace behind the record time, in seconds, for the label. */
    static double delaySeconds() { synchronized (LOCK) { return haveOffset ? (offsetMs + BUFFER_MS) / 1000.0 : 0.0; } }

    /** Number of samples the last frame held that were not usable (no contact, fast recovery or at the rail). */
    static int lastInvalid() { synchronized (LOCK) { return invalidInFrame; } }

    private static long u32(byte[] f, int o) { return (f[o] & 0xFFL) | ((f[o + 1] & 0xFFL) << 8) | ((f[o + 2] & 0xFFL) << 16) | ((f[o + 3] & 0xFFL) << 24); }

    /** Adds one live raw frame (the whole 1,584-byte frame). Returns the number of samples taken in, 0 if the frame was not usable. */
    static int feedFrame(byte[] f, long arrivalMs) {
        if (f == null || f.length < 1584 || (f[9] & 0xFF) != 16) return 0;
        int n = (f[32] & 0xFF) | ((f[33] & 0xFF) << 8);
        if (n < 1 || n > 500) return 0;
        long ctr = u32(f, 11);
        double recMs = (u32(f, 15) + (((f[19] & 0xFF) | ((f[20] & 0xFF) << 8)) / 32768.0)) * 1000.0;
        synchronized (LOCK) {
            double off = arrivalMs - recMs;
            if (!haveOffset) { offsetMs = off; haveOffset = true; } else offsetMs += (off - offsetMs) * EMA;
            boolean contiguous = lastCounter >= 0 && ctr == lastCounter + 1;
            if (lastCounter >= 0 && !contiguous) { primed = false; nextDue = Double.NaN; }   // a missing record: start the filter and the timeline again
            lastCounter = ctr;
            // a short first record is the end of its second (checked: no step at the join); the wanted start of this frame on the screen clock
            double wanted = recMs + (500 - n) * 2.0 + offsetMs + BUFFER_MS;
            double startMs, step = 2.0;
            if (Double.isNaN(nextDue)) startMs = wanted;
            else {                                                                         // keep the trace continuous: pull toward the wanted time gently
                double err = wanted - nextDue;
                double pull = Math.max(-MAX_PULL_MS, Math.min(MAX_PULL_MS, err));
                startMs = nextDue; step = 2.0 + pull / n;
            }
            int bad = 0;
            for (int k = 0; k < n; k++) {
                int b0 = f[34 + 3 * k] & 0xFF, b1 = f[35 + 3 * k] & 0xFF, b2 = f[36 + 3 * k] & 0xFF;
                int raw = ((b0 & 3) << 16) | (b1 << 8) | b2;
                if ((raw & 0x20000) != 0) raw -= 0x40000;
                boolean ok = (b0 & 0x80) != 0 && (b0 & 0x40) == 0 && Math.abs(raw) < RAIL;
                float out;
                if (ok) {
                    double uv = raw * UV_PER_COUNT;
                    if (!primed) { chain.settle(uv); primed = true; }
                    out = (float) chain.step(uv);
                } else { primed = false; out = Float.NaN; bad++; }
                int idx = (head + count) % CAP;
                if (count == CAP) { head = (head + 1) % CAP; idx = (head + count - 1) % CAP; } else count++;
                val[idx] = out;
                due[idx] = startMs + k * step;
            }
            nextDue = startMs + n * step;
            invalidInFrame = bad;
            frames++; lastFrameMs = arrivalMs;
        }
        return n;
    }

    /**
     * Copies the samples due between nowMs - windowMs and nowMs, oldest first, into outV (microvolts) and outT (due time in ms).
     * Returns how many were copied.
     */
    static int snapshot(long nowMs, double windowMs, float[] outV, double[] outT) {
        synchronized (LOCK) {
            int m = 0;
            for (int i = 0; i < count && m < outV.length; i++) {
                int idx = (head + i) % CAP;
                double d = due[idx];
                if (d > nowMs) break;
                if (d < nowMs - windowMs) continue;
                outV[m] = val[idx]; outT[m] = d; m++;
            }
            return m;
        }
    }
}
