package com.noop.mgecg;

// FILE VERSION 0.93.0 (26 Nov 2026): NEW. The numbers and words of the live reading screen (mockup 07), pure Java so they can be tested off the phone.
// Nothing here touches the strap, the frames or any file: it only turns values the reading page already has (elapsed time, good-signal seconds, noise,
// motion, the strap's verdict and HRV times) into ring fractions, one tip and the three card texts.

final class LiveScreenLogic {
    private LiveScreenLogic() {}

    /** The strap needs about 12 s before the signal counts (the page's own settling message). */
    static final double SETTLE_S = 12.0;
    /** The ring is one lap of 60 s; when a reading runs past that the lap grows to 2:30, then 5:00 (the hard cap), never back. */
    static final double[] LAPS = { 60.0, 150.0, 300.0 };

    static final int ST_NO_CONTACT = 0, ST_SETTLING = 1, ST_MEASURING = 2;
    static final int TIP_NO_CONTACT = 1, TIP_SETTLING = 2, TIP_MOVING = 3, TIP_NOISY = 4, TIP_FAIR = 5, TIP_GOOD = 6;

    static final class Ring {
        double lapS;                       // seconds for one lap
        float settle, blue;                // arc lengths as fractions of the lap (settle starts at 0, blue starts where settle ends)
        float verdictAt = Float.NaN, hrvAt = Float.NaN;      // position of the two ticks, fraction of the lap; NaN = not yet
        int state;
        String centre, sub, desc;
    }

    static final class Tip { int kind; String title, body; }

    static String mmss(double s) {
        if (Double.isNaN(s) || s < 0) s = 0;
        long t = (long) Math.floor(s + 1e-9);
        return (t / 60) + ":" + (t % 60 < 10 ? "0" : "") + (t % 60);
    }

    /** The lap for this elapsed time given the lap used so far (it only grows). */
    static double lapFor(double elapsedS, double prevLap) {
        double lap = prevLap > 0 ? prevLap : LAPS[0];
        for (int i = 0; i < LAPS.length - 1; i++) if (lap == LAPS[i] && elapsedS > lap - 5.0) lap = LAPS[i + 1];
        return lap;
    }

    /** verdictS / hrvS: seconds from the start of the reading, NaN or negative when the strap has not given them yet. */
    static Ring ring(double elapsedS, double prevLap, boolean contact, double verdictS, double hrvS) {
        Ring r = new Ring();
        if (Double.isNaN(elapsedS) || elapsedS < 0) elapsedS = 0;
        r.lapS = lapFor(elapsedS, prevLap);
        double settled = Math.min(elapsedS, SETTLE_S), measured = Math.max(0.0, elapsedS - SETTLE_S);
        r.settle = (float) Math.min(1.0, settled / r.lapS);
        r.blue = (float) Math.max(0.0, Math.min(1.0 - r.settle, measured / r.lapS));
        if (!Double.isNaN(verdictS) && verdictS >= 0 && verdictS <= r.lapS) r.verdictAt = (float) (verdictS / r.lapS);
        if (!Double.isNaN(hrvS) && hrvS >= 0 && hrvS <= r.lapS) r.hrvAt = (float) (hrvS / r.lapS);
        r.state = !contact ? ST_NO_CONTACT : elapsedS < SETTLE_S ? ST_SETTLING : ST_MEASURING;
        String word = r.state == ST_NO_CONTACT ? "no contact" : r.state == ST_SETTLING ? "settling" : "measuring";
        r.centre = mmss(elapsedS);
        r.sub = word + "  ·  of " + mmss(r.lapS);
        r.desc = "Reading time " + r.centre + " of " + mmss(r.lapS) + ", " + word + ".";
        return r;
    }

    /**
     * The one tip card. The thresholds are the ones the reading page already used for its signal light:
     * noise in the 8-25 Hz band 27 and 40 uV (n825), or the beat noise 25 and 38 uV (nz) while the band figure is not there yet.
     */
    static Tip tip(boolean contact, boolean moving, double n825, double nz) {
        Tip t = new Tip();
        if (!contact) { t.kind = TIP_NO_CONTACT; t.title = "Fingers off the clasp"; t.body = "Rest two fingertips lightly on the metal clasp. The reading carries on while you do."; return t; }
        if (Double.isNaN(n825) && Double.isNaN(nz)) { t.kind = TIP_SETTLING; t.title = "Settling"; t.body = "The strap needs about 12 s before the signal counts. Keep your hand still."; return t; }
        if (moving) { t.kind = TIP_MOVING; t.title = "A little wrist movement"; t.body = "Rest your forearm on the table and keep the fingers still."; return t; }
        boolean good, fair;
        if (!Double.isNaN(n825)) { good = n825 <= 27; fair = n825 <= 40; } else { good = nz <= 25; fair = nz <= 38; }
        if (good) { t.kind = TIP_GOOD; t.title = "Signal good"; t.body = "Stay as you are."; }
        else if (fair) { t.kind = TIP_FAIR; t.title = "Signal fair"; t.body = "Relax your hand and keep still."; }
        else { t.kind = TIP_NOISY; t.title = "Signal noisy"; t.body = "Rest your forearm on the table and keep still."; }
        return t;
    }

    /** Heart rate from the median beat gap in seconds ("--" until there is one). */
    static String hr(double medianRrS) {
        if (Double.isNaN(medianRrS) || medianRrS < 0.25 || medianRrS > 3.0) return "--";
        return String.valueOf(Math.round(60.0 / medianRrS));
    }

    /** The strap's own HRV in ms once it has sent a valid one ("--" before). */
    static String hrv(long hrvArrivedMs, int hrvVal) { return hrvArrivedMs > 0L && hrvVal > 0 ? String.valueOf(hrvVal) : "--"; }

    static String verdictLabel(double s) { return "verdict " + mmss(s); }
    static String hrvLabel(double s) { return "first HRV " + mmss(s); }

    /** Seconds from the start of the reading to a wall-clock arrival time; NaN when it has not arrived (arrival 0) or the clock reads before the start. */
    static double since(long startMs, long arrivalMs) {
        if (arrivalMs <= 0L || startMs <= 0L || arrivalMs < startMs) return Double.NaN;
        return (arrivalMs - startMs) / 1000.0;
    }
}
