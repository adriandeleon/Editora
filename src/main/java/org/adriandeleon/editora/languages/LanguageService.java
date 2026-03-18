package org.adriandeleon.editora.languages;

import java.nio.file.Path;

public interface LanguageService {
    String id();

    String displayName();

    boolean supports(Path path);

    LanguageAnalysis analyze(String text);
}

