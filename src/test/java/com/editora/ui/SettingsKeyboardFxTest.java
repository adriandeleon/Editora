package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Settings window driven from the keyboard alone: the shortcut editor, the sidebar, search, Escape. */
@Tag("fx")
class SettingsKeyboardFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static KeyEvent press(KeyCode code, boolean shift, boolean ctrl, boolean alt) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, ctrl, alt, false);
    }

    private static KeyEvent press(KeyCode code) {
        return press(code, false, false, false);
    }

    private static <T extends Node> List<T> all(Node root, Class<T> type, List<T> out) {
        if (type.isInstance(root)) {
            out.add(type.cast(root));
        }
        if (root instanceof Parent p) {
            p.getChildrenUnmodifiable().forEach(c -> all(c, type, out));
        }
        return out;
    }

    private static Object category(ListView<Object> sidebar, String name) {
        return sidebar.getItems().stream()
                .filter(i -> i instanceof Enum<?> e
                        && e.name().equals(name)
                        && !e.getDeclaringClass().getSimpleName().equals("Group"))
                .findFirst()
                .orElseThrow();
    }

    private static String chordOf(SettingsWindow window, String commandId) {
        SettingsWindow.ShortcutActions actions = FxTestSupport.field(window, "shortcutActions");
        return actions.rows().stream()
                .filter(r -> r.id().equals(commandId))
                .findFirst()
                .orElseThrow()
                .chord();
    }

    /** The chord label the Keymaps list shows for {@code commandId}. */
    private static String listedChord(SettingsWindow window, String commandId) {
        Map<String, HBox> rows = FxTestSupport.field(window, "shortcutRowsById");
        List<Label> labels = all(rows.get(commandId), Label.class, new ArrayList<>());
        return labels.get(1).getText();
    }

    private static void answerNextAlert(ButtonType answer, boolean[] seen) {
        Platform.runLater(() -> {
            for (Window w : Window.getWindows().stream().toList()) {
                if (w.getScene() != null && w.getScene().getRoot() instanceof DialogPane pane) {
                    seen[0] = true;
                    ((Button) pane.lookupButton(answer)).fire();
                }
            }
        });
    }

    private interface Body {
        void run(FxWindowFixture fx, SettingsWindow window, Stage stage) throws Exception;
    }

    /** Opens the real Settings window of a fresh fixture, runs {@code body} on the FX thread, closes it. */
    private static void withSettings(Body body) throws Exception {
        try (var fx = FxWindowFixture.create()) {
            SettingsWindow window = FxTestSupport.field(fx.controller, "settingsWindow");
            Stage[] stage = new Stage[1];
            FxTestSupport.runOnFx(() -> {
                window.show(FxTestSupport.<Stage>field(fx.controller, "stage"));
                stage[0] = FxTestSupport.field(window, "stage");
            });
            FxTestSupport.drainFx();
            try {
                FxTestSupport.runOnFx(() -> {
                    try {
                        body.run(fx, window, stage[0]);
                    } catch (RuntimeException | Error e) {
                        throw e;
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
            } finally {
                FxTestSupport.runOnFx(() -> stage[0].hide());
            }
        }
    }

    /** S4-1 / S2-19: select a row, record a chord and save it without touching the mouse. */
    @Test
    void aShortcutIsReboundFromTheKeyboardAlone() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            SettingsWindow window = FxTestSupport.field(fx.controller, "settingsWindow");
            Stage[] stage = new Stage[1];
            FxTestSupport.runOnFx(() -> {
                window.show(FxTestSupport.<Stage>field(fx.controller, "stage"));
                stage[0] = FxTestSupport.field(window, "stage");
                ListView<Object> sidebar = FxTestSupport.field(window, "sidebar");
                sidebar.getSelectionModel().select(category(sidebar, "KEYMAPS"));
            });
            FxTestSupport.drainFx();
            try {
                VBox list = FxTestSupport.field(window, "shortcutListBox");
                Map<String, HBox> rows = FxTestSupport.field(window, "shortcutRowsById");
                HBox[] second = new HBox[1];
                String[] id = new String[1];
                FxTestSupport.runOnFx(() -> {
                    assertTrue(list.getChildren().size() > 100, "the command rows");
                    long tabStops = list.getChildren().stream()
                            .filter(Node::isFocusTraversable)
                            .count();
                    assertEquals(1, tabStops, "the list is one Tab stop, not one per command");
                    HBox first = (HBox) list.getChildren().get(0);
                    assertTrue(first.isFocusTraversable(), "Tab reaches the list");
                    first.requestFocus();
                    javafx.event.Event.fireEvent(first, press(KeyCode.DOWN));
                    second[0] = (HBox) list.getChildren().get(1);
                    assertSame(second[0], stage[0].getScene().getFocusOwner(), "Down moves to the next row");
                    assertTrue(second[0].isFocusTraversable(), "and takes the Tab stop with it");
                    assertFalse(first.isFocusTraversable());
                    id[0] = rows.entrySet().stream()
                            .filter(e -> e.getValue() == second[0])
                            .findFirst()
                            .orElseThrow()
                            .getKey();
                    assertTrue(all(second[0], Button.class, new ArrayList<>()).isEmpty());
                    javafx.event.Event.fireEvent(second[0], press(KeyCode.ENTER));
                });
                FxTestSupport.drainFx();
                FxTestSupport.runOnFx(() -> {
                    HBox selected = rows.get(id[0]);
                    assertNotSame(second[0], selected, "the list was rebuilt");
                    assertEquals(
                            2,
                            all(selected, Button.class, new ArrayList<>()).size(),
                            "Enter selects the row and reveals Record and Reset");
                    assertSame(selected, stage[0].getScene().getFocusOwner(), "focus stays on the selected row");
                    javafx.event.Event.fireEvent(selected, press(KeyCode.ENTER));
                });
                FxTestSupport.drainFx();
                FxTestSupport.runOnFx(() -> {
                    HBox recording = rows.get(id[0]);
                    TextField capture =
                            all(recording, TextField.class, new ArrayList<>()).get(0);
                    assertSame(capture, stage[0].getScene().getFocusOwner(), "a second Enter starts recording");
                    assertNotNull(capture.getAccessibleText());
                    javafx.event.Event.fireEvent(capture, press(KeyCode.F9, true, true, true));
                    assertEquals("C-M-S-f9", capture.getText());
                    javafx.event.Event.fireEvent(capture, press(KeyCode.ENTER));
                });
                FxTestSupport.drainFx();
                FxTestSupport.runOnFx(() -> {
                    assertNotNull(chordOf(window, id[0]), "Enter saved the recorded chord");
                    assertEquals(chordOf(window, id[0]), listedChord(window, id[0]), "and the list shows it");
                    assertSame(rows.get(id[0]), stage[0].getScene().getFocusOwner(), "focus returns to the row");
                    assertTrue(
                            all(rows.get(id[0]), TextField.class, new ArrayList<>())
                                    .isEmpty(),
                            "recording is over");
                });
            } finally {
                FxTestSupport.runOnFx(() -> stage[0].hide());
            }
        }
    }

    /** S2-12 / S3-4, S2-13 / S3-15: the list and the chord chips follow the live keymap. */
    @Test
    void theShortcutListAndChordChipsFollowTheKeymap() throws Exception {
        withSettings((fx, window, stage) -> {
            ComboBox<String> keymap = FxTestSupport.field(window, "keymapCombo");
            Map<String, Label> chips = FxTestSupport.field(window, "chordChips");
            Label blameChip = chips.get("git.toggleBlame");
            keymap.setValue("emacs");
            String emacsSave = chordOf(window, "file.save");
            assertEquals(emacsSave, listedChord(window, "file.save"));
            String emacsBlame = chordOf(window, "git.toggleBlame");
            assertNotNull(emacsBlame, "the Emacs keymap binds Toggle Blame");
            assertEquals(emacsBlame, blameChip.getText(), "the chip is filled once the pages exist");
            assertTrue(blameChip.isVisible());

            keymap.setValue("cua");
            assertNotEquals(emacsSave, chordOf(window, "file.save"), "the live keymap changed");
            assertEquals(chordOf(window, "file.save"), listedChord(window, "file.save"), "and so did the list");
            String cuaBlame = chordOf(window, "git.toggleBlame");
            assertEquals(cuaBlame == null ? "" : cuaBlame, blameChip.getText());
            assertEquals(cuaBlame != null, blameChip.isVisible());

            // Rebinding from the list reaches the chip on the Git page too.
            SettingsWindow.ShortcutActions actions = FxTestSupport.field(window, "shortcutActions");
            FxTestSupport.call(
                    window,
                    "commitRecording",
                    new Class<?>[] {String.class, String.class},
                    "git.toggleBlame",
                    "C-M-S-F8");
            assertEquals(chordOf(window, "git.toggleBlame"), blameChip.getText());
            assertTrue(blameChip.isVisible());
            assertNotNull(actions);

            // The palette's keymap.select goes through syncKeymapCombo.
            fx.shared.getSettings().setKeymap("emacs");
            FxTestSupport.<EditorSettingsCoordinator>field(fx.controller, "editorSettings")
                    .applyKeymap("emacs");
            assertEquals(emacsSave, listedChord(window, "file.save"), "keymap.select refreshes the list");
        });
    }

    /** S3-16: Reset all shortcuts asks first. */
    @Test
    void resetAllShortcutsAsksBeforeDroppingEveryBinding() throws Exception {
        withSettings((fx, window, stage) -> {
            FxTestSupport.call(
                    window, "commitRecording", new Class<?>[] {String.class, String.class}, "file.save", "C-M-S-F7");
            String custom = chordOf(window, "file.save");
            Map<?, Region> pages = FxTestSupport.field(window, "pages");
            Button resetAll = null;
            for (Region page : pages.values()) {
                for (Button b : all(page, Button.class, new ArrayList<>())) {
                    if (com.editora.i18n.Messages.tr("settings.shortcuts.resetAll")
                            .equals(b.getText())) {
                        resetAll = b;
                    }
                }
            }
            assertNotNull(resetAll);

            boolean[] asked = {false};
            answerNextAlert(ButtonType.CANCEL, asked);
            resetAll.fire();
            assertTrue(asked[0], "a confirmation was shown");
            assertEquals(custom, chordOf(window, "file.save"), "Cancel keeps the custom binding");

            asked[0] = false;
            answerNextAlert(ButtonType.OK, asked);
            resetAll.fire();
            assertTrue(asked[0]);
            assertNotEquals(custom, chordOf(window, "file.save"), "OK resets it");
            assertEquals(chordOf(window, "file.save"), listedChord(window, "file.save"));
        });
    }

    /** S4-2: Shift+Tab and Ctrl+Tab leave the Snippets and Templates body editors. */
    @Test
    void theBodyEditorsCanBeLeftWithTheKeyboard() throws Exception {
        withSettings((fx, window, stage) -> {
            ListView<Object> sidebar = FxTestSupport.field(window, "sidebar");
            ScrollPane scroll = FxTestSupport.field(window, "contentScroll");
            for (String page : List.of("SNIPPETS", "TEMPLATES")) {
                sidebar.getSelectionModel().select(category(sidebar, page));
                stage.getScene().getRoot().applyCss();
                stage.getScene().getRoot().layout();
                CodeArea body = all(scroll.getContent(), CodeArea.class, new ArrayList<>())
                        .get(0);
                for (Node n = body; n != null; n = n.getParent()) {
                    n.setDisable(false); // the form is disabled until a row is selected
                }
                for (boolean shift : new boolean[] {true, false}) {
                    body.requestFocus();
                    assertSame(body, stage.getScene().getFocusOwner());
                    javafx.event.Event.fireEvent(body, press(KeyCode.TAB, shift, !shift, false));
                    assertNotSame(
                            body,
                            stage.getScene().getFocusOwner(),
                            page + ": " + (shift ? "Shift+Tab" : "Ctrl+Tab") + " leaves the body");
                }
            }
            assertNull(SettingsWindow.focusEscape(KeyCode.TAB, false, false, false), "plain Tab still types a tab");
            assertNull(SettingsWindow.focusEscape(KeyCode.A, true, false, false));
            assertEquals(
                    javafx.scene.TraversalDirection.PREVIOUS,
                    SettingsWindow.focusEscape(KeyCode.TAB, true, true, false));
        });
    }

    /** S4-14, S4-3 / S3-12, S4-4, S4-13, S4-15. */
    @Test
    void theSidebarSkipsHeadersAndFilteredPagesAndEscapeLeaves() throws Exception {
        withSettings((fx, window, stage) -> {
            ListView<Object> sidebar = FxTestSupport.field(window, "sidebar");
            ScrollPane scroll = FxTestSupport.field(window, "contentScroll");
            TextField search = FxTestSupport.field(window, "searchField");
            Label empty = FxTestSupport.field(window, "searchEmpty");
            Map<Object, Region> pages = FxTestSupport.field(window, "pages");
            Set<Object> hidden = FxTestSupport.field(window, "searchHiddenCats");
            assertEquals(search.getPromptText(), search.getAccessibleText(), "the search field has a name");

            // Arrow keys never rest on a group header.
            sidebar.requestFocus();
            sidebar.getSelectionModel().select(category(sidebar, "TOOL_WINDOWS"));
            javafx.event.Event.fireEvent(sidebar, press(KeyCode.DOWN));
            assertSame(category(sidebar, "EDITOR"), sidebar.getSelectionModel().getSelectedItem());
            assertSame(pages.get(category(sidebar, "EDITOR")), scroll.getContent());
            javafx.event.Event.fireEvent(sidebar, press(KeyCode.UP));
            assertSame(
                    category(sidebar, "TOOL_WINDOWS"),
                    sidebar.getSelectionModel().getSelectedItem());
            javafx.event.Event.fireEvent(sidebar, press(KeyCode.HOME));
            assertSame(
                    category(sidebar, "APPEARANCE"), sidebar.getSelectionModel().getSelectedItem());
            javafx.event.Event.fireEvent(sidebar, press(KeyCode.UP));
            assertSame(
                    category(sidebar, "APPEARANCE"), sidebar.getSelectionModel().getSelectedItem());
            javafx.event.Event.fireEvent(sidebar, press(KeyCode.END));
            assertTrue(pages.containsKey(sidebar.getSelectionModel().getSelectedItem()));

            // While searching, only pages with a hit can be reached.
            search.setText("minimap");
            assertFalse(hidden.isEmpty());
            for (KeyCode code : List.of(KeyCode.HOME, KeyCode.DOWN, KeyCode.DOWN, KeyCode.DOWN, KeyCode.PAGE_DOWN)) {
                javafx.event.Event.fireEvent(sidebar, press(code));
                Object sel = sidebar.getSelectionModel().getSelectedItem();
                assertTrue(pages.containsKey(sel) && !hidden.contains(sel), code + " landed on " + sel);
            }

            // A page is found by its own sidebar name, and that page is the one opened.
            for (String name : List.of("KEYMAPS", "BUILD_TOOLS", "ADVANCED", "TOOL_WINDOWS", "INTERFACE", "DIAGRAMS")) {
                Object cat = category(sidebar, name);
                search.setText(FxTestSupport.<String>field(cat, "display"));
                assertNotSame(empty, scroll.getContent(), name + ": its own name finds the page");
                assertFalse(hidden.contains(cat), name);
                assertSame(cat, sidebar.getSelectionModel().getSelectedItem(), name + " is opened");
            }
            assertTrue(SettingsWindow.namesPage(" tool w", "Tool Windows"));
            assertFalse(SettingsWindow.namesPage("to", "Tool Windows"), "too short to name a page");
            assertFalse(SettingsWindow.namesPage("windows", "Tool Windows"));

            // Escape: first the query, then the window.
            javafx.event.Event.fireEvent(search, press(KeyCode.ESCAPE));
            assertEquals("", search.getText(), "Escape clears the search");
            assertTrue(stage.isShowing());
            assertTrue(hidden.isEmpty());
            javafx.event.Event.fireEvent(sidebar, press(KeyCode.ESCAPE));
            assertFalse(stage.isShowing(), "Escape with no search closes the window");
        });
    }
}
