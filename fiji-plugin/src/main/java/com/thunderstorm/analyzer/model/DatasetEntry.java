package com.thunderstorm.analyzer.model;

import java.nio.file.Path;
import java.nio.file.Paths;

/** One dataset supplied by the user — mirrors Python DatasetEntry. */
public class DatasetEntry {

    public String  name;
    public Path    csvPath;        // null = use open Fiji ResultsTable
    public Path    protocolPath;   // null = no auto-fill
    public QcParams qc;

    public DatasetEntry(String name, String csvPath, String protocolPath, QcParams qc) {
        this.name         = name;
        this.csvPath      = (csvPath != null && !csvPath.isEmpty()) ? Paths.get(csvPath) : null;
        this.protocolPath = (protocolPath != null && !protocolPath.isEmpty()) ? Paths.get(protocolPath) : null;
        this.qc           = qc;
    }
}
