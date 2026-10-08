package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.WindowEvent;

import com.editora.command.KeymapManager;
import com.editora.config.ConfigManager;
import com.editora.config.SharedConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tool-window manager on its own, without a window around it: the keyboard commands, stripe order, the
 * stripe button's menu, and reopening a set of windows as Zen mode does on the way out.
 */
@Tag("fx")
class ToolWindowManagerFxTest {

    @TempDir
    Path dir;

    private SharedConfig shared;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void tearDown() {
        if (shared != null) {
            shared.shutdown();
        }
    }

    private record Rig(ToolWindowManager manager, ConfigManager config, Scene scene) {
        void layout() {
            scene.getRoot().applyCss();
            scene.getRoot().layout();
        }

        SplitPane hSplit() {
            return FxTestSupport.field(manager, "hSplit");
        }

        SplitPane vSplit() {
            return FxTestSupport.field(manager, "vSplit");
        }

        ToolWindow add(String id, ToolWindow.Side side) {
            ToolWindow tw = window(id, side);
            manager.register(tw);
            return tw;
        }

        void open(ToolWindow tw) {
            manager.open(tw);
            layout();
        }

        Button button(ToolWindow tw) {
            java.util.Map<ToolWindow, Button> buttons = FxTestSupport.field(manager, "stripeButtons");
            return buttons.get(tw);
        }
    }

    private static ToolWindow window(String id, ToolWindow.Side side) {
        return new ToolWindow(id, "Title " + id, side, () -> new Label("i"), new VBox(new Label(id)), "tool." + id);
    }

    /** To be called on the FX thread. */
    private Rig rig() {
        shared = new SharedConfig(dir, false);
        shared.load();
        ConfigManager config = new ConfigManager(shared);
        BorderPane workspace = new BorderPane();
        Region editor = new Region();
        editor.setMinSize(50, 50);
        ToolWindowManager manager = new ToolWindowManager(workspace, editor, config, new KeymapManager());
        Scene scene = new Scene(workspace, 1200, 800);
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        return new Rig(manager, config, scene);
    }

    @Test
    void theBottomResizeKeyGrowsABottomWindowAndShrinksItWithAPrefixArgument() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig rig = rig();
            ToolWindow right = rig.add("right", ToolWindow.Side.RIGHT);
            ToolWindow bottom = rig.add("bottom", ToolWindow.Side.BOTTOM);
            List<String> said = new ArrayList<>();

            rig.manager().keyboardResizeBottom(said::add);
            assertEquals(List.of(tr("status.toolwindow.noResizeTarget")), said, "nothing is open");

            rig.open(right);
            said.clear();
            rig.manager().keyboardResizeBottom(said::add);
            assertEquals(List.of(tr("status.toolwindow.noResizeTarget")), said, "the open window is not at the bottom");

            rig.manager().close(right);
            rig.open(bottom);
            rig.vSplit().setDividerPosition(0, 0.6);
            rig.layout();
            said.clear();
            rig.manager().keyboardResizeBottom(said::add);
            assertEquals(List.of(tr("status.toolwindow.larger", bottom.getTitle())), said);
            assertEquals(0.55, rig.vSplit().getDividers().get(0).getPosition(), 0.001);

            rig.manager().setKeyboardPrefixArgument(4); // C-u
            said.clear();
            rig.manager().keyboardResizeBottom(said::add);
            assertEquals(List.of(tr("status.toolwindow.smaller", bottom.getTitle())), said);
            assertEquals(0.6, rig.vSplit().getDividers().get(0).getPosition(), 0.001);
            rig.manager().setKeyboardPrefixArgument(null);

