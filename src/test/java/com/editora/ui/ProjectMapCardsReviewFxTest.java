package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;

import com.editora.command.Command;
import com.editora.command.CommandRegistry;
import com.editora.command.KeyDispatcher;
import com.editora.command.KeymapManager;
import com.editora.editor.notes.NoteDraft;
import com.editora.ui.ProjectMapPreview.Content;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Project Map review's card findings (C2–C24, D7, D9, D16): preview, declaration, note and sticky-note cards,
 * card placement and the structure outline behind declaration cards.
 */
@Tag("fx")
class ProjectMapCardsReviewFxTest {

    private static final double HOST_W = 1000;
    private static final double HOST_H = 700;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** What a card's marker actions were asked to do. */
    private static final class Markers implements ProjectMapPreview.MarkerActions {
        final List<String> bookmarks = new ArrayList<>();
        final List<NoteDraft> notes = new ArrayList<>();
        final List<Path> noteFiles = new ArrayList<>();

        @Override
        public boolean personalNotesEnabled() {
            return true;
        }

        @Override
        public void addBookmark(Path file, int line) {
            bookmarks.add(file.getFileName() + ":" + line);
        }

        @Override
        public void addPersonalNote(Path file, NoteDraft draft) {
            noteFiles.add(file);
            notes.add(draft);
        }
    }

    /** A card inside a 1000x700 host pane in a shown stage. */
    private static final class Rig implements AutoCloseable {
        final AsyncTestScope async = new AsyncTestScope();
        final List<Path> opened = new ArrayList<>();
        final Markers markers = new Markers();
        ProjectMapPreview card;
        Pane host;
        Stage stage;

        Rig() throws Exception {
            FxTestSupport.runOnFx(() -> {
                card = new ProjectMapPreview(opened::add);
                card.setMarkerActions(markers);
                host = new Pane(card);
                stage = new Stage();
                stage.setScene(new Scene(host, HOST_W, HOST_H));
                stage.show();
                host.applyCss();
                host.layout();
            });
            async.onClose(() -> FxTestSupport.runOnFx(() -> {
                card.dispose();
                stage.close();
            }));
        }

        void await(String what, java.util.function.BooleanSupplier condition) throws Exception {
            SaveGuardsFxTest.awaitOnFx(async, what, condition);
        }

        ContextMenu menu() {
            return FxTestSupport.field(card, "editorContextMenu");
        }

        MenuItem item(String key) {
            return menu().getItems().stream()
                    .filter(i -> tr(key).equals(i.getText()))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public void close() throws Exception {
            async.close();
        }
    }

    private static void key(javafx.scene.Node target, KeyCode code, boolean control) {
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, control, false, false));
    }

    // ---- C2: note and bookmark chords in a focused card act on the card's file ----

    @Test
    void noteAndBookmarkChordsTypedInACardMarkTheCardsFileNotTheActiveTab(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("App.java"), "one\ntwo\nthree\n");
        try (Rig r = new Rig()) {
            List<String> ran = new ArrayList<>();
            FxTestSupport.runOnFx(() -> {
                KeymapManager keymap = new KeymapManager();
                keymap.loadNamed("emacs");
                CommandRegistry registry = new CommandRegistry();
                for (String id : List.of("notes.add", "notes.editNote", "bookmarks.toggle", "file.save")) {
                    registry.register(Command.of(id, id, () -> ran.add(id)));
                }
                new KeyDispatcher(registry, keymap, status -> {}).install(r.stage.getScene());
                r.card.showFile(file, new Content("one\ntwo\nthree\n", false), null);
                r.host.layout();
                r.card.editor().requestFocus();
                r.card.editor().moveTo(1, 1); // the caret on the second line
            });
            FxTestSupport.runOnFx(() -> {
                key(r.card.editor(), KeyCode.C, true); // C-c m: bookmarks.toggle
                key(r.card.editor(), KeyCode.M, false);
                assertEquals(List.of("App.java:1"), r.markers.bookmarks, "the card's file, at its caret line");

                key(r.card.editor(), KeyCode.C, true); // C-c n: notes.add
                key(r.card.editor(), KeyCode.N, false);
                assertEquals(List.of(file.toAbsolutePath().normalize()), r.markers.noteFiles);
                assertEquals(1, r.markers.notes.getFirst().anchor().line());
                assertEquals(List.of(), ran, "neither reached the global command, which marks the active tab");

                key(r.card.editor(), KeyCode.X, true); // C-x C-s: any other chord is still global
                key(r.card.editor(), KeyCode.S, true);
                assertEquals(List.of("file.save"), ran);
            });
        }
    }

    // ---- C6: the keyboard-opened card menu acts on the caret line ----

    @Test
    void theMenuKeyMenuOfACardBookmarksTheCaretLineNotTheMiddleOne(@TempDir Path dir) throws Exception {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            text.append("line ").append(i).append('\n');
        }
        Path file = Files.writeString(dir.resolve("a.txt"), text);
        try (Rig r = new Rig()) {
            FxTestSupport.runOnFx(() -> {
                r.card.showFile(file, new Content(text.toString(), false), null);
                r.host.layout();
                r.card.editor().requestFocus();
                r.card.editor().moveTo(1, 0);
            });
            FxTestSupport.drainFx();
            FxTestSupport.runOnFx(() -> {
                var editor = r.card.editor();
                double x = editor.getWidth() / 2;
                double y = editor.getHeight() / 2; // what JavaFX reports for a keyboard-triggered request
                var screen = editor.localToScreen(x, y);
                Event.fireEvent(
                        editor,
                        new ContextMenuEvent(
                                ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                                x,
                                y,
                                screen.getX(),
                                screen.getY(),
                                true,
                                null));
                MenuItem bookmark = r.item("editmenu.addBookmark");
                assertNotNull(bookmark);
                bookmark.fire();
                assertEquals(List.of("a.txt:1"), r.markers.bookmarks, "the caret's line, not the middle of the card");
                assertTrue(r.menu().isShowing());
                r.menu().hide();
            });
        }
    }
}
