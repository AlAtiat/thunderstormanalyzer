package com.thunderstorm.analyzer.pipeline;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Renders a super-resolution image from localisation coordinates.
 * Pipeline: 2D bin → histogram-equalise → fire colormap → scale bar → PNG.
 */
public class SrRenderer {

    // Fire colormap: 256 colours from black → red → orange → yellow → white
    private static final int[] FIRE_R = new int[256];
    private static final int[] FIRE_G = new int[256];
    private static final int[] FIRE_B = new int[256];

    static {
        // Keypoints: (index, R, G, B)
        int[][] kp = {
            {0,   0,   0,   0},
            {64,  255, 0,   0},
            {128, 255, 128, 0},
            {192, 255, 255, 0},
            {255, 255, 255, 255}
        };
        for (int k = 0; k < kp.length - 1; k++) {
            int i0 = kp[k][0], i1 = kp[k+1][0];
            for (int i = i0; i <= i1; i++) {
                double t = (double)(i - i0) / (i1 - i0);
                FIRE_R[i] = (int)(kp[k][1] + t * (kp[k+1][1] - kp[k][1]));
                FIRE_G[i] = (int)(kp[k][2] + t * (kp[k+1][2] - kp[k][2]));
                FIRE_B[i] = (int)(kp[k][3] + t * (kp[k+1][3] - kp[k][3]));
            }
        }
    }

    /**
     * Render and save PNG.
     *
     * @param x          x coords (nm)
     * @param y          y coords (nm)
     * @param binSizeNm  histogram bin size in nm (e.g. 10 nm)
     * @param baseNm     structure scale (spacing + tolerance) anchoring the scale bar;
     *                   ≤0 falls back to a round ≈15 % of image width
     * @param out        output PNG path
     * @return           the rendered BufferedImage
     */
    public static BufferedImage render(double[] x, double[] y, double binSizeNm,
                                       double baseNm, Path out)
            throws IOException {
        if (x.length == 0) return new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);

        double minX = min(x), minY = min(y);
        double maxX = max(x), maxY = max(y);

        int cols = (int)Math.ceil((maxX - minX) / binSizeNm) + 1;
        int rows = (int)Math.ceil((maxY - minY) / binSizeNm) + 1;
        // Cap at 4096 px in each dimension
        cols = Math.min(cols, 4096);
        rows = Math.min(rows, 4096);

        double[] grid = new double[cols * rows];
        for (int i = 0; i < x.length; i++) {
            int c = (int)((x[i] - minX) / binSizeNm);
            int r = (int)((y[i] - minY) / binSizeNm);
            c = Math.min(c, cols - 1);
            r = Math.min(r, rows - 1);
            grid[r * cols + c]++;
        }

        // Histogram equalisation
        double[] sorted = grid.clone();
        Arrays.sort(sorted);
        // Build CDF lookup: grid value → [0,255]
        // For each unique value compute its rank
        int[] mapped = histEq(grid, sorted, cols * rows);

        BufferedImage img = new BufferedImage(cols, rows, BufferedImage.TYPE_INT_RGB);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int v = mapped[r * cols + c];
                img.setRGB(c, r, new Color(FIRE_R[v], FIRE_G[v], FIRE_B[v]).getRGB());
            }
        }

        // Scale bar anchored to the structure scale (spacing + tolerance).
        drawScaleBar(img, binSizeNm, cols, baseNm);

        if (out != null) ImageIO.write(img, "PNG", out.toFile());
        return img;
    }

    private static int[] histEq(double[] grid, double[] sorted, int total) {
        // Find the max non-zero value index in sorted
        int nonZeroStart = 0;
        while (nonZeroStart < sorted.length && sorted[nonZeroStart] == 0) nonZeroStart++;

        int[] mapped = new int[total];
        if (nonZeroStart == sorted.length) return mapped; // all zero

        int denom = total - nonZeroStart - 1;   // guard: 0 when only one non-zero bin
        for (int i = 0; i < total; i++) {
            if (grid[i] == 0) { mapped[i] = 0; continue; }
            if (denom <= 0) { mapped[i] = 255; continue; }  // single non-zero bin → full intensity
            // Binary search rank
            int rank = upperBound(sorted, grid[i]);
            // Map rank to [1, 255]
            int v = 1 + (int)(254.0 * (rank - nonZeroStart) / denom);
            mapped[i] = Math.min(255, Math.max(1, v));
        }
        return mapped;
    }

    private static int upperBound(double[] sorted, double val) {
        int lo = 0, hi = sorted.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted[mid] <= val) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    private static void drawScaleBar(BufferedImage img, double binSizeNm, int cols, double baseNm) {
        double fieldWidthNm = cols * binSizeNm;
        double best = niceScaleBarNm(fieldWidthNm, baseNm);
        if (best <= 0) {
            // Fallback: round 15 % of width to nearest power-of-10 * (1, 2, 5).
            double barNm = (cols * 0.15) * binSizeNm;
            double mag = Math.pow(10, Math.floor(Math.log10(barNm)));
            double[] candidates = { mag, 2*mag, 5*mag, 10*mag };
            best = mag;
            for (double c : candidates) if (Math.abs(c - barNm) < Math.abs(best - barNm)) best = c;
        }
        int finalBarPx = (int)(best / binSizeNm);

        int h = img.getHeight();
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        int margin = 10, thickness = 4;
        g.fillRect(cols - margin - finalBarPx, h - margin - thickness, finalBarPx, thickness);
        g.setFont(new Font("SansSerif", Font.PLAIN, 10));
        g.drawString(scaleBarLabel(best), cols - margin - finalBarPx, h - margin - thickness - 2);
        g.dispose();
    }

    /**
     * Scale-bar length (nm) anchored to the structure scale {@code baseNm} (spacing + tol).
     * Small fields use exactly {@code baseNm}; wide fields grow to the smallest round
     * multiple k×baseNm (k ∈ 1,2,5,10,…) reaching ~12 % of the field width. Returns 0 when
     * {@code baseNm} is missing/non-positive so the caller falls back to its old logic.
     */
    public static double niceScaleBarNm(double fieldWidthNm, double baseNm) {
        if (!(baseNm > 0) || Double.isInfinite(baseNm)) return 0;
        double target = 0.12 * fieldWidthNm;
        if (baseNm >= target) return baseNm;
        int[] ks = { 1, 2, 5, 10, 20, 50, 100, 200, 500, 1000 };
        for (int k : ks) if (k * baseNm >= target) return k * baseNm;
        return 1000 * baseNm;
    }

    /** Format a scale-bar length in nm, switching to µm at/above 1000 nm. */
    public static String scaleBarLabel(double nm) {
        return nm >= 1000 ? String.format("%.0f µm", nm / 1000) : String.format("%.0f nm", nm);
    }

    private static double min(double[] a) { double m = a[0]; for (double v : a) if (v < m) m = v; return m; }
    private static double max(double[] a) { double m = a[0]; for (double v : a) if (v > m) m = v; return m; }
}
