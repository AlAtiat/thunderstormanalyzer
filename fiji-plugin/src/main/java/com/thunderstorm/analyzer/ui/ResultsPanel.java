package com.thunderstorm.analyzer.ui;

import com.thunderstorm.analyzer.util.ResultsLoader;
import com.thunderstorm.analyzer.util.ResultsLoader.DatasetResult;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Results tab — populated after the analysis runner completes.
 * Shows per-dataset sub-tabs (Stats, Plots, Origami Viewer) and an optional
 * Comparison sub-tab when multiple datasets were processed.
 */
public class ResultsPanel extends JPanel {

    public ResultsPanel() {
        setLayout(new BorderLayout());
        showPlaceholder();
    }

    // -----------------------------------------------------------------------
    // Public API called from AnalyzerFrame after run completes
    // -----------------------------------------------------------------------

    public void populate(List<DatasetResult> results, Path outputDir) {
        removeAll();

        if (results == null || results.isEmpty()) {
            showPlaceholder();
            revalidate(); repaint();
            return;
        }

        JTabbedPane tabs = new JTabbedPane(JTabbedPane.TOP);

        for (DatasetResult r : results) {
            if (r.error != null) {
                tabs.addTab(r.name, buildErrorPanel(r.error));
            } else {
                tabs.addTab(r.name, buildDatasetTab(r));
            }
        }

        // Cross-dataset comparison tab
        Path cmpDir = outputDir.resolve("comparison");
        if (Files.isDirectory(cmpDir)) {
            tabs.addTab("Comparison", buildComparisonTab(cmpDir));
        }

        add(tabs, BorderLayout.CENTER);
        revalidate(); repaint();
    }

    // -----------------------------------------------------------------------
    // Per-dataset tab
    // -----------------------------------------------------------------------
    private JPanel buildDatasetTab(DatasetResult r) {
        JTabbedPane inner = new JTabbedPane(JTabbedPane.LEFT);
        inner.addTab("Stats",  buildStatsPanel(r));
        inner.addTab("Plots",  buildPlotsPanel(r));

        // Origami viewer tab — show if HTML carousel or showcase image exists
        Path html     = r.outputDir != null ? r.outputDir.resolve("structure_carousel.html") : null;
        Path showcase = r.plots != null ? r.plots.get("Blinking Showcase") : null;
        if ((html != null && Files.exists(html)) || showcase != null) {
            inner.addTab("Origami Viewer", buildOrigamiPanel(html, showcase));
        }

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.add(inner, BorderLayout.CENTER);
        return wrap;
    }

    private JPanel buildStatsPanel(DatasetResult r) {
        // Build table rows from stats map
        Map<String, String> stats = r.stats;
        String[][] data = stats.entrySet().stream()
            .map(e -> new String[]{e.getKey(), e.getValue()})
            .toArray(String[][]::new);
        String[] cols = {"Metric", "Value"};

        JTable table = new JTable(new DefaultTableModel(data, cols) {
            public boolean isCellEditable(int row, int col) { return false; }
        });
        table.setFillsViewportHeight(true);
        table.setRowHeight(22);
        table.getColumnModel().getColumn(0).setPreferredWidth(240);
        table.getColumnModel().getColumn(1).setPreferredWidth(120);

        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        p.add(new JScrollPane(table), BorderLayout.CENTER);

        if (r.outputDir != null) {
            JButton openFolder = new JButton("Open Results Folder");
            openFolder.addActionListener(e -> openInExplorer(r.outputDir));
            JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
            btnRow.add(openFolder);
            p.add(btnRow, BorderLayout.SOUTH);
        }
        return p;
    }