            assertTrue(rig.manager().isKeyboardCountAware("view.resizeBottomToolWindow"));
            assertFalse(rig.manager().isKeyboardCountAware("view.resizeToolWindowWider"));
            assertEquals(ToolWindow.Side.BOTTOM, rig.manager().sideOf(bottom));
        });
    }

    @Test
    void theSideResizeKeysPointTowardsTheEditorOnEitherSide() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig rig = rig();
            ToolWindow left = rig.add("left", ToolWindow.Side.LEFT);
            ToolWindow right = rig.add("right", ToolWindow.Side.RIGHT);
            ToolWindow bottom = rig.add("bottom", ToolWindow.Side.BOTTOM);
            List<String> said = new ArrayList<>();

            rig.open(bottom);
            rig.manager().keyboardResizeHorizontal(true, said::add);
            assertEquals(List.of(tr("status.toolwindow.noResizeTarget")), said, "a bottom window has no width key");
            rig.manager().close(bottom);

            rig.open(right);
            rig.hSplit().setDividerPosition(0, 0.7);
            rig.layout();
            said.clear();
            rig.manager().keyboardResizeHorizontal(true, said::add); // ">" on the right: wider
            assertEquals(List.of(tr("status.toolwindow.larger", right.getTitle())), said);
            assertEquals(0.65, rig.hSplit().getDividers().get(0).getPosition(), 0.001);
            said.clear();
            rig.manager().keyboardResizeHorizontal(false, said::add);
            assertEquals(List.of(tr("status.toolwindow.smaller", right.getTitle())), said);
            rig.manager().close(right);

            rig.open(left);
            rig.hSplit().setDividerPosition(0, 0.3);
            rig.layout();
            said.clear();
            rig.manager().keyboardResizeHorizontal(true, said::add); // ">" on the left: narrower
            assertEquals(List.of(tr("status.toolwindow.smaller", left.getTitle())), said);
            assertEquals(0.25, rig.hSplit().getDividers().get(0).getPosition(), 0.001);

            // At the limit a key that would change nothing says nothing.
            int steps = 0;
            while (rig.manager().resize(left, false)) {
                assertTrue(++steps < 40, "shrinking stops at the smallest size");
            }
            said.clear();
            rig.manager().keyboardResizeHorizontal(true, said::add);
            assertEquals(List.of(), said);
        });
    }

    @Test
    void theCloseKeySaysWhenThereIsNothingToClose() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig rig = rig();
            ToolWindow right = rig.add("right", ToolWindow.Side.RIGHT);
            List<String> said = new ArrayList<>();
            assertFalse(rig.manager().keyboardClose(said::add));
            assertEquals(List.of(tr("status.toolwindow.noCloseTarget")), said);

            rig.open(right);
            said.clear();
            assertTrue(rig.manager().keyboardClose(said::add));
            assertEquals(List.of(tr("status.toolwindow.closed", right.getTitle())), said);
            assertFalse(rig.manager().isOpen(right));
        });
    }

    @Test
    void aWindowMovesAmongItsOwnSidesButtonsAndTheOrderIsKept() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig rig = rig();
            ToolWindow a = rig.add("a", ToolWindow.Side.RIGHT);
            ToolWindow b = rig.add("b", ToolWindow.Side.RIGHT);
            ToolWindow c = rig.add("c", ToolWindow.Side.RIGHT);
            ToolWindow other = rig.add("other", ToolWindow.Side.BOTTOM);
            for (ToolWindow tw : List.of(a, b, c, other)) {
                rig.manager().setVisible(tw, true);
            }
            assertEquals(List.of(a, b, c), rig.manager().orderedOnSide(ToolWindow.Side.RIGHT));

            assertFalse(rig.manager().canMove(a, -1));
            assertFalse(rig.manager().move(a, -1), "already first");
            assertFalse(rig.manager().move(c, 1), "already last");
            assertFalse(rig.manager().move(other, 1), "alone on its side");
            assertEquals(List.of(a, b, c), rig.manager().orderedOnSide(ToolWindow.Side.RIGHT));

            assertTrue(rig.manager().move(a, 1));
            assertEquals(List.of(b, a, c), rig.manager().orderedOnSide(ToolWindow.Side.RIGHT));
            assertTrue(rig.manager().move(c, -1));
            assertEquals(List.of(b, c, a), rig.manager().orderedOnSide(ToolWindow.Side.RIGHT));

            // The stripe shows the buttons in that order, and the order is what the session stores.
            javafx.scene.layout.Pane stripe = FxTestSupport.field(rig.manager(), "rightStripe");
            assertEquals(List.of(rig.button(b), rig.button(c), rig.button(a)), stripe.getChildren());
            List<String> stored = rig.config().getWorkspaceState().getToolWindowOrder();
            assertTrue(
                    stored.indexOf("b") < stored.indexOf("c") && stored.indexOf("c") < stored.indexOf("a"),
                    stored.toString());
        });
    }

    @Test
    void aSideStoredInAnUnknownSpellingFallsBackToTheWindowsOwnSide() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig rig = rig();
            ToolWindow tw = rig.add("tw", ToolWindow.Side.RIGHT);
            rig.config().getWorkspaceState().getToolWindowSides().put("tw", "UPSIDE_DOWN");
            assertEquals(ToolWindow.Side.RIGHT, rig.manager().currentSide(tw));

            rig.config().getWorkspaceState().getToolWindowSides().put("tw", "BOTTOM");
            assertEquals(ToolWindow.Side.BOTTOM, rig.manager().currentSide(tw));
            rig.manager().setSide(tw, ToolWindow.Side.BOTTOM); // where it already is
            assertEquals(ToolWindow.Side.BOTTOM, rig.manager().currentSide(tw));
        });
    }

    @Test
    void aStripeButtonsTooltipNamesTheWindowAndItsChordWhenThereIsOne() throws Exception {
        FxTestSupport.runOnFx(() -> {
            shared = new SharedConfig(dir, false);
            shared.load();
            KeymapManager keymap = new KeymapManager();
            keymap.loadNamed("emacs");
            String bound = keymap.bindings().values().stream()
                    .filter(id -> keymap.displayChord(id) != null)
                    .findFirst()
                    .orElseThrow();
            ToolWindowManager manager =
                    new ToolWindowManager(new BorderPane(), new Region(), new ConfigManager(shared), keymap);
            ToolWindow withChord = new ToolWindow(
                    "chord", "With Chord", ToolWindow.Side.RIGHT, () -> new Label("i"), new Label("c"), bound);
            ToolWindow unbound = new ToolWindow(
                    "unbound",
                    "Unbound",
                    ToolWindow.Side.RIGHT,
                    () -> new Label("i"),
                    new Label("c"),
                    "no.such.command");
            ToolWindow noCommand =
                    new ToolWindow("plain", "Plain", ToolWindow.Side.RIGHT, () -> new Label("i"), new Label("c"), null);
            manager.register(withChord);
            manager.register(unbound);
            manager.register(noCommand);
            java.util.Map<ToolWindow, Button> buttons = FxTestSupport.field(manager, "stripeButtons");

            assertEquals(
                    "With Chord (" + keymap.displayChord(bound) + ")",
                    buttons.get(withChord).getTooltip().getText());
            assertEquals("Unbound", buttons.get(unbound).getTooltip().getText());
            assertEquals("Plain", buttons.get(noCommand).getTooltip().getText());

            keymap.loadNamed("cua"); // a live keymap switch: the tooltips are rewritten from it
            manager.refreshTooltips();
            String now = keymap.displayChord(bound);
            assertEquals(
                    now == null ? "With Chord" : "With Chord (" + now + ")",
                    buttons.get(withChord).getTooltip().getText());
        });
    }

    @Test
    void theStripeMenuOffersASplitOnlyWhenTheSideCanTakeASecondWindow() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig rig = rig();
            ToolWindow a = rig.add("a", ToolWindow.Side.RIGHT);
            ToolWindow b = rig.add("b", ToolWindow.Side.RIGHT);
            ToolWindow c = rig.add("c", ToolWindow.Side.RIGHT);
            for (ToolWindow tw : List.of(a, b, c)) {
                rig.manager().setVisible(tw, true);
            }
            ContextMenu menu = rig.button(b).getContextMenu();
            MenuItem split = menu.getItems().get(0);
            MenuItem hide = menu.getItems().get(1);
            assertEquals(tr("toolwindow.openInSplit"), split.getText());
            assertEquals(tr("toolwindow.hide"), hide.getText());

            menu.getOnShowing().handle(new WindowEvent(menu, WindowEvent.WINDOW_SHOWING));
            assertTrue(split.isDisable(), "nothing is open on that side to split with");

            rig.open(a);
            menu.getOnShowing().handle(new WindowEvent(menu, WindowEvent.WINDOW_SHOWING));
            assertFalse(split.isDisable());
            split.fire();
            rig.layout();
            assertTrue(rig.manager().isOpen(a) && rig.manager().isOpen(b), "both share the side");

            menu.getOnShowing().handle(new WindowEvent(menu, WindowEvent.WINDOW_SHOWING));
            assertTrue(split.isDisable(), "it is already there");
            rig.manager().openInSplit(b); // asked again: nothing changes
            assertTrue(rig.manager().isOpen(a) && rig.manager().isOpen(b));

            // A side holds two: a third joins by taking the companion's place, the primary stays.
            assertFalse(rig.manager().canSplitWith(c));
            rig.manager().openInSplit(c);
            rig.layout();
            assertTrue(rig.manager().isOpen(a) && rig.manager().isOpen(c));
            assertFalse(rig.manager().isOpen(b));

            hide.fire();
            assertFalse(rig.manager().isVisible(b));
            javafx.scene.layout.Pane stripe = FxTestSupport.field(rig.manager(), "rightStripe");
            assertFalse(stripe.getChildren().contains(rig.button(b)));
        });
    }

    @Test
    void leavingZenReopensTheWindowsItClosedAndMaximizesTheOneThatWas() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig rig = rig();
            ToolWindow right = rig.add("right", ToolWindow.Side.RIGHT);
            ToolWindow bottom = rig.add("bottom", ToolWindow.Side.BOTTOM);
            ToolWindow hidden = rig.add("hidden", ToolWindow.Side.RIGHT);
            rig.manager().setVisible(hidden, false);
            List<String> events = new ArrayList<>();
            rig.manager().setStateListener((tw, open) -> events.add(tw.getId() + (open ? " opened" : " closed")));

            rig.open(bottom);
            assertTrue(rig.manager().setMaximized(bottom, true));
            assertEquals(List.of("bottom opened"), events);

            List<String> ids = rig.manager().closeAllOpen();
            assertEquals(List.of("bottom"), ids);
            assertTrue(rig.manager().getOpenToolWindows().isEmpty());
            assertEquals(List.of("bottom opened", "bottom closed"), events);

            List<String> reopen = new ArrayList<>(ids);
            reopen.add("right"); // opening another side must not undo the maximize that follows
            reopen.add("hidden"); // hidden meanwhile: stays closed
            reopen.add("no-such-window");
            reopen.add("");
            reopen.add(null);
            rig.manager().openByIds(reopen);
            rig.layout();

            assertTrue(rig.manager().isOpen(right) && rig.manager().isOpen(bottom));
            assertFalse(rig.manager().isOpen(hidden));
            assertTrue(rig.manager().isMaximized(bottom), "it comes back the way it was left");
        });
    }

    @Test
    void aNodeBelongsToTheToolWindowWhoseContentHoldsIt() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig rig = rig();
            ToolWindow right = rig.add("right", ToolWindow.Side.RIGHT);
            ToolWindow lazy = new ToolWindow(
                    "lazy",
                    "Lazy",
                    ToolWindow.Side.BOTTOM,
                    () -> new Label("i"),
                    () -> new VBox(new Label("built late")),
                    null);
            rig.manager().register(lazy);
            rig.open(right);
            Label inside = (Label) ((VBox) right.getContent()).getChildren().get(0);

            assertSame(right, rig.manager().toolWindowOf(inside));
            assertSame(right, rig.manager().toolWindowOf(right.getContent()));
            assertNull(rig.manager().toolWindowOf(new Label("not in any tool window")));
            assertNull(rig.manager().toolWindowOf(rig.scene()), "a target that is not a node");
            assertNull(rig.manager().toolWindowOf(null));
            assertNull(lazy.contentIfBuilt(), "looking for an owner builds no panel");
        });
    }
}
