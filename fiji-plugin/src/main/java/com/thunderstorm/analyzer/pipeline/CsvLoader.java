package com.thunderstorm.analyzer.pipeline;

import com.opencsv.CSVReader;
import com.thunderstorm.analyzer.model.ColumnConfig;
import ij.measure.ResultsTable;

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
        List<String[]> rows;
        try (CSVReader reader = new CSVReader(new FileReader(csvPath.toFile()))) {
            rows = reader.readAll();
        }
        if (rows.size() < 2) throw new IllegalArgumentException("CSV has no data rows.");

        // Build header index (apply ThunderSTORM aliases)
        String[] rawHeaders = rows.get(0);
        Map<String, Integer> idx = buildIndex(rawHeaders);

        int xi   = require(idx, col.xCol,          rawHeaders);
        int yi   = require(idx, col.yCol,           rawHeaders);
        int ui   = require(idx, col.uncertaintyCol, rawHeaders);
        int ii   = require(idx, col.intensityCol,   rawHeaders);
        int si   = require(idx, col.sigmaCol,       rawHeaders);
        int fi   = require(idx, col.frameCol,       rawHeaders);
        int bi   = findBkgstd(idx, col.bkgstdPattern);

        int nRows = rows.size() - 1;
        double[] xA = new double[nRows], yA = new double[nRows],
                 uA = new double[nRows], iA = new double[nRows],
                 sA = new double[nRows], fA = new double[nRows],
                 bA = new double[nRows];

        for (int r = 0; r < nRows; r++) {
            String[] row = rows.get(r + 1);
            xA[r] = parseD(row, xi);
            yA[r] = parseD(row, yi);
            uA[r] = parseD(row, ui);
            iA[r] = parseD(row, ii);
            sA[r] = parseD(row, si);
            fA[r] = parseD(row, fi);
            bA[r] = bi >= 0 ? parseD(row, bi) : 0.0;
        }
        return new LocalizationData(xA, yA, uA, iA, sA, fA, bA);
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
