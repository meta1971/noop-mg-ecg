package com.noop.mgecg;

import java.util.ArrayList;
import java.util.List;

/**
 * WHOOP MG ECG ("Labrador") facts recovered from the official WHOOP Android
 * app (obfuscated DEX, read directly on 28 Sep 2026) and checked against this
 * project's own capture of the same day (216 live R17 frames + 215 stored R16
 * records from one session).
 *
 * Pure Java, no Android. Offsets are FRAME offsets, the convention used
 * everywhere else in this app (frame = "AA 01 len len 00 01 crc crc" + payload;
 * WHOOP's own code indexes the payload, i.e. frame offset - 8).
 */
public final class EcgWhoopSpec {

    private EcgWhoopSpec() {}

    // Status block - identical in live R17 (type 43) and stored R16 (layout 16)
    public static final int F_QUALITY = 21;   // 0..3, a state not a scale
    public static final int F_FLAGS = 22;     // bit1 = acquiring (WHOOP bit 9), bit3 = contact (WHOOP bit 11)
    public static final int F_RESULT = 23;    // HeartKey result code (mapping = MainActivity.strapCategory)
    public static final int F_STATE = 24;     // 1 active, 2 complete
    public static final int F_PROGRESS = 25;  // 0..100, 255 = failed
    public static final int F_REASONS = 26;   // reasons bitmask, see REASON_*
    public static final int F_AVG_HR = 27;    // WHOOP's saved result uses this HR
    public static final int F_LIVE_HR = 28;   // WHOOP's on-screen result uses this HR (0 in R16)
    public static final int F_RMSSD = 29;     // u16 ms, 0xFFFF = none (not read by WHOOP's app)
    public static final int F_COUNT = 32;     // u16 sample count
    public static final int F_SAMPLES = 34;

    /*
     * Reasons bitmask @26 - WHOOP's own enum, names and bit values verbatim.
     * The official app reads it when a reading ends Inconclusive.
     */
    public static final int REASON_LOW_AMPLITUDE = 1;
    public static final int REASON_SIGNIFICANT_NOISE = 2;
    public static final int REASON_UNSTABLE_SIGNAL = 4;
    public static final int REASON_NOT_ENOUGH_DATA = 8;

    public static List<String> reasons(int mask) {
        List<String> out = new ArrayList<>();
        if ((mask & REASON_LOW_AMPLITUDE) != 0) out.add("low amplitude");
        if ((mask & REASON_SIGNIFICANT_NOISE) != 0) out.add("significant noise");
        if ((mask & REASON_UNSTABLE_SIGNAL) != 0) out.add("unstable signal");
        if ((mask & REASON_NOT_ENOUGH_DATA) != 0) out.add("not enough data");
        if ((mask & ~0x0F) != 0) out.add("unknown bits 0x" + Integer.toHexString(mask & ~0x0F));
        return out;
    }

    // ------------------------------------------------------------------ units
    /*
     * R17 (live, 100 Hz): 1 count = 1 uV. WHOOP's app plots raw R17 samples
     * unscaled (clamped to +/-1000) in the same -1000..+2000 window it uses for
     * its cloud report waveform, whose grid is 6 boxes of 500 = standard
     * 0.5 mV boxes. OpenStrap documents the same.
     */
    public static final double R17_UV_PER_COUNT = 1.0;

    /*
     * R16 (stored, 500 Hz): passband gain R17/R16 = 0.0621 +/- 0.0003
     * (ten 20 s blocks, coherence > 0.98, 28 Sep session). As good as the
     * R17 figure above. R16 clips at +/-126976 counts = about +/-7.9 mV.
     *
     * NOTE 29 Sep 2026: two runs measured 0.0628 (spectral gain, 8 blocks of
     * 20 s, SD 0.0001) and 0.0629-0.0630 (least-squares fit of the R17 filter),
     * about 1.3% above this constant. The constant is left as it was because
     * the difference moves every uV figure by only ~1% and it is not yet known
     * whether the scale differs from day to day.
     */
    public static final double R16_UV_PER_COUNT = 0.0621;
    public static final int R16_RAIL = 126976;

