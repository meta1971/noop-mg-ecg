package com.noop.mgecg;

// FILE VERSION 0.23.0 (28 Oct 2026): signal-processing helpers for the professional report. Pure Java (no Android), tested off the phone against the Python reference.

import java.util.Arrays;

final class ProDsp {
    private ProDsp() {}

    /** One biquad section {b0, b1, b2, a1, a2} (a0 = 1), Butterworth-section designs from the audio-EQ cookbook (same as the bilinear design with pre-warping). */
    static double[] lp2(double fc, double fs, double q) { double w = 2 * Math.PI * fc / fs, c = Math.cos(w), a = Math.sin(w) / (2 * q), a0 = 1 + a; return new double[] { (1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - a) / a0 }; }
    static double[] hp2(double fc, double fs, double q) { double w = 2 * Math.PI * fc / fs, c = Math.cos(w), a = Math.sin(w) / (2 * q), a0 = 1 + a; return new double[] { (1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - a) / a0 }; }
    static double[] notch(double f0, double q, double fs) { double w = 2 * Math.PI * f0 / fs, c = Math.cos(w), a = Math.sin(w) / (2 * q), a0 = 1 + a; return new double[] { 1 / a0, -2 * c / a0, 1 / a0, -2 * c / a0, (1 - a) / a0 }; }
    static final double Q2 = Math.sqrt(0.5);
    static double[][] butterLow(int order, double fc, double fs) {
        if (order == 2) return new double[][] { lp2(fc, fs, Q2) };
        return new double[][] { lp2(fc, fs, 0.5411961), lp2(fc, fs, 1.3065630) };                // 4th order
    }
    static double[][] butterHigh2(double fc, double fs) { return new double[][] { hp2(fc, fs, Q2) }; }
    static double[][] bandPass(double lo, double hi, double fs) { return new double[][] { hp2(lo, fs, Q2), lp2(hi, fs, Q2) }; }

    static double[] sos(double[] x, double[][] s, double x0Scale) {
        double[] y = Arrays.copyOf(x, x.length);
        for (double[] k : s) {
            double g = (k[0] + k[1] + k[2]) / (1 + k[3] + k[4]), z2 = k[2] - k[4] * g, z1 = k[1] - k[3] * g + z2;
            double s1 = z1 * y[0] * x0Scale, s2 = z2 * y[0] * x0Scale;
            for (int n = 0; n < y.length; n++) {
                double in = y[n], out = k[0] * in + s1;
                s1 = k[1] * in - k[3] * out + s2;
                s2 = k[2] * in - k[4] * out;
                y[n] = out;
            }
        }
        return y;
    }

    /** Zero-phase filtering: odd extension, forward then backward pass, steady-state start (like scipy.signal.sosfiltfilt). */
    static double[] filtfilt(double[] x, double[][]... chains) {
        int n = x.length, pad = Math.min(n - 1, 1000);
        double[][] all = concat(chains);
        double[] e = new double[n + 2 * pad];
        for (int i = 0; i < pad; i++) { e[pad - 1 - i] = 2 * x[0] - x[1 + i]; e[pad + n + i] = 2 * x[n - 1] - x[n - 2 - i]; }
        System.arraycopy(x, 0, e, pad, n);
        double[] f = sos(e, all, 1.0);
        for (int i = 0; i < f.length / 2; i++) { double t = f[i]; f[i] = f[f.length - 1 - i]; f[f.length - 1 - i] = t; }
        f = sos(f, all, 1.0);
        for (int i = 0; i < f.length / 2; i++) { double t = f[i]; f[i] = f[f.length - 1 - i]; f[f.length - 1 - i] = t; }
        return Arrays.copyOfRange(f, pad, pad + n);
    }
    static double[][] concat(double[][]... chains) { int m = 0; for (double[][] c : chains) m += c.length; double[][] r = new double[m][]; int k = 0; for (double[][] c : chains) for (double[] s : c) r[k++] = s; return r; }

