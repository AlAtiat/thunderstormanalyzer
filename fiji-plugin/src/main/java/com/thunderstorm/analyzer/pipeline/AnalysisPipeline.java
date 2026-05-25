package com.thunderstorm.analyzer.pipeline;

import com.thunderstorm.analyzer.model.ColumnConfig;
import com.thunderstorm.analyzer.model.DatasetEntry;
import com.thunderstorm.analyzer.model.PlotConfig;
import com.thunderstorm.analyzer.model.QcParams;
import java.awt.image.BufferedImage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Orchestrates all pipeline steps for one dataset.
 * Called by AnalysisRunner (SwingWorker) on a background thread.
 */
public class AnalysisPipeline {

    public static class AnalysisResult {
        public String datasetName;
        public QcFilter.FilteredData qc;
        public NndAnalyzer.NndResult nnd;
        public DbscanClusterer.ClusterResult clusters;
        public BlinkingScorer.ClusterScore[] clusterScores;
        public List<TripletDetector.Triplet> triplets;
        public Path outputDir;
        public StatsWriter.StatsJson stats;
        public String errorMessage;   // non-null if pipeline failed
    }

    public static AnalysisResult run(DatasetEntry entry, ColumnConfig col,
                                     PlotConfig plot, Path baseOutputDir,
                                     Consumer<String> log) {
        AnalysisResult result = new AnalysisResult();
        result.datasetName = entry.name;

        try {
            // Create output directory
            Path outDir = baseOutputDir.resolve(sanitise(entry.name));
            Files.createDirectories(outDir);
            result.outputDir = outDir;

            log.accept("Loading data for: " + entry.name);
            CsvLoader.LocalizationData raw;
            if (entry.csvPath != null) {
                raw = CsvLoader.loadFromFile(entry.csvPath, col);
            } else {
                raw = CsvLoader.loadFromFijiTable(col);
            }
            log.accept("  Loaded " + raw.n + " raw localisations");

            // QC was already filled by the UI layer (DatasetPanel.toEntry + autoFillFromProtocol).
            // Do NOT re-apply the protocol here — it would overwrite the correct effective pixel size.
            QcParams qc = entry.qc;
            log.accept(String.format("  QC: cameraPx=%.1f nm, mag=%.1f, effectivePx=%.2f nm",
                qc.cameraPixelSizeNm, qc.magnification, qc.pixelSizeNm));

            // QC filtering
            log.accept("  Applying QC filters...");
            QcFilter.FilteredData fd = QcFilter.apply(raw, qc);
            result.qc = fd;
            log.accept(String.format("  Raw=%d  After filters=%d  After 3σ=%d",
                fd.nRaw, fd.nFiltered, fd.n3sigma));

            if (fd.n3sigma == 0) {
                result.errorMessage = "No localisations passed QC — check thresholds.";
                return result;
            }

            // Histograms
            if (plot.histograms) {
                log.accept("  Writing histograms...");
                PlotWriter.writeHistogram(fd.uncertainty, "Uncertainty (nm)", "Uncertainty Distribution", 60,
                    outDir.resolve("hist_uncertainty.png"));
                PlotWriter.writeHistogram(fd.sigma, "Sigma (nm)", "PSF Sigma Distribution", 60,
                    outDir.resolve("hist_sigma.png"));
                PlotWriter.writeHistogram(fd.intensity, "Intensity (photons)", "Intensity Distribution", 60,
                    outDir.resolve("hist_intensity.png"));
                PlotWriter.writeLocsPerFrame(fd.frame, outDir.resolve("locs_per_frame.png"));
                PlotWriter.writeIntensityVsTime(fd.frame, fd.intensity, outDir.resolve("intensity_vs_time.png"));
            }

            // NND analysis
            if (plot.nndPlots) {
                log.accept("  Computing NND...");
                NndAnalyzer.NndResult nnd = NndAnalyzer.compute(fd.x, fd.y);
                result.nnd = nnd;
                log.accept(String.format("  NND peak=%.1f nm  σ=%.1f nm  fitOk=%b",
                    nnd.peakNm, nnd.sigmaNm, nnd.fitOk));
                PlotWriter.writeNndPlot(nnd.kdeX, nnd.kdeY, nnd.fitX, nnd.fitY,
                    nnd.peakNm, outDir.resolve("nnd_plot.png"));
            }

            // SR rendering — always render to memory; save to disk if toggle on
            double binNm = qc.renderBinSizeNm > 0 ? qc.renderBinSizeNm : 20.0;
            java.awt.image.BufferedImage srImage = null;
            if (plot.superresRender || plot.blinkingShowcase) {
                log.accept("  Rendering SR image...");
                srImage = SrRenderer.render(fd.x, fd.y, binNm,
                    plot.superresRender ? outDir.resolve("sr_image.png") : null);
            }

            // DBSCAN + blinking scoring + triplet detection
            if (plot.blinkingShowcase) {
                log.accept("  Running DBSCAN clustering...");
                double nndPeak = result.nnd != null ? result.nnd.peakNm : 0;
                DbscanClusterer.ClusterResult clr = DbscanClusterer.run(
                    fd.x, fd.y, qc.pixelSizeNm, nndPeak, qc.dbscanMinSamples);
                result.clusters = clr;
                log.accept("  Clusters found: " + clr.nClusters);

                int nSpots = qc.nSpots > 0 ? qc.nSpots : 3;
                if (clr.nClusters >= nSpots) {
                    log.accept("  Scoring clusters...");
                    int totalFrames = (int) max(fd.frame) + 1;
                    BlinkingScorer.ClusterScore[] scores = BlinkingScorer.scoreAll(
                        fd.x, fd.y, fd.intensity, fd.frame,
                        clr.labels, clr.nClusters,
                        qc.blinkGapFrames, totalFrames);
                    result.clusterScores = scores;

                    log.accept("  Finding structures (nSpots=" + nSpots + ")...");
                    List<TripletDetector.Triplet> triplets = TripletDetector.find(
                        fd.x, fd.y, clr.labels, scores,
                        qc.dnaOrigamiSpacingNm, qc.spacingTolNm,
                        qc.collinearAngleDeg, qc.maxTriplets, nSpots);
                    result.triplets = triplets;
                    log.accept("  Structures found (" + nSpots + " dots): " + triplets.size());

                    if (!triplets.isEmpty() && srImage != null) {
                        // Blinking showcase: 3-panel Python-style figure for the top triplet
                        PlotWriter.writeBlinkingShowcase(
                            srImage, fd.x, fd.y,
                            triplets.get(0),
                            fd.frame, fd.intensity, clr.labels,
                            binNm, entry.name,
                            outDir.resolve("blinking_showcase.png"));

                        // HTML carousel for all found triplets
                        HtmlCarousel.write(triplets, fd.x, fd.y, fd.frame, fd.intensity,
                            clr.labels, srImage, binNm, outDir);
                    }
                }
            }

            // Stats JSON
            log.accept("  Writing stats.json...");
            StatsWriter.StatsJson statsJson = buildStats(entry.name, fd, result, qc,
                entry.protocolPath != null ? ProtocolParser.parse(entry.protocolPath) : null);
            result.stats = statsJson;
            StatsWriter.writeStats(statsJson, outDir);

            // Value CSVs
            StatsWriter.writeCsv(fd.uncertainty, "uncertainty_nm", outDir.resolve("values_uncertainty.csv"));
            StatsWriter.writeCsv(fd.sigma,       "sigma_nm",        outDir.resolve("values_sigma.csv"));
            StatsWriter.writeCsv(fd.intensity,   "intensity_photon",outDir.resolve("values_intensity.csv"));
            StatsWriter.writeCsv2(fd.x, "x_nm", fd.y, "y_nm", outDir.resolve("values_xy.csv"));

            log.accept("Done: " + entry.name);

        } catch (Exception ex) {
            result.errorMessage = ex.getMessage();
            log.accept("ERROR: " + ex.getMessage());
        }

        return result;
    }

