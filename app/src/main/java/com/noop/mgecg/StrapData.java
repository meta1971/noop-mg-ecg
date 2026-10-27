package com.noop.mgecg;

// FILE VERSION 0.13.0 (26 Oct): everything the strap sends while it is simply worn, on one page. Research only.

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.database.Cursor;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import static com.noop.mgecg.HealthViews.*;

/**
 * "Strap data": every field the strap sends in its per-second record (R18) while worn, with the newest second decoded in full, the last 24 hours
 * summarised, the live heart-rate frames, the record types stored, and the strap events (wrist on and off, charging, taps) with the ECG end-of-reading
 * events decoded. Each row says which strap bytes it comes from. Values whose meaning is not decoded are shown raw and marked as such.
 * The row builders are pure, so they are tested off the phone. Not a medical record.
 */
final class StrapData {
    private StrapData() {}

    /** {group, label, value, note}. */
    static String[] row(String g, String l, String v, String n) { return new String[] {g, l, v, n == null ? "" : n}; }

    static String state(int s) { return s == 0 ? "awake" : s == 1 ? "still" : s == 2 ? "sleep" : "up"; }

    static String clock(long sec) { return new SimpleDateFormat("EEE d MMM HH:mm:ss", Locale.UK).format(new Date(sec * 1000L)); }

    static String ageText(long sec) {
        if (sec < 0) sec = 0;
        if (sec < 90) return sec + " s";
        if (sec < 5400) return Math.round(sec / 60.0) + " min";
        return String.format(Locale.UK, "%.1f h", sec / 3600.0);
    }

    /**
     * 0.20.0: the strap's battery from its condition reports (event 29), newest first as {ts, id, name, decoded}. Returns for example "88 %, about 7 days left at the last day's rate",
     * "96 %, charging" or "88 %" while there is too little history. The rate uses the non-charging reports of the last 24 hours (since the last charge if there was one) and needs
     * at least 6 hours and a fall of at least 1 point. Pure.
     */
    static String batteryText(List<String[]> ev, long nowSec) {
        java.util.regex.Pattern pb = java.util.regex.Pattern.compile("battery ([0-9.]+) %"), pc = java.util.regex.Pattern.compile("charging (\\d)");
        List<double[]> pts = new ArrayList<>();                      // {ts, percent, charging}, oldest first
        for (int i = ev.size() - 1; i >= 0; i--) {
            String[] r = ev.get(i);
            if (!"29".equals(r[1]) || r[3] == null) continue;
            java.util.regex.Matcher m = pb.matcher(r[3]), c = pc.matcher(r[3]);
            if (!m.find()) continue;
            pts.add(new double[] { Double.parseDouble(r[0]), Double.parseDouble(m.group(1)), c.find() ? Double.parseDouble(c.group(1)) : 0 });
        }
        if (pts.isEmpty()) return null;
        double[] last = pts.get(pts.size() - 1);
        String now = Math.round(last[1]) + " %";
        if (last[2] == 1) return now + ", charging";
        int from = 0;
        for (int i = 0; i < pts.size(); i++) { if (pts.get(i)[2] == 1 || pts.get(i)[0] < nowSec - 86400L) from = i + 1; }
        if (from >= pts.size() - 1) return now;
        double[] first = pts.get(from);
        double hours = (last[0] - first[0]) / 3600.0, fall = first[1] - last[1];
        if (hours < 6.0 || fall < 1.0) return now;
        double perHour = fall / hours;
        double days = last[1] / perHour / 24.0;
        return now + String.format(java.util.Locale.UK, ", about %.0f days left at the last day's rate (%.2f %% an hour)", days, perHour);
    }

    /** 0.21.0: "57 absences over 2 min, longest 5.8 h, total 10.9 h; 83 short drops" for the last 24 hours, by event id. Reads the database: call off the main thread. */
    static String rangeLine(Context a) {
        try {
            long nowS = System.currentTimeMillis() / 1000L;
            List<long[]> evs = new ArrayList<>();
            List<String[]> raw = StrapEvents.byIds(a, nowS - 86400L, 3000, 11, 12);
            for (int i = raw.size() - 1; i >= 0; i--) { String[] r = raw.get(i); evs.add(new long[] { Integer.parseInt(r[1]), Long.parseLong(r[0]) }); }
            long[] rs = rangeSummary(evs);
            return rs[0] + " absences over 2 min" + (rs[0] > 0 ? ", longest " + ageText(rs[2]) + ", total " + ageText(rs[3]) : "") + "; " + rs[1] + " short drops";
        } catch (Throwable t) { return ""; }
    }

