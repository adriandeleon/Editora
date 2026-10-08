package com.editora.sync;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The entry-wise three-way merge of each synced file type. */
class SyncMergeTest {

    private static SyncMerge.FileResult dictionary(String base, String mine, String theirs) {
        return SyncMerge.merge(SyncCategory.DICTIONARY, "dictionary.txt", base, mine, theirs);
    }

    private static SyncMerge.FileResult snippets(String base, String mine, String theirs) {
        return SyncMerge.merge(SyncCategory.SNIPPETS, "snippets/java.json", base, mine, theirs);
    }

    private static SyncMerge.FileResult abbrevs(String base, String mine, String theirs) {
        return SyncMerge.merge(SyncCategory.ABBREVIATIONS, "abbreviations.json", base, mine, theirs);
    }

    private static String abbrevFile(String... pairs) {
        StringBuilder sb = new StringBuilder("{\"schemaVersion\":1,\"abbreviations\":[");
        for (int i = 0; i < pairs.length; i += 2) {
            sb.append(i == 0 ? "" : ",")
                    .append("{\"abbreviation\":\"")
                    .append(pairs[i])
                    .append("\",\"expansion\":\"")
                    .append(pairs[i + 1])
                    .append("\"}");
        }
        return sb.append("]}").toString();
    }

    // --- dictionary ------------------------------------------------------------------------------------

    @Test
    void wordsAddedOnBothSidesAreAllKept() {
        SyncMerge.FileResult r = dictionary("alpha\n", "alpha\nmine\n", "alpha\ntheirs\n");
        assertEquals("alpha\nmine\ntheirs\n", r.text());
        assertEquals(List.of("theirs"), r.received());
        assertEquals(List.of("mine"), r.sent());
        assertTrue(r.conflicts().isEmpty());
    }

    @Test
    void aWordRemovedOnOneSideIsRemovedAndOneAddedOnTheOtherStays() {
        SyncMerge.FileResult r = dictionary("alpha\nbeta\n", "alpha\nbeta\ngamma\n", "alpha\n");
        assertEquals("alpha\ngamma\n", r.text());
        assertEquals(List.of("beta"), r.received());
    }

    @Test
    void theDictionaryIsTheSameBytesWhateverTheOperatingSystemWrote() {
        // Windows line ends, a byte-order mark, another order, a typographic apostrophe, upper case.
        SyncMerge.FileResult r = dictionary("alpha\nbeta\n", "﻿Beta\r\nalpha\r\n", "alpha\nbeta\n");
        assertEquals("alpha\nbeta\n", r.text());
        assertTrue(r.sent().isEmpty(), "the same words: nothing to send");
        assertEquals("don't\n", SyncMerge.normalize(SyncCategory.DICTIONARY, "Don’t\r\n"));
    }

    @Test
    void aFirstSyncKeepsBothSides() {
        SyncMerge.FileResult r = dictionary(null, "mine\n", "theirs\n");
        assertEquals("mine\ntheirs\n", r.text());
        assertEquals(2, r.resultEntries());
    }

    @Test
    void anAbsentFileHereTakesTheRepositorysAndTheReverse() {
        assertEquals("theirs\n", dictionary(null, null, "theirs\n").text());
        assertEquals("mine\n", dictionary(null, "mine\n", null).text());
        assertNull(dictionary(null, null, null).text());
    }

    // --- abbreviations ---------------------------------------------------------------------------------

    @Test
    void abbreviationsMergeEntryByEntry() {
        SyncMerge.FileResult r = abbrevs(
                abbrevFile("btw", "by the way"),
                abbrevFile("btw", "by the way", "afaik", "as far as I know"),
                abbrevFile("btw", "by the way", "imo", "in my opinion"));
        assertTrue(r.text().contains("afaik")
                && r.text().contains("imo")
                && r.text().contains("btw"));
        assertEquals(List.of("imo"), r.received());
        assertEquals(List.of("afaik"), r.sent());
        assertEquals(3, r.resultEntries());
    }

    @Test
    void anAbbreviationChangedOnBothSidesKeepsThisMachinesAndIsReported() {
        SyncMerge.FileResult r = abbrevs(
                abbrevFile("btw", "by the way", "x", "old"),
                abbrevFile("btw", "BTW mine", "x", "old"),
                abbrevFile("btw", "BTW theirs", "x", "new"));
        assertTrue(r.text().contains("BTW mine"));
        assertFalse(r.text().contains("BTW theirs"));
        assertTrue(r.text().contains("\"new\""), "the other entry they changed is still taken");
        assertEquals(List.of("btw"), r.conflicts());
        assertEquals(List.of("x"), r.received());
    }

    @Test
    void anAbbreviationRemovedHereStaysRemoved() {
        SyncMerge.FileResult r =
                abbrevs(abbrevFile("a", "1", "b", "2"), abbrevFile("a", "1"), abbrevFile("a", "1", "b", "2", "c", "3"));
        assertFalse(r.text().contains("\"b\""));
        assertTrue(r.text().contains("\"c\""));
    }

    @Test
    void aStoreWrittenByANewerEditoraIsSkipped() {
        String newer = "{\"schemaVersion\":99,\"abbreviations\":[]}";
        assertEquals(
                SyncMerge.Skip.REMOTE_NEWER,
                abbrevs(null, abbrevFile("a", "1"), newer).skip());
    }

