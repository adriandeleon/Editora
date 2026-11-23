package com.editora.editops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** {@link Filler} by buffer mode: code comments only, Markdown structure left alone, list items hanging. */
class FillerModesTest {

    private static String apply(String text, Filler.Edit e) {
        return text.substring(0, e.from()) + e.replacement() + text.substring(e.to());
    }

    private static String fill(String text, int caret, int column, String comment, Filler.Mode mode) {
        Filler.Edit e = Filler.fillParagraph(text, caret, column, comment, mode, 8);
        return e == null ? null : apply(text, e);
    }

    private static String region(String text, int from, int to, int column, String comment, Filler.Mode mode) {
        Filler.Edit e = Filler.fillRegion(text, from, to, column, comment, mode, 8);
        return e == null ? null : apply(text, e);
    }

    @Test
    void modeForLanguage() {
        assertEquals(Filler.Mode.MARKDOWN, Filler.Mode.forLanguage("markdown"));
        assertEquals(Filler.Mode.TEXT, Filler.Mode.forLanguage("typst"));
        assertEquals(Filler.Mode.TEXT, Filler.Mode.forLanguage("html"));
        assertEquals(Filler.Mode.CODE, Filler.Mode.forLanguage("java"));
        assertEquals(Filler.Mode.CODE, Filler.Mode.forLanguage("ini"), "a line comment makes it code");
        assertEquals(Filler.Mode.TEXT, Filler.Mode.forLanguage("plaintext"));
    }

    @Test
    void codeModeFillsLineCommentsButNeverCode() {
        String text = "int x = 1;\n// alpha beta gamma\n// delta\nint y = 2;";
        assertEquals(
                "int x = 1;\n// alpha beta gamma delta\nint y = 2;",
                fill(text, text.indexOf("alpha"), 70, "//", Filler.Mode.CODE));
        assertNull(fill(text, 0, 5, "//", Filler.Mode.CODE), "code is a separator");
    }

    @Test
    void codeModeFillsTheTextOfABlockComment() {
        String text = "/*\n * one two three\n * four five\n */\nint z;";
        assertEquals(
                "/*\n * one two\n * three four\n * five\n */\nint z;",
                fill(text, text.indexOf("one"), 13, "//", Filler.Mode.CODE));
        assertNull(fill(text, 0, 13, "//", Filler.Mode.CODE), "the /* line is a delimiter");
    }

    @Test
    void aJavadocBlockTagIsItsOwnParagraph() {
        String text = "/**\n * Text one\n * two.\n * @param x the x value here\n */";
        assertEquals(
                "/**\n * Text one two.\n * @param x the x value here\n */",
                fill(text, text.indexOf("Text"), 70, "//", Filler.Mode.CODE));
        assertEquals(
                "/**\n * Text one\n * two.\n * @param x the\n * x value here\n */",
                fill(text, text.indexOf("@param"), 16, "//", Filler.Mode.CODE));
    }

    @Test
    void markdownStructureIsNeverFilled() {
        String md = "# Heading one two\n\n```\ncode code code\n```\n\n| a | b |\n\nTitle words\n===\n\n***\n";
        for (String probe : new String[] {"Heading", "code code", "| a", "Title", "***"}) {
            assertNull(fill(md, md.indexOf(probe), 5, null, Filler.Mode.MARKDOWN), probe);
        }
        assertEquals(
                "~~~\nkeep  this\n~~~\nsome\ntext",
                fill("~~~\nkeep  this\n~~~\nsome text", 20, 5, null, Filler.Mode.MARKDOWN));
    }

    @Test
    void listItemsHangUnderTheirText() {
        String md = "- one two three four\n- five six\n1. seven eight nine\n   ten";
        assertEquals(
                "- one two\n  three four\n- five six\n1. seven eight nine\n   ten",
                fill(md, 2, 12, null, Filler.Mode.MARKDOWN));
        assertEquals(
                "- one two three four\n- five six\n1. seven\n   eight\n   nine\n   ten",
                fill(md, md.indexOf("ten"), 10, null, Filler.Mode.MARKDOWN),
                "a plain line under an item belongs to it");
        assertNull(fill("-\n+\n", 0, 10, null, Filler.Mode.TEXT), "a marker without text");
    }

    @Test
    void aStarItemIsABulletOutsideAComment() {
        assertEquals("* alpha\n  beta", fill("* alpha beta", 0, 8, null, Filler.Mode.TEXT));
        assertEquals("**bold** text more", fill("**bold** text\nmore", 0, 70, null, Filler.Mode.TEXT));
    }

    @Test
    void regionKeepsStructuralLinesAndHandlesReversedSelections() {
        String md = "# Title\naaa bbb ccc\n\nddd eee fff\n";
        assertEquals(
                "# Title\naaa bbb\nccc\n\nddd eee\nfff\n", region(md, md.length(), 0, 7, null, Filler.Mode.MARKDOWN));
        assertNull(region("short\n", 0, 6, 70, null, Filler.Mode.TEXT), "already filled");
        assertEquals("aaa\nbbb\nccc", region("aaa bbb ccc", 0, 3, 3, null, Filler.Mode.TEXT));
    }

    @Test
    void continuationLinesMayBeIndentedDifferently() {
        assertEquals("// a b c", fill("// a\n   //   b\n  // c", 0, 70, "//", Filler.Mode.TEXT));
        assertEquals("/// a b", fill("/// a\n/// b", 0, 70, "//", Filler.Mode.TEXT));
        assertEquals("REM one two", fill("REM one\nrem two", 0, 70, "REM", Filler.Mode.TEXT));
    }

