package com.editora.editor.http;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.IntFunction;

import javafx.css.PseudoClass;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.geometry.Point2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.stage.Stage;

import com.editora.editor.EditorBuffer;
import com.editora.editor.EditorFx;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.PlainTextChange;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The result after a request line in the editor: hovering it lights the line's segments, shows the hand and its
 * tooltip, a click runs its action; an edit of several steps at once loses the lines until the next scan finds
 * the requests by their text again.
 */
@Tag("fx")
class HttpResultMarksFxTest {

    private static final PseudoClass HOVER = PseudoClass.getPseudoClass("result-hover");

    @BeforeAll
    static void boot() throws Exception {
        EditorFx.boot();
    }

    private static HttpLineResult result(int line, String anchor, String text, Runnable onClick) {
        return new HttpLineResult(
                line,
                anchor,
                List.of(
                        new HttpLineResult.Segment(text, "http-result-ok"),
                        new HttpLineResult.Segment(" · 5 ms", "http-result")),
                "http-result-ok",
                "Ran at 10:00:00",
                onClick);
    }

    private static IntFunction<String> textOf(CodeArea area) {
        return line -> line >= 0 && line < area.getParagraphs().size()
                ? area.getParagraph(line).getText()
                : null;
    }

    @Test
    void anEditOfSeveralStepsLosesTheLinesUntilTheScanFindsTheRequestsByText() throws Exception {
        EditorFx.onFx(() -> {
            CodeArea area = new CodeArea("### a\nGET /a\n\n### b\nGET /b\n");
            HttpResultMarks marks = new HttpResultMarks();
            marks.set(List.of(result(1, "GET /a", "200 OK", null), result(4, "GET /b", "404", null)), textOf(area));

            // Two steps, neither adds or removes a line: nothing moved and nothing is lost.
            assertFalse(marks.shift(List.of(new PlainTextChange(0, "", "x"), new PlainTextChange(8, "", "y")), area));
            assertEquals(
                    List.of(1, 4),
                    marks.all().stream().map(HttpLineResult::line).toList());

            // Two steps, one of them a new line: each is in its own coordinates, so the marks let the scan find
            // the requests by text instead.
            assertFalse(
                    marks.shift(List.of(new PlainTextChange(0, "", "# x\n"), new PlainTextChange(5, "", "y")), area));
            area.replaceText(0, 0, "# x\n");
            Set<Integer> changed = marks.reanchor(List.of(2, 5), textOf(area));
            assertEquals(Set.of(1, 2, 4, 5), changed, "the old lines and the new ones repaint");
            assertEquals(
                    List.of(2, 5),
                    marks.all().stream().map(HttpLineResult::line).toList());
            assertEquals("http-result-ok", marks.glyphClass(2));
        });
    }

    @Test
    void hoveringAResultLightsItWithTheHandAndAClickRunsIt() throws Exception {
        EditorFx.onFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setLanguageOverride("http");
            buffer.getArea().replaceText("### a\nGET /a\n");
            List<String> clicked = new ArrayList<>();
            buffer.setHttpResults(List.of(result(1, "GET /a", "200 OK", () -> clicked.add("a"))));
            Stage stage = EditorFx.show(buffer, 600, 200);
            try {
                CodeArea area = buffer.getArea();
                buffer.getNode().applyCss();
                buffer.getNode().layout();
                Label label = area.lookupAll(".inlay").stream()
                        .filter(n -> n instanceof Label l && l.getText().equals("200 OK"))
                        .map(n -> (Label) n)
                        .findFirst()
                        .orElseThrow();
                Cursor before = area.getCursor();

                Event.fireEvent(area, on(area, label, MouseEvent.MOUSE_MOVED));
                assertEquals(Cursor.HAND, area.getCursor());
                assertTrue(label.getPseudoClassStates().contains(HOVER));
                Event.fireEvent(area, on(area, label, MouseEvent.MOUSE_MOVED)); // still there: nothing to redo
                assertTrue(label.getPseudoClassStates().contains(HOVER));

                Event.fireEvent(area, at(area, 2, 2, MouseEvent.MOUSE_MOVED)); // off it, on the ### line
                assertEquals(before, area.getCursor());
                assertFalse(label.getPseudoClassStates().contains(HOVER));

                Event.fireEvent(area, on(area, label, MouseEvent.MOUSE_MOVED));
                Event.fireEvent(area, on(area, label, MouseEvent.MOUSE_CLICKED));
                assertEquals(List.of("a"), clicked);
                assertFalse(label.getPseudoClassStates().contains(HOVER), "the click ends the hover");

                Event.fireEvent(area, at(area, 2, 2, MouseEvent.MOUSE_EXITED));
                assertEquals(before, area.getCursor());
            } finally {
                stage.close();
            }
        });
    }

    /** A pointer event in the middle of {@code label}, as the area gets it (the label is mouse-transparent). */
    private static MouseEvent on(CodeArea area, Node label, EventType<MouseEvent> type) {
        var bounds = label.localToScene(label.getBoundsInLocal());
        Point2D inArea = area.sceneToLocal(bounds.getCenterX(), bounds.getCenterY());
        return at(area, inArea.getX(), inArea.getY(), type);
    }

    private static MouseEvent at(CodeArea area, double x, double y, EventType<MouseEvent> type) {
        Point2D onScreen = area.localToScreen(x, y);
        return new MouseEvent(
                area,
                area,
                type,
                x,
                y,
                onScreen == null ? 0 : onScreen.getX(),
                onScreen == null ? 0 : onScreen.getY(),
                MouseButton.PRIMARY,
                1,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                true,
                false,
                true,
                new PickResult(area, x, y));
    }
}
