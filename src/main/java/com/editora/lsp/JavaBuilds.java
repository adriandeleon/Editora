package com.editora.lsp;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javafx.application.Platform;

import com.editora.lsp.LspManager.BuildOutcome;
import com.editora.lsp.LspManager.BuildResult;
import com.editora.lsp.LspManager.WatchedFile;
import com.editora.text.LspDiagnostic;

/**
 * Builds of a Java project through jdtls ({@code java/buildWorkspace}), for {@link LspManager}: Editora runs jdtls
 * with autobuild off, so these are what turn saved sources into class files — for Build Project, for refreshed
 * project diagnostics, and for a debug launch, which also wants the errors the build published. Outcomes reach
 * their callbacks on the FX thread.
 */
final class JavaBuilds {

    private final LspManager m;

    JavaBuilds(LspManager m) {
        this.m = m;
    }

    /** The budget for a full workspace build — passed to the session too, whose own request bound would
     *  otherwise cancel the build at the ordinary 30 seconds. */
    static final java.time.Duration BUILD_WORKSPACE_TIMEOUT = java.time.Duration.ofMinutes(10);

    /**
     * Rebuilds the project and republishes its diagnostics ({@code java.project.refreshDiagnostics}, #743).
     *
     * <p>Editora runs jdtls with {@code autobuild} disabled (see {@link #javaInitOptions}), so nothing ever
     * recomputes diagnostics for files that aren't open — this is the manual trigger. The results arrive the
     * usual way, as {@code publishDiagnostics} notifications, not as this command's return value.
     *
     * <p>Given a longer timeout than the other commands: a cold full build of a large project genuinely
     * takes minutes, and timing out early would leave the caller reporting failure while the build ran on.
     */
    public void refreshProjectDiagnostics(Path file, Consumer<BuildOutcome> cb) {
        LanguageServerSession s = m.javaSessionFor(file);
        if (s == null || file == null) {
            Platform.runLater(() -> cb.accept(BuildOutcome.UNAVAILABLE));
            return;
        }
        // (uri, isFullBuild) — a full build, since the point is the files we have NOT opened.
        s.rawRequest("java/buildWorkspace", Boolean.TRUE, BUILD_WORKSPACE_TIMEOUT)
                .orTimeout(BUILD_WORKSPACE_TIMEOUT.toMinutes(), java.util.concurrent.TimeUnit.MINUTES)
                .whenComplete((r, e) -> {
                    BuildOutcome outcome = e != null ? BuildOutcome.UNAVAILABLE : buildOutcome(r);
                    Platform.runLater(() -> cb.accept(outcome));
                });
    }

    /**
     * Builds the Java workspace {@code file} belongs to — incrementally unless {@code full} — and reports how it
     * went (callback on the FX thread). With autobuild off this is what turns saved sources into class files
     * jdtls (and java-debug inside it, for hot code replace) can see.
     */
    public void buildJavaWorkspace(Path file, boolean full, Consumer<BuildOutcome> cb) {
        LanguageServerSession s = file == null ? null : m.javaSessionFor(file);
        if (s == null || !LspServerRegistry.JAVA_SERVER_ID.equals(s.serverId())) {
            Platform.runLater(() -> cb.accept(BuildOutcome.UNAVAILABLE));
            return;
        }
        s.rawRequest("java/buildWorkspace", full, BUILD_WORKSPACE_TIMEOUT)
                .orTimeout(BUILD_WORKSPACE_TIMEOUT.toMinutes(), java.util.concurrent.TimeUnit.MINUTES)
                .whenComplete((r, e) -> {
                    BuildOutcome outcome = e != null ? BuildOutcome.UNAVAILABLE : buildOutcome(r);
                    Platform.runLater(() -> cb.accept(outcome));
                });
    }

    /** Pure: jdtls's {@code BuildWorkspaceStatus} — an ordinal (0 FAILED, 1 SUCCEED, 2 WITH_ERROR, 3 CANCELLED)
     *  or its name, possibly as a gson primitive. */
    static BuildOutcome buildOutcome(Object reply) {
        String text = reply instanceof com.google.gson.JsonPrimitive p ? p.getAsString() : String.valueOf(reply);
        text = text.strip();
        if (text.endsWith(".0")) {
            text = text.substring(0, text.length() - 2); // a number gson read as a double
        }
        return switch (text.toUpperCase(java.util.Locale.ROOT)) {
            case "0", "FAILED" -> BuildOutcome.FAILED;
            case "1", "SUCCEED", "SUCCEEDED" -> BuildOutcome.SUCCEEDED;
            case "2", "WITH_ERROR", "WITH_ERRORS" -> BuildOutcome.WITH_ERRORS;
            case "3", "CANCELLED" -> BuildOutcome.CANCELLED;
            default -> BuildOutcome.UNAVAILABLE;
        };
    }

