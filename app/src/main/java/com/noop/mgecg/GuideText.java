package com.noop.mgecg;

// FILE VERSION 0.55.0 (14 Nov 2026): the words of the Quick guide, kept free of Android so they can be checked off the phone (X19): the tab names must match the bottom bar and the ring names must match Today.

final class GuideText {
    private GuideText() {}

    /** Tabs, in the order of the bottom bar: name, then what it is for. */
    static final String[][] TABS = {
            {"Today", "Your day at a glance: the six rings, last night's sleep, when the strap last synced, alarms and how much you have moved."},
            {"Heart", "Your heart rate now and over the last 24 hours, beat to beat, and where the time was spent."},
            {"ECG", "Record an ECG: two fingers on the clasp, forearm on a table. Past readings are listed here."},
            {"Rest", "Last night in detail: the shape of the night, the whole night and the beat intervals."},
            {"You", "The strap link, the day logger, settings, your data and backups. This guide is here too."},
    };

    /** The six rings, in the order on Today: what each one shows, plainly. Colours are DailyViews.RING_COLORS. */
    static final String[] RINGS = {
            "How ready your body looks compared with your own last nights. Higher is better.",
            "How hard the day has been so far, on a scale of 0 to 21.",
            "Your moving time all day (not workouts) against the goal you set on the Activity screen.",
            "Time asleep against your sleep goal.",
            "How far your heart rate sits above your own still baseline while you are not moving. Lower is calmer.",
            "An estimate of what has built up over several days. Lower is fresher.",
    };

    static String asText() {
        StringBuilder b = new StringBuilder();
        b.append("THE FIVE TABS\n");
        for (String[] t : TABS) b.append(t[0]).append(": ").append(t[1]).append('\n');
        b.append("\nTHE SIX RINGS\n");
        for (int i = 0; i < RINGS.length; i++) b.append(DailyViews.RING_NAMES[i]).append(": ").append(RINGS[i]).append('\n');
        return b.toString();
    }
}
