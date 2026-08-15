package com.editora.ui;

import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tool-window header's right-click menu — the same three actions its icons offer.
 *
 * <p>Asserted against the built menu rather than by showing it: a popup needs a live scene, and what is
 * worth pinning here has nothing to do with the popup. The labels are driven by the state setters, so the
 * two toggles always name the direction they will actually go — an item reading "Maximize" on an
 * already-maximized window would be a lie about what clicking it does.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ToolWindowHeaderMenuFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private record Rig(ToolWindowPanel panel, int[] closes, int[] maximizes, int[] floats) {
        ContextMenu menu() {
            return FxTestSupport.field(panel, "headerMenu");
        }
    }

    private static Rig rig() {
        int[] closes = new int[1];
        int[] maximizes = new int[1];
        int[] floats = new int[1];
        ToolWindow tw = new ToolWindow(
                "alpha", "Alpha", ToolWindow.Side.LEFT, () -> new Label("i"), new Label("content"), "tool.alpha");
        ToolWindowPanel panel = new ToolWindowPanel(tw, () -> closes[0]++, () -> maximizes[0]++, () -> floats[0]++);
        return new Rig(panel, closes, maximizes, floats);
    }

    @Test
    void theHeaderOffersTheSameThreeActionsAsItsIcons() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = rig();

            assertEquals(3, r.menu().getItems().size());
            assertEquals(tr("toolwindow.float"), r.menu().getItems().get(0).getText());
            assertEquals(tr("toolwindow.maximize"), r.menu().getItems().get(1).getText());
            assertEquals(tr("toolwindow.hide"), r.menu().getItems().get(2).getText());
        });
    }

    /** Every context-menu item in the app carries a leading glyph — these are no exception. */
    @Test
    void everyItemHasAnIcon() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = rig();
            for (var item : r.menu().getItems()) {
                assertNotNull(item.getGraphic(), item.getText() + " has no icon");
            }
        });
    }

    @Test
    void eachItemRunsTheActionItsIconWouldHaveRun() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = rig();

            r.menu().getItems().get(0).fire();
            r.menu().getItems().get(1).fire();
            r.menu().getItems().get(2).fire();

            assertEquals(1, r.floats()[0]);
            assertEquals(1, r.maximizes()[0]);
            assertEquals(1, r.closes()[0]);
        });
    }

    /** The toggles name where they are going, not where they are. */
    @Test
    void theTogglesRelabelWithTheState() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = rig();

            r.panel().setMaximized(true);
            assertEquals(
                    tr("toolwindow.restoreSize"), r.menu().getItems().get(1).getText());
            r.panel().setMaximized(false);
            assertEquals(tr("toolwindow.maximize"), r.menu().getItems().get(1).getText());

            r.panel().setFloating(true);
            assertEquals(tr("toolwindow.dock"), r.menu().getItems().get(0).getText());
            r.panel().setFloating(false);
            assertEquals(tr("toolwindow.float"), r.menu().getItems().get(0).getText());
        });
    }

    /** Maximize is meaningless for a detached stage — the same reason its button hides. */
    @Test
    void maximizeIsHiddenWhileFloating() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = rig();

            r.panel().setFloating(true);
            assertFalse(r.menu().getItems().get(1).isVisible());

            r.panel().setFloating(false);
            assertTrue(r.menu().getItems().get(1).isVisible());
        });
    }

    /** The menu belongs to the header alone — the content below it keeps its own right-click. */
    @Test
    void theMenuIsOnTheHeaderNotTheWholePanel() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = rig();

            assertNotNull(((HBox) r.panel().getTop()).getOnContextMenuRequested());
            assertNull(r.panel().getCenter().getOnContextMenuRequested(), "the content must keep its own menu");
        });
    }
}
