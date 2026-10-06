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
        return FxTestSupport.field(FxTestSupport.field(fx.controller, "chrome"), "searchEverywherePopup");
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

    private List<String> paletteCommandTitles(String query) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            CommandPalette palette = FxTestSupport.field(fx.controller, "palette");
            FxTestSupport.call(palette, "filter", new Class<?>[] {String.class}, query);
            javafx.collections.ObservableList<com.editora.command.Command> commands =
                    FxTestSupport.field(palette, "items");
            return commands.stream().map(com.editora.command.Command::title).toList();
        });
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
    void commandRowsUseTheExactCommandPaletteOrder() throws Exception {
        for (String query : List.of("", "toggle")) {
            FxTestSupport.runOnFxUnchecked(() -> popup().show(""));
            type(query.isEmpty() ? "" : ">" + query);
            List<String> searchEverywhere = items().stream().map(Item::label).toList();
            assertEquals(
                    paletteCommandTitles(query),
                    searchEverywhere,
                    "Search Everywhere command order must match the Command Palette for query '" + query + "'");
            hide();
        }
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
        // The keystroke itself: Down in the query field (the picker's cursor movement lives in PickerKeys).
        FxTestSupport.runOnFxUnchecked(() -> FxTestSupport.<javafx.scene.control.TextField>field(popup(), "input")
                .fireEvent(new javafx.scene.input.KeyEvent(
                        javafx.scene.input.KeyEvent.KEY_PRESSED,
                        "",
                        "",
                        javafx.scene.input.KeyCode.DOWN,
                        false,
                        false,
                        false,
                        false)));
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

    /**
     * The fixture window has no project, so there is no index to build. The popup used to refresh a
     * non-`>` query only through the index callback, which that state dropped: the list kept the
     * empty-query rows — every command, cursor on the first — and Enter ran it.
     */
    @Test
    void anUnscopedQueryFiltersWithoutAProject() throws Exception {
        FxTestSupport.runOnFxUnchecked(() -> popup().show(""));
        type("");
        int all = items().size();
        type("undo");
        List<Item> hits = items();
        assertFalse(hits.isEmpty());
        assertTrue(hits.size() < all, "typing narrows the list: " + hits.size() + " of " + all);
        assertEquals(paletteCommandTitles("undo").get(0), hits.get(0).label(), "the top row is the best match");
        Object selected = FxTestSupport.callOnFx(
                () -> itemOf(FxTestSupport.<javafx.scene.control.ListView<?>>field(popup(), "list")
                        .getSelectionModel()
                        .getSelectedItem()));
        // The cursor skips grayed rows, so it sits on the first match that can run.
        assertEquals(
                hits.stream().filter(Item::enabled).findFirst().orElse(null),
                selected,
                "Enter would run a match, not the first command of the full list");

        type("zzzzqqqq");
        assertTrue(items().isEmpty(), "nothing matches, so nothing is listed");
        hide();
    }

    /**
     * While the first project walk is in flight the popup answers each query with what needs no corpus, and
     * when the walk lands it refilters for the text in the field — not for the query that started the walk.
     */
    @Test
    void aWalkLandingRefiltersForTheLiveQuery() throws Exception {
        List<Runnable> parked = new java.util.ArrayList<>();
        boolean[] built = {false};
        SearchEverywherePopup local = FxTestSupport.callOnFx(() -> new SearchEverywherePopup(
                FxTestSupport.<OverlayHost>field(fx.controller, "overlayHost"), new SearchEverywherePopup.Ops() {
                    @Override
                    public List<Item> commands(String query) {
                        return List.of(new Item(Kind.COMMAND, "cmd:" + query, "", 1, "c"));
                    }

                    @Override
                    public List<Item> files(String query) {
                        return built[0] ? List.of(new Item(Kind.FILE, "file:" + query, "", 1, "f")) : List.of();
                    }

                    @Override
                    public List<Item> symbols(String query) {
                        return List.of();
                    }

                    @Override
                    public void ensureIndex(Runnable then) {
                        if (built[0]) {
                            then.run();
                        } else {
                            parked.add(then);
                        }
                    }

                    @Override
                    public void choose(Item item) {}

                    @Override
                    public String disabledReason(Item item) {
                        return null;
                    }

                    @Override
                    public void openDocs(Item item) {}
                }));
        java.util.function.Supplier<List<String>> labels = () -> {
            javafx.collections.ObservableList<Object> list = FxTestSupport.field(local, "rows");
            return list.stream()
                    .map(SearchEverywhereFxTest::itemOf)
                    .filter(java.util.Objects::nonNull)
                    .map(Item::label)
                    .toList();
        };
        java.util.function.Consumer<String> typeLocal = q -> {
            FxTestSupport.<javafx.scene.control.TextField>field(local, "input").setText(q);
            FxTestSupport.invoke(local, "refresh");
        };
        try {
            FxTestSupport.runOnFxUnchecked(() -> {
                local.show("");
                typeLocal.accept("ma");
            });
            assertEquals(List.of("cmd:ma"), FxTestSupport.callOnFx(labels::get), "commands show during the walk");
            FxTestSupport.runOnFxUnchecked(() -> typeLocal.accept("main"));
            assertEquals(List.of("cmd:main"), FxTestSupport.callOnFx(labels::get));
            assertEquals(1, parked.size(), "one callback waits on the walk, however many queries were typed");

            FxTestSupport.runOnFxUnchecked(() -> {
                built[0] = true;
                parked.get(0).run(); // the walk lands
            });
            assertEquals(
                    List.of("cmd:main", "file:main"),
                    FxTestSupport.callOnFx(labels::get),
                    "the landing answers the field's text, not the query that started the walk");
        } finally {
            hide();
        }
    }
}
