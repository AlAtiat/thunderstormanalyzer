package com.thunderstorm.analyzer.pipeline;

import org.apache.commons.math3.fitting.leastsquares.LeastSquaresBuilder;
import org.apache.commons.math3.fitting.leastsquares.LeastSquaresProblem;
import org.apache.commons.math3.fitting.leastsquares.LevenbergMarquardtOptimizer;
import org.apache.commons.math3.linear.DiagonalMatrix;

import java.util.Arrays;

/**
 * Nearest-neighbour distance analysis with Silverman KDE and Gaussian peak fitting.
 * Mirrors the Python nnd_analysis() pipeline step exactly.
 */
public class NndAnalyzer {

    public static class NndResult {
        public double[] distances;      // all NND values (nm)
        public double peakNm;           // Gaussian peak position
        public double sigmaNm;          // Gaussian sigma
        public boolean fitOk;

        // KDE curve (600 points)
        public double[] kdeX;
        public double[] kdeY;

        // Gaussian fit overlay (200 points within fit window)
        public double[] fitX;
        public double[] fitY;
    }

    // -------------------------------------------------------------------
    // Public entry point
    // -------------------------------------------------------------------

    public static NndResult compute(double[] x, double[] y) {
        int n = x.length;
        double[] nnd = computeNnd(x, y, n);

        NndResult result = new NndResult();
        result.distances = nnd;

        // KDE
        double std = stdDev(nnd);
        double bandwidth = 0.9 * std * Math.pow(n, -0.2);
        if (bandwidth < 1e-9) bandwidth = 1.0;

        double minD = min(nnd), maxD = Math.min(max(nnd), 600.0);
        int kdePoints = 600;
        double[] kdeX = linspace(minD, maxD, kdePoints);
        double[] kdeY = kde(nnd, kdeX, bandwidth);
        result.kdeX = kdeX;
        result.kdeY = kdeY;

        // Locate peak
        int peakIdx = argmax(kdeY);
        double peakGuess = kdeX[peakIdx];

        // Fit window: [peak*0.4, peak*2.2]
        double winLo = peakGuess * 0.4, winHi = peakGuess * 2.2;
        int[] winMask = windowMask(kdeX, winLo, winHi);
        if (winMask.length < 5) {
            result.peakNm  = peakGuess;
            result.sigmaNm = peakGuess * 0.3;
            result.fitOk   = false;
            result.fitX    = kdeX;
            result.fitY    = kdeY;
            return result;
        }

        double[] wx = subset(kdeX, winMask);
        double[] wy = subset(kdeY, winMask);

        double[] fitted = fitGaussian(wx, wy, peakGuess, std * 0.5, kdeY[peakIdx]);
        if (fitted != null) {
            result.peakNm  = fitted[1];
            result.sigmaNm = Math.abs(fitted[2]);
            result.fitOk   = true;

            double[] fitX = linspace(winLo, winHi, 200);
            double[] fitY = gaussian(fitX, fitted[0], fitted[1], fitted[2]);
            result.fitX = fitX;
            result.fitY = fitY;
        } else {
            result.peakNm  = peakGuess;
            result.sigmaNm = peakGuess * 0.3;
            result.fitOk   = false;
            result.fitX    = wx;
            result.fitY    = wy;
        }

        return result;
    }

    // -------------------------------------------------------------------
    // Nearest-neighbour distances via brute-force KD-tree (O(n log n))
    // -------------------------------------------------------------------

    private static double[] computeNnd(double[] x, double[] y, int n) {
        // Build a simple 2D KD-tree for NND queries
        KDNode root = buildKdTree(x, y, 0, n - 1, true, makeIndices(n));
        double[] nnd = new double[n];
        for (int i = 0; i < n; i++) {
            nnd[i] = nearestNeighbourDist(root, x[i], y[i], i, x, y);
        }
        return nnd;
    }

    // -------------------------------------------------------------------
    // Minimal 2D KD-tree
    // -------------------------------------------------------------------

    public static class KDNode {
        int idx;
        KDNode left, right;
        KDNode(int idx) { this.idx = idx; }
    }

