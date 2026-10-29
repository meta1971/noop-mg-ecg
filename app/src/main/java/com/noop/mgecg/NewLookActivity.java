package com.noop.mgecg;

// FILE VERSION 0.2.1 (3 Oct): restyled ECG screen

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.content.Intent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The new look, as a layer around the existing app. MainActivity extends this class instead of Activity and its
 * own code is not otherwise touched: it still builds the old debug UI and still owns every BLE and ECG
 * routine. When it calls setContentView(oldUi), this class keeps that UI underneath and puts the new screens on
 * top:
 *
 *   Home      connection status, "Take an ECG", the latest reading, all reports
 *   Reports   the saved reports, newest first
 *   Report    one plain-English report in a WebView, with Save as PDF
 *   (ECG)     the existing ECG screen, opened by pressing the old ECG button for you
 *   (Dev)     the old debug UI: long-press the title on Home; HOME button to come back
 *
 * Everything the new screens need from the old UI is found by the text on its buttons, so a button whose text
 * changes only makes that one action do nothing (a toast says so); nothing else breaks.
 * Reports are the HTML files EcgReport writes when a stored-ECG analysis finishes. Research only.
 */
public class NewLookActivity extends Activity {

    // The same palette and type as the opening screen (HealthViews), so the ECG pages look like the rest of the app.
    // PRIMARY is the main action colour (blue, like the Start button there); GREEN is only for "all good" status.
    static final int BG = HealthViews.BG, CARD = HealthViews.CARD, LINE = 0x1AFFFFFF, TEXT = HealthViews.INK,
            MUTED = HealthViews.MUT, PRIMARY = HealthViews.BLUE, GREEN = HealthViews.GREEN, AMBER = HealthViews.GOLD, RED = HealthViews.ROSE, INK = 0xFF05131E;

    static final int ST_HOME = 0, ST_REPORTS = 1, ST_REPORT = 2, ST_ECG = 3, ST_DEV = 4, ST_START = 5;

    private static final String ECG_BUTTON_PREFIX = "\u2764";           // the heart on the old ECG button
    private static final String SCAN_BUTTON_TEXT = "SCAN / CONNECT";
    private static final String ECG_SCREEN_MARKER = "MARK WHAT YOU ARE DOING";
    private static final String BACK_TEXT = "< BACK";

    private final Handler ui = new Handler(Looper.getMainLooper());

    View legacy;                       // the old UI, kept underneath
    int state = ST_HOME;
    private TextView dayLogStatus;   // 0.5.8: the all-day logger, one tap from Home
    private LinearLayout homeGlanceCard;     // 0.10.0: today on your feet, from the day log
    private TextView homeGlanceBody, homeWarn;
    private volatile boolean glanceLoading;
    private HealthViews.NavBar navBar;
    private View navScrim;

    private FrameLayout homeLayer, reportsLayer, reportLayer;
    private LinearLayout reportsList, homeLatestCard;
    private TextView homeConn, homeLatestTitle, homeLatestBody, reportTitle, banner;
    private Button devPill;

    private File bannerFile, openFile, latestFile;
    private long reportBaseline;
    private boolean reportBaselineSet;
    private boolean waitingConnect;
    private long waitStart, connectedSince;
    private View ecgContainerCache, statusViewCache;
    private int tickCount;

