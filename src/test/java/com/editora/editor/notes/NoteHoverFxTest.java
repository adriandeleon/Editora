package com.editora.editor.notes;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javafx.event.Event;
import javafx.event.EventType;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Scene;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.config.NoteScope;
import com.editora.config.PersonalNote;
import com.editora.config.TextAnchor;
import com.editora.editor.EditorFx;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pointer over a note ({@link NoteHover}): a click on its start marker opens it, a click beside the marker or
 * with the indicators off does nothing, hovering it shows its body and moving off or leaving the area hides it.
 */
@Tag("fx")
class NoteHoverFxTest {

    private static final String TEXT = "alpha beta gamma\ndelta epsilon\n";

    @BeforeAll
    static void boot() throws Exception {
        EditorFx.boot();
    }

    /** A note on the word at {@code start}..{@code end} of line 0. */
    private static PersonalNote note(int start, int end, String body) {
        return PersonalNote.create(
                null,
                NoteScope.WORD,
                new TextAnchor(
                        0, start, 0, end, TEXT.substring(start, end), TEXT.substring(0, start), TEXT.substring(end)),
                body,
                List.of());
    }

    private static MouseEvent event(
            CodeArea area, EventType<MouseEvent> type, double screenX, double screenY, int clicks) {
        Point2D local = area.screenToLocal(screenX, screenY);
        return new MouseEvent(
                area,
                area,
                type,
                local.getX(),
                local.getY(),
                screenX,
                screenY,
                MouseButton.PRIMARY,
                clicks,
                false,
                false,
                false,
                false,
                true,
                false,
                false,
                true,
                false,
                true,
                null);
    }

    @Test
    void aClickOnTheMarkerOpensTheNoteAndTheHoverShowsAndHidesItsBody() throws Exception {
        EditorFx.onFx(() -> {
            CodeArea area = new CodeArea(TEXT);
            NoteManager notes = new NoteManager(area);
            PersonalNote beta = note(6, 10, "**beta** note");
            notes.add(beta);
            List<PersonalNote> opened = new ArrayList<>();
            AtomicBoolean shown = new AtomicBoolean(true);
            NoteHover hover = new NoteHover(notes, shown::get, opened::add, () -> "monospace", () -> 14, () -> null);
            hover.install(area);
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(area), 500, 200));
            stage.show();
            try {
                area.applyCss();
                area.layout();
                assertTrue(notes.hasActiveSpans());
                Bounds marker = NoteHighlightOverlay.markerBoundsOnScreen(area, 6);
                assertNotNull(marker);
                double x = marker.getMinX() + 2;
                double y = marker.getMinY() + 2;

                Event.fireEvent(area, event(area, MouseEvent.MOUSE_CLICKED, x, y, 2));
                assertTrue(opened.isEmpty(), "a double click is not the marker's");
                Event.fireEvent(area, event(area, MouseEvent.MOUSE_CLICKED, x + 40, y, 1));
                assertTrue(opened.isEmpty(), "beside the marker: the click places the caret");
                Event.fireEvent(area, event(area, MouseEvent.MOUSE_CLICKED, x, y, 1));
                assertEquals(List.of(beta), opened);

                Event.fireEvent(area, event(area, MouseEvent.MOUSE_MOVED, x + 4, y + 2, 0));
                Tooltip tip = EditorFx.field(hover, "tip");
                assertNotNull(tip, "hovering the note builds its popup");
                assertTrue(tip.isShowing());
                assertEquals(beta.id(), EditorFx.field(hover, "shownId"));
                Event.fireEvent(area, event(area, MouseEvent.MOUSE_MOVED, x + 5, y + 2, 0));
                assertTrue(tip.isShowing(), "still on the same note: the popup stays");

                Bounds alpha = area.getCharacterBoundsOnScreen(1, 2).orElseThrow();
                Event.fireEvent(area, event(area, MouseEvent.MOUSE_MOVED, alpha.getCenterX(), alpha.getCenterY(), 0));
                assertFalse(tip.isShowing(), "off the note: hidden");
                assertNull(EditorFx.field(hover, "shownId"));

                Event.fireEvent(area, event(area, MouseEvent.MOUSE_MOVED, x + 4, y + 2, 0));
                assertTrue(tip.isShowing());
                Event.fireEvent(area, event(area, MouseEvent.MOUSE_EXITED, x + 4, y + 2, 0));
                assertFalse(tip.isShowing(), "leaving the area hides it");

                shown.set(false);
                Event.fireEvent(area, event(area, MouseEvent.MOUSE_CLICKED, x, y, 1));
                assertEquals(1, opened.size(), "indicators off: the marker is not there to click");
                Event.fireEvent(area, event(area, MouseEvent.MOUSE_MOVED, x + 4, y + 2, 0));
                assertFalse(tip.isShowing(), "indicators off: no popup");
            } finally {
                hover.hide();
                stage.close();
            }
        });
    }
}
