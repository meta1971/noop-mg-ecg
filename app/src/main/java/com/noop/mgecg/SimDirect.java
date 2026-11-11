package com.noop.mgecg;

// FILE VERSION 0.52.2 (11 Nov 2026): W wiring check and the strap's own contact verdict in every direct result. FILE VERSION 0.52.1 (11 Nov 2026): the DIRECT phone-audio Lab runs (decode log 151, 152, 154, 155): no divider and no DC. The strap input is high impedance, so the phone's headphone output can drive the two
// pods directly when the signal is made small DIGITALLY: 1 mV of ECG = 0.001 of full scale (-60 dBFS); nothing played into the strap exceeds -48 dBFS (a few millivolts). The phone's full-scale voltage is
// measured once with a multimeter on the calibration tones (mode M, cable on the METER) and scaled by the exact digital ratio. Pure Java (no Android): generator, ground truth and analysis, tested off the phone.
//   M  calibration tones for the meter: 5 s silence, 12 s 1 kHz at -20 dBFS, 3 s, 12 s 100 Hz at -20 dBFS, 3 s, 12 s 1 kHz at -30 dBFS, 3 s. NEVER into the strap.
//   D  direct tones: 10 Hz at -66 / -60 / -54 / -48 dBFS (6 s each, 2 s gaps), then 0.5 1 2 5 10 20 40 60 100 150 200 Hz at -54 dBFS: the scale (uV per count), its linearity and the frequency response of R16.
//   E  ECG scenarios A (40 s each): regular 70 bpm; muscle-artefact noise at +12 +6 0 -6 dB; electrode-motion noise at -6 dB; jitter with RMSSD 15 40 100 ms.
//   F  ECG scenarios B: amplitude x0.25 x0.5 x1.5 x2.5 (30 s each); rate ramp (60 s); rate step 60-110-60 bpm (60 s); premature beats (40 s); pauses (40 s); 50 Hz hum 0.01 0.03 0.1 mV (20 s each); wander and steps (40 s).
//   G  AF replay: 120 s of a real atrial-fibrillation record (SimData.AF).
// The ECG tracks are built from the average beat of a real record on EXACT beat times (SimData.TEMPLATE), with real noise records (SimData.MA, SimData.EM); every beat time is known.
// Nothing here is applied to the app. From 0.53.0 the app scale is 0.06358 uV per count (decode log 161); these runs are the physical check of it.

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Random;

final class SimDirect {
    private SimDirect() {}

    static final int SR = 48000, FS = 500;
    static final double FS_PER_MV = 0.001, MAX_STRAP_FS = 0.0041, AUDIO_AT_S = 10.0, TAIL_S = 6.0;
    static final double[] LEVEL_DB = { -66, -60, -54, -48 }, FREQ = { 0.5, 1, 2, 5, 10, 20, 40, 60, 100, 150, 200 };      // 0.53.0: 60-200 Hz added for the raw stream's upper response
    static final double TONE_DB = -54, EDGE_S = 0.002;
    static final String[] FREQ_KEY = { "0p5", "1", "2", "5", "10", "20", "40", "60", "100", "150", "200" };

    static final class Seg { String name = "", kind = ""; double start, end, param = Double.NaN; double[] beats = new double[0]; }
    static final class Track { char mode; double seconds; float[] ecg; final List<Seg> segs = new ArrayList<>(); }

    static boolean isDirect(char m) { return m == 'D' || m == 'E' || m == 'F' || m == 'G' || m == 'W'; }
    static String title(char m) { return m == 'W' ? "Wiring check" : m == 'M' ? "Meter calibration" : m == 'D' ? "Direct tones" : m == 'E' ? "ECG scenarios A" : m == 'F' ? "ECG scenarios B" : m == 'G' ? "AF replay" : "?"; }

    // ------------------------------------------------------------------ tone tracks (analytic)
    private static double db(double d) { return Math.pow(10.0, d / 20.0); }
    private static double env(double u, double len) { return u < 0 || u >= len ? 0 : u < EDGE_S ? 0.5 * (1 - Math.cos(Math.PI * u / EDGE_S)) : len - u < EDGE_S ? 0.5 * (1 - Math.cos(Math.PI * (len - u) / EDGE_S)) : 1; }
    static final double CAL_S = 50.0, TONES_S = 34 + 11 * 8 + TAIL_S;

