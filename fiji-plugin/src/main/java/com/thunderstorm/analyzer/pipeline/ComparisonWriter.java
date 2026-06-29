package com.thunderstorm.analyzer.pipeline;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.CategoryAxis;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.renderer.category.BarRenderer;
import org.jfree.data.category.DefaultCategoryDataset;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Generates cross-dataset comparison charts into outputDir/comparison/.
 * Called by AnalysisRunner after all datasets complete, when >= 2 datasets succeed.
 */
public class ComparisonWriter {

    private static final int W = 900;
    private static final int H = 600;

    // One color per dataset — cycles if more than palette size
    private static final Color[] PALETTE = {
        new Color(0x2271B2),  // blue
        new Color(0xE69F00),  // amber
        new Color(0x009E73),  // teal
        new Color(0xCC79A7),  // rose
        new Color(0x56B4E9),  // sky blue
        new Color(0xD55E00),  // vermillion
        new Color(0xF0E442),  // yellow
        new Color(0x000000),  // black
    };

    public static void write(List<AnalysisPipeline.AnalysisResult> results,
                             List<String> names,
                             Path outputDir,
                             boolean assumePhotons,
                             Consumer<String> log) throws IOException {
        log.accept("  Writing comparison charts...");
        String intensityUnit = com.thunderstorm.analyzer.model.QcParams.intensityUnit(assumePhotons);

        writeBarChart(results, names, outputDir.resolve("nnd_boxplot.png"),
            "NND Peak per Dataset", "NND Peak (nm)",
            r -> r.nnd != null ? r.nnd.peakNm : Double.NaN);

        writeBarChart(results, names, outputDir.resolve("uncertainty_boxplot.png"),
            "Mean Localisation Uncertainty", "Mean Uncertainty (nm)",
            r -> r.stats != null ? r.stats.meanUncertaintyNm : Double.NaN);

        writeBarChart(results, names, outputDir.resolve("sigma_boxplot.png"),
            "Mean PSF Sigma", "Mean Sigma (nm)",
            r -> r.stats != null ? r.stats.meanSigmaNm : Double.NaN);

        writeBarChart(results, names, outputDir.resolve("intensity_boxplot.png"),
            "Mean Intensity", "Mean Intensity (" + intensityUnit + ")",
            r -> r.stats != null ? r.stats.meanIntensity : Double.NaN);

        writeLocsOverlay(results, names, outputDir.resolve("locs_per_frame_overlay.png"), log);
        writeNndCdfOverlay(results, names, outputDir.resolve("nnd_cdf_overlay.png"), log);

        log.accept("  Comparison charts written.");
    }

    // ------------------------------------------------------------------
    // Bar chart (one bar per dataset)
    // ------------------------------------------------------------------

    @FunctionalInterface
    private interface ValueExtractor {
        double extract(AnalysisPipeline.AnalysisResult r);
    }

    private static void writeBarChart(List<AnalysisPipeline.AnalysisResult> results,
                                      List<String> names,
                                      Path out,
                                      String title,
                                      String yLabel,
                                      ValueExtractor extractor) throws IOException {
        DefaultCategoryDataset ds = new DefaultCategoryDataset();
        for (int i = 0; i < results.size(); i++) {
            double v = extractor.extract(results.get(i));
            if (!Double.isNaN(v)) {
                ds.addValue(v, "Value", names.get(i));
            }
        }
        if (ds.getRowCount() == 0) return;

        JFreeChart chart = ChartFactory.createBarChart(
            title, "Dataset", yLabel, ds,
            PlotOrientation.VERTICAL, false, false, false);

        styleBarChart(chart, results.size());
        save(chart, out);
    }

    private static void styleBarChart(JFreeChart chart, int nDatasets) {
        Font titleFont = new Font("SansSerif", Font.BOLD, 20);
        Font axisLabel = new Font("SansSerif", Font.PLAIN, 16);
        Font axisTick  = new Font("SansSerif", Font.PLAIN, 13);

        chart.setBackgroundPaint(Color.WHITE);
        chart.setBorderVisible(false);
        if (chart.getTitle() != null) chart.getTitle().setFont(titleFont);

        CategoryPlot plot = chart.getCategoryPlot();
        plot.setBackgroundPaint(Color.WHITE);
        plot.setOutlineVisible(false);
        plot.setDomainGridlinesVisible(false);
        plot.setRangeGridlinePaint(new Color(230, 230, 230));
        plot.setRangeGridlineStroke(new BasicStroke(0.7f));

        CategoryAxis domainAxis = plot.getDomainAxis();
        domainAxis.setLabelFont(axisLabel);
        domainAxis.setTickLabelFont(axisTick);
        domainAxis.setAxisLineVisible(true);
        domainAxis.setAxisLinePaint(Color.DARK_GRAY);

        NumberAxis rangeAxis = (NumberAxis) plot.getRangeAxis();
        rangeAxis.setLabelFont(axisLabel);
        rangeAxis.setTickLabelFont(axisTick);
        rangeAxis.setAxisLineVisible(true);
        rangeAxis.setAxisLinePaint(Color.DARK_GRAY);
        rangeAxis.setAutoRangeIncludesZero(true);

        BarRenderer renderer = (BarRenderer) plot.getRenderer();
        renderer.setShadowVisible(false);
        renderer.setBarPainter(new org.jfree.chart.renderer.category.StandardBarPainter());
        renderer.setMaximumBarWidth(0.25);

        // Color each bar by dataset index
        for (int col = 0; col < nDatasets; col++) {
            renderer.setSeriesPaint(col, PALETTE[col % PALETTE.length]);
            renderer.setSeriesOutlinePaint(col, Color.WHITE);
            renderer.setSeriesOutlineStroke(col, new BasicStroke(0.5f));
        }
    }

