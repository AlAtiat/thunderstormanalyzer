package com.thunderstorm.analyzer.ui;

import com.thunderstorm.analyzer.model.ColumnConfig;
import com.thunderstorm.analyzer.model.PlotConfig;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;

/**
 * Configuration tab — column mapping, origami detection settings,
 * advanced blinking parameters, and plot selection toggles.
 * No Python dependency — the pipeline is pure Java.
 */
public class ConfigPanel extends JPanel {

    // --- Column mapping ---
    private final JTextField xColField         = new JTextField("position_x", 18);
    private final JTextField yColField         = new JTextField("position_y", 18);
    private final JTextField uncertColField    = new JTextField("uncertainty_x", 18);
    private final JTextField intensityColField = new JTextField("intensity", 18);
    private final JTextField sigmaColField     = new JTextField("psf_sigma", 18);
    private final JTextField frameColField     = new JTextField("frame", 18);
    private final JTextField bkgstdField       = new JTextField("background_sigma", 18);

    // --- Origami detection ---
    private final JTextField nSpotsField      = new JTextField("3",  6);
    private final JTextField maxTripletsField = new JTextField("10", 6);

    // --- Advanced blinking ---
    private final JTextField minCyclesField = new JTextField("2",    6);
    private final JTextField minFramesField = new JTextField("5",    6);
    private final JTextField blinkGapField  = new JTextField("2",    6);
    private final JTextField minLocsField   = new JTextField("3",    6);
    private final JTextField binSizeField   = new JTextField("20",   6);
    private final JTextField angleField     = new JTextField("30.0", 6);

    // --- Plot toggles ---
    private final JCheckBox histCheck    = new JCheckBox("Histograms",                      true);
    private final JCheckBox srCheck      = new JCheckBox("Super-resolution renders",         true);
    private final JCheckBox nndCheck     = new JCheckBox("NND distribution",                 true);
    private final JCheckBox intTimeCheck = new JCheckBox("Intensity vs time",                true);
    private final JCheckBox blinkCheck   = new JCheckBox("Blinking showcase (DNA origami)",  true);
    private final JCheckBox cmpCheck     = new JCheckBox("Comparison plots",                 true);

    public ConfigPanel() {
        setLayout(new BorderLayout());
        JPanel inner = new JPanel();
        inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
        inner.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        inner.add(buildColumnMappingSection());
        inner.add(Box.createVerticalStrut(8));
        inner.add(buildOrigamiSection());
        inner.add(Box.createVerticalStrut(8));
        inner.add(buildAdvancedSection());
        inner.add(Box.createVerticalStrut(8));
        inner.add(buildPlotSection());

        add(new JScrollPane(inner), BorderLayout.CENTER);
    }