    /** Calibration tones (full-scale units). */
    static double calValue(double t) {
        if (t >= 5 && t < 17) return db(-20) * env(t - 5, 12) * Math.sin(2 * Math.PI * 1000 * (t - 5));
        if (t >= 20 && t < 32) return db(-20) * env(t - 20, 12) * Math.sin(2 * Math.PI * 100 * (t - 20));
        if (t >= 35 && t < 47) return db(-30) * env(t - 35, 12) * Math.sin(2 * Math.PI * 1000 * (t - 35));
        return 0;
    }
    /** Direct tones (full-scale units), t from the start of the audio. */
    static double toneValue(double t) {
        if (t < 0) return 0;
        if (t < 32) { int i = (int) (t / 8); double u = t - 8 * i; return u < 6 ? db(LEVEL_DB[i]) * env(u, 6) * Math.sin(2 * Math.PI * 10 * u) : 0; }
        double r = t - 34; if (r < 0) return 0; int j = (int) (r / 8); if (j >= FREQ.length) return 0; double u = r - 8 * j; return u < 6 ? db(TONE_DB) * env(u, 6) * Math.sin(2 * Math.PI * FREQ[j] * u) : 0;
    }

    // ------------------------------------------------------------------ ECG tracks (constructed at 500 Hz, millivolts)
    private static double[] tpl; private static float[] ma, em, af;
    private static synchronized void load() {
        if (tpl != null) return;
        short[] t = SimData.decode(SimData.TEMPLATE, SimData.TEMPLATE_N); double[] T = new double[t.length]; for (int i = 0; i < t.length; i++) T[i] = t[i] / 10000.0;
        int m = (int) (0.05 * FS), m2 = (int) (0.10 * FS); for (int i = 0; i < m; i++) T[i] *= 0.5 * (1 - Math.cos(Math.PI * i / m)); for (int i = 0; i < m2; i++) T[T.length - m2 + i] *= 0.5 * (1 + Math.cos(Math.PI * i / m2));
        short[] a = SimData.decode(SimData.MA, SimData.MA_N); ma = upsample(a, 2, 1.0 / 5000.0);
        short[] e = SimData.decode(SimData.EM, SimData.EM_N); em = upsample(e, 4, 1.0 / 5000.0);
        short[] f = SimData.decode(SimData.AF, SimData.AF_N); af = upsample(f, 2, 1.0 / 200.0); double mean = 0; for (float x : af) mean += x; mean /= af.length; for (int i = 0; i < af.length; i++) af[i] -= (float) mean;
        tpl = T;
    }
    private static float[] upsample(short[] x, int k, double unit) { float[] y = new float[x.length * k]; for (int i = 0; i < y.length; i++) { double p = i / (double) k; int j = (int) p; double fr = p - j; double a = x[Math.min(x.length - 1, j)], b = x[Math.min(x.length - 1, j + 1)]; y[i] = (float) ((a + (b - a) * fr) * unit); } return y; }

    private static void place(float[] x, double[] beats, double t0, double amp) {
        for (double b : beats) { int i0 = (int) Math.round((t0 + b - 0.30) * FS); for (int j = 0; j < tpl.length; j++) { int k = i0 + j; if (k >= 0 && k < x.length) x[k] += (float) (tpl[j] * amp); } }
    }
    private static double[] toArr(List<Double> l) { double[] a = new double[l.size()]; for (int i = 0; i < a.length; i++) a[i] = l.get(i); return a; }
    private static double[] regular(double dur, double hr) { List<Double> l = new ArrayList<>(); for (double t = 0.4; t < dur - 0.5; t += 60.0 / hr) l.add(t); return toArr(l); }
    private static final double RR = 60.0 / 70.0;

