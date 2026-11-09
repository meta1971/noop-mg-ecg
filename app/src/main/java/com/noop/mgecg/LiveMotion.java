package com.noop.mgecg;

// FILE VERSION 0.46.1 (9 Nov 2026): live motion from the strap's R21 frames (type 43, layout 21, 1,244 bytes, one a second: 100 samples of accelerometer and gyroscope) that arrive when
// Settings > Live motion capture (test) asked for the IMU stream (START_RAW_DATA 81 [01], then TOGGLE_IMU_MODE 106 [01 01]). Decoding as AuxSummary: accelerometer x, y, z from byte 28 (100 int16 each,
// 4096 counts per g), gyroscope from byte 640 (16.384 counts per dps); checked exact against the history copy of the same reading on 9 Nov (0.00000 g difference). Per second: the largest
// accelerometer deviation from the median (g), the largest tilt of the gravity direction from its median (degrees) and the largest gyroscope magnitude (dps). A second counts as MOVING above the 95th
// percentiles of the 62-reading analysis at rest (decode log 50.5 rev30): 0.027 g, 3.95 degrees, 7.3 dps; on 9 Nov two relaxed readings had 0 to 1 % of their seconds above them. Pure Java.

final class LiveMotion {
    private LiveMotion() {}
    static final double DEV_G = 0.027, TILT_DEG = 3.95, GYRO_DPS = 7.3;
    private static final Object L = new Object();
    private static long lastMs; private static double dev = Double.NaN, tilt = Double.NaN, gyro = Double.NaN;
    private static boolean moving; private static int frames, movingSec;

    static void reset() { synchronized (L) { lastMs = 0; dev = tilt = gyro = Double.NaN; moving = false; frames = 0; movingSec = 0; } }

    private static int i16(byte[] f, int o) { return (short) ((f[o] & 0xFF) | ((f[o + 1] & 0xFF) << 8)); }

    /** One R21 frame (1,244 bytes). Returns true when it was decoded. */
    static boolean feed(byte[] f, long nowMs) {
        if (f == null || f.length != 1244) return false;
        double[][] a = new double[100][3]; double[] gm = new double[100];
        for (int k = 0; k < 100; k++) {
            a[k][0] = i16(f, 28 + 2 * k) / 4096.0; a[k][1] = i16(f, 228 + 2 * k) / 4096.0; a[k][2] = i16(f, 428 + 2 * k) / 4096.0;
            double gx = i16(f, 640 + 2 * k) / 16.384, gy = i16(f, 840 + 2 * k) / 16.384, gz = i16(f, 1040 + 2 * k) / 16.384;
            gm[k] = Math.sqrt(gx * gx + gy * gy + gz * gz);
        }
        double[] med = new double[3]; double[] col = new double[100];
        for (int c = 0; c < 3; c++) { for (int k = 0; k < 100; k++) col[k] = a[k][c]; med[c] = ProDsp.median(col); }
        double mn = Math.sqrt(med[0] * med[0] + med[1] * med[1] + med[2] * med[2]); if (!(mn > 0.3)) return false;      // not a gravity vector: ignore
        double d = 0, t = 0, g = 0;
        for (int k = 0; k < 100; k++) {
            for (int c = 0; c < 3; c++) d = Math.max(d, Math.abs(a[k][c] - med[c]));
            double nk = Math.sqrt(a[k][0] * a[k][0] + a[k][1] * a[k][1] + a[k][2] * a[k][2]); if (nk <= 0) continue;
            double dot = (a[k][0] * med[0] + a[k][1] * med[1] + a[k][2] * med[2]) / (nk * mn);
            t = Math.max(t, Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot)))));
            g = Math.max(g, gm[k]);
        }
        synchronized (L) {
            dev = d; tilt = t; gyro = g; lastMs = nowMs; frames++;
            moving = d > DEV_G || t > TILT_DEG || g > GYRO_DPS; if (moving) movingSec++;
        }
        return true;
    }

    static int frames() { synchronized (L) { return frames; } }
    static int movingSeconds() { synchronized (L) { return movingSec; } }
    /** True when a frame arrived in the last 3 s. */
    static boolean fresh(long nowMs) { synchronized (L) { return frames > 0 && nowMs - lastMs <= 3000; } }
    static boolean moving() { synchronized (L) { return moving; } }
    static double[] last() { synchronized (L) { return new double[] { dev, tilt, gyro }; } }
}
