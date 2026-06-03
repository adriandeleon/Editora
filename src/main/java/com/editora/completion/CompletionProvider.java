package com.editora.completion;

import java.util.List;

/**
 * Supplies completions for the word being typed. Injected into {@code EditorBuffer} by the controller
 * (decoupled, like the snippet provider). {@code snippetLang} is the buffer's programming language (for
 * snippet matching); {@code dictLang} is its spell-check language (for dictionary words); {@code prose}
 * gates the dictionary source to prose buffers.
 */
@FunctionalInterface
public interface CompletionProvider {
    List<Completion> complete(String snippetLang, String dictLang, String prefix, boolean prose);
}
