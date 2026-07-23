package com.editora;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Single source of the app name, version, and build timestamp — shared by the {@code --version} CLI
 * output and the About dialog so they never drift apart.
 */
public final class AppInfo {

    public static final String NAME = "Editora";
    /** The version — the single source is {@code pom.xml}'s {@code <version>}, Maven-filtered into
     *  {@code build-info.properties} and read here, so a bump touches only the pom. Between releases the
     *  pom (and so this) carries a {@code -SNAPSHOT} suffix — see {@link #isSnapshot()}. */
    public static final String VERSION = loadVersion();
    /** Maven's marker for an unreleased development version, appended to the pom right after each release. */
    private static final String SNAPSHOT_SUFFIX = "-SNAPSHOT";
    /** Project home page (the website's custom domain). */
    public static final String HOMEPAGE = "https://editora-project.dev";
    /** Copyright notice (matches the bundled {@code LICENSE} file). */
    public static final String COPYRIGHT = "© 2026 Adrián Arturo De León Saldivar";
    /** Short license name; full terms are in the bundled {@code LICENSE} file. */
    public static final String LICENSE = "MIT License";
    /** The GitHub repository ({@code owner/name}) that publishes releases — the update-check source. */
    public static final String GITHUB_REPO = "adriandeleon/Editora";
    /** GitHub API endpoint for the latest published (non-prerelease, non-draft) release. */
    public static final String LATEST_RELEASE_API = "https://api.github.com/repos/" + GITHUB_REPO + "/releases/latest";
    /** Human-facing releases page (fallback link when the API response has no {@code html_url}). */
    public static final String RELEASES_PAGE = "https://github.com/" + GITHUB_REPO + "/releases/latest";

    private AppInfo() {}

    /** The Maven-filtered project version (single source: {@code pom.xml}'s {@code <version>}); falls back
     *  to {@code "0.0.0"} for an unfiltered run that bypassed Maven resource filtering (e.g. straight from
     *  an IDE) — every real build (incl. {@code mvn javafx:run} and the dist image) filters it. */
    private static String loadVersion() {
        try (InputStream in = AppInfo.class.getResourceAsStream("/com/editora/build-info.properties")) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                String v = props.getProperty("build.version", "");
                // Unfiltered (e.g. run straight from an IDE) leaves the literal Maven placeholder.
                if (!v.isEmpty() && !v.startsWith("${")) {
                    return v;
                }
            }
        } catch (IOException ignored) {
            // fall through to the dev fallback
        }
        return "0.0.0";
    }

    /** The Maven-filtered build timestamp; falls back gracefully for unfiltered/dev runs. */
    public static String buildTime() {
        try (InputStream in = AppInfo.class.getResourceAsStream("/com/editora/build-info.properties")) {
            if (in == null) {
                return "unknown";
            }
            Properties props = new Properties();
            props.load(in);
            String time = props.getProperty("build.time", "");
            // Unfiltered (e.g. run straight from an IDE) leaves the literal Maven placeholder.
            return time.isEmpty() || time.startsWith("${") ? "(dev build)" : time;
        } catch (IOException e) {
            return "unknown";
        }
    }

    /**
     * Whether this build runs an unreleased development version, i.e. the pom carries {@code -SNAPSHOT}.
     * The release flow bumps the pom to {@code <next>-SNAPSHOT} right after each tag, so anything built
     * off {@code master} between releases answers {@code true} and anything built from a release tag
     * answers {@code false} — which is how a test build tells itself apart from a shipped one.
     */
    public static boolean isSnapshot() {
        return isSnapshot(VERSION);
    }

    /** Pure form of {@link #isSnapshot()}, for tests. */
    static boolean isSnapshot(String version) {
        return version != null
                && version.strip().toUpperCase(java.util.Locale.ROOT).endsWith(SNAPSHOT_SUFFIX);
    }

    /**
     * {@link #VERSION} without any {@code -SNAPSHOT} suffix — the release this build is working toward.
     * Use it wherever a version must be a plain dotted number: the native-installer/bundle metadata
     * (jpackage rejects a non-numeric {@code --app-version}) and the versioned docs URLs.
     */
    public static String releaseVersion() {
        return releaseVersion(VERSION);
    }

    /** Pure form of {@link #releaseVersion()}, for tests. */
    static String releaseVersion(String version) {
        if (version == null) {
            return "";
        }
        String v = version.strip();
        return isSnapshot(v) ? v.substring(0, v.length() - SNAPSHOT_SUFFIX.length()) : v;
    }

    /** A one-line version string for {@code --version}, e.g. {@code "Editora 1.0.0 (built …)"}. */
    public static String versionLine() {
        return NAME + " " + VERSION + " (built " + buildTime() + ")";
    }

    /** {@code null} until first computed (then cached, possibly to {@code ""} when unavailable). */
    private static String gitCommit;

    /**
     * The short git commit of the working tree, or {@code ""} when it can't be determined (no {@code git}
     * on PATH, not a checkout, etc.). Read once at runtime via {@code git rev-parse}, then cached. Only
     * meant for dev builds (which run from the repo): it's surfaced in About/Welcome under {@code --dev}
     * only. Never throws.
     */
    public static String gitCommit() {
        if (gitCommit == null) {
            gitCommit = computeGitCommit();
        }
        return gitCommit;
    }

    private static String computeGitCommit() {
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "";
            }
            // A valid short hash is hex; anything else (e.g. "fatal: not a git repository") → none.
            return p.exitValue() == 0 && out.matches("[0-9a-f]{4,40}") ? out : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** {@code null} until first computed (then cached, possibly to {@code ""} when unavailable). */
    private static String gitBranch;

    /**
     * The current git branch of the working tree, or {@code ""} when it can't be determined (no {@code git}
     * on PATH, not a checkout, or a detached HEAD). Read once at runtime via {@code git rev-parse}, then
     * cached. Surfaced in About/Welcome for <b>snapshot</b> builds so a build made from a worktree/feature
     * branch can be told apart from one made off {@code master}. Never throws.
     */
    public static String gitBranch() {
        if (gitBranch == null) {
            gitBranch = computeGitBranch();
        }
        return gitBranch;
    }

    private static String computeGitBranch() {
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "";
            }
            // "HEAD" means a detached checkout (no branch); a git error line contains whitespace. Keep only a
            // plausible single-token ref name.
            if (p.exitValue() != 0 || out.isEmpty() || out.equals("HEAD") || out.matches(".*\\s.*")) {
                return "";
            }
            return out;
        } catch (Exception e) {
            return "";
        }
    }
}
