package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.test.NodeSpecParser;
import com.editora.test.NodeTestNames;
import com.editora.test.ParsedSuite;
import com.editora.test.TapParser;
import com.editora.test.TestNode;
import com.editora.test.TestNodeKind;
import com.editora.test.TestResultParser;

import static com.editora.i18n.Messages.tr;

/**
 * Runs a Node.js test file's tests — the ▶ of a {@code *.test.js}, {@code *.spec.ts} or {@code test/…} file —
 * as {@code node --test --test-reporter=tap <file>} in the Run console, its results read into Test Results
 * (the spec reporter is read too, should a shebang or option change the reporter). Rerun, Rerun Failed and
 * Rerun This Test come back here and filter by the test's exact name ({@link NodeTestNames}).
 */
final class NodeTestRunner {

    /** What the runner needs from its window. */
    interface Host {
        RunCoordinator runCoordinator();

        TestRunCoordinator testRunCoordinator();

        Path projectRoot();

        com.editora.config.Settings settings();

        void setStatus(String message);

        /** Opens a failure's stack frame, running {@code fallback} when it does not resolve. */
        void openFrame(TestNode node, Runnable fallback);
    }

    private final Host host;

    NodeTestRunner(Host host) {
        this.host = host;
    }

    /** Runs every test in {@code file}. */
    void run(Path file) {
        start(file, List.of());
    }

    /** Runs the tests of {@code file} named {@code rowNames} (Test Results rows; empty = all). */
    private void start(Path file, List<String> rowNames) {
        String label = file.getFileName().toString();
        NodeRunLauncher.plan(
                new NodeRunLauncher.Spec(
                        file,
                        host.projectRoot(),
                        "",
                        host.settings().getNodePath(),
                        List.of(),
                        NodeTestNames.nodeFlags(rowNames),
                        true,
                        Map.of(),
                        null),
                ready -> launch(file, label, rowNames, ready));
    }

    private void launch(Path file, String label, List<String> rowNames, NodeRunLauncher.Ready ready) {
        if (ready.plan().error() != null) {
            host.setStatus(tr(ready.plan().error()));
            return;
        }
        boolean[] shown = {false};
        boolean[] launchFailed = {false};
        TestResultParser[] parser = {null};
        List<String> sniff = new java.util.ArrayList<>();
        RunCoordinator.TestStream hooks = new RunCoordinator.TestStream() {
            @Override
            public void line(String text, boolean stderr) {
                if (!shown[0]) {
                    return;
                }
                if (parser[0] == null) {
                    sniff.add(text);
                    if (TapParser.looksLikeTap(sniff)) {
                        parser[0] = new TapParser(label);
                    } else if (NodeSpecParser.looksLikeSpec(sniff)) {
                        parser[0] = new NodeSpecParser(label);
                    } else {
                        if (sniff.size() > 200) {
                            sniff.clear();
                        }
                        return;
                    }
                    for (String s : sniff) {
                        results(parser[0].onLine(s, false));
                    }
                    sniff.clear();
                    return;
                }
                results(parser[0].onLine(text, stderr));
            }

            @Override
            public void exit(int code) {
                if (!shown[0]) {
                    launchFailed[0] = true;
                    return;
                }
                if (parser[0] != null) {
                    results(parser[0].onExit(code));
                }
                host.testRunCoordinator().finishExternalRun(code);
            }
        };
        if (host.runCoordinator()
                .runTests(
                        label,
                        ready.dir(),
                        ready.plan().argv(),
                        ready.plan().env(),
                        file,
                        hooks,
                        () -> start(file, rowNames))) {
            shown[0] = !launchFailed[0] && host.testRunCoordinator().startExternalRun(label, ready.dir(), driver(file));
        }
    }

    private void results(List<ParsedSuite> suites) {
        if (!suites.isEmpty()) {
            host.testRunCoordinator().externalResults(suites);
        }
    }

    private TestRunCoordinator.ExternalRun driver(Path file) {
        return new TestRunCoordinator.ExternalRun() {
            @Override
            public void rerun() {
                start(file, List.of());
            }

            @Override
            public void rerunFailed(List<TestNode> failed) {
                start(file, names(failed));
            }

            @Override
            public void runOne(TestNode node) {
                start(file, node.kind() == TestNodeKind.TEST ? names(List.of(node)) : List.of());
            }

            @Override
            public void stop() {
                host.runCoordinator().stopRun();
            }

            @Override
            public void jump(TestNode node) {
                host.openFrame(node, () -> host.setStatus(tr("status.run.linkNotFound", node.displayName())));
            }
        };
    }

    private static List<String> names(List<TestNode> nodes) {
        return nodes.stream()
                .map(TestNode::methodName)
                .filter(java.util.Objects::nonNull)
                .toList();
    }
}
