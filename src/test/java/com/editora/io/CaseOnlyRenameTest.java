package com.editora.io;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A case-only rename is exempt from the "target exists" refusal only when the target is the very same
 * directory entry — never for a second file, and never for a hard link that merely shares the content.
 */
class CaseOnlyRenameTest {

    @TempDir
    Path dir;

    @Test
    void aNameThatDoesNotExistIsNotAnAlias() throws IOException {
        Path old = Files.writeString(dir.resolve("readme.md"), "x");
        Assumptions.assumeFalse(Files.exists(dir.resolve("README.md")), "case-sensitive volume");

        assertFalse(CaseOnlyRename.isAlias(old, dir.resolve("README.md")));
        assertFalse(CaseOnlyRename.isAlias(old, old));
        assertFalse(CaseOnlyRename.isAlias(old, dir.resolve("other.md")));
    }

    @Test
    void aSecondFileThatDiffersOnlyInCaseIsNotAnAlias() throws IOException {
        Path old = Files.writeString(dir.resolve("readme.md"), "lower");
        Path upper = dir.resolve("README.md");
        Assumptions.assumeFalse(Files.exists(upper), "case-sensitive volume");
        Files.writeString(upper, "upper");

        assertFalse(CaseOnlyRename.isAlias(old, upper), "renaming over it would destroy a different file");
    }

    @Test
    void aHardLinkThatDiffersOnlyInCaseIsNotAnAlias() throws IOException {
        Path old = Files.writeString(dir.resolve("readme.md"), "shared");
        Path upper = dir.resolve("README.md");
        Assumptions.assumeFalse(Files.exists(upper), "case-sensitive volume");
        try {
            Files.createLink(upper, old);
        } catch (UnsupportedOperationException | IOException noHardLinks) {
            Assumptions.abort("hard links are unavailable here");
        }

        assertTrue(Files.isSameFile(old, upper));
        assertFalse(CaseOnlyRename.isAlias(old, upper), "two entries: the rename would silently do nothing");
    }

    @Test
    void onAVolumeThatIgnoresCaseTheOtherSpellingIsAnAliasAndCanBeRenamed() throws IOException {
        Path old = Files.writeString(dir.resolve("readme.md"), "x");
        Path upper = dir.resolve("README.md");
        Assumptions.assumeTrue(Files.exists(upper), "needs a case-insensitive volume (macOS, Windows)");

        assertTrue(CaseOnlyRename.isAlias(old, upper));
        CaseOnlyRename.move(old, upper);

        assertEquals(
                "README.md",
                upper.toRealPath(LinkOption.NOFOLLOW_LINKS).getFileName().toString());
    }

    @Test
    void theTwoStepMoveRenamesAndLeavesNothingBehind() throws IOException {
        Path old = Files.writeString(dir.resolve("a.txt"), "content");
        Path target = dir.resolve("b.txt");

        CaseOnlyRename.move(old, target);

        assertEquals("content", Files.readString(target));
        try (var entries = Files.list(dir)) {
            assertEquals(1, entries.count());
        }
    }

    @Test
    void aFailedSecondStepPutsTheFileBackUnderItsOldName() throws IOException {
        Path old = Files.writeString(dir.resolve("a.txt"), "content");
        Path occupied = Files.writeString(dir.resolve("b.txt"), "other");

        assertThrows(FileAlreadyExistsException.class, () -> CaseOnlyRename.move(old, occupied));

        assertEquals("content", Files.readString(old));
        assertEquals("other", Files.readString(occupied));
        try (var entries = Files.list(dir)) {
            assertEquals(2, entries.count());
        }
    }
}
