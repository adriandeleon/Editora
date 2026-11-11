package com.editora.git;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The working tree's changes as the Map's Changes card reads them: a patch split file by file (round 2). */
class GitChangesTest {

    private static final String PATCH = String.join(
            "\n",
            "diff --git a/src/A.java b/src/A.java",
            "index 111..222 100644",
            "--- a/src/A.java",
            "+++ b/src/A.java",
            "@@ -1,4 +1,5 @@ class A {",
            " class A {",
            "-  int one;",
            "+  long one;",
            "+  long two;",
            "   int three;",
            " }",
            "@@ -10,2 +11,1 @@",
            " a",
            "-b",
            "\\ No newline at end of file",
            "diff --git a/docs/new file.md b/docs/new file.md",
            "new file mode 100644",
            "--- /dev/null",
            "+++ b/docs/new file.md",
            "@@ -0,0 +1 @@",
            "+# New",
            "diff --git a/old.txt b/old.txt",
            "deleted file mode 100644",
            "--- a/old.txt",
            "+++ /dev/null",
            "@@ -1,2 +0,0 @@",
            "--- a dash line",
            "-gone",
            "diff --git a/logo.png b/logo.png",
            "index 333..444 100644",
            "Binary files a/logo.png and b/logo.png differ",
            "diff --git \"a/caf\\303\\251\\tx.txt\" \"b/caf\\303\\251\\tx.txt\"",
            "--- \"a/caf\\303\\251\\tx.txt\"",
            "+++ \"b/caf\\303\\251\\tx.txt\"",
            "@@ -1 +1 @@",
            "-x",
            "+y",
            "");

    @Test
    void aPatchIsSplitIntoFilesWithTheirLinesNumbered() {
        List<GitChanges.FileDiff> files = GitChanges.parse(PATCH);
        assertEquals(
                List.of("src/A.java", "docs/new file.md", "old.txt", "logo.png", "café\tx.txt"),
                files.stream().map(GitChanges.FileDiff::path).toList());
        GitChanges.FileDiff a = files.get(0);
        assertEquals('M', a.status());
        assertEquals(2, a.added());
        assertEquals(2, a.removed());
        assertEquals(List.of(0, 7), a.hunks());
        assertEquals(
                new GitChanges.Line(GitChanges.Kind.REMOVED, 2, 0, "  int one;"),
                a.lines().get(2));
        assertEquals(
                new GitChanges.Line(GitChanges.Kind.ADDED, 0, 3, "  long two;"),
                a.lines().get(4));
        assertEquals(
                new GitChanges.Line(GitChanges.Kind.CONTEXT, 3, 4, "  int three;"),
                a.lines().get(5));
        assertEquals(GitChanges.Kind.NOTE, a.lines().getLast().kind());
        assertEquals(2, a.hunkLine(0), "the first added line");
        assertEquals(12, a.hunkLine(1), "a removal: the line after it (the old line's place)");
        assertEquals(2, a.nearestNewLine(2), "a removed line: the next line in the new file");

        assertEquals('A', files.get(1).status());
        assertEquals(1, files.get(1).hunkLine(0));
        GitChanges.FileDiff old = files.get(2);
        assertEquals('D', old.status());
        assertEquals(2, old.removed(), "a removed line that starts with -- is a line, not a header");
        assertEquals(1, old.hunkLine(0), "nothing is left: line 1");
        assertTrue(files.get(3).binary());
        assertTrue(files.get(3).hunks().isEmpty());
        assertEquals(1, files.get(4).added());
        assertTrue(GitChanges.parse("").isEmpty());
        assertTrue(GitChanges.parse("not a patch\n").isEmpty());
    }

