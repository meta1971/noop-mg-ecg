package com.noop.mgecg;

// FILE VERSION 0.42.0 (5 Nov 2026): when the next reading reminder is due. Pure Java (no Android), tested off the phone.

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

final class ReminderTime {
    private ReminderTime() {}

    static final class Cfg {
        boolean on;
        int workMask = 31;                                       // Monday = bit 0 ... Sunday = bit 6
        int[] t = { 6 * 60 + 15, 19 * 60 + 30, 8 * 60 + 30, 19 * 60 + 30 };   // minutes after midnight, -1 = switched off
        int overrideDay, overrideType;                           // yyyymmdd of the day the override is for; 0 auto, 1 work day, 2 day off
    }

    static int ymd(Calendar c) { return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH); }

    /** true when the calendar day counts as a work day under the settings (the override only applies to its own date). */
    static boolean workDay(Cfg g, Calendar day) {
        if (g.overrideDay == ymd(day) && g.overrideType != 0) return g.overrideType == 1;
        int bit = (day.get(Calendar.DAY_OF_WEEK) + 5) % 7;     // Monday = 0
        return (g.workMask & (1 << bit)) != 0;
    }

    /** The next reminder after nowMs: {time in ms, slot 0-3}, or null when none is set. Looks 9 days ahead. */
    static long[] next(long nowMs, TimeZone tz, Cfg g) {
        if (g == null || !g.on) return null;
        for (int d = 0; d < 9; d++) {
            Calendar day = Calendar.getInstance(tz); day.setTimeInMillis(nowMs); day.add(Calendar.DAY_OF_YEAR, d);
            boolean work = workDay(g, day); long best = Long.MAX_VALUE; int slot = -1;
            for (int k = 0; k < 2; k++) {
                int s = (work ? 0 : 2) + k; int m = g.t[s]; if (m < 0) continue;
                Calendar at = (Calendar) day.clone(); at.set(Calendar.HOUR_OF_DAY, m / 60); at.set(Calendar.MINUTE, m % 60); at.set(Calendar.SECOND, 0); at.set(Calendar.MILLISECOND, 0);
                long ms = at.getTimeInMillis(); if (ms > nowMs + 1000L && ms < best) { best = ms; slot = s; }
            }
            if (slot >= 0) return new long[] { best, slot };
        }
        return null;
    }

    static String hhmm(int m) { return m < 0 ? "off" : String.format(Locale.UK, "%02d:%02d", m / 60, m % 60); }

}
