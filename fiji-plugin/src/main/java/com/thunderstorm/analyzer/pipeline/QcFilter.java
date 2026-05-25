package com.thunderstorm.analyzer.pipeline;

import com.thunderstorm.analyzer.model.QcParams;
import com.thunderstorm.analyzer.pipeline.CsvLoader.LocalizationData;

import java.util.Arrays;

/**
 * Applies the four-step QC filter chain to raw localizations.
 * Mirrors the Python pipeline filters exactly.
 */
public class QcFilter {

    public static class FilteredData {
        // Filtered arrays
        public double[] x, y, uncertainty, intensity, sigma, frame, bkgstd;
        public int nRaw;
        public int nFiltered;   // after filters 1-3
        public int n3sigma;     // after 3σ intensity filter
        public double intensity3sigmaLo, intensity3sigmaHi;

        // Convenience stats (computed on filtered data)
        public double meanUncertainty, medianUncertainty, stdUncertainty;
        public double meanSigma,       medianSigma;
        public double meanIntensity,   medianIntensity;
        public double meanBkgstd;
    }

    public static FilteredData apply(LocalizationData raw, QcParams qc) {
        int n = raw.n;

        // --- Filter 1-3: intensity, uncertainty, sigma ---
        boolean[] keep = new boolean[n];
        int count = 0;
        for (int i = 0; i < n; i++) {
            if (raw.intensity[i] > qc.minIntensity &&
                raw.uncertainty[i] < qc.maxUncertaintyNm &&
                raw.sigma[i] < qc.maxSigmaNm) {
                keep[i] = true;
                count++;
            }
        }

        double[] x1 = new double[count], y1 = new double[count],
                 u1 = new double[count], i1 = new double[count],
                 s1 = new double[count], f1 = new double[count],
                 b1 = new double[count];
        int j = 0;
        for (int i = 0; i < n; i++) {
            if (keep[i]) {
                x1[j] = raw.x[i]; y1[j] = raw.y[i];
                u1[j] = raw.uncertainty[i]; i1[j] = raw.intensity[i];
                s1[j] = raw.sigma[i]; f1[j] = raw.frame[i];
                b1[j] = raw.bkgstd[i]; j++;
            }
        }

        // --- Filter 4: 3σ intensity ---
        double mean = mean(i1), std = std(i1, mean);
        double lo = mean - 3 * std, hi = mean + 3 * std;
        int count2 = 0;
        boolean[] keep2 = new boolean[count];
        for (int i = 0; i < count; i++) {
            if (i1[i] >= lo && i1[i] <= hi) { keep2[i] = true; count2++; }
        }
        double[] x2 = new double[count2], y2 = new double[count2],
                 u2 = new double[count2], i2 = new double[count2],
                 s2 = new double[count2], f2 = new double[count2],
                 b2 = new double[count2];
        int k = 0;
        for (int i = 0; i < count; i++) {
            if (keep2[i]) {
                x2[k] = x1[i]; y2[k] = y1[i];
                u2[k] = u1[i]; i2[k] = i1[i];
                s2[k] = s1[i]; f2[k] = f1[i];
                b2[k] = b1[i]; k++;
            }
        }

        FilteredData fd = new FilteredData();
        fd.nRaw      = n;
        fd.nFiltered = count;
        fd.n3sigma   = count2;
        fd.x = x2; fd.y = y2; fd.uncertainty = u2; fd.intensity = i2;
        fd.sigma = s2; fd.frame = f2; fd.bkgstd = b2;
        fd.intensity3sigmaLo = lo; fd.intensity3sigmaHi = hi;

        // Stats on the final filtered set
        fd.meanUncertainty   = mean(u2);
        fd.medianUncertainty = median(u2);
        fd.stdUncertainty    = std(u2, fd.meanUncertainty);
        fd.meanSigma         = mean(s2);
        fd.medianSigma       = median(s2);
        fd.meanIntensity     = mean(i2);
        fd.medianIntensity   = median(i2);
        fd.meanBkgstd        = mean(b2);

        return fd;
    }

    // -----------------------------------------------------------------------
    // Statistics helpers
    // -----------------------------------------------------------------------
    public static double mean(double[] a) {
        if (a.length == 0) return 0;
        double s = 0; for (double v : a) s += v; return s / a.length;
    }

    public static double std(double[] a, double mean) {
        if (a.length < 2) return 0;
        double s = 0; for (double v : a) s += (v - mean) * (v - mean);
        return Math.sqrt(s / (a.length - 1));
    }

    public static double median(double[] a) {
        if (a.length == 0) return 0;
        double[] sorted = a.clone(); Arrays.sort(sorted);
        int mid = sorted.length / 2;
        return sorted.length % 2 == 0 ? (sorted[mid - 1] + sorted[mid]) / 2.0 : sorted[mid];
    }
}
