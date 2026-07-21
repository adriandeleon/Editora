package com.editora.editor;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full (length-changing) case folding in literal search — #444. {@code String.regionMatches} folds per
 * character and so can only match equal-length case pairs; these pin that {@code ß}↔{@code SS} and the
 * ligature folds match in both directions, that the reported offsets are the <em>original</em> text's, and
 * that a match can never land inside one character's expansion.
 */
class CaseFoldSearchTest {

    private static List<int[]> find(String text, String query) {
        return SearchMatcher.matches(text, query, false, false, false);
    }

    private static List<int[]> findWord(String text, String query) {
        return SearchMatcher.matches(text, query, false, false, true);
    }

    /** Asserts exactly one match, and that slicing the ORIGINAL text at the reported offsets gives it. */
    private static void assertSingleMatch(String text, String query, String expectedSlice) {
        List<int[]> m = find(text, query);
        assertEquals(1, m.size(), "expected one match of '" + query + "' in '" + text + "', got " + m.size());
        assertEquals(expectedSlice, text.substring(m.get(0)[0], m.get(0)[1]), "offsets must slice the original text");
    }

    // --- the headline cases -------------------------------------------------------------------------

    @Test
    void upperQueryFindsTheSharpS() {
        assertSingleMatch("Die Straße hier", "STRASSE", "Straße");
    }

    @Test
    void sharpSQueryFindsTheUpperText() {
        assertSingleMatch("DIE STRASSE HIER", "Straße", "STRASSE");
    }

    @Test
    void sharpSMatchesDoubleSInEitherDirection() {
        assertSingleMatch("strasse", "STRASSE", "strasse");
        assertSingleMatch("straße", "strasse", "straße");
        assertSingleMatch("strasse", "straße", "strasse");
    }

    @Test
    void ligaturesFold() {
        assertSingleMatch("the ﬁle", "FILE", "ﬁle"); // U+FB01
        assertSingleMatch("the file", "ﬁle", "file");
        assertSingleMatch("eﬄuent", "EFFLUENT", "eﬄuent"); // U+FB04, a 3-char expansion
    }

    // --- offsets must be the original text's --------------------------------------------------------

    @Test
    void offsetsAreOriginalNotFolded() {
        // "Straße" is 6 chars; its fold "STRASSE" is 7. A folded-copy implementation that forgot to map
        // offsets back would report [4,11) here and slice past the end of the word.
        String text = "auf Straße gehen";
        List<int[]> m = find(text, "STRASSE");
        assertEquals(1, m.size());
        assertEquals(4, m.get(0)[0]);
        assertEquals(10, m.get(0)[1]);
        assertEquals("Straße", text.substring(4, 10));
    }

    @Test
    void aLaterMatchIsNotShiftedByAnEarlierExpansion() {
        // Each ß adds a char to the fold; a bad offset map drifts further right with every one.
        String text = "Straße Gasse Straße ENDE";
        List<int[]> m = find(text, "ENDE");
        assertEquals(1, m.size());
        assertEquals(text.indexOf("ENDE"), m.get(0)[0]);
        assertEquals("ENDE", text.substring(m.get(0)[0], m.get(0)[1]));
    }

    @Test
    void findsEveryOccurrence() {
        String text = "Straße und Strasse und STRASSE";
        List<int[]> m = find(text, "straße");
        assertEquals(3, m.size());
        assertEquals("Straße", text.substring(m.get(0)[0], m.get(0)[1]));
        assertEquals("Strasse", text.substring(m.get(1)[0], m.get(1)[1]));
        assertEquals("STRASSE", text.substring(m.get(2)[0], m.get(2)[1]));
    }

    // --- a match must consume whole characters ------------------------------------------------------

    @Test
    void aQueryNeverMatchesHalfOfAnExpansion() {
        // ß folds to SS. Searching "S" must not report a zero-or-partial match "inside" the ß.
        String text = "Straße";
        List<int[]> m = find(text, "S");
        assertEquals(1, m.size(), "only the leading S, never half the ß");
        assertEquals(0, m.get(0)[0]);
        assertEquals(1, m.get(0)[1]);
    }

