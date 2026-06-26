package com.editora.config;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileIdentityTest {

    @Test
    void ofComputesHashSizeAndCanonicalPath(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("a.txt");
        Files.writeString(f, "hello notes");
        FileIdentity id = FileIdentity.of(f);
        assertEquals(11, id.size());
        assertFalse(id.hash().isBlank(), "small file is hashed");
        assertFalse(id.canonicalPath().isBlank());
        assertEquals(id.hash(), FileIdentity.sha256(f), "hash is deterministic");
    }

    @Test
    void matchPriorityCanonicalThenHashThenSimilar() {
        FileIdentity a = new FileIdentity("/p/config.yml", "/p/config.yml", 100, 1, "HASH1");
        // Same canonical path wins regardless of other fields.
        FileIdentity sameCanon = new FileIdentity("/other", "/p/config.yml", 999, 2, "OTHER");
        assertEquals(FileIdentity.Match.CANONICAL_PATH, FileIdentity.match(a, sameCanon));

        // Different path, same content hash → moved/renamed file.
        FileIdentity moved = new FileIdentity("/q/renamed.yml", "/q/renamed.yml", 100, 5, "HASH1");
        assertEquals(FileIdentity.Match.CONTENT_HASH, FileIdentity.match(a, moved));

        // No path/hash match but same name + size → weak "similar path".
        FileIdentity similar = new FileIdentity("/z/config.yml", "/z/config.yml", 100, 9, "");
        assertEquals(FileIdentity.Match.SIMILAR_PATH, FileIdentity.match(a, similar));

        // Nothing in common.
        FileIdentity none = new FileIdentity("/z/other.yml", "/z/other.yml", 7, 9, "");
        assertEquals(FileIdentity.Match.NONE, FileIdentity.match(a, none));
    }

    @Test
    void emptyHashDoesNotMatchByContent() {
        FileIdentity a = new FileIdentity("/a", "/a", 5, 1, "");
        FileIdentity b = new FileIdentity("/b", "/b", 9, 1, "");
        assertEquals(
                FileIdentity.Match.NONE,
                FileIdentity.match(a, b),
                "two empty hashes must not be treated as a content match");
    }

    @Test
    void ofNullIsEmptyAndEmptyFileIsNotHashed(@TempDir Path dir) throws Exception {
        FileIdentity none = FileIdentity.of(null);
        assertEquals("", none.path());
        assertEquals("", none.canonicalPath());
        assertEquals("", none.hash());
        assertEquals(0, none.size());

        Path empty = dir.resolve("empty.txt");
        Files.createFile(empty);
        FileIdentity id = FileIdentity.of(empty);
        assertEquals(0, id.size());
        assertTrue(id.hash().isBlank(), "size-0 files are not hashed");
    }

    @Test
    void matchWithANullIdentityIsNone() {
        FileIdentity a = new FileIdentity("/a", "/a", 1, 1, "h");
        assertEquals(FileIdentity.Match.NONE, FileIdentity.match(a, null));
        assertEquals(FileIdentity.Match.NONE, FileIdentity.match(null, a));
    }

    @Test
    void largeFileIsNotHashed(@TempDir Path dir) throws Exception {
        Path big = dir.resolve("big.bin");
        Files.write(big, new byte[(int) FileIdentity.MAX_HASH_BYTES + 1]);
        FileIdentity id = FileIdentity.of(big);
        assertTrue(id.hash().isBlank(), "files over the cap are not hashed");
        assertEquals(FileIdentity.MAX_HASH_BYTES + 1, id.size());
    }
}
