package com.noop.mgecg;

// FILE VERSION 0.15.0 (26 Oct): an invisible launch of the strap-link owner. Research only.

/**
 * The strap link lives in MainActivity. When Android has ended the app, the link can only come back by running MainActivity again. This subclass is
 * declared with a translucent theme and its own task; started with host_only=true, MainActivity does not show the heart app, makes its window
 * invisible and untouchable, connects, and ends itself after a few seconds, which leaves the closed screen holding the link (HEADLESS_KEEP).
 * It is started (a) by the heart app whenever it opens and finds no link, (b) by the Reconnect action of the day-logger notification, and
 * (c) by the logger service itself when Android restarts it, if the person has allowed "Display over other apps" (Android 10 and later blocks a
 * background start otherwise).
 */
public class LinkHostActivity extends MainActivity {
}