    @Test
    void anUnreadableFileOnEitherSideIsSkipped() {
        assertEquals(
                SyncMerge.Skip.REMOTE_UNREADABLE,
                abbrevs(null, abbrevFile("a", "1"), "{ broken").skip());
        assertEquals(
                SyncMerge.Skip.LOCAL_UNREADABLE,
                abbrevs(null, "{ broken", abbrevFile("a", "1")).skip());
        assertEquals(
                SyncMerge.Skip.LOCAL_UNREADABLE, snippets(null, "[1, 2]", "{}").skip());
    }

    @Test
    void theAbbreviationStoreIsCanonicalInTheRepository() {
        String compact = abbrevFile("a", "1");
        String text = abbrevs(null, compact, null).text();
        assertFalse(text.contains("\r"));
        assertTrue(text.endsWith("}\n"));
        assertEquals(text, SyncMerge.normalize(SyncCategory.ABBREVIATIONS, text.replace("\n", "\r\n")));
    }

    // --- snippets --------------------------------------------------------------------------------------

    private static final String SNIPPET_BASE = """
            {
              // my Java snippets
              "main": { "prefix": "main", "body": "public static void main" }
            }
            """;

    @Test
    void aSnippetFileOnlyOneSideChangedIsTakenWholeWithItsComments() {
        String theirs = SNIPPET_BASE.replace("my Java snippets", "shared Java snippets");
        SyncMerge.FileResult r = snippets(SNIPPET_BASE, SNIPPET_BASE.replace("\n", "\r\n"), theirs);
        assertEquals(theirs, r.text());
    }

    @Test
    void snippetsAddedOnBothSidesMergeIntoThisMachinesText() {
        String mine = SNIPPET_BASE.replace(
                "\"main\":", "\"sout\": { \"prefix\": \"sout\", \"body\": \"System.out.println\" },\n  \"main\":");
        String theirs = SNIPPET_BASE.replace(
                "\"main\":", "\"psf\": { \"prefix\": \"psf\", \"body\": [\"public static final\"] },\n  \"main\":");
        SyncMerge.FileResult r = snippets(SNIPPET_BASE, mine, theirs);
        assertTrue(r.text().contains("// my Java snippets"), r.text());
        assertTrue(r.text().contains("\"sout\"")
                && r.text().contains("\"psf\"")
                && r.text().contains("\"main\""));
        assertEquals(List.of("java: psf"), r.received());
        assertEquals(List.of("java: sout"), r.sent());
        assertEquals(3, r.resultEntries());
        // and the result is a valid snippet file with exactly those entries
        assertEquals(3, snippets(null, r.text(), null).mineEntries());
    }

    @Test
    void aSnippetRemovedThereAndAnotherEditedHere() {
        String base =
                "{\n  \"a\": {\"prefix\": \"a\", \"body\": \"1\"},\n  \"b\": {\"prefix\": \"b\", \"body\": \"2\"}\n}\n";
        String mine = base.replace("\"body\": \"1\"", "\"body\": \"one\"");
        String theirs = "{\n  \"a\": {\"prefix\": \"a\", \"body\": \"1\"}\n}\n";
        SyncMerge.FileResult r = snippets(base, mine, theirs);
        assertTrue(r.text().contains("\"one\""));
        assertFalse(r.text().contains("\"b\""));
        assertEquals(1, r.resultEntries());
    }

    @Test
    void aSnippetEditedOnBothSidesKeepsThisMachines() {
        String base = "{ \"a\": {\"prefix\": \"a\", \"body\": \"1\"} }";
        SyncMerge.FileResult r = snippets(base, base.replace("\"1\"", "\"mine\""), base.replace("\"1\"", "\"theirs\""));
        assertTrue(r.text().contains("\"mine\""));
        assertEquals(List.of("java: a"), r.conflicts());
    }

    @Test
    void aDisabledMarkTravelsLikeAnyEntry() {
        String base = "{}";
        String theirs = "{ \"main\": { \"disabled\": true } }";
        assertEquals(List.of("java: main"), snippets(base, base, theirs).received());
    }

    // --- templates -------------------------------------------------------------------------------------

    @Test
    void aTemplateIsOneEntry() {
        String base = "{\"name\":\"Class\",\"body\":\"class {}\"}";
        String mine = base.replace("class {}", "class Mine {}");
        String theirs = base.replace("class {}", "class Theirs {}");
        SyncMerge.FileResult r = SyncMerge.merge(SyncCategory.TEMPLATES, "templates/class.json", base, mine, theirs);
        assertEquals(mine, r.text());
        assertEquals(List.of("class"), r.conflicts());

        SyncMerge.FileResult removed =
                SyncMerge.merge(SyncCategory.TEMPLATES, "templates/class.json", base, base, null);
        assertNull(removed.text(), "deleted on another machine and untouched here");
        assertEquals(List.of("class"), removed.received());

        SyncMerge.FileResult kept = SyncMerge.merge(SyncCategory.TEMPLATES, "templates/class.json", base, mine, null);
        assertEquals(mine, kept.text(), "deleted there but edited here: the edit wins");
    }

    // --- categories ------------------------------------------------------------------------------------

    @Test
    void aCategoryOwnsOnlyItsOwnFiles() {
        assertEquals(SyncCategory.SNIPPETS, SyncCategory.of("snippets/java.json"));
        assertEquals(SyncCategory.TEMPLATES, SyncCategory.of("templates/My Class.JSON"));
        assertEquals(SyncCategory.DICTIONARY, SyncCategory.of("dictionary.txt"));
        assertNull(SyncCategory.of("snippets/java.json.tmp"));
        assertNull(SyncCategory.of("snippets/.hidden.json"));
        assertNull(SyncCategory.of("snippets/deep/java.json"));
        assertNull(SyncCategory.of("snippets/../settings.json"));
        assertNull(SyncCategory.of("settings.json"));
    }
}
