package com.editora.ui;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ToolBar;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The toolbar's right-click menu must render at the same size as every other menu in the app.
 *
 * <p>A {@code ContextMenu} is a popup whose CSS parent is the node it was shown from, so the global
 * context-menu rule must prevent the toolbar's deliberately larger font from following the popup out.
 */
@Tag("fx")
class ToolbarMenuFontFxTest {

    private static FxWindowFixture fx;

    @BeforeAll
    static void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        FxTestSupport.runOnFx(() -> {
            Scene scene = FxTestSupport.<Stage>field(fx.controller, "stage").getScene();
            scene.getRoot().resize(1500, 800);
            scene.getRoot().applyCss();
            scene.getRoot().layout();
        });
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    /** The font size of the first labelled item in a menu's rendered skin. */
    private static double itemFontSize(Node skinNode) {
        if (skinNode == null) {
            return -1;
        }
        skinNode.applyCss();
        if (skinNode instanceof javafx.scene.Parent p) {
            p.layout();
        }
        for (Node n : skinNode.lookupAll(".label")) {
            if (n instanceof Label l && l.getText() != null && !l.getText().isBlank()) {
                return l.getFont().getSize();
            }
        }
        return -1;
    }

    /** Shows a throwaway one-item menu from {@code owner} and reports the size its item renders at. */
    private static double sizeOfMenuShownFrom(Node owner) throws Exception {
        ContextMenu m = FxTestSupport.callOnFx(() -> {
            ContextMenu cm = new ContextMenu();
            cm.getItems().add(new MenuItem("Customize Toolbar…"));
            cm.show(owner, 100, 100);
            return cm;
        });
        try {
            return FxTestSupport.callOnFx(
                    () -> itemFontSize(m.getSkin() == null ? null : m.getSkin().getNode()));
        } finally {
            FxTestSupport.runOnFx(m::hide);
        }
    }

    @Test
    void theToolbarMenuIsNotBlownUpByTheToolbarsOwnFontSize() throws Exception {
        ToolBar bar = FxTestSupport.callOnFx(() -> FxTestSupport.<ToolBar>field(fx.controller, "toolBar"));
        Node outsideTheBar = FxTestSupport.callOnFx(() ->
                FxTestSupport.<Stage>field(fx.controller, "stage").getScene().getRoot());

        double elsewhere = sizeOfMenuShownFrom(outsideTheBar);
        double genericToolbarMenu = sizeOfMenuShownFrom(bar);
        assertTrue(elsewhere > 0, "precondition: a menu shown outside the toolbar renders a labelled item");
        assertEquals(
                elsewhere,
                genericToolbarMenu,
                0.01,
                "every context menu should ignore the toolbar's larger owner font");

        // The real menu, built by the coordinator the toolbar's right-click actually goes through.
        Object coordinator = FxTestSupport.field(fx.controller, "toolbarCoordinator");
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                coordinator, "showContextMenu", new Class<?>[] {double.class, double.class}, 100.0, 100.0));
        try {
            double actual = FxTestSupport.callOnFx(() -> {
                for (Window w : Window.getWindows()) {
                    if (w.isShowing() && w instanceof PopupWindow && w.getScene() != null) {
                        double size = itemFontSize(w.getScene().getRoot());
                        if (size > 0) {
                            return size;
                        }
                    }
                }
                return -1.0;
            });
            assertTrue(actual > 0, "the toolbar's context menu should have rendered a labelled item");
            assertEquals(
                    elsewhere,
                    actual,
                    0.01,
                    "the toolbar's right-click menu renders at " + actual + "px while every other menu in the"
                            + " app renders at " + elsewhere + "px — it is inheriting the toolbar's own font"
                            + " size (see the global .context-menu rule in app.css)");
        } finally {
            FxTestSupport.runOnFx(() -> {
                for (Window w : Window.getWindows().stream().toList()) {
                    if (w.isShowing() && w instanceof PopupWindow p) {
                        p.hide();
                    }
                }
            });
        }
    }
}
