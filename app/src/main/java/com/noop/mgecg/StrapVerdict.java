package com.noop.mgecg;

// FILE VERSION 0.27.1 (31 Oct 2026): the strap's own ECG verdict, decoded.
// Result code (ECG status byte 23) and the strap's heart rate (byte 27) map to a category exactly as the WHOOP app does it
// (WHOOP 5.469.0, aj1.c.b(code, hr)); the four unreadable reasons (byte 26) as named by OpenStrap/protocol labrador.dart.
// Research use only: this is the strap's built-in classification, shown for comparison, not a diagnosis.

final class StrapVerdict {
    private StrapVerdict() {}

    /** WHOOP's category for a result code and heart rate (bpm). */
    static String category(int code, int hr) {
        switch (code) {
            case 1: return hr >= 51 && hr <= 99 ? "Sinus rhythm" : "Unreadable";
            case 3: return hr <= 50 ? "Low heart rate" : "Unreadable";
            case 4:
                if (hr >= 51 && hr <= 99) return "Possible AFib";
                if (hr >= 100 && hr <= 150) return "AFib with high heart rate";
                if (hr >= 151 && hr <= 200) return "High heart rate";
                return "Unreadable";
            case 5:
                if (hr >= 100 && hr <= 150) return "High heart rate, no AFib detected";
                if (hr >= 151 && hr <= 200) return "High heart rate";
                return "Unreadable";
            case 6: return "Inconclusive";
            default: return "Unreadable";       // 0, 2 and any other code
        }
    }

    /** The unreadable-reason bits by name, or "" when none are set. */
    static String reasons(int mask) {
        StringBuilder s = new StringBuilder();
        if ((mask & 1) != 0) s.append("low amplitude");
        if ((mask & 2) != 0) s.append(s.length() > 0 ? ", " : "").append("significant noise");
        if ((mask & 4) != 0) s.append(s.length() > 0 ? ", " : "").append("unstable signal");
        if ((mask & 8) != 0) s.append(s.length() > 0 ? ", " : "").append("not enough data");
        if ((mask & ~15) != 0) s.append(s.length() > 0 ? ", " : "").append("other bits 0x").append(Integer.toHexString(mask & ~15));
        return s.toString();
    }

    /** "Sinus rhythm (strap code 1, heart rate 64)" plus reasons when the strap gave any. */
    static String describe(int code, int hr, int mask) {
        String r = reasons(mask);
        return category(code, hr) + " (strap code " + code + ", heart rate " + hr + ")" + (r.isEmpty() ? "" : "; reasons: " + r);
    }
}
