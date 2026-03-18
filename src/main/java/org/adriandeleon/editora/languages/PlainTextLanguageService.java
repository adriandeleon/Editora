package org.adriandeleon.editora.languages;

import java.nio.file.Path;

public final class PlainTextLanguageService implements LanguageService {
    public static final PlainTextLanguageService INSTANCE = new PlainTextLanguageService();

    private PlainTextLanguageService() {
    }

    @Override
    public String id() {
        return "plain-text";
    }

    @Override
    public String displayName() {
        return "Plain Text";
    }

    @Override
    public boolean supports(Path path) {
        return path == null;
    }

    @Override
    public LanguageAnalysis analyze(String text) {
        return LanguageAnalysis.plainText(text);
    }
}

