package com.thunderstorm.analyzer.pipeline;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYBarRenderer;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.data.statistics.HistogramDataset;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.util.Arrays;


/**
 * All JFreeChart-based plot generation. Saves PNGs at 1600×900 px.
 */
public class PlotWriter {

    private static final int CHART_W = 1600, CHART_H = 900;
    /** kept for legacy references; not used by save() */
    private static final int W = CHART_W, H = CHART_H;
    private static final Color STEEL_BLUE = new Color(70, 130, 180);
    private static final Color ORANGE     = new Color(220, 110, 40);
    private static final Color DARK_RED   = new Color(180, 30,  30);

    // -------------------------------------------------------------------
    // Histogram
    // -------------------------------------------------------------------

    public static void writeHistogram(double[] values, String xlabel, String title,
                                      int bins, Path out) throws IOException {
        if (values.length == 0) return;
        HistogramDataset ds = new HistogramDataset();
        ds.addSeries("data", values, bins);

        JFreeChart chart = ChartFactory.createHistogram(title, xlabel, "Count",
            ds, PlotOrientation.VERTICAL, false, false, false);

        XYPlot plot = chart.getXYPlot();
        plot.setBackgroundPaint(Color.WHITE);
        plot.setDomainGridlinePaint(new Color(200, 200, 200));
        plot.setRangeGridlinePaint(new Color(200, 200, 200));

        XYBarRenderer r = (XYBarRenderer) plot.getRenderer();
        r.setSeriesPaint(0, STEEL_BLUE);
        r.setDrawBarOutline(false);
        r.setShadowVisible(false);
        r.setBarAlignmentFactor(0.5);
        plot.setBackgroundPaint(Color.WHITE);
        plot.setOutlineVisible(false);
        plot.setDomainGridlinePaint(new Color(230, 230, 230));
        plot.setRangeGridlinePaint(new Color(230, 230, 230));
        plot.setDomainGridlineStroke(new BasicStroke(0.7f));
        plot.setRangeGridlineStroke(new BasicStroke(0.7f));

        // Integer count on Y axis
        ((NumberAxis) plot.getRangeAxis()).setStandardTickUnits(NumberAxis.createIntegerTickUnits());

        // Stats annotation
        double mean = QcFilter.mean(values);
        double median = QcFilter.median(values);
        double std  = QcFilter.std(values, mean);
        String ann = String.format("n=%,d  mean=%.1f  median=%.1f  σ=%.1f",
            values.length, mean, median, std);
        chart.addSubtitle(new org.jfree.chart.title.TextTitle(ann, new Font("SansSerif", Font.PLAIN, 14)));

        styleChartFonts(chart);
        save(chart, out);
    }

    // -------------------------------------------------------------------
    // NND plot: KDE + Gaussian fit overlay
    // -------------------------------------------------------------------

    public static void writeNndPlot(double[] kdeX, double[] kdeY,
                                    double[] fitX, double[] fitY,
                                    double peakNm, double spacingNm, double spacingTolNm,
                                    Path out) throws IOException {
        XYSeriesCollection ds = new XYSeriesCollection();
        XYSeries kde = new XYSeries("KDE");
        for (int i = 0; i < kdeX.length; i++) kde.add(kdeX[i], kdeY[i]);
        ds.addSeries(kde);

        if (fitX != null && fitX.length > 0) {
            XYSeries fit = new XYSeries(String.format("Fit peak=%.1f nm", peakNm));
            for (int i = 0; i < fitX.length; i++) fit.add(fitX[i], fitY[i]);
            ds.addSeries(fit);
        }

        JFreeChart chart = ChartFactory.createXYLineChart(
            "Nearest-Neighbour Distance", "NND (nm)", "Density",
            ds, PlotOrientation.VERTICAL, true, false, false);

        XYPlot plot = chart.getXYPlot();
        plot.setBackgroundPaint(Color.WHITE);
        XYLineAndShapeRenderer rend = (XYLineAndShapeRenderer) plot.getRenderer();
        rend.setSeriesPaint(0, STEEL_BLUE);
        rend.setSeriesStroke(0, new BasicStroke(2f));
        if (ds.getSeriesCount() > 1) {
            rend.setSeriesPaint(1, DARK_RED);
            rend.setSeriesStroke(1, new BasicStroke(2f, BasicStroke.CAP_BUTT,
                BasicStroke.JOIN_MITER, 10f, new float[]{6f, 3f}, 0f));
        }
        // Suppress trailing zeros on nm axes (e.g. "80" not "80.0")
        DecimalFormat nmFmt = new DecimalFormat("0.#");
        NumberAxis domainAxis = (NumberAxis) plot.getDomainAxis();
        domainAxis.setNumberFormatOverride(nmFmt);
        ((NumberAxis) plot.getRangeAxis()).setNumberFormatOverride(new DecimalFormat("0.###"));

        // Stretch the x-axis to the target window (spacing + tolerance) so the plot is
        // comparable to the target band. No markers/labels; when no valid spacing is set,
        // leave the default auto-range (KDE grid, already capped at P90 in NndAnalyzer).
        if (Double.isFinite(spacingNm) && spacingNm > 0) {
            double hi = spacingNm + (spacingTolNm > 0 ? spacingTolNm : 0);
            domainAxis.setAutoRange(false);
            domainAxis.setRange(0.0, hi);
        }

        styleChartFonts(chart);
        save(chart, out);
    }

