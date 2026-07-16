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
}
