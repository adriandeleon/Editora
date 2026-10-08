package com.editora.editor;

import java.util.ArrayList;
import java.util.List;

import javafx.event.EventType;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

import com.editora.markdown.MarkdownLint;
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
 * The marks drawn over the scrollbar (language-server diagnostics, TODO comments, Markdown lint): hovering
 * a mark names it, clicking one goes to its line, and an inactive or empty stripe stays out of the way.
 */
@Tag("fx")
class ScrollbarStripesFxTest {

    private static final int LINES = 100;

    @BeforeAll
    static void boot() throws Exception {
        EditorFx.boot();
    }

    private static CodeArea hundredLines() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < LINES; i++) {
            sb.append("line ").append(i).append(i < LINES - 1 ? "\n" : "");
        }
        CodeArea area = new CodeArea();
        area.replaceText(sb.toString());
        return area;
    }

    private static Stage show(CodeArea area, Region stripe) {
        HBox box = new HBox(area, stripe);
        HBox.setHgrow(area, Priority.ALWAYS);
        Stage stage = new Stage();
        stage.setX(100);
        stage.setY(80);
        stage.setScene(new Scene(box, 420, 300));
        stage.show();
        box.applyCss();
        box.layout();
        return stage;
    }

    /** The y at which the stripe draws {@code line}'s mark, as the stripe itself computes it. */
    private static double yOf(Region stripe, int line) {
        Canvas canvas = EditorFx.field(stripe, "canvas");
        return EditorFx.<Double>call(
                stripe,
                "yForLine",
                new Class<?>[] {int.class, double.class, int.class},
                line,
                canvas.getHeight(),
                LINES);
    }

    private static void mouse(Region stripe, EventType<MouseEvent> type, double y) {
        var screen = stripe.localToScreen(3, y);
        stripe.fireEvent(EditorFx.mouse(type, 3, y, screen.getX(), screen.getY(), false, 1));
    }

    private static Tooltip tooltip(Region stripe) {
        return EditorFx.field(stripe, "tooltip");
    }

    private static void resize(Region stripe, Stage stage) {
        stage.getScene().getRoot().applyCss();
        stage.getScene().getRoot().layout();
    }

    @Test
    void theDiagnosticStripeNamesAndJumpsToItsMarks() throws Exception {
        EditorFx.onFx(() -> {
            CodeArea area = hundredLines();
            DiagnosticStripe stripe = new DiagnosticStripe(area);
            List<Integer> jumps = new ArrayList<>();
            stripe.setOnActivate(jumps::add);
            Stage stage = show(area, stripe);
            assertFalse(stripe.isVisible(), "inactive: the scrollbar is all there is");
            assertTrue(stripe.isMouseTransparent());

            stripe.setDiagnostics(List.of(
                    new LspDiagnostic(20, 0, 20, 4, LspDiagnostic.Severity.ERROR, "cannot find symbol", "E1", "javac"),
                    new LspDiagnostic(20, 6, 20, 9, LspDiagnostic.Severity.WARNING, "unused variable", null, null),
                    new LspDiagnostic(70, 0, 70, 2, LspDiagnostic.Severity.HINT, "can be final", " ", "lint")));
            mouse(stripe, MouseEvent.MOUSE_MOVED, yOf(stripe, 20) + 1);
            assertNull(tooltip(stripe), "marks on an inactive stripe are not hoverable");
            mouse(stripe, MouseEvent.MOUSE_CLICKED, yOf(stripe, 20) + 1);
            assertTrue(jumps.isEmpty());

            stripe.setActive(true);
            stripe.setActive(true);
            resize(stripe, stage);
            assertTrue(stripe.isVisible());
            assertFalse(stripe.isMouseTransparent(), "with marks it takes the pointer");
            Canvas canvas = EditorFx.field(stripe, "canvas");
            assertEquals(stripe.getHeight(), canvas.getHeight(), 0.5, "the canvas fills the stripe once it paints");

            mouse(stripe, MouseEvent.MOUSE_MOVED, yOf(stripe, 20) + 1);
            Tooltip tip = tooltip(stripe);
            assertNotNull(tip);
            assertTrue(tip.isShowing());
            assertEquals("cannot find symbol  (javac: E1)\nunused variable", tip.getText(), "most severe first");
            assertEquals(Cursor.HAND, stripe.getCursor());
            mouse(stripe, MouseEvent.MOUSE_MOVED, yOf(stripe, 20) + 2);
            assertTrue(tip.isShowing(), "moving within the same mark keeps the tooltip where it is");

            mouse(stripe, MouseEvent.MOUSE_MOVED, yOf(stripe, 70) + 1);
            assertEquals("can be final  (lint)", tip.getText());
            mouse(stripe, MouseEvent.MOUSE_MOVED, yOf(stripe, 45));
            assertFalse(tip.isShowing(), "between marks there is nothing to say");
            assertEquals(Cursor.DEFAULT, stripe.getCursor());

            mouse(stripe, MouseEvent.MOUSE_CLICKED, yOf(stripe, 70) + 1);
            mouse(stripe, MouseEvent.MOUSE_CLICKED, yOf(stripe, 45));
            assertEquals(List.of(70), jumps, "a click on a mark goes to its line; a click elsewhere does not");

            mouse(stripe, MouseEvent.MOUSE_MOVED, yOf(stripe, 20) + 1);
            assertTrue(tip.isShowing());
            mouse(stripe, MouseEvent.MOUSE_EXITED, 0);
            assertFalse(tip.isShowing());

            // The split's second view mirrors the marks and scrolls itself.
            CodeArea second = hundredLines();
            DiagnosticStripe follower = stripe.follower(second);
            assertTrue(follower.isVisible());
            stripe.setDiagnostics(null);
            assertTrue(stripe.isMouseTransparent(), "no marks left: back out of the scrollbar's way");
            assertTrue(follower.isMouseTransparent(), "and the follower with it");
            mouse(stripe, MouseEvent.MOUSE_MOVED, yOf(stripe, 20) + 1);
            assertFalse(tip.isShowing());
            stripe.setOnActivate(null);
            stripe.setActive(false);
            assertFalse(stripe.isVisible());
            assertFalse(follower.isVisible());
            assertEquals(1, canvas.getHeight(), 0.01, "an inactive stripe gives its texture back");
            stage.close();
        });
    }

    @Test
    void theTodoStripeShowsTheCommentAndJumpsToIt() throws Exception {
        EditorFx.onFx(() -> {
            CodeArea area = hundredLines();
            TodoStripe stripe = new TodoStripe(area);
            List<Integer> jumps = new ArrayList<>();
            stripe.setOnActivate(jumps::add);
            Stage stage = show(area, stripe);
            stripe.setMarks(List.of(
                    new TodoMark(0, 4, 10, "   // TODO tidy this up  ", "#ffaa00"),
                    new TodoMark(0, 4, 60, "  ", "not-a-colour"),
                    new TodoMark(0, 4, 90, null, null)));
            mouse(stripe, MouseEvent.MOUSE_MOVED, yOf(stripe, 10) + 1);
            assertNull(tooltip(stripe));

            stripe.setActive(true);
            resize(stripe, stage);
            assertFalse(stripe.isMouseTransparent());
            mouse(stripe, MouseEvent.MOUSE_MOVED, yOf(stripe, 10) + 1);
            Tooltip tip = tooltip(stripe);
            assertNotNull(tip);
            assertTrue(tip.isShowing());
            assertEquals("// TODO tidy this up", tip.getText());
            mouse(stripe, MouseEvent.MOUSE_MOVED, yOf(stripe, 10) + 2);
            assertTrue(tip.isShowing());

            mouse(stripe, MouseEvent.MOUSE_MOVED, yOf(stripe, 35));
            assertFalse(tip.isShowing());
            mouse(stripe, MouseEvent.MOUSE_MOVED, yOf(stripe, 60) + 1);
            assertFalse(tip.isShowing(), "a mark with no text to show stays silent");
            assertEquals(Cursor.HAND, stripe.getCursor(), "but is still clickable");
            mouse(stripe, MouseEvent.MOUSE_MOVED, yOf(stripe, 90) + 1);
            assertFalse(tip.isShowing());

            mouse(stripe, MouseEvent.MOUSE_CLICKED, yOf(stripe, 60) + 1);
            mouse(stripe, MouseEvent.MOUSE_CLICKED, yOf(stripe, 35));
            assertEquals(1, jumps.size(), "one click landed on a mark");

            CodeArea second = hundredLines();
            TodoStripe follower = stripe.follower(second);
            assertTrue(follower.isVisible());
            stripe.setMarks(null);
            assertTrue(stripe.isMouseTransparent());
            stripe.setOnActivate(null);
            stripe.setActive(false);
            assertFalse(follower.isVisible());
            mouse(stripe, MouseEvent.MOUSE_CLICKED, yOf(stripe, 10) + 1);
            assertEquals(1, jumps.size());
            stage.close();
        });
    }

    @Test
    void theMarkdownLintStripeShowsTheRuleAndJumpsToItsLine() throws Exception {
        EditorFx.onFx(() -> {
            CodeArea area = hundredLines();
            MarkdownLintStripe stripe = new MarkdownLintStripe(area);
            List<Integer> jumps = new ArrayList<>();
            stripe.setOnActivate(jumps::add);
            Stage stage = show(area, stripe);
            stripe.setDiagnostics(List.of(
                    new MarkdownLint.Diagnostic(31, 1, 4, "warning", "MD009", "Trailing spaces"),
                    new MarkdownLint.Diagnostic(81, 1, 4, "error", "MD001", "Heading levels skip")));
            mouse(stripe, MouseEvent.MOUSE_MOVED, yOf(stripe, 30) + 1);
            assertNull(tooltip(stripe));

            stripe.setActive(true);
            resize(stripe, stage);
            mouse(stripe, MouseEvent.MOUSE_MOVED, yOf(stripe, 30) + 1); // diagnostics count lines from 1
            Tooltip tip = tooltip(stripe);
            assertNotNull(tip);
            assertTrue(tip.isShowing());
            assertEquals("MD009: Trailing spaces", tip.getText());
            mouse(stripe, MouseEvent.MOUSE_MOVED, yOf(stripe, 30) + 2);
            assertTrue(tip.isShowing());
            mouse(stripe, MouseEvent.MOUSE_MOVED, yOf(stripe, 55));
            assertFalse(tip.isShowing());
            assertEquals(Cursor.DEFAULT, stripe.getCursor());

            mouse(stripe, MouseEvent.MOUSE_CLICKED, yOf(stripe, 80) + 1);
            mouse(stripe, MouseEvent.MOUSE_CLICKED, yOf(stripe, 55));
            assertEquals(List.of(80), jumps, "the 0-based line of the clicked diagnostic");

            mouse(stripe, MouseEvent.MOUSE_MOVED, yOf(stripe, 80) + 1);
            assertEquals("MD001: Heading levels skip", tip.getText());
            mouse(stripe, MouseEvent.MOUSE_EXITED, 0);
            assertFalse(tip.isShowing());

            CodeArea second = hundredLines();
            MarkdownLintStripe follower = stripe.follower(second);
            assertTrue(follower.isVisible());
            stripe.setDiagnostics(null);
            assertTrue(stripe.isMouseTransparent());
            stripe.setOnActivate(null);
            stripe.setActive(false);
            assertFalse(follower.isVisible());
            stage.close();
        });
    }

    @Test
    void anEmptyDocumentHasNoMarksToHit() throws Exception {
        EditorFx.onFx(() -> {
            CodeArea area = new CodeArea();
            DiagnosticStripe stripe = new DiagnosticStripe(area);
            Stage stage = show(area, stripe);
            stripe.setActive(true);
            stripe.setDiagnostics(
                    List.of(new LspDiagnostic(5, 0, 5, 1, LspDiagnostic.Severity.INFO, "stale, past the end", "", "")));
            resize(stripe, stage);
            Canvas canvas = EditorFx.field(stripe, "canvas");
            double y = EditorFx.<Double>call(
                    stripe, "yForLine", new Class<?>[] {int.class, double.class, int.class}, 5, canvas.getHeight(), 1);
            assertTrue(y <= canvas.getHeight(), "a line past the end is clamped onto the stripe");
            mouse(stripe, MouseEvent.MOUSE_MOVED, y);
            Tooltip tip = tooltip(stripe);
            assertNotNull(tip);
            assertEquals("stale, past the end", tip.getText(), "no source or code: the message alone");
            stage.close();
        });
    }
}