    // restyled ECG screen
    private boolean ecgStyled;
    private Button ecgBackBtn, ecgStartBtn, markToggle, detailsToggle, packetsToggle;
    private TextView packetsView;                               // 0.22.2: the packets arriving, shown on the reading screen itself
    // 0.22.0: a reading started from the new tabs goes straight to the ECG screen (no old Home page in between), starts by itself after a countdown,
    // and Back returns to the ECG tab of the new app
    private boolean fromNew, counting, sawRunning, h10StartedHere;
    private int countLeft;
    private FrameLayout startLayer;
    private TextView startMsg, startSub, countdownCard;
    /** When the last reading from the new tabs ended (0 when none ran); HealthActivity shows "Preparing your report" for a few minutes after it. */
    static volatile long lastReadingEndMs;
    private TextView ecgTips, ecgPrep, ecgStoredView;
    private View[] markViews, detailViews;
    private int lastRunningState = -1;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        EcgReport.reportDir = getExternalFilesDir(null);
        try {
            getWindow().setStatusBarColor(BG);
        } catch (Throwable ignored) {
            // cosmetic only
        }
    }

    /** MainActivity calls this once with its debug UI. Keep it, and layer the new screens on top. */
    @Override
    public void setContentView(View view) {
        legacy = view;
        ecgContainerCache = null;
        statusViewCache = null;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));

        homeLayer = buildHome();
        reportsLayer = buildReports();
        reportLayer = buildReport();
        root.addView(homeLayer, new FrameLayout.LayoutParams(-1, -1));
        root.addView(reportsLayer, new FrameLayout.LayoutParams(-1, -1));
        root.addView(reportLayer, new FrameLayout.LayoutParams(-1, -1));
        startLayer = buildStart();
        root.addView(startLayer, new FrameLayout.LayoutParams(-1, -1));
        startLayer.setVisibility(View.GONE);

        devPill = pillButton("HOME", CARD, TEXT);
        devPill.setTextSize(14);
        devPill.setVisibility(View.GONE);
        devPill.setOnClickListener(v -> showHome());
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(-2, dp(44), Gravity.BOTTOM | Gravity.END);
        pl.setMargins(0, 0, dp(12), dp(12));
        root.addView(devPill, pl);

        countdownCard = tv("", 16, INK, true);
        countdownCard.setGravity(Gravity.CENTER);
        countdownCard.setPadding(dp(16), dp(16), dp(16), dp(16));
        countdownCard.setBackground(round(PRIMARY, 20));
        countdownCard.setTextColor(0xFF05131E);
        countdownCard.setVisibility(View.GONE);
        countdownCard.setOnClickListener(v -> { counting = false; countdownCard.setVisibility(View.GONE); toast("Not starting by itself. Press Start when you are ready."); });
        FrameLayout.LayoutParams cdp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        cdp.setMargins(dp(16), 0, dp(16), dp(40));
        root.addView(countdownCard, cdp);

        banner = tv("Your report is ready  \u00b7  View", 15, INK, true);
        banner.setGravity(Gravity.CENTER);
        banner.setPadding(dp(16), dp(14), dp(16), dp(14));
        banner.setBackground(round(PRIMARY, 16));
        banner.setVisibility(View.GONE);
        banner.setOnClickListener(v -> {
            if (bannerFile != null) openReport(bannerFile);
        });
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        bl.setMargins(dp(16), 0, dp(16), dp(16));
        root.addView(banner, bl);

        // the same bottom bar as the opening screen, shown on Home and the reports list (hidden while recording or reading a report)
        navScrim = new View(this) {
            @Override protected void onDraw(android.graphics.Canvas cv) {
                android.graphics.Paint p = new android.graphics.Paint();
                p.setShader(new android.graphics.LinearGradient(0, 0, 0, getHeight(), 0x0007090C, 0xFF07090C, android.graphics.Shader.TileMode.CLAMP));
                cv.drawRect(0, 0, getWidth(), getHeight(), p);
            }
        };
        root.addView(navScrim, new FrameLayout.LayoutParams(-1, dp(130), Gravity.BOTTOM));
        navBar = new HealthViews.NavBar(this, 2, t ->
                startActivity(new android.content.Intent(this, HealthActivity.class).putExtra("tab", t)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)));
        FrameLayout.LayoutParams nbp = new FrameLayout.LayoutParams(-1, dp(68), Gravity.BOTTOM);
        nbp.setMargins(dp(14), 0, dp(14), dp(18));
        root.addView(navBar, nbp);
        updateNav();
        super.setContentView(root);
        android.content.Intent li0 = getIntent();
        if (li0 != null && li0.getBooleanExtra("start_ecg", false) || pendingEcg) { fromNew = true; showStarting("Getting your strap ready\u2026", ""); }
        else showHome();
        ui.removeCallbacks(tick);
        ui.postDelayed(tick, 500);
        handleLaunchExtras(getIntent());
    }

    /** HealthActivity can ask for a saved report to be opened or an ECG reading to be started. */
    private void handleLaunchExtras(android.content.Intent in) {
        if (in == null) return;
        if (in.getBooleanExtra(KeepAlive.EXTRA_RESUME, false)) {      // tapped the "day logger paused / no strap data" alert
            in.removeExtra(KeepAlive.EXTRA_RESUME);
            KeepAlive.cancel(this);
            ui.postDelayed(() -> connectOnly(), 1500);
        }
        final String rp = in.getStringExtra("open_report");
        if (rp != null || in.getBooleanExtra("start_ecg", false)) { pendingReport = null; pendingEcg = false; }   // one path handles it, not two
        if (rp != null) {
            in.removeExtra("open_report");
            ui.postDelayed(() -> openReport(new File(rp)), 700);
        } else if (in.getBooleanExtra("start_ecg", false)) {
            in.removeExtra("start_ecg");
            fromNew = true;
            showStarting("Getting your strap ready\u2026", "");
            ui.postDelayed(() -> takeEcg(), 250);
        }
    }

    /** Set by HealthActivity just before it brings this (already running) screen forward; read in onResume, which always runs. */
    static volatile String pendingReport;
    static volatile boolean pendingEcg;

    private void consumePending() {
        final String rp = pendingReport;
        if (rp != null) {
            pendingReport = null;
            ui.postDelayed(() -> openReport(new File(rp)), 400);
        } else if (pendingEcg) {
            pendingEcg = false;
            fromNew = true;
            showStarting("Getting your strap ready\u2026", "");
            ui.postDelayed(() -> takeEcg(), 250);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        consumePending();
    }

    @Override
    protected void onNewIntent(android.content.Intent in) {
        super.onNewIntent(in);
        setIntent(in);
        handleLaunchExtras(in);
    }

    @Override
    protected void onDestroy() {
        counting = false;                                   // 0.22.1: a countdown must never start a reading on a screen that is gone
        maybeStopH10();
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        switch (state) {
            case ST_REPORT:
                showReports();
                return;
            case ST_START:
                waitingConnect = false;
                leaveToNewApp();
                return;
            case ST_REPORTS:
            case ST_DEV:
                showHome();
                return;
            case ST_ECG: {
                View c = ecgContainer();
                View back = ecgBackBtn != null ? ecgBackBtn : (c == null ? null : findText(c, BACK_TEXT));
                if (back != null) back.performClick();      // the old handler hides its screen; the tick brings Home back
                else showHome();
                return;
            }
            default:
                super.onBackPressed();
        }
    }

    // ------------------------------------------------------------------ polling

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                onTick();
            } catch (Throwable ignored) {
                // a bad tick must never stop the next one
            }
            ui.postDelayed(this, 500);
        }
    };

    /** The bottom bar belongs on Home and the reports list only. */
    private void updateNav() {
        if (navBar == null) return;
        int vis = (state == ST_HOME || state == ST_REPORTS) ? View.VISIBLE : View.GONE;
        if (navBar.getVisibility() != vis) { navBar.setVisibility(vis); if (navScrim != null) navScrim.setVisibility(vis); }
    }

    void onTick() {
        updateNav();
        updateConnection();
        if (state == ST_ECG) {
            View c = ecgContainer();
            if (c != null && c.getVisibility() != View.VISIBLE) { if (fromNew) leaveToNewApp(); else showHome(); }    // the old ECG screen was closed
            else updateEcgExtras();
        }
        checkNewReport();
        if (state == ST_HOME && tickCount++ % 10 == 0) refreshHome();
    }

    private void updateConnection() {
        boolean conn = isConnected();
        long now = System.currentTimeMillis();
        if (conn) {
            if (connectedSince == 0) connectedSince = now;
        } else {
            connectedSince = 0;
        }
        if (waitingConnect) {
            if (conn && now - connectedSince > 3000) {
                waitingConnect = false;
                openEcgScreen();
            } else if (now - waitStart > 40000) {
                waitingConnect = false;
                toast("Could not connect. Check the band is on, nearby and not connected to another app.");
                if (fromNew && state == ST_START) leaveToNewApp();
            } else if (state == ST_START && startSub != null) {
                startSub.setText(conn ? "Connected. Opening the ECG\u2026" : "Connecting\u2026 " + ((now - waitStart) / 1000) + " s (up to 40 s)");
            }
        }
        if (homeConn == null) return;
        String s = statusText();
        if (waitingConnect || s.startsWith("\u25cf SCAN") || s.startsWith("\u25cf FOUND")
                || s.startsWith("\u25cf CONNECTING") || s.startsWith("\u25cf BONDING")) {
            setConn("\u25cf  Connecting\u2026", AMBER);
        } else if (conn) {
            setConn("\u25cf  Connected", GREEN);
        } else {
            setConn("\u25cb  Not connected", MUTED);
        }
    }

    private void setConn(String text, int color) {
        homeConn.setText(text);
        homeConn.setTextColor(color);
    }

    private void checkNewReport() {
        File dir = getExternalFilesDir(null);
        if (dir == null) return;
        long newest = 0;
        File nf = null;
        for (File f : listReportFiles(dir)) {
            if (f.lastModified() > newest) {
                newest = f.lastModified();
                nf = f;
            }
        }
        if (!reportBaselineSet) {
            reportBaseline = newest;
            reportBaselineSet = true;
            return;
        }
        if (nf != null && newest > reportBaseline) {
            reportBaseline = newest;
            bannerFile = nf;
            bannerPending = true;
            if (state != ST_REPORT && banner != null) banner.setVisibility(View.VISIBLE);
        }
    }

    // ------------------------------------------------------------------ finding things in the old UI

    static View findText(View v, String prefix) {
        if (v instanceof TextView) {
            CharSequence cs = ((TextView) v).getText();
            if (cs != null && cs.toString().startsWith(prefix)) return v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findText(g.getChildAt(i), prefix);
                if (r != null) return r;
            }
        }
        return null;
    }

    /** The old ECG screen: the top-level child of the old UI that holds the marker label. */
    View ecgContainer() {
        if (ecgContainerCache != null) return ecgContainerCache;
        if (legacy == null) return null;
        View marker = findText(legacy, ECG_SCREEN_MARKER);
        if (marker == null) return null;
        View c = marker;
        while (c.getParent() instanceof View && c.getParent() != legacy) c = (View) c.getParent();
        if (c.getParent() == legacy) ecgContainerCache = c;
        return ecgContainerCache;
    }

    private String statusText() {
        if (legacy == null) return "";
        if (statusViewCache == null) {
            View v = findText(legacy, "\u25cb ");
            if (v == null) v = findText(legacy, "\u25cf ");
            statusViewCache = v;
        }
        if (statusViewCache instanceof TextView) {
            CharSequence cs = ((TextView) statusViewCache).getText();
            return cs == null ? "" : cs.toString();
        }
        return "";
    }

    boolean isConnected() {
        return statusText().startsWith("\u25cf CONNECTED");
    }

    private boolean pressOld(String prefix) {
        View b = legacy == null ? null : findText(legacy, prefix);
        if (b == null) return false;
        b.performClick();
        return true;
    }

    // ------------------------------------------------------------------ actions

    private void takeEcg() {
        if (isConnected()) {
            openEcgScreen();
            return;
        }
        waitingConnect = true;
        waitStart = System.currentTimeMillis();
        connectedSince = 0;
        setConn("\u25cf  Connecting\u2026", AMBER);
        if (!pressOld(SCAN_BUTTON_TEXT)) {
            waitingConnect = false;
            toast("Could not find the connect button. Long-press the title for the developer view.");
        }
    }

    private void openEcgScreen() {
        hideLayers();
        if (!pressOld(ECG_BUTTON_PREFIX)) {
            toast("Could not open the ECG screen. Long-press the title for the developer view.");
            showHome();
            return;
        }
        state = ST_ECG;
        restyleEcgOnce();
        fontAll(ecgContainer());
        updateEcgExtras();
        if (fromNew) beginCountdown();
    }

    // ------------------------------------------------------------------ 0.22.0: starting, countdown, leaving

    private FrameLayout buildStart() {
        FrameLayout layer = new FrameLayout(this);
        layer.setBackgroundColor(BG);
        layer.setClickable(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setPadding(dp(28), dp(28), dp(28), dp(28));
        TextView t = tv("ECG", 34, INK, true);
        t.setGravity(Gravity.CENTER);
        col.addView(t, new LinearLayout.LayoutParams(-1, -2));
        startMsg = tv("Getting your strap ready\u2026", 18, INK, true);
        startMsg.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, -2);
        mp.topMargin = dp(18);
        col.addView(startMsg, mp);
        startSub = tv("", 13, MUTED, false);
        startSub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = dp(8);
        col.addView(startSub, sp);
        Button cancel = pillButton("Cancel", CARD, TEXT);
        cancel.setOnClickListener(v -> { waitingConnect = false; leaveToNewApp(); });
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(160), dp(48));
        cp.topMargin = dp(28);
        cp.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(cancel, cp);
        layer.addView(col, new FrameLayout.LayoutParams(-1, -1));
        return layer;
    }

    private void showStarting(String msg, String sub) {
        state = ST_START;
        hideLayers();
        startLayer.setVisibility(View.VISIBLE);
        startMsg.setText(msg);
        startSub.setText(sub);
        maybeStartH10();
    }

    /** 0.23.0: with the setting on ("Record the H10 with each ECG reading") the chest strap is started with the Start flow, so it is connected before the reading; it is left alone if it was already running. */
    private void maybeStartH10() {
        try {
            if (h10StartedHere || !getSharedPreferences("labrador_ecg_control", MODE_PRIVATE).getBoolean("h10_with_reading", false)) return;
            if (PolarH10.wanted) return;
            if (PolarH10.missingPermissions(this).length > 0) { toast("H10: Bluetooth permission is missing. Open Day logger once and allow it."); return; }
            PolarH10.start(this);
            h10StartedHere = true;
        } catch (Throwable ignored) { }
    }

    private void maybeStopH10() {
        if (!h10StartedHere) return;
        h10StartedHere = false;
        try { PolarH10.stop(); } catch (Throwable ignored) { }
    }

    /** Five seconds to get the fingers on, then the same press of the Start button that you would make. Tap the card to cancel; the setting is in Settings. */
    private void beginCountdown() {
        if (!getSharedPreferences("labrador_ecg_control", MODE_PRIVATE).getBoolean("ecg_autostart", true)) return;
        counting = true;
        countLeft = 5;
        sawRunning = false;
        ui.post(this::tickCountdown);
    }

    private void tickCountdown() {
        if (!counting || state != ST_ECG || countdownCard == null) { counting = false; if (countdownCard != null) countdownCard.setVisibility(View.GONE); return; }
        if (ecgStartBtn != null && ecgStartBtn.getText().toString().startsWith("STOP")) { counting = false; countdownCard.setVisibility(View.GONE); return; }
        if (isFinishing() || isDestroyed()) { counting = false; return; }                 // 0.22.1
        if (countLeft <= 0) {
            counting = false;
            countdownCard.setVisibility(View.GONE);
            if (ecgStartBtn != null && ecgStartBtn.isAttachedToWindow()) ecgStartBtn.performClick();
            return;
        }
        countdownCard.setText("Two fingers on the indents, forearm on a table.\nStarting in " + countLeft + "    (tap to cancel)");
        countdownCard.setVisibility(View.VISIBLE);
        countLeft--;
        ui.postDelayed(this::tickCountdown, 1000);
    }

    /** Back from a reading that was started in the new tabs returns to the ECG tab; the old screens stay underneath for "Older screens". */
    private void leaveToNewApp() {
        maybeStopH10();
        boolean ran = sawRunning;
        fromNew = false;
        counting = false;
        waitingConnect = false;
        if (countdownCard != null) countdownCard.setVisibility(View.GONE);
        if (ran) lastReadingEndMs = System.currentTimeMillis();
        showHome();
        HealthActivity.pendingTab = 2;
        startActivity(new android.content.Intent(this, HealthActivity.class).putExtra("tab", 2)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    private void connectOnly() {
        if (isConnected()) {
            toast("Already connected");
            return;
        }
        if (!pressOld(SCAN_BUTTON_TEXT)) toast("Could not find the connect button.");
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }


    // ------------------------------------------------------------------ restyled ECG screen

    /**
     * Restyles the old ECG screen in place (cards, big round Start/Stop, a tips card, markers and research text folded
     * away). It finds the pieces by their position in the old screen and checks each one's type and text first; if
     * anything does not match it leaves the old look untouched, so the recording itself can never be affected.
     */
    private static String fontCss;

    /** 0.15.2: the report is plain HTML; give the WebView the app's Outfit font (not stored in the saved file, which falls back to the system font). */
    private String withFont(String html) {
        try {
            if (fontCss == null) {
                java.io.File ff = OutfitData.file(this);
                byte[] b = new byte[(int) ff.length()];
                java.io.FileInputStream in = new java.io.FileInputStream(ff);
                try { int off = 0, k; while (off < b.length && (k = in.read(b, off, b.length - off)) > 0) off += k; } finally { in.close(); }
                fontCss = "<style>@font-face{font-family:'Outfit';font-weight:100 900;src:url(data:font/ttf;base64," + android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP) + ")}</style>";
            }
            int i = html.indexOf("</head>");
            return i < 0 ? html : html.substring(0, i) + fontCss + html.substring(i);
        } catch (Throwable t) { return html; }
    }

    /** 0.15.2: Outfit on every text of the old ECG screen (log lines in a monospace font keep theirs). */
    private void fontAll(View v) {
        if (v == null) return;
        try {
            if (v instanceof TextView) {
                TextView t = (TextView) v;
                Typeface cur = t.getTypeface();
                float sp = t.getTextSize() / getResources().getDisplayMetrics().scaledDensity;
                if (cur != Typeface.MONOSPACE) t.setTypeface(HealthViews.font(this, sp >= 30f ? 300 : cur != null && cur.isBold() ? 600 : 400));   // big figures light, like Today
                int col = t.getCurrentTextColor();
                if (col == 0xFF39FF6A) t.setTextColor(GREEN);                                                       // the old neon green
                else if (col == 0xFF8FA1BD || col == 0xFFB8C4D9 || col == 0xFF5A6B85) t.setTextColor(MUTED);
                else if (col == 0xFFEAF2FF) t.setTextColor(TEXT);
                else if (col == 0xFFFF5555 || col == 0xFFFF6B6B) t.setTextColor(RED);
                else if (v instanceof Button && col == 0xFFFFFFFF) t.setTextColor(INK);                            // dark text on the bright pill, as everywhere else
            }
            if (v instanceof ViewGroup) { ViewGroup g = (ViewGroup) v; for (int i = 0; i < g.getChildCount(); i++) fontAll(g.getChildAt(i)); }
        } catch (Throwable ignored) { }
    }

    private void restyleEcgOnce() {
        if (ecgStyled) return;
        try {
            View c = ecgContainer();
            if (!(c instanceof ViewGroup)) return;
            View sv = ((ViewGroup) c).getChildAt(0);
            if (!(sv instanceof ViewGroup) || !(((ViewGroup) sv).getChildAt(0) instanceof LinearLayout)) return;
            LinearLayout col = (LinearLayout) ((ViewGroup) sv).getChildAt(0);
            if (col.getChildCount() < 19 || !(col.getChildAt(0) instanceof LinearLayout) || !(col.getChildAt(2) instanceof TextView)
                    || !(col.getChildAt(3) instanceof TextView) || !(col.getChildAt(5) instanceof LinearLayout)
                    || !(col.getChildAt(7) instanceof LinearLayout) || !(col.getChildAt(9) instanceof Button)
                    || !(col.getChildAt(11) instanceof TextView) || !(col.getChildAt(16) instanceof TextView)
                    || !(col.getChildAt(17) instanceof TextView) || !(col.getChildAt(18) instanceof TextView)) return;
            if (!((TextView) col.getChildAt(11)).getText().toString().startsWith(ECG_SCREEN_MARKER)) return;
            String bt = ((Button) col.getChildAt(9)).getText().toString();
            if (!bt.startsWith("START") && !bt.startsWith("STOP")) return;
            styleEcgColumn(c, col);
            ecgStyled = true;
        } catch (Throwable ignored) {
            // keep the old look
        }
    }

    private void styleEcgColumn(View container, LinearLayout col) {
        container.setBackgroundColor(BG);
        col.setPadding(dp(18), dp(36), dp(18), dp(24));

        // keep references first: inserting views below shifts the positions
        LinearLayout top = (LinearLayout) col.getChildAt(0);
        TextView status = (TextView) col.getChildAt(2);
        TextView verdict = (TextView) col.getChildAt(3);
        LinearLayout waveCard = (LinearLayout) col.getChildAt(5);
        LinearLayout stats = (LinearLayout) col.getChildAt(7);
        ecgStartBtn = (Button) col.getChildAt(9);
        View markLabel = col.getChildAt(11), markRow1 = col.getChildAt(12), markRow2 = col.getChildAt(13), cuff = col.getChildAt(14);
        TextView rhythm = (TextView) col.getChildAt(16);
        ecgStoredView = (TextView) col.getChildAt(17);
        TextView disclaimer = (TextView) col.getChildAt(18);

        // top row: round back arrow, title, mode chip
        if (top.getChildCount() >= 3 && top.getChildAt(0) instanceof Button && top.getChildAt(1) instanceof TextView
                && top.getChildAt(2) instanceof Button) {
            ecgBackBtn = (Button) top.getChildAt(0);
            ecgBackBtn.setText("\u2039");
            ecgBackBtn.setAllCaps(false);
            ecgBackBtn.setTextSize(26);
            ecgBackBtn.setTextColor(TEXT);
            ecgBackBtn.setBackground(round(CARD, 22));
            ecgBackBtn.setMinWidth(0);
            ecgBackBtn.setMinimumWidth(0);
            ecgBackBtn.setMinHeight(0);
            ecgBackBtn.setMinimumHeight(0);
            ecgBackBtn.setPadding(0, 0, 0, 0);
            ecgBackBtn.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
            TextView heading = (TextView) top.getChildAt(1);
            heading.setTextColor(TEXT);
            heading.setPadding(dp(8), 0, 0, 0);
            Button mode = (Button) top.getChildAt(2);
            mode.setAllCaps(false);
            mode.setTextSize(12);
            mode.setTextColor(MUTED);
            mode.setBackground(round(CARD, 16));
            mode.setMinWidth(0);
            mode.setMinimumWidth(0);
            mode.setMinHeight(dp(36));
            mode.setMinimumHeight(dp(36));
            mode.setPadding(dp(12), 0, dp(12), 0);
        }

        status.setTextSize(16);
        status.setTextColor(TEXT);
        status.setBackground(round(CARD, 16));
        status.setPadding(dp(16), dp(14), dp(16), dp(14));
        verdict.setTextSize(13);
        verdict.setTextColor(MUTED);

        waveCard.setBackground(round(CARD, 18));
        waveCard.setPadding(dp(8), dp(8), dp(8), dp(8));

        for (int i = 0; i < stats.getChildCount(); i++) {
            View blk = stats.getChildAt(i);
            blk.setBackground(round(CARD, 18));
            blk.setPadding(dp(8), dp(12), dp(8), dp(12));
            if (blk.getLayoutParams() instanceof LinearLayout.LayoutParams) {
                ((LinearLayout.LayoutParams) blk.getLayoutParams()).setMargins(dp(4), 0, dp(4), 0);
            }
        }

        ecgStartBtn.setAllCaps(false);
        ecgStartBtn.setTextSize(20);
        ecgStartBtn.setMinHeight(dp(68));
        ecgStartBtn.setMinimumHeight(dp(68));
        ecgStartBtn.setBackground(round(PRIMARY, 34));
        lastRunningState = -1;

        // tips card above the button (idle only)
        ecgTips = tv("Rest your forearm on a table  \u00b7  hold steady contact with both fingers  \u00b7  stay on for a minute or more",
                13, MUTED, false);
        ecgTips.setGravity(Gravity.CENTER);
        ecgTips.setPadding(dp(8), dp(4), dp(8), dp(10));
        col.addView(ecgTips, col.indexOfChild(ecgStartBtn), new LinearLayout.LayoutParams(-1, -2));

        // markers folded away (still work when opened)
        markViews = new View[]{markLabel, markRow1, markRow2, cuff};
        for (View v : markViews) v.setVisibility(View.GONE);
        markToggle = pillButton("Markers  \u25be", CARD, MUTED);
        markToggle.setTextSize(13);
        markToggle.setOnClickListener(v -> toggle(markToggle, markViews, "Markers"));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, dp(44));
        mp.topMargin = dp(12);
        col.addView(markToggle, col.indexOfChild(markLabel), mp);

        // 0.22.2: the packets arriving (counters per kind, the newest lines), on the reading screen; opens by itself when a reading starts
        packetsView = tv("", 10, TEXT, false);                      // 0.25.0: compact log, 10 sp monospace, tight lines
        packetsView.setTypeface(android.graphics.Typeface.MONOSPACE);
        packetsView.setLineSpacing(0, 0.92f);
        packetsView.setPadding(dp(10), dp(6), dp(10), dp(6));
        packetsView.setBackground(round(CARD, 16));
        packetsView.setVisibility(View.GONE);
        packetsView.setOnClickListener(v -> startActivity(new android.content.Intent(this, LiveFramesActivity.class)));
        packetsToggle = pillButton("Packets  \u25be", CARD, MUTED);
        packetsToggle.setTextSize(13);
        packetsToggle.setOnClickListener(v -> openPackets(packetsView.getVisibility() != View.VISIBLE));
        LinearLayout.LayoutParams ptp = new LinearLayout.LayoutParams(-1, dp(44));
        ptp.topMargin = dp(12);
        LinearLayout.LayoutParams pvp = new LinearLayout.LayoutParams(-1, -2);
        pvp.topMargin = dp(6);
        int pidx = col.indexOfChild(markToggle);
        col.addView(packetsView, pidx, pvp);
        col.addView(packetsToggle, pidx, ptp);

        // "preparing your report" line, and the research text folded away
        ecgPrep = tv("Preparing your report\u2026 this takes 1 to 3 minutes. A green banner appears when it is ready.", 14, AMBER, false);
        ecgPrep.setGravity(Gravity.CENTER);
        ecgPrep.setPadding(dp(8), dp(12), dp(8), dp(4));
        ecgPrep.setVisibility(View.GONE);
        col.addView(ecgPrep, col.indexOfChild(rhythm), new LinearLayout.LayoutParams(-1, -2));
        detailViews = new View[]{rhythm, ecgStoredView};
        for (View v : detailViews) v.setVisibility(View.GONE);
        detailsToggle = pillButton("Research details  \u25be", CARD, MUTED);
        detailsToggle.setTextSize(13);
        detailsToggle.setOnClickListener(v -> toggle(detailsToggle, detailViews, "Research details"));
        LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(-1, dp(44));
        dp2.topMargin = dp(8);
        col.addView(detailsToggle, col.indexOfChild(rhythm), dp2);

        disclaimer.setTextColor(MUTED);
        disclaimer.setTextSize(11);
    }

    // 0.25.1: a short buzz and a note when the fingers come off the clasp during a reading, or when there is no contact after 14 s
    private int lastWarnRun; private boolean warnedNoContact;
    private void contactAlert(String m) { try { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); if (ecgStartBtn != null) ecgStartBtn.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); } catch (Throwable ignored) { } }
    private final Runnable contactTick = new Runnable() {
        @Override public void run() {
            boolean running = ecgStartBtn != null && ecgStartBtn.getText().toString().startsWith("STOP");
            if (!running || isFinishing()) { warnedNoContact = false; lastWarnRun = 0; return; }
            int run = Live500.openRun;
            if (run >= 3 && lastWarnRun < 3) contactAlert("Fingers are off the clasp: put them back");
            lastWarnRun = run;
            if (!Live500.everOn && System.currentTimeMillis() - Live500.startMs > 14000L && !warnedNoContact) { warnedNoContact = true; contactAlert("No finger contact yet: put two fingers on the clasp"); }
            ui.postDelayed(this, 1000L);
        }
    };

    private final Runnable packetsTick = new Runnable() {
        @Override public void run() {
            if (packetsView == null || state != ST_ECG || packetsView.getVisibility() != View.VISIBLE) return;
            refreshPackets();
            ui.postDelayed(this, 300L);
        }
    };

    /** 0.22.2: the live packet kinds (43 raw and filtered ECG, 40 heart rate, 48 events) with a green dot while they arrive, and the newest lines. Tap for the full Live frames screen. */
    private void openPackets(boolean open) {
        if (packetsView == null) return;
        packetsView.setVisibility(open ? View.VISIBLE : View.GONE);
        packetsToggle.setText("Packets" + (open ? "  \u25b4" : "  \u25be"));
        ui.removeCallbacks(packetsTick);
        if (open) { refreshPackets(); ui.postDelayed(packetsTick, 300L); }
    }

    private void refreshPackets() {
        long now = System.currentTimeMillis();
        android.text.SpannableStringBuilder t = new android.text.SpannableStringBuilder();
        if (PolarH10.wanted) { int hh = PolarH10.hrNow(); t.append("H10 chest strap: " + (hh > 0 ? hh + " bpm" : "connecting...") + (PolarH10.recordToFile ? "  (recording)" : "") + "\n"); }
        for (FrameMonitor.RowInfo i : FrameMonitor.rows(now)) {
            if (i.type != 43 && i.type != 40 && i.type != 48) continue;
            int start = t.length();
            t.append(String.format(java.util.Locale.UK, "%s %s  %.1f/s  %d\n", i.active ? "\u25CF" : "\u25CB", i.name, i.perSecond, i.total));
            t.setSpan(new android.text.style.ForegroundColorSpan(i.active ? 0xFF2EE6A8 : MUTED), start, start + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (i.last != null && !i.last.isEmpty()) {
                int s2 = t.length();
                t.append("  " + i.last + "\n");
                t.setSpan(new android.text.style.ForegroundColorSpan(MUTED), s2, t.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        if (t.length() == 0) t.append("No packet yet. Frames arrive about once a second while the link is up.");
        else {
            t.append("Newest first:\n");
            for (String s : FrameMonitor.lines(false, 3)) t.append(s).append('\n');
        }
        t.append("Tap for the full list");
        packetsView.setText(t);
    }

    private void toggle(Button b, View[] views, String label) {
        boolean open = views[0].getVisibility() != View.VISIBLE;
        for (View v : views) v.setVisibility(open ? View.VISIBLE : View.GONE);
        b.setText(label + (open ? "  \u25b4" : "  \u25be"));
    }

    /** Keeps the restyled screen in step with the old one: tips only when idle, button colour, the report line. */
    private void updateEcgExtras() {
        if (state == ST_ECG) fontAll(ecgContainer());          // 0.15.2: late-added texts get the app font too
        if (!ecgStyled || ecgStartBtn == null) return;
        boolean running = ecgStartBtn.getText().toString().startsWith("STOP");
        if (running) sawRunning = true;
        int rs = running ? 1 : 0;
        if (rs != lastRunningState) {                       // the old code resets the colour whenever it flips the button
            int prevRs = lastRunningState;
            lastRunningState = rs;
            if (!running && prevRs == 1) ui.postDelayed(new Runnable() { @Override public void run() { maybeStopH10(); } }, 6000L);       // 0.23.0: the H10 stops a few seconds after the reading
            ecgStartBtn.setBackground(round(running ? RED : PRIMARY, 34));
            if (running) { Live500.resetContact(); lastWarnRun = 0; warnedNoContact = false; ui.removeCallbacks(contactTick); ui.postDelayed(contactTick, 1000L); }
            if (ecgTips != null) ecgTips.setVisibility(running ? View.GONE : View.VISIBLE);
            if (running && packetsView != null && packetsView.getVisibility() != View.VISIBLE
                    && getSharedPreferences("labrador_ecg_control", MODE_PRIVATE).getBoolean("ecg_packets_open", true)) openPackets(true);
        }
        if (ecgPrep != null && ecgStoredView != null) {
            boolean fetching = ecgStoredView.getText().toString().contains("Fetching the stored");
            ecgPrep.setVisibility(fetching ? View.VISIBLE : View.GONE);
        }
    }

    // ------------------------------------------------------------------ screen switching

    private void hideLayers() {
        if (startLayer != null) startLayer.setVisibility(View.GONE);
        homeLayer.setVisibility(View.GONE);
        reportsLayer.setVisibility(View.GONE);
        reportLayer.setVisibility(View.GONE);
        devPill.setVisibility(View.GONE);
        banner.setVisibility(View.GONE);
    }

    void showHome() {
        state = ST_HOME;
        hideLayers();
        homeLayer.setVisibility(View.VISIBLE);
        refreshHome();
        updateConnection();
        if (bannerFile != null && bannerFile.exists() && bannerPending) banner.setVisibility(View.VISIBLE);
    }

    private boolean bannerPending = true;

    void showReports() {
        state = ST_REPORTS;
        hideLayers();
        bannerPending = false;
        fillReports();
        reportsLayer.setVisibility(View.VISIBLE);
    }

    /** 0.19.2: reports open in ReportActivity, which runs in its own process. This screen must never create a WebView: a WebView in the logger's process gets it killed
     *  whenever Google updates Android System WebView (27 Oct 00:06:47). */
    void openReport(File f) {
        if (f == null || !f.exists()) {
            toast("Could not read that report.");
            return;
        }
        bannerPending = false;
        startActivity(new Intent(this, ReportActivity.class).putExtra("path", f.getAbsolutePath()));
    }

    private void showDev() {
        state = ST_DEV;
        hideLayers();
        devPill.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ Home

    private FrameLayout buildHome() {
        FrameLayout layer = new FrameLayout(this);
        layer.setBackgroundColor(BG);
        layer.setClickable(true);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(24), dp(44), dp(24), dp(120));

        TextView title = tv("ECG", 34, TEXT, true);
        title.setOnLongClickListener(v -> {
            showDev();
            return true;
        });
        col.addView(title, new LinearLayout.LayoutParams(-2, -2));
        col.addView(tv("A single-lead reading from your WHOOP MG", 14, MUTED, false), new LinearLayout.LayoutParams(-2, -2));

        homeConn = tv("\u25cb  Not connected", 14, MUTED, true);
        homeConn.setPadding(dp(14), dp(8), dp(14), dp(8));
        homeConn.setBackground(round(CARD, 20));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
        cp.topMargin = dp(16);
        col.addView(homeConn, cp);

        homeLatestCard = new LinearLayout(this);
        homeLatestCard.setOrientation(LinearLayout.VERTICAL);
        homeLatestCard.setPadding(dp(18), dp(16), dp(18), dp(16));
        homeLatestCard.setBackground(round(CARD, 18));
        homeLatestTitle = tv("LATEST READING", 11, MUTED, true);
        homeLatestBody = tv("", 17, TEXT, true);
        homeLatestCard.addView(homeLatestTitle, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
        bp.topMargin = dp(6);
        homeLatestCard.addView(homeLatestBody, bp);
        homeLatestCard.setOnClickListener(v -> {
            if (latestFile != null) openReport(latestFile);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(28);
        col.addView(homeLatestCard, lp);

        homeGlanceCard = new LinearLayout(this);
        homeGlanceCard.setOrientation(LinearLayout.VERTICAL);
        homeGlanceCard.setPadding(dp(18), dp(16), dp(18), dp(16));
        homeGlanceCard.setBackground(round(CARD, 18));
        homeGlanceCard.addView(tv("TODAY ON YOUR FEET  \u00b7  TAP FOR THE DAY", 11, MUTED, true), new LinearLayout.LayoutParams(-2, -2));
        homeGlanceBody = tv("\u2014", 17, TEXT, true);
        LinearLayout.LayoutParams gbp = new LinearLayout.LayoutParams(-2, -2);
        gbp.topMargin = dp(6);
        homeGlanceCard.addView(homeGlanceBody, gbp);
        homeGlanceCard.setOnClickListener(v -> startActivity(new android.content.Intent(this, ActivityActivity.class)));
        LinearLayout.LayoutParams gcp = new LinearLayout.LayoutParams(-1, -2);
        gcp.topMargin = dp(12);
        col.addView(homeGlanceCard, gcp);

        homeWarn = tv("", 13, AMBER, true);
        homeWarn.setPadding(dp(14), dp(10), dp(14), dp(10));
        homeWarn.setBackground(round(CARD, 14));
        homeWarn.setVisibility(View.GONE);
        homeWarn.setOnClickListener(v -> connectOnly());
        LinearLayout.LayoutParams hwp = new LinearLayout.LayoutParams(-1, -2);
        hwp.topMargin = dp(8);
        col.addView(homeWarn, hwp);

        Button today = pillButton("Today  \u00b7  sleep, recovery, effort, SpO2", CARD, TEXT);
        today.setOnClickListener(v -> startActivity(new android.content.Intent(this, TodayActivity.class)));
        LinearLayout.LayoutParams tdp = new LinearLayout.LayoutParams(-1, dp(56));
        tdp.topMargin = dp(24);
        col.addView(today, tdp);

        Button activity = pillButton("Activity  \u00b7  work, effort, day by day", CARD, TEXT);
        activity.setOnClickListener(v -> startActivity(new android.content.Intent(this, ActivityActivity.class)));
        LinearLayout.LayoutParams acp = new LinearLayout.LayoutParams(-1, dp(56));
        acp.topMargin = dp(12);
        col.addView(activity, acp);

        Button take = pillButton("Take an ECG", PRIMARY, INK);
        take.setTextSize(20);
        take.setOnClickListener(v -> takeEcg());
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, dp(68));
        tp.topMargin = dp(12);
        col.addView(take, tp);

        Button reports = pillButton("All reports", CARD, TEXT);
        reports.setOnClickListener(v -> showReports());
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, dp(56));
        rp.topMargin = dp(12);
        col.addView(reports, rp);

        Button dayLog = pillButton("Day logger", CARD, TEXT);
        dayLog.setOnClickListener(v -> openDayLog());
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, dp(56));
        dlp.topMargin = dp(12);
        col.addView(dayLog, dlp);

        Button exportBtn = pillButton("Export ECG data for analysis", CARD, TEXT);
        exportBtn.setOnClickListener(v -> askExport());
        LinearLayout.LayoutParams exp = new LinearLayout.LayoutParams(-1, dp(56));
        exp.topMargin = dp(12);
        col.addView(exportBtn, exp);
        dayLogStatus = tv("", 12, MUTED, false);
        dayLogStatus.setGravity(Gravity.CENTER);
        dayLogStatus.setOnClickListener(v -> openDayLog());
        LinearLayout.LayoutParams dsp = new LinearLayout.LayoutParams(-1, -2);
        dsp.topMargin = dp(6);
        col.addView(dayLogStatus, dsp);

        Button connect = pillButton("Connect to band", BG, MUTED);
        connect.setOnClickListener(v -> connectOnly());
        LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(-1, dp(48));
        kp.topMargin = dp(4);
        col.addView(connect, kp);

        TextView foot = tv("Research instrumentation. Not a medical device and not a diagnosis. "
                + "It cannot detect or rule out any heart condition.", 12, MUTED, false);
        foot.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2);
        fp.topMargin = dp(20);
        col.addView(foot, fp);

        sv.addView(col, new FrameLayout.LayoutParams(-1, -2));
        layer.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        return layer;
    }

    private void openDayLog() {
        startActivity(new android.content.Intent(this, DayLogActivity.class));
    }

    private void refreshDayLogLine() {
        if (dayLogStatus == null) return;
        try {
            dayLogStatus.setText((DayLog.enabled(this) ? "Logger ON  \u00b7  " : "Logger OFF  \u00b7  ") + DayLog.statusLine(this));
        } catch (Throwable t) {
            dayLogStatus.setText("");
        }
    }

    private volatile boolean exporting;

    /** One tap: everything, the last 30 days or the last 7 days. Writes zips of about 240 MB to Download/MGexport and says when it is done. */
    private void askExport() {
        if (exporting) {
            android.widget.Toast.makeText(this, "An export is already running.", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] items = {"Everything", "Last 30 days", "Last 7 days"};
        final int[] days = {0, 30, 7};
        new android.app.AlertDialog.Builder(this).setTitle("Export ECG data for analysis")
                .setItems(items, (d, w) -> runExport(days[w]))
                .setNegativeButton("Cancel", null).show();
    }

    private void runExport(final int days) {
        exporting = true;
        final android.content.Context app = getApplicationContext();
        android.widget.Toast.makeText(this, "Exporting in the background. You can keep using the app.", android.widget.Toast.LENGTH_LONG).show();
        new Thread(() -> {
            final EcgExport.Result r = EcgExport.run(app, days, null);
            ui.post(() -> {
                exporting = false;
                if (isFinishing()) return;
                new android.app.AlertDialog.Builder(this).setTitle(r.ok ? "Export finished" : "Export problem")
                        .setMessage(r.message + (r.ok ? "\n\nOpen your Files app, then Downloads, then MGexport. These zips hold your heart recordings: share them only with people you trust." : ""))
                        .setPositiveButton("OK", null).show();
            });
        }, "ecg-export").start();
    }

    /** Today's active time from the day log, and a warning when the logger is on but no strap data has arrived for a while. Off the main thread. */
    private void refreshGlance() {
        if (glanceLoading || homeGlanceBody == null) return;
        glanceLoading = true;
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            String body = null, warn = null;
            try {
                ActivityStore.DayData d = ActivityStore.glanceDay(app);
                if (d != null && d.hasData && d.r != null) {
                    body = "Active " + glanceHm(d.r.activeMin) + "  \u00b7  longest " + (d.r.longestBoutMin == 0 ? "\u2014" : glanceHm(d.r.longestBoutMin))
                            + "  \u00b7  load " + Math.round(d.r.load);
                } else body = "No logged data yet today";
                if (DayLog.enabled(app)) {
                    long m = ActivityStore.staleMinutes(app);
                    if (m >= KeepAlive.STALE_MIN) warn = "No strap data for " + (m >= 120 ? (m / 60) + " h" : m + " min") + ". The logger may have stopped. Tap to reconnect.";
                }
            } catch (Throwable ignored) {
                // the glance is optional
            }
            final String b = body, w = warn;
            ui.post(() -> {
                glanceLoading = false;
                if (homeGlanceBody != null && b != null) homeGlanceBody.setText(b);
                if (homeWarn != null) {
                    homeWarn.setText(w == null ? "" : w);
                    homeWarn.setVisibility(w == null ? View.GONE : View.VISIBLE);
                }
            });
        }, "home-glance").start();
    }

    private static String glanceHm(int min) {
        return min < 60 ? min + " m" : (min / 60) + ":" + String.format(Locale.UK, "%02d", min % 60);
    }

    private void refreshHome() {
        refreshDayLogLine();
        refreshGlance();
        List<Rep> reps = loadReports();
        if (reps.isEmpty()) {
            latestFile = null;
            homeLatestTitle.setText("NO READINGS YET");
            homeLatestBody.setText("Take your first ECG. Your report will appear here.");
            homeLatestBody.setTextSize(15);
            return;
        }
        Rep r = reps.get(0);
        latestFile = r.file;
        homeLatestTitle.setText("LATEST READING  \u00b7  TAP TO OPEN");
        homeLatestBody.setTextSize(17);
        homeLatestBody.setText(r.dateText + "\n" + r.summary());
    }

    // ------------------------------------------------------------------ Reports list

    private FrameLayout buildReports() {
        FrameLayout layer = new FrameLayout(this);
        layer.setBackgroundColor(BG);
        layer.setClickable(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(topBar("Reports", null, v -> showHome(), null), new LinearLayout.LayoutParams(-1, -2));
        ScrollView sv = new ScrollView(this);
        reportsList = new LinearLayout(this);
        reportsList.setOrientation(LinearLayout.VERTICAL);
        reportsList.setPadding(dp(16), dp(4), dp(16), dp(120));
        sv.addView(reportsList, new FrameLayout.LayoutParams(-1, -2));
        col.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        layer.addView(col, new FrameLayout.LayoutParams(-1, -1));
        return layer;
    }

    private void fillReports() {
        reportsList.removeAllViews();
        List<Rep> reps = loadReports();
        if (reps.isEmpty()) {
            TextView m = tv("No reports yet. Take an ECG and the report appears here a minute or two after you stop.", 15, MUTED, false);
            m.setPadding(dp(8), dp(16), dp(8), dp(8));
            reportsList.addView(m, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        for (final Rep r : reps) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(14), dp(14), dp(14));
            row.setBackground(round(CARD, 18));
            TextView g = tv(r.grade, 20, INK, true);
            g.setGravity(Gravity.CENTER);
            g.setBackground(round(r.gradeColor(), 24));
            row.addView(g, new LinearLayout.LayoutParams(dp(48), dp(48)));
            LinearLayout mid = new LinearLayout(this);
            mid.setOrientation(LinearLayout.VERTICAL);
            mid.setPadding(dp(14), 0, dp(8), 0);
            mid.addView(tv(r.dateText, 16, TEXT, true), new LinearLayout.LayoutParams(-2, -2));
            mid.addView(tv(r.summary(), 13, MUTED, false), new LinearLayout.LayoutParams(-2, -2));
            row.addView(mid, new LinearLayout.LayoutParams(0, -2, 1f));
            row.addView(tv("\u203a", 26, MUTED, false), new LinearLayout.LayoutParams(-2, -2));
            row.setOnClickListener(v -> openReport(r.file));
            row.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setTitle("Delete this report?")
                        .setMessage(r.dateText)
                        .setPositiveButton("Delete", (d, w) -> {
                            r.file.delete();
                            fillReports();
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(10);
            reportsList.addView(row, lp);
        }
    }

    // ------------------------------------------------------------------ one report

    private FrameLayout buildReport() {
        // 0.19.2: no WebView here any more (see openReport); the layer stays hidden and only keeps the layout code that still refers to it
        FrameLayout layer = new FrameLayout(this);
        layer.setBackgroundColor(BG);
        layer.setClickable(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bar = topBar("Report", null, v -> showReports(), null);
        reportTitle = (TextView) bar.getTag();
        col.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        layer.addView(col, new FrameLayout.LayoutParams(-1, -1));
        return layer;
    }

    /** Back arrow, title, optional action button. The title view is stored in the bar's tag. */
    private LinearLayout topBar(String title, String action, View.OnClickListener back, View.OnClickListener act) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(36), dp(12), dp(8));
        TextView arrow = tv("\u2039", 30, TEXT, false);
        arrow.setGravity(Gravity.CENTER);
        arrow.setBackground(round(CARD, 22));
        arrow.setOnClickListener(back);
        bar.addView(arrow, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView t = tv(title, 20, TEXT, true);
        t.setPadding(dp(14), 0, 0, 0);
        bar.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        bar.setTag(t);
        if (action != null) {
            Button b = pillButton(action, CARD, TEXT);
            b.setTextSize(14);
            b.setOnClickListener(act);
            bar.addView(b, new LinearLayout.LayoutParams(-2, dp(44)));
        }
        return bar;
    }

    // ------------------------------------------------------------------ report files

    static final class Rep {
        File file;
        long timeMs;
        String dateText = "", grade = "?", hr = "-", hrv = "-", rhythm = "-";

        int gradeColor() {
            switch (grade.isEmpty() ? '?' : grade.charAt(0)) {
                case 'A':
                case 'B':
                    return GREEN;
                case 'C':
                    return AMBER;
                case 'D':
                    return RED;
                default:
                    return MUTED;
            }
        }

        String summary() {
            StringBuilder s = new StringBuilder();
            if (!"-".equals(hr)) s.append("HR ").append(hr).append(" bpm");
            if (!"-".equals(hrv)) s.append(s.length() > 0 ? "  \u00b7  " : "").append("HRV ").append(hrv).append(" ms");
            if (!"-".equals(rhythm)) s.append(s.length() > 0 ? "  \u00b7  " : "").append(rhythm);
            return s.length() == 0 ? "Open to see why" : s.toString();
        }
    }

    static List<File> listReportFiles(File dir) {
        List<File> out = new ArrayList<>();
        File[] all = dir.listFiles();
        if (all == null) return out;
        for (File f : all) {
            String n = f.getName();
            if (n.startsWith("ecg_report_") && n.endsWith(".html") && !isFragment(f)) out.add(f);
        }
        return out;
    }

    /** 0.15.3: the late tail of a reading ("0 of 3 seconds were clean enough") is not a reading; the Reports screen lists the files itself. */
    static boolean isFragment(File f) {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                byte[] b = new byte[9000];
                int k = in.read(b);
                int tot = HealthModel.fragmentSeconds(k <= 0 ? "" : new String(b, 0, k, "UTF-8"));
                return tot >= 0 && tot < 20;
            } finally { in.close(); }
        } catch (Throwable t) { return false; }
    }

    Rep describe(File f) {
        Rep r = new Rep();
        r.file = f;
        long t = f.lastModified();
        try {
            String n = f.getName();
            long unix = Long.parseLong(n.substring("ecg_report_".length(), n.length() - ".html".length()));
            if (unix >= 1672531200L && unix <= 1924992000L) t = unix * 1000L;
        } catch (Exception ignored) {
            // keep the file's own time
        }
        r.timeMs = t;
        r.dateText = new SimpleDateFormat("EEE d MMM, HH:mm", Locale.UK).format(new Date(t));
        String head = readHead(f, 2500);
        String g = meta(head, "ecg-grade");
        if (g != null) r.grade = g;
        String v = meta(head, "ecg-hr");
        if (v != null) r.hr = v;
        v = meta(head, "ecg-hrv");
        if (v != null) r.hrv = v;
        v = meta(head, "ecg-rhythm");
        if (v != null) r.rhythm = v;
        return r;
    }

    List<Rep> loadReports() {
        List<Rep> out = new ArrayList<>();
        File dir = getExternalFilesDir(null);
        if (dir == null) return out;
        for (File f : listReportFiles(dir)) out.add(describe(f));
        Collections.sort(out, new Comparator<Rep>() {
            @Override
            public int compare(Rep a, Rep b) {
                return Long.compare(b.timeMs, a.timeMs);
            }
        });
        return out;
    }

    static String meta(String head, String name) {
        Matcher m = Pattern.compile("name=\"" + name + "\" content=\"([^\"]*)\"").matcher(head);
        return m.find() ? m.group(1) : null;
    }

    static String readHead(File f, int n) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[n];
            int k = in.read(b);
            return k <= 0 ? "" : new String(b, 0, k, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    static String readAll(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int k;
            while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ small view helpers

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (color == CARD) g.setStroke(Math.max(1, dp(1)), 0x0FFFFFFF);      // the hairline edge the other screens give their cards
        return g;
    }

    private TextView tv(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(HealthViews.font(this, bold ? 600 : 400));
        return t;
    }

    private Button pillButton(String s, int bg, int fg) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTextColor(fg);
        b.setTypeface(HealthViews.font(this, 600));
        b.setBackground(round(bg, 28));
        b.setMinHeight(dp(44));
        b.setMinimumHeight(dp(44));
        return b;
    }
}
