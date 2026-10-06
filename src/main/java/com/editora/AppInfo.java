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

    /**
     * The documentation site for THIS build — {@code <homepage>/docs/v-<version>}.
     *
     * <p>Versioned rather than a bare {@code /docs} link, so a build never points at documentation for a
     * different release. Lives here rather than in the UI because both the Help menu and the command
     * palette's per-command help need the same base, and a second copy of the path is a second thing to
     * update when the site moves.
     */
    public static String docsUrl() {
        return HOMEPAGE + "/docs/v-" + releaseVersion();
    }
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

    // --- git identity of a development build ----------------------------------------------------------

    /** Hard cap on one {@code git rev-parse}; a wedged git must not stall the lookup thread for long. */
    private static final java.time.Duration GIT_TIMEOUT = java.time.Duration.ofSeconds(2);

    private static final Object GIT_LOCK = new Object();
    private static java.util.concurrent.CompletableFuture<String> gitCommit;
    private static java.util.concurrent.CompletableFuture<String> gitBranch;

    /**
     * The short git commit this build was made from, or {@code ""} when it can't be determined (no
     * {@code git}, not a checkout, a packaged install) <b>or is not known yet</b>. Only meant for dev builds
     * (which run from the repo): it's surfaced in About/Welcome under {@code --dev} only.
     *
     * <p><b>Never blocks and never throws</b>, so it is safe on the FX thread: the first call starts a
     * background lookup (see {@link #gitCommitAsync()}) and returns {@code ""}; later calls return the cached
     * answer. A caller that must show the value as soon as it exists takes the future instead.
     */
    public static String gitCommit() {
        return gitCommitAsync().getNow("");
    }

    /** The lookup behind {@link #gitCommit()}: started once, on a daemon thread, and cached. Never fails. */
    public static java.util.concurrent.CompletableFuture<String> gitCommitAsync() {
        synchronized (GIT_LOCK) {
            if (gitCommit == null) {
                gitCommit = lookup(() -> commitFrom(git("rev-parse", "--short", "HEAD")));
            }
            return gitCommit;
        }
    }

    /**
     * The git branch this build was made from, or {@code ""} when it can't be determined (no {@code git}, not
     * a checkout, a detached HEAD) or is not known yet. Surfaced in About for <b>snapshot</b> builds so a
     * build made from a worktree/feature branch can be told apart from one made off {@code master}.
     * Non-blocking, exactly like {@link #gitCommit()}.
     */
    public static String gitBranch() {
        return gitBranchAsync().getNow("");
    }

    /** The lookup behind {@link #gitBranch()}: started once, on a daemon thread, and cached. Never fails. */
    public static java.util.concurrent.CompletableFuture<String> gitBranchAsync() {
        synchronized (GIT_LOCK) {
            if (gitBranch == null) {
                gitBranch = lookup(() -> branchFrom(git("rev-parse", "--abbrev-ref", "HEAD")));
            }
            return gitBranch;
        }
    }

    /**
     * Runs {@code query} on a daemon thread and completes with its answer, or {@code ""} if it throws.
     *
     * <p>The query spawns a process — and the first {@code ProcessRunner} call may also run the login-shell
     * PATH probe, which can take seconds — so it must not run on the thread that asks. The old code ran
     * {@code git} inline on the FX thread, and read its output to EOF <em>before</em> the {@code waitFor}
     * that carried the timeout, so a git that never exited froze the window for good.
     */
    static java.util.concurrent.CompletableFuture<String> lookup(java.util.function.Supplier<String> query) {
        java.util.concurrent.CompletableFuture<String> future = new java.util.concurrent.CompletableFuture<>();
        Thread t = new Thread(
                () -> {
                    try {
                        String value = query.get();
                        future.complete(value == null ? "" : value);
                    } catch (Throwable e) { // NOSONAR: a display-only nicety must never surface a failure
                        future.complete("");
                    }
                },
                "app-git-info");
        t.setDaemon(true);
        t.start();
        return future;
    }

    /**
     * One bounded {@code git} query in this build's own checkout, through {@link
     * com.editora.process.ProcessRunner} (augmented PATH, parse-stable locale, closed stdin, an enforced
     * timeout). {@code null} when the build has no directory to ask about.
     */
    private static com.editora.process.ProcessRunner.Result git(String... args) {
        java.nio.file.Path dir = checkoutDir(codeSource(), System.getProperty("java.home"));
        if (dir == null) {
            return null;
        }
        java.util.List<String> argv = new java.util.ArrayList<>();
        argv.add("git");
        argv.addAll(java.util.List.of(args));
        return com.editora.process.ProcessRunner.run(dir, GIT_TIMEOUT, argv);
    }

    private static java.net.URL codeSource() {
        try {
            java.security.CodeSource source =
                    AppInfo.class.getProtectionDomain().getCodeSource();
            return source == null ? null : source.getLocation();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The directory whose git checkout describes <em>this build</em>: the classes directory of a dev run
     * ({@code target/classes}), the folder holding the jar, or — for a jlinked image, whose classes live in
     * {@code jrt:} — the bundled runtime ({@code javaHome}), which sits inside the checkout when the image
     * was built there. {@code null} when none exists.
     *
     * <p>Not the launch working directory, which is what the old code used implicitly: an editor started
     * from inside some other repository reported <em>that</em> repository's commit and branch as its own.
     * Pure apart from the existence checks; unit-tested.
     */
    static java.nio.file.Path checkoutDir(java.net.URL codeSource, String javaHome) {
        if (codeSource != null && "file".equalsIgnoreCase(codeSource.getProtocol())) {
            try {
                java.nio.file.Path location = java.nio.file.Path.of(codeSource.toURI());
                if (java.nio.file.Files.isDirectory(location)) {
                    return location;
                }
                java.nio.file.Path parent = location.toAbsolutePath().getParent();
                if (parent != null && java.nio.file.Files.isDirectory(parent)) {
                    return parent;
                }
            } catch (java.net.URISyntaxException | RuntimeException e) {
                // fall through to the runtime directory
            }
        }
        if (javaHome != null && !javaHome.isBlank()) {
            try {
                java.nio.file.Path home = java.nio.file.Path.of(javaHome);
                if (java.nio.file.Files.isDirectory(home)) {
                    return home;
                }
            } catch (RuntimeException e) {
                // an unusable java.home just means there is nothing to ask about
            }
        }
        return null;
    }

    /** The short hash in a {@code git rev-parse --short HEAD} result, or {@code ""}. Pure; unit-tested. */
    static String commitFrom(com.editora.process.ProcessRunner.Result result) {
        if (result == null || !result.ok() || result.out() == null) {
            return "";
        }
        String out = result.out().strip();
        // A valid short hash is hex; anything else (e.g. "fatal: not a git repository") → none.
        return out.matches("[0-9a-f]{4,40}") ? out : "";
    }

    /** The branch in a {@code git rev-parse --abbrev-ref HEAD} result, or {@code ""}. Pure; unit-tested. */
    static String branchFrom(com.editora.process.ProcessRunner.Result result) {
        if (result == null || !result.ok() || result.out() == null) {
            return "";
        }
        String out = result.out().strip();
        // "HEAD" means a detached checkout (no branch); a git error line contains whitespace. Keep only a
        // plausible single-token ref name.
        return out.isEmpty() || out.equals("HEAD") || out.matches(".*\\s.*") ? "" : out;
    }
}
