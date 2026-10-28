package com.noop.mgecg;

// FILE VERSION 0.18.0 (27 Oct): the 500 Hz live graph data, with QRS-aware smoothing. Pure Java (no Android), tested off the phone against a scipy reference.

/**
 * Turns the live raw ECG frames (type 43, layout 16, command 126) into a display trace.
 * A frame is 500 samples of 3 bytes: b0 bit 7 finger contact, bit 6 fast recovery (discard), bits 1:0 and b1 b2 = signed 18-bit count.
 * Scale: the design value 0.0621 uV per count (absolute accuracy OPEN). The live record equals the stored one byte for byte, so the reports analyse
 * exactly these samples; this class only prepares them for the eye.
 *
 * Pipeline (all of it for display only; the reports keep their own zero-phase filter):
 *  1. causal front end: 0.67 Hz high-pass and 50 Hz notch (UK mains), settled on the first good sample so the trace starts at zero;
 *  2. a polarity-independent QRS detector on the 5 to 20 Hz band (derivative energy), tuned on 161 and 208 beats of two real readings;
 *  3. two Gaussian smoothers, fast (sigma 3 ms, about 44 Hz) and slow (sigma 8 or 12 ms, about 15 or 11 Hz), blended by a QRS weight: the fast one
 *     around each beat (from about R-100 ms to R+86 ms), the slow one in between. The R wave and its width stay, the baseline fuzz between the
 *     beats drops from about 24 to about 7 microvolts. P and T waves keep about 90 % of their height.
 * Samples are released 0.2 s after they arrive (the Gaussians and the detector need that much look-ahead) and shown BUFFER_MS plus the smoothed delivery
 * delay after their record time, so the trace scrolls steadily about 0.65 s behind the heart whatever the arrival jitter.
 */
final class Live500 {
    private Live500() {}

    static final double UV_PER_COUNT = 0.0621;
    static final int FS = 500;
    static final int CAP = 8192;                   // samples kept (16 s); a power of two
    static final int RAIL = 126900;                // the clamp is at +-126,976 counts
    static final double BUFFER_MS = 500.0;
    static final double EMA = 0.05;                // the delivery delay follows the arrival times slowly
    static final double MAX_PULL_MS = 20.0;        // at most this much correction of the timeline per frame
    static final int REL = 100;                    // release delay in samples (0.2 s)
    static final int GAPN = 40;                    // NaN samples inserted for a missing record, wider than the Gaussian
    // QRS detector and mask (samples at 500 Hz); the detector peak lies about 18 samples after the R apex
    static final int EWIN = 20, REFRACT = 125, INIT = 1000, PRE = 68, POST = 25, TAPER = 15;
    static final double THR_FRAC = 0.1, ALPHA = 0.2, MAXJUMP = 6.0, EMIN = 400.0;     // low threshold: a missed beat would be smoothed, a false one only stays sharp
    static final double SIGMA_FAST_MS = 3.0;

    static final class Biquad {
        final double b0, b1, b2, a1, a2;
        double z1, z2;
        Biquad(double b0, double b1, double b2, double a1, double a2) { this.b0 = b0; this.b1 = b1; this.b2 = b2; this.a1 = a1; this.a2 = a2; }
        double step(double x) { double y = b0 * x + z1; z1 = b1 * x - a1 * y + z2; z2 = b2 * x - a2 * y; return y; }
        void clear() { z1 = 0; z2 = 0; }
    }

    /** 0.67 Hz high-pass then 50 Hz notch, designed with scipy at 500 Hz. */
    static final class Front {
        final Biquad[] s = new Biquad[] {
            new Biquad(0.9940642234526351, -1.9881284469052702, 0.9940642234526351, -1.988093213151696, 0.9881636806588452),
            new Biquad(0.9875889380903247, -1.5979524687435611, 0.9875889380903247, -1.5979524687435611, 0.9751778761806493)
        };
        double step(double x) { for (Biquad q : s) x = q.step(x); return x; }
        void clear() { for (Biquad q : s) q.clear(); }
        void settle(double x0) { clear(); for (int i = 0; i < 1500; i++) step(x0); }
    }

