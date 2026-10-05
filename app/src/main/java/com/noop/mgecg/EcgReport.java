package com.noop.mgecg;

// FILE VERSION 0.2.9 (4 Oct): contains saveReportFile and reportDir; research-only reading, explanations, QT per heart-rate band, fine-detail QRS view, flipped traces

import java.util.List;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * Builds the plain-English ECG report as ONE self-contained HTML page (inline CSS and SVG, no network,
 * no scripts). Pure Java, no Android. Show it in a WebView, save it as a file, or print it to PDF.
 *
 * RESEARCH ONLY. Not a medical device, not a diagnosis. The wording never names a condition as a finding:
 * the rhythm result says whether beat timing looked steady, and any irregular result is hedged and
 * points to a doctor if it keeps happening or the person feels unwell.
 */
public final class EcgReport {

    private EcgReport() {}

    /** Where saved reports go (set by the app at start-up). Null = do not save. */
    public static volatile java.io.File reportDir;

    /**
     * Writes the report for a finished stored-ECG analysis as ecg_report_&lt;strap unix time&gt;.html in
     * reportDir. Never throws: a report that cannot be saved must not disturb the analysis.
     */
    public static void saveReportFile(EcgR16Analyzer.Result r, EcgR16Analyzer.Strip s) {
        java.io.File dir = reportDir;
        if (dir == null || r == null || s == null || r.recordsIn == 0 || s.mv.length == 0) return;
        try {
            String html = html(r, s, null);
            long t = s.startUnix;
            if (t < 1672531200L || t > 1924992000L) t = System.currentTimeMillis() / 1000L;
            java.io.File f = new java.io.File(dir, "ecg_report_" + t + ".html");
            java.io.FileOutputStream out = new java.io.FileOutputStream(f);
            try {
                out.write(html.getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
            // best effort only
        }
    }

    /** Extra facts the analyzer does not know. All optional. */
    public static final class Meta {
        public long startUnix;            // 0 = take it from the strip
        public String strapCategory;      // the band's own verdict, e.g. "Unreadable"; null if not known
        public String strapReasons;       // e.g. "significant noise"; null if none
        public String device = "WHOOP MG, wrist to finger";
    }

    public static final class Grade {
        public final char letter;
        public final String word;
        public final double fraction;     // measured seconds / recorded seconds
        Grade(char letter, String word, double fraction) { this.letter = letter; this.word = word; this.fraction = fraction; }
    }

    // ------------------------------------------------------------------ grading

    /**
     * Signal quality only, never the rhythm: an uneven rhythm on a clean signal is still a good reading.
     * A: at least 80% of seconds measured and under 3% odd-shaped beats. B: 60% and 6%. C: 30% (or 25 s).
     * D: anything else, including too noisy (over 10% odd-shaped beats) or too few beats.
     */
    public static Grade grade(EcgR16Analyzer.Result r) {
        int total = Math.max(1, r.secondStates.length > 0 ? r.secondStates.length : r.recordsIn);
        double frac = r.usableSeconds / (double) total;
        double bad = r.beatsChecked == 0 ? 1.0 : r.beatsBadShape / (double) r.beatsChecked;
        if (r.usableSeconds == 0 || r.intervals < 10 || r.noiseFraction > 0.10) return new Grade('D', "Poor", frac);
        if (frac >= 0.80 && bad <= 0.03) return new Grade('A', "Excellent", frac);
        if (frac >= 0.60 && bad <= 0.06) return new Grade('B', "Good", frac);
        if (frac >= 0.30 || r.usableSeconds >= 25) return new Grade('C', "Fair", frac);
        return new Grade('D', "Poor", frac);
    }

    // ------------------------------------------------------------------ page

    public static String html(EcgR16Analyzer.Result r, EcgR16Analyzer.Strip strip, Meta meta) {
        if (meta == null) meta = new Meta();
        Grade g = grade(r);
        boolean usableReading = g.letter != 'D';
        StringBuilder b = new StringBuilder(32000);
        String[] rtMeta = rhythmTile(r);
        double hrMeta = usableReading ? reportHr(r) : Double.NaN;
        String hrvMeta = (usableReading && !r.inconclusive && !Double.isNaN(r.rmssd)) ? String.valueOf(Math.round(r.rmssd)) : "-";
        b.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<meta name=\"ecg-grade\" content=\"").append(g.letter).append("\">")
                .append("<meta name=\"ecg-hr\" content=\"").append(Double.isNaN(hrMeta) ? "-" : String.valueOf(Math.round(hrMeta))).append("\">")
                .append("<meta name=\"ecg-hrv\" content=\"").append(hrvMeta).append("\">")
                .append("<meta name=\"ecg-rhythm\" content=\"").append(esc(rtMeta[0].replace("&ndash;", "-"))).append("\">")
                .append("<title>ECG report</title><style>").append(CSS).append("</style></head><body><main>");

        long start = meta.startUnix != 0 ? meta.startUnix : (strip != null ? strip.startUnix : 0);
        int secs = r.secondStates.length > 0 ? r.secondStates.length : r.recordsIn;
        String when = start == 0 ? "" : new SimpleDateFormat("EEE d MMM, HH:mm", Locale.UK).format(new Date(start * 1000L)) + " &middot; ";
        b.append("<header><h1>Your reading</h1><p class=\"sub\">").append(when).append(formatDuration(secs)).append(" &middot; ")
                .append(esc(meta.device)).append("</p></header>");

        // ---- quality
        b.append("<section class=\"card q\">").append(ring(g)).append("<div><div class=\"k\">Reading quality</div><div class=\"big\">")
                .append(g.word).append("</div><p>").append(qualitySentence(r, g, secs, meta)).append("</p></div></section>");

        // ---- what we found
        b.append("<section class=\"card\"><h2>What we found</h2><p class=\"lead\">").append(foundSentence(r, g, usableReading)).append("</p></section>");

        // ---- tiles
        double hrShown = reportHr(r);
        boolean showHr = usableReading && !Double.isNaN(hrShown);
        b.append("<div class=\"grid\">");
        b.append(tile("Heart rate", showHr ? String.valueOf(Math.round(hrShown)) : "&ndash;", showHr ? "bpm" : "",
                showHr ? (r.inconclusive ? "Average of all measured beats." : "Average of the clean beats.") : "Not shown: the reading was not clean enough.", null));
        boolean showHrv = showHr && !r.inconclusive && !Double.isNaN(r.rmssd);
        b.append(tile("HRV (RMSSD)", showHrv ? String.valueOf(Math.round(r.rmssd)) : "&ndash;", showHrv ? "ms" : "",
                showHrv ? "How much the gap between beats varies. Compare with your own past readings, not other people's."
                        : "Not shown: uneven timing makes this number unreliable.", null));
        String[] rt = rhythmTile(r);
        b.append(tile("Rhythm timing", rt[0], "", rt[1], rt[2]));
        b.append(tile("Early beats", usableReading ? String.valueOf(r.early) : "&ndash;", "", "Beats that came sooner than expected.", null));
        b.append("</div>");

        // ---- QT
        b.append(qtCard(r.qt, r.qtBands));
        b.append(bandsCard(r.qtBands));

        // ---- research-only reading
        EcgOpinion.Result op = EcgOpinion.build(r, g);
        b.append(opinionCard(op));

        // ---- graphs
        if (usableReading) {
            String tr = traceChart(strip, r.inverted);
            if (tr != null) {
                b.append("<section class=\"card\"><h2>Ten seconds of your heartbeat</h2><p class=\"hint\">Each amber marker is one heartbeat.</p>")
                        .append(tr).append("<p class=\"cap\">").append(r.inverted ? "This recording came in upside down, which happens when the two electrode contacts are the other way round, for example with the other hand or wrist. It is flipped here so the main spike points up. " : "").append("Every tall spike is one beat. The space between spikes sets your heart rate: wider gaps mean a slower rate. Small wobbles between beats are movement and muscle noise.</p></section>");
            }
            if (r.qt != null && r.qt.avg != null) {
                b.append("<section class=\"card\"><h2>Your average heartbeat</h2><p class=\"hint\">").append(r.qt.beatsUsed)
                        .append(" clean beats laid on top of each other and averaged.</p>").append(avgChart(r.qt))
                        .append("<p class=\"cap\"><b>P</b> the top chambers firing. <b>Q R S</b> the main pump firing. <b>T</b> the pump resetting. QT is the time from the start of Q to the end of T.</p>")
                        .append("<div class=\"grid2\"><div class=\"mini\"><span>R wave height</span><b>")
                        .append(String.format(Locale.US, "%.2f mV", r.qt.rAmpUv / 1000.0)).append("</b></div><div class=\"mini\"><span>T wave vs noise</span><b>")
                        .append(String.format(Locale.US, "%.0f&times;", r.qt.tAmpUv / Math.max(1e-9, r.qt.noiseUv))).append("</b></div></div></section>");
                b.append(detailCard(r.qt));
            }
            if (r.rrMs.length >= 8) {
                double[] ax = tachAxis(r);
                b.append("<section class=\"card\"><h2>Time between beats</h2><p class=\"hint\">Each dot is the gap before one heartbeat.</p>")
                        .append(tachChart(r, ax)).append("<p class=\"cap\">").append(tachCaption(r)).append("</p></section>");
                String po = poincare(r, ax);
                if (po != null) {
                    b.append("<section class=\"card\"><h2>Steadiness plot</h2><p class=\"hint\">Each dot compares one gap with the next.</p>")
                            .append(po).append("<p class=\"cap\">A tight cluster on the dashed line means a steady rhythm. A wide scatter means uneven timing.</p></section>");
                }
                String hrc = hrChart(r);
                if (hrc != null) {
                    b.append("<section class=\"card\"><h2>Heart rate across the reading</h2><p class=\"hint\">Average in 12 second steps.</p>")
                            .append(hrc).append("<p class=\"cap\">A flat line means a steady rate. Rises and falls are normal with breathing, movement and talking.</p></section>");
                }
            }
        }
        if (r.secondStates.length > 0) {
            b.append("<section class=\"card\"><h2>Which seconds counted</h2><p class=\"hint\">Only clean, settled seconds are measured.</p>")
                    .append(qualityBar(r)).append("<div class=\"legend\"><span><i style=\"background:var(--green)\"></i>Measured</span><span><i style=\"background:var(--amber)\"></i>Settling or too short</span>")
                    .append("<span><i style=\"background:var(--grey)\"></i>Signal too weak</span><span><i style=\"background:var(--red)\"></i>Contact lost</span></div>")
                    .append("<p class=\"cap\">").append(r.noiseFraction > 0.10
                            ? "Green here means the band had a signal, not that the heartbeats were clean: this reading was too noisy."
                            : "Gaps in the bar are where contact slipped or the signal was too noisy. A steadier hold fills it in.").append("</p></section>");
        }

        // ---- reliability
        b.append("<section class=\"card\"><h2>How reliable is this reading?</h2><p class=\"hint\">").append(reliabilityLine(g, r)).append("</p>");
        b.append(row("Clean seconds", r.usableSeconds + " of " + Math.max(secs, r.usableSeconds) + " (" + Math.round(g.fraction * 100) + "%)"));
        b.append(row("Beats checked", r.beatsChecked + " in the measured seconds, " + r.beatsBadShape + " odd-shaped"));
        if (!Double.isNaN(r.rAmpUv)) {
            b.append(row("Signal vs noise", String.format(Locale.US, "R wave about %.1f mV, noise about %.2f mV", r.rAmpUv / 1000.0, r.noiseUv / 1000.0)));
        }
        if (meta.strapCategory != null) {
            b.append(row("Band's own check", esc(meta.strapCategory) + (meta.strapReasons != null && !meta.strapReasons.isEmpty() ? " (" + esc(meta.strapReasons) + ")" : "")));
        }
        b.append("</section>");

        b.append("<section class=\"card\"><h2>For a better reading next time</h2><ul><li>Rest your forearm on a table and keep still.</li>")
                .append("<li>Hold steady, even contact with both fingers.</li><li>Stay on for at least 60 seconds.</li><li>Breathe normally and avoid talking.</li></ul></section>");

        b.append("<section class=\"card\"><h2>What this cannot tell you</h2><ul><li>It is one signal from wrist to finger, not a 12-lead ECG.</li>")
                .append("<li>Regular timing does not mean a healthy heart.</li><li>It cannot diagnose or rule out any condition.</li></ul>")
                .append("<p class=\"lead\">If you feel unwell, or have chest pain, fainting or palpitations, get medical advice.</p></section>");

        b.append(explainSection(r, g, op));

        b.append("<section class=\"card\"><h2>Technical details</h2>");
        b.append(row("Recording", secs + " s at 500 samples a second"));
        b.append(row("Filters", "0.67 to 40 Hz, zero-phase, 50 Hz notch"));
        if (strip != null) b.append(row("Heartbeats found", strip.beats.length + " in clean stretches"));
        if (r.qt != null) {
            b.append(row("Averaged beats", r.qt.beatsUsed + " of " + r.qt.beatsOffered + " matched the typical shape"));
            if (!Double.isNaN(r.qt.rHalfWidthMs)) {
                b.append(row("R wave width", String.format(Locale.US, "%.0f ms at half height", r.qt.rHalfWidthMs)));
                b.append(row("Q to S span", String.format(Locale.US, "%.0f ms between the dips either side of R", r.qt.qsSpanMs)));
            }
            if (r.inverted) b.append(row("Orientation", "Recording came in upside down; flipped for display and measurement"));
            b.append(row("QT method", "Start = where the QRS begins (slope), end = tangent on the T wave; 95% range from " + EcgIntervals.BOOTSTRAPS + " resamples"));
        }
        if (r.rhythm != null && r.rhythm.features != null) {
            b.append(row("Rhythm screen", String.format(Locale.US, "%d intervals, nRMSSD %.3f, sample entropy %.2f", r.rhythm.intervals, r.rhythm.features[0], r.rhythm.features[4])));
        }
        b.append("</section>");

        b.append("<section class=\"card\"><details class=\"why\"><summary>Sources and methods</summary><ol class=\"src\">");
        for (String src : EcgOpinion.SOURCES) b.append("<li>").append(esc(src)).append("</li>");
        b.append("</ol></details></section>");

        b.append("<p class=\"foot\">Research instrumentation. Not a medical device and not a diagnosis. It cannot detect or rule out any heart condition.</p>");
        b.append("</main></body></html>");
        return b.toString();
    }

    // ------------------------------------------------------------------ text

    /** Heart rate for the report: the analyzer's clean-beat rate, else the mean of every plausible interval. */
    static double reportHr(EcgR16Analyzer.Result r) {
        if (!r.inconclusive && !Double.isNaN(r.hr)) return r.hr;
        double sum = 0;
        int n = 0;
        for (int i = 0; i < r.rrMs.length; i++) {
            if (!r.rrBad[i] && r.rrMs[i] >= 300 && r.rrMs[i] <= 2000) { sum += r.rrMs[i]; n++; }
        }
        return n >= 10 ? 60000.0 / (sum / n) : Double.NaN;
    }

    static String qualitySentence(EcgR16Analyzer.Result r, Grade g, int secs, Meta meta) {
        if (r.noiseFraction > 0.10 && r.usableSeconds > 0) {
            return "The band had a signal for " + r.usableSeconds + " of " + Math.max(secs, r.usableSeconds) + " seconds, but "
                    + Math.round(r.noiseFraction * 100) + "% of the heartbeats looked distorted, so it was too noisy to trust.";
        }
        String s = r.usableSeconds + " of " + Math.max(secs, r.usableSeconds) + " seconds were clean enough to measure (" + Math.round(g.fraction * 100) + "%).";
        if (meta.strapCategory != null && (meta.strapCategory.equals("Unreadable") || meta.strapCategory.equals("Inconclusive"))) {
            s += " The band also flagged noise.";
        }
        return s;
    }

    static String foundSentence(EcgR16Analyzer.Result r, Grade g, boolean usable) {
        if (!usable) {
            return "We could not get a reliable reading this time. " + (r.usableSeconds == 0 || r.intervals < 10
                    ? "There were too few clean seconds in a row."
                    : "The signal was too noisy to tell heartbeats apart.")
                    + " Rest your forearm on a table, hold steady contact with both fingers for at least a minute, and try again.";
        }
        StringBuilder s = new StringBuilder();
        double hrNow = reportHr(r);
        if (!Double.isNaN(hrNow)) {
            s.append("Your heart rate averaged about ").append(Math.round(hrNow)).append(" beats a minute. ");
        }
        boolean irregular = r.afState == AfScreen.IRREGULAR || r.afState == AfScreen.AF_LIKE
                || (r.rhythm != null && r.rhythm.verdict == EcgRhythm.Verdict.IRREGULAR);
        boolean regular = !irregular && r.rhythm != null && r.rhythm.verdict == EcgRhythm.Verdict.REGULAR;
        if (irregular) {
            s.append("The timing between your beats looked uneven. Early beats, movement or poor contact can cause this, so repeat the reading. ");
            if (r.afState == AfScreen.AF_LIKE) {
                s.append("The pattern can also be seen with atrial fibrillation; this research screen cannot tell the difference. ");
            }
            s.append("Speak to a doctor if it keeps happening or you feel unwell.");
        } else if (regular) {
            s.append("The timing between your beats looked steady.");
        } else {
            s.append("There were not enough clean beats in a row to judge the rhythm.");
        }
        if (g.letter == 'C') s.append(" Only part of this reading was clean, so treat the numbers as a guide and try again with a longer, stiller hold.");
        return esc(s.toString());
    }

    /** {value, caption, colourVar} */
    static String[] rhythmTile(EcgR16Analyzer.Result r) {
        boolean irregular = r.afState == AfScreen.IRREGULAR || r.afState == AfScreen.AF_LIKE
                || (r.rhythm != null && r.rhythm.verdict == EcgRhythm.Verdict.IRREGULAR);
        if (irregular) return new String[]{"Irregular", "Gaps between beats were uneven. This is not a diagnosis.", "var(--amber)"};
        if (r.rhythm != null && r.rhythm.verdict == EcgRhythm.Verdict.REGULAR) {
            return new String[]{"Regular", r.rhythm.intervals + " even beats in a row. Regular timing is not the same as a healthy heart.", "var(--green)"};
        }
        String why = r.rhythm != null && r.rhythm.reason != null && r.rhythm.reason.length() > 0 ? "Not enough clean beats in a row to judge." : "Not judged this time.";
        return new String[]{"Not judged", why, null};
    }

    static String reliabilityLine(Grade g, EcgR16Analyzer.Result r) {
        if (g.letter == 'D' && r.noiseFraction > 0.10) return "Grade D. The signal was there but too noisy: many heartbeats had a distorted shape.";
        switch (g.letter) {
            case 'A': return "Grade A. Most of the reading was clean, so the numbers are well supported.";
            case 'B': return "Grade B. Most of the reading was clean.";
            case 'C': return "Grade C. The beats we could measure look trustworthy, but there were not many of them.";
            default: return "Grade D. Too little of the reading was clean to rely on.";
        }
    }

    static String tachCaption(EcgR16Analyzer.Result r) {
        double med = median(cleanRr(r));
        int odd = 0;
        for (int i = 0; i < r.rrMs.length; i++) if (!r.rrBad[i] && (r.rrMs[i] > 1.7 * med || r.rrMs[i] < 0.6 * med)) odd++;
        String s = "Most gaps were close to " + String.format(Locale.US, "%.1f", med / 1000.0) + " seconds.";
        if (odd > 0) {
            s += " The amber markers at the edges are gaps about double or half the usual, most likely a beat the detector missed or doubled rather than a skipped heartbeat.";
        }
        return s;
    }


    // ------------------------------------------------------------------ research-only reading and explanations

    static String opinionCard(EcgOpinion.Result o) {
        StringBuilder b = new StringBuilder("<section class=\"card\"><div class=\"row2\"><div class=\"k\">Research-only reading</div><span class=\"pill\">Not a diagnosis</span></div>");
        if (!o.summary.isEmpty()) b.append("<p class=\"lead\">").append(esc(o.summary)).append("</p>");
        for (EcgOpinion.Item it : o.items) {
            b.append("<div class=\"op\"><span class=\"dot l").append(it.level).append("\"></span><div><b>").append(esc(it.label))
                    .append("</b> <span class=\"mut\">").append(esc(it.value)).append("</span><p class=\"cap\">").append(esc(it.text)).append("</p></div></div>");
        }
        if (!o.ask.isEmpty()) {
            b.append("<h3>Worth knowing</h3><ul>");
            for (String a : o.ask) b.append("<li>").append(esc(a)).append("</li>");
            b.append("</ul>");
        }
        b.append("<p class=\"cap\">Green dot: inside the usual range. Amber: worth noting. Grey: could not be judged. "
                + "Every comparison is with published reference values for healthy adults and standard 12-lead limits, applied to a single lead from wrist to finger.</p></section>");
        return b.toString();
    }

    static String why(String title, String... paras) {
        StringBuilder b = new StringBuilder("<details class=\"why\"><summary>").append(title).append("</summary><div class=\"wb\">");
        for (String p : paras) b.append("<p>").append(p).append("</p>");
        return b.append("</div></details>").toString();
    }

    static String explainSection(EcgR16Analyzer.Result r, Grade g, EcgOpinion.Result o) {
        double hr = reportHr(r);
        StringBuilder b = new StringBuilder("<section class=\"card\"><h2>What each box means</h2><p class=\"hint\">Tap a heading to open it.</p>");
        b.append(why("Reading quality (grade " + g.letter + ")",
                "The grade says how much of the recording could be trusted, not how healthy your heart is. It compares the seconds that were clean enough to measure with the seconds recorded, and counts heartbeats whose shape was distorted by movement or poor contact.",
                "A: at least 80% clean and under 3% distorted beats. B: 60% and 6%. C: 30% or 25 seconds. D: anything less, or too noisy to tell beats apart."));
        b.append(why("Heart rate" + (Double.isNaN(hr) ? "" : " (" + Math.round(hr) + " bpm)"),
                "Counted from the gaps between R peaks, the tall spikes of each beat, in the clean stretches. Shown as the average over those stretches.",
                "The usual adult resting range is 60 to 100 beats a minute. Fit people are often in the 40s and 50s. Recent activity, caffeine, stress, heat, illness, dehydration and standing up all raise it. Compare with your own readings taken in the same way."));
        b.append(why("HRV, RMSSD" + (r.inconclusive || Double.isNaN(r.rmssd) ? "" : " (" + Math.round(r.rmssd) + " ms)"),
                "Heart rate variability is how much the gap between beats changes from one beat to the next. RMSSD is the root mean square of those successive changes, in milliseconds. It mostly reflects the vagus nerve's calming influence, rising and falling with each breath.",
                "Higher usually means more relaxed and better recovered; lower is expected with age, tiredness, poor sleep, alcohol, caffeine, illness, stress and shallow breathing. Healthy adults measured over 5 minutes average 42 ms (usual range 19 to 75 ms). Your recording is shorter and seated, and uneven beats or missed detections distort the number, so use it mainly to compare with your own readings.",
                "How it was calculated: gaps between clean beats, with any gap that touches an odd-shaped beat or differs by more than 20% from its neighbours set aside first."));
        b.append(why("Rhythm timing",
                "Whether the gaps between beats form a steady pattern. It uses a published screen (Dash et al. 2009) that looks at three things together: how much successive gaps differ (above 0.10 of the average gap), how unpredictable the pattern is (entropy above 0.70), and whether the ups and downs look random.",
                "Steady means none of the screen's AF-like signs were present. Irregular means the gaps were uneven, which early beats, mis-detected beats, movement or poor contact can all cause as well as an irregular rhythm. It looks only at timing, never at the shape of the beats."));
        b.append(why("Early beats",
                "A beat that arrives clearly sooner than the pattern predicts, followed by a longer pause. Occasional early beats happen in most healthy people. This count comes from timing alone, so it cannot say where in the heart they start."));
        b.append(why("QT interval and corrected QT",
                "QT is the time from the start of the QRS (the main spike) to the end of the T wave, which is the heart's electrical recharging after each beat. It shortens as the heart speeds up, so it is corrected for rate. This report uses Fridericia's formula, QT divided by the cube root of the beat-to-beat gap in seconds, which holds up better than Bazett's at faster and slower rates.",
                "The start is found where the QRS begins (slope method). The end is found with the tangent method: a line along the steepest downslope of the T wave is extended to the baseline. The range shown comes from 200 resamplings of the averaged beats.",
                "It needs about 40 clean beats, a T wave at least 8 times the noise, and a rate between 40 and 110 beats a minute, and it is withheld when the rhythm is uneven. It is experimental and has not been checked against a 12-lead ECG."));
        b.append(why("The average heartbeat, R wave height and T wave vs noise",
                "Every clean beat is laid on top of the others and averaged, which cancels random noise and leaves the typical beat. <b>P</b> is the top chambers contracting, <b>Q R S</b> the main pump, <b>T</b> the pump resetting.",
                "R wave height is the size of the main spike. It changes with how you sit or lie, arm position, breathing, skin dryness and electrode contact, so a different value on another day is usually one of these and not a sign about your heart. The band's own contact check is not sensitive enough to tell which. T wave vs noise says how many times larger the T wave is than the leftover noise; QT is only measured above 8 times.",
                "The width of the R wave and the span between the Q and S dips are shape measurements only. They are not the QRS duration clinicians quote, which depends on the filter and needs a 12-lead ECG to check."));
        b.append(why("Fine detail of the main spike (the wide-band view)",
                "The strap does not low-pass its stored ECG at 40 Hz: it records 500 samples a second and the noise and signal both extend towards 250 Hz. The standard view filters to 0.5&ndash;40 Hz to keep noise down. The detail view uses 0.5&ndash;150 Hz on the same averaged beat.",
                "A wider band does not help the rhythm check. The rhythm check uses beat timing, and in testing a wider band made the beat timing no sharper because it let in more noise than it added in steepness. Atrial fibrillation is judged from the timing of the beats and from the small waves before them, which sit between roughly 4 and 10 Hz, inside the standard band.",
                "What the detail view can show is the finer shape of the main spike: sharper points and deeper dips that the standard filter rounds off. It only works on an average of many clean beats; a single beat is too noisy at that bandwidth."));
        b.append(why("The charts",
                "<b>Ten seconds of your heartbeat</b>: the cleaned trace with each detected beat marked. <b>Time between beats</b>: each dot is one gap; an amber marker is a gap about double or half the usual, usually a missed or doubled detection. <b>Steadiness plot</b>: each dot compares one gap with the next; a tight cluster on the dashed line is a steady rhythm.",
                "<b>Heart rate across the reading</b>: the average in 12-second steps; rises and falls with breathing are normal. <b>Which seconds counted</b>: green seconds were measured, amber were settling or too short, grey were too weak, red lost contact."));
        b.append(why("How the numbers were produced",
                "The stored 500-per-second ECG is filtered between 0.67 and 40 Hz with no phase shift (zero-phase filtering, within the limit the AHA allows for linear digital filters) and a 50 Hz notch. Beats are found with a band-pass peak detector and checked for shape against the median beat. Beat gaps are cleaned before the rhythm screen and the variability numbers.",
                "Everything is computed on the phone from the band's stored recording. Nothing is sent anywhere."));
        return b.append("</section>").toString();
    }


    static int okBands(List<EcgIntervals.Band> bands) {
        int n = 0;
        if (bands != null) for (EcgIntervals.Band b : bands) if (b.res != null && b.res.status == EcgIntervals.Status.OK) n++;
        return n;
    }

    /** QT measured separately in heart-rate bands (only when the rate changed during the recording). */
    static String bandsCard(List<EcgIntervals.Band> bands) {
        if (okBands(bands) < 2) return "";
        StringBuilder b = new StringBuilder("<section class=\"card\"><div class=\"row2\"><div class=\"k\">QT across heart rates</div><span class=\"pill\">Experimental</span></div>");
        b.append("<p>Your heart rate changed during this recording, so the beats were split by rate and each group was measured on its own.</p>");
        double minF = Double.MAX_VALUE, maxF = -Double.MAX_VALUE, minB = Double.MAX_VALUE, maxB = -Double.MAX_VALUE;
        int ok = 0;
        b.append("<div class=\"rows\">");
        for (int i = bands.size() - 1; i >= 0; i--) {                 // fastest first
            EcgIntervals.Band bd = bands.get(i);
            String lab = "about " + Math.round(bd.hr) + " bpm";
            StringBuilder val = new StringBuilder();
            if (bd.res.status == EcgIntervals.Status.OK) {
                double qtcB = bd.res.qtMs / Math.sqrt(bd.rrS);
                val.append(String.format(Locale.US, "QT %.0f ms, corrected %.0f ms, %d beats", bd.res.qtMs, bd.res.qtcFMs, bd.res.beatsUsed));
                minF = Math.min(minF, bd.res.qtcFMs); maxF = Math.max(maxF, bd.res.qtcFMs);
                minB = Math.min(minB, qtcB); maxB = Math.max(maxB, qtcB);
                ok++;
            } else {
                val.append("not measured (").append(bd.res.reason.isEmpty() ? bd.res.status.toString() : bd.res.reason).append(")");
            }
            b.append(row(lab, val.toString()));
        }
        b.append("</div>");
        if (ok >= 2) {
            b.append(String.format(Locale.US, "<p>QT gets shorter as the heart speeds up, so it is corrected for rate. The corrected values differ by <b>%.0f ms</b> across these bands (Fridericia). "
                    + "Bazett's older formula would differ by %.0f ms. Smaller means the correction is working for your heart at these rates.</p>", maxF - minF, maxB - minB));
        } else {
            b.append("<p class=\"cap\">Fewer than two bands had enough clean beats to compare.</p>");
        }
        b.append("<p class=\"cap\">Single lead from wrist to finger, not checked against a 12-lead ECG. Not a diagnosis.</p></section>");
        return b.toString();
    }


    // ------------------------------------------------------------------ fine detail (wide band) view

    /** Zoomed QRS: the standard 0.5-40 Hz average beat against the same beats at 0.5-150 Hz. Null-safe. */
    static String detailCard(EcgIntervals.Result q) {
        if (q == null || q.avg == null || q.avgWide == null || q.beatsUsed < 30) return "";
        double[] a = q.avg, w = q.avgWide;
        final int H = 200, r = EcgIntervals.PRE;
        final double t0 = -80, t1 = 110;
        int i0 = r + (int) (t0 * EcgIntervals.FS / 1000.0), i1 = r + (int) (t1 * EcgIntervals.FS / 1000.0);
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (int i = i0; i <= i1; i++) { lo = Math.min(lo, Math.min(a[i], w[i])); hi = Math.max(hi, Math.max(a[i], w[i])); }
        double pad = 0.08 * (hi - lo);
        lo -= pad; hi += pad;
        final double top = 14, bottom = H - 34;
        StringBuilder b = new StringBuilder("<section class=\"card\"><h2>Fine detail of the main spike</h2><p class=\"hint\">The same beats at two bandwidths, zoomed in.</p>");
        b.append(svgOpen(H, "The main spike of the average beat at two bandwidths, 40 hertz and 150 hertz"));
        for (int ms = -60; ms <= 100; ms += 40) {
            double x = (ms - t0) / (t1 - t0) * W;
            b.append(line(x, top, x, bottom, "gl")).append(text(x, H - 16, String.valueOf(ms), "axis", "middle"));
        }
        b.append(text(W / 2.0, H - 2, "milliseconds from the R peak", "axis", "middle"));
        double yz = bottom - (0 - lo) / (hi - lo) * (bottom - top);
        if (yz > top && yz < bottom) b.append(line(0, yz, W, yz, "gl"));
        for (int pass = 0; pass < 2; pass++) {
            double[] d = pass == 0 ? a : w;
            StringBuilder pts = new StringBuilder();
            for (int i = i0; i <= i1; i++) {
                double ms = (i - r) * 1000.0 / EcgIntervals.FS;
                pts.append(f((ms - t0) / (t1 - t0) * W)).append(',').append(f(bottom - (d[i] - lo) / (hi - lo) * (bottom - top))).append(' ');
            }
            b.append("<polyline points=\"").append(pts).append("\" class=\"").append(pass == 0 ? "trace thick" : "trace thin2").append("\"></polyline>");
        }
        b.append("</svg><div class=\"legend\"><span><i class=\"sw g\"></i>Standard, 0.5&ndash;40 Hz</span><span><i class=\"sw w\"></i>Detail, 0.5&ndash;150 Hz</span></div>");
        double rS = peak(a, r - 12, r + 12, true) - median(a, r - 100, r - 60), rD = peak(w, r - 12, r + 12, true) - median(w, r - 100, r - 60);
        double sS = median(a, r - 100, r - 60) - peak(a, r + 3, r + 40, false), sD = median(w, r - 100, r - 60) - peak(w, r + 3, r + 40, false);
        b.append("<div class=\"rows\">")
                .append(row("R wave height", String.format(Locale.US, "%.0f &micro;V standard, %.0f &micro;V detail", rS, rD)))
                .append(row("S dip depth", String.format(Locale.US, "%.0f &micro;V standard, %.0f &micro;V detail", sS, sD)))
                .append("</div>");
        b.append("<p class=\"cap\">The strap records 500 samples a second with no 40 Hz cut-off, so a wider view is possible. Averaging ")
                .append(q.beatsUsed).append(" beats removes most of the noise, which is why detail above 40 Hz shows here but not in a single beat. "
                        + "Sharper or deeper points on the detail trace are real signal that the standard view smooths away. This is a morphology view: it does not change the heart rate, "
                        + "the rhythm timing or any other number, because a wider band did not make beat timing more precise in testing. Not a diagnosis.</p></section>");
        return b.toString();
    }

    static double peak(double[] a, int from, int to, boolean max) {
        double v = max ? -Double.MAX_VALUE : Double.MAX_VALUE;
        for (int i = Math.max(0, from); i <= to && i < a.length; i++) v = max ? Math.max(v, a[i]) : Math.min(v, a[i]);
        return v;
    }

    static double median(double[] a, int from, int to) {
        double[] c = Arrays.copyOfRange(a, Math.max(0, from), Math.min(a.length, to));
        Arrays.sort(c);
        return c.length == 0 ? 0 : c[c.length / 2];
    }

    // ------------------------------------------------------------------ QT card

    static String qtCard(EcgIntervals.Result q, List<EcgIntervals.Band> bands) {
        StringBuilder b = new StringBuilder("<section class=\"card\"><div class=\"row2\"><div class=\"k\">QT interval</div><span class=\"pill\">Experimental</span></div>");
        if (q != null && q.status == EcgIntervals.Status.OK) {
            b.append("<div class=\"big\">").append(String.format(Locale.US, "%.0f", q.qtMs)).append(" <small>ms</small></div><p>95% range ")
                    .append(String.format(Locale.US, "%.0f&ndash;%.0f", q.ciLoMs, q.ciHiMs)).append(" ms. Corrected for heart rate (Fridericia): <b>")
                    .append(String.format(Locale.US, "%.0f", q.qtcFMs)).append(" ms</b>, from ").append(q.beatsUsed)
                    .append(" averaged beats.</p><p class=\"cap\">Single lead from wrist to finger and not checked against a 12-lead ECG. Smartwatch QT studies differ from a 12-lead by up to about 60 ms. Not a diagnosis.</p>");
        } else {
            String why = q == null ? "It needs about 40 clean beats and a clear T wave."
                    : "It needs about 40 clean beats and a clear T wave. " + esc(q.reason.isEmpty() ? "" : "This time: " + q.reason + ".");
            boolean banded = okBands(bands) >= 2;
            b.append("<div class=\"big\">").append(banded ? "See the heart-rate bands below" : "Not measured this time").append("</div><p>").append(why)
                    .append(banded ? " Your heart rate changed during the recording, so one average would blur the T wave. It was measured in separate heart-rate bands instead."
                            : (bands != null && !bands.isEmpty() ? " Your heart rate changed during the recording and the beats were measured in separate rate bands, but the T wave was too small or too noisy in each band to place its end. A small T wave is normal soon after exercise. Measure again at rest." : ""))
                    .append(!banded && q != null && q.avg != null ? " A preview is shown on the average heartbeat below." : "").append("</p>");
        }
        return b.append("</section>").toString();
    }

    // ------------------------------------------------------------------ charts

    static final int W = 342;

    static String svgOpen(int h, String label) {
        return "<svg viewBox=\"0 0 " + W + " " + h + "\" role=\"img\" aria-label=\"" + esc(label) + "\" preserveAspectRatio=\"xMidYMid meet\">";
    }

    static String f(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "0";
        String s = String.format(Locale.US, "%.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    static String line(double x1, double y1, double x2, double y2, String cls) {
        return "<line x1=\"" + f(x1) + "\" y1=\"" + f(y1) + "\" x2=\"" + f(x2) + "\" y2=\"" + f(y2) + "\" class=\"" + cls + "\"></line>";
    }

    static String text(double x, double y, String s, String cls, String anchor) {
        return "<text x=\"" + f(x) + "\" y=\"" + f(y) + "\" class=\"" + cls + "\" text-anchor=\"" + anchor + "\">" + s + "</text>";
    }

    static String dot(double x, double y, double rad, String cls) {
        return "<circle cx=\"" + f(x) + "\" cy=\"" + f(y) + "\" r=\"" + f(rad) + "\" class=\"" + cls + "\"></circle>";
    }

    /** 10 s of the cleaned trace with beat markers; null if there is no clean 10 s. */
    static String traceChart(EcgR16Analyzer.Strip s, boolean inverted) {
        if (s == null || s.mv.length < 10 * EcgR16Analyzer.FS) return null;
        final int fs = EcgR16Analyzer.FS, win = 10 * fs;
        int best = -1, bestBeats = -1;
        for (int st = 0; st + win <= s.mv.length; st += fs) {
            boolean ok = true;
            for (int i = st; i < st + win; i += 5) {
                if (Double.isNaN(s.mv[i]) || s.quality[i] != 3) { ok = false; break; }
            }
            if (!ok) continue;
            int nb = 0;
            for (int bi : s.beats) if (bi >= st && bi < st + win) nb++;
            if (nb > bestBeats) { bestBeats = nb; best = st; }
        }
        if (best < 0) return null;
        double[] w = Arrays.copyOfRange(s.mv, best, best + win);
        if (inverted) for (int i = 0; i < w.length; i++) w[i] = -w[i];          // the recording came in upside down: draw it the usual way up
        double[] sorted = w.clone();
        Arrays.sort(sorted);
        double lo = Math.min(sorted[(int) (0.005 * win)], -0.3) * 1.15;
        double hi = Math.max(0.5, Math.min(sorted[win - 1], 2.0)) * 1.15;
        final int H = 170, L = 46;          // L = left margin for the mV labels
        final double pw = W - L - 2;
        StringBuilder b = new StringBuilder(svgOpen(H, "Ten seconds of the ECG trace with each heartbeat marked"));
        for (double mv = Math.ceil(lo * 2) / 2; mv <= hi; mv += 0.5) {
            double y = H - 26 - (mv - lo) / (hi - lo) * (H - 40);
            b.append(line(L, y, W, y, "gl")).append(text(L - 4, y + 3, mv == 0 ? "0 mV" : String.format(Locale.US, "%.1f mV", mv), "axis", "end"));
        }
        StringBuilder pts = new StringBuilder();
        int step = 4;
        for (int i = 0; i + step <= win; i += step) {
            double pick = w[i];
            for (int k = i; k < i + step; k++) if (Math.abs(w[k]) > Math.abs(pick)) pick = w[k];
            double y = H - 26 - (Math.min(Math.max(pick, lo), hi) - lo) / (hi - lo) * (H - 40);
            pts.append(f(L + i / (double) win * pw)).append(',').append(f(y)).append(' ');
        }
        b.append("<polyline points=\"").append(pts).append("\" class=\"trace\"></polyline>");
        for (int bi : s.beats) {
            if (bi < best || bi >= best + win) continue;
            double x = L + (bi - best) / (double) win * pw;
            b.append("<polygon points=\"").append(f(x - 4)).append(",4 ").append(f(x + 4)).append(",4 ").append(f(x)).append(",12\" class=\"mark\"></polygon>");
        }
        b.append(text(L, H - 8, "0 s", "axis", "start")).append(text(L + pw / 2.0, H - 8, "5 s", "axis", "middle")).append(text(W - 2, H - 8, "10 s", "axis", "end"));
        return b.append("</svg>").toString();
    }

    /** The averaged beat with P, Q, R, S, T labels and the QT bracket. */
    static String avgChart(EcgIntervals.Result q) {
        double[] a = q.avg;
        final int H = 250, n = a.length;
        double t0 = -EcgIntervals.PRE * 1000.0 / EcgIntervals.FS, t1 = EcgIntervals.POST * 1000.0 / EcgIntervals.FS;
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (double v : a) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
        lo -= 90; hi += 40;
        StringBuilder b = new StringBuilder(svgOpen(H, "The average heartbeat with the P, Q, R, S and T waves labelled and the QT interval marked"));
        final double top = 24, bottom = H - 78;
        for (int ms = -400; ms <= 600; ms += 200) {
            double x = (ms - t0) / (t1 - t0) * W;
            b.append(line(x, top, x, bottom + 52, "gl")).append(text(Math.min(Math.max(x, 10), W - 10), H - 14, String.valueOf(ms), "axis", "middle"));
        }
        b.append(text(W / 2.0, H - 1, "milliseconds from the R peak", "axis", "middle"));
        StringBuilder pts = new StringBuilder();
        for (int i = 0; i < n; i++) {
            double ms = (i - EcgIntervals.PRE) * 1000.0 / EcgIntervals.FS;
            double x = (ms - t0) / (t1 - t0) * W;
            double y = bottom - (a[i] - lo) / (hi - lo) * (bottom - top);
            pts.append(f(x)).append(',').append(f(y)).append(' ');
        }
        b.append("<polyline points=\"").append(pts).append("\" class=\"trace thick\"></polyline>");
        if (!Double.isNaN(q.qtMs)) {
            double xq = (q.qrsOnsetMs - t0) / (t1 - t0) * W, xt = (q.tEndMs - t0) / (t1 - t0) * W, yb = bottom + 30;
            b.append(line(xq, yb, xt, yb, "qt")).append(line(xq, yb - 6, xq, yb + 6, "qt")).append(line(xt, yb - 6, xt, yb + 6, "qt"));
            String lab = q.status == EcgIntervals.Status.OK ? "QT about " : "QT preview about ";
            b.append(text((xq + xt) / 2, yb - 9, lab + Math.round(q.qtMs) + " ms", "qtlab", "middle"));
        }
        int r = EcgIntervals.PRE;
        b.append(waveLabel("P", a, r - 85, r - 40, true, lo, hi, t0, t1, top, bottom, -9));
        b.append(waveLabel("Q", a, r - 45, r - 6, false, lo, hi, t0, t1, top, bottom, 15));
        b.append(waveLabel("R", a, r - 10, r + 10, true, lo, hi, t0, t1, top, bottom, -8));
        b.append(waveLabel("S", a, r + 4, r + 30, false, lo, hi, t0, t1, top, bottom, 15));
        b.append(waveLabel("T", a, r + 75, r + 225, true, lo, hi, t0, t1, top, bottom, -10));
        return b.append("</svg>").toString();
    }

    static String waveLabel(String lab, double[] a, int from, int to, boolean max, double lo, double hi, double t0, double t1,
                            double top, double bottom, double dy) {
        int k = from;
        for (int i = from; i <= to && i < a.length; i++) if (max ? a[i] > a[k] : a[i] < a[k]) k = i;
        double ms = (k - EcgIntervals.PRE) * 1000.0 / EcgIntervals.FS;
        double x = (ms - t0) / (t1 - t0) * W, y = bottom - (a[k] - lo) / (hi - lo) * (bottom - top);
        return text(x, y + dy, lab, "wave", "middle");
    }

    // ---- beat-to-beat charts

    static double[] cleanRr(EcgR16Analyzer.Result r) {
        double[] t = new double[r.rrMs.length];
        int n = 0;
        for (int i = 0; i < t.length; i++) if (!r.rrBad[i]) t[n++] = r.rrMs[i];
        return Arrays.copyOf(t, n);
    }

    static double median(double[] v) {
        if (v.length == 0) return 1000;
        double[] c = v.clone();
        Arrays.sort(c);
        return c.length % 2 == 1 ? c[c.length / 2] : 0.5 * (c[c.length / 2 - 1] + c[c.length / 2]);
    }

    /** {lo, hi} in ms, shared by the time-between-beats chart and the steadiness plot. */
    static double[] tachAxis(EcgR16Analyzer.Result r) {
        double[] c = cleanRr(r);
        if (c.length == 0) return new double[]{800, 1200};
        double[] s = c.clone();
        Arrays.sort(s);
        double med = median(c), p5 = s[(int) (0.05 * (s.length - 1))], p95 = s[(int) (0.95 * (s.length - 1))];
        double lo = Math.floor((Math.min(p5, med - 100) - 40) / 50) * 50, hi = Math.ceil((Math.max(p95, med + 100) + 40) / 50) * 50;
        return new double[]{Math.max(lo, 250), Math.min(hi, 2200)};
    }

    static String tachChart(EcgR16Analyzer.Result r, double[] ax) {
        final int H = 170;
        double total = Math.max(1, r.secondStates.length);
        double lo = ax[0], hi = ax[1];
        StringBuilder b = new StringBuilder(svgOpen(H, "Time between each pair of heartbeats across the whole reading"));
        double step = (hi - lo) > 500 ? 200 : 100;
        for (double v = Math.ceil(lo / step) * step; v <= hi; v += step) {
            double y = H - 26 - (v - lo) / (hi - lo) * (H - 40);
            b.append(line(0, y, W, y, "gl")).append(text(2, y - 3, (long) v + " ms", "axis", "start"));
        }
        for (int i = 0; i < r.rrMs.length; i++) {
            double x = r.rrTimeS[i] / total * W, v = r.rrMs[i];
            if (v > hi) b.append("<polygon points=\"").append(f(x - 4)).append(",12 ").append(f(x + 4)).append(",12 ").append(f(x)).append(",4\" class=\"mark\"></polygon>");
            else if (v < lo) b.append("<polygon points=\"").append(f(x - 4)).append(',').append(H - 30).append(' ').append(f(x + 4)).append(',').append(H - 30).append(' ').append(f(x)).append(',').append(H - 22).append("\" class=\"mark\"></polygon>");
            else b.append(dot(x, H - 26 - (v - lo) / (hi - lo) * (H - 40), 2.4, r.rrBad[i] ? "dotbad" : "dot"));
        }
        for (int s = 0; s <= 180; s += 60) if (s <= total) b.append(text(Math.min(Math.max(s / total * W, 8), W - 8), H - 8, s + " s", "axis", "middle"));
        return b.append("</svg>").toString();
    }

    static String poincare(EcgR16Analyzer.Result r, double[] ax) {
        double lo = ax[0], hi = ax[1];
        final int S = 250, pad = 34;
        StringBuilder pts = new StringBuilder();
        int n = 0;
        for (int i = 0; i + 1 < r.rrMs.length; i++) {
            if (r.rrBad[i] || r.rrBad[i + 1]) continue;
            if (Math.abs((r.rrTimeS[i + 1] - r.rrTimeS[i]) * 1000.0 - r.rrMs[i + 1]) > 8) continue;   // not consecutive
            double a = r.rrMs[i], c = r.rrMs[i + 1];
            if (a < lo || a > hi || c < lo || c > hi) continue;
            pts.append(dot(pad + (a - lo) / (hi - lo) * (S - pad - 10), S - 26 - (c - lo) / (hi - lo) * (S - 26 - 8), 2.8, "dot"));
            n++;
        }
        if (n < 8) return null;
        StringBuilder b = new StringBuilder("<svg viewBox=\"0 0 " + S + " " + (S + 4) + "\" role=\"img\" aria-label=\"Each dot compares one beat gap with the next\" class=\"sq\">");
        b.append("<rect x=\"").append(pad).append("\" y=\"8\" width=\"").append(S - pad - 10).append("\" height=\"").append(S - 34).append("\" class=\"frame\"></rect>");
        b.append(line(pad, S - 26, S - 10, 8, "ident"));
        double step = (hi - lo) > 500 ? 200 : 100;
        for (double v = Math.ceil(lo / step) * step; v <= hi; v += step) {
            b.append(text(pad + (v - lo) / (hi - lo) * (S - pad - 10), S - 10, String.valueOf((long) v), "axis", "middle"));
            b.append(text(30, S - 26 - (v - lo) / (hi - lo) * (S - 34) + 3, String.valueOf((long) v), "axis", "end"));
        }
        b.append(pts).append(text(S / 2.0 + 10, S, "this gap (ms)", "axis", "middle"));
        return b.append("</svg>").toString();
    }

    static String hrChart(EcgR16Analyzer.Result r) {
        double total = Math.max(1, r.secondStates.length);
        double med = median(cleanRr(r));
        double[] hr = new double[(int) Math.ceil(total / 12.0)];
        Arrays.fill(hr, Double.NaN);
        for (int bin = 0; bin < hr.length; bin++) {
            double sum = 0;
            int c = 0;
            for (int i = 0; i < r.rrMs.length; i++) {
                if (r.rrBad[i] || r.rrMs[i] > 1.4 * med || r.rrMs[i] < 0.6 * med) continue;
                if (r.rrTimeS[i] >= bin * 12 && r.rrTimeS[i] < (bin + 1) * 12) { sum += r.rrMs[i]; c++; }
            }
            if (c >= 4) hr[bin] = 60000.0 / (sum / c);
        }
        double mn = Double.MAX_VALUE, mx = -Double.MAX_VALUE;
        int valid = 0;
        for (double v : hr) if (!Double.isNaN(v)) { mn = Math.min(mn, v); mx = Math.max(mx, v); valid++; }
        if (valid < 3) return null;
        double lo = Math.floor((mn - 3) / 5) * 5, hi = Math.max(Math.ceil((mx + 3) / 5) * 5, lo + 15);
        final int H = 130;
        StringBuilder b = new StringBuilder(svgOpen(H, "Average heart rate over time in twelve second steps"));
        for (double v = lo + 5; v < hi; v += 5) {
            double y = H - 26 - (v - lo) / (hi - lo) * (H - 40);
            b.append(line(0, y, W, y, "gl")).append(text(2, y - 3, (long) v + " bpm", "axis", "start"));
        }
        StringBuilder pts = new StringBuilder(), dots = new StringBuilder();
        for (int bin = 0; bin < hr.length; bin++) {
            if (Double.isNaN(hr[bin])) continue;
            double x = (bin * 12 + 6) / total * W, y = H - 26 - (hr[bin] - lo) / (hi - lo) * (H - 40);
            pts.append(f(x)).append(',').append(f(y)).append(' ');
            dots.append(dot(x, y, 2.6, "dotb"));
        }
        b.append("<polyline points=\"").append(pts).append("\" class=\"hrline\"></polyline>").append(dots);
        for (int s = 0; s <= 180; s += 60) if (s <= total) b.append(text(Math.min(Math.max(s / total * W, 8), W - 8), H - 8, s + " s", "axis", "middle"));
        return b.append("</svg>").toString();
    }

    static String qualityBar(EcgR16Analyzer.Result r) {
        byte[] st = r.secondStates;
        int n = st.length;
        StringBuilder b = new StringBuilder(svgOpen(56, "Second by second map of which parts of the reading were clean enough to use"));
        String[] cls = {"qgrey", "qamber", "qgreen", "qred"};
        int i = 0;
        while (i < n) {
            int j = i;
            while (j < n && st[j] == st[i]) j++;
            double x = i / (double) n * W, w = Math.max((j - i) / (double) n * W, 1);
            b.append("<rect x=\"").append(f(x)).append("\" y=\"4\" width=\"").append(f(w)).append("\" height=\"28\" rx=\"2\" class=\"").append(cls[Math.min(Math.max(st[i], 0), 3)]).append("\"></rect>");
            i = j;
        }
        for (int s = 0; s <= 180; s += 60) if (s <= n) b.append(text(Math.min(Math.max(s / (double) n * W, 8), W - 8), 50, s + " s", "axis", "middle"));
        return b.append("</svg>").toString();
    }

    static String ring(Grade g) {
        double circ = 2 * Math.PI * 36;
        return "<svg class=\"ring\" viewBox=\"0 0 96 96\" role=\"img\" aria-label=\"Reading quality grade " + g.letter + ", " + Math.round(g.fraction * 100) + " percent of the reading was clean\">"
                + "<circle cx=\"48\" cy=\"48\" r=\"36\" class=\"rtrack\"></circle><circle cx=\"48\" cy=\"48\" r=\"36\" class=\"rfill " + (g.letter <= 'B' ? "g" : g.letter == 'C' ? "a" : "r")
                + "\" stroke-dasharray=\"" + f(circ * (g.letter == 'D' ? Math.min(g.fraction, 0.25) : Math.min(1, g.fraction))) + " " + f(circ) + "\" transform=\"rotate(-90 48 48)\"></circle>"
                + "<text x=\"48\" y=\"57\" text-anchor=\"middle\" class=\"grade\">" + g.letter + "</text></svg>";
    }

    // ------------------------------------------------------------------ small pieces

    static String tile(String label, String value, String unit, String caption, String colour) {
        return "<div class=\"card tile\"><div class=\"k\">" + label + "</div><div class=\"val\"" + (colour != null ? " style=\"color:" + colour + "\"" : "") + ">" + value
                + (unit.isEmpty() ? "" : "<small>" + unit + "</small>") + "</div><p class=\"cap\">" + caption + "</p></div>";
    }

    static String row(String k, String v) {
        return "<div class=\"row\"><span>" + k + "</span><span>" + v + "</span></div>";
    }

    static String formatDuration(int secs) {
        return (secs / 60) + " min " + String.format(Locale.US, "%02d", secs % 60) + " s";
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static final String CSS =
            ":root{--bg:#0B0F14;--card:#131A23;--line:#243244;--text:#E8EEF5;--muted:#A3B2C5;--green:#3DDC97;--amber:#FFB454;--red:#FF7A7A;--blue:#6CB6FF;--grey:#46566A}"
            + "@media print{:root{--bg:#fff;--card:#fff;--line:#c9d2dc;--text:#111;--muted:#444;--green:#0a8a54;--amber:#b36200;--red:#c0392b;--blue:#1565c0;--grey:#8a97a6}.card{break-inside:avoid;border:1px solid var(--line)}}"
            + "*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font-family:-apple-system,Roboto,'Segoe UI',system-ui,sans-serif;line-height:1.45}"
            + "main{max-width:480px;margin:0 auto;padding:20px 16px 32px;display:flex;flex-direction:column;gap:14px}"
            + "h1{font-size:24px;margin:0}h2{font-size:17px;margin:0 0 4px}.sub{color:var(--muted);font-size:13px;margin:2px 0 0}"
            + ".card{background:var(--card);border-radius:18px;padding:16px}.q{display:flex;gap:16px;align-items:center}.q p{margin:4px 0 0;color:var(--muted);font-size:14px}"
            + ".k{font-size:11px;font-weight:600;letter-spacing:.08em;text-transform:uppercase;color:var(--muted)}.big{font-size:22px;font-weight:700;margin-top:4px}"
            + ".lead{font-size:15px;line-height:1.5;margin:6px 0 0}.hint{font-size:13px;color:var(--muted);margin:0 0 10px}.cap{font-size:14px;line-height:1.45;color:var(--muted);margin:10px 0 0}"
            + ".grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.tile{min-height:140px}.tile .cap{font-size:13px;margin-top:8px}"
            + ".val{font-size:32px;font-weight:700;margin-top:8px;line-height:1}.val small{font-size:14px;color:var(--muted);font-weight:400;margin-left:6px}"
            + ".row2{display:flex;justify-content:space-between;align-items:center;gap:8px}.pill{border:1px solid var(--amber);color:var(--amber);border-radius:999px;padding:2px 10px;font-size:11px;font-weight:600;letter-spacing:.04em;text-transform:uppercase}"
            + ".grid2{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;margin-top:12px}.mini{border:1px solid var(--line);border-radius:12px;padding:10px}.mini span{display:block;font-size:11px;color:var(--muted)}.mini b{font-size:18px}"
            + ".row{display:flex;justify-content:space-between;gap:12px;padding:10px 0;border-top:1px solid var(--line);font-size:14px}.row span:first-child{color:var(--muted)}.row span:last-child{text-align:right}"
            + "ul{margin:8px 0 0;padding-left:20px;font-size:14px;line-height:1.6;color:var(--muted)}.foot{font-size:12px;color:var(--muted);text-align:center;margin:4px 8px 0}"
            + ".legend{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:6px;margin-top:6px;font-size:13px;color:var(--muted)}.legend i{display:inline-block;width:10px;height:10px;border-radius:2px;margin-right:6px}"
            + "svg{display:block;width:100%;height:auto}svg.ring{width:96px;flex:none}svg.sq{max-width:250px;margin:0 auto}"
            + ".axis{fill:var(--muted);font-size:10px}line.gl{stroke:var(--line);stroke-width:1}line.ident{stroke:var(--grey);stroke-width:1;stroke-dasharray:4 4}line.qt{stroke:var(--amber);stroke-width:2}"
            + ".frame{fill:none;stroke:var(--line)}.trace{fill:none;stroke:var(--green);stroke-width:1.5;stroke-linejoin:round}.trace.thick{stroke-width:2.2}.mark{fill:var(--amber)}"
            + ".dot{fill:var(--green);fill-opacity:.8}.dotbad{fill:var(--grey)}.dotb{fill:var(--blue)}.hrline{fill:none;stroke:var(--blue);stroke-width:2}"
            + ".wave{fill:var(--text);font-size:13px;font-weight:700}.qtlab{fill:var(--amber);font-size:12px;font-weight:600}"
            + ".qgreen{fill:var(--green)}.qamber{fill:var(--amber)}.qgrey{fill:var(--grey)}.qred{fill:var(--red)}"
            + ".rtrack{fill:none;stroke:var(--line);stroke-width:9}.rfill{fill:none;stroke-width:9;stroke-linecap:round}.rfill.g{stroke:var(--green)}.rfill.a{stroke:var(--amber)}.rfill.r{stroke:var(--red)}.grade{fill:var(--text);font-size:30px;font-weight:700}"
            + ".op{display:flex;gap:10px;padding:10px 0;border-top:1px solid var(--line)}.op p{margin:2px 0 0}.mut{color:var(--muted);font-size:13px}"
            + ".dot{width:10px;height:10px;border-radius:5px;margin-top:6px;flex:none}.dot.l0{background:var(--green)}.dot.l1{background:var(--amber)}.dot.l2{background:var(--grey)}h3{font-size:14px;margin:14px 0 2px}"
            + "details.why{border-top:1px solid var(--line);padding:2px 0}details.why summary{cursor:pointer;font-weight:600;font-size:15px;padding:10px 0;list-style:none}"
            + "details.why summary::-webkit-details-marker{display:none}details.why summary::after{content:\" \\25be\";color:var(--muted)}details.why[open] summary::after{content:\" \\25b4\"}"
            + ".legend{display:flex;gap:16px;flex-wrap:wrap;font-size:12px;color:var(--muted);margin:2px 0 8px}.sw{display:inline-block;width:18px;height:3px;border-radius:2px;margin-right:6px;vertical-align:middle}.sw.g{background:var(--green)}.sw.w{background:#cfd8e3}.trace.thin2{stroke:#cfd8e3;stroke-width:1.2;fill:none}.wb p{font-size:14px;line-height:1.5;color:var(--muted);margin:0 0 10px}ol.src{margin:8px 0 0;padding-left:18px;font-size:12px;line-height:1.5;color:var(--muted)}";
}
