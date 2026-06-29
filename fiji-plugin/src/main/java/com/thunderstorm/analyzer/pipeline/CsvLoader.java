package com.thunderstorm.analyzer.pipeline;

import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.thunderstorm.analyzer.model.ColumnConfig;
import ij.measure.ResultsTable;

import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.file.Path;
import java.util.*;

/**
 * Loads localization data from a ThunderSTORM CSV file or an open Fiji ResultsTable.
 * Returns a LocalizationData object with parallel double arrays for each column.
 */
public class CsvLoader {

    /** ThunderSTORM raw column name → canonical pipeline name */
    private static final Map<String, String> ALIASES = new HashMap<>();
    static {
        ALIASES.put("x [nm]",               "position_x");
        ALIASES.put("y [nm]",               "position_y");
        ALIASES.put("uncertainty_xy [nm]",  "uncertainty_x");
        ALIASES.put("sigma [nm]",           "psf_sigma");
        ALIASES.put("intensity [photon]",   "intensity");
        ALIASES.put("offset [photon]",      "offset");
        ALIASES.put("bkgstd [photon]",      "background_sigma");
    }

    // -----------------------------------------------------------------------
    // Result container
    // -----------------------------------------------------------------------
    public static class LocalizationData {
        public double[] x, y, uncertainty, intensity, sigma, frame, bkgstd;
        public int n;

        public LocalizationData(double[] x, double[] y, double[] uncertainty,
                                double[] intensity, double[] sigma,
                                double[] frame,  double[] bkgstd) {
            this.x           = x;
            this.y           = y;
            this.uncertainty = uncertainty;
            this.intensity   = intensity;
            this.sigma       = sigma;
            this.frame       = frame;
            this.bkgstd      = bkgstd;
            this.n           = x.length;
        }
    }

    // -----------------------------------------------------------------------
    // Load from CSV file
    // -----------------------------------------------------------------------
    public static LocalizationData loadFromFile(Path csvPath, ColumnConfig col) throws Exception {
        // Stream the CSV through a 1 MiB BufferedReader and parse row-by-row. opencsv's
        // CSVReader does NOT buffer on its own, so a bare FileReader reads essentially
        // char-by-char — pathologically slow on large (millions-of-rows) localization files.
        // Streaming with readNext() also avoids holding the whole file as a List<String[]>.
        try (CSVReader reader = new CSVReaderBuilder(
                new BufferedReader(new FileReader(csvPath.toFile()), 1 << 20)).build()) {

            String[] rawHeaders = reader.readNext();
            if (rawHeaders == null) throw new IllegalArgumentException("CSV has no data rows.");

            // Build header index (apply ThunderSTORM aliases)
            Map<String, Integer> idx = buildIndex(rawHeaders);

            int xi   = require(idx, col.xCol,          rawHeaders);
            int yi   = require(idx, col.yCol,           rawHeaders);
            int ui   = require(idx, col.uncertaintyCol, rawHeaders);
            int ii   = require(idx, col.intensityCol,   rawHeaders);
            int si   = require(idx, col.sigmaCol,       rawHeaders);
            int fi   = require(idx, col.frameCol,       rawHeaders);
            int bi   = findBkgstd(idx, col.bkgstdPattern);

            DoubleBuffer xA = new DoubleBuffer(), yA = new DoubleBuffer(),
                         uA = new DoubleBuffer(), iA = new DoubleBuffer(),
                         sA = new DoubleBuffer(), fA = new DoubleBuffer(),
                         bA = new DoubleBuffer();

            String[] row;
            while ((row = reader.readNext()) != null) {
                xA.add(parseD(row, xi));
                yA.add(parseD(row, yi));
                uA.add(parseD(row, ui));
                iA.add(parseD(row, ii));
                sA.add(parseD(row, si));
                fA.add(parseD(row, fi));
                bA.add(bi >= 0 ? parseD(row, bi) : 0.0);
            }
            if (xA.size == 0) throw new IllegalArgumentException("CSV has no data rows.");

            return new LocalizationData(xA.trimmed(), yA.trimmed(), uA.trimmed(),
                                        iA.trimmed(), sA.trimmed(), fA.trimmed(), bA.trimmed());
        }
    }

