package com.editora.editor.debug;

import java.util.ArrayList;
import java.util.List;

import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;

import com.editora.editor.EditorBuffer;
import com.editora.editor.EditorFx;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Quick Evaluate (IntelliJ's Alt+click) while paused: the expression clicked, a call with its arguments, or the
 * selection clicked in, handed to the value popup to evaluate as code. Not paused, or not Alt alone, it does nothing.
 */
@Tag("fx")
class QuickEvaluateFxTest {

    private static final String SOURCE = "total = items.get(i + 1) + other.depth\n";

    @BeforeAll
    static void boot() throws Exception {
        EditorFx.boot();
    }

    /** Records Quick Evaluate's requests. */
    private static final class Recorder implements DebugValuePresenter {
        final List<String> evaluated = new ArrayList<>();

        @Override
        public void show(CodeArea area, String expression, double x, double y, boolean keyboard) {}

        @Override
        public void showEvaluated(CodeArea area, String expression, double x, double y) {
            evaluated.add(expression);
        }

        @Override
        public void hide() {}

        @Override
        public void mouseLeft() {}

        @Override
        public boolean mouseInside() {
            return false;
        }

        @Override
        public boolean isShowing() {
            return false;
        }
    }

    private static MouseEvent altClick(CodeArea area, int offset, boolean alt, boolean shift) {
        Bounds screen = area.getCharacterBoundsOnScreen(offset, offset + 1).orElseThrow();
        Point2D local = area.screenToLocal(screen.getCenterX(), screen.getCenterY());
        return new MouseEvent(
                MouseEvent.MOUSE_CLICKED,
                local.getX(),
                local.getY(),
                screen.getCenterX(),
                screen.getCenterY(),
                MouseButton.PRIMARY,
                1,
                shift,
                false,
                alt,
                false,
                false,
                false,
                false,
                false,
                false,
                true,
                null);
    }

    private static MouseEvent as(javafx.event.EventType<MouseEvent> type, MouseEvent e) {
        return e.copyFor(e.getSource(), e.getTarget(), type);
    }

    @Test
    void altClickEvaluatesTheCallOrTheSelectionClickedIn() throws Exception {
        EditorFx.onFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setLanguageOverride("java");
            buffer.getArea().replaceText(SOURCE);
            Recorder recorder = new Recorder();
            buffer.setDebugValuePresenter(recorder);
            buffer.setDebugHoverEvaluator((expr, sink) -> sink.accept("v"));
            Stage stage = EditorFx.show(buffer, 700, 300);
            CodeArea area = buffer.getArea();

            area.fireEvent(altClick(area, SOURCE.indexOf("get") + 1, true, false));
            assertEquals(List.of(), recorder.evaluated, "not paused: nothing to evaluate in");

            buffer.setDebugHoverActive(true);
            area.fireEvent(altClick(area, SOURCE.indexOf("get") + 1, true, false));
            assertEquals(List.of("items.get(i + 1)"), recorder.evaluated, "the call, arguments and all");

            area.fireEvent(altClick(area, SOURCE.indexOf("depth") + 1, false, false));
            area.fireEvent(altClick(area, SOURCE.indexOf("depth") + 1, true, true));
            assertEquals(1, recorder.evaluated.size(), "a plain click, or Alt+Shift, is not Quick Evaluate");

            int from = SOURCE.indexOf("items");
            area.selectRange(from, SOURCE.indexOf(")") + 1);
            area.fireEvent(as(MouseEvent.MOUSE_PRESSED, altClick(area, from + 2, true, false)));
            area.moveTo(from + 2); // what the press does to the caret before the click arrives
            area.fireEvent(altClick(area, from + 2, true, false));
            assertEquals("items.get(i + 1)", recorder.evaluated.getLast(), "the selection clicked in");
            area.fireEvent(altClick(area, SOURCE.indexOf("depth") + 1, true, false));
            assertEquals("other.depth", recorder.evaluated.getLast(), "outside the selection: the name clicked");
            stage.close();
            buffer.dispose();
        });
    }

    @Test
    void quickClickIsAPrimaryAltClickThatDidNotDrag() {
        assertTrue(DebugHover.quickClick(true, true, false, true, 1));
        assertFalse(DebugHover.quickClick(true, true, false, false, 1), "a drag (box selection)");
        assertFalse(DebugHover.quickClick(true, true, false, true, 2));
        assertFalse(DebugHover.quickClick(false, true, false, true, 1));
        assertFalse(DebugHover.quickClick(true, true, true, true, 1));
    }
}