    // ------------------------------------------------------------ R16 words
    /*
     * Each R16 sample = [tag][hi][lo]:
     *   tag bit7 = lead-on (clears within one sample when fingers leave)
     *   tag bit6 = amplifier fast-recovery window: a contiguous 250-sample
     *              (500 ms) run that starts ~125-130 ms after the signal hits
     *              the rail - the documented automatic fast-settling behaviour
     *              of this Maxim/ADI biopotential family. Not ECG.
     *   tag bits1-0 = bits 17-16 of an 18-bit two's-complement value
     */
    public static int r16Sample(byte[] f, int i) {
        int o = F_SAMPLES + 3 * i;
        int v = ((f[o] & 0x03) << 16) | ((f[o + 1] & 0xFF) << 8) | (f[o + 2] & 0xFF);
        return v >= (1 << 17) ? v - (1 << 18) : v;
    }

    public static boolean r16LeadOn(byte[] f, int i) {
        return (f[F_SAMPLES + 3 * i] & 0x80) != 0;
    }

    public static boolean r16FastRecovery(byte[] f, int i) {
        return (f[F_SAMPLES + 3 * i] & 0x40) != 0;
    }

    public static int r16Count(byte[] f) {
        return Math.min(500, (f[F_COUNT] & 0xFF) | ((f[F_COUNT + 1] & 0xFF) << 8));
    }

    public static int r16FastRecoveryCount(byte[] f) {
        int n = r16Count(f), c = 0;
        for (int i = 0; i < n; i++) if (r16FastRecovery(f, i)) c++;
        return c;
    }

    public static int r16LeadOffCount(byte[] f) {
        int n = r16Count(f), c = 0;
        for (int i = 0; i < n; i++) if (!r16LeadOn(f, i)) c++;
        return c;
    }

    /*
     * R16 trailer @1534 = electrode impedance, I/Q: count (10 or 11), then
     * 11 x int16 I, then 11 x int16 Q. Good contact on this unit: I ~78,
     * Q ~-21. Fingers off: I ~420-440, Q ~280-290.
     */
    public static final int F_LEADOFF_IQ = F_SAMPLES + 3 * 500;   // 1534
    public static final double LEAD_OFF_I_THRESHOLD = 200;
    /** Mean |I+jQ| of the lead-off trailer: about 80-90 with good contact, about 450 for the seconds after a finger lift. */
    public static final double LEAD_OFF_MAG_THRESHOLD = 250;

    public static double r16LeadOffMeanMag(byte[] f) {
        if (f.length < F_LEADOFF_IQ + 1 + 44) return 0;
        int n = Math.min(11, f[F_LEADOFF_IQ] & 0xFF);
        if (n == 0) return 0;
        double s = 0;
        for (int k = 0; k < n; k++) {
            int oi = F_LEADOFF_IQ + 1 + 2 * k, oq = F_LEADOFF_IQ + 1 + 22 + 2 * k;
            double i = (short) ((f[oi] & 0xFF) | ((f[oi + 1] & 0xFF) << 8));
            double q = (short) ((f[oq] & 0xFF) | ((f[oq + 1] & 0xFF) << 8));
            s += Math.hypot(i, q);
        }
        return s / n;
    }

    public static double r16LeadOffMeanI(byte[] f) {
        if (f.length < F_LEADOFF_IQ + 1 + 44) return 0;
        int n = Math.min(11, f[F_LEADOFF_IQ] & 0xFF);
        if (n == 0) return 0;
        double s = 0;
        for (int k = 0; k < n; k++) {
            int o = F_LEADOFF_IQ + 1 + 2 * k;
            s += (short) ((f[o] & 0xFF) | ((f[o + 1] & 0xFF) << 8));
        }
        return s / n;
    }

