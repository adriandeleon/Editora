package com.editora.editor.todo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javafx.geometry.Bounds;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import com.editora.editor.EditorFx;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the TODO highlight paints ({@link TodoHighlightOverlay}): the keyword, its {@code (@tag)} and its
 * {@code !priority} each washed in their own colour, the tag underlined in its solid colour, an unparseable colour
 * shown in the fallback, and nothing for a mark an edit left past the end of the document or one on a folded line
 * — without either stopping the marks after it from painting.
 */
@Tag("fx")
class TodoHighlightPaintFxTest {

    private static final String TEXT = String.join(
            "\n",
            "// TODO(@adl) !high fix the parser",
            "int x = 1;",
            "// FIXME stale",
            "{",
            "  // NOTE hidden",
            "}",
            "");

    @BeforeAll
    static void boot() throws Exception {
        EditorFx.boot();
    }

    private static int at(String part) {
        return TEXT.indexOf(part);
    }

    private static int end(String part) {
        return TEXT.indexOf(part) + part.length();
    }

    /** The canvas's pixel colour at the middle of {@code [start,end)} ({@code dy}: rows from its bottom edge). */
    private static Color pixel(TodoHighlightOverlay overlay, CodeArea area, PixelReader px, int start, int end) {
        Bounds b = overlay.screenToLocal(
                area.getCharacterBoundsOnScreen(start, end).orElseThrow());
        return px.getColor((int) b.getCenterX(), (int) b.getCenterY());
    }

    private static Color underline(TodoHighlightOverlay overlay, CodeArea area, PixelReader px, int start, int end) {
        Bounds b = overlay.screenToLocal(
                area.getCharacterBoundsOnScreen(start, end).orElseThrow());
        return px.getColor((int) b.getCenterX(), (int) Math.floor(b.getMaxY()) - 1);
    }

    @Test
    void eachPartIsWashedInItsColourTheTagIsUnderlinedAndStaleOrFoldedMarksAreSkipped() throws Exception {
        EditorFx.onFx(() -> {
            CodeArea area = new CodeArea(TEXT);
            TodoHighlightOverlay overlay = new TodoHighlightOverlay(area);
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(area, overlay), 600, 300));
            stage.show();
            area.foldParagraphs(3, 5); // hides "  // NOTE hidden" and "}"

            List<TodoMark> marks = new ArrayList<>();
            marks.add(new TodoMark(
                    at("TODO"),
                    end("TODO"),
                    0,
                    "",
                    "#ffff00",
                    at("@adl"),
                    end("@adl"),
                    "#00ff00",
                    at("!high"),
                    end("!high"),
                    "#ff0000"));
            // An edit shrank the document since this one was computed: skipped, the rest still paint.
            marks.add(new TodoMark(TEXT.length() - 2, TEXT.length() + 40, 5, "", "#0000ff"));
            marks.add(new TodoMark(at("FIXME"), end("FIXME"), 2, "", "not-a-colour"));
            marks.add(new TodoMark(at("NOTE"), end("NOTE"), 4, "", "#0000ff"));
            overlay.setMarks(Arrays.asList(marks.get(0), null, marks.get(1), marks.get(2), marks.get(3)));
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            EditorFx.call(overlay, "redraw");

            Canvas canvas = EditorFx.field(overlay, "canvas");
            SnapshotParameters params = new SnapshotParameters();
            params.setFill(Color.TRANSPARENT);
            WritableImage img = canvas.snapshot(params, null);
            PixelReader px = img.getPixelReader();

            Color keyword = pixel(overlay, area, px, at("TODO"), end("TODO"));
            assertTrue(
                    keyword.getOpacity() > 0.1
                            && keyword.getRed() > 0.8
                            && keyword.getGreen() > 0.8
                            && keyword.getBlue() < 0.3,
                    "the keyword in its yellow wash: " + keyword);
            Color tag = pixel(overlay, area, px, at("@adl"), end("@adl"));
            assertTrue(tag.getGreen() > 0.8 && tag.getRed() < 0.3, "the tag in its green wash: " + tag);
            Color tagLine = underline(overlay, area, px, at("@adl"), end("@adl"));
            assertEquals(1.0, tagLine.getOpacity(), 0.02, "the tag's underline is solid: " + tagLine);
            assertTrue(tagLine.getGreen() > 0.9 && tagLine.getRed() < 0.1, tagLine.toString());
            Color priority = pixel(overlay, area, px, at("!high"), end("!high"));
            assertTrue(priority.getRed() > 0.8 && priority.getGreen() < 0.3, "the priority in red: " + priority);

            Color fallback = pixel(overlay, area, px, at("FIXME"), end("FIXME"));
            Color expected = Color.web("#E5C07B");
            assertTrue(
                    fallback.getOpacity() > 0.1
                            && Math.abs(fallback.getRed() - expected.getRed()) < 0.05
                            && Math.abs(fallback.getGreen() - expected.getGreen()) < 0.05
                            && Math.abs(fallback.getBlue() - expected.getBlue()) < 0.05,
                    "an unparseable colour shows in the fallback amber: " + fallback);

            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    Color c = px.getColor(x, y);
                    assertTrue(
                            c.getOpacity() < 0.05 || c.getBlue() < c.getRed() + 0.3,
                            "nothing blue: neither the stale mark nor the folded one paints (" + x + "," + y + ")");
                }
            }
            stage.close();
        });
    }

    @Test
    void theColourCacheStaysBoundedAndKeepsAnswering() throws Exception {
        EditorFx.onFx(
                () -> { // FX-thread only, like its callers
                    for (int i = 0; i < 300; i++) {
                        String web = String.format("#%06x", i * 4099);
                        assertEquals(Color.web(web), TodoColors.solid(web), web);
                    }
                    java.util.Map<?, ?> solid = EditorFx.staticField(TodoColors.class, "SOLID");
                    assertTrue(
                            solid.size() <= 256,
                            "a stream of distinct values never grows it past its cap: " + solid.size());
                    assertEquals(Color.web("#123456"), TodoColors.solid("#123456"));
                    assertEquals(Color.web("#123456", 0.3), TodoColors.wash("#123456", 0.3));
                    assertEquals(
                            Color.web("#E5C07B"), TodoColors.solid("no such colour"), "an invalid value: the fallback");
                });
    }

    @Test
    void theStripeSkipsAMissingMark() throws Exception {
        EditorFx.onFx(() -> {
            TodoStripe stripe = new TodoStripe(new CodeArea(TEXT));
            TodoMark mark = new TodoMark(at("TODO"), end("TODO"), 0, "// TODO", "#ffaa00");
            stripe.setMarks(Arrays.asList(null, mark, null));
            assertEquals(List.of(mark), EditorFx.field(stripe, "marks"));
        });
    }
}
