package com.noop.mgecg;

// FILE VERSION 0.61.0 (15 Nov 2026): NEW. The Android side of the 0.61.0 screen work: three display settings kept in SharedPreferences "ux" (text_step 0 to 2, contrast, big_taps) and the helper that applies the text size to an
// activity (attachBaseContext: the system font scale times 1.0, 1.15 or 1.3, capped at 1.6). Applies to the activities that call scaled(): Today, Heart, ECG list, Rest, You and the wellbeing page. The live ECG reading screen
// (EcgPage inside MainActivity) is not scaled: its fixed-height controls were laid out for the default size and were not re-tested at larger sizes.

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

final class UxKit {
    private UxKit() {}

    static final String PREFS = "ux";
    static final int MUT_HI = 0xFFD3DBE6;      // HealthViews.MUT is 0xFF98A3B3; this is the higher-contrast replacement for muted text

    static SharedPreferences sp(Context c) { return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    static int textStep(Context c) { try { return Math.max(0, Math.min(UxLogic.STEPS - 1, sp(c).getInt("text_step", 0))); } catch (RuntimeException e) { return 0; } }
    static boolean contrast(Context c) { try { return sp(c).getBoolean("contrast", false); } catch (RuntimeException e) { return false; } }
    static boolean bigTaps(Context c) { try { return sp(c).getBoolean("big_taps", false); } catch (RuntimeException e) { return false; } }

    /** For attachBaseContext. Returns [base] unchanged at the normal step or on any error. */
    static Context scaled(Context base) {
        try {
            int st = textStep(base);
            if (st <= 0) return base;
            Configuration cfg = new Configuration(base.getResources().getConfiguration());
            cfg.fontScale = Math.min(1.6f, cfg.fontScale * UxLogic.factor(st));
            return base.createConfigurationContext(cfg);
        } catch (Throwable t) { return base; }
    }

    static int muted(Context c, int col) { return col == HealthViews.MUT && contrast(c) ? MUT_HI : col; }
}
