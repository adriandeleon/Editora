package org.adriandeleon.editora.languages;

import org.fxmisc.richtext.model.StyleSpans;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

public interface LanguageService {
    String id();

    String displayName();

    default List<String> fileExtensions() {
        return List.of();
    }

    boolean supports(Path path);

    LanguageAnalysis analyze(String text);

    default boolean supportsProgressiveHighlighting(String text) {
        return false;
    }

    default StyleSpans<Collection<String>> highlightRange(String text, int start, int end) {
        String normalizedText = text == null ? "" : text;
        int normalizedStart = Math.max(0, Math.min(start, normalizedText.length()));
        int normalizedEnd = Math.max(normalizedStart, Math.min(end, normalizedText.length()));
        return LanguageAnalysis.plainText(normalizedText.substring(normalizedStart, normalizedEnd)).highlighting();
    }
}

