package com.editora.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the pure {@link Filler} (Emacs fill commands). */
class FillerTest {

    /** Applies an edit to {@code text} and returns the resulting full text. */
    private static String apply(String text, Filler.Edit e) {
        return text.substring(0, e.from()) + e.replacement() + text.substring(e.to());
    }

    @Test
    void wrapsLongParagraphToFillColumn() {
        String text = "one two three four five six seven eight nine ten";
        Filler.Edit e = Filler.fillParagraph(text, 0, 20, null);
        assertEquals("one two three four\nfive six seven eight\nnine ten", apply(text, e));
        // every line within the column
        for (String line : apply(text, e).split("\n")) {
            assertTrue(line.length() <= 20, "line over column: " + line);
        }
    }

    @Test
    void joinsShortLinesAndCollapsesWhitespace() {
        String text = "alpha\nbeta   gamma\ndelta";
        Filler.Edit e = Filler.fillParagraph(text, 0, 70, null);
        assertEquals("alpha beta gamma delta", apply(text, e));
    }

    @Test
    void preservesLeadingIndent() {
        String text = "    lorem ipsum dolor sit amet consectetur adipiscing";
        Filler.Edit e = Filler.fillParagraph(text, 0, 24, null);
        String out = apply(text, e);
        for (String line : out.split("\n")) {
            assertTrue(line.startsWith("    "), "indent lost: [" + line + "]");
        }
        assertEquals("    lorem ipsum dolor\n    sit amet consectetur\n    adipiscing", out);
    }

    @Test
    void usesLineCommentAsFillPrefix() {
        String text = "// the quick brown fox jumps over the lazy dog again";
        Filler.Edit e = Filler.fillParagraph(text, 0, 24, "//");
        String out = apply(text, e);
        for (String line : out.split("\n")) {
            assertTrue(line.startsWith("// "), "comment prefix lost: [" + line + "]");
        }
        assertEquals("// the quick brown fox\n// jumps over the lazy\n// dog again", out);
    }

    @Test
    void usesBlockquotePrefix() {
        String text = "> quoted text that should wrap across multiple lines here";
        Filler.Edit e = Filler.fillParagraph(text, 0, 20, null);
        for (String line : apply(text, e).split("\n")) {
            assertTrue(line.startsWith("> "), "quote prefix lost: [" + line + "]");
        }
    }

    @Test
    void onlyFillsTheParagraphAtCaret() {
        String text = "first paragraph here\n\nsecond paragraph stays put untouched";
        Filler.Edit e = Filler.fillParagraph(text, 0, 10, null); // caret in first paragraph
        String out = apply(text, e);
        assertTrue(out.endsWith("\n\nsecond paragraph stays put untouched"), out);
        assertTrue(out.startsWith("first\nparagraph\nhere"), out);
    }

    @Test
    void caretOnBlankLineFillsFollowingParagraph() {
        String text = "\n\nlorem ipsum dolor sit amet";
        Filler.Edit e = Filler.fillParagraph(text, 0, 12, null);
        assertEquals("\n\nlorem ipsum\ndolor sit\namet", apply(text, e));
    }

    @Test
    void neverBreaksAWordLongerThanTheColumn() {
        String text = "supercalifragilisticexpialidocious and more";
        Filler.Edit e = Filler.fillParagraph(text, 0, 10, null);
        assertEquals("supercalifragilisticexpialidocious\nand more", apply(text, e));
    }

    @Test
    void alreadyFilledIsNoOp() {
        String text = "short line";
        assertNull(Filler.fillParagraph(text, 0, 70, null));
    }

    @Test
    void blankBufferIsNoOp() {
        assertNull(Filler.fillParagraph("   \n  ", 0, 70, null));
    }

    @Test
    void fillRegionFillsEachParagraphPreservingBlankSeparators() {
        String text = "aaa bbb ccc ddd\n\neee fff ggg hhh";
        Filler.Edit e = Filler.fillRegion(text, 0, text.length(), 7, null);
        assertEquals("aaa bbb\nccc ddd\n\neee fff\nggg hhh", apply(text, e));
    }

    @Test
    void fillPrefixDetection() {
        assertEquals("    ", Filler.fillPrefix("    hello world", null));
        assertEquals("// ", Filler.fillPrefix("// hello", "//"));
        assertEquals("  # ", Filler.fillPrefix("  # indented comment", "#"));
        assertEquals("> ", Filler.fillPrefix("> quote", null));
        assertEquals("", Filler.fillPrefix("plain text", "//"));
    }
}
