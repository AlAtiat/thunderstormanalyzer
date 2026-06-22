package com.thunderstorm.analyzer.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.thunderstorm.analyzer.ThunderSTORMAnalyzer_;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Manual update check against the project's GitHub Releases.
 *
 * Nothing here runs automatically — {@link com.thunderstorm.analyzer.ui.AnalyzerFrame}
 * calls {@link #check()} on a background thread when the user clicks "Check for Updates".
 * Uses only the JDK HTTP client and gson (already a dependency); no API token. The
 * unauthenticated GitHub API allows 60 requests/hour/IP, ample for an on-demand check.
 */
public class UpdateChecker {

    public static final String GITHUB_REPO   = "AlAtiat/thunderstormanalyzer";
    public static final String RELEASES_API  = "https://api.github.com/repos/" + GITHUB_REPO + "/releases/latest";
    public static final String RELEASES_PAGE = "https://github.com/" + GITHUB_REPO + "/releases/latest";

    /** Used when the jar manifest has no Implementation-Version (e.g. run from the IDE). */
    private static final String FALLBACK_VERSION = "1.0.0";

    /** Outcome of a check. When {@code error != null} the check could not be completed. */
    public static class Result {
        public String  currentVersion;
        public String  latestTag;        // e.g. "v1.1.0"
        public String  htmlUrl;          // release page to open
        public boolean updateAvailable;
        public String  error;            // non-null on failure (no network, bad status, ...)
    }

    /** Version baked into the shaded jar manifest (Implementation-Version), or a fallback. */
    public static String currentVersion() {
        Package pkg = ThunderSTORMAnalyzer_.class.getPackage();
        String v = pkg != null ? pkg.getImplementationVersion() : null;
        return (v != null && !v.isEmpty()) ? v : FALLBACK_VERSION;
    }

    /** Blocking GitHub Releases check. Never throws — failures land in {@link Result#error}. */
    public static Result check() {
        Result r = new Result();
        r.currentVersion = currentVersion();
        r.htmlUrl = RELEASES_PAGE;
        try {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(RELEASES_API))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "thunderstormanalyzer-update-check")
                .GET()
                .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                r.error = "Update server returned HTTP " + resp.statusCode();
                return r;
            }
            JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
            if (json.has("tag_name") && !json.get("tag_name").isJsonNull()) {
                r.latestTag = json.get("tag_name").getAsString();
            }
            if (json.has("html_url") && !json.get("html_url").isJsonNull()) {
                r.htmlUrl = json.get("html_url").getAsString();
            }
            r.updateAvailable = r.latestTag != null && isNewer(r.latestTag, r.currentVersion);
        } catch (Exception ex) {
            r.error = ex.getMessage() != null ? ex.getMessage() : ex.toString();
        }
        return r;
    }

    /** True when {@code latestTag} is a strictly newer version than {@code current}. */
    static boolean isNewer(String latestTag, String current) {
        int[] a = parseVersion(latestTag);
        int[] b = parseVersion(current);
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int ai = i < a.length ? a[i] : 0;
            int bi = i < b.length ? b[i] : 0;
            if (ai != bi) return ai > bi;
        }
        return false;
    }

    /** Parse leading integers from a dotted version/tag; tolerates a leading 'v' and suffixes. */
    static int[] parseVersion(String value) {
        if (value == null) return new int[0];
        String cleaned = value.trim();
        while (!cleaned.isEmpty() && (cleaned.charAt(0) == 'v' || cleaned.charAt(0) == 'V')) {
            cleaned = cleaned.substring(1);
        }
        String[] chunks = cleaned.split("\\.");
        java.util.List<Integer> parts = new java.util.ArrayList<>();
        for (String chunk : chunks) {
            StringBuilder digits = new StringBuilder();
            for (int i = 0; i < chunk.length(); i++) {
                char c = chunk.charAt(i);
                if (Character.isDigit(c)) digits.append(c);
                else break;
            }
            if (digits.length() == 0) break;
            parts.add(Integer.parseInt(digits.toString()));
        }
        int[] out = new int[parts.size()];
        for (int i = 0; i < out.length; i++) out[i] = parts.get(i);
        return out;
    }
}
