package com.noop.mgecg;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

// FILE VERSION 0.91.0 (25 Nov 2026): the one-page printout for a doctor, built into every report as a hidden section (class "dp") that ReportActivity shows alone when "Doctor page" is pressed.
// Three clean 6 second strips on ECG paper scale (25 mm/s, 10 mm/mV), the heart rate, the timing screen's wording and the QTcF as an experimental number. No QRS width (it is not shown per reading anywhere in the app),
// no strap serial, no name: the Name and Date of birth lines are left blank for the person to fill in by hand.
final class DoctorPage {
    private DoctorPage() { }

    static final int FS = 500, SEC = 6, WIN = SEC * FS;

    /** Print and screen rules for the page. EcgReport.CSS appends this. */
    static final String CSS =
            ".dp{display:none}"
            + "body.doc{background:#fff;color:#111}body.doc main{max-width:none;padding:0;display:block}body.doc main>*{display:none!important}body.doc main>.dp{display:block!important}"
            + ".dp{font-family:'Outfit',-apple-system,Roboto,'Segoe UI',system-ui,sans-serif;color:#111;background:#fff;padding:14px 12px;line-height:1.4}"
            + ".dp h2{font-size:20px;font-weight:600;margin:0}.dp .dpm{font-size:12px;color:#444;margin:2px 0 10px}.dp .dpn{display:flex;gap:18px;margin:10px 0 12px;font-size:13px}.dp .dpn span{flex:1;border-bottom:1px solid #111;padding-bottom:14px}"
            + ".dp .dps{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:6px 14px;margin:8px 0 12px;font-size:13px}.dp .dps div{border-top:1px solid #bbb;padding-top:4px}.dp .dps b{font-size:16px;font-weight:600}"
            + ".dp .dpl{font-size:11px;color:#444;margin:10px 0 2px}.dp svg{display:block;width:100%;height:auto;background:#fff}.dp svg.st{border:1px solid #d99;width:150mm;max-width:100%;height:auto}"
            + ".dp ul{margin:6px 0 0;padding-left:18px;font-size:11px;color:#333;line-height:1.5}.dp .dpf{font-size:11px;color:#333;margin-top:10px;border-top:1px solid #bbb;padding-top:6px}"
            + "@media print{.dp{padding:0}body.doc .dp svg{break-inside:avoid}body.doc .dp svg.st{width:150mm;max-width:none;height:40mm}@page{size:A4;margin:10mm}}";

    /** First samples of up to three non-overlapping, fully clean 6 second windows with at least 4 marked beats and no rail hit or recovery window within 3 s of either end: the first, the middle one and the last. */
    static int[] windows(EcgR16Analyzer.Strip s) {
        if (s == null || s.mv == null || s.quality == null || s.mv.length < WIN) return new int[0];
        List<Integer> ok = new ArrayList<>();
        for (int st = 0; st + WIN <= s.mv.length; st += FS) {
            boolean clean = true;
            for (int i = st; i < st + WIN; i += 5) if (Double.isNaN(s.mv[i]) || s.quality[i] != 3) { clean = false; break; }
            if (!clean) continue;
            if (nearFlag(s, st - MARGIN, st + WIN + MARGIN)) continue;      // not next to a rail hit or recovery window: the baseline there is still settling or lifting
            int nb = 0;
            for (int bi : s.beats) if (bi >= st && bi < st + WIN) nb++;
            if (nb >= 4) ok.add(st);
        }
        if (ok.isEmpty()) return new int[0];
        int first = ok.get(0), last = ok.get(ok.size() - 1), mid = first;
        double target = (first + last) / 2.0;
        for (int st : ok) if (Math.abs(st - target) < Math.abs(mid - target)) mid = st;
        List<Integer> pick = new ArrayList<>();
        pick.add(first);
        if (mid >= first + WIN) pick.add(mid);
        if (last >= pick.get(pick.size() - 1) + WIN) pick.add(last);
        int[] out = new int[pick.size()];
        for (int i = 0; i < out.length; i++) out[i] = pick.get(i);
        return out;
    }

    static final int MARGIN = 3 * FS;

    private static boolean nearFlag(EcgR16Analyzer.Strip s, int a, int b) {
        if (s.flags == null || s.flags.length == 0) return false;
        for (int i = Math.max(0, a); i < Math.min(s.flags.length, b); i += 5) if (s.flags[i] != 0) return true;
        return false;
    }

