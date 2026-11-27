package com.editora.ui;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;

import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import com.editora.mcp.McpBridge;
import com.editora.test.TestNode;
import com.editora.test.TestNodeKind;
import com.editora.test.TestRun;
import com.editora.test.TestStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The window's {@code test_results} and {@code debug_state}: a test run's tree read as the Test Results window holds
 * it, and a Debug panel's stack and expanded variables read as it shows them.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpTestDebugStateFxTest {

    private FxWindowFixture fx;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    @Test
    void theLastRunsTestsAreReadFromItsTree() throws Exception {
        assertFalse(fx.controller.testResults(false).ran(), "no run yet");
        TestRun run = new TestRun(null, Path.of("/r"), List.of("test"), List.of(), 0);
        TestNode suite = run.root().addChild(new TestNode(TestNodeKind.CLASS, "FooTest", "FooTest"));
        TestNode ok = suite.addChild(new TestNode(TestNodeKind.TEST, "FooTest#adds", "adds"));
        ok.setStatus(TestStatus.PASSED);
        TestNode bad = suite.addChild(new TestNode(TestNodeKind.TEST, "FooTest#subtracts", "subtracts"));
        bad.setStatus(TestStatus.FAILED);
        bad.setFailureMessage("expected 1 but was 2");
        bad.setStackTrace("x".repeat(WindowMcpBridge.TEST_TEXT_LIMIT + 10));
        java.lang.reflect.Field current = TestRunCoordinator.class.getDeclaredField("currentRun");
        current.setAccessible(true);
        FxTestSupport.runOnFx(() -> {
            try {
                current.set(fx.controller.testRunCoordinator, run);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        });

        McpBridge.TestSummary failures = fx.controller.testResults(false);
        assertTrue(failures.ran());
        assertEquals(1, failures.tests().size(), "the passing test is left out");
        McpBridge.TestCase t = failures.tests().get(0);
        assertEquals("FooTest", t.suite());
        assertEquals("subtracts", t.name());
        assertEquals("FAILED", t.status());
        assertEquals("expected 1 but was 2", t.message());
        assertTrue(t.stackTrace().length() < WindowMcpBridge.TEST_TEXT_LIMIT + 5, "a long trace is cut");
        assertEquals(2, fx.controller.testResults(true).tests().size());
    }

    @Test
    void aPanelsStackAndExpandedVariablesAreWhatItShows() throws Exception {
        DebugPanel panel = FxTestSupport.callOnFx(() -> new DebugPanel(noopActions()));
        FxTestSupport.runOnFx(() -> {
            panel.setState(DapManager.State.SUSPENDED);
            panel.setCallStack(List.of(
                    new DapModels.StackFrameInfo(3, "main", Path.of("/r/Main.java"), 10, 5, null, "", null),
                    new DapModels.StackFrameInfo(4, "run", null, 0, 0, null, "", null)));
            TreeView<DebugPanel.VarRow> vars = FxTestSupport.field(panel, "variables");
            TreeItem<DebugPanel.VarRow> root = new TreeItem<>();
            TreeItem<DebugPanel.VarRow> locals =
                    new TreeItem<>(new DebugPanel.VarRow("Locals", "", "", 1, 0, DebugPanel.Kind.SCOPE));
            TreeItem<DebugPanel.VarRow> list =
                    new TreeItem<>(new DebugPanel.VarRow("xs", "size=1", "List", 2, 1, DebugPanel.Kind.VARIABLE));
            list.getChildren()
                    .add(new TreeItem<>(new DebugPanel.VarRow("[0]", "7", "int", 0, 2, DebugPanel.Kind.VARIABLE)));
            locals.getChildren().add(list);
            locals.setExpanded(true);
            root.getChildren().add(locals);
            vars.setRoot(root);
        });
        DebugPanel.Snapshot s = FxTestSupport.callOnFx(() -> panel.snapshot(100));
        assertEquals("SUSPENDED", s.state());
        assertEquals(2, s.frames().size());
        assertEquals(
                List.of("Locals", "xs"),
                s.variables().stream().map(DebugPanel.VarLine::name).toList(),
                "xs is not expanded: its element is not read");
        assertEquals(1, s.variables().get(1).depth());
        assertEquals(
                1, FxTestSupport.callOnFx(() -> panel.snapshot(1)).variables().size(), "at most maxRows");
    }

    private static DebugPanel.Actions noopActions() {
        return (DebugPanel.Actions) Proxy.newProxyInstance(
                DebugPanel.Actions.class.getClassLoader(), new Class<?>[] {DebugPanel.Actions.class}, (p, m, a) -> {
                    Class<?> rt = m.getReturnType();
                    if (rt == boolean.class) {
                        return false;
                    }
                    if (rt == int.class) {
                        return 0;
                    }
                    if (rt == long.class) {
                        return 0L;
                    }
                    return null;
                });
    }
}
