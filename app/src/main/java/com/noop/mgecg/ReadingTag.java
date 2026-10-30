package com.noop.mgecg;

// FILE VERSION 0.25.2 (30 Oct 2026): the position or activity tag the person picks before Start (lying, sitting, standing, after exercise), kept in reading_tags.csv (strap-independent: phone time, unix seconds). Pure Java.

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

final class ReadingTag {
    private ReadingTag() {}

    /** Appends "unix_seconds,tag" (also an empty tag, so an earlier tag does not carry over to the next reading). */
    static void note(File dir, String tag) {
        if (dir == null) return;
        try (FileWriter w = new FileWriter(new File(dir, "reading_tags.csv"), true)) { w.write((System.currentTimeMillis() / 1000L) + "," + (tag == null ? "" : tag.replace(',', ' ')) + "\n"); } catch (Exception ignored) { }
    }

    /** The tag noted from 90 s before to 30 s after the reading's first frame; empty when none. */
    static String forReading(File dir, double t0) {
        if (dir == null) return "";
        File f = new File(dir, "reading_tags.csv"); if (!f.exists() || f.length() > 2000000) return "";
        String best = ""; double bestD = 1e9;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String ln; while ((ln = br.readLine()) != null) { int c = ln.indexOf(','); if (c < 1) continue; double t; try { t = Double.parseDouble(ln.substring(0, c)); } catch (NumberFormatException e) { continue; }
                double d = t - t0; if (d >= -90 && d <= 30 && Math.abs(d) < bestD) { bestD = Math.abs(d); best = ln.substring(c + 1).trim(); } }
        } catch (Exception ignored) { }
        return best;
    }
}
