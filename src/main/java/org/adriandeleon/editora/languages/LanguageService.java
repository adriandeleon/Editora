package org.adriandeleon.editora.languages;

import java.nio.file.Path;
import java.util.List;

public interface LanguageService {
    String id();

    String displayName();

    default List<String> fileExtensions() {
        return List.of();
    }

    boolean supports(Path path);

    LanguageAnalysis analyze(String text);
}

