package com.editora.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WholeDocumentEditTest {

    private static String apply(String current, WholeDocumentEdit edit) {
        return current.substring(0, edit.start()) + edit.replacement() + current.substring(edit.end());
    }

    private static WholeDocumentEdit check(String current, String next) {
        WholeDocumentEdit edit = WholeDocumentEdit.between(current, next);
        assertEquals(next, apply(current, edit));
        return edit;
    }

    @Test
    void equalTextsNeedNoEdit() {
        assertNull(WholeDocumentEdit.between("", ""));
        assertNull(WholeDocumentEdit.between("same\ntext\n", "same\ntext\n"));
    }

    @Test
    void onlyTheDifferingMiddleIsReplaced() {
        WholeDocumentEdit edit = check("alpha beta gamma", "alpha BETA gamma");
        assertEquals(new WholeDocumentEdit(6, 10, "BETA"), edit);
    }

    @Test
    void insertionAndDeletionAreEmptyOnOneSide() {
        assertEquals(new WholeDocumentEdit(3, 3, "XY"), check("abcdef", "abcXYdef"));
        assertEquals(new WholeDocumentEdit(3, 5, ""), check("abcXYdef", "abcdef"));
    }

    @Test
    void aRepeatedCharacterIsNotCountedInBothPrefixAndSuffix() {
        // "aa" -> "aaa": prefix 2 leaves no room for a suffix; the edit must still produce the new text.
        assertEquals(new WholeDocumentEdit(2, 2, "a"), check("aa", "aaa"));
        assertEquals(new WholeDocumentEdit(2, 3, ""), check("aaa", "aa"));
    }

    @Test
    void appendAndPrependAndEmpty() {
        assertEquals(new WholeDocumentEdit(3, 3, "def"), check("abc", "abcdef"));
        assertEquals(new WholeDocumentEdit(0, 0, "abc"), check("def", "abcdef"));
        assertEquals(new WholeDocumentEdit(0, 3, ""), check("abc", ""));
        assertEquals(new WholeDocumentEdit(0, 0, "abc"), check("", "abc"));
    }

    @Test
    void aSurrogatePairIsNeverSplitAtThePrefix() {
        // U+1F600 and U+1F601 share their high surrogate: the edit must replace the whole pair.
        String current = "a😀b";
        String next = "a😁b";
        assertEquals(new WholeDocumentEdit(1, 3, "😁"), check(current, next));
    }

    @Test
    void aSurrogatePairIsNeverSplitAtTheSuffix() {
        // U+1F600 and U+1F400 share their low surrogate.
        String current = "a😀b";
        String next = "a🈀b";
        assertEquals(new WholeDocumentEdit(1, 3, "🈀"), check(current, next));
    }

    @Test
    void aCarriageReturnStaysWithItsLineFeed() {
        // The prefix "a\r" would leave the "\n" as the whole replacement: a lone line break of its own.
        assertEquals(new WholeDocumentEdit(1, 2, "\r\n"), check("a\rb", "a\r\nb"));
        // The suffix "\nb" would separate the pair on the other side.
        assertEquals(new WholeDocumentEdit(1, 2, "\r\n"), check("a\nb", "a\r\nb"));
    }

    @Test
    void aOneCharacterChangeInALargeDocumentIsAOneCharacterEdit() {
        String line = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcde\n";
        String current = line.repeat(16 * 1024); // 1 MiB
        int at = current.length() / 2;
        String next = current.substring(0, at) + 'X' + current.substring(at + 1);
        WholeDocumentEdit edit = check(current, next);
        assertEquals(1, edit.end() - edit.start());
        assertEquals("X", edit.replacement());
    }
}
