package com.thunderstorm.analyzer.pipeline;

import org.apache.commons.math3.transform.FastFourierTransformer;
import org.apache.commons.math3.transform.DftNormalization;
import org.apache.commons.math3.transform.TransformType;
import org.apache.commons.math3.complex.Complex;

import java.util.Arrays;

/**
 * Scores DBSCAN clusters for DNA origami blinking quality.
 * Mirrors Python blink_quality_score() exactly.
 */
public class BlinkingScorer {

    public static class ClusterScore {
        public int clusterId;
        public int nUnique;       // unique frames
        public int nCycles;       // on/off cycles
        public double consistency; // 1 / (cv + eps)
        public double periodicity; // FFT-based periodicity score
        public double score;       // final combined score
        public double meanIntensity;
        public double[] frames;    // unique sorted frame numbers
        public double[] intensities;
    }

    /**
     * Score all clusters in the DBSCAN result.
     *
     * @param x           x coordinates of all localisations
     * @param y           y coordinates
     * @param intensity   intensities
     * @param frame       frame numbers
     * @param labels      DBSCAN labels
     * @param nClusters   number of valid clusters
     * @param gapThreshold frames gap considered an off-event (default 1)
     * @param minCycles    minimum on/off cycles for a cluster to score > 0 (blink gate)
     * @param minFrames    minimum unique active frames for a cluster to score > 0 (blink gate)
     * @param totalFrames  total frames in the acquisition (for FFT signal length)
     */
    public static ClusterScore[] scoreAll(double[] x, double[] y,
                                          double[] intensity, double[] frame,
                                          int[] labels, int nClusters,
                                          int gapThreshold, int minCycles, int minFrames,
                                          int totalFrames) {
        ClusterScore[] scores = new ClusterScore[nClusters];
        for (int cid = 0; cid < nClusters; cid++) {
            int[] members = DbscanClusterer.clusterMembers(labels, cid);
            scores[cid] = scoreCluster(cid, members, intensity, frame,
                                       gapThreshold, minCycles, minFrames, totalFrames);
        }
        // Sort descending by score
        Arrays.sort(scores, (a, b) -> Double.compare(b.score, a.score));
        return scores;
    }

    private static ClusterScore scoreCluster(int cid, int[] members,
                                             double[] intensity, double[] frame,
                                             int gapThreshold, int minCycles, int minFrames,
                                             int totalFrames) {
        ClusterScore cs = new ClusterScore();
        cs.clusterId = cid;

        // Gather frames and intensities for this cluster
        int n = members.length;
        if (n == 0) {            // empty cluster — cannot blink
            cs.frames = new double[0];
            cs.intensities = new double[0];
            cs.score = 0.0;
            return cs;
        }
        double[] framesArr = new double[n];
        double[] intenArr  = new double[n];
        for (int i = 0; i < n; i++) {
            framesArr[i] = frame[members[i]];
            intenArr[i]  = intensity[members[i]];
        }
        Arrays.sort(framesArr);

        // Unique frames
        int nUnique = 1;
        for (int i = 1; i < framesArr.length; i++) if (framesArr[i] != framesArr[i-1]) nUnique++;
        cs.nUnique = nUnique;
        cs.frames = framesArr;
        cs.intensities = intenArr;

        // Cycles: count gaps > threshold in sorted unique frames
        double[] uniqueFrames = uniqueSorted(framesArr);
        cs.nCycles = countCycles(uniqueFrames, gapThreshold);

        // CV of intensity → consistency
        double mean = 0; for (double v : intenArr) mean += v; mean /= n;
        double variance = 0; for (double v : intenArr) variance += (v-mean)*(v-mean);
        double std = n > 1 ? Math.sqrt(variance/(n-1)) : 0;
        double cv = mean > 0 ? std / mean : 0;
        cs.consistency = 1.0 / (cv + 1e-6);
        cs.meanIntensity = mean;

        // FFT periodicity
        cs.periodicity = fourierPeriodicity(uniqueFrames, Math.max(totalFrames, (int)uniqueFrames[uniqueFrames.length-1] + 1));

        // Blink gate (mirrors Python _score_cluster): a cluster that does not switch
        // on/off enough times, or is not active in enough frames, scores 0 and is
        // excluded from structure detection.
        if (cs.nCycles < minCycles || cs.nUnique < minFrames) {
            cs.score = 0.0;
        } else {
            cs.score = cs.nCycles * cs.nUnique * cs.consistency * (1.0 + cs.periodicity);
        }
        return cs;
    }

    static int countCycles(double[] sortedUniqueFrames, int gapThreshold) {
        if (sortedUniqueFrames.length == 0) return 0;
        int cycles = 1;
        for (int i = 1; i < sortedUniqueFrames.length; i++) {
            if (sortedUniqueFrames[i] - sortedUniqueFrames[i-1] > gapThreshold) cycles++;
        }
        return cycles;
    }

    static double fourierPeriodicity(double[] uniqueFrames, int totalFrames) {
        if (totalFrames < 2 || uniqueFrames.length < 2) return 0;
        // Next power-of-two ≥ totalFrames for FFT
        int fftLen = 1;
        while (fftLen < totalFrames) fftLen <<= 1;

        double[] signal = new double[fftLen];
        for (double f : uniqueFrames) {
            int idx = (int) f;
            if (idx >= 0 && idx < fftLen) signal[idx] = 1.0;
        }

        FastFourierTransformer fft = new FastFourierTransformer(DftNormalization.STANDARD);
        Complex[] spectrum = fft.transform(signal, TransformType.FORWARD);

        // Zero DC; compute max magnitude / total magnitude (excluding DC)
        double maxMag = 0, totalMag = 0;
        for (int i = 1; i < spectrum.length / 2; i++) {
            double mag = spectrum[i].abs();
            totalMag += mag;
            if (mag > maxMag) maxMag = mag;
        }
        return totalMag > 0 ? maxMag / totalMag : 0;
    }

    private static double[] uniqueSorted(double[] sortedFrames) {
        java.util.List<Double> list = new java.util.ArrayList<>();
        list.add(sortedFrames[0]);
        for (int i = 1; i < sortedFrames.length; i++)
            if (sortedFrames[i] != sortedFrames[i-1]) list.add(sortedFrames[i]);
        double[] out = new double[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
    }
}
