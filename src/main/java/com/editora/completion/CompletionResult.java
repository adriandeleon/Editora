package com.editora.completion;

import java.util.List;

/** A server list retains its completeness so prefix edits need not make another round trip. */
public record CompletionResult(List<Completion> items, boolean incomplete) {
    public static final CompletionResult EMPTY = new CompletionResult(List.of(), false);

    public CompletionResult {
        items = List.copyOf(items);
    }
}
