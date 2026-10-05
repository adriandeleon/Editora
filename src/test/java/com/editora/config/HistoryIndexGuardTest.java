package com.editora.config;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryIndexGuardTest {

    private static Path blob(Path blobs) throws Exception {
        Path shard = Files.createDirectories(blobs.resolve("ab"));
        return Files.writeString(shard.resolve("abcdef.txt.gz"), "x");
    }

    @Test
    void aFreshConfigDirHasNotLostItsIndex(@TempDir Path dir) throws Exception {
        Path index = dir.resolve("index.json");
        assertFalse(HistoryIndexGuard.lostIndex(index, dir.resolve("blobs")), "no index, no bodies: no history yet");
        Files.createDirectories(dir.resolve("blobs").resolve("ab"));
        Files.writeString(index, "");
        assertFalse(HistoryIndexGuard.lostIndex(index, dir.resolve("blobs")), "empty shard directories are not bodies");
    }

    @Test
    void anEmptyOrMissingIndexBesideStoredBodiesIsLost(@TempDir Path dir) throws Exception {
        Path index = dir.resolve("index.json");
        blob(dir.resolve("blobs"));
        assertTrue(HistoryIndexGuard.lostIndex(index, dir.resolve("blobs")), "missing");
        Files.writeString(index, "");
        assertTrue(HistoryIndexGuard.lostIndex(index, dir.resolve("blobs")), "zero-length");
        Files.writeString(index, "{}");
        assertFalse(HistoryIndexGuard.lostIndex(index, dir.resolve("blobs")), "content is the reader's to judge");
    }

    @Test
    void onlyBackupsOfTheIndexCount(@TempDir Path dir) throws Exception {
        Path index = dir.resolve("index.json");
        Files.writeString(index, "{}");
        Files.writeString(dir.resolve(".index.json-123.tmp"), "");
        Files.writeString(dir.resolve("index.json.tmp"), "");
        Files.createDirectories(dir.resolve("blobs"));
        assertFalse(HistoryIndexGuard.backupPresent(index));
        for (String name : new String[] {"index.json.v3.bak", "index.json.corrupt.bak", "index.json.corrupt.bak.2"}) {
            Path backup = Files.writeString(dir.resolve(name), "");
            assertTrue(HistoryIndexGuard.backupPresent(index), name);
            Files.delete(backup);
        }
        assertFalse(HistoryIndexGuard.backupPresent(dir.resolve("nowhere").resolve("index.json")));
    }
}
