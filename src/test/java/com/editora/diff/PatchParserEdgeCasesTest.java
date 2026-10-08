package com.editora.diff;

import java.util.List;

import com.editora.diff.PatchParser.FilePatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The patch parser on what real patches throw at it besides the tidy case: git's C-quoted paths, headers that
 * are missing or carry a timestamp, a hunk nobody introduced, and the "no newline" marker after each kind of
 * line.
 */
class PatchParserEdgeCasesTest {

    // --- C-quoted paths ----------------------------------------------------------------------------

    @Test
    void anUnquotedOrHalfQuotedNameIsLeftAsItIs() {
        assertEquals("plain.txt", PatchParser.unquote("  plain.txt  "), "only surrounding blanks are dropped");
        assertEquals("\"", PatchParser.unquote("\""), "one quote character is a name, not an empty quoted one");
        assertEquals("\"no closing quote", PatchParser.unquote("\"no closing quote"));
        assertEquals("closing only\"", PatchParser.unquote("closing only\""));
        assertEquals("", PatchParser.unquote("\"\""), "an empty quoted name");
    }

    @Test
    void everyCEscapeGitWritesIsDecoded() {
        assertEquals("tab\there", PatchParser.unquote("\"tab\\there\""));
        assertEquals("line\nfeed", PatchParser.unquote("\"line\\nfeed\""));
        assertEquals("carriage\rreturn", PatchParser.unquote("\"carriage\\rreturn\""));
        assertEquals("bell\u0007", PatchParser.unquote("\"bell\\a\""));
        assertEquals("back\bspace", PatchParser.unquote("\"back\\bspace\""));
        assertEquals("form\ffeed", PatchParser.unquote("\"form\\ffeed\""));
        assertEquals("vertical\u000Btab", PatchParser.unquote("\"vertical\\vtab\""));
        assertEquals("say \"hi\"", PatchParser.unquote("\"say \\\"hi\\\"\""));
        assertEquals("back\\slash", PatchParser.unquote("\"back\\\\slash\""));
    }

    @Test
    void octalEscapesAreUtf8BytesOfOneToThreeDigits() {
        assertEquals("café.txt", PatchParser.unquote("\"caf\\303\\251.txt\""));
        assertEquals("日本.txt", PatchParser.unquote("\"\\346\\227\\245\\346\\234\\254.txt\""));
        assertEquals("A", PatchParser.unquote("\"\\101\""));
        assertEquals("\u0007x", PatchParser.unquote("\"\\7x\""), "a one-digit escape stops at the first non-digit");
        assertEquals("\u003F9", PatchParser.unquote("\"\\779\""), "9 is not an octal digit: it is the next character");
        assertEquals("é literal", PatchParser.unquote("\"é literal\""), "core.quotePath=false keeps non-ASCII as is");
    }

    @Test
    void aBackslashWithNothingAfterItIsKeptAsABackslash() {
        // The quoted text ends in an escaped quote: there is no closing quote left, so nothing follows.
        assertEquals("abc\\", PatchParser.unquote("\"abc\\\""));
    }

    // --- headers -----------------------------------------------------------------------------------

    @Test
    void aGnuDiffTimestampAfterTheNameIsNotPartOfTheName() {
        List<FilePatch> files = PatchParser.parse("--- a/src/f.txt\t2026-01-01 00:00:00.000000000 +0000\n"
                + "+++ b/src/f.txt\t2026-01-02 00:00:00.000000000 +0000\n"
                + "@@ -1 +1 @@\n-old\n+new\n");
        assertEquals(1, files.size());
        assertEquals("src/f.txt", files.get(0).oldPath());
        assertEquals("src/f.txt", files.get(0).newPath());
    }

    @Test
    void aQuotedHeaderNameIsDecodedAndItsPrefixDropped() {
        List<FilePatch> files = PatchParser.parse(
                "--- \"a/caf\\303\\251.txt\"\n+++ \"b/caf\\303\\251.txt\"\n" + "@@ -1 +1 @@\n-old\n+new\n");
        assertEquals("café.txt", files.get(0).oldPath());
        assertEquals("café.txt", files.get(0).newPath());
    }

    @ParameterizedTest
    @CsvSource({"a/,a/", "b/,b/", "a,a", "/dev/null,/dev/null"})
    void aNameThatIsOnlyAPrefixIsNotEmptied(String label, String expected) {
        List<FilePatch> files = PatchParser.parse("--- " + label + "\n+++ b/f.txt\n@@ -1 +1 @@\n-old\n+new\n");
        assertEquals(expected, files.get(0).oldPath());
    }

