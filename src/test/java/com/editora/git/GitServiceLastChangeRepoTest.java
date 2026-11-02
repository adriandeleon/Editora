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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Map card's "Last change" against real repositories (Map review 4): lines with uncommitted edits above them are
 * asked as HEAD's lines (C8), and a shallow clone's boundary commit is not taken for a change (C7).
 */
class GitServiceLastChangeRepoTest {

    @TempDir
    Path temp;

    private final GitService service = new GitService();

    @AfterEach
    void stop() {
        service.shutdown();
    }

    /** A.java: one() by Ada, two() last changed by Bob, three() by Cy. */
    private Path threeAuthors(Path repo) throws Exception {
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
        return file;
    }

    @Test
    void uncommittedLinesAboveADeclarationDoNotShiftItsLastChange() throws Exception {
        Path file = threeAuthors(temp.resolve("repo"));
        // two() is lines 3..5 (0-based) at HEAD.
        assertEquals("Bob", service.lastChangeNow(file, 3, 5).author());

        List<String> lines = new ArrayList<>(List.of(Files.readString(file).split("\n")));
        lines.addAll(1, List.of("  // new", "  // lines"));
        Files.writeString(file, String.join("\n", lines) + "\n");
        // On disk two() is now 5..7: those are three()'s lines at HEAD.
        GitService.LastChange two = service.lastChangeNow(file, 5, 7);
        assertEquals("Bob", two.author(), "the lines are asked as HEAD numbers them");
        assertTrue(two.lines());
        assertEquals("Cy", service.lastChangeNow(file, 9, 11).author(), "three() moved down too");

        GitService.LastChange added = service.lastChangeNow(file, 1, 2);
        assertNotNull(added);
        assertFalse(added.lines(), "lines that are all new have no history: the file's last change");
        assertEquals("Cy", added.author());

        // Past the end of HEAD's file once shifted: still the right lines, not git's "has only N lines".
        GitService.LastChange last = service.lastChangeNow(file, 12, 12);
        assertTrue(last.lines(), "the closing brace maps to HEAD's last line");
    }

    @Test
    void theDiffMapsWorkingLinesToHeadLines() {
        List<int[]> hunks = GitService.diffHunks(
                "diff --git a/A b/A\n@@ -0,0 +1,2 @@\n+a\n+b\n@@ -5 +7,3 @@\n-x\n+y\n+z\n+w\n@@ -9,2 +12,0 @@\n-p\n-q\n");
        assertEquals(3, hunks.size());
        assertEquals(1, GitService.headLine(hunks, 3, true), "two lines were added above");
        assertEquals(4, GitService.headLine(hunks, 6, false));
        assertEquals(5, GitService.headLine(hunks, 8, true), "inside a change: its first old line");
        assertEquals(5, GitService.headLine(hunks, 8, false), "or its last");
        assertEquals(6, GitService.headLine(hunks, 10, true), "after the change");
        assertEquals(8, GitService.headLine(hunks, 12, true), "before a deletion");
        assertEquals(11, GitService.headLine(hunks, 13, true), "after it");
        assertTrue(GitService.headLine(hunks, 1, true) > GitService.headLine(hunks, 2, false), "all new: empty");
    }

    @Test
    void aShallowClonesBoundaryCommitIsNotTakenForTheLastChange() throws Exception {
        Path full = temp.resolve("full");
        threeAuthors(full);
        Files.writeString(full.resolve("B.txt"), "b\n");
        GitServiceRecentChangesTest.commit(full, 1, "Dee", "Dee adds B");

        Path one = temp.resolve("depth1");
        GitServiceRecentChangesTest.git(
                temp, null, "clone", "-q", "--depth", "1", full.toUri().toString(), one.toString());
        GitService.LastChange cut = service.lastChangeNow(one.resolve("A.java"), 3, 5);
        assertTrue(cut.shallow(), "git names the clone's one commit for lines older than it");
        assertTrue(service.lastChangeNow(one.resolve("B.txt"), -1, -1).shallow(), "a file too");

        Path two = temp.resolve("depth2");
        GitServiceRecentChangesTest.git(
                temp, null, "clone", "-q", "--depth", "2", full.toUri().toString(), two.toString());
        GitService.LastChange dee = service.lastChangeNow(two.resolve("B.txt"), -1, -1);
        assertEquals("Dee", dee.author());
        assertFalse(dee.shallow(), "a commit above the cut is a real change");
        assertTrue(service.lastChangeNow(two.resolve("A.java"), 3, 5).shallow());

        assertFalse(service.lastChangeNow(full.resolve("A.java"), 3, 5).shallow(), "a full clone has no cut");
    }
}
