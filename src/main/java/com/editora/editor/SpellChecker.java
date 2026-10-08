package com.editora.editor;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.lucene.analysis.hunspell.Hunspell;

/**
 * Per-buffer spell checker: wraps the (shared, cached) {@link Hunspell} engine for a language plus the
 * user's added words and a per-session "ignored" set, and supplies the pure word-splitting used by the
 * overlay. Lookups are cheap; {@link #suggest} is only called on demand (right-click).
 *
 * <p>{@link #wordSpans}, {@link #checkableWords} and {@link #skip} are pure and unit-tested; spelling itself
 * defers to Hunspell.
 */
public final class SpellChecker {

    /**
     * How a line is cut into tokens before its words are judged. The difference is only in what the angle
     * brackets mean: wrapping punctuation in prose and code, tag delimiters in HTML (so the text between
     * two tags is its own token), and label delimiters in Typst (so {@code <fig-1>} is never prose).
     */
    public enum Syntax {
        PLAIN,
        HTML,
        TYPST
    }

    /**
     * Words the user chose to ignore, for the rest of this run of the application and in every buffer and
     * window: "Ignore" on a word in one tab used to leave the same word squiggled in the next one. Never
     * persisted — a word worth keeping is added to the dictionary instead.
     */
    private static final Set<String> SESSION_IGNORED = ConcurrentHashMap.newKeySet();

    /** A whitespace-bounded token longer than this is data (a hash, a minified blob), never a word. */
    static final int MAX_TOKEN = 128;

    private final Set<String> userWords; // shared, persisted (ConfigManager)
    private final Set<String> ignored; // "Ignore" choices: the session-wide set unless a test supplies one
    private volatile boolean userWordsEnabled = true; // honor the personal dictionary (Settings toggle)
    private volatile boolean technicalWordsEnabled = true; // honor the bundled technical dictionary (Settings toggle)
    private volatile String langId;

    public SpellChecker(String langId, Set<String> userWords) {
        this(langId, userWords, SESSION_IGNORED);
    }

    /** As above with an explicit ignore set, so a test does not write to the session-wide one. */
    SpellChecker(String langId, Set<String> userWords, Set<String> ignored) {
        this.langId = langId;
        this.userWords = userWords == null ? new HashSet<>() : userWords;
        this.ignored = ignored;
        // Deliberately NOT ensureBuilt() here: every EditorBuffer constructs a SpellChecker, so building from
        // the ctor spent ~200 ms of startup CPU + ~1 MB retained per language even with spell check OFF.
        // setSpellCheckEnabled(true) and setLanguage() both already trigger the build when it's actually needed.
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

    /**
     * Stops flagging {@code word} for the rest of the session, in every buffer (the ignore set is shared and
     * not persisted). Returns whether the word was newly ignored, so the caller knows to repaint.
     */
    public boolean ignore(String word) {
        String key = canonical(word);
        return !key.isEmpty() && ignored.add(key);
    }

    /**
     * The form a word is stored under in the user dictionary and the ignore set, and looked up by: typographic
     * apostrophes as ASCII, composed accents (NFC), lower case, and without a trailing possessive
     * {@code 's}. Storing the raw text instead made "Add to Dictionary" a no-op for {@code Editora’s}: the
     * lookup normalized the apostrophe and never found what had been written. Pure.
     */
    public static String canonical(String word) {
        if (word == null) {
            return "";
        }
        String w = compose(normalizeApostrophes(word.strip())).toLowerCase(Locale.ROOT);
        return w.endsWith("'s") && w.length() > 2 ? w.substring(0, w.length() - 2) : w;
    }

    /** Whether {@code word} is misspelled. Returns {@code false} for skipped words, user/ignored words,
     *  and when the dictionary isn't ready (so we never flag while loading). */
    public boolean isMisspelled(String word) {
        if (skip(word) || foreignScript(word)) {
            return false;
        }
        // The dictionaries (and user words) spell "don't" with an ASCII apostrophe, but macOS/iOS
        // auto-substitute U+2019 — normalize so "don’t" isn't reported as a misspelling. Likewise a
        // decomposed "café" (e + U+0301, what macOS file names and some pasted text carry) is composed.
        String normalized = compose(normalizeApostrophes(word));
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (known(lower)) {
            return false;
        }
        // "kubernetes's", "middlewares", "adrianx's": the possessive or plural of a word the user or the
        // technical list already vouches for. Each form used to need adding separately.
        String stem = stem(lower);
        if (stem != null && known(stem)) {
            return false;
        }
        if (stem != null && stem.endsWith("e") && lower.endsWith("es") && known(stem.substring(0, stem.length() - 1))) {
            return false; // "regexes" → "regex"
        }
        Hunspell h = SpellDictionaries.ifReady(langId).orElse(null);
        if (h == null) {
            return false;
        }
        if (h.spell(normalized)) {
            return false;
        }
        // English borrows accented words whole ("café", "résumé", "naïve") and its dictionaries list only
        // the plain spelling; the accented one is not a mistake.
        if (langId != null && langId.startsWith("en")) {
            String plain = stripDiacritics(normalized);
            return plain.equals(normalized) || !h.spell(plain);
        }
        return true;
    }

    /** A word the user added, chose to ignore, or that the bundled technical list contains. */
    private boolean known(String lower) {
        return (userWordsEnabled && userWords.contains(lower))
                || ignored.contains(lower)
                || (technicalWordsEnabled && TechnicalDictionary.contains(lower));
    }

    /**
     * {@code lower} without a possessive {@code 's} or a plural {@code s}, or null when it has neither. The
     * plural is only taken off a stem of three letters or more that does not itself end in {@code s}, so
     * "class" and "is" are left alone. Pure.
     */
    static String stem(String lower) {
        int n = lower.length();
        if (n > 2 && lower.endsWith("'s")) {
            return lower.substring(0, n - 2);
        }
        if (n > 3 && lower.charAt(n - 1) == 's' && lower.charAt(n - 2) != 's' && lower.charAt(n - 2) != '\'') {
            return lower.substring(0, n - 1);
        }
        return null;
    }

    /**
     * Whether {@code word} is written in a script the bundled dictionaries do not cover. Every one of them is
     * a Latin-alphabet language, so a run of Chinese, Cyrillic, Greek, Arabic or kana can only ever be
     * "unknown" — it is another language, not a misspelling, and squiggling all of it is noise. Pure.
     */
    static boolean foreignScript(String word) {
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (c < 0x250) {
                continue; // Basic Latin … Latin Extended-B: the common case, no table lookup
            }
            if (Character.isLetter(c) && Character.UnicodeScript.of(c) != Character.UnicodeScript.LATIN) {
                return true;
            }
        }
        return false;
    }

