package com.noop.mgecg;

// FILE VERSION 0.43.0 (6 Nov 2026): the "Lab" checklist (decode log 50.6 item 1c). Ticks come from reading_notes.csv: a reading counts for a run when its notes hold run = <id> and stop = run
// (the guided run finished by itself). Floors for readings made before the notes held them: A twice (5 Nov 22:37, 6 Nov 20:06), B twice (6 Nov 20:23 and 20:30), C once (6 Nov 21:09). Target 2 each;
// E counts the two time windows (08:00-10:00 and 19:00-21:00, device clock), F counts readings. Pure Java.

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

final class LabTests {
    private LabTests() {}

    static final String[] IDS = { "A", "B", "C", "D", "E", "F" };
    static final int[] FLOOR = { 2, 2, 1, 0, 0, 0 };

    static final class Result { int[] n = new int[6]; boolean eMorning, eEvening; }

    static String plain(String id) {
        switch (id) {
            case "A": return "Clench your left fist at 15 s, let go at 20 s.";
            case "B": return "Slide both fingers a few mm sideways at 15 s and again at 24 s.";
            case "C": return "Ease to a very light touch at 15 s, press firmly again at 25 s.";
            case "D": return "Turn your left forearm outward at 15 s, back at 20 s.";
            case "E": return "Sit still. Once 08:00-10:00 and once 19:00-21:00, same chair and fingers.";
            default: return "Sit still. Once straight after work (before washing your hands), once before work.";
        }
    }

    static String name(String id) {
        switch (id) {
            case "A": return "Left fist";
            case "B": return "Finger slip";
            case "C": return "Light touch";
            case "D": return "Forearm rotate";
            case "E": return "Still, morning and evening";
            default: return "Still, after work";
        }
    }

    static int idx(String id) { for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return i; return -1; }

    static boolean morning(int hour) { return hour >= 8 && hour < 10; }
    static boolean evening(int hour) { return hour >= 19 && hour < 21; }
    static int hourOf(long startSec) { Calendar c = Calendar.getInstance(); c.setTimeInMillis(startSec * 1000L); return c.get(Calendar.HOUR_OF_DAY); }
    static int hourNow() { return Calendar.getInstance().get(Calendar.HOUR_OF_DAY); }

    static Result done(File dir) {
        Result r = new Result();
        Map<Long, String[]> by = new HashMap<>();      // start -> {run, stop}
        try {
            File f = dir == null ? null : new File(dir, "reading_notes.csv");
            if (f != null && f.exists() && f.length() < 4000000) {
                try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                    String ln;
                    while ((ln = br.readLine()) != null) {
                        int a = ln.indexOf(','), b = ln.indexOf(',', a + 1); if (a < 1 || b < 0) continue;
                        long s; try { s = Long.parseLong(ln.substring(0, a)); } catch (NumberFormatException e) { continue; }
                        String k = ln.substring(a + 1, b), v = ln.substring(b + 1).trim();
                        String[] x = by.get(s); if (x == null) { x = new String[] { "", "" }; by.put(s, x); }
                        if (k.equals("run")) x[0] = v; else if (k.equals("stop")) x[1] = v;
                    }
                }
            }
        } catch (Throwable ignored) { }
        for (Map.Entry<Long, String[]> e : by.entrySet()) {
            String run = e.getValue()[0]; if (run.isEmpty() || !"run".equals(e.getValue()[1])) continue;
            int i = idx(run); if (i < 0) continue;
            if (i == 4) { int h = hourOf(e.getKey()); if (morning(h)) r.eMorning = true; else if (evening(h)) r.eEvening = true; }
            else r.n[i]++;
        }
        r.n[4] = (r.eMorning ? 1 : 0) + (r.eEvening ? 1 : 0);
        for (int i = 0; i < 6; i++) { if (r.n[i] < FLOOR[i]) r.n[i] = FLOOR[i]; if (r.n[i] > 2) r.n[i] = 2; }
        return r;
    }

    /** The first undone test for the "Next test" chip: A to D in order; E only inside a window not done yet; never F (that one is only in the Lab). Empty when none. */
    static String next(Result r, int hour) {
        for (int i = 0; i < 4; i++) if (r.n[i] < 2) return IDS[i];
        if ((morning(hour) && !r.eMorning) || (evening(hour) && !r.eEvening)) return "E";
        return "";
    }

    static boolean canStartE(Result r, int hour) { return (morning(hour) && !r.eMorning) || (evening(hour) && !r.eEvening); }
}
