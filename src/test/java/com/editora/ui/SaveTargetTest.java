package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SaveTargetTest {

    @Test
    void existsAndNotExistsAreNotComplements() {
        assertEquals(SaveTarget.PRESENT, SaveTarget.of(true, false));
        assertEquals(SaveTarget.ABSENT, SaveTarget.of(false, true));
        assertEquals(SaveTarget.INDETERMINATE, SaveTarget.of(false, false));
        assertEquals(SaveTarget.PRESENT, SaveTarget.of(true, true), "a positive sighting wins a racing answer");
    }

    @Test
    void aRealFileAndARealGapClassifyAsExpected(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("here.txt"), "x");
        Path gap = dir.resolve("not-here.txt");
        assertEquals(SaveTarget.PRESENT, SaveTarget.of(Files.exists(file), Files.notExists(file)));
        assertEquals(SaveTarget.ABSENT, SaveTarget.of(Files.exists(gap), Files.notExists(gap)));
    }

    @Test
    void theRetryBoundLeavesRoomForTheConflictPrompt() {
        // Attempt 0 is the ordinary write; attempt 1 is the re-check after a detected race, where the user is
        // asked. The bound must allow that, and must be finite.
        assertTrue(SaveTarget.MAX_ATTEMPTS >= 2);
        assertTrue(SaveTarget.MAX_ATTEMPTS <= 10);
    }
}
