package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import javafx.application.Platform;

import com.editora.process.ExecTargets;
import com.editora.process.ProcessRunner;
import com.editora.run.PythonLaunch;
import com.editora.run.PythonRunPlan;
import com.editora.test.JavaTestScanner;
import com.editora.test.PytestReportParser;
import com.editora.test.PytestScanner;
import com.editora.test.TestNode;
import com.editora.test.TestNodeKind;
import com.editora.test.UnittestOutputParser;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Runs Python tests: pytest, with its JUnit XML report read into Test Results, or — when the project's
 * interpreter has no pytest — Python's own {@code unittest} for {@code TestCase} tests, its verbose output read
 * as it prints. The command streams in the Run console (Stop, input and links as for any run) under the project's
 * interpreter, in the project root with the root and {@code src/} on {@code PYTHONPATH} (see
 * {@link PythonRunPlan}). Test Results' rerun, rerun-failed, run-one and jump-to-source come back here.
 *
 * <p>A file's gutter ▶ runs the file, a test's ▶ (and Run Test at Caret) that test, {@code test.run} the whole
 * project.
 */
final class PythonTestRunner {

    /** What the runner needs from its window. */
    interface Host {
        RunCoordinator runCoordinator();

        TestRunCoordinator testRunCoordinator();

        /** This window's project root, or null. */
        @Nullable
        Path projectRoot();

        /** The interpreter picked for the project ("" = detect). */
        String interpreterChoice();

        com.editora.config.Settings settings();

        void setStatus(String message);

        void openAndGoto(Path file, int line0, int col0);

        /** Opens a failure's stack frame, running {@code fallback} when it does not resolve. */
        void openFrame(TestNode node, Runnable fallback);

        /**
         * Debugs tests by id in {@code cwd} under {@code anchor}'s interpreter (see {@code
         * DebugCoordinator.debugPythonTests}); a no-op where there is no debugger.
         */
        default void debugTests(
                Path anchor, Path cwd, List<String> pytestIds, List<String> unittestIds, @Nullable String label) {}

        /** Whether tests run in {@code where} can be debugged (the Python debugger is set up for that machine). */
        default boolean canDebug(@Nullable Path where) {
            return false;
        }
    }

    private final Host host;

    PythonTestRunner(Host host) {
        this.host = host;
    }

    /**
     * What one run covers.
     *
     * @param rootDir where pytest runs (the project root, or the file's folder)
     * @param pytestIds node ids relative to {@code rootDir}; empty = everything under it
     * @param unittestDir where unittest runs (the file's folder, or the project's {@code tests/})
     * @param unittestIds dotted ids relative to {@code unittestDir}; empty = discover; null = not unittest tests
     * @param label the Test Results / console title
     * @param script the file being tested (saved first), or null
     */
    record Scope(
            Path rootDir,
            List<String> pytestIds,
            Path unittestDir,
            @Nullable List<String> unittestIds,
            @Nullable String label,
            @Nullable Path script) {}

    /** The tests of {@code file} ({@code target} null), or one class/function in it (a gutter ▶ target). */
    void run(Path file, String text, JavaTestScanner.@Nullable TestTarget target) {
        start(scopeFor(file, text, target, host.projectRoot()));
    }

    /** {@code test.run} for Python: every test under the project, or under {@code anchor}'s folder without one. */
    void runAll(@Nullable Path anchor) {
        Path project = host.projectRoot();
        Path root = project != null ? project : anchor == null ? null : anchor.getParent();
        if (root == null) {
            host.setStatus(tr("status.testrunner.noBuildTool"));
            return;
        }
        Path tests = root.resolve("tests");
        Path unittestDir = !com.editora.vfs.Vfs.isRemote(tests) && Files.isDirectory(tests) ? tests : root;
        start(new Scope(
                root,
                List.of(),
                unittestDir,
                List.of(),
                root.getFileName() == null
                        ? root.toString()
                        : root.getFileName().toString(),
                null));
    }

