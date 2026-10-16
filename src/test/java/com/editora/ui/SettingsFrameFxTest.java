package com.editora.ui;

import java.util.List;
import java.util.Set;

import javafx.scene.Node;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Settings window's frame: how it opens, what a command opening it on a page shows, the sidebar. */
@Tag("fx")
class SettingsFrameFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    static Object category(ListView<Object> sidebar, String name) {
        return sidebar.getItems().stream()
                .filter(i -> i instanceof Enum<?> e
                        && e.name().equals(name)
                        && !e.getDeclaringClass().getSimpleName().equals("Group"))
                .findFirst()
                .orElseThrow();
    }

    /** The rows of the page on show that the search filter leaves visible. */
    static int visibleRowsOnPage(SettingsWindow window) {
        ScrollPane scroll = FxTestSupport.field(window, "contentScroll");
        Node page = scroll.getContent();
        List<Object> rows = FxTestSupport.field(window, "rows");
        int n = 0;
        for (Object row : rows) {
            Node node = (Node) FxTestSupport.call(row, "node", new Class<?>[0]);
            boolean under = false;
            for (Node p = node; p != null; p = p.getParent()) {
                under |= p == page;
            }
            if (under && node.isVisible()) {
                n++;
            }
        }
        return n;
    }

    @Test
    void aCommandOpeningAPageClearsASearchLeftInTheBox() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            SettingsWindow window = fx.controller.settingsWindow;
            Stage owner = FxTestSupport.field(fx.controller, "stage");
            FxTestSupport.runOnFx(() -> window.show(owner));
            Stage stage = FxTestSupport.field(window, "stage");
            TextField search = FxTestSupport.field(window, "searchField");
            ListView<Object> sidebar = FxTestSupport.field(window, "sidebar");
            FxTestSupport.runOnFx(() -> {
                search.setText("font");
                stage.close(); // the title bar / Close button: the search stays in the box
            });
            for (String[] c : List.of(
                    new String[] {"snippets.manage", "SNIPPETS"},
                    new String[] {"sync.setup", "SYNC"},
                    new String[] {"abbrev.manage", "ABBREVIATIONS"})) {
                FxTestSupport.runOnFx(() -> {
                    search.setText("font");
                    stage.close();
                    fx.controller.registry.run(c[0]);
                });
                FxTestSupport.runOnFx(() -> {
                    assertTrue(stage.isShowing(), c[0]);
                    assertEquals("", search.getText(), c[0] + " clears the search");
                    assertSame(
                            category(sidebar, c[1]), sidebar.getSelectionModel().getSelectedItem(), c[0]);
                    Set<Object> hidden = FxTestSupport.field(window, "searchHiddenCats");
                    assertTrue(hidden.isEmpty(), c[0] + ": no page greyed out");
                    assertTrue(visibleRowsOnPage(window) > 0, c[0] + " shows the page's settings");
                });
            }
            FxTestSupport.runOnFx(stage::hide);
        }
    }

    @Test
    void aPlainReopenKeepsTheSearchSelectedSoTypingReplacesIt() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            SettingsWindow window = fx.controller.settingsWindow;
            Stage owner = FxTestSupport.field(fx.controller, "stage");
            FxTestSupport.runOnFx(() -> window.show(owner));
            Stage stage = FxTestSupport.field(window, "stage");
            TextField search = FxTestSupport.field(window, "searchField");
            FxTestSupport.runOnFx(() -> {
                search.setText("font");
                stage.close();
                window.show(owner);
            });
            FxTestSupport.runOnFx(() -> {
                assertEquals("font", search.getText());
                assertEquals("font", search.getSelectedText(), "the kept search is selected");
                assertSame(search, stage.getScene().getFocusOwner(), "a reopen focuses the search, not nothing");
                stage.hide();
            });
        }
    }

    /** Whether the sidebar's selected row is inside its viewport, once the deferred scroll has run. */
    static boolean selectedRowInView(ListView<Object> sidebar) {
        int i = sidebar.getSelectionModel().getSelectedIndex();
        sidebar.applyCss();
        sidebar.layout(); // what the next pulse would draw
        var flow = (javafx.scene.control.skin.VirtualFlow<?>) sidebar.lookup(".virtual-flow");
        return flow.getFirstVisibleCell().getIndex() <= i
                && i <= flow.getLastVisibleCell().getIndex();
    }

    static void search(TextField field, String q) throws Exception {
        FxTestSupport.runOnFx(() -> field.setText(q));
        FxTestSupport.drainFx(); // the sidebar reveal is deferred a pulse
        FxTestSupport.drainFx();
    }

    @Test
    void searchOpensThePageWithTheBestHitAndShowsItsSidebarRow() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            SettingsWindow window = fx.controller.settingsWindow;
            FxTestSupport.runOnFx(() -> window.show(FxTestSupport.<Stage>field(fx.controller, "stage")));
            Stage stage = FxTestSupport.field(window, "stage");
            TextField search = FxTestSupport.field(window, "searchField");
            ListView<Object> sidebar = FxTestSupport.field(window, "sidebar");
            FxTestSupport.runOnFx(() -> sidebar.getSelectionModel().select(category(sidebar, "ADVANCED")));
            // "wrap" used to open Workspace (its Trusted Folders keyword "wrapper"); "line numbers" and
            // "minimap" opened Interface (a Simple-mode description) — both only because they come first.
            for (String[] c : List.of(
                    new String[] {"wrap", "EDITOR"},
                    new String[] {"line numbers", "EDITOR"},
                    new String[] {"minimap", "EDITOR"},
                    new String[] {"zoom", "APPEARANCE"},
                    new String[] {"blame", "GIT"},
                    new String[] {"mcp", "MCP"})) {
                search(search, "");
                FxTestSupport.runOnFx(() -> sidebar.getSelectionModel().select(category(sidebar, "ADVANCED")));
                search(search, c[0]);
                FxTestSupport.runOnFx(() -> {
                    assertSame(
                            category(sidebar, c[1]), sidebar.getSelectionModel().getSelectedItem(), c[0]);
                    assertTrue(selectedRowInView(sidebar), c[0] + ": the selected page's row is in view");
                });
            }
            FxTestSupport.runOnFx(() -> sidebar.getSelectionModel().select(category(sidebar, "APPEARANCE")));
            search(search, "");
            FxTestSupport.runOnFx(() -> window.showSync(FxTestSupport.<Stage>field(fx.controller, "stage")));
            FxTestSupport.drainFx();
            FxTestSupport.drainFx();
            FxTestSupport.runOnFx(() -> assertTrue(selectedRowInView(sidebar), "Sync: Set Up shows the Sync row"));
            search(search, "vim");
            FxTestSupport.runOnFx(() -> {
                javafx.scene.control.Label empty = FxTestSupport.field(window, "searchEmpty");
                assertTrue(empty.isVisible(), "no Vim keymap exists, so nothing claims to match it");
                stage.hide();
            });
        }
    }

    /** The pages a search for {@code q} lights up in the sidebar. */
    static Set<Object> hits(SettingsWindow window, TextField search, String q) throws Exception {
        search(search, q);
        return FxTestSupport.callOnFx(() -> {
            ListView<Object> sidebar = FxTestSupport.field(window, "sidebar");
            Set<Object> hidden = FxTestSupport.field(window, "searchHiddenCats");
            Set<Object> out = new java.util.LinkedHashSet<>();
            for (Object item : sidebar.getItems()) {
                if (item.getClass().getSimpleName().equals("Category") && !hidden.contains(item)) {
                    out.add(((Enum<?>) item).name());
                }
            }
            return out;
        });
    }

    @Test
    void whatASearchFindsDoesNotDependOnThePagesOpenedBeforeIt() throws Exception {
        List<String> queries = List.of("Duplicate Line", "Java Class", "Edit: Cut", "Time", "Emacs");
        List<Set<Object>> fresh = new java.util.ArrayList<>();
        try (var fx = FxWindowFixture.create()) {
            SettingsWindow window = fx.controller.settingsWindow;
            FxTestSupport.runOnFx(() -> window.show(FxTestSupport.<Stage>field(fx.controller, "stage")));
            TextField search = FxTestSupport.field(window, "searchField");
            for (String q : queries) {
                fresh.add(hits(window, search, q));
            }
            // A command name is found through the Keymaps list, and carried into its filter.
            search(search, "Duplicate Line");
            TextField shortcutFilter = FxTestSupport.field(window, "shortcutFilter");
            FxTestSupport.runOnFx(() -> assertEquals("Duplicate Line", shortcutFilter.getText()));
            search(search, "");
            FxTestSupport.runOnFx(() -> assertEquals("", shortcutFilter.getText(), "clearing the search clears it"));
            assertTrue(fresh.get(0).contains("KEYMAPS"), "a command name finds the Keymaps page");
            assertTrue(fresh.get(4).contains("KEYMAPS"), "a keymap's name finds the Keymap row");
            FxTestSupport.runOnFx(
                    () -> FxTestSupport.<Stage>field(window, "stage").hide());
        }
        try (var fx = FxWindowFixture.create()) {
            SettingsWindow window = fx.controller.settingsWindow;
            FxTestSupport.runOnFx(() -> window.show(FxTestSupport.<Stage>field(fx.controller, "stage")));
            ListView<Object> sidebar = FxTestSupport.field(window, "sidebar");
            for (String page : List.of("KEYMAPS", "TEMPLATES", "TOOLBAR", "SNIPPETS")) {
                FxTestSupport.runOnFx(() -> sidebar.getSelectionModel().select(category(sidebar, page)));
                FxTestSupport.drainFx();
            }
            TextField search = FxTestSupport.field(window, "searchField");
            for (int i = 0; i < queries.size(); i++) {
                assertEquals(
                        fresh.get(i), hits(window, search, queries.get(i)), queries.get(i) + " after visiting pages");
            }
            FxTestSupport.runOnFx(
                    () -> FxTestSupport.<Stage>field(window, "stage").hide());
        }
    }

    @Test
    void theKeyboardReachesTheSearchFromAnywhere() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            SettingsWindow window = fx.controller.settingsWindow;
            FxTestSupport.runOnFx(() -> window.show(FxTestSupport.<Stage>field(fx.controller, "stage")));
            Stage stage = FxTestSupport.field(window, "stage");
            TextField search = FxTestSupport.field(window, "searchField");
            ListView<Object> sidebar = FxTestSupport.field(window, "sidebar");
            FxTestSupport.runOnFx(() -> {
                sidebar.requestFocus();
                javafx.event.Event.fireEvent(
                        sidebar,
                        new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F, false, !isMac(), false, isMac()));
                assertSame(search, stage.getScene().getFocusOwner(), "Ctrl/Cmd+F focuses the search");
                javafx.event.Event.fireEvent(
                        search, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.DOWN, false, false, false, false));
                assertSame(sidebar, stage.getScene().getFocusOwner(), "Down goes on to the pages");
                javafx.event.Event.fireEvent(
                        sidebar,
                        new KeyEvent(KeyEvent.KEY_TYPED, "g", "", KeyCode.UNDEFINED, false, false, false, false));
                assertSame(search, stage.getScene().getFocusOwner(), "a letter typed on the sidebar searches");
                assertEquals("g", search.getText());
                stage.hide();
            });
        }
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("mac");
    }
}