    // -----------------------------------------------------------------------
    // Sections
    // -----------------------------------------------------------------------
    private JPanel buildColumnMappingSection() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(new TitledBorder("Column Mapping"));
        GridBagConstraints gc = gbc();
        row(p, gc, 0, "X position column:",    xColField);
        row(p, gc, 1, "Y position column:",    yColField);
        row(p, gc, 2, "Uncertainty column:",   uncertColField);
        row(p, gc, 3, "Intensity column:",     intensityColField);
        row(p, gc, 4, "PSF sigma column:",     sigmaColField);
        row(p, gc, 5, "Frame column:",         frameColField);
        row(p, gc, 6, "Background σ pattern:", bkgstdField);
        return p;
    }

    private JPanel buildOrigamiSection() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        p.setBorder(new TitledBorder("Structure Detection"));
        nSpotsField.setToolTipText(
            "Number of fluorescent dots per DNA origami structure. Default is 3 (triplet).");
        maxTripletsField.setToolTipText(
            "Maximum number of detected structures shown in the HTML carousel and showcase image.");
        JLabel nSpotsLabel = new JLabel("Dots per structure:");
        nSpotsLabel.setToolTipText(nSpotsField.getToolTipText());
        JLabel maxLabel = new JLabel("Max structures to show:");
        maxLabel.setToolTipText(maxTripletsField.getToolTipText());
        p.add(nSpotsLabel);    p.add(nSpotsField);
        p.add(maxLabel);       p.add(maxTripletsField);
        return p;
    }

    private JPanel buildAdvancedSection() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(new TitledBorder("Detection Tuning"));
        GridBagConstraints gc = gbc();

        minCyclesField.setToolTipText(
            "A dot must switch on and off at least this many times to be counted as a valid blinking dot.");
        minFramesField.setToolTipText(
            "A dot must appear in at least this many frames to be considered active.");
        blinkGapField.setToolTipText(
            "Maximum gap between detections (in frames) before a new blink cycle is counted.");
        minLocsField.setToolTipText(
            "Minimum number of raw localisations grouped into one dot (DBSCAN min-samples). " +
            "Increase to reject noise; decrease to catch faint emitters.");
        binSizeField.setToolTipText(
            "Pixel size (nm) used when rendering the super-resolution image. " +
            "Smaller = sharper but slower to render.");
        angleField.setToolTipText(
            "Maximum allowed deviation from a straight line (degrees) for a multi-dot " +
            "structure to be accepted as valid.");

        rowWithTip(p, gc, 0, "Min blink cycles:",       minCyclesField);
        rowWithTip(p, gc, 1, "Min active frames:",      minFramesField);
        rowWithTip(p, gc, 2, "Blink gap (frames):",     blinkGapField);
        rowWithTip(p, gc, 3, "Min detections per dot:", minLocsField);
        rowWithTip(p, gc, 4, "SR image pixel size (nm):", binSizeField);
        rowWithTip(p, gc, 5, "Max alignment error (°):", angleField);
        return p;
    }

    private JPanel buildPlotSection() {
        JPanel p = new JPanel(new GridLayout(0, 2, 6, 4));
        p.setBorder(new TitledBorder("Plot Selection"));
        p.add(histCheck);    p.add(srCheck);
        p.add(nndCheck);     p.add(intTimeCheck);
        p.add(blinkCheck);   p.add(cmpCheck);
        return p;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------
    private GridBagConstraints gbc() {
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(2, 6, 2, 6);
        gc.anchor = GridBagConstraints.WEST;
        return gc;
    }

    private void row(JPanel p, GridBagConstraints gc, int row, String label, JTextField field) {
        gc.gridx = 0; gc.gridy = row; gc.fill = GridBagConstraints.NONE; gc.weightx = 0;
        p.add(new JLabel(label), gc);
        gc.gridx = 1; gc.fill = GridBagConstraints.HORIZONTAL; gc.weightx = 1.0;
        p.add(field, gc);
    }

    private void rowWithTip(JPanel p, GridBagConstraints gc, int row, String label, JTextField field) {
        gc.gridx = 0; gc.gridy = row; gc.fill = GridBagConstraints.NONE; gc.weightx = 0;
        JLabel lbl = new JLabel(label);
        lbl.setToolTipText(field.getToolTipText());
        p.add(lbl, gc);
        gc.gridx = 1; gc.fill = GridBagConstraints.HORIZONTAL; gc.weightx = 1.0;
        p.add(field, gc);
    }

    // -----------------------------------------------------------------------
    // Public: extract model objects
    // -----------------------------------------------------------------------
    public ColumnConfig getColumnConfig() {
        ColumnConfig c = new ColumnConfig();
        c.xCol           = xColField.getText().trim();
        c.yCol           = yColField.getText().trim();
        c.uncertaintyCol = uncertColField.getText().trim();
        c.intensityCol   = intensityColField.getText().trim();
        c.sigmaCol       = sigmaColField.getText().trim();
        c.frameCol       = frameColField.getText().trim();
        c.bkgstdPattern  = bkgstdField.getText().trim();
        return c;
    }

    public PlotConfig getPlotConfig() {
        PlotConfig p = new PlotConfig();
        p.histograms       = histCheck.isSelected();
        p.superresRender   = srCheck.isSelected();
        p.nndPlots         = nndCheck.isSelected();
        p.intensityVsTime  = intTimeCheck.isSelected();
        p.blinkingShowcase = blinkCheck.isSelected();
        p.comparisonPlots  = cmpCheck.isSelected();
        return p;
    }

    public int    getMinCycles()   { return parseInt(minCyclesField, 2); }
    public int    getMinFrames()   { return parseInt(minFramesField, 5); }
    public int    getBlinkGap()    { return parseInt(blinkGapField,  2); }
    public int    getMinLocs()     { return parseInt(minLocsField,   3); }
    public int    getBinSize()     { return parseInt(binSizeField,  20); }
    public double getAngle()       { return parseDouble(angleField, 30.0); }
    public int    getNSpots()      { return parseInt(nSpotsField,    3); }
    public int    getMaxTriplets() { return parseInt(maxTripletsField, 10); }

    private int parseInt(JTextField f, int def) {
        try { return Integer.parseInt(f.getText().trim()); } catch (NumberFormatException e) { return def; }
    }

    private double parseDouble(JTextField f, double def) {
        try { return Double.parseDouble(f.getText().trim()); } catch (NumberFormatException e) { return def; }
    }
}