    @Test
    void lineKinds() {
        assertNull(Filler.kind("  /* open", "//"));
        assertNull(Filler.kind("*/", "//"));
        assertNull(Filler.kind("//   ", "//"), "a marker without text separates");
        assertEquals("//", Filler.kind("//! inner doc", "//"));
        assertEquals("REM", Filler.kind("rem a remark", "REM"));
        assertEquals("", Filler.kind("REMOTE=1", "REM"));
        assertEquals("", Filler.kind("**bold**", null));
        assertEquals(">", Filler.kind("> quote", "#"));
    }

    @Test
    void widthsAndPrefixes() {
        assertEquals(6, Filler.width("\tab", 0, 4));
        assertEquals(4, Filler.width("\t", 1, 4));
        assertEquals(5, Filler.width("漢字x", 0, 8));
        assertEquals(2, Filler.width("😀", 0, 8));
        assertArrayEquals(new String[] {"- ", "  "}, Filler.proseBreakPrefixes("- item text", null));
        assertArrayEquals(new String[] {"  12) ", "      "}, Filler.proseBreakPrefixes("  12) item", null));
        assertArrayEquals(new String[] {"* ", "  "}, Filler.proseBreakPrefixes("* star item", null));
        assertArrayEquals(new String[] {"", ""}, Filler.proseBreakPrefixes("- ", null));
        assertArrayEquals(new String[] {"# ", "# "}, Filler.proseBreakPrefixes("# comment", "#"));
        assertArrayEquals(new String[] {"", ""}, Filler.proseBreakPrefixes("1.5 is a number", null));
    }

    @Test
    void wideCharactersTakeTwoColumnsInEveryEastAsianBlock() {
        int[] wide = {
            0x1100, 0x115F, 0x2E80, 0xA4CF, 0xAC00, 0xD7A3, 0xF900, 0xFAFF, 0xFE30, 0xFE4F, 0xFF00, 0xFF60, 0xFFE0,
            0xFFE6, 0x1F300, 0x1FAFF, 0x20000, 0x3FFFD
        };
        for (int cp : wide) {
            assertEquals(2, Filler.width(new String(Character.toChars(cp)), 0, 8), Integer.toHexString(cp));
        }
        int[] narrow = {
            0x41, 0x10FF, 0x1160, 0x2E7F, 0xA4D0, 0xABFF, 0xD7A4, 0xF8FF, 0xFB00, 0xFE2F, 0xFE50, 0xFEFF, 0xFF61,
            0xFFDF, 0xFFE7, 0x1F2FF, 0x1FB00, 0x1FFFF, 0x3FFFE
        };
        for (int cp : narrow) {
            assertEquals(1, Filler.width(new String(Character.toChars(cp)), 0, 8), Integer.toHexString(cp));
        }
    }

    @Test
    void markdownRulesAndHeadingsAreToldFromText() {
        for (String structural : new String[] {"#", "###### six", "#\tx", "- - -", "___", "* * *", "=", "--"}) {
            String md = "para one two\n" + structural + "\nthree four";
            Filler.Edit e = Filler.fillRegion(md, 0, md.length(), 70, null, Filler.Mode.MARKDOWN, 8);
            String out = e == null ? md : apply(md, e);
            assertEquals(true, out.contains("\n" + structural + "\n"), structural + " -> " + out);
        }
        for (String text : new String[] {"####### seven", "#tag", "-- x", "== x", "=-", "**", "_x_"}) {
            String md = "para\n" + text + "\nend";
            assertEquals("para " + text + " end", fill(md, 0, 70, null, Filler.Mode.MARKDOWN), text + " is plain text");
        }
    }

    @Test
    void listMarkersNeedASpaceAfterThem() {
        assertEquals("x 1.5 and", fill("x\n1.5 and", 0, 70, null, Filler.Mode.TEXT));
        assertNull(fill("a\n2) more", 0, 70, null, Filler.Mode.TEXT), "2) starts an item of its own");
        assertEquals("+\tone\n  two", fill("+\tone two", 0, 8, null, Filler.Mode.TEXT));
        assertEquals(
                "1234567890. ten digits is not a marker",
                fill("1234567890. ten digits\nis not a marker", 0, 70, null, Filler.Mode.TEXT));
        assertEquals("-x y", fill("-x\ny", 0, 70, null, Filler.Mode.TEXT));
        assertEquals("12 z", fill("12\nz", 0, 70, null, Filler.Mode.TEXT));
    }

    @Test
    void aNullModeReadsAsText() {
        Filler.Edit e = Filler.fillParagraph("a\nb", 0, 70, " ", null, 8);
        assertEquals("a b", apply("a\nb", e));
    }

    @Test
    void starItemsInsideAndOutsideComments() {
        String text = "/*\n * a\n * b\n */\n* c\n* d";
        assertEquals("/*\n * a b\n */\n* c\n* d", fill(text, 5, 70, null, Filler.Mode.TEXT));
        assertNull(fill(text, text.indexOf("* c"), 70, null, Filler.Mode.TEXT), "each * item is its own paragraph");
        String afterClose = "*/\n* e f";
        assertEquals("*/\n* e\n  f", fill(afterClose, 4, 4, null, Filler.Mode.TEXT));
    }
}
