package com.editora.editor;

/**
 * Whether a language's snippets are in memory, and how to wait for them: they are read off the FX thread
 * (PERF-01, Pauta 6.0.1), so a Tab pressed before they are holds itself until they land instead of being decided
 * without them ({@code BufferSnippets.deferUntilSnippetsLoaded}). A buffer with none wired treats every language as
 * loaded.
 */
public interface SnippetLoading {

    boolean loaded(String language);

    /** Runs {@code then} on the FX thread once {@code language}'s snippets are loaded. */
    void whenLoaded(String language, Runnable then);
}
