package com.editora.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javafx.geometry.Side;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a right-click row that cannot run says why ({@link RowReasons}): in the status line of the editor window its
 * menu was opened from, or of the editor window that owns that window; and the words it says it with.
 */
@Tag("fx")
class RowReasonsWindowFxTest {

    @BeforeAll
    static void boot() throws Exception {
        EditorFx.boot();
    }

    @Test
    void aMenuSaysWhyInItsOwnWindowOrTheEditorWindowThatOwnsIt() throws Exception {
        List<String> main = new ArrayList<>();
        List<String> other = new ArrayList<>();
        Stage[] stages = new Stage[3];
        try {
            EditorFx.onFx(() -> {
                stages[0] = new Stage();
                RowReasons.sayIn(stages[0], main::add);
                stages[1] = new Stage(); // Settings, a Map window: no status line, owned by the editor window
                stages[1].initOwner(stages[0]);
                stages[2] = new Stage();
                RowReasons.sayIn(stages[2], other::add);

                say(stages[0], "main");
                say(stages[1], "owned");
                say(stages[2], "other");
                assertEquals(List.of("main", "owned"), main);
                assertEquals(List.of("other"), other);
            });
        } finally {
            EditorFx.onFx(() -> {
                for (Stage s : stages) {
                    if (s != null) {
                        s.close();
                    }
                }
            });
        }
    }

    @Test
    void aMenuOfAWindowWithNoEditorWindowAboveItSaysWhyInAShowingEditorWindow() throws Exception {
        List<String> main = new ArrayList<>();
        Stage[] stages = new Stage[2];
        try {
            EditorFx.onFx(() -> {
                stages[0] = new Stage();
                stages[0].setScene(new Scene(new StackPane(), 100, 100));
                RowReasons.sayIn(stages[0], main::add);
                stages[0].show();
                stages[0].requestFocus(); // the focused editor window is the one a stray menu speaks in
                stages[1] = new Stage(); // a window no editor window owns
                say(stages[1], "anywhere");
                say(null, "nowhere");
            });
            assertEquals(List.of("anywhere", "nowhere"), main);
            // With no editor window focused, the first one shown hears it.
            List<String> other = new ArrayList<>();
            Stage[] more = new Stage[2];
            try {
                EditorFx.onFx(() -> {
                    stages[0].hide();
                    more[0] = new Stage();
                    more[0].setScene(new Scene(new StackPane(), 100, 100));
                    RowReasons.sayIn(more[0], other::add);
                    more[1] = new Stage();
                    more[1].setScene(new Scene(new StackPane(), 100, 100));
                    RowReasons.sayIn(more[1], other::add);
                    more[0].show();
                    more[1].show();
                    Consumer<String> say = RowReasons.sayFor(null);
                    assertNotNull(say);
                    say.accept("someone");
                });
                assertEquals(List.of("someone"), other);
            } finally {
                EditorFx.onFx(() -> {
                    for (Stage s : more) {
                        if (s != null) {
                            s.close();
                        }
                    }
                });
            }
        } finally {
            EditorFx.onFx(() -> {
                for (Stage s : stages) {
                    if (s != null) {
                        s.close();
                    }
                }
            });
        }
    }

    @Test
    void aRowInASubmenuSaysWhyInTheWindowTheRootMenuWasOpenedFrom() throws Exception {
        List<String> said = new ArrayList<>();
        Stage[] stage = new Stage[1];
        try {
            EditorFx.onFx(() -> {
                stage[0] = new Stage();
                Button owner = new Button("x");
                stage[0].setScene(new Scene(new StackPane(owner), 200, 200));
                RowReasons.sayIn(stage[0], said::add);
                stage[0].show();
                MenuItem revert = new MenuItem("Revert");
                Menu git = new Menu("Git", null, revert);
                ContextMenu menu = new ContextMenu(new MenuItem("First"), git);
                assertSame(menu, RowReasons.rootMenu(revert));
                assertSame(menu, RowReasons.rootMenu(git));
                assertNull(RowReasons.rootMenu(new MenuItem("loose")));
                menu.show(owner, Side.BOTTOM, 0, 0);
                RowReasons.because(revert, "palette.disabled.needsRepo");
                assertTrue(RowReasons.sayWhy(revert));
                assertFalse(menu.isShowing(), "the whole menu closes");
                assertEquals(
                        List.of(tr("status.commandUnavailable", "Revert", tr("palette.disabled.needsRepo"))), said);
                RowReasons.because(revert, null);
                assertFalse(RowReasons.sayWhy(revert), "a row that can run says nothing");
                MenuItem loose = RowReasons.because(new MenuItem("Loose"), "palette.disabled.needsRepo");
                assertTrue(RowReasons.sayWhy(loose), "a row in no menu is still refused");
                assertEquals(1, said.size(), "but has nowhere to say it");
            });
        } finally {
            EditorFx.onFx(() -> {
                if (stage[0] != null) {
                    stage[0].close();
                }
            });
        }
    }

