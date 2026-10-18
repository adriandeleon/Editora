package com.editora.editops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Enter inside a JSDoc/Javadoc block comment continues it (Node.js review finding A6). */
class CommentContinuationTest {

    /** The text after pressing Enter at the {@code |} in {@code marked}, with {@code |} where the caret lands. */
    private static String enter(String marked, String language) {
        int caret = marked.indexOf('|');
        String text = marked.replace("|", "");
        Indenter.EnterEdit e = Indenter.enterEdit(text, caret, language, "  ");
        String inserted =
                e.insert().substring(0, e.caretOffset()) + "|" + e.insert().substring(e.caretOffset());
        return text.substring(0, caret) + inserted + text.substring(caret);
    }

    @Test
    void enterAfterAnUnclosedDocOpenerAddsTheBodyLineAndTheCloser() {
        assertEquals("/**\n * |\n */\nfunction f() {}\n", enter("/**|\nfunction f() {}\n", "javascript"));
        assertEquals("  /**\n   * |\n   */\n", enter("  /**|\n", "typescript"));
    }

    @Test
    void enterBetweenTheOpenerAndTheCloserSplitsThem() {
        assertEquals("/**\n * |\n */\n", enter("/**|*/\n", "javascript"));
    }

    @Test
    void anOpenerAlreadyClosedBelowOnlyContinues() {
        assertEquals("/**\n * |\n * Loads it.\n */\n", enter("/**|\n * Loads it.\n */\n", "javascript"));
        // The next comment's closer does not count when another comment opens first.
        assertEquals("/**\n * |\n */\nx();\n/* a */\n", enter("/**|\nx();\n/* a */\n", "javascript"));
    }

    @Test
    void aBodyLineContinuesAndTheCloserLineGoesBackToTheCommentIndent() {
        assertEquals("/**\n * Loads.\n * |\n */", enter("/**\n * Loads.|\n */", "javascript"));
        assertEquals("  /**\n   * @param a\n   * |", enter("  /**\n   * @param a|", "java"));
        assertEquals("  /**\n   */\n  |", enter("  /**\n   */|", "javascript"));
    }

    @Test
    void codeAndOtherLanguagesAreLeftAlone() {
        assertEquals("const a = 1;\n|", enter("const a = 1;|", "javascript"));
        assertEquals("// x\n|", enter("// x|", "javascript"));
        assertEquals("x = 1\n|", enter("x = 1|", "python"));
        // A one-line /** … */ comment is closed: plain Enter after it.
        assertEquals("/** done */\n|", enter("/** done */|", "javascript"));
    }
}
