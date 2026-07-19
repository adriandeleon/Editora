package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.util.Duration;

import com.editora.build.BuildTool;
import com.editora.run.StackTraceLinks;
import com.editora.test.JvmReportDirs;
import com.editora.test.ParsedSuite;
import com.editora.test.TapParser;
import com.editora.test.TestNode;
import com.editora.test.TestResultParser;
import com.editora.test.TestResultParsers;
import com.editora.test.TestRun;
import com.editora.test.TestTreeBuilder;

import static com.editora.i18n.Messages.tr;

/**
 * The Test Results feature (CoordinatorHost pattern). Implements {@link TestRunHook}: when a
 * {@link BuildCoordinator} recognizes a {@code test} run it forwards the stream here, and this coordinator
 * builds the results tree — from the raw stream (Go/Cargo/npm) or by polling the JUnit-XML reports dir
 * (Maven/Gradle) — into {@link TestRunnerPanel}. Tree merges are coalesced to one panel refresh per FX pulse;
 * JVM report files are parsed off the FX thread on a poll thread and merged on the FX thread; an elapsed-time
 * {@link Timeline} ticks the header. Owned by {@code MainController}, which supplies the {@link Ops} window
 * hooks and injects this hook into every build coordinator.
 */
final class TestRunCoordinator implements TestRunHook {

    /** Window hooks beyond {@link CoordinatorHost}. */
    interface Ops {
        /** Opens (and focuses) the Test Results tool window. */
        void openTestResults();

        /** Shows/hides the Test Results stripe (available once a run has occurred). */
        void setTestResultsAvailable(boolean available);

        /** A stack-trace frame (from a failure) to resolve + jump to — reuses the shared run-link resolver. */
        void openLink(StackTraceLinks.Link link);

        /** Navigate to a test's source by name (class→file + method), when there's no usable stack frame. */
        void jumpToTest(TestNode node, BuildTool tool);

        /** Re-invoke the tool's build coordinator with these task args (rerun / rerun-failed). */
        void runTest(BuildTool tool, Path root, List<String> taskArgs, List<String> toggleArgs);

        /** Stop the running build for {@code tool} (the tool's own BuildService). */
        void stopTest(BuildTool tool);
    }

    /** JVM report poll interval — reports appear per class as each finishes; ~750 ms is responsive + cheap. */
    private static final long POLL_MS = 750;
    /** Bounded depth for the reactor/multi-project report-dir walk. */
    private static final int WALK_DEPTH = 5;
    /** Cap the npm sniff buffer so a non-TAP runner can't grow memory before we give up on structure. */
    private static final int NPM_SNIFF_LIMIT = 200;

