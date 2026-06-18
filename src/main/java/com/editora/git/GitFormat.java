package com.editora.git;

/**
 * Small pure formatters for displaying Git data — extracted from {@code MainController} so they're testable
 * without the toolkit. Used for status messages, diff titles, and the blame "Annotate" column.
 */
public final class GitFormat {

    private GitFormat() {}

    /** The abbreviated (7-char) commit hash, or {@code ""} for a null hash; shorter hashes pass through. */
    public static String shortHash(String hash) {
        return hash == null ? "" : hash.substring(0, Math.min(7, hash.length()));
    }

    /** The author's first name (the part before the first space) for the compact blame column. */
    public static String shortAuthor(String author) {
        if (author == null) {
            return "";
        }
        String trimmed = author.strip();
        int sp = trimmed.indexOf(' ');
        return sp > 0 ? trimmed.substring(0, sp) : trimmed;
    }
}
