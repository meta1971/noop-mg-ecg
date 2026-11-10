package com.noop.mgecg;

// FILE VERSION 0.50.0 (10 Nov 2026): the rule and the alert keep the 0.30.0 behaviour and gain what the held data showed (decode log 141): a window also needs a clean pulse signal (median pulse SNR 20 or more), little movement, no activity flag, lock lost in at most 10 % of seconds and an interval median that matches the heart rate (double-counted beats); an alert whose windows ended during sleep waits until the strap says awake (or 9 hours); every window is stored in daylog.db table rhythm_win (Trends row, export mg_rhythm_screen.csv).
// FILE VERSION 0.30.0 (31 Oct 2026): irregular rhythm watch (rest gate = the strap's own STILL flag). Runs after every history ingest of the day logger. Research only, not a medical device.
// Rule (settled on the public AF database and on 24 h of this strap, see the screen text): per-second records whose pulse lock is lost or whose byte 33 high nibble
// is not 0 are dropped; beat intervals (R18 bytes 23-31, milliseconds) outside 300-2000 ms or more than 40 % from the median of the previous 11 are dropped;
// a 5-minute window with at least 100 intervals, heart rate 100 or less and the strap's sleep state (byte 81 bits 4-5) STILL, sleep or up in at least 80 % of its seconds is "irregular" when RMSSD / mean interval is above 0.12;
// the alert needs 3 irregular windows in a row (15 minutes), and at most one alert per 6 hours. The alert says: take an ECG.

import android.app.Activity;
import android.app.Dialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
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

final class IrregularWatch {
    private IrregularWatch() {}

    static final int WIN_S = 300, MIN_INTERVALS = 100, NEED = 3, MAX_REST_HR = 100;
    static final double THR = 0.12, FILTER = 0.40, MIN_STILL = 0.80;
    private static final String PREFS = "irrwatch", CH = "irregular_watch";

    static final class Win { long start; int n; double nr; int hr; boolean rest, flag; double mot = 1, lost = 1, sleep, snr = -999, medm; boolean act, gate; }
    static final double SNR_MIN = 20, MOT_MAX = 0.10, LOST_MAX = 0.10, MOT_G = 0.05, CONSIST = 0.2, SLEEP_SHARE = 0.80;
    static final int MIN_RECS = 270;
    /** 0.50.0: false = only windows in which the strap says asleep (state 2 in 80 % of seconds) can be irregular (default; held data: no alert in 42 nights); true = also still windows while awake (Settings below; more alerts). Set from the stored choice before each evaluation. */
    static volatile boolean awakeToo = false;

    private static double median(double[] a, int from, int to) {          // [from, to)
        double[] b = Arrays.copyOfRange(a, from, to); Arrays.sort(b); int n = b.length;
        return n % 2 == 1 ? b[n / 2] : 0.5 * (b[n / 2 - 1] + b[n / 2]);
    }

