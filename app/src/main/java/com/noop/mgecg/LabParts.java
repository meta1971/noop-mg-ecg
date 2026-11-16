package com.noop.mgecg;

// FILE VERSION 0.64.0 (16 Nov 2026): Lab S split into SHORT PARTS, one action and one number each (decode log addendum 233). Pure Java (no Android): generator, ground truth and analysis, tested off the phone.
//   N  meter tone: 5 s silence, 100 Hz at -20 dBFS for 15 s, 5 s silence. NEVER into the strap. One meter number (the app uses the 100 Hz number first).
//   S  scale: one 10 Hz tone at -54 dBFS for 30 s. One reading, one number (uV per count) and its drift over the 30 s.
//   L  level ladder: 10 Hz at -54 (lead), then -66 / -60 / -54 / -48 dBFS, 6 s each: scale at four levels and linearity.
//   H  low tones: 10 Hz lead, then 5, 2, 1, 0.5 Hz at -54 dBFS, then 10 Hz again: gain against 10 Hz, from which an UPPER BOUND of the high-pass corner follows.
//   U  upper tones: 10 Hz lead, then 20, 40, 60, 100, 150, 200 Hz at -54 dBFS, then 10 Hz again: the response above 10 Hz.
// Every part has the same 10 Hz lead so that the analysis finds the start by itself: the PLAY time on the old phone does not matter. Nothing is applied to the app.

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

final class LabParts {
    private LabParts() {}

    static final int FS = SimDirect.FS;
    static final double LEAD_S = 5.0, TAIL_S = 4.0, EDGE_S = 0.002;

    static final class Part { String name; double f, db, start, len; Part(String n, double f, double db, double s, double l) { name = n; this.f = f; this.db = db; start = s; len = l; } }

    static boolean is(char m) { return m == 'S' || m == 'L' || m == 'H' || m == 'U'; }
    static String title(char m) { return m == 'N' ? "Meter tone" : m == 'S' ? "Scale" : m == 'L' ? "Level ladder" : m == 'H' ? "Low tones" : m == 'U' ? "Upper tones" : "?"; }
    static String stepNo(char m) { return m == 'N' ? "2" : m == 'S' ? "4" : m == 'L' ? "5" : m == 'H' ? "6" : m == 'U' ? "7" : "?"; }

    private static double db(double d) { return Math.pow(10.0, d / 20.0); }
    private static double env(double u, double len) { return u < 0 || u >= len ? 0 : u < EDGE_S ? 0.5 * (1 - Math.cos(Math.PI * u / EDGE_S)) : len - u < EDGE_S ? 0.5 * (1 - Math.cos(Math.PI * (len - u) / EDGE_S)) : 1; }

    private static final java.util.HashMap<Character, List<Part>> CACHE = new java.util.HashMap<>();
    /** The segments of a part, times from the start of the audio file (the first LEAD_S seconds are silence). Cached: the generator asks for them once per sample. */
    static synchronized List<Part> parts(char m) {
        List<Part> c = CACHE.get(m); if (c == null) { c = build(m); CACHE.put(m, c); } return c;
    }
    private static List<Part> build(char m) {
        List<Part> l = new ArrayList<>(); double t = LEAD_S;
        if (m == 'S') { l.add(new Part("K54", 10, -54, t, 30)); return l; }
        l.add(new Part("lead", 10, -54, t, 6)); t += 8;
        if (m == 'L') { double[] d = { -66, -60, -54, -48 }; for (double x : d) { l.add(new Part("L" + (int) -x, 10, x, t, 6)); t += 8; } }
        else if (m == 'H') { double[] f = { 5, 2, 1, 0.5 }, n = { 6, 8, 10, 12 }; for (int i = 0; i < f.length; i++) { l.add(new Part("f" + key(f[i]), f[i], -54, t, n[i])); t += n[i] + 2; } l.add(new Part("end10", 10, -54, t, 6)); }
        else if (m == 'U') { double[] f = { 20, 40, 60, 100, 150, 200 }; for (double x : f) { l.add(new Part("f" + key(x), x, -54, t, 6)); t += 8; } l.add(new Part("end10", 10, -54, t, 6)); }
        return l;
    }
    static String key(double f) { return f == Math.rint(f) ? String.valueOf((int) f) : String.valueOf(f).replace('.', 'p'); }

    static double seconds(char m) {
        if (m == 'N') return 25.0;
        List<Part> l = parts(m); Part z = l.get(l.size() - 1); return z.start + z.len + TAIL_S;
    }

    /** Full-scale value of the part at file time t. */
    static double value(char m, double t) {
        if (t < 0) return 0;
        if (m == 'N') return t >= 5 && t < 20 ? db(-20) * env(t - 5, 15) * Math.sin(2 * Math.PI * 100 * (t - 5)) : 0;
        for (Part p : parts(m)) if (t >= p.start && t < p.start + p.len) { double u = t - p.start; return db(p.db) * env(u, p.len) * Math.sin(2 * Math.PI * p.f * u); }
        return 0;
    }