    static synchronized Track track(char mode) {
        load(); Track tr = new Track(); tr.mode = mode;
        if (mode == 'M') { tr.seconds = CAL_S; return tr; }
        if (mode == 'D') { tr.seconds = TONES_S; for (int i = 0; i < 4; i++) { Seg s = new Seg(); s.name = "L" + (int) -LEVEL_DB[i]; s.kind = "level"; s.start = 8 * i; s.end = 8 * i + 6; s.param = LEVEL_DB[i]; tr.segs.add(s); } for (int j = 0; j < FREQ.length; j++) { Seg s = new Seg(); s.name = "F" + FREQ_KEY[j]; s.kind = "tone"; s.start = 34 + 8 * j; s.end = s.start + 6; s.param = FREQ[j]; tr.segs.add(s); } return tr; }
        if (mode == 'G') { tr.seconds = af.length / (double) FS + TAIL_S; tr.ecg = Arrays.copyOf(af, (int) (tr.seconds * FS)); Seg s = new Seg(); s.name = "AF"; s.kind = "af"; s.start = 0; s.end = af.length / (double) FS; tr.segs.add(s); return tr; }
        Object[][] spec = mode == 'E'
            ? new Object[][] { { "REF", "regular", 40.0, 0.0 }, { "MA12", "ma", 40.0, 12.0 }, { "MA6", "ma", 40.0, 6.0 }, { "MA0", "ma", 40.0, 0.0 }, { "MAm6", "ma", 40.0, -6.0 }, { "EMm6", "em", 40.0, -6.0 }, { "JIT15", "jitter", 40.0, 15.0 }, { "JIT40", "jitter", 40.0, 40.0 }, { "JIT100", "jitter", 40.0, 100.0 } }
            : new Object[][] { { "AMP025", "amp", 30.0, 0.25 }, { "AMP05", "amp", 30.0, 0.5 }, { "AMP15", "amp", 30.0, 1.5 }, { "AMP25", "amp", 30.0, 2.5 }, { "RAMP", "ramp", 60.0, 0.0 }, { "STEP", "step", 60.0, 0.0 }, { "ECTOPY", "ectopy", 40.0, 0.0 }, { "PAUSES", "pauses", 40.0, 0.0 },
                               { "HUM001", "hum", 20.0, 0.01 }, { "HUM003", "hum", 20.0, 0.03 }, { "HUM01", "hum", 20.0, 0.1 }, { "WANDER", "wander", 40.0, 0.0 } };
        double total = 0; for (Object[] s : spec) total += (Double) s[2]; tr.seconds = total + TAIL_S; float[] x = new float[(int) (tr.seconds * FS)]; double t0 = 0; int idx = 0;
        for (Object[] sp : spec) {
            Seg s = new Seg(); s.name = (String) sp[0]; s.kind = (String) sp[1]; double dur = (Double) sp[2]; s.param = (Double) sp[3]; s.start = t0; s.end = t0 + dur; Random r = new Random(1000 + 31 * (mode == 'E' ? 1 : 2) + idx);
            int a = (int) (t0 * FS), n = (int) (dur * FS); double amp = 1.0; double[] bt;
            if (s.kind.equals("jitter")) { List<Double> l = new ArrayList<>(); double sd = s.param / 1000.0 / Math.sqrt(2), t = 0.4; while (t < dur - 0.8) { l.add(t); t += Math.max(0.35, RR + r.nextGaussian() * sd); } bt = toArr(l); }
            else if (s.kind.equals("ramp")) { List<Double> l = new ArrayList<>(); double t = 0.4; while (t < dur - 0.8) { l.add(t); t += 60.0 / (82.5 - 37.5 * Math.sin(2 * Math.PI * t / 60.0)); } bt = toArr(l); }
            else if (s.kind.equals("step")) { List<Double> l = new ArrayList<>(); double t = 0.4; while (t < dur - 0.8) { l.add(t); t += 60.0 / (t < 20 ? 60.0 : t < 40 ? 110.0 : 60.0); } bt = toArr(l); }
            else if (s.kind.equals("ectopy")) { List<Double> l = new ArrayList<>(); int K = (int) (dur / RR) + 2; for (int k = 0; k < K; k++) { double t = 0.4 + k * RR - ((k > 0 && k % 8 == 0) ? 0.35 * RR : 0); if (t < dur - 0.8) l.add(t); } bt = toArr(l); }
            else if (s.kind.equals("pauses")) { List<Double> l = new ArrayList<>(); int K = (int) (dur / RR) + 2; for (int k = 0; k < K; k++) { if (k > 0 && k % 10 == 0) continue; double t = 0.4 + k * RR; if (t < dur - 0.8) l.add(t); } bt = toArr(l); }
            else { bt = regular(dur, 70.0); if (s.kind.equals("amp")) amp = s.param; }
            place(x, bt, t0, amp); s.beats = new double[bt.length]; for (int i = 0; i < bt.length; i++) s.beats[i] = t0 + bt[i];
            if (s.kind.equals("ma") || s.kind.equals("em")) {
                float[] N = s.kind.equals("ma") ? ma : em; int off = r.nextInt(Math.max(1, N.length - n - 1)); double se = 0, sn = 0; for (int i = 0; i < n; i++) { se += x[a + i] * (double) x[a + i]; sn += N[off + i] * (double) N[off + i]; }
                double g = Math.sqrt(se / n) / Math.sqrt(sn / n) / Math.pow(10, s.param / 20.0); for (int i = 0; i < n; i++) x[a + i] += (float) (g * N[off + i]);
            } else if (s.kind.equals("hum")) { for (int i = 0; i < n; i++) x[a + i] += (float) (s.param * Math.sin(2 * Math.PI * 50 * i / (double) FS)); }
            else if (s.kind.equals("wander")) { double cur = 0, nxt = 4.0; for (int i = 0; i < n; i++) { double t = i / (double) FS; if (t >= nxt) { cur += r.nextBoolean() ? 0.5 : -0.5; cur *= 0.6; nxt += 6 + 4 * r.nextDouble(); } x[a + i] += (float) (0.4 * Math.sin(2 * Math.PI * 0.3 * t) + cur); } }
            tr.segs.add(s); t0 += dur; idx++;
        }
        tr.ecg = x; return tr;
    }

