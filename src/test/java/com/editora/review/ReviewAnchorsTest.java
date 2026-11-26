package com.editora.review;

import java.util.List;

import com.editora.config.ReviewAuthor;
import com.editora.config.ReviewThread;
import com.editora.config.TextAnchor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ReviewAnchors}: a thread's lines captured, found again after edits, outdated, and replaced. */
class ReviewAnchorsTest {

    private static final String FILE = """
            class A {
                int a = 1;
                int b = 2;
                int c = 3;
            }
            """;

    private static ReviewThread on(String text, int startLine, int endLine) {
        TextAnchor anchor = ReviewAnchors.capture(text, startLine, endLine);
        return new ReviewThreads(() -> 1L)
                .start(List.of(), ReviewThreads.Start.onFile("A.java", anchor), ReviewAuthor.user(), "why?", null)
                .get(0);
    }

    @Test
    void aCaptureHoldsTheWholeLinesAndSomeContext() {
        TextAnchor a = ReviewAnchors.capture(FILE, 2, 1); // either order
        assertEquals(1, a.line());
        assertEquals(2, a.endLine());
        assertEquals("    int a = 1;\n    int b = 2;", a.selectedText());
        assertEquals("class A {\n", a.prefix());
        assertTrue(a.suffix().startsWith("\n    int c = 3;"), a.suffix());
        assertEquals(0, ReviewAnchors.capture(FILE, -5, -5).line(), "clamped to the text");
        assertEquals("", ReviewAnchors.capture(FILE, 99, 99).selectedText(), "past the end: the last (empty) line");
        assertEquals("x", ReviewAnchors.capture("x\r\ny\r\n", 0, 0).selectedText(), "without its CRLF");
    }

    @Test
    void aThreadFollowsItsLinesWhenLinesAreAddedAbove() {
        ReviewThread t = on(FILE, 2, 2);
        String edited = "// header\n// more\n" + FILE;
        ReviewAnchors.Located at = ReviewAnchors.locate(t, edited);
        assertFalse(at.outdated());
        assertTrue(at.moved());
        assertEquals(4, at.startLine());
        assertEquals(4, at.endLine());
        ReviewAnchors.Located same = ReviewAnchors.locate(t, FILE);
        assertFalse(same.moved(), "unchanged text: where it was");
    }

    @Test
    void aThreadPicksItsOwnCopyOfARepeatedLineByContext() {
        String text = "a\nx = 0;\nb\nc\nx = 0;\nd\n";
        ReviewThread second = on(text, 4, 4); // the x = 0; between c and d
        String edited = "new\n" + text;
        assertEquals(5, ReviewAnchors.locate(second, edited).startLine());
    }

    @Test
    void aThreadWhoseLinesChangedIsOutdatedAndKeepsWhereItWas() {
        ReviewThread t = on(FILE, 2, 3);
        String edited = FILE.replace("int b = 2;", "int b = 20;");
        ReviewAnchors.Located at = ReviewAnchors.locate(t, edited);
        assertTrue(at.outdated());
        assertEquals(2, at.startLine());
        assertEquals(3, at.endLine());
        assertTrue(ReviewAnchors.locate(t, "").outdated(), "the file emptied");
    }

    @Test
    void aSuggestionReplacesTheThreadsLinesAndNothingElse() {
        ReviewThread t = on(FILE, 2, 3);
        String applied = ReviewAnchors.applySuggestion(t, "// top\n" + FILE, "    int bc = 23;");
        assertEquals("// top\nclass A {\n    int a = 1;\n    int bc = 23;\n}\n", applied);
        String crlf = FILE.replace("\n", "\r\n");
        ReviewThread onCrlf = on(crlf, 1, 1);
        assertEquals(
                crlf.replace("    int a = 1;", "    int a = 10;"),
                ReviewAnchors.applySuggestion(onCrlf, crlf, "    int a = 10;"),
                "the line ending is kept");
        assertNull(ReviewAnchors.applySuggestion(t, FILE.replace("int c", "long c"), "x"), "outdated: not applied");
    }
}
