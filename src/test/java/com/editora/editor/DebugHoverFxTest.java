package com.editora.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import javafx.util.Duration;

import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The debugger's value popup (review B7 / D2 / C23): a name in a string or a comment is not evaluated, the
 * mouse must rest on a word before it is, and Show Value at Caret does it from the keyboard.
 */
@Tag("fx")
class DebugHoverFxTest {

    private static final String SOURCE = "total = other.depth + 1  # depth of total\nlabel = \"depth\"\n";

    @AfterEach
    void restore() {
        DebugHover.setDebounceForTest(null);
    }

    private static MouseEvent moveTo(EditorBuffer buffer, int offset) {
        CodeArea area = buffer.getArea();
        Bounds screen = area.getCharacterBoundsOnScreen(offset, offset + 1).orElseThrow();
        Point2D local = area.screenToLocal(screen.getCenterX(), screen.getCenterY());
        return EditorFx.mouseWithShortcut(
                MouseEvent.MOUSE_MOVED, local.getX(), local.getY(), screen.getCenterX(), screen.getCenterY(), false, 0);
    }

    private static EditorBuffer buffer(List<String> asked) {
        EditorBuffer buffer = new EditorBuffer();
        buffer.setLanguageOverride("python");
        buffer.getArea().replaceText(SOURCE);
        int comment = SOURCE.indexOf('#');
        buffer.getArea().setStyle(comment, SOURCE.indexOf('\n'), List.of("comment"));
        int string = SOURCE.indexOf("\"depth\"");
        buffer.getArea().setStyle(string, string + 7, List.of("string"));
        buffer.setDebugHoverEvaluator((expr, sink) -> {
            asked.add(expr);
            sink.accept("v(" + expr + ")");
        });
        return buffer;
    }

    @Test
    void aNameInACommentOrAStringIsNotEvaluatedAndAnAttributeIsEvaluatedWithItsObject() throws Exception {
        DebugHover.setDebounceForTest(Duration.ZERO);
        EditorFx.onFx(() -> {
            List<String> asked = new ArrayList<>();
            EditorBuffer buffer = buffer(asked);
            Stage stage = EditorFx.show(buffer, 700, 300);
            buffer.setDebugHoverActive(true);
            buffer.getArea().fireEvent(moveTo(buffer, SOURCE.indexOf("depth", SOURCE.indexOf('#')) + 1));
            buffer.getArea().fireEvent(moveTo(buffer, SOURCE.indexOf("\"depth\"") + 2));
            assertEquals(List.of(), asked, "words in a comment and in a string are not evaluated");
            buffer.getArea().fireEvent(moveTo(buffer, SOURCE.indexOf("depth") + 1));
            assertEquals(List.of("other.depth"), asked, "the attribute together with its object");
            stage.close();
            buffer.dispose();
        });
    }

    @Test
    void theMouseMustRestOnAWordBeforeItIsEvaluated() throws Exception {
        DebugHover.setDebounceForTest(Duration.millis(150));
        List<String> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
        EditorBuffer[] holder = new EditorBuffer[1];
        Stage[] stage = new Stage[1];
        EditorFx.onFx(() -> {
            holder[0] = buffer(asked);
            stage[0] = EditorFx.show(holder[0], 700, 300);
            holder[0].setDebugHoverActive(true);
            // Crossing three words on the way: none of them is worth an evaluate on its own.
            holder[0].getArea().fireEvent(moveTo(holder[0], 1)); // total
            holder[0].getArea().fireEvent(moveTo(holder[0], SOURCE.indexOf("other") + 1));
            holder[0].getArea().fireEvent(moveTo(holder[0], SOURCE.indexOf("depth") + 1)); // rests here
            assertEquals(List.of(), asked, "nothing evaluated while the mouse is moving");
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (asked.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        Thread.sleep(300); // and nothing else arrives after the one it rested on
        assertEquals(List.of("other.depth"), asked);
        EditorFx.onFx(() -> {
            stage[0].close();
            holder[0].dispose();
        });
    }

    @Test
    void showValueAtCaretShowsTheNameAtTheCaret() throws Exception {
        DebugHover.setDebounceForTest(Duration.ZERO);
        EditorFx.onFx(() -> {
            List<String> asked = new ArrayList<>();
            EditorBuffer buffer = buffer(asked);
            Stage stage = EditorFx.show(buffer, 700, 300);
            buffer.getArea().moveTo(SOURCE.indexOf("depth") + 5); // just after "depth"
            assertFalse(buffer.showDebugValueAtCaret(), "not paused: nothing to show");
            buffer.setDebugHoverActive(true);
            assertTrue(buffer.showDebugValueAtCaret());
            Tooltip tip = ((DebugHover) EditorFx.field(buffer, "debugHover")).tooltip();
            assertNotNull(tip);
            assertEquals("other.depth = v(other.depth)", tip.getText());
            buffer.getArea().moveTo(SOURCE.indexOf("=")); // "total |= ...": a space before, an operator after
            assertFalse(buffer.showDebugValueAtCaret(), "on an operator there is no name");
            stage.close();
            buffer.dispose();
        });
    }
}
