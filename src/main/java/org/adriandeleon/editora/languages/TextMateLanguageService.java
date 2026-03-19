package org.adriandeleon.editora.languages;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class TextMateLanguageService implements LanguageService {
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
        return new LanguageAnalysis(TextMateTokenizer.computeHighlighting(grammar, text == null ? "" : text), List.of());
    }
}

