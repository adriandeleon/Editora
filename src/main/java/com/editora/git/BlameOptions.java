package com.editora.git;

import java.util.ArrayList;
import java.util.List;

/**
 * How blame attributes lines, beyond git's default: {@code ignoreWhitespace} ({@code -w}) looks through
 * commits that only re-indented or re-spaced a line, and {@code detectMoves} ({@code -M -C}) follows lines
 * moved within the file or moved/copied from another file changed in the same commit, so a refactoring that
 * shuffled code does not take the credit for writing it. Part of the blame cache key.
 */
public record BlameOptions(boolean ignoreWhitespace, boolean detectMoves) {

    public static final BlameOptions NONE = new BlameOptions(false, false);

    public List<String> args() {
        List<String> args = new ArrayList<>(3);
        if (ignoreWhitespace) {
            args.add("-w");
        }
        if (detectMoves) {
            args.add("-M");
            args.add("-C");
        }
        return args;
    }

    public BlameOptions withIgnoreWhitespace(boolean on) {
        return new BlameOptions(on, detectMoves);
    }

    public BlameOptions withDetectMoves(boolean on) {
        return new BlameOptions(ignoreWhitespace, on);
    }
}
