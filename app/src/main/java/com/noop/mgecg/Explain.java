package com.noop.mgecg;

// FILE VERSION 0.21.0 (27 Oct 2026): the plain-language explanations behind every card, ring and graph. Pure Java (no Android), so the wording is reviewable and tested.

import java.util.Locale;

/**
 * One method per card or graph. Each returns rows of {heading, text}: what it shows, how to read it, how it is worked out, and what to watch for. The screens add the person's own
 * numbers next to these. Everything here is an ESTIMATE from the strap's own records, for research, not a medical measure; the wording says so where it matters.
 */
final class Explain {
    private Explain() {}

    static final String WHAT = "WHAT IT SHOWS", READ = "HOW TO READ IT", HOW = "HOW IT IS WORKED OUT", WATCH = "WORTH KNOWING";

    static String[][] heartNow(int maxHr) {
        return new String[][] {
            {WHAT, "The heart rate the strap's optical sensor reads at your wrist, about once a second. Green light shines into the skin and the light that comes back pulses with every beat."},
            {READ, "At rest 60 to 100 beats a minute is the usual adult range; your own resting rate is often lower. The zone tells you how hard the heart is working against a maximum of " + maxHr + " bpm."},
            {HOW, "Live from the strap's real-time stream while the link is up, otherwise the newest stored second. Against a chest strap at rest the median difference was 0 bpm."},
            {WATCH, "The optical signal drops out with cold skin, a loose band or hard arm movement. The Strap data row \"Pulse lock lost\" shows how often; when it is high (above 80 % of seconds on a cold working day) the rate is a weaker estimate."}
        };
    }

    static String[][] recoveryBands() {
        return new String[][] {
            {READ, "Above about two thirds the night looked better than your usual (resting heart rate lower, HRV higher). Around the middle it looked like a usual night. Below about a third it looked worse than usual. It compares you with yourself only."},
            {WATCH, "Short or broken nights, alcohol, illness, a late meal and a hard day all move it. A day-to-day change of 10 to 15 points is normal noise."}
        };
    }

    static String[][] effortScale() {
        return new String[][] {
            {READ, "0 is a day at rest, around 8 to 10 is a moderately busy day, 14 and above is a hard day, 21 is the ceiling. The scale flattens near the top, so doubling the work does not double the number."},
            {WATCH, "Effort counts heart-rate time in zones, so it misses work the heart rate does not show (carrying, gripping) and it loses minutes when the optical heart rate drops out. Steps and the Activity card fill part of that gap."}
        };
    }

    static String[][] sleepRing(double goalH) {
        return new String[][] {
            {READ, "The ring fills towards your sleep need of " + String.format(Locale.UK, "%.0f", goalH) + " hours. The coloured parts are the strap's estimate of awake, light, deep and REM sleep through the night."},
            {WATCH, "Stages are estimated from heart rate and movement, not from brain waves, so treat the split as a guide. Hours asleep are the more reliable number."}
        };
    }

    static String[][] sync() {
        return new String[][] {
            {WHAT, "How current the phone's copy of the strap's data is. The strap stores everything itself (about three weeks) and the phone collects it in a pull, normally every 30 minutes."},
            {READ, "\"Pages behind\" is how much the strap holds that the phone has not collected yet; a few pages is a minute or two of data. If you were away from the phone the number is larger and shrinks to nothing after the next pull."},
            {WATCH, "Nothing is lost while the phone is out of range. Sync now asks the strap for everything it holds."}
        };
    }

    static String[][] activity() {
        return new String[][] {
            {WHAT, "Stretches of sustained physical activity found in today's records, including manual work, not only exercise."},
            {HOW, "Each minute is classed from the heart rate as a share of your heart-rate reserve (resting rate to maximum) and from how much the wrist moves. A stretch of active minutes, bridging short pauses such as breaks, becomes one bout with its length, average and peak heart rate. Raised heart rate with no movement (stress, caffeine, heat) is kept apart and is never counted as activity."},
            {WATCH, "The thresholds were chosen from one evening run and are still being tuned on real work days. The strap's own walk and run class is not used because it under-reported a jog."}
        };
    }

    static String[][] journal() {
        return new String[][] {
            {WHAT, "Time-stamped notes you add with one tap: caffeine, a meal, alcohol, stress, a workout, a symptom, going to bed."},
            {HOW, "Each tap is written to the day log with the time. Later, heart rate, HRV and sleep can be lined up against the notes to see what changed after them."}
        };
    }

