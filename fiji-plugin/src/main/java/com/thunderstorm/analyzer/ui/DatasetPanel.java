package com.thunderstorm.analyzer.ui;

import com.thunderstorm.analyzer.model.DatasetEntry;
import com.thunderstorm.analyzer.model.QcParams;
import com.thunderstorm.analyzer.pipeline.ProtocolParser;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.io.File;
import java.nio.file.Paths;
import java.util.function.Consumer;

/**
 * One dataset row — mirrors Python's DatasetRow widget.
 * Contains all per-dataset fields: name, CSV, protocol, camera/optics, QC thresholds.
 */
public class DatasetPanel extends JPanel {

    // --- File path labels ---
    private final JLabel csvLabel      = new JLabel("No file selected");
    private final JLabel protocolLabel = new JLabel("None (optional)");
    private File csvFile      = null;
    private File protocolFile = null;

    // --- Name ---
    private final JTextField nameField = new JTextField(20);

    // --- Camera / optics ---
    private final JTextField cameraPxField = new JTextField("6500", 8);
    private final JTextField magField      = new JTextField("100",  6);  // physical objective
    private final JTextField visMagField   = new JTextField("5",    6);  // visualization/render
    private final JTextField binSizeField  = new JTextField("13.0", 6);  // live auto value (editable)
    private final JLabel     effPxLabel    = new JLabel("65.00 nm/px · bin 13.0 nm");
    // Guard: while we write the auto bin into binSizeField, its DocumentListener must not
    // re-trigger the recompute (would recurse).
    private boolean syncingBin = false;

    // --- QC fields ---
    private final JTextField maxSigmaField       = new JTextField("768.0", 8);
    private final JTextField maxUncertaintyField = new JTextField("40.0",  8);
    private final JTextField minIntensityField   = new JTextField("1000.0",8);
    private final JTextField spacingField        = new JTextField("80.0",  8);
    private final JTextField spacingTolField     = new JTextField("20.0",  8);

    private final Runnable onRemove;

    public DatasetPanel(int index, Runnable onRemove) {
        this.onRemove = onRemove;
        nameField.setText("Dataset " + index);
        buildUI();
        wireEffectivePixelCalc();
        recomputeBin();   // pre-fill the bin field with the live auto value
    }