    /** The error diagnostics each building session publishes, captured on the reader thread (see buildWorkspace). */
    final Map<LanguageServerSession, Map<Path, List<LspDiagnostic>>> buildCaptures =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Records {@code file}'s errors for a build of {@code source} in progress (reader thread). */
    void captureBuildErrors(LanguageServerSession source, Path file, List<LspDiagnostic> mapped) {
        Map<Path, List<LspDiagnostic>> capture = buildCaptures.get(source);
        if (capture == null) {
            return;
        }
        List<LspDiagnostic> errors = mapped.stream()
                .filter(d -> d.severity() == LspDiagnostic.Severity.ERROR)
                .toList();
        if (errors.isEmpty()) {
            capture.remove(file);
        } else {
            capture.put(file, errors);
        }
    }

    /**
     * Builds {@code file}'s Java project the way a debug launch needs it: Editora runs jdtls with autobuild off,
     * so nothing else writes the class files the program is launched from. An incremental build first; when it
     * reports errors without publishing any (the markers were already there), a full build, which republishes
     * them all. The result is delivered on the FX thread.
     */
    public void buildJavaWorkspaceForLaunch(Path file, Consumer<BuildResult> cb) {
        LanguageServerSession s = file == null ? null : m.sessionFor(file);
        if (s == null || !LspServerRegistry.JAVA_SERVER_ID.equals(s.serverId())) {
            Platform.runLater(() -> cb.accept(new BuildResult(BuildOutcome.UNAVAILABLE, Map.of())));
            return;
        }
        Runnable launchBuild = () -> build(s, false, first -> {
            if (first.outcome() == BuildOutcome.WITH_ERRORS && first.errors().isEmpty()) {
                build(s, true, cb);
            } else {
                cb.accept(first);
            }
        });
        Path root = s.root();
        if (root == null || !com.editora.process.ExecTargets.runsHere(root)) {
            launchBuild.run();
            return;
        }
        // What was written on disk just now may not have reached jdtls through a watcher yet (review 3, A207).
        Thread.ofVirtual().name("java-launch-fresh-sources").start(() -> {
            List<WatchedFile> fresh = FreshJavaSources.since(
                    root, System.currentTimeMillis() - FreshJavaSources.WINDOW_MS, FreshJavaSources.MAX_VISITED);
            Platform.runLater(() -> {
                // an open file's edits reach jdtls as its document's; only files changed outside Editora count
                List<org.eclipse.lsp4j.FileEvent> events =
                        LspFileWatching.eventsUnderRoot(fresh, root, s.uris()).stream()
                                .filter(e -> !s.isOpen(e.getUri()))
                                .toList();
                if (events.isEmpty()) {
                    launchBuild.run();
                    return;
                }
                s.didChangeWatchedFiles(events);
                // jdtls takes watched-file events in on a thread of its own: give it a moment before the build
                javafx.animation.PauseTransition settle =
                        new javafx.animation.PauseTransition(javafx.util.Duration.millis(FreshJavaSources.SETTLE_MS));
                settle.setOnFinished(e -> launchBuild.run());
                settle.play();
            });
        });
    }

    private void build(LanguageServerSession s, boolean full, Consumer<BuildResult> cb) {
        Map<Path, List<LspDiagnostic>> capture = new java.util.concurrent.ConcurrentHashMap<>();
        buildCaptures.put(s, capture);
        s.rawRequest("java/buildWorkspace", full, BUILD_WORKSPACE_TIMEOUT)
                .orTimeout(BUILD_WORKSPACE_TIMEOUT.toMinutes(), java.util.concurrent.TimeUnit.MINUTES)
                .whenComplete((r, e) -> {
                    buildCaptures.remove(s, capture);
                    BuildOutcome outcome = e != null ? BuildOutcome.UNAVAILABLE : buildOutcome(r);
                    Map<Path, List<LspDiagnostic>> errors = new java.util.TreeMap<>(capture);
                    Platform.runLater(
                            () -> cb.accept(new BuildResult(outcome, java.util.Collections.unmodifiableMap(errors))));
                });
    }
}
