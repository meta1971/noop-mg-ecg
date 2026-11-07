package com.noop.mgecg;

// FILE VERSION 0.44.0 (7 Nov 2026): landscape full screen of the live 500 Hz graph (decode log 50.6 item 1, A4). A 10 s window on the same Live500View
// (quality colours, paper or dark theme, R markers as on the reading page). A tap switches auto / fixed scale; Back or the cross returns; it closes by
// itself when the live frames stop (the reading ended). No new permission; the reading itself is not touched.

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

public class LiveFullActivity extends Activity {
    private Live500View view;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        try { getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); } catch (Throwable ignored) { }
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Live500View.paper ? Color.parseColor("#FFFCF7") : Color.parseColor("#0D1015"));
        view = new Live500View(this);
        view.setWindowMs(10000.0);
        view.setTapOpensFull(false);
        view.setStaleCallback(new Runnable() { @Override public void run() { finish(); } });
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));
        float dp = getResources().getDisplayMetrics().density;
        TextView x = new TextView(this);
        x.setText("\u2715"); x.setTextSize(20); x.setGravity(Gravity.CENTER);
        x.setTextColor(Live500View.paper ? Color.parseColor("#4A4F5C") : Color.parseColor("#8FA1BD"));
        x.setContentDescription("Close the full screen graph");
        x.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { finish(); } });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int) (52 * dp), (int) (52 * dp), Gravity.TOP | Gravity.END);
        root.addView(x, lp);
        setContentView(root);
        hideBars();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) { super.onWindowFocusChanged(hasFocus); if (hasFocus) hideBars(); }

    @SuppressWarnings("deprecation")
    private void hideBars() {
        try {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        } catch (Throwable ignored) { }
    }
}
