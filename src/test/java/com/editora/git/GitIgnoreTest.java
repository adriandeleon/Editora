package com.editora.git;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GitIgnoreTest {

    @Test
    void appendsEntryEnsuringTrailingNewline() {
        assertEquals("a.log\n", GitIgnore.withEntry("", "a.log"));
        assertEquals("a.log\n", GitIgnore.withEntry(null, "a.log"));
        assertEquals("*.class\na.log\n", GitIgnore.withEntry("*.class", "a.log")); // no trailing newline → added
        assertEquals("*.class\na.log\n", GitIgnore.withEntry("*.class\n", "a.log"));
    }

    @Test
    void returnsNullWhenAlreadyPresent() {
        assertNull(GitIgnore.withEntry("*.class\na.log\n", "a.log"));
        assertNull(GitIgnore.withEntry("  a.log  \n", "a.log")); // matched ignoring surrounding whitespace
        assertNull(GitIgnore.withEntry("x", "  ")); // blank entry → nothing to add
    }

    @Test
    void normalizesBackslashesAndDirectoryTrailingSlash() {
        assertEquals("build/x.o\n", GitIgnore.withEntry("", "build\\x.o"));
        assertEquals("target/", GitIgnore.entryFor("target", true));
        assertEquals("src/App.java", GitIgnore.entryFor("src/App.java", false));
        assertEquals("dir/", GitIgnore.entryFor("dir/", true)); // already ends with slash → unchanged
    }
}
