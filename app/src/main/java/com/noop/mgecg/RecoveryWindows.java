package com.noop.mgecg;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// FILE VERSION 0.91.0 (25 Nov 2026): the strap's fast-recovery windows of one reading, read from the per-sample flags (Strip.flags: 1 = rail, 2 = fast recovery, both taken from the stored frames).
// Pure: no Android classes, so it runs in the plain-Java tests.
final class RecoveryWindows {
    private RecoveryWindows() { }

    static final int FS = 500, LOOKBACK = 300, FR = 250;

    /** One window: first sample, length in samples, first rail sample of the excursion before it (-1 = none seen), heartbeats the detector found inside it. */
    static final class Win {
        int start, len, span, railFirst, beatsInside;
        /** Milliseconds from the first rail sample to the window start; -1 when no rail sample precedes it. */
        int lagMs() { return railFirst < 0 ? -1 : (start - railFirst) * 1000 / FS; }
    }

    /** Runs of flag 2 in the strip. Empty when the strip has no flags. */
    static List<Win> find(EcgR16Analyzer.Strip s) {
        List<Win> out = new ArrayList<>();
        if (s == null || s.flags == null || s.flags.length == 0) return out;
        byte[] f = s.flags;
        int n = f.length, prevEnd = 0, i = 0;
        while (i < n) {
            if (f[i] != 2) { i++; continue; }
            int j = i;
            while (j < n && f[j] == 2) j++;
            int flagged = j - i;
            // A window can run across a short stored record (the samples after its end are simply not there): join the pieces while fewer than FR samples are flagged and only missing samples lie between them.
            while (flagged < FR) {
                int k = j;
                while (k < n && f[k] == 0 && Double.isNaN(s.mv[k]) && k - j <= 500) k++;
                if (k >= n || k == j || f[k] != 2) break;
                int m = k;
                while (m < n && f[m] == 2) m++;
                flagged += m - k; j = m;
            }
            Win w = new Win();
            w.start = i; w.len = flagged; w.span = j - i; w.railFirst = -1;
            for (int k = Math.max(prevEnd, i - LOOKBACK); k < i; k++) if (f[k] == 1) { w.railFirst = k; break; }
            if (s.beats != null) for (int bi : s.beats) if (bi >= i && bi < j) w.beatsInside++;
            out.add(w);
            prevEnd = j; i = j;
        }
        return out;
    }

    static String clock(int sample) {
        int sec = sample / FS;
        return String.format(Locale.US, "%d:%02d", sec / 60, sec % 60);
    }

    /** The Research card. Always returns a card (an empty state says none were flagged), or "" when the strip has no samples at all. */
    static String card(EcgR16Analyzer.Strip s) {
        if (s == null || s.mv == null || s.mv.length == 0) return "";
        List<Win> ws = find(s);
        StringBuilder b = new StringBuilder("<section class=\"card\"><div class=\"row2\"><div class=\"k\">Recovery windows</div><span class=\"pill n\">Flagged by the strap</span></div>");
        b.append("<p class=\"hint\">When the signal reaches the amplifier's limit, the strap blanks the next half second while the amplifier settles. Nothing is measured inside a window.</p>");
        if (ws.isEmpty()) {
            b.append("<p class=\"cap\">None in this reading: the signal never reached the limit long enough to trigger one.</p></section>");
            return b.toString();
        }
        int n = s.mv.length, W = EcgReport.W, H = 34;
        StringBuilder g = new StringBuilder(EcgReport.svgOpen(H, "Where the recovery windows fall along the reading"));
        g.append("<rect x=\"0\" y=\"10\" width=\"").append(W).append("\" height=\"10\" rx=\"5\" style=\"fill:var(--line)\"/>");
        int total = 0;
        for (Win w : ws) {
            double x = (double) w.start / n * W, wd = Math.max(3.0, (double) w.span / n * W);
            g.append("<rect x=\"").append(EcgReport.f(Math.min(x, W - wd))).append("\" y=\"6\" width=\"").append(EcgReport.f(wd)).append("\" height=\"18\" rx=\"3\" style=\"fill:var(--amber)\"/>");
            total += w.len;
        }
        g.append(EcgReport.text(0, 32, "0:00", "axis", "start")).append(EcgReport.text(W, 32, clock(n), "axis", "end")).append("</svg>");
        b.append(g);
        int shown = 0;
        for (Win w : ws) {
            if (shown++ >= 8) break;
            int lag = w.lagMs();
            b.append(EcgReport.row(clock(w.start), String.format(Locale.US, "%.1f s blanked", w.len / (double) FS)
                    + (lag >= 0 ? String.format(Locale.US, ", began %d ms after the signal hit its limit", lag) : ", no limit hit seen just before it")
                    + (w.beatsInside > 0 ? String.format(Locale.US, "; %d beat(s) marked inside", w.beatsInside) : "")));
        }
        if (ws.size() > 8) b.append("<p class=\"cap\">And ").append(ws.size() - 8).append(" more.</p>");
        b.append("<p class=\"cap\">").append(ws.size()).append(ws.size() == 1 ? " window" : " windows").append(", ")
                .append(String.format(Locale.US, "%.1f s", total / (double) FS)).append(" in all. Times count from the first stored sample of the reading. The flags and the limit hits are read from the stored data, not estimated.</p></section>");
        return b.toString();
    }
}