    // ------------------------------------------------------------------
    // Locs-per-frame overlay (one line series per dataset)
    // ------------------------------------------------------------------

    private static void writeLocsOverlay(List<AnalysisPipeline.AnalysisResult> results,
                                         List<String> names,
                                         Path out,
                                         Consumer<String> log) throws IOException {
        XYSeriesCollection ds = new XYSeriesCollection();
        for (int i = 0; i < results.size(); i++) {
            AnalysisPipeline.AnalysisResult r = results.get(i);
            if (r.qc == null || r.qc.frame == null || r.qc.frame.length == 0) continue;

            // Build counts-per-frame map
            double[] frames = r.qc.frame;
            int maxFrame = 0;
            for (double f : frames) if ((int) f > maxFrame) maxFrame = (int) f;
            int[] counts = new int[maxFrame + 1];
            for (double f : frames) counts[(int) f]++;

            XYSeries series = new XYSeries(names.get(i));
            for (int fr = 0; fr <= maxFrame; fr++) {
                series.add((double) fr, (double) counts[fr]);
            }
            ds.addSeries(series);
        }
        if (ds.getSeriesCount() == 0) return;

        JFreeChart chart = ChartFactory.createXYLineChart(
            "Localisations per Frame", "Frame", "Localisations",
            ds, PlotOrientation.VERTICAL, true, false, false);

        PlotWriter.styleChartFonts(chart);
        styleMultiLineSeries(chart, ds.getSeriesCount());
        save(chart, out);
    }

    // ------------------------------------------------------------------
    // NND KDE overlay (one series per dataset)
    // ------------------------------------------------------------------

    private static void writeNndCdfOverlay(List<AnalysisPipeline.AnalysisResult> results,
                                           List<String> names,
                                           Path out,
                                           Consumer<String> log) throws IOException {
        XYSeriesCollection ds = new XYSeriesCollection();
        for (int i = 0; i < results.size(); i++) {
            AnalysisPipeline.AnalysisResult r = results.get(i);
            if (r.nnd == null || r.nnd.kdeX == null || r.nnd.kdeY == null) continue;

            XYSeries series = new XYSeries(names.get(i));
            double[] kdeX = r.nnd.kdeX;
            double[] kdeY = r.nnd.kdeY;
            for (int k = 0; k < Math.min(kdeX.length, kdeY.length); k++) {
                series.add(kdeX[k], kdeY[k]);
            }
            ds.addSeries(series);
        }
        if (ds.getSeriesCount() == 0) return;

        JFreeChart chart = ChartFactory.createXYLineChart(
            "NND Distribution Overlay", "NND (nm)", "Density",
            ds, PlotOrientation.VERTICAL, true, false, false);

        PlotWriter.styleChartFonts(chart);
        styleMultiLineSeries(chart, ds.getSeriesCount());
        save(chart, out);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static void styleMultiLineSeries(JFreeChart chart, int nSeries) {
        org.jfree.chart.plot.XYPlot plot =
            (org.jfree.chart.plot.XYPlot) chart.getPlot();
        org.jfree.chart.renderer.xy.XYLineAndShapeRenderer rend =
            (org.jfree.chart.renderer.xy.XYLineAndShapeRenderer) plot.getRenderer();
        for (int i = 0; i < nSeries; i++) {
            rend.setSeriesPaint(i, PALETTE[i % PALETTE.length]);
            rend.setSeriesStroke(i, new BasicStroke(2.0f));
            rend.setSeriesShapesVisible(i, false);
        }
    }

    private static void save(JFreeChart chart, Path out) throws IOException {
        BufferedImage img = chart.createBufferedImage(W, H);
        ImageIO.write(img, "png", out.toFile());
    }
}