    private static int[] makeIndices(int n) {
        int[] idx = new int[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        return idx;
    }

    private static KDNode buildKdTree(double[] x, double[] y, int lo, int hi, boolean splitX, int[] idx) {
        if (lo > hi) return null;
        if (lo == hi) return new KDNode(idx[lo]);

        // Partial sort on the split dimension
        int mid = (lo + hi) / 2;
        partialSort(x, y, idx, lo, hi, mid, splitX);

        KDNode node = new KDNode(idx[mid]);
        node.left  = buildKdTree(x, y, lo, mid - 1, !splitX, idx);
        node.right = buildKdTree(x, y, mid + 1, hi,  !splitX, idx);
        return node;
    }

    /** Rearranges idx[lo..hi] so that idx[target] holds the median on the chosen axis. */
    private static void partialSort(double[] x, double[] y, int[] idx, int lo, int hi, int target, boolean onX) {
        while (lo < hi) {
            int pivot = partition(x, y, idx, lo, hi, onX);
            if (pivot == target) return;
            if (pivot < target) lo = pivot + 1;
            else hi = pivot - 1;
        }
    }

    private static int partition(double[] x, double[] y, int[] idx, int lo, int hi, boolean onX) {
        double pivotVal = onX ? x[idx[hi]] : y[idx[hi]];
        int i = lo;
        for (int j = lo; j < hi; j++) {
            double v = onX ? x[idx[j]] : y[idx[j]];
            if (v <= pivotVal) { int tmp = idx[i]; idx[i] = idx[j]; idx[j] = tmp; i++; }
        }
        int tmp = idx[i]; idx[i] = idx[hi]; idx[hi] = tmp;
        return i;
    }

    private static double nearestNeighbourDist(KDNode root, double qx, double qy, int skipIdx,
                                               double[] x, double[] y) {
        double[] best = { Double.MAX_VALUE };
        nnSearch(root, qx, qy, skipIdx, x, y, true, best);
        return Math.sqrt(best[0]);
    }

    private static void nnSearch(KDNode node, double qx, double qy, int skipIdx,
                                 double[] x, double[] y, boolean splitX, double[] bestSq) {
        if (node == null) return;
        if (node.idx != skipIdx) {
            double dx = x[node.idx] - qx, dy = y[node.idx] - qy;
            double dSq = dx * dx + dy * dy;
            if (dSq < bestSq[0]) bestSq[0] = dSq;
        }
        double diff = splitX ? (qx - x[node.idx]) : (qy - y[node.idx]);
        KDNode near = diff <= 0 ? node.left : node.right;
        KDNode far  = diff <= 0 ? node.right : node.left;
        nnSearch(near, qx, qy, skipIdx, x, y, !splitX, bestSq);
        if (diff * diff < bestSq[0])
            nnSearch(far, qx, qy, skipIdx, x, y, !splitX, bestSq);
    }

    // -------------------------------------------------------------------
    // KDE (Gaussian kernel)
    // -------------------------------------------------------------------

    private static double[] kde(double[] data, double[] gridX, double bw) {
        double[] out = new double[gridX.length];
        double inv = 1.0 / (bw * Math.sqrt(2 * Math.PI));
        double inv2bw2 = 1.0 / (2.0 * bw * bw);
        for (double d : data) {
            for (int j = 0; j < gridX.length; j++) {
                double diff = gridX[j] - d;
                out[j] += inv * Math.exp(-diff * diff * inv2bw2);
            }
        }
        double norm = data.length;
        for (int j = 0; j < out.length; j++) out[j] /= norm;
        return out;
    }

    // -------------------------------------------------------------------
    // Gaussian fit via Levenberg-Marquardt
    // -------------------------------------------------------------------

    private static double[] fitGaussian(double[] xd, double[] yd, double p0, double s0, double a0) {
        double[] init = { a0, p0, s0 };
        return fitGaussianDirect(xd, yd, init);
    }

    private static double[] fitGaussianDirect(double[] xd, double[] yd, double[] init) {
        try {
            org.apache.commons.math3.fitting.leastsquares.MultivariateJacobianFunction model =
                point -> {
                    double a   = point.getEntry(0);
                    double mu  = point.getEntry(1);
                    double sig = point.getEntry(2);
                    double[] vals = new double[xd.length];
                    double[][] jac = new double[xd.length][3];
                    for (int i = 0; i < xd.length; i++) {
                        double diff = xd[i] - mu;
                        double e = Math.exp(-diff * diff / (2.0 * sig * sig));
                        vals[i]   = a * e;
                        jac[i][0] = e;
                        jac[i][1] = a * e * diff / (sig * sig);
                        jac[i][2] = a * e * (diff * diff) / (sig * sig * sig);
                    }
                    return new org.apache.commons.math3.util.Pair<>(
                        new org.apache.commons.math3.linear.ArrayRealVector(vals, false),
                        new org.apache.commons.math3.linear.Array2DRowRealMatrix(jac, false)
                    );
                };

            double[] weights = new double[xd.length];
            Arrays.fill(weights, 1.0);

            LeastSquaresProblem problem = new LeastSquaresBuilder()
                .start(init)
                .model(model)
                .target(yd)
                .weight(new DiagonalMatrix(weights))
                .maxEvaluations(500)
                .maxIterations(500)
                .build();

            var optimum = new LevenbergMarquardtOptimizer().optimize(problem);
            double[] p = optimum.getPoint().toArray();
            // Sanity: sigma and amplitude must be positive, peak in reasonable range
            if (p[0] > 0 && p[2] > 0 && p[1] > 0) return p;
        } catch (Exception ignored) {}
        return null;
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    private static double[] gaussian(double[] x, double a, double mu, double sigma) {
        double[] out = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            double d = x[i] - mu;
            out[i] = a * Math.exp(-d * d / (2.0 * sigma * sigma));
        }
        return out;
    }

    private static double[] linspace(double lo, double hi, int n) {
        double[] out = new double[n];
        double step = (hi - lo) / (n - 1);
        for (int i = 0; i < n; i++) out[i] = lo + i * step;
        return out;
    }

    private static int argmax(double[] a) {
        int idx = 0;
        for (int i = 1; i < a.length; i++) if (a[i] > a[idx]) idx = i;
        return idx;
    }

    private static int[] windowMask(double[] x, double lo, double hi) {
        int cnt = 0;
        for (double v : x) if (v >= lo && v <= hi) cnt++;
        int[] out = new int[cnt];
        int j = 0;
        for (int i = 0; i < x.length; i++) if (x[i] >= lo && x[i] <= hi) out[j++] = i;
        return out;
    }

    private static double[] subset(double[] a, int[] idx) {
        double[] out = new double[idx.length];
        for (int i = 0; i < idx.length; i++) out[i] = a[idx[i]];
        return out;
    }

    private static double stdDev(double[] a) {
        if (a.length < 2) return 1.0;
        double mean = 0; for (double v : a) mean += v; mean /= a.length;
        double s = 0; for (double v : a) s += (v - mean) * (v - mean);
        return Math.sqrt(s / (a.length - 1));
    }

    private static double min(double[] a) { double m = a[0]; for (double v : a) if (v < m) m = v; return m; }
    private static double max(double[] a) { double m = a[0]; for (double v : a) if (v > m) m = v; return m; }

    /** Range query: returns indices of all points within radius r of (qx, qy). */
    public static int[] rangeQuery(double[] x, double[] y, double qx, double qy, double r, KDNode root) {
        java.util.List<Integer> result = new java.util.ArrayList<>();
        rangeSearch(root, qx, qy, r * r, x, y, true, result);
        return result.stream().mapToInt(Integer::intValue).toArray();
    }

    private static void rangeSearch(KDNode node, double qx, double qy, double rSq,
                                    double[] x, double[] y, boolean splitX,
                                    java.util.List<Integer> result) {
        if (node == null) return;
        double dx = x[node.idx] - qx, dy = y[node.idx] - qy;
        if (dx * dx + dy * dy <= rSq) result.add(node.idx);
        double diff = splitX ? (qx - x[node.idx]) : (qy - y[node.idx]);
        KDNode near = diff <= 0 ? node.left : node.right;
        KDNode far  = diff <= 0 ? node.right : node.left;
        rangeSearch(near, qx, qy, rSq, x, y, !splitX, result);
        if (diff * diff <= rSq)
            rangeSearch(far, qx, qy, rSq, x, y, !splitX, result);
    }

    /** Build a KD-tree from parallel x/y arrays — exposed for use by DbscanClusterer. */
    public static KDNode buildTree(double[] x, double[] y) {
        return buildKdTree(x, y, 0, x.length - 1, true, makeIndices(x.length));
    }
}
