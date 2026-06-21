package com.editora.config;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
}
