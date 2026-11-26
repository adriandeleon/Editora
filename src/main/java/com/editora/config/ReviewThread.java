package com.editora.config;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.Nullable;

/**
 * A review thread: the lines it is about and the comments on them. Kept in {@link ReviewStore} per repository, so
 * {@code path} is relative to the repository root, with forward slashes.
 *
 * <p>The {@code anchor} is captured as a Personal Note's is (its text plus a little context), so the thread follows
 * its lines as the file is edited; when they are gone the thread is <em>outdated</em>, which is worked out against
 * the current text and not stored (see {@code com.editora.review.ReviewAnchors}). {@code side} says which text the
 * anchor is in: the file as it is ({@link Side#NEW}), or the base it is compared with ({@link Side#OLD}), named by
 * {@code baseRevision} (a commit id, or {@code null} for the index).
 *
 * <p>An agent's finding carries a {@code severity} and a {@code category}; a person's comment usually has neither.
 * The {@code stateNote} says why a thread was resolved or dismissed. A Jackson-serialized record.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReviewThread(
        String id,
        String path,
        Side side,
        @Nullable String baseRevision,
        TextAnchor anchor,
        State state,
        @Nullable String stateNote,
        @Nullable Severity severity,
        @Nullable String category,
        List<ReviewComment> comments,
        long createdAt,
        long updatedAt) {

    /** Which text the anchor is in. */
    public enum Side {
        /** The file as it is now (a diff's right side). */
        NEW,
        /** The base it is compared with (a diff's left side). */
        OLD
    }

    /** Where a thread stands. */
    public enum State {
        OPEN,
        /** Done with: fixed, or answered. */
        RESOLVED,
        /** Not acted on, with a reason (a finding that is wrong or not worth it); remembered so it isn't raised again. */
        DISMISSED
    }

    /** How much an agent's finding matters, most first. */
    public enum Severity {
        BLOCKER,
        IMPORTANT,
        MINOR,
        NIT
    }

    public ReviewThread {
        id = id == null ? "" : id;
        path = path == null ? "" : path;
        side = side == null ? Side.NEW : side;
        anchor = anchor == null ? new TextAnchor(0, 0, 0, 0, "", "", "") : anchor;
        state = state == null ? State.OPEN : state;
        comments = comments == null ? List.of() : List.copyOf(comments);
    }

    /** The first comment, which started the thread. */
    public @Nullable ReviewComment root() {
        return comments.isEmpty() ? null : comments.get(0);
    }

    public boolean open() {
        return state == State.OPEN;
    }
}
