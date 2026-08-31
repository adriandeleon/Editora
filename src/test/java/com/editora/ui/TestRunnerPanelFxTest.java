package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

import com.editora.build.BuildTool;
import com.editora.test.ParsedSuite;
import com.editora.test.ParsedTest;
import com.editora.test.TestFilter;
import com.editora.test.TestNode;
import com.editora.test.TestRun;
import com.editora.test.TestStatus;
import com.editora.test.TestTreeBuilder;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the real {@link TestRunnerPanel} JavaFX code headlessly: a run's suites/tests build the TreeView,
 * the header status reflects finish, and selecting a failed leaf populates the detail console. This exercises
 * the cell factory / tree sync / detail wiring that the pure {@code com.editora.test} tests can't.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestRunnerPanelFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildsTreeAndDetailFromARun() throws Exception {
        TestRun run = new TestRun(BuildTool.GO, Path.of("."), List.of("test", "./..."), List.of(), 0L);
        TestTreeBuilder.merge(
                run.root(),
                new ParsedSuite(
                        "ex/pkg",
                        List.of(new ParsedTest(
                                "ex/pkg",
                                "TestA",
                                TestStatus.PASSED,
                                20,
                                null,
                                null,
                                null,
                                "hello from the test",
                                null,
                                0))));
        TestTreeBuilder.merge(
                run.root(),
                new ParsedSuite(
                        "ex/pkg",
                        List.of(new ParsedTest(
                                "ex/pkg",
                                "TestB",
                                TestStatus.FAILED,
                                5,
                                null,
                                "boom",
                                "at foo_test.go:9",
                                null,
                                null,
                                0))));

        TestRunnerPanel panel = FxTestSupport.callOnFx(() -> {
            TestRunnerPanel p = new TestRunnerPanel();
            p.startRun("test ./...");
            p.update(run);
            return p;
        });

        TreeView<TestNode> tree = FxTestSupport.field(panel, "tree");
        assertEquals(1, tree.getRoot().getChildren().size(), "one suite");
        TreeItem<TestNode> suite = tree.getRoot().getChildren().get(0);
        assertEquals("ex/pkg", suite.getValue().displayName());
        assertEquals(2, suite.getChildren().size(), "two tests");

        // With nothing selected, the detail pane shows the run summary (not blank) — the Stage-3 fix.
        CodeArea detail = FxTestSupport.field(panel, "detail");
        assertTrue(detail.getText().contains("test ./..."), "detail shows the run header when nothing is selected");

        // Selecting a PASSING leaf shows real results, not just "Passed": the fully-qualified name and its
        // captured output (previously the pane was empty / status-only for a pass).
        TreeItem<TestNode> passed = suite.getChildren().stream()
                .filter(i -> i.getValue().status() == TestStatus.PASSED)
                .findFirst()
                .orElseThrow();
        FxTestSupport.runOnFx(() -> tree.getSelectionModel().select(passed));
        assertTrue(detail.getText().contains("ex/pkg.TestA"), "detail shows the fully-qualified test name");
        assertTrue(detail.getText().contains("hello from the test"), "detail shows the test's captured output");

        // Selecting the failed leaf populates the detail console with its message/stack.
        TreeItem<TestNode> failed = suite.getChildren().stream()
                .filter(i -> i.getValue().status() == TestStatus.FAILED)
                .findFirst()
                .orElseThrow();
        FxTestSupport.runOnFx(() -> tree.getSelectionModel().select(failed));
        assertTrue(detail.getText().contains("boom"), "detail shows the failure message");
        assertTrue(detail.getText().contains("foo_test.go:9"), "detail shows the stack frame");

        // Finishing a run with a failure sets a failed-summary status.
        run.finish(1, 100);
        FxTestSupport.runOnFx(() -> panel.finishRun(run, 1));
        Label status = FxTestSupport.field(panel, "status");
        assertTrue(status.getText() != null && !status.getText().isBlank());
    }

    @Test
    @SuppressWarnings("unchecked")
    void trackRunningTestFollowsUntilTheUserSelectsARow() throws Exception {
        TestRun run = new TestRun(BuildTool.GO, Path.of("."), List.of("test", "./..."), List.of(), 0L);
        TestTreeBuilder.merge(
                run.root(),
                new ParsedSuite(
                        "ex/pkg",
                        List.of(
                                ParsedTest.of("ex/pkg", "TestA", TestStatus.PASSED, 1),
                                ParsedTest.of("ex/pkg", "TestB", TestStatus.PASSED, 1))));

        TestRunnerPanel panel = FxTestSupport.callOnFx(() -> {
            TestRunnerPanel p = new TestRunnerPanel();
            p.startRun("test ./...");
            return p;
        });
        ToggleButton follow = FxTestSupport.field(panel, "followToggle");
        assertTrue(follow.isSelected(), "a fresh run tracks by default");

        // A live update with a frontier node keeps tracking on and must not throw.
        TestNode frontier = run.root().childById("ex/pkg").childById("ex/pkg#TestB");
        FxTestSupport.runOnFx(() -> panel.update(run, frontier));
        assertTrue(follow.isSelected());

        // Selecting a row means "I'm reading this" → tracking stops so the view isn't yanked away.
        TreeView<TestNode> tree = FxTestSupport.field(panel, "tree");
        TreeItem<TestNode> first =
                tree.getRoot().getChildren().get(0).getChildren().get(0);
        FxTestSupport.runOnFx(() -> tree.getSelectionModel().select(first));
        assertFalse(follow.isSelected(), "user selection disables tracking");

        // A new run re-enables it.
        FxTestSupport.runOnFx(() -> panel.startRun("test ./..."));
        assertTrue(follow.isSelected(), "a new run re-enables tracking");
    }

    /**
     * The filter's whole point on a big run: hiding passed tests must take their all-passing class rows with
     * them, or a single failure among thousands is no easier to find. Also pins that the chips are the
     * filter control (clicking one re-renders) and that a live-run update keeps the narrowed view.
     */
    @Test
    @SuppressWarnings("unchecked")
    void hidingPassedTestsAlsoHidesTheirAllPassingClasses() throws Exception {
        TestRun run = new TestRun(BuildTool.MAVEN, Path.of("."), List.of("test"), List.of(), 0L);
        TestTreeBuilder.merge(
                run.root(),
                new ParsedSuite(
                        "com.x.GreenTest",
                        List.of(
                                ParsedTest.of("com.x.GreenTest", "a", TestStatus.PASSED, 1),
                                ParsedTest.of("com.x.GreenTest", "b", TestStatus.PASSED, 1))));
        TestTreeBuilder.merge(
                run.root(),
                new ParsedSuite(
                        "com.x.RedTest",
                        List.of(
                                ParsedTest.of("com.x.RedTest", "ok", TestStatus.PASSED, 1),
                                ParsedTest.of("com.x.RedTest", "boom", TestStatus.FAILED, 1))));

        TestRunnerPanel panel = FxTestSupport.callOnFx(() -> {
            TestRunnerPanel p = new TestRunnerPanel();
            p.startRun("test");
            p.update(run);
            return p;
        });
        TreeView<TestNode> tree = FxTestSupport.field(panel, "tree");
        assertEquals(2, tree.getRoot().getChildren().size(), "both classes show unfiltered");

        ToggleButton passedChip = FxTestSupport.field(panel, "passedChip");
        FxTestSupport.runOnFx(passedChip::fire); // a click: ToggleButton.fire() flips it and fires the action
        assertFalse(passedChip.isSelected());
        assertEquals(1, tree.getRoot().getChildren().size(), "the all-passing class is gone, not left empty");
        TreeItem<TestNode> red = tree.getRoot().getChildren().get(0);
        assertEquals("com.x.RedTest", red.getValue().displayName());
        assertEquals(1, red.getChildren().size(), "only the failing test remains in the surviving class");
        assertEquals("boom", red.getChildren().get(0).getValue().displayName());

        // A live update re-applies the filter rather than re-showing everything.
        FxTestSupport.runOnFx(() -> panel.update(run));
        assertEquals(1, tree.getRoot().getChildren().size());

        // Turning the chip back on restores the hidden class in model order.
        FxTestSupport.runOnFx(passedChip::fire);
        assertTrue(passedChip.isSelected());
        assertEquals(2, tree.getRoot().getChildren().size());
        assertEquals(
                "com.x.GreenTest",
                tree.getRoot().getChildren().get(0).getValue().displayName());
    }

    /** The name filter narrows by test or class, and clearing it restores the tree. */
    @Test
    @SuppressWarnings("unchecked")
    void theNameFilterNarrowsTheTreeAndIsReversible() throws Exception {
        TestRun run = new TestRun(BuildTool.MAVEN, Path.of("."), List.of("test"), List.of(), 0L);
        TestTreeBuilder.merge(
                run.root(),
                new ParsedSuite(
                        "com.x.AlphaTest", List.of(ParsedTest.of("com.x.AlphaTest", "one", TestStatus.PASSED, 1))));
        TestTreeBuilder.merge(
                run.root(),
                new ParsedSuite(
                        "com.x.BetaTest", List.of(ParsedTest.of("com.x.BetaTest", "two", TestStatus.PASSED, 1))));

        TestRunnerPanel panel = FxTestSupport.callOnFx(() -> {
            TestRunnerPanel p = new TestRunnerPanel();
            p.startRun("test");
            p.update(run);
            return p;
        });
        TreeView<TestNode> tree = FxTestSupport.field(panel, "tree");
        TextField field = FxTestSupport.field(panel, "filterField");

        FxTestSupport.runOnFx(() -> field.setText("beta"));
        assertEquals(1, tree.getRoot().getChildren().size(), "typing a class name keeps only that class");
        assertEquals(
                "com.x.BetaTest", tree.getRoot().getChildren().get(0).getValue().displayName());

        FxTestSupport.runOnFx(() -> field.setText("no-such-test"));
        assertEquals(0, tree.getRoot().getChildren().size(), "no matches → an empty tree, not a stale one");

        FxTestSupport.runOnFx(() -> field.clear());
        assertEquals(2, tree.getRoot().getChildren().size());
    }

    /** {@code test.showOnlyFailed} / {@code test.showAllTests} drive the same chips the user clicks. */
    @Test
    @SuppressWarnings("unchecked")
    void showOnlyFailedAndClearFilterRoundTrip() throws Exception {
        TestRun run = new TestRun(BuildTool.MAVEN, Path.of("."), List.of("test"), List.of(), 0L);
        TestTreeBuilder.merge(
                run.root(),
                new ParsedSuite(
                        "com.x.MixedTest",
                        List.of(
                                ParsedTest.of("com.x.MixedTest", "ok", TestStatus.PASSED, 1),
                                ParsedTest.of("com.x.MixedTest", "skipped", TestStatus.SKIPPED, 0),
                                ParsedTest.of("com.x.MixedTest", "bad", TestStatus.FAILED, 1))));

        TestRunnerPanel panel = FxTestSupport.callOnFx(() -> {
            TestRunnerPanel p = new TestRunnerPanel();
            p.startRun("test");
            p.update(run);
            p.showOnlyFailed();
            return p;
        });
        TreeView<TestNode> tree = FxTestSupport.field(panel, "tree");
        assertEquals(1, tree.getRoot().getChildren().size());
        assertEquals(1, tree.getRoot().getChildren().get(0).getChildren().size(), "only the failing test");
        assertEquals(
                "bad",
                tree.getRoot()
                        .getChildren()
                        .get(0)
                        .getChildren()
                        .get(0)
                        .getValue()
                        .displayName());

        FxTestSupport.runOnFx(panel::clearFilter);
        assertEquals(3, tree.getRoot().getChildren().get(0).getChildren().size(), "all three are back");
        assertFalse(FxTestSupport.<TestFilter>callOnFx(panel::filter).isActive());
    }

    /**
     * Opening the window focuses the filter field and selects nothing — selecting row 0 (what it used to do)
     * trips the "the user is reading a row" listener and would silently stop run tracking.
     */
    @Test
    @SuppressWarnings("unchecked")
    void focusFirstItemDoesNotDisableRunTracking() throws Exception {
        TestRun run = new TestRun(BuildTool.MAVEN, Path.of("."), List.of("test"), List.of(), 0L);
        TestTreeBuilder.merge(
                run.root(), new ParsedSuite("pkg", List.of(ParsedTest.of("pkg", "TestA", TestStatus.PASSED, 1))));
        TestRunnerPanel panel = FxTestSupport.callOnFx(() -> {
            TestRunnerPanel p = new TestRunnerPanel();
            p.startRun("test");
            p.update(run);
            p.focusFirstItem();
            return p;
        });
        ToggleButton follow = FxTestSupport.field(panel, "followToggle");
        TreeView<TestNode> tree = FxTestSupport.field(panel, "tree");
        assertTrue(tree.getSelectionModel().isEmpty(), "nothing is selected on open");
        assertTrue(follow.isSelected(), "tracking survives opening the window mid-run");
    }
}