    @Test
    void aHunkWithNoFileHeaderIsStillRead() {
        List<FilePatch> files = PatchParser.parse("@@ -7,2 +7,2 @@\n keep\n-old\n+new\n");
        assertEquals(1, files.size());
        FilePatch file = files.get(0);
        assertEquals("", file.oldPath());
        assertEquals("", file.newPath());
        assertEquals(List.of("keep", "old"), file.oldLines());
        assertEquals(List.of("keep", "new"), file.newLines());
        assertEquals(List.of(7, 8), file.oldLineNumbers(), "numbered as the hunk header says");
    }

    @Test
    void aSectionMayHaveOnlyItsNewNameOrBareMarkers() {
        FilePatch onlyNew =
                PatchParser.parse("+++ b/created.txt\n@@ -0,0 +1 @@\n+hello\n").get(0);
        assertEquals("", onlyNew.oldPath());
        assertEquals("created.txt", onlyNew.newPath());
        assertEquals(List.of("hello"), onlyNew.newLines());

        FilePatch bare =
                PatchParser.parse("---\n+++\n@@ -1 +1 @@\n-old\n+new\n").get(0);
        assertEquals("", bare.oldPath());
        assertEquals("", bare.newPath());
        assertEquals(1, bare.additions());
        assertEquals(1, bare.deletions());
    }

    @Test
    void headersAloneWithoutAnyHunkStillNameAFile() {
        List<FilePatch> files =
                PatchParser.parse("--- a/first.txt\n+++ b/first.txt\n--- a/second.txt\n+++ b/second.txt\n");
        assertEquals(
                List.of("first.txt", "second.txt"),
                files.stream().map(FilePatch::newPath).toList());
        assertTrue(files.get(0).oldLines().isEmpty());
    }

    @Test
    void nothingThatLooksLikeADiffParsesToNothing() {
        assertEquals(List.of(), PatchParser.parse(null));
        assertEquals(List.of(), PatchParser.parse("   \n\n"));
        assertEquals(List.of(), PatchParser.parse("Just some prose.\nNo diff here.\n"));
        assertEquals(List.of(), PatchParser.parseAllSections("index 1234567..89abcde 100644\n"));
    }

    // --- hunk bodies -------------------------------------------------------------------------------

    @Test
    void anEmptyLineInAHunkIsAnEmptyContextLineAndALoneSignAnEmptyChangedLine() {
        // Mail clients and editors strip the single space of a blank context line.
        FilePatch file = PatchParser.parse("--- a/f.txt\n+++ b/f.txt\n@@ -1,3 +1,3 @@\n first\n\n-\n+\n")
                .get(0);
        assertEquals(List.of("first", "", ""), file.oldLines());
        assertEquals(List.of("first", "", ""), file.newLines());
        assertEquals(1, file.additions());
        assertEquals(1, file.deletions());
    }

    @Test
    void theNoNewlineMarkerAppliesToTheSideOfTheLineBeforeIt() {
        String head = "--- a/f.txt\n+++ b/f.txt\n";
        FilePatch afterRemoved = PatchParser.parse(head + "@@ -1 +1 @@\n-old\n\\ No newline at end of file\n+new\n")
                .get(0);
        assertFalse(afterRemoved.oldFinalNewline());
        assertTrue(afterRemoved.newFinalNewline());

        FilePatch afterAdded = PatchParser.parse(head + "@@ -1 +1 @@\n-old\n+new\n\\ No newline at end of file\n")
                .get(0);
        assertTrue(afterAdded.oldFinalNewline());
        assertFalse(afterAdded.newFinalNewline());

        // After a context line the last line is shared: neither side ends in a newline.
        FilePatch afterContext = PatchParser.parse(
                        head + "@@ -1,2 +1,2 @@\n-old\n+new\n last\n\\ No newline at end of file\n")
                .get(0);
        assertFalse(afterContext.oldFinalNewline());
        assertFalse(afterContext.newFinalNewline());

        // A marker before any file is noise, not a crash.
        assertEquals(List.of(), PatchParser.parse("\\ No newline at end of file\n"));
    }

    @Test
    void crlfPatchesAreReadLikeLfOnes() {
        FilePatch file = PatchParser.parse("--- a/f.txt\r\n+++ b/f.txt\r\n@@ -1 +1 @@\r\n-old\r\n+new\r\n")
                .get(0);
        assertEquals("f.txt", file.newPath());
        assertEquals(List.of("old"), file.oldLines());
        assertEquals(List.of("new"), file.newLines());
    }

    // --- git sections without hunks ----------------------------------------------------------------

