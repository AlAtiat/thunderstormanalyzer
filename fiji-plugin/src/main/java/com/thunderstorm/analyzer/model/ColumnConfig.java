package com.thunderstorm.analyzer.model;

/** Mirrors Python ColumnConfig — maps canonical pipeline names to CSV column headers. */
public class ColumnConfig {

    public String xCol          = "position_x";
    public String yCol          = "position_y";
    public String uncertaintyCol = "uncertainty_x";
    public String intensityCol  = "intensity";
    public String sigmaCol      = "psf_sigma";
    public String frameCol      = "frame";
    public String bkgstdPattern = "background_sigma";

    public ColumnConfig() {}
}
