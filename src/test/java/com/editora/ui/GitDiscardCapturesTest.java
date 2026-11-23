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

    @Test
    void severalSpecsMatchWhatSelectsMatches() {
        GitStatus status = status(
                new GitStatus.FileEntry("src/A.java", '.', 'M', null),
                new GitStatus.FileEntry("srcx/B.java", '.', 'M', null),
                new GitStatus.FileEntry("docs/guide/C.md", 'M', '.', null),
                new GitStatus.FileEntry("top.txt", '.', 'M', null));
        List<String> specs = List.of("src/", "docs/guide", "top.txt");
        List<Path> expected = new java.util.ArrayList<>();
        for (String spec : specs) {
            expected.add(ROOT.resolve(spec).normalize());
        }
        for (GitStatus.FileEntry e : status.files()) {
            if (specs.stream().anyMatch(spec -> GitCoordinator.selects(spec, e.path()))) {
                Path p = ROOT.resolve(e.path()).normalize();
                if (!expected.contains(p)) {
                    expected.add(p);
                }
            }
        }
        assertEquals(expected, GitDiscardCaptures.overwritten(ROOT, status, specs));
    }

    @Test
    void thousandsOfSelectedRowsAgainstThousandsOfStatusRowsIsLinear() {
        List<GitStatus.FileEntry> files = new java.util.ArrayList<>();
        List<String> specs = new java.util.ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            String path = "pkg" + (i % 50) + "/File" + i + ".java";
            files.add(new GitStatus.FileEntry(path, '.', 'M', null));
            specs.add(path);
        }
        GitStatus status = new GitStatus(true, "main", null, 0, 0, files);
        long start = System.nanoTime();
        List<Path> out = GitDiscardCaptures.overwritten(ROOT, status, specs);
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertEquals(20_000, out.size());
        org.junit.jupiter.api.Assertions.assertTrue(millis < 3_000, "20k x 20k took " + millis + " ms");
    }
}
