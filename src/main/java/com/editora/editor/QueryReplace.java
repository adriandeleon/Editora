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
 * called afresh against the current text after every action; {@link #afterReplace} / {@link #afterSkip}
 * say where that next call resumes.
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
     * The next match at or after {@code from}, with its resolved replacement, or empty when none remains
     * (including when {@code from} lies past the end of the text, which is how a session leaves a zero-width
     * match at the very end).
     *
     * @throws RuntimeException if the replacement references a regex group the pattern does not have
     *     ({@link Matcher#appendReplacement}); the caller reports it rather than half-applying an edit
     */
    public static Optional<Match> next(String text, int from, Spec spec) {
        if (unusable(text, from, spec)) {
            return Optional.empty();
        }
        int start = Math.max(0, from);
        return spec.regex() ? nextRegex(text, start, spec) : nextLiteral(text, start, spec);
    }

    private static boolean unusable(String text, int from, Spec spec) {
        return text == null
                || spec == null
                || spec.query() == null
                || spec.query().isEmpty()
                || from > text.length();
    }

    private static Optional<Match> nextLiteral(String text, int from, Spec spec) {
        for (int[] m : SearchMatcher.matches(text, spec.query(), spec.caseSensitive(), false, spec.wholeWord())) {
            if (m[0] >= from) {
                return Optional.of(literalMatch(text, m, spec));
            }
        }
        return Optional.empty();
    }

    private static Match literalMatch(String text, int[] m, Spec spec) {
        String repl = spec.preserveCase()
                ? PreserveCase.apply(text.substring(m[0], m[1]), spec.replacement())
                : spec.replacement();
        return new Match(m[0], m[1], repl);
    }

    private static Optional<Match> nextRegex(String text, int from, Spec spec) {
        List<Match> one = regexMatches(text, from, spec, 1);
        return one.isEmpty() ? Optional.empty() : Optional.of(one.get(0));
    }

    /**
     * Up to {@code limit} regex matches from {@code from}, in <b>one</b> pass of one matcher. Each expansion
     * comes from {@link Matcher#appendReplacement}, which writes the text since the previous match followed
     * by the expansion — so the expansion starts {@code gap} characters into what was just appended, and
     * only the first match copies a prefix of the document.
     *
     * <p>A search that blows the shared backtracking budget, or overflows the stack on a deeply recursive
     * pattern, yields no matches at all: a partial plan would look like a finished "replace all the rest".
     */
    private static List<Match> regexMatches(String text, int from, Spec spec, int limit) {
        Pattern p = SearchMatcher.compileDocumentRegex(spec.query(), spec.caseSensitive(), spec.wholeWord());
        if (p == null) {
            return List.of();
        }
        List<Match> out = new ArrayList<>();
        Matcher m = p.matcher(SearchMatcher.budgetedSequence(text));
        StringBuffer scratch = new StringBuffer();
        int appended = 0; // the matcher's append position: the end of the previous match
        try {
            // find() resumes at the previous match's end, one further after a zero-width match.
            for (boolean found = m.find(from); found; found = m.find()) {
                int gap = m.start() - appended;
                scratch.setLength(0);
                m.appendReplacement(scratch, spec.replacement());
                appended = m.end();
                String expanded = scratch.substring(gap);
                String repl = spec.preserveCase() ? PreserveCase.apply(m.group(), expanded) : expanded;
                out.add(new Match(m.start(), m.end(), repl));
                if (out.size() >= limit) {
                    break;
                }
            }
        } catch (SearchMatcher.MatchBudgetExceededException | StackOverflowError abandoned) {
            return List.of();
        }
        return out;
    }

    /**
     * Every remaining match from {@code from}, each with its replacement, resolved against the
     * <em>original</em> text (no edits applied). Backs the {@code !} "replace all the rest" action, which
     * the caller splices in one edit. Built in a single pass over the text, so it stays linear however many
     * matches there are.
     */
    public static List<Match> planRemaining(String text, int from, Spec spec) {
        if (unusable(text, from, spec)) {
            return new ArrayList<>();
        }
        int at = Math.max(0, from);
        if (spec.regex()) {
            return regexMatches(text, at, spec, Integer.MAX_VALUE);
        }
        List<Match> out = new ArrayList<>();
        for (int[] m : SearchMatcher.matches(text, spec.query(), spec.caseSensitive(), false, spec.wholeWord())) {
            if (m[0] >= at) {
                out.add(literalMatch(text, m, spec));
            }
        }
        return out;
    }

    /**
     * The offset to resume scanning from once {@code match} has been replaced by its replacement: right
     * after the inserted text, so a match that begins there (the next of several adjacent ones, when the
     * replacement is empty) is still offered. After a <em>zero-width</em> match ({@code $}, a lookahead) the
     * scan resumes one character further, because the assertion that matched still holds at that spot and
     * would be offered again for ever.
     */
    public static int afterReplace(Match match) {
        int end = match.start() + match.replacement().length();
        return match.end() > match.start() ? end : end + 1;
    }

    /** The offset to resume scanning from when {@code match} is skipped: its end, one further if zero-width. */
    public static int afterSkip(Match match) {
        return match.end() > match.start() ? match.end() : match.end() + 1;
    }
}
