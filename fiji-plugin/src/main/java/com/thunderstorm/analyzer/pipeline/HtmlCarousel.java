package com.thunderstorm.analyzer.pipeline;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

/**
 * Generates a self-contained HTML carousel for DNA origami structures (N dots).
 * Each slide mirrors the Python _write_interactive_viewer() layout:
 *   - Top-left: Full SR image with bounding box + dashed connectors
 *   - Top-right: Zoomed SR patch with coloured circles on each spot
 *   - Bottom: Stacked normalised blinking traces for all N spots
 */
public class HtmlCarousel {

    /**
     * @param structures   detected structures (already sorted by score desc)
     * @param x          all localisation x coords (nm)
     * @param y          all localisation y coords (nm)
     * @param frame      all frame numbers
     * @param intensity  all intensities
     * @param labels     DBSCAN cluster labels
     * @param srFull     pre-rendered full SR BufferedImage
     * @param binSizeNm  SR bin size (nm)
     * @param outputDir  where to write structure_carousel.html
     */
    public static void write(List<StructureDetector.Structure> structures,
                             double[] x, double[] y, double[] frame,
                             double[] intensity, int[] labels,
                             BufferedImage srFull,
                             double binSizeNm,
                             double spacingNm, double spacingTolNm,
                             Path outputDir) throws IOException {

        StringBuilder slides = new StringBuilder();
        for (int ti = 0; ti < structures.size(); ti++) {
            StructureDetector.Structure t = structures.get(ti);
            slides.append(buildSlide(ti, t, x, y, frame, intensity, labels, srFull, binSizeNm,
                spacingNm, spacingTolNm));
        }

        String html = TEMPLATE
            .replace("{{SLIDES}}", slides.toString())
            .replace("{{N}}", String.valueOf(structures.size()));

        Path out = outputDir.resolve("structure_carousel.html");
        Files.writeString(out, html, StandardCharsets.UTF_8);
    }

    private static String buildSlide(int idx, StructureDetector.Structure t,
                                     double[] x, double[] y, double[] frame,
                                     double[] intensity, int[] labels,
                                     BufferedImage srFull, double binSizeNm,
                                     double spacingNm, double spacingTolNm) throws IOException {

        // Build the showcase figure for this structure
        int nDots = t.clusterIds.length;
        BufferedImage showcase = buildShowcaseImage(t, x, y, frame, intensity, labels,
            srFull, binSizeNm, spacingNm, spacingTolNm, "Structure #" + (idx + 1));
        String showcaseB64 = toBase64(showcase);

        // Per-spot blinking trace mini-charts — one per dot (N dots)
        StringBuilder traceHtml = new StringBuilder();
        for (int k = 0; k < nDots; k++) {
            int cid = t.clusterIds[k];
            int[] members = DbscanClusterer.clusterMembers(labels, cid);
            double[] cf = new double[members.length];
            double[] ci = new double[members.length];
            for (int m = 0; m < members.length; m++) { cf[m] = frame[members[m]]; ci[m] = intensity[members[m]]; }

            // Binary on/off: add (frame, 1.0) for each detected frame
            int frameMin = (int) java.util.Arrays.stream(cf).min().orElse(0);
            int frameMax = (int) java.util.Arrays.stream(cf).max().orElse(0);
            boolean[] onOff = new boolean[frameMax - frameMin + 1];
            for (int m = 0; m < cf.length; m++) {
                int fi = (int) cf[m] - frameMin;
                if (fi >= 0 && fi < onOff.length) onOff[fi] = true;
            }
            org.jfree.data.xy.XYSeries series = new org.jfree.data.xy.XYSeries("Spot " + (k + 1));
            for (int f = 0; f < onOff.length; f++) {
                series.add(frameMin + f, onOff[f] ? 1.0 : 0.0);
            }

            org.jfree.data.xy.XYSeriesCollection ds = new org.jfree.data.xy.XYSeriesCollection(series);
            org.jfree.chart.JFreeChart traceChart = org.jfree.chart.ChartFactory.createXYStepChart(
                "Spot " + (k + 1), "Frame", "On/Off", ds,
                org.jfree.chart.plot.PlotOrientation.VERTICAL, false, false, false);
            traceChart.getXYPlot().getRangeAxis().setRange(0.0, 1.4);
            traceChart.setBackgroundPaint(Color.BLACK);
            traceChart.getXYPlot().setBackgroundPaint(new Color(20, 20, 20));
            traceChart.getXYPlot().setDomainGridlinePaint(new Color(60, 60, 60));
            traceChart.getXYPlot().setRangeGridlinePaint(new Color(60, 60, 60));
            ((org.jfree.chart.renderer.xy.XYLineAndShapeRenderer)
                traceChart.getXYPlot().getRenderer())
                .setSeriesPaint(0, PlotWriter.SPOT_COLORS[k % PlotWriter.SPOT_COLORS.length]);

            String traceB64 = Base64.getEncoder().encodeToString(PlotWriter.toBytes(traceChart));
            BlinkingScorer.ClusterScore sc = t.clusterScores[k];
            traceHtml.append(String.format(
                "<div class='trace'><img src='data:image/png;base64,%s'/>" +
                "<p>Dot #%d &nbsp;|&nbsp; Cycles: %d &nbsp;|&nbsp; Frames: %d &nbsp;|&nbsp; Score: %.0f</p></div>",
                traceB64, k + 1, sc.nCycles, sc.nUnique, sc.score));
        }

        return String.format(
            "<div class='slide' id='slide-%d'>" +
            "<h2>Structure #%d (%d dots) &nbsp;—&nbsp; Score: %.0f &nbsp;|&nbsp; Spacing: %.1f nm &nbsp;|&nbsp; Angle: %.1f°</h2>" +
            "<div class='showcase'><img src='data:image/png;base64,%s'/></div>" +
            "<div class='traces'>%s</div>" +
            "</div>",
            idx, idx + 1, nDots, t.score, t.spacingNm, t.angleDeg, showcaseB64, traceHtml);
    }

