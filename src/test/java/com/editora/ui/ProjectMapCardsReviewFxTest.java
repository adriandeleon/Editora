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
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    private static String status(ProjectMapPreview card) {
        return FxTestSupport.<javafx.scene.control.Label>field(card, "status").getText();
    }

    private static void altKey(javafx.scene.Node target, KeyCode code, boolean shift) {
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, false, true, false));
    }

    // ---- C3: a declaration card whose declaration went keeps its last lines ----

    @Test
    void aDeclarationCardKeepsItsLinesWhenTheDeclarationIsDeletedAndFollowsOneThatMoved(@TempDir Path dir)
            throws Exception {
        String before =
                "class App {\n  void a() {\n  }\n  static class Inner {\n    int x;\n  }\n  void main() {\n  }\n}\n";
        Path file = Files.writeString(dir.resolve("App.java"), before);
        try (Rig r = new Rig()) {
            FxTestSupport.runOnFx(() -> {
                r.card.showRange(file, new ProjectMapPreview.Range("Inner", 3, 3, 5), new Content(before, false), null);
                assertEquals(
                        "  static class Inner {\n    int x;\n  }",
                        r.card.editor().getText());

                // Two lines above it: the same lines, two further down, before any outline arrives.
                String moved = "// one\n// two\n" + before;
                r.card.refresh(new Content(moved, false));
                assertEquals(
                        "  static class Inner {\n    int x;\n  }",
                        r.card.editor().getText());
                assertEquals(5, r.card.range().line());

                // The class deleted: the text is held, not cut by the old lines (it used to show main()).
                String deleted = "// one\n// two\nclass App {\n  void a() {\n  }\n  void main() {\n  }\n}\n";
                r.card.refresh(new Content(deleted, false));
                assertEquals(
                        "  static class Inner {\n    int x;\n  }",
                        r.card.editor().getText());
                r.card.setSymbolMissing(true); // the new outline has no Inner
                assertEquals(
                        "  static class Inner {\n    int x;\n  }",
                        r.card.editor().getText());
                assertTrue(status(r.card).contains(tr("project.map.preview.symbolMissing")));
                FxTestSupport.call(r.card, "rebuildEditorContextMenu", new Class<?>[] {int.class}, 0);
                assertTrue(r.item("editmenu.addBookmark").isDisable(), "its lines are no longer the file's");
                assertTrue(r.item("editmenu.addNote").isDisable());
                assertTrue(r.card.runMarkerCommand("bookmarks.toggle"));
                assertEquals(List.of(), r.markers.bookmarks);

                // Back again (an undo): the outline finds it and the held text is shown at its lines.
                r.card.setSymbolMissing(false);
                r.card.refresh(new Content(moved, false));
                r.card.setRange(new ProjectMapPreview.Range("Inner", 5, 5, 7));
                assertEquals(
                        "  static class Inner {\n    int x;\n  }",
                        r.card.editor().getText());
            });
        }
    }

    // ---- C15: cards move and resize from the keyboard ----

    @Test
    void altArrowsMoveACardAndAltShiftArrowsResizeIt(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("a.txt"), "one\ntwo\n");
        try (Rig r = new Rig()) {
            FxTestSupport.runOnFx(() -> {
                KeymapManager keymap = new KeymapManager();
                keymap.loadNamed("intellij"); // Alt+arrows are bound there, so the claim must win
                new KeyDispatcher(new CommandRegistry(), keymap, status -> {}).install(r.stage.getScene());
                r.card.showFile(file, new Content("one\ntwo\n", false), null);
                r.host.layout();
                r.card.editor().requestFocus();
            });
            FxTestSupport.drainFx();
            FxTestSupport.runOnFx(() -> {
                double x = r.card.getLayoutX();
                double y = r.card.getLayoutY();
                double w = r.card.getWidth();
                double h = r.card.getHeight();
                altKey(r.card.editor(), KeyCode.LEFT, false);
                altKey(r.card.editor(), KeyCode.DOWN, false);
                assertEquals(x - ProjectMapPreview.KEYBOARD_STEP, r.card.getLayoutX(), 0.01);
                assertEquals(y + ProjectMapPreview.KEYBOARD_STEP, r.card.getLayoutY(), 0.01);
                altKey(r.card.editor(), KeyCode.RIGHT, true);
                altKey(r.card.editor(), KeyCode.UP, true);
                assertEquals(w + ProjectMapPreview.KEYBOARD_STEP, r.card.getWidth(), 0.01);
                assertEquals(h - ProjectMapPreview.KEYBOARD_STEP, r.card.getHeight(), 0.01);
                assertTrue(r.card.getAccessibleHelp().contains(String.valueOf(Math.round(r.card.getLayoutX()))));
            });
        }
    }

    // ---- C16: a long file is partly coloured and says so ----

    @Test
    void aFileOverTheHighlightCapColoursItsFirstLinesAndSaysSo(@TempDir Path dir) throws Exception {
        StringBuilder text = new StringBuilder();
        while (text.length() < 165_000) {
            text.append("int value").append(text.length()).append(" = 1; // a comment\n");
        }
        Path file = Files.writeString(dir.resolve("Big.java"), text);
        try (Rig r = new Rig()) {
            FxTestSupport.runOnFx(() -> r.card.showFile(file, Content.closed(null), null));
            r.await(
                    "the first lines to be coloured",
                    () -> !r.card.editor().getStyleOfChar(0, 1).isEmpty());
            FxTestSupport.runOnFx(() -> {
                int coloured = FxTestSupport.field(r.card, "colouredLines");
                assertTrue(
                        coloured > 0
                                && coloured < r.card.editor().getParagraphs().size(),
                        "its first lines");
                assertTrue(status(r.card).contains(tr("project.map.preview.partlyColoured", coloured)), status(r.card));
            });
        }
    }

    // ---- C17: a binary file gets a compact information card ----

    @Test
    void aBinaryFileGetsAnInformationCardWithNoOpen(@TempDir Path dir) throws Exception {
        byte[] bytes = new byte[3000];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i % 7 == 0 ? 0 : i);
        }
        Path file = Files.write(dir.resolve("data.bin"), bytes);
        try (Rig r = new Rig()) {
            List<Path> revealed = new ArrayList<>();
            FxTestSupport.runOnFx(() -> {
                r.card.setOnReveal(revealed::add);
                r.card.showFile(file, Content.closed(null), null);
            });
            r.await("the file's facts", r.card::showsFileInfo);
            FxTestSupport.runOnFx(() -> {
                javafx.scene.layout.BorderPane frame = FxTestSupport.field(r.card, "frame");
                javafx.scene.layout.VBox info = (javafx.scene.layout.VBox) frame.getCenter();
                List<String> texts = info.getChildren().stream()
                        .filter(javafx.scene.control.Labeled.class::isInstance)
                        .map(node -> ((javafx.scene.control.Labeled) node).getText())
                        .toList();
                assertEquals(tr("project.map.preview.binaryTitle"), texts.getFirst());
                assertTrue(
                        texts.contains(tr("project.map.preview.infoSize", StatusBar.formatSize(3000))),
                        texts::toString);
                assertTrue(FxTestSupport.<javafx.scene.control.Button>field(r.card, "open")
                        .isDisable());
                assertEquals(ProjectMapPreview.MIN_WIDTH, r.card.getWidth(), 0.5, "a compact card");
                javafx.scene.control.Button reveal =
                        (javafx.scene.control.Button) info.getChildren().getLast();
                assertEquals(tr("project.menu.revealInFileManager"), reveal.getText());
                reveal.fire();
                assertEquals(List.of(file.toAbsolutePath().normalize()), revealed);
            });
        }
    }

    @Test
    void anImageOverTheCapSaysItIsTooLargeAndHowLarge(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("huge.png");
        try (var out = Files.newOutputStream(file)) {
            byte[] block = new byte[1 << 20];
            for (int i = 0; i < 21; i++) {
                out.write(block);
            }
        }
        try (Rig r = new Rig()) {
            FxTestSupport.runOnFx(() -> r.card.showFile(file, Content.closed(null), null));
            r.await("the file's facts", r.card::showsFileInfo);
            FxTestSupport.runOnFx(() -> {
                javafx.scene.layout.BorderPane frame = FxTestSupport.field(r.card, "frame");
                javafx.scene.layout.VBox info = (javafx.scene.layout.VBox) frame.getCenter();
                assertEquals(
                        tr("project.map.preview.imageTooLarge", StatusBar.formatSize(21L << 20)),
                        ((javafx.scene.control.Label) info.getChildren().getFirst()).getText());
            });
        }
    }

    // ---- C19: cards use the editor font and remember the text zoom ----

    @Test
    void cardTextUsesTheEditorFont(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("a.txt"), "one\n");
        try (Rig r = new Rig()) {
            FxTestSupport.runOnFx(() -> {
                r.card.showFile(file, new Content("one\n", false), null);
                ProjectMapPreview.setEditorFont("Serif", 15);
                try {
                    assertTrue(
                            r.card.editor().getStyle().contains("'Serif'"),
                            r.card.editor().getStyle());
                    assertTrue(
                            r.card.editor().getStyle().contains("15.0px"),
                            r.card.editor().getStyle());
                    List<Double> zooms = new ArrayList<>();
                    r.card.setOnContentZoomed(zooms::add);
                    FxTestSupport.<javafx.scene.control.Button>field(r.card, "zoomIn")
                            .fire();
                    assertTrue(
                            r.card.editor().getStyle().contains("16.5"),
                            r.card.editor().getStyle());
                    assertEquals(1, zooms.size(), "the owner is told, to open its next card at it");
                    ProjectMapPreview next = new ProjectMapPreview(ignored -> {});
                    next.setInitialContentZoom(zooms.getFirst());
                    assertTrue(next.editor().getStyle().contains("16.5"));
                    next.dispose();
                } finally {
                    ProjectMapPreview.setEditorFont("Monospaced", 12);
                }
            });
        }
    }

    // ---- C22: the Notes button while "Hide all open Personal Notes" is on ----

    @Test
    void theNotesButtonLooksOffAndSaysWhyWhileAllNotesAreHidden(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("a.txt"), "one\n");
        try (Rig r = new Rig()) {
            FxTestSupport.runOnFx(() -> {
                int[] asked = {0};
                r.card.setOnShowSuppressedNotes(() -> asked[0]++);
                r.card.showFile(file, new Content("one\n", false), null);
                r.card.setNoteCount(2, true);
                r.card.setNotesShown(true);
                javafx.scene.control.ToggleButton notes = FxTestSupport.field(r.card, "notes");
                assertTrue(notes.isSelected());

                r.card.setNotesSuppressed(true);
                assertFalse(notes.isSelected(), "not pressed while nothing shows");
                assertEquals(
                        tr("project.map.preview.notesHiddenByOption"),
                        notes.getTooltip().getText());
                assertTrue(r.card.notesShown(), "still stuck on, only out of sight");
                notes.fire();
                assertEquals(1, asked[0], "a press asks for the notes back");
                assertTrue(r.card.notesShown());

                r.card.setNotesSuppressed(false);
                assertTrue(notes.isSelected());
            });
        }
    }

    // ---- C23, C24: Copy All, unsaved status ----

    @Test
    void copyWithNothingSelectedSaysCopyAllAndABufferWithUnsavedEditsSaysSo(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("a.txt"), "one\ntwo\n");
        try (Rig r = new Rig()) {
            FxTestSupport.runOnFx(() -> {
                r.card.showFile(file, new Content("one\ntwo\n// unsaved\n", false), null);
                FxTestSupport.call(r.card, "rebuildEditorContextMenu", new Class<?>[] {int.class}, 0);
                assertNotNull(r.item("project.map.preview.copyAll"));
                r.card.editor().selectRange(0, 3);
                FxTestSupport.call(r.card, "rebuildEditorContextMenu", new Class<?>[] {int.class}, 0);
                assertNotNull(r.item("editmenu.copy"));

                assertFalse(status(r.card).contains(tr("project.map.preview.unsaved")));
                r.card.setUnsaved(true);
                assertTrue(status(r.card).contains(tr("project.map.preview.unsaved")));
                r.card.setUnsaved(false);
                assertFalse(status(r.card).contains(tr("project.map.preview.unsaved")));
            });
        }
    }

    // ---- D7: cards share two loader threads ----

    @Test
    void eightCardsShareAtMostTwoLoaderThreads(@TempDir Path dir) throws Exception {
        List<ProjectMapPreview> cards = new ArrayList<>();
        try (Rig r = new Rig()) {
            for (int i = 0; i < 8; i++) {
                Path file = Files.writeString(dir.resolve("f" + i + ".txt"), "file " + i + "\n");
                FxTestSupport.runOnFx(() -> {
                    ProjectMapPreview card = new ProjectMapPreview(ignored -> {});
                    r.host.getChildren().add(card);
                    card.showFile(file, Content.closed(null), null);
                    cards.add(card);
                });
            }
            r.await(
                    "every card to load",
                    () -> cards.stream()
                            .allMatch(card -> card.editor().getText().startsWith("file ")));
            long loaders = Thread.getAllStackTraces().keySet().stream()
                    .filter(thread -> thread.getName().equals("project-map-preview-loader"))
                    .count();
            assertTrue(loaders <= 2, "one shared pool, not a thread per card: " + loaders);
            FxTestSupport.runOnFx(() -> cards.forEach(ProjectMapPreview::dispose));
        }
    }

    // ---- D16: a card created before its host has a size waits for one ----

    @Test
    void aCardCreatedInAnUnsizedHostWaitsForItsSizeInsteadOfPolling(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("a.txt"), "one\n");
        try (Rig r = new Rig()) {
            FxTestSupport.runOnFx(() -> {
                Pane unsized = new Pane();
                ProjectMapPreview card = new ProjectMapPreview(ignored -> {});
                unsized.getChildren().add(card);
                card.showFile(file, new Content("one\n", false), null);
                assertTrue(card.waitingForSize());
                unsized.resize(800, 600); // laid out at last
                assertFalse(card.waitingForSize(), "placed once the host has a size");
                assertTrue(card.getWidth() >= ProjectMapPreview.MIN_WIDTH);
                card.dispose();
            });
        }
    }

    // ---- D9: an unedited buffer is not read again ----

    @Test
    void aPollSkipsReadingABufferWhoseEditCounterDidNotMove(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("a.txt"), "one\n");
        try (Rig r = new Rig()) {
            int[] reads = {0};
            String[] text = {"one\n"};
            long[] version = {1};
            java.util.function.Supplier<Content> content = () -> Content.buffer(
                    () -> {
                        reads[0]++;
                        return text[0];
                    },
                    false,
                    version[0]);
            FxTestSupport.runOnFx(() -> {
                r.card.showFile(file, content.get(), null);
                assertEquals(1, reads[0]);
                r.card.refresh(content.get());
                r.card.refresh(content.get());
                assertEquals(1, reads[0], "same edit counter: the text is not even read");
                text[0] = "two\n";
                version[0] = 2;
                r.card.refresh(content.get());
                assertEquals(2, reads[0]);
                assertEquals("two\n", r.card.editor().getText());
            });
        }
    }

    private static com.editora.config.PersonalNote note(
            com.editora.config.NoteScope scope, int line, int column, int endColumn, String text, String prefix) {
        return new com.editora.config.PersonalNote(
                java.util.UUID.randomUUID(),
                null,
                scope,
                new com.editora.config.TextAnchor(line, column, line, endColumn, text, prefix, ""),
                "body " + line,
                List.of(),
                com.editora.config.NoteStatus.ACTIVE,
                line,
                line);
    }

    // ---- C5: stored notes are found again in a file changed on disk ----

    @Test
    void storedNotesAreFoundAgainInTheCardsTextOrMarkedOrphaned() {
        String text = "// one\n// two\n// three\nclass App {\n  void main() {}\n}\n";
        var moved = note(com.editora.config.NoteScope.WORD, 1, 2, 6, "main", "  void ");
        var gone = note(com.editora.config.NoteScope.WORD, 0, 0, 4, "gone", "");
        List<com.editora.config.PersonalNote> found = ProjectMapStickyNotes.reanchored(List.of(moved, gone), text);
        assertEquals(4, found.get(0).anchor().line(), "three lines were added above it on disk");
        assertEquals(7, found.get(0).anchor().column());
        assertEquals(com.editora.config.NoteStatus.ACTIVE, found.get(0).status());
        assertEquals(com.editora.config.NoteStatus.ORPHANED, found.get(1).status());
        assertEquals(tr("project.map.sticky.orphaned"), ProjectMapStickyNotes.where(found.get(1)));
        assertEquals(List.of(moved), ProjectMapStickyNotes.reanchored(List.of(moved), ""), "no text: as stored");
    }

    // ---- C9, C10: many notes ----

    @Test
    void notesPastTwoStacksGoIntoOneMoreNotesSheetAndAreAllCounted() throws Exception {
        List<Double> wanted = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            wanted.add(0.0);
        }
        int shown = ProjectMapStickyNotes.sheetsShown(wanted, 420);
        assertTrue(shown > 0 && shown < 30, "some, not all: " + shown);
        List<Double> withMore = new ArrayList<>(wanted.subList(0, shown));
        withMore.add(Double.MAX_VALUE);
        assertTrue(ProjectMapStickyNotes.stack(withMore, 420).stream().allMatch(at -> at[0] < 2), "two stacks");
        assertEquals(3, ProjectMapStickyNotes.sheetsShown(List.of(0.0, 0.0, 0.0), 420), "few notes: all");

        try (Rig r = new Rig()) {
            List<com.editora.config.PersonalNote> notes = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < 30; i++) {
                text.append("line ").append(i).append('\n');
                notes.add(note(com.editora.config.NoteScope.LINE, i, 0, 0, "", ""));
            }
            FxTestSupport.runOnFx(() -> {
                r.card.showFile(Path.of("notes30.txt"), new Content(text.toString(), false), null);
                r.host.layout();
                r.card.setNoteCount(ProjectMapStickyNotes.notesFor(notes, null).size(), true);
                assertEquals(
                        "30",
                        FxTestSupport.<javafx.scene.control.ToggleButton>field(r.card, "notes")
                                .getText());
                int[] more = {0};
                ProjectMapStickyNotes stuck =
                        new ProjectMapStickyNotes(r.card, r.host, (n, b) -> {}, () -> {}, () -> {}, () -> {});
                stuck.setOnMore(() -> more[0]++);
                stuck.show(notes);
                assertTrue(stuck.sheetCount() < 30);
                assertEquals(30, stuck.sheetCount() + stuck.moreCount(), "every note is in a sheet or the +K sheet");
                javafx.scene.control.Button open = r.host.getChildren().stream()
                        .filter(node -> node.getStyleClass().contains("project-map-sticky-more"))
                        .map(node -> (javafx.scene.control.Button) node.lookup(".project-map-sticky-more-button"))
                        .findFirst()
                        .orElseThrow();
                assertEquals(tr("project.map.sticky.more", stuck.moreCount()), open.getText());
                open.fire();
                assertEquals(1, more[0], "it opens the file's note card");
                stuck.dispose();
            });
        }
    }

    // ---- C14: the note card heads each note with its line and opens the file there ----

    @Test
    void aNoteCardHeadsEachNoteWithItsLineAndOpensTheFileThere() throws Exception {
        FxTestSupport.runOnFx(() -> {
            List<String> opened = new ArrayList<>();
            ProjectMapNotePreview card = new ProjectMapNotePreview((n, b) -> {});
            card.setOnOpenAt((file, line) -> opened.add(file.getFileName() + ":" + line));
            var onLine = note(com.editora.config.NoteScope.WORD, 7, 4, 7, "add", "int ");
            var folder = note(com.editora.config.NoteScope.FOLDER, 0, 0, 0, "", "");
            card.showNotes(
                    Path.of("App.java"), List.of(onLine, folder), new ProjectMapPreview.Placement(0, 0, 400, 300));
            assertEquals(List.of(tr("project.map.sticky.line", 8)), card.headings(), "a folder's note has none");
            javafx.scene.layout.VBox rows = FxTestSupport.field(card, "notes");
            javafx.scene.control.Button heading =
                    (javafx.scene.control.Button) rows.getChildren().getFirst();
            assertTrue(heading.getTooltip().getText().startsWith("add"), "the noted text");
            heading.fire();
            assertEquals(List.of("App.java:7"), opened);
            card.dispose();
        });
    }

    // ---- C4: declarations are labelled with their parameter types and keep their path across overloads ----

    @Test
    void parameterTypesAreReadFromTheDeclarationLine() {
        assertEquals(
                "int, int, int",
                ProjectMapStructure.parameterTypes("  public int add(int a, int b, int c) {", "add", "java"));
        assertEquals("", ProjectMapStructure.parameterTypes("  void reset() {", "reset", "java"));
        assertEquals(
                "String, Map<String, Integer>, String...",
                ProjectMapStructure.parameterTypes(
                        "void f(@Nullable String s, final Map<String, Integer> m, String... rest) {", "f", "java"));
        assertEquals(
                "Int, Int", ProjectMapStructure.parameterTypes("fun add(a: Int, b: Int = 2): Int {", "add", "kotlin"));
        assertEquals("x, y", ProjectMapStructure.parameterTypes("    def m(self, x, y=1):", "m", "python"));
        assertEquals(
                "T, () => void",
                ProjectMapStructure.parameterTypes("function foo<T>(x: T, cb: () => void) {", "foo", "typescript"));
        assertEquals("a, b int", ProjectMapStructure.parameterTypes("func add(a, b int) int {", "add", "go"));
        assertEquals("char*", ProjectMapStructure.parameterTypes("int len(char *s) {", "len", "c"));
        assertNull(ProjectMapStructure.parameterTypes("  void wrapped(int a,", "wrapped", "java"), "goes on below");
        assertNull(ProjectMapStructure.parameterTypes("  readd(1);", "add", "java"), "not the name");

        StructureNode method = new StructureNode(null, "add", "method", 0);
        assertEquals("add(int, int)", ProjectMapStructure.displayLabel(method, "int add(int a, int b) {", "java"));
        assertEquals("add", ProjectMapStructure.displayLabel(method, "int add(int a,", "java"), "no invented ()");
    }

    @Test
    void anInsertedOverloadLeavesADeclarationsPathAloneAndAnEditedSignatureIsFollowed(@TempDir Path dir)
            throws Exception {
        String two = "class App {\n  int add(int a, int b) {\n    return a + b;\n  }\n"
                + "  int add(int a, int b, int c) {\n    return a + b + c;\n  }\n}\n";
        Path file = Files.writeString(dir.resolve("App.java"), two);
        String[] text = {two};
        ProjectMapStructure structure =
                FxTestSupport.callOnFx(() -> new ProjectMapStructure(path -> new Content(text[0], false)));
        ProjectMapModel.Entry entry = new ProjectMapModel.Entry(file, dir, 1, false);
        try {
            FxTestSupport.runOnFx(() -> structure.toggle(entry));
            Path three = awaitSymbol(structure, entry, "add(int, int, int)");

            text[0] = two.replace("class App {\n", "class App {\n  int add(String s) {\n    return 0;\n  }\n");
            FxTestSupport.runOnFx(() -> structure.refresh(java.util.Set.of()));
            awaitSymbol(structure, entry, "add(String)");
            assertEquals(three, awaitSymbol(structure, entry, "add(int, int, int)"), "an overload above: same path");

            text[0] = text[0].replace("add(int a, int b, int c)", "add(long a, int b, int c)");
            FxTestSupport.runOnFx(() -> structure.refresh(java.util.Set.of()));
            Path edited = awaitSymbol(structure, entry, "add(long, int, int)");
            assertEquals(edited, FxTestSupport.callOnFx(() -> structure.successor(three)), "followed to its new path");
        } finally {
            FxTestSupport.runOnFx(structure::dispose);
        }
    }

    private static Path awaitSymbol(ProjectMapStructure structure, ProjectMapModel.Entry file, String label)
            throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Path found = FxTestSupport.callOnFx(() -> structure.withStructure(List.of(file)).stream()
                    .filter(row -> row.isSymbol()
                            && row.symbol() != null
                            && label.equals(row.symbol().label()))
                    .map(ProjectMapModel.Entry::path)
                    .findFirst()
                    .orElse(null));
            if (found != null) {
                return found;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no declaration " + label);
    }

    // ---- C11: one-line members, CSS rules, YAML keys ----

    private static List<StructureNode> outline(String name, String text) {
        return StructureOutline.build(StructureOutline.Source.file(name, text).analyzed());
    }

    @Test
    void oneLineMembersCssRulesAndYamlKeysAreInTheOutline() {
        List<StructureNode> java =
                outline("Many.java", "class Many {\n  void m0() {}\n  int m1() { return 1; }\n  void m2() {}\n}\n");
        assertEquals(1, java.size());
        assertEquals(
                3,
                java.getFirst().children().size(),
                "one-line methods: " + java.getFirst().children());

        List<StructureNode> kotlin = outline("top.kt", "fun top() = 1\n\nfun other() {\n  println()\n}\n");
        assertTrue(kotlin.stream().anyMatch(n -> n.label().equals("top")), kotlin::toString);

        List<StructureNode> css = outline(
                "a.css",
                "body {\n  margin: 0;\n}\n.card .title {\n  color: red;\n}\n@media (max-width: 600px) {\n  #id > p {\n    x: y;\n  }\n}\n");
        assertEquals(
                List.of("body", ".card .title", "@media (max-width: 600px)"),
                css.stream().map(StructureNode::label).toList());
        assertEquals("#id > p", css.getLast().children().getFirst().label());

        List<StructureNode> yaml = outline(
                "ci.yml",
                "name: CI\non:\n  push:\n    branches: [main]\njobs:\n  build:\n    steps:\n      - run: |\n          echo: not a key\n      - name: x\n");
        assertEquals(
                List.of("name", "on", "jobs"),
                yaml.stream().map(StructureNode::label).toList());
        assertEquals("push", yaml.get(1).children().getFirst().label(), "under on, not under name");
        StructureNode steps = yaml.get(2).children().getFirst().children().getFirst();
        assertEquals(
                List.of("run", "name"),
                steps.children().stream().map(StructureNode::label).toList(),
                "block scalar lines skipped");
    }

    // ---- C12, C13, C20: partial outlines, .http requests, # comments ----

    @Test
    void aRequestRunsToTheNextOneAndHashCommentsAndCutBlocksBelongToTheirDeclaration() {
        List<String> http = List.of(
                "### Get",
                "GET https://x/users",
                "",
                "### Create",
                "POST https://x/users",
                "Content-Type: application/json",
                "",
                "{\"a\": 1}",
                "",
                "### Last",
                "GET https://x/last");
        StructureNode create = new StructureNode(null, "Create — POST", StructureOutline.HTTP_REQUEST_KIND, 4);
        assertArrayEquals(new int[] {4, 7}, ProjectMapStructure.range(create, http, 10, 10), "headers and body");

        List<String> python =
                List.of("import x", "", "# Helper comment above f.", "@decorator", "def first():", "    pass");
        StructureNode def = new StructureNode(null, "first", "function", 4);
        assertArrayEquals(
                new int[] {2, 4},
                ProjectMapStructure.range(def, new ProjectMapStructure.Source(python, "#", "python", false), -1, 5));
        assertEquals(2, ProjectMapStructure.leadingDocStart(List.of("#!/bin/sh", "", "# about f", "f() {"), 3, "#"));
        assertEquals(1, ProjectMapStructure.leadingDocStart(List.of("#!/bin/sh", "f() {"), 1, "#"), "not a shebang");

        List<String> cut = List.of("class Big {", "  void big() {", "    work();", "    more();");
        StructureNode big = new StructureNode(null, "big", "method", 1);
        assertArrayEquals(
                new int[] {1, 3},
                ProjectMapStructure.range(big, new ProjectMapStructure.Source(cut, "//", "java", true), -1, 3),
                "its block was cut off by the read cap: it runs to the end of what was read");
        assertArrayEquals(
                new int[] {1, 1},
                ProjectMapStructure.range(big, new ProjectMapStructure.Source(cut, "//", "java", false), -1, 3));
    }

    @Test
    void anOutlineOfACutFileSaysItIsPartial(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("Big.java"), "class Big {\n  void a() {\n  }\n");
        ProjectMapStructure structure = FxTestSupport.callOnFx(
                () -> new ProjectMapStructure(path -> new Content("class Big {\n  void a() {\n  }\n", true)));
        ProjectMapModel.Entry entry = new ProjectMapModel.Entry(file, dir, 1, false);
        try {
            FxTestSupport.runOnFx(() -> structure.toggle(entry));
            awaitSymbol(structure, entry, "Big");
            List<String> rows = FxTestSupport.callOnFx(() -> structure.withStructure(List.of(entry)).stream()
                    .filter(ProjectMapModel.Entry::isPlaceholder)
                    .map(ProjectMapModel.Entry::name)
                    .toList());
            assertTrue(rows.contains(tr("project.map.structure.partial")), rows::toString);
        } finally {
            FxTestSupport.runOnFx(structure::dispose);
        }
    }

    // ---- C7 (with B10): a card opens where it covers least, never over the columns that lead to it ----

    @Test
    void aCardOpensClearOfTheColumnsThatLeadToItsRowAndOfItsFilesNextColumn(@TempDir Path temp) throws Exception {
        Path root = Files.createDirectories(temp.resolve("project")).toRealPath();
        Path acme = Files.createDirectories(root.resolve("src/main/java/com/acme"));
        Path app = Files.writeString(acme.resolve("App.java"), "class App {\n  void a() {\n  }\n}\n");
        for (int i = 0; i < 6; i++) {
            Files.writeString(acme.resolve("Other" + i + ".java"), "class Other {}\n");
        }
        List<Path> folders = List.of(
                root.resolve("src"),
                root.resolve("src/main"),
                root.resolve("src/main/java"),
                root.resolve("src/main/java/com"),
                acme);
        ProjectMapView view = FxTestSupport.callOnFx(() -> {
            ProjectMapView map = new ProjectMapView(path -> {}, path -> false, path -> false, path -> null);
            Scene scene = new Scene(map, 1600, 820);
            scene.getStylesheets()
                    .add(ProjectMapCardsReviewFxTest.class
                            .getResource("/com/editora/styles/app.css")
                            .toExternalForm());
            map.resize(1600, 820);
            map.applyCss();
            map.layout();
            map.setRememberedFlow(ProjectMapView.FlowDirection.LEFT_TO_RIGHT.name(), ignored -> {});
            map.setRememberedNavigation(true, false, (keep, focus) -> {});
            map.setRoot(root);
            FxTestSupport.<java.util.Set<Path>>field(map, "expanded").addAll(folders);
            FxTestSupport.invoke(map, "reload");
            return map;
        });
        try {
            javafx.scene.layout.Region surface = FxTestSupport.callOnFx(() -> FxTestSupport.field(view, "surface"));
            awaitFx("the App.java row", () -> rowBox(surface, app) != null);
            FxTestSupport.drainFx();
            FxTestSupport.runOnFx(() -> {
                view.layout();
                FxTestSupport.call(surface, "setSelected", new Class<?>[] {Path.class}, app);
                FxTestSupport.call(view, "previewSelection", new Class<?>[] {Path.class}, app);
            });
            FxTestSupport.drainFx();
            FxTestSupport.runOnFx(() -> {
                ProjectMapPreview card = FxTestSupport.<java.util.Map<Path, ProjectMapPreview>>field(view, "previews")
                        .get(app);
                assertNotNull(card);
                double[] rect = cardRect(card);
                double covered = 0;
                for (Object box : FxTestSupport.<List<?>>field(surface, "columnBoxes")) {
                    ProjectMapModel.Column column =
                            (ProjectMapModel.Column) FxTestSupport.call(box, "column", new Class<?>[0]);
                    if (column.parent() != null && app.startsWith(column.parent())) {
                        covered += overlap(rect, boxRect(box));
                    }
                }
                assertEquals(0, covered, 0.5, "the card covers none of the columns that lead to App.java");
            });
            // B10: the file's Structure column opens in the band the card kept clear.
            FxTestSupport.runOnFx(() -> FxTestSupport.call(
                    view, "toggleStructure", new Class<?>[] {ProjectMapModel.Entry.class}, rowEntry(surface, app)));
            awaitFx("the Structure column", () -> columnOf(surface, app) != null);
            FxTestSupport.runOnFx(() -> {
                ProjectMapPreview card = FxTestSupport.<java.util.Map<Path, ProjectMapPreview>>field(view, "previews")
                        .get(app);
                assertEquals(0, overlap(cardRect(card), boxRect(columnOf(surface, app))), 0.5, "not under the card");
            });
        } finally {
            FxTestSupport.runOnFx(view::dispose);
        }
    }

    private static void awaitFx(String what, java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (FxTestSupport.callOnFx(condition::getAsBoolean)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    private static Object rowBox(javafx.scene.layout.Region surface, Path path) {
        for (Object box : FxTestSupport.<List<?>>field(surface, "boxes")) {
            ProjectMapModel.Entry entry = (ProjectMapModel.Entry) FxTestSupport.call(box, "entry", new Class<?>[0]);
            if (entry.path().equals(path)) {
                return box;
            }
        }
        return null;
    }

    private static ProjectMapModel.Entry rowEntry(javafx.scene.layout.Region surface, Path path) {
        return (ProjectMapModel.Entry) FxTestSupport.call(rowBox(surface, path), "entry", new Class<?>[0]);
    }

    private static Object columnOf(javafx.scene.layout.Region surface, Path parent) {
        for (Object box : FxTestSupport.<List<?>>field(surface, "columnBoxes")) {
            ProjectMapModel.Column column = (ProjectMapModel.Column) FxTestSupport.call(box, "column", new Class<?>[0]);
            if (parent.equals(column.parent())) {
                return box;
            }
        }
        return null;
    }

    private static double[] boxRect(Object box) {
        return new double[] {
            (double) FxTestSupport.call(box, "x", new Class<?>[0]),
            (double) FxTestSupport.call(box, "y", new Class<?>[0]),
            (double) FxTestSupport.call(box, "width", new Class<?>[0]),
            (double) FxTestSupport.call(box, "height", new Class<?>[0])
        };
    }

    private static double[] cardRect(ProjectMapPreview card) {
        return new double[] {
            card.getLayoutX(), card.getLayoutY(), card.getWidth() * card.mapScale(), card.getHeight() * card.mapScale()
        };
    }

    private static double overlap(double[] a, double[] b) {
        double across = Math.min(a[0] + a[2], b[0] + b[2]) - Math.max(a[0], b[0]);
        double down = Math.min(a[1] + a[3], b[1] + b[3]) - Math.max(a[1], b[1]);
        return across > 0 && down > 0 ? across * down : 0;
    }

    @Test
    void theOverlapCostWeighsWhatACardWouldCover() {
        List<double[]> obstacles = List.of(new double[] {0, 0, 100, 100, 4}, new double[] {200, 0, 100, 100, 1});
        assertEquals(0, ProjectMapView.cardOverlapCost(100, 0, 100, 100, obstacles), 1e-9, "between them");
        assertEquals(50 * 100 * 4, ProjectMapView.cardOverlapCost(50, 0, 100, 100, obstacles), 1e-9);
        assertEquals(50 * 100 * 1, ProjectMapView.cardOverlapCost(150, 0, 100, 100, obstacles), 1e-9);
    }
}
