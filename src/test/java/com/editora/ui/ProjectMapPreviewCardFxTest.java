package com.editora.ui;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.image.ImageView;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

import com.editora.config.NoteScope;
import com.editora.editor.NoteDraft;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One floating preview card of the Project Map, on its own: what it shows for a text file, an image, a
 * binary, a file that is gone and an open buffer; how it follows changes; and its keys, menu, drag and resize.
 */
@Tag("fx")
class ProjectMapPreviewCardFxTest {

    private static final double HOST_W = 1000;
    private static final double HOST_H = 700;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** A card inside a 1000x700 host pane in a shown stage. */
    private static final class Rig implements AutoCloseable {
        final AsyncTestScope async = new AsyncTestScope();
        final List<Path> opened = new ArrayList<>();
        ProjectMapPreview card;
        Pane host;
        Stage stage;

        Rig() throws Exception {
            FxTestSupport.runOnFx(() -> {
                card = new ProjectMapPreview(opened::add);
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

        void awaitText(String text) throws Exception {
            await("the card to show its text", () -> text.equals(card.editor().getText()));
        }

        String status() {
            return FxTestSupport.<Label>field(card, "status").getText();
        }

        Button button(String field) {
            return FxTestSupport.field(card, field);
        }

        /** The placeholder shown instead of text, or null. */
        String placeholder() {
            return card.editor().getPlaceholder() instanceof Label label ? label.getText() : null;
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

        void rebuildMenu(int clickedLine) {
            FxTestSupport.call(card, "rebuildEditorContextMenu", new Class<?>[] {int.class}, clickedLine);
        }

        /** Presses a key in the card's text; true when the card's own key filter took it. */
        boolean press(KeyCode code, boolean shift, boolean control) {
            // The card filters on the way down: a press it consumes never arrives at the text area's filter.
            boolean[] reached = new boolean[1];
            javafx.event.EventHandler<KeyEvent> probe = e -> reached[0] = true;
            card.editor().addEventFilter(KeyEvent.KEY_PRESSED, probe);
            javafx.event.Event.fireEvent(
                    card.editor(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, control, false, false));
            card.editor().removeEventFilter(KeyEvent.KEY_PRESSED, probe);
            return !reached[0];
        }

        @Override
        public void close() throws Exception {
            async.close();
        }
    }

    private static MouseEvent mouse(
            javafx.event.EventType<MouseEvent> type, MouseButton button, double screenX, double screenY, boolean down) {
        return new MouseEvent(
                type, 0, 0, screenX, screenY, button, 1, false, false, false, false, down, false, false, true, false,
                false, null);
    }

    @Test
    void aTextFileIsReadFromDiskAndOpenHandsItToTheEditor(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("Notes.java"), "class Notes {\r\n}\r\n");
        try (Rig r = new Rig()) {
            FxTestSupport.runOnFx(() -> {
                assertFalse(r.card.isVisible(), "a card shows nothing until it is given a file");
                r.card.showFile(null, Content.closed(null), null);
                assertFalse(r.card.isVisible());

                r.card.showFile(file, Content.closed(null), null);
                assertTrue(r.card.isVisible());
                assertEquals(tr("project.map.preview.loading"), r.status());
                assertEquals(
                        "Notes.java",
                        FxTestSupport.<Label>field(r.card, "title").getText());
                assertEquals(file, r.card.path());
                assertFalse(r.card.showsOpenBuffer());
            });
            r.awaitText("class Notes {\n}\n"); // in the editor's line-ending form
            FxTestSupport.runOnFx(() -> {
                assertEquals("", r.status());
                assertEquals(0, r.card.editor().getCaretPosition(), "a new card starts at the top");
                assertEquals(24, r.card.getLayoutY(), "placed in the panel's top-right corner by default");
                assertEquals(HOST_W - r.card.getWidth() - 24, r.card.getLayoutX(), 0.5);

                r.button("open").fire();
                assertEquals(List.of(file), r.opened);
            });
        }
    }

    @Test
    void anImageIsShownAsAnImageWithItsSizeAndZoomsWithTheButtons(@TempDir Path dir) throws Exception {
        Path picture = dir.resolve("logo.png");
        javax.imageio.ImageIO.write(new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB), "png", picture.toFile());
        try (Rig r = new Rig()) {
            FxTestSupport.runOnFx(() -> r.card.showFile(picture, Content.closed(null), null));
            ImageView view = FxTestSupport.field(r.card, "imageView");
            r.await("the image", () -> view.getImage() != null);
            FxTestSupport.runOnFx(() -> {
                assertEquals("40 × 20", r.status());
                assertEquals("", r.card.editor().getText());
                assertSame(
                        FxTestSupport.field(r.card, "imageScroll"),
                        FxTestSupport.<BorderPane>field(r.card, "frame").getCenter());
                assertEquals(40, view.getFitWidth(), 0.01);

                r.button("zoomIn").fire();
                assertEquals(44, view.getFitWidth(), 0.01, "ten percent larger");
                r.button("zoomOut").fire();
                r.button("zoomOut").fire();
                assertEquals(40 / 1.1, view.getFitWidth(), 0.01);
                for (int i = 0; i < 20; i++) {
                    r.button("zoomOut").fire();
                }
                assertEquals(20, view.getFitWidth(), 0.01, "never below half size");

                r.card.focusContent();
                assertSame(
                        FxTestSupport.field(r.card, "imageScroll"),
                        r.stage.getScene().getFocusOwner(),
                        "an image card takes the focus on its image");
            });
        }
    }

    @Test
    void aBinaryFileAndABrokenImageSayTheyCannotBeShown(@TempDir Path dir) throws Exception {
        Path binary = Files.write(dir.resolve("data.bin"), new byte[] {'a', 'b', 0, 'c'});
        Path broken = Files.writeString(dir.resolve("broken.png"), "this is not a PNG");
        try (Rig r = new Rig()) {
            FxTestSupport.runOnFx(() -> r.card.showFile(binary, Content.closed(null), null));
            r.await("the binary notice", () -> tr("project.map.preview.binary").equals(r.placeholder()));
            assertEquals("", FxTestSupport.callOnFx(() -> r.card.editor().getText()));
            assertEquals("", FxTestSupport.callOnFx(r::status));

            FxTestSupport.runOnFx(() -> r.card.showFile(broken, Content.closed(null), null));
            r.await("the failure notice", () -> tr("project.map.preview.failed").equals(r.placeholder()));
        }
    }

    @Test
    void aFileThatIsMissingSaysSoAndComesBackWhenItReappears(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("later.txt");
        try (Rig r = new Rig()) {
            FxTestSupport.runOnFx(() -> r.card.showFile(file, Content.closed(null), null));
            r.await(
                    "the missing notice",
                    () -> tr("project.map.preview.missing").equals(r.placeholder()));
            FxTestSupport.runOnFx(() -> {
                assertTrue(r.card.getStyleClass().contains("project-map-preview-missing"));
                assertTrue(r.button("open").isDisable(), "there is nothing to open");
            });

            Files.writeString(file, "now it exists\n");
            FxTestSupport.runOnFx(() -> r.card.refresh(Content.closed(null)));
            r.awaitText("now it exists\n");
            FxTestSupport.runOnFx(() -> {
                assertFalse(r.card.getStyleClass().contains("project-map-preview-missing"));
                assertFalse(r.button("open").isDisable());
                assertEquals("", r.status());
            });

            Files.delete(file);
            FxTestSupport.runOnFx(() -> r.card.refresh(null));
            r.await(
                    "the card to notice the deletion",
                    () -> tr("project.map.preview.missing").equals(r.status()));
            assertEquals(
                    "now it exists\n",
                    FxTestSupport.callOnFx(() -> r.card.editor().getText()),
                    "the text stays: it may be the last copy the user can read");
        }
    }

    @Test
    void aFileChangedOnDiskIsReadAgainOnlyWhenItReallyChanged(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("log.txt"), "first\n");
        try (Rig r = new Rig()) {
            FxTestSupport.runOnFx(() -> r.card.showFile(file, Content.closed(null), null));
            r.awaitText("first\n");

            FxTestSupport.runOnFx(() -> {
                r.card.editor().selectRange(1, 3);
                r.card.refresh(Content.closed(null)); // unchanged on disk
            });
            r.await("the unchanged check to finish", () -> !(boolean) FxTestSupport.field(r.card, "statPending"));
            assertEquals(
                    "1,3",
                    FxTestSupport.callOnFx(() ->
                            r.card.editor().getAnchor() + "," + r.card.editor().getCaretPosition()),
                    "an unchanged file leaves the card exactly as it was");

            Files.writeString(file, "first\nand a second line\n");
            FxTestSupport.runOnFx(() -> r.card.refresh(Content.closed(null)));
            r.awaitText("first\nand a second line\n");
            assertEquals(
                    "1,3",
                    FxTestSupport.callOnFx(() ->
                            r.card.editor().getAnchor() + "," + r.card.editor().getCaretPosition()),
                    "text appended after the selection does not move it");
        }
    }

