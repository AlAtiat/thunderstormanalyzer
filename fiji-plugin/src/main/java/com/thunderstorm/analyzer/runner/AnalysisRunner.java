package com.thunderstorm.analyzer.runner;

import com.thunderstorm.analyzer.model.ColumnConfig;
import com.thunderstorm.analyzer.model.DatasetEntry;
import com.thunderstorm.analyzer.model.PlotConfig;
import com.thunderstorm.analyzer.pipeline.AnalysisPipeline;
import com.thunderstorm.analyzer.pipeline.ComparisonWriter;
import com.thunderstorm.analyzer.util.ResultsLoader;
import com.thunderstorm.analyzer.util.ResultsLoader.DatasetResult;

import javax.swing.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * SwingWorker that drives the pure-Java analysis pipeline on a background thread.
 * No Python required — calls AnalysisPipeline.run() directly.
 */
public class AnalysisRunner extends SwingWorker<List<DatasetResult>, String> {

    private final List<DatasetEntry>             datasets;
    private final ColumnConfig                   colConfig;
    private final PlotConfig                     plotConfig;
    private final Path                           outputDir;
    private final Consumer<String>               onLog;
    private final Consumer<List<DatasetResult>>  onDone;

    public AnalysisRunner(List<DatasetEntry> datasets,
                          ColumnConfig colConfig,
                          PlotConfig   plotConfig,
                          Path         outputDir,
                          Consumer<String> onLog,
                          Consumer<List<DatasetResult>> onDone) {
        this.datasets  = datasets;
        this.colConfig = colConfig;
        this.plotConfig = plotConfig;
        this.outputDir  = outputDir;
        this.onLog      = onLog;
        this.onDone     = onDone;
    }

    @Override
    protected List<DatasetResult> doInBackground() {
        List<DatasetResult>                  results    = new ArrayList<>();
        List<AnalysisPipeline.AnalysisResult> rawResults = new ArrayList<>();
        List<String>                          names      = new ArrayList<>();

        for (DatasetEntry entry : datasets) {
            publish(">>> Analysing: " + entry.name);
            AnalysisPipeline.AnalysisResult ar =
                AnalysisPipeline.run(entry, colConfig, plotConfig, outputDir, this::publish);

            DatasetResult dr = new DatasetResult();
            dr.name  = entry.name;
            if (ar.errorMessage != null) {
                dr.error = ar.errorMessage;
            } else {
                dr.outputDir = ar.outputDir;
                try {
                    Path statsJson = ar.outputDir.resolve("stats.json");
                    ResultsLoader.DatasetResult loaded = ResultsLoader.load(statsJson, ar.outputDir);
                    dr.stats = loaded.stats;
                    dr.plots = loaded.plots;
                } catch (Exception ex) {
                    dr.error = "Results load error: " + ex.getMessage();
                }
                rawResults.add(ar);
                names.add(entry.name);
            }
            results.add(dr);
            publish(ar.errorMessage != null
                ? "<<< Failed: " + entry.name
                : "<<< Done: " + entry.name);
        }

        if (plotConfig.comparisonPlots && rawResults.size() > 1) {
            publish(">>> Generating comparison plots...");
            try {
                Path cmpDir = outputDir.resolve("comparison");
                Files.createDirectories(cmpDir);
                boolean assumePhotons = !datasets.isEmpty() && datasets.get(0).qc.assumePhotons;
                ComparisonWriter.write(rawResults, names, cmpDir, assumePhotons, this::publish);
            } catch (Exception ex) {
                publish("  Comparison plots failed: " + ex.getMessage());
            }
        }

        return results;
    }

    @Override
    protected void process(List<String> chunks) {
        for (String line : chunks) onLog.accept(line);
    }

    @Override
    protected void done() {
        try {
            onDone.accept(get());
        } catch (Exception ex) {
            onLog.accept("RUNNER ERROR: " + ex.getMessage());
            onDone.accept(new ArrayList<>());
        }
    }
}
