package com.editora.ui;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A12-18: a bare name typed into the file finder belongs to the directory being listed. */
class FileFinderTypedPathTest {

    @Test
    void aBareNameResolvesAgainstTheListedDirectory() {
        Path dir = Path.of("proj", "src").toAbsolutePath();
        assertEquals(dir.resolve("notes.txt"), FileFinder.typedPath("notes.txt", dir));
    }

    @Test
    void anAbsolutePathIsTakenAsTyped() {
        Path dir = Path.of("proj", "src").toAbsolutePath();
        Path other = Path.of("elsewhere", "a.txt").toAbsolutePath();
        assertEquals(other, FileFinder.typedPath(other.toString(), dir));
    }

    @Test
    void blankIsNothingAndNoDirectoryLeavesTheTextAlone() {
        assertNull(FileFinder.typedPath("  ", Path.of("x")));
        assertEquals(Path.of("notes.txt"), FileFinder.typedPath("notes.txt", null));
    }
}
