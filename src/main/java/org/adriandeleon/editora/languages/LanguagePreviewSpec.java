package org.adriandeleon.editora.languages;

import java.nio.file.Path;

public record LanguagePreviewSpec(String displayName, String description, Path samplePath, String sampleText) {
    public LanguagePreviewSpec {
        displayName = displayName == null ? "Language" : displayName;
        description = description == null ? "" : description;
        samplePath = samplePath == null ? Path.of("preview.txt") : samplePath;
        sampleText = sampleText == null ? "" : sampleText;
    }

    @Override
    public String toString() {
        return displayName;
    }
}

