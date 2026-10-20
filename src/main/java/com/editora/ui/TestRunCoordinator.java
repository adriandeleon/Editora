package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
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
import com.editora.search.GitignoreFilter;
import com.editora.search.ProjectWalk;
import com.editora.test.GradleTaskOutcomes;
import com.editora.test.JavaTestScanner;
import com.editora.test.JdwpAttachRetry;
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
import com.editora.test.TestSourceLocator;
import com.editora.test.TestStatus;
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

        /** Why "Debug Test" cannot work — the missing piece, as Debug says it (review A7). */
        default String debugUnavailableMessage() {
            return tr("status.debug.unavailable");
        }

        /** Attach the debugger to a test JVM suspended on {@code port} ({@code className} anchors the source). */
        void attachDebugger(String className, String host, int port);

        /**
         * A Debug Test run is waiting for its JVM to attach: Debug ▸ Stop runs {@code cancel} (which ends the
         * build) until a session starts or the run ends ({@code null} clears it).
         */
        default void setPendingDebugAttach(Runnable cancel) {}

        /** Whether a debug session is live or starting (the Maven attach loop tells a closed port by it). */
        default boolean debugSessionActive() {
            return false;
        }
    }

    /**
     * A test run Editora starts itself rather than through a build tool — pytest, or Python's unittest — whose
     * output streams in the Run console and whose results it feeds in here. It answers the panel's actions.
     */
    interface ExternalRun {
        void rerun();

        /** Reruns just these failed tests. */
        void rerunFailed(List<TestNode> failed);

        /** Runs one test, or a suite row's tests. */
        void runOne(TestNode node);

        void stop();

        /** Opens the test's source. */
        void jump(TestNode node);

        /** Debugs one test, or a suite row's tests; false when this run's tests cannot be debugged. */
        default boolean debug(TestNode node) {
            return false;
        }

        /** Whether its rows offer Debug (the Python debugger is set up for where they run). */
        default boolean canDebug() {
            return false;
        }
    }

    /** JVM report poll interval — reports appear per class as each finishes; ~750 ms is responsive + cheap. */
    private static final long POLL_MS = 750;

    /** Report directories on a remote host are listed over the network: less often. */
    private static final long REMOTE_POLL_MS = 2000;

    private static long poll(Path root) {
        return com.editora.vfs.Vfs.isRemote(root) ? REMOTE_POLL_MS : POLL_MS;
    }
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
    /** The Test Results panel, built on its first use (normally a test run or the window's first open). */
    private TestRunnerPanel panel;
    /** The output font to apply when the panel is built (set by every settings apply, panel or not). */
    private String outputFontFamily;

    private int outputFontSize;
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
    // Bumped per run (FX thread); guards a late poll tick (reports, or the Maven attach loop) from a newer run.
    private volatile int runGeneration;
    private boolean seeded; // this run pre-seeded pending nodes → prune the never-reported ones at finish
    private TestNode followTarget; // the newest result — kept scrolled into view while the run is live
    private String awaitingAttachFor; // class whose suspended test JVM we should attach to (Debug Test)
    /** The JDWP ports of this run's JVMs already attached to (Debug Test). */
    private final java.util.Set<Integer> attachedPorts = new java.util.HashSet<>();
    /**
     * Debug Test on Maven: the loopback port Editora chose for the fork and polls until it listens (review
     * D1 — Surefire 3 withholds the JDWP banner until the suspended JVM has run); 0 when the banner is the way
     * (Gradle). The poller thread only probes; what follows is decided on the FX thread ({@link #onAttachProbe}),
     * which owns the rest. The deadline is reset by Surefire's own {@code T E S T S} banner, so a long compile
     * before the tests does not count against it.
     */
    private int mavenDebugPort;

    private ScheduledFuture<?> attachTask;
    private boolean attachAsked;
    private boolean everAttached;
    private long attachDeadline;
    /** Test seams: how the Maven debug port is chosen, and how long the first attach may take. */
    java.util.function.IntSupplier debugPortPicker = () -> {
        try {
            return JdwpAttachRetry.freeLoopbackPort();
        } catch (java.io.IOException e) {
            return -1;
        }
    };

    long attachDeadlineMs = JdwpAttachRetry.DEADLINE_MS;

    private Timeline elapsedTimer;
    private boolean refreshPending;

    /** Gradle's own account of this run: whether it printed task lines, and the tasks it reused. */
    private boolean sawGradleTaskLines;

    /** Maven said a {@code -Dtest=} filter matched nothing (and failed the build for it, without Editora's flags). */
    private boolean sawNoTestsMatching;

    /** The report folders of this run's module beyond the standard ones (a pom's {@code reportsDirectory}, review D17). */
    private volatile List<Path> configuredReportDirs = List.of();

    private final Set<String> reusedGradleTasks = new java.util.HashSet<>();

    // npm TAP sniffing
    private boolean npm;
    private boolean tapDecided;
    /** This npm run's results came from node:test (spec or TAP with subtests): it can rerun tests by name. */
    private boolean nodeTestRun;
    /** The package manager this npm run launched ({@code npm}, {@code pnpm} …): how test names are passed on. */
    private String npmManager = "npm";

    private final List<String> npmSniff = new ArrayList<>();

    TestRunCoordinator(CoordinatorHost host, Ops ops) {
        this.host = host;
        this.ops = ops;
    }

    TestRunnerPanel panel() {
        if (panel == null) {
            panel = new TestRunnerPanel();
            panel.setOnRerun(this::rerun);
            panel.setOnRerunFailed(this::rerunFailed);
            panel.setOnStop(this::stop);
            panel.setOnLink(ops::openLink);
            panel.setOnActivate(this::activate);
            panel.setOnRerunOne(this::rerunOne);
            panel.setOnDebugOne(this::debugOne);
            // A row's Debug item: an external (Python) run's own debugger, else the build tool's (Java).
            panel.setDebugAvailable(() -> external != null ? external.canDebug() : ops.debugAvailable());
            if (outputFontFamily != null) {
                panel.setOutputFont(outputFontFamily, outputFontSize);
            }
        }
        return panel;
    }

    /** Matches the output font to the editor font; remembered for a panel not built yet. */
    void setOutputFont(String family, int size) {
        outputFontFamily = family;
        outputFontSize = size;
        if (panel != null) {
            panel.setOutputFont(family, size);
        }
    }

    /** The external run the tree shows, or null when it shows a build tool's (see {@link ExternalRun}). */
    private ExternalRun external;

    /**
     * Starts showing an external run ({@link ExternalRun}): a fresh tree, the Test Results window, the timer.
     * False when the Test Runner is off, or a build tool's test run is still going (it keeps the tree).
     */
    boolean startExternalRun(String label, Path workingDir, ExternalRun driver) {
        if (!isEnabled() || (currentRun != null && currentRun.isRunning() && currentTool != null)) {
            return false;
        }
        stopPolling();
        currentTool = null;
        external = driver;
        currentRun = new TestRun(null, workingDir, List.of(label), List.of(), System.currentTimeMillis());
        parser = null;
        fileBased = false;
        npm = false;
        tapDecided = false;
        npmSniff.clear();
        seeded = false;
        followTarget = null;
        awaitingAttachFor = null;
        panel().startRun(label);
        ops.setTestResultsAvailable(true);
        ops.openTestResults();
        startElapsedTimer();
        ++runGeneration;
        return true;
    }

    /** Results of the external run, as they arrive (FX thread). */
    void externalResults(List<ParsedSuite> suites) {
        if (external != null && currentRun != null && currentRun.isRunning()) {
            mergeAll(suites);
        }
    }

    /** The external run ended with {@code code} ({@code -1} when it was stopped or could not start). */
    void finishExternalRun(int code) {
        if (external == null || currentRun == null || !currentRun.isRunning()) {
            return;
        }
        stopElapsedTimer();
        completeRun(currentRun, runGeneration, code);
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
        // There is one run state — one tree, one parser — and the hook's callbacks carry no run identity.
        // Build tools run concurrently by design, so a second tool's test run started while this one is in
        // flight would take over that state: the first tool's remaining output would go to the wrong
        // parser and its exit would finish the wrong run. Decline; it runs in its own console instead.
        if (currentRun != null && currentRun.isRunning() && currentTool != tool) {
            return false;
        }
        currentTool = tool;
        external = null;
        stoppedByUser = false;
        currentRun = new TestRun(tool, workingDir, taskArgs, toggleArgs, System.currentTimeMillis());
        parser = TestResultParsers.forTool(tool);
        fileBased = TestResultParsers.isFileBased(tool);
        npm = tool == BuildTool.NPM;
        tapDecided = false;
        nodeTestRun = false;
        npmManager = resolvedArgv == null || resolvedArgv.isEmpty() ? "npm" : resolvedArgv.get(0);
        npmSniff.clear();
        seeded = false;
        followTarget = null;
        awaitingAttachFor = null;
        sawGradleTaskLines = false;
        sawNoTestsMatching = false;
        configuredReportDirs = List.of();
        reusedGradleTasks.clear();

        panel().startRun(String.join(" ", taskArgs));
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
            poller.execute(() -> {
                configuredReportDirs = JvmReportDirs.reportDirs(
                        t, dir, t == BuildTool.MAVEN ? com.editora.maven.PomBuildConfig.read(dir) : null);
                baselineReports(t, dir);
            });
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
        // Maven's fork is on a port chosen up front, which the attach loop owns: Surefire 3 surfaces that
        // banner only after the attached JVM ran, when the loop may already be waiting for the next fork, so
        // acting on it would attach a port that is gone (queued as "another JVM is waiting").
        if (awaitingAttachFor != null) {
            if (mavenDebugPort > 0 && TestDebug.isSurefireTestsBanner(line)) {
                attachDeadline = System.currentTimeMillis() + attachDeadlineMs; // the tests start now
            }
            int port = TestDebug.jdwpPort(line);
            if (port > 0 && port != mavenDebugPort) {
                // Kept for the whole run: every forked JVM prints its own banner and waits (review D11). A port
                // already attached is the same JVM's banner printed again.
                if (attachedPorts.add(port)) {
                    ops.attachDebugger(awaitingAttachFor, "localhost", port);
                }
            }
        }
        if (fileBased) {
            if (currentTool == BuildTool.MAVEN && TestRunRecognizer.isNoTestsMatchingLine(line)) {
                sawNoTestsMatching = true; // a hand-typed -Dtest= without the reactor flags (review D9)
            }
            if (currentTool == BuildTool.GRADLE) {
                sawGradleTaskLines |= GradleTaskOutcomes.isTaskLine(line);
                String reused = GradleTaskOutcomes.reusedTask(line);
                if (reused != null) {
                    reusedGradleTasks.add(reused);
                }
            }
            return; // JVM: results come from the report files, not the console
        }
        if (npm && !tapDecided) {
            npmSniff.add(line);
            boolean tap = TapParser.looksLikeTap(npmSniff);
            if (!tap && com.editora.test.NodeSpecParser.looksLikeSpec(npmSniff)) {
                parser = new com.editora.test.NodeSpecParser(); // node --test's default reporter on a pipe
                nodeTestRun = true;
            }
            if (tap || nodeTestRun) {
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

    /** The run the user stopped ends as "Stopped" (Maven review B5), not as a failed run with SIGTERM's code. */
    @Override
    public void onTestStopped(int code) {
        stoppedByUser = true;
        finish(code);
    }

    /** Set by {@link #onTestStopped} for the run in flight; {@link #completeRun} reads it into the header. */
    private boolean stoppedByUser;

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
        stopAttachLoop();
        if (awaitingAttachFor != null) {
            ops.setPendingDebugAttach(null); // the run is over: nothing left for Debug ▸ Stop to end
        }
        TestRun run = currentRun;
        int gen = runGeneration;
        if (fileBased) {
            BuildTool tool = currentTool;
            Path dir = run.workingDir();
            // Which leftover reports may stand for this run if it rewrote nothing: those of the test tasks
            // Gradle itself reported as up to date. Sweeping every report under the project showed another
            // module's week-old failure for a successful `:app:test`. Under -q there are no task lines to go
            // by, and every report is taken as before.
            Set<String> reusedTasks = Set.copyOf(reusedGradleTasks);
            boolean sawTaskLines = sawGradleTaskLines;
            java.util.function.Predicate<Path> leftovers =
                    sawTaskLines ? file -> GradleTaskOutcomes.reportBelongsTo(dir, file, reusedTasks) : file -> true;
            // The final sweep walks + DOM-parses the reports — file I/O, never on the FX thread. The
            // single-threaded poller serializes this after any in-flight tick; its mergeAll runLater is posted
            // before completeRun, so the last class lands before the run is marked finished.
            poller.execute(() -> {
                sweepReports(gen, tool, dir, true, null); // full: catch the last class + anything a tick missed
                // An up-to-date Gradle test task rewrote nothing, so every report equals its baseline and
                // the sweep above found no results: show the ones on disk rather than an empty tree.
                boolean reused = TestRunRecognizer.showsExistingReports(tool, code, !seenMtimes.isEmpty())
                        && (!sawTaskLines || !reusedTasks.isEmpty());
                if (reused) {
                    sweepReports(gen, tool, dir, true, leftovers);
                }
                Platform.runLater(() -> {
                    completeRun(run, gen, code);
                    if (reused && gen == runGeneration) {
                        host.setStatus(tr("status.testrunner.upToDate"));
                    }
                });
            });
        } else {
            mergeAll(parser.onExit(code));
            if (npm && !tapDecided) {
                // No structured (TAP) output — surface an honest banner rather than an empty tree.
                String banner = tr("testrunner.tap.unavailable");
                TestTreeBuilder.merge(run.root(), new ParsedSuite(banner, List.of()));
                run.root().childById(banner).setStatus(TestStatus.SKIPPED); // a message row, not a running suite
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
        panel().finishRun(run, code, stoppedByUser);
        // A Maven filter that matches nothing is not an error (the flags a reactor needs see to that), so
        // the build is green and the tree empty. Say so: "0 of 0 tests passed" reads as a pass. A hand-typed
        // filter lacks those flags: Surefire then fails the build saying so, which reads like a broken build.
        if ((code == 0 || sawNoTestsMatching)
                && run.tool() == BuildTool.MAVEN
                && TestRunRecognizer.isFilteredRun(run.tool(), run.taskArgs())
                && run.counts().total() == 0) {
            host.setStatus(tr("status.testrunner.noMatch"));
        }
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
                panel().update(currentRun, followTarget);
            }
        });
    }

    // --- elapsed timer -----------------------------------------------------------------------------

    private void startElapsedTimer() {
        stopElapsedTimer();
        elapsedTimer = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            if (currentRun != null && currentRun.isRunning()) {
                panel().setElapsed(currentRun.elapsedMillis(System.currentTimeMillis()));
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
                () -> sweepReports(gen, tool, root, false, null), poll(root), poll(root), TimeUnit.MILLISECONDS);
    }

    /** Poller thread: snapshot the pre-run report mtimes (and reset the seen/baseline maps for the new run). */
    private void baselineReports(BuildTool tool, Path root) {
        seenMtimes.clear();
        baselineMtimes.clear();
        for (Path file : reportFilesOfRun(tool, root)) {
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
        try {
            List<Path> javaTestFiles = testSourceFiles(root);
            if (javaTestFiles == null) {
                return; // too many files — don't stall the run scanning them; per-class pop-in still works
            }
            for (Path f : javaTestFiles) {
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

    /**
     * The project's Java test sources, or {@code null} when there are more than {@link #MAX_SEED_FILES}.
     * Through the shared pruned walk: build output, dependency trees, dot-directories and {@code .gitignore}d
     * paths are never entered, where the old {@code Files.walk} enumerated all of {@code target/} and
     * {@code node_modules/} only to throw the entries away.
     */
    static List<Path> testSourceFiles(Path root) {
        List<Path> files = new ArrayList<>();
        ProjectWalk.Outcome walked = ProjectWalk.walk(
                root,
                new ProjectWalk.Options(SEED_WALK_DEPTH, MAX_SEED_FILES, GitignoreFilter.load(root)),
                new ProjectWalk.Visitor() {
                    @Override
                    public boolean enter(Path dir, String rel) {
                        return !ProjectWalk.isBuildOutputDir(rel);
                    }

                    @Override
                    public ProjectWalk.Verdict file(Path file, String rel, BasicFileAttributes attrs) {
                        if (!attrs.isRegularFile() || !TestSourceLocator.isTestSourcePath(rel)) {
                            return ProjectWalk.Verdict.SKIP;
                        }
                        files.add(file);
                        return ProjectWalk.Verdict.ACCEPT;
                    }
                });
        return walked.capped() ? null : files;
    }

    /**
     * Scans the report dirs for {@code TEST-*.xml} files, parses those whose mtime advanced (all of them when
     * {@code full}), and merges the results on the FX thread. Runs on the poll thread (or the FX thread for the
     * final sweep — parsing is bounded and off the hot path either way). {@code leftovers}, when not null,
     * also admits the reports it accepts although they are untouched since before the run (see
     * {@link TestRunRecognizer#showsExistingReports}).
     */
    private void sweepReports(
            int gen, BuildTool tool, Path root, boolean full, java.util.function.Predicate<Path> leftovers) {
        try {
            List<ParsedSuite> parsed = new ArrayList<>();
            for (Path file : reportFilesOfRun(tool, root)) {
                long mtime = Files.getLastModifiedTime(file).toMillis();
                Long base = baselineMtimes.get(file);
                if (base != null && base == mtime && (leftovers == null || !leftovers.test(file))) {
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

    /** Directory names that never hold a module, so the report-dir walk does not enter them. */
    private static final Set<String> NOT_A_MODULE = Set.of("node_modules");

    /**
     * The {@code TEST-*.xml} files of every module's report directory under {@code root}.
     *
     * <p>Runs every {@link #POLL_MS} for the length of a test run, so it must not enumerate the project. The
     * report directories live under {@code target/} and {@code build/} — exactly what {@code .gitignore}
     * excludes — so the gitignore filter cannot be used here; what is pruned instead is everything that
     * cannot contain one: dot-directories ({@code .git} alone is thousands of entries), dependency trees,
     * anything below the depth limit, and a report directory's own contents.
     */
    private List<Path> reportFilesOfRun(BuildTool tool, Path root) {
        return reportFiles(tool, root, configuredReportDirs);
    }

    /** {@link #reportFiles(BuildTool, Path, List)} with the standard folders only. */
    static List<Path> reportFiles(BuildTool tool, Path root) {
        return reportFiles(tool, root, List.of());
    }

    /** As {@link #reportFiles(BuildTool, Path)} with {@code configured} (a pom's own folders) looked in too. */
    static List<Path> reportFiles(BuildTool tool, Path root, List<Path> configured) {
        Set<Path> dirs = new LinkedHashSet<>(JvmReportDirs.reportDirs(tool, root));
        dirs.addAll(configured);
        ProjectWalk.walk(
                root,
                new ProjectWalk.Options(WALK_DEPTH + 1, Integer.MAX_VALUE, GitignoreFilter.NONE),
                new ProjectWalk.Visitor() {
                    @Override
                    public boolean enter(Path dir, String rel) {
                        String name = rel.substring(rel.lastIndexOf('/') + 1);
                        if (JvmReportDirs.isReportDirName(tool, name)) {
                            dirs.add(dir);
                            return false; // its files are listed below; nothing nested is another module
                        }
                        long depth = rel.chars().filter(c -> c == '/').count() + 1;
                        return depth < WALK_DEPTH && !NOT_A_MODULE.contains(name);
                    }

                    @Override
                    public ProjectWalk.Verdict file(Path file, String rel, BasicFileAttributes attrs) {
                        return ProjectWalk.Verdict.SKIP; // only directories matter to this walk
                    }
                });
        // A set: Gradle's standard dir (build/test-results/test) lies inside the walked one
        // (build/test-results), and a file listed twice was parsed and merged twice on every sweep.
        Set<Path> files = new LinkedHashSet<>();
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
        return new ArrayList<>(files);
    }

    // --- panel actions -----------------------------------------------------------------------------

    /**
     * Double-click on a result. One route for passing and failing tests alike: the navigation coordinator
     * prefers the failure's own stack frame (an exact line) and falls back to the name-based lookup when the
     * frame does not resolve — which a failed test never got while its frame was opened here directly.
     */
    private void activate(TestNode node) {
        if (node != null && external != null) {
            external.jump(node);
        } else if (node != null) {
            ops.jumpToTest(node, currentTool);
        }
    }

    /** The first stack frame that resolves to a location — preferring one in the test's own source file. */
    static StackTraceLinks.Link firstFrame(String stackTrace, String preferredFile) {
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

    /** Editor action: debug one scanned JUnit class/method without requiring a previous test-results row. */
    void debugSingleTest(
            BuildTool tool, JavaTestScanner.TestTarget target, java.util.function.Consumer<List<String>> launch) {
        if (!ops.debugAvailable()) {
            host.setStatus(ops.debugUnavailableMessage());
            return;
        }
        com.editora.editor.EditorBuffer active = host.activeBuffer();
        int port = mavenDebugPort(tool);
        List<String> args = TestDebug.debugTaskArgs(
                tool,
                target.className(),
                target.methodName(),
                gradleDebugInit(tool, active == null ? null : active.getPath()),
                port);
        if (args.isEmpty()) {
            host.setStatus(tr("status.testrunner.debugUnsupported"));
            return;
        }
        int before = runGeneration;
        launch.accept(args);
        if (runGeneration != before) { // the run started (not refused as busy / not detected)
            armDebugger(target.className(), port);
        }
    }

    /** The port a Maven test debug forks on (chosen now, polled until it listens); 0 for the banner tools. */
    private int mavenDebugPort(BuildTool tool) {
        return tool == BuildTool.MAVEN ? Math.max(0, debugPortPicker.getAsInt()) : 0;
    }

    private void runOne(TestNode node, boolean debug) {
        if (node == null || currentRun == null || node.className() == null) {
            return;
        }
        if (external != null) {
            if (debug) {
                if (!external.debug(node)) {
                    host.setStatus(tr("status.testrunner.debugUnsupported"));
                }
            } else {
                external.runOne(node);
            }
            return;
        }
        BuildTool tool = currentRun.tool();
        String method = node.kind() == TestNodeKind.TEST ? node.methodName() : null;
        if (tool == BuildTool.NPM && !debug && nodeTestRun()) {
            // node:test: the same script, filtered by the test's exact name (a suite row reruns them all).
            ops.runTest(
                    tool,
                    currentRun.workingDir(),
                    com.editora.test.NodeTestNames.npmTask(
                            currentRun.taskArgs(), method == null ? List.of() : List.of(method), npmManager),
                    currentRun.toggleArgs());
            return;
        }
        int port = debug ? mavenDebugPort(tool) : 0;
        List<String> args = debug
                ? TestDebug.debugTaskArgs(
                        tool, node.className(), method, gradleDebugInit(tool, currentRun.workingDir()), port)
                : TestRunRecognizer.singleTestTask(tool, node.className(), method);
        if (args.isEmpty()) {
            host.setStatus(tr(debug ? "status.testrunner.debugUnsupported" : "status.testrunner.rerunUnsupported"));
            return;
        }
        Path dir = currentRun.workingDir();
        List<String> toggles = currentRun.toggleArgs();
        if (debug) {
            startDebugRun(tool, dir, args, toggles, node.className(), port);
        } else {
            ops.runTest(tool, dir, args, toggles);
        }
    }

    /**
     * The init script that debugs a Gradle test run's JVMs on ports the system picks (each fork its own, all
     * attached in turn), or null for {@code --debug-jvm}'s 5005: not Gradle, or a project whose build runs on
     * another machine, where a file written here does not exist.
     */
    private static Path gradleDebugInit(BuildTool tool, Path where) {
        if (tool != BuildTool.GRADLE || where == null || !com.editora.process.ExecTargets.runsHere(where)) {
            return null;
        }
        return com.editora.build.GradleDebugInit.write(List.of(TestDebug.GRADLE_TEST_TASK));
    }

    private void startDebugRun(
            BuildTool tool, Path dir, List<String> args, List<String> toggles, String className, int port) {
        int before = runGeneration;
        ops.runTest(tool, dir, args, toggles);
        if (runGeneration != before) {
            armDebugger(className, port);
        }
    }

    private void armDebugger(String className, int port) {
        // Set AFTER the launch: ops.runTest re-enters onTestRunStart synchronously, which resets the
        // per-run state (including this flag) — arming it beforehand would be wiped before the JVM ever
        // printed its JDWP banner. Surefire/Gradle fork the test JVM suspended; onTestOutput sees the
        // banner and attaches — or, for Maven, the port chosen up front is polled until the fork listens.
        awaitingAttachFor = className;
        attachedPorts.clear();
        mavenDebugPort = port;
        // The waiting status is set after the launch too: the build's own "running …" status would overwrite
        // it at once (review D14), and the Test Results header says it instead of the whole argv.
        host.setStatus(tr("status.testrunner.debugWaiting"));
        panel().setRunNote(port > 0 ? tr("testrunner.debugWaiting", port) : tr("testrunner.debugWaitingBanner"));
        ops.setPendingDebugAttach(this::stop); // Debug ▸ Stop while waiting ends the build, as it does a session's
        if (port > 0) {
            startAttachLoop(runGeneration, port);
        }
    }

    /** Polls the chosen port every {@link JdwpAttachRetry#INTERVAL_MS} until the Maven fork listens on it. */
    private void startAttachLoop(int gen, int port) {
        stopAttachLoop();
        attachAsked = false;
        everAttached = false;
        attachDeadline = System.currentTimeMillis() + attachDeadlineMs;
        attachTask = poller.scheduleWithFixedDelay(
                () -> attachTick(gen, port),
                JdwpAttachRetry.INTERVAL_MS,
                JdwpAttachRetry.INTERVAL_MS,
                TimeUnit.MILLISECONDS);
    }

    private void stopAttachLoop() {
        if (attachTask != null) {
            attachTask.cancel(false);
            attachTask = null;
        }
    }

    /** Poller thread: one probe of the port; what follows is decided on the FX thread. */
    private void attachTick(int gen, int port) {
        if (gen != runGeneration) {
            return; // a newer run: its own loop (if any) is the live one
        }
        JdwpAttachRetry.Probe probe = JdwpAttachRetry.probe("127.0.0.1", port, JdwpAttachRetry.PROBE_TIMEOUT_MS);
        Platform.runLater(() -> onAttachProbe(gen, port, probe));
    }

    /** FX thread: what a probe of the chosen port means for this run. */
    private void onAttachProbe(int gen, int port, JdwpAttachRetry.Probe probe) {
        if (gen != runGeneration || attachTask == null) {
            return;
        }
        JdwpAttachRetry.Decision decision = JdwpAttachRetry.decide(
                probe, attachAsked, ops.debugSessionActive(), everAttached, System.currentTimeMillis(), attachDeadline);
        switch (decision) {
            case ATTACH -> {
                attachAsked = true;
                everAttached = true;
                if (awaitingAttachFor != null && attachedPorts.add(port)) {
                    ops.attachDebugger(awaitingAttachFor, "localhost", port);
                }
            }
            case PORT_CLOSED -> {
                // The attached JVM ran and exited (or died): a further fork of the same run (forkCount>1 with
                // reuseForks=false, reruns) binds the same port next and waits suspended — attach it in turn.
                attachAsked = false;
                attachedPorts.remove(port);
                if (currentRun != null && currentRun.isRunning()) {
                    ops.setPendingDebugAttach(this::stop); // waiting again: Stop ends the build
                }
            }
            case TIMED_OUT -> {
                stopAttachLoop();
                String message = tr("status.testrunner.debugNoAttach", port, attachDeadlineMs / 1000);
                host.setStatus(message);
                panel().setRunNote(message);
            }
            case KEEP_WAITING -> {}
        }
    }

    /** {@code test.showOnlyFailed}: open the window and narrow the tree to failures/errors. */
    void showOnlyFailed() {
        ops.openTestResults();
        panel().showOnlyFailed();
        host.setStatus(tr("status.testrunner.filterFailed"));
    }

    /** {@code test.showAllTests}: open the window and drop every status/name filter. */
    void showAllTests() {
        ops.openTestResults();
        panel().clearFilter();
        host.setStatus(tr("status.testrunner.filterCleared"));
    }

    /** {@code test.filterTests}: open the window and put the caret in its filter field. */
    void focusFilter() {
        ops.openTestResults();
        panel().focusFilter();
    }

    void rerun() {
        if (external != null) {
            external.rerun();
        } else if (currentRun != null) {
            ops.runTest(currentRun.tool(), currentRun.workingDir(), currentRun.taskArgs(), currentRun.toggleArgs());
        }
    }

    void rerunFailed() {
        if (currentRun == null) {
            return;
        }
        if (external != null) {
            List<TestNode> failed = currentRun.root().failedLeaves();
            if (failed.isEmpty()) {
                external.rerun();
            } else {
                external.rerunFailed(failed);
            }
            return;
        }
        if (currentRun.tool() == BuildTool.NPM && nodeTestRun()) {
            List<String> names = currentRun.root().failedLeaves().stream()
                    .map(TestNode::methodName)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            ops.runTest(
                    currentRun.tool(),
                    currentRun.workingDir(),
                    com.editora.test.NodeTestNames.npmTask(currentRun.taskArgs(), names, npmManager),
                    currentRun.toggleArgs());
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

    /** Whether this npm run's results came from node:test, which filters tests by name. */
    private boolean nodeTestRun() {
        return nodeTestRun || (parser instanceof TapParser tap && tap.nodeTest());
    }

    void stop() {
        // Debug ▸ Stop on a Debug Test session comes here too (its ownerStop): the loop ends before the build
        // does, so the JVM the debugger just left — its agent listens again while it runs on to its end —
        // is not taken for a next fork and attached as it exits.
        stopAttachLoop();
        if (external != null) {
            external.stop();
        } else if (currentTool != null) {
            ops.stopTest(currentTool);
        }
    }

    void shutdown() {
        stopPolling();
        stopAttachLoop();
        stopElapsedTimer();
        poller.shutdownNow();
    }
}
