package com.noop.mgecg;

// FILE VERSION 0.41.0 (5 Nov 2026): guided timed runs (decode log 50.6 items 1 to 4): a cue at each step (text, buzz, beep), the run stops itself at 45 s.
// The cue times are noted with the reading so the analysis needs no stopwatch. Android-free apart from Handler.

import android.os.Handler;
import android.os.Looper;

final class GuidedRun {
    static final class Step { final int sec; final String cue, mark; Step(int s, String c, String m) { sec = s; cue = c; mark = m; } }
    static final class Def {
        final String id, title, setup; final int stopSec; final Step[] steps;
        Def(String id, String title, String setup, int stopSec, Step... steps) { this.id = id; this.title = title; this.setup = setup; this.stopSec = stopSec; this.steps = steps; }
    }
    static final String SETUP = "Forearms on a table, left wrist with the MG facing up, two right-hand fingers firmly on the clasp. Tag Table.";
    static final Def[] ALL = {
        new Def("", "Free reading", "", 0),
        new Def("A", "A: left fist clench", SETUP, 45, new Step(15, "Clench your LEFT fist hard", null), new Step(20, "Release and relax", null)),
        new Def("B", "B: finger slip", SETUP, 45, new Step(15, "Slide both fingers about 3 mm sideways", null), new Step(18, "Slide back", null), new Step(24, "Slide sideways again", null), new Step(27, "Slide back", null)),
        new Def("C", "C: light touch", SETUP, 45, new Step(15, "Ease off to a very light touch (no lifting)", "LIGHT"), new Step(25, "Press firmly again", "FIRM")),
        new Def("D", "D: forearm rotate", SETUP, 45, new Step(15, "Rotate the left forearm 45 degrees outward over 2 s", null), new Step(17, "Hold", null), new Step(20, "Rotate back over 2 s", null)),
        new Def("E", "E: still (morning or evening)", "Same chair, same fingers each time: once 08:00-10:00 and once 19:00-21:00. " + SETUP, 45),
        new Def("F", "F: still (after work)", "Straight after work, before washing your hands, and once before work. " + SETUP, 45)
    };

    static Def byId(String id) { for (Def d : ALL) if (d.id.equals(id == null ? "" : id)) return d; return ALL[0]; }

    interface Sink { void cue(String text, String mark, int sec); void tick(String status); void stop(); }

    private final Handler h = new Handler(Looper.getMainLooper());
    private Def def; private Sink sink; private long t0; private boolean active; private int fired;

    boolean active() { return active; }

    void start(Def d, long startMs, Sink s) {
        cancel(); if (d == null || d.id.isEmpty()) return;
        def = d; sink = s; t0 = startMs; active = true; fired = 0; h.post(loop);
    }
    void cancel() { active = false; h.removeCallbacks(loop); }

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            if (!active || def == null) return;
            double el = (System.currentTimeMillis() - t0) / 1000.0;
            while (fired < def.steps.length && el >= def.steps[fired].sec) { Step st = def.steps[fired++]; if (sink != null) sink.cue(st.cue, st.mark, st.sec); }
            String next = null; for (Step st : def.steps) if (st.sec > el) { next = String.format(java.util.Locale.UK, "Next in %.0f s: %s", st.sec - el, st.cue); break; }
            if (sink != null) sink.tick(fired == 0 && next != null ? "Keep still and relaxed. " + next : (next != null ? next : String.format(java.util.Locale.UK, "Keep still. The run stops by itself at %d s", def.stopSec)));
            if (def.stopSec > 0 && el >= def.stopSec) { active = false; if (sink != null) sink.stop(); return; }
            h.postDelayed(this, 200);
        }
    };
}