    @Test
    void anOpenBufferIsMirroredEditByEditAndFallsBackToDiskWhenItsTabCloses(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("draft.txt"), "saved on disk\n");
        try (Rig r = new Rig()) {
            FxTestSupport.runOnFx(() -> {
                r.card.showFile(
                        file,
                        new Content("alpha beta gamma\n", false),
                        (w, h, pw, ph) -> new ProjectMapPreview.Placement(50, 60, 700, 300, true));
                assertEquals("alpha beta gamma\n", r.card.editor().getText(), "an open buffer's text needs no read");
                assertEquals("", r.status());
                assertTrue(r.card.showsOpenBuffer());
                assertEquals(50, r.card.getLayoutX());
                assertEquals(60, r.card.getLayoutY());
                assertEquals(300, r.card.getHeight(), "where and how tall the map said");

                r.card.editor().selectRange(11, 16); // "gamma"
                r.card.refresh(new Content("alpha BETA! gamma\n", false));
                assertEquals("alpha BETA! gamma\n", r.card.editor().getText());
                assertEquals("gamma", r.card.editor().getSelectedText(), "a selection after the edit moves with it");

                r.card.editor().selectRange(0, 5);
                r.card.refresh(new Content("alpha BETA! gamma\n", false)); // nothing changed
                assertEquals("alpha", r.card.editor().getSelectedText());

                r.card.editor().selectRange(7, 9); // inside the word that is about to change
                r.card.refresh(new Content("alpha b gamma\n", false));
                assertEquals(6, r.card.editor().getCaretPosition(), "a caret inside the edit lands where it began");

                r.card.refresh(new Content("alpha b gamma, cut here", true));
                assertEquals(tr("project.map.preview.truncated"), r.status());

                r.card.refresh(Content.closed(null)); // its tab was closed
                assertFalse(r.card.showsOpenBuffer());
            });
            r.awaitText("saved on disk\n");
        }
    }