    /** Band-pass 5 to 20 Hz (two second-order sections) for the QRS detector. */
    static final class Band {
        final Biquad[] s = new Biquad[] { new Biquad(BP0[0], BP0[1], BP0[2], BP0[3], BP0[4]), new Biquad(BP1[0], BP1[1], BP1[2], BP1[3], BP1[4]) };
        double step(double x) { for (Biquad q : s) x = q.step(x); return x; }
        void clear() { for (Biquad q : s) q.clear(); }
    }
    private static final double[] BP0 = BP_SOS(0), BP1 = BP_SOS(1);
    private static double[] BP_SOS(int i) {
        // scipy.signal.butter(2, [5, 20], 'band', fs=500, output='sos'): b0 b1 b2 a1 a2 per section
        double[][] t = {
            { 0.007820208033497188, 0.015640416066994376, 0.007820208033497188, -1.7732572279128416, 0.8176260248009183 },
            { 1.0, -2.0, 1.0, -1.9319316285575803, 0.9368667064258096 }
        };
        return t[i];
    }

    private static final Object LOCK = new Object();
    private static final Front front = new Front();
    private static final Band band = new Band();
    private static final double[] xin = new double[CAP], outv = new double[CAP], env = new double[CAP], due = new double[CAP];
    private static long nAbs, procUpTo;                         // samples appended, samples with a final output
    private static boolean primed, haveOffset;
    private static double offsetMs, nextDue = Double.NaN, lastDueMs;
    private static long frames, lastFrameMs, lastCounter = -1;
    private static int invalidInFrame;
    // detector state
    private static double z1, z2;                               // band-pass output one and two samples ago
    private static final double[] ebuf = new double[EWIN];
    private static double esum;
    private static int eidx, ninit;
    private static long lastPeak = -1_000_000_000L;
    private static int bigCount;
    private static double peakLevel, initMax;
    private static boolean inited;
    private static final long[] pk = new long[64];
    private static int pkCount;
    private static long firstPeak = -1;
    // smoothing
    private static int mode = 2;                                // 0 off (no smoothing between beats), 1 light, 2 smooth
    private static double[] kFast = kernel(SIGMA_FAST_MS), kSlow = kernel(12.0);

    private static double[] kernel(double sigmaMs) {
        double s = sigmaMs / 1000.0 * FS; int h = (int) Math.ceil(3 * s); double[] k = new double[2 * h + 1];
        for (int i = -h; i <= h; i++) k[i + h] = Math.exp(-0.5 * (i / s) * (i / s));
        return k;
    }

    /** 0 = off, 1 = light (slow sigma 8 ms), 2 = smooth (12 ms). Set before a reading; takes effect for samples released after the call. */
    static void setMode(int m) { synchronized (LOCK) { mode = (m < 0 || m > 2) ? 2 : m; kSlow = kernel(mode == 0 ? SIGMA_FAST_MS : mode == 1 ? 8.0 : 12.0); } }
    static String modeLabel() { synchronized (LOCK) { return mode == 0 ? "not smoothed" : mode == 1 ? "light smoothing" : "smoothed"; } }

    static void reset() {
        synchronized (LOCK) {
            nAbs = 0; procUpTo = 0; primed = false; haveOffset = false; frames = 0; lastFrameMs = 0; lastCounter = -1; nextDue = Double.NaN; lastDueMs = 0;
            front.clear(); band.clear(); z1 = 0; z2 = 0; java.util.Arrays.fill(ebuf, 0); esum = 0; eidx = 0; ninit = 0; lastPeak = -1_000_000_000L; bigCount = 0;
            peakLevel = 0; initMax = 0; inited = false; pkCount = 0; firstPeak = -1;
        }
    }

    static long frames() { synchronized (LOCK) { return frames; } }
    static boolean isLive(long nowMs) { synchronized (LOCK) { return frames > 0 && nowMs - lastFrameMs < 3000; } }
    static double delaySeconds() { synchronized (LOCK) { return haveOffset ? (offsetMs + BUFFER_MS) / 1000.0 : 0.0; } }
    static int lastInvalid() { synchronized (LOCK) { return invalidInFrame; } }
    static int beatsSeen() { synchronized (LOCK) { return pkCount; } }

    static volatile java.util.function.DoubleConsumer testSink;      // off-phone tests only: every final output sample
    static volatile java.util.List<Long> testPeakSink;               // off-phone tests only: every detected beat (sample index)

