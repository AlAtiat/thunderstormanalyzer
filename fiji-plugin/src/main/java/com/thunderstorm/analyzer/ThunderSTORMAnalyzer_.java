package com.thunderstorm.analyzer;

import com.thunderstorm.analyzer.ui.AnalyzerFrame;
import ij.plugin.PlugIn;

import javax.swing.*;

/**
 * ImageJ/Fiji plugin entry point.
 *
 * The trailing underscore makes ImageJ recognise this as a runnable plugin.
 * Registered in plugins.config as:
 *   Plugins>ThunderSTORM, "ThunderSTORM Analyzer", com.thunderstorm.analyzer.ThunderSTORMAnalyzer_
 *
 * Installation:
 *   1. mvn package  (in fiji-plugin/)
 *   2. Copy target/thunderstorm-analyzer-1.0.0.jar → Fiji.app/plugins/
 *   3. Start Fiji → Plugins > ThunderSTORM > ThunderSTORM Analyzer
 */
public class ThunderSTORMAnalyzer_ implements PlugIn {

    @Override
    public void run(String arg) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}
            AnalyzerFrame frame = new AnalyzerFrame();
            frame.setVisible(true);
        });
    }

    /** Standalone entry point for development/testing outside Fiji. */
    public static void main(String[] args) {
        new ThunderSTORMAnalyzer_().run("");
    }
}
