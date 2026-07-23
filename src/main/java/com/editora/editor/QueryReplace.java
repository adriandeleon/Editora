package com.editora.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.editora.editops.PreserveCase;

/**
 * The pure kernel of Emacs {@code query-replace} ({@code M-%}) / {@code query-replace-regexp}
 * ({@code C-M-%}): given the live document text and a search offset, find the next match and compute the
 * text that should replace it — regex group references expanded, and the result optionally recased to the
 * match ({@link PreserveCase}). No toolkit dependency; the interactive session applies the edits and reads
 * the keystrokes.
 *
 * <p>Query-replace is interactive and edits one match at a time, so each replacement shifts the offsets of
 * everything after it. Rather than precompute a plan that the first edit invalidates, {@link #next} is
 * called afresh against the current text after every action — which is also why it is a single-match
 * function, not a whole-buffer one.
 *
 * <p><b>Regex expansion honours surrounding context.</b> The replacement is produced with
 * {@link Matcher#appendReplacement} on the full-text matcher (not by re-matching the matched substring in
 * isolation), so a pattern with a lookbehind/lookahead expands correctly and the JDK's own {@code $}/{@code
 * \} replacement syntax is used verbatim rather than reimplemented here.
 */
public final class QueryReplace {

    /** A query-replace request: what to find, what to replace with, and how to match. */
    public record Spec(
            String query,
            String replacement,
            boolean caseSensitive,
            boolean regex,
            boolean wholeWord,
            boolean preserveCase) {}

    /** One resolved match: its span {@code [start, end)} and the text to put in its place. */
    public record Match(int start, int end, String replacement) {}

    private QueryReplace() {}

    /**
     * The next match at or after {@code from}, with its resolved replacement, or empty when none remains.
     *
     * @throws RuntimeException if the replacement references a regex group the pattern does not have
     *     ({@link Matcher#appendReplacement}); the caller reports it rather than half-applying an edit
     */
    public static Optional<Match> next(String text, int from, Spec spec) {
        if (text == null || spec == null || spec.query() == null || spec.query().isEmpty()) {
            return Optional.empty();
        }
        int start = Math.max(0, Math.min(from, text.length()));
        return spec.regex() ? nextRegex(text, start, spec) : nextLiteral(text, start, spec);
    }

    private static Optional<Match> nextLiteral(String text, int from, Spec spec) {
        for (int[] m : SearchMatcher.matches(text, spec.query(), spec.caseSensitive(), false, spec.wholeWord())) {
            if (m[0] >= from) {
                String matched = text.substring(m[0], m[1]);
                String repl =
                        spec.preserveCase() ? PreserveCase.apply(matched, spec.replacement()) : spec.replacement();
                return Optional.of(new Match(m[0], m[1], repl));
            }
        }
        return Optional.empty();
    }

    private static Optional<Match> nextRegex(String text, int from, Spec spec) {
        Pattern p = SearchMatcher.compileRegex(spec.query(), spec.caseSensitive(), spec.wholeWord());
        if (p == null) {
            return Optional.empty();
        }
        Matcher m = p.matcher(text);
        if (!m.find(from)) {
            return Optional.empty();
        }
        // A fresh matcher with no prior appendReplacement writes text[0, m.start()) followed by the
        // expansion, so the expansion begins exactly m.start() characters into the scratch buffer.
        StringBuffer scratch = new StringBuffer();
        m.appendReplacement(scratch, spec.replacement());
        String expanded = scratch.substring(m.start());
        String repl = spec.preserveCase() ? PreserveCase.apply(m.group(), expanded) : expanded;
        return Optional.of(new Match(m.start(), m.end(), repl));
    }

    /**
     * Every remaining match from {@code from}, each with its replacement, resolved against the
     * <em>original</em> text (no edits applied). Backs the {@code !} "replace all the rest" action, which
     * the caller splices in one edit. A zero-width match advances the scan by one so it cannot loop.
     */
    public static List<Match> planRemaining(String text, int from, Spec spec) {
        List<Match> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        int at = Math.max(0, Math.min(from, text.length()));
        while (at <= text.length()) {
            Optional<Match> next = next(text, at, spec);
            if (next.isEmpty()) {
                break;
            }
            Match m = next.get();
            out.add(m);
            at = m.end() > m.start() ? m.end() : m.end() + 1; // step past a zero-width match
        }
        return out;
    }

    /**
     * The offset to resume scanning from after acting on {@code match}. When the match was replaced pass
     * the replacement length as {@code consumed}; when skipped pass its own width. Guarantees forward
     * progress even for a zero-width match, so the session can never loop.
     */
    public static int advance(Match match, int consumed) {
        int end = match.start() + Math.max(0, consumed);
        return end > match.start() ? end : end + 1;
    }
}