    private static int p(long gi) { return (int) (gi & (CAP - 1)); }
    private static long u32(byte[] f, int o) { return (f[o] & 0xFFL) | ((f[o + 1] & 0xFFL) << 8) | ((f[o + 2] & 0xFFL) << 16) | ((f[o + 3] & 0xFFL) << 24); }

    /** One sample into the front end and the detector (call with LOCK held). */
    private static void append(boolean ok, double uv, double dueMs) {
        long gi = nAbs; int pi = p(gi);
        double ei;
        if (ok) {
            if (!primed) {
                front.settle(uv);
                band.clear(); z1 = 0; z2 = 0; java.util.Arrays.fill(ebuf, 0); esum = 0; primed = true;
            }
            double y = front.step(uv);
            xin[pi] = y;
            double z = band.step(y);
            double d = z - z2; z2 = z1; z1 = z;                       // z[i] - z[i-2]
            double d2 = d * d; double old = ebuf[eidx]; ebuf[eidx] = d2; eidx = (eidx + 1) % EWIN; esum += d2 - old;
            ei = esum / EWIN;
        } else {
            primed = false; xin[pi] = Double.NaN; ei = 0.0;
        }
        env[pi] = ei; due[pi] = dueMs; outv[pi] = Double.NaN; lastDueMs = dueMs;
        nAbs++;
        if (gi >= 2) {                                                // peak decision for sample gi-1
            double c = env[p(gi - 1)];
            if (!inited) {
                if (c > initMax) initMax = c;
                ninit++;
                if (ninit >= INIT) { peakLevel = initMax; inited = true; }
            } else {
                double thr = Math.max(THR_FRAC * peakLevel, EMIN);
                if (c > thr && c >= env[p(gi - 2)] && c > ei && (gi - 1 - lastPeak) > REFRACT) {
                    if (c < MAXJUMP * peakLevel) {
                        addPeak(gi - 1); peakLevel = (1 - ALPHA) * peakLevel + ALPHA * c; bigCount = 0;
                    } else if (++bigCount >= 3) {                      // three huge beats in a row: the level was too low, take it over
                        peakLevel = c; bigCount = 0; addPeak(gi - 1);
                    }
                }
                long sil = gi - 1 - lastPeak;
                if (sil > 4L * FS && sil % FS == 0) peakLevel = Math.max(peakLevel * 0.7, EMIN / THR_FRAC);   // nothing found for 4 s: let the threshold fall
            }
        }
    }

    private static void addPeak(long q) {
        pk[pkCount & 63] = q; pkCount++; if (firstPeak < 0) firstPeak = q; if (testPeakSink != null) testPeakSink.add(q);
        lastPeak = q;
    }

    private static double gconv(double[] k, long gi) {
        int h = k.length / 2; double num = 0, den = 0, sum = 0;
        for (int j = -h; j <= h; j++) {
            sum += k[j + h];
            long q = gi + j; if (q < 0 || q >= nAbs) continue;
            double v = xin[p(q)]; if (Double.isNaN(v)) continue;
            num += k[j + h] * v; den += k[j + h];
        }
        return den > 0.5 * sum ? num / den : Double.NaN;
    }

    /** QRS weight at sample gi: 1 inside a beat's window, 0 between beats, raised-cosine-free linear tapers of TAPER samples. */
    private static double weight(long gi) {
        if (pkCount == 0 || gi < firstPeak - PRE - TAPER) return 1.0;
        double w = 0;
        int n = Math.min(pkCount, 64);
        for (int j = 0; j < n; j++) {
            long q = pk[(pkCount - 1 - j) & 63];
            if (q + POST + TAPER < gi) break;
            if (q - PRE - TAPER > gi) continue;
            double a = Math.min((gi - (q - PRE - TAPER)) / (double) TAPER, ((q + POST + TAPER) - gi) / (double) TAPER);
            a = Math.max(0, Math.min(1, a));
            if (a > w) w = a;
        }
        return w;
    }

