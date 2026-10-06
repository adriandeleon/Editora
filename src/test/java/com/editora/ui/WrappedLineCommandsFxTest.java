package com.editora.ui;

import javafx.scene.input.KeyCode;

import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With word wrap on, {@code C-a}, {@code C-e} and {@code C-k} agree on what a line is: the logical line.
 * {@code C-e} used to stop at the end of the visual row, so {@code C-e C-k} — "go to the end, kill nothing
 * but the newline" — deleted the rest of the paragraph instead.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WrappedLineCommandsFxTest {

    private static final String LONG = "word ".repeat(120).strip(); // 599 characters: several visual rows

    private EditingFx e;

    @BeforeAll
    void setUp() throws Exception {
        e = EditingFx.create();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (e != null) {
            e.dispose();
        }
    }

    @Test
    void lineEndGoesToTheEndOfTheWrappedLineAndKillLineThenTakesOnlyTheNewline() throws Exception {
        EditorBuffer b = e.open("wrapped.txt", "first\n" + LONG + "\nlast\n");
        CodeArea area = b.getArea();
        FxTestSupport.runOnFx(() -> b.setWordWrap(true));
        e.pulses(20);
        assertTrue(
                FxTestSupport.callOnFx(() -> area.getParagraphLinesCount(1)) > 1,
                "the long paragraph is wrapped over several rows");
        FxTestSupport.runOnFx(() -> area.moveTo(1, 250)); // somewhere in the middle rows

        e.ctrl(area, KeyCode.E);
        assertEquals(LONG.length(), (int) FxTestSupport.callOnFx(area::getCaretColumn), "C-e: end of the line");
        assertEquals(1, (int) FxTestSupport.callOnFx(area::getCurrentParagraph));

        e.ctrl(area, KeyCode.A);
        assertEquals(0, (int) FxTestSupport.callOnFx(area::getCaretColumn), "C-a: start of the same line");

        e.ctrl(area, KeyCode.E);
        e.ctrl(area, KeyCode.K);
        assertEquals(
                "first\n" + LONG + "last\n",
                FxTestSupport.callOnFx(area::getText),
                "C-k at the end of the line joins the next line; it does not delete text");
    }
}
