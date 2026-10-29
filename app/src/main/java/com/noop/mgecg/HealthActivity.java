package com.noop.mgecg;

// FILE VERSION 0.9.0 (23 Oct): the heart-first personal health app. Research only.

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import static com.noop.mgecg.HealthViews.*;

/**
 * Today, Heart, ECG, Rest and You, laid out like a glass-and-glow fitness app but about the heart: live heart rate, the ECG
 * readings the strap makes, sleep with its stages, SpO2, and how resting pulse moves over the nights. Everything is computed on
 * this phone from the strap's own records. Sleep stages, recovery-style comparisons and zones are estimates and say so.
 * RESEARCH ONLY, not a medical device and not a diagnosis.
 */
public class HealthActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout content;
    private NavBar nav;
    private int tab = 0;
    private HealthModel.Data data;
    private volatile boolean loading;
    private boolean loadedOnce = false;                  // 0.13.4: the first load has finished (so "no records" is true, not just "not loaded yet")
    private TextView todayHrNum, todayHrSub; private String todayHrSubTail = "";               // 0.13.4: the Today heart-rate card follows the live frames
    private volatile List<ActivityDetector.Bout> actBouts = new ArrayList<ActivityDetector.Bout>();
    private volatile String actLine = "";          // today's activity in one line, read off the main thread
    private volatile String syncRange = "", syncBattery = "";     // 0.21.0: for the sync card's explainer, read off the main thread
    private final java.util.HashSet<String> openCards = new java.util.HashSet<>();     // 0.21.0: which explainers are open (they stay open when the screen refreshes)
    static volatile int pendingTab = -1;               // 0.22.0: set by the old screens when they hand back to a tab (onNewIntent is not always called)
    private volatile String syncSub = "";           // 0.20.0: the strap line of the sync strip (pages behind, battery), read off the main thread
    private TextView syncTitleTv, syncSubTv;
    private int ringOpen = -1;                       // 0.20.0: which ring on Today is expanded (0 recovery, 1 effort, 2 sleep), -1 none
    private LinearLayout ringPanel;
    private TextView liveNum, liveUnit;
    private HealthModel.Data liveData;
    private final Runnable liveTick = new Runnable() {
        @Override public void run() {
            if (tab == 1 && liveNum != null && liveUnit != null) {
                int h = shownHr(liveData);
                liveNum.setText(h > 0 ? String.valueOf(h) : "\u2014");
                liveUnit.setText("  " + shownLabel(liveData));
            }
            applyLink();
            if (tab == 0) updateSyncStrip();                      // 0.20.0
            if (tab == 0 && todayHrNum != null && todayHrSub != null) {
                int hh = shownHr(data);
                todayHrNum.setText(hh > 0 ? hh + " bpm" : "\u2014");
                todayHrSub.setText(shownLabel(data).replace("bpm, ", "") + todayHrSubTail);
            }
            ui.postDelayed(this, 2000L);
        }
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() { load(); ui.postDelayed(this, 60000L); }
    };

    // ------------------------------------------------------------------ lifecycle
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        FrameMonitor.captureTo(getExternalFilesDir(null));          // 0.25.0: raw copies of undecoded frames
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        root.addView(new GlowBg(this), new FrameLayout.LayoutParams(-1, -1));
        content = new FrameLayout(this);
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));
        tab = Math.max(0, Math.min(4, getIntent().getIntExtra("tab", 0)));      // other screens open a given tab
        nav = new NavBar(this, tab, new NavBar.Listener() {
            @Override public void onTab(int t) { tab = t; render(); }
        });
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(-1, Math.round(dp(this, 68)), Gravity.BOTTOM);
        np.setMargins(Math.round(dp(this, 14)), 0, Math.round(dp(this, 14)), Math.round(dp(this, 18)));
        View scrim = new View(this) {
            @Override protected void onDraw(Canvas cv) {
                Paint p = new Paint();
                p.setShader(new LinearGradient(0, 0, 0, getHeight(), 0x0007090C, 0xFF07090C, Shader.TileMode.CLAMP));
                cv.drawRect(0, 0, getWidth(), getHeight(), p);
            }
        };
        root.addView(scrim, new FrameLayout.LayoutParams(-1, Math.round(dp(this, 130)), Gravity.BOTTOM));
        root.addView(nav, np);
        setContentView(root);
        render();
    }

    /** Another screen's bottom bar asks for a tab while this screen is already open. */
    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        tab = Math.max(0, Math.min(4, i.getIntExtra("tab", tab)));
        if (nav != null) nav.setActive(tab);
        render();
    }

    @Override protected void onResume() { super.onResume(); if (pendingTab >= 0) { int pt = pendingTab; pendingTab = -1; tab = Math.max(0, Math.min(4, pt)); nav.setActive(tab); render(); } ui.removeCallbacks(tick); ui.post(tick); ui.removeCallbacks(liveTick); ui.post(liveTick); if (DayLog.enabled(this)) { DayLogService.ensure(this); MainActivity.startLinkHost(this); } }

    @Override protected void onPause() { super.onPause(); ui.removeCallbacks(tick); ui.removeCallbacks(liveTick); }

    /** The heart rate to show: a live notification from the last 90 seconds, otherwise the newest logged second. */
    private int shownHr(HealthModel.Data d) {
        LiveHr.Sample s = LiveHr.latest();
        if (s != null && System.currentTimeMillis() - s.ms < 90000L) return s.hr;
        return d == null ? 0 : d.nowHr;
    }

    private String shownLabel(HealthModel.Data d) {
        LiveHr.Sample s = LiveHr.latest();
        if (s != null && System.currentTimeMillis() - s.ms < 90000L) return "bpm, " + LiveHr.ageText(s);
        if (d != null && d.nowHr > 0 && d.nowTs > 0) return "bpm, logged " + ago(d.nowTs);
        return "bpm";
    }

    private long[] ecgSecs() {
        List<HealthModel.Reading> rs = data == null ? null : data.readings;
        if (rs == null) return new long[0];
        long[] out = new long[rs.size()];
        for (int i = 0; i < out.length; i++) out[i] = rs.get(i).timeMs / 1000L;
        return out;
    }

    private void openGraph(int rangeIdx) { HeartGraph.show(this, rangeIdx, maxHr(), ecgSecs()); }

    private SharedPreferences sp() { return getSharedPreferences("today", MODE_PRIVATE); }
    private int maxHr() { return sp().getInt("maxhr", 167); }
    private float goal() { return sp().getFloat("goal", 8.0f); }
    private String name() { return sp().getString("name", "Ame"); }

    private void load() {
        if (loading) return;
        loading = true;
        final Context app = getApplicationContext();
        final int mh = maxHr();
        final double g = goal();
        new Thread(new Runnable() {
            @Override public void run() {
                HealthModel.Data d = null;
                try { d = HealthModel.load(app, mh, g); } catch (Throwable ignored) { }
                try {
                    ActivityStore.DayData ad = ActivityStore.glanceDay(app);
                    if (ad != null && ad.r != null && ad.r.bouts != null) actBouts = new ArrayList<ActivityDetector.Bout>(ad.r.bouts);
                    actLine = (ad != null && ad.hasData && ad.r != null)
                            ? "Active " + HealthModel.hmShort(ad.r.activeMin * 60L) + (ad.r.longestBoutMin > 0 ? " \u00b7 longest " + HealthModel.hmShort(ad.r.longestBoutMin * 60L) : "") + " \u00b7 load " + Math.round(ad.r.load)
                            : "No logged data yet today";
                } catch (Throwable ignored) { }
                try {                                                    // 0.20.0: strap line of the sync strip, from the last condition report (every 10 minutes)
                    String cond = StrapEvents.lastDec(app, 29), pages = "", bat = "";
                    if (cond != null) {
                        java.util.regex.Matcher mp = java.util.regex.Pattern.compile("backlog (\\d+) pages").matcher(cond), mb = java.util.regex.Pattern.compile("battery ([0-9.]+) %").matcher(cond);
                        if (mp.find()) pages = mp.group(1) + " pages behind";
                        if (mb.find()) bat = "battery " + Math.round(Double.parseDouble(mb.group(1))) + " %";
                    }
                    syncRange = StrapData.rangeLine(app);
                    String bl = StrapData.batteryLine(app);
                    syncBattery = bl == null ? "" : bl;
                    syncSub = cond == null ? "" : "Strap: " + pages + (pages.isEmpty() || bat.isEmpty() ? "" : "  \u00b7  ") + bat;
                } catch (Throwable ignored) { }
                final HealthModel.Data dd = d;
                ui.post(new Runnable() { @Override public void run() { loading = false; loadedOnce = true; if (!isFinishing()) { data = dd; render(); } } });
            }
        }, "health-load").start();
    }

    // ------------------------------------------------------------------ small builders
    private int dpi(float v) { return Math.round(dp(this, v)); }

    private TextView tv(String s, float sp, int color, int weight) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(font(this, weight));
        t.setIncludeFontPadding(false);
        return t;
    }

    private TextView caps(String s) {
        TextView t = tv(s, 11, MUT, 400);
        t.setLetterSpacing(0.16f);
        return t;
    }

    private LinearLayout.LayoutParams lp(int w, int h, float topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = dpi(topDp);
        return p;
    }

    private LinearLayout card(float padDp) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(glass(this, 22));
        c.setPadding(dpi(padDp), dpi(padDp), dpi(padDp), dpi(padDp));
        return c;
    }

    private LinearLayout hrow() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private View spacer() { View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f)); return v; }

    private TextView chip(String s, int dot) {
        TextView t = tv(s, 12, INK, 400);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dpi(13), dpi(8), dpi(13), dpi(8));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(0x0DFFFFFF); g.setCornerRadius(dp(this, 17)); g.setStroke(dpi(1), 0x0FFFFFFF);
        t.setBackground(g);
        if (dot != 0) {
            t.setText("\u25CF  " + s);
        }
        return t;
    }

    private LinearLayout screen() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dpi(18), dpi(40), dpi(18), dpi(120));
        return col;
    }

    private View wrap(LinearLayout col) {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(col, new ViewGroup.LayoutParams(-1, -2));
        return sv;
    }

    private View head(String greet, String title, View right) {
        LinearLayout r = hrow();
        r.setGravity(Gravity.TOP);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.addView(tv(greet, 13, MUT, 400));
        TextView t = tv(title, 28, INK, 400);
        t.setPadding(0, dpi(6), 0, 0);
        l.addView(t);
        r.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        r.addView(right != null ? right : linkChip());   // 0.13.3: the strap link state is on every tab
        return r;
    }

    private View rowCard(String title, String sub, View.OnClickListener click) {
        LinearLayout c = card(15);
        c.addView(tv(title, 13.5f, INK, 400));
        TextView s = tv(sub, 12.5f, MUT, 400);
        s.setPadding(0, dpi(3), 0, 0);
        c.addView(s);
        if (click != null) c.setOnClickListener(click);
        return c;
    }

    private View pillButton(String text, int color, int fg, View.OnClickListener l) {
        TextView t = tv(text, 14, fg, 600);
        t.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(color); g.setCornerRadius(dp(this, 21));
        t.setBackground(g);
        t.setOnClickListener(l);
        return t;
    }

    // ------------------------------------------------------------------ import a pull zip
    private String importNote = "A reinstall empties the app. Import the zip you exported (history, ECG reports, strips) to bring those nights back.";
    private static final int REQ_ZIP = 77;

    private void pickZip() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"application/zip", "application/x-zip-compressed", "application/octet-stream"});
        startActivityForResult(i, REQ_ZIP);
    }

    @Override protected void onActivityResult(int req, int res, final Intent data2) {
        super.onActivityResult(req, res, data2);
        if (req != REQ_ZIP || res != RESULT_OK || data2 == null || data2.getData() == null) return;
        final android.net.Uri uri = data2.getData();
        final Context app = getApplicationContext();
        importNote = "Importing\u2026";
        render();
        new Thread(new Runnable() {
            @Override public void run() {
                final String msg = importZip(app, uri);
                ui.post(new Runnable() { @Override public void run() { importNote = msg; render(); } });
                long[] waits = {3000L, 7000L, 12000L, 20000L, 30000L};          // the history is read in the background
                for (long w : waits) {
                    try { Thread.sleep(w); } catch (InterruptedException ignored) { }
                    ui.post(new Runnable() { @Override public void run() { load(); } });
                }
            }
        }, "health-import").start();
    }

    // ------------------------------------------------------------------ export summary (small CSV files, the raw files stay on the phone)
    private volatile boolean exporting;

    private void exportSummary(final int days) {
        if (exporting) return;
        exporting = true;
        importNote = "Writing the summary files\u2026 (about a minute)";
        render();
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                SummaryExport.Result r;
                try {
                    r = SummaryExport.run(app, days);
                } catch (Throwable t) {
                    r = new SummaryExport.Result();
                    r.note = "Export stopped: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
                }
                final SummaryExport.Result res = r;
                ui.post(new Runnable() {
                    @Override public void run() {
                        exporting = false;
                        importNote = res.note;
                        render();
                        if (!res.uris.isEmpty()) {
                            try {
                                Intent i = new Intent(Intent.ACTION_SEND_MULTIPLE);
                                i.setType("text/csv");
                                i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, res.uris);
                                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                startActivity(Intent.createChooser(i, "Send the summary files"));
                            } catch (Throwable ignored) { }
                        }
                    }
                });
            }
        }, "summary-export").start();
    }

    /** Copy the history files, ECG reports and strips out of a pull zip into the app's folder, then let the day logger read them. */
    private static String importZip(Context app, android.net.Uri uri) {
        java.io.File dir = app.getExternalFilesDir(null);
        if (dir == null) return "No storage folder available.";
        int bins = 0, reports = 0, other = 0, skipped = 0;
        try {
            java.io.InputStream raw = app.getContentResolver().openInputStream(uri);
            if (raw == null) return "Could not open that file.";
            java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(new java.io.BufferedInputStream(raw));
            try {
                java.util.zip.ZipEntry e;
                byte[] buf = new byte[64 * 1024];
                while ((e = zin.getNextEntry()) != null) {
                    if (e.isDirectory()) continue;
                    String n = e.getName();
                    n = n.substring(n.lastIndexOf('/') + 1);
                    boolean bin = n.startsWith("historical_bin_") && n.endsWith(".bin");
                    boolean rep = n.startsWith("ecg_report_") && n.endsWith(".html");
                    boolean oth = (n.startsWith("ecg_strip_") && n.endsWith(".png")) || (n.startsWith("labrador_analysis_") && n.endsWith(".txt")) || (n.startsWith("realtime_ecg_bin_") && n.endsWith(".bin"))
                            || (n.startsWith("labrador_bin_") && n.endsWith(".bin")) || (n.startsWith("reconstructed_waveform_") && n.endsWith(".csv"));
                    if (!bin && !rep && !oth) continue;
                    java.io.File out = new java.io.File(dir, n);
                    if (out.exists() && out.length() > 0) { skipped++; continue; }
                    java.io.FileOutputStream fo = new java.io.FileOutputStream(out);
                    try {
                        int k;
                        while ((k = zin.read(buf)) > 0) fo.write(buf, 0, k);
                    } finally {
                        fo.close();
                    }
                    // the day logger skips files touched in the last 90 s ("still being written"), so date these as old
                    //noinspection ResultOfMethodCallIgnored
                    out.setLastModified(System.currentTimeMillis() - 5L * 60L * 1000L);
                    if (bin) bins++; else if (rep) reports++; else other++;
                }
            } finally {
                zin.close();
            }
        } catch (Throwable t) {
            return "Import stopped: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
        }
        DayLog.scanNewFiles(app);
        return "Imported " + bins + " history files, " + reports + " ECG reports and " + other + " other files" + (skipped > 0 ? " (" + skipped + " were already here)" : "") + ". Reading the history now; the screens fill in over the next minute.";
    }

    /** Bring the already-running main screen forward (never a second copy: it owns the strap link). */
    private Intent oldScreens() { Intent i = new Intent(this, MainActivity.class); i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT); i.putExtra("stay", true); return i; }

    private void startEcg() { NewLookActivity.pendingEcg = true; Intent i = oldScreens(); i.putExtra("start_ecg", true); startActivity(i); }

    private void openReport(java.io.File f) { startActivity(new Intent(this, ReportActivity.class).putExtra("path", f.getAbsolutePath())); }        // 0.19.2: its own process, so the logger process never loads WebView

    @Override public void onBackPressed() { moveTaskToBack(true); }

    private void go(int t) { tab = t; nav.setActive(t); render(); }

    // ------------------------------------------------------------------ render
    private void render() {
        content.removeAllViews();
        View v;
        switch (tab) {
            case 1: v = heartTab(); break;
            case 2: v = ecgTab(); break;
            case 3: v = restTab(); break;
            case 4: v = youTab(); break;
            default: v = todayTab();
        }
        content.addView(v, new FrameLayout.LayoutParams(-1, -1));
    }

    private static String ago(long unix) {
        long s = System.currentTimeMillis() / 1000L - unix;
        if (s < 90) return s + " s ago";
        if (s < 5400) return (s / 60) + " min ago";
        if (s < 172800) return String.format(Locale.UK, "%.0f h ago", s / 3600.0);
        return (s / 86400) + " days ago";
    }

    private static String hhmm(long unix) { return new SimpleDateFormat("HH:mm", Locale.UK).format(new Date(unix * 1000L)); }

    private int screenW() { return getResources().getDisplayMetrics().widthPixels - dpi(36); }

    // ------------------------------------------------------------------ TODAY
    // ---- 0.13.3: the strap link, always visible
    private TextView linkChipRef;

    private View linkChip() {
        LinkState.Info li = MainActivity.linkState(this);
        TextView t = chip(li.chip, 1);
        t.setTextColor(li.level == LinkState.GOOD ? GREEN : li.level == LinkState.WARN ? GOLD : ROSE);
        t.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { showLinkDialog(); } });
        linkChipRef = t;
        return t;
    }

    private void applyLink() {
        TextView t = linkChipRef;
        if (t == null) return;
        LinkState.Info li = MainActivity.linkState(this);
        t.setText("\u25CF  " + li.chip);
        t.setTextColor(li.level == LinkState.GOOD ? GREEN : li.level == LinkState.WARN ? GOLD : ROSE);
    }

    private void showLinkDialog() {
        final LinkState.Info li = MainActivity.linkState(this);
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        b.setTitle(li.title).setMessage(li.detail);
        if (li.action == LinkState.ACT_TURN_ON || li.action == LinkState.ACT_START)
            b.setPositiveButton(li.action == LinkState.ACT_TURN_ON ? "Turn on the day logger" : "Start the link", new android.content.DialogInterface.OnClickListener() {
                @Override public void onClick(android.content.DialogInterface d, int w) { turnOnLogger(); }
            });
        else if (li.action == LinkState.ACT_RECONNECT)
            b.setPositiveButton("Reconnect now", new android.content.DialogInterface.OnClickListener() {
                @Override public void onClick(android.content.DialogInterface d, int w) { MainActivity.requestReconnect(HealthActivity.this); }
            });
        b.setNegativeButton("Close", null);
        b.show();
    }

    /** The two ways the all-day log silently stops: the day logger is switched off, or the strap has gone quiet. Null when all is well. */
    private View loggerBanner(long ageSec) {
        if (!DayLog.enabled(this)) {
            LinearLayout c = card(16);
            c.addView(caps("DAY LOGGER IS OFF"));
            TextView t = tv("Nothing is being recorded, so recovery, sleep, trends and the morning summary stop updating. Switch it on and the app connects to the strap in the background.", 13, INK, 400);
            t.setLineSpacing(0, 1.25f);
            t.setPadding(0, dpi(8), 0, dpi(10));
            c.addView(t);
            c.addView(pillButton("Turn on the day logger", BLUE, 0xFF05131E, new View.OnClickListener() { @Override public void onClick(View v) { turnOnLogger(); } }), lp(-1, dpi(42), 0));
            return c;
        }
        LinkState.Info li = MainActivity.linkState(this);
        if (LinkState.staleBanner(li.level, ageSec)) {
            LinearLayout c = card(16);
            c.addView(caps(li.level == LinkState.GOOD ? "NO HISTORY PULLED FOR A WHILE" : "NO NEW DATA FROM THE STRAP"));
            TextView t = tv(li.level == LinkState.GOOD
                    ? "The strap is connected, but the newest stored second is " + StrapData.ageText(ageSec) + " old. Use \"Pull now\" in the day-logger notification."
                    : "The newest second is " + StrapData.ageText(ageSec) + " old and the strap is not connected (" + li.chip.toLowerCase(java.util.Locale.UK) + "). Check that the strap is on your wrist and not on the charger, and that it has battery. The app keeps trying on its own. Tap the chip at the top for details.", 13, INK, 400);
            t.setLineSpacing(0, 1.25f);
            t.setPadding(0, dpi(8), 0, 0);
            c.addView(t);
            return c;
        }
        return null;
    }

    private void turnOnLogger() {
        DayLog.setEnabled(this, true);
        DayLogService.start(this);
        MainActivity.startLinkHost(this);      // 0.15.0: invisible, no screen change
        android.widget.Toast.makeText(this, "Day logger on. It connects to the strap in the background.", android.widget.Toast.LENGTH_LONG).show();
        render();
    }

    // ------------------------------------------------------------------ 0.20.0: sync strip and the opened rings on Today
    // ------------------------------------------------------------------ 0.21.0: explainers on every card, graph and ring
    interface Filler { void fill(LinearLayout panel); }

    /** Adds a "More about this" strip to a card; tapping opens a panel built on demand. The open state survives a refresh. */
    private void expandable(final String key, final LinearLayout card, final Filler f) {
        final LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setVisibility(View.GONE);
        final TextView bar = tv("\u25BE  More about this", 12, BLUE, 600);
        bar.setPadding(0, dpi(12), 0, dpi(2));
        card.addView(bar);
        card.addView(panel, lp(-1, -2, 4));
        final Runnable open = new Runnable() { @Override public void run() {
            panel.removeAllViews();
            View sep = new View(HealthActivity.this);
            sep.setBackgroundColor(0x14FFFFFF);
            panel.addView(sep, new LinearLayout.LayoutParams(-1, 1));
            try { f.fill(panel); } catch (Throwable t) { panel.addView(tv("Could not build this panel.", 12, MUT, 400)); }
            panel.setVisibility(View.VISIBLE);
            bar.setText("\u25B4  Hide");
        } };
        bar.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            if (openCards.contains(key)) { openCards.remove(key); panel.removeAllViews(); panel.setVisibility(View.GONE); bar.setText("\u25BE  More about this"); }
            else { openCards.add(key); open.run(); }
        } });
        if (openCards.contains(key)) open.run();
    }

    /** Heading and paragraph rows from Explain. */
    private void rows(LinearLayout p, String[][] r) {
        for (String[] x : r) {
            p.addView(caps(x[0]), lp(-2, -2, 12));
            TextView t = tv(x[1], 12.5f, INK, 400);
            t.setLineSpacing(0, 1.25f);
            p.addView(t, lp(-2, -2, 3));
        }
    }

    private void yours(LinearLayout p) { p.addView(caps("YOUR NUMBERS"), lp(-2, -2, 12)); }

    private static final String[] ZONE_NAME = {"Resting", "Easy", "Steady", "Hard", "All out"};

    private void fillHrNow(LinearLayout p) {
        HealthModel.Data d = data;
        if (d == null || d.day == null) return;
        TodayModel.Day day = d.day;
        int mh = maxHr(), hr = shownHr(d);
        yours(p);
        kv(p, "Now", hr > 0 ? hr + " bpm  (" + ZONE_NAME[HealthModel.zoneOfHr(hr, mh)] + ")" : "\u2014");
        if (d.hr24Max > 0) kv(p, "Last 24 hours", d.hr24Min + " low, " + Math.round(d.hr24Mean) + " average, " + d.hr24Max + " high");
        if (!Double.isNaN(day.restingHr)) kv(p, "Lowest five minutes", Math.round(day.restingHr) + " bpm");
        int[] ze = HealthModel.zoneEdges(mh);
        kv(p, "Zone edges (maximum " + mh + ")", ze[0] + " / " + ze[1] + " / " + ze[2] + " / " + ze[3] + " bpm");
        rows(p, Explain.heartNow(mh));
    }

    private void fillSyncPanel(LinearLayout p) {
        yours(p);
        kv(p, "Last pull", syncTitleText(data == null ? null : data.day));
        if (!syncSub.isEmpty()) kv(p, "Strap", syncSub.replace("Strap: ", ""));
        if (!syncBattery.isEmpty()) kv(p, "Battery", syncBattery);
        if (!syncRange.isEmpty()) kv(p, "Out of range (24 h)", syncRange);
        kv(p, "Logger", StrapData.loggerLine(this));
        rows(p, Explain.sync());
    }

    private void fillActivityPanel(LinearLayout p) {
        HealthModel.Data d = data;
        yours(p);
        if (d != null && d.day != null) {
            kv(p, "Steps today", String.valueOf(d.day.steps));
            kv(p, "Walking / running (strap's class)", d.day.walkMin + " / " + d.day.runMin + " min");
        }
        int shown = 0;
        for (ActivityDetector.Bout b : actBouts) {
            LinearLayout r = hrow();
            r.addView(tv(hhmm(b.start) + "  " + b.durationMin + " min" + (b.label == null || b.label.isEmpty() ? "" : "  \u00b7  " + b.label), 12.5f, INK, 400), new LinearLayout.LayoutParams(0, -2, 1f));
            r.addView(tv("avg " + Math.round(b.avgHr) + " \u00b7 peak " + Math.round(b.peakHr), 12, MUT, 400));
            p.addView(r, lp(-1, -2, 8));
            if (++shown >= 12) break;
        }
        if (shown == 0) note(p, "No bout found yet today.");
        rows(p, Explain.activity());
    }

    private void fillJournalPanel(LinearLayout p) {
        yours(p);
        int n = 0;
        try {
            long from = TodayModel.dayStartOf(System.currentTimeMillis() / 1000L) * 1000L;
            android.database.Cursor q = DayLog.db(this).rawQuery("SELECT ms, text FROM events WHERE kind = 'marker' AND ms >= ? ORDER BY ms DESC LIMIT 20", new String[] {String.valueOf(from)});
            while (q.moveToNext()) { kv(p, new SimpleDateFormat("HH:mm", Locale.UK).format(new Date(q.getLong(0))), String.valueOf(q.getString(1))); n++; }
            q.close();
        } catch (Throwable ignored) { }
        if (n == 0) note(p, "No notes logged since 04:00.");
        rows(p, Explain.journal());
    }

    private void fillEcgPanel(LinearLayout p) {
        HealthModel.Data d = data;
        yours(p);
        int k = 0;
        if (d != null) for (HealthModel.Reading r : d.readings) {
            kv(p, r.when, ("-".equals(r.hr) ? "" : r.hr + " bpm") + ("-".equals(r.hrv) ? "" : "  \u00b7  HRV " + r.hrv + " ms") + ("-".equals(r.rhythm) ? "" : "  \u00b7  " + r.rhythm) + ("-".equals(r.grade) ? "" : "  \u00b7  grade " + r.grade));
            if (++k >= 5) break;
        }
        if (k == 0) note(p, "No reading saved yet.");
        rows(p, Explain.ecgReadings());
    }

    private void fillH24Panel(LinearLayout p) {
        HealthModel.Data d = data;
        if (d == null || d.day == null) return;
        yours(p);
        if (d.hr24Max > 0) { kv(p, "Lowest", d.hr24Min + " bpm"); kv(p, "Average", Math.round(d.hr24Mean) + " bpm"); kv(p, "Highest", d.hr24Max + " bpm"); }
        if (!Double.isNaN(d.day.restingHr)) kv(p, "Lowest five minutes", Math.round(d.day.restingHr) + " bpm");
        for (int i = 0; i < 5; i++) if (d.zoneSec[i] > 0) kv(p, ZONE_NAME[i], HealthModel.hm(d.zoneSec[i] / 3600.0));
        rows(p, Explain.hrChart());
    }

    private void fillZonesPanel(LinearLayout p) {
        int mh = maxHr();
        int[] ze = HealthModel.zoneEdges(mh);
        yours(p);
        kv(p, "Maximum heart rate", mh + " bpm");
        kv(p, "Resting", "under " + ze[0] + " bpm");
        kv(p, "Easy", ze[0] + " to " + ze[1] + " bpm");
        kv(p, "Steady", ze[1] + " to " + ze[2] + " bpm");
        kv(p, "Hard", ze[2] + " to " + ze[3] + " bpm");
        kv(p, "All out", ze[3] + " bpm and over");
        rows(p, Explain.zones());
    }

    private void fillB2BPanel(LinearLayout p) {
        HealthModel.Data d = data;
        if (d == null) return;
        yours(p);
        for (HealthModel.Reading r : d.readings) if (!"-".equals(r.hrv)) { kv(p, "Latest ECG HRV", r.hrv + " ms  (" + r.when + ")"); break; }
        double lastOpt = Double.NaN;
        for (TodayModel.NightStat ns : d.nights) if (!Double.isNaN(ns.rmssd)) lastOpt = ns.rmssd;
        if (!Double.isNaN(lastOpt)) kv(p, "Last night, optical", Math.round(lastOpt) + " ms");
        rows(p, Explain.beatToBeat());
    }

    private void fillOverTimePanel(LinearLayout p) {
        HealthModel.Data d = data;
        if (d == null) return;
        yours(p);
        int shown = 0;
        for (int i = d.nights.size() - 1; i >= 0 && shown < 7; i--) {
            TodayModel.NightStat ns = d.nights.get(i);
            if (Double.isNaN(ns.hrLow5)) continue;
            kv(p, new SimpleDateFormat("EEE d MMM", Locale.UK).format(new Date(ns.bed * 1000L)), Math.round(ns.hrLow5) + " bpm" + (Double.isNaN(ns.rmssd) ? "" : "  \u00b7  HRV " + Math.round(ns.rmssd) + " ms"));
            shown++;
        }
        if (shown == 0) note(p, "No night with a resting heart rate yet.");
        rows(p, Explain.heartOverTime());
    }

    private void fillSleepWeekPanel(LinearLayout p) {
        HealthModel.Data d = data;
        if (d == null) return;
        double goal = goal();
        yours(p);
        for (int i = d.lastSeven.length - 1; i >= 0; i--) {
            if (Double.isNaN(d.lastSeven[i])) continue;
            double diff = d.lastSeven[i] - goal;
            kv(p, new SimpleDateFormat("EEE d MMM", Locale.UK).format(new Date(d.lastSevenBed[i] * 1000L)), HealthModel.hm(d.lastSeven[i]) + "  (" + (diff < 0 ? HealthModel.hm(-diff) + " under" : HealthModel.hm(diff) + " over") + ")");
        }
        rows(p, Explain.sleepWeek());
    }

    private void fillShapePanel(LinearLayout p) {
        HealthModel.Data d = data;
        if (d == null) return;
        long tot = 0;
        for (long s : d.stageSecs) tot += s;
        yours(p);
        if (tot > 0) {
            String[] nm = {"Awake", "REM", "Light", "Deep"};
            int[] idx = {HealthModel.WAKE, HealthModel.REM, HealthModel.LIGHT, HealthModel.DEEP};
            for (int i = 0; i < 4; i++) kv(p, nm[i], HealthModel.hmShort(d.stageSecs[idx[i]]) + "  (" + Math.round(100.0 * d.stageSecs[idx[i]] / tot) + " %)");
        }
        rows(p, Explain.sleepShape());
    }

    private void fillWholeNightPanel(LinearLayout p) {
        HealthModel.Data d = data;
        if (d == null) return;
        int sure = 0, all = 0;
        for (int i = 0; i < d.binStage.length; i++) if (d.binStage[i] >= 0) { all++; if (d.binSure[i]) sure++; }
        yours(p);
        kv(p, "Five-minute blocks", String.valueOf(all));
        kv(p, "Where two methods agree", all == 0 ? "\u2014" : Math.round(100.0 * sure / all) + " %");
        rows(p, Explain.sleepNight());
    }

    private void fillSpo2Panel(LinearLayout p) {
        HealthModel.Data d = data;
        if (d == null || d.day == null || d.day.spo2 == null) return;
        yours(p);
        kv(p, "Night value", d.day.spo2.median + " %");
        kv(p, "Windows used", String.valueOf(d.day.spo2.validWindows));
        kv(p, "Lowest / highest window", d.day.spo2.min + " / " + d.day.spo2.max + " %");
        kv(p, "Windows below 94 %", String.valueOf(d.day.spo2.below94));
        rows(p, Explain.spo2());
    }

    private void fillMonitorPanel(LinearLayout p, DailyViews.Metric mt) {
        int st = mt.status();
        kv(p, "Last night", mt.valueText());
        kv(p, "Your usual", mt.rangeText());
        kv(p, "Against your usual", st < 0 ? "not enough nights yet" : st == 0 ? "inside the usual range" : st == 1 ? "a little outside (worth noting)" : "well outside");
        rows(p, Explain.monitor(mt.name));
    }

    private LinearLayout explainerCard(String title, String sub) {
        LinearLayout c = card(14);
        c.addView(caps(title));
        c.addView(tv(sub, 11.5f, MUT, 400), lp(-2, -2, 2));
        return c;
    }

    private String syncTitleText(TodayModel.Day day) {
        String ps = MainActivity.pullStatus();
        if (ps != null) return "Syncing\u2026 " + ps;
        long lp = DayLog.lastPullMs(this);
        if (lp > 0) {
            long s = Math.max(0L, (System.currentTimeMillis() - lp) / 1000L);
            return "Synced " + (s < 90 ? s + " s" : s < 5400 ? (s / 60) + " min" : String.format(Locale.UK, "%.1f h", s / 3600.0)) + " ago";
        }
        return day != null && day.newestTs > 0 ? "Latest record " + ago(day.newestTs) : "Not synced yet";
    }

    private View syncStrip(final TodayModel.Day day) {
        LinearLayout c = card(12);
        LinearLayout r = hrow();
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        syncTitleTv = tv(syncTitleText(day), 14, INK, 600);
        syncSubTv = tv(syncSub.isEmpty() ? (MainActivity.linkRunning() ? "Strap link up" : "Strap link not up: Sync now opens it") : syncSub, 11, MUT, 400);
        left.addView(syncTitleTv);
        left.addView(syncSubTv, lp(-2, -2, 2));
        r.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView btn = HeartGraph.pill(this, "Sync now", 0x1A2EE6A8, 0xFF2EE6A8);
        btn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            if (!MainActivity.linkRunning()) MainActivity.startLinkHost(HealthActivity.this);
            MainActivity.requestPull();
            android.widget.Toast.makeText(HealthActivity.this, "Syncing the strap", android.widget.Toast.LENGTH_SHORT).show();
            if (syncTitleTv != null) syncTitleTv.setText("Syncing\u2026");
        } });
        r.addView(btn);
        c.addView(r);
        expandable("t_sync", c, new Filler() { @Override public void fill(LinearLayout p) { fillSyncPanel(p); } });
        return c;
    }

    private void updateSyncStrip() {
        try {
            if (syncTitleTv == null || syncSubTv == null) return;
            syncTitleTv.setText(syncTitleText(data == null ? null : data.day));
            if (!syncSub.isEmpty()) syncSubTv.setText(syncSub);
        } catch (Throwable ignored) { }
    }

    private void toggleRing(int i) {
        if (ringPanel == null) return;
        if (ringOpen == i) { ringOpen = -1; ringPanel.removeAllViews(); ringPanel.setVisibility(View.GONE); return; }
        showRing(i);
    }

    private void showRing(int i) {
        if (ringPanel == null) return;
        HealthModel.Data d = data;
        if (d == null || d.day == null) return;
        ringOpen = i;
        ringPanel.removeAllViews();
        LinearLayout c = card(16);
        if (i == 0) fillRecovery(c, d.day); else if (i == 1) fillEffort(c, d.day); else fillSleep(c, d.day);
        ringPanel.addView(c, lp(-1, -2, 0));
        ringPanel.setVisibility(View.VISIBLE);
    }

    private void kv(LinearLayout c, String k, String v) {
        LinearLayout r = hrow();
        r.addView(tv(k, 13, INK, 400), new LinearLayout.LayoutParams(0, -2, 1f));
        r.addView(tv(v, 13, INK, 600));
        c.addView(r, lp(-1, -2, 8));
    }

    private void note(LinearLayout c, String s) {
        TextView t = tv(s, 11, MUT, 400);
        t.setLineSpacing(0, 1.2f);
        c.addView(t, lp(-2, -2, 8));
    }

    /** "Lower than your usual by 0.8 spreads" for a z-score where positive means better. Pure text. */
    static String zText(String what, double z, String betterWord, String worseWord) {
        if (Double.isNaN(z)) return "";
        if (Math.abs(z) < 0.3) return what + ": right at your usual.";
        return what + ": " + String.format(Locale.UK, "%.1f", Math.abs(z)) + " spreads " + (z > 0 ? betterWord : worseWord) + " (" + (z > 0 ? "good" : "worth watching") + ").";
    }

    private void fillRecovery(LinearLayout c, TodayModel.Day day) {
        SleepNight.Night n = day.night;
        c.addView(caps("RECOVERY: HOW IT IS WORKED OUT"));
        if (Double.isNaN(day.recovery)) {
            note(c, "Not shown yet. It needs " + TodayModel.MIN_BASELINE + " earlier nights that each have a resting heart rate and an HRV; this strap has " + day.baselineNights + ".");
        } else {
            kv(c, "Estimate", Math.round(day.recovery) + " %");
            kv(c, "Compared with", "your last " + day.baselineNights + " nights");
            if (n != null) {
                kv(c, "Resting heart rate (lowest 5 min of the night)", Math.round(n.hrLow5) + " bpm");
                note(c, zText("Against your usual", day.zRhr, "lower", "higher"));
                kv(c, "Overnight HRV (RMSSD, optical)", Math.round(n.rmssd) + " ms");
                note(c, zText("Against your usual", day.zHrv, "higher", "lower"));
            }
            note(c, "Score = 100 x the normal curve of the average of the two z-scores. One spread is your own usual night-to-night change. A lower resting rate and a higher HRV both raise it.");
            rows(c, Explain.recoveryBands());
        }
        if (n != null) {
            kv(c, "Slept", HealthModel.hm(n.tstH));
            if (!Double.isNaN(n.brMedian)) kv(c, "Breathing", String.format(Locale.UK, "%.1f a minute", n.brMedian));
            if (!Double.isNaN(n.skinMedian)) kv(c, "Skin temperature (night median)", String.format(Locale.UK, "%.1f \u00b0C", n.skinMedian));
            kv(c, "Not in the score", "sleep, breathing, skin, SpO2");
        }
        note(c, "An estimate from this strap's own records. Optical HRV reads higher than an ECG strap's; your own nights absorb that. Not WHOOP's recovery and not a medical measure.");
    }

    private void fillEffort(LinearLayout c, TodayModel.Day day) {
        c.addView(caps("EFFORT: HOW IT IS WORKED OUT"));
        kv(c, "Effort", String.format(Locale.UK, "%.1f of 21", day.effort));
        kv(c, "Counted from", hhmm(day.dayStart) + " (wake-up or 04:00)");
        int[] lo = {0, 50, 60, 70, 80, 90};
        String[] nm = {"Zone 0", "Zone 1", "Zone 2", "Zone 3", "Zone 4", "Zone 5"};
        for (int z = 0; z < 6; z++) {
            int a = Math.round(lo[z] * day.maxHr / 100f), b = z == 5 ? day.maxHr : Math.round(lo[z + 1] * day.maxHr / 100f) - 1;
            kv(c, nm[z] + "  " + (z == 0 ? "under " + (Math.round(50 * day.maxHr / 100f)) : a + " to " + b) + " bpm  (x" + z + ")", day.zoneMin[z] + " min");
        }
        kv(c, "Load (minutes x zone weight)", String.format(Locale.UK, "%.0f", day.trimp));
        note(c, "Effort = 21 x (1 - e^(-load / 150)). A minute counts when the strap logged at least 20 heart-rate seconds in it; its median sets the zone from your maximum heart rate of " + day.maxHr + " bpm.");
        kv(c, "Steps", String.valueOf(day.steps));
        kv(c, "Walking / running (strap's class)", day.walkMin + " / " + day.runMin + " min");
        rows(c, Explain.effortScale());
    }

    private void fillSleep(LinearLayout c, TodayModel.Day day) {
        SleepNight.Night n = day.night;
        c.addView(caps("SLEEP: LAST NIGHT"));
        if (n == null) { note(c, "No complete night found yet."); return; }
        kv(c, "In bed", hhmm(n.bedTs) + " to " + hhmm(n.endTs) + "  (" + HealthModel.hm(n.tibH) + ")");
        kv(c, "Asleep", HealthModel.hm(n.tstH) + (Double.isNaN(day.sleepPct) ? "" : "  (" + Math.round(day.sleepPct) + " % of your " + Math.round(day.sleepGoalH) + " h goal)"));
        kv(c, "Efficiency", Math.round(n.effPct) + " %");
        kv(c, "Time to settle", Math.round(n.settleMin) + " min");
        kv(c, "In bed but still / awake / up", Math.round(n.minStill) + " / " + Math.round(n.minWake) + " / " + Math.round(n.minUp) + " min");
        kv(c, "Restless spells", String.valueOf(n.restless.size()));
        if (!Double.isNaN(n.hrLow5)) kv(c, "Lowest 5-minute heart rate", Math.round(n.hrLow5) + " bpm" + (n.hrLow5Ts > 0 ? " at " + hhmm(n.hrLow5Ts) : ""));
        if (!Double.isNaN(n.hrMean)) kv(c, "Average heart rate", Math.round(n.hrMean) + " bpm");
        if (!Double.isNaN(n.rmssd)) kv(c, "HRV (RMSSD, optical)", Math.round(n.rmssd) + " ms");
        if (!Double.isNaN(n.brMedian)) kv(c, "Breathing", String.format(Locale.UK, "%.1f a minute", n.brMedian));
        if (!Double.isNaN(n.skinMedian)) kv(c, "Skin temperature", String.format(Locale.UK, "%.1f \u00b0C (%.1f to %.1f)", n.skinMedian, n.skinMin, n.skinMax));
        if (day.spo2 != null && day.spo2.validWindows > 0) kv(c, "SpO2 (sleep-only windows)", day.spo2.median + " %  (" + day.spo2.validWindows + " windows)");
        kv(c, "Records present", Math.round(n.coveragePct) + " %" + (n.partialStart || n.partialEnd ? "  (partial night)" : ""));
        note(c, "From the strap's own sleep state (awake, still, sleep, up): an envelope, not a stage split. The Rest tab shows the estimated stages.");
        rows(c, Explain.sleepRing(day.sleepGoalH));
    }

    private View todayTab() {
        LinearLayout col = screen();
        HealthModel.Data d = data;
        if (!loadedOnce) {
            col.addView(head("Hi, " + name(), "Today", null));
            TextView lt = tv("Reading your records\u2026", 14, MUT, 400);
            lt.setPadding(0, dpi(18), 0, 0);
            col.addView(lt);
            return wrap(col);
        }
        if (d == null || d.day == null || d.day.newestTs == 0) {
            col.addView(head("Hi, " + name(), "Today", null));
            LinearLayout c = card(18);
            c.addView(caps("NO RECORDS YET"));
            TextView t = tv("Turn on the day logger and pull the strap's history. This screen fills in from the strap's own per-second records.", 14, MUT, 400);
            t.setPadding(0, dpi(8), 0, 0);
            c.addView(t);
            col.addView(c, lp(-1, -2, 18));
            col.addView(pillButton("Open the day logger", BLUE, 0xFF05131E, new View.OnClickListener() { @Override public void onClick(View v) { startActivity(new Intent(HealthActivity.this, DayLogActivity.class)); } }), lp(-1, dpi(46), 14));
            return wrap(col);
        }
        TodayModel.Day day = d.day;
        SleepNight.Night n = day.night;
        long age = System.currentTimeMillis() / 1000L - day.newestTs;
        col.addView(head("Hi, " + name(), n != null ? "You slept " + HealthModel.hmShort((long) (n.tstH * 3600)) : "Good morning", null));
        View banner = loggerBanner(age);                       // 0.13.2: say so when the all-day log is not running
        if (banner != null) col.addView(banner, lp(-1, -2, 12));
        col.addView(syncStrip(day), lp(-1, -2, 10));              // 0.20.0: when it last synced, how far behind the strap is, and a Sync now button
        // ---- the three rings and the day's plan (0.12.0)
        double rec = day.recovery;
        float[] rf = {Double.isNaN(rec) ? 0f : (float) (rec / 100.0), (float) (day.effort / 21.0), Double.isNaN(day.sleepPct) ? 0f : (float) Math.min(1.0, day.sleepPct / 100.0)};
        int[] rc = {DailyViews.recoveryColor(rec), BLUE, VIOLET};
        String[] rb = {Double.isNaN(rec) ? "\u2014" : Math.round(rec) + "%", String.format(Locale.UK, "%.1f", day.effort), Double.isNaN(day.sleepPct) ? "\u2014" : Math.round(day.sleepPct) + "%"};
        String[] rl = {"RECOVERY", "EFFORT", "SLEEP"};
        String[] rcap = {Double.isNaN(rec) ? "needs " + TodayModel.MIN_BASELINE + " nights" : "vs your last " + day.baselineNights + " nights", "of 21 today",
                n == null ? "no night yet" : HealthModel.hmShort((long) (n.tstH * 3600)) + " of " + Math.round(day.sleepGoalH) + " h goal"};
        DailyViews.RingsView rings = new DailyViews.RingsView(this, rf, rc, rb, rl, rcap);
        final android.view.GestureDetector ringTap = new android.view.GestureDetector(this, new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapUp(android.view.MotionEvent e) { return true; }
        });
        rings.setClickable(true);
        rings.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, android.view.MotionEvent e) {                // 0.20.0: tap a ring to open how it is worked out
                if (ringTap.onTouchEvent(e) && e.getAction() == android.view.MotionEvent.ACTION_UP) toggleRing(Math.max(0, Math.min(2, (int) (e.getX() / (v.getWidth() / 3f)))));
                return false;
            }
        });
        col.addView(rings, lp(-1, dpi(196), 6));
        TextView ringHint = tv("Tap Recovery, Effort or Sleep to see how it is worked out.", 11, MUT, 400);
        ringHint.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(ringHint, lp(-1, -2, 0));
        ringPanel = new LinearLayout(this);
        ringPanel.setOrientation(LinearLayout.VERTICAL);
        ringPanel.setVisibility(View.GONE);
        col.addView(ringPanel, lp(-1, -2, 8));
        if (ringOpen >= 0) showRing(ringOpen);
        TextView plan = tv(DailyViews.plan(rec), 14, INK, 400);
        plan.setLineSpacing(0, 1.25f);
        col.addView(plan, lp(-2, -2, 8));
        col.addView(tv("Estimates from this strap's own records, built on your own recent nights. Not WHOOP's scores and not a medical measure.", 11, MUT, 400), lp(-2, -2, 4));

        // ---- workouts and activity found in today's records (0.20.0: moved up, under the rings)
        LinearLayout wkc = card(16);
        wkc.addView(caps("ACTIVITY TODAY"));
        wkc.addView(tv(actLine.isEmpty() ? "Steps, time on your feet and workouts found today" : actLine + (day.steps > 0 ? "  \u00b7  " + day.steps + " steps" : ""), 12, INK, 400), lp(-2, -2, 6));
        int shownBouts = 0;
        List<ActivityDetector.Bout> bs = actBouts;
        for (ActivityDetector.Bout b : bs) {
            if (b.durationMin < 10) continue;
            LinearLayout wr = hrow();
            wr.addView(tv(hhmm(b.start) + "  " + b.durationMin + " min" + (b.label == null || b.label.isEmpty() ? "" : "  \u00b7  " + b.label), 13, INK, 400), new LinearLayout.LayoutParams(0, -2, 1f));
            wr.addView(tv("avg " + Math.round(b.avgHr) + " \u00b7 peak " + Math.round(b.peakHr), 12, MUT, 400));
            wkc.addView(wr, lp(-1, -2, 10));
            if (++shownBouts >= 6) break;
        }
        if (shownBouts == 0) wkc.addView(tv("No stretch of ten minutes or more found yet today.", 12, MUT, 400), lp(-2, -2, 8));
        wkc.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { startActivity(new Intent(HealthActivity.this, ActivityActivity.class)); } });
        expandable("t_act", wkc, new Filler() { @Override public void fill(LinearLayout p) { fillActivityPanel(p); } });
        col.addView(wkc, lp(-1, -2, 12));

        // ---- heart rate now
        LinearLayout nowCard = card(14);
        LinearLayout nowRow = hrow();
        nowRow.addView(caps("HEART RATE NOW"));
        nowRow.addView(spacer());
        todayHrNum = tv(shownHr(d) > 0 ? shownHr(d) + " bpm" : "\u2014", 20, INK, 300);
        nowRow.addView(todayHrNum);
        nowCard.addView(nowRow);
        todayHrSub = tv(shownLabel(d).replace("bpm, ", "") + (n != null && !Double.isNaN(day.restingHr) ? "  \u00b7  lowest five minutes " + Math.round(day.restingHr) : ""), 11, MUT, 400);
        todayHrSubTail = (n != null && !Double.isNaN(day.restingHr) ? "  \u00b7  lowest five minutes " + Math.round(day.restingHr) : "");
        nowCard.addView(todayHrSub, lp(-2, -2, 4));
        nowCard.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { go(1); } });
        expandable("t_hr", nowCard, new Filler() { @Override public void fill(LinearLayout p) { fillHrNow(p); } });
        col.addView(nowCard, lp(-1, -2, 12));

        // ---- health monitor: last night against your own usual range
        List<DailyViews.Metric> mon = DailyViews.monitor(d);
        if (!mon.isEmpty()) {
            LinearLayout hmc = card(16);
            LinearLayout hmh = hrow();
            hmh.addView(caps("HEALTH MONITOR"));
            hmh.addView(spacer());
            hmh.addView(tv("last night against your usual", 12, MUT, 400));
            hmc.addView(hmh);
            for (DailyViews.Metric mt : mon) {
                LinearLayout row = hrow();
                row.addView(tv(mt.name, 13, INK, 400), new LinearLayout.LayoutParams(0, -2, 1f));
                row.addView(tv(mt.valueText(), 15, INK, 600));
                hmc.addView(row, lp(-1, -2, 12));
                hmc.addView(new DailyViews.BandView(this, mt), lp(-1, dpi(18), 2));
                hmc.addView(tv(mt.rangeText(), 11, MUT, 400));
                final LinearLayout mp = new LinearLayout(this);
                mp.setOrientation(LinearLayout.VERTICAL);
                mp.setVisibility(View.GONE);
                hmc.addView(mp, lp(-1, -2, 4));
                final String mkey = "mon_" + mt.name;
                final DailyViews.Metric mtf = mt;
                row.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                    if (openCards.contains(mkey)) { openCards.remove(mkey); mp.removeAllViews(); mp.setVisibility(View.GONE); }
                    else { openCards.add(mkey); mp.removeAllViews(); fillMonitorPanel(mp, mtf); mp.setVisibility(View.VISIBLE); }
                } });
                if (openCards.contains(mkey)) { fillMonitorPanel(mp, mtf); mp.setVisibility(View.VISIBLE); }
            }
            TextView hn = tv("Tap a line to see what it means. Green is inside your usual range, amber about two spreads out, red further. HRV here comes from the wrist's optical intervals. A guide, not a diagnosis.", 11, MUT, 400);
            hn.setLineSpacing(0, 1.2f);
            hmc.addView(hn, lp(-2, -2, 10));
            col.addView(hmc, lp(-1, -2, 12));
        }

        col.addView(rowCard("Trends", "Recovery, resting heart rate, HRV, sleep, breathing and skin temperature over 7, 30 or 90 nights",
                new View.OnClickListener() { @Override public void onClick(View v) { if (data != null) Trends.show(HealthActivity.this, data); } }), lp(-1, -2, 12));
        col.addView(rowCard("Healthspan (research)", "Age estimate and pace of aging from your nights: resting heart rate, sleep regularity, fitness. A research estimate, not WHOOP's number",
                new View.OnClickListener() { @Override public void onClick(View v) { if (data != null) Healthspan.show(HealthActivity.this, data); } }), lp(-1, -2, 12));
        col.addView(rowCard("Strap data", "Every value the strap sends while worn: heart, motion, temperatures, state, events",
                new View.OnClickListener() { @Override public void onClick(View v) { StrapData.show(HealthActivity.this); } }), lp(-1, -2, 10));

        // ---- effort today
        LinearLayout efc = card(16);
        LinearLayout efh = hrow();
        efh.addView(caps("EFFORT"));
        efh.addView(spacer());
        efh.addView(tv(String.format(Locale.UK, "%.1f of 21", day.effort), 13, INK, 600));
        efc.addView(efh);
        efc.addView(new ZoneBarView(this, d.zoneSec), lp(-1, dpi(10), 10));
        efc.addView(tv("Time in heart-rate zones over the last 24 hours, weighted into a 0 to 21 effort." + (day.steps > 0 ? " " + day.steps + " steps." : "") + " This is not WHOOP's strain.", 11, MUT, 400), lp(-2, -2, 8));
        expandable("t_effort", efc, new Filler() { @Override public void fill(LinearLayout p) { TodayModel.Day dd = data == null ? null : data.day; if (dd != null) fillEffort(p, dd); } });
        col.addView(efc, lp(-1, -2, 12));

        // ---- journal: one tap puts a time-stamped marker in the day log
        LinearLayout jc = card(16);
        jc.addView(caps("JOURNAL"));
        jc.addView(tv("Tap to log it now. The log lines it up with the heart and sleep numbers later.", 11, MUT, 400), lp(-2, -2, 4));
        String[][] marks = {{"Caffeine", "caffeine"}, {"Meal", "meal"}, {"Alcohol", "alcohol"}, {"Stress", "stress"}, {"Workout start", "workout_start"}, {"Workout end", "workout_end"}, {"Symptom", "symptom"}, {"Going to bed", "bed"}};
        LinearLayout jr = null;
        for (int mi = 0; mi < marks.length; mi++) {
            if (mi % 2 == 0) { jr = hrow(); jc.addView(jr, lp(-1, -2, 8)); }
            final String mName = marks[mi][0], mKind = marks[mi][1];
            TextView pill = HeartGraph.pill(this, mName, 0x1AFFFFFF, INK);
            pill.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { DayLog.addEvent(HealthActivity.this, "marker", mKind); android.widget.Toast.makeText(HealthActivity.this, mName + " logged at " + hhmm(System.currentTimeMillis() / 1000L), android.widget.Toast.LENGTH_SHORT).show(); } });
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(0, -2, 1f);
            pl.rightMargin = dpi(8);
            jr.addView(pill, pl);
        }
        expandable("t_journal", jc, new Filler() { @Override public void fill(LinearLayout p) { fillJournalPanel(p); } });
        col.addView(jc, lp(-1, -2, 12));

        String ecgTitle = "No ECG reading yet", ecgSub = "Start one from the ECG tab";
        if (!d.readings.isEmpty()) {
            HealthModel.Reading r = d.readings.get(0);
            for (HealthModel.Reading q : d.readings) if (!"-".equals(q.hr)) { r = q; break; }
            ecgTitle = "Last ECG \u00b7 " + r.when;
            ecgSub = ("-".equals(r.hr) ? "" : "HR " + r.hr + " bpm") + ("-".equals(r.rhythm) ? "" : (("-".equals(r.hr) ? "" : " \u00b7 ") + r.rhythm));
            if (ecgSub.isEmpty()) ecgSub = "Open to see why";
        }
        col.addView(rowCard(ecgTitle, ecgSub, new View.OnClickListener() { @Override public void onClick(View v) { go(2); } }), lp(-1, -2, 16));
        LinearLayout egc = explainerCard("ABOUT YOUR ECG READINGS", "What the grades mean and how to get a strong reading");
        expandable("t_ecg", egc, new Filler() { @Override public void fill(LinearLayout p) { fillEcgPanel(p); } });
        col.addView(egc, lp(-1, -2, 10));
        if (n != null) {
            String sub = (Double.isNaN(n.brMedian) ? "" : "Breathing " + String.format(Locale.UK, "%.1f", n.brMedian) + " a minute") + (Double.isNaN(n.skinMedian) ? "" : ", skin " + String.format(Locale.UK, "%.1f", n.skinMedian) + " \u00b0C");
            col.addView(rowCard("Last night \u00b7 " + HealthModel.hm(n.tstH) + " asleep" + (day.spo2 != null && day.spo2.validWindows > 0 ? " \u00b7 SpO2 " + day.spo2.median + "%" : ""), sub.isEmpty() ? "Open Rest for the whole night" : sub,
                    new View.OnClickListener() { @Override public void onClick(View v) { go(3); } }), lp(-1, -2, 10));
        }
        LinearLayout c = card(16);
        LinearLayout h = hrow();
        h.addView(caps("THE LAST 24 HOURS"));
        h.addView(spacer());
        h.addView(tv("data to " + ago(day.newestTs) + "  \u00b7  Full graph \u203a", 12, MUT, 400));
        c.addView(h);
        View last24 = new HrChartView(this, day.hrT, day.hrV, n == null ? 0 : n.bedTs, n == null ? 0 : n.endTs, false);
        last24.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { openGraph(2); } });
        c.addView(last24, lp(-1, dpi(110), 8));
        TextView hint24 = tv("Tap to open the full graph: pinch to zoom, drag to move, expand to fill the screen.", 11, MUT, 400);
        hint24.setPadding(0, dpi(6), 0, 0);
        c.addView(hint24);
        c.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { openGraph(2); } });
        expandable("t_h24", c, new Filler() { @Override public void fill(LinearLayout p) { fillH24Panel(p); } });
        col.addView(c, lp(-1, -2, 10));
        return wrap(col);
    }

    // ------------------------------------------------------------------ HEART
    private View heartTab() {
        LinearLayout col = screen();
        HealthModel.Data d = data;
        if (d == null || d.day == null || d.day.newestTs == 0) {
            col.addView(head("Heart", "Heart", null));
            col.addView(tv("No records yet.", 14, MUT, 400), lp(-2, -2, 16));
            return wrap(col);
        }
        TodayModel.Day day = d.day;
        col.addView(tv("Heart", 13, MUT, 400));
        LinearLayout big = hrow();
        big.setGravity(Gravity.BOTTOM);
        TextView num = tv(shownHr(d) > 0 ? String.valueOf(shownHr(d)) : "\u2014", 64, INK, 200);
        num.setTypeface(font(this, 200));
        big.addView(num);
        TextView unit = tv("  " + shownLabel(d), 14, MUT, 400);
        liveNum = num; liveUnit = unit; liveData = d;
        unit.setPadding(0, 0, 0, dpi(8));
        big.addView(unit);
        big.addView(spacer());
        LinearLayout rt = new LinearLayout(this);
        rt.setOrientation(LinearLayout.VERTICAL);
        rt.setGravity(Gravity.END);
        rt.addView(caps("BEAT TO BEAT"));
        String hrv = "\u2014";
        String hrvSrc = "No ECG reading yet";
        for (HealthModel.Reading r : d.readings) if (!"-".equals(r.hrv)) { hrv = r.hrv + " ms"; hrvSrc = "From the " + r.when + " ECG"; break; }
        rt.addView(tv(hrv, 24, INK, 300));
        big.addView(rt);
        col.addView(big, lp(-1, -2, 8));
        col.addView(tv(shownHr(d) > 0 && !Double.isNaN(day.restingHr) ? (shownHr(d) > day.restingHr + 8 ? "Above your lowest 5-minute rate of " : "Close to your lowest 5-minute rate of ") + Math.round(day.restingHr) + ". " + hrvSrc + "." : hrvSrc + ".", 12, MUT, 400), lp(-2, -2, 6));

        LinearLayout hrc = explainerCard("ABOUT THE HEART RATE", "How it is measured, what the zones mean and when to trust it");
        expandable("h_hr", hrc, new Filler() { @Override public void fill(LinearLayout p) { fillHrNow(p); } });
        col.addView(hrc, lp(-1, -2, 10));
        LinearLayout c = card(16);
        LinearLayout h = hrow();
        h.addView(caps("TODAY \u00b7 LAST 24 HOURS"));
        h.addView(spacer());
        h.addView(tv(d.hr24Max > 0 ? d.hr24Min + " low \u00b7 " + Math.round(d.hr24Mean) + " avg \u00b7 " + d.hr24Max + " high" : "", 12, MUT, 400));
        c.addView(h);
        View chartView = new HrChartView(this, day.hrT, day.hrV, day.night == null ? 0 : day.night.bedTs, day.night == null ? 0 : day.night.endTs, true);
        chartView.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { openGraph(2); } });
        c.addView(chartView, lp(-1, dpi(170), 8));
        LinearLayout chipsRow = hrow();
        for (int ri = 0; ri < HeartGraph.RANGE_LABEL.length; ri++) {
            final int rIdx = ri;
            TextView chp = HeartGraph.pill(this, HeartGraph.RANGE_LABEL[ri], 0x1AFFFFFF, INK);
            chp.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { openGraph(rIdx); } });
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
            cl.rightMargin = dpi(8);
            chipsRow.addView(chp, cl);
        }
        chipsRow.addView(spacer());
        chipsRow.addView(tv("Full graph \u203a", 12, BLUE, 400));
        c.addView(chipsRow, lp(-1, -2, 10));
        expandable("h_chart", c, new Filler() { @Override public void fill(LinearLayout p) { fillH24Panel(p); } });
        col.addView(c, lp(-1, -2, 14));

        LinearLayout z = card(16);
        z.addView(caps("WHERE THE TIME WAS SPENT"));
        z.addView(new ZoneBarView(this, d.zoneSec), lp(-1, dpi(10), 12));
        int mh = maxHr();
        String[] nm = {"Resting", "Easy", "Steady", "Hard", "All out"};
        int[] colz = {0xFF6A73F2, 0xFF4CB4FF, 0xFF2EE0B8, 0xFFF2B35B, 0xFFFF6B6B};
        int[] ze = HealthModel.zoneEdges(mh);
        int[] edge = {0, ze[0], ze[1], ze[2], ze[3]};
        for (int i = 0; i < 5; i++) {
            if (i > 2 && d.zoneSec[i] == 0 && i == 4) continue;
            LinearLayout r = hrow();
            View dot = new View(this);
            android.graphics.drawable.GradientDrawable dg = new android.graphics.drawable.GradientDrawable();
            dg.setColor(colz[i]); dg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            dot.setBackground(dg);
            r.addView(dot, new LinearLayout.LayoutParams(dpi(8), dpi(8)));
            TextView a = tv("  " + nm[i], 13, INK, 400);
            r.addView(a, new LinearLayout.LayoutParams(dpi(96), -2));
            r.addView(tv(i == 0 ? "under " + edge[1] : edge[i] + (i < 4 ? " to " + edge[i + 1] : " and over"), 12, MUT, 400));
            r.addView(spacer());
            r.addView(tv(d.zoneSec[i] == 0 ? "\u2014" : HealthModel.hm(d.zoneSec[i] / 3600.0), 13, INK, 600));
            z.addView(r, lp(-1, dpi(26), i == 0 ? 10 : 0));
        }
        expandable("h_zones", z, new Filler() { @Override public void fill(LinearLayout p) { fillZonesPanel(p); } });
        col.addView(z, lp(-1, -2, 12));

        LinearLayout bb = card(16);
        LinearLayout bh = hrow();
        bh.addView(caps("BEAT TO BEAT"));
        bh.addView(spacer());
        double lastOpt = Double.NaN;
        for (TodayModel.NightStat ns : d.nights) if (!Double.isNaN(ns.rmssd)) lastOpt = ns.rmssd;
        bh.addView(tv(Double.isNaN(lastOpt) ? "" : "last night " + Math.round(lastOpt) + " ms, optical", 12, MUT, 400));
        bb.addView(bh);
        LinearLayout two = hrow();
        LinearLayout lcol = new LinearLayout(this);
        lcol.setOrientation(LinearLayout.VERTICAL);
        lcol.addView(caps("FROM THE ECG"));
        lcol.addView(tv(hrv, 26, INK, 300));
        lcol.addView(tv(hrvSrc, 11, MUT, 400));
        two.addView(lcol, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout rcol = new LinearLayout(this);
        rcol.setOrientation(LinearLayout.VERTICAL);
        rcol.addView(caps("FROM THE WRIST (OPTICAL)"));
        rcol.addView(tv(Double.isNaN(lastOpt) ? "\u2014" : Math.round(lastOpt) + " ms", 26, INK, 300));
        rcol.addView(tv("overnight RMSSD; runs about 1.8 times the chest strap's", 11, MUT, 400));
        two.addView(rcol, new LinearLayout.LayoutParams(0, -2, 1f));
        bb.addView(two, lp(-1, -2, 10));
        int withRm = 0;
        for (TodayModel.NightStat ns : d.nights) if (!Double.isNaN(ns.rmssd)) withRm++;
        if (withRm >= 2) bb.addView(new NightsLineView(this, d.nights, true), lp(-1, dpi(90), 10));
        expandable("h_b2b", bb, new Filler() { @Override public void fill(LinearLayout p) { fillB2BPanel(p); } });
        col.addView(bb, lp(-1, -2, 12));

        LinearLayout rd = card(16);
        rd.addView(tv("A reading, now", 16, INK, 600));
        TextView t = tv("About three minutes with two fingers on the clasp. An ECG reading gives beat-to-beat figures you can set beside the overnight ones.", 12.5f, MUT, 400);
        t.setLineSpacing(0, 1.2f);
        t.setPadding(0, dpi(6), 0, 0);
        rd.addView(t);
        rd.addView(pillButton("Start an ECG reading", BLUE, 0xFF05131E, new View.OnClickListener() { @Override public void onClick(View v) { startEcg(); } }), lp(-1, dpi(42), 12));
        expandable("h_read", rd, new Filler() { @Override public void fill(LinearLayout p) { fillEcgPanel(p); } });
        col.addView(rd, lp(-1, -2, 12));

        // heart over time
        LinearLayout ov = card(16);
        LinearLayout oh = hrow();
        oh.addView(caps("HEART OVER TIME"));
        oh.addView(spacer());
        int nn = d.nights.size();
        oh.addView(tv(nn + (nn == 1 ? " night" : " nights") + " logged", 12, nn >= 14 ? MUT : GOLD, 400));
        ov.addView(oh);
        if (nn >= 2) ov.addView(new NightsLineView(this, d.nights, false), lp(-1, dpi(110), 10));
        TextView note = tv(nn >= 2 ? "Lowest five-minute heart rate each night. Optical HRV is in the Rest tab." : "The line draws itself from the second night on. Last night's lowest five-minute heart rate was " + (Double.isNaN(day.restingHr) ? "\u2014" : String.valueOf(Math.round(day.restingHr))) + ".", 12, MUT, 400);
        note.setPadding(0, dpi(8), 0, 0);
        ov.addView(note);
        expandable("h_over", ov, new Filler() { @Override public void fill(LinearLayout p) { fillOverTimePanel(p); } });
        col.addView(ov, lp(-1, -2, 12));
        return wrap(col);
    }

    /** Lowest five-minute heart rate per logged night, newest on the right. */
    static final class NightsLineView extends View {
        private final List<TodayModel.NightStat> nights;
        private final boolean rm;
        NightsLineView(Context c, List<TodayModel.NightStat> nights, boolean rmssd) { super(c); this.nights = nights; this.rm = rmssd; }
        private double val(TodayModel.NightStat s) { return rm ? s.rmssd : s.hrLow5; }
        @Override protected void onDraw(Canvas cv) {
            Context c = getContext();
            List<TodayModel.NightStat> use = new ArrayList<>();
            for (TodayModel.NightStat s : nights) if (!Double.isNaN(val(s))) use.add(s);
            if (use.size() > 14) use = use.subList(use.size() - 14, use.size());
            if (use.size() < 2) return;
            float w = getWidth(), h = getHeight() - dp(c, 14);
            double lo = 1e9, hi = -1e9;
            for (TodayModel.NightStat s : use) { lo = Math.min(lo, val(s)); hi = Math.max(hi, val(s)); }
            lo = Math.floor(lo - 2); hi = Math.ceil(hi + 2);
            if (hi - lo < 8) { hi = lo + 8; }
            List<float[]> pts = new ArrayList<>();
            for (int i = 0; i < use.size(); i++) pts.add(new float[] {dp(c, 8) + (w - dp(c, 16)) * i / (use.size() - 1f), (float) (h * (1 - (val(use.get(i)) - lo) / (hi - lo)))});
            Paint g = new Paint(Paint.ANTI_ALIAS_FLAG);
            g.setColor(0x12FFFFFF); g.setStrokeWidth(dp(c, 1));
            for (int k = 0; k < 4; k++) cv.drawLine(0, h * k / 3f, w, h * k / 3f, g);
            Paint ln = new Paint(Paint.ANTI_ALIAS_FLAG);
            ln.setStyle(Paint.Style.STROKE); ln.setStrokeWidth(dp(c, 2.2f)); ln.setStrokeCap(Paint.Cap.ROUND);
            ln.setShader(new LinearGradient(0, 0, w, 0, 0xFFFF7A93, 0xFFFFC36B, Shader.TileMode.CLAMP));
            cv.drawPath(smooth(pts), ln);
            for (float[] p : pts) { cv.drawCircle(p[0], p[1], dp(c, 5), paint(0x40FF7A93)); cv.drawCircle(p[0], p[1], dp(c, 2.6f), paint(0xFFFFFFFF)); }
            Paint tx = new Paint(Paint.ANTI_ALIAS_FLAG);
            tx.setColor(MUT); tx.setTextSize(dp(c, 10)); tx.setTypeface(font(c, 400));
            SimpleDateFormat f = new SimpleDateFormat("d MMM", Locale.UK);
            tx.setTextAlign(Paint.Align.LEFT);
            cv.drawText(f.format(new Date(use.get(0).bed * 1000L)), 0, getHeight() - dp(c, 2), tx);
            tx.setTextAlign(Paint.Align.RIGHT);
            cv.drawText(f.format(new Date(use.get(use.size() - 1).bed * 1000L)), w, getHeight() - dp(c, 2), tx);
        }
    }

    // ------------------------------------------------------------------ ECG
    private View ecgTab() {
        LinearLayout col = screen();
        HealthModel.Data d = data;
        int n = d == null ? 0 : d.readings.size();
        col.addView(tv("ECG", 13, MUT, 400));
        LinearLayout top = hrow();
        top.addView(tv(n + (n == 1 ? " reading" : " readings"), 40, INK, 200));
        top.addView(spacer());
        top.addView(pillButton("Start", BLUE, 0xFF05131E, new View.OnClickListener() { @Override public void onClick(View v) { startEcg(); } }), new LinearLayout.LayoutParams(dpi(110), dpi(38)));
        col.addView(top, lp(-1, -2, 8));
        col.addView(tv(n == 0 ? "None saved yet. Two fingers on the clasp, about three minutes." : "Newest first. Tap one to open its full report.", 12.5f, MUT, 400), lp(-2, -2, 4));
        long lastEnd = NewLookActivity.lastReadingEndMs;                   // 0.22.0: a reading just ended: the report takes 1 to 3 minutes
        if (lastEnd > 0 && System.currentTimeMillis() - lastEnd < 6 * 60000L && (n == 0 || d.readings.get(0).timeMs < lastEnd - 300000L)) {
            col.addView(rowCard("Preparing your report\u2026", "The stored ECG is fetched and analysed, 1 to 3 minutes. This list fills in by itself.", null), lp(-1, -2, 12));
        }
        if (n > 0 && d.readings.get(0).file != null) {
            final java.io.File newest = d.readings.get(0).file;
            col.addView(pillButton("Professional PDF of the newest reading", 0x1A2EE6A8, 0xFF2EE6A8, new View.OnClickListener() { @Override public void onClick(View v) { ProReportMaker.make(HealthActivity.this, newest); } }), lp(-1, dpi(44), 12));        // 0.23.0
        }
        col.addView(pillButton("Save the analysis code (Python and Java) to Downloads", 0x1AFFFFFF, INK, new View.OnClickListener() { @Override public void onClick(View v) { ProReportMaker.saveCode(HealthActivity.this); } }), lp(-1, dpi(44), 8));      // 0.23.1
        col.addView(pillButton("Recover readings from the strap's stored data", 0x1AFFFFFF, INK, new View.OnClickListener() { @Override public void onClick(View v) { StoredReading.show(HealthActivity.this); } }), lp(-1, dpi(44), 8));      // 0.25.1
        LinearLayout mc = explainerCard("HOW THE REPORT IS MADE AND CHECKED", "Method, agreement with the Python reference, and the database tests");
        expandable("e_method", mc, new Filler() { @Override public void fill(LinearLayout p) { rows(p, Explain.reportMethod()); } });
        col.addView(mc, lp(-1, -2, 10));
        LinearLayout egt = explainerCard("HOW TO GET A STRONG READING", "What the grades mean, finger placement, what the QRS height tells you");
        expandable("e_ecg", egt, new Filler() { @Override public void fill(LinearLayout p) { fillEcgPanel(p); } });
        col.addView(egt, lp(-1, -2, 12));
        if (d != null) {
            for (final HealthModel.Reading r : d.readings) {
                LinearLayout c = card(16);
                LinearLayout h = hrow();
                h.addView(tv(r.when, 15, INK, 600));
                h.addView(spacer());
                if (!"-".equals(r.grade)) {
                    String gr = r.grade.toUpperCase(Locale.UK);
                    h.addView(chipSmall("GRADE " + gr, gr.startsWith("A") || gr.startsWith("B") ? GREEN : (gr.startsWith("C") ? GOLD : MUT)));
                }
                c.addView(h);
                StringBuilder sub = new StringBuilder();
                if (!"-".equals(r.hr)) sub.append("HR ").append(r.hr).append(" bpm");
                if (!"-".equals(r.hrv)) sub.append(sub.length() > 0 ? " \u00b7 " : "").append("HRV ").append(r.hrv).append(" ms");
                if (!"-".equals(r.rhythm)) sub.append(sub.length() > 0 ? " \u00b7 " : "").append(r.rhythm);
                TextView s = tv(sub.length() == 0 ? "Open to see why" : sub.toString(), 12.5f, MUT, 400);
                s.setPadding(0, dpi(4), 0, 0);
                c.addView(s);
                c.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { openReport(r.file); } });
                col.addView(c, lp(-1, -2, 12));
            }
        }
        col.addView(pillButton("Export ECG data for analysis", 0x1AFFFFFF, INK, new View.OnClickListener() { @Override public void onClick(View v) { EcgExport.ask(HealthActivity.this); } }), lp(-1, dpi(44), 16));
        col.addView(tv("Research only: not a diagnosis. Reports show how reliable each measurement was.", 11, MUT, 400), lp(-2, -2, 16));
        return wrap(col);
    }

    private View chipSmall(String s, int color) {
        TextView t = tv(s, 9.5f, color, 600);
        t.setLetterSpacing(0.12f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dpi(11), dpi(5), dpi(11), dpi(5));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(0x0DFFFFFF); g.setCornerRadius(dp(this, 13)); g.setStroke(dpi(1), 0x0FFFFFFF);
        t.setBackground(g);
        return t;
    }

    // ------------------------------------------------------------------ REST
    private View restTab() {
        LinearLayout col = screen();
        HealthModel.Data d = data;
        if (d == null || d.day == null || d.day.night == null) {
            col.addView(head("Last night", "Rest", null));
            col.addView(tv("No complete night in the records yet.", 14, MUT, 400), lp(-2, -2, 16));
            return wrap(col);
        }
        SleepNight.Night n = d.day.night;
        double goal = goal();
        col.addView(head("Last night", "Rest", chip(hhmm(n.bedTs) + " to " + hhmm(n.endTs), 0)));
        int rs = Math.min(screenW(), dpi(290));
        double need = Math.min(1.0, n.tstH / goal);
        View ring = new StageRingView(this, ringSegments(d, need), HealthModel.hm(n.tstH).replace(" ", ""), "ASLEEP");
        LinearLayout.LayoutParams rp = lp(rs, rs, 0);
        rp.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(ring, rp);
        double diff = n.tstH - goal;
        TextView chipT = chip((diff < 0 ? HealthModel.hm(-diff) + " under" : HealthModel.hm(diff) + " over"), 0);
        chipT.setTextColor(0xFFBFE6FF);
        LinearLayout.LayoutParams cp = lp(-2, -2, -64);
        cp.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(chipT, cp);
        TextView capt = tv("the ring closes at your need \u00b7 " + HealthModel.hm(goal), 12, MUT, 400);
        capt.setGravity(Gravity.CENTER);
        col.addView(capt, lp(-1, -2, 28));
        col.addView(rowCard("The ring is " + Math.round(100 * need) + "% closed. See what the night was made of", "Stages are estimates from heart rate and movement", null), lp(-1, -2, 14));
        LinearLayout ringc = explainerCard("ABOUT THE RING", "What it fills towards and how the stages are estimated");
        final double goalF = goal;
        expandable("r_ring", ringc, new Filler() { @Override public void fill(LinearLayout p) { rows(p, Explain.sleepRing(goalF)); } });
        col.addView(ringc, lp(-1, -2, 10));

        LinearLayout w = card(16);
        w.addView(caps("YOUR LAST SEVEN"));
        w.addView(new WeekBarsView(this, d.lastSeven, d.lastSevenBed, goal), lp(-1, dpi(140), 12));
        expandable("r_week", w, new Filler() { @Override public void fill(LinearLayout p) { fillSleepWeekPanel(p); } });
        col.addView(w, lp(-1, -2, 12));

        long tot = 0;
        for (long s : d.stageSecs) tot += s;
        LinearLayout sh = card(16);
        LinearLayout hh = hrow();
        hh.addView(caps("THE SHAPE OF IT"));
        hh.addView(spacer());
        hh.addView(tv(hhmm(n.bedTs) + " to " + hhmm(n.endTs), 12, MUT, 400));
        sh.addView(hh);
        if (tot > 0) {
            sh.addView(stageBar(d.stageSecs), lp(-1, dpi(10), 12));
            TextView lg = tv("Deep " + HealthModel.hmShort(d.stageSecs[HealthModel.DEEP]) + " \u00b7 REM " + HealthModel.hmShort(d.stageSecs[HealthModel.REM]) + " \u00b7 Light " + HealthModel.hmShort(d.stageSecs[HealthModel.LIGHT]) + " \u00b7 estimate", 12, MUT, 400);
            lg.setPadding(0, dpi(8), 0, 0);
            sh.addView(lg);
        } else {
            sh.addView(tv("The night was too short to stage.", 13, MUT, 400), lp(-2, -2, 10));
        }
        expandable("r_shape", sh, new Filler() { @Override public void fill(LinearLayout p) { fillShapePanel(p); } });
        col.addView(sh, lp(-1, -2, 12));

        if (tot > 0) {
            LinearLayout wn = card(16);
            LinearLayout wh = hrow();
            wh.addView(caps("THE WHOLE NIGHT"));
            wh.addView(spacer());
            wh.addView(chipSmall("ESTIMATE", GOLD));
            wn.addView(wh);
            wn.addView(new StageFlowView(this, d.binStage, d.binSure, d.binStart), lp(-1, dpi(140), 10));
            long[] s = d.stageSecs;
            String[] nm = {"AWAKE", "REM", "LIGHT", "DEEP"};
            int[] idx = {HealthModel.WAKE, HealthModel.REM, HealthModel.LIGHT, HealthModel.DEEP};
            for (int i = 0; i < 4; i++) {
                LinearLayout r = hrow();
                TextView a = tv(nm[i], 11, MUT, 600);
                a.setLetterSpacing(0.14f);
                r.addView(a, new LinearLayout.LayoutParams(dpi(58), -2));
                r.addView(pips((float) s[idx[i]] / tot, STAGE[idx[i]]), new LinearLayout.LayoutParams(0, dpi(6), 1f));
                TextView pc = tv(Math.round(100.0 * s[idx[i]] / tot) + "%", 12, STAGE[idx[i]], 400);
                pc.setGravity(Gravity.END);
                r.addView(pc, new LinearLayout.LayoutParams(dpi(44), -2));
                TextView tm = tv(HealthModel.hmShort(s[idx[i]]), 15, INK, 300);
                tm.setGravity(Gravity.END);
                r.addView(tm, new LinearLayout.LayoutParams(dpi(48), -2));
                wn.addView(r, lp(-1, dpi(30), i == 0 ? 8 : 0));
            }
            int sure = 0, all = 0;
            for (int i = 0; i < d.binStage.length; i++) if (d.binStage[i] >= 0) { all++; if (d.binSure[i]) sure++; }
            TextView note = tv("Solid blocks are where two staging methods agree (" + (all == 0 ? 0 : Math.round(100.0 * sure / all)) + "% of the night). Dashed blocks are where they differ. Nothing in a wrist band sees brain activity, so these are estimates, not a sleep study.", 11, MUT, 400);
            note.setLineSpacing(0, 1.2f);
            note.setPadding(0, dpi(10), 0, 0);
            wn.addView(note);
            expandable("r_night", wn, new Filler() { @Override public void fill(LinearLayout p) { fillWholeNightPanel(p); } });
            col.addView(wn, lp(-1, -2, 12));
        }
        if (d.day.spo2 != null && d.day.spo2.validWindows > 0) {
            LinearLayout so = card(16);
            LinearLayout sh2 = hrow();
            sh2.addView(caps("SPO2 \u00b7 STRAP ESTIMATE"));
            sh2.addView(spacer());
            sh2.addView(tv(d.day.spo2.median + "%", 20, 0xFF46E0C8, 300));
            so.addView(sh2);
            so.addView(new SpoView(this, d.spo2Windows), lp(-1, dpi(96), 10));
            TextView t = tv(d.day.spo2.validWindows + " windows, " + d.day.spo2.min + " to " + d.day.spo2.max + "%. " + (d.day.spo2.below94 == 0 ? "None below 94. " : d.day.spo2.below94 + " below 94. ") + "Not calibrated against an oximeter.", 12, MUT, 400);
            t.setPadding(0, dpi(8), 0, 0);
            so.addView(t);
            expandable("r_spo2", so, new Filler() { @Override public void fill(LinearLayout p) { fillSpo2Panel(p); } });
            col.addView(so, lp(-1, -2, 12));
        }
        // ---- this week: average sleep, sleep debt and bedtime spread
        List<TodayModel.NightStat> wk = Trends.allNights(d);
        if (wk.size() >= 2) {
            LinearLayout wc = card(16);
            wc.addView(caps("THIS WEEK"));
            TextView wt = tv(Trends.weekSummary(wk, goal()), 13, INK, 400);
            wt.setLineSpacing(0, 1.25f);
            wt.setPadding(0, dpi(8), 0, 0);
            wc.addView(wt);
            wc.addView(tv("Tap for the trends", 12, BLUE, 400), lp(-2, -2, 8));
            wc.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { if (data != null) Trends.show(HealthActivity.this, data); } });
            col.addView(wc, lp(-1, -2, 12));
        }
        return wrap(col);
    }

    /** Segments for the ring: {stage, from, to, sure}. Fractions of the closed part of the ring, merged and de-confettied. */
    private List<float[]> ringSegments(HealthModel.Data d, double need) {
        List<float[]> out = new ArrayList<>();
        int n = d.binStage.length;
        if (n == 0) return out;
        int[] sm = new int[n];
        for (int i = 0; i < n; i++) {
            int[] c = new int[4];
            for (int k = Math.max(0, i - 1); k <= Math.min(n - 1, i + 1); k++) if (d.binStage[k] >= 0) c[d.binStage[k]]++;
            int best = d.binStage[i] >= 0 ? d.binStage[i] : HealthModel.LIGHT, bc = c[best];
            for (int k = 0; k < 4; k++) if (c[k] > bc) { bc = c[k]; best = k; }
            sm[i] = best;
        }
        int i = 0;
        while (i < n) {
            int j = i;
            double sure = 0;
            while (j + 1 < n && sm[j + 1] == sm[i]) j++;
            for (int k = i; k <= j; k++) sure += d.binSure[k] ? 1 : 0;
            out.add(new float[] {sm[i], (float) (need * i / n), (float) (need * (j + 1) / n), sure / (j - i + 1) >= 0.5 ? 1f : 0f});
            i = j + 1;
        }
        return out;
    }

    private View stageBar(final long[] s) {
        return new View(this) {
            @Override protected void onDraw(Canvas cv) {
                float w = getWidth(), h = getHeight(), gap = dp(getContext(), 3), x = 0;
                long tot = s[HealthModel.DEEP] + s[HealthModel.REM] + s[HealthModel.LIGHT];
                if (tot == 0) return;
                int[] order = {HealthModel.DEEP, HealthModel.REM, HealthModel.LIGHT};
                float usable = w - 2 * gap;
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                for (int k : order) {
                    float wi = usable * s[k] / tot;
                    p.setColor(STAGE[k]);
                    cv.drawRoundRect(new RectF(x, 0, x + wi, h), dp(getContext(), 3), dp(getContext(), 3), p);
                    x += wi + gap;
                }
            }
        };
    }

    private View pips(final float frac, final int color) {
        return new View(this) {
            @Override protected void onDraw(Canvas cv) {
                float w = getWidth(), h = getHeight(), gap = dp(getContext(), 3);
                int n = 14, on = Math.round(frac * n);
                float pw = (w - gap * (n - 1)) / n;
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                for (int i = 0; i < n; i++) {
                    p.setColor(alpha(color, i < on ? 1f : 0.14f));
                    cv.drawRoundRect(new RectF(i * (pw + gap), 0, i * (pw + gap) + pw, h), h / 2f, h / 2f, p);
                }
            }
        };
    }

    // ------------------------------------------------------------------ YOU
    private View youTab() {
        LinearLayout col = screen();
        HealthModel.Data d = data;
        col.addView(head("Strap, logger and settings", "You", null));
        float logged = d == null || d.day == null ? 0f : (float) d.day.loggedH;
        View av = avatar(Math.min(1f, logged / 24f));
        LinearLayout.LayoutParams ap = lp(dpi(170), dpi(170), 10);
        ap.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(av, ap);
        TextView nm = tv(name(), 22, INK, 400);
        nm.setGravity(Gravity.CENTER);
        col.addView(nm, lp(-1, -2, 4));
        TextView cap = tv(String.format(Locale.UK, "%.1f h logged in the last 24", logged), 12.5f, MUT, 400);
        cap.setGravity(Gravity.CENTER);
        col.addView(cap, lp(-1, -2, 6));

        LinearLayout st = card(16);
        st.addView(caps("SETTINGS"));
        final SharedPreferences ctl = getSharedPreferences("labrador_ecg_control", MODE_PRIVATE);
        st.addView(toggleRow("Start the ECG reading by itself after a 5 second countdown", ctl, "ecg_autostart"), lp(-1, dpi(52), 6));
        st.addView(toggleRow("Show the packet counters on the screen during a reading", ctl, "ecg_packets_open"), lp(-1, dpi(52), 0));
        st.addView(toggleRow("Record the H10 with each ECG reading (adds a PDF page)", ctl, "h10_with_reading", false), lp(-1, dpi(52), 0));
        st.addView(toggleRow("Near-live 500 Hz during readings", ctl, "ecg_tail"), lp(-1, dpi(52), 0));
        st.addView(toggleRow("Auto-restart at the 10-minute cap", ctl, "ecg_autorestart"), lp(-1, dpi(52), 0));
        st.addView(toggleRow("Set the strap clock to the sub-second (as the official app)", ctl, "clock_subsec"), lp(-1, dpi(52), 0));
        final TextView lrv = tv(liveRawModeText(ctl), 14, BLUE, 600);
        st.addView(valueRow("Live raw 500 Hz during readings (command 126)", lrv, new View.OnClickListener() {
            @Override public void onClick(View v) { int md = (ctl.getInt("ecg_liveraw_mode", 1) + 1) % 3; ctl.edit().putInt("ecg_liveraw_mode", md).apply(); lrv.setText(liveRawModeText(ctl)); }
        }), lp(-1, dpi(52), 0));
        TextView lrNote = tv("Tap to change: Off, On at start (the default: the live 500 Hz stream from the first second, about 0.1 s behind), Staged (126 on at about 16 s and off at about 41 s of one reading, a test).", 11.5f, MUT, 400);
        lrNote.setPadding(0, 0, 0, dpi(6));
        st.addView(lrNote);
        st.addView(toggleRow("Show the 500 Hz graph when live raw frames arrive", ctl, "ecg_view500"), lp(-1, dpi(52), 0));
        final TextView smv = tv(smoothText(ctl), 14, BLUE, 600);
        st.addView(valueRow("500 Hz graph smoothing (display only)", smv, new View.OnClickListener() {
            @Override public void onClick(View v) { int md = ctl.getInt("ecg_smooth500", 2); md = md == 2 ? 1 : md == 1 ? 0 : 2; ctl.edit().putInt("ecg_smooth500", md).apply(); smv.setText(smoothText(ctl)); }
        }), lp(-1, dpi(52), 0));
        TextView smNote = tv("Tap to change: Smooth (default), Light, Off. Smooths the noise between the beats and keeps the QRS sharp; the R wave, the P and T waves are kept (T and P about 90 %). Display only: the reports and the stored data are not changed. Takes effect at the next reading.", 11.5f, MUT, 400);
        smNote.setPadding(0, 0, 0, dpi(6));
        st.addView(smNote);
        st.addView(toggleRow("TEST: save filtered ECG (command 127)", ctl, "ecg_savefiltered", false), lp(-1, dpi(52), 0));
        st.addView(toggleRow("Morning summary notification", sp(), "n_morning"), lp(-1, dpi(52), 0));
        st.addView(toggleRow("Strap battery alert (15% or less)", sp(), "n_battery"), lp(-1, dpi(52), 0));
        final boolean ovl = android.os.Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(this);
        st.addView(pillButton(ovl ? "Restart the strap link by itself: allowed" : "Allow the app to restart the strap link by itself", ovl ? 0x1AFFFFFF : BLUE, ovl ? INK : 0xFF05131E, new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + getPackageName()))); } catch (Throwable ignored) { }
            }
        }), lp(-1, dpi(44), 10));
        TextView ovNote = tv("If Android ends the app, only a screen start can bring the strap link back, and Android allows that from the background only with \"Display over other apps\". Allow it and the logger restores the link by itself, invisibly. Nothing is drawn over other apps.", 11.5f, MUT, 400);
        ovNote.setLineSpacing(0, 1.2f);
        ovNote.setPadding(0, dpi(6), 0, 0);
        st.addView(ovNote);
        final TextView goalV = tv(String.format(Locale.UK, "%.1f h", goal()), 14, INK, 600);
        st.addView(valueRow("Sleep goal", goalV, new View.OnClickListener() {
            @Override public void onClick(View v) { float g = goal() + 0.5f; if (g > 9.5f) g = 6.5f; sp().edit().putFloat("goal", g).apply(); goalV.setText(String.format(Locale.UK, "%.1f h", g)); load(); }
        }), lp(-1, dpi(52), 0));
        final TextView mxV = tv(maxHr() + " bpm", 14, INK, 600);
        st.addView(valueRow("Maximum heart rate", mxV, new View.OnClickListener() { @Override public void onClick(View v) { askMaxHr(mxV); } }), lp(-1, dpi(52), 0));
        col.addView(st, lp(-1, -2, 16));

        LinearLayout dt = card(16);
        dt.addView(caps("YOUR DATA"));
        TextView t = tv("Everything stays on this phone. The day logger holds the raw history and exports it as one zip.", 12.5f, MUT, 400);
        t.setLineSpacing(0, 1.2f);
        t.setPadding(0, dpi(6), 0, 0);
        dt.addView(t);
        dt.addView(pillButton("Import a pull (zip)", BLUE, 0xFF05131E, new View.OnClickListener() { @Override public void onClick(View v) { pickZip(); } }), lp(-1, dpi(44), 12));
        dt.addView(pillButton("Export and send data", BLUE, 0xFF05131E, new View.OnClickListener() { @Override public void onClick(View v) { ExportScreen.show(HealthActivity.this); } }), lp(-1, dpi(44), 12));
        TextView exNote = tv("Pick a period, make one dated zip, send it. Earlier exports are listed with their date and time.", 11.5f, MUT, 400);
        exNote.setPadding(0, dpi(6), 0, 0);
        dt.addView(exNote);
        dt.addView(pillButton("Daily backup", 0x1AFFFFFF, INK, new View.OnClickListener() { @Override public void onClick(View v) { BackupScreen.show(HealthActivity.this); } }), lp(-1, dpi(44), 12));
        TextView bkNote = tv("Copies all the app's data every day at a time you choose into Download/MGbackup.  Backup: " + Backup.shortStatus(this), 11.5f, MUT, 400);
        bkNote.setPadding(0, dpi(6), 0, 0);
        dt.addView(bkNote);
        dt.addView(pillButton("Day logger and export", 0x1AFFFFFF, INK, new View.OnClickListener() { @Override public void onClick(View v) { startActivity(new Intent(HealthActivity.this, DayLogActivity.class)); } }), lp(-1, dpi(44), 12));
        dt.addView(pillButton("Export ECG data for analysis", 0x1AFFFFFF, INK, new View.OnClickListener() { @Override public void onClick(View v) { EcgExport.ask(HealthActivity.this); } }), lp(-1, dpi(44), 8));
        dt.addView(pillButton("Live frames (watch the strap's packets arrive)", 0x1A2EE6A8, 0xFF2EE6A8, new View.OnClickListener() { @Override public void onClick(View v) { startActivity(new Intent(HealthActivity.this, LiveFramesActivity.class)); } }), lp(-1, dpi(44), 8));
        dt.addView(pillButton("Older screens (ECG tools)", 0x1AFFFFFF, INK, new View.OnClickListener() { @Override public void onClick(View v) { startActivity(oldScreens()); } }), lp(-1, dpi(44), 8));
        TextView imp = tv(importNote, 11.5f, MUT, 400);
        imp.setPadding(0, dpi(10), 0, 0);
        dt.addView(imp);
        col.addView(dt, lp(-1, -2, 12));
        return wrap(col);
    }

    private View avatar(final float frac) {
        final String letter = name().isEmpty() ? "A" : name().substring(0, 1).toUpperCase(Locale.UK);
        return new View(this) {
            @Override protected void onDraw(Canvas cv) {
                Context c = getContext();
                float size = getWidth(), cx = size / 2f, cy = size / 2f, r = size * 0.40f, sw = dp(c, 10);
                RectF box = new RectF(cx - r, cy - r, cx + r, cy + r);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeWidth(sw);
                p.setColor(0x12FFFFFF);
                cv.drawArc(box, 0, 360, false, p);
                for (int pass = 0; pass < 3; pass++) {
                    p.setShader(new LinearGradient(0, 0, size, size, 0xFFF2B35B, 0xFFFF8CA2, Shader.TileMode.CLAMP));
                    p.setStrokeWidth(sw + dp(c, 3 * (2 - pass)));
                    p.setAlpha(pass < 2 ? 50 + 40 * pass : 255);
                    cv.drawArc(box, -90, 360f * frac, false, p);
                }
                p.setShader(null);
                Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
                t.setColor(INK); t.setTextAlign(Paint.Align.CENTER); t.setTypeface(font(c, 200)); t.setTextSize(dp(c, 62));
                cv.drawText(letter, cx, cy + dp(c, 22), t);
            }
        };
    }

    private View toggleRow(String label, final SharedPreferences prefs, final String key) { return toggleRow(label, prefs, key, true); }

    private static String smoothText(SharedPreferences p) {
        int md = p.getInt("ecg_smooth500", 2);
        return md == 1 ? "Light" : md == 0 ? "Off" : "Smooth";
    }

    private static String liveRawModeText(SharedPreferences p) {
        int md = p.getInt("ecg_liveraw_mode", 1);
        return md == 1 ? "On at start" : md == 2 ? "Staged" : "Off";
    }

    private View toggleRow(String label, final SharedPreferences prefs, final String key, final boolean def) {
        LinearLayout r = hrow();
        r.addView(tv(label, 14, INK, 400), new LinearLayout.LayoutParams(0, -2, 1f));
        final Toggle t = new Toggle(this, prefs.getBoolean(key, def));
        t.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { boolean on = !prefs.getBoolean(key, def); prefs.edit().putBoolean(key, on).apply(); t.set(on); } });
        r.addView(t, new LinearLayout.LayoutParams(dpi(46), dpi(28)));
        r.setBackground(null);
        return r;
    }

    private View valueRow(String label, TextView value, View.OnClickListener l) {
        LinearLayout r = hrow();
        r.addView(tv(label, 14, INK, 400), new LinearLayout.LayoutParams(0, -2, 1f));
        r.addView(value);
        r.setOnClickListener(l);
        return r;
    }

    /** The small pill switch. */
    static final class Toggle extends View {
        private boolean on;
        Toggle(Context c, boolean on) { super(c); this.on = on; }
        void set(boolean v) { on = v; invalidate(); }
        @Override protected void onDraw(Canvas cv) {
            Context c = getContext();
            float w = getWidth(), h = getHeight();
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            if (on) p.setShader(new LinearGradient(0, 0, w, h, 0xFF37B6F6, 0xFF1788E8, Shader.TileMode.CLAMP)); else p.setColor(0x1FFFFFFF);
            cv.drawRoundRect(new RectF(0, 0, w, h), h / 2f, h / 2f, p);
            p.setShader(null);
            p.setColor(0xFFFFFFFF);
            float r = h / 2f - dp(c, 3);
            cv.drawCircle(on ? w - h / 2f : h / 2f, h / 2f, r, p);
        }
    }

    private void askMaxHr(final TextView target) {
        final EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(String.valueOf(maxHr()));
        new AlertDialog.Builder(this).setTitle("Maximum heart rate (bpm)")
                .setMessage("Used only for the heart-rate zones. 220 minus your age is a rough guess; use a measured value if you have one.")
                .setView(e)
                .setPositiveButton("Save", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface di, int w) {
                        try {
                            int v = Integer.parseInt(e.getText().toString().trim());
                            if (v >= 120 && v <= 230) { sp().edit().putInt("maxhr", v).apply(); target.setText(v + " bpm"); load(); }
                        } catch (NumberFormatException ignored) { }
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    /** Soft colour behind everything: a faint teal wash at the top and a blue glow low down, as in the mock-ups. */
    static final class GlowBg extends View {
        GlowBg(Context c) { super(c); }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new android.graphics.RadialGradient(w / 2f, 0f, w * 0.9f, new int[] {0x3328788F, 0x00000000}, null, Shader.TileMode.CLAMP));
            cv.drawRect(0, 0, w, h, p);
            p.setShader(new android.graphics.RadialGradient(w / 2f, h * 0.78f, w * 0.7f, new int[] {0x2A2FA8F0, 0x00000000}, null, Shader.TileMode.CLAMP));
            cv.drawRect(0, 0, w, h, p);
        }
    }
}
