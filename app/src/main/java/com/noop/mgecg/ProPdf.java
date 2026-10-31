package com.noop.mgecg;

// FILE VERSION 0.23.0 (28 Oct 2026): the pages of the professional ECG report, drawn through a small Pen interface so the same code runs on the phone (PdfDocument) and in the test bench.
// Research use only: not a diagnosis and not a medical device.

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

final class ProPdf {
    private ProPdf() {}

    interface Pen {
        void newPage();                                                                 // A4 landscape, 842 x 595 points
        void line(double x1, double y1, double x2, double y2, double w, int rgb);
        void rect(double x, double y, double w, double h, int fill, int stroke, double sw);        // -1 for none
        void text(String s, double x, double y, double size, int rgb, boolean bold, int align);    // y is the baseline; align 0 left, 1 centre, 2 right
        void poly(double[] xs, double[] ys, int n, double w, int rgb);
        void fill(double[] xs, double[] ys, int n, int rgb, int alpha);
        void dot(double x, double y, double r, int fill, int stroke);
        double width(String s, double size, boolean bold);
    }
    static final int INK = 0x14202E, BLUE = 0x1F5FA8, RED = 0xC0392B, GREEN = 0x2E8B57, GREY = 0x6B7886, LIGHT = 0xEEF3F8, PINK1 = 0xFADADA, PINK2 = 0xF0A8A8, ORANGE = 0xE08A2E, W = 842, H = 595;

    static final class Info { String when = "", fileName = ""; String note = ""; }

    /** Draws every page. h may be null (no H10 recording of the reading). */
    static int pageNo;
    static void render(Pen p, ProAnalysis.Result r, H10Compare.Result h, Info info) {
        pageNo = 0;
        pageAnswers(p, r, h, info); pageSummary(p, r, info); pageStrip(p, r, info); pageBeat(p, r, info); pageHrv(p, r, info); pageBand(p, r, info);
        if (h != null && h.nPaired >= 20) pageH10(p, r, h, info);
        pageConditions(p, r, info); pageDetectors(p, r, info);
        pageEval(p, r, info); pageMethods(p, info); pageRefs(p, info);
    }

    // ------------------------------------------------------------------ helpers
    static void header(Pen p, String title, String sub, Info info, int page) {
        p.newPage(); p.text(title, 36, 52, 20, INK, true, 0); if (sub != null) p.text(sub, 36, 70, 9, GREY, false, 0);
        p.text("WHOOP MG 500 Hz ECG evaluation  |  " + info.when + "  |  research use only: not a diagnosis and not a medical device", 36, 578, 7.5, GREY, false, 0); p.text("page " + (++pageNo), W - 36, 578, 7.5, GREY, false, 2);
    }
    static double para(Pen p, String s, double x, double y, double maxW, double size, double lead, int rgb, boolean bold) {
        for (String blk : s.split("\n")) {
            StringBuilder line = new StringBuilder();
            for (String w : blk.split(" ")) {
                String t = line.length() == 0 ? w : line + " " + w;
                if (p.width(t, size, bold) > maxW && line.length() > 0) { p.text(line.toString(), x, y, size, rgb, bold, 0); y += lead; line = new StringBuilder(w); } else line = new StringBuilder(t);
            }
            if (line.length() > 0) { p.text(line.toString(), x, y, size, rgb, bold, 0); y += lead; }
        }
        return y;
    }
    static double bullet(Pen p, String s, double x, double y, double maxW, double size) { p.dot(x + 3, y - size * 0.32, 1.8, BLUE, -1); return para(p, s, x + 12, y, maxW - 12, size, size * 1.32, INK, false) + 2; }
    static String f0(double v) { return Double.isNaN(v) ? "-" : String.format(Locale.UK, "%.0f", v); }
    static String f1(double v) { return Double.isNaN(v) ? "-" : String.format(Locale.UK, "%.1f", v); }
    /** 0.23.2: the spectrum is only computed from about 64 s of beats; without it LF, HF and the breathing peak are not values (they used to print as 0 and a first-bin artefact). */
    static boolean specOk(ProAnalysis.Hrv h) { return h != null && (h.lf > 0 || h.hf > 0); }
    /** 0.23.2: states where this reading's RMSSD sits against the database, instead of a fixed sentence. */
    static String rmssdSentence(double v) {
        String where = v < 21 ? "below the 5th percentile (21 ms)" : v < 40 ? "between the 5th percentile (21 ms) and the median (40 ms)" : v <= 64 ? "between the median (40 ms) and the 95th percentile (64 ms)" : "above the 95th percentile (64 ms)";
        return "This reading's RMSSD (" + f1(v) + " ms) is " + where + " of the healthy-rhythm database (five-minute windows at 58 to 70 bpm).";
    }

    static final class Ax {
        double x0, y0, w, h, xmin, xmax, ymin, ymax;
        Ax(double x0, double y0, double w, double h, double xmin, double xmax, double ymin, double ymax) { this.x0 = x0; this.y0 = y0; this.w = w; this.h = h; this.xmin = xmin; this.xmax = xmax; this.ymin = ymin; this.ymax = ymax; }
        double X(double v) { return x0 + (v - xmin) / (xmax - xmin) * w; }
        double Y(double v) { return y0 + h - (v - ymin) / (ymax - ymin) * h; }
        void frame(Pen p, String xl, String yl, double xt, double yt, boolean grid) {
            if (grid) { for (double v = Math.ceil(xmin / xt) * xt; v <= xmax + 1e-9; v += xt) p.line(X(v), y0, X(v), y0 + h, 0.3, 0xE3E9F0); for (double v = Math.ceil(ymin / yt) * yt; v <= ymax + 1e-9; v += yt) p.line(x0, Y(v), x0 + w, Y(v), 0.3, 0xE3E9F0); }
            p.line(x0, y0 + h, x0 + w, y0 + h, 0.8, GREY); p.line(x0, y0, x0, y0 + h, 0.8, GREY);
            for (double v = Math.ceil(xmin / xt) * xt; v <= xmax + 1e-9; v += xt) p.text(trim(v), X(v), y0 + h + 10, 7, GREY, false, 1);
            for (double v = Math.ceil(ymin / yt) * yt; v <= ymax + 1e-9; v += yt) p.text(lab(v), x0 - 4, Y(v) + 2.5, 7, GREY, false, 2);
            if (xl != null) p.text(xl, x0 + w / 2, y0 + h + 21, 7.5, INK, false, 1); if (yl != null) p.text(yl, x0, y0 - 6, 7.5, INK, false, 0);
        }
        static String trim(double v) { return v == Math.rint(v) ? String.format(Locale.UK, "%.0f", v) : String.format(Locale.UK, "%.1f", v); }
        void curve(Pen p, double[] xs, double[] ys, double w, int rgb) { double[] a = new double[xs.length], b = new double[xs.length]; int n = 0; for (int i = 0; i < xs.length; i++) { if (xs[i] < xmin || xs[i] > xmax) continue; a[n] = X(xs[i]); b[n] = Math.max(y0 - 2, Math.min(y0 + h + 2, Y(ys[i]))); n++; } if (n > 1) p.poly(a, b, n, w, rgb); }
        int dec = -1;
        String lab(double v) { return dec >= 0 ? String.format(Locale.UK, "%." + dec + "f", v) : trim(v); }
    }
    static double[] grid(int n, double a, double step) { double[] g = new double[n]; for (int i = 0; i < n; i++) g[i] = a + i * step; return g; }

    /** ECG paper: 1 mm = 2.8346 pt; 25 mm/s, 10 mm/mV. Draws the grid for seconds x mV and returns the pen helper mapping. */
    static final double MM = 72.0 / 25.4;
    static void paper(Pen p, double x, double y, double secs, double mvTall) {
        double w = secs * 25 * MM, h = mvTall * 10 * MM; p.rect(x, y, w, h, 0xFFFCFC, -1, 0);
        for (double t = 0; t <= secs * 25 + 1e-6; t += 1) p.line(x + t * MM, y, x + t * MM, y + h, (t % 5 == 0) ? 0.6 : 0.25, (t % 5 == 0) ? PINK2 : PINK1);
        for (double v = 0; v <= mvTall * 10 + 1e-6; v += 1) p.line(x, y + v * MM, x + w, y + v * MM, (v % 5 == 0) ? 0.6 : 0.25, (v % 5 == 0) ? PINK2 : PINK1);
    }
    static void stripRow(Pen p, ProAnalysis.Result r, double x, double y, double startS, double secs, boolean labels) {
        double mvTall = 4.0, baseMv = 2.5; paper(p, x, y, secs, mvTall);
        double sx = 25 * MM / 500.0, sy = 10 * MM / 1000.0; int a = (int) (startS * 500), b = (int) Math.min(r.stripY.length, (startS + secs) * 500); if (b - a < 2) return;
        double[] xs = new double[b - a], ys = new double[b - a]; for (int i = a; i < b; i++) { xs[i - a] = x + (i - a) * sx; ys[i - a] = y + (baseMv * 10 * MM) - r.stripY[i] * sy; }
        p.poly(xs, ys, b - a, 0.8, 0x111111);
        p.poly(new double[] {x + 2, x + 2, x + 2 + 5 * MM, x + 2 + 5 * MM}, new double[] {y + baseMv * 10 * MM, y + baseMv * 10 * MM - 10 * MM, y + baseMv * 10 * MM - 10 * MM, y + baseMv * 10 * MM}, 4, 0.9, 0x111111);
        int last = -1;
        for (int pk : r.stripPeaks) { if (pk < a || pk >= b) continue; double px = x + (pk - a) * sx; p.dot(px, y + baseMv * 10 * MM - r.stripY[pk] * sy - 5, 1.6, RED, -1); if (last >= 0 && labels) p.text(String.valueOf(Math.round((pk - last) * 2.0)), (px + (x + (last - a) * sx)) / 2, y + 9, 6.5, BLUE, false, 1); last = pk; }
    }

