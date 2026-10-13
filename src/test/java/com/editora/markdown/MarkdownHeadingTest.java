package com.editora.markdown;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MarkdownHeadingTest {

    private static String applied(String text, int from, int to, int delta) {
        MarkdownEdit e = MarkdownHeading.apply(text, from, to, delta);
        return e == null ? text : text.substring(0, e.from()) + e.replacement() + text.substring(e.to());
    }

    @Test
    void demotePromote() {
        assertEquals("# foo", applied("foo", 0, 0, 1));
        assertEquals("## foo", applied("# foo", 0, 0, 1));
        assertEquals("## foo", applied("### foo", 0, 0, -1));
        assertEquals("# foo", applied("# foo", 0, 0, -1), "promote stops at level 1; Normal is the Style choice");
    }

    @Test
    void clampsToOneThroughSix() {
        assertEquals("###### h", applied("###### h", 0, 0, 1));
        assertEquals("foo", applied("foo", 0, 0, -1));
    }

    @Test
    void setLevelAbsolute() {
        MarkdownEdit e = MarkdownHeading.setLevel("foo", 0, 0, 3);
        assertEquals("### foo", e.replacement());
        MarkdownEdit z = MarkdownHeading.setLevel("## foo", 0, 0, 0);
        assertEquals("foo", z.replacement());
    }

    @Test
    void multiLineSelection() {
        MarkdownEdit e = MarkdownHeading.apply("a\nb", 0, 3, 1);
        assertEquals("# a\n# b", e.replacement());
        assertEquals(0, e.from());
        assertEquals(3, e.to());
    }

    @Test
    void preservesIndentation() {
        assertEquals("  ## x", applied("  # x", 0, 0, 1));
    }

    /** The edit as the editor applies it, with {@code ‸} at a collapsed caret or {@code «»} around a selection. */
    private static String shown(String marked, java.util.function.Function<int[], MarkdownEdit> op, String[] text) {
        int caret = marked.indexOf('‸');
        int a = marked.indexOf('«');
        int b = marked.indexOf('»') - 1;
        String clean = marked.replace("‸", "").replace("«", "").replace("»", "");
        text[0] = clean;
        int[] sel = caret >= 0 ? new int[] {caret, caret} : new int[] {a, b};
        MarkdownEdit e = op.apply(sel);
        if (e == null) {
            return marked;
        }
        StringBuilder out = new StringBuilder(clean.substring(0, e.from()) + e.replacement() + clean.substring(e.to()));
        if (e.selStart() == e.selEnd()) {
            out.insert(e.selStart(), '‸');
        } else {
            out.insert(e.selEnd(), '»').insert(e.selStart(), '«');
        }
        return out.toString();
    }

    private static String demoted(String marked) {
        String[] t = new String[1];
        return shown(marked, sel -> MarkdownHeading.apply(t[0], sel[0], sel[1], 1), t);
    }

    @Test
    void theCaretStaysOnItsTextInsteadOfSelectingTheLine() {
        // The whole line used to come back selected, so the next key typed replaced it.
        assertEquals("# My ti‸tle\nnext", demoted("My ti‸tle\nnext"));
        assertEquals("## ‸x", demoted("# ‸x"));
        assertEquals("# a «word» b", demoted("a «word» b"));
    }

    @Test
    void aSelectionEndingAtTheStartOfTheNextLineLeavesThatLineAlone() {
        assertEquals("«# one\n»two", demoted("«one\n»two"));
    }

    @Test
    void blankLinesNeverBecomeEmptyHeadings() {
        assertEquals("«# one\n\n# two»", demoted("«one\n\ntwo»"));
        assertNull(MarkdownHeading.apply("", 0, 0, 1));
        assertNull(MarkdownHeading.setLevel("a\n\nb", 2, 2, 2));
    }

    @Test
    void overSeveralLinesOnlyTheHeadingsMove() {
        assertEquals("«## A\ntext\n### B»", demoted("«# A\ntext\n## B»"));
    }

    @Test
    void theHeadingGoesBehindAQuoteOrListMarker() {
        assertEquals("- # ‸item", demoted("- ‸item"));
        assertEquals("> # ‸q", demoted("> ‸q"));
        assertEquals("1. ## ‸x", demoted("1. # ‸x"));
    }

    @Test
    void aTabAfterTheHashesIsStillAHeading() {
        assertEquals("##\t‸T", demoted("#\t‸T"));
    }

    @Test
    void aCommentInAFencedBlockIsNotAHeading() {
        assertEquals("```sh\n# com‸ment\n```", demoted("```sh\n# com‸ment\n```"));
    }

    @Test
    void aSetextHeadingBecomesAnAtxOne() {
        assertEquals("## Ti‸tle\nbody", demoted("Ti‸tle\n=====\nbody"));
        assertEquals("### Ti‸tle", demoted("Ti‸tle\n---"));
        MarkdownEdit normal = MarkdownHeading.setLevel("Title\n===", 0, 0, 0);
        assertEquals("Title", normal.replacement());
        assertEquals(1, MarkdownHeading.levelAt("Title\n===", 2));
        assertEquals(3, MarkdownHeading.levelAt("x\n### h", 4));
        assertEquals(0, MarkdownHeading.levelAt("plain", 0));
    }
}