    @Test
    void untrackedFilesAreAddedWholeAndTheReadingIsCapped() {
        GitChanges.FileDiff text = GitChanges.untracked("n.txt", "one\r\ntwo\n", false);
        assertEquals('U', text.status());
        assertEquals(2, text.added());
        assertEquals(
                List.of("one", "two"),
                text.lines().stream().skip(1).map(GitChanges.Line::text).toList());
        assertEquals(1, text.hunkLine(0));
        assertTrue(GitChanges.untracked("e.txt", "", false).hunks().isEmpty());
        assertTrue(GitChanges.untracked("b.bin", "a\0b", false).binary());
        GitChanges.FileDiff big = GitChanges.untracked("big.txt", "x\n".repeat(GitChanges.MAX_LINES + 10), false);
        assertTrue(big.truncated());
        assertEquals(GitChanges.MAX_LINES + 10, big.added(), "the count stays whole");

        List<GitChanges.FileDiff> many = new java.util.ArrayList<>();
        for (int i = 0; i < GitChanges.MAX_FILES + 1; i++) {
            many.add(GitChanges.untracked(String.format("f%04d", i), "x\n", false));
        }
        GitChanges.Result capped = GitChanges.merge(List.of(), many, false);
        assertTrue(capped.truncated());
        assertEquals(GitChanges.MAX_FILES, capped.files().size());
        GitChanges.Result both = GitChanges.merge(GitChanges.parse(PATCH), List.of(text), false);
        assertTrue(both.ok());
        assertFalse(both.truncated());
        assertEquals("docs/new file.md", both.files().get(1).path(), "in path order");
        assertEquals('U', both.file("n.txt").status());
        assertNull(both.file("missing"));
        assertFalse(GitChanges.Result.UNAVAILABLE.ok());
    }

    @Test
    void headerPathsAndQuotedNames() {
        assertEquals("a b/c", GitChanges.headerPath("a/a b/c b/a b/c"));
        assertEquals("x", GitChanges.headerPath("a/x b/y"), "not the same twice: up to b/");
        assertEquals(List.of("a/\"q\"", "b/n\nl"), GitChanges.quotedParts("\"a/\\\"q\\\"\" \"b/n\\nl\""));
    }

    @Test
    void aLongDiffKeepsItsCountsButNotEveryLine() {
        StringBuilder patch =
                new StringBuilder("diff --git a/x b/x\n@@ -1,0 +1," + (GitChanges.MAX_LINES + 5) + " @@\n");
        for (int i = 0; i < GitChanges.MAX_LINES + 5; i++) {
            patch.append("+l\n");
        }
        GitChanges.FileDiff file = GitChanges.parse(patch.toString()).getFirst();
        assertTrue(file.truncated());
        assertEquals(GitChanges.MAX_LINES, file.lines().size());
        assertEquals(GitChanges.MAX_LINES + 5, file.added());
    }

    @Test
    void oddPatchesAreReadAsFarAsTheyMakeSense() {
        String crlf = "diff --git a/w.txt b/w.txt\r\n@@ -1 +1 @@\r\n-a\r\n+b\r\n";
        GitChanges.FileDiff windows = GitChanges.parse(crlf).getFirst();
        assertEquals("b", windows.lines().getLast().text(), "the CR is not the line's");
        GitChanges.FileDiff odd = GitChanges.parse(String.join(
                        "\n",
                        "diff --git x y",
                        "GIT binary patch",
                        "@@ not a header",
                        "@@ -5,1 +4,0 @@",
                        "-gone",
                        "?unknown",
                        ""))
                .getFirst();
        assertEquals("x y", odd.path(), "no a/ b/: the header as it is");
        assertTrue(odd.binary());
        assertEquals(1, odd.hunks().size(), "the line that is no header is left out");
        assertEquals(4, odd.hunkLine(0), "a removal with nothing after: where the hunk starts");
        assertEquals(1, odd.removed());
        assertEquals("caf\u00e9", GitChanges.headerPath("\"caf\\303\\251\""), "quoted without a/");
        assertEquals(List.of("t\tr\r", "\\7"), GitChanges.quotedParts("\"t\\tr\\r\" \"\\\\7\""));
        assertEquals(List.of("ab"), GitChanges.quotedParts("x\"ab"), "an unclosed quote ends with the text");

        // A hunk past the line cap leaves its header out, and is not a hunk to step to.
        StringBuilder patch = new StringBuilder("diff --git a/x b/x\n@@ -1,0 +1," + GitChanges.MAX_LINES + " @@\n");
        patch.append("+l\n".repeat(GitChanges.MAX_LINES));
        patch.append("@@ -9,0 +9999,1 @@\n+m\n");
        GitChanges.FileDiff capped = GitChanges.parse(patch.toString()).getFirst();
        assertEquals(1, capped.hunks().size());
        assertTrue(capped.truncated());
        assertEquals(GitChanges.MAX_LINES + 1, capped.added());
    }
}