    // ------------------------------------------------------------------ pages
    static void pageAnswers(Pen p, ProAnalysis.Result r, H10Compare.Result h, Info info) {
        header(p, "WHOOP MG 500 Hz ECG: evaluation report", "One lead (wrist under the band to the fingertips of the other hand). " + info.when + ": " + r.seconds + " s recorded, " + r.cleanSeconds + " s of quality 3, " + r.beatsUsed + " beats averaged.", info, 1);
        double y = 96, w = W - 72;
        p.text("Reading by reading: what this report found", 36, y, 12.5, BLUE, true, 0); y += 16;
        if (r.hrvNote != null && !r.hrvNote.isEmpty()) y = bullet(p, r.hrvNote, 36, y, w, 9.3);
        if (r.tag != null && !r.tag.isEmpty()) y = bullet(p, "Position or activity tag chosen before Start: " + r.tag + ".", 36, y, w, 9.3);
        y = bullet(p, String.format(Locale.UK, "Rhythm and rate: %.1f bpm, R-R %.0f to %.0f ms, irregularity (normalised RMSSD) %.3f; in the public AF database sinus windows have a median of 0.03 and AF windows 0.29.", r.hrv.hr, r.rrMin, r.rrMax, r.nrmssdMedian), 36, y, w, 9.3);
        y = bullet(p, String.format(Locale.UK, "Intervals on the mean beat: PR %s ms, QRS %s ms, QT %s ms, QTc Fridericia %s ms (Bazett %s, Framingham %s). P %s, R %s, S %s, T %s microvolts. QT is repeatable but only accurate to about 40 ms; the P boundary to about 25 ms.", f0(r.iv.pr), f0(r.iv.qrs), f0(r.iv.qt), f0(r.iv.qtcF), f0(r.iv.qtcB), f0(r.iv.qtcFr), f0(r.iv.pAmp), f0(r.iv.rAmp), f0(r.iv.sAmp), f0(r.iv.tAmp)), 36, y, w, 9.3);
        y = bullet(p, String.format(Locale.UK, "Heart-rate variability: SDNN %.1f ms, RMSSD %.1f ms, breathing %s. Healthy sinus rhythm at 58 to 70 bpm in the Normal Sinus Rhythm Database: RMSSD median 40 ms, 5th percentile 21 ms.", r.hrv.sdnnDetr, r.hrv.rmssd, specOk(r.hrv) ? String.format(Locale.UK, "%.0f a minute", r.hrv.fpk * 60) : "not computed (needs about 3 minutes)"), 36, y, w, 9.3);
        if (r.hrv.removed > 0) y = bullet(p, r.hrv.removed + " interval(s) were more than 20 % away from the running median and left out of the variability figures. Seen by both devices when an H10 was worn, such an interval is a real early or late beat, not noise.", 36, y, w, 9.3);
        y += 6; p.text("Is the signal above 40 Hz useful? (study of 26 to 28 Oct 2026 on this strap, 9 readings)", 36, y, 12.5, BLUE, true, 0); y += 16;
        y = bullet(p, String.format(Locale.UK, "Real: two independent halves of the beats agree in the 40 to 150 Hz band (this reading %.2f, QRS-free control %.2f; the study range 0.93 to 0.98 against -0.10 to 0.26). After averaging this reading's beats the QRS stays %.0f dB above the noise at 40 to 60 Hz and %.0f dB at 100 to 130 Hz.", r.splitQrs, r.splitCtl, r.snrAvg[2], r.snrAvg[5]), 36, y, w, 9.3);
        y = bullet(p, "Small on a single beat: 5 to 7 dB at 40 to 60 Hz, about 2 dB at 60 to 80 Hz and about 0 dB above 80 Hz. Above about 80 Hz the detail only appears after averaging.", 36, y, w, 9.3);
        y = bullet(p, String.format(Locale.UK, "Waveform: the filtered 100 Hz stream (R17) draws the same QRS width (%.0f against %.0f ms) but only %.0f %% of the R height (%.0f against %.0f microvolts). The AHA/ACC/HRS statement asks for a cutoff of at least 150 Hz to measure amplitudes correctly and calls 40 Hz suboptimal.", r.r17Width, r.widthLp40, 100 * r.r17Height / r.rLp40, r.r17Height, r.rLp40), 36, y, w, 9.3);
        y = bullet(p, "Timing and rhythm: no gain. Against a Polar H10 (264 beats) the R-R error was 0.7 ms with a 40 Hz low-pass, 2.5 ms with none and 4.1 ms from whole 100 Hz samples; interpolated 100 Hz samples gave 0.7 ms. Atrial-fibrillation screening from beat times scored an AUC of 0.983 at 250 Hz and at 100 Hz (public AF database) and fell only at 25 Hz.", 36, y, w, 9.3);
        y += 6; p.text("Two modes, 256 Hz for atrial fibrillation?", 36, y, 12.5, BLUE, true, 0); y += 16;
        y = bullet(p, "No strap bandwidth mode exists in what we found: the 40 Hz limit was a display filter and the strap already stores 500 Hz raw. AF is judged from beat timing and the absence of P waves (energy at about 4 to 10 Hz); neither needs 256 Hz.", 36, y, w, 9.3);
        if (h != null && h.nPaired >= 20) y = bullet(p, String.format(Locale.UK, "H10 worn with this reading: %d of %d beats paired, beat-time scatter %.1f ms, R-R difference SD %.1f ms, RMSSD %.1f (strap) against %.1f ms (H10).", h.nPaired, h.nMg, h.timingSdMs, h.rrDiffSdMs, h.rmssdMg, h.rmssdH10), 36, y, w, 9.3);
        else y = bullet(p, "No H10 recording was found for this reading. Switch on \"Record the H10 with each ECG reading\" in Settings to add a chest-strap check page.", 36, y, w, 9.3);
        para(p, info.note, 36, 560, w, 8, 10, GREY, false);
    }

    static void pageSummary(Pen p, ProAnalysis.Result r, Info info) {
        header(p, "ECG evaluation: summary", "Single lead, 500 Hz, " + info.when + "  |  design scale 0.0621 microvolts per count", info, 2);
        double sx0 = 36; double mmw = 250 * MM; int a0 = 0; double startS = 0; if (r.stripY != null) { paper(p, sx0, 84, 10, 4); stripRow(p, r, sx0, 84, 0, 10, true); }
        p.text("25 mm/s, 10 mm/mV, 0.5 to 40 Hz; red marks: R waves; blue: R-R in ms", sx0, 84 + 4 * 10 * MM + 12, 7.5, GREY, false, 0);
        drawBeat(p, r, 36, 224, 430, 215, false);
        // table
        double tx = 478, ty = 238, rowH = 14.2; ProAnalysis.Iv v = r.iv; ProAnalysis.Hrv hv = r.hrv;
        String[][] rows = {
            {"RATE AND RHYTHM", "", ""}, {"Heart rate / R-R", String.format(Locale.UK, "%.1f bpm / %.0f ms", hv.hr, hv.meanRr), "60 to 100 at rest"}, {"Irregularity (nRMSSD)", String.format(Locale.UK, "%.3f", r.nrmssdMedian), "sinus 0.03, AF 0.29"},
            {"INTERVALS (mean beat)", "", ""}, {"PR", ciTxt(v.pr, r.ci[1], "ms"), "120-200; P edge +-25"}, {"QRS", ciTxt(v.qrs, r.ci[0], "ms"), "<120; method based"}, {"QT", ciTxt(v.qt, r.ci[2], "ms"), "accuracy +-40 ms"},
            {"QTc Fridericia", ciTxt(v.qtcF, r.ci[3], "ms"), "<450 M, <460 F"}, {"QTc Bazett", f0(v.qtcB) + " ms", ""}, {"QTc Framingham", f0(v.qtcFr) + " ms", "QT + 154 (1 - R-R)"},
            {"AMPLITUDES", "", ""}, {"P / R / S / T", f0(v.pAmp) + " / " + f0(v.rAmp) + " / " + f0(v.sAmp) + " / " + f0(v.tAmp) + " uV", "scale unverified"}, {"ST at J+60 ms", f0(v.st) + " uV", "scale unverified"},
            {"VARIABILITY", "", ""}, {"SDNN / RMSSD", f1(hv.sdnnDetr) + " / " + f1(hv.rmssd) + " ms", "NSRDB med 40, p5 21"}, {"LF / HF, breathing", specOk(hv) ? f0(hv.lf) + " / " + f0(hv.hf) + " ms2, " + f0(hv.fpk * 60) + "/min" : "not computed", specOk(hv) ? "short reading" : "needs about 3 min"}};
        for (int i = 0; i < rows.length; i++) { double yy = ty + i * rowH; if (rows[i][1].isEmpty()) { p.text(rows[i][0], tx, yy, 8, BLUE, true, 0); p.line(tx, yy + 3, W - 36, yy + 3, 0.5, 0xC8D3DF); } else { p.text(rows[i][0], tx + 6, yy, 8, INK, false, 0); p.text(rows[i][1], tx + 104, yy, 8, INK, true, 0); p.text(rows[i][2], tx + 236, yy, 6.4, GREY, false, 0); } }
        // lower panels
        drawTach(p, r, 36, 552, 250, 68, true); drawPoincare(p, r, 330, 484, 66); drawPsd(p, r, 450, 490, 190, 62);
        p.text("Signal quality", 664, 492, 8, BLUE, true, 0); p.text(r.cleanSeconds + " of " + r.seconds + " s quality 3", 664, 506, 7.5, INK, false, 0); p.text(String.format(Locale.UK, "noise per beat %.0f uV", r.noiseSingle), 664, 518, 7.5, INK, false, 0); p.text(String.format(Locale.UK, "on the mean beat %.1f uV", r.noiseMean), 664, 529, 7.5, INK, false, 0); p.text(r.beatsUsed + " of " + r.beatsTotal + " beats used", 664, 540, 7.5, INK, false, 0);
    }
    static String ciTxt(double v, double[] ci, String u) { return f0(v) + " " + u + (ci != null ? " (" + f0(ci[0]) + " to " + f0(ci[1]) + ")" : ""); }

