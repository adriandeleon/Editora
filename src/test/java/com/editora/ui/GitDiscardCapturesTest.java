package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import com.editora.git.GitStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** R3: which files a Rollback of tracked changes overwrites, so Local History copies them first. */
class GitDiscardCapturesTest {

    private static final Path ROOT = Path.of("/repo").toAbsolutePath();

    private static GitStatus status(GitStatus.FileEntry... files) {
        return new GitStatus(true, "main", null, 0, 0, List.of(files));
    }

    @Test
    void aFileSpecIsItself() {
        assertEquals(List.of(ROOT.resolve("a.txt")), GitDiscardCaptures.overwritten(ROOT, status(), List.of("a.txt")));
    }

    @Test
    void aFolderSpecIsEveryChangedTrackedFileUnderItButNotUntrackedOnes() {
        GitStatus status = status(
                new GitStatus.FileEntry("src/A.java", '.', 'M', null),
                new GitStatus.FileEntry("src/deep/B.java", 'M', '.', null),
                new GitStatus.FileEntry("src/New.java", '?', '?', null),
                new GitStatus.FileEntry("other/C.java", '.', 'M', null));

        assertEquals(
                List.of(ROOT.resolve("src"), ROOT.resolve("src/A.java"), ROOT.resolve("src/deep/B.java")),
                GitDiscardCaptures.overwritten(ROOT, status, List.of("src")),
                "the folder itself is listed too; it is passed over later as not a regular file");
    }

    @Test
    void repeatsAreDroppedAndAMissingStatusStillListsTheSpecs() {
        GitStatus status = status(new GitStatus.FileEntry("a.txt", '.', 'M', null));
        assertEquals(
                List.of(ROOT.resolve("a.txt")),
                GitDiscardCaptures.overwritten(ROOT, status, List.of("a.txt", "a.txt", ".")));
        assertEquals(List.of(ROOT.resolve("b.txt")), GitDiscardCaptures.overwritten(ROOT, null, List.of("b.txt", "")));
    }
}