    @Test
    void escapeClosesTheCardAndTabLeavesTheText(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("a.txt"), "text\n");
        try (Rig r = new Rig()) {
            List<String> calls = new ArrayList<>();
            FxTestSupport.runOnFx(() -> {
                r.card.showFile(file, new Content("text\n", false), null);
                r.card.setOnEscape(() -> calls.add("escape"));
                r.card.setOnClose(() -> calls.add("close"));

                assertFalse(r.press(KeyCode.ESCAPE, true, false), "Shift+Escape is not the card's key");
                assertFalse(r.press(KeyCode.ESCAPE, false, true), "nor is a chord with Control");
                assertFalse(r.press(KeyCode.A, false, false));
                assertTrue(calls.isEmpty());

                assertTrue(r.press(KeyCode.ESCAPE, false, false));
                assertEquals(List.of("escape"), calls);
                assertTrue(r.press(KeyCode.TAB, false, false), "Tab moves on instead of typing into a read-only area");
                assertTrue(r.press(KeyCode.TAB, true, false));

                r.button("close").fire();
                assertEquals(List.of("escape", "close"), calls);

                // Without callbacks Escape closes, and closing hides the card.
                r.card.setOnEscape(null);
                r.card.setOnClose(null);
                r.card.setOnActivate(null);
                r.card.setOnTouch(null);
                r.press(KeyCode.ESCAPE, false, false);
                assertFalse(r.card.isVisible());
                assertNull(r.card.path());
                assertEquals("", r.card.editor().getText());
            });
        }
    }

    @Test
    void theContextMenuCopiesAndAddsMarkersForTheLineOrSelection(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("a.txt"), "x");
        try (Rig r = new Rig()) {
            List<String> marks = new ArrayList<>();
            List<NoteDraft> drafts = new ArrayList<>();
            boolean[] notes = {false};
            FxTestSupport.runOnFx(() -> {
                r.card.setMarkerActions(new ProjectMapPreview.MarkerActions() {
                    @Override
                    public boolean personalNotesEnabled() {
                        return notes[0];
                    }

                    @Override
                    public void addBookmark(Path target, int line) {
                        marks.add(target.getFileName() + ":" + line);
                    }

                    @Override
                    public void addPersonalNote(Path target, NoteDraft draft) {
                        drafts.add(draft);
                    }
                });
                r.card.showFile(file, new Content("", false), null);
                r.rebuildMenu(0);
                assertTrue(r.item("editmenu.copy").isDisable(), "nothing to copy from an empty card");
                assertTrue(r.item("editmenu.selectAll").isDisable());
                assertNull(r.item("editmenu.addBookmark"), "and no line to mark");

                r.card.refresh(new Content("first line\nsecond line\nthird\n", false));
                r.rebuildMenu(1);
                assertFalse(r.item("editmenu.copy").isDisable());
                assertTrue(r.item("editmenu.addNote").isDisable(), "Personal Notes are switched off");
                r.item("editmenu.addBookmark").fire();
                assertEquals(List.of("a.txt:1"), marks);

                notes[0] = true;
                r.rebuildMenu(1);
                r.item("editmenu.addNote").fire();
                NoteDraft line = drafts.get(0);
                assertEquals(NoteScope.LINE, line.scope());
                assertEquals("second line", line.anchor().selectedText());
                assertEquals(1, line.anchor().line());
                assertEquals("first line\n", line.anchor().prefix());
                assertEquals("\nthird\n", line.anchor().suffix());

                r.card.editor().selectRange(6, 10); // "line" on the first line
                r.rebuildMenu(2);
                r.item("editmenu.addNote").fire();
                assertEquals(NoteScope.WORD, drafts.get(1).scope(), "a selection within one line");
                assertEquals("line", drafts.get(1).anchor().selectedText());
                assertEquals(0, drafts.get(1).anchor().line());
                assertEquals(6, drafts.get(1).anchor().column());

                r.card.editor().selectRange(6, 17); // across the line break
                r.rebuildMenu(0);
                r.item("editmenu.addNote").fire();
                assertEquals(NoteScope.RANGE, drafts.get(2).scope());
                assertEquals(1, drafts.get(2).anchor().endLine());

                r.rebuildMenu(99);
                r.card.editor().deselect();
                r.rebuildMenu(99);
                r.item("editmenu.addNote").fire();
                assertEquals("", drafts.get(3).anchor().selectedText(), "a click below the text means its last line");

                r.item("editmenu.selectAll").fire();
                assertEquals("first line\nsecond line\nthird\n", r.card.editor().getSelectedText());
            });
        }
    }

    @Test
    void aRightClickOpensTheMenuAndAClickInTheTextDismissesIt(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("a.txt"), "x");
        try (Rig r = new Rig()) {
            List<String> calls = new ArrayList<>();
            FxTestSupport.runOnFx(() -> {
                r.card.showFile(file, new Content("one\ntwo\n", false), null);
                r.card.setOnEscape(() -> calls.add("escape"));
                r.host.applyCss();
                r.host.layout();

                ContextMenuEvent request =
                        new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 5, 5, 300, 300, false, null);
                r.card.editor().getOnContextMenuRequested().handle(request);
                assertTrue(request.isConsumed());
                assertTrue(r.menu().isShowing());
                assertNotNull(r.item("editmenu.copy"));

                javafx.event.Event.fireEvent(
                        r.card.editor(), mouse(MouseEvent.MOUSE_PRESSED, MouseButton.PRIMARY, 10, 10, true));
                assertFalse(r.menu().isShowing(), "a click in the text dismisses it too");
            });
        }
    }

    @Test
    void theCardIsDraggedByItsTitleBarAndStaysInsideThePanel(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("a.txt"), "x");
        try (Rig r = new Rig()) {
            List<String> calls = new ArrayList<>();
            FxTestSupport.runOnFx(() -> {
                r.card.setOnActivate(() -> calls.add("activate"));
                r.card.showFile(
                        file,
                        new Content("text\n", false),
                        (w, h, pw, ph) -> new ProjectMapPreview.Placement(100, 100, 700, 300));
                Region bar = FxTestSupport.field(r.card, "titleBar");

                javafx.event.Event.fireEvent(
                        bar, mouse(MouseEvent.MOUSE_PRESSED, MouseButton.SECONDARY, 500, 500, false));
                javafx.event.Event.fireEvent(
                        bar, mouse(MouseEvent.MOUSE_DRAGGED, MouseButton.SECONDARY, 600, 600, false));
                assertEquals(100, r.card.getLayoutX(), "a right-button drag moves nothing");
                assertTrue(calls.contains("activate"), "but any press on the card brings it forward");

                javafx.event.Event.fireEvent(bar, mouse(MouseEvent.MOUSE_PRESSED, MouseButton.PRIMARY, 500, 500, true));
                javafx.event.Event.fireEvent(bar, mouse(MouseEvent.MOUSE_DRAGGED, MouseButton.PRIMARY, 550, 530, true));
                assertEquals(150, r.card.getLayoutX());
                assertEquals(130, r.card.getLayoutY());

                javafx.event.Event.fireEvent(
                        bar, mouse(MouseEvent.MOUSE_DRAGGED, MouseButton.PRIMARY, 5000, 5000, true));
                assertEquals(HOST_W - r.card.getWidth() - ProjectMapPreview.EDGE_MARGIN, r.card.getLayoutX());
                assertEquals(HOST_H - r.card.getHeight() - ProjectMapPreview.EDGE_MARGIN, r.card.getLayoutY());
                javafx.event.Event.fireEvent(
                        bar, mouse(MouseEvent.MOUSE_DRAGGED, MouseButton.PRIMARY, -5000, -5000, true));
                assertEquals(ProjectMapPreview.EDGE_MARGIN, r.card.getLayoutX());
                assertEquals(ProjectMapPreview.EDGE_MARGIN, r.card.getLayoutY());

                // A press that starts on one of the bar's buttons is that button's click, not a drag.
                double x = r.card.getLayoutX();
                javafx.event.Event.fireEvent(
                        r.button("open"), mouse(MouseEvent.MOUSE_PRESSED, MouseButton.PRIMARY, 0, 0, true));
                javafx.event.Event.fireEvent(bar, mouse(MouseEvent.MOUSE_DRAGGED, MouseButton.PRIMARY, 0, 0, false));
                assertEquals(x, r.card.getLayoutX());
            });
        }
    }

    @Test
    void theGripResizesTheCardBetweenItsMinimumAndTheRoomThereIs(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("a.txt"), "x");
        try (Rig r = new Rig()) {
            FxTestSupport.runOnFx(() -> {
                r.card.showFile(
                        file,
                        new Content("text\n", false),
                        (w, h, pw, ph) -> new ProjectMapPreview.Placement(100, 100, 700, 300));
                Region grip = FxTestSupport.field(r.card, "resizeGrip");

                javafx.event.Event.fireEvent(grip, mouse(MouseEvent.MOUSE_PRESSED, MouseButton.SECONDARY, 0, 0, false));
                javafx.event.Event.fireEvent(
                        grip, mouse(MouseEvent.MOUSE_DRAGGED, MouseButton.SECONDARY, 90, 90, false));
                assertEquals(700, r.card.getWidth(), "only the primary button resizes");

                javafx.event.Event.fireEvent(
                        grip, mouse(MouseEvent.MOUSE_PRESSED, MouseButton.PRIMARY, 500, 400, true));
                javafx.event.Event.fireEvent(
                        grip, mouse(MouseEvent.MOUSE_DRAGGED, MouseButton.PRIMARY, 600, 450, true));
                assertEquals(800, r.card.getWidth());
                assertEquals(350, r.card.getHeight());

                javafx.event.Event.fireEvent(
                        grip, mouse(MouseEvent.MOUSE_DRAGGED, MouseButton.PRIMARY, -900, -900, true));
                assertEquals(ProjectMapPreview.MIN_WIDTH, r.card.getWidth());
                assertEquals(ProjectMapPreview.MIN_HEIGHT, r.card.getHeight());

                javafx.event.Event.fireEvent(
                        grip, mouse(MouseEvent.MOUSE_DRAGGED, MouseButton.PRIMARY, 9000, 9000, true));
                assertEquals(HOST_W - 100 - ProjectMapPreview.EDGE_MARGIN, r.card.getWidth(), "up to the panel's edge");
                assertEquals(HOST_H - 100 - ProjectMapPreview.EDGE_MARGIN, r.card.getHeight());

                r.card.constrainTo(500, 400);
                assertEquals(500 - 2 * ProjectMapPreview.EDGE_MARGIN, r.card.getWidth(), "a shrunken panel shrinks it");
                assertEquals(ProjectMapPreview.EDGE_MARGIN, r.card.getLayoutX());
                double width = r.card.getWidth();
                r.card.constrainTo(0, 0);
                assertEquals(width, r.card.getWidth(), "a panel with no size yet constrains nothing");
            });
        }
    }

    @Test
    void aDisposedCardIgnoresEverythingAfterwards(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("a.txt"), "x");
        try (Rig r = new Rig()) {
            FxTestSupport.runOnFx(() -> {
                r.card.showFile(file, new Content("text\n", false), null);
                r.card.dispose();
                assertFalse(r.card.isVisible());

                r.card.showFile(file, new Content("again\n", false), null);
                r.card.refresh(new Content("again\n", false));
                assertFalse(r.card.isVisible());
                assertEquals("", r.card.editor().getText());

                new ProjectMapPreview(null).refresh(Content.closed(null)); // never shown: nothing to refresh
            });
        }
    }

    // --- the pure helpers ---

    @Test
    void aReadCutMidCharacterDropsTheHalfCharacterInsteadOfRejectingTheFile() {
        byte[] euro = "a€".getBytes(StandardCharsets.UTF_8); // 61 E2 82 AC
        assertArrayEquals(
                new byte[] {'a'}, ProjectMapPreview.trimIncompleteTail(java.util.Arrays.copyOf(euro, 2), null));
        assertArrayEquals(
                new byte[] {'a'}, ProjectMapPreview.trimIncompleteTail(java.util.Arrays.copyOf(euro, 3), null));
        assertSame(euro, ProjectMapPreview.trimIncompleteTail(euro, null), "a whole character is kept as it is");
        byte[] ascii = "plain".getBytes(StandardCharsets.UTF_8);
        assertSame(ascii, ProjectMapPreview.trimIncompleteTail(ascii, null));
        assertEquals(0, ProjectMapPreview.trimIncompleteTail(new byte[0], null).length);
        byte[] emoji = "😀".getBytes(StandardCharsets.UTF_8); // four bytes
        assertEquals(0, ProjectMapPreview.trimIncompleteTail(java.util.Arrays.copyOf(emoji, 3), null).length);
        byte[] accent = "é".getBytes(StandardCharsets.UTF_8); // two bytes
        assertEquals(0, ProjectMapPreview.trimIncompleteTail(java.util.Arrays.copyOf(accent, 1), null).length);

        assertEquals("a", ProjectMapPreview.decode(java.util.Arrays.copyOf(euro, 3), true, null));
        assertEquals(
                "one\ntwo\n", ProjectMapPreview.decode("one\r\ntwo\r\n".getBytes(StandardCharsets.UTF_8), false, null));
    }

    @Test
    void aUtf16ReadCutMidUnitOrMidSurrogatePairIsTrimmedToWholeCharacters() {
        byte[] le = "a😀".getBytes(StandardCharsets.UTF_16LE); // 61 00 | 3D D8 | 00 DE
        assertArrayEquals(
                java.util.Arrays.copyOf(le, 2),
                ProjectMapPreview.trimIncompleteTail(java.util.Arrays.copyOf(le, 3), "utf-16le"),
                "half a code unit");
        assertArrayEquals(
                java.util.Arrays.copyOf(le, 2),
                ProjectMapPreview.trimIncompleteTail(java.util.Arrays.copyOf(le, 4), "utf-16le"),
                "a high surrogate without its low half");
        assertSame(le, ProjectMapPreview.trimIncompleteTail(le, "utf-16le"));

        byte[] be = "a😀".getBytes(StandardCharsets.UTF_16BE); // 00 61 | D8 3D | DE 00
        assertArrayEquals(
                java.util.Arrays.copyOf(be, 2),
                ProjectMapPreview.trimIncompleteTail(java.util.Arrays.copyOf(be, 4), "utf-16be"));
        assertSame(be, ProjectMapPreview.trimIncompleteTail(be, "utf-16be"));
        assertEquals(0, ProjectMapPreview.trimIncompleteTail(new byte[] {0x61}, "utf-16le").length);

        byte[] latin = {'a', (byte) 0xE9};
        assertSame(latin, ProjectMapPreview.trimIncompleteTail(latin, "latin1"), "a single-byte charset has no halves");
    }

    @Test
    void theDisplayCapNeverEndsOnHalfASurrogatePair() {
        assertSame("short", ProjectMapPreview.capText("short", false));
        String cutByCaller = "abc" + "😀".charAt(0);
        assertEquals("abc", ProjectMapPreview.capText(cutByCaller, true));
        assertSame(cutByCaller, ProjectMapPreview.capText(cutByCaller, false), "not cut: left exactly as it is");

        String filler = "x".repeat(ProjectMapPreview.MAX_PREVIEW_CHARS - 1);
        assertEquals(filler, ProjectMapPreview.capText(filler + "😀" + "tail", false), "the pair straddles the cap");
        assertEquals(
                ProjectMapPreview.MAX_PREVIEW_CHARS,
                ProjectMapPreview.capText(filler + "ab", false).length());
        assertEquals("", ProjectMapPreview.capText("", true));
    }

    @Test
    void aSizeIsHeldBetweenTheMinimumAndTheRoomAndTheRoomWinsWhenSmaller() {
        assertEquals(500, ProjectMapPreview.boundedSize(500, 340, 900));
        assertEquals(340, ProjectMapPreview.boundedSize(100, 340, 900), "never under the minimum");
        assertEquals(900, ProjectMapPreview.boundedSize(5000, 340, 900), "never over the room");
        assertEquals(200, ProjectMapPreview.boundedSize(500, 340, 200), "a panel narrower than the minimum wins");
        assertEquals(1, ProjectMapPreview.boundedSize(500, 340, -40), "and no room at all is one pixel, not negative");
        assertEquals(5, ProjectMapPreview.clamp(5, 0, 10));
        assertEquals(0, ProjectMapPreview.clamp(-3, 0, 10));
        assertEquals(10, ProjectMapPreview.clamp(30, 0, 10));
    }

    @Test
    void contentFromTheEditorDefaultsToAnOpenBufferAndNullTextIsEmpty() {
        Content open = new Content(null, false);
        assertEquals("", open.text());
        assertTrue(open.open());
        assertNull(open.editorConfigCharset());
        Content closed = Content.closed("latin1");
        assertFalse(closed.open());
        assertEquals("latin1", closed.editorConfigCharset());
        assertFalse(new ProjectMapPreview.Placement(1, 2, 3, 4).growLeft());
    }
}