    @Test
    void aRenameACopyAndAModeChangeAreListedEvenWithoutHunks() {
        List<FilePatch> files = PatchParser.parseAllSections("diff --git a/old name.txt b/new name.txt\n"
                + "similarity index 100%\n"
                + "rename from old name.txt\n"
                + "rename to new name.txt\n"
                + "diff --git a/src.txt b/copy.txt\n"
                + "copy from src.txt\n"
                + "copy to copy.txt\n"
                + "diff --git a/run.sh b/run.sh\n"
                + "old mode 100644\n"
                + "new mode 100755\n");
        assertEquals(3, files.size());
        assertEquals("old name.txt", files.get(0).oldPath());
        assertEquals("new name.txt", files.get(0).newPath());
        assertEquals("src.txt", files.get(1).oldPath());
        assertEquals("copy.txt", files.get(1).newPath());
        assertEquals("run.sh", files.get(2).oldPath(), "a/P b/P is split exactly in the middle");
        assertEquals("run.sh", files.get(2).newPath());
        assertTrue(files.get(2).oldLines().isEmpty());
    }

    @Test
    void addedAndDeletedBinaryFilesGetTheirMissingSideFromTheModeLine() {
        List<FilePatch> files = PatchParser.parseAllSections("diff --git a/logo.png b/logo.png\n"
                + "new file mode 100644\n"
                + "Binary files /dev/null and b/logo.png differ\n"
                + "diff --git a/old.png b/old.png\n"
                + "deleted file mode 100644\n"
                + "Binary files a/old.png and /dev/null differ\n");
        assertEquals("/dev/null", files.get(0).oldPath());
        assertEquals("logo.png", files.get(0).newPath());
        assertEquals("old.png", files.get(1).oldPath());
        assertEquals("/dev/null", files.get(1).newPath());
    }

    @Test
    void quotedAndUnusualGitHeaderLinesAreSplitWhereTheSecondNameStarts() {
        List<FilePatch> quoted = PatchParser.parseAllSections(
                "diff --git \"a/caf\\303\\251 \\\"x\\\".txt\" \"b/caf\\303\\251 \\\"x\\\".txt\"\nold mode 100644\n");
        assertEquals("café \"x\".txt", quoted.get(0).oldPath());
        assertEquals("café \"x\".txt", quoted.get(0).newPath());

        // Different names on the two sides: split at " b/".
        List<FilePatch> renamed =
                PatchParser.parseAllSections("diff --git a/one.txt b/two.txt\nsimilarity index 90%\n");
        assertEquals("one.txt", renamed.get(0).oldPath());
        assertEquals("two.txt", renamed.get(0).newPath());

        // Only the second name is quoted.
        List<FilePatch> half = PatchParser.parseAllSections("diff --git a/plain.txt \"b/caf\\303\\251.txt\"\n");
        assertEquals("plain.txt", half.get(0).oldPath());
        assertEquals("café.txt", half.get(0).newPath());

        // A header that cannot be split names nothing; the rename lines after it still do.
        List<FilePatch> odd =
                PatchParser.parseAllSections("diff --git something-else\nrename from was.txt\nrename to is.txt\n");
        assertEquals("was.txt", odd.get(0).oldPath());
        assertEquals("is.txt", odd.get(0).newPath());
        assertEquals(List.of(), PatchParser.parseAllSections("diff --git something-else\n"), "nothing names it");
    }

    @Test
    void aGitSectionWithHunksIsOneFileNotTwo() {
        List<FilePatch> files = PatchParser.parseAllSections("diff --git a/f.txt b/f.txt\n"
                + "index 1111111..2222222 100644\n"
                + "--- a/f.txt\n"
                + "+++ b/f.txt\n"
                + "@@ -1 +1 @@\n-old\n+new\n"
                + "diff --git a/g.txt b/g.txt\n"
                + "--- a/g.txt\n"
                + "+++ b/g.txt\n"
                + "@@ -3 +3 @@\n-three\n+THREE\n");
        assertEquals(
                List.of("f.txt", "g.txt"),
                files.stream().map(FilePatch::newPath).toList());
        assertEquals(List.of(3), files.get(1).newLineNumbers());
    }

    @Test
    void aFilePatchBuiltWithoutLineNumbersHasNone() {
        FilePatch built = new FilePatch("a.txt", "a.txt", List.of("x"), List.of("y"), 1, 1, true, false, null, null);
        assertEquals(List.of(), built.oldLineNumbers());
        assertEquals(List.of(), built.newLineNumbers());
        FilePatch shorter = new FilePatch("a.txt", "a.txt", List.of("x"), List.of("y"), 1, 1);
        assertTrue(shorter.oldFinalNewline() && shorter.newFinalNewline(), "both sides end in a newline by default");
    }
}
