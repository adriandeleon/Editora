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
    void entriesAreAnchoredSoOnlyTheChosenPathIsIgnored() {
        // Unanchored, "notes.txt" also ignored docs/notes.txt and every other same-named file.
        assertEquals("/notes.txt", GitIgnore.entryFor("notes.txt", false));
        assertEquals("/target/", GitIgnore.entryFor("target", true));
        assertEquals("/src/App.java", GitIgnore.entryFor("src/App.java", false));
        assertEquals("/dir/", GitIgnore.entryFor("dir/", true)); // already ends with slash → one slash
        assertEquals("/build/x.o", GitIgnore.entryFor("build\\x.o", false)); // Windows separators
        assertEquals("", GitIgnore.entryFor("", false));
    }

    @Test
    void namesThatLookLikePatternSyntaxAreMatchedLiterally() {
        // The leading slash already stops "#"/"!" being read as a comment / a negation.
        assertEquals("/#notes", GitIgnore.entryFor("#notes", false));
        assertEquals("/!important", GitIgnore.entryFor("!important", false));
        assertEquals("/a\\*b\\?c", GitIgnore.entryFor("a*b?c", false));
        assertEquals("/data\\[1\\].csv", GitIgnore.entryFor("data[1].csv", false));
        assertEquals("/trailing\\ ", GitIgnore.entryFor("trailing ", false));
        assertEquals("/in side", GitIgnore.entryFor("in side", false)); // an inner space needs no escape
    }

    @Test
    void anEscapedEntryIsWrittenVerbatim() {
        // withEntry used to turn every backslash into "/" and strip the entry, undoing the escapes.
        assertEquals("/a\\*b\n", GitIgnore.withEntry("", GitIgnore.entryFor("a*b", false)));
        assertEquals("/trailing\\ \n", GitIgnore.withEntry("", GitIgnore.entryFor("trailing ", false)));
        assertNull(GitIgnore.withEntry("/a\\*b\n", "/a\\*b"));
    }
}