    static void pageStrip(Pen p, ProAnalysis.Result r, Info info) {
        header(p, "ECG strip: 30 s at clinical scale", "25 mm/s, 10 mm/mV, one lead, 500 Hz sampling, 0.5 to 40 Hz and a 50 Hz notch. Numbers above the trace: R-R intervals in ms.", info, 3);
        if (r.stripY == null) { p.text("Not enough clean signal for a strip.", 36, 120, 11, GREY, false, 0); return; }
        for (int i = 0; i < 3; i++) { double y = 84 + i * (4 * 10 * MM + 22); stripRow(p, r, 36, y, i * 10, 10, true); p.text(String.format(Locale.UK, "%.0f s", r.stripStartS + i * 10), 36, y + 4 * 10 * MM + 10, 7, GREY, false, 0); }
        p.text(String.format(Locale.UK, "Mean beat of the whole reading: HR %.1f bpm, PR %s, QRS %s, QT %s, QTcF %s ms, R %s, T %s uV.", r.hrv.hr, f0(r.iv.pr), f0(r.iv.qrs), f0(r.iv.qt), f0(r.iv.qtcF), f0(r.iv.rAmp), f0(r.iv.tAmp)), 36, 540, 9, INK, false, 0);
    }

    static void drawBeat(Pen p, ProAnalysis.Result r, double x, double y, double w, double h, boolean legend) {
        ProAnalysis.Iv v = r.iv; double[] t = grid(650, -250 * 2, 2); double base = v.base; double[] a = new double[650], lo = new double[650], hi = new double[650], l40 = new double[650];
        for (int i = 0; i < 650; i++) { a[i] = (r.meanFull[i] - base) / 1000; lo[i] = (r.meanFull[i] - r.sd[i] - base) / 1000; hi[i] = (r.meanFull[i] + r.sd[i] - base) / 1000; l40[i] = (r.meanLp40[i] - base) / 1000; }
        Ax ax = new Ax(x + 34, y + 8, w - 40, h - 38, -300, 520, -0.9, 1.15); double[] tt = new double[650]; for (int i = 0; i < 650; i++) tt[i] = (i - 250) * 2.0;
        if (!Double.isNaN(v.pOn)) p.rect(ax.X(v.pOn), ax.y0, ax.X(v.pOff) - ax.X(v.pOn), ax.h, 0xDDF0E3, -1, 0);
        p.rect(ax.X(v.qrsOn), ax.y0, ax.X(v.qrsOff) - ax.X(v.qrsOn), ax.h, 0xF8DCD8, -1, 0); if (!Double.isNaN(v.tEnd)) p.rect(ax.X(v.qrsOff + 60), ax.y0, ax.X(v.tEnd) - ax.X(v.qrsOff + 60), ax.h, 0xE0ECF8, -1, 0);
        ax.frame(p, "ms from the R apex", "millivolts", 100, 0.25, false);
        int i0 = 0, i1 = 649; while (tt[i0] < -300) i0++; while (tt[i1] > 520) i1--; int m = i1 - i0 + 1; double[] px = new double[2 * m], py = new double[2 * m]; for (int i = 0; i < m; i++) { px[i] = ax.X(tt[i0 + i]); py[i] = ax.Y(hi[i0 + i]); px[2 * m - 1 - i] = ax.X(tt[i0 + i]); py[2 * m - 1 - i] = ax.Y(lo[i0 + i]); } p.fill(px, py, 2 * m, 0x8A96A3, 70);
        ax.curve(p, tt, a, 1.1, INK); p.line(ax.x0, ax.Y(0), ax.x0 + ax.w, ax.Y(0), 0.4, 0xA0AAB5);
        if (!Double.isNaN(v.pOn)) { p.text("P", ax.X(v.pPeak), ax.Y(0.2), 10, GREEN, true, 1); }
        p.text("R", ax.X(0), ax.Y(1.06), 10, RED, true, 1); if (!Double.isNaN(v.tPeak)) p.text("T", ax.X(v.tPeak), ax.Y(0.26), 10, BLUE, true, 1);
        double yb = -0.38;
        if (!Double.isNaN(v.pr)) arrow(p, ax, v.pOn, v.qrsOn, yb, "PR " + f0(v.pr), GREEN);
        arrow(p, ax, v.qrsOn, v.qrsOff, yb - 0.17, "QRS " + f0(v.qrs), RED);
        if (!Double.isNaN(v.tEnd)) arrow(p, ax, v.qrsOn, v.tEnd, yb - 0.34, "QT " + f0(v.qt) + " ms (QTcF " + f0(v.qtcF) + ")", BLUE);
        if (legend) { p.text("black: mean beat 0.5 to 250 Hz, grey band: +-1 SD of " + r.beatsUsed + " beats, blue: low-passed at 40 Hz", ax.x0, y + h - 2, 7.5, GREY, false, 0); ax.curve(p, tt, l40, 0.9, BLUE); }
    }
    static void arrow(Pen p, Ax ax, double x0, double x1, double y, String txt, int rgb) {
        double a = ax.X(x0), b = ax.X(x1), yy = ax.Y(y); p.line(a, yy, b, yy, 1, rgb); p.line(a, yy - 3, a, yy + 3, 1, rgb); p.line(b, yy - 3, b, yy + 3, 1, rgb); p.text(txt, (a + b) / 2, yy + 11, 8, rgb, true, 1);
    }
    static void pageBeat(Pen p, ProAnalysis.Result r, Info info) {
        header(p, "Mean beat, intervals and amplitudes", "Mean of " + r.beatsUsed + " aligned beats; confidence ranges from 300 bootstrap resamples of the beats.", info, 4);
        drawBeat(p, r, 36, 80, W - 72, 300, true);
        ProAnalysis.Iv v = r.iv; String[][] rows = {{"Measure", "Value (95 % range)", "Usual range", "Notes"},
            {"PR interval", ciTxt(v.pr, r.ci[1], "ms"), "120 to 200 ms", "P onset (15 % of the P height) to QRS onset; the P wave (" + f0(v.pAmp) + " uV) is small against " + f0(r.noiseSingle) + " uV of noise per beat."},
            {"QRS duration", ciTxt(v.qrs, r.ci[0], "ms"), "under 120 ms", "Slope threshold 5 % of the maximum (70 ms at 8 %); method dependent."},
            {"QT interval", ciTxt(v.qt, r.ci[2], "ms"), "depends on rate", "QRS onset to the tangent T end; repeatable, but absolute accuracy about 40 ms (QT Database test)."},
            {"QTc Fridericia", ciTxt(v.qtcF, r.ci[3], "ms"), "under 450 men, 460 women", "QT / R-R^(1/3); Bazett " + f0(v.qtcB) + " ms, Framingham " + f0(v.qtcFr) + " ms (the heart-rate recovery of 29 Oct favoured Fridericia and Framingham over Bazett)."},
            {"ST at J+60 ms", f0(v.st) + " uV", "about 0", "Relative to the end of the PR segment; the 0.5 Hz zero-phase filter matched a 0.05 Hz reference within 1 uV."},
            {"P / R / S / T", f0(v.pAmp) + " / " + f0(v.rAmp) + " / " + f0(v.sAmp) + " / " + f0(v.tAmp) + " uV", "wrist lead", "S = lowest point within 80 ms after R. Scale is the design value, not checked with a calibrator."}};
        double ty = 410; double[] cx = {36, 150, 290, 420};
        for (int i = 0; i < rows.length; i++) { if (i == 0) p.rect(36, ty - 11, W - 72, 15, LIGHT, -1, 0); for (int j = 0; j < 4; j++) para(p, rows[i][j], cx[j] + 3, ty, j < 3 ? cx[j + 1] - cx[j] - 6 : W - 36 - cx[3] - 6, 7.8, 9, i == 0 ? INK : (j == 3 ? GREY : INK), i == 0 || j == 1); ty += (i == 0 ? 17 : 20); p.line(36, ty - 12, W - 36, ty - 12, 0.3, 0xC8D3DF); }
    }

