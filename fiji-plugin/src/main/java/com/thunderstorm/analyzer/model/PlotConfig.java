package com.thunderstorm.analyzer.model;

/** Mirrors Python PlotConfig — feature toggles for which output plots to generate. */
public class PlotConfig {

    public boolean histograms        = true;
    public boolean superresRender    = true;
    public boolean nndPlots          = true;
    public boolean intensityVsTime   = true;
    public boolean blinkingShowcase  = true;
    public boolean comparisonPlots   = true;

    public PlotConfig() {}
}
