package com.editora.editops;

import com.editora.editops.Commenter.CommentStyle;
import com.editora.editops.Commenter.Edit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Unit tests for the pure comment toggle engine. */
class CommenterTest {

    /** Applies a toggle and returns the resulting full document text. */
    private static String apply(String text, int selStart, int selEnd, String lang) {
        Edit e = Commenter.toggle(text, selStart, selEnd, Commenter.styleFor(lang));
        if (e == null) {
            return text;
        }
        return text.substring(0, e.from()) + e.replacement() + text.substring(e.to());
    }

    @Test
    void styleMapping() {
        assertEquals(new CommentStyle("//", "/*", "*/"), Commenter.styleFor("java"));
        assertEquals(new CommentStyle("#", null, null), Commenter.styleFor("python"));
        assertEquals(new CommentStyle(null, "<!--", "-->"), Commenter.styleFor("xml"));
        assertEquals(new CommentStyle(null, "/*", "*/"), Commenter.styleFor("css"));
        assertEquals(new CommentStyle("--", "/*", "*/"), Commenter.styleFor("sql"));
        assertEquals(new CommentStyle("//", null, null), Commenter.styleFor("markwhen"));
    }

    @Test
    void markwhenLineComment() {
        assertEquals("// 2023: Event", apply("2023: Event", 0, 0, "markwhen"));
        assertEquals("2023: Event", apply("// 2023: Event", 0, 0, "markwhen")); // round-trip
    }

    @Test
    void singleLineUsesLineComment() {
        assertEquals("// foo", apply("foo", 0, 0, "java")); // comment
        assertEquals("foo", apply("// foo", 0, 0, "java")); // uncomment (round-trip)
        assertEquals("    // x = 1", apply("    x = 1", 0, 0, "java")); // keeps indentation
    }

    @Test
    void pythonLineComment() {
        assertEquals("# hi", apply("hi", 0, 0, "python"));
        assertEquals("hi", apply("# hi", 0, 0, "python"));
    }

    @Test
    void multiLineUsesBlockWhenAvailable() {
        String text = "a\nb";
        assertEquals("/* a\nb */", apply(text, 0, text.length(), "java")); // block wraps the selection
    }

    @Test
    void multiLineFallsBackToLineCommentWhenNoBlock() {
        String text = "a\nb";
        assertEquals("# a\n# b", apply(text, 0, text.length(), "python"));
        // round-trip uncomment
        assertEquals("a\nb", apply("# a\n# b", 0, "# a\n# b".length(), "python"));
    }

    @Test
    void multiLineLineCommentTogglesAllOrNothing() {
        // a mix (one commented, one not) → commenting everything, not uncommenting
        String text = "# a\nb";
        assertEquals("# # a\n# b", apply(text, 0, text.length(), "python"));
    }

    @Test
    void blockToggleWrapAndUnwrap() {
        assertEquals("<!-- x -->", apply("x", 0, 1, "xml"));
        assertEquals("x", apply("<!-- x -->", 0, "<!-- x -->".length(), "xml"));
        // css single line (block-only)
        assertEquals("/* color: red; */", apply("color: red;", 0, 0, "css"));
    }

    @Test
    void plaintextHasNoComment() {
        assertNull(Commenter.toggle("hello", 0, 0, Commenter.styleFor("plaintext")));
    }

    @Test
    void blankLinesSkippedWhenCommenting() {
        String text = "a\n\nb";
        assertEquals("# a\n\n# b", apply(text, 0, text.length(), "python")); // blank line untouched
    }
}