    static String[][] ecgReadings() {
        return new String[][] {
            {WHAT, "A reading is about one to three minutes of the heart's electrical signal measured between the wrist under the band and the fingertips of the other hand on the clasp."},
            {READ, "Grade A (excellent): at least 80 % of the seconds were usable and almost no beats looked distorted. B (good): at least 60 %. C (fair): usable but patchy. D (poor): too little clean signal, or too many distorted beats, to measure. A report shows only what the clean part supports."},
            {HOW, "Sit with the forearm resting on a table, still and relaxed, two fingers on the indents, for at least a minute. The live graph shows a QRS height. On this strap it ran 0.8 to 0.95 mV in seven readings one evening and 0.45 mV in one morning reading that still graded A, so the number differs between readings and a lower one is not by itself a bad reading. What spoiled a reading was noise between the beats, which was highest in the reading with the fingers on the indents only (grade D). Pressing the fingers on the wrist skin as well did not lower the height."},
            {WATCH, "Research only, not a diagnosis. A regular rhythm in a short reading does not rule anything out, and a poor reading says nothing about the heart. If you feel unwell, get medical advice whatever the report says."}
        };
    }

    static String[][] hrChart() {
        return new String[][] {
            {WHAT, "Your heart rate over the last 24 hours as the median of each five minutes. The shaded stretch is the night the strap recorded."},
            {READ, "Low flat stretches are rest and sleep. Steps up are walking or work; sharp peaks are effort or stress. The lowest point of the night is your resting heart rate."},
            {WATCH, "Where the optical signal drops out the line is built from fewer good seconds. Tap the chart for the full graph, where you can zoom and move."}
        };
    }

    static String[][] zones() {
        return new String[][] {
            {WHAT, "How the last 24 hours were split across five heart-rate zones, as a share of your maximum heart rate."},
            {READ, "Resting and easy are everyday living; steady is brisk walking or work; hard and all out are real effort. Most of a normal day sits in the first two."},
            {HOW, "Every logged second is put in a zone by its heart rate against your maximum. Change the maximum in Settings if yours differs from the default."}
        };
    }

    static String[][] beatToBeat() {
        return new String[][] {
            {WHAT, "Heart-rate variability: how much the time between heartbeats changes from beat to beat. It is reported as RMSSD in milliseconds."},
            {READ, "A higher number usually goes with rest and good recovery, a lower one with strain, poor sleep or illness. Compare it with your own past values, not with other people's."},
            {HOW, "From the ECG, the beat times are exact. From the wrist the strap measures the pulse by light, which reads about 1.5 to 1.8 times higher than a chest strap, so the two columns are not directly comparable. Your own nights absorb the difference."},
            {WATCH, "A short ECG reading is a snapshot, and exercise suppresses HRV for hours: a reading soon after a run or a hard work shift reads low."}
        };
    }

    static String[][] heartOverTime() {
        return new String[][] {
            {WHAT, "The lowest five-minute heart rate of each logged night, newest on the right."},
            {READ, "A steady line is a steady baseline. A rise over several nights can go with poor sleep, stress, alcohol, illness or a hard training block; a fall usually goes with better fitness and recovery."},
            {WATCH, "It needs a few nights before the pattern means anything; fourteen give a solid baseline."}
        };
    }

    static String[][] sleepWeek() {
        return new String[][] {
            {WHAT, "Hours asleep on each of your last seven nights against your sleep need."},
            {READ, "Bars that reach the line met your need. A run of short bars builds sleep debt, which one long night does not fully repay."}
        };
    }

    static String[][] sleepShape() {
        return new String[][] {
            {WHAT, "The night split into awake, light, deep and REM sleep."},
            {READ, "Typical healthy adults spend very roughly 13 to 23 % of sleep in deep sleep and 20 to 25 % in REM, with light sleep making up the rest. Individual nights vary a lot."},
            {WATCH, "These stages are estimated from heart rate and movement, not brain activity. They can be a stage out in places; the whole-night graph shows where two methods agree."}
        };
    }

    static String[][] sleepNight() {
        return new String[][] {
            {WHAT, "Every five minutes of the night, placed in awake, REM, light or deep sleep."},
            {READ, "Solid blocks are where two staging methods agree; dashed blocks are where they differ and the stage is less certain. Deep sleep usually clusters in the first half of the night, REM in the second."}
        };
    }

    static String[][] spo2() {
        return new String[][] {
            {WHAT, "A blood-oxygen estimate from the strap's own readings during sleep."},
            {HOW, "The strap measures in short windows of 30 seconds about every 20 minutes while you sleep. A window counts when it holds at least five in-band seconds (70 to 100), and the night's value is the mean of the window medians. Other values in the same byte are status codes, not readings, and are ignored."},
            {READ, "Typical healthy sleepers sit around 94 to 98 %. A few windows slightly below is common."},
            {WATCH, "This is an estimate that has not been checked against a reference oximeter, so it is not a medical measurement. If you have symptoms or concerns, use a proper oximeter and ask a clinician."}
        };
    }

