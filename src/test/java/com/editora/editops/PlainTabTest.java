package com.editora.editops;

import com.editora.editops.Indenter.TabEdit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PlainTabTest {

    private static String apply(String text, TabEdit e) {
        return text.substring(0, e.from()) + e.replacement() + text.substring(e.to());
    }

    private static TabEdit tab(String text, int selStart, int selEnd, boolean shift) {
        return PlainTab.edit(text, selStart, selEnd, "markdown", 4, shift, null, null);
    }

    @Test
    void theLanguagesItServesAreTheOnesSmartTabDeclines() {
        assertEquals(Indenter.Style.PLAIN, Indenter.styleFor("markdown"));
        assertEquals(null, Indenter.smartTab("- a\n- b", 0, 7, "markdown", 4, false));
        // …and the block edit is borrowed from a language that does have a style.
        assertNotEquals(Indenter.Style.PLAIN, Indenter.styleFor("java"));
    }

    @Test
    void aSelectionIsIndentedLineByLineAndStaysSelected() {
        String text = "- a\n- b\n- c";
        TabEdit e = tab(text, 0, 7, false);
        assertEquals("    - a\n    - b\n- c", apply(text, e));
        assertEquals(0, e.selStart());
        assertEquals(15, e.selEnd());
    }

    @Test
    void shiftTabDedentsASelection() {
        String text = "  - a\n  - b";
        assertEquals("- a\n- b", apply(text, tab(text, 0, text.length(), true)));
    }

    @Test
    void shiftTabDedentsTheCaretLine() {
        String text = "    item";
        TabEdit e = tab(text, 6, 6, true);
        assertEquals("item", apply(text, e));
        assertEquals(2, e.selStart(), "the caret keeps its place in the text");
    }

    @Test
    void tabAtACaretInsertsOneUnitWhereverItIs() {
        TabEdit mid = tab("ab", 2, 2, false);
        assertEquals("ab    ", apply("ab", mid));
        assertEquals(6, mid.selStart());
        // In leading whitespace too: prose has no "indent the line above implies" to snap to.
        String text = "para\n";
        assertEquals("para\n    ", apply(text, tab(text, 5, 5, false)));
    }

    @Test
    void theUnitFollowsTheFileAndTheOverride() {
        assertEquals("ab\t", apply("ab", PlainTab.edit("ab", 2, 2, "plaintext", 4, false, false, null)));
        assertEquals("ab  ", apply("ab", PlainTab.edit("ab", 2, 2, "plaintext", 4, false, true, 2)));
        String tabbed = "\tx\ny";
        assertEquals("\tx\ny\t", apply(tabbed, PlainTab.edit(tabbed, 4, 4, "plaintext", 4, false, null, null)));
    }

    @Test
    void aTabStaysATabWhereItIsSyntax() {
        // A Makefile recipe and a TSV column separator, even under a "spaces" indent style.
        assertEquals("all:\n\t", apply("all:\n", PlainTab.edit("all:\n", 5, 5, "makefile", 4, false, true, 4)));
        assertEquals("a\t", apply("a", PlainTab.edit("a", 1, 1, "csv", 4, false, true, 4)));
        String recipe = "all:\necho a\necho b";
        assertEquals(
                "all:\n\techo a\n\techo b",
                apply(recipe, PlainTab.edit(recipe, 5, recipe.length(), "makefile", 4, false, null, null)));
    }
}
