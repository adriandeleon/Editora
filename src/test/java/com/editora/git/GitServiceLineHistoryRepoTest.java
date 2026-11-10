package com.editora.git;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A declaration's history for the Map's History card (gap 12), read from real repositories with {@code git log -L}. */
class GitServiceLineHistoryRepoTest {

    @TempDir
    Path temp;

    private final GitService service = new GitService();

    @AfterEach
    void stop() {
        service.shutdown();
    }

    /** A.java: one() by Ada, two() changed by Bob then Dee, three() by Cy. */
    private Path history(Path repo) throws Exception {
        Files.createDirectories(repo);
        GitServiceRecentChangesTest.git(repo, null, "init", "-q", "-b", "main");
        Path file = repo.resolve("A.java");
        List<String> lines = new ArrayList<>(List.of(
                "class A {",
                "  int one() { return 1; }",
                "",
                "  int two() {",
                "    return 2;",
                "  }",
                "",
                "  int three() {",
                "    return 3;",
                "  }",
                "}"));
        Files.writeString(file, String.join("\n", lines) + "\n");
        GitServiceRecentChangesTest.commit(repo, 9, "Ada Lovelace", "start");
        lines.set(4, "    return 2 + 0;");
        Files.writeString(file, String.join("\n", lines) + "\n");
        GitServiceRecentChangesTest.commit(repo, 8, "Bob", "Bob edits two");
        lines.set(8, "    return 3 + 0;");
        Files.writeString(file, String.join("\n", lines) + "\n");
        GitServiceRecentChangesTest.commit(repo, 7, "Cy", "Cy edits three");
        lines.set(4, "    return 2 + 1;");
        Files.writeString(file, String.join("\n", lines) + "\n");
        GitServiceRecentChangesTest.commit(repo, 6, "Dee", "Dee edits two again");
        return file;
    }

    @Test
    void aDeclarationsCommitsComeNewestFirstWithTheirPartOfTheDiff() throws Exception {
        Path file = history(temp.resolve("repo"));
        Path dir = file.getParent();

        // two() is lines 3..5 (0-based).
        LineHistory.Result two = service.readLineHistory(dir, file, 3, 5, 10);
        assertEquals(LineHistory.Status.OK, two.status());
        assertEquals(
                List.of("Dee edits two again", "Bob edits two", "start"),
                two.commits().stream().map(LineHistory.Commit::subject).toList(),
                "Cy's commit changed three(), not two()");
        assertEquals(List.of("Dee", "Bob", "Ada Lovelace"), two.authors());
        assertFalse(two.truncated());
        assertEquals(dir.toRealPath(), two.root().toRealPath());
        LineHistory.Commit dee = two.commits().get(0);
        assertEquals("A.java", dee.path());
        assertNull(dee.previousPath());
        assertTrue(dee.patch().contains("-    return 2 + 0;"), dee.patch().toString());
        assertTrue(dee.patch().contains("+    return 2 + 1;"), dee.patch().toString());
        assertEquals(1, dee.added());
        assertEquals(1, dee.removed());
        LineHistory.Commit start = two.commits().get(2);
        assertEquals(0, start.removed(), "the first commit brought the lines in");
        assertEquals(3, start.added());

        LineHistory.Result newest = service.readLineHistory(dir, file, 3, 5, 1);
        assertEquals(1, newest.commits().size());
        assertTrue(newest.truncated(), "there were more than one");
    }

    @Test
    void uncommittedAndUntrackedLinesHaveNoHistory() throws Exception {
        Path file = history(temp.resolve("repo"));
        Path dir = file.getParent();
        List<String> lines = new ArrayList<>(List.of(Files.readString(file).split("\n")));
        lines.addAll(1, List.of("  // new", "  // lines"));
        Files.writeString(file, String.join("\n", lines) + "\n");

        assertEquals(
                LineHistory.Status.NEW_LINES,
                service.readLineHistory(dir, file, 1, 2, 10).status());
        LineHistory.Result moved = service.readLineHistory(dir, file, 5, 7, 10);
        assertEquals(3, moved.commits().size(), "two() moved down two lines: asked as HEAD numbers it");

        Path loose = dir.resolve("Loose.java");
        Files.writeString(loose, "class Loose {}\n");
        assertEquals(
                LineHistory.Status.UNAVAILABLE,
                service.readLineHistory(dir, loose, 0, 0, 10).status());

        assertEquals(
                LineHistory.Status.UNAVAILABLE,
                service.readLineHistory(dir, file, 40, 41, 10).status(),
                "past the end of HEAD's file: no history, not a failure");

        Path fresh = temp.resolve("fresh");
        Files.createDirectories(fresh);
        GitServiceRecentChangesTest.git(fresh, null, "init", "-q", "-b", "main");
        Path unborn = Files.writeString(fresh.resolve("U.java"), "class U {}\n");
        assertEquals(
                LineHistory.Status.UNAVAILABLE,
                service.readLineHistory(fresh, unborn, 0, 0, 10).status(),
                "no commit yet");

        Path outside = temp.resolve("plain");
        Files.createDirectories(outside);
        Path plain = outside.resolve("P.java");
        Files.writeString(plain, "class P {}\n");
        assertEquals(
                LineHistory.Status.UNAVAILABLE,
                service.readLineHistory(outside, plain, 0, 0, 10).status());
    }

    @Test
    void aMovedFilesOlderCommitsNameItsOldPath() throws Exception {
        Path file = history(temp.resolve("repo"));
        Path repo = file.getParent();
        GitServiceRecentChangesTest.git(repo, null, "mv", "A.java", "B.java");
        GitServiceRecentChangesTest.commit(repo, 5, "Eve", "rename");
        Path moved = repo.resolve("B.java");

        LineHistory.Result two = service.readLineHistory(repo, moved, 3, 5, 10);
        assertEquals(LineHistory.Status.OK, two.status());
        assertEquals(
                List.of("Dee edits two again", "Bob edits two", "start"),
                two.commits().stream().map(LineHistory.Commit::subject).toList(),
                "the move changed no line");
        assertEquals("A.java", two.commits().get(0).path(), "where the lines were in that commit");
    }
}