    private final CoordinatorHost host;
    private final Ops ops;
    private final TestRunnerPanel panel = new TestRunnerPanel();
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "test-report-poll");
        t.setDaemon(true);
        return t;
    });

    private TestRun currentRun;
    private BuildTool currentTool;
    private TestResultParser parser;
    private boolean fileBased;
    private ScheduledFuture<?> pollTask;
    private final Map<Path, Long> seenMtimes = new HashMap<>();
    private Timeline elapsedTimer;
    private boolean refreshPending;

    // npm TAP sniffing
    private boolean npm;
    private boolean tapDecided;
    private final List<String> npmSniff = new ArrayList<>();

    TestRunCoordinator(CoordinatorHost host, Ops ops) {
        this.host = host;
        this.ops = ops;
        panel.setOnRerun(this::rerun);
        panel.setOnRerunFailed(this::rerunFailed);
        panel.setOnStop(this::stop);
        panel.setOnLink(ops::openLink);
        panel.setOnActivate(this::activate);
    }

    TestRunnerPanel panel() {
        return panel;
    }

    void setOutputFont(String family, int size) {
        panel.setOutputFont(family, size);
    }

    private boolean isEnabled() {
        return host.settings().isTestRunner() && !host.simpleModeActive();
    }

    // --- TestRunHook -------------------------------------------------------------------------------

    @Override
    public boolean onTestRunStart(
            BuildTool tool,
            Path workingDir,
            List<String> taskArgs,
            List<String> toggleArgs,
            List<String> resolvedArgv) {
        if (!isEnabled()) {
            return false;
        }
        currentTool = tool;
        currentRun = new TestRun(tool, workingDir, taskArgs, toggleArgs, System.currentTimeMillis());
        parser = TestResultParsers.forTool(tool);
        fileBased = TestResultParsers.isFileBased(tool);
        npm = tool == BuildTool.NPM;
        tapDecided = false;
        npmSniff.clear();
        seenMtimes.clear();

        panel.startRun(String.join(" ", taskArgs));
        ops.setTestResultsAvailable(true);
        ops.openTestResults();
        startElapsedTimer();
        if (fileBased) {
            startPolling(tool, workingDir);
        }
        return true;
    }

    @Override
    public void onTestOutput(String line, boolean stderr) {
        if (currentRun == null || fileBased) {
            return; // JVM: results come from the report files, not the console
        }
        if (npm && !tapDecided) {
            npmSniff.add(line);
            if (TapParser.looksLikeTap(npmSniff)) {
                tapDecided = true;
                for (String buffered : npmSniff) {
                    mergeAll(parser.onLine(buffered, false));
                }
                npmSniff.clear();
            } else if (npmSniff.size() > NPM_SNIFF_LIMIT) {
                npmSniff.clear(); // keep sniffing cheaply; the exit handler shows the fallback banner
            }
            return;
        }
        mergeAll(parser.onLine(line, stderr));
    }

    @Override
    public void onTestExit(int code) {
        finish(code);
    }

    @Override
    public void onTestError(String message) {
        if (currentRun != null) {
            host.setStatus(tr("status.testrunner.failed", message));
        }
        finish(-1);
    }

    @Override
    public String consoleLine(String raw, boolean stderr) {
        return parser != null ? parser.consoleLine(raw, stderr) : raw;
    }

    // --- run lifecycle -----------------------------------------------------------------------------

    private void finish(int code) {
        if (currentRun == null) {
            return;
        }
        stopPolling();
        if (fileBased) {
            sweepReports(currentTool, currentRun.workingDir(), true); // final sweep: catch the last class
        } else {
            mergeAll(parser.onExit(code));
            if (npm && !tapDecided) {
                // No structured (TAP) output — surface an honest banner rather than an empty tree.
                TestTreeBuilder.merge(currentRun.root(), new ParsedSuite(tr("testrunner.tap.unavailable"), List.of()));
            }
        }
        stopElapsedTimer();
        currentRun.finish(code, System.currentTimeMillis());
        TestRun run = currentRun;
        panel.finishRun(run, code);
    }

    private void mergeAll(List<ParsedSuite> suites) {
        if (suites.isEmpty() || currentRun == null) {
            return;
        }
        for (ParsedSuite suite : suites) {
            TestTreeBuilder.merge(currentRun.root(), suite);
        }
        requestRefresh();
    }

    /** Coalesces many merges in one FX pulse into a single panel refresh. */
    private void requestRefresh() {
        if (refreshPending || currentRun == null) {
            return;
        }
        refreshPending = true;
        Platform.runLater(() -> {
            refreshPending = false;
            if (currentRun != null) {
                panel.update(currentRun);
            }
        });
    }

    // --- elapsed timer -----------------------------------------------------------------------------

    private void startElapsedTimer() {
        stopElapsedTimer();
        elapsedTimer = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            if (currentRun != null && currentRun.isRunning()) {
                panel.setElapsed(currentRun.elapsedMillis(System.currentTimeMillis()));
            }
        }));
        elapsedTimer.setCycleCount(Animation.INDEFINITE);
        elapsedTimer.play();
    }

    private void stopElapsedTimer() {
        if (elapsedTimer != null) {
            elapsedTimer.stop();
            elapsedTimer = null;
        }
    }

    // --- JVM report polling ------------------------------------------------------------------------

    private void startPolling(BuildTool tool, Path root) {
        pollTask = poller.scheduleWithFixedDelay(
                () -> sweepReports(tool, root, false), POLL_MS, POLL_MS, TimeUnit.MILLISECONDS);
    }

    private void stopPolling() {
        if (pollTask != null) {
            pollTask.cancel(false);
            pollTask = null;
        }
    }

    /**
     * Scans the report dirs for {@code TEST-*.xml} files, parses those whose mtime advanced (all of them when
     * {@code full}), and merges the results on the FX thread. Runs on the poll thread (or the FX thread for the
     * final sweep — parsing is bounded and off the hot path either way).
     */
    private void sweepReports(BuildTool tool, Path root, boolean full) {
        try {
            List<ParsedSuite> parsed = new ArrayList<>();
            for (Path file : reportFiles(tool, root)) {
                long mtime = Files.getLastModifiedTime(file).toMillis();
                Long seen = seenMtimes.get(file);
                if (!full && seen != null && seen == mtime) {
                    continue;
                }
                ParsedSuite suite = parser.parseReportFile(file);
                if (suite != null) {
                    seenMtimes.put(file, mtime);
                    parsed.add(suite);
                }
            }
            if (!parsed.isEmpty()) {
                Platform.runLater(() -> mergeAll(parsed));
            }
        } catch (Exception e) {
            // A transient FS error mid-run must not kill the poller; the next tick / final sweep retries.
        }
    }

    private List<Path> reportFiles(BuildTool tool, Path root) {
        Set<Path> dirs = new LinkedHashSet<>(JvmReportDirs.reportDirs(tool, root));
        try (Stream<Path> walk = Files.walk(root, WALK_DEPTH)) {
            walk.filter(Files::isDirectory)
                    .filter(d -> {
                        Path name = d.getFileName();
                        return name != null && JvmReportDirs.isReportDirName(tool, name.toString());
                    })
                    .forEach(dirs::add);
        } catch (Exception ignored) {
            // fall back to the standard leaf dirs
        }
        List<Path> files = new ArrayList<>();
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> list = Files.walk(dir, 2)) {
                list.filter(Files::isRegularFile)
                        .filter(f -> {
                            String n = f.getFileName().toString();
                            return n.startsWith("TEST-") && n.endsWith(".xml");
                        })
                        .forEach(files::add);
            } catch (Exception ignored) {
                // skip an unreadable dir
            }
        }
        return files;
    }

    // --- panel actions -----------------------------------------------------------------------------

    private void activate(TestNode node) {
        if (node == null) {
            return;
        }
        if (node.stackTrace() != null) {
            StackTraceLinks.Link link = firstFrame(node.stackTrace(), node.sourceFileHint());
            if (link != null) {
                ops.openLink(link);
                return;
            }
        }
        ops.jumpToTest(node, currentTool);
    }

    /** The first stack frame that resolves to a location — preferring one in the test's own source file. */
    private static StackTraceLinks.Link firstFrame(String stackTrace, String preferredFile) {
        StackTraceLinks.Link fallback = null;
        for (String line : stackTrace.split("\n")) {
            StackTraceLinks.Link link = StackTraceLinks.parse(line);
            if (link == null) {
                continue;
            }
            if (preferredFile != null && link.file() != null && link.file().endsWith(preferredFile)) {
                return link;
            }
            if (fallback == null) {
                fallback = link;
            }
        }
        return fallback;
    }

    void rerun() {
        if (currentRun != null) {
            ops.runTest(currentRun.tool(), currentRun.workingDir(), currentRun.taskArgs(), currentRun.toggleArgs());
        }
    }

    void rerunFailed() {
        if (currentRun == null) {
            return;
        }
        List<String> failed = currentRun.failedTestFilters();
        if (failed.isEmpty()) {
            host.setStatus(tr("status.testrunner.rerunFailedUnsupported"));
            ops.runTest(currentRun.tool(), currentRun.workingDir(), currentRun.taskArgs(), currentRun.toggleArgs());
            return;
        }
        ops.runTest(currentRun.tool(), currentRun.workingDir(), failed, currentRun.toggleArgs());
    }

    void stop() {
        if (currentTool != null) {
            ops.stopTest(currentTool);
        }
    }

    void shutdown() {
        stopPolling();
        stopElapsedTimer();
        poller.shutdownNow();
    }
}
