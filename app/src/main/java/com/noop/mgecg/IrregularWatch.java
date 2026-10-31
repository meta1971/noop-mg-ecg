package com.noop.mgecg;

// FILE VERSION 0.28.0 (31 Oct 2026): irregular rhythm watch. Runs after every history ingest of the day logger. Research only, not a medical device.
// Rule (settled on the public AF database and on 24 h of this strap, see the screen text): per-second records whose pulse lock is lost or whose byte 33 high nibble
// is not 0 are dropped; beat intervals (R18 bytes 23-31, milliseconds) outside 300-2000 ms or more than 40 % from the median of the previous 11 are dropped;
// a 5-minute window with at least 100 intervals, heart rate 100 or less and little movement is "irregular" when RMSSD / mean interval is above 0.12;
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
    static final double THR = 0.12, FILTER = 0.40, MAX_MOVE_FRAC = 0.10;
    private static final String PREFS = "irrwatch", CH = "irregular_watch";

    static final class Win { long start; int n; double nr; int hr; boolean rest, flag; }

    private static double median(double[] a, int from, int to) {          // [from, to)
        double[] b = Arrays.copyOfRange(a, from, to); Arrays.sort(b); int n = b.length;
        return n % 2 == 1 ? b[n / 2] : 0.5 * (b[n / 2 - 1] + b[n / 2]);
    }

    /** Pure function (tested off the phone against the Python reference): 5-minute windows from per-second R18 records in time order. */
    static List<Win> windows(List<byte[]> recs) {
        List<Win> out = new ArrayList<Win>();
        int cap = recs.size() * 4 + 4, m = 0, s = 0;
        long[] its = new long[cap]; double[] irr = new double[cap];
        long[] sts = new long[recs.size() + 1]; int[] shr = new int[recs.size() + 1]; int[] sact = new int[recs.size() + 1];
        long first = Long.MAX_VALUE, last = 0;
        for (byte[] f : recs) {
            if (!R18Codec.isR18(f)) continue;
            long t = R18Codec.ts(f); if (t < first) first = t; if (t > last) last = t;
            if ((R18Codec.u8(f, 33) & 0xF0) != 0 || (byte) R18Codec.u8(f, 108) == -128) continue;     // pulse lock lost or byte 33 level above 0
            sts[s] = t; shr[s] = R18Codec.hr(f); sact[s] = R18Codec.u8(f, 63); s++;
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
            List<Double> hrs = new ArrayList<Double>(); int mv = 0, ns = 0;
            for (int i = 0; i < s; i++) if (sts[i] >= w && sts[i] < w + WIN_S) { ns++; if (shr[i] > 0) hrs.add((double) shr[i]); if (sact[i] > 0) mv++; }
            double[] h = new double[hrs.size()]; for (int i = 0; i < h.length; i++) h[i] = hrs.get(i);
            w1.hr = h.length == 0 ? 0 : (int) Math.round(median(h, 0, h.length));
            w1.rest = ns > 0 && w1.hr > 0 && w1.hr <= MAX_REST_HR && mv <= MAX_MOVE_FRAC * ns;
            w1.flag = w1.rest && w1.nr > THR;
            out.add(w1);
        }
        return out;
    }

    /** Pure: end time (seconds) of the latest run of NEED consecutive irregular windows, with its mean score x1000 and mean heart rate; {0,0,0} when there is none. A missing window breaks a run. */
    static long[] alertRun(List<Win> ws) {
        int run = 0; long prev = -1, end = 0; double sumNr = 0, aNr = 0; int sumHr = 0, aHr = 0;
        for (Win w : ws) {
            if (w.flag) {
                if (run > 0 && w.start == prev + WIN_S) { run++; sumNr += w.nr; sumHr += w.hr; } else { run = 1; sumNr = w.nr; sumHr = w.hr; }
                if (run >= NEED) { end = w.start + WIN_S; aNr = sumNr / run; aHr = Math.round((float) sumHr / run); }
            } else run = 0;
            prev = w.start;
        }
        return new long[] { end, Math.round(aNr * 1000), aHr };
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
            List<Win> ws = windows(recs);
            // keep the last 48 hours of window results (start,nr x1000,n,hr,flags: 1 rest, 2 irregular)
            java.util.TreeMap<Long, String> keep = new java.util.TreeMap<Long, String>();
            for (String ln : p.getString("wins", "").split("\n")) { String[] q = ln.split(","); if (q.length == 5) { try { long st = Long.parseLong(q[0]); if (st > now - 48L * 3600L) keep.put(st, ln); } catch (Exception ignored) { } } }
            for (Win w : ws) keep.put(w.start, w.start + "," + Math.round(w.nr * 1000) + "," + w.n + "," + w.hr + "," + ((w.rest ? 1 : 0) | (w.flag ? 2 : 0)));
            StringBuilder sb = new StringBuilder(); for (String ln : keep.values()) sb.append(ln).append('\n');
            p.edit().putString("wins", sb.toString()).putLong("lastEval", now).apply();
            long[] ar = alertRun(ws); long alertEnd = ar[0]; double aNr = ar[1] / 1000.0; int aHr = (int) ar[2];
            long lastAlert = p.getLong("lastAlertEnd", 0L);
            if (alertEnd > 0 && alertEnd > lastAlert + 6L * 3600L && alertEnd >= now - 12L * 3600L) {
                String hist = p.getString("alerts", "");
                p.edit().putLong("lastAlertEnd", alertEnd).putString("alerts", alertEnd + "," + ar[1] + "," + aHr + "\n" + hist).apply();
                notifyUser(c, alertEnd);
            }
        } catch (Throwable ignored) { }
    }

    private static void notifyUser(Context c, long endSec) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel(CH, "Irregular rhythm watch", NotificationManager.IMPORTANCE_DEFAULT));
            HealthActivity.pendingTab = 2;
            Intent open = new Intent(c, HealthActivity.class).putExtra("tab", 2).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi = PendingIntent.getActivity(c, 4402, open, PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000);      // FLAG_IMMUTABLE
            String at = new SimpleDateFormat("HH:mm", Locale.UK).format(new Date(endSec * 1000L));
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CH) : new Notification.Builder(c);
            b.setSmallIcon(android.R.drawable.ic_dialog_info)
             .setContentTitle("Irregular pulse pattern")
             .setContentText("At rest for 15 minutes, until " + at + ". Take an ECG now.")
             .setStyle(new Notification.BigTextStyle().bigText("Your pulse looked irregular at rest for 15 minutes, until " + at + ". Take an ECG reading now to look at the rhythm. Research only, not a diagnosis; an irregular pulse is not always atrial fibrillation."))
             .setContentIntent(pi).setAutoCancel(true);
            nm.notify(4402, b.build());
        } catch (Throwable ignored) { }
    }

    /** Counts over the stored windows of the last 24 hours: {checked, at rest, irregular}, and the largest score x1000. */
    static int[] last24(Context c) {
        int chk = 0, rest = 0, irr = 0, mx = 0; long lim = System.currentTimeMillis() / 1000L - 24L * 3600L;
        for (String ln : sp(c).getString("wins", "").split("\n")) { String[] q = ln.split(","); if (q.length != 5) continue; try { if (Long.parseLong(q[0]) < lim) continue; int f = Integer.parseInt(q[4]); chk++; if ((f & 1) != 0) { rest++; mx = Math.max(mx, Integer.parseInt(q[1])); } if ((f & 2) != 0) irr++; } catch (Exception ignored) { } }
        return new int[] { chk, rest, irr, mx };
    }

    static String statusLine(Context c) {
        if (!on(c)) return "Off";
        int[] k = last24(c);
        if (k[0] == 0) return "On. Waiting for the next history pull to check your pulse.";
        return "On. Last 24 hours: " + k[0] + " windows checked (" + k[1] + " at rest), " + k[2] + " irregular";
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
        final Runnable fill = new Runnable() { @Override public void run() { status.setText(statusLine(app)); ((TextView) toggle).setText(on(app) ? "Turn the watch off" : "Turn the watch on"); } };
        toggle.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { setOn(app, !on(app)); fill.run(); } });
        LinearLayout card = new LinearLayout(a); card.setOrientation(LinearLayout.VERTICAL); card.setBackground(glass(a, 18)); int cp = Math.round(dp(a, 16)); card.setPadding(cp, cp, cp, cp);
        card.addView(status); card.addView(toggle, HeartGraph.lp(a, -2, -2, 12));
        col.addView(card, HeartGraph.lp(a, -1, -2, 14));
        StringBuilder al = new StringBuilder(); int cnt = 0;
        for (String ln : sp(app).getString("alerts", "").split("\n")) { String[] q = ln.split(","); if (q.length != 3 || cnt >= 8) continue; try { al.append(new SimpleDateFormat("EEE d MMM HH:mm", Locale.UK).format(new Date(Long.parseLong(q[0]) * 1000L))).append(": score ").append(String.format(Locale.UK, "%.2f", Integer.parseInt(q[1]) / 1000.0)).append(", heart rate ").append(q[2]).append('\n'); cnt++; } catch (Exception ignored) { } }
        TextView hist = HeartGraph.label(a, cnt == 0 ? "No alerts yet." : "Alerts (newest first)\n" + al, 13, MUT, 400); hist.setLineSpacing(0, 1.2f);
        col.addView(hist, HeartGraph.lp(a, -1, -2, 14));
        TextView how = HeartGraph.label(a,
            "HOW IT WORKS\n"
            + "After each history pull from the strap the app takes the last 6 hours of per-second records. It drops seconds where the strap's pulse lock is lost or its signal level (byte 33) is not clean, and any beat interval more than 40 % away from the recent median.\n\n"
            + "A 5-minute window with at least 100 intervals, heart rate 100 or less and little movement counts as irregular when RMSSD divided by the mean interval is above 0.12 (about 0.02 on your normal days). Three irregular windows in a row (15 minutes) raise one notification, at most one every 6 hours. It says: take an ECG.\n\n"
            + "WHAT IS MEASURED\n"
            + "Public AF database, 23 recordings, 5-minute windows from ECG R-R with the same rule: atrial fibrillation windows flagged 99 % (429 windows); windows of other rhythms flagged 40 % (2,063 windows, many with frequent early beats). So an alert means irregular, not necessarily atrial fibrillation.\n"
            + "Your strap, 24 hours 30 to 31 Oct: 154 windows, none above 0.10, largest 0.087.\n"
            + "Not measured: the strap has never recorded atrial fibrillation, so the sensitivity of the optical pulse is unknown. Pulse intervals arrive with each pull (every 30 minutes), so an alert can be up to about 45 minutes late.\n\n"
            + "Research only. Not a medical device. If you feel unwell, get help; do not wait for this.", 13, MUT, 400);
        how.setLineSpacing(0, 1.25f); col.addView(how, HeartGraph.lp(a, -1, -2, 16));
        fill.run();
        dlg.setContentView(root); dlg.show();
    }
}