    /** {@code s} with combining accents composed (NFC); returns {@code s} itself when it has none. Pure. */
    static String compose(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (isCombiningMark(s.charAt(i))) {
                return Normalizer.normalize(s, Normalizer.Form.NFC);
            }
        }
        return s;
    }

    /** {@code s} with its accents removed ({@code café} → {@code cafe}); {@code s} itself when it is ASCII. */
    static String stripDiacritics(String s) {
        boolean ascii = true;
        for (int i = 0; i < s.length() && ascii; i++) {
            ascii = s.charAt(i) < 0x80;
        }
        if (ascii) {
            return s;
        }
        String decomposed = Normalizer.normalize(s, Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            if (!isCombiningMark(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** A combining accent: part of the letter before it, not a break in the word. */
    private static boolean isCombiningMark(char c) {
        return c >= 0x300 && Character.getType(c) == Character.NON_SPACING_MARK;
    }

    /** Replaces the typographic apostrophes editors auto-substitute with the ASCII {@code '} the dictionaries
     *  actually contain, so {@code don’t} matches {@code don't}. Pure. */
    static String normalizeApostrophes(String s) {
        if (s == null || (s.indexOf('’') < 0 && s.indexOf('‘') < 0)) {
            return s;
        }
        return s.replace('’', '\'').replace('‘', '\'');
    }

    /** An apostrophe that can sit inside a word — ASCII plus the typographic ones auto-substituted on
     *  macOS/iOS ({@code don’t}). */
    private static boolean isApostrophe(char c) {
        return c == '\'' || c == '’' || c == '‘';
    }

    /** A hyphen/dash that joins words in prose. Includes the em/en dashes editors auto-substitute for
     *  {@code --}/{@code -}, plus the soft hyphen — none of which make a token "structural". */
    private static boolean isDash(char c) {
        return c == '-'
                || c == '‐' // hyphen
                || c == '‑' // non-breaking hyphen
                || c == '‒' // figure dash
                || c == '–' // en dash
                || c == '—' // em dash
                || c == '−' // minus sign
                || c == '­'; // soft hyphen
    }

    /** A character that ends a whitespace-bounded token. {@link Character#isWhitespace} is NOT enough: it
     *  reports {@code false} for NBSP (U+00A0), which is pervasive in pasted text and required by French
     *  typography — without this the words either side of an NBSP merge into one "structural" token and are
     *  never checked. A comma ends one too: {@code recieve,seperate} (a missing space) is two words, and a
     *  comma is never part of a path, a URL or an identifier. In HTML so do the angle brackets, which is what
     *  makes the text between two tags a token of its own. */
    private static boolean isTokenBreak(char c, Syntax syntax) {
        return Character.isWhitespace(c)
                || c == ','
                || Character.isSpaceChar(c)
                || (syntax == Syntax.HTML && (c == '<' || c == '>'));
    }

    /**
     * Up to eight suggestions for a misspelled word (empty if the dictionary isn't ready), best first. May
     * take as long as Hunspell's own limit (about a quarter of a second for some long French and Spanish
     * words), so call it off the FX thread — Hunspell is thread-safe.
     */
    public List<String> suggest(String word) {
        Hunspell h = SpellDictionaries.ifReady(langId).orElse(null);
        if (h == null || word == null || word.isBlank()) {
            return List.of();
        }
        try {
            String normalized = compose(normalizeApostrophes(word));
            return rankSuggestions(normalized, h.suggest(normalized), 8);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * Hunspell's suggestions with the junk taken out and the likeliest first, capped at {@code max}. Pure.
     *
     * <p>Dropped: a "split the word in two" suggestion with a fragment under three letters ({@code untill} →
     * {@code until l}, {@code occured} → {@code occur ed}); {@code alot} → {@code a lot} is kept. Promoted,
     * in Hunspell's order: a suggestion that differs from the word by one doubled or undoubled letter or by
     * two swapped neighbours, the commonest slips by far — {@code coment} offered {@code comment} fourth,
     * behind {@code cement} and {@code moment}.
     */
    static List<String> rankSuggestions(String word, List<String> raw, int max) {
        List<String> likely = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        String lower = word.toLowerCase(Locale.ROOT);
        for (String s : raw) {
            if (s == null || s.isBlank() || splitJunk(s) || likely.contains(s) || rest.contains(s)) {
                continue;
            }
            (commonSlip(lower, s.toLowerCase(Locale.ROOT)) ? likely : rest).add(s);
        }
        likely.addAll(rest);
        return likely.size() > max ? List.copyOf(likely.subList(0, max)) : likely;
    }

    /** A multi-word suggestion with a fragment shorter than three letters that is not a real one-letter word. */
    private static boolean splitJunk(String suggestion) {
        if (suggestion.indexOf(' ') < 0) {
            return false;
        }
        for (String part : suggestion.split(" ")) {
            if (part.length() < 3 && !part.equals("a") && !part.equals("I")) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code b} is {@code a} with one letter doubled or undoubled, or two neighbours swapped. */
    private static boolean commonSlip(String a, String b) {
        int n = a.length();
        int m = b.length();
        if (n == m) {
            int i = 0;
            while (i < n && a.charAt(i) == b.charAt(i)) {
                i++;
            }
            return i < n - 1
                    && a.charAt(i) == b.charAt(i + 1)
                    && a.charAt(i + 1) == b.charAt(i)
                    && a.regionMatches(i + 2, b, i + 2, n - i - 2);
        }
        if (Math.abs(n - m) != 1) {
            return false;
        }
        String shorter = n < m ? a : b;
        String longer = n < m ? b : a;
        int i = 0;
        while (i < shorter.length() && shorter.charAt(i) == longer.charAt(i)) {
            i++;
        }
        // longer has one extra letter at i; it must repeat a neighbour, and the rest must line up.
        boolean doubled = (i > 0 && longer.charAt(i) == longer.charAt(i - 1))
                || (i + 1 < longer.length() && longer.charAt(i) == longer.charAt(i + 1));
        return doubled && shorter.regionMatches(i, longer, i + 1, shorter.length() - i);
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

    /** Wrapping punctuation trimmed from a token's ends before judging it: quotes (the low and angled ones
     *  German and French open with included), brackets, sentence punctuation — the inverted marks that open a
     *  Spanish question or exclamation too — and Markdown emphasis ({@code *bold*}, {@code _emphasis_},
     *  {@code ~~strike~~}). Notably excludes {@code / \\ @ =} and {@code -}: those are part of the token's
     *  structure (or intra-word), not edge decoration. An underscore or tilde <em>inside</em> the token still
     *  makes it structural ({@code snake_case}, {@code ~/notes}). */
    private static final String EDGE = "\"'`()[]{}<>,;:.!?*#|…‘’“”«»¿¡„‚‹›‟_~";

    /** Documentation tags whose next token names a parameter, type or symbol rather than being prose. */
    private static final Set<String> NAME_TAGS = Set.of(
            "@param",
            "@throws",
            "@exception",
            "@see",
            "@link",
            "@linkplain",
            "@property",
            "@typedef",
            "@template",
            "@type",
            "@arg",
            "@argument",
            "@tparam",
            "@raises",
            "@value",
            "@uses",
            "@provides",
            "@code",
            "@literal");

    private static boolean isEdge(char c, Syntax syntax) {
        return EDGE.indexOf(c) >= 0 && !(syntax == Syntax.TYPST && (c == '<' || c == '>'));
    }

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
     *
     * <p>This judges one run in isolation and rescans its token to do so. A whole line goes through
     * {@link #checkableWords}, which judges each token once.
     */
    public static boolean partOfStructuredToken(String line, int start, int end) {
        if (line == null || start < 0 || end > line.length() || start >= end) {
            return false;
        }
        int ts = start;
        int te = end;
        while (ts > 0 && !isTokenBreak(line.charAt(ts - 1), Syntax.PLAIN)) {
            ts--;
        }
        while (te < line.length() && !isTokenBreak(line.charAt(te), Syntax.PLAIN)) {
            te++;
        }
        return structured(line, ts, te, Syntax.PLAIN);
    }

    /**
     * Whether the token {@code [ts, te)} is structural rather than prose: over-long, an option flag
     * ({@code -rn}, {@code --verbose}), or holding anything but letters, dashes and apostrophes once its
     * wrapping punctuation is trimmed.
     */
    private static boolean structured(String line, int ts, int te, Syntax syntax) {
        if (te - ts > MAX_TOKEN) {
            return true; // no word is this long, and scanning it again per word is what cost 430 ms
        }
        while (ts < te && isEdge(line.charAt(ts), syntax)) {
            ts++;
        }
        while (te > ts && isEdge(line.charAt(te - 1), syntax)) {
            te--;
        }
        if (ts < te && line.charAt(ts) == '-') {
            return true; // a command-line option, not a hyphenated word
        }
        for (int k = ts; k < te; k++) {
            char c = line.charAt(k);
            if (!Character.isLetter(c) && !isDash(c) && !isApostrophe(c) && !isCombiningMark(c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The spans of {@code line}'s words that are worth looking up: every letter run of every token that is
     * not structural (see {@link #partOfStructuredToken}), minus the name that follows a documentation tag
     * such as {@code @param}. Pure.
     *
     * <p>One pass over the line, each token judged once. The overlay used to ask
     * {@link #partOfStructuredToken} per misspelled word, which rescanned that word's whole token: on a
     * 60 KB line with no whitespace that was a 60 KB scan per word, about 430 ms per keystroke.
     */
    public static List<int[]> checkableWords(String line, Syntax syntax) {
        List<int[]> spans = new ArrayList<>();
        if (line == null || line.isEmpty()) {
            return spans;
        }
        int n = line.length();
        int i = 0;
        boolean nameFollows = false; // the next token is the name a documentation tag introduces
        while (i < n) {
            if (isTokenBreak(line.charAt(i), syntax)) {
                i++;
                continue;
            }
            int ts = i;
            while (i < n && !isTokenBreak(line.charAt(i), syntax)) {
                i++;
            }
            if (nameFollows) {
                // "@param {string} name": the type in braces is not the name; the token after it is.
                nameFollows = line.charAt(ts) == '{';
                continue;
            }
            if (line.charAt(ts) == '@' || (i - ts > 1 && line.charAt(ts) == '{' && line.charAt(ts + 1) == '@')) {
                int from = line.charAt(ts) == '{' ? ts + 1 : ts;
                nameFollows = NAME_TAGS.contains(line.substring(from, i));
                continue; // a tag is structural either way
            }
            if (!structured(line, ts, i, syntax)) {
                wordRuns(line, ts, i, spans);
            }
        }
        return spans;
    }

    /**
     * Splits a line into checkable word spans ({@code [start, end)} offsets) — maximal runs of letters
     * with intra-word apostrophes (so {@code don't} stays one word), trimming leading/trailing
     * apostrophes. Pure; no toolkit. Eligibility (comment/string scope, etc.) is decided by the caller.
     */
    public static List<int[]> wordSpans(String line) {
        List<int[]> spans = new ArrayList<>();
        if (line != null && !line.isEmpty()) {
            wordRuns(line, 0, line.length(), spans);
        }
        return spans;
    }

    /** Appends the word spans of {@code line[from, to)} to {@code spans}. */
    private static void wordRuns(String line, int from, int to, List<int[]> spans) {
        int n = to;
        int i = from;
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
                if (Character.isLetter(ch) || isCombiningMark(ch)) {
                    end++; // a combining accent belongs to the letter before it (decomposed "café")
                } else if (isApostrophe(ch) && end + 1 < n && Character.isLetter(line.charAt(end + 1))) {
                    end++; // apostrophe between letters stays in the word (incl. the typographic "don’t")
                } else {
                    break;
                }
            }
            // Trim a trailing apostrophe that slipped in (shouldn't, given the look-ahead, but be safe).
            while (end > start && isApostrophe(line.charAt(end - 1))) {
                end--;
            }
            if (end > start) {
                spans.add(new int[] {start, end});
            }
            i = Math.max(end, start + 1);
        }
    }
}