    // ------------------------------------------------------------ R17 frames
    /** Fingers off: the strap keeps sending R17 frames but zeroes every sample. */
    public static boolean r17AllZero(byte[] f) {
        if (f.length < 240) return false;
        int n = Math.min(100, (f[F_COUNT] & 0xFF) | ((f[F_COUNT + 1] & 0xFF) << 8));
        if (n == 0) return false;
        for (int i = 0; i < n; i++) {
            if (f[F_SAMPLES + 2 * i] != 0 || f[F_SAMPLES + 2 * i + 1] != 0) return false;
        }
        return true;
    }

    // --------------------------------------------------------------- filters
    /*
     * The strap's own live filter, measured from paired R16/R17 data:
     * 2nd-order Butterworth-like high-pass with -3 dB at 0.5 Hz, flat to
     * ~35 Hz, linear-phase low-pass ~41 Hz, ~270 ms group delay, then every
     * 5th sample kept (500 -> 100 Hz). whoopLiveReplica() reproduces it from
     * R16 at r = 0.998 (refit 29 Sep 2026, see there). R17 needs no further filtering on the phone (WHOOP's
     * app applies none; 50 Hz mains sits at R17's Nyquist, above its band).
     *
     * improvedFilter() is for stored R16: zero-phase 0.67 Hz high-pass (the
     * diagnostic-ECG rule allows 0.67 Hz only when zero-phase - no ST
     * distortion), a 50 Hz UK mains notch, zero-phase 40 Hz low-pass.
     * Best beat-to-beat SNR of the chains tested on 28 Sep (5.7 vs 4.9).
     */
    public static final class Biquad {
        final double b0, b1, b2, a1, a2;
        double x1, x2, y1, y2;

        Biquad(double b0, double b1, double b2, double a0, double a1, double a2) {
            this.b0 = b0 / a0; this.b1 = b1 / a0; this.b2 = b2 / a0;
            this.a1 = a1 / a0; this.a2 = a2 / a0;
        }

