package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import com.editora.config.PersonalNote;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Project Map's editable Personal Notes card on its own: what it lists, when an edit is saved, what a
 * blanked note does, how it follows the store while the user is typing, and its keys, drag and resize.
 */
@Tag("fx")
class ProjectMapNoteCardFxTest {

    private static final double HOST_W = 900;
    private static final double HOST_H = 600;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static PersonalNote note(String body) {
        return new PersonalNote(UUID.randomUUID(), null, null, null, body, null, null, 1, 1);
    }

    private static PersonalNote withBody(PersonalNote note, String body) {
        return new PersonalNote(
                note.id(), note.file(), note.scope(), note.anchor(), body, note.tags(), note.status(), 1, 2);
    }

    /** A note card in a 900x600 host, recording what it saves. */
    private static final class Rig {
        final List<String> saved = new ArrayList<>();
        final List<String> calls = new ArrayList<>();
        final ProjectMapNotePreview card = new ProjectMapNotePreview((note, body) -> saved.add(note.id() + "=" + body));
        final Pane host = new Pane(card);
        final Stage stage = new Stage();

        Rig() {
            stage.setScene(new Scene(host, HOST_W, HOST_H));
            stage.show();
        }

        void show(List<PersonalNote> notes) {
            card.showNotes(
                    Path.of("project", "src", "Main.java").toAbsolutePath(),
                    notes,
                    new ProjectMapPreview.Placement(100, 80, 420, 300));
            host.applyCss();
            host.layout();
        }

        List<TextArea> editors() {
            VBox notes = FxTestSupport.field(card, "notes");
            return notes.getChildren().stream()
                    .filter(TextArea.class::isInstance)
                    .map(TextArea.class::cast)
                    .toList();
        }

        void saveWithShortcut(TextArea editor) {
            javafx.event.Event.fireEvent(
                    editor, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, true, false, true));
        }

