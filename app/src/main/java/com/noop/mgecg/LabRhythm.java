package com.noop.mgecg;

// FILE VERSION 0.90.0 (25 Nov 2026): RHYTHM REPLAY for the old-phone rig (decode log addendum 459). Pure Java (no Android): short known-rhythm tracks and the overdrive track, truth tables, file names.
// Why: the strap's beat detector, its verdict, events 118 / 119 (n1, n2, A, B, C, D, E), the HRV field and the fast-recovery timer have only ever been seen on live hearts, whose true beats are unknown.
// Here every beat time, amplitude and rate is known. The wiring is the tone rig of 22 Nov (T clip on the clasp, S foil on the LEFT finger, strap on the LEFT wrist: the wearer's own heartbeat is nulled).
//   r  R1 reference rhythm      regular 70 bpm, R = 1 mV nominal, 50 s (one reading = one first window for events 118 / 119)
//   s  R2 half amplitude        regular 70 bpm, R = 0.5 mV nominal, 50 s
//   t  R3 premature beats       70 bpm, every 8th beat 0.35 R-R early, 50 s
//   u  R4 fast rhythm           regular 100 bpm, R = 1 mV nominal, 50 s
//   v  R5 overdrive bursts      10 Hz lead tone, then 7 levels x 4 bursts of ONE 1 Hz cycle (4 / 8.5 / 9.5 / 11 / 14 / 21 / 40 mV nominal = 0.004 .. 0.040 of full scale), alternating start polarity, spaced 6.0004 s (0.2 sample of phase per burst), 10 Hz closing tone
//   E, F, G (SimDirect)         the older scenario tracks, now playable from the old phone as well (WAV export in SimLabSheet)
// Every exported audio file starts with LEAD_S = 1 s of silence. Press PLAY on the old phone within 4 s of tapping Start: the strap's own analysis window is about seconds 7 to 37 of the reading, so the rhythm must already be running by then (the in-app
// internal playback of E, F, G keeps its 10 s lead, SimDirect.AUDIO_AT_S). The analysis finds the lag by itself (OLD_CENTRE_S +- 7 s).
// Nothing here is applied to the app.

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class LabRhythm {
    private LabRhythm() {}

    static final int FS = SimDirect.FS;
    static final char REF = 'r', HALF = 's', ECTOPY = 't', FAST = 'u', OVERDRIVE = 'v';
    /** The five short modes, then the three older ones: the export order (file numbers 1 to 8). */
    static final char[] EXPORT = { REF, HALF, ECTOPY, FAST, OVERDRIVE, 'E', 'F', 'G' };
    static final double LEAD_S = 1.0, OLD_CENTRE_S = 5.0, OLD_HALF_S = 7.0, DUR_S = 50.0, RR = 60.0 / 70.0, MAX_FS_OVERDRIVE = 0.045;
    /** Burst peaks in nominal mV (1 mV = -60 dBFS): 4 stays under the rail (scale from the linear part), the others put the strap at its +-126,976 clip for about 50 to 220 samples per half-cycle. */
    static final double[] OD_LEVEL_MV = { 4, 8.5, 9.5, 11, 14, 21, 40 };
    static final int OD_PER_LEVEL = 4;
    static final double OD_GAP_S = 6.0, OD_PHASE_S = 0.0004, OD_LEAD_AT_S = 2.0, OD_LEAD_LEN_S = 6.0, OD_LEAD_MV = 2.0, OD_FIRST_AT_S = 10.0, OD_FREQ_HZ = 1.0;

    static boolean is(char m) { return m == REF || m == HALF || m == ECTOPY || m == FAST || m == OVERDRIVE; }
    /** True for every mode the old phone can play from an exported file (the five short ones and E, F, G). */
    static boolean plays(char m) { return is(m) || m == 'E' || m == 'F' || m == 'G'; }
    static int number(char m) { for (int i = 0; i < EXPORT.length; i++) if (EXPORT[i] == m) return i + 1; return 0; }
    static String id(char m) { return m == 'E' || m == 'F' || m == 'G' ? String.valueOf(m) : "R" + number(m); }
    static String title(char m) {
        switch (m) {
            case 'r': return "R1 reference rhythm (70 bpm, 1 mV)"; case 's': return "R2 half amplitude (70 bpm, 0.5 mV)"; case 't': return "R3 premature beats (every 8th)";
            case 'u': return "R4 fast rhythm (100 bpm, 1 mV)"; case 'v': return "R5 overdrive bursts"; default: return SimDirect.title(m);
        }
    }
    static String file(char m) {
        switch (m) {
            case 'r': return "LabR_1_REF.wav"; case 's': return "LabR_2_HALF.wav"; case 't': return "LabR_3_ECTOPY.wav"; case 'u': return "LabR_4_FAST.wav"; case 'v': return "LabR_5_OVERDRIVE.wav";
            case 'E': return "LabR_6_E.wav"; case 'F': return "LabR_7_F.wav"; case 'G': return "LabR_8_G.wav"; default: return "LabR_x.wav";
        }
    }
    static String what(char m) {
        switch (m) {
            case 'r': return "Regular 70 bpm, normal size. 50 s of sound, about 1:10 of reading. Gives the strap's events for a known first window.";
            case 's': return "The same rhythm at half size. Shows how the strap's amplitude numbers and its acceptance follow the size.";
            case 't': return "Every 8th beat comes early. Shows which intervals the strap rejects.";
            case 'u': return "Regular 100 bpm. Shows the rate numbers and the high-rate rule.";
            case 'v': return "Slow pulses that push the strap to its limit, 28 of them, up to about 40 mV at 1 V full scale. Times the fast-recovery rule against a known signal. About 3.5 minutes of sound.";
            case 'E': return "Older track: regular, noise at four levels, three jitter levels. 6.5 min.";
            case 'F': return "Older track: size ladder, rate ramp and step, premature beats, pauses, hum, wander. 7.5 min.";
            default: return "Older track: 2 minutes of a real atrial-fibrillation record. The strap's verdict against a known rhythm.";
        }
    }
    /** Planned reading length in ms: 1 s of silence, the old phone started by hand up to 8 s late, plus 4 s of slack. */
    static long planMs(SimDirect.Track t) { return (long) ((LEAD_S + t.seconds + 12.0) * 1000); }

    // ------------------------------------------------------------------ tracks (500 Hz, millivolts, the same build as SimDirect)
    static SimDirect.Track track(char m) {
        SimDirect.load();
        if (m == OVERDRIVE) return overdrive();
        SimDirect.Track tr = new SimDirect.Track(); tr.mode = m; tr.seconds = DUR_S + SimDirect.TAIL_S;
        float[] x = new float[(int) (tr.seconds * FS)];
        SimDirect.Seg s = new SimDirect.Seg(); s.start = 0; s.end = DUR_S;
        double[] bt; double amp = 1.0;
        if (m == REF) { s.name = "REF"; s.kind = "regular"; s.param = 70; bt = SimDirect.regular(DUR_S, 70.0); }
        else if (m == HALF) { s.name = "HALF"; s.kind = "amp"; s.param = 0.5; amp = 0.5; bt = SimDirect.regular(DUR_S, 70.0); }
        else if (m == FAST) { s.name = "FAST"; s.kind = "regular"; s.param = 100; bt = SimDirect.regular(DUR_S, 100.0); }
        else { s.name = "ECTOPY"; s.kind = "ectopy"; s.param = 8; bt = ectopy(DUR_S); }
        SimDirect.place(x, bt, 0.0, amp);
        s.beats = bt; tr.ecg = x; tr.segs.add(s); return tr;
    }
    /** The premature-beat pattern of SimDirect's ECTOPY segment: beat k at 0.4 + k R-R, every 8th one 0.35 R-R early. */
    static double[] ectopy(double dur) {
        List<Double> l = new ArrayList<>(); int K = (int) (dur / RR) + 2;
        for (int k = 0; k < K; k++) { double t = 0.4 + k * RR - ((k > 0 && k % 8 == 0) ? 0.35 * RR : 0); if (t < dur - 0.8) l.add(t); }
        return SimDirect.toArr(l);
    }

    private static void addTone(float[] x, double start, double len, double f, double ampMv) {
        double edge = 0.02;
        for (int i = (int) (start * FS); i < x.length && i < (int) ((start + len) * FS) + 1; i++) {
            double t = i / (double) FS, u = t - start; if (u < 0 || u >= len) continue;
            double e = u < edge ? 0.5 * (1 - Math.cos(Math.PI * u / edge)) : len - u < edge ? 0.5 * (1 - Math.cos(Math.PI * (len - u) / edge)) : 1.0;
            x[i] += (float) (ampMv * e * Math.sin(2 * Math.PI * f * u));
        }
    }
    static String odName(double lv, int k) { return "OD" + String.format(Locale.US, "%.1f", lv).replace(".", "p") + "_" + k; }
    private static SimDirect.Track overdrive() {
        SimDirect.Track tr = new SimDirect.Track(); tr.mode = OVERDRIVE;
        double per = OD_GAP_S + OD_PHASE_S, t = OD_FIRST_AT_S; int levels = OD_LEVEL_MV.length;
        double endAt = OD_FIRST_AT_S + levels * (OD_PER_LEVEL * per + 2.0);
        tr.seconds = endAt + OD_LEAD_LEN_S + SimDirect.TAIL_S; float[] x = new float[(int) (tr.seconds * FS)];
        addTone(x, OD_LEAD_AT_S, OD_LEAD_LEN_S, 10.0, OD_LEAD_MV);
        SimDirect.Seg lead = new SimDirect.Seg(); lead.name = "LEAD"; lead.kind = "tone"; lead.param = 10; lead.start = OD_LEAD_AT_S; lead.end = OD_LEAD_AT_S + OD_LEAD_LEN_S; tr.segs.add(lead);
        for (int li = 0; li < levels; li++) {
            double amp = OD_LEVEL_MV[li];
            for (int k = 0; k < OD_PER_LEVEL; k++) {
                double st = t + k * per; int sign = (k % 2 == 0) ? 1 : -1;
                int i0 = (int) Math.floor(st * FS), i1 = (int) Math.ceil((st + 1.0 / OD_FREQ_HZ) * FS);
                for (int i = Math.max(0, i0); i <= i1 && i < x.length; i++) { double u = i / (double) FS - st; if (u < 0 || u >= 1.0 / OD_FREQ_HZ) continue; x[i] += (float) (sign * amp * Math.sin(2 * Math.PI * OD_FREQ_HZ * u)); }
                SimDirect.Seg s = new SimDirect.Seg(); s.name = odName(amp, k + 1); s.kind = sign > 0 ? "od+" : "od-"; s.param = amp; s.start = st; s.end = st + 1.0 / OD_FREQ_HZ; tr.segs.add(s);
            }
            t += OD_PER_LEVEL * per + 2.0;
        }
        addTone(x, endAt, OD_LEAD_LEN_S, 10.0, OD_LEAD_MV);
        SimDirect.Seg end = new SimDirect.Seg(); end.name = "END"; end.kind = "tone"; end.param = 10; end.start = endAt; end.end = endAt + OD_LEAD_LEN_S; tr.segs.add(end);
        tr.ecg = x; return tr;
    }

    // ------------------------------------------------------------------ truth tables (times in AUDIO FILE time: the file starts with AUDIO_AT_S of silence)
    /** One CSV: type seg / beat, mode, id, segment, kind, param, t0, t1. For seg rows t0 / t1 are the segment start and end; for beat rows t0 is the R apex time and t1 is empty. */
    static String truthCsv() {
        StringBuilder sb = new StringBuilder("type,mode,id,file,segment,kind,param,t0_s,t1_s\n");
        for (char m : EXPORT) {
            SimDirect.Track tr = SimDirect.track(m); double off = LEAD_S;
            sb.append("track,").append(m).append(',').append(id(m)).append(',').append(file(m)).append(",,,,").append(String.format(Locale.US, "0,%.3f", off + tr.seconds)).append('\n');
            for (SimDirect.Seg s : tr.segs) {
                sb.append("seg,").append(m).append(',').append(id(m)).append(',').append(file(m)).append(',').append(s.name).append(',').append(s.kind).append(',').append(Double.isNaN(s.param) ? "" : String.format(Locale.US, "%.4g", s.param)).append(',').append(String.format(Locale.US, "%.4f,%.4f", off + s.start, off + s.end)).append('\n');
                for (double b : s.beats) sb.append("beat,").append(m).append(',').append(id(m)).append(',').append(file(m)).append(',').append(s.name).append(",,,").append(String.format(Locale.US, "%.4f", off + b)).append(",\n");
            }
        }
        return sb.toString();
    }
    /** The played waveform of one mode at 500 Hz in nominal millivolts (file time), for modes whose beats are not tabulated (G, the AF record). */
    static String refCsv(char m) {
        SimDirect.Track tr = SimDirect.track(m); StringBuilder sb = new StringBuilder("time_s,mV_nominal\n");
        if (tr.ecg == null) return sb.toString();
        for (int i = 0; i < tr.ecg.length; i++) sb.append(String.format(Locale.US, "%.3f,%.4f\n", LEAD_S + i / (double) FS, tr.ecg[i]));
        return sb.toString();
    }

    // ------------------------------------------------------------------ WAV (16-bit mono 48 kHz; the file starts with AUDIO_AT_S of silence, same triangular dither as the in-app playback)
    static long samples(SimDirect.Track tr) { return Math.round((LEAD_S + tr.seconds) * SimDirect.SR); }
    static void pcm(SimDirect.Track tr, byte[] out, int n, long pos, java.util.Random rng) {
        long lead = Math.round(LEAD_S * SimDirect.SR), a = pos - lead; float[] s = new float[n];
        if (a >= 0) SimDirect.fill(tr, s, n, a, rng);
        else {
            int z = (int) Math.min(n, -a);
            for (int i = 0; i < z; i++) s[i] = (float) ((rng.nextDouble() - rng.nextDouble()) / 32768.0);
            if (z < n) { float[] t = new float[n - z]; SimDirect.fill(tr, t, n - z, 0, rng); System.arraycopy(t, 0, s, z, n - z); }
        }
        for (int i = 0; i < n; i++) { int v = (int) Math.round(Math.max(-1.0, Math.min(1.0, s[i])) * 32767.0); out[2 * i] = (byte) v; out[2 * i + 1] = (byte) (v >> 8); }
    }

    // ------------------------------------------------------------------ analysis
    /** r, s, t, u, E, F, G: the SimDirect comparison (lag search widened for a hand-started old phone). v: lag from the signed whole-track match, then peak and rail count per burst. */
    static SimDirect.Result analyse(SimDirect.Track tr, java.util.List<byte[]> fr, double vfs, double playRelS, boolean oldPhone) {
        if (tr.mode != OVERDRIVE) return SimDirect.analyseEcg(tr, fr, vfs, playRelS, oldPhone);
        SimDirect.Result R = new SimDirect.Result(); R.notes.put("simd_mode", "v"); double[] c = SimDirect.counts(fr);
        if (c.length < 60 * FS || tr.ecg == null) { R.card.add("The record is shorter than 60 s: no result."); return R; }
        double mean = 0; for (double v : c) mean += v; mean /= c.length; double[] cc = new double[c.length]; for (int i = 0; i < c.length; i++) cc[i] = Math.abs(c[i]) > 1e6 ? 0 : c[i] - mean;
        double exp = oldPhone && Double.isNaN(playRelS) ? OLD_CENTRE_S : Double.isNaN(playRelS) ? SimDirect.AUDIO_AT_S : playRelS, hw = oldPhone && Double.isNaN(playRelS) ? OLD_HALF_S : 5.0;
        int lo = (int) ((exp - hw) * FS), hi = (int) ((exp + hw) * FS), best = -1; double bv = -1e300; int nRef = tr.ecg.length;
        for (int L = lo; L <= hi; L++) { double sum = 0; for (int i = 0; i < nRef; i += 2) { int k = L + i; if (k >= 0 && k < cc.length) sum += cc[k] * tr.ecg[i]; } if (sum > bv) { bv = sum; best = L; } }
        int lag = best; R.notes.put("simd_lag_s", String.format(Locale.US, "%.3f", lag / (double) FS));
        StringBuilder card = new StringBuilder("Overdrive bursts (peak counts, samples at or over the rail flag 126,900 / at the clip 126,976):");
        for (SimDirect.Seg sg : tr.segs) {
            if (!sg.name.startsWith("OD")) continue;
            int a = lag + (int) Math.round(sg.start * FS) - FS / 2, b = lag + (int) Math.round(sg.end * FS) + FS; double pk = 0; int nr = 0, nc = 0;
            for (int i = Math.max(0, a); i < b && i < c.length; i++) { double v = Math.abs(c[i]); if (v > 1e6) continue; if (v > pk) pk = v; if (v >= 126900) nr++; if (v >= 126976) nc++; }
            R.notes.put("simd_od_peak_" + sg.name, String.format(Locale.US, "%.0f", pk)); R.notes.put("simd_od_rail_" + sg.name, String.valueOf(nr)); R.notes.put("simd_od_clip_" + sg.name, String.valueOf(nc));
        }
        for (double lv : OD_LEVEL_MV) {
            double sumPk = 0; int cnt = 0, rail = 0, clip = 0;
            for (int k = 1; k <= OD_PER_LEVEL; k++) { String key = odName(lv, k); String pk = R.notes.get("simd_od_peak_" + key); if (pk == null) continue; sumPk += Double.parseDouble(pk); cnt++; rail += Integer.parseInt(R.notes.get("simd_od_rail_" + key)); clip += Integer.parseInt(R.notes.get("simd_od_clip_" + key)); }
            if (cnt > 0) card.append(String.format(Locale.US, "\n%.1f mV nominal: mean peak %.0f, rail %d, clip %d (%d bursts)", lv, sumPk / cnt, rail, clip, cnt));
        }
        R.card.add(card.toString());
        R.card.add("The played signal was found " + String.format(Locale.US, "%.2f", lag / (double) FS) + " s into the reading. The fast-recovery timing against the burst times is worked out from the Full pull (MG_xref73.py). Nothing is applied to the app.");
        R.ok = true; return R;
    }
}
