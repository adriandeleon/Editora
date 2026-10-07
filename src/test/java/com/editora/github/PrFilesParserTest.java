package com.editora.github;

import java.util.List;

import com.editora.diff.PatchParser.FilePatch;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrFilesParserTest {

    /** What {@code gh pr diff} prints for a pull request above GitHub's diff limits. */
    private static final String TOO_LARGE = """
            could not find pull request diff: HTTP 406: Sorry, the diff exceeded the maximum number of files (300). \
            Consider using 'List pull requests files' API or locally cloning the repository instead. \
            (https://api.github.com/repos/o/r/pulls/1018)
            PullRequest.diff too_large
            """;

    @Test
    void recognisesGitHubRefusingTheDiffForItsSize() {
        assertTrue(PrFilesParser.diffTooLarge(TOO_LARGE));
        assertTrue(
                PrFilesParser.diffTooLarge("HTTP 406: Sorry, the diff exceeded the maximum number of lines (20000)"));
        assertFalse(PrFilesParser.diffTooLarge("HTTP 404: Not Found"));
        assertFalse(PrFilesParser.diffTooLarge("no pull requests found"));
        assertFalse(PrFilesParser.diffTooLarge(null));
    }

    @Test
    void readsEveryPageThatPaginatePrintsBackToBack() {
        String pages = """
                [{"filename":"a.txt","status":"modified","additions":1,"deletions":1,
                  "patch":"@@ -40,3 +40,3 @@\\n ctx\\n-old\\n+new\\n ctx2"}]
                [{"filename":"new.txt","status":"added","additions":2,"deletions":0,"patch":"@@ -0,0 +1,2 @@\\n+one\\n+two"},
                 {"filename":"gone.txt","status":"removed","additions":0,"deletions":1,"patch":"@@ -1 +0,0 @@\\n-bye"}]
                """;
        PrFilesParser.Result result = PrFilesParser.parse(pages);
        List<FilePatch> files = result.files();

        assertEquals(3, files.size(), "both pages");
        assertEquals(0, result.withoutPatch());
        FilePatch a = files.get(0);
        assertEquals("a.txt", a.oldPath());
        assertEquals("a.txt", a.newPath());
        assertEquals(List.of("ctx", "old", "ctx2"), a.oldLines());
        assertEquals(List.of("ctx", "new", "ctx2"), a.newLines());
        assertEquals(List.of(40, 41, 42), a.newLineNumbers(), "the hunk header's numbers, not 1, 2, 3");
        assertEquals("/dev/null", files.get(1).oldPath());
        assertEquals(List.of("one", "two"), files.get(1).newLines());
        assertEquals("/dev/null", files.get(2).newPath());
        assertEquals(PrReviewSummary.statusOf(files.get(1)).letter(), "A");
        assertEquals(PrReviewSummary.statusOf(files.get(2)).letter(), "D");
    }

    @Test
    void aSlurpedArrayOfPagesReadsTheSame() {
        String slurped =
                "[[{\"filename\":\"a\",\"status\":\"modified\"}],[{\"filename\":\"b\",\"status\":\"modified\"}]]";
        assertEquals(2, PrFilesParser.parse(slurped).files().size());
    }

    @Test
    void aFileGitHubSendsNoPatchForIsListedAndCounted() {
        String json = """
                [{"filename":"big.json","status":"modified","additions":9000,"deletions":8000},
                 {"filename":"logo.png","status":"added","additions":0,"deletions":0},
                 {"filename":"new/name.txt","previous_filename":"old/name.txt","status":"renamed","additions":0,"deletions":0}]
                """;
        PrFilesParser.Result result = PrFilesParser.parse(json);

        assertEquals(3, result.files().size());
        assertEquals(1, result.withoutPatch(), "only the file that changed text and came without its hunks");
        FilePatch big = result.files().get(0);
        assertTrue(big.oldLines().isEmpty() && big.newLines().isEmpty());
        assertEquals(9000, big.additions());
        assertEquals(
                "old/name.txt → new/name.txt",
                PrReviewSummary.displayPath(result.files().get(2)));
        assertFalse(result.capped());
    }

    @Test
    void badInputYieldsNothingAndATruncatedLastPageKeepsTheEarlierOnes() {
        assertTrue(PrFilesParser.parse(null).files().isEmpty());
        assertTrue(PrFilesParser.parse("").files().isEmpty());
        assertTrue(PrFilesParser.parse("garbage").files().isEmpty());
        assertTrue(PrFilesParser.parse("{\"message\":\"Not Found\"}").files().isEmpty());
        String cut = "[{\"filename\":\"a\",\"status\":\"modified\"}]\n[{\"filename\":\"b\",\"sta";
        assertEquals(1, PrFilesParser.parse(cut).files().size());
    }
}
