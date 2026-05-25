package com.thunderstorm.analyzer.pipeline;

import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.SingularValueDecomposition;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Detects DNA origami structures (N collinear dots) from DBSCAN cluster scores.
 * Supports any N ≥ 2 via a recursive chain-extension algorithm.
 * Default N = 3 (triplet). N = 2 = dimer (pair), N = 4 = tetramer, etc.
 */
public class TripletDetector {

    public static class Triplet {
        public int[] clusterIds;                        // N cluster ids, ordered along axis
        public double[] centreX;                        // cluster centroid x (nm)
        public double[] centreY;                        // cluster centroid y (nm)
        public double score;
        public double spacingNm;                        // mean inter-spot distance
        public double angleDeg;                         // max deviation from collinearity (deg)
        public BlinkingScorer.ClusterScore[] clusterScores;
    }

    /**
     * Finds collinear N-spot DNA origami structures.
     *
     * @param x            all localisation x (nm)
     * @param y            all localisation y (nm)
     * @param labels       DBSCAN labels
     * @param scores       cluster scores, sorted desc by score
     * @param spacingNm    expected inter-spot spacing (nm)
     * @param spacingTolNm tolerance on spacing (nm)
     * @param angleTolDeg  max collinearity deviation per segment (degrees)
     * @param maxStructures cap on returned structures
     * @param nSpots       dots per origami structure (≥ 2; default 3)
     */
    public static List<Triplet> find(double[] x, double[] y, int[] labels,
                                     BlinkingScorer.ClusterScore[] scores,
                                     double spacingNm, double spacingTolNm,
                                     double angleTolDeg, int maxStructures, int nSpots) {
        // Guard: minimum 2 dots per structure
        if (nSpots < 2) nSpots = 2;
        int nClusters = scores.length;
        if (nClusters < nSpots) return Collections.emptyList();

        // Compute cluster centroids
        double[] cx = new double[nClusters];
        double[] cy = new double[nClusters];
        int[]    ns = new int[nClusters];
        for (int i = 0; i < x.length; i++) {
            int cid = labels[i];
            if (cid < 0 || cid >= nClusters) continue;
            cx[cid] += x[i]; cy[cid] += y[i]; ns[cid]++;
        }
        for (int c = 0; c < nClusters; c++) {
            if (ns[c] > 0) { cx[c] /= ns[c]; cy[c] /= ns[c]; }
        }

        NndAnalyzer.KDNode centTree = NndAnalyzer.buildTree(cx, cy);

        double lo = spacingNm - spacingTolNm;
        double hi = spacingNm + spacingTolNm;

        Map<Integer, BlinkingScorer.ClusterScore> scoreMap = new HashMap<>();
        for (BlinkingScorer.ClusterScore sc : scores) scoreMap.put(sc.clusterId, sc);

        double angleTolRad = Math.toRadians(angleTolDeg);
        List<Triplet> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (int i = 0; i < nClusters && result.size() < maxStructures * 3; i++) {
            int cidA = scores[i].clusterId;
            int[] candidatesB = NndAnalyzer.rangeQuery(cx, cy, cx[cidA], cy[cidA], hi, centTree);
            for (int cidB : candidatesB) {
                if (cidB == cidA) continue;
                double dAB = dist(cx[cidA], cy[cidA], cx[cidB], cy[cidB]);
                if (dAB < lo || dAB > hi) continue;

                if (nSpots == 2) {
                    // Dimer: pair at correct spacing is a complete structure
                    acceptChain(new int[]{cidA, cidB}, dAB, 0.0,
                        cx, cy, scoreMap, seen, result);
                } else {
                    // 3+ dots: extend chain recursively
                    extendChain(new int[]{cidA, cidB}, dAB, angleTolRad,
                        nSpots, lo, hi, spacingTolNm, cx, cy,
                        centTree, scoreMap, seen, result);
                }
            }
        }

        result.sort((a, b) -> Double.compare(b.score, a.score));
        return result.subList(0, Math.min(maxStructures, result.size()));
    }

