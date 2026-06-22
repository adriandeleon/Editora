package com.editora.editor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.lucene.analysis.hunspell.Hunspell;

/**
 * Per-buffer spell checker: wraps the (shared, cached) {@link Hunspell} engine for a language plus the
 * user's added words and a per-session "ignored" set, and supplies the pure word-splitting used by the
 * overlay. Lookups are cheap; {@link #suggest} is only called on demand (right-click).
 *
 * <p>{@link #wordSpans} and {@link #skip} are pure and unit-tested; spelling itself defers to Hunspell.
 */
public final class SpellChecker {

    private final Set<String> userWords; // shared, persisted (ConfigManager)
    private final Set<String> ignored = new HashSet<>(); // per-session "Ignore" choices
    private volatile boolean userWordsEnabled = true; // honor the personal dictionary (Settings toggle)
    private volatile boolean technicalWordsEnabled = true; // honor the bundled technical dictionary (Settings toggle)
    private volatile String langId;

    public SpellChecker(String langId, Set<String> userWords) {
        this.langId = langId;
        this.userWords = userWords == null ? new HashSet<>() : userWords;
        SpellDictionaries.ensureBuilt(langId, null);
    }

    public void setLanguage(String langId, Runnable onReady) {
        this.langId = langId;
        SpellDictionaries.ensureBuilt(langId, onReady);
    }

    public String getLanguage() {
        return langId;
    }

    /** Whether the active dictionary is loaded; until then nothing is flagged. */
    public boolean ready() {
        return SpellDictionaries.ifReady(langId).isPresent();
    }

    /** Whether the personal dictionary (user words) is honored; off re-flags those words. */
    public void setUserWordsEnabled(boolean enabled) {
        this.userWordsEnabled = enabled;
    }

    public boolean isUserWordsEnabled() {
        return userWordsEnabled;
    }

    /** Whether the bundled technical dictionary is honored; off re-flags those terms. */
    public void setTechnicalWordsEnabled(boolean enabled) {
        this.technicalWordsEnabled = enabled;
    }

    public boolean isTechnicalWordsEnabled() {
        return technicalWordsEnabled;
    }

    /** Adds a word to the per-session ignore set (not persisted). */
    public void ignore(String word) {
        if (word != null && !word.isBlank()) {
            ignored.add(word.toLowerCase(Locale.ROOT));
        }
    }

    /** Whether {@code word} is misspelled. Returns {@code false} for skipped words, user/ignored words,
     *  and when the dictionary isn't ready (so we never flag while loading). */
    public boolean isMisspelled(String word) {
        if (skip(word)) {
            return false;
        }
        String lower = word.toLowerCase(Locale.ROOT);
        if ((userWordsEnabled && userWords.contains(lower)) || ignored.contains(lower)) {
            return false;
        }
        if (technicalWordsEnabled && TechnicalDictionary.contains(lower)) {
            return false; // a bundled technical term (config, async, kubernetes, …)
        }
        Hunspell h = SpellDictionaries.ifReady(langId).orElse(null);
        if (h == null) {
            return false;
        }
        return !h.spell(word);
    }

    /** Up to a handful of suggestions for a misspelled word (empty if the dictionary isn't ready). */
    public List<String> suggest(String word) {
        Hunspell h = SpellDictionaries.ifReady(langId).orElse(null);
        if (h == null || word == null || word.isBlank()) {
            return List.of();
        }
        try {
            List<String> all = h.suggest(word);
            return all.size() > 8 ? all.subList(0, 8) : all;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * Words that should not be spell-checked: too short, containing digits, ALL-CAPS acronyms, and
     * camel/Pascal-case identifiers (an uppercase letter after the first char) — these are almost always
     * code or abbreviations rather than prose.
     */
    public static boolean skip(String word) {
        if (word == null || word.length() < 2) {
            return true;
        }
        boolean allUpper = true;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (Character.isDigit(c)) {
                return true;
            }
            if (i > 0 && Character.isUpperCase(c)) {
                return true; // camelCase / PascalCase identifier
            }
            if (!Character.isUpperCase(c) && Character.isLetter(c)) {
                allUpper = false;
            }
        }
        return allUpper; // ALL-CAPS acronym (e.g. HTTP, NASA)
    }

    /** Wrapping punctuation trimmed from a whitespace token's ends before judging it (quotes, brackets,
     *  sentence punctuation, Markdown emphasis). Notably excludes {@code / \\ @ _ = ~} and {@code -}: those
     *  are part of the token's structure (or intra-word), not edge decoration. */
    private static final String EDGE = "\"'`()[]{}<>,;:.!?*#|…‘’“”";

    /**
     * Whether the letter run {@code [start, end)} is part of a URL, file path, e-mail, or
     * dotted/underscored/colon-joined identifier rather than a standalone prose word — i.e. spell check
     * should leave it alone. Looks at the whole whitespace-bounded token around the run: after trimming
     * wrapping punctuation, if it contains anything other than letters, {@code -}, or {@code '} (a slash,
     * colon, {@code @}, underscore, internal dot, digit, …) the run is structural. Pure / unit-tested.
     *
     * <p>So {@code ./mvnw}, {@code mvnw.cmd}, {@code javafx:run}, {@code https://x/y}, {@code a@b.com},
     * {@code snake_case}, {@code file.txt} are skipped, while {@code well-known}, {@code don't},
     * {@code sentence.} (trailing period trimmed) and ordinary words are still checked.
     */
    public static boolean partOfStructuredToken(String line, int start, int end) {
        if (line == null || start < 0 || end > line.length() || start >= end) {
            return false;
        }
        int ts = start;
        int te = end;
        while (ts > 0 && !Character.isWhitespace(line.charAt(ts - 1))) {
            ts--;
        }
        while (te < line.length() && !Character.isWhitespace(line.charAt(te))) {
            te++;
        }
        while (ts < te && EDGE.indexOf(line.charAt(ts)) >= 0) {
            ts++;
        }
        while (te > ts && EDGE.indexOf(line.charAt(te - 1)) >= 0) {
            te--;
        }
        for (int k = ts; k < te; k++) {
            char c = line.charAt(k);
            if (!Character.isLetter(c) && c != '-' && c != '\'') {
                return true;
            }
        }
        return false;
    }

    /**
     * Splits a line into checkable word spans ({@code [start, end)} offsets) — maximal runs of letters
     * with intra-word apostrophes (so {@code don't} stays one word), trimming leading/trailing
     * apostrophes. Pure; no toolkit. Eligibility (comment/string scope, etc.) is decided by the caller.
     */
    public static List<int[]> wordSpans(String line) {
        List<int[]> spans = new ArrayList<>();
        if (line == null || line.isEmpty()) {
            return spans;
        }
        int n = line.length();
        int i = 0;
        while (i < n) {
            char c = line.charAt(i);
            if (!Character.isLetter(c)) {
                i++;
                continue;
            }
            int start = i;
            int end = i;
            while (end < n) {
                char ch = line.charAt(end);
                if (Character.isLetter(ch)) {
                    end++;
                } else if (ch == '\'' && end + 1 < n && Character.isLetter(line.charAt(end + 1))) {
                    end++; // apostrophe between letters stays in the word
                } else {
                    break;
                }
            }
            // Trim a trailing apostrophe that slipped in (shouldn't, given the look-ahead, but be safe).
            while (end > start && line.charAt(end - 1) == '\'') {
                end--;
            }
            if (end > start) {
                spans.add(new int[] {start, end});
            }
            i = Math.max(end, start + 1);
        }
        return spans;
    }
}
