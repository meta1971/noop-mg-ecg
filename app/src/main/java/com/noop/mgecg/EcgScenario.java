package com.noop.mgecg;

// FILE VERSION 0.79.0 (23 Nov 2026): NEW. Which strap high-pass model the headline QT / T / ST of a report use, the custom corner of the scenario table,
// and the roll-off correction of the detail beat, as one stored setting (same pattern as ScaleSetting). The models themselves and the maths are in EcgIntervals
// (pure Java). Default: the 0.40 Hz strap input pole undone (addendum 414), roll-off correction on. A stored 0 means as recorded. Every report lists ALL models whatever is chosen here (addenda 413, 414).

import android.content.Context;
import android.content.SharedPreferences;

final class EcgScenario {
    private EcgScenario() {}

    static final String PREFS = "ecg_scenario";
    private static volatile boolean loaded;

    private static SharedPreferences sp(Context c) { return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static void load(Context c) {
        try {
            SharedPreferences p = sp(c);
            double fc = p.getFloat("fc", 0.40f), cu = p.getFloat("custom", 0f);
            EcgIntervals.SCEN_CUSTOM_HZ = (cu > 0.005 && cu <= 3.0) ? cu : 0;
            EcgIntervals.STRAP_HP_FC_HZ = (fc > 0.005 && fc <= 3.0) ? fc : 0;
            EcgIntervals.HF_COMP = p.getBoolean("hf", true);
        } catch (Throwable ignored) { }
        loaded = true;
    }

    /** Any thread, before an activity has called load(): read the setting through the process's Application. */
    static synchronized void ensure() {
        if (loaded) return;
        try { Object app = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null); if (app instanceof Context) load((Context) app); } catch (Throwable ignored) { }
        loaded = true;
    }

    static double headlineHz() { return EcgIntervals.STRAP_HP_FC_HZ; }
    static boolean hf() { return EcgIntervals.HF_COMP; }

    private static void save(Context c) {
        try { sp(c).edit().putFloat("fc", (float) EcgIntervals.STRAP_HP_FC_HZ).putFloat("custom", (float) EcgIntervals.SCEN_CUSTOM_HZ).putBoolean("hf", EcgIntervals.HF_COMP).apply(); } catch (Throwable ignored) { }
    }

    /** Steps the headline model to the next one in the list (the custom corner, if set, is the last), then back to "as recorded". */
    static void next(Context c) {
        double[] f = EcgIntervals.scenarioFcs();
        int at = 0;
        for (int i = 0; i < f.length; i++) if (Math.abs(f[i] - EcgIntervals.STRAP_HP_FC_HZ) < 1e-6) at = i;
        EcgIntervals.STRAP_HP_FC_HZ = f[(at + 1) % f.length];
        save(c);
    }

    /** Sets the custom corner (0 removes it). If the headline was the old custom corner it falls back to as recorded. */
    static boolean setCustom(Context c, double hz) {
        if (!(hz == 0 || (hz > 0.005 && hz <= 3.0))) return false;
        if (Math.abs(EcgIntervals.STRAP_HP_FC_HZ - EcgIntervals.SCEN_CUSTOM_HZ) < 1e-6 && EcgIntervals.SCEN_CUSTOM_HZ > 0) EcgIntervals.STRAP_HP_FC_HZ = 0;
        EcgIntervals.SCEN_CUSTOM_HZ = hz;
        save(c);
        return true;
    }

    static void toggleHf(Context c) { EcgIntervals.HF_COMP = !EcgIntervals.HF_COMP; save(c); }

    static String headlineShort() {
        double fc = EcgIntervals.STRAP_HP_FC_HZ;
        return fc <= 0 ? "As recorded" : String.format(java.util.Locale.UK, "%.2f Hz undone", fc);
    }

    static String customShort() {
        return EcgIntervals.SCEN_CUSTOM_HZ > 0 ? String.format(java.util.Locale.UK, "%.2f Hz", EcgIntervals.SCEN_CUSTOM_HZ) : "None";
    }

    static String status() {
        double fc = EcgIntervals.STRAP_HP_FC_HZ;
        return (fc <= 0 ? "Headline QT, T and ST are measured on the beat as recorded (as in builds before 0.79.0)."
                : "Headline QT, T and ST of NEW reports undo a " + String.format(java.util.Locale.UK, "%.2f", fc) + " Hz strap high-pass: " + EcgIntervals.scenarioName(fc) + (Math.abs(fc - 0.40) < 1e-6 ? " (default from 0.79.0)" : "") + ". On the wrist this reads QT about 10 ms longer than as recorded, so it is not comparable with readings made before 0.79.0.")
                + " Every report lists all models side by side and prints the as-recorded value. Tap to change.";
    }

    /** For the reading note: the corner the headline of this reading used. */
    static String note() { return String.format(java.util.Locale.US, "%.2f", EcgIntervals.STRAP_HP_FC_HZ); }
}