    static void drawTach(Pen p, ProAnalysis.Result r, double x, double y, double w, double h, boolean small) {
        double[] rr = r.hrv.rrAll; double[] t = new double[rr.length]; double acc = 0; for (int i = 0; i < rr.length; i++) { acc += rr[i] / 1000; t[i] = acc; }
        double lo = 1e9, hi = -1e9; for (double v : rr) { lo = Math.min(lo, v); hi = Math.max(hi, v); } lo = Math.floor(lo / 10) * 10 - 10; hi = Math.ceil(hi / 10) * 10 + 10;
        Ax ax = new Ax(x + 28, y - h, w - 34, h, 0, acc, lo, hi); ax.frame(p, small ? null : "time (s)", small ? "RR ms" : "R-R interval (ms)", Math.max(10, Math.round(acc / 6 / 10) * 10), small ? Math.max(10, Math.round((hi - lo) / 3 / 10) * 10) : 20, false);
        ax.curve(p, t, rr, 0.6, GREY); for (int i = 0; i < rr.length; i++) p.dot(ax.X(t[i]), ax.Y(rr[i]), 1.1, r.hrv.keep[i] ? BLUE : RED, -1);
    }
    static void drawPoincare(Pen p, ProAnalysis.Result r, double x, double y, double s) {
        double m = r.hrv.meanRr; Ax ax = new Ax(x, y, s, s, m - 50, m + 50, m - 50, m + 50); p.rect(x, y, s, s, -1, 0xC8D3DF, 0.6); p.line(ax.X(m - 50), ax.Y(m - 50), ax.X(m + 50), ax.Y(m + 50), 0.4, 0xB0BAC5);
        double[] rr = r.hrv.rrAll; for (int i = 0; i + 1 < rr.length; i++) if (r.hrv.keep[i] && r.hrv.keep[i + 1]) p.dot(ax.X(rr[i]), ax.Y(rr[i + 1]), 1.1, BLUE, -1);
        int n = 40; double[] ex = new double[n + 1], ey = new double[n + 1]; for (int i = 0; i <= n; i++) { double a = 2 * Math.PI * i / n, u = r.hrv.sd2 * Math.cos(a), v = r.hrv.sd1 * Math.sin(a); ex[i] = ax.X(m + (u - v) / Math.sqrt(2)); ey[i] = ax.Y(m + (u + v) / Math.sqrt(2)); } p.poly(ex, ey, n + 1, 0.9, RED);
        p.text(String.format(Locale.UK, "Poincare SD1 %.0f, SD2 %.0f ms", r.hrv.sd1, r.hrv.sd2), x, y - 4, 7.5, INK, true, 0);
    }
    static void drawPsd(Pen p, ProAnalysis.Result r, double x, double y, double w, double h) {
        double[] f = r.hrv.psdF, pw = r.hrv.psdP; double mx = 0; for (int i = 0; i < f.length; i++) if (f[i] <= 0.5) mx = Math.max(mx, pw[i]); Ax ax = new Ax(x, y, w, h, 0, 0.5, 0, mx * 1.1);
        for (int i = 0; i + 1 < f.length && f[i + 1] <= 0.5; i++) { int col = f[i] >= 0.15 && f[i] < 0.4 ? 0xF6D3C7 : (f[i] >= 0.04 && f[i] < 0.15 ? 0xC9D9EE : -1); if (col >= 0) p.rect(ax.X(f[i]), ax.Y(pw[i]), ax.X(f[i + 1]) - ax.X(f[i]), ax.y0 + ax.h - ax.Y(pw[i]), col, -1, 0); }
        int n = 0; for (double v : f) if (v <= 0.5) n++; double[] fx = Arrays.copyOf(f, n), fy = Arrays.copyOf(pw, n); ax.frame(p, "Hz", null, 0.1, mx * 2, false); ax.curve(p, fx, fy, 0.9, INK);
        p.text(String.format(Locale.UK, "R-R spectrum: LF %.0f, HF %.0f ms2 (LF blue, HF red)", r.hrv.lf, r.hrv.hf), x, y - 4, 7.5, INK, true, 0);
    }
    static void pageHrv(Pen p, ProAnalysis.Result r, Info info) {
        header(p, "Heart-rate variability", "From the " + r.hrv.n + " beat-to-beat intervals of " + (r.rateStep ? "the steadiest stretch of the reading (the heart rate changed: see page 1)" : "the reading") + " (artifact filter: within 20 % of the running median of 11).", info, 5);
        p.text("R-R tachogram (blue: kept, red: removed)", 36, 96, 9, INK, true, 0); drawTach(p, r, 36, 245, 380, 130, false);
        drawPoincare(p, r, 450, 110, 140); drawPsd(p, r, 620, 120, 190, 120);
        String[][] rows = {{"Measure", "This reading", "Reference", "Source"},
            {"SDNN (trend removed)", f1(r.hrv.sdnnDetr) + " ms", "NSRDB at 58 to 70 bpm: median 52 ms (30 to 80)", "computed from 859 five-minute windows"},
            {"RMSSD", f1(r.hrv.rmssd) + " ms", "NSRDB: median 40 ms, 5th percentile 21, 95th 64", "same windows"},
            {"pNN50", f1(r.hrv.pnn50) + " %", "", "successive differences over 50 ms"}, {"SD1 / SD2", f1(r.hrv.sd1) + " / " + f1(r.hrv.sd2) + " ms", "", "Poincare plot"},
            {"LF / HF power", specOk(r.hrv) ? f0(r.hrv.lf) + " / " + f0(r.hrv.hf) + " ms2" : "not computed", "no norm quoted", specOk(r.hrv) ? "a short reading; LF is unreliable" : "needs about 3 minutes of beats"}, {"Breathing from the series", specOk(r.hrv) ? f0(r.hrv.fpk * 60) + " a minute" : "not computed", "12 to 20 awake", "spectral peak 0.1 to 0.4 Hz"}};
        double ty = 300; double[] cx = {36, 200, 330, 600};
        for (int i = 0; i < rows.length; i++) { if (i == 0) p.rect(36, ty - 11, W - 72, 15, LIGHT, -1, 0); for (int j = 0; j < 4; j++) p.text(rows[i][j], cx[j] + 3, ty, 8, i == 0 || j == 1 ? INK : (j == 3 ? GREY : INK), i == 0 || j == 1, 0); ty += 17; p.line(36, ty - 11, W - 36, ty - 11, 0.3, 0xC8D3DF); }
        para(p, "Variability falls with age and after exertion, and a short reading is a snapshot: compare with your own readings taken at the same time of day and posture. Published short-term norms differ widely between studies and are lower than the 1996 Task Force values, so the healthy-rhythm database computed here is used as the main comparison. The reading is shorter than the 5 minute standard.", 36, ty + 16, W - 72, 8.5, 11, GREY, false);
    }

    static void pageBand(Pen p, ProAnalysis.Result r, Info info) {
        header(p, "Does the signal above 40 Hz carry information?", "Beat-locked analysis of this reading (" + r.beatsUsed + " beats), alignment from the 5 to 30 Hz band only.", info, 6);
        // A: ensemble S/N and B: single beat
        Ax a = new Ax(70, 112, 330, 140, 0, 9, -5, 50); p.text("A  Signal to noise of the averaged QRS (dB)", 36, 98, 9, INK, true, 0);
        a.frame(p, null, null, 1, 10, true); String[] lab = {"0-20", "20-40", "40-60", "60-80", "80-100", "100-130", "130-160", "160-200", "200-250"};
        for (int i = 0; i < 9; i++) { double v = r.snrAvg[i]; double top = Math.min(50, Math.max(0, v)); p.rect(a.X(i) + 5, a.Y(top), a.X(1) - a.X(0) - 10, a.Y(0) - a.Y(top), i < 2 ? 0x9DB2CC : BLUE, -1, 0); p.text(f0(v), a.X(i) + (a.X(1) - a.X(0)) / 2, a.Y(top) - 3, 7.5, INK, false, 1); p.text(lab[i], a.X(i) + (a.X(1) - a.X(0)) / 2, a.y0 + a.h + 10, 6.5, GREY, false, 1); }
        p.text("frequency band (Hz); the part above 40 Hz is bands 3 to 9", a.x0, a.y0 + a.h + 22, 7, GREY, false, 0);
        Ax b = new Ax(470, 112, 330, 140, 0, 9, -3, 12); p.text("B  The same on a single beat, no averaging (dB)", 440, 98, 9, INK, true, 0); b.frame(p, null, null, 1, 3, true);
        for (int i = 0; i < 9; i++) { double v = r.snrSingle[i]; double top = Math.min(12, Math.max(0, v)); p.rect(b.X(i) + 5, b.Y(top), b.X(1) - b.X(0) - 10, b.Y(0) - b.Y(top), i >= 2 ? (v > 3 ? BLUE : 0x9DB2CC) : 0x9DB2CC, -1, 0); if (v > 12) p.text(f0(v) + " (off scale)", b.X(i) + (b.X(1) - b.X(0)) / 2, b.Y(top) + 12, 6.4, 0xFFFFFF, true, 1); else p.text(f1(v), b.X(i) + (b.X(1) - b.X(0)) / 2, b.Y(top) - 3, 6.8, INK, false, 1); p.text(lab[i], b.X(i) + (b.X(1) - b.X(0)) / 2, b.y0 + b.h + 10, 6.5, GREY, false, 1); }
        // C: split half   D: QRS zoom
        p.text("C  Is it real? Two independent halves of the beats", 36, 300, 9, INK, true, 0);
        p.rect(70, 316, 80, 10, 0xFFFFFF, -1, 0); p.rect(70, 316, 80 * Math.max(0, r.splitQrs), 12, BLUE, -1, 0); p.text("QRS window  " + String.format(Locale.UK, "%.2f", r.splitQrs), 160, 326, 8, INK, false, 0);
        p.rect(70, 336, 80 * Math.max(0, r.splitCtl), 12, 0xBDBDBD, -1, 0); p.text("QRS-free control  " + String.format(Locale.UK, "%.2f", r.splitCtl), 160, 346, 8, INK, false, 0);
        para(p, "Correlation of the 40 to 150 Hz traces of the two halves (1 = identical). A real beat-locked signal repeats; noise does not.", 36, 372, 300, 7.8, 10, GREY, false);
        p.text("D  The QRS at three bandwidths (microvolts)", 440, 300, 9, INK, true, 0);
        Ax d = new Ax(480, 312, 320, 150, -70, 90, -150, 1050); d.frame(p, "ms from the R apex", null, 20, 250, true);
        double[] tt = new double[650], full = new double[650], l40 = new double[650], df = new double[650]; for (int i = 0; i < 650; i++) { tt[i] = (i - 250) * 2.0; full[i] = r.meanFull[i] - r.iv.base; l40[i] = r.meanLp40[i] - r.iv.base; df[i] = (r.meanFull[i] - r.meanLp40[i]) * 4; }
        int i0 = 215, i1 = 296; d.curve(p, Arrays.copyOfRange(tt, i0, i1), Arrays.copyOfRange(full, i0, i1), 1.3, INK); d.curve(p, Arrays.copyOfRange(tt, i0, i1), Arrays.copyOfRange(l40, i0, i1), 1.0, BLUE); d.curve(p, Arrays.copyOfRange(tt, i0, i1), Arrays.copyOfRange(df, i0, i1), 0.9, GREEN);
        if (r.r17Mean != null) { double[] tx = new double[411], ty = new double[411]; for (int i = 0; i < 411; i++) { tx[i] = -300 + i * 2.0; ty[i] = r.r17Mean[i]; } int a0 = 115, a1 = 196; d.curve(p, Arrays.copyOfRange(tx, a0, a1), Arrays.copyOfRange(ty, a0, a1), 1.0, RED); for (int i = a0; i < a1; i += 5) p.dot(d.X(tx[i]), d.Y(ty[i]), 1.5, RED, -1); }
        p.text("black 0.5-250 Hz   blue low-pass 40 Hz   red filtered 100 Hz stream (R17)   green difference x 4", 480, 480, 7, GREY, false, 0);
        String t = String.format(Locale.UK, "R height: %.0f uV (0.5-250 Hz), %.0f at 40 Hz, %.0f in the 100 Hz stream (%.0f %% of the 40 Hz beat, lag %.0f ms). Width at half height: %.0f ms (500 Hz), %.0f ms (100 Hz stream, interpolated); on whole 100 Hz samples the QRS spans only 4 samples.", r.rFull, r.rLp40, r.r17Height, 100 * r.r17Height / r.rLp40, r.r17Lag * 1000, r.widthFull, r.r17Width);
        double y = para(p, t, 36, 500, 770, 8.3, 10.5, INK, false);
        para(p, "Reading: the energy above 40 Hz is beat-locked signal (C), strong after averaging (A) and weak on one beat (B). It carries the height and sharpness of the QRS (D). It does not improve beat timing or rhythm detection (see the H10 and database pages).", 36, y + 2, 770, 8.3, 10.5, GREY, false);
    }