    private JPanel buildPlotsPanel(DatasetResult r) {
        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        if (r.plots != null) {
            for (Map.Entry<String, Path> entry : r.plots.entrySet()) {
                JPanel card = imageCard(entry.getKey(), entry.getValue());
                card.setAlignmentX(Component.LEFT_ALIGNMENT);
                card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 500));
                card.setPreferredSize(new Dimension(800, 500));
                col.add(card);
                col.add(Box.createVerticalStrut(10));
            }
        }

        JScrollPane scroll = new JScrollPane(col);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(scroll, BorderLayout.CENTER);
        return wrapper;
    }

    private JPanel buildOrigamiPanel(Path html, Path trace) {
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        if (trace != null) {
            BufferedImage img = ResultsLoader.loadImage(trace);
            if (img != null) p.add(new ScalableImagePanel(img, 1200, 700), BorderLayout.CENTER);
        }

        if (html != null && Files.exists(html)) {
            JButton openHtml = new JButton("Open Structure Carousel in Browser");
            openHtml.addActionListener(e -> {
                try { Desktop.getDesktop().browse(html.toUri()); }
                catch (IOException ex) {
                    JOptionPane.showMessageDialog(this, "Cannot open browser:\n" + ex.getMessage());
                }
            });
            JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
            btnRow.add(openHtml);
            p.add(btnRow, BorderLayout.SOUTH);
        }
        return p;
    }

    // -----------------------------------------------------------------------
    // Comparison tab
    // -----------------------------------------------------------------------
    private JPanel buildComparisonTab(Path cmpDir) {
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        String[] plots = {
            "nnd_boxplot.png", "uncertainty_boxplot.png",
            "intensity_boxplot.png", "sigma_boxplot.png",
            "nnd_cdf_overlay.png", "locs_per_frame_overlay.png"
        };
        for (String name : plots) {
            Path img = cmpDir.resolve(name);
            if (Files.exists(img)) {
                String label = name.replace(".png", "").replace("_", " ");
                JPanel card = imageCard(label, img);
                card.setAlignmentX(Component.LEFT_ALIGNMENT);
                card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 500));
                card.setPreferredSize(new Dimension(800, 500));
                col.add(card);
                col.add(Box.createVerticalStrut(10));
            }
        }
        JScrollPane scroll = new JScrollPane(col);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        p.add(scroll, BorderLayout.CENTER);

        JButton openFolder = new JButton("Open Comparison Folder");
        openFolder.addActionListener(e -> openInExplorer(cmpDir));
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        btnRow.add(openFolder);
        p.add(btnRow, BorderLayout.SOUTH);
        return p;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------
    private void showPlaceholder() {
        JLabel lbl = new JLabel(
            "<html><center><br><br><i>No results yet.<br>" +
            "Add datasets and click ▶ Run Analysis.</i></center></html>",
            SwingConstants.CENTER);
        lbl.setForeground(Color.GRAY);
        add(lbl, BorderLayout.CENTER);
    }

    private JPanel imageCard(String title, Path img) {
        JPanel card = new JPanel(new BorderLayout(0, 4));
        card.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(Color.LIGHT_GRAY),
            title,
            javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
            javax.swing.border.TitledBorder.DEFAULT_POSITION,
            new Font("SansSerif", Font.BOLD, 14)
        ));
        if (img != null && Files.exists(img)) {
            BufferedImage bi = ResultsLoader.loadImage(img);
            if (bi != null) {
                // ScalableImagePanel fills the card; preferred size is set by the caller
                card.add(new ScalableImagePanel(bi, 800, 450), BorderLayout.CENTER);
            } else {
                card.add(new JLabel("(not generated)", SwingConstants.CENTER), BorderLayout.CENTER);
            }
        } else {
            card.add(new JLabel("(not generated)", SwingConstants.CENTER), BorderLayout.CENTER);
        }
        return card;
    }

    // -----------------------------------------------------------------------
    // Responsive image panel — scales image to fill component at paint time
    // -----------------------------------------------------------------------
    private static class ScalableImagePanel extends JPanel {
        private final BufferedImage img;

        ScalableImagePanel(BufferedImage img, int prefW, int prefH) {
            this.img = img;
            setPreferredSize(new Dimension(prefW, prefH));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (img == null) return;
            int w = getWidth(), h = getHeight();
            double scale = Math.min((double) w / img.getWidth(), (double) h / img.getHeight());
            int dw = (int)(img.getWidth() * scale);
            int dh = (int)(img.getHeight() * scale);
            int ox = (w - dw) / 2;
            int oy = (h - dh) / 2;
            g.drawImage(img, ox, oy, dw, dh, this);
        }
    }

    private void addCard(JPanel grid, String title, Path img) {
        grid.add(imageCard(title, img));
    }

    private JPanel buildErrorPanel(String error) {
        JTextArea ta = new JTextArea("Analysis failed:\n\n" + error);
        ta.setForeground(Color.RED);
        ta.setEditable(false);
        ta.setBackground(getBackground());
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        p.add(new JScrollPane(ta), BorderLayout.CENTER);
        return p;
    }

    private void openInExplorer(Path dir) {
        try { Desktop.getDesktop().open(dir.toFile()); }
        catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Cannot open folder:\n" + ex.getMessage());
        }
    }
}
