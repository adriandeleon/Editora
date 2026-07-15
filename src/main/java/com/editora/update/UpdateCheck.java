package com.editora.update;

import com.editora.plugin.PluginInstaller;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Pure, toolkit-free core of the update check: parse a GitHub {@code /releases/latest} JSON payload into a
 * {@link ReleaseInfo}, decide whether that release is newer than the running version, and throttle how often
 * the background check runs. All decisions are testable without a network or the FX toolkit; the impure
 * fetch/schedule lives in {@link UpdateService}.
 */
public final class UpdateCheck {

    /** Default background-check interval: once a day. */
    public static final long DEFAULT_INTERVAL_MS = 24L * 60 * 60 * 1000;

    private UpdateCheck() {}

    /**
     * Parses a GitHub releases-API payload (the {@code /releases/latest} object) into a {@link ReleaseInfo},
     * or {@code null} when it isn't a usable release ({@code draft}/{@code prerelease}, or no {@code tag_name}).
     * Reads via {@code readTree} (no POJO binding, so no {@code module-info opens}).
     */
    public static ReleaseInfo parseLatest(ObjectMapper mapper, byte[] json) {
        try {
            JsonNode root = mapper.readTree(json);
            if (root == null || !root.isObject()) {
                return null;
            }
            if (root.path("draft").asBoolean(false) || root.path("prerelease").asBoolean(false)) {
                return null; // a full release only — never nudge toward a draft/pre-release
            }
            String tag = root.path("tag_name").asText("").strip();
            if (tag.isEmpty()) {
                return null;
            }
            String version = normalizeVersion(tag);
            String url = root.path("html_url").asText("").strip();
            String name = root.path("name").asText("").strip();
            return new ReleaseInfo(version, url, name);
        } catch (Exception e) {
            return null;
        }
    }

    /** Strips a leading {@code v}/{@code V} from a tag ({@code v0.9.5} → {@code 0.9.5}). */
    public static String normalizeVersion(String tag) {
        String t = tag == null ? "" : tag.strip();
        if (!t.isEmpty() && (t.charAt(0) == 'v' || t.charAt(0) == 'V')) {
            return t.substring(1);
        }
        return t;
    }

    /**
     * Whether {@code latest} is strictly newer than {@code current} (both plain semver, no leading {@code v}).
     * Reuses the shared, unit-tested {@link PluginInstaller#compareVersions}. A blank/unknown current version
     * ({@code 0.0.0} dev fallback) is treated as older, so a dev run still surfaces the real latest release.
     */
    public static boolean isNewer(String current, String latest) {
        if (latest == null || latest.isBlank()) {
            return false;
        }
        return PluginInstaller.compareVersions(latest.strip(), current == null ? "" : current.strip()) > 0;
    }

    /**
     * Whether a background check is due: never checked ({@code lastCheckMs <= 0}), or at least {@code intervalMs}
     * has elapsed since the last one. A future {@code lastCheckMs} (clock moved back) also counts as due.
     */
    public static boolean isDue(long lastCheckMs, long nowMs, long intervalMs) {
        if (lastCheckMs <= 0) {
            return true;
        }
        long elapsed = nowMs - lastCheckMs;
        return elapsed < 0 || elapsed >= intervalMs;
    }
}
