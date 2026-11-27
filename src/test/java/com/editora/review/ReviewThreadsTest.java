package com.editora.review;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.editora.config.ReviewAuthor;
import com.editora.config.ReviewComment;
import com.editora.config.ReviewThread;
import com.editora.config.TextAnchor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ReviewThreads}: each change to a list of threads, with a clock the test turns. */
class ReviewThreadsTest {

    private final AtomicLong now = new AtomicLong(100);
    private final ReviewThreads ops = new ReviewThreads(now::get);
    private final TextAnchor anchor = ReviewAnchors.capture("a\nb\nc\n", 1, 1);

    private List<ReviewThread> one() {
        return ops.start(
                List.of(), ReviewThreads.Start.onFile("src/A.java", anchor), ReviewAuthor.user(), "Why b?", null);
    }

    @Test
    void aThreadStartsOpenWithItsFirstComment() {
        ReviewThread t = one().get(0);
        assertEquals(ReviewThread.State.OPEN, t.state());
        assertEquals("src/A.java", t.path());
        assertEquals(ReviewThread.Side.NEW, t.side());
        ReviewComment root = t.root();
        assertEquals("Why b?", root.body());
        assertEquals(ReviewAuthor.Kind.USER, root.author().kind());
        assertEquals(100, t.createdAt());
        assertEquals(0, root.editedAt());
        assertNotEquals(t.id(), root.id());
    }

    @Test
    void anAgentFindingCarriesItsSeverityCategoryAndSuggestion() {
        ReviewThreads.Start finding = new ReviewThreads.Start(
                "src/A.java", ReviewThread.Side.NEW, null, anchor, ReviewThread.Severity.IMPORTANT, "correctness");
        ReviewThread t = ops.start(List.of(), finding, ReviewAuthor.agent("Claude Code"), "Off by one.", "b2")
                .get(0);
        assertEquals(ReviewThread.Severity.IMPORTANT, t.severity());
        assertEquals("correctness", t.category());
        assertEquals("Claude Code", t.root().author().name());
        assertEquals("b2", t.root().suggestion());
    }

    @Test
    void aReplyGoesAtTheEndAndReopensAResolvedThread() {
        List<ReviewThread> threads = one();
        String id = threads.get(0).id();
        now.set(200);
        threads = ops.resolve(threads, id, "fixed in the next commit");
        assertEquals(ReviewThread.State.RESOLVED, threads.get(0).state());
        assertEquals("fixed in the next commit", threads.get(0).stateNote());
        assertEquals(200, threads.get(0).updatedAt());
        now.set(300);
        threads = ops.reply(threads, id, ReviewAuthor.agent("Claude Code"), "Not fixed: line 2 still reads b.", null);
        ReviewThread t = threads.get(0);
        assertEquals(2, t.comments().size());
        assertEquals(ReviewThread.State.OPEN, t.state());
        assertNull(t.stateNote());
        assertEquals(300, t.updatedAt());
    }

    @Test
    void editingMarksTheCommentEditedAndDismissingKeepsTheReason() {
        List<ReviewThread> threads = one();
        ReviewThread t = threads.get(0);
        now.set(150);
        threads = ops.edit(threads, t.id(), t.root().id(), "Why b, not c?", "c");
        assertEquals("Why b, not c?", threads.get(0).root().body());
        assertEquals(150, threads.get(0).root().editedAt());
        assertEquals("c", threads.get(0).root().suggestion());
        threads = ops.dismiss(threads, t.id(), "b is right here");
        assertEquals(ReviewThread.State.DISMISSED, threads.get(0).state());
        threads = ops.reopen(threads, t.id());
        assertEquals(ReviewThread.State.OPEN, threads.get(0).state());
    }

    @Test
    void deletingTheLastCommentDeletesTheThread() {
        List<ReviewThread> threads = one();
        ReviewThread t = threads.get(0);
        threads = ops.reply(threads, t.id(), ReviewAuthor.user(), "second", null);
        threads = ops.deleteComment(threads, t.id(), t.root().id());
        assertEquals(1, threads.size());
        assertEquals("second", threads.get(0).root().body());
        threads = ops.deleteComment(threads, t.id(), threads.get(0).root().id());
        assertTrue(threads.isEmpty());
        assertTrue(ops.delete(one(), one().get(0).id()).size() == 1, "another thread's id: nothing deleted");
    }

    @Test
    void anUnknownIdChangesNothing() {
        List<ReviewThread> threads = one();
        List<ReviewThread> same = ops.resolve(threads, "no-such-thread", null);
        assertSame(threads.get(0), same.get(0));
    }

    @Test
    void aLongBodyIsCut() {
        String huge = "x".repeat(ReviewThreads.MAX_BODY + 10);
        ReviewThread t = ops.start(List.of(), ReviewThreads.Start.onFile("a", anchor), ReviewAuthor.user(), huge, null)
                .get(0);
        assertEquals(ReviewThreads.MAX_BODY, t.root().body().length());
    }

    @Test
    void reanchoringMovesOnlyTheThreadsOnThatFilesNewSide() {
        List<ReviewThread> threads = one();
        ReviewThreads.Start old =
                new ReviewThreads.Start("src/A.java", ReviewThread.Side.OLD, "abc123", anchor, null, null);
        threads = ops.start(threads, old, ReviewAuthor.user(), "on the base", null);
        threads = ops.start(threads, ReviewThreads.Start.onFile("src/B.java", anchor), ReviewAuthor.user(), "B", null);
        List<ReviewThread> moved = ReviewThreads.reanchored(threads, "src/A.java", "new\na\nb\nc\n");
        assertEquals(2, moved.get(0).anchor().line(), "the file's own thread moved down a line");
        assertSame(threads.get(1), moved.get(1), "the base side is another text");
        assertSame(threads.get(2), moved.get(2), "another file");
        assertEquals(1, ReviewThreads.openOn(moved, "src/B.java").size());
    }
}