        static Biquad highPass(double fs, double f0, double q) {
            double w = 2 * Math.PI * f0 / fs, c = Math.cos(w), al = Math.sin(w) / (2 * q);
            return new Biquad((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al);
        }

        static Biquad lowPass(double fs, double f0, double q) {
            double w = 2 * Math.PI * f0 / fs, c = Math.cos(w), al = Math.sin(w) / (2 * q);
            return new Biquad((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + al, -2 * c, 1 - al);
        }

        static Biquad notch(double fs, double f0, double q) {
            double w = 2 * Math.PI * f0 / fs, c = Math.cos(w), al = Math.sin(w) / (2 * q);
            return new Biquad(1, -2 * c, 1, 1 + al, -2 * c, 1 - al);
        }

        double step(double x) {
            double y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1; x1 = x; y2 = y1; y1 = y;
            return y;
        }

        void prime(double x0) {   // start in steady state for a constant input
            double dc = (b0 + b1 + b2) / (1 + a1 + a2);
            x1 = x2 = x0;
            y1 = y2 = x0 * dc;
        }
    }

    private static double[] runCausal(double[] x, Biquad... chain) {
        double[] y = x.clone();
        for (Biquad b : chain) {
            if (y.length == 0) return y;
            b.prime(y[0]);
            for (int i = 0; i < y.length; i++) y[i] = b.step(y[i]);
        }
        return y;
    }

    private static void reverse(double[] a) {
        for (int i = 0, j = a.length - 1; i < j; i++, j--) { double t = a[i]; a[i] = a[j]; a[j] = t; }
    }

    /** Forward-backward, with up to 1 s of odd mirror padding at each end. */
    private static double[] runZeroPhase(double[] x, int pad, Biquad... chain) {
        if (x.length < 3) return x.clone();
        pad = Math.min(pad, x.length - 1);
        double[] ext = new double[x.length + 2 * pad];
        for (int i = 0; i < pad; i++) {
            ext[i] = 2 * x[0] - x[pad - i];
            ext[ext.length - 1 - i] = 2 * x[x.length - 1] - x[x.length - 1 - pad + i];
        }
        System.arraycopy(x, 0, ext, pad, x.length);
        double[] f = runCausal(ext, chain);
        reverse(f);
        f = runCausal(f, chain);
        reverse(f);
        double[] out = new double[x.length];
        System.arraycopy(f, pad, out, 0, x.length);
        return out;
    }

    /** Stored R16 display/measurement filter. 500 Hz, uV in, uV out. */
    public static double[] improvedFilter(double[] uv500) {
        double fs = 500;
        return runZeroPhase(uv500, 500,
                Biquad.highPass(fs, 0.67, Math.sqrt(0.5)),
                Biquad.notch(fs, 50.0, 30.0),
                Biquad.lowPass(fs, 40.0, 0.5411961),
                Biquad.lowPass(fs, 40.0, 1.3065630));
    }

    /**
     * Reproduces the strap's live R17 from R16 (uV in at 500 Hz, uV out at 100 Hz).
     *
     * Refitted 29 Sep 2026 on two runs (about 6 minutes of paired R16/R17, output
     * of a free 800-tap least-squares filter as the ceiling): causal 2nd-order
     * high-pass at 0.50 Hz (Q 0.716), then a 271-tap linear-phase low-pass
     * (-3 dB about 41 Hz, Kaiser window beta 15, cutoff 42.6 Hz), keeping
     * filtered samples 3, 8, 13 ... (500 -> 100 Hz). That has ~270 ms group
     * delay and lines up with R17 sample for sample. Unexplained variance was
     * 0.44% on run 1 and 0.26% on run 2, against 0.10% (in-sample) and 0.28%
     * (out-of-sample) for the free filter, so what is left is not a fixed
     * filter. The previous 125-tap version was ~140 ms too early, at a 1.6%
     * higher gain than R17 counts (see R16_UV_PER_COUNT).
     */
    public static double[] whoopLiveReplica(double[] uv500) {
        double fs = 500;
        double[] y = runCausal(uv500, Biquad.highPass(fs, 0.50, 0.7156));
        double[] h = firLowPassKaiser(271, 42.6, fs, 15.0);
        final int phase = 3;
        int n = uv500.length - phase;
        double[] out = new double[Math.max(0, (n + 4) / 5)];
        for (int k = 0; k < out.length; k++) {
            int idx = k * 5 + phase;
            double s = 0;
            for (int j = 0; j < h.length; j++) s += h[j] * y[Math.max(0, idx - j)];
            out[k] = s;
        }
        return out;
    }

    /** Zeroth-order modified Bessel function, series form (enough terms for a Kaiser window). */
    private static double bessel0(double x) {
        double sum = 1, term = 1, q = x * x / 4;
        for (int k = 1; k < 60; k++) {
            term *= q / ((double) k * k);
            sum += term;
        }
        return sum;
    }

    /** Linear-phase low-pass, windowed sinc with a Kaiser window, unity gain at DC. */
    static double[] firLowPassKaiser(int taps, double fc, double fs, double beta) {
        double[] h = new double[taps];
        double m = (taps - 1) / 2.0, sum = 0, i0b = bessel0(beta);
        for (int i = 0; i < taps; i++) {
            double t = i - m;
            double sinc = t == 0 ? 2 * fc / fs : Math.sin(2 * Math.PI * fc / fs * t) / (Math.PI * t);
            double r = t / m;
            double w = bessel0(beta * Math.sqrt(Math.max(0, 1 - r * r))) / i0b;
            h[i] = sinc * w;
            sum += h[i];
        }
        for (int i = 0; i < taps; i++) h[i] /= sum;
        return h;
    }

    static double[] firLowPass(int taps, double fc, double fs) {
        double[] h = new double[taps];
        double m = (taps - 1) / 2.0, sum = 0;
        for (int i = 0; i < taps; i++) {
            double t = i - m;
            double sinc = t == 0 ? 2 * fc / fs : Math.sin(2 * Math.PI * fc / fs * t) / (Math.PI * t);
            h[i] = sinc * (0.54 - 0.46 * Math.cos(2 * Math.PI * i / (taps - 1)));
            sum += h[i];
        }
        for (int i = 0; i < taps; i++) h[i] /= sum;
        return h;
    }
}