    /** One strip on paper scale: 150 mm wide (6 s at 25 mm/s), 40 mm high (+/-2 mV at 10 mm/mV). {@code id}: makes the grid pattern ids unique inside the page. */
    static String strip(EcgR16Analyzer.Strip s, int st, boolean inverted, int id) {
        StringBuilder b = new StringBuilder(14000);
        b.append("<svg class=\"st\" viewBox=\"0 0 150 40\" width=\"150mm\" height=\"40mm\" role=\"img\" aria-label=\"Six seconds of ECG on paper scale\">")
                .append("<defs><pattern id=\"dm").append(id).append("\" width=\"1\" height=\"1\" patternUnits=\"userSpaceOnUse\"><path d=\"M1 0H0V1\" fill=\"none\" stroke=\"#f3d3d3\" stroke-width=\"0.12\"/></pattern>")
                .append("<pattern id=\"dM").append(id).append("\" width=\"5\" height=\"5\" patternUnits=\"userSpaceOnUse\"><path d=\"M5 0H0V5\" fill=\"none\" stroke=\"#e39a9a\" stroke-width=\"0.2\"/></pattern></defs>")
                .append("<rect width=\"150\" height=\"40\" fill=\"url(#dm").append(id).append(")\"/><rect width=\"150\" height=\"40\" fill=\"url(#dM").append(id).append(")\"/>")
                .append("<polyline fill=\"none\" stroke=\"#111\" stroke-width=\"0.28\" stroke-linejoin=\"round\" points=\"");
        double sign = inverted ? -1.0 : 1.0;
        for (int k = 0; k < WIN; k += 3) {
            double mv = sign * s.mv[st + k];
            double y = 20.0 - mv * 10.0;
            if (y < 0.3) y = 0.3; else if (y > 39.7) y = 39.7;
            b.append(String.format(Locale.US, "%.2f,%.2f ", k * 0.05, y));
        }
        b.append("\"/></svg>");
        return b.toString();
    }

    static String clock(int sample) {
        int sec = sample / FS;
        return String.format(Locale.US, "%d:%02d", sec / 60, sec % 60);
    }

    /** The hidden page section. Always returns a section: with no clean 6 s stretch it says so instead of drawing a strip. */
    static String section(EcgR16Analyzer.Result r, EcgR16Analyzer.Strip strip, EcgReport.Grade g, boolean usable, long startUnix) {
        StringBuilder b = new StringBuilder(48000);
        String when = startUnix <= 0 ? "" : new SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.UK).format(new Date(startUnix * 1000L));
        b.append("<section class=\"dp\"><h2>ECG strips</h2><p class=\"dpm\">One lead, wrist to finger (WHOOP MG), recorded at 500 samples a second").append(when.isEmpty() ? "" : ". " + when).append("</p>");
        b.append("<div class=\"dpn\"><span>Name:</span><span>Date of birth:</span></div>");
        double hr = EcgReport.reportHr(r);
        EcgIntervals.Result q = r.qt;
        boolean qtOk = q != null && q.status == EcgIntervals.Status.OK && !Double.isNaN(q.qtcFMs);
        String[] rt = EcgReport.rhythmTile(r);
        int secs = r.secondStates.length > 0 ? r.secondStates.length : r.recordsIn;
        b.append("<div class=\"dps\"><div>Heart rate<br><b>").append(usable && !Double.isNaN(hr) ? Math.round(hr) + " bpm" : "not measured").append("</b></div>")
                .append("<div>Beat-timing screen<br><b>").append(rt[0]).append("</b></div>")
                .append("<div>Reading quality<br><b>").append(g.word).append("</b> (").append(r.usableSeconds).append(" of ").append(Math.max(secs, r.usableSeconds)).append(" s clean)</div>")
                .append("<div>QTcF, Fridericia<br><b>").append(qtOk && usable ? String.format(Locale.US, "%.0f ms", q.qtcFMs) : "not measured").append("</b> experimental</div></div>");
        int[] ws = usable ? windows(strip) : new int[0];
        if (ws.length == 0) {
            b.append("<p class=\"dpl\">No clean 6 second stretch was found in this reading, so no strip is shown.</p>");
        } else {
            for (int i = 0; i < ws.length; i++) {
                b.append("<p class=\"dpl\">Strip ").append(i + 1).append(": ").append(clock(ws[i])).append(" to ").append(clock(ws[i] + WIN)).append(" into the recording. 25 mm/s, 10 mm/mV.</p>")
                        .append(strip(strip, ws[i], r.inverted, i + 1));
            }
        }
        b.append("<ul><li>Time axis is exact. The amplitude scale has not been checked against a known voltage, so heights are approximate.</li>")
                .append("<li>Filtered 0.67 to 40 Hz (zero-phase) with a 50 Hz notch, which can change the shape of the ST segment and T wave.</li>")
                .append("<li>The QTcF is a research estimate from an averaged beat. It has not been compared with a 12-lead ECG.</li>")
                .append(r.inverted ? "<li>The recording came in upside down and is drawn the usual way up.</li>" : "").append("</ul>");
        b.append("<p class=\"dpf\">Research instrument. Not a medical device and not a diagnosis. One lead from wrist to finger is not a 12-lead ECG and cannot detect or rule out any heart condition. "
                + "If you have chest pain, fainting or palpitations, get medical advice.</p></section>");
        return b.toString();
    }
}
