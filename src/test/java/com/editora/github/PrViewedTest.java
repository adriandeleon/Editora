package com.editora.github;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link PrViewed}: the calls it builds and what it reads from GitHub's answers. */
class PrViewedTest {

    private static final String URL = "https://github.com/octo/app/pull/7";

    @Test
    void theReadAsksForTheIdTheFilesAndTheLastReviewedCommit() {
        GhInvocation read = PrViewed.read(URL, 7);
        String args = String.join(" ", read.args());
        assertTrue(args.contains("owner=octo") && args.contains("name=app") && args.contains("number=7"), args);
        assertTrue(args.contains("viewerViewedState") && args.contains("viewerLatestReview"), args);
        assertNull(PrViewed.read("not a url", 7));
    }

    @Test
    void theAnswerSaysWhatIsViewedAndWhetherTheHeadMovedSinceTheReview() {
        PrViewed.State s = PrViewed.parse("""
                {"data":{"repository":{"pullRequest":{"id":"PR_1","viewerLatestReview":{"commit":{"oid":"abc1234"}},
                 "files":{"nodes":[{"path":"a.txt","viewerViewedState":"VIEWED"},
                                   {"path":"b.txt","viewerViewedState":"DISMISSED"},
                                   {"path":"c.txt","viewerViewedState":"UNVIEWED"}]}}}}}
                """);
        assertEquals("PR_1", s.prId());
        assertTrue(s.viewed("a.txt"));
        assertFalse(s.viewed("b.txt"), "viewed, then changed: not viewed");
        assertEquals(1, s.viewedCount(List.of("a.txt", "b.txt", "c.txt", "d.txt")));
        assertTrue(s.changedSinceReview("def5678"));
        assertFalse(s.changedSinceReview("ABC1234"), "the same commit, any case");
        assertFalse(s.changedSinceReview(""));

        PrViewed.State never = PrViewed.parse(
                "{\"data\":{\"repository\":{\"pullRequest\":{\"id\":\"PR_2\",\"viewerLatestReview\":null,"
                        + "\"files\":{\"nodes\":[]}}}}}");
        assertFalse(never.changedSinceReview("def5678"), "never reviewed: nothing 'since'");
        assertEquals(PrViewed.State.NONE, PrViewed.parse("{\"errors\":[{}]}"));
        assertEquals(PrViewed.State.NONE, PrViewed.parse("not json"));
    }

    @Test
    void markingNamesTheFileAndThePullRequest() {
        String on = String.join(
                " ", PrViewed.mark(URL, 7, "PR_1", "src/A.java", true).args());
        assertTrue(on.contains("markFileAsViewed") && on.contains("id=PR_1") && on.contains("path=src/A.java"), on);
        String off = String.join(
                " ", PrViewed.mark(URL, 7, "PR_1", "src/A.java", false).args());
        assertTrue(off.contains("unmarkFileAsViewed"), off);
        assertNull(PrViewed.mark(URL, 7, "", "a", true), "no pull request id read yet");
    }

    @Test
    void theCompareTakesOnlyCommitIdsAndItsAnswerFeedsTheFilesParser() {
        GhInvocation c = PrViewed.compare(URL, 7, "abc1234", "def5678");
        assertEquals(
                "repos/octo/app/compare/abc1234...def5678",
                c.args().get(c.args().size() - 1));
        assertNull(PrViewed.compare(URL, 7, "abc;rm", "def5678"), "not a commit id: no call");
        assertNull(PrViewed.compare(URL, 7, "", "def5678"));
        String json = "{\"status\":\"diverged\",\"files\":[{\"filename\":\"a.txt\",\"status\":\"modified\","
                + "\"additions\":1,\"deletions\":1,\"patch\":\"@@ -1 +1 @@\\n-x\\n+y\"}]}";
        assertEquals("diverged", PrViewed.compareStatus(json));
        assertEquals(1, PrFilesParser.parse(PrViewed.compareFiles(json)).files().size());
        assertEquals("", PrViewed.compareStatus("nope"));
    }
}