    /** The scope of a file or one target in it (pure apart from the path arithmetic; tested). */
    static Scope scopeFor(
            Path file, String text, JavaTestScanner.@Nullable TestTarget target, @Nullable Path projectRoot) {
        Path folder = java.util.Objects.requireNonNull(file.getParent(), "a test file is in a folder");
        Path root = projectRoot != null && file.normalize().startsWith(projectRoot.normalize()) ? projectRoot : folder;
        String rel = root.relativize(file).toString().replace('\\', '/');
        String fileName = file.getFileName().toString();
        String module = fileName.endsWith(".py") ? fileName.substring(0, fileName.length() - 3) : fileName;
        String cls = target == null ? "" : target.className();
        String method = target == null ? null : target.methodName();
        boolean wholeFile = target == null || (method == null && cls.isEmpty());
        String suffix = wholeFile ? "" : method == null ? cls : cls.isEmpty() ? method : cls + "::" + method;
        List<String> pytest = List.of(suffix.isEmpty() ? rel : rel + "::" + suffix);
        List<String> unittest = null;
        if (looksLikeUnittest(text)) {
            if (wholeFile) {
                unittest = List.of(module);
            } else if (!cls.isEmpty()) {
                unittest = List.of(module + "." + cls + (method == null ? "" : "." + method));
            }
        }
        String label = wholeFile ? fileName : suffix.replace("::", ".");
        return new Scope(root, pytest, folder, unittest, label, file);
    }

    /** Whether a file's tests are {@code unittest} tests, which Python can run without pytest. */
    static boolean looksLikeUnittest(String text) {
        return text != null && (text.contains("unittest") || text.contains("TestCase"));
    }

    /** Plans pytest for {@code scope}, checks the interpreter has it, then runs pytest or unittest (or says why not). */
    void start(Scope scope) {
        if (host.runCoordinator().isRunning()) {
            host.setStatus(tr("status.run.busy"));
            return;
        }
        Path where = scope.rootDir();
        PythonRunLauncher.plan(request(scope, true, List.of()), where, probePlan -> {
            List<String> python =
                    PythonLaunch.withoutEnvPrefix(probePlan.interpreter().command());
            host.setStatus(tr("status.testrunner.pythonChecking"));
            Thread.ofVirtual().name("pytest-probe").start(() -> {
                List<String> probe = new ArrayList<>(python);
                probe.add("-c");
                probe.add("import pytest");
                ProcessRunner.Result r = ProcessRunner.run(where, Duration.ofSeconds(20), probe);
                boolean hasPytest = r != null && r.exit() == 0;
                Platform.runLater(() -> launch(scope, hasPytest, String.join(" ", python)));
            });
        });
    }

    /** The plan request for pytest ({@code -m pytest}) or unittest in {@code scope}. */
    private PythonRunPlan.Request request(Scope scope, boolean pytest, List<String> args) {
        Path dir = pytest ? scope.rootDir() : scope.unittestDir();
        return new PythonRunPlan.Request(
                scope.script(),
                host.projectRoot(),
                pytest ? "pytest" : "unittest",
                true,
                args,
                null,
                "",
                List.of(),
                host.interpreterChoice(),
                PythonRunLauncher.setting(dir, host.settings()),
                java.util.Map.of());
    }

    private void launch(Scope scope, boolean hasPytest, String python) {
        if (hasPytest) {
            launchPytest(scope);
        } else if (scope.unittestIds() instanceof List<String> unittestIds) {
            launchUnittest(scope, unittestIds);
        } else {
            host.setStatus(tr("status.testrunner.noPytest", python));
        }
    }