    /** Pure function (tested off the phone against the Python reference): 5-minute windows from per-second R18 records in time order. */
    static List<Win> windows(List<byte[]> recs) {
        List<Win> out = new ArrayList<Win>();
        int cap = recs.size() * 4 + 4, m = 0, s = 0;
        long[] its = new long[cap]; double[] irr = new double[cap];
        long[] sts = new long[recs.size() + 1]; int[] shr = new int[recs.size() + 1]; long[] ats = new long[recs.size() + 1]; boolean[] ast = new boolean[recs.size() + 1]; int a = 0;
        boolean[] amot = new boolean[recs.size() + 1], alost = new boolean[recs.size() + 1], aact = new boolean[recs.size() + 1], asl = new boolean[recs.size() + 1]; int[] asnr = new int[recs.size() + 1];
        long first = Long.MAX_VALUE, last = 0;
        for (byte[] f : recs) {
            if (!R18Codec.isR18(f)) continue;
            long t = R18Codec.ts(f); if (t < first) first = t; if (t > last) last = t;
            ats[a] = t; ast[a] = R18Codec.state(f) >= 1;                                                // every second: still share of a window
            float dyn = R18Codec.f32(f, 41); amot[a] = dyn >= MOT_G; int sn = (byte) R18Codec.u8(f, 108); alost[a] = sn == -128; asnr[a] = sn; aact[a] = R18Codec.u8(f, 63) > 0; asl[a] = R18Codec.state(f) == 2; a++;      // 0.50.0: window quality gates
            if ((R18Codec.u8(f, 33) & 0xF0) != 0 || (byte) R18Codec.u8(f, 108) == -128) continue;     // pulse lock lost or byte 33 level above 0
            sts[s] = t; shr[s] = R18Codec.hr(f); s++;
            int n = Math.min(R18Codec.u8(f, 23), 4);
            for (int k = 0; k < n; k++) { int v = R18Codec.u16(f, 24 + 2 * k); if (v >= 300 && v <= 2000) { its[m] = t; irr[m] = v; m++; } }
        }
        if (m < MIN_INTERVALS) return out;
        boolean[] keep = new boolean[m];
        for (int j = 0; j < m; j++) {
            int lo = Math.max(0, j - 11);
            double ref = j - lo >= 5 ? median(irr, lo, j) : irr[j];
            keep[j] = Math.abs(irr[j] - ref) <= FILTER * ref;
        }
        for (long w = (first / WIN_S) * WIN_S; w + WIN_S <= last + 1; w += WIN_S) {
            double[] x = new double[MIN_INTERVALS * 4]; int c = 0;
            for (int j = 0; j < m; j++) if (keep[j] && its[j] >= w && its[j] < w + WIN_S) { if (c == x.length) x = Arrays.copyOf(x, c * 2); x[c++] = irr[j]; }
            if (c < MIN_INTERVALS) continue;
            double sum = 0, sq = 0; for (int i = 0; i < c; i++) sum += x[i]; double mean = sum / c;
            for (int i = 1; i < c; i++) { double d = x[i] - x[i - 1]; sq += d * d; }
            Win w1 = new Win(); w1.start = w; w1.n = c; w1.nr = Math.sqrt(sq / (c - 1)) / mean;
            List<Double> hrs = new ArrayList<Double>();
            for (int i = 0; i < s; i++) if (sts[i] >= w && sts[i] < w + WIN_S && shr[i] > 0) hrs.add((double) shr[i]);
            int tot = 0, still = 0, mo = 0, lo = 0, sl = 0; boolean ac = false; List<Double> sns = new ArrayList<Double>();
            for (int i = 0; i < a; i++) if (ats[i] >= w && ats[i] < w + WIN_S) { tot++; if (ast[i]) still++; if (amot[i]) mo++; if (alost[i]) lo++; else sns.add((double) asnr[i]); if (aact[i]) ac = true; if (asl[i]) sl++; }
            double[] h = new double[hrs.size()]; for (int i = 0; i < h.length; i++) h[i] = hrs.get(i);
            w1.hr = h.length == 0 ? 0 : (int) Math.round(median(h, 0, h.length));
            w1.rest = tot >= 150 && still >= MIN_STILL * tot && w1.hr > 0 && w1.hr <= MAX_REST_HR;
            w1.mot = tot > 0 ? mo / (double) tot : 1; w1.lost = tot > 0 ? lo / (double) tot : 1; w1.sleep = tot > 0 ? sl / (double) tot : 0; w1.act = ac;
            if (!sns.isEmpty()) { double[] sa = new double[sns.size()]; for (int i = 0; i < sa.length; i++) sa[i] = sns.get(i); w1.snr = median(sa, 0, sa.length); }
            w1.medm = w1.hr > 0 ? median(x, 0, c) * w1.hr / 60000.0 : 0;
            w1.gate = w1.rest && tot >= MIN_RECS && w1.mot <= MOT_MAX && w1.lost <= LOST_MAX && !w1.act && w1.snr >= SNR_MIN && Math.abs(w1.medm - 1) < CONSIST;
            w1.flag = w1.gate && w1.nr > THR && (awakeToo || w1.sleep >= SLEEP_SHARE);
            out.add(w1);
        }
        return out;
    }

