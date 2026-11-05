package com.noop.mgecg;

// FILE VERSION 0.42.2 (5 Nov 2026): one place for a pull the person asked for. If the strap link owner is not running yet, start it and ask again once it is up
// (before this the first tap found no link, did nothing, and still said "Syncing"). MainActivity then says what happened (decode log 118).

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

final class PullNow {
    private PullNow() {}
    private static final Handler H = new Handler(Looper.getMainLooper());

    static void request(final Context c0) {
        final Context c = c0.getApplicationContext();
        try {
            if (MainActivity.linkRunning()) { MainActivity.requestPullUser(); return; }
            MainActivity.startLinkHost(c0);
            android.widget.Toast.makeText(c, "Opening the strap link, then pulling\u2026", android.widget.Toast.LENGTH_SHORT).show();
            final int[] tries = { 0 };
            H.postDelayed(new Runnable() { @Override public void run() {
                if (MainActivity.linkRunning()) { MainActivity.requestPullUser(); return; }
                if (++tries[0] < 15) H.postDelayed(this, 1000L); else android.widget.Toast.makeText(c, "The strap link did not come up. Open the app and check the strap.", android.widget.Toast.LENGTH_LONG).show();
            } }, 1500L);
        } catch (Throwable ignored) { }
    }
}
