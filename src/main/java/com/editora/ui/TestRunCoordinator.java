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
import com.editora.test.JavaTestScanner;
import com.editora.test.JvmReportDirs;
import com.editora.test.ParsedSuite;
import com.editora.test.TapParser;
import com.editora.test.TestDebug;
import com.editora.test.TestNode;
import com.editora.test.TestNodeKind;
import com.editora.test.TestPlan;
import com.editora.test.TestResultParser;
import com.editora.test.TestResultParsers;
import com.editora.test.TestRun;
import com.editora.test.TestRunRecognizer;
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

        /** Whether "Debug Test" can work (Java debugging configured + adapter available). */
        boolean debugAvailable();

        /** Attach the debugger to a test JVM suspended on {@code port} ({@code className} anchors the source). */
        void attachDebugger(String className, String host, int port);
    }

    /** JVM report poll interval — reports appear per class as each finishes; ~750 ms is responsive + cheap. */
    private static final long POLL_MS = 750;
    /** Bounded depth for the reactor/multi-project report-dir walk. */
    private static final int WALK_DEPTH = 5;
    /** Cap the npm sniff buffer so a non-TAP runner can't grow memory before we give up on structure. */
    private static final int NPM_SNIFF_LIMIT = 200;
    /** Bounds for the pre-seed project test-source scan (skip seeding beyond these — fall back to pop-in). */
    private static final int SEED_WALK_DEPTH = 12;

    private static final int MAX_SEED_FILES = 4000;
    private static final int MAX_SEED_TESTS = 20_000;
    private static final long MAX_SEED_FILE_BYTES = 512 * 1024;

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
    // seenMtimes/baselineMtimes are touched ONLY on the poller thread (baseline task → ticks → final sweep).
    private final Map<Path, Long> seenMtimes = new HashMap<>();
    private final Map<Path, Long> baselineMtimes = new HashMap<>(); // pre-run report mtimes (skip stale leftovers)
    private int runGeneration; // bumped per run (FX thread); guards a late poll tick from merging into a newer run
    private boolean seeded; // this run pre-seeded pending nodes → prune the never-reported ones at finish
    private TestNode followTarget; // the newest result — kept scrolled into view while the run is live
    private String awaitingAttachFor; // class whose suspended test JVM we should attach to (Debug Test)
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
        panel.setOnRerunOne(this::rerunOne);
        panel.setOnDebugOne(this::debugOne);
        panel.setDebugAvailable(ops::debugAvailable);
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
        seeded = false;
        followTarget = null;
        awaitingAttachFor = null;

        panel.startRun(String.join(" ", taskArgs));
        ops.setTestResultsAvailable(true);
        ops.openTestResults();
        startElapsedTimer();
        int gen = ++runGeneration;
        if (fileBased) {
            Path dir = workingDir;
            BuildTool t = tool;
            // Snapshot the pre-run report mtimes on the poller thread BEFORE the first tick, so a leftover
            // TEST-*.xml from a prior run is never parsed as this run's result (a full `mvn test` recreates
            // the dir, but `-Dtest=Foo` leaves every other class's stale report in place).
            poller.execute(() -> baselineReports(t, dir));
            startPolling(gen, t, dir);
            // IntelliJ-style: pre-seed the whole expected test list greyed-out (RUNNING) for an unfiltered run,
            // so tests show as pending and flip to green/red instead of popping in per finished class. A
            // filtered run (-Dtest=/--tests) only touches its target, so seeding everything would mislead.
            if (!TestRunRecognizer.isFilteredRun(tool, taskArgs)) {
                seeded = true;
                poller.execute(() -> seedFromSources(gen, dir));
            }
        }
        return true;
    }

    @Override
    public void onTestOutput(String line, boolean stderr) {
        if (currentRun == null) {
            return;
        }
        // "Debug Test": the suspended test JVM prints the JDWP banner — attach as soon as we see it. Checked
        // before the file-based return, because for Maven/Gradle the console is the ONLY place it appears.
        if (awaitingAttachFor != null) {
            int port = TestDebug.jdwpPort(line);
            if (port > 0) {
                String cls = awaitingAttachFor;
                awaitingAttachFor = null;
                ops.attachDebugger(cls, "localhost", port);
            }
        }
        if (fileBased) {
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
        stopElapsedTimer();
        TestRun run = currentRun;
        int gen = runGeneration;
        if (fileBased) {
            BuildTool tool = currentTool;
            Path dir = run.workingDir();
            // The final sweep walks + DOM-parses the reports — file I/O, never on the FX thread. The
            // single-threaded poller serializes this after any in-flight tick; its mergeAll runLater is posted
            // before completeRun, so the last class lands before the run is marked finished.
            poller.execute(() -> {
                sweepReports(gen, tool, dir, true); // full: catch the last class + anything a tick missed
                Platform.runLater(() -> completeRun(run, gen, code));
            });
        } else {
            mergeAll(parser.onExit(code));
            if (npm && !tapDecided) {
                // No structured (TAP) output — surface an honest banner rather than an empty tree.
                TestTreeBuilder.merge(run.root(), new ParsedSuite(tr("testrunner.tap.unavailable"), List.of()));
            }
            completeRun(run, gen, code);
        }
    }

    /** FX thread. Finalizes the run unless a newer run has already superseded it. */
    private void completeRun(TestRun run, int gen, int code) {
        if (gen != runGeneration || run != currentRun) {
            return;
        }
        if (seeded) {
            run.root().pruneRunning(); // drop pre-seeded placeholders that never got a result (parameterized/skipped)
        }
        run.finish(code, System.currentTimeMillis());
        panel.finishRun(run, code);
    }

    private void mergeAll(List<ParsedSuite> suites) {
        if (suites.isEmpty() || currentRun == null) {
            return;
        }
        for (ParsedSuite suite : suites) {
            TestTreeBuilder.merge(currentRun.root(), suite);
            // Remember the run's frontier — the newest result — so the panel can keep it scrolled into view
            // ("track running test"). Only result merges move it; the pending pre-seed deliberately doesn't,
            // or the view would jump to the bottom of the whole list before anything has run.
            if (!suite.tests().isEmpty()) {
                TestNode suiteNode = currentRun.root().childById(suite.suiteName());
                if (suiteNode != null) {
                    TestNode leaf = suiteNode.childById(
                            suite.tests().get(suite.tests().size() - 1).id());
                    if (leaf != null) {
                        followTarget = leaf;
                    }
                }
            }
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
                panel.update(currentRun, followTarget);
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

    private void startPolling(int gen, BuildTool tool, Path root) {
        pollTask = poller.scheduleWithFixedDelay(
                () -> sweepReports(gen, tool, root, false), POLL_MS, POLL_MS, TimeUnit.MILLISECONDS);
    }

    /** Poller thread: snapshot the pre-run report mtimes (and reset the seen/baseline maps for the new run). */
    private void baselineReports(BuildTool tool, Path root) {
        seenMtimes.clear();
        baselineMtimes.clear();
        for (Path file : reportFiles(tool, root)) {
            try {
                baselineMtimes.put(file, Files.getLastModifiedTime(file).toMillis());
            } catch (Exception ignored) {
                // unreadable — treat as absent, so it's parsed if the run (re)writes it
            }
        }
    }

    private void stopPolling() {
        if (pollTask != null) {
            pollTask.cancel(false);
            pollTask = null;
        }
    }

    /**
     * Poller thread: walk the project's Java test sources, scan each ({@link JavaTestScanner}) for JUnit tests,
     * and merge a greyed-out pending list ({@link TestPlan#seed}) into the tree on the FX thread — so the full
     * expected test set shows up front and each entry flips to green/red as results arrive. Bounded + capped;
     * skips seeding for a pathologically large project (falls back to per-class pop-in).
     */
    private void seedFromSources(int gen, Path root) {
        List<JavaTestScanner.TestTarget> targets = new ArrayList<>();
        int files = 0;
        try (Stream<Path> walk = Files.walk(root, SEED_WALK_DEPTH)) {
            List<Path> javaTestFiles = walk.filter(Files::isRegularFile)
                    .filter(TestRunCoordinator::isTestSourceFile)
                    .limit(MAX_SEED_FILES + 1L)
                    .toList();
            if (javaTestFiles.size() > MAX_SEED_FILES) {
                return; // too many files — don't stall the run scanning them; per-class pop-in still works
            }
            for (Path f : javaTestFiles) {
                files++;
                if (Files.size(f) > MAX_SEED_FILE_BYTES) {
                    continue;
                }
                targets.addAll(JavaTestScanner.scan(Files.readString(f)));
                if (targets.size() > MAX_SEED_TESTS) {
                    return;
                }
            }
        } catch (Exception e) {
            return; // an FS error during the pre-scan must never break the run; results still stream in
        }
        List<ParsedSuite> seed = TestPlan.seed(targets);
        if (seed.isEmpty()) {
            return;
        }
        Platform.runLater(() -> {
            if (gen == runGeneration && currentRun != null) {
                for (ParsedSuite s : seed) {
                    TestTreeBuilder.seed(currentRun.root(), s); // create-missing-only: never downgrades a result
                }
                requestRefresh();
            }
        });
    }

    /** A Java file under a {@code test} source dir (heuristic to skip {@code src/main} + speed the scan). */
    private static boolean isTestSourceFile(Path f) {
        String name = f.getFileName().toString();
        if (!name.endsWith(".java")) {
            return false;
        }
        for (Path seg : f) {
            String s = seg.toString();
            if (s.equals("test") || s.equals("tests")) {
                return true; // src/test/java, or a Gradle test source set
            }
        }
        return false;
    }

    /**
     * Scans the report dirs for {@code TEST-*.xml} files, parses those whose mtime advanced (all of them when
     * {@code full}), and merges the results on the FX thread. Runs on the poll thread (or the FX thread for the
     * final sweep — parsing is bounded and off the hot path either way).
     */
    private void sweepReports(int gen, BuildTool tool, Path root, boolean full) {
        try {
            List<ParsedSuite> parsed = new ArrayList<>();
            for (Path file : reportFiles(tool, root)) {
                long mtime = Files.getLastModifiedTime(file).toMillis();
                Long base = baselineMtimes.get(file);
                if (base != null && base == mtime) {
                    continue; // an untouched leftover from a previous run — never this run's result
                }
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
                Platform.runLater(() -> {
                    if (gen == runGeneration) { // drop a late tick from a superseded run
                        mergeAll(parsed);
                    }
                });
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

    /** Context menu: rerun just the clicked test (or class, for a suite row). */
    private void rerunOne(TestNode node) {
        runOne(node, false);
    }

    /** Context menu: rerun the clicked test/class with the JVM suspended, then attach the debugger. */
    private void debugOne(TestNode node) {
        runOne(node, true);
    }

    private void runOne(TestNode node, boolean debug) {
        if (node == null || currentRun == null || node.className() == null) {
            return;
        }
        BuildTool tool = currentRun.tool();
        String method = node.kind() == TestNodeKind.TEST ? node.methodName() : null;
        List<String> args = debug
                ? TestDebug.debugTaskArgs(tool, node.className(), method)
                : TestRunRecognizer.singleTestTask(tool, node.className(), method);
        if (args.isEmpty()) {
            host.setStatus(tr(debug ? "status.testrunner.debugUnsupported" : "status.testrunner.rerunUnsupported"));
            return;
        }
        Path dir = currentRun.workingDir();
        List<String> toggles = currentRun.toggleArgs();
        if (debug) {
            host.setStatus(tr("status.testrunner.debugWaiting"));
        }
        ops.runTest(tool, dir, args, toggles);
        if (debug) {
            // Set AFTER the launch: ops.runTest re-enters onTestRunStart synchronously, which resets the
            // per-run state (including this flag) — arming it beforehand would be wiped before the JVM ever
            // printed its JDWP banner. Surefire/Gradle fork the test JVM suspended; onTestOutput sees the
            // banner and attaches.
            awaitingAttachFor = node.className();
        }
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
