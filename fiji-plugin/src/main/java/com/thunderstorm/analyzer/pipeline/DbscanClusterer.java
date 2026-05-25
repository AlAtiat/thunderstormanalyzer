package com.thunderstorm.analyzer.pipeline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Pure-Java DBSCAN using the shared KD-tree from NndAnalyzer for range queries.
 * Mirrors Python sklearn.cluster.DBSCAN with the same adaptive-epsilon logic.
 */
public class DbscanClusterer {

    public static final int NOISE = -1;

    public static class ClusterResult {
        public int[] labels;       // NOISE (-1) or cluster id 0..N-1
        public int nClusters;
        public double eps;
        public int minPts;
    }

    /**
     * @param x            x-coordinates (nm)
     * @param y            y-coordinates (nm)
     * @param pixelSizeNm  camera pixel size, used for adaptive-epsilon
     * @param nndPeakNm    NND peak (spacing estimate); 0 to use pixelSize only
     * @param minSamples   minimum cluster members (default 3)
     */
    public static ClusterResult run(double[] x, double[] y,
                                    double pixelSizeNm, double nndPeakNm,
                                    int minSamples) {
        int n = x.length;

        // Adaptive epsilon: min(2×pixelSize, spacing/2.2), clamped ≥ 10 nm
        double eps = 2.0 * pixelSizeNm;
        if (nndPeakNm > 0) eps = Math.min(eps, nndPeakNm / 2.2);
        eps = Math.max(eps, 10.0);

        ClusterResult cr = new ClusterResult();
        cr.eps = eps;
        cr.minPts = minSamples;
        cr.labels = new int[n];
        Arrays.fill(cr.labels, -2); // -2 = unvisited

        NndAnalyzer.KDNode tree = NndAnalyzer.buildTree(x, y);

        int clusterId = 0;
        for (int i = 0; i < n; i++) {
            if (cr.labels[i] != -2) continue;
            int[] neighbours = NndAnalyzer.rangeQuery(x, y, x[i], y[i], eps, tree);
            if (neighbours.length < minSamples) {
                cr.labels[i] = NOISE;
                continue;
            }
            cr.labels[i] = clusterId;
            List<Integer> seeds = new ArrayList<>();
            for (int nb : neighbours) if (nb != i) seeds.add(nb);
            int si = 0;
            while (si < seeds.size()) {
                int q = seeds.get(si++);
                if (cr.labels[q] == NOISE) cr.labels[q] = clusterId;
                if (cr.labels[q] != -2) continue;
                cr.labels[q] = clusterId;
                int[] qNeighbours = NndAnalyzer.rangeQuery(x, y, x[q], y[q], eps, tree);
                if (qNeighbours.length >= minSamples) {
                    for (int nb : qNeighbours) if (cr.labels[nb] == -2 || cr.labels[nb] == NOISE)
                        seeds.add(nb);
                }
            }
            clusterId++;
        }
        // Any still-unvisited (isolated singleton) → noise
        for (int i = 0; i < n; i++) if (cr.labels[i] == -2) cr.labels[i] = NOISE;
        cr.nClusters = clusterId;
        return cr;
    }

    /** Returns the indices belonging to a given cluster id. */
    public static int[] clusterMembers(int[] labels, int clusterId) {
        int cnt = 0;
        for (int l : labels) if (l == clusterId) cnt++;
        int[] out = new int[cnt];
        int j = 0;
        for (int i = 0; i < labels.length; i++) if (labels[i] == clusterId) out[j++] = i;
        return out;
    }
}