    /** Peak of the track in full-scale units (for the safety check and the tests). */
    static double peakFs(Track tr) {
        double mx = 0;
        if (tr.ecg != null) { for (float v : tr.ecg) mx = Math.max(mx, Math.abs(v) * FS_PER_MV); return mx; }
        for (double t = 0; t < tr.seconds; t += 1.0 / 4000.0) mx = Math.max(mx, Math.abs(tr.mode == 'M' ? calValue(t) : toneValue(t))); return mx;
    }

    /** n float samples at 48 kHz from sample index pos (0 = the start of the audio). ECG tracks: cubic interpolation of the 500 Hz track, with +-1 LSB (16-bit) triangular dither so that a 16-bit output path stays linear. */
    static void fill(Track tr, float[] out, int n, long pos, Random rng) {
        for (int i = 0; i < n; i++) {
            double t = (pos + i) / (double) SR, v;
            if (tr.mode == 'M') v = calValue(t);
            else if (tr.mode == 'D') v = toneValue(t);
            else { double p = t * FS; int j = (int) p; double f = p - j; float[] e = tr.ecg; int L = e.length; if (j + 2 >= L) v = 0; else { double p0 = e[Math.max(0, j - 1)], p1 = e[j], p2 = e[j + 1], p3 = e[j + 2]; v = 0.5 * (2 * p1 + (-p0 + p2) * f + (2 * p0 - 5 * p1 + 4 * p2 - p3) * f * f + (-p0 + 3 * p1 - 3 * p2 + p3) * f * f * f) * FS_PER_MV; } }
            if (tr.mode != 'M') { if (v > MAX_STRAP_FS) v = MAX_STRAP_FS; else if (v < -MAX_STRAP_FS) v = -MAX_STRAP_FS; v += (rng.nextDouble() - rng.nextDouble()) / 32768.0; }
            out[i] = (float) v;
        }
    }

