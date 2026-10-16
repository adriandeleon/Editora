package com.editora.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.function.Predicate;

import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import com.editora.config.NoteScope;
import com.editora.config.PersonalNote;
import com.editora.config.TextAnchor;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.TwoDimensional.Bias;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the Personal Notes indicators (the amber highlight and corner marker of {@link NoteHighlightOverlay}) are
 * painted — on the note's text in the frame that shows it, one box per visual row, never on a folded line or over
 * the gutter — and that keeping them up to date rebuilds no gutter.
 */
@Tag("fx")
class NoteIndicatorsFxTest {

    @BeforeAll
    static void boot() throws Exception {
        EditorFx.boot();
    }

    private static PersonalNote note(CodeArea area, int start, int end) {
        var sp = area.offsetToPosition(start, Bias.Forward);
        var ep = area.offsetToPosition(end, Bias.Forward);
        String doc = area.getText();
        return PersonalNote.create(
                null,
                sp.getMajor() == ep.getMajor() ? NoteScope.WORD : NoteScope.RANGE,
                new TextAnchor(
                        sp.getMajor(),
                        sp.getMinor(),
                        ep.getMajor(),
                        ep.getMinor(),
                        doc.substring(start, end),
                        doc.substring(Math.max(0, start - 20), start),
                        doc.substring(end, Math.min(doc.length(), end + 20))),
                "body",
                List.of());
    }

    private static Canvas canvas(EditorBuffer buffer) {
        return EditorFx.field(EditorFx.field(buffer, "noteOverlay"), "canvas");
    }