    static SimDirect.Track track(char m) {
        SimDirect.Track tr = new SimDirect.Track(); tr.mode = m; tr.seconds = seconds(m);
        if (m != 'N') for (Part p : parts(m)) { SimDirect.Seg s = new SimDirect.Seg(); s.name = p.name; s.kind = "tone"; s.start = p.start; s.end = p.start + p.len; s.param = p.f; tr.segs.add(s); }
        return tr;
    }

    // ------------------------------------------------------------------ analysis (on counts at 500 Hz)
    private static String f(double v, int d) { return Double.isNaN(v) || Double.isInfinite(v) ? "" : String.format(Locale.US, "%." + d + "f", v); }

    /** Seconds (in the record) at which the first 10 Hz tone starts; NaN when none is found. */
    static double onset(double[] c) {
        int nA = (int) ((c.length / (double) FS - 2.0) / 0.1); if (nA < 70) return Double.NaN;
        double[] A = new double[nA]; for (int i = 0; i < nA; i++) { double t = 1.0 + 0.1 * i; A[i] = SimDirect.lockIn(c, 10.0, t - 0.5, t + 0.5); }
        double[] base = Arrays.copyOf(A, 40); Arrays.sort(base); double b0 = base[20]; int i0 = -1;
        for (int i = 0; i < nA; i++) if (A[i] > 8 * Math.max(b0, 1e-9) && A[i] > 20) { i0 = i; break; }
        if (i0 < 0) return Double.NaN;
        double[] pl = Arrays.copyOfRange(A, Math.min(nA - 1, i0 + 15), Math.min(nA, i0 + 45)); Arrays.sort(pl); double plateau = pl.length == 0 ? A[i0] : pl[pl.length / 2];
        int j = i0; while (j < nA - 1 && A[j] < 0.5 * plateau) j++;
        return 1.0 + 0.1 * j;      // the 1 s window is half on the tone at this point: its centre is the onset
    }

    static SimDirect.Result analyse(char m, double[] c, double vfsPkMv) {
        SimDirect.Result R = new SimDirect.Result(); R.notes.put("simp_mode", String.valueOf(m));
        if (c.length < 30 * FS) { R.card.add("The record is shorter than 30 s: no result. Start the reading, then press PLAY on the old phone within the first 8 seconds."); return R; }
        double s0 = onset(c);
        if (Double.isNaN(s0)) { R.card.add("No 10 Hz tone was found in the record. Was the cable on the strap (not the meter), the old phone's volume at maximum, and PLAY pressed during the reading?"); return R; }
        boolean cal = !Double.isNaN(vfsPkMv) && vfsPkMv > 0; R.notes.put("simp_onset_s", f(s0, 2)); if (cal) R.notes.put("simp_vfs_mV", f(vfsPkMv, 1));
        double app = ProAnalysis.uv(); List<Part> ps = parts(m);
        if (m == 'S') {
            Part p = ps.get(0); double a = s0 + 1.0; double[] w = new double[3]; for (int i = 0; i < 3; i++) w[i] = SimDirect.lockIn(c, 10.0, a + i * 9.0, a + i * 9.0 + 9.0);
            double all = SimDirect.lockIn(c, 10.0, a, a + 27.0); R.notes.put("simp_counts_K54", f(all, 0));
            double drift = 100 * (w[2] / w[0] - 1); R.notes.put("simp_drift_pct", f(drift, 2));
            if (cal) { double k = vfsPkMv * 1000.0 * db(p.db) / all; R.notes.put("simp_K_uV_per_count", f(k, 5)); R.card.add("Scale " + f(k, 4) + " uV per count; the app uses " + f(app, 4) + ": " + String.format(Locale.US, "%+.1f", 100 * (k / app - 1)) + " %. The tone amplitude changed " + String.format(Locale.US, "%+.2f", drift) + " % from the first to the last third of the run."); }
            else R.card.add("No meter number stored: " + f(all, 0) + " counts at -54 dBFS. Do step 2 (the meter number) and the scale follows from this reading.");
        } else {
            java.util.LinkedHashMap<String, Double> amp = new java.util.LinkedHashMap<>();
            for (Part p : ps) { double a = s0 + (p.start - LEAD_S) + 2.0, room = p.len - 2.5; double cyc = Math.max(1, Math.floor(room * p.f)); amp.put(p.name, SimDirect.lockIn(c, p.f, a, a + cyc / p.f)); }
            double lead = amp.get("lead"); R.notes.put("simp_counts_lead", f(lead, 0));
            if (m == 'L') {
                StringBuilder sb = new StringBuilder(); double mean = 0; int n = 0; double[] r = new double[4]; String[] nm = { "L66", "L60", "L54", "L48" }; double[] dbs = { -66, -60, -54, -48 };
                for (int i = 0; i < 4; i++) { double ai = amp.get(nm[i]); R.notes.put("simp_counts_" + nm[i], f(ai, 0)); r[i] = ai / db(dbs[i]); mean += r[i] / 4; if (cal) { double k = vfsPkMv * 1000.0 * db(dbs[i]) / ai; R.notes.put("simp_K_" + nm[i], f(k, 5)); sb.append(nm[i].substring(1)).append(": ").append(f(k, 4)).append("  "); } }
                double mx = 0; for (int i = 0; i < 4; i++) mx = Math.max(mx, Math.abs(r[i] / mean - 1) * 100); R.notes.put("simp_lin_pct", f(mx, 2));
                if (cal) { double k = vfsPkMv * 1000.0 * db(-54) / amp.get("L54"); R.notes.put("simp_K_uV_per_count", f(k, 5)); R.card.add("Scale at -54 dBFS " + f(k, 4) + " uV per count (app " + f(app, 4) + ": " + String.format(Locale.US, "%+.1f", 100 * (k / app - 1)) + " %). By level, uV per count: " + sb.toString().trim()); }
                R.card.add("Linearity over -66 to -48 dBFS: largest deviation " + f(mx, 1) + " % (counts divided by the played amplitude, against their mean).");
            } else {
                double end = amp.get("end10"); double refAmp = 0.5 * (lead + end); double dr = 100 * (end / lead - 1); R.notes.put("simp_end10_vs_lead_pct", f(dr, 2)); StringBuilder sb = new StringBuilder();
                for (Part p : ps) { if (p.name.equals("lead") || p.name.equals("end10")) continue; double g = amp.get(p.name) / refAmp; R.notes.put("simp_gain_" + p.name.substring(1), f(g, 3)); sb.append(f(p.f, p.f < 1 ? 1 : 0)).append(" Hz ").append(f(g, 3)).append(";  "); }
                R.card.add("Gain against 10 Hz: " + sb.toString().trim());
                if (m == 'H') {
                    double g05 = amp.get("f0p5") / refAmp, g1 = amp.get("f1") / refAmp;
                    R.card.add(corner(g05, 0.5, "0.5 Hz") + "\n" + corner(g1, 1.0, "1 Hz"));
                    R.card.add("How to read it: this measures the raw R16 stream recorded here. Earlier work puts its corner at 0.05 Hz or lower (INFERRED), so a gain near 1.00 at 0.5 Hz is expected; the R17 live path has a second-order 0.5 Hz high-pass (gain about 0.71 at 0.5 Hz, 0.97 at 1 Hz). The old phone's own output coupling can only lower the gain, so a corner worked out here is an UPPER BOUND for the strap alone.");
                }
                R.card.add("The closing 10 Hz block differs from the lead by " + String.format(Locale.US, "%+.2f", dr) + " % (drift over the run).");
            }
        }
        R.card.add("These are measurements of this wiring and this phone output; nothing is applied to the app."); R.ok = true; return R;
    }

