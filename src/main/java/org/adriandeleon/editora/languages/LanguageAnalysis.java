package org.adriandeleon.editora.languages;

import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

public record LanguageAnalysis(StyleSpans<Collection<String>> highlighting, List<Diagnostic> diagnostics) {
    public static LanguageAnalysis plainText(String text) {
        StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
        builder.add(Collections.emptyList(), text.length());
        return new LanguageAnalysis(builder.create(), List.of());
    }
}

