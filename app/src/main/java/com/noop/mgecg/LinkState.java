package com.noop.mgecg;

// FILE VERSION 0.13.3 (26 Oct): one plain answer to "am I connected to the strap?". Research only.

import java.util.Locale;

/**
 * What the strap link is doing right now, in words a person can act on. Pure: MainActivity.linkState() gathers the inputs, the screens show the result.
 * Order of the checks matters: the logger switch first, then whether the link code is running at all, then Bluetooth, then the link itself.
 */
final class LinkState {
    private LinkState() {}

    static final int GOOD = 0, WARN = 1, BAD = 2;
    static final int ACT_NONE = 0, ACT_TURN_ON = 1, ACT_START = 2, ACT_RECONNECT = 3;

    static final class Info {
        String code, chip, title, detail;
        int level, action;
    }

    static String age(long ms) {
        long s = Math.max(0L, ms) / 1000L;
        if (s < 90L) return s + " s";
        if (s < 5400L) return Math.round(s / 60.0) + " min";
        return String.format(Locale.UK, "%.1f h", s / 3600.0);
    }

    /**
     * 0.13.4: should Today warn that no new data is arriving? Stored seconds only arrive with each history pull (about every 30 min), so a healthy
     * link legitimately shows data 20 or 25 minutes old. Warn at 20 min only when the link itself is not good; with a good link only after 75 min.
     */
    static boolean staleBanner(int linkLevel, long ageSec) {
        return linkLevel == GOOD ? ageSec > 75L * 60L : ageSec > 20L * 60L;
    }

    private static Info info(String code, String chip, String title, String detail, int level, int action) {
        Info i = new Info();
        i.code = code; i.chip = chip; i.title = title; i.detail = detail; i.level = level; i.action = action;
        return i;
    }

    /**
     * @param running   the MainActivity that owns the strap link exists (screen open, or kept alive by the logger)
     * @param ready     link up and command channel ready
     * @param connecting a connection attempt is in progress
     * @param passive   waiting for the phone to complete a background connect when the strap is in range
     * @param rxAgeMs   milliseconds since the last byte from the strap, -1 if none this session
     * @param liveAgeMs milliseconds since the last real-time heart-rate frame, -1 if none
     * @param retryMs   current wait between reconnect tries
     */
    static Info describe(boolean loggerOn, boolean running, boolean ready, boolean connecting, boolean passive,
                         long rxAgeMs, int liveHr, long liveAgeMs, boolean btOff, boolean bleOnly, long retryMs) {
        if (!loggerOn)
            return info("off", "LOGGER OFF", "Day logger is off",
                    "The day logger is off, so the app is not holding a link to the strap and nothing is being recorded. Turn it on and the app connects in the background.", BAD, ACT_TURN_ON);
        if (!running)
            return info("idle", "LINK NOT RUNNING", "Strap link is not running",
                    "The logger is on but the part of the app that talks to the strap is not running (the phone may have restarted or the app was swiped away). Start it and it reconnects by itself.", BAD, ACT_START);
        if (btOff && !bleOnly)
            return info("btoff", "BLUETOOTH OFF", "Bluetooth is off",
                    "Bluetooth is off and Bluetooth scanning (Settings > Location) is off too, so the phone cannot reach the strap. Switch Bluetooth on, or turn Bluetooth scanning on.", BAD, ACT_NONE);
        if (ready) {
            String live = liveAgeMs >= 0L && liveAgeMs < 30000L && liveHr > 0 ? " Live heart rate " + liveHr + " bpm." : "";
            if (rxAgeMs >= 0L && rxAgeMs < 30000L)
                return info("live", "LIVE", "Connected, live", "Connected. Last data from the strap " + age(rxAgeMs) + " ago." + live, GOOD, ACT_NONE);
            if (rxAgeMs < 0L || rxAgeMs < 15L * 60000L)
                return info("connected", "CONNECTED", "Connected",
                        "Connected." + (rxAgeMs < 0L ? " Nothing received yet this session." : " Last data from the strap " + age(rxAgeMs) + " ago.") + " The strap sends in bursts, so a quiet minute is normal." + live, GOOD, ACT_NONE);
            return info("silent", "QUIET", "Link up but quiet",
                    "The link looks up but nothing has arrived for " + age(rxAgeMs) + ". The app closes and reopens it by itself after 15 minutes of silence.", WARN, ACT_RECONNECT);
        }
        if (connecting)
            return info("connecting", "CONNECTING", "Connecting", "Connecting to the strap. This can take up to a minute. Keep the strap on and close to the phone.", WARN, ACT_RECONNECT);
        if (passive)
            return info("waiting", "WAITING", "Waiting for the strap", "Waiting for the strap to come into range; the phone completes the connection by itself. Check the strap is on your wrist, charged and not on the charger.", WARN, ACT_RECONNECT);
        return info("down", "OFFLINE", "Not connected",
                "Not connected to the strap. The app retries about every " + Math.max(1L, Math.round(retryMs / 60000.0)) + " min. Check the strap is on your wrist, charged and not on the charger.", BAD, ACT_RECONNECT);
    }
}