    // -----------------------------------------------------------------------
    // Build UI
    // -----------------------------------------------------------------------
    private void buildUI() {
        setLayout(new BorderLayout(4, 4));
        setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createEmptyBorder(4, 4, 4, 4),
            BorderFactory.createLineBorder(Color.LIGHT_GRAY, 1)
        ));

        // Header row: name + remove button
        JPanel header = new JPanel(new BorderLayout(6, 0));
        header.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        header.add(new JLabel("Name:"), BorderLayout.WEST);
        header.add(nameField, BorderLayout.CENTER);
        JButton removeBtn = new JButton("✕ Remove");
        removeBtn.setForeground(new Color(180, 0, 0));
        removeBtn.addActionListener(e -> onRemove.run());
        header.add(removeBtn, BorderLayout.EAST);

        // Body: file + optics + QC
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(2, 6, 6, 6));
        body.add(buildFileSection());
        body.add(Box.createVerticalStrut(4));
        body.add(buildOpticsSection());
        body.add(Box.createVerticalStrut(4));
        body.add(buildQcSection());

        add(header, BorderLayout.NORTH);
        add(body,   BorderLayout.CENTER);
    }

    private JPanel buildFileSection() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(new TitledBorder("Files"));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(2, 4, 2, 4);
        gc.anchor = GridBagConstraints.WEST;

        // CSV row
        gc.gridx = 0; gc.gridy = 0; gc.fill = GridBagConstraints.NONE;
        p.add(new JLabel("CSV file:"), gc);
        gc.gridx = 1; gc.fill = GridBagConstraints.HORIZONTAL; gc.weightx = 1.0;
        csvLabel.setForeground(Color.DARK_GRAY);
        p.add(csvLabel, gc);
        gc.gridx = 2; gc.fill = GridBagConstraints.NONE; gc.weightx = 0;
        JButton csvBtn = new JButton("Browse…");
        csvBtn.addActionListener(e -> browseFile(false));
        p.add(csvBtn, gc);

        // Protocol row
        gc.gridx = 0; gc.gridy = 1; gc.fill = GridBagConstraints.NONE; gc.weightx = 0;
        p.add(new JLabel("Protocol:"), gc);
        gc.gridx = 1; gc.fill = GridBagConstraints.HORIZONTAL; gc.weightx = 1.0;
        protocolLabel.setForeground(Color.GRAY);
        p.add(protocolLabel, gc);
        gc.gridx = 2; gc.fill = GridBagConstraints.NONE; gc.weightx = 0;
        JButton protoBtn = new JButton("Browse…");
        protoBtn.addActionListener(e -> browseFile(true));
        p.add(protoBtn, gc);

        return p;
    }

    private JPanel buildOpticsSection() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(new TitledBorder("Camera / Optics"));
        cameraPxField.setToolTipText(
            "Physical size of one camera pixel in nanometres (e.g. 6500 nm for an EMCCD with 6.5 µm pixels).");
        magField.setToolTipText(
            "Physical OBJECTIVE magnification (e.g. 100 for a 100× objective). " +
            "Effective pixel size = Camera px ÷ Objective mag.");
        visMagField.setToolTipText(
            "VISUALIZATION (super-res render) magnification. This is ThunderSTORM's render " +
            "magnification, not the objective.");
        binSizeField.setToolTipText(
            "SR render bin size in nm. Shows the live auto value = Effective pixel ÷ Visualization mag, " +
            "floored at 1 nm (20 nm fallback). Lower the visualization mag for a coarser bin. Editable — " +
            "a manual value persists until the other optics fields change.");
        effPxLabel.setToolTipText(
            "Effective pixel = Camera px ÷ Objective mag;  render bin = Effective pixel ÷ Visualization mag (nm).");
        JLabel camLabel = new JLabel("Camera px (nm):");
        camLabel.setToolTipText(cameraPxField.getToolTipText());
        JLabel magLabel = new JLabel("Objective mag (×):");
        magLabel.setToolTipText(magField.getToolTipText());
        JLabel visLabel = new JLabel("Visualization mag (×):");
        visLabel.setToolTipText(visMagField.getToolTipText());
        JLabel binLabel = new JLabel("Bin size (nm):");
        binLabel.setToolTipText(binSizeField.getToolTipText());

        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(2, 4, 2, 4);
        gc.anchor = GridBagConstraints.WEST;
        // Row 0: Camera px | Objective mag
        gc.gridx = 0; gc.gridy = 0; p.add(camLabel, gc);
        gc.gridx = 1;               p.add(cameraPxField, gc);
        gc.gridx = 2;               p.add(magLabel, gc);
        gc.gridx = 3;               p.add(magField, gc);
        // Row 1: Visualization mag | Bin size
        gc.gridx = 0; gc.gridy = 1; p.add(visLabel, gc);
        gc.gridx = 1;               p.add(visMagField, gc);
        gc.gridx = 2;               p.add(binLabel, gc);
        gc.gridx = 3;               p.add(binSizeField, gc);
        // Row 2: effective-pixel / bin summary spanning all four columns
        gc.gridx = 0; gc.gridy = 2; gc.gridwidth = 4;
        p.add(effPxLabel, gc);
        return p;
    }

    private JPanel buildQcSection() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(new TitledBorder("QC Parameters"));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(2, 6, 2, 6);
        gc.anchor = GridBagConstraints.WEST;

        addRow(p, gc, 0, "Max sigma (nm):",       maxSigmaField);
        addRow(p, gc, 1, "Max uncertainty (nm):", maxUncertaintyField);
        addRow(p, gc, 2, "Min intensity:",        minIntensityField);
        addRow(p, gc, 3, "Spacing / NND (nm):",   spacingField);
        addRow(p, gc, 4, "Spacing tol. (nm):",    spacingTolField);
        return p;
    }

    private void addRow(JPanel p, GridBagConstraints gc, int row, String label, JTextField field) {
        gc.gridx = 0; gc.gridy = row; gc.fill = GridBagConstraints.NONE; gc.weightx = 0;
        p.add(new JLabel(label), gc);
        gc.gridx = 1; gc.fill = GridBagConstraints.HORIZONTAL; gc.weightx = 1.0;
        p.add(field, gc);
    }

    // -----------------------------------------------------------------------
    // File browsing
    // -----------------------------------------------------------------------
    private void browseFile(boolean isProtocol) {
        JFileChooser fc = new JFileChooser();
        if (!isProtocol) {
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("CSV files", "csv"));
        } else {
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Protocol files", "json", "txt"));
        }
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File chosen = fc.getSelectedFile();
            if (isProtocol) {
                protocolFile = chosen;
                protocolLabel.setText(chosen.getName());
                protocolLabel.setForeground(Color.DARK_GRAY);
                autoFillFromProtocol(chosen);
            } else {
                csvFile = chosen;
                csvLabel.setText(chosen.getName());
                csvLabel.setForeground(Color.DARK_GRAY);
                autoFillFromCsv(chosen);
            }
        }
    }

    /** Auto-fills camera optics fields from a ThunderSTORM protocol JSON file.
     *  Only sets camera px and magnification — sigma/uncertainty come from CSV P95. */
    private void autoFillFromProtocol(File protoFile) {
        try {
            ProtocolParser.ProtocolInfo info = ProtocolParser.parse(protoFile.toPath());
            if (info.cameraPxNm > 0) {
                // ThunderSTORM's cameraSettings.pixelSize is the EFFECTIVE pixel size at the
                // sample (it already accounts for magnification). The Python reference
                // (qc_from_protocol) treats it as effective with magnification = 1, so do the
                // same here: effective pixel = pixelSize ÷ 1. (Manual entry still uses raw
                // camera px ÷ objective magnification.)
                cameraPxField.setText(String.format("%.1f", info.cameraPxNm));
                magField.setText("1");
            }
            if (!Double.isNaN(info.filterIntensity))
                minIntensityField.setText(String.format("%.0f", info.filterIntensity));
        } catch (Exception ex) {
            // Protocol parse failed — leave fields as they are
        }
    }

    /**
     * Auto-fills maxSigma and maxUncertainty from P95 of the CSV sigma/uncertainty columns.
     * Runs on a background thread so the EDT is never blocked.
     */
    private void autoFillFromCsv(File f) {
        new Thread(() -> {
            try {
                int sigmaIdx = -1, uncertIdx = -1;
                java.util.ArrayList<Double> sigmas  = new java.util.ArrayList<>();
                java.util.ArrayList<Double> uncerts = new java.util.ArrayList<>();
                try (java.io.BufferedReader br =
                         new java.io.BufferedReader(new java.io.FileReader(f))) {
                    String header = br.readLine();
                    if (header == null) return;
                    String[] hdrs = header.split(",");
                    for (int i = 0; i < hdrs.length; i++) {
                        String h = hdrs[i].trim().toLowerCase();
                        if (h.equals("sigma [nm]") || h.equals("psf_sigma") || h.contains("sigma"))
                            sigmaIdx  = i;
                        if (h.equals("uncertainty_xy [nm]") || h.equals("uncertainty_x")
                                || h.contains("uncertainty"))
                            uncertIdx = i;
                    }
                    if (sigmaIdx < 0 && uncertIdx < 0) return;
                    String line;
                    while ((line = br.readLine()) != null) {
                        String[] parts = line.split(",");
                        if (sigmaIdx  >= 0 && sigmaIdx  < parts.length) {
                            try { sigmas.add(Double.parseDouble(parts[sigmaIdx].trim())); }
                            catch (NumberFormatException ignored) {}
                        }
                        if (uncertIdx >= 0 && uncertIdx < parts.length) {
                            try { uncerts.add(Double.parseDouble(parts[uncertIdx].trim())); }
                            catch (NumberFormatException ignored) {}
                        }
                    }
                }
                final double p95s = percentileList(sigmas);
                final double p95u = percentileList(uncerts);
                SwingUtilities.invokeLater(() -> {
                    if (p95s > 0) maxSigmaField.setText(String.format("%.1f", p95s));
                    if (p95u > 0) maxUncertaintyField.setText(String.format("%.1f", p95u));
                });
            } catch (Exception ignored) {}
        }, "csv-p95-scan").start();
    }

    private static double percentileList(java.util.ArrayList<Double> list) {
        if (list == null || list.isEmpty()) return 0;
        double[] arr = new double[list.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = list.get(i);
        java.util.Arrays.sort(arr);
        int idx = (int)(arr.length * 0.95);
        if (idx >= arr.length) idx = arr.length - 1;
        return arr[idx];
    }

    // -----------------------------------------------------------------------
    // Effective pixel auto-calculation
    // -----------------------------------------------------------------------
    private void wireEffectivePixelCalc() {
        Runnable update = () -> recomputeBin();
        cameraPxField.getDocument().addDocumentListener(docListener(update));
        magField.getDocument().addDocumentListener(docListener(update));
        visMagField.getDocument().addDocumentListener(docListener(update));
        // Camera/objective/visualization feed the auto-recompute that writes binSizeField.
        // binSizeField's own listener only refreshes the summary label (never writes the
        // field), so a manual edit is reflected below but not clobbered; the syncingBin
        // guard stops the auto-write from double-updating.
        binSizeField.getDocument().addDocumentListener(docListener(this::updateBinLabelFromField));
    }

    /**
     * Refresh the summary label from binSizeField's current (possibly manual) value.
     * Label-only: never writes binSizeField, so a typed value is preserved. No-op during
     * the guarded auto-write, which sets effPxLabel itself in recomputeBin.
     */
    private void updateBinLabelFromField() {
        if (syncingBin) return;
        try {
            double cam = Double.parseDouble(cameraPxField.getText().trim());
            double mag = Double.parseDouble(magField.getText().trim());
            double eff = mag > 0 ? cam / mag : 0;
            double bin = parseFieldBin();
            if (bin <= 0) bin = renderBin(eff, parseDouble(visMagField, 0));
            effPxLabel.setText(String.format("%.2f nm/px · bin %.1f nm", eff, bin));
        } catch (NumberFormatException ex) {
            effPxLabel.setText("—");
        }
    }

    /**
     * Recompute the auto bin (effective pixel ÷ visualization mag) from this dataset's
     * optics, write it into binSizeField (under the syncingBin guard so the write doesn't
     * recurse), and refresh the summary label. Triggered only by camera/objective/vis edits;
     * a value the user typed into the bin field persists until one of those changes.
     */
    private void recomputeBin() {
        if (syncingBin) return;
        try {
            double cam = Double.parseDouble(cameraPxField.getText().trim());
            double mag = Double.parseDouble(magField.getText().trim());
            double eff = mag > 0 ? cam / mag : 0;
            double bin = renderBin(eff, parseDouble(visMagField, 0));
            syncingBin = true;
            try {
                binSizeField.setText(String.format("%.1f", bin));
            } finally {
                syncingBin = false;
            }
            effPxLabel.setText(String.format("%.2f nm/px · bin %.1f nm", eff, bin));
        } catch (NumberFormatException ex) {
            effPxLabel.setText("—");
        }
    }

    /**
     * Auto SR render bin size = effective pixel ÷ visualization magnification (ThunderSTORM's
     * standard render bin). A smaller visualization mag gives a larger (coarser) bin. Floored
     * at 1 nm; falls back to 20 nm when the magnification is missing/non-positive.
     */
    private static double renderBin(double effectivePxNm, double visMag) {
        if (visMag > 0) {
            double bin = effectivePxNm / visMag;
            if (bin > 0 && Double.isFinite(bin)) return Math.max(1.0, bin);
        }
        return 20.0;
    }

    /** The bin size shown in the field if valid & positive, else 0 (= fall back to auto). */
    private double parseFieldBin() {
        String raw = binSizeField.getText().trim();
        if (raw.isEmpty()) return 0;
        try {
            double v = Double.parseDouble(raw);
            return v > 0 ? v : 0;
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private javax.swing.event.DocumentListener docListener(Runnable r) {
        return new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { r.run(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { r.run(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
        };
    }

    // -----------------------------------------------------------------------
    // Public: extract DatasetEntry for the runner
    // -----------------------------------------------------------------------
    public DatasetEntry toEntry() {
        QcParams qc = new QcParams();
        qc.cameraPixelSizeNm   = parseDouble(cameraPxField, 6500.0);
        qc.magnification       = parseDouble(magField, 100.0);
        qc.pixelSizeNm         = qc.effectivePixelSizeNm();
        qc.visualizationMag    = parseDouble(visMagField, 5.0);
        qc.maxSigmaNm          = parseDouble(maxSigmaField, 768.0);
        qc.maxUncertaintyNm    = parseDouble(maxUncertaintyField, 40.0);
        qc.minIntensity        = parseDouble(minIntensityField, 1000.0);
        qc.dnaOrigamiSpacingNm = parseDouble(spacingField, 80.0);
        qc.spacingTolNm        = parseDouble(spacingTolField, 20.0);
        // Per-dataset SR bin: use the value shown in the field (normally the live auto
        // value, or an in-the-moment manual edit), else recompute the auto value.
        double fieldBin        = parseFieldBin();
        qc.renderBinSizeNm     = fieldBin > 0 ? fieldBin
            : renderBin(qc.pixelSizeNm, qc.visualizationMag);

        String csvPath  = csvFile      != null ? csvFile.getAbsolutePath()      : null;
        String protoPath= protocolFile != null ? protocolFile.getAbsolutePath() : null;
        return new DatasetEntry(nameField.getText().trim(), csvPath, protoPath, qc);
    }

    /** Called when "Load Results…" exports ThunderSTORM's table to a temp CSV. */
    public void setFromExportedCsv(String datasetName, java.io.File tempCsvFile) {
        nameField.setText(datasetName);
        csvFile = tempCsvFile;
        csvLabel.setText("<ThunderSTORM export: " + tempCsvFile.getName() + ">");
        csvLabel.setForeground(new Color(0, 120, 0));
        autoFillFromCsv(tempCsvFile);
    }

    /** Applies a magnification string read from Fiji Prefs (e.g. "100.0"). */
    public void applyMagnification(String magStr) {
        try {
            double mag = Double.parseDouble(magStr.trim());
            magField.setText(String.format("%.1f", mag));
        } catch (NumberFormatException ignored) {}
    }

    /** Applies a ThunderSTORM protocol file (auto-fill camera/QC fields). */
    public void applyProtocol(File protoFile) {
        protocolFile = protoFile;
        protocolLabel.setText(protoFile.getName());
        protocolLabel.setForeground(Color.DARK_GRAY);
        autoFillFromProtocol(protoFile);
    }

    /** Returns true if a CSV file is selected (including a ThunderSTORM export temp file). */
    public boolean isReady() {
        return csvFile != null && csvFile.exists();
    }

    public String getDatasetName() {
        return nameField.getText().trim();
    }

    private double parseDouble(JTextField f, double def) {
        try { return Double.parseDouble(f.getText().trim()); }
        catch (NumberFormatException e) { return def; }
    }
}