    /** Pure: end time (seconds) of the latest run of NEED consecutive irregular windows, with its mean score x1000 and mean heart rate; {0,0,0} when there is none. A missing window breaks a run. */
    static long[] alertRun(List<Win> ws) {
        int run = 0; long prev = -1, end = 0, runStart = 0, aStart = 0; double sumNr = 0, aNr = 0; int sumHr = 0, aHr = 0;
        for (Win w : ws) {
            if (w.flag) {
                if (run > 0 && w.start == prev + WIN_S) { run++; sumNr += w.nr; sumHr += w.hr; } else { run = 1; sumNr = w.nr; sumHr = w.hr; runStart = w.start; }
                if (run >= NEED) { end = w.start + WIN_S; aNr = sumNr / run; aHr = Math.round((float) sumHr / run); aStart = runStart; }
            } else run = 0;
            prev = w.start;
        }
        return new long[] { end, Math.round(aNr * 1000), aHr, aStart };
    }

    private static SharedPreferences sp(Context c) { return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    static boolean on(Context c) { return sp(c).getBoolean("on", true); }
    static void setOn(Context c, boolean v) { sp(c).edit().putBoolean("on", v).apply(); }

    /** Called from DayLog after each ingest (background thread). */
    static void evaluate(Context c0) {
        try {
            Context c = c0.getApplicationContext(); SharedPreferences p = sp(c);
            if (!p.getBoolean("on", true)) return;
            long now = System.currentTimeMillis() / 1000L;
            List<byte[]> recs = new ArrayList<byte[]>();
            SQLiteDatabase d = DayLog.db(c);
            Cursor cu = d.rawQuery("SELECT rec FROM r18 WHERE ts >= ? ORDER BY ts", new String[] { String.valueOf(now - 6L * 3600L) });
            try { while (cu.moveToNext()) { byte[] b = cu.getBlob(0); if (b != null) recs.add(b); } } finally { cu.close(); }
            awakeToo = p.getBoolean("awakeToo", false);
            List<Win> ws = windows(recs);
            // keep the last 48 hours of window results (start,nr x1000,n,hr,flags: 1 rest, 2 irregular, 4 usable = every quality gate passed, 8 asleep = strap state 2 in 80 % of the window)
            java.util.TreeMap<Long, String> keep = new java.util.TreeMap<Long, String>();
            for (String ln : p.getString("wins", "").split("\n")) { String[] q = ln.split(","); if (q.length == 5) { try { long st = Long.parseLong(q[0]); if (st > now - 48L * 3600L) keep.put(st, ln); } catch (Exception ignored) { } } }
            for (Win w : ws) keep.put(w.start, w.start + "," + Math.round(w.nr * 1000) + "," + w.n + "," + w.hr + "," + flagsOf(w));
            StringBuilder sb = new StringBuilder(); for (String ln : keep.values()) sb.append(ln).append('\n');
            p.edit().putString("wins", sb.toString()).putLong("lastEval", now).apply();
            store(c, ws);
            long[] ar = alertRun(ws); long alertEnd = ar[0]; double aNr = ar[1] / 1000.0; int aHr = (int) ar[2];
            long lastAlert = p.getLong("lastAlertEnd", 0L);
            if (alertEnd > 0 && alertEnd > lastAlert + 6L * 3600L && alertEnd >= now - 12L * 3600L) {
                String hist = p.getString("alerts", "");
                p.edit().putLong("lastAlertEnd", alertEnd).putLong("pendEnd", alertEnd).putLong("pendStart", ar[3]).putString("alerts", alertEnd + "," + ar[1] + "," + aHr + "\n" + hist).apply();
            }
            deliver(c, p, recs, now);
            if (!p.getBoolean("backfilled50", false)) startBackfill(c);
        } catch (Throwable ignored) { }
    }

    static int flagsOf(Win w) { return (w.rest ? 1 : 0) | (w.flag ? 2 : 0) | (w.gate ? 4 : 0) | (w.sleep >= SLEEP_SHARE ? 8 : 0); }

    /** 0.50.0: an alert queued while the strap says asleep waits until the newest five minutes of records show it awake (or 9 hours); awake alerts go at once. */
    private static void deliver(Context c, SharedPreferences p, List<byte[]> recs, long now) {
        long pe = p.getLong("pendEnd", 0L); if (pe <= 0L) return;
        long ps = p.getLong("pendStart", pe - 900L); boolean awake = false;
        long tmax = 0; for (byte[] f : recs) if (R18Codec.isR18(f)) tmax = Math.max(tmax, R18Codec.ts(f));
        if (tmax > 0) { int tot = 0, sl = 0; for (byte[] f : recs) if (R18Codec.isR18(f) && R18Codec.ts(f) > tmax - 300L) { tot++; if (R18Codec.state(f) == 2) sl++; } awake = tot >= 60 && sl < 0.5 * tot && now - tmax < 3600L; }
        if (awake || now - pe > 9L * 3600L) { p.edit().putLong("pendEnd", 0L).apply(); notifyUser(c, ps, pe); }
    }

    private static void ensureTable(SQLiteDatabase d) { d.execSQL("CREATE TABLE IF NOT EXISTS rhythm_win (start INTEGER PRIMARY KEY, n INTEGER, nr INTEGER, hr INTEGER, flags INTEGER, snr INTEGER, mot INTEGER, lost INTEGER, sleep INTEGER)"); }

    /** 0.50.0: every window, one row each (nr, motion, lost and sleep shares x1000; snr -999 = none): the Trends row and mg_rhythm_screen.csv read it. */
    static void store(Context c, List<Win> ws) {
        if (ws == null || ws.isEmpty()) return;
        try {
            SQLiteDatabase d = DayLog.db(c); ensureTable(d); d.beginTransaction();
            try { for (Win w : ws) d.execSQL("INSERT OR REPLACE INTO rhythm_win VALUES (?,?,?,?,?,?,?,?,?)", new Object[] { w.start, w.n, Math.round(w.nr * 1000), w.hr, flagsOf(w), Math.round(w.snr), Math.round(w.mot * 1000), Math.round(w.lost * 1000), Math.round(w.sleep * 1000) }); d.setTransactionSuccessful(); } finally { d.endTransaction(); }
        } catch (Throwable ignored) { }
    }

    private static volatile boolean backfilling = false;

    /** 0.50.0: one-time background fill of rhythm_win from the stored per-second records (last 100 days, 6-hour blocks, same rule as the live check). */
    static void startBackfill(final Context c) {
        if (backfilling) return; backfilling = true;
        Thread t = new Thread(new Runnable() { @Override public void run() {
            try {
                SQLiteDatabase d = DayLog.db(c); ensureTable(d); long lo = 0, hi = 0;
                Cursor q = d.rawQuery("SELECT MIN(ts), MAX(ts) FROM r18", null);
                try { if (q.moveToFirst() && !q.isNull(0)) { lo = q.getLong(0); hi = q.getLong(1); } } finally { q.close(); }
                long B = 6L * 3600L, from = Math.max(lo, hi - 100L * 86400L);
                for (long b = (from / B) * B; b <= hi && hi > 0; b += B) {
                    List<byte[]> rs = new ArrayList<byte[]>();
                    Cursor cu = d.rawQuery("SELECT rec FROM r18 WHERE ts >= ? AND ts < ? ORDER BY ts", new String[] { String.valueOf(b), String.valueOf(b + B) });
                    try { while (cu.moveToNext()) { byte[] x = cu.getBlob(0); if (x != null) rs.add(x); } } finally { cu.close(); }
                    if (rs.size() >= MIN_RECS) store(c, windows(rs));
                    try { Thread.sleep(5); } catch (InterruptedException ie) { return; }
                }
                sp(c).edit().putBoolean("backfilled50", true).apply();
            } catch (Throwable ignored) { } finally { backfilling = false; }
        } }, "rhythm-backfill");
        t.setPriority(Thread.MIN_PRIORITY); t.start();
    }

    /** Trends: the night's pulse scatter while asleep = median nr (x100, percent) over the usable asleep windows that start in [from, to); {value, usable windows, irregular windows}; value NaN under 6 windows. */
    static double[] sleepScatter(Context c, long from, long to) {
        List<Double> v = new ArrayList<Double>(); int irr = 0;
        try {
            SQLiteDatabase d = DayLog.db(c); ensureTable(d);
            Cursor cu = d.rawQuery("SELECT nr, flags FROM rhythm_win WHERE start >= ? AND start < ?", new String[] { String.valueOf(from), String.valueOf(to) });
            try { while (cu.moveToNext()) { int f = cu.getInt(1); if ((f & 12) != 12) continue; v.add(cu.getInt(0) / 10.0); if ((f & 2) != 0) irr++; } } finally { cu.close(); }
        } catch (Throwable ignored) { }
        if (v.size() < 6) return new double[] { Double.NaN, v.size(), irr };
        double[] a = new double[v.size()]; for (int i = 0; i < a.length; i++) a[i] = v.get(i);
        return new double[] { median(a, 0, a.length), v.size(), irr };
    }

    /** Export: mg_rhythm_screen.csv (one row per stored window since the given unix second). */
    static String csv(SQLiteDatabase d, long since) {
        StringBuilder sb = new StringBuilder("start_unix,n,nr_x1000,hr,flags,snr,mot_x1000,lost_x1000,sleep_x1000\n");
        try {
            ensureTable(d);
            Cursor cu = d.rawQuery("SELECT start, n, nr, hr, flags, snr, mot, lost, sleep FROM rhythm_win WHERE start >= ? ORDER BY start", new String[] { String.valueOf(since) });
            try { while (cu.moveToNext()) { for (int i = 0; i < 9; i++) { if (i > 0) sb.append(','); sb.append(cu.getLong(i)); } sb.append('\n'); } } finally { cu.close(); }
        } catch (Throwable ignored) { }
        return sb.toString();
    }

    private static void notifyUser(Context c, long startSec, long endSec) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel(CH, "Irregular rhythm watch", NotificationManager.IMPORTANCE_DEFAULT));
            HealthActivity.pendingTab = 2;
            Intent open = new Intent(c, HealthActivity.class).putExtra("tab", 2).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi = PendingIntent.getActivity(c, 4402, open, PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000);      // FLAG_IMMUTABLE
            SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.UK); String at = hm.format(new Date(startSec * 1000L)) + "-" + hm.format(new Date(endSec * 1000L));
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CH) : new Notification.Builder(c);
            b.setSmallIcon(android.R.drawable.ic_dialog_info)
             .setContentTitle("Irregular pulse pattern")
             .setContentText("At rest, " + at + ". Not a diagnosis. Take an ECG.")
             .setStyle(new Notification.BigTextStyle().bigText("Your pulse looked irregular at rest, " + at + " (15 minutes or more). Take an ECG reading to look at the rhythm. Research only, not a diagnosis; an irregular pulse is not always atrial fibrillation."))
             .setContentIntent(pi).setAutoCancel(true);
            nm.notify(4402, b.build());
        } catch (Throwable ignored) { }
    }

    /** Counts over the stored windows of the last 24 hours: {checked, at rest, irregular}, and the largest score x1000. */
    static int[] last24(Context c) {
        int chk = 0, rest = 0, irr = 0, mx = 0, use = 0; long lim = System.currentTimeMillis() / 1000L - 24L * 3600L;
        for (String ln : sp(c).getString("wins", "").split("\n")) { String[] q = ln.split(","); if (q.length != 5) continue; try { if (Long.parseLong(q[0]) < lim) continue; int f = Integer.parseInt(q[4]); chk++; if ((f & 1) != 0) { rest++; mx = Math.max(mx, Integer.parseInt(q[1])); } if ((f & 2) != 0) irr++; if ((f & 4) != 0) use++; } catch (Exception ignored) { } }
        return new int[] { chk, rest, irr, mx, use };
    }

    /** Last 48 hours of stored rest (STILL) windows grouped by local clock hour: {median RMSSD in ms, count} per hour (optical, relative: about 1.5 to 2 times an ECG value). RMSSD = score x mean interval, mean interval = 60000 / heart rate. */
    static double[][] hourly(Context c) {
        List<List<Double>> g = new ArrayList<List<Double>>(); for (int i = 0; i < 24; i++) g.add(new ArrayList<Double>());
        long lim = System.currentTimeMillis() / 1000L - 48L * 3600L; java.util.Calendar cal = java.util.Calendar.getInstance();
        for (String ln : sp(c).getString("wins", "").split("\n")) {
            String[] q = ln.split(","); if (q.length != 5) continue;
            try { long st = Long.parseLong(q[0]); if (st < lim) continue; if ((Integer.parseInt(q[4]) & 1) == 0) continue; double nr = Integer.parseInt(q[1]) / 1000.0; int hr = Integer.parseInt(q[3]); if (hr < 30) continue;
                cal.setTimeInMillis(st * 1000L); g.get(cal.get(java.util.Calendar.HOUR_OF_DAY)).add(nr * 60000.0 / hr); } catch (Exception ignored) { }
        }
        double[][] out = new double[24][2];
        for (int h = 0; h < 24; h++) { List<Double> v = g.get(h); out[h][1] = v.size(); if (v.isEmpty()) { out[h][0] = Double.NaN; continue; } java.util.Collections.sort(v); out[h][0] = v.size() % 2 == 1 ? v.get(v.size() / 2) : 0.5 * (v.get(v.size() / 2 - 1) + v.get(v.size() / 2)); }
        return out;
    }

    static String statusLine(Context c) {
        if (!on(c)) return "Off";
        int[] k = last24(c);
        if (k[0] == 0) return "On. Waiting for the next history pull to check your pulse.";
        return "On. Last 24 hours: " + k[0] + " windows checked (" + k[1] + " at rest, " + k[4] + " with a clean signal), " + k[2] + " irregular";
    }

    static void show(final Activity a) {
        final Dialog dlg = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(a); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG);
        int pad = Math.round(dp(a, 16)); root.setPadding(pad, Math.round(dp(a, 30)), pad, Math.round(dp(a, 14)));
        LinearLayout top = new LinearLayout(a); top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(HeartGraph.label(a, "Irregular rhythm watch", 22, INK, 300));
        top.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView close = HeartGraph.pill(a, "Close", 0x1AFFFFFF, INK); close.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { dlg.dismiss(); } }); top.addView(close);
        root.addView(top);
        final LinearLayout col = new LinearLayout(a); col.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(a); sv.addView(col); root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        final Context app = a.getApplicationContext();
        final TextView status = HeartGraph.label(a, "", 15, INK, 400); status.setLineSpacing(0, 1.2f);
        final TextView toggle = HeartGraph.pill(a, "", BLUE, 0xFF05131E);
        final TextView toggle2 = HeartGraph.pill(a, "", 0x1AFFFFFF, INK);
        final Runnable fill = new Runnable() { @Override public void run() { status.setText(statusLine(app)); ((TextView) toggle).setText(on(app) ? "Turn the watch off" : "Turn the watch on"); toggle2.setText(sp(app).getBoolean("awakeToo", false) ? "Awake checks: on (more alerts)" : "Awake checks: off (sleep only)"); } };
        toggle.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { setOn(app, !on(app)); fill.run(); } });
        toggle2.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { sp(app).edit().putBoolean("awakeToo", !sp(app).getBoolean("awakeToo", false)).apply(); fill.run(); } });
        LinearLayout card = new LinearLayout(a); card.setOrientation(LinearLayout.VERTICAL); card.setBackground(glass(a, 18)); int cp = Math.round(dp(a, 16)); card.setPadding(cp, cp, cp, cp);
        card.addView(status); card.addView(toggle, HeartGraph.lp(a, -2, -2, 12)); card.addView(toggle2, HeartGraph.lp(a, -2, -2, 8));
        col.addView(card, HeartGraph.lp(a, -1, -2, 14));
        StringBuilder al = new StringBuilder(); int cnt = 0;
        for (String ln : sp(app).getString("alerts", "").split("\n")) { String[] q = ln.split(","); if (q.length != 3 || cnt >= 8) continue; try { al.append(new SimpleDateFormat("EEE d MMM HH:mm", Locale.UK).format(new Date(Long.parseLong(q[0]) * 1000L))).append(": score ").append(String.format(Locale.UK, "%.2f", Integer.parseInt(q[1]) / 1000.0)).append(", heart rate ").append(q[2]).append('\n'); cnt++; } catch (Exception ignored) { } }
        TextView hist = HeartGraph.label(a, cnt == 0 ? "No alerts yet." : "Alerts (newest first)\n" + al, 13, MUT, 400); hist.setLineSpacing(0, 1.2f);
        col.addView(hist, HeartGraph.lp(a, -1, -2, 14));
        TextView how = HeartGraph.label(a,
            "HOW IT WORKS\n"
            + "After each history pull from the strap the app takes the last 6 hours of per-second records. It drops seconds where the strap's pulse lock is lost or its signal level (byte 33) is not clean, and any beat interval more than 40 % away from the recent median.\n\n"
            + "A 5-minute window with at least 100 intervals, heart rate 100 or less and the strap's own STILL flag in at least 80 % of its seconds counts as irregular when RMSSD divided by the mean interval is above 0.12 (about 0.02 on your normal days). Three irregular windows in a row (15 minutes) raise one notification, at most one every 6 hours. It says: take an ECG.\n\n"
            + "SINCE 0.50.0\nBy default only windows in which the strap says you are asleep can count as irregular; the switch Awake checks adds still windows while awake (more alerts: in held strap data they gave several irregular-looking runs with no known cause). A window must also have a clean pulse signal (median pulse SNR 20 or more), movement above 0.05 g in at most 10 % of its seconds, no walking or running flag, pulse lock lost in at most 10 % of seconds, and a median interval that matches the heart rate within 20 % (this removes windows where the strap counted beats twice). Held strap data (decode log 141) showed that irregular-looking windows with a weak pulse signal were artefacts. If the three windows end while the strap says you are asleep, the alert waits until you are awake (or 9 hours). Every window is stored for the Trends row Sleep pulse scatter and the export file mg_rhythm_screen.csv.\n\n"
            + "WHAT IS MEASURED\n"
            + "Public AF database, 23 recordings, 5-minute windows from ECG R-R with the same rule: atrial fibrillation windows flagged 99 % (429 windows); windows of other rhythms flagged 40 % (2,063 windows, many with frequent early beats). So an alert means irregular, not necessarily atrial fibrillation.\n"
            + "Your strap, 10 days (20 to 31 Oct): 1,013 still windows of 1,320 with enough intervals: median 0.019, 99th percentile 0.081, none above 0.12, so no alert. The 307 windows where the strap was not still gave median 0.050, and the 6 windows above 0.12 under the older movement rule were all of that kind. Without the signal-level gate the same days give 156 windows above 0.12: the gates are what make it usable; together they drop about 23 % of windows.\n"
            + "Not measured: the strap has never recorded atrial fibrillation, so the sensitivity of the optical pulse is unknown. Pulse intervals arrive with each pull (every 30 minutes), so an alert can be up to about 45 minutes late.\n\n"
            + "Research only. Not a medical device. If you feel unwell, get help; do not wait for this.", 13, MUT, 400);
        how.setLineSpacing(0, 1.25f); col.addView(how, HeartGraph.lp(a, -1, -2, 16));
        fill.run();
        dlg.setContentView(root); dlg.show();
    }
}
