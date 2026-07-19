package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import javafx.scene.control.Label;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

import com.editora.build.BuildTool;
import com.editora.test.ParsedSuite;
import com.editora.test.ParsedTest;
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
                new ParsedSuite("ex/pkg", List.of(ParsedTest.of("ex/pkg", "TestA", TestStatus.PASSED, 20))));
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

        // Selecting the failed leaf populates the detail console with its message/stack.
        TreeItem<TestNode> failed = suite.getChildren().stream()
                .filter(i -> i.getValue().status() == TestStatus.FAILED)
                .findFirst()
                .orElseThrow();
        FxTestSupport.runOnFx(() -> tree.getSelectionModel().select(failed));
        CodeArea detail = FxTestSupport.field(panel, "detail");
        assertTrue(detail.getText().contains("boom"), "detail shows the failure message");
        assertTrue(detail.getText().contains("foo_test.go:9"), "detail shows the stack frame");

        // Finishing a run with a failure sets a failed-summary status.
        run.finish(1, 100);
        FxTestSupport.runOnFx(() -> panel.finishRun(run, 1));
        Label status = FxTestSupport.field(panel, "status");
        assertTrue(status.getText() != null && !status.getText().isBlank());
    }
}
