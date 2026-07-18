package com.editora.completion;

import java.util.List;

/**
 * Supplies completions for the word being typed. Injected into {@code EditorBuffer} by the controller
 * (decoupled, like the snippet provider). {@code snippetLang} is the buffer's programming language (for
 * snippet matching); {@code dictLang} is its spell-check language (for dictionary words); {@code prose}
 * gates the dictionary source to prose buffers.
 *
 * <p>{@code prefix} is the identifier run before the caret (used for words/keywords); {@code snippetPrefix} is
 * the wider non-whitespace token (used to match snippets whose trigger isn't an identifier — {@code #include},
 * the emmet {@code !}, {@code ?xml}), so those surface in the popup, not just via Tab (#446).
 */
@FunctionalInterface
public interface CompletionProvider {
    List<Completion> complete(String snippetLang, String dictLang, String prefix, String snippetPrefix, boolean prose);
}
