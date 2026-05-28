package com.editora.editor;

import java.util.Collection;
import java.util.Collections;
import java.util.regex.Matcher;

import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

/** Computes RichTextFX style spans for a document using a language's {@link LanguageRules}. */
public final class SyntaxHighlighter {

    private SyntaxHighlighter() {
    }

    /**
     * Computes style spans covering the whole text. Returns null for empty text (RichTextFX cannot
     * build zero-length spans) — callers should skip applying styles in that case.
     */
    public static StyleSpans<Collection<String>> compute(String text, LanguageRules rules) {
        if (text.isEmpty()) {
            return null;
        }
        StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
        if (rules == null || rules.pattern() == null) {
            builder.add(Collections.emptyList(), text.length());
            return builder.create();
        }
        Matcher matcher = rules.pattern().matcher(text);
        int lastEnd = 0;
        while (matcher.find()) {
            String style = rules.styleFor(matcher);
            builder.add(Collections.emptyList(), matcher.start() - lastEnd);
            builder.add(style == null ? Collections.emptyList() : Collections.singleton(style),
                    matcher.end() - matcher.start());
            lastEnd = matcher.end();
        }
        builder.add(Collections.emptyList(), text.length() - lastEnd);
        return builder.create();
    }
}
