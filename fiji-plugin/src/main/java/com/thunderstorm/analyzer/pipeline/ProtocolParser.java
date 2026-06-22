package com.thunderstorm.analyzer.pipeline;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.thunderstorm.analyzer.model.QcParams;
import ij.Prefs;

import java.io.FileReader;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a ThunderSTORM JSON protocol file and derives QC thresholds automatically.
 * Also reads magnification from Fiji Preferences if set by ThunderSTORM.
 */
public class ProtocolParser {

    private static final double FALLBACK_INITIAL_SIGMA_PX = 1.6;
    private static final Pattern FILTER_REGEX =
        Pattern.compile("(\\w+)\\s*[<>]=?\\s*([\\d.]+)");

    public static class ProtocolInfo {
        public double cameraPxNm       = 160.0;  // raw camera pixel size from cameraSettings.pixelSize
        public double magnification    = 100.0;
        public double initialSigmaPx   = FALLBACK_INITIAL_SIGMA_PX;
        public double filterUncertainty = Double.NaN;
        public double filterIntensity   = Double.NaN;
        // raw protocol for stats.json
        public String sourceImage      = "";
        public String detector         = "";
        public String estimator        = "";
        public String tsVersion        = "";
    }

    /** Parse a ThunderSTORM protocol JSON file. */
    public static ProtocolInfo parse(Path protocolPath) {
        ProtocolInfo info = new ProtocolInfo();
        if (protocolPath == null) return info;

        try (FileReader fr = new FileReader(protocolPath.toFile())) {
            JsonObject root = JsonParser.parseReader(fr).getAsJsonObject();

            // Camera settings — pixelSize is the RAW sensor pixel size in nm
            if (root.has("cameraSettings")) {
                JsonObject cam = root.getAsJsonObject("cameraSettings");
                if (cam.has("pixelSize"))
                    info.cameraPxNm = cam.get("pixelSize").getAsDouble();
            }

            // Source image title lives under imageInfo.title (not root.sourceImage)
            if (root.has("imageInfo")) {
                JsonObject imgInfo = root.getAsJsonObject("imageInfo");
                if (imgInfo.has("title"))
                    info.sourceImage = imgInfo.get("title").getAsString();
            } else if (root.has("sourceImage")) {
                info.sourceImage = root.get("sourceImage").getAsString();
            }
            if (root.has("version")) info.tsVersion = root.get("version").getAsString();

            // Analysis estimator (contains initialSigma)
            if (root.has("analysisEstimator")) {
                JsonObject est = root.getAsJsonObject("analysisEstimator");
                info.estimator = est.toString();
                if (est.has("initialSigma"))
                    info.initialSigmaPx = est.get("initialSigma").getAsDouble();
                else if (est.has("sigma"))
                    info.initialSigmaPx = est.get("sigma").getAsDouble();
            }

            // Detector
            if (root.has("analysisDetector")) {
                JsonObject det = root.getAsJsonObject("analysisDetector");
                info.detector = det.toString();
            }

            // Post-processing filters
            if (root.has("postProcessing")) {
                for (var el : root.getAsJsonArray("postProcessing")) {
                    JsonObject step = el.getAsJsonObject();
                    String stepName = step.has("name") ? step.get("name").getAsString() : "";
                    if (stepName.equalsIgnoreCase("Filter") && step.has("options")) {
                        parseFilterOptions(step.get("options").getAsString(), info);
                    }
                }
            }
        } catch (Exception ex) {
            // Return defaults if file unreadable
        }

        // Also try to read magnification from Fiji ThunderSTORM prefs
        String prefMag = Prefs.get("thunderstorm.setup.magnification", "");
        if (!prefMag.isEmpty()) {
            try { info.magnification = Double.parseDouble(prefMag.trim()); }
            catch (NumberFormatException ignored) {}
        }

        return info;
    }

    /** Apply protocol-derived values to a QcParams object. */
    public static void autoFillQc(ProtocolInfo info, QcParams qc) {
        // ThunderSTORM's cameraSettings.pixelSize is the EFFECTIVE pixel size; mirror the
        // Python reference (qc_from_protocol): treat it as effective with magnification = 1
        // rather than dividing by magnification (which would shrink it ~100×).
        qc.cameraPixelSizeNm = info.cameraPxNm;
        qc.magnification     = 1.0;
        qc.pixelSizeNm       = info.cameraPxNm;             // effective = protocol pixelSize
        qc.maxSigmaNm        = info.initialSigmaPx * qc.pixelSizeNm * 3.0;
        qc.maxUncertaintyNm  = qc.pixelSizeNm / 4.0;

        if (!Double.isNaN(info.filterUncertainty))
            qc.maxUncertaintyNm = info.filterUncertainty;
        if (!Double.isNaN(info.filterIntensity))
            qc.minIntensity = info.filterIntensity;
    }

    // -----------------------------------------------------------------------
    private static void parseFilterOptions(String options, ProtocolInfo info) {
        Matcher m = FILTER_REGEX.matcher(options);
        while (m.find()) {
            String key = m.group(1).toLowerCase();
            double val;
            try { val = Double.parseDouble(m.group(2)); }
            catch (NumberFormatException e) { continue; }

            if (key.contains("uncertainty")) info.filterUncertainty = val;
            else if (key.contains("intensity")) info.filterIntensity = val;
        }
    }
}
