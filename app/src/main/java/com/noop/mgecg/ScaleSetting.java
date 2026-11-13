package com.noop.mgecg;

// FILE VERSION 0.54.0 (13 Nov 2026): NEW. The ECG scale (microvolts per R16 count) as ONE versioned setting. Default 0.06358 (1 V / (2^17 x 120), INFERRED, about 80 %); another contributor measured 0.0763 from
// WHOOP's printed report; a known-voltage run decides. Until it is marked verified, every microvolt figure the app shows carries SCALE_TAG. Changing the value (or marking it verified) bumps the version; each reading
// stores the scale it was made with (reading note uv_per_count), so the table can show every old reading at the current scale (counts-linear columns only). Readings before 0.53.0 used 0.0621.

import android.content.Context;
import android.content.SharedPreferences;

final class ScaleSetting {
    private ScaleSetting() {}

    static final double DEFAULT = 0.06358, OLD = 0.0621;
    static final String PREFS = "ecg_scale", SCALE_TAG = "scale provisional ±20 %";
    private static volatile double cached = DEFAULT;
    private static volatile boolean verified;
    private static volatile int version = 1;
    private static volatile boolean loaded;

    private static SharedPreferences sp(Context c) { return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static void load(Context c) {
        try {
            SharedPreferences p = sp(c);
            double v = p.getFloat("uv", (float) DEFAULT);
            cached = (v > 0.02 && v < 0.2) ? v : DEFAULT;
            verified = p.getBoolean("verified", false);
            version = p.getInt("version", 1);
        } catch (Throwable ignored) { }
        loaded = true;
    }

    /** The scale in use now (microvolts per count). Safe to call from any thread; before load() it is the default. */
    static double uv() { if (!loaded) auto(); return cached; }
    /** Any thread, before an activity has called load(): read the setting through the process's Application. */
    private static synchronized void auto() {
        if (loaded) return;
        try { Object app = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null); if (app instanceof Context) load((Context) app); } catch (Throwable ignored) { }
        loaded = true;
    }
    static boolean verified() { return verified; }
    static int version() { return version; }
    static boolean isLoaded() { return loaded; }

    static void set(Context c, double v, boolean markVerified) {
        if (!(v > 0.02 && v < 0.2)) return;
        sp(c).edit().putFloat("uv", (float) v).putBoolean("verified", markVerified).putInt("version", version + 1).apply();
        cached = v; verified = markVerified; version = version + 1;
    }

    static void reset(Context c) { set(c, DEFAULT, false); }

    /** " (scale provisional +-20 %)" until the scale is marked verified, else an empty string. Append it to any text that quotes microvolts. */
    static String tag() { return verified ? "" : " (" + SCALE_TAG + ")"; }

    static String status() {
        return String.format(java.util.Locale.UK, "%.5f µV per count · version %d · %s", cached, version, verified ? "verified with a known voltage" : "provisional, ±20 % until the known-voltage run");
    }

    /** Factor that turns a microvolt figure made with scale `then` (a reading note; absent = the old 0.0621) into the same figure at the scale in use now. */
    static double factorFrom(String thenNote) {
        double then = OLD;
        try { if (thenNote != null && thenNote.length() > 0) { double t = Double.parseDouble(thenNote.trim()); if (t > 0.02 && t < 0.2) then = t; } } catch (NumberFormatException ignored) { }
        return cached / then;
    }
}
