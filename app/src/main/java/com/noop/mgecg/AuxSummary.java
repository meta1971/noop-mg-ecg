package com.noop.mgecg;

/**
 * One-second summaries of the two records the strap banks beside the stored ECG: R21 (1244 B, wrist motion at 100 Hz) and
 * R20 (2140 B, optical deep buffer at 50 Hz). Pure Java. The full samples stay in the raw history files; these few numbers per
 * second are what the all-day database keeps so motion and pulse quality can be lined up with the ECG later.
 *
 * R21: from byte 28 int16 LE accelerometer x,y,z (100 samples each, 4096 counts per g); three header words; from byte 640 int16
 *      LE gyroscope x,y,z (100 each, 16.384 counts per degree/second). Checked 22 Oct 2026: |a| 1.0014 g on a still wrist.
 * R20: five blocks of 422 bytes from byte 26. Block byte 0 = samples in the block (25 in the first, partial record, otherwise 50);
 *      bytes 1-3 LED A (driver byte, current u16 in units of 0.01 mA); 4-6 LED B; slot A = samples at block+21 and slot B at
 *      block+221, 4-byte LE signed 24-bit. Blocks: 0 green (the pulse), 1 red and 2 spare (configured but no samples on this
 *      strap), 3 infrared (LED current 0), 4 ambient (dark reference).
 */
final class AuxSummary {
    private AuxSummary() {}

    static final int R20_LEN = 2140, R21_LEN = 1244;
    static final int[] R20_BLOCK = {26, 448, 870, 1292, 1714};

    static final class Motion { double accSdG, accMeanG, gyroDps; int n; }
    static final class Optical { double greenMa, greenDc, greenSd, ambientDc; int greenN; }

    private static int i16(byte[] f, int o) { return (short) ((f[o] & 0xff) | ((f[o + 1] & 0xff) << 8)); }
    private static int s24(byte[] f, int o) {
        int v = (f[o] & 0xff) | ((f[o + 1] & 0xff) << 8) | ((f[o + 2] & 0xff) << 16);
        return (v << 8) >> 8;
    }

    static Motion motion(byte[] f) {
        if (f == null || f.length != R21_LEN) return null;
        Motion m = new Motion();
        m.n = 100;
        double sum = 0, sum2 = 0, gs = 0;
        for (int k = 0; k < 100; k++) {
            double x = i16(f, 28 + 2 * k) / 4096.0, y = i16(f, 228 + 2 * k) / 4096.0, z = i16(f, 428 + 2 * k) / 4096.0;
            double a = Math.sqrt(x * x + y * y + z * z);
            sum += a; sum2 += a * a;
            double gx = i16(f, 640 + 2 * k), gy = i16(f, 840 + 2 * k), gz = i16(f, 1040 + 2 * k);
            gs += gx * gx + gy * gy + gz * gz;
        }
        m.accMeanG = sum / 100.0;
        m.accSdG = Math.sqrt(Math.max(0, sum2 / 100.0 - m.accMeanG * m.accMeanG));
        m.gyroDps = Math.sqrt(gs / 300.0) / 16.384;
        return m;
    }

    static Optical optical(byte[] f) {
        if (f == null || f.length != R20_LEN) return null;
        Optical o = new Optical();
        int b0 = R20_BLOCK[0];
        int n = f[b0] & 0xff;
        if (n > 50) n = 50;
        o.greenN = n;
        o.greenMa = ((f[b0 + 2] & 0xff) | ((f[b0 + 3] & 0xff) << 8)) * 0.01;
        if (n > 0) {
            double s = 0, s2 = 0;
            for (int k = 0; k < n; k++) { double v = s24(f, b0 + 21 + 4 * k); s += v; s2 += v * v; }
            o.greenDc = s / n;
            o.greenSd = Math.sqrt(Math.max(0, s2 / n - o.greenDc * o.greenDc));
        }
        int b4 = R20_BLOCK[4];
        int na = Math.min(50, f[b4] & 0xff);
        if (na > 0) { double s = 0; for (int k = 0; k < na; k++) s += s24(f, b4 + 21 + 4 * k); o.ambientDc = s / na; }
        return o;
    }
}
