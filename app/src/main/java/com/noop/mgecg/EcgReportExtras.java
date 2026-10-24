package com.noop.mgecg;

// FILE VERSION 0.1.0 (23 Oct 2026): two report cards built from streams the app already decodes but the HTML report never showed:
//   - the wrist pulse laid on the heartbeat (EcgAux R20, six optical channels at 50 Hz)
//   - wrist movement during the reading (EcgAux R21, 100 Hz)
// Pure Java, same SVG helpers and CSS classes as EcgReport. Never throws; returns "" when the data are not there. Research only.

import java.util.Locale;

final class EcgReportExtras {
    private EcgReportExtras() {}

    private static final int W = EcgReport.W;

    static String cards(EcgR16Analyzer.Result r, EcgR16Analyzer.Strip s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        try { b.append(pulseCard(s)); } catch (Throwable ignored) { }
        try { b.append(motionCard(s)); } catch (Throwable ignored) { }
        return b.toString();
    }

    // ---------------------------------------------------------------- pulse after each heartbeat

    private static String pulseCard(EcgR16Analyzer.Strip s) {
        if (s.pulse == null || s.pulse.length == 0 || s.pulseShow == null || s.pulseShow.length == 0 || s.beats == null || s.beats.length < 20) return "";
        int ch = s.pulseShow[0];
        double[] st = s.pulseStats[ch];
        if (st[7] < 30) return "";
        double[] y = s.pulse[ch];
        final int fs = EcgAux.PULSE_FS;
        final double t0 = -0.2, t1 = 1.0;
        final int nw = (int) Math.round((t1 - t0) * fs) + 1;
        double[] sum = new double[nw];
        int n = 0;
        for (int p : s.beats) {
            double t = p / (double) EcgR16Analyzer.FS;
            int a = (int) Math.floor((t + t0) * fs) - 1, bb = (int) Math.ceil((t + t1) * fs) + 1;
            if (a < 0 || bb >= y.length) continue;
            boolean ok = true;
            for (int i = a; i <= bb && ok; i++) if (Double.isNaN(y[i])) ok = false;
            if (!ok) continue;
            double[] row = new double[nw];
            double mean = 0;
            for (int j = 0; j < nw; j++) {
                double x = (t + t0) * fs + j;
                int i = (int) Math.floor(x);
                double fr = x - i;
                row[j] = y[i] * (1 - fr) + y[i + 1] * fr;
                mean += row[j];
            }
            mean /= nw;
            for (int j = 0; j < nw; j++) sum[j] += row[j] - mean;
            n++;
        }
        if (n < 20) return "";
        double mx = 1e-9;
        for (int j = 0; j < nw; j++) { sum[j] /= n; mx = Math.max(mx, Math.abs(sum[j])); }

        final int H = 160, L = 8, R = W - 8, T = 22, B = H - 30;
        StringBuilder b = new StringBuilder(EcgReport.svgOpen(H, "The pulse at the wrist, averaged over heartbeats and timed from the heartbeat's R wave"));
        double zero = (T + B) / 2.0;
        b.append(EcgReport.line(L, zero, R, zero, "gl"));
        for (int ms = 0; ms <= 1000; ms += 200) {
            double x = L + (ms / 1000.0 + 0.2) / 1.2 * (R - L);
            b.append(EcgReport.line(x, T, x, B, "gl")).append(EcgReport.text(x, H - 10, ms + " ms", "axis", "middle"));
        }
        double xr = L + 0.2 / 1.2 * (R - L);
        b.append(EcgReport.line(xr, T - 4, xr, B, "qt")).append(EcgReport.text(xr, T - 8, "R", "qtlab", "middle"));
        StringBuilder pts = new StringBuilder();
        double peakX = 0, peakY = 0, best = -1;
        for (int j = 0; j < nw; j++) {
            double x = L + j / (double) (nw - 1) * (R - L), v = sum[j] / mx;
            double yy = zero - v * (B - T) / 2.0 * 0.92;
            pts.append(EcgReport.f(x)).append(',').append(EcgReport.f(yy)).append(' ');
            double ms = (t0 + j / (double) fs) * 1000.0;
            if (ms > 100 && sum[j] > best) { best = sum[j]; peakX = x; peakY = yy; }
        }
        b.append("<polyline points=\"").append(pts).append("\" class=\"trace thick\"></polyline>");
        if (!Double.isNaN(st[4]) && st[4] > 0 && st[4] < 900) {
            double xf = L + (st[4] / 1000.0 + 0.2) / 1.2 * (R - L);
            b.append(EcgReport.line(xf, T, xf, B, "ident")).append(EcgReport.text(xf, T + 10, "foot " + Math.round(st[4]) + " ms", "axis", "middle"));
        }
        if (best > 0) b.append(EcgReport.dot(peakX, peakY, 3.2, "mark"));
        b.append("</svg>");

        StringBuilder c = new StringBuilder();
        c.append("<section class=\"card\"><h2>Your pulse after each heartbeat</h2><p class=\"hint\">").append(n)
                .append(" heartbeats lined up on their R wave and the wrist sensor's pulse averaged, so the trace shows when the pulse wave arrives.</p>")
                .append(b).append("<p class=\"cap\">");
        if (!Double.isNaN(st[4]) && !Double.isNaN(st[6]))
            c.append(String.format(Locale.US, "The pulse wave started to rise at the wrist about <b>%.0f ms</b> (give or take %.0f) after the heartbeat's electrical spike, and peaked about <b>%.0f ms</b> after it. ",
                    st[4], Double.isNaN(st[5]) ? 0 : st[5], st[6]));
        c.append("The vertical amber line is the R wave, the dashed line the start of the pulse rise, the dot the pulse peak.</p>")
                .append("<details class=\"why\"><summary>What does this tell me?</summary><p class=\"mut\">It is the time the pulse takes to travel from the heart to your wrist. That delay changes with arm position, temperature, "
                        + "how tight the strap is and blood pressure, so compare it only with your own readings taken the same way. It is not a blood-pressure measurement and cannot diagnose anything. "
                        + "A trace with a clear, repeatable shape also tells you the optical sensor had good contact.</p></details></section>");
        return c.toString();
    }