    /**
     * Recursively extends a collinear chain by one dot at a time.
     * chain[] holds cluster ids built so far; target length = nSpots.
     */
    private static void extendChain(int[] chain, double lastSpacing,
                                    double angleTolRad, int nSpots,
                                    double lo, double hi, double spacingTolNm,
                                    double[] cx, double[] cy,
                                    NndAnalyzer.KDNode centTree,
                                    Map<Integer, BlinkingScorer.ClusterScore> scoreMap,
                                    Set<String> seen,
                                    List<Triplet> result) {
        int depth = chain.length;  // number of dots placed so far

        if (depth == nSpots) {
            // Chain is complete — compute max angle deviation across all segments
            double maxAngle = 0;
            for (int seg = 1; seg < depth - 1; seg++) {
                double v1x = cx[chain[seg]]   - cx[chain[seg - 1]];
                double v1y = cy[chain[seg]]   - cy[chain[seg - 1]];
                double v2x = cx[chain[seg+1]] - cx[chain[seg]];
                double v2y = cy[chain[seg+1]] - cy[chain[seg]];
                double cosA = (v1x*v2x + v1y*v2y)
                    / (Math.hypot(v1x, v1y) * Math.hypot(v2x, v2y) + 1e-12);
                cosA = Math.max(-1, Math.min(1, cosA));
                maxAngle = Math.max(maxAngle, Math.acos(Math.abs(cosA)));
            }
            acceptChain(chain, lastSpacing, maxAngle, cx, cy, scoreMap, seen, result);
            return;
        }

        // Predict next dot position: extend from chain[-2] through chain[-1]
        int prev = chain[depth - 2];
        int last = chain[depth - 1];
        double vx = cx[last] - cx[prev];
        double vy = cy[last] - cy[prev];
        double predX = cx[last] + vx;
        double predY = cy[last] + vy;

        int[] candidates = NndAnalyzer.rangeQuery(cx, cy, predX, predY,
            spacingTolNm * 1.5, centTree);

        for (int next : candidates) {
            // Must not already be in the chain
            boolean dup = false;
            for (int c : chain) if (c == next) { dup = true; break; }
            if (dup) continue;

            double dNext = dist(cx[last], cy[last], cx[next], cy[next]);
            if (dNext < lo || dNext > hi) continue;

            // Collinearity check for this new segment
            double v1x = vx, v1y = vy;
            double v2x = cx[next] - cx[last];
            double v2y = cy[next] - cy[last];
            double cosA = (v1x*v2x + v1y*v2y)
                / (Math.hypot(v1x, v1y) * Math.hypot(v2x, v2y) + 1e-12);
            cosA = Math.max(-1, Math.min(1, cosA));
            if (Math.acos(Math.abs(cosA)) > angleTolRad) continue;

            int[] extended = Arrays.copyOf(chain, depth + 1);
            extended[depth] = next;
            extendChain(extended, (lastSpacing * (depth - 1) + dNext) / depth,
                angleTolRad, nSpots, lo, hi, spacingTolNm,
                cx, cy, centTree, scoreMap, seen, result);
        }
    }

    /** Validates and records a completed N-dot chain. */
    private static void acceptChain(int[] chain, double meanSpacing, double maxAngleRad,
                                    double[] cx, double[] cy,
                                    Map<Integer, BlinkingScorer.ClusterScore> scoreMap,
                                    Set<String> seen, List<Triplet> result) {
        // Deduplication key: sorted ids joined by "_"
        int[] sorted = chain.clone();
        Arrays.sort(sorted);
        String key = Arrays.stream(sorted).mapToObj(String::valueOf)
                           .collect(Collectors.joining("_"));
        if (seen.contains(key)) return;
        seen.add(key);

        // Order by PCA projection along the main axis
        int[] ordered = pcaOrder(chain, cx, cy);

        // Collect scores — skip if any cluster has no score entry
        BlinkingScorer.ClusterScore[] clScores = new BlinkingScorer.ClusterScore[ordered.length];
        for (int i = 0; i < ordered.length; i++) {
            clScores[i] = scoreMap.get(ordered[i]);
            if (clScores[i] == null) return;
        }

        // Score: min(nCycles)² × mean(nCycles) × sum(quality)
        double minCycles  = Arrays.stream(clScores).mapToInt(s -> s.nCycles).min().orElse(0);
        double meanCycles = Arrays.stream(clScores).mapToDouble(s -> s.nCycles).average().orElse(0);
        double qualSum    = Arrays.stream(clScores).mapToDouble(s -> s.score).sum();
        double structScore = minCycles * minCycles * meanCycles * qualSum;

        Triplet t = new Triplet();
        t.clusterIds   = ordered;
        t.centreX      = Arrays.stream(ordered).mapToDouble(id -> cx[id]).toArray();
        t.centreY      = Arrays.stream(ordered).mapToDouble(id -> cy[id]).toArray();
        t.score        = structScore;
        t.spacingNm    = meanSpacing;
        t.angleDeg     = Math.toDegrees(maxAngleRad);
        t.clusterScores = clScores;
        result.add(t);
    }

    /** Orders N cluster ids along the first principal component (SVD). Works for any N ≥ 2. */
    private static int[] pcaOrder(int[] ids, double[] cx, double[] cy) {
        int n = ids.length;
        double mX = 0, mY = 0;
        for (int id : ids) { mX += cx[id]; mY += cy[id]; }
        mX /= n; mY /= n;

        double[][] mat = new double[n][2];
        for (int i = 0; i < n; i++) {
            mat[i][0] = cx[ids[i]] - mX;
            mat[i][1] = cy[ids[i]] - mY;
        }

        double[] pc;
        if (n == 2) {
            // SVD on 2×2 is fine but avoid the library call for this trivial case
            pc = new double[]{mat[1][0] - mat[0][0], mat[1][1] - mat[0][1]};
            double len = Math.hypot(pc[0], pc[1]);
            if (len > 0) { pc[0] /= len; pc[1] /= len; }
        } else {
            pc = new SingularValueDecomposition(
                     new Array2DRowRealMatrix(mat, false))
                     .getV().getColumn(0);
        }

        double[] proj = new double[n];
        for (int i = 0; i < n; i++) proj[i] = mat[i][0]*pc[0] + mat[i][1]*pc[1];

        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, Comparator.comparingDouble(k -> proj[k]));

        int[] result = new int[n];
        for (int i = 0; i < n; i++) result[i] = ids[order[i]];
        return result;
    }

    private static double dist(double x1, double y1, double x2, double y2) {
        double dx = x2 - x1, dy = y2 - y1;
        return Math.sqrt(dx*dx + dy*dy);
    }
}
