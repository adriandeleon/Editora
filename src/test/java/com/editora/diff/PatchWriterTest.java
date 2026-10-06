package com.editora.diff;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatchWriterTest {

    @Test
    void identicalTextYieldsEmptyPatch() {
        assertEquals("", PatchWriter.unifiedDiff("a/f", "b/f", "x\ny\n", "x\ny\n"));
    }

    @Test
    void emitsUnifiedDiffWithHeadersAndHunk() {
        String patch = PatchWriter.unifiedDiff("a/file.txt", "b/file.txt", "x\ny\nz\n", "x\nY\nz\n");
        assertTrue(patch.startsWith("--- a/file.txt\n+++ b/file.txt\n"), patch);
        assertTrue(patch.contains("@@"), patch);
        assertTrue(patch.contains("-y"), patch);
        assertTrue(patch.contains("+Y"), patch);
        assertTrue(patch.endsWith("\n"));
    }

    @Test
    void finalNewlineOnlyDifferenceProducesApplicablePatch() {
        String patch = PatchWriter.unifiedDiff("a/f", "b/f", "same\n", "same");
        assertTrue(patch.contains("-same"), patch);
        assertTrue(patch.contains("+same"), patch);
        assertTrue(patch.contains("\\ No newline at end of file"), patch);
        PatchParser.FilePatch parsed = PatchParser.parse(patch).get(0);
        assertTrue(parsed.oldFinalNewline());
        assertTrue(!parsed.newFinalNewline());
    }

    @Test
    void writesFinalNewlineMarkersAlongsideContentChanges() {
        String patch = PatchWriter.unifiedDiff("a/f", "b/f", "first\nold\nlast", "first\nnew\nlast\n");

        assertTrue(patch.contains("-old"), patch);
        assertTrue(patch.contains("+new"), patch);
        assertTrue(patch.contains("\\ No newline at end of file"), patch);
    }

    @Test
    void marksBothChangedSidesWhenNeitherHasAFinalNewline() {
        String patch = PatchWriter.unifiedDiff("a/f", "b/f", "first\nold", "first\nnew");

        assertEquals(2, patch.split("\\\\ No newline at end of file", -1).length - 1, patch);
        PatchParser.FilePatch parsed = PatchParser.parse(patch).get(0);
        assertTrue(!parsed.oldFinalNewline());
        assertTrue(!parsed.newFinalNewline());
    }

    @Test
    void appendsAnEofHunkWhenContentChangeIsFarFromFinalNewlineChange() {
        String middle = "unchanged\n".repeat(12);
        String patch = PatchWriter.unifiedDiff("a/f", "b/f", "old\n" + middle + "last", "new\n" + middle + "last\n");

        assertTrue(patch.indexOf("@@") != patch.lastIndexOf("@@"), patch);
        assertTrue(patch.contains("\\ No newline at end of file"), patch);
    }

    @Test
    void eofMarkerIsNotAttachedToAnEarlierRepeatedLine() {
        String middle = "unchanged\n".repeat(12);
        String patch = PatchWriter.unifiedDiff(
                "a/f", "b/f", "tail\nold\n" + middle + "tail", "TAIL\nold\n" + middle + "tail\n");

        assertEquals(1, patch.split("\\\\ No newline at end of file", -1).length - 1, patch);
        PatchParser.FilePatch parsed = PatchParser.parse(patch).get(0);
        assertTrue(!parsed.oldFinalNewline());
        assertTrue(parsed.newFinalNewline());
    }

    @Test
    void marksAnUnterminatedLineAddedToOrDeletedFromAnEmptyFile() {
        String added = PatchWriter.unifiedDiff("a/f", "b/f", "", "new");
        String deleted = PatchWriter.unifiedDiff("a/f", "b/f", "old", "");

        assertTrue(added.contains("+new\n\\ No newline at end of file"), added);
        assertTrue(deleted.contains("-old\n\\ No newline at end of file"), deleted);
    }

    // --- end-of-file shapes, checked against real `git apply` ----------------------------------------

    /** Both sides unterminated and lines appended after the old last line: that line is no longer last. */
    @Test
    void appendingAfterAnUnterminatedLastLineReplacesItWithMarkers() {
        assertEquals("""
                --- a/f
                +++ b/f
                @@ -1,1 +1,2 @@
                -a
                \\ No newline at end of file
                +a
                +b
                \\ No newline at end of file
                """, PatchWriter.unifiedDiff("a/f", "b/f", "a", "a\nb"));
    }

    /** Both unterminated and trailing lines deleted: the surviving line becomes the unterminated last one. */
    @Test
    void deletingTrailingLinesAtAnUnterminatedEofKeepsTheResultUnterminated() {
        String patch = PatchWriter.unifiedDiff("a/f", "b/f", "a\nb", "a");

        assertTrue(patch.contains("-b\n\\ No newline at end of file\n"), patch);
        assertTrue(patch.contains("+a\n\\ No newline at end of file\n"), patch);
    }

    /** Only one side's last line is in the hunk and the EOF state differs: one hunk, never an overlapping second. */
    @Test
    void anAppendedUnterminatedLineStaysInOneHunk() {
        String patch = PatchWriter.unifiedDiff("a/f", "b/f", "a\nb\n", "a\nb\nc");

        assertEquals(1, patch.split("\n@@ ", -1).length - 1, patch);
        assertTrue(patch.endsWith(" a\n b\n+c\n\\ No newline at end of file\n"), patch);
    }

    @Test
    void anUnterminatedLastLineSharedByBothSidesIsMarkedContext() {
        String patch = PatchWriter.unifiedDiff("a/f", "b/f", "old\nsame", "new\nsame");

        assertTrue(patch.endsWith("-old\n+new\n same\n\\ No newline at end of file\n"), patch);
    }

    /**
     * Every pairing of a few bodies and both end-of-file states must produce a patch real Git accepts and
     * that reproduces the right side byte for byte — including the three shapes that used to be rejected,
     * to gain a newline, or to carry an overlapping hunk.
     */
    @Test
    void everyEofShapeRoundTripsThroughGitApply(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(gitAvailable(), "git is not installed");
        run(dir, null, "git", "init", "-q");
        // Git for Windows defaults to core.autocrlf=true, which rewrites the applied file's line endings and
        // would fail the byte-for-byte comparison below for a reason that has nothing to do with the patch.
        run(dir, null, "git", "config", "core.autocrlf", "false");
        List<String> bodies = List.of(
                "",
                "a",
                "a\nb",
                "a\nb\nc",
                "b",
                "x\na\nb",
                "a\n\nb",
                "one\ntwo\nthree\nfour\nfive\nsix\nseven\neight\nnine\nten\neleven\ntwelve",
                "ONE\ntwo\nthree\nfour\nfive\nsix\nseven\neight\nnine\nten\neleven\ntwelve",
                "one\ntwo\nthree\nfour\nfive\nsix\nseven\neight\nnine\nten\neleven\ntwelve\nthirteen");
        List<String> texts = new ArrayList<>();
        for (String body : bodies) {
            texts.add(body);
            if (!body.isEmpty()) {
                texts.add(body + "\n");
            }
        }
        Path file = dir.resolve("f.txt");
        int checked = 0;
        for (String left : texts) {
            for (String right : texts) {
                if (left.equals(right) || left.isEmpty() || right.isEmpty()) {
                    continue; // creation/deletion need git's /dev/null headers, which this writer does not emit
                }
                String patch = PatchWriter.unifiedDiff("a/f.txt", "b/f.txt", left, right);
                Files.writeString(file, left);
                String label = "[" + left.replace("\n", "\\n") + "] -> [" + right.replace("\n", "\\n") + "]\n" + patch;
                assertEquals(0, run(dir, patch, "git", "apply", "--check", "-").exit(), "git apply --check: " + label);
                assertEquals(0, run(dir, patch, "git", "apply", "-").exit(), "git apply: " + label);
                assertEquals(right, Files.readString(file), label);
                checked++;
            }
        }
        assertTrue(checked > 200, "the matrix should cover every EOF combination, covered " + checked);
    }

    @Test
    void carriageReturnsStayOnTheirLines() {
        String patch = PatchWriter.unifiedDiff("a/f", "b/f", "a\r\nb\r\n", "a\r\nB\r\n");

        assertTrue(patch.endsWith(" a\r\n-b\r\n+B\r\n"), patch.replace("\r", "\\r"));
    }

    /** A patch exported for CRLF, mixed or lone-CR content must apply to that content with real Git. */
    @Test
    void carriageReturnShapesRoundTripThroughGitApply(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(gitAvailable(), "git is not installed");
        run(dir, null, "git", "init", "-q");
        run(dir, null, "git", "config", "core.autocrlf", "false");
        List<String[]> cases = List.of(
                new String[] {"a\r\nb\r\n", "a\r\nB\r\n"},
                new String[] {"a\r\nb\nc\r\n", "a\r\nb\nC\r\n"},
                new String[] {"a\rb\rc", "a\rB\rc"},
                new String[] {"a\r\nb\r\n", "a\r\nb\r\nc\r\n"},
                new String[] {"a\r\nb", "a\r\nb\r\n"},
                new String[] {"a\r\nb\r\n", "a\nb\n"});
        Path file = dir.resolve("f.txt");
        for (String[] c : cases) {
            String patch = PatchWriter.unifiedDiff("a/f.txt", "b/f.txt", c[0], c[1]);
            Files.writeString(file, c[0]);
            String label = (c[0] + " -> " + c[1] + "\n" + patch).replace("\r", "\\r");
            assertEquals(0, run(dir, patch, "git", "apply", "--check", "-").exit(), "git apply --check: " + label);
            assertEquals(0, run(dir, patch, "git", "apply", "-").exit(), "git apply: " + label);
            assertEquals(c[1], Files.readString(file), label);
        }
    }

    private record Outcome(int exit, String output) {}

    private static boolean gitAvailable() {
        try {
            return run(null, null, "git", "--version").exit() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static Outcome run(Path dir, String stdin, String... command) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        if (dir != null) {
            builder.directory(dir.toFile());
        }
        Process process = builder.start();
        try (OutputStream in = process.getOutputStream()) {
            if (stdin != null) {
                in.write(stdin.getBytes(StandardCharsets.UTF_8));
            }
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Outcome(process.waitFor(), output);
    }
}