    /** 0.21.0: the strap battery line (see batteryText), by event id. Reads the database: call off the main thread. */
    static String batteryLine(Context a) {
        try { return batteryText(StrapEvents.byIds(a, System.currentTimeMillis() / 1000L - 3 * 86400L, 600, 29), System.currentTimeMillis() / 1000L); } catch (Throwable t) { return null; }
    }

    /** 0.21.0: the logger's own counters in one line, from preferences (cheap, main thread is fine). */
    static String loggerLine(Context a) {
        try {
            android.content.SharedPreferences ld = a.getSharedPreferences("linkdiag", Context.MODE_PRIVATE);
            long nx = ld.getLong("alarm_next_ms", 0L);
            return "alarm every 15 min" + (nx > 0 ? ", next " + new java.text.SimpleDateFormat("HH:mm", Locale.UK).format(new Date(nx)) : "") + "; app swiped away " + ld.getInt("n_task_removed", 0) + " times; ran " + ld.getInt("n_alarm", 0)
                    + " alarm checks; restarted by the alarm " + ld.getInt("n_alarm_restart", 0) + " times; restarted by Android " + ld.getInt("n_svc_restart", 0) + " times";
        } catch (Throwable t) { return ""; }
    }

    /**
     * 0.19.3: how much of the last 24 hours the strap was out of reach, from its own "Bluetooth down" (12) and "Bluetooth up" (11) events, oldest first as {id, strapTime}.
     * Gaps of 2 minutes or more count as absences, shorter ones as drops. Returns {absences, drops, longestSec, totalSec}. Pure.
     */
    static long[] rangeSummary(java.util.List<long[]> ev) {
        long down = -1, longest = 0, total = 0; int away = 0, drops = 0;
        for (long[] e : ev) {
            if (e[0] == 12) down = e[1];
            else if (e[0] == 11 && down >= 0) {
                long d = e[1] - down; down = -1;
                if (d < 0) continue;
                if (d >= 120) { away++; total += d; if (d > longest) longest = d; } else drops++;
            }
        }
        return new long[] { away, drops, longest, total };
    }

    /** One line on what happened at each open and swipe-away (counts kept in preferences). Pure. */
    static String diagText(int kept, int rebuilt, int fresh, int swipeKeep, long lastMs, String lastKind) {
        String last = lastMs <= 0L ? "" : ". Last open: " + ("kept".equals(lastKind) ? "kept the link" : "rebuilt".equals(lastKind) ? "rebuilt the link" : "fresh start") + " at " + new SimpleDateFormat("d MMM HH:mm", Locale.UK).format(new Date(lastMs));
        return "Closed screens that kept the link " + swipeKeep + "; opens that kept it " + kept + ", rebuilt it " + rebuilt + ", fresh start " + fresh + last;
    }

    /** Is the all-day log running, and how fresh is the newest second? Pure. */
    static List<String[]> statusRows(boolean loggerOn, long newestTs, long nowSec) { return statusRows(loggerOn, newestTs, nowSec, false); }

    static List<String[]> statusRows(boolean loggerOn, long newestTs, long nowSec, boolean linkGood) {
        List<String[]> r = new ArrayList<>();
        String G = "STATUS";
        r.add(row(G, "Day logger", loggerOn ? "on" : "OFF", loggerOn ? null : "nothing is recorded; switch it on from the Today screen"));
        if (newestTs <= 0) { r.add(row(G, "Newest record", "none", null)); return r; }
        long age = nowSec - newestTs;
        String note = null;
        if (!loggerOn) { if (age > 600) note = "the logger was off, so the strap's history has not been pulled"; }
        else if (linkGood) { if (age > 75 * 60) note = "connected, but no history pulled for over an hour"; else if (age > 600) note = "stored seconds arrive with each history pull, about every 30 minutes; the link itself is live"; }
        else if (age > 600) note = "no new data: strap off the wrist or on the charger, flat, or Bluetooth off";
        r.add(row(G, "Newest record", ageText(age) + " ago", note));
        return r;
    }