    /** Per canvas row: {@code {minX, maxX}} of the painted pixels, or null. FX thread. */
    private static int[][] painted(EditorBuffer buffer) {
        Canvas canvas = canvas(buffer);
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        WritableImage img = canvas.snapshot(params, null);
        PixelReader px = img.getPixelReader();
        int[][] rows = new int[(int) img.getHeight()][];
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((px.getArgb(x, y) >>> 24) > 30) {
                    rows[y] = rows[y] == null ? new int[] {x, x} : new int[] {rows[y][0], x};
                }
            }
        }
        return rows;
    }

    /** The canvas-local box of the character at {@code offset}, or null. FX thread. */
    private static Bounds charBox(EditorBuffer buffer, int offset) {
        return buffer.getArea()
                .getCharacterBoundsOnScreen(offset, offset + 1)
                .map(canvas(buffer)::screenToLocal)
                .orElse(null);
    }

    /** The painted extent on the row through the middle of {@code box}, or null. FX thread. */
    private static int[] paintedAt(EditorBuffer buffer, Bounds box) {
        int[][] rows = painted(buffer);
        int y = (int) Math.round(box.getCenterY());
        return y < 0 || y >= rows.length ? null : rows[y];
    }

    /** Whether any pixel of the canvas is painted. FX thread. */
    private static boolean anyPainted(EditorBuffer buffer) {
        for (int[] row : painted(buffer)) {
            if (row != null) {
                return true;
            }
        }
        return false;
    }

    /** Re-checks {@code done} on the FX thread for up to three seconds; returns its last answer. */
    private static boolean eventually(EditorBuffer buffer, Predicate<EditorBuffer> done) throws Exception {
        for (int attempt = 0; attempt < 60; attempt++) {
            if (EditorFx.callFx(() -> done.test(buffer))) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    /** Whether the highlight on the row of the note at {@code start} begins where its first character does. */
    private static boolean onText(EditorBuffer buffer, int start) {
        Bounds box = charBox(buffer, start);
        int[] row = box == null ? null : paintedAt(buffer, box);
        return row != null && Math.abs(row[0] - box.getMinX()) <= 1.5;
    }

    /**
     * Runs {@code change} and compares the highlight with the note's text in every frame shown for a while after
     * it ({@link FrameWatch}). Returns a line per frame that had it elsewhere.
     */
    private static List<String> framesOffText(EditorBuffer buffer, int start, Runnable change) throws Exception {
        Object overlay = EditorFx.callFx(() -> EditorFx.field(buffer, "noteOverlay"));
        return FrameWatch.wrongFrames(
                buffer,
                overlay,
                () -> {
                    Bounds box = charBox(buffer, start);
                    int[] row = box == null ? null : paintedAt(buffer, box);
                    return box != null && (row == null || Math.abs(row[0] - box.getMinX()) > 1.5)
                            ? "text at x=" + box.getMinX() + ", highlight at " + (row == null ? "none" : "x=" + row[0])
                            : null;
                },
                change);
    }

    @Test
    void everyFrameHasTheHighlightOnTheTextWhenTheGutterOrFontChanges() throws Exception {
        EditorBuffer[] b = new EditorBuffer[1];
        Stage[] stage = new Stage[1];
        EditorFx.onFx(() -> {
            b[0] = new EditorBuffer();
            b[0].setInitialContent("note here\n" + "line\n".repeat(997) + "end"); // 999 lines
            b[0].applyNotes(List.of(note(b[0].getArea(), 0, 4)));
            stage[0] = EditorFx.show(b[0], 600, 300);
            b[0].getArea().showParagraphAtTop(0);
        });
        try {
            assertTrue(eventually(b[0], x -> onText(x, 0)), "painted on the note to begin with");
            double before = EditorFx.callFx(() -> charBox(b[0], 0).getMinX());

            // The 1,000th line widens the line numbers: the text moves right, a pulse after the edit.
            assertEquals(List.of(), framesOffText(b[0], 0, () -> b[0].getArea().appendText("\nmore")), "1,000 lines");
            double wider = EditorFx.callFx(() -> charBox(b[0], 0).getMinX());
            assertTrue(wider > before + 2, "the fixture widens the gutter: " + before + " then " + wider);

            assertEquals(List.of(), framesOffText(b[0], 0, () -> b[0].setLineNumbersVisible(false)), "numbers off");
            assertEquals(List.of(), framesOffText(b[0], 0, () -> b[0].setLineNumbersVisible(true)), "numbers on");
            assertEquals(List.of(), framesOffText(b[0], 0, () -> b[0].setFont("Monospaced", 22)), "a larger font");
            assertTrue(EditorFx.callFx(() -> charBox(b[0], 0).getMinX()) > wider + 2, "the font moved the text");
        } finally {
            EditorFx.onFx(() -> {
                stage[0].close();
                b[0].dispose();
            });
        }
    }

    @Test
    void aNoteThatCrossesARowOfAWrappedLineIsPaintedOnlyOverItsOwnText() throws Exception {
        String line = "word ".repeat(60).strip();
        EditorBuffer[] b = new EditorBuffer[1];
        Stage[] stage = new Stage[1];
        EditorFx.onFx(() -> {
            b[0] = new EditorBuffer();
            b[0].setInitialContent(line + "\nsecond\n");
            b[0].setWordWrap(true);
            stage[0] = EditorFx.show(b[0], 420, 400);
        });
        try {
            int[] row2 = {-1};
            assertTrue(
                    eventually(b[0], x -> {
                        Bounds first = charBox(x, 0);
                        for (int i = 1; first != null && i < line.length(); i++) {
                            if (charBox(x, i).getMinY() > first.getMinY() + 2) {
                                row2[0] = i;
                                return true;
                            }
                        }
                        return false;
                    }),
                    "the line wraps");
            int start = row2[0] - 10;
            int end = row2[0] + 9;
            EditorFx.onFx(() -> b[0].applyNotes(List.of(note(b[0].getArea(), start, end))));
            assertTrue(eventually(b[0], x -> onText(x, start)), "the first row starts at the note");
            EditorFx.onFx(() -> {
                Bounds first = charBox(b[0], start);
                Bounds last = charBox(b[0], end - 1);
                Bounds textStart = charBox(b[0], 0);
                int[] top = paintedAt(b[0], first);
                int[] bottom = paintedAt(b[0], last);
                assertEquals(first.getMinX(), top[0], 1.5, "row 1 begins at the note, not at the line's start");
                assertEquals(textStart.getMinX(), bottom[0], 1.5, "row 2 begins where the text does");
                assertEquals(last.getMaxX(), bottom[1], 1.5, "and ends with the note, not at the row's end");
                Bounds below = charBox(b[0], row2[0] * 2 + 2);
                assertEquals(null, paintedAt(b[0], below), "the rows after the note are not painted");
            });
        } finally {
            EditorFx.onFx(() -> {
                stage[0].close();
                b[0].dispose();
            });
        }
    }

    @Test
    void aNoteOnAFoldedLineIsNotPainted() throws Exception {
        String text = "head\nalpha\nbeta noted\ngamma\ntail\n";
        int start = text.indexOf("noted");
        EditorBuffer[] b = new EditorBuffer[1];
        Stage[] stage = new Stage[1];
        EditorFx.onFx(() -> {
            b[0] = new EditorBuffer();
            b[0].setInitialContent(text);
            stage[0] = EditorFx.show(b[0], 420, 300);
            b[0].applyNotes(List.of(note(b[0].getArea(), start, start + 5)));
        });
        try {
            assertTrue(eventually(b[0], x -> onText(x, start)), "painted while unfolded");
            EditorFx.onFx(() -> b[0].getArea().foldParagraphs(0, 3));
            assertTrue(EditorFx.callFx(() -> b[0].getArea().isFolded(2)), "the note's line is folded");
            assertTrue(eventually(b[0], x -> !anyPainted(x)), "nothing is painted for a folded line");
        } finally {
            EditorFx.onFx(() -> {
                stage[0].close();
                b[0].dispose();
            });
        }
    }

    @Test
    void scrolledSidewaysTheHighlightStaysOffTheGutter() throws Exception {
        EditorBuffer[] b = new EditorBuffer[1];
        Stage[] stage = new Stage[1];
        EditorFx.onFx(() -> {
            b[0] = new EditorBuffer();
            b[0].setInitialContent("abcdefghij".repeat(40) + "\nx\n");
            stage[0] = EditorFx.show(b[0], 420, 300);
            b[0].applyNotes(List.of(note(b[0].getArea(), 0, 60)));
        });
        try {
            assertTrue(eventually(b[0], x -> onText(x, 0)), "painted from the start of the text");
            double textLeft = EditorFx.callFx(() -> charBox(b[0], 0).getMinX());
            assertTrue(textLeft > 10, "the fixture has a gutter: " + textLeft);
            EditorFx.onFx(() -> b[0].getArea().scrollXToPixel(200));
            assertTrue(
                    eventually(b[0], x -> charBox(x, 0) == null || charBox(x, 0).getMinX() < 0),
                    "the note's start scrolled under the gutter");
            assertTrue(
                    eventually(b[0], x -> {
                        int[] row = paintedAt(x, charBox(x, 30));
                        return row != null && row[0] >= textLeft - 0.5;
                    }),
                    "the rest of the note is still painted, from where the text area begins");
            EditorFx.onFx(() -> {
                for (int[] row : painted(b[0])) {
                    if (row != null) {
                        assertTrue(row[0] >= textLeft - 0.5, "nothing over the line numbers: x=" + row[0]);
                    }
                }
            });
        } finally {
            EditorFx.onFx(() -> {
                stage[0].close();
                b[0].dispose();
            });
        }
    }

    /**
     * Notes have no gutter glyph. An edit that moves them used to rebuild the gutter cell of each note's old and
     * new line ({@code recreateParagraphGraphic}, which builds a cell even for a line far off screen): an Enter
     * above 200 notes queued 400 of them. Opening a file and toggling the indicators rebuilt the whole gutter.
     */
    @Test
    void keepingTheNotesUpToDateRebuildsNoGutter() throws Exception {
        // Flowless re-indexes the cells it holds on every edit whatever the notes, so the measure is a buffer
        // without notes taken through the same steps.
        int without = offScreenGutterBuilds(false);
        int with = offScreenGutterBuilds(true);
        assertEquals(without, with, "the notes' lines, all far off screen, get no gutter cell of their own");
    }

    /** Gutter graphics built for lines far below the viewport while five Enters move every note down a line. */
    private static int offScreenGutterBuilds(boolean withNotes) throws Exception {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            text.append("line ").append(i).append('\n');
        }
        EditorBuffer[] b = new EditorBuffer[1];
        Stage[] stage = new Stage[1];
        AtomicInteger offScreen = new AtomicInteger();
        @SuppressWarnings("unchecked")
        IntFunction<Node>[] counting = new IntFunction[1];
        EditorFx.onFx(() -> {
            b[0] = new EditorBuffer();
            b[0].setInitialContent(text.toString());
            CodeArea area = b[0].getArea();
            List<PersonalNote> notes = new ArrayList<>();
            for (int line = 200; withNotes && line < 400; line += 10) {
                int s = area.getAbsolutePosition(line, 0);
                notes.add(note(area, s, s + 4));
            }
            b[0].applyNotes(notes);
            stage[0] = EditorFx.show(b[0], 600, 300);
            IntFunction<? extends Node> base = area.getParagraphGraphicFactory();
            counting[0] = line -> {
                if (line >= 150) {
                    offScreen.incrementAndGet();
                }
                return base.apply(line);
            };
            area.setParagraphGraphicFactory(counting[0]);
        });
        try {
            settle();
            offScreen.set(0);
            EditorFx.onFx(() -> {
                for (int i = 0; i < 5; i++) {
                    b[0].getArea().insertText(0, "\n"); // every note moves down a line
                }
            });
            settle();
            int built = offScreen.get();
            EditorFx.onFx(() -> {
                NoteManager notes = b[0].getNoteManager();
                assertEquals(withNotes, !notes.isEmpty());
                b[0].applyNotes(notes.snapshot());
                b[0].setNoteIndicatorsVisible(false);
                b[0].setNoteIndicatorsVisible(true);
                PersonalNote extra = note(b[0].getArea(), 10, 11);
                notes.add(extra);
                notes.remove(extra.id());
                assertSame(counting[0], b[0].getArea().getParagraphGraphicFactory(), "the gutter was not rebuilt");
            });
            return built;
        } finally {
            EditorFx.onFx(() -> {
                stage[0].close();
                b[0].dispose();
            });
        }
    }

    /** Lets queued work and the fold manager's debounced recompute run. */
    private static void settle() throws Exception {
        EditorFx.drain();
        Thread.sleep(400);
        EditorFx.drain();
    }
}
