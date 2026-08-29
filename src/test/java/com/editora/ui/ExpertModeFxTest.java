package com.editora.ui;

import javafx.scene.Node;
import javafx.scene.control.TabPane;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.HBox;

import com.editora.config.ConfigManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage of Expert mode through the real {@link MainController}. Expert is Zen's near-twin: it
 * hides the toolbar + tab header like Zen, but <em>keeps the status bar</em> (the line-number gutter is kept
 * too — that decision is pinned purely in {@code ChromeTest}). Also pins that Expert is per-window
 * {@code WorkspaceState} (never a shared {@code Settings} mutation) and that the two focus modes are mutually
 * exclusive.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExpertModeFxTest {

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
    void expertHidesToolbarButKeepsStatusBar() throws Exception {
        ToolBar toolBar = FxTestSupport.field(fx.controller, "toolBar");
        StatusBar statusBar = FxTestSupport.field(fx.controller, "statusBar");
        TabPane tabPane = FxTestSupport.field(fx.controller, "tabPane");
        ConfigManager config = FxTestSupport.field(fx.controller, "config");

        assertTrue(FxTestSupport.callOnFx(toolBar::isVisible), "toolbar visible by default");
        assertTrue(FxTestSupport.callOnFx(statusBar::isVisible), "status bar visible by default");

        // Enter Expert → toolbar + tab header hidden, but the status bar is KEPT (the whole point vs Zen).
        FxTestSupport.runOnFx(() -> fx.controller.setExpertMode(true));
        assertFalse(FxTestSupport.callOnFx(toolBar::isVisible), "toolbar hidden in Expert");
        assertFalse(FxTestSupport.callOnFx(toolBar::isManaged), "toolbar unmanaged in Expert");
        assertTrue(
                FxTestSupport.callOnFx(() -> tabPane.getStyleClass().contains("no-tab-header")),
                "tab header collapsed in Expert");
        assertTrue(FxTestSupport.callOnFx(statusBar::isVisible), "status bar KEPT in Expert");
        assertTrue(
                FxTestSupport.callOnFx(() -> config.getWorkspaceState().isExpertMode()),
                "Expert flag set on window state");

        // Leave Expert → toolbar restored.
        FxTestSupport.runOnFx(() -> fx.controller.setExpertMode(false));
        assertTrue(FxTestSupport.callOnFx(toolBar::isVisible), "toolbar restored after Expert");
        assertFalse(FxTestSupport.callOnFx(() -> config.getWorkspaceState().isExpertMode()), "Expert flag cleared");
    }

    /**
     * The toolbar is two containers on one row — the {@code ToolBar} icon cluster and the pinned
     * {@code toolbarTail} (project combo, Open Folder, Recent, badges, Settings). Hiding only the ToolBar
     * left the tail's icons stranded on an otherwise-stripped Expert window, so this asserts the tail's own
     * buttons are <em>effectively</em> invisible (the node and every ancestor), not merely that some
     * particular container was flagged — the property the user sees, whichever node ends up carrying it.
     */
    @Test
    void expertHidesTheFixedToolbarTailToo() throws Exception {
        HBox tail = FxTestSupport.field(fx.controller, "toolbarTail");
        HBox row = FxTestSupport.field(fx.controller, "toolbarRow");
        Node settings = FxTestSupport.field(fx.controller, "settingsButton");
        Node recent = FxTestSupport.field(fx.controller, "recentButton");

        // Guard the guard: an empty tail would make every assertion below vacuously true.
        assertFalse(FxTestSupport.callOnFx(() -> tail.getChildren().isEmpty()), "the tail holds the fixed icons");
        assertTrue(FxTestSupport.callOnFx(() -> effectivelyVisible(settings)), "Settings visible by default");

        FxTestSupport.runOnFx(() -> fx.controller.setExpertMode(true));
        assertFalse(FxTestSupport.callOnFx(() -> effectivelyVisible(settings)), "Settings hidden in Expert");
        assertFalse(FxTestSupport.callOnFx(() -> effectivelyVisible(recent)), "Recent hidden in Expert");
        assertFalse(FxTestSupport.callOnFx(row::isManaged), "the toolbar row reserves no layout space in Expert");

        FxTestSupport.runOnFx(() -> fx.controller.setExpertMode(false));
        assertTrue(FxTestSupport.callOnFx(() -> effectivelyVisible(settings)), "Settings restored after Expert");
    }

    /** A node is only on screen when it and every ancestor is visible — hiding any one of them suffices. */
    private static boolean effectivelyVisible(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (!n.isVisible()) {
                return false;
            }
        }
        return true;
    }

    @Test
    void zenAndExpertAreMutuallyExclusive() throws Exception {
        ConfigManager config = FxTestSupport.field(fx.controller, "config");

        FxTestSupport.runOnFx(() -> fx.controller.setZenMode(true));
        assertTrue(FxTestSupport.callOnFx(() -> config.getWorkspaceState().isZenMode()));

        // Entering Expert turns Zen off.
        FxTestSupport.runOnFx(() -> fx.controller.setExpertMode(true));
        assertTrue(FxTestSupport.callOnFx(() -> config.getWorkspaceState().isExpertMode()), "Expert on");
        assertFalse(FxTestSupport.callOnFx(() -> config.getWorkspaceState().isZenMode()), "Zen turned off");

        // And the reverse: entering Zen turns Expert off.
        FxTestSupport.runOnFx(() -> fx.controller.setZenMode(true));
        assertTrue(FxTestSupport.callOnFx(() -> config.getWorkspaceState().isZenMode()), "Zen on");
        assertFalse(FxTestSupport.callOnFx(() -> config.getWorkspaceState().isExpertMode()), "Expert turned off");

        FxTestSupport.runOnFx(() -> fx.controller.setZenMode(false));
    }
}