    /** Growable primitive double array — avoids Double boxing while streaming the CSV. */
    private static final class DoubleBuffer {
        private double[] data = new double[1 << 16];
        private int size = 0;

        void add(double v) {
            if (size == data.length) data = Arrays.copyOf(data, data.length << 1);
            data[size++] = v;
        }

        double[] trimmed() {
            return Arrays.copyOf(data, size);
        }
    }

    // -----------------------------------------------------------------------
    // Load from open Fiji ResultsTable (ThunderSTORM result window)
    // -----------------------------------------------------------------------
    public static LocalizationData loadFromFijiTable(ColumnConfig col) {
        ResultsTable rt = ResultsTable.getResultsTable();
        if (rt == null || rt.size() == 0)
            throw new IllegalStateException("No open ResultsTable found in Fiji.");

        // Map canonical names through aliases to find actual headings
        int n = rt.size();
        double[] xA = getCol(rt, col.xCol,          n);
        double[] yA = getCol(rt, col.yCol,           n);
        double[] uA = getCol(rt, col.uncertaintyCol, n);
        double[] iA = getCol(rt, col.intensityCol,   n);
        double[] sA = getCol(rt, col.sigmaCol,       n);
        double[] fA = getCol(rt, col.frameCol,       n);
        double[] bA = findBkgstdCol(rt, col.bkgstdPattern, n);
        return new LocalizationData(xA, yA, uA, iA, sA, fA, bA);
    }

    /** Returns all column headings present in a Fiji ResultsTable (for column picker). */
    public static String[] fijiTableHeadings() {
        ResultsTable rt = ResultsTable.getResultsTable();
        if (rt == null) return new String[0];
        return rt.getHeadings();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static Map<String, Integer> buildIndex(String[] headers) {
        Map<String, Integer> idx = new LinkedHashMap<>();
        for (int i = 0; i < headers.length; i++) {
            String h = headers[i].trim();
            idx.put(h, i);
            String alias = ALIASES.get(h);
            if (alias != null) idx.put(alias, i);
        }
        return idx;
    }

    private static int require(Map<String, Integer> idx, String name, String[] rawHeaders) {
        Integer i = idx.get(name);
        if (i != null) return i;
        // fallback: case-insensitive partial match
        for (Map.Entry<String, Integer> e : idx.entrySet()) {
            if (e.getKey().toLowerCase().contains(name.toLowerCase())) return e.getValue();
        }
        throw new IllegalArgumentException(
            "Column '" + name + "' not found. Available: " + Arrays.toString(rawHeaders));
    }

    private static int findBkgstd(Map<String, Integer> idx, String pattern) {
        for (Map.Entry<String, Integer> e : idx.entrySet()) {
            if (e.getKey().toLowerCase().contains(pattern.toLowerCase())) return e.getValue();
        }
        return -1;
    }

    private static double parseD(String[] row, int col) {
        if (col < 0 || col >= row.length) return 0.0;
        try { return Double.parseDouble(row[col].trim()); }
        catch (NumberFormatException e) { return 0.0; }
    }

    private static double[] getCol(ResultsTable rt, String colName, int n) {
        // Try exact, then alias, then partial
        if (rt.columnExists(colName)) {
            return rt.getColumnAsDoubles(rt.getColumnIndex(colName));
        }
        String[] heads = rt.getHeadings();
        for (String h : heads) {
            String alias = ALIASES.get(h);
            if (colName.equals(alias)) {
                return rt.getColumnAsDoubles(rt.getColumnIndex(h));
            }
        }
        for (String h : heads) {
            if (h.toLowerCase().contains(colName.toLowerCase())) {
                return rt.getColumnAsDoubles(rt.getColumnIndex(h));
            }
        }
        // column absent — return zeros
        return new double[n];
    }

    private static double[] findBkgstdCol(ResultsTable rt, String pattern, int n) {
        for (String h : rt.getHeadings()) {
            if (h.toLowerCase().contains(pattern.toLowerCase())) {
                return rt.getColumnAsDoubles(rt.getColumnIndex(h));
            }
        }
        return new double[n];
    }
}
