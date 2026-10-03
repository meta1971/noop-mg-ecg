package com.noop.mgecg;

// FILE VERSION 0.2.5 (3 Oct): research-only reading

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * A research-only reading of one recording, built only from numbers the analyzer already measured and from published
 * reference values. It never names a condition as a finding; where a value falls outside a published reference range it
 * says so, says what commonly causes that, and says what would settle the question. Pure Java, no Android.
 *
 * Reference values used (all quoted in the report's source list):
 *  - resting heart rate 60 to 100 bpm (standard adult resting range)
 *  - short-term RMSSD in healthy adults: mean 42 ms, range 19 to 75 ms; SDNN mean 50 ms, range 32 to 93 ms
 *    (Nunan, Sandercock, Brodie 2010, via Shaffer and Ginsberg 2017, Table 6)
 *  - QTc limits, 12-lead (AHA/ACCF/HRS 2009, Part IV): normal below 450 ms (men) and 460 ms (women); prolonged at or
 *    above those; markedly prolonged at or above 500 ms; short at or below 390 ms
 *  - spread of manual QT measurements between experts on the same 12-lead beats: SD about 43 ms (PhysioNet QT
 *    Database validation, Laguna and Jane et al.)
 */
public final class EcgOpinion {

    private EcgOpinion() {}

    public static final int OK = 0, NOTE = 1, LIMITED = 2;      // typical / worth noting / cannot judge

    public static final class Item {
        public final String label, value, text;
        public final int level;
        Item(String label, String value, int level, String text) {
            this.label = label; this.value = value; this.level = level; this.text = text;
        }
    }

    public static final class Result {
        public final List<Item> items = new ArrayList<>();
        public final List<String> ask = new ArrayList<>();
        public String summary = "";
        public double sdnnMs = Double.NaN;
        public double pnn50 = Double.NaN;
    }

    public static Result build(EcgR16Analyzer.Result r, EcgReport.Grade g) {
        Result o = new Result();
        if (g.letter == 'D') {
            o.items.add(new Item("Reading", "not enough clean data", LIMITED,
                    "Too little of this recording was clean to say anything useful. Try again with your forearm resting on a table."));
            o.summary = "There was not enough clean data for a research reading this time.";
            o.ask.add("If you ever feel palpitations, dizziness, fainting, chest pain or breathlessness, get medical advice whatever this report says.");
            return o;
        }

        StringBuilder sum = new StringBuilder();
        boolean irregular = r.afState == AfScreen.IRREGULAR || r.afState == AfScreen.AF_LIKE
                || (r.rhythm != null && r.rhythm.verdict == EcgRhythm.Verdict.IRREGULAR);
        boolean regular = !irregular && r.rhythm != null && r.rhythm.verdict == EcgRhythm.Verdict.REGULAR;

        // ---- heart rate
        double hr = EcgReport.reportHr(r);
        if (!Double.isNaN(hr)) {
            String v = Math.round(hr) + " bpm";
            if (hr < 50) {
                o.items.add(new Item("Heart rate", v, NOTE, "Below 50 beats a minute. This is common in people who train a lot and during rest, and it is not a problem in itself. If you feel faint, dizzy or very tired with a rate this low, mention it to a doctor."));
                sum.append("a slow resting rate of about ").append(Math.round(hr)).append(" bpm");
            } else if (hr < 60) {
                o.items.add(new Item("Heart rate", v, OK, "Just under the usual 60 to 100 resting range. Common in fit people and when completely relaxed."));
                sum.append("a resting rate of about ").append(Math.round(hr)).append(" bpm");
            } else if (hr <= 100) {
                o.items.add(new Item("Heart rate", v, OK, "Inside the usual adult resting range of 60 to 100 beats a minute."));
                sum.append("a resting rate of about ").append(Math.round(hr)).append(" bpm");
            } else {
                o.items.add(new Item("Heart rate", v, NOTE, "Above 100 beats a minute. After activity, caffeine, stress, fever or dehydration this is expected. If it stays above 100 at complete rest on several days, mention it to a doctor."));
                sum.append("a raised rate of about ").append(Math.round(hr)).append(" bpm");
            }
        }

        // ---- rhythm
        if (irregular) {
            o.items.add(new Item("Rhythm timing", "uneven", NOTE,
                    "The gaps between beats were uneven. Early beats (very common and usually harmless), missed or doubled detections, movement and poor contact all cause this, and this screen cannot tell them apart. "
                            + (r.afState == AfScreen.AF_LIKE ? "The pattern also matches what published screens use to flag atrial fibrillation, so a repeat reading matters. " : "")
                            + "One reading is not a finding: repeat it, still and rested."));
            sum.append(sum.length() > 0 ? ", " : "").append("uneven beat timing that needs repeating");
            o.ask.add("An uneven result that repeats on different days is worth showing to a doctor, together with the saved strip image.");
        } else if (regular) {
            String d = r.rhythm != null && r.rhythm.features != null
                    ? String.format(Locale.US, " (successive-difference ratio %.3f, entropy %.2f; the published AF-like pattern needs above 0.10 and above 0.70)", r.rhythm.features[0], r.rhythm.features[4]) : "";
            o.items.add(new Item("Rhythm timing", "steady", OK,
                    "The beat-to-beat timing was steady" + d + ". That is the pattern of a normal sinus rhythm, but timing alone cannot confirm it: it says nothing about the shape of the beats, and a short recording can miss a rhythm that comes and goes."));
            sum.append(sum.length() > 0 ? ", " : "").append("steady beat timing");
        } else {
            o.items.add(new Item("Rhythm timing", "not judged", LIMITED,
                    "There were not enough clean beats in a row to judge the rhythm. A longer, stiller hold usually fixes that."));
        }

        // ---- early beats
        if (r.early > 0) {
            o.items.add(new Item("Early beats", String.valueOf(r.early), NOTE,
                    "Beats that came clearly sooner than the pattern. Occasional early beats are very common in healthy people and are usually harmless; they become worth a conversation if they are frequent, you feel them as skipped beats, or they come with symptoms."));
        } else {
            o.items.add(new Item("Early beats", "0", OK, "No beats arrived clearly early in the clean stretches."));
        }

        // ---- HRV
        computeHrv(r, o);
        if (!r.inconclusive && !Double.isNaN(r.rmssd)) {
            String v = Math.round(r.rmssd) + " ms RMSSD" + (Double.isNaN(o.sdnnMs) ? "" : ", " + Math.round(o.sdnnMs) + " ms SDNN");
            String ref = "In healthy adults measured over about 5 minutes, published values average 42 ms (usual range 19 to 75 ms) for RMSSD and 50 ms (32 to 93 ms) for SDNN. ";
            if (r.rmssd < 19) {
                o.items.add(new Item("Heart rate variability", v, NOTE, ref
                        + "Yours is below that range. That is common with age, tiredness, poor sleep, alcohol or caffeine, a short seated recording, or shallow breathing, and it is not a medical finding on its own. What matters is your own trend over weeks: measure at the same time of day, seated, forearm supported."));
                sum.append(sum.length() > 0 ? "; " : "").append("heart-rate variability below the healthy reference range (").append(Math.round(r.rmssd)).append(" ms against 19 to 75)");
                o.ask.add("A single low HRV reading is not a medical finding. Look at your trend over several weeks before drawing conclusions.");
            } else if (r.rmssd <= 75) {
                o.items.add(new Item("Heart rate variability", v, OK, ref + "Yours is inside that range."));
                sum.append(sum.length() > 0 ? "; " : "").append("heart-rate variability inside the healthy reference range");
            } else {
                o.items.add(new Item("Heart rate variability", v, NOTE, ref + "Yours is above that range. Very high values are usual in young, fit people. They can also come from mis-detected beats or an uneven rhythm, which is why this reading needs a clean, steady recording."));
            }
        }

        // ---- QT
        EcgIntervals.Result q = r.qt;
        if (q != null && q.status == EcgIntervals.Status.OK) {
            double qtc = q.qtcFMs;
            String v = Math.round(qtc) + " ms (QT " + Math.round(q.qtMs) + " ms)";
            String base = "Standard 12-lead limits (AHA/ACCF/HRS 2009): normal below 450 ms in men and 460 ms in women, prolonged at or above those, markedly prolonged at or above 500 ms, and short at or below 390 ms. "
                    + "This reading comes from one lead between wrist and finger, so it has a wide error: smartwatch QT studies differ from a 12-lead by up to about 60 ms, and even experts measuring the same 12-lead beats by hand differ by about 43 ms (standard deviation). ";
            if (qtc >= 500) {
                o.items.add(new Item("Corrected QT (Fridericia)", v, NOTE, base + "Yours is well above the usual limit. Treat it as a reason to get a proper 12-lead ECG soon, not as a result: this device is not accurate enough to tell."));
                sum.append(sum.length() > 0 ? "; " : "").append("a corrected QT well above the usual limit on this single-lead method");
                o.ask.add("Ask for a 12-lead ECG with a measured QT, and tell them about any medicines or supplements you take, because some lengthen the QT interval.");
            } else if (qtc >= 450) {
                o.items.add(new Item("Corrected QT (Fridericia)", v, NOTE, base + "Yours is at or above the usual upper limit for men, within this method's error of normal. A repeat on another day, and a 12-lead ECG, would settle it."));
                sum.append(sum.length() > 0 ? "; " : "").append("a corrected QT near the upper limit on this single-lead method");
                o.ask.add("If this stays at or above 450 ms on repeat readings, ask for a 12-lead ECG with a measured QT.");
            } else if (qtc > 390) {
                o.items.add(new Item("Corrected QT (Fridericia)", v, OK, base + "Yours is inside the usual range."));
                sum.append(sum.length() > 0 ? "; " : "").append("a corrected QT of ").append(Math.round(qtc)).append(" ms, inside the usual range");
            } else {
                o.items.add(new Item("Corrected QT (Fridericia)", v, NOTE, base
                        + "Yours is at or below the short limit. That cannot be called a short QT from this device: single-lead tangent readings can sit a few tens of milliseconds away from a 12-lead measurement, and the direction of that error varies between studies. True short QT is rare. Only a 12-lead ECG can say whether it is real."));
                sum.append(sum.length() > 0 ? "; " : "").append("a corrected QT of ").append(Math.round(qtc)).append(" ms, at the low end, which this single-lead method cannot call short or normal");
                o.ask.add("If you want the QT question settled, ask for a 12-lead ECG with a measured QT.");
            }
        } else if (q != null) {
            o.items.add(new Item("Corrected QT (Fridericia)", "not measured", LIMITED, q.reason.isEmpty()
                    ? "Not enough clean beats or a clear T wave." : "Not measured: " + q.reason + "."));
        }

        // ---- signal quality
        o.items.add(new Item("Signal quality", g.letter + " (" + g.word + ")", g.letter <= 'B' ? OK : NOTE,
                "Grade " + g.letter + " means " + Math.round(g.fraction * 100) + "% of the recording was clean enough to measure. "
                        + (g.letter <= 'B' ? "The numbers above rest on plenty of clean beats." : "Treat the numbers above as a guide and repeat with a longer, stiller hold.")));

        o.summary = sum.length() == 0 ? "" : "Research-only impression: " + sum + ". "
                + "These are measurements compared with published reference ranges, not a diagnosis.";
        o.ask.add(0, "If you ever feel palpitations, dizziness, fainting, chest pain or breathlessness, get medical advice whatever this report says.");
        return o;
    }

    /** SDNN and pNN50 from the clean intervals (those not touching an odd-shaped beat and within 30% of the median). */
    static void computeHrv(EcgR16Analyzer.Result r, Result o) {
        double[] rr = EcgReport.cleanRr(r);
        if (rr.length < 30) return;
        double med = EcgReport.median(rr);
        List<Double> ok = new ArrayList<>();
        for (double v : rr) if (v > 0.7 * med && v < 1.3 * med) ok.add(v);
        if (ok.size() < 30) return;
        double mean = 0;
        for (double v : ok) mean += v;
        mean /= ok.size();
        double s = 0;
        for (double v : ok) s += (v - mean) * (v - mean);
        o.sdnnMs = Math.sqrt(s / (ok.size() - 1));
        int over = 0, n = 0;
        for (int i = 1; i < ok.size(); i++) {
            n++;
            if (Math.abs(ok.get(i) - ok.get(i - 1)) > 50) over++;
        }
        o.pnn50 = n == 0 ? Double.NaN : 100.0 * over / n;
    }

    static String capitalise(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Source list for the footer of the report. */
    public static final List<String> SOURCES = Arrays.asList(
            "Task Force of the ESC and NASPE (1996). Heart rate variability: standards of measurement, physiological interpretation and clinical use. Circulation 93:1043-1065.",
            "Nunan D, Sandercock GR, Brodie DA (2010). A quantitative systematic review of normal values for short-term heart rate variability in healthy adults. Pacing Clin Electrophysiol 33:1407-1417.",
            "Shaffer F, Ginsberg JP (2017). An overview of heart rate variability metrics and norms. Front Public Health 5:258 (Table 6).",
            "Rautaharju PM, Surawicz B, Gettes LS (2009). AHA/ACCF/HRS recommendations for the standardization and interpretation of the electrocardiogram, Part IV: the ST segment, T and U waves, and the QT interval. Circulation 119:e241-e250.",
            "Fridericia LS (1920). Die Systolendauer im Elektrokardiogramm bei normalen Menschen und bei Herzkranken. Acta Med Scand 53:469-486.",
            "Laguna P, Mark RG, Goldberger AL, Moody GB (1997). A database for evaluation of algorithms for measurement of QT and other waveform intervals in the ECG. Computers in Cardiology 24:673-676 (PhysioNet QT Database).",
            "Dash S, Chon KH, Lu S, Raeder EA (2009). Automatic real time detection of atrial fibrillation. Ann Biomed Eng 37:1701-1709.",
            "Richman JS, Moorman JR (2000). Physiological time-series analysis using approximate entropy and sample entropy. Am J Physiol Heart Circ Physiol 278:H2039-H2049.");
}