    private void launchPytest(Scope scope) {
        Path where = scope.rootDir();
        boolean here = ExecTargets.runsHere(where);
        Path report;
        String reportArg;
        try {
            if (here) {
                report = Files.createTempFile("editora-pytest", ".xml");
                report.toFile().deleteOnExit();
                Files.deleteIfExists(report); // pytest writes it; a stale empty file is no report
                reportArg = report.toString();
            } else {
                reportArg = "/tmp/editora-pytest-" + java.util.UUID.randomUUID() + ".xml";
                report = ExecTargets.forPath(where).toPath(reportArg);
            }
        } catch (java.io.IOException | RuntimeException e) {
            host.setStatus(tr("status.testrunner.failed", String.valueOf(e.getMessage())));
            return;
        }
        List<String> args = new ArrayList<>(scope.pytestIds());
        args.addAll(List.of("--junitxml=" + reportArg, "-o", "junit_family=xunit1", "--color=yes"));
        Path reportFile = report;
        PythonRunLauncher.plan(request(scope, true, args), where, plan -> {
            boolean[] shown = {false}; // set once it runs: Test Results opens over the Run console
            boolean[] launchFailed = {false};
            RunCoordinator.TestStream hooks = new RunCoordinator.TestStream() {
                @Override
                public void line(String text, boolean stderr) {}

                @Override
                public void exit(int code) {
                    if (!shown[0]) {
                        launchFailed[0] = true;
                        return;
                    }
                    Thread.ofVirtual().name("pytest-report").start(() -> {
                        String xml;
                        try {
                            xml = Files.readString(reportFile);
                            if (here) {
                                Files.deleteIfExists(reportFile);
                            }
                        } catch (java.io.IOException | RuntimeException e) {
                            xml = "";
                        }
                        var suites = PytestReportParser.parse(xml);
                        Platform.runLater(() -> {
                            host.testRunCoordinator().externalResults(suites);
                            host.testRunCoordinator().finishExternalRun(code);
                        });
                    });
                }
            };
            if (host.runCoordinator()
                    .runTests(
                            scope.label(), where, plan.argv(), plan.env(), scope.script(), hooks, () -> start(scope))) {
                shown[0] = !launchFailed[0]
                        && host.testRunCoordinator().startExternalRun(scope.label(), where, driver(scope, true));
            }
        });
    }

    private void launchUnittest(Scope scope, List<String> unittestIds) {
        Path dir = scope.unittestDir();
        List<String> args = new ArrayList<>();
        if (unittestIds.isEmpty()) {
            args.add("discover");
        }
        args.addAll(List.of("-v", "-b"));
        args.addAll(unittestIds);
        PythonRunLauncher.plan(request(scope, false, args), dir, plan -> {
            boolean[] shown = {false};
            boolean[] launchFailed = {false};
            UnittestOutputParser parser = new UnittestOutputParser();
            RunCoordinator.TestStream hooks = new RunCoordinator.TestStream() {
                @Override
                public void line(String text, boolean stderr) {
                    if (shown[0]) {
                        host.testRunCoordinator().externalResults(parser.onLine(text, stderr));
                    }
                }

                @Override
                public void exit(int code) {
                    if (shown[0]) {
                        host.testRunCoordinator().externalResults(parser.onExit(code));
                        host.testRunCoordinator().finishExternalRun(code);
                    } else {
                        launchFailed[0] = true; // it could not start: no Test Results for it
                    }
                }
            };
            if (host.runCoordinator()
                    .runTests(scope.label(), dir, plan.argv(), plan.env(), scope.script(), hooks, () -> start(scope))) {
                shown[0] = !launchFailed[0]
                        && host.testRunCoordinator().startExternalRun(scope.label(), dir, driver(scope, false));
                host.setStatus(tr("status.testrunner.unittestFallback"));
            }
        });
    }

    /** Test Results' actions for a run of {@code scope}. */
    private TestRunCoordinator.ExternalRun driver(Scope scope, boolean pytest) {
        return new TestRunCoordinator.ExternalRun() {
            @Override
            public void rerun() {
                start(scope);
            }

            @Override
            public void rerunFailed(List<TestNode> failed) {
                start(narrowed(scope, failed, pytest));
            }

            @Override
            public void runOne(TestNode node) {
                start(narrowed(scope, List.of(node), pytest));
            }

            @Override
            public void stop() {
                host.runCoordinator().stopRun();
            }

            @Override
            public boolean canDebug() {
                return host.canDebug(pytest ? scope.rootDir() : scope.unittestDir());
            }

            @Override
            public boolean debug(TestNode node) {
                DebugTarget t = debugTarget(scope, node, pytest);
                if (t == null) {
                    return false;
                }
                host.debugTests(t.anchor(), t.cwd(), t.pytestIds(), t.unittestIds(), node.displayName());
                return true;
            }

            @Override
            public void jump(TestNode node) {
                Runnable byName = () -> jumpByName(scope, node, pytest);
                if (node.stackTrace() != null) {
                    host.openFrame(node, byName);
                } else {
                    byName.run();
                }
            }
        };
    }

    /** What a Test Results row's Debug runs: the ids, the folder, and the file whose interpreter is used. */
    record DebugTarget(Path anchor, Path cwd, List<String> pytestIds, List<String> unittestIds) {}