    // ---------------------------------------------------------------- wrist movement

    private static String motionCard(EcgR16Analyzer.Strip s) {
        if (s.motionMg == null || s.motionMg.length < 10 * EcgAux.MOTION_FS) return "";
        final int fs = EcgAux.MOTION_FS;
        int nSec = s.motionMg.length / fs;
        double[] v = new double[nSec];
        int valid = 0;
        for (int i = 0; i < nSec; i++) {
            double sq = 0; int c = 0;
            for (int k = 0; k < fs; k++) { double m = s.motionMg[i * fs + k]; if (!Double.isNaN(m)) { sq += m * m; c++; } }
            if (c >= fs / 2) { v[i] = Math.sqrt(sq / c); valid++; } else v[i] = Double.NaN;
        }
        if (valid < 10) return "";
        int still = 0, some = 0, lots = 0;
        double top = 60;
        for (double x : v) {
            if (Double.isNaN(x)) continue;
            if (x < 40) still++; else if (x < 150) some++; else lots++;
            top = Math.max(top, x);
        }
        top = Math.min(Math.max(top, 100), 600);
        final int H = 100, T = 8, B = H - 22;
        StringBuilder b = new StringBuilder(EcgReport.svgOpen(H, "Wrist movement in every second of the reading"));
        for (double g : new double[] {40, 150}) {
            double y = B - Math.min(g, top) / top * (B - T);
            b.append(EcgReport.line(0, y, W, y, "gl"));
        }
        double bw = Math.max(1.0, W / (double) nSec);
        for (int i = 0; i < nSec; i++) {
            if (Double.isNaN(v[i])) continue;
            double h = Math.max(1.5, Math.min(v[i], top) / top * (B - T));
            String cls = v[i] < 40 ? "qgreen" : v[i] < 150 ? "qamber" : "qred";
            b.append("<rect x=\"").append(EcgReport.f(i * W / (double) nSec)).append("\" y=\"").append(EcgReport.f(B - h)).append("\" width=\"")
                    .append(EcgReport.f(Math.max(bw - 0.4, 0.8))).append("\" height=\"").append(EcgReport.f(h)).append("\" class=\"").append(cls).append("\"></rect>");
        }
        for (int sec = 0; sec <= nSec; sec += 60) if (sec <= nSec)
            b.append(EcgReport.text(Math.min(Math.max(sec / (double) nSec * W, 8), W - 8), H - 6, sec + " s", "axis", "middle"));
        b.append("</svg>");
        int pct = (int) Math.round(100.0 * still / valid);
        return "<section class=\"card\"><h2>Wrist movement during the reading</h2><p class=\"hint\">One bar per second, from the strap's accelerometer.</p>" + b
                + "<p class=\"cap\">Still for <b>" + pct + "%</b> of the reading" + (some + lots > 0 ? ", some movement in " + some + " s and clear movement in " + lots + " s" : "")
                + ". Green is still, amber is a little movement, red is the wrist clearly moving.</p>"
                + "<details class=\"why\"><summary>Why does this matter?</summary><p class=\"mut\">Wrist movement is the most common reason heartbeats are thrown out of a reading: the electrodes shift and the trace blurs. "
                + "If the red bars line up with the seconds that did not count, resting your forearm on a table and keeping your fingers still will give a cleaner reading next time.</p></details></section>";
    }
}
