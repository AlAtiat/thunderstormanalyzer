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

    // --- NND analysis ---
    public double nndTargetNm          = 80.0;
    public double nndToleranceNm       = 20.0;

    // --- DNA origami triplet detection ---
    public double dnaOrigamiSpacingNm  = 80.0;
    public double spacingTolNm         = 20.0;
    public int    nSpots               = 3;
    public int    maxTriplets          = 10;

    // --- Blinking parameters ---
    public int    minBlinkCycles       = 2;
    public int    minBlinkFrames       = 5;
    public int    blinkGapFrames       = 2;
    public int    dbscanMinSamples     = 3;

    // --- Rendering ---
    public int    renderBinSizeNm      = 20;
    public double collinearAngleDeg    = 30.0;

    public QcParams() {}

    /** Returns effective pixel size (camera_px / magnification). */
    public double effectivePixelSizeNm() {
        return magnification > 0 ? cameraPixelSizeNm / magnification : pixelSizeNm;
    }
}
