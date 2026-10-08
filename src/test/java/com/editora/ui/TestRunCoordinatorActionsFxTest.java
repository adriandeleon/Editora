package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.editora.build.BuildTool;
import com.editora.config.Settings;
import com.editora.run.StackTraceLinks;
import com.editora.test.JavaTestScanner;
import com.editora.test.TestNode;
import com.editora.test.TestRun;
import com.editora.test.TestStatus;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the Test Results window does with a finished run: its rows, the row menu (Go to, Rerun, Debug), the
 * Rerun / Rerun Failed / Stop buttons' commands, and Debug Test attaching when the suspended test JVM
 * announces its port. No build tool is started: the run is played through the coordinator's hook, with a
 * JUnit report written into the project the way Surefire or Gradle would.
 */
@Tag("fx")
class TestRunCoordinatorActionsFxTest {

    private static final String REPORT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="com.x.FooTest" tests="4" failures="1" errors="1" skipped="1" time="0.05">
              <testcase name="works" classname="com.x.FooTest" time="0.02"/>
              <testcase name="fails" classname="com.x.FooTest" time="0.01">
                <failure message="expected 1 but was 2" type="org.opentest4j.AssertionFailedError">at com.x.FooTest.fails(FooTest.java:12)</failure>
              </testcase>
              <testcase name="breaks" classname="com.x.FooTest" time="0.01">
                <error message="boom" type="java.lang.IllegalStateException">at com.x.FooTest.breaks(FooTest.java:20)</error>
              </testcase>
              <testcase name="later" classname="com.x.FooTest" time="0"><skipped/></testcase>
            </testsuite>
            """;

    private static final String JDWP_BANNER = "Listening for transport dt_socket at address: 5005";

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @TempDir
    Path dir;

    private final List<String> statuses = new ArrayList<>();
    private final List<String> calls = new ArrayList<>();
    private boolean debugAvailable;
    private Settings settings;
    private TestRunCoordinator coordinator;
    private Stage stage;

    private final class Host extends CoordinatorHostStub {
        @Override
        public Settings settings() {
            return settings;
        }

        @Override
        public void setStatus(String message) {
            statuses.add(message);
        }
    }

    private final class Ops implements TestRunCoordinator.Ops {
        @Override
        public void openTestResults() {
            calls.add("open");
        }

        @Override
        public void setTestResultsAvailable(boolean available) {}

        @Override
        public void openLink(StackTraceLinks.Link link) {}

        @Override
        public void jumpToTest(TestNode node, BuildTool tool) {
            calls.add("jump " + tool + " " + node.className() + "#" + node.methodName());
        }

        @Override
        public void runTest(BuildTool tool, Path root, List<String> taskArgs, List<String> toggleArgs) {
            calls.add("run " + tool + " " + taskArgs + " " + toggleArgs);
            // As the build coordinator does: launching the task starts a new run through the hook, at once.
            coordinator.onTestRunStart(tool, root, taskArgs, toggleArgs, List.of("tool"));
        }

        @Override
        public void stopTest(BuildTool tool) {
            calls.add("stop " + tool);
        }

        @Override
        public boolean debugAvailable() {
            return debugAvailable;
        }

        @Override
        public void attachDebugger(String className, String host, int port) {
            calls.add("attach " + className + " " + host + ":" + port);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        settings = new Settings();
        settings.setTestRunner(true);
        coordinator = FxTestSupport.callOnFx(() -> new TestRunCoordinator(new Host(), new Ops()));
    }

    @AfterEach
    void tearDown() throws Exception {
        FxTestSupport.runOnFx(() -> {
            for (Window w : List.copyOf(Window.getWindows())) {
                if (w instanceof ContextMenu menu) {
                    menu.hide();
                }
            }
            if (stage != null) {
                stage.hide();
            }
            coordinator.shutdown();
        });
    }

    // --- harness --------------------------------------------------------------------------------------

    /** Everything queued on the report poller has run, and what it posted to the FX thread too. */
    private void settle() throws Exception {
        ScheduledExecutorService poller = FxTestSupport.field(coordinator, "poller");
        poller.submit(() -> {}).get(30, TimeUnit.SECONDS);
        FxTestSupport.drainFx();
        FxTestSupport.drainFx();
    }

    /** A finished, filtered Gradle run of {@code com.x.FooTest} whose report was written during the run. */
    private void finishedGradleRun() throws Exception {
        List<String> task = List.of("test", "--tests", "com.x.FooTest");
        FxTestSupport.runOnFx(() -> assertTrue(
                coordinator.onTestRunStart(BuildTool.GRADLE, dir, task, List.of("--offline"), List.of("gradle"))));
        settle(); // the pre-run snapshot of the reports folder has been taken
        Path reports = Files.createDirectories(dir.resolve("build/test-results/test"));
        Files.writeString(reports.resolve("TEST-com.x.FooTest.xml"), REPORT);
        FxTestSupport.runOnFx(() -> coordinator.onTestExit(1));
        settle();
        calls.clear();
        statuses.clear();
    }

    private TreeView<TestNode> tree() {
        return FxTestSupport.field(coordinator.panel(), "tree");
    }

    private List<String> leaves() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            List<String> out = new ArrayList<>();
            for (TreeItem<TestNode> suite : tree().getRoot().getChildren()) {
                for (TreeItem<TestNode> leaf : suite.getChildren()) {
                    out.add(leaf.getValue().displayName() + ":"
                            + leaf.getValue().status());
                }
            }
            return out;
        });
    }

    /** Selects the suite row ({@code test == null}) or one of its tests. FX thread. */
    private void select(String test) {
        TreeItem<TestNode> suite = tree().getRoot().getChildren().get(0);
        TreeItem<TestNode> item = test == null
                ? suite
                : suite.getChildren().stream()
                        .filter(i -> i.getValue().displayName().equals(test))
                        .findFirst()
                        .orElseThrow();
        tree().getSelectionModel().select(item);
    }

    /** Shows the window the panel lives in, so its rows are laid out and its row menu has somewhere to open. */
    private void showWindow() throws Exception {
        FxTestSupport.runOnFx(() -> {
            stage = new Stage();
            stage.setScene(new Scene(coordinator.panel(), 900, 600));
            stage.show();
            coordinator.panel().applyCss();
            coordinator.panel().layout();
        });
        FxTestSupport.drainFx();
    }

    /** Right-clicks the tree and returns the menu it opened, or null when none did. FX thread. */
    private ContextMenu rightClick() {
        tree().fireEvent(new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 10, 10, 10, 10, false, null));
        for (Window w : Window.getWindows()) {
            if (w instanceof ContextMenu menu && menu.isShowing()) {
                return menu;
            }
        }
        return null;
    }

    private List<String> menuLabels(String selection) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            select(selection);
            ContextMenu menu = rightClick();
            assertNotNull(menu, "the row menu opened");
            List<String> labels =
                    menu.getItems().stream().map(MenuItem::getText).toList();
            menu.hide();
            return labels;
        });
    }

    private void chooseFromMenu(String selection, String label) throws Exception {
        FxTestSupport.runOnFx(() -> {
            select(selection);
            ContextMenu menu = rightClick();
            assertNotNull(menu, "the row menu opened");
            MenuItem item = menu.getItems().stream()
                    .filter(i -> label.equals(i.getText()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no '" + label + "' in " + menu.getItems()));
            item.fire();
            menu.hide();
        });
        FxTestSupport.drainFx();
    }

    // --- the run as shown -------------------------------------------------------------------------------

    @Test
    void theReportOfARunBecomesRowsWithTheirOutcomeAndTheSuiteASummary() throws Exception {
        finishedGradleRun();
        showWindow();

        assertEquals(List.of("works:PASSED", "fails:FAILED", "breaks:ERROR", "later:SKIPPED"), leaves());
        CodeArea detail = FxTestSupport.field(coordinator.panel(), "detail");
        FxTestSupport.runOnFx(() -> select(null));
        assertTrue(
                FxTestSupport.callOnFx(detail::getText)
                        .contains(
                                tr("testrunner.detail.suiteSummary", 4, 1, 2, 1).strip()),
                "the suite row sums its tests: " + FxTestSupport.callOnFx(detail::getText));
        FxTestSupport.runOnFx(() -> select("breaks"));
        String broken = FxTestSupport.callOnFx(detail::getText);
        assertTrue(broken.contains(tr("testrunner.detail.status.error")), broken);
        assertTrue(broken.contains("java.lang.IllegalStateException: boom"), broken);
        assertTrue(broken.contains("FooTest.java:20"), broken);
        FxTestSupport.runOnFx(() -> select("later"));
        assertTrue(FxTestSupport.callOnFx(detail::getText).contains(tr("testrunner.detail.status.skipped")));

        // The rows themselves: a name each, and a duration on a test that took measurable time.
        FxTestSupport.runOnFx(() -> {
            tree().getSelectionModel().clearSelection();
            coordinator.panel().applyCss();
            coordinator.panel().layout();
        });
        List<String> shown = FxTestSupport.callOnFx(() -> tree().lookupAll(".tree-cell .label").stream()
                .map(n -> ((Label) n).getText())
                .toList());
        assertTrue(shown.containsAll(List.of("com.x.FooTest", "works", "fails", "breaks", "later")), shown.toString());
        assertTrue(shown.contains("20 ms"), "the passing test's 0.02 s: " + shown);
        assertFalse(shown.contains("0 ms"), "no duration is shown for a test that reported none: " + shown);
    }

    @Test
    void aDisabledTestRunnerLeavesTheRunToItsOwnConsole() throws Exception {
        settings.setTestRunner(false);

        boolean taken = FxTestSupport.callOnFx(
                () -> coordinator.onTestRunStart(BuildTool.GRADLE, dir, List.of("test"), List.of(), List.of("gradle")));
        FxTestSupport.runOnFx(() -> {
            coordinator.onTestOutput("anything", false);
            coordinator.onTestExit(0);
            coordinator.rerun();
            coordinator.rerunFailed();
            coordinator.stop();
        });

        assertFalse(taken);
        assertEquals(List.of(), calls, "with no run there is nothing to open, rerun or stop");
        assertEquals("raw line", FxTestSupport.callOnFx(() -> coordinator.consoleLine("raw line", false)));
    }

    // --- the row menu, double-click and Enter -----------------------------------------------------------

    @Test
    void theRowMenuOffersWhatCanBeDoneWithThatRow() throws Exception {
        finishedGradleRun();
        showWindow();

        assertEquals(List.of(tr("testrunner.menu.goToTest"), tr("testrunner.menu.rerunTest")), menuLabels("fails"));
        debugAvailable = true;
        assertEquals(
                List.of(
                        tr("testrunner.menu.goToTest"),
                        tr("testrunner.menu.rerunTest"),
                        tr("testrunner.menu.debugTest")),
                menuLabels("fails"));

        assertNull(
                FxTestSupport.callOnFx(() -> {
                    tree().getSelectionModel().clearSelection();
                    return rightClick();
                }),
                "with no row selected there is nothing to offer");
    }

    /**
     * A class row had no menu at all: only test rows knew their class, and the menu is not shown for a row
     * without one. "Go to Test Class", "Rerun This Class" and "Debug This Class" could not be reached.
     */
    @Test
    void aClassRowHasItsOwnMenuAndRerunsOrDebugsTheWholeClass() throws Exception {
        finishedGradleRun();
        showWindow();
        debugAvailable = true;

        assertEquals(
                List.of(
                        tr("testrunner.menu.goToClass"),
                        tr("testrunner.menu.rerunClass"),
                        tr("testrunner.menu.debugClass")),
                menuLabels(null));

        chooseFromMenu(null, tr("testrunner.menu.goToClass"));
        assertEquals(List.of("jump GRADLE com.x.FooTest#null"), calls);

        calls.clear();
        chooseFromMenu(null, tr("testrunner.menu.rerunClass"));
        assertEquals("run GRADLE [test, --tests, com.x.FooTest] [--offline]", calls.get(0));

        finishedGradleRun(); // the rerun started a new run: finish it before using its rows
        chooseFromMenu(null, tr("testrunner.menu.debugClass"));
        assertEquals("run GRADLE [test, --tests, com.x.FooTest, --debug-jvm] [--offline]", calls.get(0));
        FxTestSupport.runOnFx(() -> coordinator.onTestOutput(JDWP_BANNER, false));
        assertEquals("attach com.x.FooTest localhost:5005", calls.get(calls.size() - 1));
    }

    @Test
    void goToJumpsToTheTestAndRerunRunsOnlyThatTest() throws Exception {
        finishedGradleRun();
        showWindow();

        chooseFromMenu("fails", tr("testrunner.menu.goToTest"));
        assertEquals(List.of("jump GRADLE com.x.FooTest#fails"), calls);

        calls.clear();
        chooseFromMenu("fails", tr("testrunner.menu.rerunTest"));
        assertEquals("run GRADLE [test, --tests, com.x.FooTest.fails] [--offline]", calls.get(0));
    }

    @Test
    void debugTestRunsItSuspendedAndAttachesWhenTheTestJvmSaysWhere() throws Exception {
        finishedGradleRun();
        showWindow();
        debugAvailable = true;

        chooseFromMenu("fails", tr("testrunner.menu.debugTest"));

        assertEquals(List.of(tr("status.testrunner.debugWaiting")), statuses);
        assertEquals("run GRADLE [test, --tests, com.x.FooTest.fails, --debug-jvm] [--offline]", calls.get(0));
        FxTestSupport.runOnFx(() -> {
            coordinator.onTestOutput("> Task :test", false);
            coordinator.onTestOutput(JDWP_BANNER, false);
            coordinator.onTestOutput(JDWP_BANNER, false); // printed again by a second fork: already attached
        });
        assertEquals(
                List.of("attach com.x.FooTest localhost:5005"),
                calls.stream().filter(c -> c.startsWith("attach")).toList());
    }

    @Test
    void aDoubleClickOrEnterOnATestJumpsToItAndOnASuiteDoesNothing() throws Exception {
        finishedGradleRun();
        MouseEvent doubleClick = new MouseEvent(
                MouseEvent.MOUSE_CLICKED,
                5,
                5,
                5,
                5,
                MouseButton.PRIMARY,
                2,
                false,
                false,
                false,
                false,
                true,
                false,
                false,
                false,
                false,
                true,
                null);
        MouseEvent singleClick = new MouseEvent(
                MouseEvent.MOUSE_CLICKED,
                5,
                5,
                5,
                5,
                MouseButton.PRIMARY,
                1,
                false,
                false,
                false,
                false,
                true,
                false,
                false,
                false,
                false,
                true,
                null);
        KeyEvent enter = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false);
        KeyEvent space = new KeyEvent(KeyEvent.KEY_PRESSED, " ", " ", KeyCode.SPACE, false, false, false, false);

        FxTestSupport.runOnFx(() -> {
            select("works");
            tree().fireEvent(singleClick);
            tree().fireEvent(space);
        });
        assertEquals(List.of(), calls, "one click only selects");

        FxTestSupport.runOnFx(() -> {
            tree().fireEvent(doubleClick);
            select("breaks");
            tree().fireEvent(enter);
            select(null);
            tree().fireEvent(doubleClick);
            tree().fireEvent(enter);
        });
        assertEquals(List.of("jump GRADLE com.x.FooTest#works", "jump GRADLE com.x.FooTest#breaks"), calls);
    }

    // --- Rerun, Rerun Failed, Stop ----------------------------------------------------------------------

    @Test
    void rerunRepeatsTheRunAndRerunFailedNarrowsItToWhatFailed() throws Exception {
        finishedGradleRun();

        FxTestSupport.runOnFx(coordinator::rerun);
        assertEquals("run GRADLE [test, --tests, com.x.FooTest] [--offline]", calls.get(0));

        finishedGradleRun();
        FxTestSupport.runOnFx(coordinator::rerunFailed);
        assertEquals(
                "run GRADLE [test, --tests, com.x.FooTest.fails, --tests, com.x.FooTest.breaks] [--offline]",
                calls.get(0));
        assertEquals(List.of(), statuses);

        FxTestSupport.runOnFx(coordinator::stop);
        assertEquals("stop GRADLE", calls.get(calls.size() - 1));
    }

    @Test
    void rerunFailedWithNothingFailedRunsEverythingAgainAndSaysWhy() throws Exception {
        FxTestSupport.runOnFx(() -> {
            assertTrue(coordinator.onTestRunStart(
                    BuildTool.GO, dir, List.of("test", "./..."), List.of(), List.of("go", "test", "-json", "./...")));
            coordinator.onTestOutput("{\"Action\":\"run\",\"Package\":\"ex/pkg\",\"Test\":\"TestA\"}", false);
            coordinator.onTestOutput(
                    "{\"Action\":\"pass\",\"Package\":\"ex/pkg\",\"Test\":\"TestA\",\"Elapsed\":0.02}", false);
            coordinator.onTestExit(0);
        });
        settle();
        assertEquals(List.of("TestA:PASSED"), leaves());

        FxTestSupport.runOnFx(coordinator::rerunFailed);

        assertEquals(List.of(tr("status.testrunner.rerunFailedUnsupported")), statuses);
        assertEquals(
                List.of("run GO [test, ./...] []"),
                calls.stream().filter(c -> c.startsWith("run")).toList());
    }

    @Test
    void aToolWithNoPerTestFilterCannotRerunOrDebugOneRow() throws Exception {
        FxTestSupport.runOnFx(() -> {
            assertTrue(coordinator.onTestRunStart(
                    BuildTool.GO, dir, List.of("test", "./..."), List.of(), List.of("go", "test", "-json", "./...")));
            coordinator.onTestOutput("{\"Action\":\"run\",\"Package\":\"ex/pkg\",\"Test\":\"TestA\"}", false);
            coordinator.onTestOutput(
                    "{\"Action\":\"fail\",\"Package\":\"ex/pkg\",\"Test\":\"TestA\",\"Elapsed\":0.02}", false);
            coordinator.onTestExit(1);
        });
        settle();
        showWindow();
        debugAvailable = true;
        calls.clear();

        chooseFromMenu("TestA", tr("testrunner.menu.rerunTest"));
        chooseFromMenu("TestA", tr("testrunner.menu.debugTest"));

        assertEquals(
                List.of(tr("status.testrunner.rerunUnsupported"), tr("status.testrunner.debugUnsupported")), statuses);
        assertEquals(List.of(), calls, "nothing was launched");
    }

    // --- Debug Test from the editor ---------------------------------------------------------------------

    @Test
    void debuggingATestFromTheEditorNeedsADebuggerAndAToolThatCanSuspend() throws Exception {
        JavaTestScanner.TestTarget target = new JavaTestScanner.TestTarget(4, "demo.CalcTest", "adds", false);
        List<List<String>> launched = new ArrayList<>();

        FxTestSupport.runOnFx(() -> coordinator.debugSingleTest(BuildTool.MAVEN, target, launched::add));
        debugAvailable = true;
        FxTestSupport.runOnFx(() -> coordinator.debugSingleTest(BuildTool.CARGO, target, launched::add));
        assertEquals(List.of(tr("status.debug.unavailable"), tr("status.testrunner.debugUnsupported")), statuses);
        assertEquals(List.of(), launched);

        statuses.clear();
        FxTestSupport.runOnFx(() -> coordinator.debugSingleTest(BuildTool.MAVEN, target, args -> {
            launched.add(args);
            coordinator.onTestRunStart(BuildTool.MAVEN, dir, args, List.of(), List.of("mvn"));
        }));
        assertEquals(List.of(tr("status.testrunner.debugWaiting")), statuses);
        assertEquals("test", launched.get(0).get(0));
        assertEquals("-Dtest=CalcTest#adds", launched.get(0).get(1));
        assertEquals(
                "-Dmaven.surefire.debug", launched.get(0).get(launched.get(0).size() - 1));

        FxTestSupport.runOnFx(() -> coordinator.onTestOutput(JDWP_BANNER, false));
        assertEquals(
                List.of("attach demo.CalcTest localhost:5005"),
                calls.stream().filter(c -> c.startsWith("attach")).toList());
    }

    // --- how results arrive -----------------------------------------------------------------------------

    @Test
    void anUnfilteredRunListsTheProjectsTestsAsPendingAndDropsTheOnesThatNeverRan() throws Exception {
        Path source = dir.resolve("src/test/java/demo/CalcTest.java");
        Files.createDirectories(source.getParent());
        Files.writeString(
                source,
                "package demo;\n\nclass CalcTest {\n    @Test\n    void adds() {}\n\n    @Test\n    void subtracts() {}\n}\n");

        FxTestSupport.runOnFx(() -> assertTrue(
                coordinator.onTestRunStart(BuildTool.MAVEN, dir, List.of("test"), List.of(), List.of("mvn"))));
        settle();
        assertEquals(List.of("adds:RUNNING", "subtracts:RUNNING"), leaves(), "known before the first one finishes");

        // The build stops at a compile error: no report is written, and nothing stays "running" for good.
        FxTestSupport.runOnFx(() -> coordinator.onTestExit(1));
        settle();
        assertEquals(List.of(), leaves());
        TestRun run = FxTestSupport.callOnFx(() -> FxTestSupport.field(coordinator, "currentRun"));
        assertFalse(FxTestSupport.callOnFx(run::isRunning));
    }

    @Test
    void npmOutputIsReadAsTapOnceItLooksLikeTap() throws Exception {
        FxTestSupport.runOnFx(() -> {
            assertTrue(coordinator.onTestRunStart(
                    BuildTool.NPM, dir, List.of("run", "test"), List.of(), List.of("npm", "run", "test")));
            coordinator.onTestOutput("> demo@1.0.0 test", false);
            coordinator.onTestOutput("> node --test", false);
            coordinator.onTestOutput("TAP version 13", false);
            coordinator.onTestOutput("ok 1 - adds", false);
            coordinator.onTestOutput("not ok 2 - subtracts", false);
            coordinator.onTestOutput("1..2", false);
            coordinator.onTestExit(1);
        });
        settle();

        List<String> leaves = leaves();
        assertEquals(2, leaves.size(), leaves.toString());
        assertTrue(
                leaves.get(0).startsWith("adds") && leaves.get(0).endsWith(":" + TestStatus.PASSED), leaves.toString());
        assertTrue(
                leaves.get(1).startsWith("subtracts") && leaves.get(1).endsWith(":" + TestStatus.FAILED),
                leaves.toString());
    }

    @Test
    void npmOutputThatNeverLooksLikeTapYieldsNoRows() throws Exception {
        FxTestSupport.runOnFx(() -> {
            assertTrue(coordinator.onTestRunStart(
                    BuildTool.NPM, dir, List.of("run", "test"), List.of(), List.of("npm", "run", "test")));
            for (int i = 0; i < 450; i++) {
                coordinator.onTestOutput("  PASS  src/case" + i + ".test.js", false);
            }
            coordinator.onTestExit(0);
        });
        settle();

        assertEquals(List.of(), leaves());
    }

    @Test
    void aRunThatCouldNotBeStartedIsReportedAndEnded() throws Exception {
        FxTestSupport.runOnFx(() -> coordinator.onTestError("never started")); // no run: nothing to report

        FxTestSupport.runOnFx(() -> {
            assertTrue(coordinator.onTestRunStart(
                    BuildTool.GO, dir, List.of("test", "./..."), List.of(), List.of("go", "test", "./...")));
            coordinator.onTestError("Cannot run program \"go\"");
        });
        settle();

        assertEquals(List.of(tr("status.testrunner.failed", "Cannot run program \"go\"")), statuses);
        TestRun run = FxTestSupport.callOnFx(() -> FxTestSupport.field(coordinator, "currentRun"));
        assertFalse(FxTestSupport.callOnFx(run::isRunning));
    }

    // --- the filter commands ----------------------------------------------------------------------------

    @Test
    void theFilterCommandsOpenTheWindowAndNarrowOrRestoreTheTree() throws Exception {
        finishedGradleRun();

        FxTestSupport.runOnFx(coordinator::showOnlyFailed);
        assertEquals(List.of("fails:FAILED", "breaks:ERROR"), leaves());
        FxTestSupport.runOnFx(coordinator::showAllTests);
        assertEquals(4, leaves().size());
        FxTestSupport.runOnFx(coordinator::focusFilter);

        assertEquals(List.of("open", "open", "open"), calls);
        assertEquals(List.of(tr("status.testrunner.filterFailed"), tr("status.testrunner.filterCleared")), statuses);
    }
}
