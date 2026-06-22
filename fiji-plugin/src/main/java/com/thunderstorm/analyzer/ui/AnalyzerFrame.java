package com.thunderstorm.analyzer.ui;

import com.thunderstorm.analyzer.model.DatasetEntry;
import com.thunderstorm.analyzer.model.ColumnConfig;
import com.thunderstorm.analyzer.model.PlotConfig;
import com.thunderstorm.analyzer.pipeline.ProtocolParser;
import com.thunderstorm.analyzer.runner.AnalysisRunner;
import com.thunderstorm.analyzer.util.ResultsLoader.DatasetResult;
import com.thunderstorm.analyzer.util.UpdateChecker;
import ij.Prefs;
import ij.WindowManager;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Main JFrame for the ThunderSTORM Analyzer Fiji plugin.
 *
 * Layout:
 *   ┌─────────────────────────────────────────────────────────┐
 *   │  [Sidebar 220px]  │  [JTabbedPane: Datasets|Results|Config] │
 *   └─────────────────────────────────────────────────────────┘
 */
public class AnalyzerFrame extends JFrame {

    // --- Sidebar controls ---
    private final JButton addDatasetBtn  = new JButton("+ Add Dataset");
    private final JButton loadFijiBtn    = new JButton("Load Results…");
    private final JButton runBtn         = new JButton("▶ Run Analysis");
    private final JButton clearBtn       = new JButton("Clear All");
    private final JButton exportZipBtn   = new JButton("Export ZIP…");
    private final JButton openFolderBtn  = new JButton("Open Results Folder");
    private final JButton checkUpdatesBtn = new JButton("Check for Updates");
    private final JLabel  statusLabel    = new JLabel("Status: Idle");
    private final JProgressBar progressBar = new JProgressBar();

    // --- Log area (in Datasets tab) ---
    private final JTextArea logArea = new JTextArea(8, 40);

    // --- Tabs ---
    private final JTabbedPane mainTabs  = new JTabbedPane();
    private final JPanel      datasetsListPanel = new JPanel();
    private final ResultsPanel resultsPanel     = new ResultsPanel();
    private final ConfigPanel  configPanel      = new ConfigPanel();

    // --- State ---
    private final List<DatasetPanel> datasetPanels = new ArrayList<>();
    private int  datasetCounter = 1;
    private Path outputDir      = null;
    private AnalysisRunner currentRunner = null;

    public AnalyzerFrame() {
        super("ThunderSTORM Analyzer");
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setPreferredSize(new Dimension(1200, 760));
        buildUI();
        pack();
        setLocationRelativeTo(null);
    }

    // -----------------------------------------------------------------------
    // Build UI
    // -----------------------------------------------------------------------
    private void buildUI() {
        JPanel sidebar = buildSidebar();
        sidebar.setPreferredSize(new Dimension(220, 0));

        // Datasets tab
        datasetsListPanel.setLayout(new BoxLayout(datasetsListPanel, BoxLayout.Y_AXIS));
        JLabel emptyHint = new JLabel("<html><i><br>&nbsp;&nbsp;Click '+ Add Dataset' or<br>&nbsp;&nbsp;'Load Results…' to begin.</i></html>");
        emptyHint.setForeground(Color.GRAY);
        datasetsListPanel.add(emptyHint);

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("Log"));
        logScroll.setPreferredSize(new Dimension(0, 140));

        JScrollPane datasetScroll = new JScrollPane(datasetsListPanel);
        datasetScroll.getVerticalScrollBar().setUnitIncrement(16);

        JPanel datasetsTab = new JPanel(new BorderLayout(0, 4));
        datasetsTab.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        datasetsTab.add(datasetScroll, BorderLayout.CENTER);
        datasetsTab.add(logScroll,     BorderLayout.SOUTH);