    // ------------------------------------------------------------------ analysis
    static final class Result { final LinkedHashMap<String, String> notes = new LinkedHashMap<>(); final List<String> card = new ArrayList<>(); boolean ok; }
    private static String f(double v, int d) { return Double.isNaN(v) || Double.isInfinite(v) ? "" : String.format(Locale.US, "%." + d + "f", v); }

    /** R16 counts at 500 Hz by the record counter (missing seconds stay 0). */
    static double[] counts(List<byte[]> fr) {
        if (fr == null || fr.isEmpty()) return new double[0];
        long lo = Long.MAX_VALUE, hi = Long.MIN_VALUE; for (byte[] x : fr) { long c = ProAnalysis.u32(x, 11); if (c < lo) lo = c; if (c > hi) hi = c; }
        if (hi - lo > 3600 || hi - lo + 1 < fr.size() / 2) { lo = Long.MAX_VALUE; hi = Long.MIN_VALUE; for (byte[] x : fr) { long c = ProAnalysis.u32(x, 15); if (c < lo) lo = c; if (c > hi) hi = c; } if (hi - lo > 3600) return new double[0]; final long l0 = lo; return fillCounts(fr, lo, hi, false); }
        return fillCounts(fr, lo, hi, true);
    }
    private static double[] fillCounts(List<byte[]> fr, long lo, long hi, boolean byCounter) {
        double[] c = new double[(int) (hi - lo + 1) * FS];
        for (byte[] x : fr) { int s = (int) ((byCounter ? ProAnalysis.u32(x, 11) : ProAnalysis.u32(x, 15)) - lo), n = Math.min(500, ProAnalysis.u16(x, 32)); for (int k = 0; k < n; k++) { int b0 = x[34 + 3 * k] & 0xFF, b1 = x[35 + 3 * k] & 0xFF, b2 = x[36 + 3 * k] & 0xFF; int r = ((b0 & 3) << 16) | (b1 << 8) | b2; if ((r & 0x20000) != 0) r -= 0x40000; c[s * FS + k] = r; } }
        return c;
    }
    static double lockIn(double[] c, double fr, double t0, double t1) {
        int a = (int) Math.round(t0 * FS), b = Math.min(c.length, (int) Math.round(t1 * FS)); if (a < 0 || b - a < 50) return Double.NaN; double m = 0; for (int i = a; i < b; i++) m += c[i]; m /= (b - a); double si = 0, sq = 0;
        for (int i = a; i < b; i++) { double ph = 2 * Math.PI * fr * ((i - a) / (double) FS); si += (c[i] - m) * Math.cos(ph); sq += (c[i] - m) * Math.sin(ph); }
        return 2.0 * Math.hypot(si / (b - a), sq / (b - a));
    }