    // ------------------------------------------------------------------ the newest second, every field
    static List<String[]> nowRows(byte[] f) {
        List<String[]> r = new ArrayList<>();
        if (f == null || !R18Codec.isR18(f)) return r;
        Locale L = Locale.UK;
        int n = Math.min(R18Codec.u8(f, 23), 4);
        StringBuilder rr = new StringBuilder();
        for (int k = 0; k < n; k++) { if (k > 0) rr.append(", "); rr.append(Math.round(R18Codec.rr(f, k))); }
        String H = "HEART";
        r.add(row(H, "Heart rate", R18Codec.hr(f) + " bpm", "byte 22; checked against a chest strap: median difference 0 bpm"));
        r.add(row(H, "Second heart-rate estimate", R18Codec.u8(f, 37) + " bpm", "byte 37"));
        r.add(row(H, "Beat intervals this second", n == 0 ? "none" : rr + " ms", "bytes 23 to 31; read as milliseconds; optical, so HRV from them reads high"));
        r.add(row(H, "Optical quality", R18Codec.u8(f, 40) + " of 255", "byte 40"));
        r.add(row(H, "Pulse signal-to-noise", (byte) R18Codec.u8(f, 108) + " / " + (byte) R18Codec.u8(f, 109), "bytes 108 and 109 (int8); -128 means the pulse lock is lost"));
        r.add(row(H, "Detector level", String.valueOf(R18Codec.u16(f, 106)), "bytes 106 and 107"));
        r.add(row(H, "Pulse-lock-lost value", String.valueOf(R18Codec.u8(f, 105)), "byte 105; non-zero almost only when the pulse lock is lost; the number itself is not decoded"));
        r.add(row(H, "Signal-quality value", String.format(L, "%.3f", R18Codec.f32(f, 113)), "bytes 113 to 116 (float); about -5.3 when clean, moves toward -2 with motion"));
        r.add(row(H, "Cardiac flags", String.valueOf(R18Codec.u8(f, 33)), "byte 33; not decoded"));
        r.add(row(H, "Heart-rate quality flags", String.valueOf(R18Codec.u8(f, 36)), "byte 36; not decoded"));
        String M = "MOTION";
        double gx = R18Codec.f32(f, 45), gy = R18Codec.f32(f, 49), gz = R18Codec.f32(f, 53);
        r.add(row(M, "Largest acceleration step", String.format(L, "%.4f g", R18Codec.f32(f, 41)), "bytes 41 to 44; about 0.002 at rest, 0.3 jogging"));
        r.add(row(M, "Gravity direction", String.format(L, "x %.3f  y %.3f  z %.3f", gx, gy, gz) + String.format(L, "  (%.3f g)", Math.sqrt(gx * gx + gy * gy + gz * gz)), "bytes 45 to 56; which way the strap's axes point"));
        r.add(row(M, "Step counter", String.valueOf(R18Codec.steps(f)), "bytes 57 and 58; running total"));
        r.add(row(M, "Step cadence", String.valueOf(R18Codec.u16(f, 59)), "byte 59"));
        int act = R18Codec.u8(f, 63);
        r.add(row(M, "Activity class", R18Codec.activityName(act), "byte 63; none, walk or run (walk against run not yet separated)"));
        r.add(row(M, "Stillness flag", (R18Codec.u8(f, 32) & 0x80) != 0 ? "still" : "moving or unset", "byte 32 bit 7; a candidate, not proven"));
        String T = "TEMPERATURE";
        r.add(row(T, "Skin", R18Codec.skinOk(f) ? String.format(L, "%.2f \u00b0C", R18Codec.skinC(f)) : "not available", "bytes 73 and 74"));
        r.add(row(T, "Strap battery cell", String.format(L, "%.1f \u00b0C", R18Codec.i16(f, 69) / 10.0), "bytes 69 and 70"));
        r.add(row(T, "Ambient (inside the strap)", String.format(L, "%.1f \u00b0C", R18Codec.i16(f, 71) / 10.0), "bytes 71 and 72"));
        String S = "STATE";
        r.add(row(S, "Sleep state", state(R18Codec.state(f)), "byte 81 bits 4 and 5: awake, still, sleep or up; an envelope, not a stage"));
        r.add(row(S, "Byte 81 low bits", String.valueOf(R18Codec.u8(f, 81) & 0x0f), "not decoded; 0 or 1 in 96% of seconds"));
        int b82 = R18Codec.u8(f, 82);
        r.add(row(S, "Blood-oxygen byte", b82 == 0 ? "no window" : String.valueOf(b82), "byte 82; a percentage only inside the sleep-only window (about every 20 minutes), other values are status codes"));
        r.add(row(S, "Optical sampling", (R18Codec.u8(f, 10) & 0x80) != 0 ? "25 Hz" : "50 Hz", "byte 10 bit 7; 50 Hz only while an ECG runs"));
        r.add(row(S, "Optical gain word", String.format("0x%04X", R18Codec.u16(f, 75)), "bytes 75 and 76; the only live one of three; not decoded"));
        String R = "THIS RECORD";
        r.add(row(R, "Strap time", clock(R18Codec.ts(f)), "bytes 15 to 18"));
        r.add(row(R, "Record counter", String.valueOf(R18Codec.u32(f, 11)), "bytes 11 to 14; +1 per second"));
        return r;
    }