    // -------------------------------------------------------------------
    // Locs per frame
    // -------------------------------------------------------------------

    public static void writeLocsPerFrame(double[] frames, Path out) throws IOException {
        if (frames.length == 0) return;
        // Count locs per frame index
        int maxFrame = 0;
        for (double f : frames) if ((int)f > maxFrame) maxFrame = (int)f;
        int[] counts = new int[maxFrame + 1];
        for (double f : frames) counts[(int)f]++;

        XYSeries series = new XYSeries("Locs/frame");
        for (int i = 0; i <= maxFrame; i++) series.add(i, counts[i]);
        XYSeriesCollection ds = new XYSeriesCollection(series);

        JFreeChart chart = ChartFactory.createXYLineChart(
            "Localisations per Frame", "Frame", "Count",
            ds, PlotOrientation.VERTICAL, false, false, false);

        styleLineChart(chart, STEEL_BLUE);
        XYPlot lpfPlot = chart.getXYPlot();
        ((NumberAxis) lpfPlot.getDomainAxis()).setStandardTickUnits(NumberAxis.createIntegerTickUnits());
        ((NumberAxis) lpfPlot.getRangeAxis()).setStandardTickUnits(NumberAxis.createIntegerTickUnits());
        styleChartFonts(chart);
        save(chart, out);
    }

    // -------------------------------------------------------------------
    // Intensity vs time (scatter)
    // -------------------------------------------------------------------

    public static void writeIntensityVsTime(double[] frames, double[] intensity,
                                            String intensityUnit, Path out) throws IOException {
        if (frames.length == 0) return;
        int maxFrame = 0;
        for (double f : frames) if ((int)f > maxFrame) maxFrame = (int)f;
        double[] sum = new double[maxFrame + 1];
        int[]    cnt = new int[maxFrame + 1];
        for (int i = 0; i < frames.length; i++) {
            int fi = (int) frames[i];
            if (fi >= 0 && fi <= maxFrame) { sum[fi] += intensity[i]; cnt[fi]++; }
        }
        XYSeries series = new XYSeries("Mean intensity");
        for (int f = 0; f <= maxFrame; f++)
            if (cnt[f] > 0) series.add(f, sum[f] / cnt[f]);
        XYSeriesCollection ds = new XYSeriesCollection(series);

        JFreeChart chart = ChartFactory.createXYLineChart(
            "Mean Intensity per Frame", "Frame", "Intensity (" + intensityUnit + ")",
            ds, PlotOrientation.VERTICAL, false, false, false);
        styleLineChart(chart, STEEL_BLUE);
        XYPlot plot = chart.getXYPlot();
        ((NumberAxis) plot.getDomainAxis()).setStandardTickUnits(NumberAxis.createIntegerTickUnits());
        ((NumberAxis) plot.getRangeAxis()).setNumberFormatOverride(new DecimalFormat("#,##0"));
        styleChartFonts(chart);
        save(chart, out);
    }

