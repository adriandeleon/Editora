package com.editora.github;

import java.util.List;

import com.editora.diff.PatchParser.FilePatch;
import com.editora.git.GitFileStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PrReviewSummaryTest {

    private static FilePatch fp(String oldPath, String newPath, int adds, int dels) {
        return new FilePatch(oldPath, newPath, List.of(), List.of(), adds, dels);
    }

    @Test
    void classifiesStatusFromSidesPresent() {
        assertEquals(GitFileStatus.ADDED, PrReviewSummary.statusOf(fp("/dev/null", "new.txt", 5, 0)));
        assertEquals(GitFileStatus.ADDED, PrReviewSummary.statusOf(fp("", "new.txt", 5, 0)));
        assertEquals(GitFileStatus.DELETED, PrReviewSummary.statusOf(fp("gone.txt", "/dev/null", 0, 5)));
        assertEquals(GitFileStatus.DELETED, PrReviewSummary.statusOf(fp("gone.txt", "", 0, 5)));
        assertEquals(GitFileStatus.RENAMED, PrReviewSummary.statusOf(fp("old.txt", "new.txt", 1, 1)));
        assertEquals(GitFileStatus.MODIFIED, PrReviewSummary.statusOf(fp("same.txt", "same.txt", 2, 3)));
    }

    @Test
    void displayPathShowsArrowOnlyForRename() {
        assertEquals("new.txt", PrReviewSummary.displayPath(fp("", "new.txt", 1, 0)));
        assertEquals("same.txt", PrReviewSummary.displayPath(fp("same.txt", "same.txt", 1, 1)));
        assertEquals("old.txt → new.txt", PrReviewSummary.displayPath(fp("old.txt", "new.txt", 1, 1)));
        assertEquals("gone.txt", PrReviewSummary.displayPath(fp("gone.txt", "/dev/null", 0, 3)));
    }

    @Test
    void effectivePathPrefersNewSide() {
        assertEquals("new.txt", PrReviewSummary.effectivePath(fp("old.txt", "new.txt", 1, 1)));
        assertEquals("gone.txt", PrReviewSummary.effectivePath(fp("gone.txt", "/dev/null", 0, 3)));
    }

    @Test
    void descriptionCollapse() {
        String shortBody = "line1\nline2";
        assertEquals(false, PrReviewSummary.isLongDescription(shortBody, 6, 500));
        assertEquals(false, PrReviewSummary.isLongDescription(null, 6, 500));

        String manyLines = "a\nb\nc\nd\ne\nf\ng\nh";
        org.junit.jupiter.api.Assertions.assertTrue(PrReviewSummary.isLongDescription(manyLines, 6, 500));
        assertEquals("a\nb\nc\nd\ne\nf", PrReviewSummary.collapseDescription(manyLines, 6, 500));

        String longLine = "x".repeat(600);
        org.junit.jupiter.api.Assertions.assertTrue(PrReviewSummary.isLongDescription(longLine, 6, 500));
        assertEquals(500, PrReviewSummary.collapseDescription(longLine, 6, 500).length());
    }

    @Test
    void rowsAndTotals() {
        List<FilePatch> files =
                List.of(fp("", "a.txt", 10, 0), fp("b.txt", "b.txt", 3, 4), fp("c.txt", "/dev/null", 0, 7));
        List<PrReviewSummary.FileRow> rows = PrReviewSummary.rows(files);
        assertEquals(3, rows.size());
        assertEquals(13, PrReviewSummary.totalAdditions(rows));
        assertEquals(11, PrReviewSummary.totalDeletions(rows));
        assertEquals(GitFileStatus.ADDED, rows.get(0).status());
        assertEquals("a.txt", rows.get(0).path());
    }
}