    /** 0.52.2: what the strap itself says about the contact (decode log 157: the simulator attempt of 8 Nov recorded an OPEN electrode, leads on in 4 % and 21 % of the samples and a contact value of
     *  about 350 where a finger gives about 78). share[0] = share of samples with the lead-on flag, share[1] = median of the contact value (lead-off I, frame bytes 1535..), share[2] = 50 Hz amplitude in uV. */
    static double[] contact(List<byte[]> fr) {
        long on = 0, tot = 0; List<Double> iv = new ArrayList<>();
        for (byte[] x : fr) { int n = Math.min(500, ProAnalysis.u16(x, 32)); for (int k = 0; k < n; k++) { if ((x[34 + 3 * k] & 0x80) != 0) on++; tot++; }
            if (x.length >= 1557 && (x[1534] & 0xFF) == 11) for (int k = 0; k < 11; k++) iv.add((double) (short) ((x[1535 + 2 * k] & 0xFF) | (x[1536 + 2 * k] << 8))); }
        double[] c = counts(fr); double hum = Double.NaN; if (c.length >= 10 * FS) hum = lockIn(c, 50.0, Math.max(0, c.length / (double) FS - 10.0), c.length / (double) FS) * ProAnalysis.UV;
        double med = Double.NaN; if (!iv.isEmpty()) { double[] a = toArr(iv); Arrays.sort(a); med = a[a.length / 2]; }
        return new double[] { tot == 0 ? Double.NaN : on / (double) tot, med, hum };
    }
    static boolean contactOpen(double[] c) { return !Double.isNaN(c[0]) && c[0] < 0.6; }
    private static void contactNotes(Result R, double[] c) {
        R.notes.put("simd_lead_on", f(c[0], 3)); R.notes.put("simd_contact_I", f(c[1], 0)); R.notes.put("simd_hum50_uV", f(c[2], 0));
        if (contactOpen(c)) R.card.add(0, "OPEN CONTACT: the strap had its leads on in only " + f(100 * c[0], 0) + " % of the run (contact value " + f(c[1], 0) + "; a finger gives about 78, an open electrode about 350). The result below is not a measurement: fix the wiring and run W first.");
    }
    /** Mode W: a short reading with the wiring in place and NO signal: does the strap see a closed contact? */
    static Result wiring(List<byte[]> fr) {
        Result R = new Result(); R.notes.put("simd_mode", "W"); if (fr == null || fr.size() < 8) { R.card.add("The record is too short for a wiring check."); return R; }
        double[] c = contact(fr); R.notes.put("simd_lead_on", f(c[0], 3)); R.notes.put("simd_contact_I", f(c[1], 0)); R.notes.put("simd_hum50_uV", f(c[2], 0));
        boolean open = contactOpen(c), weak = !open && (c[0] < 0.95 || c[1] > 120);
        R.card.add(open ? "OPEN: the strap does not see a closed contact." : weak ? "WEAK: the contact comes and goes." : "GOOD: the strap sees a steady contact. The direct runs can start.");
        R.card.add("Leads on in " + f(100 * c[0], 0) + " % of the samples; contact value " + f(c[1], 0) + " (a finger gives about 78, an open electrode about 350); 50 Hz pick-up " + f(c[2], 0) + " uV.");
        if (open || weak) R.card.add("Check, in this order: the clip on the clasp (bare metal on the clasp contact, clipped, not taped); the foil on the LEFT index fingertip (the strap arm), pressed flat; the strap snug on the left wrist; the plug fully in.");
        R.ok = true; return R;
    }