    static String f2(double v) { return Double.isNaN(v) ? "-" : String.format(Locale.UK, "%.2f", v); }
    static String dropTxt(double a, double b) { return Double.isNaN(a) || Double.isNaN(b) ? "-" : "-" + f0(a - b) + " bpm"; }
    static String secOrNone(int s) { return s < 0 ? "not reached" : s + " s"; }
    static String liftLens(ProAnalysis.Result r) { StringBuilder sb = new StringBuilder(); for (int i = 0; i < r.lifts && i < 3; i++) sb.append(i == 0 ? "" : ", ").append(String.format(Locale.UK, "%.0f s", r.liftLen[i])); if (r.lifts > 3) sb.append(", ..."); return sb.toString(); }
    static String liftText(ProAnalysis.Result r) { StringBuilder sb = new StringBuilder(); for (int i = 0; i < r.lifts && i < 3; i++) sb.append(i == 0 ? "" : ", ").append(String.format(Locale.UK, "%.0f s at %d:%02d", r.liftLen[i], (int) r.liftStart[i] / 60, (int) r.liftStart[i] % 60)); if (r.lifts > 3) sb.append(", ..."); return sb.toString(); }
    /** 0.24.0: the conditions of this reading against the six readings of 28 and 29 Oct, with a hint for each flag. */
    static void pageConditions(Pen p, ProAnalysis.Result r, Info info) {
        header(p, "Signal conditions of this reading", "What limited this reading and what to change next time. Reference = the readings of 28 and 29 Oct on this strap (wrist to finger, at rest, H10 worn)." + (r.tag != null && !r.tag.isEmpty() ? " Tag: " + r.tag + "." : ""), info, 0);
        double pn = (r.iv != null && !Double.isNaN(r.iv.pAmp) && r.noiseMean > 0) ? r.iv.pAmp / r.noiseMean : Double.NaN;
        double rn = (r.iv != null && r.noiseSingle > 0) ? r.iv.rAmp / r.noiseSingle : Double.NaN;
        String[][] rows = {
            {"", "This reading", "Reference", "What it means"},
            {"First second of quality 3", secOrNone(r.firstQ3), "10 to 18 s", "warm-up before the strap trusts the signal (the strap itself, not the contact)"},
            {"Seconds with usable data before it", String.valueOf(r.earlyUsable), "7 to 12", "quality 1 or 2, no gaps; usable for beat timing in a test (79 to 92 % of beats, 3 ms error)"},
            {"Strap settling phase ends at", secOrNone(r.settleEnd), "38 to 40 s", "status byte 22 changes from 10 to 8"},
            {"Contact impedance, median", Double.isNaN(r.impI) ? "-" : f0(r.impI), "75 to 80", "above 200 means poor contact"},
            {"Impedance in the first 10 s", Double.isNaN(r.impIFirst10) ? "-" : f0(r.impIFirst10), "75 to 80", "high at the start: press evenly before Start"},
            {"Seconds with poor contact", Double.isNaN(r.impPoorShare) ? "-" : f0(100 * r.impPoorShare) + " %", "2 to 10 %", "seconds with impedance above 200"},
            {"Finger lifts (electrodes open)", r.lifts == 0 ? "none" : r.lifts + " (" + liftLens(r) + ")", "none", "each lift costs about 7 s until quality 3 returns; nothing restarts"},
            {"Noise per beat", f0(r.noiseSingle) + " uV", "38 to 57 uV", "0.5 to 40 Hz, after removing the average beat"},
            {"Noise on the mean beat", f1(r.noiseMean) + " uV", "2.8 to 6.8 uV", "falls with the square root of the number of beats"},
            {"P wave / noise on the mean beat", Double.isNaN(pn) ? "-" : f1(pn), "2 to 10", "below 3 the P wave and PR are not reliable"},
            {"R height / noise per beat", Double.isNaN(rn) ? "-" : f1(rn), "9 to 18", "contact, pressure and placement"},
            {"Seconds left out for wrist movement", String.valueOf(r.motionDropped), "0 at a still table", "wrist rotation over 1.5 deg/s (gyroscope); left out of every average"},
            {"Strap: optical saturation / fit flag", r.gates == null || r.gates.seconds == 0 ? "-" : r.gates.saturated + " / " + r.gates.fitFlag + " s", "0 / 0 at rest", "its own per-second flags: detector rail-pinned; strap-fit classifier not settled"},
            {"Strap: heart-rate source infrared / optical noise", r.gates == null || r.gates.seconds == 0 ? "-" : r.gates.irSource + " s / " + f2(r.gates.logVar), "0 s / about -5.3", "infrared used instead of green; log-variance of the optical signal (lower is cleaner)"},
            {"Beats used / seconds of quality 3", r.beatsUsed + " / " + r.qcount[3] + (r.weighted ? String.format(Locale.UK, " (weighted: %.0f effective)", r.nEff) : ""), "71 to 192 / 65 to 180", "204 s gave 2.8 uV on the mean beat, 80 to 95 s gave 4.6 to 6.8 uV"},
        };
        double ty = 100;
        for (int i = 0; i < rows.length; i++) { if (i == 0) p.rect(36, ty - 11, 770, 15, LIGHT, -1, 0); p.text(rows[i][0], 40, ty, 8.5, INK, i == 0, 0); p.text(rows[i][1], 250, ty, 8.5, INK, true, 0); p.text(rows[i][2], 340, ty, 8.5, GREY, i == 0, 0); p.text(rows[i][3], 450, ty, 8, GREY, false, 0); ty += 17.5; }
        double y = ty + 18; p.text("What to change next time", 36, y, 10, INK, true, 0); y += 14;
        List<String> hints = new ArrayList<>();
        if (r.firstQ3 > 15 || r.firstQ3 < 0) hints.add("Slow start: put the fingers on, damp the finger and the wrist skin, press evenly and wait about 5 s before Start (the strap needed " + secOrNone(r.firstQ3) + " to trust the signal).");
        if ((!Double.isNaN(r.impI) && r.impI > 150) || (!Double.isNaN(r.impPoorShare) && r.impPoorShare > 0.2)) hints.add("Contact impedance is high: damp the finger and the skin under the strap, avoid dry or cold hands, keep the pressure even.");
        if (r.noiseSingle > 55) hints.add("Noise is high: rest the forearm on a table, relax the hand and shoulder, do not grip or press hard, stay still and quiet.");
        if (!Double.isNaN(pn) && pn < 3) hints.add("The P wave is within 3 times the noise on the mean beat: a reading of 3 minutes or more lowers that noise by about the square root of the beats.");
        if (r.seconds < 90) hints.add("Short reading (" + r.seconds + " s): the first " + (r.firstQ3 < 0 ? 12 : r.firstQ3) + " s are warm-up, so under 90 s leaves few clean beats; use 90 s for rate and rhythm, 3 minutes for shape.");
        if (r.lifts > 0) hints.add("The fingers came off " + r.lifts + " time" + (r.lifts == 1 ? "" : "s") + " (" + liftText(r) + "). Keep both fingers on and press evenly; the phone now buzzes when contact is lost for 3 s.");
        if (hints.isEmpty()) hints.add("No condition flags: this reading is at or better than the reference range.");
        for (String h : hints) y = para(p, "- " + h, 40, y, 760, 8.5, 11, INK, false) + 2;
        y += 12; p.text("Why these tests", 36, y, 10, INK, true, 0); y += 14;
        para(p, "Published work on dry-electrode ECG: the skin-electrode impedance falls and the signal-to-noise ratio rises over minutes of stable contact, even without skin preparation, and electrode movement lowers the signal-to-noise ratio markedly [13]. Contact pressure and skin moisture also change the impedance. Muscle noise is the usual limit of a wrist lead. The P wave is normally smaller in lead I (wrist to finger) than in lead II, so P and PR need averaging over more beats. Compare this page between readings that differ in one condition (damp or dry fingers, arm supported or not, pressure, reading length).", 40, y, 760, 8.5, 11, GREY, false);
    }

