package com.editora.diff;

import java.util.List;

import com.editora.diff.PatchParser.FilePatch;
import com.editora.git.GitFileStatus;
import com.editora.github.PrReviewSummary;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A pull-request review lists every file the diff touches: a pure rename, a mode-only change and a binary
 * file have no hunks, and used to vanish from the list (and from "N files changed").
 */
class PatchParserSectionsTest {

    /** A real {@code git diff}: {@code git diff --stat} reports 5 files changed. */
    private static final String DIFF = """
            diff --git a/blob.bin b/blob.bin
            new file mode 100644
            index 0000000..233c526
            Binary files /dev/null and b/blob.bin differ
            diff --git "a/caf\\303\\251.txt" "b/caf\\303\\251.txt"
            index 587be6b..975fbec 100644
            --- "a/caf\\303\\251.txt"
            +++ "b/caf\\303\\251.txt"
            @@ -1 +1 @@
            -x
            +y
            diff --git a/old-name.txt b/new-name.txt
            similarity index 100%
            rename from old-name.txt
            rename to new-name.txt
            diff --git a/plain.txt b/plain.txt
            index 814f4a4..879de50 100644
            --- a/plain.txt
            +++ b/plain.txt
            @@ -1,2 +1,2 @@
             one
            -two
            +TWO
            diff --git a/run.sh b/run.sh
            old mode 100644
            new mode 100755
            """;

    @Test
    void everyFileOfTheDiffGetsARow() {
        List<PrReviewSummary.FileRow> rows = PrReviewSummary.rows(PatchParser.parseAllSections(DIFF));

        assertEquals(
                List.of("blob.bin", "café.txt", "old-name.txt → new-name.txt", "plain.txt", "run.sh"),
                rows.stream().map(PrReviewSummary.FileRow::displayPath).toList());
        assertEquals(
                List.of(
                        GitFileStatus.ADDED,
                        GitFileStatus.MODIFIED,
                        GitFileStatus.RENAMED,
                        GitFileStatus.MODIFIED,
                        GitFileStatus.MODIFIED),
                rows.stream().map(PrReviewSummary.FileRow::status).toList());
    }

    @Test
    void hunklessSectionsCarryNoLinesAndTextOnesKeepTheirs() {
        List<FilePatch> files = PatchParser.parseAllSections(DIFF);

        assertTrue(files.get(0).oldLines().isEmpty() && files.get(0).newLines().isEmpty(), "binary");
        assertEquals(List.of("x"), files.get(1).oldLines());
        assertEquals(List.of("y"), files.get(1).newLines());
        assertTrue(files.get(2).newLines().isEmpty(), "pure rename");
        assertEquals(List.of("one", "TWO"), files.get(3).newLines());
        assertEquals(1, files.get(3).additions());
    }

    @Test
    void theSectionHeaderIsSplitExactlyWhenThePathContainsSpaces() {
        List<FilePatch> files = PatchParser.parseAllSections(
                "diff --git a/my b/file.sh b/my b/file.sh\nold mode 100644\nnew mode 100755\n");

        assertEquals(1, files.size());
        assertEquals("my b/file.sh", files.get(0).oldPath());
        assertEquals("my b/file.sh", files.get(0).newPath());
    }

    @Test
    void aDeletedBinaryIsADeletion() {
        List<FilePatch> files = PatchParser.parseAllSections(
                "diff --git a/old.bin b/old.bin\ndeleted file mode 100644\nindex 233c526..0000000\nBinary files a/old.bin and /dev/null differ\n");

        assertEquals(GitFileStatus.DELETED, PrReviewSummary.statusOf(files.get(0)));
    }

    @Test
    void thePlainParserStillSkipsHunklessSectionsButDecodesQuotedLabels() {
        List<FilePatch> files = PatchParser.parse(DIFF);

        assertEquals(2, files.size(), "the .patch viewer shows only what it can diff");
        assertEquals("café.txt", files.get(0).oldPath());
        assertEquals("café.txt", files.get(0).newPath());
    }
}
