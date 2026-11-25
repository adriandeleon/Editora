package com.editora.logviewer;

import java.util.List;

/**
 * Which log each line of a merged log came from ({@link LogMerge}): what its source tags show. Immutable; the
 * line numbers are those of the merged text as a whole, whatever a filter shows of it.
 *
 * @param names the logs' names, by source index
 * @param sources for each line, the index of its log
 */
public record LogSources(List<String> names, int[] sources) {

    public LogSources {
        names = List.copyOf(names);
    }

    /** The source index of line {@code line} (0-based), or -1 past the end. */
    public int sourceOf(int line) {
        return line >= 0 && line < sources.length ? sources[line] : -1;
    }
}
