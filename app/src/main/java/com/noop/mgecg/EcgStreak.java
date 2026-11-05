package com.noop.mgecg;

// FILE VERSION 0.42.0 (5 Nov 2026): days with a reading this week, the streak, morning / evening counts. Used by the Home card, the widget and the readings table. Pure Java, tested off the phone.

import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;
import java.util.TimeZone;

final class EcgStreak {
    private EcgStreak() {}

    static final String[] DOW = { "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun" };

    static final class Out {
        int streak, weekDays, weekMorning, weekEvening, totalDays;
        boolean[] week = new boolean[7];
        boolean today;
        int todayIdx;                                  // 0 = Monday
    }

    private static long dayOf(long ms, TimeZone tz) { return Math.floorDiv(ms + tz.getOffset(ms), 86400000L); }

    /** startsSec: the start time (unix seconds) of every reading. A day counts when it has at least one reading. */
    static Out compute(long[] startsSec, long nowMs, TimeZone tz) {
        Out o = new Out();
        Set<Long> days = new HashSet<Long>();
        long today = dayOf(nowMs, tz);
        Calendar c = Calendar.getInstance(tz); c.setTimeInMillis(nowMs);
        o.todayIdx = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;
        long weekStart = today - o.todayIdx;
        if (startsSec != null) for (long s : startsSec) {
            long ms = s * 1000L; if (ms > nowMs + 86400000L) continue;
            long d = dayOf(ms, tz); days.add(d);
            if (d >= weekStart && d <= today) {
                int hour = (int) (Math.floorMod(ms + tz.getOffset(ms), 86400000L) / 3600000L);
                if (hour >= 4 && hour < 12) o.weekMorning++; else if (hour >= 17 && hour < 24) o.weekEvening++;
            }
        }
        o.totalDays = days.size(); o.today = days.contains(today);
        for (int i = 0; i < 7; i++) { o.week[i] = days.contains(weekStart + i); if (o.week[i]) o.weekDays++; }
        long d = o.today ? today : today - 1;
        while (days.contains(d)) { o.streak++; d--; }
        return o;
    }

    /** "Mon ● Tue ● Wed ○ ..." with today in brackets. */
    static String strip(Out o) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            if (i > 0) sb.append("  ");
            String d = DOW[i]; if (i == o.todayIdx) d = "[" + d + "]";
            sb.append(d).append(' ').append(o.week[i] ? "\u25CF" : "\u25CB");
        }
        return sb.toString();
    }

    static String summary(Out o) {
        return "This week " + o.weekDays + " of 7 days  \u00b7  streak " + o.streak + (o.streak == 1 ? " day" : " days") + "  \u00b7  morning " + o.weekMorning + ", evening " + o.weekEvening;
    }
}
