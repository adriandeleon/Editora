package com.editora.editor;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Scene;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;

import com.editora.config.NoteScope;
import com.editora.config.PersonalNote;
import com.editora.config.TextAnchor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins Personal Notes hover hit-testing to CodeArea coordinates when the event originated in a child node. */
@Tag("fx")
class PersonalNoteHoverFxTest {

    @Test
    void hoverUsesScreenPositionInsteadOfTheEventSourcesLocalCoordinates() throws Exception {
        FxToolkit.registerPrimaryStage();
        AtomicReference<EditorBuffer> bufferRef = new AtomicReference<>();
        AtomicReference<PersonalNote> noteRef = new AtomicReference<>();
        AtomicReference<Bounds> targetRef = new AtomicReference<>();

        onFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.getArea().replaceText("first line\nhello target world");
            PersonalNote note = PersonalNote.create(
                    null,
                    NoteScope.WORD,
                    new TextAnchor(1, 6, 1, 12, "target", "first line\nhello ", " world"),
                    "Hover body",
                    List.of());
            buffer.applyNotes(List.of(note));

            Stage stage = new Stage();
            stage.setX(180);
            stage.setY(140);
            stage.setScene(new Scene(buffer.getNode(), 700, 360));
            stage.show();
            buffer.getArea().requestFollowCaret();
            buffer.getArea().layout();

            Bounds target = buffer.getArea()
                    .getCharacterBoundsOnScreen(17, 18)
                    .orElseThrow(() -> new AssertionError("target character must be visible"));
            bufferRef.set(buffer);
            noteRef.set(note);
            targetRef.set(target);
        });

        onFx(() -> {
            Bounds target = targetRef.get();
            // (0,0) deliberately represents a child node's unrelated local coordinates. The screen
            // position points at the annotated word and is the coordinate space that survives bubbling.
            MouseEvent moved = new MouseEvent(
                    MouseEvent.MOUSE_MOVED,
                    0,
                    0,
                    target.getCenterX(),
                    target.getCenterY(),
                    MouseButton.NONE,
                    0,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    null);
            bufferRef.get().getArea().fireEvent(moved);

            assertEquals(noteRef.get().id(), field(bufferRef.get(), "hoverNoteId"));
            Tooltip tooltip = field(bufferRef.get(), "noteTip");
            assertTrue(tooltip.isShowing(), "hovering the annotated text must show its tooltip");
            assertNotNull(tooltip.getGraphic(), "the note body is rendered into the tooltip");
            tooltip.hide();
            bufferRef.get().dispose();
            ((Stage) bufferRef.get().getNode().getScene().getWindow()).close();
        });
    }

    private static void onFx(Runnable task) throws Exception {
        if (Platform.isFxApplicationThread()) {
            task.run();
            return;
        }
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                task.run();
            } catch (Throwable failure) {
                error.set(failure);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(60, TimeUnit.SECONDS)) {
            throw new IllegalStateException("timed out on the FX thread");
        }
        if (error.get() != null) {
            throw new RuntimeException(error.get());
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(target);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}
