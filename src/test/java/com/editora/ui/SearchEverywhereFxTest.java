package com.editora.ui;

import java.util.List;

import javafx.scene.input.KeyCode;
import javafx.scene.robot.Robot;

import com.editora.command.CommandRegistry;
import com.editora.config.ConfigManager;
import com.editora.search.SearchEverywhere;
import com.editora.search.SearchEverywhere.Item;
import com.editora.search.SearchEverywhere.Kind;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Search Everywhere end to end through the real window: the command opens it, typing produces results
 * from the real command registry, and a command-scoped query does not drag in the project index.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchEverywhereFxTest {

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

    // These wrap their reflection rather than declaring `throws`, so they can be used inside the
    // Runnable lambdas runOnFx takes — which cannot throw a checked exception.
    private SearchEverywherePopup popup() {
        return FxTestSupport.field(fx.controller, "searchEverywherePopup");
    }

    private void hide() {
        FxTestSupport.runOnFxUnchecked(() ->
                FxTestSupport.<OverlayHost>field(fx.controller, "overlayHost").hide());
    }

    @SuppressWarnings("unchecked")
    private List<Object> rows() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            javafx.collections.ObservableList<Object> list = FxTestSupport.field(popup(), "rows");
            return new java.util.ArrayList<Object>(list);
        });
    }

    private void type(String query) {
        FxTestSupport.runOnFxUnchecked(() -> {
            javafx.scene.control.TextField input = FxTestSupport.field(popup(), "input");
            input.setText(query);
            FxTestSupport.invoke(popup(), "refresh"); // drive the debounce directly rather than race it
        });
    }

    /** The {@code Item} behind a row, or null for a group header. */
    private static Item itemOf(Object row) {
        return row.getClass().getSimpleName().equals("ItemRow")
                ? (Item) FxTestSupport.call(row, "item", new Class<?>[0])
                : null;
    }

    private List<Item> items() throws Exception {
        return rows().stream()
                .map(SearchEverywhereFxTest::itemOf)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    @Test
    void theCommandOpensThePopup() throws Exception {
        CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
        FxTestSupport.runOnFxUnchecked(() -> registry.run("search.everywhere"));
        assertTrue(popup().isShown());
        hide();
    }

    @Test
    void commandShiftEOpensThePopupWithTheEmacsKeymapOnMac() throws Exception {
        if (!com.editora.command.KeymapManager.isMac()) {
            return;
        }
        ConfigManager config = FxTestSupport.field(fx.controller, "config");
        String original = config.getSettings().getKeymap();
        try {
            FxTestSupport.runOnFxUnchecked(() -> {
                config.getSettings().setKeymap("emacs");
                fx.windowManager.reloadSharedKeymap();
                javafx.stage.Stage stage = FxTestSupport.field(fx.controller, "stage");
                stage.requestFocus();
                Robot robot = new Robot();
                robot.keyPress(KeyCode.COMMAND);
                robot.keyPress(KeyCode.SHIFT);
                robot.keyPress(KeyCode.E);
                robot.keyRelease(KeyCode.E);
                robot.keyRelease(KeyCode.SHIFT);
                robot.keyRelease(KeyCode.COMMAND);
            });
            FxTestSupport.runOnFx(() -> {});
            assertTrue(popup().isShown(), "Cmd-Shift-E must reach Search Everywhere with the Emacs keymap on macOS");
            FxTestSupport.runOnFxUnchecked(() -> {
                Robot robot = new Robot();
                robot.keyPress(KeyCode.U);
                robot.keyRelease(KeyCode.U);
            });
            FxTestSupport.runOnFx(() -> {});
            String query = FxTestSupport.callOnFx(() -> {
                javafx.scene.control.TextField input = FxTestSupport.field(popup(), "input");
                return input.getText();
            });
            assertEquals("u", query, "the opening Command chord must not swallow the first query character");
            hide();
        } finally {
            FxTestSupport.runOnFxUnchecked(() -> {
                config.getSettings().setKeymap(original);
                fx.windowManager.reloadSharedKeymap();
            });
        }
    }

    @Test
    void aCommandScopedQueryFindsRealCommands() throws Exception {
        FxTestSupport.runOnFxUnchecked(() -> popup().show(""));
        type(">undo");
        assertFalse(rows().isEmpty(), "the real command registry should have matched 'undo'");
        FxTestSupport.runOnFxUnchecked(() -> FxTestSupport.invoke(popup(), "chooseSelected"));
    }

    @Test
    void anEmptyQueryListsEveryCommandAndNothingElse() throws Exception {
        // This is what lets Search Everywhere stand in for the command palette: opening it shows the same
        // browsable list rather than a blank box. Commands only, so no project walk is provoked.
        FxTestSupport.runOnFxUnchecked(() -> popup().show(""));
        type("");
        List<Item> items = items();
        assertFalse(items.isEmpty(), "an empty query is the browse-everything list");
        assertTrue(
                items.stream().allMatch(i -> i.kind() == Kind.COMMAND),
                "an empty query must not reach the file or symbol corpus");
        // Uncapped: with one source in play the per-group cap would otherwise trim this to a handful.
        assertTrue(
                items.size() > SearchEverywhere.DEFAULT_PER_GROUP,
                "the single-source list must not be capped — it would be a worse palette");
        hide();
    }

    @Test
    void aBareFileSigilDoesNotWalkTheProject() throws Exception {
        // A sigil with nothing typed after it is a scope, not an empty query: name the scope, walk nothing.
        FxTestSupport.runOnFxUnchecked(() -> popup().show(""));
        type("#");
        assertTrue(rows().isEmpty(), "a bare sigil must not list anything");
        hide();
    }

    @Test
    void aDisabledCommandIsListedGrayedRatherThanHidden() throws Exception {
        // Hiding it means the user never learns the command exists or what would switch it on, which is
        // the whole reason the palette shows gated commands. LSP is off by default, so its commands are
        // gated in a fresh config dir.
        FxTestSupport.runOnFxUnchecked(() -> popup().show(""));
        type("");
        List<Item> items = items();
        assertTrue(
                items.stream().anyMatch(i -> !i.enabled()),
                "sanity: a fresh window has gated commands (LSP is off by default)");
        // ...and every one of them sorts after the commands the user can actually run.
        int firstDisabled = -1;
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).enabled()) {
                firstDisabled = i;
                break;
            }
        }
        assertTrue(
                items.subList(firstDisabled, items.size()).stream().noneMatch(Item::enabled),
                "a disabled row must never outrank one the user can run");
        hide();
    }

    @Test
    void theCursorNeverRestsOnADisabledRow() throws Exception {
        FxTestSupport.runOnFxUnchecked(() -> popup().show(""));
        type("");
        Item selected = FxTestSupport.callOnFx(() -> {
            javafx.scene.control.ListView<?> list = FxTestSupport.field(popup(), "list");
            Object row = list.getSelectionModel().getSelectedItem();
            return row == null ? null : itemOf(row);
        });
        assertTrue(selected == null || selected.enabled(), "Enter would do nothing on a grayed row");
        hide();
    }

    @Test
    void aDisabledRowRendersGrayedAndTheStylingDoesNotStickToARecycledCell() throws Exception {
        // The styling and the explanation happen in the cell factory, which a headless list never lays
        // out — so drive updateItem directly, or none of this render path is covered at all.
        FxTestSupport.runOnFxUnchecked(() -> popup().show(""));
        type("");
        List<Object> rows = rows();
        Object disabledRow = rows.stream()
                .filter(r -> itemOf(r) != null && !itemOf(r).enabled())
                .findFirst()
                .orElseThrow(() -> new AssertionError("sanity: a fresh window has gated commands"));
        Object enabledRow = rows.stream()
                .filter(r -> itemOf(r) != null && itemOf(r).enabled())
                .findFirst()
                .orElseThrow();
        Class<?> rowType = disabledRow.getClass().getInterfaces()[0];

        boolean[] seen = FxTestSupport.callOnFx(() -> {
            javafx.scene.control.ListView<Object> list = FxTestSupport.field(popup(), "list");
            javafx.scene.control.ListCell<Object> cell = list.getCellFactory().call(list);
            Class<?>[] sig = {rowType, boolean.class};
            FxTestSupport.call(cell, "updateItem", sig, disabledRow, false);
            boolean grayed = cell.getGraphic().getStyleClass().contains("palette-disabled");
            boolean explained = cell.getTooltip() != null;
            // Now reuse the same cell for a row the user CAN run: a recycled cell that kept the previous
            // row's graying or tooltip is the classic cell-factory bug, and it lies about state.
            FxTestSupport.call(cell, "updateItem", sig, enabledRow, false);
            boolean stuckStyle = cell.getGraphic().getStyleClass().contains("palette-disabled");
            boolean stuckTip = cell.getTooltip() != null;
            return new boolean[] {grayed, explained, stuckStyle, stuckTip};
        });
        assertTrue(seen[0], "a command whose feature is off must render grayed");
        assertTrue(seen[1], "a gray row with no explanation reads as a bug rather than a state");
        assertFalse(seen[2], "the graying must not survive onto a recycled cell");
        assertFalse(seen[3], "the previous row's explanation must not survive onto a recycled cell");
        hide();
    }

    /** The index the cursor sits on right now, or -1. */
    private int cursor() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            javafx.scene.control.ListView<?> list = FxTestSupport.field(popup(), "list");
            return list.getSelectionModel().getSelectedIndex();
        });
    }

    @Test
    void theCursorLandsOnTheFirstRunnableRowOnEveryOpen() throws Exception {
        // The first open of a session used to differ from every later one: its populate runs before the
        // ListView has a skin, so the selection could not paint and scrollTo() no-opped, while re-opening
        // with a stale query queued a second populate after layout and quietly fixed itself.
        FxTestSupport.runOnFxUnchecked(() -> popup().show(""));
        FxTestSupport.runOnFx(() -> {}); // let the deferred re-assert run
        int first = cursor();

        FxTestSupport.runOnFxUnchecked(() -> {
            javafx.scene.control.TextField input = FxTestSupport.field(popup(), "input");
            input.setText("undo");
        });
        hide();

        FxTestSupport.runOnFxUnchecked(() -> popup().show(""));
        FxTestSupport.runOnFx(() -> {});
        int second = cursor();

        assertEquals(first, second, "the first open must land the cursor where every later open does");
        assertTrue(first > 0, "the cursor sits on the first runnable row, which is below the group header");
        assertTrue(
                itemOf(rows().get(first)) != null && itemOf(rows().get(first)).enabled());
        hide();
    }

    @Test
    void theDeferredReassertDoesNotOverrideAKeystrokeThatBeatItToIt() throws Exception {
        // reassertCursor runs a pulse after the card is shown. If the user has already moved within that
        // window, putting the cursor back would be the picker fighting them over their own keystroke.
        FxTestSupport.runOnFxUnchecked(() -> popup().show(""));
        FxTestSupport.runOnFx(() -> {});
        int landed = cursor();
        FxTestSupport.runOnFxUnchecked(() -> FxTestSupport.call(popup(), "move", new Class<?>[] {int.class}, 1));
        int moved = cursor();
        assertTrue(moved != landed, "sanity: the move actually moved the cursor");
        FxTestSupport.runOnFxUnchecked(() -> FxTestSupport.invoke(popup(), "reassertCursor"));
        assertEquals(moved, cursor(), "a cursor the user has already moved must be left alone");
        hide();
    }

    @Test
    void theSettingDecidesWhichPickerThePaletteChordOpens() throws Exception {
        CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
        ConfigManager config = FxTestSupport.field(fx.controller, "config");
        CommandPalette palette = FxTestSupport.field(fx.controller, "palette");
        boolean original = config.getSettings().isPaletteUsesSearchEverywhere();
        try {
            FxTestSupport.runOnFxUnchecked(() -> {
                config.getSettings().setPaletteUsesSearchEverywhere(false);
                registry.run("palette.show");
            });
            assertTrue(palette.isShown(), "off by default: the chord still opens the command palette");
            assertFalse(popup().isShown());
            hide();

            FxTestSupport.runOnFxUnchecked(() -> {
                config.getSettings().setPaletteUsesSearchEverywhere(true);
                registry.run("palette.show");
            });
            assertTrue(popup().isShown(), "on: the chord opens Search Everywhere instead");
            assertFalse(palette.isShown());
            hide();
        } finally {
            FxTestSupport.runOnFxUnchecked(() -> config.getSettings().setPaletteUsesSearchEverywhere(original));
        }
    }

    @Test
    void headersAreNeverSelected() throws Exception {
        FxTestSupport.runOnFxUnchecked(() -> popup().show(""));
        type(">toggle");
        Object selected = FxTestSupport.callOnFx(() -> {
            javafx.scene.control.ListView<?> list = FxTestSupport.field(popup(), "list");
            return list.getSelectionModel().getSelectedItem();
        });
        assertTrue(
                selected == null || !selected.getClass().getSimpleName().equals("HeaderRow"),
                "the cursor must skip group headers, which are labels rather than results");
        hide();
    }
}