    /** 0.25.0: pulse arrival time, early beats, QT and ST by window, each with its measurement uncertainty. */
    /** 0.27.1: start "Detectors (continued)" when the next block would not fit above the footer. */
    static double room(Pen p, double y, double need, Info info) {
        if (y + need <= 545) return y;
        header(p, "Detectors (continued)", null, info, 0); return 96;
    }

    static void pageDetectors(Pen p, ProAnalysis.Result r, Info info) {
        header(p, "Detectors: pulse arrival, early beats, QT and ST", "Each value comes with its own measurement error. A change is only called real when it is larger than that error. Research use only.", info, 0);
        double y = 96; p.text("Pulse arrival time (ECG R wave to the green-LED pulse peak at the wrist, stored 50 Hz optical)", 36, y, 10, INK, true, 0); y += 16;
        ProDetect.Pat P = r.pat;
        if (P == null || Double.isNaN(P.medianMs)) y = para(p, P == null ? "Not available." : P.note, 40, y, 760, 8.5, 11, GREY, false) + 4;
        else {
            double rrBreath = specOk(r.hrv) ? r.hrv.fpk * 60 : Double.NaN;
            y = para(p, String.format(Locale.UK, "%d of %d beats have a pulse. Median %s ms, spread (SD) %s ms, typical deviation %s ms. Breathing from the pulse arrival series: %s%s. Changes from beat to beat and between readings mean more than the absolute delay, which depends on the clocks of the two sensors.", P.withPulse, P.beats, f0(P.medianMs), f1(P.sdMs), f1(P.madMs), Double.isNaN(P.breathPerMin) ? "not computed (it needs 2 minutes of data)" : f0(P.breathPerMin) + " a minute", Double.isNaN(rrBreath) ? "" : " (from the beat intervals: " + f0(rrBreath) + ")"), 40, y, 760, 8.5, 11, INK, false) + 6;
            if (P.covered > 0) {      // 0.27.0 pulse-confirmed beats
                StringBuilder un = new StringBuilder(); for (int i = 0; i < Math.min(6, P.unconfirmedT.size()); i++) { double t = P.unconfirmedT.get(i); un.append(i == 0 ? " (at " : ", ").append((int) (t / 60)).append(':').append(String.format(Locale.UK, "%02d", (int) (t % 60))); } if (un.length() > 0) un.append(P.unconfirmedT.size() > 6 ? ", ...)" : ")");
                y = para(p, String.format(Locale.UK, "Pulse-confirmed beats: %d of %d (%.1f %%) have a wrist pulse within 120 ms of the usual delay; %d without one%s. Beat intervals checked against the pulse-to-pulse interval: %d, of which %d differ by more than 60 ms (a beat the ECG detector may have misplaced, or a pulse distorted by movement).%s",
                        P.confirmed, P.covered, 100.0 * P.confirmed / P.covered, P.covered - P.confirmed, un, P.ivChecked, P.ivDisagree,
                        P.earlyTotal > 0 ? String.format(Locale.UK, " Early beats with no confirmed pulse: %d of %d (an early beat often moves too little blood to make a clear wrist pulse).", P.earlyNoPulse, P.earlyTotal) : ""), 40, y, 760, 8.5, 11, INK, false) + 4;
            }
        }
        ProDetect.Opt O = r.opt;
        if (O != null && O.seconds >= 30 && (!Double.isNaN(O.greenPi) || !Double.isNaN(O.irRatio))) {
            y = room(p, y, 70, info);      // 0.27.1: continue on a new page instead of running into the footer
            p.text("Optical contact and perfusion (stored 50 Hz optical)", 36, y, 10, INK, true, 0); y += 16;
            y = para(p, String.format(Locale.UK, "Green perfusion %s %% (pulse size against the steady light level; 8.0 %% in the 29 Oct 21:39 reading). Infrared near/far photodiode ratio %s (range %s to %s over 20 s blocks): it changes when the strap shifts on the wrist (9.5 to 10.9 during a jog on 29 Oct), and the strap's own wear detector logs the same ratio. INFERRED: block 4 of the optical record is infrared.", f1(O.greenPi), f2(O.irRatio), f2(O.irRatioMin), f2(O.irRatioMax)), 40, y, 760, 8.5, 11, INK, false) + 2;
            if (!O.settings.isEmpty()) y = para(p, "The strap's green settings during the reading: " + O.settings + (O.settingChanges > 0 ? " (changed " + O.settingChanges + " times; perfusion before and after a change is not comparable)" : " (no change)") + ". Compare perfusion between readings only at the same settings.", 40, y, 760, 8.5, 11, GREY, false) + 6;
        }
        if (r.strapHr > 0 || r.strapHrv > 0) {
            y = room(p, y, 70, info);      // 0.27.1: continue on a new page instead of running into the footer
            p.text("The strap's own figures (its built-in analysis)", 36, y, 10, INK, true, 0); y += 16;
            if (r.strapCode >= 0) y = para(p, "Its verdict, mapped the way the WHOOP app maps it (research use, not a diagnosis): " + StrapVerdict.describe(r.strapCode, Math.max(0, r.strapHr), r.strapMask) + ".", 40, y, 760, 8.5, 11, INK, false) + 2;
            y = para(p, String.format(Locale.UK, "Heart rate at its verdict %s bpm (this report %.0f); its HRV field at the end of the reading %s ms. The HRV field is a rolling RMSSD: across 150 checks in four readings it matched our RMSSD with r 0.90 and a mean difference of -0.8 ms.", r.strapHr > 0 ? String.valueOf(r.strapHr) : "-", r.hrv.hr, r.strapHrv > 0 ? String.valueOf(r.strapHrv) : "-"), 40, y, 760, 8.5, 11, INK, false) + 6;
        }
        ProDetect.Bcg Bc = r.bcg;
        if (Bc != null && Bc.beats >= 40 && !Double.isNaN(Bc.gyroPeakMs)) {
            y = room(p, y, 70, info);      // 0.27.1: continue on a new page instead of running into the footer
            p.text("Heartbeat in the wrist motion sensors (stored 100 Hz gyroscope and accelerometer)", 36, y, 10, INK, true, 0); y += 16;
            y = para(p, String.format(Locale.UK, "Averaged over %d beats: gyroscope wave starts %s ms and peaks %.0f ms after the R wave (%.2f deg/s, signal %.0f times its noise; random timing gives about 1); accelerometer peak %.0f ms (%.1f mg, signal %.0f). Pulse arrival at the LED: %s ms. The motion wave is the mechanical beat at the wrist, a third view of the same heartbeat next to the ECG and the optical pulse.",
                    Bc.beats, Double.isNaN(Bc.gyroOnsetMs) ? "-" : String.format(Locale.UK, "%.0f", Bc.gyroOnsetMs), Bc.gyroPeakMs, Bc.gyroPeakDps, Bc.gyroSnr, Bc.accPeakMs, Bc.accPeakMg, Bc.accSnr, r.pat == null || Double.isNaN(r.pat.medianMs) ? "-" : String.format(Locale.UK, "%.0f", r.pat.medianMs)), 40, y, 760, 8.5, 11, INK, false) + 6;
        }
        if (!Double.isNaN(r.twaRms)) {
            y = room(p, y, 70, info);      // 0.27.1: continue on a new page instead of running into the footer
            p.text("T-wave alternans (every other beat, ST-T segment)", 36, y, 10, INK, true, 0); y += 16;
            y = para(p, String.format(Locale.UK, "Odd against even beats: %.2f uV RMS; random half-splits of the same beats give %.2f uV (95th percentile %.2f). %s The clinical threshold is about 1.9 uV and is judged at a raised heart rate (about 105 to 110 bpm), so a resting value shows what the strap can resolve rather than a risk.",
                    r.twaRms, r.twaFloor, r.twa95, r.twaRms > r.twa95 ? "Above the noise: repeat to confirm." : "Not above the noise."), 40, y, 760, 8.5, 11, INK, false) + 6;
        }
        y = room(p, y, 70, info);      // 0.27.1: continue on a new page instead of running into the footer
        p.text("Early beats (an interval under 80 % of the local median, followed by one over 112 %)", 36, y, 10, INK, true, 0); y += 16;
        if (r.earlyT == null || r.earlyT.length == 0) y = para(p, "None found in the clean stretches of this reading.", 40, y, 760, 8.5, 11, INK, false) + 6;
        else { StringBuilder sb = new StringBuilder(r.earlyT.length + " found: "); for (int i = 0; i < r.earlyT.length && i < 8; i++) sb.append(String.format(Locale.UK, "%s at %.1f s (interval %.0f %%, next %.0f %%)", i == 0 ? "" : "; ", r.earlyT[i], 100 * r.earlyR1[i], 100 * r.earlyR2[i])); y = para(p, sb.toString() + ". The artifact filter removes such intervals from the heart-rate variability figures; they are listed here instead.", 40, y, 760, 8.5, 11, INK, false) + 6; }
        y = room(p, y, 70, info);      // 0.27.1: continue on a new page instead of running into the footer
        p.text("Exercise before or during this reading (strap activity record; recovery taken from the ECG)", 36, y, 10, INK, true, 0); y += 16;
        ProDetect.Ex X = r.ex;
        if (X == null || !X.found) y = para(p, "None found in the 10 minutes before this reading or during it.", 40, y, 760, 8.5, 11, INK, false) + 6;
        else {
            String s = String.format(Locale.UK, "%.0f s of running or walking, %s (%.0f steps, %.0f a minute). Heart rate at rest before it %s bpm, peak %s bpm (optical). ", X.durS, X.startRel < 0 ? String.format(Locale.UK, "%.0f s before the reading", -X.startRel) : String.format(Locale.UK, "starting %.0f s into the reading", X.startRel), X.steps, X.cadence, f0(X.restHr), f0(X.peakHr));
            if (X.ecgCovers) s += String.format(Locale.UK, "ECG heart rate after it: %s bpm at 3 to 13 s, %s at 30 s (%s), %s at 60 s (%s), %s at 90 s (%s). The optical rate reads low after exercise, so the recovery uses the ECG.", f0(X.hr0), f0(X.hr30), dropTxt(X.hr0, X.hr30), f0(X.hr60), dropTxt(X.hr0, X.hr60), f0(X.hr90), dropTxt(X.hr0, X.hr90));
            else s += "The ECG did not cover the first seconds after it, so no recovery is given.";
            y = para(p, s, 40, y, 760, 8.5, 11, INK, false) + 6;
        }
        y = room(p, y, 105, info);
        p.text("QT and ST per window of 20 beats (step 10 beats)", 36, y, 10, INK, true, 0); y += 14;
        int nw = r.qtWin == null ? 0 : r.qtWin.length;
        if (nw < 4 || Double.isNaN(r.qtSdAcross)) y = para(p, "Too few valid windows in this reading (" + nw + "): a reading of 90 s or more gives about 6 windows, 3 minutes about 18.", 40, y, 760, 8.5, 11, GREY, false) + 4;
        else {
            double qm = ProDsp.median(r.qtWin), sm = ProDsp.median(r.stWin); double qr = r.qtSdAcross / r.qtSigMean, sr = r.stSdAcross / r.stSigMean;
            String[][] rows = {{"", "Median", "Spread (SD)", "Error / window", "Spread / error", "Verdict"},
                {"QT (tangent), ms", f0(qm), f1(r.qtSdAcross), f1(r.qtSigMean), f1(qr), qr > 1.5 ? "varies more than the error" : "within measurement error"},
                {"ST at J+60 ms, uV", f0(sm), f1(r.stSdAcross), f1(r.stSigMean), f1(sr), sr > 1.5 ? "varies more than the error" : "within measurement error"}};
            for (int i = 0; i < rows.length; i++) { if (i == 0) p.rect(36, y - 11, 770, 15, LIGHT, -1, 0); p.text(rows[i][0], 40, y, 8.5, INK, i == 0, 0); p.text(rows[i][1], 190, y, 8.5, INK, i > 0, 0); p.text(rows[i][2], 260, y, 8.5, INK, i > 0, 0); p.text(rows[i][3], 345, y, 8.5, INK, i > 0, 0); p.text(rows[i][4], 440, y, 8.5, INK, i > 0, 0); p.text(rows[i][5], 540, y, 8.5, i == 0 ? INK : GREY, i == 0, 0); y += 17.5; }
            y += 4; y = para(p, nw + " windows. A QT or ST change is real only when it exceeds about twice the measurement error. The stored ECG keeps components down to about 0.01 Hz (30 Oct test: no high-pass of the strap's own in the ECG band); ST is measured after this report's 0.5 Hz zero-phase filter, which matched a 0.05 Hz reference within 1 uV, so ST shapes are faithful; the microvolt scale itself is still unverified.", 40, y, 760, 8.5, 11, GREY, false) + 4;
        }
    }