    static double[] gradient(double[] a) { int n = a.length; double[] g = new double[n]; g[0] = a[1] - a[0]; g[n - 1] = a[n - 1] - a[n - 2]; for (int i = 1; i < n - 1; i++) g[i] = (a[i + 1] - a[i - 1]) / 2.0; return g; }
    static double median(double[] x, int a, int b) { double[] t = Arrays.copyOfRange(x, a, b); Arrays.sort(t); int n = t.length; return n == 0 ? Double.NaN : (n % 2 == 1 ? t[n / 2] : 0.5 * (t[n / 2 - 1] + t[n / 2])); }
    static double median(double[] x) { return median(x, 0, x.length); }
    static double percentile(double[] x, double p) { double[] t = x.clone(); Arrays.sort(t); double pos = p / 100.0 * (t.length - 1); int i = (int) Math.floor(pos); double f = pos - i; return i + 1 < t.length ? t[i] * (1 - f) + t[i + 1] * f : t[i]; }
    static double mean(double[] x) { double s = 0; for (double v : x) s += v; return s / x.length; }
    static double std(double[] x, int ddof) { double m = mean(x), s = 0; for (double v : x) s += (v - m) * (v - m); return Math.sqrt(s / (x.length - ddof)); }
    static double corr(double[] a, double[] b) { double ma = mean(a), mb = mean(b), sab = 0, saa = 0, sbb = 0; for (int i = 0; i < a.length; i++) { sab += (a[i] - ma) * (b[i] - mb); saa += (a[i] - ma) * (a[i] - ma); sbb += (b[i] - mb) * (b[i] - mb); } return sab / Math.sqrt(saa * sbb); }

    /** Running median over a window of w (odd) samples, edges replicated (like scipy median_filter mode nearest). */
    static double[] runMedian(double[] x, int w) {
        int h = w / 2; double[] r = new double[x.length]; double[] t = new double[w];
        for (int i = 0; i < x.length; i++) { for (int j = 0; j < w; j++) { int k = Math.max(0, Math.min(x.length - 1, i - h + j)); t[j] = x[k]; } Arrays.sort(t); r[i] = t[h]; }
        return r;
    }

    /** scipy-like find_peaks with height and distance. */
    static int[] findPeaks(double[] z, double height, int distance) {
        int n = z.length; int[] cand = new int[n]; int c = 0;
        for (int i = 1; i < n - 1; i++) {
            if (z[i] > z[i - 1] && z[i] >= z[i + 1]) {
                int j = i; while (j + 1 < n - 1 && z[j + 1] == z[i]) j++;
                if (z[j] > z[j + 1] || j == n - 2) { int mid = (i + j) / 2; if (z[mid] >= height) cand[c++] = mid; i = j; }
            }
        }
        Integer[] ord = new Integer[c]; for (int i = 0; i < c; i++) ord[i] = i;
        final double[] zz = z; final int[] cc = cand;
        Arrays.sort(ord, (p, q) -> Double.compare(zz[cc[q]], zz[cc[p]]));
        boolean[] keep = new boolean[c]; Arrays.fill(keep, true);
        for (int a = 0; a < c; a++) {
            int pa = ord[a]; if (!keep[pa]) continue;
            for (int b = pa - 1; b >= 0 && cand[pa] - cand[b] < distance; b--) keep[b] = false;
            for (int b = pa + 1; b < c && cand[b] - cand[pa] < distance; b++) keep[b] = false;
        }
        int[] out = new int[c]; int m = 0; for (int i = 0; i < c; i++) if (keep[i]) out[m++] = cand[i]; return Arrays.copyOf(out, m);
    }