    private static void process() {
        long limit = nAbs - REL;
        long from = Math.max(procUpTo, nAbs - CAP + 64);
        for (long gi = from; gi < limit; gi++) {
            double x = xin[p(gi)];
            if (Double.isNaN(x)) { outv[p(gi)] = Double.NaN; if (testSink != null) testSink.accept(Double.NaN); continue; }
            double yf = gconv(kFast, gi);
            double w = weight(gi);
            double y = yf;
            if (w < 1.0 && mode != 0) { double ys = gconv(kSlow, gi); y = (Double.isNaN(ys) || Double.isNaN(yf)) ? yf : w * yf + (1 - w) * ys; }
            outv[p(gi)] = y;
            if (testSink != null) testSink.accept(y);
        }
        if (limit > procUpTo) procUpTo = limit;
    }

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
            if (lastCounter >= 0 && !contiguous) {                      // a missing record: break the trace, start the filters and the timeline again
                for (int g = 0; g < GAPN; g++) append(false, 0, lastDueMs + 2.0);
                nextDue = Double.NaN;
            }
            lastCounter = ctr;
            double wanted = recMs + (500 - n) * 2.0 + offsetMs + BUFFER_MS;      // a short first record is the end of its second
            double startMs, step = 2.0;
            if (Double.isNaN(nextDue)) startMs = Math.max(wanted, lastDueMs + 2.0);
            else {
                double pull = Math.max(-MAX_PULL_MS, Math.min(MAX_PULL_MS, wanted - nextDue));
                startMs = nextDue; step = 2.0 + pull / n;
            }
            int bad = 0;
            for (int k = 0; k < n; k++) {
                int b0 = f[34 + 3 * k] & 0xFF, b1 = f[35 + 3 * k] & 0xFF, b2 = f[36 + 3 * k] & 0xFF;
                int raw = ((b0 & 3) << 16) | (b1 << 8) | b2;
                if ((raw & 0x20000) != 0) raw -= 0x40000;
                boolean ok = (b0 & 0x80) != 0 && (b0 & 0x40) == 0 && Math.abs(raw) < RAIL;
                if (!ok) bad++;
                append(ok, raw * UV_PER_COUNT, startMs + k * step);
            }
            nextDue = startMs + n * step;
            invalidInFrame = bad;
            frames++; lastFrameMs = arrivalMs;
            process();
        }
        return n;
    }

    /**
     * Copies the final samples due between nowMs - windowMs and nowMs, oldest first, into outV (microvolts after the smoothing) and outT (due time in ms).
     * Returns how many were copied.
     */
    static int snapshot(long nowMs, double windowMs, float[] outV, double[] outT) {
        synchronized (LOCK) {
            int m = 0;
            long start = Math.max(0, nAbs - CAP + 64);
            for (long gi = start; gi < procUpTo && m < outV.length; gi++) {
                int pi = p(gi); double d = due[pi];
                if (d > nowMs) break;
                if (d < nowMs - windowMs) continue;
                outV[m] = (float) outv[pi]; outT[m] = d; m++;
            }
            return m;
        }
    }

    // ---- test hooks (used by the off-phone tests) ----
    static double[] testOutputs() { synchronized (LOCK) { int n = (int) Math.min(procUpTo, CAP - 64); double[] r = new double[n]; long s = procUpTo - n; for (int i = 0; i < n; i++) r[i] = outv[p(s + i)]; return r; } }
    static long[] testPeaks() { synchronized (LOCK) { int n = Math.min(pkCount, 64); long[] r = new long[n]; for (int i = 0; i < n; i++) r[i] = pk[(pkCount - n + i) & 63]; return r; } }

    /**
     * 0.22.0: the QRS height read-out in millivolts from the last m display samples (microvolts, NaN where there is no signal). For each whole second the 99.5th percentile minus the
     * median (every second holds at least one beat), then the median over the seconds, so one second with a lifted finger or a rail spike cannot move it. tmp needs 500 floats.
     * Returns NaN when there are fewer than two usable seconds. Pure.
     */
    static float qrsHeightMv(float[] v, int m, float[] tmp) {
        int ncs = m / 500;
        if (ncs < 2) return Float.NaN;
        float[] am = new float[ncs];
        int na = 0;
        for (int cs = 0; cs < ncs; cs++) {
            int kk = 0;
            for (int i = cs * 500; i < (cs + 1) * 500; i++) if (!Float.isNaN(v[i])) tmp[kk++] = v[i] / 1000f;
            if (kk < 250) continue;
            java.util.Arrays.sort(tmp, 0, kk);
            am[na++] = tmp[Math.min(kk - 1, (int) (kk * 0.995f))] - tmp[kk / 2];
        }
        if (na < 2) return Float.NaN;
        java.util.Arrays.sort(am, 0, na);
        return am[na / 2];
    }
}