        mainTabs.addTab("Datasets",      datasetsTab);
        mainTabs.addTab("Results",       resultsPanel);
        mainTabs.addTab("Configuration", configPanel);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebar, mainTabs);
        split.setDividerLocation(220);
        split.setDividerSize(4);
        split.setResizeWeight(0.0);

        getContentPane().add(split, BorderLayout.CENTER);

        // Wire buttons
        addDatasetBtn.addActionListener(e -> addDataset());
        loadFijiBtn.addActionListener(e -> loadFijiTables());
        runBtn.addActionListener(e -> runAnalysis());
        clearBtn.addActionListener(e -> clearAll());
        exportZipBtn.addActionListener(e -> exportZip());
        openFolderBtn.addActionListener(e -> openFolder());
        checkUpdatesBtn.addActionListener(e -> checkForUpdates());

        exportZipBtn.setEnabled(false);
        openFolderBtn.setEnabled(false);
        progressBar.setIndeterminate(false);
    }

    private JPanel buildSidebar() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createEmptyBorder(10, 8, 10, 8));

        Dimension btnSize = new Dimension(200, 32);
        for (JButton btn : new JButton[]{addDatasetBtn, loadFijiBtn, runBtn, clearBtn, exportZipBtn, openFolderBtn, checkUpdatesBtn}) {
            btn.setMaximumSize(btnSize);
            btn.setAlignmentX(Component.LEFT_ALIGNMENT);
            p.add(btn);
            p.add(Box.createVerticalStrut(6));
        }

        p.add(Box.createVerticalStrut(12));

        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(statusLabel);
        p.add(Box.createVerticalStrut(6));

        progressBar.setMaximumSize(new Dimension(200, 14));
        progressBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(progressBar);
        p.add(Box.createVerticalGlue());

        JLabel version = new JLabel("ThunderSTORM Analyzer v" + UpdateChecker.currentVersion());
        version.setForeground(Color.GRAY);
        version.setFont(version.getFont().deriveFont(10f));
        version.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(version);
        return p;
    }

    // -----------------------------------------------------------------------
    // Dataset management
    // -----------------------------------------------------------------------
    private void addDataset() {
        if (datasetPanels.isEmpty()) {
            datasetsListPanel.removeAll();
        }

        final int idx = datasetCounter++;
        DatasetPanel dp = new DatasetPanel(idx, () -> removeDataset(idx));
        dp.putClientProperty("idx", idx);
        datasetPanels.add(dp);
        datasetsListPanel.add(dp);
        datasetsListPanel.revalidate();
        datasetsListPanel.repaint();
    }

    private void removeDataset(int idx) {
        datasetPanels.removeIf(dp -> {
            if (Integer.valueOf(idx).equals(dp.getClientProperty("idx"))) {
                datasetsListPanel.remove(dp);
                return true;
            }
            return false;
        });
        if (datasetPanels.isEmpty()) {
            datasetsListPanel.add(new JLabel(
                "<html><i><br>&nbsp;&nbsp;Click '+ Add Dataset' or<br>&nbsp;&nbsp;'Load Results…' to begin.</i></html>"));
        }
        datasetsListPanel.revalidate();
        datasetsListPanel.repaint();
    }

    private void clearAll() {
        datasetPanels.clear();
        datasetsListPanel.removeAll();
        datasetsListPanel.add(new JLabel(
            "<html><i><br>&nbsp;&nbsp;Click '+ Add Dataset' or<br>&nbsp;&nbsp;'Load Results…' to begin.</i></html>"));
        datasetsListPanel.revalidate();
        datasetsListPanel.repaint();
        logArea.setText("");
        statusLabel.setText("Status: Idle");
        outputDir = null;
        exportZipBtn.setEnabled(false);
        openFolderBtn.setEnabled(false);
        resultsPanel.populate(null, Paths.get("."));
    }

    // -----------------------------------------------------------------------
    // Load ThunderSTORM table by triggering its own Export command → temp CSV
    // -----------------------------------------------------------------------
    private void loadFijiTables() {
        appendLog("=== Load Results: exporting ThunderSTORM table ===");

        // Ask for output directory FIRST (once per session) — no temp copy needed
        if (outputDir == null) {
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle("Choose Output Directory for Analysis Results");
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            outputDir = fc.getSelectedFile().toPath();
            exportZipBtn.setEnabled(true);
            openFolderBtn.setEnabled(true);
        }
        appendLog("  Output directory: " + outputDir);

        // Export ThunderSTORM table directly into the output directory
        java.nio.file.Path exportCsv   = outputDir.resolve("results.csv");
        java.nio.file.Path exportProto = outputDir.resolve("results-protocol.txt");

        try { java.nio.file.Files.createDirectories(outputDir); }
        catch (java.io.IOException ex) {
            appendLog("  ERROR: cannot create output dir: " + ex.getMessage());
            JOptionPane.showMessageDialog(this, "Cannot create output directory:\n" + ex.getMessage());
            return;
        }

        String macroOpts = "filepath=[" + exportCsv.toString() + "] "
            + "fileformat=[CSV (comma separated)] "
            + "floatprecision=5 "
            + "saveprotocol=true";
        appendLog("  Running: Export results → " + exportCsv.getFileName());
        ij.IJ.run("Export results", macroOpts);

        // Verify the export wrote something
        if (!java.nio.file.Files.exists(exportCsv) || exportCsv.toFile().length() == 0) {
            appendLog("  Export produced no file — no ThunderSTORM results table is open.");
            JOptionPane.showMessageDialog(this,
                "No ThunderSTORM results table found.\n" +
                "Run ThunderSTORM analysis in Fiji first, then click this button.",
                "No Results Found", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        long rows;
        try (java.io.BufferedReader br =
                 new java.io.BufferedReader(new java.io.FileReader(exportCsv.toFile()))) {
            rows = br.lines().count() - 1;
        } catch (java.io.IOException ex) { rows = -1; }
        appendLog("  Export OK — ~" + rows + " rows");

        // Use the active Fiji image title as the dataset name (best effort)
        String datasetName = "ThunderSTORM Results";
        String[] imageTitles = WindowManager.getImageTitles();
        if (imageTitles != null && imageTitles.length > 0) datasetName = imageTitles[0];

        // Skip if already loaded
        for (DatasetPanel dp : datasetPanels) {
            if (dp.getDatasetName().equals(datasetName)) {
                JOptionPane.showMessageDialog(this,
                    "'" + datasetName + "' is already loaded as a dataset.",
                    "Already Loaded", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
        }

        if (datasetPanels.isEmpty()) datasetsListPanel.removeAll();
        final int idx = datasetCounter++;
        DatasetPanel dp = new DatasetPanel(idx, () -> removeDataset(idx));
        dp.putClientProperty("idx", idx);
        dp.setFromExportedCsv(datasetName, exportCsv.toFile());

        // Protocol auto-fill: ThunderSTORM writes results-protocol.txt alongside the CSV
        if (java.nio.file.Files.exists(exportProto)) {
            appendLog("  Protocol found — auto-filling camera/optics");
            dp.applyProtocol(exportProto.toFile());
        } else {
            String prefMag = Prefs.get("thunderstorm.setup.magnification", "");
            if (!prefMag.isEmpty()) dp.applyMagnification(prefMag);
            String prefProto = Prefs.get("thunderstorm.setup.path", "");
            if (!prefProto.isEmpty()) {
                java.io.File protoFile = new java.io.File(prefProto);
                if (protoFile.exists()) dp.applyProtocol(protoFile);
            }
        }

        datasetPanels.add(dp);
        datasetsListPanel.add(dp);
        datasetsListPanel.revalidate();
        datasetsListPanel.repaint();

        statusLabel.setText("Status: Loaded ThunderSTORM dataset (" + rows + " rows)");
        appendLog("  Dataset panel added: " + datasetName);
    }

    // -----------------------------------------------------------------------
    // Run analysis
    // -----------------------------------------------------------------------
    private void runAnalysis() {
        if (datasetPanels.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please add at least one dataset.");
            return;
        }

        List<DatasetEntry> entries = new ArrayList<>();
        for (DatasetPanel dp : datasetPanels) {
            if (!dp.isReady()) {
                JOptionPane.showMessageDialog(this,
                    "Dataset '" + dp.getDatasetName() + "' has no CSV file selected.");
                return;
            }
            DatasetEntry e = dp.toEntry();
            // Apply global advanced parameters from config panel
            e.qc.minBlinkCycles    = configPanel.getMinCycles();
            e.qc.minBlinkFrames    = configPanel.getMinFrames();
            e.qc.blinkGapFrames    = configPanel.getBlinkGap();
            e.qc.dbscanMinSamples  = configPanel.getMinLocs();
            e.qc.renderBinSizeNm   = configPanel.getBinSize();
            e.qc.collinearAngleDeg = configPanel.getAngle();
            e.qc.nSpots            = configPanel.getNSpots();
            e.qc.maxTriplets       = configPanel.getMaxTriplets();
            entries.add(e);
        }

        // Choose output directory (skip if already set from "Load Results…")
        if (outputDir == null) {
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle("Choose Output Directory");
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            outputDir = fc.getSelectedFile().toPath();
        }

        ColumnConfig colConfig  = configPanel.getColumnConfig();
        PlotConfig   plotConfig = configPanel.getPlotConfig();

        // Disable controls, show progress
        setRunningState(true);
        logArea.setText("");

        currentRunner = new AnalysisRunner(
            entries, colConfig, plotConfig, outputDir,
            line -> SwingUtilities.invokeLater(() -> appendLog(line)),
            results -> SwingUtilities.invokeLater(() -> onAnalysisDone(results))
        );
        currentRunner.execute();
    }

    private void onAnalysisDone(List<DatasetResult> results) {
        setRunningState(false);
        long ok = results.stream().filter(r -> r.error == null).count();
        statusLabel.setText("Status: Done (" + ok + "/" + results.size() + " OK)");
        exportZipBtn.setEnabled(outputDir != null);
        openFolderBtn.setEnabled(outputDir != null);
        resultsPanel.populate(results, outputDir);
        mainTabs.setSelectedIndex(1); // switch to Results tab
    }

    private void setRunningState(boolean running) {
        runBtn.setEnabled(!running);
        addDatasetBtn.setEnabled(!running);
        loadFijiBtn.setEnabled(!running);
        clearBtn.setEnabled(!running);
        progressBar.setIndeterminate(running);
        statusLabel.setText(running ? "Status: Running…" : "Status: Idle");
    }

    // -----------------------------------------------------------------------
    // Export ZIP
    // -----------------------------------------------------------------------
    private void exportZip() {
        if (outputDir == null) return;
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Save ZIP as…");
        fc.setSelectedFile(new File("thunderstorm_results.zip"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path target = fc.getSelectedFile().toPath();

        new SwingWorker<Void, Void>() {
            protected Void doInBackground() throws Exception {
                zipDirectory(outputDir, target);
                return null;
            }
            protected void done() {
                try {
                    get();
                    statusLabel.setText("Status: ZIP exported.");
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(AnalyzerFrame.this,
                        "ZIP export failed:\n" + ex.getMessage());
                }
            }
        }.execute();
    }

    private void zipDirectory(Path sourceDir, Path targetZip) throws IOException {
        try (var zos = new java.util.zip.ZipOutputStream(
                       Files.newOutputStream(targetZip))) {
            Files.walk(sourceDir).filter(p -> !Files.isDirectory(p)).forEach(p -> {
                String entry = sourceDir.relativize(p).toString().replace("\\", "/");
                try {
                    zos.putNextEntry(new java.util.zip.ZipEntry(entry));
                    Files.copy(p, zos);
                    zos.closeEntry();
                } catch (IOException ignored) {}
            });
        }
    }

    // -----------------------------------------------------------------------
    // Open folder
    // -----------------------------------------------------------------------
    private void openFolder() {
        if (outputDir == null) return;
        try { Desktop.getDesktop().open(outputDir.toFile()); }
        catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Cannot open folder:\n" + ex.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Check for updates (manual — GitHub Releases, on a background thread)
    // -----------------------------------------------------------------------
    private void checkForUpdates() {
        checkUpdatesBtn.setEnabled(false);
        new SwingWorker<UpdateChecker.Result, Void>() {
            protected UpdateChecker.Result doInBackground() {
                return UpdateChecker.check();
            }
            protected void done() {
                checkUpdatesBtn.setEnabled(true);
                UpdateChecker.Result r;
                try {
                    r = get();
                } catch (Exception ex) {
                    showInfo("Couldn't check for updates:\n" + ex.getMessage(),
                        JOptionPane.WARNING_MESSAGE);
                    return;
                }
                if (r.error != null) {
                    showInfo("Couldn't reach the update server.\n"
                        + "Check your internet connection and try again.",
                        JOptionPane.WARNING_MESSAGE);
                    return;
                }
                if (!r.updateAvailable) {
                    showInfo("You're up to date.\nInstalled version: " + r.currentVersion,
                        JOptionPane.INFORMATION_MESSAGE);
                    return;
                }
                String latest = r.latestTag != null ? r.latestTag.replaceFirst("^[vV]", "") : "?";
                int choice = JOptionPane.showConfirmDialog(AnalyzerFrame.this,
                    "Version " + latest + " is available (you have " + r.currentVersion + ").\n\n"
                        + "Open the download page?",
                    "Update Available", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
                if (choice == JOptionPane.YES_OPTION) {
                    try {
                        Desktop.getDesktop().browse(new URI(r.htmlUrl));
                    } catch (Exception ex) {
                        showInfo("Couldn't open the browser. Visit:\n" + r.htmlUrl,
                            JOptionPane.INFORMATION_MESSAGE);
                    }
                }
            }
        }.execute();
    }

    private void showInfo(String message, int messageType) {
        JOptionPane.showMessageDialog(this, message, "Check for Updates", messageType);
    }

    // -----------------------------------------------------------------------
    // Log
    // -----------------------------------------------------------------------
    private void appendLog(String line) {
        logArea.append(line + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }
}
