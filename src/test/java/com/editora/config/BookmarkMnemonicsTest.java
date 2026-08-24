package com.editora.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookmarkMnemonicsTest {

    private static Map<String, List<Bookmark>> map() {
        Map<String, List<Bookmark>> m = new LinkedHashMap<>();
        m.put("A.java", List.of(new Bookmark(10, "", "a"), new Bookmark(20, "", "b")));
        m.put("B.java", List.of(new Bookmark(5, "", "c")));
        return m;
    }

    @Test
    void assignsAMnemonicToTheTargetBookmark() {
        var out = BookmarkMnemonics.assign(map(), "A.java", 20, "3");
        assertEquals("3", out.get("A.java").get(1).mnemonic());
        assertEquals("", out.get("A.java").get(0).mnemonic());
    }

    @Test
    void aMnemonicIsUniqueAcrossTheProjectSoReassigningMovesIt() {
        // The chord is a NAME for a location; one that resolves to two places is a menu, not a shortcut.
        var first = BookmarkMnemonics.assign(map(), "A.java", 10, "3");
        var second = BookmarkMnemonics.assign(first, "B.java", 5, "3");
        assertEquals("", second.get("A.java").get(0).mnemonic(), "the previous holder must give it up");
        assertEquals("3", second.get("B.java").get(0).mnemonic());
        assertEquals(List.of("3"), BookmarkMnemonics.assigned(second));
    }

    @Test
    void anEmptyMnemonicClearsIt() {
        var set = BookmarkMnemonics.assign(map(), "A.java", 10, "3");
        var cleared = BookmarkMnemonics.assign(set, "A.java", 10, "");
        assertEquals("", cleared.get("A.java").get(0).mnemonic());
        assertEquals(List.of(), BookmarkMnemonics.assigned(cleared));
    }

    @Test
    void anAbsentTargetLeavesTheMapAlone() {
        // Labelling must not invent a bookmark — creating one is the caller's decision.
        Map<String, List<Bookmark>> in = map();
        assertSame(in, BookmarkMnemonics.assign(in, "A.java", 999, "3"));
        assertSame(in, BookmarkMnemonics.assign(in, "Nope.java", 1, "3"));
    }

    @Test
    void findLocatesTheHolder() {
        var out = BookmarkMnemonics.assign(map(), "B.java", 5, "7");
        var found = BookmarkMnemonics.find(out, "7");
        assertEquals("B.java", found.file());
        assertEquals(5, found.bookmark().line());
    }

    @Test
    void findReturnsNullForAnUnassignedOrInvalidMnemonic() {
        assertNull(BookmarkMnemonics.find(map(), "7"));
        assertNull(BookmarkMnemonics.find(map(), ""));
        assertNull(BookmarkMnemonics.find(map(), "!!"));
        assertNull(BookmarkMnemonics.find(map(), null));
    }

    @Test
    void normalizeAcceptsOneAlphanumericAndFoldsCase() {
        assertEquals("3", BookmarkMnemonics.normalize("3"));
        assertEquals("a", BookmarkMnemonics.normalize("A"));
        assertEquals("a", BookmarkMnemonics.normalize(" a "));
        assertEquals("", BookmarkMnemonics.normalize("ab"));
        assertEquals("", BookmarkMnemonics.normalize("!"));
        assertEquals("", BookmarkMnemonics.normalize(""));
        assertEquals("", BookmarkMnemonics.normalize(null));
    }

    @Test
    void aMnemonicIsExactlyOneCharacterOrNothing() {
        assertEquals("", new Bookmark(1, "", "", "abc").mnemonic());
        assertEquals("", new Bookmark(1, "", "", null).mnemonic());
        assertEquals("3", new Bookmark(1, "", "", "3").mnemonic());
    }

    @Test
    void mnemonicSurvivesALineShiftAndANoteChange() {
        Bookmark b = new Bookmark(1, "n", "t", "5");
        assertEquals("5", b.withLine(9).mnemonic());
        assertEquals("5", b.withNote("other").mnemonic());
    }

    @Test
    void theLabelIsBracketedAndUppercased() {
        assertEquals("[3]", BookmarkMnemonics.label(new Bookmark(1, "", "", "3")));
        assertEquals("[A]", BookmarkMnemonics.label(new Bookmark(1, "", "", "a")));
        assertEquals("", BookmarkMnemonics.label(new Bookmark(1, "", "", "")));
        assertEquals("", BookmarkMnemonics.label(null));
    }

    @Test
    void assignedListsEveryMnemonicSorted() {
        var m = BookmarkMnemonics.assign(BookmarkMnemonics.assign(map(), "A.java", 10, "9"), "B.java", 5, "2");
        assertEquals(List.of("2", "9"), BookmarkMnemonics.assigned(m));
        assertTrue(BookmarkMnemonics.assigned(map()).isEmpty());
    }
}
