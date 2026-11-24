package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.editora.run.StackTraceLinks;
import com.editora.test.ParsedSuite;
import com.editora.test.ParsedTest;
import com.editora.test.TestNode;
import com.editora.test.TestNodeKind;
import com.editora.test.TestResultParser;
import com.editora.test.TestStatus;
import com.editora.test.ToolTestKind;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Runs a test file under its own runner — Jest, Vitest, Deno, RSpec, Minitest, PHPUnit, ExUnit, {@code dart test} —
 * as Run File and Run Test at Caret plan it, into Test Results: the run told to report ({@link ToolTestKind}), its
 * console the runner's own, its results read from the report file when it ends or from the console as it runs. Rerun
 * runs the same; Rerun Failed and Run This Test run the whole file narrowed to their tests by the runner's filter; a
 * row opens its test where it fails, else where it is declared.
 */
final class ToolTestRunner {

    /** What the runner needs from its window. */
    interface Host {
        RunCoordinator runCoordinator();

        TestRunCoordinator testRunCoordinator();

        void setStatus(String message);

        /** Opens {@code link}, running {@code fallback} when it does not resolve. */
        void openLink(StackTraceLinks.Link link, Runnable fallback);
    }

    /**
     * A run: {@code argv} as Run File or Run Test at Caret planned it, in {@code dir}, of test file {@code file}.
     * {@code rebuild}, for a built test program (C, C++), builds it again before each later run (Rerun, Rerun Failed,
     * Run This Test), then goes on; null for a runner that reads the sources itself.
     */
    record Spec(
            ToolTestKind kind,
            String label,
            Path dir,
            List<String> argv,
            Map<String, String> env,
            Path file,
            java.util.function.@Nullable Consumer<Runnable> rebuild) {

        Spec(ToolTestKind kind, String label, Path dir, List<String> argv, Map<String, String> env, Path file) {
            this(kind, label, dir, argv, env, file, null);
        }

        Spec narrowed(List<String> names) {
            return new Spec(kind, label, dir, kind.filtered(kind.wholeFile(argv), names), env, file, rebuild);
        }
    }

    private final Host host;

    ToolTestRunner(Host host) {
        this.host = host;
    }

    /** Runs {@code spec} into Test Results. */
    void run(Spec spec) {
        launchOrBuild(spec, false);
    }

    /** {@link #run}; {@code again}: a later run, of a program that is built again first ({@link Spec#rebuild}). */
    private void launchOrBuild(Spec spec, boolean again) {
        java.util.function.Consumer<Runnable> rebuild = spec.rebuild();
        if (again && rebuild != null) {
            rebuild.accept(() -> launchOrBuild(spec, false));
            return;
        }
        if (spec.kind() != ToolTestKind.RSPEC) {
            launch(spec, List.of());
            return;
        }
        // The project's .rspec names its console formats, which the JSON report would otherwise silence.
        com.editora.perf.OffFx.then(
                "run-rspec-formats",
                () -> {
                    Path dotRspec = spec.dir().resolve(".rspec");
                    return Files.isRegularFile(dotRspec) ? Files.readString(dotRspec) : null;
                },
                a -> launch(spec, ToolTestKind.rspecFormats(a.value())));
    }

    private void launch(Spec spec, List<String> rspecFormats) {
        ToolTestKind kind = spec.kind();
        Path report = kind.fileReport()
                ? Path.of(
                        System.getProperty("java.io.tmpdir"),
                        "editora-tests-" + java.util.UUID.randomUUID() + kind.reportSuffix())
                : null;
        List<String> argv = report == null
                ? kind.withReport(spec.argv(), Path.of(""), rspecFormats)
                : kind.withReport(spec.argv(), report, rspecFormats);
        boolean dropSkipped =
                kind.reportsFilteredAsSkipped() && !kind.wholeFile(spec.argv()).equals(spec.argv());
        TestResultParser parser = kind.consoleParser(spec.dir());
        boolean[] shown = {false};
        boolean[] launchFailed = {false};
        TestParseQueue parses = new TestParseQueue("tool-test-output-parse", this::results);
        RunCoordinator.TestStream hooks = new RunCoordinator.TestStream() {
            @Override
            public void line(String text, boolean stderr) {
                if (shown[0] && parser != null) {
                    parses.parse(() -> parser.onLine(text, stderr)); // on the parse thread, in order (PERF-01)
                }
            }

            @Override
            public void exit(int code) {
                if (!shown[0]) {
                    launchFailed[0] = true;
                    deleteLater(report);
                    return;
                }
                parses.parse(() -> {
                    if (parser != null) {
                        return parser.onExit(code);
                    }
                    return read(kind, report, spec.dir(), dropSkipped);
                });
                parses.then(() -> host.testRunCoordinator().finishExternalRun(code));
            }
        };
        host.runCoordinator()
                .runTests(
                        spec.label(),
                        spec.dir(),
                        argv,
                        spec.env(),
                        spec.file(),
                        hooks,
                        () -> launchOrBuild(spec, true),
                        () -> shown[0] = !launchFailed[0]
                                && host.testRunCoordinator().startExternalRun(spec.label(), spec.dir(), driver(spec)));
    }

