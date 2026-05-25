package com.thunderstorm.analyzer.pipeline;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.opencsv.CSVWriter;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Writes stats.json and per-column CSV value files to the output directory.
 */
public class StatsWriter {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** A POJO that mirrors what Python wrote to stats.json. */
    public static class StatsJson {
        public String datasetName;
        public int nRaw;
        public int nFiltered;
        public int n3sigma;
        public double meanUncertaintyNm;
        public double medianUncertaintyNm;
        public double meanSigmaNm;
        public double medianSigmaNm;
        public double meanIntensity;
        public double medianIntensity;
        public double meanBkgstd;
        public double nndPeakNm;
        public double nndSigmaNm;
        public boolean nndFitOk;
        public int nClusters;
        public int nTriplets;
        public String protocolSource;
        public String tsVersion;
        public double pixelSizeNm;
        public double magnification;
    }

    public static void writeStats(StatsJson stats, Path outputDir) throws IOException {
        Path out = outputDir.resolve("stats.json");
        try (FileWriter fw = new FileWriter(out.toFile())) {
            GSON.toJson(stats, fw);
        }
    }

    public static void writeCsv(double[] values, String header, Path path) throws IOException {
        try (CSVWriter w = new CSVWriter(new FileWriter(path.toFile()))) {
            w.writeNext(new String[]{header});
            for (double v : values) w.writeNext(new String[]{String.valueOf(v)});
        }
    }

    public static void writeCsv2(double[] col1, String h1, double[] col2, String h2,
                                 Path path) throws IOException {
        try (CSVWriter w = new CSVWriter(new FileWriter(path.toFile()))) {
            w.writeNext(new String[]{h1, h2});
            int n = Math.min(col1.length, col2.length);
            for (int i = 0; i < n; i++)
                w.writeNext(new String[]{String.valueOf(col1[i]), String.valueOf(col2[i])});
        }
    }
}
