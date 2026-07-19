package com.editora.diff;

import java.util.List;

import com.editora.diff.PatchParser.FilePatch;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatchParserTest {

    @Test
    void bareSingleFileDiff() {
        String patch = """
                --- a/Foo.txt
                +++ b/Foo.txt
                @@ -1,3 +1,3 @@
                 one
                -two
                +TWO
                 three
                """;
        List<FilePatch> files = PatchParser.parse(patch);
        assertEquals(1, files.size());
        FilePatch f = files.get(0);
        assertEquals("Foo.txt", f.oldPath());
        assertEquals("Foo.txt", f.newPath());
        assertEquals(List.of("one", "two", "three"), f.oldLines());
        assertEquals(List.of("one", "TWO", "three"), f.newLines());
    }

    @Test
    void countsAdditionsAndDeletionsExcludingContext() {
        // Two context lines carried on both sides, plus one add + two deletes — the counts must ignore context.
        String patch = """
                --- a/Foo.txt
                +++ b/Foo.txt
                @@ -1,4 +1,3 @@
                 keep1
                -gone1
                -gone2
                +added1
                 keep2
                """;
        FilePatch f = PatchParser.parse(patch).get(0);
        assertEquals(1, f.additions());
        assertEquals(2, f.deletions());
        // oldLines/newLines include the 2 context lines, so their sizes are NOT the diff stat.
        assertEquals(4, f.oldLines().size());
        assertEquals(3, f.newLines().size());
    }

    @Test
    void gitStyleSingleFileDiff() {
        String patch = """
                diff --git a/src/Main.java b/src/Main.java
                index 1234567..89abcde 100644
                --- a/src/Main.java
                +++ b/src/Main.java
                @@ -1,2 +1,2 @@
                -old line
                +new line
                 unchanged
                """;
        List<FilePatch> files = PatchParser.parse(patch);
        assertEquals(1, files.size());
        FilePatch f = files.get(0);
        assertEquals("src/Main.java", f.oldPath());
        assertEquals("src/Main.java", f.newPath());
        assertEquals(List.of("old line", "unchanged"), f.oldLines());
        assertEquals(List.of("new line", "unchanged"), f.newLines());
    }

    @Test
    void multipleHunksInOneFileWithAGap() {
        String patch = """
                --- a/x.txt
                +++ b/x.txt
                @@ -1,2 +1,2 @@
                -a
                +A
                 b
                @@ -40,2 +40,2 @@
                 c
                -d
                +D
                """;
        List<FilePatch> files = PatchParser.parse(patch);
        assertEquals(1, files.size());
        FilePatch f = files.get(0);
        assertEquals(List.of("a", "b", "c", "d"), f.oldLines());
        assertEquals(List.of("A", "b", "c", "D"), f.newLines());
    }

    @Test
    void multiFilePatchParsesEachFileSeparately() {
        String patch = """
                diff --git a/One.txt b/One.txt
                index 111..222 100644
                --- a/One.txt
                +++ b/One.txt
                @@ -1,1 +1,1 @@
                -one
                +ONE
                diff --git a/Two.txt b/Two.txt
                index 333..444 100644
                --- a/Two.txt
                +++ b/Two.txt
                @@ -1,1 +1,1 @@
                -two
                +TWO
                """;
        List<FilePatch> files = PatchParser.parse(patch);
        assertEquals(2, files.size());
        assertEquals("One.txt", files.get(0).newPath());
        assertEquals(List.of("ONE"), files.get(0).newLines());
        assertEquals("Two.txt", files.get(1).newPath());
        assertEquals(List.of("TWO"), files.get(1).newLines());
    }

    @Test
    void addedFileHasDevNullOldPath() {
        String patch = """
                --- /dev/null
                +++ b/New.txt
                @@ -0,0 +1,2 @@
                +hello
                +world
                """;
        List<FilePatch> files = PatchParser.parse(patch);
        assertEquals(1, files.size());
        FilePatch f = files.get(0);
        assertEquals("/dev/null", f.oldPath());
        assertEquals("New.txt", f.newPath());
        assertTrue(f.oldLines().isEmpty());
        assertEquals(List.of("hello", "world"), f.newLines());
    }

    @Test
    void deletedFileHasDevNullNewPath() {
        String patch = """
                --- a/Old.txt
                +++ /dev/null
                @@ -1,2 +0,0 @@
                -hello
                -world
                """;
        List<FilePatch> files = PatchParser.parse(patch);
        assertEquals(1, files.size());
        FilePatch f = files.get(0);
        assertEquals("Old.txt", f.oldPath());
        assertEquals("/dev/null", f.newPath());
        assertEquals(List.of("hello", "world"), f.oldLines());
        assertTrue(f.newLines().isEmpty());
    }

    @Test
    void noNewlineMarkerIsSkippedNotCountedAsContent() {
        String patch = """
                --- a/x.txt
                +++ b/x.txt
                @@ -1,1 +1,1 @@
                -old
                \\ No newline at end of file
                +new
                \\ No newline at end of file
                """;
        List<FilePatch> files = PatchParser.parse(patch);
        assertEquals(1, files.size());
        assertEquals(List.of("old"), files.get(0).oldLines());
        assertEquals(List.of("new"), files.get(0).newLines());
    }

    /** A removed line whose own text looks like a file-header marker ("--- fake header") must still be
     *  consumed as hunk content (per the {@code @@} line counts), never mistaken for the next file's
     *  {@code ---} — real-world-messy input per the project's testing convention. */
    @Test
    void hunkContentLineThatLooksLikeAFileHeaderIsNotMisparsed() {
        String patch = """
                --- a/doc.md
                +++ b/doc.md
                @@ -1,3 +1,3 @@
                 title
                -- fake header
                +- real content
                 end
                """;
        List<FilePatch> files = PatchParser.parse(patch);
        assertEquals(1, files.size());
        FilePatch f = files.get(0);
        assertEquals(List.of("title", "- fake header", "end"), f.oldLines());
        assertEquals(List.of("title", "- real content", "end"), f.newLines());
    }

    @Test
    void crlfLineEndingsAreNormalized() {
        String patch = "--- a/x.txt\r\n+++ b/x.txt\r\n@@ -1,1 +1,1 @@\r\n-old\r\n+new\r\n";
        List<FilePatch> files = PatchParser.parse(patch);
        assertEquals(1, files.size());
        assertEquals(List.of("old"), files.get(0).oldLines());
        assertEquals(List.of("new"), files.get(0).newLines());
    }

    @Test
    void garbageInputReturnsEmptyList() {
        assertTrue(PatchParser.parse("just some random text\nwith no diff markers at all\n")
                .isEmpty());
        assertTrue(PatchParser.parse("").isEmpty());
        assertTrue(PatchParser.parse(null).isEmpty());
    }

    @Test
    void roundTripsThroughPatchWriterForAShortFile() {
        String left = "one\ntwo\nthree\nfour\n";
        String right = "one\nTWO\nthree\nfour\n";
        String patch = PatchWriter.unifiedDiff("a/x.txt", "b/x.txt", left, right);
        List<FilePatch> files = PatchParser.parse(patch);
        assertEquals(1, files.size());
        FilePatch f = files.get(0);
        assertEquals(DiffEngine.lines(left), f.oldLines());
        assertEquals(DiffEngine.lines(right), f.newLines());
    }
}
