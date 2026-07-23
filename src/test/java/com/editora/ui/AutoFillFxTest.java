package com.editora.ui;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Auto-fill break-as-you-type against a real buffer. The pure {@code AutoFillTest} covers where the break
 * lands; this covers the buffer wiring — that typing past the fill column inserts a break, that the caret
 * follows, that it fires only for prose, and that a single undo removes the whole typed word plus its break.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AutoFillFxTest {

    private FxWindowFixture fx;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    /** A prose buffer (plain text) with auto-fill on and a small fill column, caret at the end. */
    private EditorBuffer prose(String content, int fillColumn) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent(content);
            FxTestSupport.call(fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, b, true);
            b.setFillColumn(fillColumn);
            b.setAutoFillEnabled(true);
            b.getArea().moveTo(b.getArea().getLength());
            return b;
        });
    }

    private String text(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> b.getArea().getText());
    }

    /** Types {@code s} character by character at the caret, as the editor would. */
    private void type(EditorBuffer b, String s) throws Exception {
        for (int i = 0; i < s.length(); i++) {
            String ch = String.valueOf(s.charAt(i));
            FxTestSupport.runOnFx(() -> b.getArea().insertText(b.getArea().getCaretPosition(), ch));
        }
    }

    @Test
    void typingPastTheFillColumnBreaksTheLine() throws Exception {
        EditorBuffer b = prose("the quick brown", 10); // 15 chars, already over — the next space triggers
        type(b, " fox");
        assertEquals("the quick\nbrown fox", text(b), "the line broke at the space before it went over");
    }

    @Test
    void theCaretStaysWithTheTextBeingTyped() throws Exception {
        EditorBuffer b = prose("aaaa bbbb cccc", 10);
        type(b, "d"); // "cccc" → "ccccd" pushes over → break before cccc
        // caret should be right after the 'd' we just typed, on the new line
        int caret = FxTestSupport.callOnFx(() -> b.getArea().getCaretPosition());
        String t = text(b);
        assertEquals("aaaa bbbb\nccccd", t);
        assertEquals(t.length(), caret, "the caret followed the typed character onto the new line");
    }

    @Test
    void aVeryLongWordIsNotBrokenAndDoesNotHang() throws Exception {
        EditorBuffer b = prose("", 8); // start empty, so there is no whitespace anywhere to break on
        type(b, "superlongwordwithnospaces");
        assertEquals("superlongwordwithnospaces", text(b), "no whitespace to break on — the line is left long");
    }

    @Test
    void codeBuffersAreNeverAutoFilled() throws Exception {
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer buf = new EditorBuffer();
            buf.setContent("int aaa = bbb");
            buf.setLanguageOverride("java"); // not prose
            FxTestSupport.call(fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, buf, true);
            buf.setFillColumn(10);
            buf.setAutoFillEnabled(true);
            buf.getArea().moveTo(buf.getArea().getLength());
            return buf;
        });
        type(b, " + ccc");
        assertEquals("int aaa = bbb + ccc", text(b), "auto-fill is prose-only; code is never wrapped");
    }

    @Test
    void breaksCarryTheLeadingIndent() throws Exception {
        EditorBuffer b = prose("    aaaa bbbb", 14); // "    aaaa bbbb" (13) fits; adding a word wraps once
        type(b, " cccc");
        assertEquals("    aaaa bbbb\n    cccc", text(b), "the wrapped line keeps the paragraph indent");
    }

    @Test
    void oneUndoRemovesTheTypedWordAndItsBreakTogether() throws Exception {
        EditorBuffer b = prose("the quick brown", 10);
        type(b, " fox");
        assertEquals("the quick\nbrown fox", text(b));
        // Undo repeatedly until the auto-fill break is gone; the break must not be a separate,
        // stranded undo step that leaves the document half-wrapped.
        FxTestSupport.runOnFx(() -> {
            for (int i = 0; i < 10 && b.getArea().getText().contains("\n"); i++) {
                b.getArea().undo();
            }
        });
        assertFalse(text(b).contains("\n"), "undo unwinds the auto-inserted break, not stranding it");
    }
}