    static void pageH10(Pen p, ProAnalysis.Result r, H10Compare.Result h, Info info) {
        header(p, "Chest-strap check (Polar H10, same time)", "The H10 is a reference for beat times and for the shape below about 40 Hz (130 Hz sampling); it cannot show anything above 40 Hz.", info, 7);
        String[][] rows = {{"", "Strap (MG)", "H10"}, {"Beats found", String.valueOf(h.nMg), String.valueOf(h.nH10)}, {"Beats paired (within 40 ms)", String.valueOf(h.nPaired), ""}, {"Heart rate (paired beats)", f1(h.hrMg) + " bpm", f1(h.hrH10) + " bpm"}, {"RMSSD (paired beats)", f1(h.rmssdMg) + " ms", f1(h.rmssdH10) + " ms"}, {"R height (mean beat)", f0(h.mgR) + " uV", f0(h.h10R) + " uV"}};
        double ty = 100; for (int i = 0; i < rows.length; i++) { if (i == 0) p.rect(36, ty - 11, 380, 15, LIGHT, -1, 0); p.text(rows[i][0], 40, ty, 8.5, INK, i == 0, 0); p.text(rows[i][1], 250, ty, 8.5, INK, true, 0); p.text(rows[i][2], 340, ty, 8.5, INK, true, 0); ty += 17; p.line(36, ty - 11, 416, ty - 11, 0.3, 0xC8D3DF); }
        para(p, String.format(Locale.UK, "Clock offset %.3f s (found from the beat pattern" + (h.piecewise ? "; refitted every 30 s because the two clocks drift apart over a long reading" : "") + "). Beat-time scatter between the devices %.2f ms; R-R interval difference SD %.2f ms (median %+.1f ms).", h.offsetS, h.timingSdMs, h.rrDiffSdMs, h.rrDiffMedianMs), 36, ty + 10, 380, 8.5, 11, INK, false);
        para(p, "The R-R difference is the combined timing error of both devices, so the strap's own timing error is below it. A heart-rate and RMSSD that agree between devices show that the beats are real and correctly placed; an interval that both devices show is a real beat, not noise. The H10 reads a larger R wave because it is a chest lead.", 36, ty + 54, 380, 8.3, 10.5, GREY, false);
        if (h.h10Mean != null && h.mgMean != null) {
            p.text("Mean beat on both devices (microvolts, each from its own baseline)", 440, 96, 9, INK, true, 0);
            double mx = Math.max(h.h10R, h.mgR) * 1.1; Ax ax = new Ax(480, 108, 330, 200, -300, 520, -mx * 0.3, mx); ax.frame(p, "ms from the R apex", null, 100, 500, true);
            double[] tt = grid(411, -300, 2); ax.curve(p, tt, h.h10Mean, 1.1, ORANGE); ax.curve(p, tt, h.mgMean, 1.1, BLUE); p.text("orange: H10   blue: strap, 40 Hz low-pass", 480, 350, 8, GREY, false, 0);
        }
    }