    static String[][] monitor(String name) {
        if ("Resting heart rate".equals(name)) return new String[][] {
            {WHAT, "The lowest five-minute heart rate of the night: how slowly your heart ticked over when it was most at rest."},
            {READ, "Lower than your usual is good (more recovered); higher than your usual means the night was harder on you. The band shows your usual range; the dot is last night."}};
        if ("Heart rate variability".equals(name)) return new String[][] {
            {WHAT, "Overnight HRV (RMSSD) measured by the wrist's pulse sensor."},
            {READ, "Higher than your usual is good; lower means more strain. The wrist reads higher than a chest strap, so compare only with your own nights."}};
        if ("Breathing".equals(name)) return new String[][] {
            {WHAT, "Breaths a minute through the night, from the rhythm of your heartbeat intervals."},
            {READ, "Adults usually breathe about 12 to 16 times a minute asleep. A sudden rise on a night you were unwell or had been drinking is common."}};
        if ("Blood oxygen".equals(name)) return new String[][] {
            {WHAT, "The night's blood-oxygen estimate (see the SpO2 card in Rest)."},
            {READ, "Typical healthy sleepers sit around 94 to 98 %. Treat it as a trend, not a measurement."}};
        if ("Skin temperature".equals(name)) return new String[][] {
            {WHAT, "The median skin temperature at the wrist during the night."},
            {READ, "It follows the room and the bedding as well as you, so look at the change from your own usual rather than the number. A rise of a degree or more across several nights can go with illness."}};
        return new String[][] {{WHAT, name}};
    }

    static String[][] gradeLine(char letter) {
        switch (letter) {
            case 'A': return new String[][] {{READ, "Excellent: at least 80 % of the seconds were usable and almost no beats looked distorted."}};
            case 'B': return new String[][] {{READ, "Good: at least 60 % of the seconds were usable and few beats looked distorted."}};
            case 'C': return new String[][] {{READ, "Fair: usable but patchy; part of the recording was clean enough to measure."}};
            default: return new String[][] {{READ, "Poor: too little clean signal, or too many distorted beats."}};
        }
    }

    /** 0.21.0: one short paragraph for each Trends graph (the chips are Recovery, Resting HR, HRV, Sleep, Breathing, Skin temp, Blood oxygen). */
    static String trend(String name) {
        if ("Recovery".equals(name)) return "Each night's recovery estimate against the nights before it. Higher is a better night than usual for you (lower resting heart rate, higher HRV). A day-to-day change of 10 to 15 points is normal; look for a run of low nights.";
        if ("Resting HR".equals(name)) return "The lowest five-minute heart rate of each night. A steady line is a steady baseline; several nights rising can go with poor sleep, stress, alcohol, illness or a hard training block, and a slow fall usually goes with better fitness.";
        if ("HRV".equals(name)) return "Overnight heart-rate variability (RMSSD) from the wrist. It moves a lot from night to night, so judge the direction over a week, not one point. The wrist reads higher than a chest strap; compare only with your own nights.";
        if ("Sleep".equals(name)) return "Hours asleep each night. The shaded band is your usual range; compare the line with your sleep need, and watch for a run of short nights, which builds sleep debt.";
        if ("Breathing".equals(name)) return "Breaths a minute through the night, taken from the rhythm of the heartbeat intervals. Adults usually breathe about 12 to 16 times a minute asleep; a jump on a night you were unwell or had been drinking is common.";
        if ("Skin temp".equals(name)) return "The night's median skin temperature at the wrist. It follows the room and the bedding as well as you, so read the change from your own usual: a rise of a degree or more across several nights can go with illness.";
        if ("Blood oxygen".equals(name)) return "The night's blood-oxygen estimate from the strap's sleep-only windows. Typical healthy sleepers sit around 94 to 98 %. It is an estimate that has not been checked against a reference oximeter, so use it as a trend only.";
        return "";
    }

    static String[][] reportMethod() {
        return new String[][] {
            {WHAT, "The professional PDF is made on the phone from the reading's own live raw ECG frames (500 samples a second). It averages the clean beats, measures the P, QRS and T boundaries on the average, and adds heart-rate variability, a study of the part of the signal above 40 Hz, and a chest-strap check when an H10 was recorded."},
            {HOW, "The Java code in the app is a port of a Python analysis. On nine readings the two agree on heart rate within 0.01 bpm, RMSSD within 0.13 ms, QT within 0.3 ms, and QRS and PR within one sample. With an H10 worn, beats are paired and compared: the R-R difference was 0.7 ms on two simultaneous readings."},
            {WATCH, "The method was tested once, on 28 Oct 2026, against public PhysioNet databases: QRS onset and end within about 10 to 14 ms of cardiologists' marks, the T end (QT) within about 40 ms, AF detection from beat times unchanged down to about 50 Hz timing, and a healthy-rhythm reference for variability. Those results are fixed in the report; the app does not download or re-run them. The code (Python and Java) can be saved to Downloads with the button above."}
        };
    }
}