        void dispose() {
            stage.close();
        }
    }

    private static MouseEvent mouse(
            javafx.event.EventType<MouseEvent> type, MouseButton button, double screenX, double screenY, boolean down) {
        return new MouseEvent(
                type, 0, 0, screenX, screenY, button, 1, false, false, false, false, down, false, false, true, false,
                false, null);
    }

    @Test
    void theCardListsEachNoteInItsOwnEditorOrSaysThereAreNone() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = new Rig();
            assertFalse(r.card.isVisible());

            r.show(List.of(note("first\nsecond\nthird"), note("short")));
            assertTrue(r.card.isVisible());
            assertEquals(
                    "Main.java", FxTestSupport.<Label>field(r.card, "title").getText());
            assertEquals(
                    List.of("first\nsecond\nthird", "short"),
                    r.editors().stream().map(TextArea::getText).toList());
            assertEquals(4, r.editors().get(0).getPrefRowCount(), "a row taller than its note by one line");
            assertEquals(3, r.editors().get(1).getPrefRowCount(), "but never under three");
            assertEquals(100, r.card.getLayoutX());
            assertEquals(420, r.card.getWidth());

            r.card.focusContent();
            assertSame(r.editors().get(0), r.stage.getScene().getFocusOwner());

            r.show(null);
            assertTrue(r.editors().isEmpty());
            VBox notes = FxTestSupport.field(r.card, "notes");
            assertEquals(
                    tr("project.map.notes.empty"), ((Label) notes.getChildren().get(0)).getText());
            r.card.focusContent();
            assertSame(
                    FxTestSupport.<Button>field(r.card, "close"),
                    r.stage.getScene().getFocusOwner(),
                    "with no note to edit the focus goes to the card's Close button");
            r.dispose();
        });
    }

    @Test
    void anEditIsSavedOnTheShortcutAndOnLeavingTheNoteButOnlyWhenItChanged() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = new Rig();
            PersonalNote first = note("first");
            PersonalNote second = note("second");
            r.show(List.of(first, second));
            TextArea a = r.editors().get(0);
            TextArea b = r.editors().get(1);

            r.saveWithShortcut(a);
            assertTrue(r.saved.isEmpty(), "nothing changed, nothing saved");

            a.setText("  first, revised  ");
            r.saveWithShortcut(a);
            assertEquals(List.of(first.id() + "=first, revised"), r.saved, "saved trimmed");
            r.saveWithShortcut(a);
            assertEquals(1, r.saved.size(), "the same text is not saved twice");

            b.requestFocus();
            b.setText("second, revised");
            a.requestFocus(); // leaving the note saves it
            assertEquals(List.of(first.id() + "=first, revised", second.id() + "=second, revised"), r.saved);

            javafx.event.Event.fireEvent(
                    a, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
            assertEquals(2, r.saved.size(), "a plain Enter is a new line, not a save");
            r.dispose();
        });
    }

    @Test
    void blankingANoteDoesNotDeleteItTheTextComesBackAndTheOwnerIsTold() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = new Rig();
            r.card.setOnBlankRejected(() -> r.calls.add("blank rejected"));
            r.show(List.of(note("keep me"), note("")));
            TextArea kept = r.editors().get(0);
            TextArea empty = r.editors().get(1);

            kept.setText("   ");
            r.saveWithShortcut(kept);
            assertEquals("keep me", kept.getText(), "deleting a note is the Notes panel's job");
            assertEquals(List.of("blank rejected"), r.calls);
            assertTrue(r.saved.isEmpty());

            empty.setText(" ");
            r.saveWithShortcut(empty);
            assertEquals(1, r.calls.size(), "a note that was already empty has nothing to restore");
            assertTrue(r.saved.isEmpty());
            r.dispose();
        });
    }

    @Test
    void aRefreshFromTheStoreKeepsAnEditInProgressAndAdoptsEverythingElse() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = new Rig();
            PersonalNote first = note("first");
            PersonalNote second = note("second");
            r.show(List.of(first, second));
            r.editors().get(0).setText("first, being typed");

            // The same notes, the second changed elsewhere (the Notes panel).
            r.card.refreshNotes(List.of(first, withBody(second, "second, changed elsewhere")));
            assertEquals("first, being typed", r.editors().get(0).getText(), "typing is never interrupted");
            assertEquals("second, changed elsewhere", r.editors().get(1).getText());
            TextArea sameEditor = r.editors().get(1);

            // A note was added: the rows are rebuilt, the edit in progress carried over.
            PersonalNote third = note("third");
            r.card.refreshNotes(List.of(first, withBody(second, "second, changed elsewhere"), third));
            assertEquals(
                    List.of("first, being typed", "second, changed elsewhere", "third"),
                    r.editors().stream().map(TextArea::getText).toList());
            assertTrue(r.editors().get(1) != sameEditor, "rebuilt because the set of notes changed");

            r.saveWithShortcut(r.editors().get(0));
            assertEquals(List.of(first.id() + "=first, being typed"), r.saved);

            // The store answers a save with the note as it now is: the row adopts it and is no longer an edit.
            r.card.refreshNotes(List.of(withBody(first, "first, being typed"), second, third));
            r.saveWithShortcut(r.editors().get(0));
            assertEquals(1, r.saved.size());

            r.card.refreshNotes(null);
            assertTrue(r.editors().isEmpty(), "every note deleted elsewhere");
            r.dispose();
        });
    }

    @Test
    void closingTheCardSavesWhatWasStillBeingTyped() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = new Rig();
            PersonalNote first = note("first");
            r.show(List.of(first, note("untouched")));
            r.editors().get(0).setText("first, unsaved");

            r.card.dispose();

            assertEquals(List.of(first.id() + "=first, unsaved"), r.saved);
            assertTrue(r.editors().isEmpty());
            r.dispose();
        });
    }

    @Test
    void escapeAndTheCloseButtonGoToTheirCallbacksAndDefaultToHidingTheCard() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = new Rig();
            r.show(List.of(note("note")));
            r.card.setOnEscape(() -> r.calls.add("escape"));
            r.card.setOnClose(() -> r.calls.add("close"));
            TextArea editor = r.editors().get(0);

            javafx.event.Event.fireEvent(
                    editor, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, true, false, false, false));
            javafx.event.Event.fireEvent(
                    editor, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, true, false, false));
            javafx.event.Event.fireEvent(
                    editor, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, true, false));
            javafx.event.Event.fireEvent(
                    editor, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, true));
            assertTrue(r.calls.isEmpty(), "Escape with a modifier is somebody else's chord");

            javafx.event.Event.fireEvent(
                    editor, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
            FxTestSupport.<Button>field(r.card, "close").fire();
            assertEquals(List.of("escape", "close"), r.calls);

            r.card.setOnEscape(null);
            r.card.setOnClose(null);
            r.card.setOnActivate(null);
            r.card.setOnTouch(null);
            r.card.setOnBlankRejected(null);
            javafx.event.Event.fireEvent(
                    editor, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
            assertFalse(r.card.isVisible(), "with no owner Escape simply hides the card");
            r.dispose();
        });
    }

    @Test
    void theCardIsDraggedAndResizedInsideThePanel() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = new Rig();
            r.card.setOnActivate(() -> r.calls.add("activate"));
            r.show(List.of(note("note")));
            Region bar = FxTestSupport.field(r.card, "titleBar");
            Region grip = FxTestSupport.field(r.card, "resizeGrip");
            double margin = ProjectMapPreview.EDGE_MARGIN;

            javafx.event.Event.fireEvent(bar, mouse(MouseEvent.MOUSE_PRESSED, MouseButton.SECONDARY, 300, 300, false));
            javafx.event.Event.fireEvent(bar, mouse(MouseEvent.MOUSE_DRAGGED, MouseButton.SECONDARY, 400, 400, false));
            assertEquals(100, r.card.getLayoutX(), "only the primary button drags");
            assertTrue(r.calls.contains("activate"), "any press brings the card forward");

            javafx.event.Event.fireEvent(bar, mouse(MouseEvent.MOUSE_PRESSED, MouseButton.PRIMARY, 300, 300, true));
            javafx.event.Event.fireEvent(bar, mouse(MouseEvent.MOUSE_DRAGGED, MouseButton.PRIMARY, 340, 320, true));
            assertEquals(140, r.card.getLayoutX());
            assertEquals(100, r.card.getLayoutY());
            javafx.event.Event.fireEvent(bar, mouse(MouseEvent.MOUSE_DRAGGED, MouseButton.PRIMARY, 9000, 9000, true));
            assertEquals(HOST_W - 420 - margin, r.card.getLayoutX());
            assertEquals(HOST_H - 300 - margin, r.card.getLayoutY());
            javafx.event.Event.fireEvent(bar, mouse(MouseEvent.MOUSE_DRAGGED, MouseButton.PRIMARY, -9000, -9000, true));
            assertEquals(margin, r.card.getLayoutX());
            assertEquals(margin, r.card.getLayoutY());

            javafx.event.Event.fireEvent(grip, mouse(MouseEvent.MOUSE_PRESSED, MouseButton.SECONDARY, 0, 0, false));
            javafx.event.Event.fireEvent(grip, mouse(MouseEvent.MOUSE_DRAGGED, MouseButton.SECONDARY, 50, 50, false));
            assertEquals(420, r.card.getWidth(), "only the primary button resizes");

            javafx.event.Event.fireEvent(grip, mouse(MouseEvent.MOUSE_PRESSED, MouseButton.PRIMARY, 500, 400, true));
            javafx.event.Event.fireEvent(grip, mouse(MouseEvent.MOUSE_DRAGGED, MouseButton.PRIMARY, 560, 430, true));
            assertEquals(480, r.card.getWidth());
            assertEquals(330, r.card.getHeight());
            javafx.event.Event.fireEvent(grip, mouse(MouseEvent.MOUSE_DRAGGED, MouseButton.PRIMARY, -900, -900, true));
            assertEquals(ProjectMapNotePreview.MIN_WIDTH, r.card.getWidth());
            assertEquals(ProjectMapNotePreview.MIN_HEIGHT, r.card.getHeight());
            javafx.event.Event.fireEvent(grip, mouse(MouseEvent.MOUSE_DRAGGED, MouseButton.PRIMARY, 9000, 9000, true));
            assertEquals(HOST_W - 2 * margin, r.card.getWidth(), "up to the panel's edge");

            r.card.constrainTo(400, 300);
            assertEquals(400 - 2 * margin, r.card.getWidth(), "a shrunken panel shrinks the card");
            assertEquals(300 - 2 * margin, r.card.getHeight());
            assertEquals(margin, r.card.getLayoutX());
            double width = r.card.getWidth();
            r.card.constrainTo(0, 0);
            assertEquals(width, r.card.getWidth(), "a panel with no size yet constrains nothing");

            ProjectMapNotePreview neverShown = new ProjectMapNotePreview(null);
            neverShown.constrainTo(400, 300);
            assertEquals(0, neverShown.getWidth(), "a card that was never placed is left alone");
            r.dispose();
        });
    }
}