    @Test
    void aQueryEndingMidExpansionDoesNotMatch() {
        // "STRAS" would consume only the first half of ß's expansion — not a match on "Straße".
        assertTrue(find("Straße", "STRAS").isEmpty());
    }

    @Test
    void everyReportedRangeIsNonEmptyAndOrdered() {
        String text = "ß ss SS ﬁ fi";
        for (String q : new String[] {"ss", "SS", "ß", "fi", "ﬁ"}) {
            int prevEnd = -1;
            for (int[] r : find(text, q)) {
                assertTrue(r[1] > r[0], q + ": empty range " + r[0] + ".." + r[1]);
                assertTrue(r[0] >= prevEnd, q + ": overlapping/unordered range");
                prevEnd = r[1];
            }
        }
    }

    // --- interaction with the other flags -----------------------------------------------------------

    @Test
    void caseSensitiveSearchDoesNotFold() {
        assertTrue(
                SearchMatcher.matches("Straße", "STRASSE", true, false, false).isEmpty(),
                "a case-sensitive search must stay exact");
        assertEquals(
                1, SearchMatcher.matches("Straße", "Straße", true, false, false).size());
    }

    @Test
    void wholeWordStillAppliesOnOriginalOffsets() {
        assertEquals(1, findWord("die Straße hier", "STRASSE").size());
        assertTrue(findWord("Straßenbahn", "STRASSE").isEmpty(), "a prefix of a longer word is not whole-word");
    }

    @Test
    void plainCaseFoldsStillWorkOnTheFoldingPath() {
        // A text containing ß routes through the folding path — ordinary folds must keep working there.
        assertSingleMatch("Straße, Café", "café", "Café");
    }

    // --- the fast path must be untouched ------------------------------------------------------------

    @Test
    void asciiTextIsUnaffected() {
        String text = "The quick brown fox";
        assertEquals(1, find(text, "QUICK").size());
        assertEquals(1, find(text, "quick").size());
        assertTrue(find(text, "zebra").isEmpty());
    }

    @Test
    void equalLengthFoldsStillWorkWithoutTheFoldingPath() {
        assertSingleMatch("Café au lait", "CAFÉ", "Café"); // é does not expand — plain path
    }

    @Test
    void mayExpandRejectsAsciiAndAcceptsExpandingChars() {
        assertTrue(!CaseFold.mayExpand("plain ascii text"));
        assertTrue(!CaseFold.mayExpand("café résumé naïve")); // accented, but nothing expands
        assertTrue(!CaseFold.mayExpand(""));
        assertTrue(!CaseFold.mayExpand(null));
        assertTrue(CaseFold.mayExpand("Straße"));
        assertTrue(CaseFold.mayExpand("ﬁle"));
    }

    @Test
    void foldMatchesJavaUppercasingForSimpleCases() {
        assertEquals("STRASSE", CaseFold.fold("Straße"));
        assertEquals("STRASSE", CaseFold.fold("strasse"));
        assertEquals("FILE", CaseFold.fold("ﬁle"));
        assertEquals("", CaseFold.fold(""));
        assertEquals("", CaseFold.fold(null));
    }

    @Test
    void emptyAndAbsentQueriesAreSafe() {
        assertTrue(find("Straße", "").isEmpty());
        assertTrue(find("Straße", "xyz").isEmpty());
        assertTrue(find("", "STRASSE").isEmpty());
    }

    /** A supplementary-plane char must not derail the walk (no expanding code point exists above the BMP). */
    @Test
    void surrogatePairsAreWalkedWholeAndDoNotBreakOffsets() {
        String text = "a😀 Straße"; // an emoji before the word
        List<int[]> m = find(text, "STRASSE");
        assertEquals(1, m.size());
        assertEquals("Straße", text.substring(m.get(0)[0], m.get(0)[1]));
    }
}