    // -------------------------------------------------------------------
    // Blinking trace for a single cluster
    // -------------------------------------------------------------------

    public static void writeBlinkingTrace(double[] frames, double[] intensities,
                                          int clusterId, Path out) throws IOException {
        XYSeries series = new XYSeries("Cluster " + clusterId);
        Integer[] idx = new Integer[frames.length];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) -> Double.compare(frames[a], frames[b]));
        for (int i : idx) series.add(frames[i], intensities[i]);

        XYSeriesCollection ds = new XYSeriesCollection(series);
        JFreeChart chart = ChartFactory.createXYLineChart(
            "Blinking Trace — Cluster " + clusterId, "Frame", "Intensity",
            ds, PlotOrientation.VERTICAL, false, false, false);
        styleLineChart(chart, ORANGE);
        save(chart, out);
    }

    // -------------------------------------------------------------------
    // Blinking showcase: 3-panel figure matching Python _plot_blinking_showcase
    // Panel layout: [SR full | SR zoom] on top, [stacked traces] on bottom
    // -------------------------------------------------------------------

    static final Color[] SPOT_COLORS = {
        new Color(0xF4, 0x43, 0x36),  // #F44336
        new Color(0x4C, 0xAF, 0x50),  // #4CAF50
        new Color(0x21, 0x96, 0xF3),  // #2196F3
    };

    /**
     * Renders the blinking showcase figure (3200×2400 px @ 200 DPI, black background).
     * Matches Python figsize=(16,12), dpi=200 output exactly.
     *
     * @param srFull      Full SR BufferedImage (already rendered at binSizeNm)
     * @param allX        All x coords used for SR (nm)
     * @param allY        All y coords
     * @param structure     The top structure
     * @param allFrames   frame array of ALL localisations
     * @param binSizeNm   bin size used for the SR render
     * @param datasetName dataset label for title
     * @param out         output PNG path
     */
    public static void writeBlinkingShowcase(
            BufferedImage srFull,
            double[] allX, double[] allY,
            StructureDetector.Structure structure,
            double[] allFrames, double[] allIntensity,
            int[] clusterLabels,
            double binSizeNm,
            double spacingNm, double spacingTolNm,
            String datasetName,
            Path out) throws IOException {

        // 3200×2400 = 16×12 inches @ 200 DPI — matches Python figsize=(16,12), dpi=200
        int W = 3200, H = 2400;
        // height_ratios=[3,1] → 75% SR, 25% traces
        int srH    = (int)(H * 0.75);   // 1800 px
        int traceH = H - srH;           // 600 px
        // width_ratios=[3,2] → 60% full SR, 40% zoom
        int mainW = (int)(W * 0.60);    // 1920 px
        int zoomW = W - mainW;          // 1280 px

        BufferedImage canvas = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        // Black background (savefig facecolor="black")
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, W, H);

        // --- Panel 1: Full SR image (top-left) ---
        Image scaledFull = srFull.getScaledInstance(mainW, srH, Image.SCALE_AREA_AVERAGING);
        g.drawImage(scaledFull, 0, 0, null);

        // Compute SR coordinate → pixel mapping for the full panel
        double minX = arrMin(allX), minY = arrMin(allY);
        double maxX = arrMax(allX), maxY = arrMax(allY);
        double scaleX = mainW / (maxX - minX);
        double scaleY = srH   / (maxY - minY);

        // Larger pad gives context around the structure so it appears centred, not filling the panel
        double padNm = 400.0;
        double bx0 = arrMin(structure.centreX) - padNm;
        double bx1 = arrMax(structure.centreX) + padNm;
        double by0 = arrMin(structure.centreY) - padNm;
        double by1 = arrMax(structure.centreY) + padNm;

        int rx0 = clamp((int)((bx0 - minX) * scaleX), 0, mainW - 1);
        int ry0 = clamp((int)((by0 - minY) * scaleY), 0, srH   - 1);
        int rx1 = clamp((int)((bx1 - minX) * scaleX), 0, mainW - 1);
        int ry1 = clamp((int)((by1 - minY) * scaleY), 0, srH   - 1);

        // White selection rectangle on full SR
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(2.0f));
        g.drawRect(rx0, ry0, rx1 - rx0, ry1 - ry0);

        // Title: fontsize ~20 pt at 200 dpi ≈ 40 px; white
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 40));
        g.drawString(datasetName + " — DNA origami structure", 24, 52);

        // --- Panel 2: Zoomed SR patch (top-right), inset with margin so connectors
        //     visibly travel to the patch corners rather than the full-panel edges ---
        int zoomMargin = srH / 8;   // ~12.5% padding on all sides inside the zoom cell
        int zoomImgX = mainW + zoomMargin;
        int zoomImgY = zoomMargin;
        int zoomImgW = zoomW - 2 * zoomMargin;
        int zoomImgH = srH  - 2 * zoomMargin;

        int cropX = clamp((int)((bx0 - minX) / binSizeNm), 0, srFull.getWidth() - 1);
        int cropY = clamp((int)((by0 - minY) / binSizeNm), 0, srFull.getHeight() - 1);
        int cropW = clamp((int)((bx1 - bx0)  / binSizeNm), 1, srFull.getWidth()  - cropX);
        int cropH = clamp((int)((by1 - by0)  / binSizeNm), 1, srFull.getHeight() - cropY);
        BufferedImage patch = srFull.getSubimage(cropX, cropY, cropW, cropH);
        // Fill zoom cell background first so the margin area is black
        g.setColor(Color.BLACK);
        g.fillRect(mainW, 0, zoomW, srH);
        Image scaledZoom = patch.getScaledInstance(zoomImgW, zoomImgH, Image.SCALE_AREA_AVERAGING);
        g.drawImage(scaledZoom, zoomImgX, zoomImgY, null);

        double zoomScaleX = (double) zoomImgW / (bx1 - bx0);
        double zoomScaleY = (double) zoomImgH / (by1 - by0);

        // White border around the inset zoom image
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(3.0f));
        g.drawRect(zoomImgX, zoomImgY, zoomImgW - 1, zoomImgH - 1);

        // Dashed connector lines from bounding-box corners → zoom image corners
        g.setColor(new Color(255, 255, 255, (int)(255 * 0.7)));
        float[] dash = {6f, 3f};
        g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, dash, 0f));
        g.drawLine(rx1, ry0, zoomImgX, zoomImgY);
        g.drawLine(rx1, ry1, zoomImgX, zoomImgY + zoomImgH);

        // --- Nm coordinate axis labels on the zoom panel ---
        g.setFont(new Font("SansSerif", Font.PLAIN, 24));
        FontMetrics fmAxis = g.getFontMetrics();
        int tickLen = 12;
        double tickStep = 100.0; // nm per tick

        // X-axis: ticks and labels BELOW the border (in the bottom margin)
        double xTickStart = Math.ceil(bx0 / tickStep) * tickStep;
        for (double xNm = xTickStart; xNm <= bx1; xNm += tickStep) {
            int px = zoomImgX + (int)((xNm - bx0) * zoomScaleX);
            if (px < zoomImgX || px > zoomImgX + zoomImgW) continue;
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(1.5f));
            g.drawLine(px, zoomImgY + zoomImgH + 3, px, zoomImgY + zoomImgH + tickLen);
            String lbl = String.format("%.0f", xNm);
            int lw = fmAxis.stringWidth(lbl);
            g.drawString(lbl, px - lw / 2, zoomImgY + zoomImgH + tickLen + fmAxis.getAscent());
        }

        // Y-axis: ticks and labels to the LEFT of the border (in the left margin)
        double yTickStart = Math.ceil(by0 / tickStep) * tickStep;
        for (double yNm = yTickStart; yNm <= by1; yNm += tickStep) {
            int py = zoomImgY + (int)((yNm - by0) * zoomScaleY);
            if (py < zoomImgY || py > zoomImgY + zoomImgH) continue;
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(1.5f));
            g.drawLine(zoomImgX - tickLen, py, zoomImgX - 3, py);
            String lbl = String.format("%.0f", yNm);
            int lw = fmAxis.stringWidth(lbl);
            g.drawString(lbl, zoomImgX - tickLen - 4 - lw, py + fmAxis.getAscent() / 2);
        }

        // Spot circles: r=30 nm → pixels; linewidth=2.0; alpha=0.9
        g.setStroke(new BasicStroke(2.0f));
        int nSpotsDraw = Math.min(structure.centreX.length, SPOT_COLORS.length);
        for (int k = 0; k < nSpotsDraw; k++) {
            double cx = structure.centreX[k];
            double cy = structure.centreY[k];
            int px = zoomImgX + (int)((cx - bx0) * zoomScaleX);
            int py = zoomImgY + (int)((cy - by0) * zoomScaleY);
            double rNm = 30.0;
            int rad = Math.max((int)(rNm * Math.min(zoomScaleX, zoomScaleY)), 8);
            Color col = SPOT_COLORS[k % SPOT_COLORS.length];
            Composite origComp = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.9f));
            g.setColor(col);
            g.drawOval(px - rad, py - rad, rad * 2, rad * 2);
            g.setComposite(origComp);
        }

        // Scale bar positioned in lower-left of zoom image, anchored to the structure
        // scale (spacing + tolerance); falls back to 100 nm when no spacing is set.
        double barLenNm = SrRenderer.niceScaleBarNm(bx1 - bx0, spacingNm + spacingTolNm);
        if (barLenNm <= 0) barLenNm = 100.0;
        double barXnm = bx0 + padNm * 0.15;
        double barYnm = by1 - padNm * 0.25;
        int barX  = zoomImgX + (int)((barXnm - bx0) * zoomScaleX);
        int barY  = zoomImgY + (int)((barYnm - by0) * zoomScaleY);
        int barPx = (int)(barLenNm * zoomScaleX);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(2.5f));
        g.drawLine(barX, barY, barX + barPx, barY);
        int labelFontSz = 28;
        g.setFont(new Font("SansSerif", Font.PLAIN, labelFontSz));
        FontMetrics fm = g.getFontMetrics();
        String barLabel = SrRenderer.scaleBarLabel(barLenNm);
        int labelW = fm.stringWidth(barLabel);
        g.drawString(barLabel, barX + barPx / 2 - labelW / 2, barY - 6);

        // Zoom panel subtitle inside the inset
        g.setFont(new Font("SansSerif", Font.BOLD, 36));
        g.drawString("Magnified", zoomImgX + 20, zoomImgY + 46);

        // --- Panel 3: Stacked blinking traces (bottom, full width) ---
        drawStackedTraces(g, structure, allFrames, allIntensity, clusterLabels,
            0, srH, W, traceH);

        g.dispose();
        ImageIO.write(canvas, "PNG", out.toFile());
    }

    private static void drawStackedTraces(Graphics2D g,
                                          StructureDetector.Structure structure,
                                          double[] allFrames, double[] allIntensity,
                                          int[] clusterLabels,
                                          int x, int y, int w, int h) {
        // Background: #0a0a0a
        g.setColor(new Color(10, 10, 10));
        g.fillRect(x, y, w, h);

        int frameMin = (int) arrMin(allFrames);
        int frameMax = (int) arrMax(allFrames);
        int nFrames  = frameMax - frameMin + 1;

        int nDots = structure.clusterIds.length;

        // Build per-spot mean intensity per frame
        double[][] traces = new double[nDots][nFrames];
        int[][]    counts = new int[nDots][nFrames];
        int[]      nCycles = new int[nDots];

        for (int k = 0; k < nDots; k++) {
            int[] members = DbscanClusterer.clusterMembers(clusterLabels, structure.clusterIds[k]);
            for (int m : members) {
                int fi = (int) allFrames[m] - frameMin;
                if (fi >= 0 && fi < nFrames) {
                    traces[k][fi] += allIntensity[m];
                    counts[k][fi]++;
                }
            }
            for (int f = 0; f < nFrames; f++)
                if (counts[k][f] > 0) traces[k][f] /= counts[k][f];
            nCycles[k] = structure.clusterScores[k].nCycles;
        }

        // Font sizes scaled for 3200×2400 canvas (≈200 DPI)
        int labelFontSz  = 32;
        int titleFontSz  = 36;
        int yLabelFontSz = 28;
        int legendFontSz = 26;

        int marginLeft   = 160;
        int marginRight  = 40;
        int marginTop    = 70;
        int marginBottom = 90;

        int plotW = w - marginLeft - marginRight;
        int plotH = h - marginTop - marginBottom;

        // Title: "Blinking traces — all N dots", white, fontsize 10 → 20 px
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.PLAIN, titleFontSz));
        String title = "Blinking traces — all " + nDots + " dots";
        FontMetrics fmTitle = g.getFontMetrics();
        g.drawString(title, x + w / 2 - fmTitle.stringWidth(title) / 2, y + marginTop - 10);

        // Y-axis label: rotated 90°, "Norm. intensity + vertical offset"
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setColor(Color.WHITE);
        g2.setFont(new Font("SansSerif", Font.PLAIN, yLabelFontSz));
        String yLabel = "Norm. intensity + vertical offset";
        FontMetrics fmY = g2.getFontMetrics();
        int yLabelX = x + 28;
        int yLabelY = y + marginTop + plotH / 2 + fmY.stringWidth(yLabel) / 2;
        g2.translate(yLabelX, yLabelY);
        g2.rotate(-Math.PI / 2);
        g2.drawString(yLabel, 0, 0);
        g2.dispose();

        // X-axis label: "Frame", centered, white, fontsize 10 → 20 px
        g.setFont(new Font("SansSerif", Font.PLAIN, labelFontSz));
        FontMetrics fmX = g.getFontMetrics();
        String xLabel = "Frame";
        g.setColor(Color.WHITE);
        g.drawString(xLabel, x + marginLeft + plotW / 2 - fmX.stringWidth(xLabel) / 2,
            y + h - 12);

        // Draw traces
        for (int k = 0; k < nDots; k++) {
            double peak = arrMax(traces[k]);
            if (peak <= 0) peak = 1.0;
            // Python: offset = (n_spots-1-i)*0.3
            double offset = (nDots - 1 - k) * 0.3;
            Color col = SPOT_COLORS[k % SPOT_COLORS.length];

            int[] lineX = new int[nFrames];
            int[] lineY = new int[nFrames];
            for (int f = 0; f < nFrames; f++) {
                double norm = traces[k][f] / peak;
                double v    = Math.min(norm + offset, 1.0);
                lineX[f] = x + marginLeft + (nFrames > 1 ? (int)(plotW * f / (double)(nFrames - 1)) : 0);
                lineY[f] = y + marginTop + (int)(plotH * (1.0 - v));
            }

            int baselineY = y + marginTop + (int)(plotH * (1.0 - offset));

            // Fill: alpha=0.35 → 89/255
            int[] fillX = new int[nFrames + 2];
            int[] fillY = new int[nFrames + 2];
            System.arraycopy(lineX, 0, fillX, 0, nFrames);
            System.arraycopy(lineY, 0, fillY, 0, nFrames);
            fillX[nFrames]     = lineX[nFrames - 1];
            fillY[nFrames]     = baselineY;
            fillX[nFrames + 1] = lineX[0];
            fillY[nFrames + 1] = baselineY;

            g.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), 89));
            g.fillPolygon(fillX, fillY, nFrames + 2);

            // Line: 3.5f is visible on 3200px canvas (matches Python linewidth=1.2 @ 200 DPI)
            g.setColor(col);
            g.setStroke(new BasicStroke(3.5f));
            g.drawPolyline(lineX, lineY, nFrames);
        }

        // Legend box: dark bg #222222, border #555555
        int legendEntryH = legendFontSz + 10;
        int legendPad = 16;
        int legendW = 380;
        int legendH = legendPad * 2 + nDots * legendEntryH;
        int legendX = x + w - marginRight - legendW - 20;
        int legendY = y + marginTop + 16;

        g.setColor(new Color(0x22, 0x22, 0x22));
        g.fillRect(legendX, legendY, legendW, legendH);
        g.setColor(new Color(0x55, 0x55, 0x55));
        g.setStroke(new BasicStroke(1.0f));
        g.drawRect(legendX, legendY, legendW, legendH);

        g.setFont(new Font("SansSerif", Font.PLAIN, legendFontSz));
        for (int k = 0; k < nDots; k++) {
            Color col = SPOT_COLORS[k % SPOT_COLORS.length];
            g.setColor(col);
            int entryY = legendY + legendPad + k * legendEntryH + legendFontSz;
            g.drawString("Dot #" + (k + 1) + "  (" + nCycles[k] + " cycles)",
                legendX + legendPad, entryY);
        }

        // X-axis tick labels: frame min/max
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.PLAIN, labelFontSz - 4));
        g.drawString(String.valueOf(frameMin), x + marginLeft, y + marginTop + plotH + 18);
        String maxStr = String.valueOf(frameMax);
        FontMetrics fmTick = g.getFontMetrics();
        g.drawString(maxStr, x + marginLeft + plotW - fmTick.stringWidth(maxStr),
            y + marginTop + plotH + 18);
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    private static double arrMin(double[] a) { double m = a[0]; for (double v : a) if (v < m) m = v; return m; }
    private static double arrMax(double[] a) { double m = a[0]; for (double v : a) if (v > m) m = v; return m; }
    private static double arrMax(double[] a, int len) { double m = 0; for (int i = 0; i < len; i++) if (a[i] > m) m = a[i]; return m; }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    static void styleLineChart(JFreeChart chart, Color color) {
        XYPlot plot = chart.getXYPlot();
        XYLineAndShapeRenderer rend = (XYLineAndShapeRenderer) plot.getRenderer();
        rend.setSeriesPaint(0, color);
        rend.setSeriesStroke(0, new BasicStroke(2.0f));
        rend.setSeriesShapesVisible(0, false);
    }

    /** Publication-quality styling: white background, minimal frame, clean axes. */
    static void styleChartFonts(JFreeChart chart) {
        Font titleFont = new Font("SansSerif", Font.BOLD,  20);
        Font axisLabel = new Font("SansSerif", Font.PLAIN, 16);
        Font axisTick  = new Font("SansSerif", Font.PLAIN, 13);

        chart.setBackgroundPaint(Color.WHITE);
        chart.setBorderVisible(false);
        if (chart.getTitle() != null) chart.getTitle().setFont(titleFont);
        if (chart.getLegend() != null) {
            chart.getLegend().setItemFont(new Font("SansSerif", Font.PLAIN, 13));
            chart.getLegend().setBackgroundPaint(Color.WHITE);
            chart.getLegend().setFrame(new org.jfree.chart.block.BlockBorder(Color.LIGHT_GRAY));
        }

        XYPlot plot = chart.getXYPlot();
        plot.setBackgroundPaint(Color.WHITE);
        plot.setOutlineVisible(false);
        plot.setDomainGridlinePaint(new Color(230, 230, 230));
        plot.setRangeGridlinePaint(new Color(230, 230, 230));
        plot.setDomainGridlineStroke(new BasicStroke(0.7f));
        plot.setRangeGridlineStroke(new BasicStroke(0.7f));
        plot.getDomainAxis().setLabelFont(axisLabel);
        plot.getDomainAxis().setTickLabelFont(axisTick);
        plot.getDomainAxis().setAxisLineVisible(true);
        plot.getDomainAxis().setAxisLinePaint(Color.DARK_GRAY);
        plot.getRangeAxis().setLabelFont(axisLabel);
        plot.getRangeAxis().setTickLabelFont(axisTick);
        plot.getRangeAxis().setAxisLineVisible(true);
        plot.getRangeAxis().setAxisLinePaint(Color.DARK_GRAY);
    }

    static void save(JFreeChart chart, Path out) throws IOException {
        chart.setBackgroundPaint(Color.WHITE);
        BufferedImage img = chart.createBufferedImage(CHART_W, CHART_H);
        ImageIO.write(img, "PNG", out.toFile());
    }

    /** Encode a chart to PNG bytes (base64 embedding in HTML). */
    public static byte[] toBytes(JFreeChart chart) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(chart.createBufferedImage(CHART_W, CHART_H), "PNG", bos);
        return bos.toByteArray();
    }
}