    /**
     * The debug run of one Test Results row of a {@code scope} run (pure apart from path arithmetic; tested): a
     * pytest run debugs the row's node id from the same folder; a unittest run (no pytest) its dotted id. The
     * anchor is the row's file (from its report location or module), else the run's file, else its folder.
     * Null when the row names nothing to run.
     */
    static @Nullable DebugTarget debugTarget(Scope scope, TestNode node, boolean pytest) {
        if (pytest) {
            String id = pytestId(node);
            if (id == null) {
                return null;
            }
            String file = id.contains("::") ? id.substring(0, id.indexOf("::")) : id;
            Path anchor = scope.rootDir().resolve(file);
            return new DebugTarget(anchor, scope.rootDir(), List.of(id), List.of());
        }
        String id = unittestId(node);
        String cls = node.className();
        if (id == null || cls == null) { // a row with no class has no unittest id
            return null;
        }
        String module = cls.contains(".") ? cls.substring(0, cls.lastIndexOf('.')) : cls;
        Path anchor = scope.unittestDir().resolve(module.replace('.', '/') + ".py");
        if (scope.script() != null
                && !anchor.normalize().startsWith(scope.unittestDir().normalize())) {
            anchor = scope.script();
        }
        return new DebugTarget(anchor, scope.unittestDir(), List.of(), List.of(id));
    }

    /** {@code scope} limited to {@code nodes} (tests, or suite rows standing for their tests). */
    static Scope narrowed(Scope scope, List<TestNode> nodes, boolean pytest) {
        List<String> ids = new ArrayList<>();
        for (TestNode n : nodes) {
            String id = pytest ? pytestId(n) : unittestId(n);
            if (id != null && !ids.contains(id)) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return scope;
        }
        String label = nodes.size() == 1 ? nodes.get(0).displayName() : scope.label();
        return pytest
                ? new Scope(scope.rootDir(), ids, scope.unittestDir(), null, label, scope.script())
                : new Scope(scope.rootDir(), scope.pytestIds(), scope.unittestDir(), ids, label, scope.script());
    }

    /** A Test Results row's pytest node id: from its file and class, or a suite row's first test's file. */
    static @Nullable String pytestId(TestNode n) {
        if (n.kind() == TestNodeKind.TEST) {
            return PytestReportParser.nodeId(n.sourceFileHint(), n.className(), n.methodName());
        }
        for (TestNode child : n.children()) {
            if (child.sourceFileHint() != null) {
                return PytestReportParser.nodeId(child.sourceFileHint(), n.className(), null);
            }
        }
        return null;
    }

    /** A Test Results row's unittest id: {@code module.Class.test} (a suite row: {@code module.Class}). */
    static @Nullable String unittestId(TestNode n) {
        if (n.className() == null) {
            return null;
        }
        return n.kind() == TestNodeKind.TEST && n.methodName() != null
                ? n.className() + "." + n.methodName()
                : n.className();
    }

    /** Opens a test's source by its report location (pytest) or its module and name (unittest). */
    private void jumpByName(Scope scope, TestNode node, boolean pytest) {
        Path file = null;
        int line = -1;
        if (pytest && node.sourceFileHint() != null) {
            file = scope.rootDir().resolve(node.sourceFileHint());
            line = node.sourceLineHint() - 1;
        } else if (node.className() != null) {
            String cls = node.className();
            String module = cls.contains(".") ? cls.substring(0, cls.lastIndexOf('.')) : cls;
            file = scope.unittestDir().resolve(module.replace('.', '/') + ".py");
        }
        if (file == null || !Files.isRegularFile(file)) {
            host.setStatus(tr("status.testrunner.noSource", node.displayName()));
            return;
        }
        if (line < 0 && node.methodName() != null) {
            line = defLine(file, node.methodName());
        }
        host.openAndGoto(file, Math.max(0, line), 0);
    }

    /** The 0-based line of {@code def name} in {@code file}, or 0. */
    private static int defLine(Path file, String name) {
        try {
            String text = Files.readString(file);
            for (PytestScanner.TestTarget t : PytestScanner.scan(text)) {
                if (name.equals(t.functionName())) {
                    return t.line();
                }
            }
        } catch (java.io.IOException | RuntimeException ignored) {
            // unreadable: the top of the file
        }
        return 0;
    }
}