    /** Corner frequency that would give gain g at f: first order and second order (Butterworth). */
    static String corner(double g, double f0, String at) {
        if (Double.isNaN(g) || g <= 0) return "At " + at + ": no gain value.";
        if (g >= 0.995) return "At " + at + ": gain " + f(g, 3) + " = no measurable attenuation; any corner is below about " + f(f0 * Math.sqrt(1 / (0.99 * 0.99) - 1), 2) + " Hz (first order, gain 0.99 or more).";
        double c1 = f0 * Math.sqrt(1 / (g * g) - 1), c2 = f0 * Math.pow(1 / (g * g) - 1, 0.25);
        return "At " + at + ": gain " + f(g, 3) + " = corner at most " + f(c1, 2) + " Hz if first order, " + f(c2, 2) + " Hz if second order.";
    }

    // ------------------------------------------------------------------ WAV (16-bit mono 48 kHz, the same +-1 LSB triangular dither and clip as the in-app playback)
    static byte[] wavHeader(long samples) {
        long data = samples * 2, riff = 36 + data; byte[] h = new byte[44];
        put(h, 0, "RIFF"); le32(h, 4, riff); put(h, 8, "WAVE"); put(h, 12, "fmt "); le32(h, 16, 16); le16(h, 20, 1); le16(h, 22, 1); le32(h, 24, SimDirect.SR); le32(h, 28, SimDirect.SR * 2L); le16(h, 32, 2); le16(h, 34, 16); put(h, 36, "data"); le32(h, 40, data);
        return h;
    }
    private static void put(byte[] b, int o, String s) { for (int i = 0; i < s.length(); i++) b[o + i] = (byte) s.charAt(i); }
    private static void le32(byte[] b, int o, long v) { for (int i = 0; i < 4; i++) b[o + i] = (byte) (v >> (8 * i)); }
    private static void le16(byte[] b, int o, int v) { b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); }
    /** 16-bit little-endian bytes for n samples from sample index pos of the track of mode m. */
    static void pcm(char m, byte[] out, int n, long pos, java.util.Random rng) {
        float[] s = new float[n]; SimDirect.Track tr = track(m); SimDirect.fill(tr, s, n, pos, rng);
        for (int i = 0; i < n; i++) { int v = (int) Math.round(Math.max(-1.0, Math.min(1.0, s[i])) * 32767.0); out[2 * i] = (byte) v; out[2 * i + 1] = (byte) (v >> 8); }
    }
}