    /** Mode D. vfsPkMv = the phone's full-scale peak voltage in mV (NaN when no calibration is stored: amplitudes in counts only). */
    static Result analyseTones(List<byte[]> fr, double vfsPkMv) {
        Result R = new Result(); R.notes.put("simd_mode", "D"); double[] c = counts(fr);
        if (c.length < 60 * FS) { R.card.add("The record is shorter than 60 s: no result."); return R; }
        int nA = (int) ((c.length / (double) FS - 2.0) / 0.1); double[] A = new double[nA]; for (int i = 0; i < nA; i++) { double t = 1.0 + 0.1 * i; A[i] = lockIn(c, 10.0, t - 0.5, t + 0.5); }
        double[] base = Arrays.copyOf(A, Math.min(60, nA)); Arrays.sort(base); double b0 = base[base.length / 2]; int i0 = -1; for (int i = 0; i < nA; i++) if (A[i] > 8 * Math.max(b0, 1e-9) && A[i] > 20) { i0 = i; break; }
        if (i0 < 0) { R.card.add("No 10 Hz tone was found in the record. Was the cable on the strap and the media volume up?"); contactNotes(R, contact(fr)); return R; }
        double[] pl = Arrays.copyOfRange(A, Math.min(nA - 1, i0 + 15), Math.min(nA, i0 + 45)); Arrays.sort(pl); double plateau = pl.length == 0 ? A[i0] : pl[pl.length / 2]; int j = i0; while (j < nA - 1 && A[j] < 0.5 * plateau) j++;      // the 1 s window is half full of tone exactly at the block start
        double s0 = 1.0 + 0.1 * j; R.notes.put("simd_onset_s", f(s0, 2)); boolean cal = !Double.isNaN(vfsPkMv) && vfsPkMv > 0; if (cal) R.notes.put("simd_vfs_mV", f(vfsPkMv, 1));
        double[] ks = new double[5]; double[] amp = new double[4]; int nk = 0; double a10 = Double.NaN;
        for (int i = 0; i < 4; i++) { double st = s0 + 8 * i; amp[i] = lockIn(c, 10.0, st + 1, st + 5); R.notes.put("simd_counts_L" + (int) -LEVEL_DB[i], f(amp[i], 0)); if (cal) { double k = vfsPkMv * 1000.0 * db(LEVEL_DB[i]) / amp[i]; R.notes.put("simd_K_L" + (int) -LEVEL_DB[i], f(k, 5)); ks[nk++] = k; } }
        final int NF = FREQ.length; double[] gain = new double[NF]; double[] af = new double[NF];
        for (int q = 0; q < NF; q++) { double st = s0 + 34 + 8 * q; af[q] = lockIn(c, FREQ[q], st + 1, st + 5); if (FREQ[q] == 10.0) a10 = af[q]; }
        for (int q = 0; q < NF; q++) { gain[q] = af[q] / a10; R.notes.put("simd_gain_" + FREQ_KEY[q], f(gain[q], 3)); }
        if (cal && !Double.isNaN(a10)) ks[nk++] = vfsPkMv * 1000.0 * db(TONE_DB) / a10;
        double lin = Double.NaN; { double r0 = amp[0] / db(LEVEL_DB[0]), mx = 0, mean = 0; for (int i = 0; i < 4; i++) mean += amp[i] / db(LEVEL_DB[i]) / 4; for (int i = 0; i < 4; i++) mx = Math.max(mx, Math.abs(amp[i] / db(LEVEL_DB[i]) / mean - 1) * 100); lin = mx; R.notes.put("simd_linearity_pct", f(lin, 2)); }
        if (cal && nk > 0) { double m = 0; for (int i = 0; i < nk; i++) m += ks[i] / nk; double sd = 0; for (int i = 0; i < nk; i++) sd += (ks[i] - m) * (ks[i] - m); sd = Math.sqrt(sd / nk); R.notes.put("simd_K_uV_per_count", f(m, 5)); R.notes.put("simd_K_sd", f(sd, 5));
            R.card.add("Scale " + f(m, 4) + " uV per count from five 10 Hz blocks (spread " + f(sd, 4) + "); app scale " + f(ProAnalysis.UV, 4) + ": " + String.format(Locale.US, "%+.1f", 100 * (m / ProAnalysis.UV - 1)) + " %"); }
        else R.card.add("No calibration stored: amplitudes are in counts only (" + f(amp[2], 0) + " counts at -54 dBFS). Run M (meter calibration) and analyse again from the Full pull.");
        R.card.add("Linearity over -66 to -48 dBFS: largest deviation " + f(lin, 1) + " %");
        R.card.add("R16 gain against 10 Hz: 0.5 Hz " + f(gain[0], 2) + ", 1 Hz " + f(gain[1], 2) + ", 2 Hz " + f(gain[2], 2) + ", 5 Hz " + f(gain[3], 2) + ", 20 Hz " + f(gain[5], 2) + ", 40 Hz " + f(gain[6], 2) + ", 60 Hz " + f(gain[7], 2) + ", 100 Hz " + f(gain[8], 2) + ", 150 Hz " + f(gain[9], 2) + ", 200 Hz " + f(gain[10], 2) + " (below 2 Hz the phone's own output response is included)");
        R.card.add("These are measurements of this wiring and this phone output; nothing is applied to the app."); contactNotes(R, contact(fr)); R.ok = true; return R;
    }

    private static double[] band(double[] x) { double[] y = ProDsp.filtfilt(x, ProDsp.butterLow(4, 40, FS)); return ProDsp.filtfilt(y, ProDsp.butterHigh2(5, FS)); }