    /** The report's tests, the report then deleted; none when the runner wrote none (it failed before). */
    private static List<ParsedSuite> read(ToolTestKind kind, @Nullable Path report, Path dir, boolean dropSkipped) {
        if (report == null) {
            return List.of();
        }
        try {
            if (!Files.isRegularFile(report)) {
                return List.of();
            }
            List<ParsedSuite> suites = kind.readReport(Files.readString(report), dir);
            if (!dropSkipped) {
                return suites;
            }
            // Jest, Vitest and Deno report the tests a filter left out as skipped: a narrowed run shows only its own.
            List<ParsedSuite> out = new ArrayList<>();
            for (ParsedSuite s : suites) {
                List<ParsedTest> ran = s.tests().stream()
                        .filter(t -> t.status() != TestStatus.SKIPPED)
                        .toList();
                if (!ran.isEmpty()) {
                    out.add(new ParsedSuite(s.suiteName(), ran, s.suiteStdout(), s.suiteStderr()));
                }
            }
            return out;
        } catch (Exception unreadable) {
            return List.of();
        } finally {
            try {
                Files.deleteIfExists(report);
            } catch (Exception kept) {
                // a temporary file: the system's to clear
            }
        }
    }

    private static void deleteLater(@Nullable Path report) {
        if (report != null) {
            com.editora.perf.OffFx.then("tool-test-report-delete", () -> Files.deleteIfExists(report), a -> {});
        }
    }

    private void results(List<ParsedSuite> suites) {
        if (!suites.isEmpty()) {
            host.testRunCoordinator().externalResults(suites);
        }
    }

    private TestRunCoordinator.ExternalRun driver(Spec spec) {
        return new TestRunCoordinator.ExternalRun() {
            @Override
            public void rerun() {
                launchOrBuild(spec, true);
            }

            @Override
            public void rerunFailed(List<TestNode> failed) {
                launchOrBuild(spec.narrowed(names(failed)), true);
            }

            @Override
            public void runOne(TestNode node) {
                launchOrBuild(
                        spec.narrowed(names(node.kind() == TestNodeKind.TEST ? List.of(node) : node.children())), true);
            }

            @Override
            public void stop() {
                host.runCoordinator().stopRun();
            }

            @Override
            public void jump(TestNode node) {
                ToolTestRunner.this.jump(spec, node);
            }
        };
    }

    private static List<String> names(List<TestNode> nodes) {
        return nodes.stream()
                .filter(n -> n.kind() == TestNodeKind.TEST)
                .map(TestNode::methodName)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }

    /**
     * Opens {@code node}'s test: a failure where it failed in its file, else where the runner says it is declared,
     * else the first frame of its stack, else the line of its file that names it.
     */
    private void jump(Spec spec, TestNode node) {
        Runnable notFound = () -> host.setStatus(tr("status.run.linkNotFound", node.displayName()));
        String hint = node.sourceFileHint();
        StackTraceLinks.Link frame = node.stackTrace() == null
                ? null
                : TestRunCoordinator.firstFrame(
                        node.stackTrace(),
                        hint == null ? null : String.valueOf(Path.of(hint).getFileName()));
        if (frame != null
                && hint != null
                && frame.file().endsWith(String.valueOf(Path.of(hint).getFileName()))) {
            host.openLink(frame, notFound);
            return;
        }
        if (hint != null && node.sourceLineHint() > 0) {
            host.openLink(new StackTraceLinks.Link(hint, node.sourceLineHint()), notFound);
            return;
        }
        if (frame != null) {
            host.openLink(frame, notFound);
            return;
        }
        Path file = hint != null ? Path.of(hint) : spec.file();
        String name = node.methodName();
        if (node.kind() != TestNodeKind.TEST || name == null) {
            host.openLink(new StackTraceLinks.Link(file.toString(), 1), notFound);
            return;
        }
        com.editora.perf.OffFx.then(
                "tool-test-jump",
                () -> Files.isRegularFile(file) ? ToolTestKind.lineOf(Files.readString(file), name) : 0,
                a -> {
                    Integer line = a.value();
                    host.openLink(
                            new StackTraceLinks.Link(file.toString(), line == null || line <= 0 ? 1 : line), notFound);
                });
    }
}
