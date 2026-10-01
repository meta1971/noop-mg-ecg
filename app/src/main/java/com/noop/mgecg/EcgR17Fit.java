package com.noop.mgecg;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Per-session fit of the strap's live R17 stream against the stored R16 ECG of the same seconds.
 *
 * R17 is R16 through a fixed causal filter (see EcgWhoopSpec.whoopLiveReplica). Running the replica on the
 * stored samples and regressing the real R17 on it gives, for every session:
 *   scale       R16 uV per count, if R17 is exactly 1 uV per count (0.06287, 0.06300, 0.06324 on three
 *               sessions on 29-30 Sep 2026, against 0.0621 in the code, so it is worth tracking)
 *   unexplained the share of R17's variance the fixed filter does not reproduce (0.43%, 0.26%, 0.10%)
 *   lag         the shift in R17 samples that matched best (0 on all three)
 * Only "good" seconds count: R17 present with quality 3 and contact, and the three seconds before it too, so
 * the filter has settled. Pure Java; research use only.
 */
final class EcgR17Fit {

    static final class Result {
        double scale = Double.NaN, gain = Double.NaN, unexplained = Double.NaN;
        int lag, seconds;
        String why = "";

        String toLog() {
            if (!why.isEmpty()) return "R17_FIT not_run why=" + why.replace(' ', '_');
            return String.format(Locale.US,
                    "R17_FIT scale_uV_per_count=%.5f gain_vs_code=%.4f lag=%d unexplained=%.2f%% seconds=%d",
                    scale, gain, lag, unexplained, seconds);
        }
    }

    private EcgR17Fit() {}

    private static long u32(byte[] f, int o) {
        return (f[o] & 0xffL) | ((f[o + 1] & 0xffL) << 8) | ((f[o + 2] & 0xffL) << 16) | ((f[o + 3] & 0xffL) << 24);
    }

    static Result fit(List<byte[]> r16, List<byte[]> r17) {
        Result res = new Result();
        if (r16 == null || r17 == null || r16.size() < 20 || r17.size() < 20) { res.why = "too few records"; return res; }
        long first = Long.MAX_VALUE, last = Long.MIN_VALUE;
        for (byte[] f : r16) {
            if (f.length != 1584) continue;
            long s = u32(f, 11);
            first = Math.min(first, s);
            last = Math.max(last, s);
        }
        if (last < first) { res.why = "no stored records"; return res; }
        int n = (int) Math.min(last - first + 1, 3600);
        double[] uv = new double[n * EcgR16Analyzer.FS];
        for (byte[] f : r16) {
            if (f.length != 1584) continue;
            long k = u32(f, 11) - first;
            if (k < 0 || k >= n || EcgWhoopSpec.r16Count(f) != 500) continue;
            for (int i = 0; i < 500; i++) uv[(int) k * 500 + i] = EcgWhoopSpec.r16Sample(f, i) * EcgWhoopSpec.R16_UV_PER_COUNT;
        }
        double[] rep = EcgWhoopSpec.whoopLiveReplica(uv);
        // real R17 by record, only where it is clean
        Map<Long, short[]> ok = new HashMap<>();
        for (byte[] f : r17) {
            if (f.length != 240) continue;
            int cnt = (f[32] & 0xff) | ((f[33] & 0xff) << 8);
            if (cnt != 100 || (f[21] & 0xff) != 3 || (f[22] & 0x08) == 0) continue;
            short[] s = new short[100];
            for (int i = 0; i < 100; i++) s[i] = (short) ((f[34 + 2 * i] & 0xff) | (f[35 + 2 * i] << 8));
            ok.put(u32(f, 11) - first, s);
        }
        TreeMap<Long, short[]> good = new TreeMap<>();
        for (Map.Entry<Long, short[]> e : ok.entrySet()) {
            long k = e.getKey();
            if (k < 0 || k >= n) continue;
            boolean settled = true;
            for (long j = k - 3; j <= k && settled; j++) settled = ok.containsKey(j);
            if (settled) good.put(k, e.getValue());
        }
        if (good.size() < 30) { res.why = "fewer than 30 settled seconds"; return res; }
        double bestV = Double.MAX_VALUE;
        for (int lag = -4; lag <= 4; lag++) {
            double sxy = 0, sxx = 0, sy = 0, sy2 = 0, sx = 0;
            long cnt = 0;
            for (Map.Entry<Long, short[]> e : good.entrySet()) {
                int base = (int) (e.getKey() * 100);
                for (int i = 0; i < 100; i++) {
                    int ri = base + i - lag;
                    if (ri < 0 || ri >= rep.length) continue;
                    double y = e.getValue()[i], x = rep[ri];
                    sxy += x * y; sxx += x * x; sy += y; sy2 += y * y; sx += x; cnt++;
                }
            }
            if (cnt < 1000 || sxx == 0) continue;
            double g = sxy / sxx;
            double varY = sy2 / cnt - (sy / cnt) * (sy / cnt);
            double sse = sy2 - 2 * g * sxy + g * g * sxx;       // sum of squared residuals
            double meanRes = (sy - g * sx) / cnt;
            double v = (sse / cnt - meanRes * meanRes) / varY;
            if (v < bestV) {
                bestV = v;
                res.lag = lag;
                res.gain = g;
                res.unexplained = 100.0 * v;
                res.seconds = good.size();
            }
        }
        if (Double.isNaN(res.gain)) { res.why = "no usable overlap"; return res; }
        res.scale = EcgWhoopSpec.R16_UV_PER_COUNT * res.gain;
        return res;
    }
}