    private static StatsWriter.StatsJson buildStats(String name,
                                                     QcFilter.FilteredData fd,
                                                     AnalysisResult result,
                                                     QcParams qc,
                                                     ProtocolParser.ProtocolInfo proto) {
        StatsWriter.StatsJson s = new StatsWriter.StatsJson();
        s.datasetName       = name;
        s.nRaw              = fd.nRaw;
        s.nFiltered         = fd.nFiltered;
        s.n3sigma           = fd.n3sigma;
        s.meanUncertaintyNm = fd.meanUncertainty;
        s.medianUncertaintyNm = fd.medianUncertainty;
        s.meanSigmaNm       = fd.meanSigma;
        s.medianSigmaNm     = fd.medianSigma;
        s.meanIntensity     = fd.meanIntensity;
        s.medianIntensity   = fd.medianIntensity;
        s.meanBkgstd        = fd.meanBkgstd;
        s.pixelSizeNm       = qc.pixelSizeNm;
        s.magnification     = qc.magnification;
        if (result.nnd != null) {
            s.nndPeakNm  = result.nnd.peakNm;
            s.nndSigmaNm = result.nnd.sigmaNm;
            s.nndFitOk   = result.nnd.fitOk;
        }
        if (result.clusters != null)  s.nClusters  = result.clusters.nClusters;
        if (result.triplets != null)  s.nTriplets  = result.triplets.size();
        if (proto != null) {
            s.protocolSource = proto.sourceImage;
            s.tsVersion      = proto.tsVersion;
        }
        return s;
    }

    private static double max(double[] a) {
        double m = a[0]; for (double v : a) if (v > m) m = v; return m;
    }

    private static String sanitise(String name) {
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }
}
