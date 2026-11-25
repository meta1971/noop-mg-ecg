package com.noop.mgecg;

import java.util.Locale;
import java.util.TimeZone;

// FILE VERSION 0.88.0 (24 Nov 2026): lead runs L1 / L2 / L3 ride on TRI-GO (extra tri_run; titles, wearing text and the role of each copy; decode log 436). 0.78.0 (21 Nov 2026): TRI-GO. One tap (ECG tab > Lab > T) in any of the three copies of the app (A com.noop.mgecg, B .b, C .c) starts the ECG reading in all installed copies at ONE shared phone-clock time.
// The tapped copy fixes the start time (tap + LEAD_MS), arms itself and launches the other installed copies in turn (explicit intent, extras tri_at / tri_chain / tri_home, same flags as the widget's start); each copy arms the same start time
// and launches the next one; the last one brings the tapped copy back to the front. Every copy presses its own START at the start time (late, at once, if its strap was still connecting). Pure helpers, no Android classes (tested off the phone, XQ78).
final class TriGo {
    static final String[] PKGS = { "com.noop.mgecg", "com.noop.mgecg.b", "com.noop.mgecg.c" };      // copy A, B, C (the applicationIds of build.gradle)
    static final String ACTIVITY = "com.noop.mgecg.MainActivity";                                  // the namespace is the same in all three copies
    static final String X_AT = "tri_at", X_CHAIN = "tri_chain", X_HOME = "tri_home";
    static final String X_RUN = "tri_run";     // 0.88.0: the lead run this shared start belongs to ("" = a plain TRI-GO)
    /** 0.88.0 lead runs (decode log 436). Codes, titles, how to wear and touch, and what each copy records. Lead signs follow 400 / 420: a strap reads (skin under the strap) minus (what touches the clasp). */
    static final String[] RUNS = { "L1", "L2", "L3" };
    static String runTitle(String r) {
        if ("L1".equals(r)) return "L1   Six leads: wrist + ankle";
        if ("L2".equals(r)) return "L2   Closure: two straps on the ankle";
        if ("L3".equals(r)) return "L3   Twin: both wrists";
        return "";
    }
    static String runHow(String r) {
        if ("L1".equals(r)) return "Strap A on the left wrist, strap C on the left ankle. Sit, left ankle resting on the right knee. RIGHT fingertip on A's clasp, LEFT fingertip on C's clasp. Keep still 60 s. A records lead I, C lead III; II, aVR, aVL and aVF follow from the two. Strap B is not used.";
        if ("L2".equals(r)) return "Straps B and C side by side on the left ankle, ankle resting on the right knee. RIGHT fingertip on B's clasp, LEFT fingertip on C's clasp. Keep still 60 s. B records lead II, C lead III; lead I = II minus III, to set against run L1. Strap A is not used.";
        if ("L3".equals(r)) return "Strap A on the left wrist, strap B on the right wrist, forearms crossed on the table. RIGHT fingertip on A's clasp, LEFT fingertip on B's clasp. Keep still 60 s. A records lead I, B the same lead upside down: the two straps should agree. Strap C is not used.";
        return "";
    }
    /** What the copy with this letter records in a run; "" for an unknown run. */
    static String role(String r, String letter) {
        if ("L1".equals(r)) return "A".equals(letter) ? "I: left wrist, right fingertip on the clasp" : "C".equals(letter) ? "III: left ankle, left fingertip on the clasp" : "not used";
        if ("L2".equals(r)) return "B".equals(letter) ? "II: left ankle, right fingertip on the clasp" : "C".equals(letter) ? "III: left ankle, left fingertip on the clasp" : "not used";
        if ("L3".equals(r)) return "A".equals(letter) ? "I: left wrist, right fingertip on the clasp" : "B".equals(letter) ? "minus I: right wrist, left fingertip on the clasp" : "not used";
        return "";
    }
    static boolean isRun(String r) { for (String k : RUNS) if (k.equals(r)) return true; return false; }
    static final long LEAD_MS = 30000L;        // from the tap to the shared start: time to put both hands on the clasps
    static final long PAST_MS = 5000L;         // an arriving start time may be this far in the past ...
    static final long AHEAD_MS = 120000L;      // ... and this far ahead (anything else is refused)
    static final long GIVE_UP_MS = 150000L;    // a copy that has not reached the reading page this long after the start time drops it
    static final long FORWARD_MS = 1200L;      // a launched copy waits this long (its page is up) before it launches the next one

    private TriGo() { }

    static boolean isCopy(String p) {
        if (p == null) return false;
        for (String k : PKGS) if (k.equals(p)) return true;
        return false;
    }

    /** Letter of a copy: A, B, C, or ? */
    static String suffix(String pkg) {
        if (pkg == null) return "?";
        if (pkg.equals(PKGS[0])) return "A";
        if (pkg.equals(PKGS[1])) return "B";
        if (pkg.equals(PKGS[2])) return "C";
        return "?";
    }

    /** The other installed copies, in the order A, B, C, comma separated; installed[i] says whether PKGS[i] is installed. */
    static String chain(String self, boolean[] installed) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < PKGS.length; i++) {
            if (PKGS[i].equals(self)) continue;
            if (installed == null || i >= installed.length || !installed[i]) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(PKGS[i]);
        }
        return sb.toString();
    }

    /** The first package of a chain, or "" (also "" when it is not one of our three, so an intent cannot make the app launch anything else). */
    static String first(String chain) {
        if (chain == null || chain.isEmpty()) return "";
        int k = chain.indexOf(',');
        String f = k < 0 ? chain : chain.substring(0, k);
        return isCopy(f) ? f : "";
    }

    static String rest(String chain) {
        if (chain == null) return "";
        int k = chain.indexOf(',');
        return k < 0 ? "" : chain.substring(k + 1);
    }

    static boolean acceptable(long at, long now) { return at >= now - PAST_MS && at <= now + AHEAD_MS; }

    static boolean expired(long at, long now) { return now > at + GIVE_UP_MS; }

    /** Next check: every 250 ms, and exactly at the start time for the last one. */
    static long tickDelay(long left) { return left <= 0L ? 1L : Math.min(250L, left); }

    static String clock(long at, TimeZone tz) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("HH:mm:ss", Locale.UK);
        f.setTimeZone(tz);
        return f.format(new java.util.Date(at));
    }

    static String pill(long at, long now, TimeZone tz) {
        long s = (at - now + 999L) / 1000L;
        if (s < 0L) s = 0L;
        return "TRI-GO: all three straps start together at " + clock(at, tz) + "\nin " + s + " s    (tap to cancel)";
    }
}