    /** Renders the showcase figure via PlotWriter.writeBlinkingShowcase and returns it as a BufferedImage. */
    private static BufferedImage buildShowcaseImage(StructureDetector.Structure t,
                                                    double[] allX, double[] allY,
                                                    double[] allFrames, double[] allIntensity,
                                                    int[] clusterLabels,
                                                    BufferedImage srFull, double binSizeNm,
                                                    double spacingNm, double spacingTolNm,
                                                    String label) throws IOException {
        // Delegate to a temp file then return the image
        java.io.File tmp = java.io.File.createTempFile("tsa_showcase_", ".png");
        tmp.deleteOnExit();
        PlotWriter.writeBlinkingShowcase(srFull, allX, allY, t, allFrames, allIntensity,
            clusterLabels, binSizeNm, spacingNm, spacingTolNm, label, tmp.toPath());
        return ImageIO.read(tmp);
    }

    private static String toBase64(BufferedImage img) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "PNG", bos);
        return Base64.getEncoder().encodeToString(bos.toByteArray());
    }

    // -------------------------------------------------------------------
    // HTML template
    // -------------------------------------------------------------------
    private static final String TEMPLATE = "<!DOCTYPE html>\n" +
        "<html><head><meta charset='UTF-8'/>\n" +
        "<title>DNA Origami Structure Carousel</title>\n" +
        "<style>\n" +
        "body{font-family:Arial,sans-serif;background:#111;color:#eee;margin:0;padding:20px;}\n" +
        "h1{text-align:center;color:#7ec8e3;}\n" +
        ".carousel{max-width:1400px;margin:0 auto;}\n" +
        ".slide{display:none;padding:20px;background:#1e1e1e;border-radius:10px;margin-bottom:20px;}\n" +
        ".slide.active{display:block;}\n" +
        ".slide h2{color:#7ec8e3;margin-top:0;font-size:1em;}\n" +
        ".showcase img{width:100%;border-radius:6px;}\n" +
        ".traces{display:flex;flex-wrap:wrap;gap:10px;margin-top:12px;}\n" +
        ".trace{flex:1;min-width:280px;background:#2a2a2a;border-radius:6px;padding:8px;}\n" +
        ".trace img{width:100%;}\n" +
        ".trace p{margin:4px 0 0;font-size:0.8em;color:#aaa;text-align:center;}\n" +
        ".nav{text-align:center;margin:20px 0;}\n" +
        ".nav button{background:#7ec8e3;border:none;color:#000;padding:10px 28px;" +
        "margin:0 6px;border-radius:6px;cursor:pointer;font-size:1em;font-weight:bold;}\n" +
        ".nav button:hover{background:#5ba8c7;}\n" +
        "#counter{color:#888;margin-top:8px;font-size:0.9em;}\n" +
        "</style></head>\n" +
        "<body>\n" +
        "<h1>DNA Origami Structure Showcase &nbsp;({{N}} structures)</h1>\n" +
        "<div class='carousel'>\n" +
        "{{SLIDES}}\n" +
        "</div>\n" +
        "<div class='nav'>\n" +
        "  <button onclick='prev()'>&#8592; Previous</button>\n" +
        "  <button onclick='next()'>Next &#8594;</button>\n" +
        "  <div id='counter'></div>\n" +
        "</div>\n" +
        "<script>\n" +
        "var cur=0;\n" +
        "var sl=document.querySelectorAll('.slide');\n" +
        "function show(i){sl.forEach(function(s){s.classList.remove('active');});" +
        "sl[i].classList.add('active');" +
        "document.getElementById('counter').textContent=(i+1)+' / '+sl.length;}\n" +
        "function next(){cur=(cur+1)%sl.length;show(cur);}\n" +
        "function prev(){cur=(cur-1+sl.length)%sl.length;show(cur);}\n" +
        "if(sl.length>0)show(0);\n" +
        "</script></body></html>";
}
