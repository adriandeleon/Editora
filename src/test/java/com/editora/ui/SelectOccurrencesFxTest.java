package com.editora.ui;

import java.util.List;

import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end (headless-FX) coverage of "select all occurrences": a real {@link EditorBuffer} with
 * multi-caret enabled places a caret at every occurrence of the word/selection, leaving the primary on the
 * one under the caret. The word/primary logic is unit-tested in {@code SelectOccurrencesTest}.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SelectOccurrencesFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private EditorBuffer buffer(String content) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent(content);
            b.setMultiCaretEnabled(true);
            b.getNode();
            return b;
        });
    }

    @Test
    void selectsACaretAtEveryOccurrenceOfTheWordUnderTheCaret() throws Exception {
        EditorBuffer b = buffer("foo bar foo baz foo");
        int count = FxTestSupport.callOnFx(() -> {
            CodeArea a = FxTestSupport.field(b, "area");
            a.moveTo(1); // inside the first "foo"
            int n = b.selectAllOccurrences();
            assertTrue(b.hasMultipleCarets(), "three foos → extra carets");
            return n;
        });
        assertEquals(3, count, "one caret per occurrence of 'foo'");
    }

    @Test
    void usesTheSelectionWhenThereIsOne() throws Exception {
        EditorBuffer b = buffer("ab abc ab abcd ab");
        int count = FxTestSupport.callOnFx(() -> {
            CodeArea a = FxTestSupport.field(b, "area");
            a.selectRange(0, 2); // "ab" — a substring that also occurs inside "abc"/"abcd"
            return b.selectAllOccurrences();
        });
        // literal substring match: "ab" appears in "ab", "abc", "ab", "abcd", "ab" = 5 times
        assertEquals(5, count);
    }

    @Test
    void caseSensitiveMatching() throws Exception {
        EditorBuffer b = buffer("Foo foo FOO foo");
        int count = FxTestSupport.callOnFx(() -> {
            CodeArea a = FxTestSupport.field(b, "area");
            a.moveTo(a.getText().indexOf("foo") + 1); // the lowercase "foo"
            return b.selectAllOccurrences();
        });
        assertEquals(2, count, "only the two lowercase 'foo' match, not 'Foo'/'FOO'");
    }

    @Test
    void noWordUnderCaretIsANoOp() throws Exception {
        EditorBuffer b = buffer("a + b");
        int count = FxTestSupport.callOnFx(() -> {
            CodeArea a = FxTestSupport.field(b, "area");
            a.moveTo(2); // on the '+'
            int n = b.selectAllOccurrences();
            assertFalse(b.hasMultipleCarets());
            return n;
        });
        assertEquals(0, count);
    }

    @Test
    void placeOccurrenceCaretsKeepsThePrimaryUnderTheAnchor() throws Exception {
        EditorBuffer b = buffer("xx .. xx .. xx");
        int[] primary = FxTestSupport.callOnFx(() -> {
            CodeArea a = FxTestSupport.field(b, "area");
            List<int[]> ranges = List.of(new int[] {0, 2}, new int[] {6, 8}, new int[] {12, 14});
            b.placeOccurrenceCarets(ranges, 6); // anchor inside the middle occurrence
            return new int[] {a.getSelection().getStart(), a.getSelection().getEnd()};
        });
        assertEquals(6, primary[0], "the primary caret is the occurrence containing the anchor");
        assertEquals(8, primary[1]);
    }
}
