package com.editora.editor;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.core.grammar.IStateStack;
import org.eclipse.tm4e.core.grammar.IToken;
import org.eclipse.tm4e.core.grammar.ITokenizeLineResult;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

/**
 * Computes RichTextFX style spans for a whole document using a TextMate {@link IGrammar}.
 *
 * <p>The document is tokenized line by line, carrying the grammar's {@link IStateStack} across line
 * boundaries so multi-line constructs (block comments, heredocs, fenced code) highlight correctly.
 * Each token's TextMate scope list is reduced to a single CSS style class (see
 * {@link #styleForScopes}); the classes are themed in {@code styles/syntax.css}.
 */
public final class TextMateHighlighter {

    /** Zero means "no per-line timeout" to tm4e — we tokenize lazily/debounced anyway. */
    private static final Duration NO_TIMEOUT = Duration.ZERO;

    private TextMateHighlighter() {
    }

    /**
     * Style spans covering the whole text. Returns {@code null} for empty text or a null grammar
     * (RichTextFX cannot build zero-length spans) — callers should skip applying styles then.
     */
    public static StyleSpans<Collection<String>> compute(String text, IGrammar grammar) {
        if (text == null || text.isEmpty() || grammar == null) {
            return null;
        }
        // Adjacent runs with the same style are merged before they reach the builder: we collapse
        // many TextMate scopes onto a few coarse classes, so a single line yields long stretches of
        // identical (or empty) styling. RichTextFX materializes one Text node per span, so emitting
        // every token as its own span balloons the node count and makes layout/scrolling crawl.
        SpanMerger spans = new SpanMerger();
        IStateStack state = null;
        int pos = 0;
        int length = text.length();
        while (true) {
            int newline = text.indexOf('\n', pos);
            int lineEnd = newline < 0 ? length : newline;
            String line = text.substring(pos, lineEnd);
            // Some grammars contain rules tm4e can't parse (malformed captures) or regexes the joni
            // backend rejects (variable-length look-behind, etc.). Tokenizing such a line throws; we
            // must never let that escape onto the JavaFX thread, so degrade that line to plain text
            // and carry the last good state forward.
            try {
                // Pass the newline so end-of-line ($) anchors and line-ending rules match as expected.
                ITokenizeLineResult<IToken[]> result = grammar.tokenizeLine(line + "\n", state, NO_TIMEOUT);
                state = result.getRuleStack();
                addLineSpans(spans, line.length(), result.getTokens());
            } catch (Exception | LinkageError e) {
                spans.add(null, line.length());
            }
            if (newline < 0) {
                break;
            }
            spans.add(null, 1); // the '\n' itself
            pos = newline + 1;
        }
        return spans.build();
    }

    private static void addLineSpans(SpanMerger spans, int lineLength, IToken[] tokens) {
        int last = 0;
        for (IToken token : tokens) {
            int start = Math.min(token.getStartIndex(), lineLength);
            int end = Math.min(token.getEndIndex(), lineLength); // clamp the synthetic '\n' away
            if (end <= start) {
                continue;
            }
            if (start > last) {
                spans.add(null, start - last);
            }
            spans.add(styleForScopes(token.getScopes()), end - start);
            last = end;
        }
        if (lineLength > last) {
            spans.add(null, lineLength - last);
        }
    }

    /**
     * Accumulates style spans, coalescing consecutive runs that share the same style class (including
     * runs with no style) into a single span before handing them to the {@link StyleSpansBuilder}.
     */
    private static final class SpanMerger {
        private final StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
        private String current; // style class of the open run, or null for unstyled
        private int length;     // accumulated length of the open run

        /** Append {@code len} characters styled with {@code style} (null = unstyled). */
        void add(String style, int len) {
            if (len <= 0) {
                return;
            }
            if (length == 0) {
                current = style;
                length = len;
            } else if (java.util.Objects.equals(style, current)) {
                length += len;
            } else {
                flush();
                current = style;
                length = len;
            }
        }

        private void flush() {
            if (length > 0) {
                builder.add(current == null ? Collections.emptyList() : List.of(current), length);
                length = 0;
            }
        }

        StyleSpans<Collection<String>> build() {
            flush();
            return builder.create();
        }
    }

    /**
     * Reduces a TextMate scope list (least-specific first) to one CSS style class, or {@code null}
     * for unstyled text. The most specific scope wins, so we scan from the end.
     */
    static String styleForScopes(List<String> scopes) {
        if (scopes == null) {
            return null;
        }
        for (int i = scopes.size() - 1; i >= 0; i--) {
            String style = classify(scopes.get(i));
            if (style != null) {
                return style;
            }
        }
        return null;
    }

    /** Maps a single TextMate scope to a token category, checking the most specific prefixes first. */
    private static String classify(String scope) {
        if (scope.startsWith("comment")) {
            return "comment";
        }
        if (scope.startsWith("constant.numeric")) {
            return "number";
        }
        if (scope.startsWith("constant.character.escape") || scope.startsWith("constant.other.placeholder")) {
            return "escape";
        }
        if (scope.startsWith("constant.language")) {
            return "constant";
        }
        if (scope.startsWith("string.regexp")) {
            return "regexp";
        }
        if (scope.startsWith("string")) {
            return "string";
        }
        // Annotations/decorators before generic storage/keyword rules.
        if (scope.contains("annotation") || scope.startsWith("meta.decorator") || scope.startsWith("entity.name.function.decorator")) {
            return "annotation";
        }
        if (scope.startsWith("keyword.operator")) {
            return "operator";
        }
        if (scope.startsWith("keyword")) {
            return "keyword";
        }
        if (scope.startsWith("storage")) {
            return "keyword";
        }
        if (scope.startsWith("entity.name.function") || scope.startsWith("support.function")
                || scope.startsWith("meta.function-call")) {
            return "function";
        }
        if (scope.startsWith("entity.name.tag")) {
            return "tag";
        }
        if (scope.startsWith("entity.other.attribute-name")) {
            return "attribute";
        }
        if (scope.startsWith("entity.name.section") || scope.startsWith("markup.heading")) {
            return "heading";
        }
        if (scope.startsWith("entity.name.type") || scope.startsWith("entity.name.class")
                || scope.startsWith("entity.name.namespace") || scope.startsWith("entity.other.inherited-class")
                || scope.startsWith("support.type") || scope.startsWith("support.class")) {
            return "type";
        }
        if (scope.startsWith("variable.language")) {
            return "keyword";
        }
        if (scope.startsWith("support.constant")) {
            return "constant";
        }
        if (scope.startsWith("variable")) {
            return "variable";
        }
        if (scope.startsWith("support")) {
            return "type";
        }
        if (scope.startsWith("markup.bold")) {
            return "bold";
        }
        if (scope.startsWith("markup.italic")) {
            return "italic";
        }
        if (scope.startsWith("markup.underline.link") || scope.startsWith("markup.link")
                || scope.startsWith("string.other.link")) {
            return "link";
        }
        if (scope.startsWith("markup.inline.raw") || scope.startsWith("markup.raw")
                || scope.startsWith("markup.fenced_code")) {
            return "code";
        }
        if (scope.startsWith("invalid")) {
            return "invalid";
        }
        return null;
    }
}
