package com.noop.mgecg;

// FILE VERSION 0.94.0 (26 Nov 2026): NEW, pure Java (no Android). Splits the whole stored recording of one reading into stretches and says why each one is not clean:
// settling at the start, strap quality 2 / 1 / 0, rail hits, the strap's own fast-recovery windows, no samples, and the pattern "rail, recovery window, then no signal"
// (named a finger lift, INFERRED from that pattern: the strip has no contact flag). Also the recovery-window table (start, length, beats inside) and the wrist-movement seconds.
// Built only from what EcgR16Analyzer.Strip already holds: quality (byte per sample), mv (NaN = no usable signal), flags (1 rail, 2 fast recovery), beats, motionMg.

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class StripSegments {
    private StripSegments() { }

    static final byte CLEAN = 0, SETTLING = 1, FAIR = 2, POOR = 3, NONE = 4, RAIL = 5, RECOVERY = 6, GAP = 7, LIFT = 8;      // LIFT only in reasons, never per sample
    /** Sustained strap quality 3 means: 2 s of samples with at least 90 % clean. Settling is everything not clean before that. */
    static final double SUSTAIN_S = 2.0;
    static final double SUSTAIN_FRAC = 0.9;
    /** A stretch shorter than this is left out of the "why greyed" list (recovery windows are in their own table). */
    static final double MIN_LIST_S = 1.0;
    /** After a rail hit or recovery window, this much no signal makes it a lift. */
    static final double LIFT_GAP_S = 1.0;
    static final int MAX_LINES = 8, MAX_WINDOWS = 12;
    /** Wrist movement: RMS of the second's acceleration change, same cut as the report's "some movement" (EcgReportExtras.motionCard). */
    static final double MOVE_MG = 40.0;

    // ------------------------------------------------------------------ per sample

    /** One kind per sample of {@code mv}. Null arrays are read as "nothing": no flags, no quality (= 0). */
    static byte[] kinds(byte[] quality, double[] mv, byte[] flags, double fs) {
        int n = mv == null ? 0 : mv.length;
        byte[] k = new byte[n];
        boolean hasF = flags != null && flags.length == n, hasQ = quality != null && quality.length == n;
        for (int i = 0; i < n; i++) {
            int f = hasF ? flags[i] : 0, q = hasQ ? quality[i] : 0;
            if (f == 1) k[i] = RAIL;
            else if (f == 2) k[i] = RECOVERY;
            else if (Double.isNaN(mv[i])) k[i] = GAP;
            else k[i] = q >= 3 ? CLEAN : q == 2 ? FAIR : q == 1 ? POOR : NONE;
        }
        int s = settleEnd(k, fs);
        for (int i = 0; i < s && i < n; i++) if (k[i] == FAIR || k[i] == POOR || k[i] == NONE) k[i] = SETTLING;
        return k;
    }

    /** Index of the first sample that starts a sustained clean stretch; 0 when the reading is clean from the start; 0 as well when it never gets there (then nothing is called settling). */
    static int settleEnd(byte[] k, double fs) {
        int n = k.length, need = Math.max(1, (int) Math.round(SUSTAIN_S * fs));
        if (n < need) return 0;
        int[] pre = new int[n + 1];
        for (int i = 0; i < n; i++) pre[i + 1] = pre[i] + (k[i] == CLEAN ? 1 : 0);
        for (int i = 0; i + need <= n; i++) {
            if (k[i] == CLEAN && pre[i + need] - pre[i] >= SUSTAIN_FRAC * need) return i;
        }
        return 0;
    }

    // ------------------------------------------------------------------ stretches

    static final class Seg {
        final byte kind; final double t0, t1;
        Seg(byte kind, double t0, double t1) { this.kind = kind; this.t0 = t0; this.t1 = t1; }
        double len() { return t1 - t0; }
    }

    /** Runs of equal kind, in order. */
    static List<Seg> runs(byte[] k, double fs) {
        List<Seg> out = new ArrayList<>();
        int i = 0, n = k.length;
        while (i < n) {
            int j = i; while (j < n && k[j] == k[i]) j++;
            out.add(new Seg(k[i], i / fs, j / fs));
            i = j;
        }
        return out;
    }

    /** End (seconds) of a lift that starts with the rail or recovery run ending at {@code endIdx}: more rail/recovery samples are skipped, then no signal (GAP or quality 0) for at least LIFT_GAP_S. -1 when it is not one. */
    static double liftEnd(byte[] k, int endIdx, double fs) {
        int n = k.length, i = endIdx;
        while (i < n && (k[i] == RAIL || k[i] == RECOVERY)) i++;
        int j = i; while (j < n && (k[j] == GAP || k[j] == NONE)) j++;
        return (j - i) / fs >= LIFT_GAP_S - 1e-9 ? j / fs : -1.0;
    }

    /** The stretches that are not clean and last at least MIN_LIST_S, in order, with a rail/recovery run plus the no-signal that follows folded into one LIFT. Short rail / recovery pieces go to windows(). */
    static List<Seg> reasons(byte[] k, double fs) {
        List<Seg> raw = runs(k, fs), out = new ArrayList<>();
        int se = settleEnd(k, fs);
        boolean settling = false;
        if (se > 0) for (int i = 0; i < se; i++) if (k[i] == SETTLING) { settling = true; break; }
        double from = 0.0;
        if (settling) { out.add(new Seg(SETTLING, 0.0, se / fs)); from = se / fs; }      // the whole lead-in is one stretch; its rail / recovery pieces are in the window table
        for (int r = 0; r < raw.size(); r++) {
            Seg s = raw.get(r);
            if (s.t1 <= from + 1e-9 || s.kind == CLEAN) continue;
            if (s.kind == RAIL || s.kind == RECOVERY) {
                double le = liftEnd(k, (int) Math.round(s.t1 * fs), fs);
                if (le > 0) {
                    out.add(new Seg(LIFT, s.t0, le));
                    while (r + 1 < raw.size() && raw.get(r + 1).t0 < le - 1e-9) r++;      // swallow the pieces the lift covers
                }
                continue;                                                                      // a plain rail / recovery piece is in the window table
            }
            if (s.len() + 1e-9 >= MIN_LIST_S) out.add(s);
        }
        return out;
    }

    // ------------------------------------------------------------------ recovery windows

    static final class Win { double start, len; int beats; String label = ""; }

    /** The strap's fast-recovery windows (flag 2), in order, with the beats marked inside each. */
    static List<Win> windows(byte[] flags, byte[] k, int[] beats, double fs) {
        List<Win> out = new ArrayList<>();
        if (flags == null) return out;
        int n = flags.length;
        int i = 0;
        while (i < n) {
            if (flags[i] != 2) { i++; continue; }
            int j = i; while (j < n && flags[j] == 2) j++;
            Win w = new Win();
            w.start = i / fs; w.len = (j - i) / fs;
            if (beats != null) for (int b : beats) if (b >= i && b < j) w.beats++;
            if (w.start < 1.0) w.label = "Start-up";
            else if (k != null && k.length == n && liftEnd(k, j, fs) > 0) w.label = "Then no signal";
            else w.label = "Mid-reading";
            out.add(w);
            i = j;
        }
        return out;
    }

    /** One sentence over the table: "2 windows, 0.5 s each. No beat is marked inside either." */
    static String windowSummary(List<Win> ws) {
        if (ws == null || ws.isEmpty()) return "The strap set no fast-recovery window in this reading.";
        int n = ws.size(), withBeats = 0;
        double mn = Double.MAX_VALUE, mx = 0;
        for (Win w : ws) { if (w.beats > 0) withBeats++; mn = Math.min(mn, w.len); mx = Math.max(mx, w.len); }
        String len = Math.abs(mx - mn) < 0.005 ? sec1(mx) + " s each" : sec1(mn) + " to " + sec1(mx) + " s";
        String beats = withBeats == 0 ? (n == 1 ? "No beat is marked inside it." : "No beat is marked inside any of them.")
                : (withBeats == 1 ? "1 holds a marked beat." : withBeats + " hold a marked beat.");
        return n + (n == 1 ? " window, " : " windows, ") + len + ". " + beats;
    }

    // ------------------------------------------------------------------ words

    static String name(byte kind) {
        switch (kind) {
            case CLEAN: return "Clean";
            case SETTLING: return "Settling";
            case FAIR: return "Quality 2";
            case POOR: return "Quality 1";
            case NONE: return "Quality 0";
            case RAIL: return "Rail hit";
            case RECOVERY: return "Recovery window";
            case GAP: return "No signal";
            case LIFT: return "Looks like a finger lift";
            default: return "";
        }
    }

    static String why(byte kind) {
        switch (kind) {
            case CLEAN: return "Strap quality 3. Beats are marked in a clean stretch of 5 s or more.";
            case SETTLING: return "The signal has not reached strap quality 3 yet.";
            case FAIR: return "Strap quality 2 (fair). The trace is shown, no beats are marked.";
            case POOR: return "Strap quality 1 (poor). The trace is shown, no beats are marked.";
            case NONE: return "The strap reports no quality here. No beats are marked.";
            case RAIL: return "The signal hit the amplifier's limit.";
            case RECOVERY: return "The strap's own fast-recovery window after a rail hit. The strap flags it, it is not estimated.";
            case GAP: return "No usable samples here.";
            case LIFT: return "A rail hit or recovery window, then no signal. Inferred from that pattern; this trace has no contact flag.";
            default: return "";
        }
    }

    /** 128 or 128.4: whole seconds without a decimal, otherwise one. */
    static String sec(double t) { return Math.abs(t - Math.rint(t)) < 0.05 ? String.format(Locale.US, "%.0f", Math.rint(t)) : String.format(Locale.US, "%.1f", t); }
    static String sec1(double t) { return String.format(Locale.US, "%.1f", t); }

    /** "9 to 127 s" */
    static String span(double a, double b) { return sec(a) + " to " + sec(b) + " s"; }

    /** One line of the list: the kind it is about (-1 = none, for the "and N more" line) and its text. */
    static final class Line { final int kind; final String text; Line(int kind, String text) { this.kind = kind; this.text = text; } }

    /** The lines of the "why a stretch is greyed" list: settling, the clean total, then at most MAX_LINES other stretches, then a count of what was left out. */
    static List<Line> linesK(byte[] k, double fs) {
        List<Line> out = new ArrayList<>();
        int n = k.length;
        if (n == 0) return out;
        int clean = 0; for (byte b : k) if (b == CLEAN) clean++;
        List<Seg> rs = reasons(k, fs);
        int idx = 0;
        if (!rs.isEmpty() && rs.get(0).kind == SETTLING && rs.get(0).t0 < 0.05) {
            Seg s = rs.get(0);
            out.add(new Line(SETTLING, line(s.t0, s.t1, SETTLING)));
            idx = 1;
        }
        out.add(clean == 0 ? new Line(NONE, "No clean stretch: nothing in this recording reached strap quality 3.")
                : new Line(CLEAN, sec(clean / fs) + " s clean of " + sec(n / fs) + " s (" + Math.round(100.0 * clean / n) + " %). " + why(CLEAN)));
        int shown = 0, skipped = 0;
        for (int r = idx; r < rs.size(); r++) {
            Seg s = rs.get(r);
            if (shown >= MAX_LINES) { skipped++; continue; }
            out.add(new Line(s.kind, line(s.t0, s.t1, s.kind)));
            shown++;
        }
        if (skipped > 0) out.add(new Line(-1, "and " + skipped + " more stretch" + (skipped == 1 ? "" : "es") + "."));
        return out;
    }

    static List<String> lines(byte[] k, double fs) {
        List<String> out = new ArrayList<>();
        for (Line l : linesK(k, fs)) out.add(l.text);
        return out;
    }

    /** What to say when the user taps at time {@code t} (seconds) on a stretch that is not clean; null on a clean one or outside the recording. */
    static String reasonAt(byte[] k, double fs, double t) {
        if (k == null || k.length == 0 || t < 0) return null;
        int n = k.length, i = (int) Math.floor(t * fs);
        if (i < 0 || i >= n || k[i] == CLEAN) return null;
        byte kind = k[i];
        if (kind == SETTLING) return line(0.0, settleEnd(k, fs) / fs, SETTLING);
        if (kind == RAIL || kind == RECOVERY) {
            int a = i, b = i;
            while (a > 0 && (k[a - 1] == RAIL || k[a - 1] == RECOVERY)) a--;
            while (b < n && (k[b] == RAIL || k[b] == RECOVERY)) b++;
            double le = liftEnd(k, b, fs);
            if (le > 0) return line(a / fs, le, LIFT);
        } else if (kind == GAP || kind == NONE) {
            int a = i;
            while (a > 0 && (k[a - 1] == GAP || k[a - 1] == NONE)) a--;
            if (a > 0 && (k[a - 1] == RAIL || k[a - 1] == RECOVERY)) {
                int bs = a - 1;
                while (bs > 0 && (k[bs - 1] == RAIL || k[bs - 1] == RECOVERY)) bs--;
                double le = liftEnd(k, a, fs);
                if (le > 0) return line(bs / fs, le, LIFT);
            }
        }
        int a = i, b = i;
        while (a > 0 && k[a - 1] == kind) a--;
        while (b < n && k[b] == kind) b++;
        return line(a / fs, b / fs, kind);
    }

    private static String line(double a, double b, byte kind) { return span(a, b) + "  \u00b7  " + name(kind) + ". " + why(kind); }

    // ------------------------------------------------------------------ wrist movement

    /** Per second of the reading: 1 when the RMS of that second's motion samples is MOVE_MG or more, 0 when below, -1 when too few samples. {@code motionFs} = EcgAux.MOTION_FS. */
    static byte[] moving(double[] motionMg, int motionFs) {
        if (motionMg == null || motionFs <= 0) return new byte[0];
        int nSec = motionMg.length / motionFs;
        byte[] out = new byte[nSec];
        for (int i = 0; i < nSec; i++) {
            double sq = 0; int c = 0;
            for (int k = 0; k < motionFs; k++) { double m = motionMg[i * motionFs + k]; if (!Double.isNaN(m)) { sq += m * m; c++; } }
            out[i] = c < motionFs / 2 ? (byte) -1 : (byte) (Math.sqrt(sq / c) >= MOVE_MG ? 1 : 0);
        }
        return out;
    }
}