    // ------------------------------------------------------------------ the last 24 hours
    static List<String[]> dayRows(List<byte[]> recs, long nowSec) {
        List<String[]> r = new ArrayList<>();
        String G = "LAST 24 HOURS";
        int n = recs.size();
        if (n == 0) { r.add(row(G, "Records", "none yet", null)); return r; }
        Locale L = Locale.UK;
        long first = R18Codec.ts(recs.get(0)), last = R18Codec.ts(recs.get(n - 1));
        long span = Math.max(1L, Math.min(86400L, nowSec - first));
        r.add(row(G, "Seconds recorded", n + " of " + span, String.format(L, "%.1f %% covered", 100.0 * n / span)));
        double hs = 0; int hmin = 999, hmax = 0, hn = 0, withRr = 0, rrN = 0, lockLost = 0, optSum = 0, still = 0, walk = 0, run = 0, spo2Win = 0;
        int[] st = new int[4];
        double[] skin = new double[n]; int sk = 0;
        double cmin = 99, cmax = -99, amin = 99, amax = -99;
        long stepsGained = 0; int prevSteps = -1;
        for (byte[] f : recs) {
            int hr = R18Codec.hr(f);
            if (hr > 0) { hs += hr; hn++; hmin = Math.min(hmin, hr); hmax = Math.max(hmax, hr); }
            int c = Math.min(R18Codec.u8(f, 23), 4);
            if (c > 0) { withRr++; rrN += c; }
            if ((byte) R18Codec.u8(f, 108) == -128) lockLost++;
            optSum += R18Codec.u8(f, 40);
            if ((R18Codec.u8(f, 32) & 0x80) != 0) still++;
            int a = R18Codec.u8(f, 63);
            if (a == 1) walk++; else if (a == 2) run++;
            st[R18Codec.state(f)]++;
            if (R18Codec.skinOk(f)) skin[sk++] = R18Codec.skinC(f);
            double cc = R18Codec.i16(f, 69) / 10.0, aa = R18Codec.i16(f, 71) / 10.0;
            cmin = Math.min(cmin, cc); cmax = Math.max(cmax, cc); amin = Math.min(amin, aa); amax = Math.max(amax, aa);
            int s = R18Codec.steps(f);
            if (prevSteps >= 0 && s >= prevSteps && s - prevSteps < 500) stepsGained += s - prevSteps;
            prevSteps = s;
            if (R18Codec.spo2Window(f) && R18Codec.u8(f, 82) > 0 && R18Codec.u8(f, 82) <= 100) spo2Win++;
        }
        r.add(row(G, "Heart rate", hn == 0 ? "\u2014" : String.format(L, "%d to %d, average %.0f bpm", hmin, hmax, hs / hn), "byte 22"));
        r.add(row(G, "Beat intervals", rrN + " in " + withRr + " seconds", String.format(L, "%.0f %% of seconds carry at least one", 100.0 * withRr / n)));
        r.add(row(G, "Pulse lock lost", String.format(L, "%.0f %% of seconds", 100.0 * lockLost / n), "pulse signal-to-noise at its floor"));
        r.add(row(G, "Optical quality", String.format(L, "average %.0f of 255", optSum / (double) n), null));
        if (sk > 0) {
            double[] s2 = Arrays.copyOf(skin, sk); Arrays.sort(s2);
            r.add(row(G, "Skin temperature", String.format(L, "%.1f to %.1f, median %.2f \u00b0C", s2[0], s2[sk - 1], s2[sk / 2]), "bytes 73 and 74"));
        }
        r.add(row(G, "Strap cell and inside temperature", String.format(L, "%.1f to %.1f and %.1f to %.1f \u00b0C", cmin, cmax, amin, amax), "bytes 69 to 72"));
        r.add(row(G, "Steps counted", String.valueOf(stepsGained), "byte 57 and 58, sum of rises"));
        r.add(row(G, "Walking and running", (walk / 60) + " min and " + (run / 60) + " min", "activity class, byte 63"));
        r.add(row(G, "Strap sleep state", String.format(L, "awake %s, still %s, sleep %s, up %s", hm(st[0]), hm(st[1]), hm(st[2]), hm(st[3])), "byte 81 bits 4 and 5"));
        r.add(row(G, "Blood-oxygen windows", String.valueOf(spo2Win / 30), "seconds with a value in the sleep-only window, divided by 30"));
        r.add(row(G, "Newest record", clock(last), null));
        return r;
    }

    private static String hm(int seconds) { int m = seconds / 60; return (m / 60) + "h " + String.format(Locale.UK, "%02d", m % 60) + "m"; }

