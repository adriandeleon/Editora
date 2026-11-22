package com.editora.editor.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.editora.editops.PreserveCase;
import com.editora.text.SearchMatcher;
import org.jspecify.annotations.Nullable;

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

    /**
     * A document read in pieces, so a session reads only as far as its next match (PERF-08, Pauta 6.2.0): an
     * answer in an interactive session must not copy the whole document each time.
     */
    public interface Source {
        int length();

        /** The text of {@code [start, end)}. */
        String text(int start, int end);

        static Source of(
                java.util.function.IntSupplier length, java.util.function.BiFunction<Integer, Integer, String> text) {
            return new Source() {
                @Override
                public int length() {
                    return length.getAsInt();
                }

                @Override
                public String text(int start, int end) {
                    return text.apply(start, end);
                }
            };
        }
    }

    /** Thrown by a {@link Piece} asked for text outside it: the search needs a bigger piece. */
    public static final class NeedMore extends RuntimeException {
        private static final long serialVersionUID = 1L;

        NeedMore() {
            super(null, null, false, false);
        }
    }

    /**
     * A stretch of a document copied on the FX thread, for {@link #nextIn} to run on off it: the document's
     * length is the real one, and reading outside the stretch throws {@link NeedMore}, so the caller copies a
     * stretch twice as long and asks again. The answer is the one the whole document gives.
     */
    public static final class Piece implements Source {
        private final String text;
        private final int base;
        private final int length;

        Piece(String text, int base, int length) {
            this.text = text;
            this.base = base;
            this.length = length;
        }

        @Override
        public int length() {
            return length;
        }

        @Override
        public String text(int start, int end) {
            if (start < base || end > base + text.length()) {
                throw new NeedMore();
            }
            return text.substring(start - base, end - base);
        }
    }

    /**
     * Copies from {@code doc} what a search from {@code from} sees first: the context before it and {@code size}
     * characters after it. FX thread (a short copy); search the piece off it with {@link #nextIn}.
     */
    public static Piece piece(Source doc, int from, int size) {
        int length = doc.length();
        int start = Math.max(0, Math.min(from, length));
        int base = Math.max(0, start - REGEX_CONTEXT);
        int end = (int) Math.min(length, (long) start + Math.max(1, size));
        return new Piece(doc.text(base, end), base, length);
    }

    /** The first piece a session copies past its offset ({@link #piece}). */
    public static final int FIRST_PIECE = 64 * 1024;

    /** The first stretch a literal search reads past the offset; doubled until the match is certain. */
    static final int FIRST_WINDOW = 16 * 1024;

    /** How much text before the search offset a regex sees, for a lookbehind or a word boundary. */
    static final int REGEX_CONTEXT = 1024;

    /** The pieces a regex reads the document in. */
    static final int PAGE = 16 * 1024;

    /**
     * {@link #next(String, int, Spec)} on a document read in pieces, only as far as the answer needs. A literal is
     * searched a stretch at a time from {@code from} on, until the first match starting at or after it ends inside
     * the stretch (or the document ends): the leftmost match at or after {@code from}. A regex runs over the
     * document as it would over the whole text, its characters read a page at a time as the engine asks for them;
     * it sees {@link #REGEX_CONTEXT} characters before {@code from}.
     */
    public static Optional<Match> nextIn(Source doc, int from, Spec spec) {
        int length = doc.length();
        if (spec == null || spec.query() == null || spec.query().isEmpty() || from > length) {
            return Optional.empty();
        }
        int start = Math.max(0, from);
        if (spec.regex()) {
            int base = Math.max(0, start - REGEX_CONTEXT);
            Optional<Match> m = regexIn(new Paged(doc, base, length), start - base, spec);
            return m.map(found -> new Match(found.start() + base, found.end() + base, found.replacement()));
        }
        int base = Math.max(0, start - 1); // the character before: a whole word's boundary
        for (long window = FIRST_WINDOW; ; window *= 2) {
            int end = (int) Math.min(length, start + window);
            boolean whole = end == length;
            String text = doc.text(base, end);
            int[] m = literalIn(text, start - base, spec, whole);
            if (m == null) {
                continue; // the text past the window could change the answer
            }
            return m.length == 0 ? Optional.empty() : Optional.of(literalShifted(text, m, spec, base));
        }
    }

    private static Match literalShifted(String text, int[] m, Spec spec, int base) {
        Match found = literalMatch(text, m, spec);
        return new Match(found.start() + base, found.end() + base, found.replacement());
    }

    /** The first match at or after {@code at}; an empty array for none; null when the window cannot tell. */
    private static int @Nullable [] literalIn(String text, int at, Spec spec, boolean whole) {
        for (int[] m : SearchMatcher.matches(text, spec.query(), spec.caseSensitive(), false, spec.wholeWord())) {
            if (m[0] < at) {
                continue;
            }
            // Its word boundary, or a longer folded match, may lie past the window.
            return !whole && m[1] >= text.length() ? null : m;
        }
        return whole ? new int[0] : null;
    }

    private static Optional<Match> regexIn(Paged text, int at, Spec spec) {
        Pattern p = SearchMatcher.compileDocumentRegex(spec.query(), spec.caseSensitive(), spec.wholeWord());
        if (p == null) {
            return Optional.empty();
        }
        Matcher m = p.matcher(SearchMatcher.budgetedSequence(text));
        try {
            if (!m.find(at)) {
                return Optional.empty();
            }
            // appendReplacement writes the text since the view's start, then the expansion: at most the context and
            // the stretch up to the match, never the document.
            StringBuffer scratch = new StringBuffer();
            int gap = m.start();
            m.appendReplacement(scratch, spec.replacement());
            String expanded = scratch.substring(gap);
            String repl = spec.preserveCase() ? PreserveCase.apply(m.group(), expanded) : expanded;
            return Optional.of(new Match(m.start(), m.end(), repl));
        } catch (SearchMatcher.MatchBudgetExceededException | StackOverflowError abandoned) {
            return Optional.empty();
        }
    }

    /**
     * The document from {@code base} on as a {@link CharSequence} the regex engine reads a page at a time. Two pages
     * are kept, so a lookbehind or a backtrack across a page boundary does not read them again and again.
     */
    private static final class Paged implements CharSequence {
        private final Source doc;
        private final int base;
        private final int end;
        private String page = "";
        private int pageStart = -1;
        private String other = "";
        private int otherStart = -1;

        Paged(Source doc, int base, int end) {
            this.doc = doc;
            this.base = base;
            this.end = end;
        }

        @Override
        public int length() {
            return end - base;
        }

        @Override
        public char charAt(int index) {
            int at = base + index;
            if (pageStart < 0 || at < pageStart || at >= pageStart + page.length()) {
                if (otherStart >= 0 && at >= otherStart && at < otherStart + other.length()) {
                    String p = page;
                    int ps = pageStart;
                    page = other;
                    pageStart = otherStart;
                    other = p;
                    otherStart = ps;
                } else {
                    other = page;
                    otherStart = pageStart;
                    pageStart = at - at % PAGE;
                    page = doc.text(pageStart, Math.min(end, pageStart + PAGE));
                }
            }
            return page.charAt(at - pageStart);
        }

        @Override
        public CharSequence subSequence(int start, int stop) {
            StringBuilder sb = new StringBuilder(stop - start);
            for (int i = start; i < stop; i++) {
                sb.append(charAt(i));
            }
            return sb.toString();
        }

        @Override
        public String toString() {
            return subSequence(0, length()).toString();
        }
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
