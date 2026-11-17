package com.noop.mgecg;

// FILE VERSION 0.69.0 (17 Nov 2026): QUICK TICKS for the scale lab: the rig GATE and the micro-tests G1-G3 (gain), C1-C3 (corner), L1-L2 (ladder) of decode log addendum 258.4, with the checks of 263. Pure Java (no Android): generator plan, analysis and tick rules, tested off the phone.
//   GATE k  silent reading in the exact rig wiring (cable plugged into the old phone, no sound): must show an ordinary ECG before any tone test counts (260.5).
//   ISO p   isolation 1: cable on the strap and the foil, NOT plugged into the old phone.   ISO q   isolation 3: plugged in with a 10 Hz lead-in tone (the tone is subtracted before the ECG check).
//   G1 a    10 Hz -54 dBFS 15 s    G2 b   20 Hz -54 dBFS 15 s    G3 c   10 Hz -48 dBFS 15 s          (gain K in uV per count; needs the stored meter number)
//   C1 d    5 Hz and 2 Hz, 10 s each    C2 e   1 Hz 8 s and 0.5 Hz 12 s, both at -51 dBFS             (relative gain against the 10 Hz lead-in at the same level: an upper bound for the high-pass corner)
//   L1 f    -66 and -54 dBFS 6 s each    L2 g   -60 and -48 dBFS 6 s each, 10 Hz                       (linearity against the lead-in of the same file)
//   C3 h    no audio: lift the finger three times, fit the recovery after each re-contact (decode log 258.1 method)
// Every audio file starts with 5 s of silence and a 6 s 10 Hz lead-in (-54 dBFS; -51 for C1 and C2): the analysis finds the start by itself, and the lead-in is the volume reference of the file.
// Nothing here is applied to the app. A test counts toward a box only when the gate state at its start was PASS or CHECK.

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class LabQuick {
    private LabQuick() {}

    static final int FS = SimDirect.FS;
    static final double LEAD_S = LabParts.LEAD_S, TAIL_S = LabParts.TAIL_S, SETTLE_S = 13.0;
    static final char G1 = 'a', G2 = 'b', G3 = 'c', C1 = 'd', C2 = 'e', L1 = 'f', L2 = 'g', C3 = 'h', GATE = 'k', ISO1 = 'p', ISO3 = 'q';
    static final char[] TESTS = { G1, G2, G3, C1, C2, L1, L2, C3 };
    static final double MAINS_MAX_UV = 7.0, CONTACT_MIN = 0.95, I_MAX = 120, REL_MAX = 0.02, SAT_COUNTS = 120000;

    static boolean is(char m) { return (m >= 'a' && m <= 'h') || m == GATE || m == ISO1 || m == ISO3; }
    static boolean audio(char m) { return (m >= 'a' && m <= 'g') || m == ISO3; }
    static boolean silent(char m) { return m == GATE || m == ISO1 || m == C3; }
    static boolean isGate(char m) { return m == GATE || m == ISO1 || m == ISO3; }
    static String id(char m) {
        switch (m) { case 'a': return "G1"; case 'b': return "G2"; case 'c': return "G3"; case 'd': return "C1"; case 'e': return "C2"; case 'f': return "L1"; case 'g': return "L2"; case 'h': return "C3"; case 'k': return "GATE"; case 'p': return "ISO1"; case 'q': return "ISO3"; default: return "?"; }
    }
    static String title(char m) {
        switch (m) {
            case 'a': return "G1 gain A (10 Hz)"; case 'b': return "G2 gain B (20 Hz)"; case 'c': return "G3 gain C (10 Hz, higher level)";
            case 'd': return "C1 corner A (5 and 2 Hz)"; case 'e': return "C2 corner B (1 and 0.5 Hz)"; case 'f': return "L1 ladder A (-66, -54)"; case 'g': return "L2 ladder B (-60, -48)";
            case 'h': return "C3 lift test (no sound)"; case 'k': return "Rig gate (plugged in, silent)"; case 'p': return "Isolation 1 (not plugged in)"; case 'q': return "Isolation 3 (plugged in, lead-in tone)"; default: return "?";
        }
    }
    static String file(char m) { return m == ISO3 ? "LabQ_GATE_tone.wav" : "LabQ_" + id(m) + ".wav"; }

    // ------------------------------------------------------------------ plan (the same Part objects as LabParts, so the generator is shared)
    private static final HashMap<Character, List<LabParts.Part>> CACHE = new HashMap<>();
    static synchronized List<LabParts.Part> parts(char m) {
        List<LabParts.Part> c = CACHE.get(m); if (c == null) { c = build(m); CACHE.put(m, c); } return c;
    }
    private static List<LabParts.Part> build(char m) {
        List<LabParts.Part> l = new ArrayList<>(); double t = LEAD_S;
        if (silent(m)) return l;
        if (m == ISO3) { l.add(new LabParts.Part("lead", 10, -54, t, 40)); return l; }
        double ld = (m == C1 || m == C2) ? -51 : -54;
        l.add(new LabParts.Part("lead", 10, ld, t, 6)); t += 8;
        if (m == G1) l.add(new LabParts.Part("K54", 10, -54, t, 15));
        else if (m == G2) l.add(new LabParts.Part("f20", 20, -54, t, 15));
        else if (m == G3) l.add(new LabParts.Part("K48", 10, -48, t, 15));
        else if (m == C1) { l.add(new LabParts.Part("f5", 5, -51, t, 10)); t += 12; l.add(new LabParts.Part("f2", 2, -51, t, 10)); }
        else if (m == C2) { l.add(new LabParts.Part("f1", 1, -51, t, 8)); t += 10; l.add(new LabParts.Part("f0p5", 0.5, -51, t, 12)); }
        else if (m == L1) { l.add(new LabParts.Part("L66", 10, -66, t, 6)); t += 8; l.add(new LabParts.Part("L54", 10, -54, t, 6)); }
        else if (m == L2) { l.add(new LabParts.Part("L60", 10, -60, t, 6)); t += 8; l.add(new LabParts.Part("L48", 10, -48, t, 6)); }
        return l;
    }
    /** Length of the audio (or of the silent reading) in seconds. */
    static double seconds(char m) {
        if (m == GATE || m == ISO1) return 45.0; if (m == C3) return 105.0;
        List<LabParts.Part> l = parts(m); LabParts.Part z = l.get(l.size() - 1); return z.start + z.len + TAIL_S;
    }
    /** Planned reading length in ms: audio runs get 12 s more for pressing PLAY on the old phone. */
    static long planMs(char m) { return (long) ((seconds(m) + (audio(m) ? 12.0 : 0.0)) * 1000); }

    static final double[] LIFT_AT = { 15, 45, 75 };
    /** The cue line while the reading runs (seconds since the reading start). */
    static String cue(char m, int s, long planMs) {
        int left = (int) Math.max(0, (planMs / 1000) - s);
        if (m == C3) {
            if (s < 12) return "Lift test: finger on the foil, keep still. First lift in " + (15 - s) + " s.";
            for (double li : LIFT_AT) {
                int a = (int) li;
                if (s >= a - 3 && s < a) return "LIFT the finger in " + (a - s) + " s";
                if (s >= a && s < a + 10) return "FINGER OFF: hold it off, " + (a + 10 - s) + " s";
                if (s >= a + 10 && s < a + 13) return "TOUCH the foil now and keep still";
            }
            return "Finger on, keep still  ·  " + left + " s left";
        }
        return title(m) + ": keep still, no talking, no touching the wiring  ·  " + left + " s left";
    }

    // ------------------------------------------------------------------ decode
    static final class Rec {
        double[] c; byte[] fl; boolean[] have; int secs; long lo; int[] q, res, prog, hr, state; double[] I;
        boolean fr(int i) { return (fl[i] & 0x40) != 0; }
        boolean contact(int i) { return (fl[i] & 0x80) != 0; }
        int last() { for (int i = have.length - 1; i >= 0; i--) if (have[i]) return i; return -1; }
    }
    /** R16 frames to counts, flags and per-second header fields, by the record counter (u32 at 11). The first record of a reading holds about 245 samples: its second has a hole, which stays marked missing. */
    static Rec decode(List<byte[]> fr) {
        if (fr == null || fr.isEmpty()) return null;
        long lo = Long.MAX_VALUE, hi = Long.MIN_VALUE;
        for (byte[] x : fr) { if (x.length < 1560) continue; long c = ProAnalysis.u32(x, 11); if (c < lo) lo = c; if (c > hi) hi = c; }
        if (hi < lo || hi - lo > 3600) return null;
        int n = (int) (hi - lo + 1); Rec R = new Rec(); R.lo = lo; R.secs = n; R.c = new double[n * FS]; R.fl = new byte[n * FS]; R.have = new boolean[n * FS];
        R.q = new int[n]; R.res = new int[n]; R.prog = new int[n]; R.hr = new int[n]; R.state = new int[n]; R.I = new double[n];
        Arrays.fill(R.q, -1); Arrays.fill(R.res, -1); Arrays.fill(R.prog, -1); Arrays.fill(R.hr, -1); Arrays.fill(R.state, -1); Arrays.fill(R.I, Double.NaN);
        for (byte[] x : fr) {
            if (x.length < 1560) continue;
            int s = (int) (ProAnalysis.u32(x, 11) - lo), cnt = Math.min(500, ProAnalysis.u16(x, 32));
            for (int k = 0; k < cnt; k++) {
                int b0 = x[34 + 3 * k] & 0xFF, b1 = x[35 + 3 * k] & 0xFF, b2 = x[36 + 3 * k] & 0xFF; int r = ((b0 & 3) << 16) | (b1 << 8) | b2; if ((r & 0x20000) != 0) r -= 0x40000;
                int idx = s * FS + k; R.c[idx] = r; R.fl[idx] = (byte) b0; R.have[idx] = true;
            }
            R.q[s] = x[21] & 0xFF; R.res[s] = x[23] & 0xFF; R.state[s] = x[24] & 0xFF; R.prog[s] = x[25] & 0xFF; R.hr[s] = x[27] & 0xFF;
            if (x.length >= 1557 && (x[1534] & 0xFF) == 11) { double[] w = new double[11]; for (int k = 0; k < 11; k++) w[k] = (short) ((x[1535 + 2 * k] & 0xFF) | (x[1536 + 2 * k] << 8)); Arrays.sort(w); R.I[s] = w[5]; }
        }
        return R;
    }

    private static String f(double v, int d) { return Double.isNaN(v) || Double.isInfinite(v) ? "" : String.format(Locale.US, "%." + d + "f", v); }
    private static String pct(double v) { return Double.isNaN(v) ? "n/a" : String.format(Locale.US, "%+.1f %%", v); }
    private static double db(double d) { return Math.pow(10.0, d / 20.0); }

    // ------------------------------------------------------------------ one tone window
    static final class Seg {
        String name; double f, dbfs, a, b; int cyc;
        double amp = Double.NaN, h1 = Double.NaN, h2 = Double.NaN, noise = Double.NaN, rel = Double.NaN, k = Double.NaN, drift = Double.NaN, g = Double.NaN, nl = Double.NaN, vsApp = Double.NaN;
        boolean ok; String why = "";
    }
    /** Lock-in amplitude of one tone over whole cycles (2 s after the segment start, to 0.5 s before its end), with the checks of 263: samples present, contact, no fast-recovery or rail, and the noise of the estimate from eight neighbouring frequencies that are orthogonal over the same window. */
    static Seg tone(Rec R, double s0, LabParts.Part p, double vfsPkMv, double appUv) {
        Seg g = new Seg(); g.name = p.name; g.f = p.f; g.dbfs = p.db;
        double a = s0 + (p.start - LEAD_S) + 2.0, room = p.len - 2.5; g.cyc = (int) Math.floor(room * p.f);
        if (g.cyc < 1) { g.why = "too short"; return g; }
        double T = g.cyc / p.f; g.a = a; g.b = a + T; int ia = (int) Math.round(g.a * FS), ib = (int) Math.round(g.b * FS);
        if (ia < 0 || ib > R.c.length || ib - ia < 50) { g.why = "the record ends before this tone"; return g; }
        int miss = 0, off = 0, frs = 0; double mx = 0;
        for (int i = ia; i < ib; i++) { if (!R.have[i]) miss++; else { if (!R.contact(i)) off++; if (R.fr(i)) frs++; mx = Math.max(mx, Math.abs(R.c[i])); } }
        int n = ib - ia;
        g.amp = SimDirect.lockIn(R.c, p.f, g.a, g.b);
        if (miss > 0) g.why = "samples missing (" + miss + ")"; else if (off > 0.02 * n) g.why = "contact lost"; else if (frs > 0) g.why = "fast recovery in the window (the signal hit the rail?)"; else if (mx >= SAT_COUNTS) g.why = "saturated: lower the old phone's volume";
        if (g.cyc >= 4) { int n1 = g.cyc / 2; g.h1 = SimDirect.lockIn(R.c, p.f, g.a, g.a + n1 / p.f); g.h2 = SimDirect.lockIn(R.c, p.f, g.a + n1 / p.f, g.b); if (g.h1 > 0 && T >= 7.0) g.drift = 100.0 * (g.h2 / g.h1 - 1.0); }
        double s2 = 0; int nn = 0;
        for (int j = -5; j <= 5; j++) { if (j > -2 && j < 2) continue; double fj = p.f + j / T; if (fj < 0.25 || fj > 240) continue; double aj = SimDirect.lockIn(R.c, fj, g.a, g.b); if (!Double.isNaN(aj)) { s2 += aj * aj; nn++; } }
        if (nn >= 3) g.noise = Math.sqrt(s2 / nn);
        if (g.amp > 0 && !Double.isNaN(g.noise)) g.rel = g.noise / g.amp;
        if (vfsPkMv > 0 && g.amp > 0) { g.k = vfsPkMv * 1000.0 * db(p.db) / g.amp; g.vsApp = 100.0 * (g.k / appUv - 1.0); }
        if (g.why.isEmpty() && (Double.isNaN(g.rel) || g.rel > REL_MAX)) g.why = "noise error " + (Double.isNaN(g.rel) ? "n/a" : f(100 * g.rel, 1) + " %") + " (over 2 %)";
        g.ok = g.why.isEmpty() && g.amp > 0;
        return g;
    }

    // ------------------------------------------------------------------ the audio tests
    /** Analysis of G1-G3, C1, C2, L1, L2. vfsPkMv = the phone's full-scale peak voltage in mV (NaN: counts only, no K). */
    static SimDirect.Result analyseTest(char m, List<byte[]> fr, double vfsPkMv, double appUv) {
        SimDirect.Result Z = new SimDirect.Result(); Z.notes.put("labq_mode", id(m));
        Rec R = decode(fr);
        if (R == null || R.c.length < 25 * FS) { Z.card.add("The record is shorter than 25 s: no result. Start the reading, then press PLAY on the old phone within the first 8 seconds."); return Z; }
        double s0 = LabParts.onset(R.c);
        if (Double.isNaN(s0)) { Z.card.add("No 10 Hz lead-in was found. Was the cable on the strap (not the meter), the old phone's volume at maximum, effects off, and PLAY pressed during the reading?"); return Z; }
        List<LabParts.Part> ps = parts(m); boolean cal = vfsPkMv > 0;
        Z.notes.put("labq_onset_s", f(s0, 2)); if (cal) Z.notes.put("labq_vfs_mV", f(vfsPkMv, 1));
        Seg lead = tone(R, s0, ps.get(0), vfsPkMv, appUv); Z.notes.put("labq_lead_counts", f(lead.amp, 0)); Z.notes.put("labq_lead_db", f(lead.dbfs, 0));
        Z.notes.put("labq_s0", segNote(lead));
        if (!lead.ok) Z.card.add("The 10 Hz lead-in is not usable (" + lead.why + "): the volume reference of this file is missing, so the results below are relative only and nothing ticks from them.");
        int valid = 0; boolean gainTest = false;
        for (int j = 1; j < ps.size(); j++) {
            LabParts.Part p = ps.get(j); Seg s = tone(R, s0, p, vfsPkMv, appUv);
            if (lead.amp > 0) { s.g = s.amp / lead.amp; s.nl = s.g / db(p.db - lead.dbfs); }
            Z.notes.put("labq_s" + j, segNote(s));
            String lab = f(p.f, p.f < 1 ? 1 : 0) + " Hz at " + f(p.db, 0) + " dBFS";
            if (!s.ok) { Z.card.add(lab + ": NOT USABLE, " + s.why + ". Repeat this test."); continue; }
            if (!lead.ok) continue;
            valid++;
            if (m == G1 || m == G2 || m == G3) { gainTest = true; Z.card.add(lab + ": " + (cal ? "K " + f(s.k, 4) + " uV per count (" + pct(s.vsApp) + " against the app's " + f(appUv, 4) + "), " : "no meter number stored (step 2): ") + "first half to second half " + pct(s.drift) + ", noise error " + f(100 * s.rel, 2) + " %."); }
            else if (m == C1 || m == C2) Z.card.add(lab + ": gain " + f(s.g, 3) + " against the 10 Hz lead-in (noise error " + f(100 * s.rel, 2) + " %). " + LabParts.corner(s.g, p.f, f(p.f, p.f < 1 ? 1 : 0) + " Hz"));
            else Z.card.add(lab + ": " + f(s.g, 4) + " x the lead-in; against a straight line " + pct(100 * (s.nl - 1.0)) + " (noise error " + f(100 * s.rel, 2) + " %).");
        }
        if (gainTest && cal) Z.card.add("K assumes the old phone's output at this frequency is as large as at 100 Hz, where the meter number was read. A frequency or level that does not agree shows in the next test.");
        if (lead.ok && cal && !Double.isNaN(lead.vsApp)) Z.card.add("Lead-in (10 Hz, " + f(lead.dbfs, 0) + " dBFS): " + f(lead.amp, 0) + " counts, " + pct(lead.vsApp) + " against the app scale: if this differs by over 3 % between tests, the old phone's volume or the contact changed.");
        Z.notes.put("labq_valid", String.valueOf(valid)); Z.ok = valid > 0;
        if (valid == 0) Z.card.add("No usable tone in this run: it does not tick anything.");
        return Z;
    }
    private static String segNote(Seg s) { return f(s.f, 2) + "," + f(s.dbfs, 0) + "," + f(s.amp, 1) + "," + f(s.k, 5) + "," + f(s.drift, 2) + "," + f(s.rel, 4) + "," + (s.ok ? "1" : "0") + "," + f(s.g, 4) + "," + f(s.nl, 4); }

    // ------------------------------------------------------------------ the gate
    /** Removes the fitted sinusoid of frequency f (least squares over the whole array). */
    static double[] removeTone(double[] y, double f) {
        int n = y.length; double m = 0; for (double v : y) m += v; m /= n; double si = 0, sq = 0;
        for (int i = 0; i < n; i++) { double ph = 2 * Math.PI * f * i / FS; si += (y[i] - m) * Math.cos(ph); sq += (y[i] - m) * Math.sin(ph); }
        si *= 2.0 / n; sq *= 2.0 / n; double[] o = new double[n];
        for (int i = 0; i < n; i++) { double ph = 2 * Math.PI * f * i / FS; o[i] = y[i] - m - si * Math.cos(ph) - sq * Math.sin(ph); }
        return o;
    }
    /** 50 Hz amplitude in uV (median over 5 s windows, each less the noise of the neighbouring orthogonal frequencies; 0 when nothing is left). NaN with fewer than 2 clean windows. */
    static double mains(Rec R, int from, int to, double uv) {
        List<Double> v = new ArrayList<>();
        for (int a = from; a + 5 * FS <= to; a += 5 * FS) {
            boolean clean = true; for (int i = a; i < a + 5 * FS; i += 5) if (!R.have[i] || R.fr(i)) { clean = false; break; }
            if (!clean) continue;
            double A = SimDirect.lockIn(R.c, 50.0, a / (double) FS, a / (double) FS + 5.0), s2 = 0; int nn = 0;
            for (int j = -5; j <= 5; j++) { if (j > -2 && j < 2) continue; double aj = SimDirect.lockIn(R.c, 50.0 + j / 5.0, a / (double) FS, a / (double) FS + 5.0); if (!Double.isNaN(aj)) { s2 += aj * aj; nn++; } }
            double nz = nn > 0 ? s2 / nn : 0; v.add(uv * Math.sqrt(Math.max(0, A * A - nz)));
        }
        if (v.size() < 2) return Double.NaN; double[] x = new double[v.size()]; for (int i = 0; i < x.length; i++) x[i] = v.get(i); return ProDsp.median(x);
    }

    /** Gate for the variants k (plugged in, silent), p (not plugged in) and q (plugged in, lead-in tone). state PASS, CHECK, FAIL or SHORT in the notes. */
    static SimDirect.Result analyseGate(char variant, List<byte[]> fr, double appUv) {
        SimDirect.Result Z = new SimDirect.Result(); Z.notes.put("labq_mode", id(variant));
        Rec R = decode(fr); int last = R == null ? -1 : R.last();
        if (R == null || last < 30 * FS) { Z.notes.put("labq_state", "SHORT"); Z.card.add("GATE: the record is shorter than 30 s: no result. Start again and keep the reading running to the end."); return Z; }
        int from = (int) (SETTLE_S * FS), to = last + 1; boolean tone = variant == ISO3;
        // contact, fast recovery, rail
        int on = 0, tot = 0, runs = 0; boolean inFr = false; double mx = 0;
        for (int i = from; i < to; i++) { if (!R.have[i]) continue; tot++; if (R.contact(i)) on++; boolean fr1 = R.fr(i); if (fr1 && !inFr) runs++; inFr = fr1; mx = Math.max(mx, Math.abs(R.c[i])); }
        double contact = tot == 0 ? 0 : on / (double) tot;
        double[] iv = new double[R.secs]; int ni = 0; for (int s = (int) SETTLE_S; s < R.secs; s++) if (!Double.isNaN(R.I[s])) iv[ni++] = R.I[s]; double iMed = ni == 0 ? Double.NaN : ProDsp.median(Arrays.copyOf(iv, ni));
        double mains = mains(R, from, to, appUv);
        // the strap's verdict: the last second at progress 100
        int res = -1, shr = -1; for (int s = R.secs - 1; s >= 0; s--) if (R.prog[s] == 100) { res = R.res[s]; break; }
        List<Double> hv = new ArrayList<>(); for (int s = Math.max((int) SETTLE_S, R.secs - 25); s < R.secs; s++) if (R.hr[s] >= 30 && R.hr[s] <= 220) hv.add((double) R.hr[s]);
        if (!hv.isEmpty()) { double[] x = new double[hv.size()]; for (int i = 0; i < x.length; i++) x[i] = hv.get(i); shr = (int) Math.round(ProDsp.median(x)); }
        // our own beats on the longest unbroken stretch (the tone is subtracted first in variant q)
        int bs = -1, bl = 0, cs = -1; for (int i = from; i <= to; i++) { boolean h = i < to && R.have[i]; if (h && cs < 0) cs = i; if (!h && cs >= 0) { if (i - cs > bl) { bl = i - cs; bs = cs; } cs = -1; } }
        double hrApp = Double.NaN, rAmp = Double.NaN, reg = Double.NaN; int nb = 0; double toneAmp = Double.NaN;
        if (bl >= 20 * FS) {
            double[] y = new double[bl]; for (int i = 0; i < bl; i++) y[i] = R.c[bs + i] * appUv;
            if (tone) { toneAmp = SimDirect.lockIn(R.c, 10.0, bs / (double) FS, (bs + bl) / (double) FS); y = removeTone(y, 10.0); }
            int[] pk = ProAnalysis.detectR(y); nb = pk.length;
            if (pk.length >= 15) {
                double[] rr = new double[pk.length - 1]; int m = 0; for (int i = 1; i < pk.length; i++) { double d = (pk[i] - pk[i - 1]) / (double) FS; if (d > 0.3 && d < 2.0) rr[m++] = d; }
                if (m >= 10) { double md = ProDsp.median(Arrays.copyOf(rr, m)); hrApp = 60.0 / md; int near = 0; for (int i = 0; i < m; i++) if (rr[i] >= 0.75 * md && rr[i] <= 1.25 * md) near++; reg = near / (double) m; }
                double[] ra = new double[pk.length]; int q = 0; for (int p : pk) { if (p < 60 || p + 5 >= y.length) continue; double[] b = Arrays.copyOfRange(y, p - 60, p - 25); ra[q++] = y[p] - ProDsp.median(b); } if (q > 0) rAmp = ProDsp.median(Arrays.copyOf(ra, q));
            }
        } else if (tone) toneAmp = SimDirect.lockIn(R.c, 10.0, from / (double) FS, Math.min(to, from + 10 * FS) / (double) FS);
        // the verdicts, in the order they are named
        List<String> bad = new ArrayList<>(); boolean checkOnly = false;
        if (contact < CONTACT_MIN) bad.add("the strap had contact in only " + f(100 * contact, 0) + " % of the samples (needs 95 %)");
        if (!Double.isNaN(iMed) && iMed > I_MAX) bad.add("contact value " + f(iMed, 0) + " (a finger gives about 78, an open electrode about 350)");
        if (runs > 1 || mx >= SAT_COUNTS) bad.add(runs + " fast-recovery runs after the first 13 s" + (mx >= SAT_COUNTS ? " and a signal at the rail" : ""));
        if (Double.isNaN(mains)) bad.add("too few clean 5 s windows to measure the 50 Hz pick-up");
        else if (mains > MAINS_MAX_UV) bad.add("50 Hz pick-up " + f(mains, 1) + " uV (limit " + f(MAINS_MAX_UV, 0) + "; ordinary readings: median 1.4, 95th percentile 5.3 before 8 Nov, 3.3 and 7.1 since)");
        if (tone) { if (Double.isNaN(toneAmp) || toneAmp < 5000) bad.add("the 10 Hz lead-in tone is not reaching the strap (" + f(toneAmp, 0) + " counts; about 44,000 expected at -54 dBFS): was PLAY pressed, volume at maximum?"); }
        else if (res != 1) bad.add(res < 0 ? "the strap has not given a verdict (progress 100 never reached)" : "the strap's verdict is code " + res + " (1 = ordinary ECG)");
        if (Double.isNaN(hrApp)) bad.add("our beat finder found " + nb + " beats (needs 15 or more): no readable ECG");
        else if (!Double.isNaN(rAmp) && rAmp < 300) bad.add((tone ? "after removing the tone, " : "") + "the R wave is only " + f(rAmp, 0) + " uV (ordinary readings 500 to 1,300): no ordinary ECG; the beats found are noise");
        else if (hrApp < 40 || hrApp > 150) bad.add((tone ? "after removing the tone, " : "") + "heart rate " + f(hrApp, 0) + " is outside 40 to 150: no ordinary ECG");
        else if (tone) { if (!Double.isNaN(reg) && reg < 0.7) checkOnly = true; }
        else if (shr > 0) { double d = Math.abs(hrApp - shr), ratio = hrApp / shr; if (d > 3) { if (d <= 8 || Math.abs(ratio - 2) < 0.15 || Math.abs(ratio - 0.5) < 0.08) checkOnly = true; else bad.add("our heart rate " + f(hrApp, 0) + " against the strap's " + shr + " (needs within 3)"); } if (!Double.isNaN(reg) && reg < 0.7) checkOnly = true; }
        else bad.add("the strap gave no heart rate to compare with");
        String state = !bad.isEmpty() ? "FAIL" : checkOnly ? "CHECK" : "PASS";
        Z.notes.put("labq_state", state); Z.notes.put("labq_contact", f(contact, 3)); Z.notes.put("labq_lead_I", f(iMed, 0)); Z.notes.put("labq_fr_runs", String.valueOf(runs)); Z.notes.put("labq_mains_uV", f(mains, 1));
        Z.notes.put("labq_result", String.valueOf(res)); Z.notes.put("labq_hr_strap", String.valueOf(shr)); Z.notes.put("labq_hr_app", f(hrApp, 1)); Z.notes.put("labq_r_uV", f(rAmp, 0)); Z.notes.put("labq_beats", String.valueOf(nb)); Z.notes.put("labq_rr_regular", f(reg, 2));
        Z.notes.put("labq_why", bad.isEmpty() ? (checkOnly ? "heart rate differs from the strap by 3-8, is a double count, or the beat spacing is irregular" : "") : bad.get(0).replace(',', ';').replace('=', ' '));
        String head = state.equals("PASS") ? "GATE PASSED: an ordinary ECG in this wiring. The tone tests can start." : state.equals("CHECK") ? "GATE CHECK: a readable ECG, but our heart rate differs from the strap's by more than 3 (or the beat spacing is irregular). Tests may go ahead; they are marked." : "GATE FAILED: no tone test counts yet.";
        Z.card.add(head);
        for (String b : bad) Z.card.add("• " + b);
        Z.card.add("Contact " + f(100 * contact, 0) + " %, contact value " + f(iMed, 0) + ", 50 Hz " + f(mains, 1) + " uV, fast-recovery runs " + runs + ", strap verdict " + res + ", heart rate strap " + shr + " / ours " + f(hrApp, 0) + ", R wave " + f(rAmp, 0) + " uV (evening readings: 930 to 1,290; daytime 500 to 800).");
        Z.ok = true; return Z;
    }

    // ------------------------------------------------------------------ C3: recovery after a lift
    /** Fit c + s t + A exp(-t/tau) to baseline points (t in s, b in uV) over a grid of tau; returns {tau, A, sse, sseLine} (NaN when no fit). */
    static double[] fitRecovery(double[] t, double[] b) {
        int n = t.length; if (n < 8) return new double[] { Double.NaN, Double.NaN, Double.NaN, Double.NaN };
        double sseLine = lsq(t, b, null)[3]; double bestSse = Double.MAX_VALUE, bestTau = Double.NaN, bestA = Double.NaN; int bi = -1, NG = 60;
        for (int gi = 0; gi < NG; gi++) { double tau = 0.2 * Math.pow(60.0 / 0.2, gi / (double) (NG - 1)); double[] e = new double[n]; for (int i = 0; i < n; i++) e[i] = Math.exp(-t[i] / tau); double[] r = lsq(t, b, e); if (r[3] < bestSse) { bestSse = r[3]; bestTau = tau; bestA = r[2]; bi = gi; } }
        boolean ok = bestSse <= 0.5 * sseLine && Math.abs(bestA) >= 15.0 && bi > 0 && bi < NG - 1;
        return new double[] { ok ? bestTau : Double.NaN, bestA, bestSse, sseLine };
    }
    /** Least squares of b on [1, t, e] (e may be null): {c, s, A, sse}. */
    private static double[] lsq(double[] t, double[] b, double[] e) {
        int p = e == null ? 2 : 3, n = t.length; double[][] M = new double[p][p + 1];
        for (int i = 0; i < n; i++) { double[] x = e == null ? new double[] { 1, t[i] } : new double[] { 1, t[i], e[i] }; for (int r = 0; r < p; r++) { for (int c = 0; c < p; c++) M[r][c] += x[r] * x[c]; M[r][p] += x[r] * b[i]; } }
        for (int c = 0; c < p; c++) { int pv = c; for (int r = c + 1; r < p; r++) if (Math.abs(M[r][c]) > Math.abs(M[pv][c])) pv = r; double[] tmp = M[c]; M[c] = M[pv]; M[pv] = tmp; double d = M[c][c]; if (Math.abs(d) < 1e-12) return new double[] { 0, 0, 0, Double.MAX_VALUE };
            for (int r = 0; r < p; r++) if (r != c) { double fct = M[r][c] / d; for (int k = c; k <= p; k++) M[r][k] -= fct * M[c][k]; } }
        double[] sol = new double[p]; for (int r = 0; r < p; r++) sol[r] = M[r][p] / M[r][r];
        double sse = 0; for (int i = 0; i < n; i++) { double y = sol[0] + sol[1] * t[i] + (e == null ? 0 : sol[2] * e[i]); sse += (b[i] - y) * (b[i] - y); }
        return new double[] { sol[0], sol[1], e == null ? 0 : sol[2], sse };
    }
    /** C3: every re-contact after 3 s or more without contact (from 10 s on): the baseline recovery over 15 s from 0.1 s after the end of its fast-recovery run. */
    static SimDirect.Result analyseLift(List<byte[]> fr, double appUv) {
        SimDirect.Result Z = new SimDirect.Result(); Z.notes.put("labq_mode", "C3");
        Rec R = decode(fr); int last = R == null ? -1 : R.last();
        if (R == null || last < 60 * FS) { Z.card.add("The record is shorter than 60 s: no result."); return Z; }
        List<Double> taus = new ArrayList<>(); int ev = 0, lifts = 0; int i = (int) (10 * FS);
        while (i < last) {
            if (!(R.have[i] && !R.contact(i))) { i++; continue; }
            int s = i; while (i < last && R.have[i] && !R.contact(i)) i++; int e = i;      // contact off from s to e
            if (e - s < 3 * FS || e >= last) continue; lifts++;
            int fs = -1, fe = -1; for (int k = Math.max(0, e - FS / 2); k < Math.min(last, e + FS / 2); k++) if (R.have[k] && R.fr(k)) { fs = k; break; }
            if (fs >= 0) { int k = fs; while (k < last && R.have[k] && R.fr(k)) k++; fe = k; }
            int w0 = (fs >= 0 ? fe : e) + (int) (0.1 * FS), w1 = w0 + 15 * FS; String lab = "lift " + lifts + " (contact back at " + f(e / (double) FS, 0) + " s)";
            if (w1 > last) { Z.notes.put("labq_c3_" + lifts, "window"); Z.card.add(lab + ": the record ends before 15 s of recovery."); continue; }
            boolean clean = true; for (int k = w0; k < w1; k++) if (!R.have[k] || R.fr(k) || !R.contact(k) || Math.abs(R.c[k]) >= SAT_COUNTS) { clean = false; break; }
            if (!clean) { Z.notes.put("labq_c3_" + lifts, "unclean"); Z.card.add(lab + ": not clean (a new lift, a fast-recovery run or the rail inside the 15 s)."); continue; }
            int npt = (15 * FS - 250) / 125 + 1; double[] t = new double[npt], b = new double[npt];
            for (int q = 0; q < npt; q++) { double[] seg = Arrays.copyOfRange(R.c, w0 + q * 125, w0 + q * 125 + 250); t[q] = (q * 125 + 125) / (double) FS; b[q] = ProDsp.median(seg) * appUv; }
            double[] fit = fitRecovery(t, b); ev++;
            if (!Double.isNaN(fit[0]) && Math.abs(fit[1]) > 1900.0) { Z.notes.put("labq_c3_" + lifts, "big"); Z.card.add(lab + ": start offset " + f(Math.abs(fit[1]), 0) + " uV is above 1.9 mV: recovery is faster above that size (decode log 262), so this lift is not used. Lift and touch more gently (foil flat, no press)."); continue; }
            if (Double.isNaN(fit[0])) { Z.notes.put("labq_c3_" + lifts, "nofit"); Z.card.add(lab + ": no single exponential fits (offset " + f(Math.abs(fit[1]), 0) + " uV)."); }
            else { taus.add(fit[0]); double rms = Math.sqrt(fit[2] / npt); Z.notes.put("labq_c3_" + lifts, f(fit[0], 3) + "," + f(fit[1], 0) + "," + f(rms, 1)); Z.card.add(lab + ": recovery time constant " + f(fit[0], 2) + " s, start offset " + f(fit[1], 0) + " uV, residual " + f(rms, 1) + " uV rms (corner " + f(1 / (2 * Math.PI * fit[0]), 3) + " Hz if it were a single high-pass)."); }
        }
        StringBuilder tl = new StringBuilder(); for (double v : taus) { if (tl.length() > 0) tl.append(' '); tl.append(f(v, 3)); }
        Z.notes.put("labq_c3_lifts", String.valueOf(lifts)); Z.notes.put("labq_c3_fits", String.valueOf(taus.size())); Z.notes.put("labq_c3_taus", tl.toString());
        if (lifts == 0) Z.card.add("No lift of 3 s or more was found: the finger stayed on, or the strap kept contact. Repeat and lift the finger off the foil clearly."); else Z.card.add(lifts + " lifts, " + taus.size() + " usable fits. " + (taus.size() >= 2 ? "Spread (largest over smallest time constant): " + f(Collections.max(taus) / Collections.min(taus), 2) + "." : "Two consistent fits are needed to tick the corner box."));
        Z.card.add("Slow baseline wander (breathing, about 0.2 Hz) makes the fitted time constant read LOW (synthetic test: 30 uV of wander turns 3.0 s into 2.2 s), so the corner above is an upper bound, not a point value. The recovery time constant is a measurement of the strap AND the electrode; it equals the amplifier's corner only if the contact adds no time constant of its own (decode log 158.4 found no single time constant in natural steps).");
        Z.ok = !taus.isEmpty(); return Z;
    }

    // ------------------------------------------------------------------ store and ticks
    static String encode(Map<String, String> notes, long ts, String gated) {
        StringBuilder sb = new StringBuilder("ts=" + ts + ";gated=" + gated);
        for (Map.Entry<String, String> e : notes.entrySet()) { String k = e.getKey(); if (!k.startsWith("labq_")) continue; sb.append(';').append(k.substring(5)).append('=').append(e.getValue().replace(';', ' ').replace('=', ' ')); }
        return sb.toString();
    }
    static Map<String, String> decodeStore(String s) {
        Map<String, String> m = new LinkedHashMap<>(); if (s == null) return m;
        for (String part : s.split(";")) { int q = part.indexOf('='); if (q > 0) m.put(part.substring(0, q), part.substring(q + 1)); }
        return m;
    }
    static boolean gatedOk(Map<String, String> m) { String g = m.get("gated"); return "PASS".equals(g) || "CHECK".equals(g); }

    static final class Tick { int state; String name, line; }
    private static double num(String s) { try { return Double.parseDouble(s); } catch (Throwable e) { return Double.NaN; } }
    /** one parsed tone line of a stored test: f, dBFS, amp, K, drift, rel, ok, g, nl */
    private static double[] seg(Map<String, String> m, int j) { String s = m.get("s" + j); if (s == null) return null; String[] p = s.split(",", -1); if (p.length < 9) return null; double[] v = new double[9]; for (int i = 0; i < 9; i++) v[i] = num(p[i]); return v; }

    /** The three boxes from the stored results of the tests (id -> stored map). Only results with a gate state of PASS or CHECK count. */
    static Tick[] ticks(Map<String, Map<String, String>> store, double appUv) {
        Tick gain = new Tick(), corner = new Tick(), ladder = new Tick(); gain.name = "GAIN"; corner.name = "CORNER"; ladder.name = "LADDER";
        // collect
        List<double[]> gk = new ArrayList<>(); List<double[]> lv = new ArrayList<>(); double g2 = Double.NaN, g5 = Double.NaN, g1 = Double.NaN, g05 = Double.NaN; int ungated = 0, nGainRuns = 0;
        for (Map.Entry<String, Map<String, String>> e : store.entrySet()) {
            Map<String, String> m = e.getValue(); if (m == null || m.isEmpty()) continue; String id = e.getKey();
            if (!gatedOk(m)) { ungated++; continue; }
            if (id.equals("C3")) continue;
            double[] lead = seg(m, 0); boolean leadOk = lead != null && lead[6] == 1;
            for (int j = 1; j < 4; j++) {
                double[] s = seg(m, j); if (s == null || s[6] != 1 || !leadOk) continue;
                if ((id.equals("G1") || id.equals("G2") || id.equals("G3")) && !Double.isNaN(s[3])) { gk.add(new double[] { s[0], s[1], s[3], s[4] }); nGainRuns++; }
                if ((id.equals("L1") || id.equals("L2")) && !Double.isNaN(s[3])) gk.add(new double[] { s[0], s[1], s[3], Double.NaN });
                if (id.startsWith("G") || id.startsWith("L")) { if (!Double.isNaN(s[8]) && s[0] == 10) lv.add(new double[] { s[1], s[8], s[5] }); }
                if (id.equals("C1") || id.equals("C2")) { double gg = s[7]; if (s[0] == 2) g2 = gg; else if (s[0] == 5) g5 = gg; else if (s[0] == 1) g1 = gg; else if (s[0] == 0.5) g05 = gg; }
            }
        }
        // GAIN
        if (gk.isEmpty()) { gain.state = 0; gain.line = "Not done. Needs the meter number (step 2), the gate and one of G1, G2, G3."; }
        else {
            double bestD = 1e9; int pi = -1, pj = -1;
            for (int i = 0; i < gk.size(); i++) for (int j = i + 1; j < gk.size(); j++) { double[] a = gk.get(i), b = gk.get(j); if (a[0] == b[0] && a[1] == b[1]) continue; double d = Math.abs(a[2] / b[2] - 1.0); boolean dr = (Double.isNaN(a[3]) || Math.abs(a[3]) <= 1.5) && (Double.isNaN(b[3]) || Math.abs(b[3]) <= 1.5); if (dr && d < bestD) { bestD = d; pi = i; pj = j; } }
            if (pi >= 0 && bestD <= 0.02) { double k = 0.5 * (gk.get(pi)[2] + gk.get(pj)[2]); gain.state = 2; gain.line = "K " + f(k, 4) + " uV per count (" + pct(100 * (k / appUv - 1)) + " against the app's " + f(appUv, 4) + "; within 3 %: " + (Math.abs(k / appUv - 1) <= 0.03 ? "yes" : "no") + "). Two results agree within " + f(100 * bestD, 1) + " %."; }
            else { double[] a = gk.get(gk.size() - 1); gain.state = 1; gain.line = "K " + f(a[2], 4) + " uV per count at " + f(a[0], 0) + " Hz, " + f(a[1], 0) + " dBFS (" + pct(100 * (a[2] / appUv - 1)) + " against the app's " + f(appUv, 4) + "). " + (pi >= 0 ? "The two nearest results differ by " + f(100 * bestD, 1) + " %: repeat one." : "One more at a different level or frequency (G2 or G3) completes it."); }
        }
        // CORNER
        Map<String, String> c3 = store.get("C3"); int c3n = 0; double c3tau = Double.NaN, c3spread = Double.NaN;
        if (c3 != null && gatedOk(c3)) { String tl = c3.get("c3_taus"); if (tl != null && !tl.trim().isEmpty()) { String[] p = tl.trim().split(" "); double[] tv = new double[p.length]; for (int i = 0; i < p.length; i++) tv[i] = num(p[i]); Arrays.sort(tv); c3n = tv.length; c3tau = tv[tv.length / 2]; c3spread = tv[tv.length - 1] / tv[0]; } }
        boolean c1 = !Double.isNaN(g2) || !Double.isNaN(g5), c2 = !Double.isNaN(g05);
        if (c2 && g05 >= 0.95) { corner.state = 2; corner.line = "Gain at 0.5 Hz " + f(g05, 3) + " against 10 Hz: corner at most " + f(0.5 * Math.sqrt(1 / (Math.min(g05, 0.99) * Math.min(g05, 0.99)) - 1), 3) + " Hz (first order, gain capped at 0.99 for the noise; an upper bound: the old phone's own coupling can only lower the gain)."; }
        else if (c3n >= 2 && c3spread <= 1.5) { corner.state = 2; corner.line = "Recovery after lifts: time constant " + f(c3tau, 2) + " s (median of " + c3n + " fits, spread " + f(c3spread, 2) + "): corner at most " + f(1 / (2 * Math.PI * c3tau), 3) + " Hz if it is one high-pass (wander only makes the time constant read low, so this is an upper bound)."; }
        else if (c2) { corner.state = 1; corner.line = "Gain at 0.5 Hz is " + f(g05, 3) + ", under 0.95: the strap and the old phone's own output coupling cannot be told apart at this attenuation; the lift test (C3) measures the strap alone. " + (c1 ? LabParts.corner(c1Best(g2, g5), !Double.isNaN(g2) ? 2.0 : 5.0, !Double.isNaN(g2) ? "2 Hz" : "5 Hz") : ""); }
        else if (c1) { double gg = !Double.isNaN(g2) ? g2 : g5; corner.state = 1; corner.line = LabParts.corner(gg, !Double.isNaN(g2) ? 2.0 : 5.0, !Double.isNaN(g2) ? "2 Hz" : "5 Hz") + " C2 (1 and 0.5 Hz) completes it."; }
        else if (c3n == 1 || (c3 != null && gatedOk(c3) && c3n == 0 && c3.get("c3_lifts") != null)) { corner.state = 1; corner.line = "Lift test: " + c3n + " usable fit" + (c3n == 1 ? " (time constant " + f(c3tau, 2) + " s)" : "s") + "; two consistent fits are needed."; }
        else { corner.state = 0; corner.line = "Not done. C1 then C2 (about 90 s), or the lift test C3."; }
        // LADDER: normalised to the lead-in of the same file (1.000 = straight)
        Map<Double, double[]> byLevel = new java.util.TreeMap<>();
        for (double[] x : lv) { double[] a = byLevel.get(x[0]); if (a == null) byLevel.put(x[0], new double[] { x[1], 1 }); else { a[0] += x[1]; a[1] += 1; } }
        if (byLevel.size() < 2) { ladder.state = byLevel.isEmpty() ? 0 : 1; ladder.line = byLevel.isEmpty() ? "Not done. Fastest: L1 and G3 (three levels, 18 dB, about 80 s)." : "One level so far: L1 and G3 give three."; }
        else {
            double mean = 0; for (double[] a : byLevel.values()) mean += (a[0] / a[1]) / byLevel.size(); double lo = byLevel.keySet().iterator().next(), hi = lo; for (double d : byLevel.keySet()) hi = Math.max(hi, d);
            double worst = 0, worstDb = Double.NaN; for (Map.Entry<Double, double[]> e : byLevel.entrySet()) { double dv = Math.abs((e.getValue()[0] / e.getValue()[1]) / mean - 1.0) * 100; if (dv > worst) { worst = dv; worstDb = e.getKey(); } }
            StringBuilder lvs = new StringBuilder(); for (Map.Entry<Double, double[]> e : byLevel.entrySet()) { if (lvs.length() > 0) lvs.append(", "); lvs.append(f(e.getKey(), 0)).append(": ").append(f((e.getValue()[0] / e.getValue()[1]), 4)); }
            if (byLevel.size() >= 3 && hi - lo >= 12 && worst <= 1.5) { ladder.state = 2; ladder.line = "Linear over " + f(hi - lo, 0) + " dB (" + f(lo, 0) + " to " + f(hi, 0) + " dBFS): largest deviation " + f(worst, 1) + " %. Gain against the lead-in by level: " + lvs + "."; }
            else { ladder.state = 1; ladder.line = (worst > 3 ? "Not linear at " + f(worstDb, 0) + " dBFS (" + f(worst, 1) + " % off the mean). " : "") + byLevel.size() + " levels over " + f(hi - lo, 0) + " dB, largest deviation " + f(worst, 1) + " %" + (byLevel.size() < 3 || hi - lo < 12 ? "; needs 3 levels spanning 12 dB or more (L1 and G3)." : "; each level must be within 1.5 % of the mean.") + " By level: " + lvs + "."; }
        }
        if (ungated > 0) { String w = " (" + ungated + " result" + (ungated == 1 ? "" : "s") + " without a passed gate not counted)"; gain.line += w; }
        return new Tick[] { gain, corner, ladder };
    }
    private static double c1Best(double g2, double g5) { return !Double.isNaN(g2) ? g2 : g5; }

    /** Which step of the isolation ladder to do next, from the stored gate states ("" = not run). */
    static String isolationText(String sK, String sP, String sQ) {
        boolean k = "PASS".equals(sK) || "CHECK".equals(sK), p = "PASS".equals(sP) || "CHECK".equals(sP), q = "PASS".equals(sQ) || "CHECK".equals(sQ);
        if (sK == null || sK.isEmpty()) return "Run the rig gate first (cable plugged into the old phone, no sound, 45 s).";
        if (k && (sQ == null || sQ.isEmpty())) return "Gate passed. Isolation 3 (the lead-in tone) is optional: run it if a tone test later fails the gate-free checks.";
        if (k && q) return "Gate passed, and the ECG survives the lead-in tone.";
        if (k) return "Gate passed, but the ECG does not survive the lead-in tone (isolation 3): the tone path (volume too high, clipping, 50 Hz harmonic) is the problem; lower the old phone's volume one step and repeat isolation 3.";
        if (sP == null || sP.isEmpty()) return "Gate failed. Next: isolation 1 (cable on the strap and the foil, NOT plugged into the old phone).";
        if (!p) return "Isolation 1 also fails: the problem is the strap, clip, foil or wrist contact, not the old phone. Fix the contact first (clip on bare clasp metal, foil flat on the left index fingertip, strap snug), then repeat isolation 1.";
        return "Isolation 1 passes but the plugged-in gate fails: plugging into the old phone breaks the ECG (INFERRED: ground or mains pick-up through the old phone). Try the old phone on battery with its charger unplugged and in airplane mode, then the gate again.";
    }
}
