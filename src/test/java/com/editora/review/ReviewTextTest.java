package com.editora.review;

import java.util.List;

import com.editora.config.ReviewAuthor;
import com.editora.config.ReviewThread;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ReviewText}: suggestion blocks, marker labels, and what an agent is asked to fix a thread. */
class ReviewTextTest {

    private final ReviewThreads ops = new ReviewThreads(() -> 1L);

    @Test
    void aSuggestionBlockIsTakenOutOfTheBodyAndPutBackForEditing() {
        ReviewText.Parsed p = ReviewText.parse("Rename it.\n\n```suggestion\nint count = 0;\n```\nThanks.");
        assertEquals("Rename it.\n\nThanks.", p.body());
        assertEquals("int count = 0;", p.suggestion());
        assertNull(ReviewText.parse("No block.").suggestion());
        assertEquals("a\nb", ReviewText.parse("```suggestion\na\nb").suggestion(), "unclosed: to the end");
    }

    @Test
    void theFixPromptNamesTheLinesTheFindingTheCommentsAndHowToResolve() {
        ReviewThreads.Start finding = new ReviewThreads.Start(
                "src/A.java",
                ReviewThread.Side.NEW,
                null,
                ReviewAnchors.capture("a\nb\n", 1, 1),
                ReviewThread.Severity.IMPORTANT,
                "correctness");
        List<ReviewThread> threads =
                ops.start(List.of(), finding, ReviewAuthor.agent("Claude Code"), "Off by one.", "b2");
        threads = ops.reply(threads, threads.get(0).id(), ReviewAuthor.user(), "Agreed.", null);
        ReviewThread t = threads.get(0);
        String p = ReviewText.fixPrompt("/repo/src/A.java", 2, 2, t, "b");
        assertTrue(p.startsWith("Fix this code review finding in /repo/src/A.java, line 2."), p);
        assertTrue(p.contains("Severity: important (correctness)"));
        assertTrue(p.contains("Claude Code wrote:\nOff by one."));
        assertTrue(p.contains("The user wrote:\nAgreed."));
        assertTrue(p.contains("```\nb\n```"), "the lines as they are now");
        assertTrue(p.contains("suggested replacement for those lines:\n```\nb2\n```"));
        assertTrue(p.contains("thread_id \"" + t.id() + "\""));
        String plain = ReviewText.fixPrompt(
                "/r/B.java",
                1,
                3,
                ops.start(
                                List.of(),
                                ReviewThreads.Start.onFile("B.java", ReviewAnchors.capture("x", 0, 0)),
                                ReviewAuthor.user(),
                                "Why?",
                                null)
                        .get(0),
                "x");
        assertTrue(plain.startsWith("Fix this code review comment in /r/B.java, lines 1-3."), plain);
        assertFalse(plain.contains("Severity"));
        assertFalse(plain.contains("suggested replacement"));
    }
}
