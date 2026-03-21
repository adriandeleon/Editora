package org.adriandeleon.editora.languages;

/**
 * Represents a foldable region in a document, identified by its 0-based start and end line indices.
 * Both endpoints are inclusive, and {@code endLine} must be strictly greater than {@code startLine}.
 */
public record FoldRange(int startLine, int endLine) {
    public FoldRange {
        if (startLine < 0) {
            throw new IllegalArgumentException("startLine must be >= 0, got: " + startLine);
        }
        if (endLine <= startLine) {
            throw new IllegalArgumentException("endLine must be > startLine, got endLine=" + endLine + " startLine=" + startLine);
        }
    }

    /** Number of lines hidden when this range is collapsed (endLine - startLine). */
    public int foldedLineCount() {
        return endLine - startLine;
    }
}

