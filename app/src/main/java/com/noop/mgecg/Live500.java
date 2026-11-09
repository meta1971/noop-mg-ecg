package com.noop.mgecg;

// FILE VERSION 0.44.0 (7 Nov 2026): strap quality byte kept per sample (snapshotQ, quality colouring of the live trace), clean view (slow Gaussian 20 ms), newestBeatDue (heart-rate pulse); 0.41.0 (5 Nov 2026): pro display (PR-point spline baseline, beat windows, averaged beat, live noise by band, verdict timing, contact value); 0.18.0 (27 Oct): the 500 Hz live graph data, with QRS-aware smoothing. Pure Java (no Android), tested off the phone against a scipy reference.

/**
 * Turns the live raw ECG frames (type 43, layout 16, command 126) into a display trace.
 * A frame is 500 samples of 3 bytes: b0 bit 7 finger contact, bit 6 fast recovery (discard), bits 1:0 and b1 b2 = signed 18-bit count.
 * Scale: the design value 0.0621 uV per count (absolute accuracy OPEN). The live record equals the stored one byte for byte, so the reports analyse
 * exactly these samples; this class only prepares them for the eye.
 *
 * Pipeline (all of it for display only; the reports keep their own zero-phase filter):
 *  1. causal front end: 0.05 Hz first-order high-pass (0.26.1; was 0.67 Hz second order, which dug a false dip after each R wave) and 50 Hz notch (UK mains), settled on the first good sample so the trace starts at zero;
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
    static final double BUFFER_MS = 300.0;          // 0.41.1: was 500; the frames arrive every 1.00 s +- 0.03 s (p5-p95), so 0.3 s is enough margin
    static final double PRO_BUFFER_MS = 800.0;      // 0.41.1: was 1500; at the right edge 800 ms gave the flattest baseline (robust PR spread 10 / 13 / 0.3 uV against 22 / 37 / 18.5 uV without the spline)
    static volatile boolean pro = true;              // 0.41.0: set from the ECG page before a reading; read at reset()
    static volatile double bufferMs = BUFFER_MS;
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
            new Biquad(0.9996859393893597, -0.9996859393893597, 0.0, -0.9993718787787192, 0.0),      // 0.26.1: first-order 0.05 Hz high-pass (the 0.67 Hz second-order one dug a false dip after every R wave: S -156 against -30 uV true on 29 Oct)
            new Biquad(0.9875889380903247, -1.5979524687435611, 0.9875889380903247, -1.5979524687435611, 0.9751778761806493)
        };
        double step(double x) { for (Biquad q : s) x = q.step(x); return x; }
        void clear() { for (Biquad q : s) q.clear(); }
        void settle(double x0) { clear(); s[0].z1 = -s[0].b0 * x0; }      // 0.26.1: the exact steady state of a constant input (the 0.05 Hz filter needs about 30 s to settle by stepping)
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
    private static final byte[] qual = new byte[CAP];                // 0.44.0: the strap's per-second quality byte (R16 byte 21) of the frame each sample came from; 0 for inserted gap samples
    private static byte curQ;                                     // set under LOCK before the samples of a frame are appended
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
    private static final long[] pend = new long[64]; private static int pendHead, pendTail;      // 0.27.2: beats waiting for their noise window
    private static final java.util.ArrayList<Double> bnoise = new java.util.ArrayList<>();      // 0.27.2: per-beat noise (uV), as the report measures it
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
    static void setMode(int m) { synchronized (LOCK) { mode = (m < 0 || m > 2) ? 2 : m; kSlow = kernel(slowSigmaMs()); } }
    /** 0.44.0 clean view: heavier display smoothing between the beats (slow Gaussian sigma 20 ms instead of 8 or 12 ms); the QRS weight and the fast Gaussian are unchanged. Display only. */
    static volatile boolean cleanView;
    static final double SIGMA_CLEAN_MS = 20.0;
    static void setClean(boolean on) { synchronized (LOCK) { cleanView = on; kSlow = kernel(slowSigmaMs()); } }
    private static double slowSigmaMs() { return cleanView ? SIGMA_CLEAN_MS : mode == 0 ? SIGMA_FAST_MS : mode == 1 ? 8.0 : 12.0; }
    static String modeLabel() { synchronized (LOCK) { return cleanView ? "smoothed (clean view)" : mode == 0 ? "not smoothed" : mode == 1 ? "light smoothing" : "smoothed"; } }

    // 0.25.1: electrode contact seen in the live frames (about one frame a second)
    static volatile int openRun; static volatile boolean everOn; static volatile long startMs;
    static void resetContact() { openRun = 0; everOn = false; startMs = System.currentTimeMillis(); synchronized (NQ) { lastNoiseUv = Double.NaN; cleanSec = 0; nq.clear(); frames0 = frames; beats0 = pkCount; } lastCode = 0; lastState = 0; lastProgress = 255; lastMask = 0; lastAvgHr = 0; synchronized (LOCK) { pendHead = pendTail; } synchronized (NQ) { bnoise.clear(); } }
    // 0.27.0: live signal quality. Per frame (one second): white-noise estimate from the sample-to-sample differences (median absolute deviation / 0.6745 / sqrt 2),
    // lead-on samples only. Against the report's noise per beat over 20 readings: r 0.86, report = 1.30 x this (1.11 to 1.75).
    private static final Object NQ = new Object(); static volatile double lastNoiseUv = Double.NaN; static volatile int cleanSec; private static final java.util.ArrayList<Double> nq = new java.util.ArrayList<>(); private static long frames0, beats0;
    static final double REPORT_PER_LIVE = 1.30;
    static volatile int lastCode, lastState, lastProgress = 255, lastMask, lastAvgHr;      // 0.27.1: status bytes 23 to 27 of the newest live frame
    private static void noteNoise(byte[] f, int n) {
        int q = f[21] & 0xFF; double[] v = new double[n]; int m = 0;
        for (int i = 0; i < n; i++) { int b0 = f[34 + 3 * i] & 0xFF; if ((b0 & 0x80) == 0 || (b0 & 0x40) != 0) continue; int r = ((b0 & 3) << 16) | ((f[35 + 3 * i] & 0xFF) << 8) | (f[36 + 3 * i] & 0xFF); if ((r & 0x20000) != 0) r -= 0x40000; v[m++] = r * UV_PER_COUNT; }
        if (m < 400) { lastNoiseUv = Double.NaN; return; }
        double[] d = new double[m - 1]; for (int i = 1; i < m; i++) d[i - 1] = v[i] - v[i - 1];
        double md = med(d); double[] a = new double[d.length]; for (int i = 0; i < d.length; i++) a[i] = Math.abs(d[i] - md);
        double nz = med(a) / 0.6745 / Math.sqrt(2); lastNoiseUv = nz;
        if (q == 3) synchronized (NQ) { cleanSec++; nq.add(nz); }
    }
    /** 0.27.2: for each detected beat whose window is complete, the SD of the detrended samples 450-550 ms after it (front-end output), as the report's
     *  noise per beat does. Called under LOCK after each frame. */
    private static void drainBeatNoise() {
        while (pendHead < pendTail) {
            long q = pend[pendHead & 63];
            if (nAbs <= q + 280) break;
            pendHead++;
            if (nAbs - (q + 225) > CAP - 64) continue;
            double[] w = new double[51]; boolean ok = true;
            for (int k = 0; k <= 50; k++) { double v = xin[p(q + 225 + k)]; if (Double.isNaN(v)) { ok = false; break; } w[k] = v; }
            if (!ok) continue;
            double mx = 25.0, sy = 0, sxy = 0, sxx = 0; for (int k = 0; k <= 50; k++) sy += w[k]; double my = sy / 51.0;
            for (int k = 0; k <= 50; k++) { sxy += (k - mx) * (w[k] - my); sxx += (k - mx) * (k - mx); }
            double b = sxy / sxx, ss = 0; for (int k = 0; k <= 50; k++) { double r = w[k] - (my + b * (k - mx)); ss += r * r; }
            double sd = Math.sqrt(ss / 50.0);
            if (sd > 0 && sd < 2000) synchronized (NQ) { bnoise.add(sd); }
        }
    }

    private static double med(double[] x) { double[] c = x.clone(); java.util.Arrays.sort(c); int k = c.length; return k == 0 ? Double.NaN : (k % 2 == 1 ? c[k / 2] : (c[k / 2 - 1] + c[k / 2]) / 2); }
    /** Median live noise over the last k clean seconds. */
    static double recentNoise(int k) { synchronized (NQ) { int s = nq.size(); if (s == 0) return Double.NaN; double[] x = new double[Math.min(k, s)]; for (int i = 0; i < x.length; i++) x[i] = nq.get(s - 1 - i); return med(x); } }
    /** Expected noise on the report's mean beat if the reading stopped now (uV): 1.30 x median live noise / sqrt(beats in clean seconds). */
    static double meanBeatNoiseEstimate() {
        synchronized (NQ) { int nb = bnoise.size(); if (nb >= 20) { double[] x = new double[nb]; for (int i = 0; i < nb; i++) x[i] = bnoise.get(i); return 1.10 * med(x) / Math.sqrt(nb); } }      // 0.27.2: measured per beat, like the report; x1.10 keeps it on the safe side (replays: -13 to +9 % before the factor)
        synchronized (NQ) { int s = nq.size(); if (s < 10) return Double.NaN; double[] x = new double[s]; for (int i = 0; i < s; i++) x[i] = nq.get(i);
            double rate; synchronized (LOCK) { long fr = frames - frames0, bt = pkCount - beats0; rate = fr > 5 ? (double) bt / fr : 1.1; } rate = Math.max(0.6, Math.min(2.5, rate));
            return REPORT_PER_LIVE * med(x) / Math.sqrt(cleanSec * rate); }
    }
    static void reset() {
        synchronized (LOCK) {
            nAbs = 0; procUpTo = 0; primed = false; haveOffset = false; frames = 0; lastFrameMs = 0; lastCounter = -1; nextDue = Double.NaN; lastDueMs = 0;
            front.clear(); band.clear(); z1 = 0; z2 = 0; java.util.Arrays.fill(ebuf, 0); esum = 0; eidx = 0; ninit = 0; lastPeak = -1_000_000_000L; bigCount = 0;
            peakLevel = 0; initMax = 0; inited = false; pkCount = 0; firstPeak = -1;
            bufferMs = pro ? PRO_BUFFER_MS : BUFFER_MS; proReset(); LiveBands.reset();
        }
        sessionMs = System.currentTimeMillis(); verdictMs = 0; hrvMs = 0; hrvVal = 0; verdictCode = 0; verdictHr = 0; lastHrv = 65535; synchronized (ZL) { zList.clear(); }
    }

    static long frames() { synchronized (LOCK) { return frames; } }
    /** Milliseconds since the last live frame arrived (Long.MAX_VALUE when none came in this session). */
    static long sinceLastFrameMs(long nowMs) { synchronized (LOCK) { return frames > 0 ? nowMs - lastFrameMs : Long.MAX_VALUE; } }
    static boolean isLive(long nowMs) { synchronized (LOCK) { return frames > 0 && nowMs - lastFrameMs < 3000; } }
    /** Seconds from a heartbeat at the strap to the same heartbeat on the screen. A live record is stamped at its END and holds the second before it (history time = live time - 1.01 s, decode log 50.4), so the samples are 0.15 to 1.16 s old when the frame arrives; the display adds bufferMs on top. Up to 0.41.0 the label left out the 1.01 s. */
    static double delaySeconds() { synchronized (LOCK) { return haveOffset ? (offsetMs + 1010.0 + bufferMs) / 1000.0 : 0.0; } }
    static int lastInvalid() { synchronized (LOCK) { return invalidInFrame; } }
    static int beatsSeen() { synchronized (LOCK) { return pkCount; } }

    static volatile java.util.function.DoubleConsumer testSink;      // off-phone tests only: every final output sample
    static volatile java.util.List<double[]> testCandSink;           // off-phone tests only: every beat candidate (time s, height, signed height)
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
            xin[pi] = y; LiveBands.sample(gi, y, true);
            double z = band.step(y);
            double d = z - z2; z2 = z1; z1 = z;                       // z[i] - z[i-2]
            double d2 = d * d; double old = ebuf[eidx]; ebuf[eidx] = d2; eidx = (eidx + 1) % EWIN; esum += d2 - old;
            ei = esum / EWIN;
        } else {
            primed = false; xin[pi] = Double.NaN; ei = 0.0; LiveBands.sample(gi, 0.0, false);
        }
        env[pi] = ei; due[pi] = dueMs; outv[pi] = Double.NaN; qual[pi] = curQ; lastDueMs = dueMs;
        nAbs++;
        if (gi >= 2) {                                                // peak decision for sample gi-1
            double c = env[p(gi - 1)];
            if (!inited) {
                if (c > initMax) initMax = c;
                ninit++;
                if (ninit >= INIT) { peakLevel = initMax; inited = true; }
            } else {
                double thr = Math.max(THR_FRAC * peakLevel, EMIN);
                if (c > thr && c >= env[p(gi - 2)] && c > ei && ((gi - 1 - lastPeak) > REFRACT || ((gi - 1 - lastPeak) > BYPASS_GAP && c > BYPASS_K * lastPeakC))) {
                    if (c < MAXJUMP * peakLevel) {
                        addPeak(gi - 1); if (c >= 0.5 * peakLevel) peakLevel = (1 - ALPHA) * peakLevel + ALPHA * c; bigCount = 0;
                    } else if (++bigCount >= 3) {                      // three huge beats in a row: the level was too low, take it over
                        peakLevel = c; bigCount = 0; addPeak(gi - 1);
                    }
                }
                long sil = gi - 1 - lastPeak;
                if (sil > 4L * FS && sil % FS == 0) peakLevel = Math.max(peakLevel * 0.7, EMIN / THR_FRAC);   // nothing found for 4 s: let the threshold fall
            }
        }
    }

    /** 0.46.0: noise-robust detection (found on 87 real readings, 9 Nov: above 25 uV of 8-25 Hz noise the live detector fired on ripple peaks and its 0.25 s lockout then blocked the real beat behind them; accepted-beat recall 0.67 at 25-30 uV, 0.61 above 40). (1) A candidate inside the lockout is still taken when its energy is BYPASS_K times that of the last peak and it is at least BYPASS_GAP samples (50 ms) later: a real QRS cannot follow a ripple peak by less than 0.25 s otherwise, and the pro stage keeps the tallest candidate of a group; (2) the peak level is only updated by candidates of at least half of it, so a run of ripple peaks cannot drag the threshold down. Tested off-phone on the live frames of 87 readings: accepted-beat recall 0.95 / 0.95 / 0.92 / 0.85 / 0.89 at noise up to 20 / 20-25 / 25-30 / 30-40 / over 40 uV, precision 0.99 / 0.97 / 0.91 / 0.89 / 0.83. */
    static final double BYPASS_K = 2.5; static final int BYPASS_GAP = 25;
    private static double lastPeakC; static volatile java.util.List<Long> testBeatSink;      // off-phone tests only
    private static void addPeak(long q) {
        lastPeakC = env[p(q)];
        pend[pendTail & 63] = q; pendTail++;      // 0.27.2: queue the beat for its noise window
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
            if (w < 1.0 && (mode != 0 || cleanView)) { double ys = gconv(kSlow, gi); y = (Double.isNaN(ys) || Double.isNaN(yf)) ? yf : w * yf + (1 - w) * ys; }
            outv[p(gi)] = y;
            if (testSink != null) testSink.accept(y);
        }
        if (limit > procUpTo) procUpTo = limit;
        try { proUpdate(); } catch (RuntimeException ignored) { }
    }

    /** Adds one live raw frame (the whole 1,584-byte frame). Returns the number of samples taken in, 0 if the frame was not usable. */
    static int feedFrame(byte[] f, long arrivalMs) {
        if (f == null || f.length < 1584 || (f[9] & 0xFF) != 16) return 0;
        int n = (f[32] & 0xFF) | ((f[33] & 0xFF) << 8);
        if (n < 1 || n > 500) return 0;
        { int off = 0, tot = 0; for (int k = 0; k < n; k += 10) { tot++; if ((f[34 + 3 * k] & 0x80) == 0) off++; } if (tot > 0) { if (off * 10 >= tot * 9) openRun++; else { openRun = 0; if (off == 0) everOn = true; } } }
        try { noteNoise(f, n); } catch (Throwable ignored) { }      // 0.27.0
        try { synchronized (LOCK) { drainBeatNoise(); } } catch (Throwable ignored) { }      // 0.27.2
        lastCode = f[23] & 0xFF; lastState = f[24] & 0xFF; lastProgress = f[25] & 0xFF; lastMask = f[26] & 0xFF; lastAvgHr = f[27] & 0xFF;      // 0.27.1
        lastHrv = (f[29] & 0xFF) | ((f[30] & 0xFF) << 8);
        if (lastProgress == 100 && lastCode != 0 && verdictMs == 0) { verdictMs = arrivalMs; verdictCode = lastCode; verdictHr = lastAvgHr; }      // 0.41.0
        if (lastHrv != 65535 && lastHrv != 0 && hrvMs == 0) { hrvMs = arrivalMs; hrvVal = lastHrv; }
        try { noteZ(f); } catch (Throwable ignored) { }
        long ctr = u32(f, 11);
        double recMs = (u32(f, 15) + (((f[19] & 0xFF) | ((f[20] & 0xFF) << 8)) / 32768.0)) * 1000.0;
        synchronized (LOCK) {
            double off = arrivalMs - recMs;
            if (!haveOffset) { offsetMs = off; haveOffset = true; } else offsetMs += (off - offsetMs) * EMA;
            boolean contiguous = lastCounter >= 0 && ctr == lastCounter + 1;
            if (lastCounter >= 0 && !contiguous) {                      // a missing record: break the trace, start the filters and the timeline again
                curQ = 0;
                for (int g = 0; g < GAPN; g++) append(false, 0, lastDueMs + 2.0);
                nextDue = Double.NaN;
            }
            lastCounter = ctr;
            double wanted = recMs + (500 - n) * 2.0 + offsetMs + bufferMs;      // a short first record is the end of its second
            double startMs, step = 2.0;
            if (Double.isNaN(nextDue)) startMs = Math.max(wanted, lastDueMs + 2.0);
            else {
                double pull = Math.max(-MAX_PULL_MS, Math.min(MAX_PULL_MS, wanted - nextDue));
                startMs = nextDue; step = 2.0 + pull / n;
            }
            int bad = 0;
            curQ = (byte) (f[21] & 0xFF);
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
    static int snapshot(long nowMs, double windowMs, float[] outV, double[] outT) { return snapshotQ(nowMs, windowMs, outV, outT, null); }

    /** As snapshot, and the strap's quality byte (0 to 3) of each copied sample into outQ (may be null). */
    static int snapshotQ(long nowMs, double windowMs, float[] outV, double[] outT, byte[] outQ) {
        synchronized (LOCK) {
            int m = 0;
            long start = Math.max(0, nAbs - CAP + 64);
            for (long gi = start; gi < procUpTo && m < outV.length; gi++) {
                int pi = p(gi); double d = due[pi];
                if (d > nowMs) break;
                if (d < nowMs - windowMs) continue;
                outV[m] = (float) outv[pi]; outT[m] = d; if (outQ != null) outQ[m] = qual[pi]; m++;
            }
            if (pro && m > 0) { try { applyBase(outV, outT, m); } catch (RuntimeException ex) { if (testCandSink != null) ex.printStackTrace(); } }
            return m;
        }
    }


    // ======================================================================== 0.41.0 pro display
    static final int PRE_W = 200, POST_W = 375;                      // beat window: 400 ms before the R apex, 750 ms after (as EcgIntervals)
    static final double DIM_GATE = 0.65;                              // a beat whose shape correlates below this with the running template is drawn dim
    private static final int NB = 128, MAXWIN = 150;
    private static final long[] bApex = new long[NB];
    private static final double[] bPr = new double[NB], bPrDue = new double[NB], bDue = new double[NB];
    private static final float[] bAmp = new float[NB];
    private static final boolean[] bOk = new boolean[NB];
    private static int bN, pkDone, winNext;
    private static final java.util.ArrayList<double[]> wins = new java.util.ArrayList<>();
    static volatile long sessionMs, verdictMs, hrvMs; static volatile int lastHrv = 65535, hrvVal, verdictCode, verdictHr;
    private static final Object ZL = new Object(); private static final java.util.ArrayList<Double> zList = new java.util.ArrayList<>();

    private static final double[] candMag = new double[40]; private static int candN; private static long lastAccApex;
    private static boolean havePend; private static long pendApex; private static double pendMag, pendAmp, pendPr;
    static final int GROUP = 175;                                    // peaks within 0.35 s are one beat candidate (the largest wins)
    /** 0.46.0: PERSONAL-TEMPLATE VETO. tpl is the median beat of the personal beat bank (121 samples around the R apex, smoothed like the display trace, zero mean, unit norm), set before a reading; null = no bank yet, nothing changes. A beat candidate whose QRS part (+-120 ms of the display trace) correlates under TPL_VETO_RHO with it is not accepted unless it is taller than TPL_VETO_FRAC x the reference height. Leave-one-out test on the live frames of 87 real readings (9 Nov): accepted-beat precision at 25-30 / 30-40 / over 40 uV of noise 0.91 / 0.89 / 0.83 -> 0.96 / 0.92 / 0.98, recall 0.93 -> 0.93 (6 readings 0.5 to 5.7 points lower). */
    static final double TPL_VETO_RHO = 0.5, TPL_VETO_FRAC = 1.2;
    static volatile double[] tpl; static volatile int tplBeats;
    /** t: 121 samples around the R apex of the personal median beat (full band, any scale); n = beats behind it. */
    static void setTemplate(double[] t, int n) {
        if (t == null || t.length != 121) { tpl = null; tplBeats = 0; return; }
        double[] k = kFast; int h = k.length / 2; double[] sm = new double[121];
        for (int i = 0; i < 121; i++) { double num = 0, den = 0; for (int j = -h; j <= h; j++) { int q = i + j; if (q < 0 || q >= 121) continue; num += k[j + h] * t[q]; den += k[j + h]; } sm[i] = num / den; }
        double m = 0; for (double v : sm) m += v; m /= 121; double nn = 0; for (int i = 0; i < 121; i++) { sm[i] -= m; nn += sm[i] * sm[i]; } nn = Math.sqrt(nn);
        if (!(nn > 0)) { tpl = null; tplBeats = 0; return; }
        for (int i = 0; i < 121; i++) sm[i] /= nn; tpl = sm; tplBeats = n;
    }
    private static double tplRho(long apex, double sign) {
        double[] T = tpl; if (T == null) return Double.NaN;
        double[] w = new double[121]; double m = 0;
        for (int j = 0; j < 121; j++) { double v = outv[p(apex - 60 + j)]; if (Double.isNaN(v)) return Double.NaN; w[j] = sign * v; m += w[j]; }
        m /= 121; double nn = 0, dot = 0; for (int j = 0; j < 121; j++) { w[j] -= m; nn += w[j] * w[j]; dot += w[j] * T[j]; }
        return nn > 0 ? dot / Math.sqrt(nn) : Double.NaN;
    }
    private static void finishPending() {
        if (!havePend) return; havePend = false;
        candMag[candN % 40] = pendMag; candN++;
        if (candN < 8) return;                                                                // the first groups only set the level
        int k = Math.min(candN, 40); double[] t = java.util.Arrays.copyOf(candMag, k); java.util.Arrays.sort(t);
        double ref = t[(9 * (k - 1)) / 10];                                                   // 90th percentile of the group heights: the true beats are the tall ones
        if (pendMag < ACCEPT_FRAC * ref) return;                                              // a T wave, a noise burst
        { double rho = tplRho(pendApex, pendAmp >= 0 ? 1.0 : -1.0); if (!Double.isNaN(rho) && rho < TPL_VETO_RHO && pendMag < TPL_VETO_FRAC * ref) return; }      // 0.46.0: not shaped like this person's beat
        if (pendApex - lastAccApex < 150) return;                                             // closer than 0.3 s to the last beat
        lastAccApex = pendApex;
        int i = bN & (NB - 1);
        bApex[i] = pendApex; bPr[i] = pendPr; bPrDue[i] = due[p(pendApex - 42)]; bDue[i] = due[p(pendApex)]; bAmp[i] = (float) pendAmp; bOk[i] = true;
        bN++;
        LiveBands.beat(pendApex + 18);
        if (testBeatSink != null) testBeatSink.add(pendApex);
    }
    static final double ACCEPT_FRAC = 0.45;                          // a beat candidate counts as a beat when its height is at least this fraction of the 90th percentile of the last 40 candidate heights (as the offline detector of decode log 50.4)
    private static void proReset() { bN = 0; pkDone = 0; winNext = 0; wins.clear(); candN = 0; havePend = false; lastAccApex = Long.MIN_VALUE / 2; cleanBeatN = 0; }
    /** 0.45.0 (c): beats whose window matches the running template (correlation 0.95 or more, R height 60 to 140 % of the template's). The live counter toward the clean-beat target; the same rule as the report's beat selection (e). */
    private static volatile int cleanBeatN;
    static int cleanBeats() { return cleanBeatN; }

    /** Lead-off block of the live frame (decode log 2b): median magnitude of the valid slots, about 80 at good contact. */
    private static void noteZ(byte[] f) {
        if (f.length < 1580) return;
        int n = f[1534] & 0xFF; if (n < 1 || n > 11) return;
        double[] z = new double[n];
        for (int k = 0; k < n; k++) { int i = (f[1535 + 2 * k] & 0xFF) | ((f[1536 + 2 * k] & 0xFF) << 8); int q = (short) ((f[1557 + 2 * k] & 0xFF) | ((f[1558 + 2 * k] & 0xFF) << 8)); z[k] = Math.sqrt((double) i * i + (double) q * q); }
        double m = med(z); synchronized (ZL) { if (zList.size() < 1200) zList.add(m); }
    }
    /** Median lead-off magnitude over the reading (NaN when none). */
    static double medianZ() { synchronized (ZL) { int s = zList.size(); if (s == 0) return Double.NaN; double[] x = new double[s]; for (int i = 0; i < s; i++) x[i] = zList.get(i); return med(x); } }

    private static double medRange(double[] a, long from, long to) {
        double[] t = new double[(int) (to - from)]; int k = 0;
        for (long g = from; g < to; g++) { double v = a[p(g)]; if (!Double.isNaN(v)) t[k++] = v; }
        if (k < 8) return Double.NaN; return med(java.util.Arrays.copyOf(t, k));
    }

    private static double corr(double[] a, double[] b, int from, int to) {
        double ma = 0, mb = 0; int n = to - from; for (int i = from; i < to; i++) { ma += a[i]; mb += b[i]; } ma /= n; mb /= n;
        double sab = 0, saa = 0, sbb = 0; for (int i = from; i < to; i++) { double x = a[i] - ma, y = b[i] - mb; sab += x * y; saa += x * x; sbb += y * y; }
        return (saa > 0 && sbb > 0) ? sab / Math.sqrt(saa * sbb) : 0.0;
    }

    /** Called under LOCK after every frame: stage 1 finds the R apex, PR level and height of each new beat; stage 2 cuts the beat window once its 750 ms are final. */
    private static void proUpdate() {
        if (pkCount - pkDone > 60) pkDone = pkCount - 60;
        while (pkDone < pkCount) {
            long q = pk[pkDone & 63], c = q - 18;
            if (procUpTo < c + 25) break;
            long best = -1; double bv = -1;
            for (long g = c - 20; g <= c + 20; g++) { if (g < nAbs - CAP + 64) continue; double v = outv[p(g)]; if (Double.isNaN(v)) continue; double a = Math.abs(v); if (a > bv) { bv = a; best = g; } }
            pkDone++;
            if (best < 0 || best - 60 < nAbs - CAP + 64) continue;
            double pr = medRange(outv, best - 50, best - 35);
            if (Double.isNaN(pr)) continue;
            double amp = outv[p(best)] - pr, mag = Math.abs(amp);
            if (testCandSink != null) testCandSink.add(new double[] { best / (double) FS, mag, amp });
            if (havePend && best - pendApex < GROUP) { if (mag > pendMag) { pendApex = best; pendMag = mag; pendAmp = amp; pendPr = pr; } continue; }   // keep the largest peak within 0.35 s
            finishPending();
            havePend = true; pendApex = best; pendMag = mag; pendAmp = amp; pendPr = pr;
        }
        if (havePend && procUpTo >= pendApex + 100) finishPending();
        while (winNext < bN) {
            if (bN - winNext > NB - 8) winNext = bN - (NB - 8);
            int i = winNext & (NB - 1); long apex = bApex[i];
            if (procUpTo < apex + POST_W + 40) break;
            winNext++;
            if (apex - PRE_W < nAbs - CAP + 64) continue;
            double[] w = new double[PRE_W + POST_W]; boolean ok = true;
            for (int j = 0; j < w.length; j++) { double v = gconv(kFast, apex - PRE_W + j); if (Double.isNaN(v)) { ok = false; break; } w[j] = v; }
            if (!ok) continue;
            double[] seg = java.util.Arrays.copyOfRange(w, PRE_W - 50, PRE_W - 35); java.util.Arrays.sort(seg); double base = seg[seg.length / 2];
            double mx = 0; for (int j = PRE_W - 6; j <= PRE_W + 6; j++) { double d = w[j] - base; if (Math.abs(d) > Math.abs(mx)) mx = d; }
            double sign = mx >= 0 ? 1.0 : -1.0;
            for (int j = 0; j < w.length; j++) w[j] = sign * (w[j] - base);
            if (wins.size() >= 8) {
                int n = Math.min(wins.size(), 40); double[] tm = new double[w.length], col = new double[n];
                for (int j = 0; j < w.length; j++) { for (int g = 0; g < n; g++) col[g] = wins.get(wins.size() - n + g)[j]; tm[j] = med(col); }
                bOk[i] = corr(w, tm, PRE_W - 50, PRE_W + 225) >= DIM_GATE;
                double tmx = 0; for (int j = PRE_W - 6; j <= PRE_W + 6; j++) tmx = Math.max(tmx, Math.abs(tm[j]));
                double cq = corr(w, tm, PRE_W - 40, PRE_W + 60);      // the report's rule looks at the QRS (-80 to +120 ms), not at the T wave and the drift around it
                if (cq >= 0.95 && tmx > 0 && Math.abs(mx) >= 0.6 * tmx && Math.abs(mx) <= 1.4 * tmx) cleanBeatN++;
            }
            wins.add(w); if (wins.size() > MAXWIN) wins.remove(0);
        }
    }

    /** Subtracts the PR-point spline (cubic Hermite through the per-beat PR levels, 3-point median first) from the copied samples. Outside the known beats the baseline is held flat. */
    private static void applyBase(float[] v, double[] t, int m) {
        int cnt = Math.min(bN, NB), k = 0; double[] tt = new double[cnt], yy = new double[cnt];
        for (int i = bN - cnt; i < bN; i++) { int q = i & (NB - 1); if (Double.isNaN(bPr[q]) || Double.isNaN(bPrDue[q]) || !bOk[q]) continue; if (k > 0 && bPrDue[q] <= tt[k - 1]) continue; tt[k] = bPrDue[q]; yy[k] = bPr[q]; k++; }
        if (k < 2) return;
        double[] ym = new double[k];
        for (int j = 0; j < k; j++) { double a = yy[Math.max(0, j - 1)], b = yy[j], c = yy[Math.min(k - 1, j + 1)]; ym[j] = Math.max(Math.min(a, b), Math.min(Math.max(a, b), c)); }
        for (int j = 0; j < k; j++) {                                    // a level more than 40 uV from the median of its 5 neighbours is a bad beat: use that median
            int lo = Math.max(0, j - 2), hi = Math.min(k - 1, j + 2); double[] w5 = new double[hi - lo + 1]; for (int g = lo; g <= hi; g++) w5[g - lo] = ym[g];
            double m5 = med(w5); if (Math.abs(ym[j] - m5) > 40.0) ym[j] = m5;
        }
        double[] mt = new double[k];
        for (int j = 0; j < k; j++) { int a = Math.max(0, j - 1), b = Math.min(k - 1, j + 1); mt[j] = (b > a) ? (ym[b] - ym[a]) / (tt[b] - tt[a]) : 0.0; }
        int seg = 0;
        for (int i = 0; i < m; i++) {
            if (Float.isNaN(v[i])) continue;
            double x = t[i], base;
            if (x <= tt[0]) base = ym[0]; else if (x >= tt[k - 1]) base = ym[k - 1];
            else {
                while (seg < k - 2 && x > tt[seg + 1]) seg++;
                while (seg > 0 && x < tt[seg]) seg--;
                double h = tt[seg + 1] - tt[seg], s = (x - tt[seg]) / h, s2 = s * s, s3 = s2 * s;
                base = (2 * s3 - 3 * s2 + 1) * ym[seg] + (s3 - 2 * s2 + s) * h * mt[seg] + (-2 * s3 + 3 * s2) * ym[seg + 1] + (s3 - s2) * h * mt[seg + 1];
            }
            v[i] -= (float) base;
        }
    }

    /** Beats (R apex time on the display timeline, signed height in uV against the PR level, shape ok) inside the window; returns how many were copied. */
    static int proBeats(long nowMs, double windowMs, double[] tOut, float[] ampOut, boolean[] okOut) {
        synchronized (LOCK) {
            int cnt = Math.min(bN, NB), m = 0;
            for (int i = bN - cnt; i < bN && m < tOut.length; i++) { int q = i & (NB - 1); double d = bDue[q]; if (d > nowMs || d < nowMs - windowMs) continue; tOut[m] = d; ampOut[m] = bAmp[q]; okOut[m] = bOk[q]; m++; }
            return m;
        }
    }
    /** 0.44.0: display time (ms) of the newest accepted beat that is already on the screen (its due time is not after nowMs); NaN when none. The same beats as the R markers. */
    static double newestBeatDue(long nowMs) {
        synchronized (LOCK) {
            int cnt = Math.min(bN, NB);
            for (int i = bN - 1; i >= bN - cnt; i--) { double d = bDue[i & (NB - 1)]; if (d <= nowMs) return d; }
            return Double.NaN;
        }
    }
    /** The kept beat windows (PRE_W + POST_W samples, uV, baseline at the PR level, R up). Shared arrays: do not change them. */
    static java.util.List<double[]> windowsCopy() { synchronized (LOCK) { return new java.util.ArrayList<>(wins); } }
    static int beatCount() { synchronized (LOCK) { return bN; } }
    /** Median R-R of the last beats in seconds (NaN with fewer than 4 beats). */
    static double medianRrS() {
        synchronized (LOCK) {
            int cnt = Math.min(bN, 24); if (cnt < 4) return Double.NaN; double[] d = new double[cnt - 1];
            for (int i = 0; i < cnt - 1; i++) { int a = (bN - cnt + i) & (NB - 1), b = (bN - cnt + i + 1) & (NB - 1); d[i] = (bApex[b] - bApex[a]) / (double) FS; }
            return med(d);
        }
    }
    /** Beats in the first seconds of the reading and the whole reading (for the notes file). */
    static int beatsTotal() { synchronized (LOCK) { return pkCount; } }

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
