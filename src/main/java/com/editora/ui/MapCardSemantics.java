package com.editora.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import com.editora.text.SemanticToken;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

/**
 * A code card's colours with the language server's semantic tokens laid over them (Map code browser round 2), as the
 * editor lays them over its TextMate colours: a token's classes win where it stands. The tokens are the file's; the
 * card shows the file from its line {@code firstLine} (a declaration card) or from its first. Pure.
 */
final class MapCardSemantics {

    private MapCardSemantics() {}

    /**
     * {@code base} — the colours of the card's {@code text} — with {@code tokens} over them; {@code base} itself when
     * no token falls inside the coloured text.
     */
    static StyleSpans<Collection<String>> overlay(
            StyleSpans<Collection<String>> base, String text, List<SemanticToken> tokens, int firstLine) {
        if (tokens.isEmpty() || base.length() == 0) {
            return base;
        }
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        List<SemanticToken> sorted = new ArrayList<>(tokens);
        sorted.sort(Comparator.comparingInt(SemanticToken::line).thenComparingInt(SemanticToken::startChar));
        StyleSpansBuilder<Collection<String>> spans = new StyleSpansBuilder<>();
        int at = 0;
        int limit = base.length();
        boolean any = false;
        for (SemanticToken token : sorted) {
            int line = token.line() - firstLine;
            if (line < 0 || line >= starts.size()) {
                continue;
            }
            int lineEnd = line + 1 < starts.size() ? starts.get(line + 1) - 1 : text.length();
            int start = starts.get(line) + token.startChar();
            int end = Math.min(start + token.length(), Math.min(lineEnd, limit));
            if (start < at || start >= end) {
                continue; // overlapping an earlier token, past its line, or past the coloured text
            }
            if (start > at) {
                spans.add(List.of(), start - at);
            }
            spans.add(List.of(token.cssClasses().trim().split("\\s+")), end - start);
            at = end;
            any = true;
        }
        if (!any) {
            return base;
        }
        if (at < limit) {
            spans.add(List.of(), limit - at);
        }
        return base.overlay(spans.create(), (lex, semantic) -> semantic.isEmpty() ? lex : semantic);
    }
}
