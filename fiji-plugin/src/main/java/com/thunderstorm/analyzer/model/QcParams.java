package com.thunderstorm.analyzer.model;

/** Mirrors Python QcParams dataclass — all quality-control thresholds and analysis parameters. */
public class QcParams {

    // --- Camera / optics ---
    public double pixelSizeNm          = 65.0;
    public double cameraPixelSizeNm    = 6500.0;
    public double magnification        = 100.0;

    // --- QC filters ---
    public double maxSigmaNm           = 768.0;
    public double maxUncertaintyNm     = 40.0;
    public double minIntensity         = 1000.0;

    // --- DNA origami structure detection (spacing also serves as the NND-target band) ---
    public double dnaOrigamiSpacingNm  = 80.0;
    public double spacingTolNm         = 20.0;
    public int    nSpots               = 3;
    public int    maxStructures        = 10;

    // --- Blinking parameters ---
    public int    minBlinkCycles       = 2;
    public int    minBlinkFrames       = 5;
    public int    blinkGapFrames       = 2;
    public int    dbscanMinSamples     = 3;

    // --- Rendering ---
    // Super-resolution render up-sampling (ThunderSTORM-style). renderBinSizeNm is
    // DERIVED: effectivePixelSizeNm() / visualizationMag (20 nm fallback).
    public double visualizationMag     = 5.0;
    public double renderBinSizeNm      = 20.0;
    public double collinearAngleDeg    = 30.0;

    // Whether the loaded intensity column is photon-calibrated. The plugin never recomputes
    // intensity (ThunderSTORM already applied/skipped the gain on export), so this only
    // controls labeling: false → "a.u." (uncalibrated, default), true → "photon".
    public boolean assumePhotons       = false;

    public QcParams() {}

    /** Returns effective pixel size (camera_px / magnification). */
    public double effectivePixelSizeNm() {
        return magnification > 0 ? cameraPixelSizeNm / magnification : pixelSizeNm;
    }

    /** Intensity unit label: "photon" when a real calibration is assumed, else "a.u.". */
    public static String intensityUnit(boolean assumePhotons) {
        return assumePhotons ? "photon" : "a.u.";
    }
}
