package com.thunderstorm.analyzer.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reads stats.json and PNG images written by the Java pipeline. */
public class ResultsLoader {

    // -----------------------------------------------------------------------
    // Result POJO
    // -----------------------------------------------------------------------
    public static class DatasetResult {
        public String  name          = "";
        public String  error         = null;

        // Stats (populated from stats.json by load())
        public int     nRaw          = 0;
        public int     nFiltered     = 0;
        public int     n3sigma       = 0;
        public double  meanUncertainty   = 0;
        public double  medianUncertainty = 0;
        public double  meanSigma         = 0;
        public double  meanIntensity     = 0;
        public double  nndPeakNm         = 0;
        public double  nndSigmaNm        = 0;
        public int     nClusters         = 0;
        public int     nStructures       = 0;

        // Directory where all outputs live
        public Path    outputDir     = null;

        // Named image paths for display in ResultsPanel
        public Map<String, Path> plots = new LinkedHashMap<>();

        // Raw stats map for display table
        public Map<String, String> stats = new LinkedHashMap<>();
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    public static DatasetResult load(Path statsJsonPath, Path outputDir) {
        DatasetResult r = new DatasetResult();
        r.outputDir = outputDir;

        if (!Files.exists(statsJsonPath)) {
            r.error = "stats.json not found: " + statsJsonPath;
            return r;
        }

        try (FileReader fr = new FileReader(statsJsonPath.toFile())) {
            JsonObject json = JsonParser.parseReader(fr).getAsJsonObject();
            r.name               = getString(json, "datasetName", "");
            r.nRaw               = getInt(json, "nRaw");
            r.nFiltered          = getInt(json, "nFiltered");
            r.n3sigma            = getInt(json, "n3sigma");
            r.meanUncertainty    = getDouble(json, "meanUncertaintyNm");
            r.medianUncertainty  = getDouble(json, "medianUncertaintyNm");
            r.meanSigma          = getDouble(json, "meanSigmaNm");
            r.meanIntensity      = getDouble(json, "meanIntensity");
            r.nndPeakNm          = getDouble(json, "nndPeakNm");
            r.nndSigmaNm         = getDouble(json, "nndSigmaNm");
            r.nClusters          = getInt(json, "nClusters");
            r.nStructures        = getInt(json, "nStructures");
        } catch (Exception ex) {
            r.error = "Failed to parse stats.json: " + ex.getMessage();
            return r;
        }

        Path dir = statsJsonPath.getParent();

        // Build stats display map
        r.stats.put("Raw localisations",   String.valueOf(r.nRaw));
        r.stats.put("After QC filters",    String.valueOf(r.nFiltered));
        r.stats.put("After 3σ filter",     String.valueOf(r.n3sigma));
        r.stats.put("Mean uncertainty (nm)", String.format("%.2f", r.meanUncertainty));
        r.stats.put("Median uncertainty (nm)", String.format("%.2f", r.medianUncertainty));
        r.stats.put("Mean sigma (nm)",     String.format("%.2f", r.meanSigma));
        r.stats.put("Mean intensity",      String.format("%.0f", r.meanIntensity));
        r.stats.put("NND peak (nm)",       String.format("%.1f", r.nndPeakNm));
        r.stats.put("Clusters found",      String.valueOf(r.nClusters));
        r.stats.put("Structures found",    String.valueOf(r.nStructures));

        // Build plot paths (file names match what AnalysisPipeline writes)
        addPlot(r, dir, "SR Image",           "sr_image.png");
        addPlot(r, dir, "NND Plot",           "nnd_plot.png");
        addPlot(r, dir, "Uncertainty Hist",   "hist_uncertainty.png");
        addPlot(r, dir, "Sigma Hist",         "hist_sigma.png");
        addPlot(r, dir, "Intensity Hist",     "hist_intensity.png");
        addPlot(r, dir, "Locs/Frame",         "locs_per_frame.png");
        addPlot(r, dir, "Intensity vs Time",  "intensity_vs_time.png");
        addPlot(r, dir, "Blinking Showcase",   "blinking_showcase.png");

        return r;
    }

    private static void addPlot(DatasetResult r, Path dir, String label, String file) {
        Path p = dir.resolve(file);
        if (Files.exists(p)) r.plots.put(label, p);
    }

    public static BufferedImage loadImage(Path pngPath) {
        if (pngPath == null || !Files.exists(pngPath)) return null;
        try { return ImageIO.read(pngPath.toFile()); }
        catch (IOException ex) { return null; }
    }

    public static ImageIcon loadScaledIcon(Path pngPath, int maxWidth, int maxHeight) {
        if (pngPath == null || !Files.exists(pngPath)) return null;
        try {
            BufferedImage img = ImageIO.read(pngPath.toFile());
            if (img == null) return null;
            double scale = Math.min(
                (double) maxWidth  / img.getWidth(),
                (double) maxHeight / img.getHeight());
            int w = (int)(img.getWidth()  * scale);
            int h = (int)(img.getHeight() * scale);
            Image scaled = img.getScaledInstance(w, h, Image.SCALE_SMOOTH);
            return new ImageIcon(scaled);
        } catch (IOException ex) { return null; }
    }

    private static String getString(JsonObject json, String key, String def) {
        try { return json.get(key).getAsString(); } catch (Exception e) { return def; }
    }
    private static int getInt(JsonObject json, String key) {
        try { return json.get(key).getAsInt(); } catch (Exception e) { return 0; }
    }
    private static double getDouble(JsonObject json, String key) {
        try { return json.get(key).getAsDouble(); } catch (Exception e) { return 0.0; }
    }
}
