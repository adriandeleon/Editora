package com.editora.search;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A12-16: an invalid regex replacement is named before Replace in Files touches a file. */
class ReplacementErrorTest {

    private static SearchQuery regex(String text) {
        return new SearchQuery(text, true, true, false);
    }

    @Test
    void referencesTheJdkRejectsAreReported() {
        assertNotNull(MultiFileSearch.replacementError(regex("cost"), "$9"), "no such group");
        assertNotNull(MultiFileSearch.replacementError(regex("cost"), "$cost"), "a $ that starts no reference");
        assertNotNull(MultiFileSearch.replacementError(regex("cost"), "x\\"), "trailing backslash");
        assertNotNull(MultiFileSearch.replacementError(regex("cost"), "x$"), "trailing $");
        assertNotNull(MultiFileSearch.replacementError(regex("(a)"), "${nope}"), "unknown named group");
        assertNotNull(MultiFileSearch.replacementError(new SearchQuery("a", false, true, true), "$1"), "whole word");
    }

    @Test
    void validReplacementsPass() {
        assertNull(MultiFileSearch.replacementError(regex("(a)(b)"), "$2$1"));
        assertNull(MultiFileSearch.replacementError(regex("(?<w>a)"), "${w}!"));
        assertNull(MultiFileSearch.replacementError(regex("a"), "\\$5 \\\\"));
        assertNull(MultiFileSearch.replacementError(regex("a"), ""));
        assertNull(MultiFileSearch.replacementError(regex("a # trailing comment"), "b"));
    }

    @Test
    void aLiteralSearchTakesItsReplacementVerbatim() {
        SearchQuery literal = new SearchQuery("cost", true, false, false);
        assertNull(MultiFileSearch.replacementError(literal, "$cost\\"));
        assertEquals(1, MultiFileSearch.replaceAll("cost", literal, "$cost\\").count());
    }

    @Test
    void aBadPatternIsNotReportedAsABadReplacement() {
        assertNull(MultiFileSearch.replacementError(regex("a("), "$9"));
    }

    @Test
    void anEscapedGlobMetacharacterBecomesALiteralForTheJavaMatcher() {
        assertEquals("we,ird/[*].txt", Globs.javaGlob("we\\,ird/\\*.txt", true).replace("[,]", ","));
        assertEquals("src\\main", Globs.javaGlob("src\\main", false), "Windows: the backslash is a separator");
    }
}
