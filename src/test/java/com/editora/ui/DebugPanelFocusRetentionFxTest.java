package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import javafx.scene.Node;
import javafx.stage.Stage;

import com.editora.dap.DapModels;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression: stepping from the Debug panel's single-key shortcuts must keep keyboard focus in the panel.
 * The execution-line reveal ({@code highlightFrame} → {@code openPath}) focuses the editor to show the
 * stopped line; when the user is driving from the panel, that focus must be handed straight back, or every
 * step steals focus to the code and the next key press is lost.
 *
 * <p>Self-guarding: headless focus can't always be granted, so the test skips (JUnit assumption) unless the
 * panel can actually take focus first.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DebugPanelFocusRetentionFxTest {

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

    private boolean focusWithinDebugPanel() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            DebugPanel panel =
                    FxTestSupport.field(FxTestSupport.field(fx.controller, "debugCoordinator"), "debugPanel");
            Stage stage = FxTestSupport.field(fx.controller, "stage");
            Node owner = stage.getScene() == null ? null : stage.getScene().getFocusOwner();
            for (Node n = owner; n != null; n = n.getParent()) {
                if (n == panel) {
                    return true;
                }
            }
            return false;
        });
    }

    @Test
    void steppingFromThePanelKeepsFocusInThePanel() throws Exception {
        Object debug = FxTestSupport.field(fx.controller, "debugCoordinator");
        Path real = fx.configDir.resolve("Focus.java");
        Files.writeString(real, "class Focus {\n  void go() {}\n}\n");

        FxTestSupport.runOnFx(() -> {
            FxTestSupport.call(fx.controller, "openPath", new Class[] {Path.class}, real);
            ToolWindow tw = FxTestSupport.field(fx.controller, "debugToolWindow");
            ToolWindowManager tws = FxTestSupport.field(fx.controller, "toolWindows");
            tws.setAvailable(tw, true); // buffer-gated; make its stripe/panel available for the test
            tws.open(tw); // opens + moves focus into the panel (focusFirstItem, deferred)
        });

        // The deferred focusFirstItem has run by now (FIFO on the FX queue). If the headless window can't
        // hold focus, there's nothing to regress — skip rather than assert on an ungrantable focus.
        Assumptions.assumeTrue(focusWithinDebugPanel(), "headless window could not focus the Debug panel");

        // The debugger stops in the open file (as a step would). The reveal focuses the editor…
        DapModels.StackFrameInfo frame = new DapModels.StackFrameInfo(1, "Focus.go", real, 1, 0);
        FxTestSupport.runOnFx(
                () -> FxTestSupport.call(debug, "highlightFrame", new Class[] {DapModels.StackFrameInfo.class}, frame));

        // …but the panel-focus restore (Platform.runLater in highlightFrame) has since run, so focus is back.
        assertTrue(focusWithinDebugPanel(), "focus must return to the Debug panel after a step-driven reveal");
    }
}
