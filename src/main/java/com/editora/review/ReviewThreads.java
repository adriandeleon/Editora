package com.editora.review;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

import com.editora.config.ReviewAuthor;
import com.editora.config.ReviewComment;
import com.editora.config.ReviewThread;
import com.editora.config.TextAnchor;
import org.jspecify.annotations.Nullable;

/**
 * What can be done to review threads, as pure functions from one list of threads to the next: start one, reply,
 * edit or delete a comment, resolve, dismiss, reopen. Each returns a new list (the store keeps whole lists), leaves
 * the order alone, and stamps {@code updatedAt} with the clock it was given. An id that is not there changes nothing.
 */
public final class ReviewThreads {

    /** The longest comment body kept: a review is not a place for a pasted log. */
    public static final int MAX_BODY = 20_000;

    private final LongSupplier clock;

    public ReviewThreads(LongSupplier clock) {
        this.clock = clock;
    }

    /** With the wall clock. */
    public static ReviewThreads now() {
        return new ReviewThreads(System::currentTimeMillis);
    }

    /** What a new thread is about, besides its first comment. */
    public record Start(
            String path,
            ReviewThread.Side side,
            @Nullable String baseRevision,
            TextAnchor anchor,
            ReviewThread.@Nullable Severity severity,
            @Nullable String category) {

        /** A person's comment on lines of the file as it is. */
        public static Start onFile(String path, TextAnchor anchor) {
            return new Start(path, ReviewThread.Side.NEW, null, anchor, null, null);
        }
    }

    /** {@code threads} with a new thread at the end, started by {@code author}'s {@code body}. */
    public List<ReviewThread> start(
            List<ReviewThread> threads, Start what, ReviewAuthor author, String body, @Nullable String suggestion) {
        long t = clock.getAsLong();
        ReviewComment first = new ReviewComment(newId(), author, cap(body), t, 0, suggestion);
        ReviewThread thread = new ReviewThread(
                newId(),
                what.path(),
                what.side(),
                what.baseRevision(),
                what.anchor(),
                ReviewThread.State.OPEN,
                null,
                what.severity(),
                what.category(),
                List.of(first),
                t,
                t);
        List<ReviewThread> out = new ArrayList<>(threads);
        out.add(thread);
        return out;
    }

    /** A reply at the end of thread {@code threadId}. Replying to a resolved or dismissed thread reopens it. */
    public List<ReviewThread> reply(
            List<ReviewThread> threads,
            String threadId,
            ReviewAuthor author,
            String body,
            @Nullable String suggestion) {
        long t = clock.getAsLong();
        return map(threads, threadId, th -> {
            List<ReviewComment> comments = new ArrayList<>(th.comments());
            comments.add(new ReviewComment(newId(), author, cap(body), t, 0, suggestion));
            return with(th, ReviewThread.State.OPEN, null, comments, t);
        });
    }

    /** Comment {@code commentId}'s new body and suggestion. */
    public List<ReviewThread> edit(
            List<ReviewThread> threads, String threadId, String commentId, String body, @Nullable String suggestion) {
        long t = clock.getAsLong();
        return map(threads, threadId, th -> {
            List<ReviewComment> comments = new ArrayList<>();
            for (ReviewComment c : th.comments()) {
                comments.add(c.id().equals(commentId) ? c.edited(cap(body), suggestion, t) : c);
            }
            return with(th, th.state(), th.stateNote(), comments, t);
        });
    }

    /** Without comment {@code commentId}; deleting a thread's last comment deletes the thread. */
    public List<ReviewThread> deleteComment(List<ReviewThread> threads, String threadId, String commentId) {
        long t = clock.getAsLong();
        List<ReviewThread> out = new ArrayList<>();
        for (ReviewThread th : threads) {
            if (!th.id().equals(threadId)) {
                out.add(th);
                continue;
            }
            List<ReviewComment> comments = new ArrayList<>(th.comments());
            comments.removeIf(c -> c.id().equals(commentId));
            if (!comments.isEmpty()) {
                out.add(with(th, th.state(), th.stateNote(), comments, t));
            }
        }
        return out;
    }

    /** Without thread {@code threadId}. */
    public List<ReviewThread> delete(List<ReviewThread> threads, String threadId) {
        List<ReviewThread> out = new ArrayList<>(threads);
        out.removeIf(th -> th.id().equals(threadId));
        return out;
    }

    /** Thread {@code threadId} resolved, with an optional note (what fixed it). */
    public List<ReviewThread> resolve(List<ReviewThread> threads, String threadId, @Nullable String note) {
        return setState(threads, threadId, ReviewThread.State.RESOLVED, note);
    }

    /** Thread {@code threadId} dismissed, with the reason it is not acted on. */
    public List<ReviewThread> dismiss(List<ReviewThread> threads, String threadId, String reason) {
        return setState(threads, threadId, ReviewThread.State.DISMISSED, reason);
    }

    /** Thread {@code threadId} open again. */
    public List<ReviewThread> reopen(List<ReviewThread> threads, String threadId) {
        return setState(threads, threadId, ReviewThread.State.OPEN, null);
    }

    /** Each thread re-anchored where its lines are in {@code text} now (for those on {@code path}'s new side). */
    public static List<ReviewThread> reanchored(List<ReviewThread> threads, String path, String text) {
        List<ReviewThread> out = new ArrayList<>(threads.size());
        for (ReviewThread th : threads) {
            if (th.path().equals(path) && th.side() == ReviewThread.Side.NEW) {
                ReviewAnchors.Located at = ReviewAnchors.locate(th, text);
                out.add(at.moved() ? withAnchor(th, at.anchor()) : th);
            } else {
                out.add(th);
            }
        }
        return out;
    }

    /** The threads {@code keep} accepts, in order. */
    public static List<ReviewThread> filter(List<ReviewThread> threads, Predicate<ReviewThread> keep) {
        return threads.stream().filter(keep).toList();
    }

    /** The open threads on {@code path}. */
    public static List<ReviewThread> openOn(List<ReviewThread> threads, String path) {
        return filter(threads, th -> th.open() && th.path().equals(path));
    }

    private List<ReviewThread> setState(
            List<ReviewThread> threads, String threadId, ReviewThread.State state, @Nullable String note) {
        long t = clock.getAsLong();
        return map(threads, threadId, th -> with(th, state, note, th.comments(), t));
    }

    private static List<ReviewThread> map(
            List<ReviewThread> threads, String threadId, java.util.function.UnaryOperator<ReviewThread> change) {
        List<ReviewThread> out = new ArrayList<>(threads.size());
        for (ReviewThread th : threads) {
            out.add(th.id().equals(threadId) ? change.apply(th) : th);
        }
        return out;
    }

    private static ReviewThread with(
            ReviewThread th,
            ReviewThread.State state,
            @Nullable String note,
            List<ReviewComment> comments,
            long updatedAt) {
        return new ReviewThread(
                th.id(),
                th.path(),
                th.side(),
                th.baseRevision(),
                th.anchor(),
                state,
                note,
                th.severity(),
                th.category(),
                comments,
                th.createdAt(),
                updatedAt);
    }

    private static ReviewThread withAnchor(ReviewThread th, TextAnchor anchor) {
        return new ReviewThread(
                th.id(),
                th.path(),
                th.side(),
                th.baseRevision(),
                anchor,
                th.state(),
                th.stateNote(),
                th.severity(),
                th.category(),
                th.comments(),
                th.createdAt(),
                th.updatedAt());
    }

    private static String cap(String body) {
        return body.length() > MAX_BODY ? body.substring(0, MAX_BODY) : body;
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }
}