    // ------------------------------------------------------------------ everything else
    static List<String[]> liveRows() {
        List<String[]> r = new ArrayList<>();
        String G = "LIVE FROM THE STRAP";
        LiveHr.Sample s = LiveHr.latest();
        if (s == null) { r.add(row(G, "Real-time heart rate", "no frame yet since the app started", "needs the live stream switched on; the day logger does this")); return r; }
        StringBuilder rr = new StringBuilder();
        for (int i = 0; i < s.rr.length; i++) { if (i > 0) rr.append(", "); rr.append(s.rr[i]); }
        r.add(row(G, "Real-time heart rate", s.hr + " bpm  \u00b7  " + LiveHr.ageText(s), "type-40 frame or the heart-rate characteristic"));
        r.add(row(G, "Beat intervals in that frame", s.rr.length == 0 ? "none" : rr + " ms", "milliseconds"));
        return r;
    }

    static List<String[]> typeRows(Context c) {
        List<String[]> r = new ArrayList<>();
        String G = "RECORD TYPES IN THE LOG";
        try {
            Cursor q = DayLog.db(c).rawQuery("SELECT COUNT(*), MIN(ts), MAX(ts) FROM r18", null);
            if (q.moveToFirst()) r.add(row(G, "Per-second records (layout 18)", q.getLong(0) + " seconds", q.getLong(0) > 0 ? "from " + clock(q.getLong(1)) : null));
            q.close();
            q = DayLog.db(c).rawQuery("SELECT ver, COUNT(*) FROM other_rec GROUP BY ver ORDER BY ver", null);
            while (q.moveToNext()) {
                int v = q.getInt(0);
                String what = v == 16 ? "ECG, 500 samples per second" : v == 20 ? "raw optical, 50 Hz" : v == 21 ? "raw motion, 100 Hz" : v == 26 ? "pulse packets" : v == 22 ? "layout 22, not decoded" : "other";
                r.add(row(G, "Layout " + v, q.getLong(1) + " seconds", what));
            }
            q.close();
        } catch (Throwable ignored) { }
        return r;
    }

    static List<String[]> eventRows(Context c, long nowSec) {
        List<String[]> r = new ArrayList<>();
        String G = "STRAP EVENTS";
        List<String[]> ev = StrapEvents.recent(c, nowSec - 86400L, 400);
        if (ev.isEmpty()) { r.add(row(G, "Events in the last 24 hours", "none stored yet", "kept from this version on; the strap sends them while the app is connected")); return r; }
        int[] ids = {9, 10, 7, 8, 14, 60, 11, 12, 113, 114};
        for (int id : ids) {
            int cnt = 0; long newest = 0;
            for (String[] e : ev) if (Integer.parseInt(e[1]) == id) { cnt++; if (newest == 0) newest = Long.parseLong(e[0]); }
            if (cnt > 0) r.add(row(G, StrapEvents.name(id), cnt + " in 24 h", "latest " + clock(newest)));
        }
        int shown = 0;
        for (String[] e : ev) {
            int id = Integer.parseInt(e[1]);
            if (id != 118 && id != 119) continue;
            r.add(row("ECG END-OF-READING EVENTS", StrapEvents.name(id) + "  " + clock(Long.parseLong(e[0])), e[3], id == 119 ? "the strap's own beat count and quality mix for its 30 s window" : "A to E and n2 are only partly decoded"));
            if (++shown >= 6) break;
        }
        return r;
    }