    @Test
    void theWordsARowIsNamedAndSpokenBy() throws Exception {
        EditorFx.onFx(() -> {
            assertEquals("Cut. Nothing.", RowReasons.spoken("Cut", "Nothing."));
            assertEquals("Nothing.", RowReasons.spoken(null, "Nothing."));
            assertEquals("Nothing.", RowReasons.spoken(" ", "Nothing."));
            assertEquals("Cut", RowReasons.title(new MenuItem("Cut")));
            MenuItem untitled = MenuChords.tag(new MenuItem(), "edit.copy");
            assertEquals(MenuTitles.of(tr("command.edit.copy")), RowReasons.title(untitled));
            assertEquals("", RowReasons.title(new MenuItem()));
            MenuItem none = RowReasons.placeholder(new MenuItem("(none)"));
            assertTrue(RowReasons.isPlaceholder(none));
            assertFalse(RowReasons.isPlaceholder(new MenuItem("x")));
            assertNull(RowReasons.tooltipOf(none), "not shown: no tooltip");
            assertNotNull(RowReasons.when(new MenuItem("y"), false, "palette.disabled.needsRepo"));
        });
    }

    @Test
    void theEdgesOfTheMechanism() throws Exception {
        List<String> said = new ArrayList<>();
        Stage[] stage = new Stage[1];
        try {
            EditorFx.onFx(() -> {
                RowReasons.install(); // a second install changes nothing
                stage[0] = new Stage();
                javafx.scene.control.TextField field = new javafx.scene.control.TextField();
                Button owner = new Button("x");
                stage[0].setScene(new Scene(new javafx.scene.layout.VBox(owner, field), 200, 200));
                RowReasons.sayIn(stage[0], said::add);
                stage[0].show();

                // A row whose reason JavaFX's own state contradicts (enabled behind its back) says nothing.
                MenuItem row = RowReasons.because(new MenuItem("Row"), "palette.disabled.needsRepo");
                row.setDisable(false);
                assertFalse(RowReasons.sayWhy(row));

                // A row with no words is named by its command; blank words count as none.
                MenuItem blank = MenuChords.tag(new MenuItem(" "), "edit.copy");
                assertEquals(MenuTitles.of(tr("command.edit.copy")), RowReasons.title(blank));

                // A menu of a popup says why in the window the popup belongs to.
                ContextMenu outer = new ContextMenu(new MenuItem("a"));
                outer.show(owner, Side.BOTTOM, 0, 0);
                Consumer<String> viaPopup = RowReasons.sayFor(outer);
                assertNotNull(viaPopup);
                viaPopup.accept("via popup");
                assertEquals(List.of("via popup"), said);
                outer.hide();

                // A row drawn from nodes of its own has no title label: it still gets the tooltip and the help.
                javafx.scene.control.CustomMenuItem custom =
                        new javafx.scene.control.CustomMenuItem(new javafx.scene.control.Label("custom"));
                RowReasons.because(custom, "palette.disabled.needsRepo");
                ContextMenu customMenu = new ContextMenu(custom);
                customMenu.show(owner, Side.BOTTOM, 0, 0);
                assertNotNull(RowReasons.tooltipOf(custom));
                // Another key on the row is the menu's: it is not taken.
                javafx.scene.input.KeyEvent down = new javafx.scene.input.KeyEvent(
                        javafx.scene.input.KeyEvent.KEY_PRESSED,
                        "",
                        "",
                        javafx.scene.input.KeyCode.DOWN,
                        false,
                        false,
                        false,
                        false);
                custom.getStyleableNode().fireEvent(down);
                assertEquals(1, said.size(), "Down says nothing");
                customMenu.hide();

                // A text field's menu of the app's own rows is left as it is.
                MenuItem own = new MenuItem("Own");
                own.setDisable(true);
                ContextMenu fieldMenu = new ContextMenu(own);
                field.setContextMenu(fieldMenu);
                fieldMenu.show(field, Side.BOTTOM, 0, 0);
                assertNull(RowReasons.reasonOf(own));
                fieldMenu.hide();
                ContextMenu empty = new ContextMenu();
                RowReasons.explainTextInputMenu(empty); // no owner, no rows: nothing to do
                assertTrue(empty.getItems().isEmpty());
            });
        } finally {
            EditorFx.onFx(() -> {
                if (stage[0] != null) {
                    stage[0].close();
                }
            });
        }
    }

    private static void say(Stage window, String message) {
        Consumer<String> say = RowReasons.sayFor(window);
        assertNotNull(say, "a status line for " + message);
        say.accept(message);
    }
}