    // ---------------------------------------------------------------- FFT
    static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) { int bit = n >> 1; for (; (j & bit) != 0; bit >>= 1) j ^= bit; j ^= bit; if (i < j) { double t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; } }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len, wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = i + k + len / 2; double tr = re[b] * cr - im[b] * ci, ti = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - tr; im[b] = im[a] - ti; re[a] += tr; im[a] += ti;
                    double nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr;
                }
            }
        }
    }
    /** Power spectrum of a Hann-windowed, mean-removed segment, zero padded to nfft (like the Python band_power). Returns power per bin 0..nfft/2. */
    static double[] bandPower(double[] seg, int a, int len, int nfft) {
        double[] re = new double[nfft], im = new double[nfft]; double m = 0; for (int i = 0; i < len; i++) m += seg[a + i]; m /= len;
        for (int i = 0; i < len; i++) { double w = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (len - 1)); re[i] = (seg[a + i] - m) * w; }
        fft(re, im); double[] p = new double[nfft / 2 + 1]; for (int k = 0; k <= nfft / 2; k++) p[k] = re[k] * re[k] + im[k] * im[k]; return p;
    }
    /** Welch power spectral density (density scaling, Hann, 50 % overlap), fs in Hz. Returns {freqs, psd}. */
    static double[][] welch(double[] x, double fs, int nperseg) {
        int step = nperseg / 2, nseg = 0; int nf = nperseg / 2 + 1; double[] acc = new double[nf];
        double wsum = 0; double[] w = new double[nperseg]; for (int i = 0; i < nperseg; i++) { w[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / nperseg); wsum += w[i] * w[i]; }
        for (int s = 0; s + nperseg <= x.length; s += step) {
            double[] re = new double[nperseg], im = new double[nperseg]; for (int i = 0; i < nperseg; i++) re[i] = x[s + i] * w[i];
            int p2 = 1; while (p2 < nperseg) p2 <<= 1;
            double[] rr = Arrays.copyOf(re, p2), ii = new double[p2];
            // nperseg is a power of two (256) in this use; otherwise fall back to a direct DFT
            if (p2 == nperseg) { fft(rr, ii); for (int k = 0; k < nf; k++) { double pw = rr[k] * rr[k] + ii[k] * ii[k]; acc[k] += (k == 0 || k == nperseg / 2 ? 1 : 2) * pw; } }
            else { for (int k = 0; k < nf; k++) { double sr = 0, si = 0; for (int i = 0; i < nperseg; i++) { double a = -2 * Math.PI * k * i / nperseg; sr += re[i] * Math.cos(a); si += re[i] * Math.sin(a); } acc[k] += (k == 0 || k == nperseg / 2 ? 1 : 2) * (sr * sr + si * si); } }
            nseg++;
        }
        double[] f = new double[nf], p = new double[nf]; for (int k = 0; k < nf; k++) { f[k] = k * fs / nperseg; p[k] = acc[k] / (Math.max(1, nseg) * fs * wsum); }
        return new double[][] { f, p };
    }
    /** Natural cubic spline evaluated at the points xi (x strictly increasing). */
    static double[] spline(double[] x, double[] y, double[] xi) {
        int n = x.length; double[] h = new double[n - 1], al = new double[n], l = new double[n], mu = new double[n], z = new double[n], c = new double[n], b = new double[n - 1], d = new double[n - 1];
        for (int i = 0; i < n - 1; i++) h[i] = x[i + 1] - x[i];
        for (int i = 1; i < n - 1; i++) al[i] = 3 / h[i] * (y[i + 1] - y[i]) - 3 / h[i - 1] * (y[i] - y[i - 1]);
        l[0] = 1; for (int i = 1; i < n - 1; i++) { l[i] = 2 * (x[i + 1] - x[i - 1]) - h[i - 1] * mu[i - 1]; mu[i] = h[i] / l[i]; z[i] = (al[i] - h[i - 1] * z[i - 1]) / l[i]; }
        l[n - 1] = 1;
        for (int j = n - 2; j >= 0; j--) { c[j] = z[j] - mu[j] * c[j + 1]; b[j] = (y[j + 1] - y[j]) / h[j] - h[j] * (c[j + 1] + 2 * c[j]) / 3; d[j] = (c[j + 1] - c[j]) / (3 * h[j]); }
        double[] r = new double[xi.length];
        for (int k = 0; k < xi.length; k++) { int j = Arrays.binarySearch(x, xi[k]); if (j < 0) j = -j - 2; j = Math.max(0, Math.min(n - 2, j)); double dx = xi[k] - x[j]; r[k] = y[j] + b[j] * dx + c[j] * dx * dx + d[j] * dx * dx * dx; }
        return r;
    }
    static double[] detrend(double[] y) { int n = y.length; double sx = 0, sy = 0, sxx = 0, sxy = 0; for (int i = 0; i < n; i++) { sx += i; sy += y[i]; sxx += (double) i * i; sxy += i * y[i]; } double b = (n * sxy - sx * sy) / (n * sxx - sx * sx), a = (sy - b * sx) / n; double[] r = new double[n]; for (int i = 0; i < n; i++) r[i] = y[i] - (a + b * i); return r; }
}