    static String asText(List<String[]> rows) {
        StringBuilder b = new StringBuilder("MG strap data, ").append(clock(System.currentTimeMillis() / 1000L)).append('\n');
        String g = "";
        for (String[] x : rows) {
            if (!x[0].equals(g)) { g = x[0]; b.append('\n').append(g).append('\n'); }
            b.append("  ").append(x[1]).append(": ").append(x[2]);
            if (!x[3].isEmpty()) b.append("  [").append(x[3]).append(']');
            b.append('\n');
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ screen
    static void show(final Activity a) {
        final Dialog dlg = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        int pad = Math.round(dp(a, 16));
        root.setPadding(pad, Math.round(dp(a, 30)), pad, Math.round(dp(a, 14)));
        LinearLayout top = new LinearLayout(a);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(HeartGraph.label(a, "Strap data", 22, INK, 300));
        top.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1f));
        final TextView copy = HeartGraph.pill(a, "Copy", 0x1AFFFFFF, INK);
        top.addView(copy);
        TextView close = HeartGraph.pill(a, "Close", 0x1AFFFFFF, INK);
        close.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { dlg.dismiss(); } });
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
        cl.leftMargin = Math.round(dp(a, 8));
        top.addView(close, cl);
        root.addView(top);
        root.addView(HeartGraph.label(a, "Everything the strap sends while it is worn. Nothing here needs an ECG reading.", 12, MUT, 400), HeartGraph.lp(a, -1, -2, 6));
        LinearLayout actions = new LinearLayout(a);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        TextView lfBtn = HeartGraph.pill(a, "Live frames", 0x1A2EE6A8, 0xFF2EE6A8);
        lfBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { a.startActivity(new android.content.Intent(a, LiveFramesActivity.class)); } });
        actions.addView(lfBtn);
        TextView pnBtn = HeartGraph.pill(a, "Pull now", 0x1AFFFFFF, INK);
        pnBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            if (!MainActivity.linkRunning()) MainActivity.startLinkHost(a);
            MainActivity.requestPull();
            android.widget.Toast.makeText(a, "Pull requested", android.widget.Toast.LENGTH_SHORT).show();
        } });
        LinearLayout.LayoutParams pl2 = new LinearLayout.LayoutParams(-2, -2);
        pl2.leftMargin = Math.round(dp(a, 8));
        actions.addView(pnBtn, pl2);
        root.addView(actions, HeartGraph.lp(a, -2, -2, 8));
        final LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.addView(HeartGraph.label(a, "Reading the log\u2026", 14, MUT, 400));
        ScrollView sv = new ScrollView(a);
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        dlg.setContentView(root);
        dlg.getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        dlg.show();
        new Thread(new Runnable() {
            @Override public void run() {
                final List<String[]> rows = new ArrayList<>();
                try {
                    long now = System.currentTimeMillis() / 1000L;
                    Cursor q = DayLog.db(a).rawQuery("SELECT rec FROM r18 WHERE ts >= ? ORDER BY ts", new String[] {String.valueOf(now - 86400L)});
                    List<byte[]> recs = new ArrayList<>();
                    while (q.moveToNext()) recs.add(q.getBlob(0));
                    q.close();
                    if (recs.isEmpty()) {
                        q = DayLog.db(a).rawQuery("SELECT rec FROM r18 ORDER BY ts DESC LIMIT 1", null);
                        if (q.moveToFirst()) recs.add(q.getBlob(0));
                        q.close();
                    }
                    long newest = 0L;
                    Cursor nq = DayLog.db(a).rawQuery("SELECT MAX(ts) FROM r18", null);
                    try { if (nq.moveToFirst()) newest = nq.getLong(0); } finally { nq.close(); }
                    LinkState.Info li = MainActivity.linkState(a);
                    List<String[]> st = statusRows(DayLog.enabled(a), newest, now, li.level == LinkState.GOOD);
                    st.add(Math.min(1, st.size()), row("STATUS", "Strap link", li.title, li.detail));
                    st.add(Math.min(2, st.size()), row("STATUS", "Logger service", DayLogService.alive ? "running" : (DayLog.enabled(a) ? "NOT running" : "off"),
                            "The foreground service (the \"MG day logger\" notification) is what stops Android ending the app when you swipe it away. After an app update or a restart it must be started again; the app now does that when it opens."));
                    st.add(Math.min(3, st.size()), row("STATUS", "Daily backup", Backup.shortStatus(a), Backup.statusText(a)));
                    {   // 0.19.3: out of range summary from the strap's own connection events
                        long nowS = System.currentTimeMillis() / 1000L;
                        java.util.List<long[]> evs = new java.util.ArrayList<>();
                        java.util.List<String[]> raw = StrapEvents.byIds(a, nowS - 86400L, 3000, 11, 12);          // 0.20.1: by id; a limit on all events cut the night off
                        for (int i = raw.size() - 1; i >= 0; i--) { String[] r = raw.get(i); int id = Integer.parseInt(r[1]); if (id == 11 || id == 12) evs.add(new long[] { id, Long.parseLong(r[0]) }); }
                        long[] rs = rangeSummary(evs);
                        st.add(Math.min(3, st.size()), row("STATUS", "Out of range (24 h)", rs[0] + " absences over 2 min" + (rs[0] > 0 ? ", longest " + ageText(rs[2]) + ", total " + ageText(rs[3]) : "") + "; " + rs[1] + " short drops",
                                "From the strap's own Bluetooth up and down events in the last 24 hours. While the phone is out of reach the strap keeps everything (about three weeks of storage) and the logger pulls it by itself when the strap is back in range. The events of an absence arrive with the first pull after you are back."));
                    }
                    String btv = "?";
                    try { android.bluetooth.BluetoothAdapter ad = android.bluetooth.BluetoothAdapter.getDefaultAdapter(); if (ad != null) btv = ad.isEnabled() ? "ON" : "switch OFF (BLE-only or off)"; } catch (Throwable ignored) { }
                    android.content.SharedPreferences ck = a.getSharedPreferences("clockchk", Context.MODE_PRIVATE);
                    st.add(Math.min(3, st.size()), row("STATUS", "Phone Bluetooth", btv, "The official WHOOP app runs ECG only on a fully ready link (connected, bonded, hello exchanged, clock set). For the live raw test keep the Bluetooth switch ON; today's earlier readings ran with it off (BLE-only)."));
                    if (ck.getLong("at_ms", 0L) > 0) st.add(Math.min(4, st.size()), row("STATUS", "Strap clock", String.format(java.util.Locale.UK, "%+.2f s against the phone", ck.getFloat("diff_s", 0f)), "Measured after the last clock set (includes the Bluetooth delay of some tens of milliseconds). Before the sub-second set it was about -0.4 to -1.1 s."));
                    // 0.19.0: logger health: why the app process ended, crashes, swipes, the 15 minute alarm, battery and overlay settings
                    android.content.SharedPreferences ld = a.getSharedPreferences("linkdiag", Context.MODE_PRIVATE);
                    String pe = ProcExit.stored(a);
                    String[] pel = pe.isEmpty() ? new String[0] : pe.split("\n");
                    st.add(Math.min(5, st.size()), row("STATUS", "App process deaths", pel.length == 0 ? (android.os.Build.VERSION.SDK_INT < 30 ? "not available below Android 11" : "none recorded yet") : pel.length + " recorded, newest " + pel[0].substring(0, Math.min(pel[0].length(), 60)),
                            pel.length == 0 ? "Android keeps the reason each time it ends this app (system exit history, read at every start and by the 15 minute alarm). A line looks like: when, REASON, importance when it ended, process." : pe));
                    String cr = CrashLog.last(a);
                    st.add(Math.min(6, st.size()), row("STATUS", "Last app crash", cr.isEmpty() ? "none recorded" : cr.substring(0, Math.min(cr.length(), 60)), cr.isEmpty() ? "An uncaught exception would be stored here with its stack." : cr));
                    long trm = ld.getLong("task_removed_ms", 0L), alm = ld.getLong("alarm_ms", 0L);
                    java.text.SimpleDateFormat hf = new java.text.SimpleDateFormat("EEE d MMM HH:mm", java.util.Locale.UK);
                    st.add(Math.min(7, st.size()), row("STATUS", "Logger survival", "swiped away " + ld.getInt("n_task_removed", 0) + "x, alarm ran " + ld.getInt("n_alarm", 0) + "x, restarted by alarm " + ld.getInt("n_alarm_restart", 0) + "x",
                            "Battery optimisation: " + (KeepAlive.batteryExempt(a) ? "exempt (good)" : "NOT exempt (Android may end the app)") + ". Display over other apps: " + (android.os.Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(a) ? "allowed" : "not allowed")
                                    + ". Last swipe away: " + (trm > 0 ? hf.format(new java.util.Date(trm)) : "never counted") + ". Next alarm due: " + (ld.getLong("alarm_next_ms", 0L) > 0 ? hf.format(new java.util.Date(ld.getLong("alarm_next_ms", 0L))) + (System.currentTimeMillis() > ld.getLong("alarm_next_ms", 0L) + 300000L ? " (OVERDUE by " + ageText((System.currentTimeMillis() - ld.getLong("alarm_next_ms", 0L)) / 1000L) + ")" : "") : "unknown")
                                    + ". Last alarm: " + (alm > 0 ? hf.format(new java.util.Date(alm)) + (ld.getBoolean("alarm_alive", true) ? " (service was running)" : " (service was NOT running, the alarm started it)") : "not yet")
                                    + ". Android restarted the service by itself " + ld.getInt("n_svc_restart", 0) + "x."));
                    {
                        String bt = batteryText(StrapEvents.byIds(a, now - 3 * 86400L, 600, 29), now);
                        if (bt != null) st.add(Math.min(5, st.size()), row("STATUS", "Strap battery", bt, "From the strap's own condition report, every 10 minutes. The rate is the fall over the last 24 hours without charging; it includes ECG readings and the live stream."));
                    }
                    android.content.SharedPreferences bl = a.getSharedPreferences("backlog", Context.MODE_PRIVATE);
                    String cond = StrapEvents.lastDec(a, 29);
                    if (bl.getLong("ring", 0L) > 0 || cond != null) {
                        long ring = bl.getLong("ring", 0L), beh = bl.getLong("behind", 0L);
                        st.add(Math.min(4, st.size()), row("STATUS", "Strap storage", ring > 0 ? String.format(java.util.Locale.UK, "%,d pages behind (%.2f %%)", beh, 100.0 * beh / ring) : "no pull yet",
                                (ring > 0 ? "Flash ring of " + String.format(java.util.Locale.UK, "%,d", ring) + " pages; read pointer " + bl.getLong("read", 0L) + ", write pointer " + bl.getLong("write", 0L) + ", at the last pull. " : "")
                                        + (cond != null ? "Strap condition report (every 10 minutes): " + cond : "No condition report received yet.")));
                    }
                    android.content.SharedPreferences lrp = a.getSharedPreferences("liveraw", Context.MODE_PRIVATE);
                    if (lrp.getInt("n_ok", 0) + lrp.getInt("n_bad", 0) + lrp.getInt("r126", -1) + lrp.getInt("r127", -1) > -2) {
                        st.add(Math.min(5, st.size()), row("STATUS", "Live raw 500 Hz test", lrp.getInt("n_ok", 0) + " frames ok / " + lrp.getInt("n_bad", 0) + " bad",
                                "Command 126 answered " + (lrp.getInt("r126", -1) < 0 ? "not yet" : String.valueOf(lrp.getInt("r126", -1))) + ", command 127 answered " + (lrp.getInt("r127", -1) < 0 ? "not yet" : String.valueOf(lrp.getInt("r127", -1)))
                                        + " (1 = accepted). Gaps " + lrp.getInt("gaps", 0) + ", missing records " + lrp.getInt("missing", 0) + ". Last frame lag " + String.format(java.util.Locale.UK, "%.1f", lrp.getFloat("lag_s", 0f)) + " s including the clock offset; last record counter " + lrp.getLong("last_ctr", 0L) + ", " + lrp.getInt("last_n", 0) + " samples. Frame census: " + lrp.getString("census", "none yet")));
                    }
                    android.content.SharedPreferences dp = a.getSharedPreferences("linkdiag", Context.MODE_PRIVATE);
                    st.add(Math.min(6, st.size()), row("STATUS", "Swipe-away and reopen", dp.getInt("n_kept", 0) + " kept / " + dp.getInt("n_rebuilt", 0) + " rebuilt / " + dp.getInt("n_fresh", 0) + " fresh",
                            diagText(dp.getInt("n_kept", 0), dp.getInt("n_rebuilt", 0), dp.getInt("n_fresh", 0), dp.getInt("n_swipe_keep", 0), dp.getLong("last_ms", 0L), dp.getString("last_kind", ""))
                            + ". Fresh start after a swipe means Android killed the app process."));
                    st.add(Math.min(7, st.size()), row("STATUS", "Link owner starts",
                            dp.getInt("n_host", 0) + " invisible / " + dp.getInt("n_destroy", 0) + " screens ended / " + dp.getInt("n_svc_restart", 0) + " service restarts",
                            "Invisible = started by the app with no screen. Screens ended counts every time the strap-link screen was destroyed, kept or not. Service restarts = Android brought the logger service back after ending it. Service starts " + dp.getInt("n_svc_start", 0) + "."));
                    rows.addAll(st);
                    if (!recs.isEmpty()) rows.addAll(nowRows(recs.get(recs.size() - 1)));
                    rows.addAll(liveRows());
                    rows.addAll(dayRows(recs, now));
                    rows.addAll(eventRows(a, now));
                    rows.addAll(typeRows(a));
                } catch (Throwable t) { rows.add(row("ERROR", "Could not read the log", String.valueOf(t), null)); }
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        body.removeAllViews();
                        String g = "";
                        for (String[] x : rows) {
                            if (!x[0].equals(g)) {
                                g = x[0];
                                TextView h = HeartGraph.label(a, g, 11, 0xFF6EC3FF, 600);
                                h.setLetterSpacing(0.14f);
                                body.addView(h, HeartGraph.lp(a, -1, -2, 18));
                            }
                            LinearLayout rw = new LinearLayout(a);
                            rw.setOrientation(LinearLayout.VERTICAL);
                            LinearLayout line = new LinearLayout(a);
                            line.setOrientation(LinearLayout.HORIZONTAL);
                            TextView l = HeartGraph.label(a, x[1], 13, INK, 400);
                            TextView v = HeartGraph.label(a, x[2], 13, INK, 600);
                            v.setGravity(Gravity.END);
                            line.addView(l, new LinearLayout.LayoutParams(0, -2, 0.9f));
                            line.addView(v, new LinearLayout.LayoutParams(0, -2, 1.3f));
                            rw.addView(line);
                            if (!x[3].isEmpty()) rw.addView(HeartGraph.label(a, x[3], 10.5f, MUT, 400));
                            body.addView(rw, HeartGraph.lp(a, -1, -2, 9));
                        }
                        copy.setOnClickListener(new View.OnClickListener() {
                            @Override public void onClick(View v) {
                                try {
                                    ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
                                    cm.setPrimaryClip(ClipData.newPlainText("MG strap data", asText(rows)));
                                    android.widget.Toast.makeText(a, "Copied", android.widget.Toast.LENGTH_SHORT).show();
                                } catch (Throwable ignored) { }
                            }
                        });
                    }
                });
            }
        }, "strapdata").start();
    }
}
