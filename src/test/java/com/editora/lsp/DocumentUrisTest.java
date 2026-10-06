package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DocumentUris} must hand back exactly the string {@code Path.toUri().toString()} produces — servers
 * are told a document's URI once and every later message has to repeat it byte for byte — and must stop
 * recomputing it, which is the point: {@code toUri()} stats the file.
 */
class DocumentUrisTest {

    /** Names that exercise the percent-encoding: spaces, non-ASCII, and characters with a meaning in a URI. */
    private static final List<String> NAMES = List.of(
            "Plain.java",
            "with space.java",
            "año/Ñandú.java",
            "日本語/ファイル.txt",
            "emoji-😀.md",
            "hash#tag.py",
            "percent%20literal.txt",
            "100%.txt",
            "question?mark.txt",
            "semi;colon&amp.txt",
            "plus+equals=.txt",
            "[brackets]{braces}.txt",
            "a'b\"c.txt",
            "deep/er/and/deeper/file.rs");

    private static List<Path> corpus(Path dir) {
        List<Path> paths = new ArrayList<>();
        for (String name : NAMES) {
            try {
                paths.add(dir.resolve(name));
            } catch (java.nio.file.InvalidPathException e) {
                // a character this platform's file names cannot hold (Windows: ? and ")
            }
        }
        return paths;
    }

    @Test
    void theRememberedStringIsExactlyWhatToUriProduces(@TempDir Path dir) throws Exception {
        for (Path path : corpus(dir)) {
            String expected = path.toUri().toString();
            assertEquals(expected, DocumentUris.of(path), "first use: " + path);
            assertEquals(expected, DocumentUris.of(path), "remembered: " + path);
        }
        // The same for files that exist: toUri() consults the file system, and must still agree.
        for (Path path : corpus(dir)) {
            try {
                Files.createDirectories(path.getParent());
                Files.writeString(path, "x");
            } catch (java.io.IOException | java.nio.file.InvalidPathException e) {
                continue; // not a name this file system accepts
            }
            DocumentUris.forget(path);
            assertEquals(path.toUri().toString(), DocumentUris.of(path), "existing file: " + path);
        }
    }

    @Test
    void aRelativePathIsRememberedAsItsAbsoluteUri() {
        Path relative = Path.of("src", "Main Class.java");
        assertEquals(relative.toUri().toString(), DocumentUris.of(relative));
        assertEquals(relative.toAbsolutePath().toUri().toString(), DocumentUris.of(relative));
    }

    /** The whole point: the second question is answered without building a URI again. */
    @Test
    void aSecondLookupReturnsTheSameStringWithoutRecomputing(@TempDir Path dir) {
        Path file = dir.resolve("Once.java");
        String first = DocumentUris.of(file);

        assertSame(first, DocumentUris.of(file), "toUri() allocates a new string on every call");
        assertSame(first, DocumentUris.of(dir.resolve("Once.java")), "an equal path, not only the same object");
    }

    @Test
    void forgettingAPathRecomputesIt(@TempDir Path dir) {
        Path file = dir.resolve("Closed.java");
        String first = DocumentUris.of(file);
        DocumentUris.forget(file);

        String again = DocumentUris.of(file);
        assertEquals(first, again);
        assertTrue(first != again, "a forgotten path is computed afresh");
    }

    /** A directory's URI ends in a slash only while it is a directory, so it is the one form never kept. */
    @Test
    void aDirectoryUriIsNotRemembered(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("thing");
        Files.createDirectory(path);
        assertTrue(DocumentUris.of(path).endsWith("/"), "precondition: toUri() marks a directory");

        Files.delete(path);
        Files.writeString(path, "now a file");

        assertEquals(path.toUri().toString(), DocumentUris.of(path));
        assertTrue(!DocumentUris.of(path).endsWith("/"), "the earlier directory form must not stick");
    }

    @Test
    void theMapIsBounded(@TempDir Path dir) {
        for (int i = 0; i < DocumentUris.MAX_ENTRIES * 2 + 10; i++) {
            DocumentUris.of(dir.resolve("f" + i + ".txt"));
        }
        assertTrue(DocumentUris.size() <= DocumentUris.MAX_ENTRIES, "entries: " + DocumentUris.size());
    }
}