    static void pageEval(Pen p, ProAnalysis.Result r, Info info) {
        header(p, "Evaluation on public PhysioNet databases", "Done on 28 Oct 2026 with the code that makes this report; the numbers on this page are fixed results of that study, not of this reading, except where marked.", info, 8);
        // A: AUC bars
        p.text("A  AF detection from beat times against timing precision", 36, 98, 9, INK, true, 0); p.text("MIT-BIH AF Database, 17,367 windows of 30 s", 36, 108, 7.5, GREY, false, 0);
        String[] lb = {"250 Hz", "125", "100", "62.5", "50", "25", "+0.7 ms", "+2.5", "+4", "+10"}; double[] au = {0.983, 0.982, 0.983, 0.981, 0.982, 0.968, 0.983, 0.983, 0.983, 0.980};
        Ax ax = new Ax(60, 122, 360, 100, 0, 10, 0.95, 0.99); ax.dec = 2; ax.frame(p, null, "AUC", 1, 0.01, true);
        for (int i = 0; i < 10; i++) { p.rect(ax.X(i) + 4, ax.Y(au[i]), ax.X(1) - ax.X(0) - 8, ax.y0 + ax.h - ax.Y(au[i]), au[i] > 0.975 ? BLUE : RED, -1, 0); p.text(String.format(Locale.UK, "%.3f", au[i]), ax.X(i) + 18, ax.Y(au[i]) - 3, 6.8, INK, false, 1); p.text(lb[i], ax.X(i) + 18, ax.y0 + ax.h + 10, 6.5, GREY, false, 1); }
        p.text("timing resolution (first six) and random timing error added at 250 Hz (last four)", ax.x0, ax.y0 + ax.h + 22, 7, GREY, false, 0);
        // B: RMSSD vs NSRDB
        p.text("B  RMSSD against healthy sinus rhythm", 440, 98, 9, INK, true, 0); p.text("Normal Sinus Rhythm DB, 859 five-minute windows at 58 to 70 bpm", 440, 108, 7.5, GREY, false, 0);
        Ax bx = new Ax(480, 122, 300, 100, 0, 80, 0, 1); bx.frame(p, "RMSSD (ms)", null, 10, 1, false);
        double[] pc = {21, 30, 40, 48, 64}; p.rect(bx.X(pc[0]), 140, bx.X(pc[4]) - bx.X(pc[0]), 40, 0xE0E9F3, -1, 0); p.rect(bx.X(pc[1]), 140, bx.X(pc[3]) - bx.X(pc[1]), 40, 0xBFD0E6, -1, 0); p.line(bx.X(pc[2]), 136, bx.X(pc[2]), 184, 1.5, INK);
        p.text("5th 21", bx.X(pc[0]), 196, 7.5, GREY, false, 1); p.text("median 40", bx.X(pc[2]), 196, 7.5, GREY, false, 1); p.text("95th 64", bx.X(pc[4]), 196, 7.5, GREY, false, 1);
        p.dot(bx.X(Math.min(79, r.hrv.rmssd)), 160, 5, GREEN, -1); p.text("this reading " + f1(r.hrv.rmssd), bx.X(Math.min(79, r.hrv.rmssd)) + 8, 148, 8, GREEN, true, 0);
        // C: QTDB table
        p.text("C  Delineation against cardiologists' marks (PhysioNet QT Database, 35 of 105 records)", 36, 262, 9, INK, true, 0);
        String[][] rows = {{"Boundary", "Mean error", "SD across records", "Records", "CSE tolerance on the SD"}, {"QRS onset", "+6.5 ms", "10.5 ms", "16 (held out)", "6.5 ms"}, {"QRS end", "-10.1 ms", "14.1 ms", "16 (held out)", "11.6 ms"}, {"T end", "+11.6 ms", "82.9 ms (median |error| 46 ms)", "14", "30.6 ms"}, {"P onset / end (single beats)", "", "25 and 27 ms", "35", "10.2 / 12.7 ms"}};
        double ty = 282; double[] cx = {36, 200, 290, 470, 560}; for (int i = 0; i < rows.length; i++) { if (i == 0) p.rect(36, ty - 11, W - 72, 15, LIGHT, -1, 0); for (int j = 0; j < 5; j++) p.text(rows[i][j], cx[j] + 3, ty, 8, INK, i == 0 || j == 1, 0); ty += 17; p.line(36, ty - 11, W - 36, ty - 11, 0.3, 0xC8D3DF); }
        double y = para(p, "What this shows: atrial-fibrillation screening from beat times is insensitive to timing resolution above 50 Hz and to a few milliseconds of timing error, so a 256 Hz mode would not help it. " + rmssdSentence(r.hrv.rmssd) + " Delineation of the QRS is within about 10 to 14 ms of the cardiologists, the T end (QT) within about 40 ms: use QT as a trend.", 36, ty + 14, W - 72, 8.6, 11, INK, false);
        para(p, "What it does not show: that the strap detects atrial fibrillation (none was recorded by this strap; the AF database is two-lead ambulatory data), or that any value here is clinically accurate. A clinician must review any ECG.", 36, y + 2, W - 72, 8.6, 11, GREY, false);
    }

    static void pageMethods(Pen p, Info info) {
        header(p, "Methods and limitations", null, info, 9);
        double y = 96, w = W - 72; p.text("Methods in brief", 36, y, 12, BLUE, true, 0); y += 16;
        String[] m = {
            "Record: live raw ECG frames (type 43, layout 16, 500 samples a second, 18-bit signed, CRC checked) at the design scale of 0.0621 microvolts per count. Seconds with the strap quality flag 3 and no flagged or railed samples form the clean segments (0.5 s trimmed at each end).",
            "Beats: 0.5 Hz high-pass and 50 Hz notch (zero phase); R apexes from the 5 to 20 Hz energy envelope refined to the sample maximum; windows of 500 ms before to 800 ms after the apex; sub-sample alignment by cross-correlation using only the 5 to 30 Hz band; beats with a correlation under 0.9 to the median beat are dropped.",
            "Delineation: QRS onset and end where the absolute slope falls under 5 % of its maximum around the R wave; isoelectric level at the end of the PR segment; P wave as the largest upright deflection 230 to 40 ms before the QRS (15 % boundaries); T end by the tangent method on the steepest descent after the T peak (15 Hz low-pass). Ranges: 300 bootstrap resamples of the beats.",
            "Variability: artifact filter, SDNN of the detrended series, RMSSD, pNN50, Poincare SD1 and SD2; spectrum from the series resampled at 4 Hz (cubic spline), linear trend removed, Welch with 64 s Hann segments, LF 0.04 to 0.15 Hz and HF 0.15 to 0.40 Hz.",
            "Bandwidth study: signal to noise of the averaged QRS per band against a sign-flip bootstrap of the residuals; single-beat signal to noise against the T-P stretch; split-half reproducibility of the 40 to 150 Hz trace; the filtered stream (R17) averaged phase-correct around the same beats.",
            "H10: R peaks of both devices, clock offset found from the beat pattern, pairing within 40 ms; mean beats on a 2 ms grid."};
        for (String s : m) y = bullet(p, s, 36, y, w, 8.8);
        y += 8; p.text("Limitations", 36, y, 12, BLUE, true, 0); y += 16;
        String[] l = {
            "One strap, one person, one lead; readings at rest or shortly after exertion. Nothing here is a diagnosis.",
            "The amplitude scale is the design value and was not checked with a calibrator. ST levels are altered by the 0.5 Hz high-pass of the analysis and by the strap's own filtering.",
            "The delineation was validated on single-lead Holter data of a different kind (QT Database, normal and abnormal records); the PR and the T end are not accurate enough for clinical measurement. The CSE tolerance limits are shown for orientation.",
            "Reference values come from healthy volunteers (NSRDB, ages about 25 to 45) and published norms; a comparison with a different age or a post-work reading is only a rough guide.",
            "The signal above 40 Hz was tested for reality and for use in timing; its value for waveform morphology in disease was not tested."};
        for (String s : l) y = bullet(p, s, 36, y, w, 8.8);
    }
    static void pageRefs(Pen p, Info info) {
        header(p, "References", null, info, 10); double y = 96;
        String[] refs = {
            "[1] Goldberger AL, et al. PhysioBank, PhysioToolkit, and PhysioNet. Circulation 2000;101:e215-e220.",
            "[2] Moody GB, Mark RG. A new method for detecting atrial fibrillation using R-R intervals. Computers in Cardiology 1983;10:227-230 (MIT-BIH Atrial Fibrillation Database).",
            "[3] Laguna P, Mark RG, Goldberger AL, Moody GB. A database for evaluation of algorithms for measurement of QT and other waveform intervals in the ECG. Computers in Cardiology 1997;24:673-676.",
            "[4] MIT-BIH Normal Sinus Rhythm Database, PhysioNet [1].",
            "[5] Task Force of the ESC and NASPE. Heart rate variability: standards of measurement, physiological interpretation and clinical use. Circulation 1996;93:1043-1065.",
            "[6] Nunan D, Sandercock GRH, Brodie DA. A quantitative systematic review of normal values for short-term heart rate variability in healthy adults. Pacing Clin Electrophysiol 2010;33:1407-1417.",
            "[7] Rautaharju PM, Surawicz B, Gettes LS. AHA/ACCF/HRS recommendations for the standardization and interpretation of the electrocardiogram: Part IV. Circulation 2009;119:e241-e250.",
            "[8] Kligfield P, et al. Recommendations for the standardization and interpretation of the electrocardiogram: Part I: the electrocardiogram and its technology. Circulation 2007;115:1306-1324 (high-frequency cutoff of at least 150 Hz for adults; 40 Hz called suboptimal).",
            "[9] Bazett HC. Heart 1920;7:353-370. Fridericia LS. Acta Med Scand 1920;53:469-486 (rate correction of the QT interval).",
            "[10] Dash S, Chon KH, Lu S, Raeder EA. Automatic real time detection of atrial fibrillation. Ann Biomed Eng 2009;37:1701-1709.",
            "[11] The CSE Working Party. Recommendations for measurement standards in quantitative electrocardiography. Eur Heart J 1985;6:815-825.",
            "[12] Jane R, Blasi A, Garcia J, Laguna P. Evaluation of an automatic threshold based detector of waveform limits in Holter ECG with the QT database. Computers in Cardiology 1997 (tolerances 10.2, 12.7, 6.5, 11.6 and 30.6 ms).",
            "[13] Joutsen A, et al. ECG signal quality in intermittent long-term dry electrode recordings with controlled motion artifacts. Sci Rep 2024 (doi 10.1038/s41598-024-56595-0)."};
        for (String s : refs) y = para(p, s, 36, y, W - 72, 8.8, 11.5, INK, false) + 4;
        para(p, "Generated on the phone by the NOOP-MG-ECG app from the reading's own live raw ECG frames. Research use only.", 36, y + 12, W - 72, 8.5, 11, GREY, false);
    }

    static String whenText(double unix) { SimpleDateFormat f = new SimpleDateFormat("EEE d MMM yyyy HH:mm 'UTC'", Locale.UK); f.setTimeZone(TimeZone.getTimeZone("UTC")); return f.format(new Date((long) (unix * 1000))); }
}
