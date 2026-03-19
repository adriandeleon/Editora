package org.adriandeleon.editora.languages;

import org.fxmisc.richtext.model.StyleSpans;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class TextMateLanguageService implements LanguageService {
    static final int LARGE_FILE_CHARACTER_THRESHOLD = 128 * 1024;
    static final int LARGE_FILE_LINE_THRESHOLD = 2_500;

    private final String id;
    private final String displayName;
    private final TextMateGrammar grammar;
    private final Set<String> fileTypes;

    TextMateLanguageService(String id, String displayName, TextMateGrammar grammar, Set<String> fileTypes) {
        this.id = id;
        this.displayName = displayName;
        this.grammar = grammar;
        this.fileTypes = fileTypes == null ? Set.of() : Set.copyOf(fileTypes);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String displayName() {
        return displayName;
    }

    @Override
    public List<String> fileExtensions() {
        return fileTypes.stream().sorted().toList();
    }

    @Override
    public boolean supports(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }
        String fileName = path.getFileName().toString();
        int extensionIndex = fileName.lastIndexOf('.');
        if (extensionIndex < 0 || extensionIndex == fileName.length() - 1) {
            return false;
        }
        String extension = fileName.substring(extensionIndex + 1).toLowerCase(Locale.ROOT);
        return fileTypes.contains(extension);
    }

    @Override
    public LanguageAnalysis analyze(String text) {
        String normalizedText = text == null ? "" : text;
        return new LanguageAnalysis(TextMateTokenizer.computeHighlighting(grammar, normalizedText), List.of());
    }

    @Override
    public boolean supportsProgressiveHighlighting(String text) {
        return isLargeFile(text);
    }

    @Override
    public StyleSpans<Collection<String>> highlightRange(String text, int start, int end) {
        String normalizedText = text == null ? "" : text;
        int normalizedStart = Math.max(0, Math.min(start, normalizedText.length()));
        int normalizedEnd = Math.max(normalizedStart, Math.min(end, normalizedText.length()));
        return TextMateTokenizer.computeHighlighting(grammar, normalizedText.substring(normalizedStart, normalizedEnd));
    }

    static boolean isLargeFile(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        if (text.length() > LARGE_FILE_CHARACTER_THRESHOLD) {
            return true;
        }

        int lineCount = 1;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '\n' && ++lineCount > LARGE_FILE_LINE_THRESHOLD) {
                return true;
            }
        }
        return false;
    }
}

