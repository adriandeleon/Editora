package com.editora.github;

import org.jspecify.annotations.Nullable;

/**
 * A review comment written on lines of a pull request's diff and not sent yet: it goes with the review when that is
 * submitted, as one of its {@code comments}. {@code left} is the base side (GitHub's {@code LEFT}), else the head
 * ({@code RIGHT}); lines are the file's own, 1-based, {@code startLine..line} inclusive.
 */
public record PrLineDraft(String id, String path, boolean left, int startLine, int line, String body) {

    public PrLineDraft {
        startLine = Math.min(startLine, line);
    }

    /** GitHub's name for the side. */
    public String side() {
        return left ? "LEFT" : "RIGHT";
    }

    /** Whether this draft is on {@code line} of {@code path}'s {@code left} side (its last line marks it). */
    public boolean on(String filePath, boolean leftSide, int fileLine) {
        return path.equals(filePath) && left == leftSide && line == fileLine;
    }

    /** A draft with a fresh id. */
    public static PrLineDraft create(String path, boolean left, int startLine, int line, String body) {
        return new PrLineDraft(java.util.UUID.randomUUID().toString(), path, left, startLine, line, body);
    }

    /** This draft with a new body. */
    public PrLineDraft withBody(@Nullable String newBody) {
        return new PrLineDraft(id, path, left, startLine, line, newBody == null ? "" : newBody);
    }
}