    /** Modes E, F, G. playRelS = seconds from the reading start to the audio start as the phone saw it (NaN: 10 s assumed); the lag is searched +-4 s around it. */
    static Result analyseEcg(Track tr, List<byte[]> fr, double vfsPkMv, double playRelS) {
        Result R = new Result(); R.notes.put("simd_mode", String.valueOf(tr.mode)); double[] c = counts(fr);
        if (c.length < 40 * FS || tr.ecg == null) { R.card.add("The record is shorter than 40 s: no result."); return R; }
        double[] ref = new double[tr.ecg.length]; for (int i = 0; i < ref.length; i++) ref[i] = tr.ecg[i]; double[] rb = band(ref), cb = band(c);
        double exp = Double.isNaN(playRelS) ? AUDIO_AT_S : playRelS; int nRef = rb.length;      // the WHOLE track: a regular rhythm alone repeats every beat and would give several equal answers
        // coarse lag on a 100 Hz grid (10 ms steps), then every 2 ms around the best
        int best = -1; double bestV = -1e300; int lo = (int) ((exp - 4) * FS), hi = (int) ((exp + 4) * FS);
        for (int L = lo; L <= hi; L += 5) { double s = 0; for (int i = 0; i < nRef; i += 5) { int k = L + i; if (k >= 0 && k < cb.length) s += cb[k] * rb[i]; } if (Math.abs(s) > bestV) { bestV = Math.abs(s); best = L; } }
        int best2 = best; bestV = -1e300; for (int L = best - 6; L <= best + 6; L++) { double s = 0; for (int i = 0; i < nRef; i++) { int k = L + i; if (k >= 0 && k < cb.length) s += cb[k] * rb[i]; } if (Math.abs(s) > bestV) { bestV = Math.abs(s); best2 = L; } }
        int lag = best2; R.notes.put("simd_lag_s", f(lag / (double) FS, 3)); boolean cal = !Double.isNaN(vfsPkMv) && vfsPkMv > 0; if (cal) R.notes.put("simd_vfs_mV", f(vfsPkMv, 1));
        List<Double> ks = new ArrayList<>(); StringBuilder low = new StringBuilder();
        for (Seg s : tr.segs) {
            int a = (int) (s.start * FS) + 250, b = (int) (s.end * FS) - 250; double sxy = 0, sxx = 0, syy = 0; int n = 0;
            for (int i = a; i < b; i++) { int k = lag + i; if (k < 0 || k >= cb.length || i >= rb.length) continue; sxy += cb[k] * rb[i]; sxx += rb[i] * rb[i]; syy += cb[k] * cb[k]; n++; }
            if (n < 5 * FS || sxx <= 0 || syy <= 0) { R.notes.put("simd_c_" + s.name, ""); continue; }
            double slope = sxy / sxx, corr = sxy / Math.sqrt(sxx * syy); R.notes.put("simd_c_" + s.name, f(corr, 3)); R.notes.put("simd_slope_" + s.name, f(slope, 1));      // counts per mV of ECG
            if (cal) { double k = vfsPkMv / slope; R.notes.put("simd_K_" + s.name, f(k, 5)); if (corr >= 0.9) ks.add(k); }
            if (corr < 0.9) low.append(s.name).append(' ').append(f(corr, 2)).append("; ");
        }
        if (cal && !ks.isEmpty()) { double[] k = toArr(ks); Arrays.sort(k); double med = k[k.length / 2]; R.notes.put("simd_K_uV_per_count", f(med, 5)); R.notes.put("simd_K_min", f(k[0], 5)); R.notes.put("simd_K_max", f(k[k.length - 1], 5));
            R.card.add("Scale " + f(med, 4) + " uV per count (median of " + k.length + " segments, " + f(k[0], 4) + " to " + f(k[k.length - 1], 4) + "); app scale " + f(ProAnalysis.UV, 4) + ": " + String.format(Locale.US, "%+.1f", 100 * (med / ProAnalysis.UV - 1)) + " %"); }
        else if (!cal) R.card.add("No calibration stored: the scale cannot be given. The match of every segment is still in the notes.");
        R.card.add("The played signal was found " + f(lag / (double) FS, 2) + " s into the reading; " + tr.segs.size() + " segments compared with the strap's record (5-40 Hz).");
        if (low.length() > 0) R.card.add("Segments matching under 0.90: " + low.toString().trim());
        R.card.add("Beat timing, the strap's heart rate and its quality marks against the known beats are worked out from the Full pull. Nothing is applied to the app."); contactNotes(R, contact(fr)); R.ok = true; return R;
    }
}
