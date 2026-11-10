package com.editora.git;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link LineHistory#parse}: {@code git log -L} output read without Git (Map gap 12). */
class LineHistoryTest {

    private static final String RS = "\u001e";
    private static final String US = "\u001f";

    private static String header(String hash, String author, long when, String subject) {
        return RS + hash + US + author + US + when + US + subject + "\n";
    }

    @Test
    void commitsAreReadWithTheirPathsAndPatch() {
        String out = header("1111111aaaa", "Dee", 300, "edit: two")
                + "\n"
                + "diff --git a/src/B.java b/src/B.java\n"
                + "--- a/src/A.java\n"
                + "+++ b/src/B.java\n"
                + "@@ -4,3 +4,3 @@\n"
                + "   int two() {\n"
                + "-    return 2;\n"
                + "+    return 3;\n"
                + "   }\n"
                + "\n"
                + header("2222222bbbb", "Ada", 100, "start | with a bar")
                + "\n"
                + "diff --git a/src/A.java b/src/A.java\n"
                + "--- /dev/null\n"
                + "+++ b/src/A.java\n"
                + "@@ -0,0 +4,3 @@\n"
                + "+  int two() {\n"
                + "+    return 2;\n"
                + "+  }\n";
        Path root = Path.of("/repo");
        LineHistory.Result result = LineHistory.parse(out, 10, root);

        assertEquals(LineHistory.Status.OK, result.status());
        assertEquals(root, result.root());
        assertFalse(result.truncated());
        assertEquals(2, result.commits().size());
        LineHistory.Commit dee = result.commits().get(0);
        assertEquals("1111111aaaa", dee.hash());
        assertEquals("Dee", dee.author());
        assertEquals(300, dee.epochSeconds());
        assertEquals("edit: two", dee.subject());
        assertEquals("src/B.java", dee.path());
        assertEquals("src/A.java", dee.previousPath(), "moved in this commit");
        assertEquals(
                List.of("@@ -4,3 +4,3 @@", "   int two() {", "-    return 2;", "+    return 3;", "   }"), dee.patch());
        assertEquals(1, dee.added());
        assertEquals(1, dee.removed());

        LineHistory.Commit ada = result.commits().get(1);
        assertEquals("start | with a bar", ada.subject());
        assertEquals("src/A.java", ada.path());
        assertNull(ada.previousPath(), "a new file has no old path");
        assertEquals(3, ada.added());
        assertEquals(0, ada.removed());
        assertEquals(List.of("Dee", "Ada"), result.authors());
    }

    @Test
    void aLongHistoryIsCutAndSaysSo() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            out.append(header("abcdef" + i + "0000", i % 2 == 0 ? "Ann" : "Ben", i, "c" + i))
                    .append("\n@@ -1 +1 @@\n-a\n+b\n");
        }
        LineHistory.Result result = LineHistory.parse(out.toString(), 3, null);
        assertEquals(3, result.commits().size());
        assertTrue(result.truncated());
        assertEquals(List.of("Ann", "Ben"), result.authors());

        assertFalse(LineHistory.parse(out.toString(), 5, null).truncated());
    }

    @Test
    void unreadableRecordsAreSkipped() {
        assertTrue(LineHistory.parse(null, 10, null).commits().isEmpty());
        assertTrue(LineHistory.parse("", 10, null).commits().isEmpty());
        String out = RS + "short" + US + "x" + US + "1" + US + "s\n" // too short a hash
                + RS + "no fields at all\n"
                + header("3333333cccc", "Cy", 0, "kept").replace(US + "0" + US, US + "soon" + US)
                + "--- \"a/odd name.txt\"\n+++ \"b/odd name.txt\"\n@@ -1 +1 @@\n-x\n+y\n"
                + "diff --git a/other b/other\n+not counted\n";
        LineHistory.Result result = LineHistory.parse(out, 10, null);
        assertEquals(1, result.commits().size());
        LineHistory.Commit cy = result.commits().get(0);
        assertEquals(0, cy.epochSeconds(), "an unreadable time reads as 0");
        assertEquals("odd name.txt", cy.path());
        assertEquals(1, cy.added(), "a second file's diff is not this history's");
        assertEquals(1, cy.removed());
    }

    @Test
    void aDeletedSideOrNoSideAtAllStillReads() {
        String out = header("5555555eeee", "Ed", 2, "gone")
                + "--- a/old.txt\n+++ /dev/null\n@@ -1 +0,0 @@\n-x\n"
                + header("6666666ffff", "Fi", 1, "bare")
                + "@@ -1 +1 @@\n-a\n+b\n"
                + header("7777777gggg", "Gu", 0, "odd")
                + "--- \"\n+++ plain.txt\n@@ -1 +1 @@\n+c\n";
        List<LineHistory.Commit> commits = LineHistory.parse(out, 10, null).commits();
        assertEquals("old.txt", commits.get(0).path(), "deleted: its old path");
        assertNull(commits.get(0).previousPath());
        assertEquals("", commits.get(1).path(), "no file header");
        assertEquals("plain.txt", commits.get(2).path(), "no b/ prefix: as written");
        assertEquals("\"", commits.get(2).previousPath(), "a lone quote is a name");
    }

    @Test
    void aRewriteKeepsItsFirstLinesOnly() {
        StringBuilder out = new StringBuilder(header("4444444dddd", "Di", 1, "rewrite"));
        out.append("@@ -1,0 +1,3000 @@\n");
        for (int i = 0; i < 3000; i++) {
            out.append("+line ").append(i).append('\n');
        }
        LineHistory.Commit commit =
                LineHistory.parse(out.toString(), 1, null).commits().get(0);
        assertEquals(LineHistory.MAX_PATCH_LINES, commit.patch().size());
        assertEquals(3000, commit.added(), "the counts are of the whole diff");
    }

    @Test
    void resultsWithoutCommits() {
        LineHistory.Result none = LineHistory.Result.unavailable();
        assertEquals(LineHistory.Status.UNAVAILABLE, none.status());
        assertTrue(none.commits().isEmpty());
        assertNull(none.root());
        LineHistory.Result failed = LineHistory.Result.failed(Path.of("/r"), "bad");
        assertEquals(LineHistory.Status.FAILED, failed.status());
        assertEquals("bad", failed.problem());
        assertTrue(failed.authors().isEmpty());
    }
}
