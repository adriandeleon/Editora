package com.editora.config;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** add/remove on the personal spell dictionary persist to dictionary.txt (backing the Settings editor). */
class UserDictionaryTest {

    @Test
    void addAndRemovePersistToDisk(@TempDir Path dir) throws Exception {
        ConfigManager c = new ConfigManager(dir);

        c.addUserWord("Foo"); // lower-cased
        assertTrue(c.getUserDictionary().contains("foo"));
        assertTrue(Files.readString(c.getUserDictionaryFile()).contains("foo"));

        c.addUserWord("bar");
        c.removeUserWord("foo");
        assertFalse(c.getUserDictionary().contains("foo"));
        String onDisk = Files.readString(c.getUserDictionaryFile());
        assertFalse(onDisk.contains("foo"), "removed word must be gone from the file");
        assertTrue(onDisk.contains("bar"), "remaining word must stay in the file");
    }

    @Test
    void removingAnAbsentWordIsANoOp(@TempDir Path dir) {
        ConfigManager c = new ConfigManager(dir);
        c.removeUserWord("nothere"); // must not throw
        assertTrue(c.getUserDictionary().isEmpty());
    }

    // --- load robustness: dictionary.txt is a plain text file the user can also hand-edit -----------

    /** Loads dictionary.txt fresh (as a new launch would). */
    private static java.util.Set<String> reload(Path dir) {
        ConfigManager fresh = new ConfigManager(dir);
        fresh.load();
        return java.util.Set.copyOf(fresh.getUserDictionary());
    }

    @Test
    void aMalformedByteDoesNotDiscardTheWholeDictionary(@TempDir Path dir) throws Exception {
        // A lone 0xE9 (latin-1 "é") is invalid UTF-8. A strict decode (readAllLines) threw on it and left the
        // set EMPTY — losing every user word; a later remove would then rewrite the file from that empty set.
        byte[] bad = {'a', 'l', 'p', 'h', 'a', '\n', (byte) 0xE9, '\n', 'g', 'a', 'm', 'm', 'a', '\n'};
        Files.write(dir.resolve("dictionary.txt"), bad);

        java.util.Set<String> words = reload(dir);
        assertTrue(words.contains("alpha"), "a bad byte must not discard the surrounding words");
        assertTrue(words.contains("gamma"));
    }

    @Test
    void aByteOrderMarkDoesNotBreakTheFirstWord(@TempDir Path dir) throws Exception {
        // String.strip() does NOT remove U+FEFF (it's Cf, not whitespace), so the first word stayed
        // permanently unmatchable.
        Files.write(
                dir.resolve("dictionary.txt"),
                "﻿editora\nhunspell\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        java.util.Set<String> words = reload(dir);
        assertTrue(words.contains("editora"), "a BOM'd first word is still usable");
        assertFalse(words.contains("﻿editora"));
    }

    @Test
    void blankAndDuplicateLinesAreTolerated(@TempDir Path dir) throws Exception {
        Files.write(
                dir.resolve("dictionary.txt"),
                "alpha\n\n  \nALPHA\r\nbeta\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(java.util.Set.of("alpha", "beta"), reload(dir));
    }

    @Test
    void addingAWordToAFileWithNoFinalNewlineStartsANewLine(@TempDir Path dir) throws Exception {
        // Hand-edited or synced: the last line is not terminated.
        Files.writeString(dir.resolve("dictionary.txt"), "alpha\nbeta");
        ConfigManager c = new ConfigManager(dir);
        c.load();

        c.addUserWord("gamma");

        assertEquals(java.util.Set.of("alpha", "beta", "gamma"), reload(dir), "not alpha + betagamma");
        c.addUserWord("delta");
        assertEquals(
                java.util.List.of("alpha", "beta", "gamma", "delta"),
                Files.readAllLines(dir.resolve("dictionary.txt")),
                "and a terminated file gets no blank line");
    }

    // --- the stored form, and re-reading a hand-edited file -------------------------------------------

    @Test
    void aTypographicApostropheIsStoredAsTheAsciiOneTheCheckerLooksUp(@TempDir Path dir) throws Exception {
        ConfigManager c = new ConfigManager(dir);
        c.addUserWord("zzq’abc");
        assertTrue(
                c.getUserDictionary().contains("zzq'abc"), c.getUserDictionary().toString());
        assertTrue(Files.readString(c.getUserDictionaryFile()).contains("zzq'abc"));
        c.addUserWord("ZZQ'ABC"); // the same word: not written twice
        assertEquals(1, Files.readString(c.getUserDictionaryFile()).lines().count());
        // A file written by an older build (or by hand) with the typographic form is read in the same way.
        Files.writeString(dir.resolve("dictionary.txt"), "l’été\n");
        assertTrue(reload(dir).contains("l'été"));
        c.removeUserWord("zzq‘abc");
        assertFalse(c.getUserDictionary().contains("zzq'abc"));
    }

    @Test
    void reloadPicksUpLinesAddedAndRemovedByHand(@TempDir Path dir) throws Exception {
        ConfigManager c = new ConfigManager(dir);
        c.addUserWord("alpha");
        c.addUserWord("beta");
        java.util.Set<String> shared = c.getUserDictionary(); // the very set every buffer's checker holds
        assertFalse(c.reloadUserDictionary(), "nothing changed on disk");

        Files.writeString(c.getUserDictionaryFile(), "alpha\nParagraf\n"); // beta removed, a word added
        assertTrue(c.reloadUserDictionary());
        assertEquals(java.util.Set.of("alpha", "paragraf"), shared, "updated in place");
        assertTrue(shared == c.getUserDictionary());

        // A later add still appends to what is on disk rather than rewriting from a stale base.
        c.addUserWord("gamma");
        assertEquals(java.util.Set.of("alpha", "paragraf", "gamma"), reload(dir));
        c.removeUserWord("alpha");
        assertEquals(java.util.Set.of("paragraf", "gamma"), reload(dir));

        Files.delete(c.getUserDictionaryFile());
        assertTrue(c.reloadUserDictionary());
        assertTrue(shared.isEmpty(), "a deleted file is an empty dictionary");
    }
}
