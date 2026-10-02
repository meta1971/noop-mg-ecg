package com.noop.mgecg;

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
import android.webkit.WebSettings;
import android.webkit.WebView;
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

    static final int BG = 0xFF0B0F14, CARD = 0xFF131A23, LINE = 0xFF243244, TEXT = 0xFFE8EEF5,
            MUTED = 0xFFA3B2C5, GREEN = 0xFF3DDC97, AMBER = 0xFFFFB454, RED = 0xFFFF7A7A, INK = 0xFF06251A;

    static final int ST_HOME = 0, ST_REPORTS = 1, ST_REPORT = 2, ST_ECG = 3, ST_DEV = 4;

    private static final String ECG_BUTTON_PREFIX = "\u2764";           // the heart on the old ECG button
    private static final String SCAN_BUTTON_TEXT = "SCAN / CONNECT";
    private static final String ECG_SCREEN_MARKER = "MARK WHAT YOU ARE DOING";
    private static final String BACK_TEXT = "< BACK";

    private final Handler ui = new Handler(Looper.getMainLooper());

    View legacy;                       // the old UI, kept underneath
    int state = ST_HOME;

    private FrameLayout homeLayer, reportsLayer, reportLayer;
    private LinearLayout reportsList, homeLatestCard;
    private TextView homeConn, homeLatestTitle, homeLatestBody, reportTitle, banner;
    private Button devPill;
    private WebView webView;

    private File bannerFile, openFile, latestFile;
    private long reportBaseline;
    private boolean reportBaselineSet;
    private boolean waitingConnect;
    private long waitStart, connectedSince;
    private View ecgContainerCache, statusViewCache;
    private int tickCount;

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

        devPill = pillButton("HOME", CARD, TEXT);
        devPill.setTextSize(14);
        devPill.setVisibility(View.GONE);
        devPill.setOnClickListener(v -> showHome());
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(-2, dp(44), Gravity.BOTTOM | Gravity.END);
        pl.setMargins(0, 0, dp(12), dp(12));
        root.addView(devPill, pl);

        banner = tv("Your report is ready  \u00b7  View", 15, INK, true);
        banner.setGravity(Gravity.CENTER);
        banner.setPadding(dp(16), dp(14), dp(16), dp(14));
        banner.setBackground(round(GREEN, 16));
        banner.setVisibility(View.GONE);
        banner.setOnClickListener(v -> {
            if (bannerFile != null) openReport(bannerFile);
        });
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        bl.setMargins(dp(16), 0, dp(16), dp(16));
        root.addView(banner, bl);

        super.setContentView(root);
        showHome();
        ui.removeCallbacks(tick);
        ui.postDelayed(tick, 500);
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(tick);
        try {
            if (webView != null) webView.destroy();
        } catch (Throwable ignored) {
            // shutting down anyway
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        switch (state) {
            case ST_REPORT:
                showReports();
                return;
            case ST_REPORTS:
            case ST_DEV:
                showHome();
                return;
            case ST_ECG: {
                View c = ecgContainer();
                View back = c == null ? null : findText(c, BACK_TEXT);
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

    void onTick() {
        updateConnection();
        if (state == ST_ECG) {
            View c = ecgContainer();
            if (c != null && c.getVisibility() != View.VISIBLE) showHome();    // the old ECG screen was closed
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

    // ------------------------------------------------------------------ screen switching

    private void hideLayers() {
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

    void openReport(File f) {
        String html = readAll(f);
        if (html == null) {
            toast("Could not read that report.");
            return;
        }
        state = ST_REPORT;
        openFile = f;
        hideLayers();
        bannerPending = false;
        Rep r = describe(f);
        reportTitle.setText(r.dateText);
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
        reportLayer.setVisibility(View.VISIBLE);
    }

    private void showDev() {
        state = ST_DEV;
        hideLayers();
        devPill.setVisibility(View.VISIBLE);
    }

    private void printReport() {
        try {
            PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
            if (pm == null || webView == null) return;
            String name = "ECG report";
            pm.print(name, webView.createPrintDocumentAdapter(name), new PrintAttributes.Builder().build());
        } catch (Throwable t) {
            toast("Could not start printing: " + t.getMessage());
        }
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
        col.setPadding(dp(24), dp(44), dp(24), dp(24));

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

        Button take = pillButton("Take an ECG", GREEN, INK);
        take.setTextSize(20);
        take.setOnClickListener(v -> takeEcg());
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, dp(68));
        tp.topMargin = dp(24);
        col.addView(take, tp);

        Button reports = pillButton("All reports", CARD, TEXT);
        reports.setOnClickListener(v -> showReports());
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, dp(56));
        rp.topMargin = dp(12);
        col.addView(reports, rp);

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

    private void refreshHome() {
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
        reportsList.setPadding(dp(16), dp(4), dp(16), dp(24));
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
        FrameLayout layer = new FrameLayout(this);
        layer.setBackgroundColor(BG);
        layer.setClickable(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bar = topBar("Report", "Save PDF", v -> showReports(), v -> printReport());
        reportTitle = (TextView) bar.getTag();
        col.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        webView = new WebView(this);
        webView.setBackgroundColor(BG);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(false);
        ws.setAllowFileAccess(false);
        col.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1f));
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
            if (n.startsWith("ecg_report_") && n.endsWith(".html")) out.add(f);
        }
        return out;
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
        return g;
    }

    private TextView tv(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button pillButton(String s, int bg, int fg) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(17);
        b.setTextColor(fg);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(round(bg, 28));
        b.setMinHeight(dp(44));
        b.setMinimumHeight(dp(44));
        return b;
    }
}
