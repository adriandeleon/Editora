package com.editora.ui;

import java.nio.file.Files;

import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.BorderPane;

import com.editora.command.KeymapManager;
import com.editora.config.ConfigManager;
import com.editora.config.SharedConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keyboard resizing semantics for left, right, and bottom tool windows. */
@Tag("fx")
class ToolWindowKeyboardResizeFxTest {

    @BeforeAll
    static void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private record Rig(ToolWindowManager manager, Scene scene, SplitPane horizontal, SplitPane vertical) {
        void layout() {
            scene.getRoot().applyCss();
            scene.getRoot().layout();
        }
    }

    private static Rig rig() throws Exception {
        SharedConfig shared = new SharedConfig(Files.createTempDirectory("editora-tool-resize"), false);
        shared.load();
        BorderPane workspace = new BorderPane();
        ToolWindowManager manager =
                new ToolWindowManager(workspace, new Label("editor"), new ConfigManager(shared), new KeymapManager());
        Scene scene = new Scene(workspace, 1200, 800);
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        return new Rig(manager, scene, FxTestSupport.field(manager, "hSplit"), FxTestSupport.field(manager, "vSplit"));
    }

    private static ToolWindow window(String id, ToolWindow.Side side) {
        return new ToolWindow(id, id, side, () -> new Label("i"), new Label(id), "tool." + id);
    }

    private static void open(Rig rig, ToolWindow window) {
        rig.manager().register(window);
        rig.manager().open(window);
        rig.layout();
    }

    @Test
    void growingAndShrinkingARightWindowMovesItsDividerLeftAndRight() throws Exception {
        Rig rig = rig();
        ToolWindow right = window("right", ToolWindow.Side.RIGHT);
        FxTestSupport.runOnFx(() -> open(rig, right));
        FxTestSupport.runOnFx(() -> {
            rig.horizontal().setDividerPosition(0, 0.70);
            assertTrue(rig.manager().resize(right, true));
            assertEquals(0.65, rig.horizontal().getDividerPositions()[0], 0.001);
            assertTrue(rig.manager().resize(right, false));
            assertEquals(0.70, rig.horizontal().getDividerPositions()[0], 0.001);
        });
    }

    @Test
    void growingAndShrinkingALeftWindowUsesTheOppositeDividerDirection() throws Exception {
        Rig rig = rig();
        ToolWindow left = window("left", ToolWindow.Side.LEFT);
        FxTestSupport.runOnFx(() -> open(rig, left));
        FxTestSupport.runOnFx(() -> {
            rig.horizontal().setDividerPosition(0, 0.30);
            assertTrue(rig.manager().resize(left, true));
            assertEquals(0.35, rig.horizontal().getDividerPositions()[0], 0.001);
            assertTrue(rig.manager().resize(left, false));
            assertEquals(0.30, rig.horizontal().getDividerPositions()[0], 0.001);
        });
    }

    @Test
    void growingAndShrinkingABottomWindowMovesItsDividerUpAndDown() throws Exception {
        Rig rig = rig();
        ToolWindow bottom = window("bottom", ToolWindow.Side.BOTTOM);
        FxTestSupport.runOnFx(() -> open(rig, bottom));
        FxTestSupport.runOnFx(() -> {
            rig.vertical().setDividerPosition(0, 0.70);
            assertTrue(rig.manager().resize(bottom, true));
            assertEquals(0.65, rig.vertical().getDividerPositions()[0], 0.001);
            assertTrue(rig.manager().resize(bottom, false));
            assertEquals(0.70, rig.vertical().getDividerPositions()[0], 0.001);
        });
    }

    @Test
    void resizingRefusesClosedWindows() throws Exception {
        Rig rig = rig();
        ToolWindow closed = window("closed", ToolWindow.Side.RIGHT);
        FxTestSupport.runOnFx(() -> assertFalse(rig.manager().resize(closed, true)));
    }

    @Test
    void closeCommandClosesTheCurrentToolWindow() throws Exception {
        Rig rig = rig();
        ToolWindow right = window("right", ToolWindow.Side.RIGHT);
        FxTestSupport.runOnFx(() -> open(rig, right));
        FxTestSupport.runOnFx(() -> {
            assertTrue(rig.manager().keyboardClose(s -> {}));
            assertFalse(rig.manager().isOpen(right));
        });
    }
}
