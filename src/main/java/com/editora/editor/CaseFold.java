package com.editora.editor;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Full (length-changing) case folding for literal search — what lets a case-insensitive search for
 * {@code STRASSE} find {@code Straße}, and {@code FI} find {@code ﬁ}.
 *
 * <p>{@link String#regionMatches(boolean, int, String, int, int)} folds <b>per character</b>, so it can only
 * match case pairs of equal length: it finds {@code é}↔{@code É} but never {@code ß}↔{@code SS} or
 * {@code ﬁ}↔{@code FI}, because those fold to a different number of characters. (Java's regex engine has the
 * same limit — {@code CASE_INSENSITIVE | UNICODE_CASE} is <em>simple</em> folding, so the regex path misses
 * these too and cannot be fixed the same way; a folded haystack would break user patterns and offsets.)
 *
 * <p><b>The fold is per code point, applied on the fly.</b> The obvious implementation — fold the whole
 * document, search that, map offsets back — needs a folded copy plus an index array, i.e. roughly three
 * times the document in transient allocation <em>per search</em>, on a path the find bar runs as you type.
 * Instead {@link #matchAt} walks the original text and expands one source code point at a time, comparing
 * against the pre-folded query. That allocates nothing, keeps the existing O(n·m) cost, and yields original
 * offsets directly — no index map to get wrong.
 *
 * <p>It also makes match alignment fall out for free: a match must consume <em>whole</em> source code points,
 * so searching {@code S} can never report "half of the ß" as a match (which an offset map over a folded copy
 * has to special-case, and silently gets wrong if it does not).
 *
 * <p>Folding uses {@code toUpperCase(Locale.ROOT)} per code point: upper-casing is where Java exposes the
 * length-changing mappings ({@code ß}→{@code SS}, {@code ﬁ}→{@code FI}), and it unifies both directions since
 * {@code ss}→{@code SS} as well. {@code Locale.ROOT} keeps the Turkish dotted/dotless {@code i} out of it.
 * Per-code-point folding can differ from whole-string folding for the few context-sensitive mappings (e.g.
 * Greek final sigma), which is the deliberate trade for not needing an index map.
 *
 * <p>Pure, so it is unit-tested.
 */
final class CaseFold {

    /**
     * No code point below this expands, so the scan for "does this text need full folding at all?" rejects
     * all ASCII — and therefore essentially all code — with one comparison per character. Verified against
     * the JDK's Unicode tables: exactly 102 code points expand, all in the BMP, spanning U+00DF…U+FB17, and
     * none is ASCII or supplementary.
     */
    private static final char MIN_EXPANDING = 0x00DF; // ß

    private static final char MAX_EXPANDING = 0xFB17; // the last Armenian ligature

    private CaseFold() {}

    /**
     * The expanding-code-point table, built on first use. Only a search whose text or query contains a
     * character in {@code [MIN_EXPANDING, MAX_EXPANDING]} touches it, so an ASCII-only session never pays
     * for it. Deriving it from the running JDK's tables (rather than hardcoding 102 code points) keeps it
     * correct across Unicode version bumps.
     */
    private static final class Table {
        static final BitSet EXPANDING = new BitSet(MAX_EXPANDING + 1);
        static final Map<Character, String> EXPANSIONS = new HashMap<>();

        static {
            for (char c = MIN_EXPANDING; c <= MAX_EXPANDING; c++) {
                String upper = String.valueOf(c).toUpperCase(Locale.ROOT);
                if (upper.length() > 1) {
                    EXPANDING.set(c);
                    EXPANSIONS.put(c, upper);
                }
                if (c == MAX_EXPANDING) {
                    break; // guard the char wrap-around when MAX_EXPANDING is Character.MAX_VALUE
                }
            }
        }
    }

    private static boolean expands(char c) {
        return c >= MIN_EXPANDING && c <= MAX_EXPANDING && Table.EXPANDING.get(c);
    }

    /**
     * Whether {@code s} contains any character whose case fold changes length — i.e. whether a
     * case-insensitive search over it needs the full-folding path at all. False for any ASCII-only string,
     * decided with one comparison per character.
     */
    static boolean mayExpand(String s) {
        if (s == null) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= MIN_EXPANDING && c <= MAX_EXPANDING && Table.EXPANDING.get(c)) {
                return true;
            }
        }
        return false;
    }

    /** The query folded once up front, per code point, to compare against on-the-fly-folded text. */
    static String fold(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            appendFolded(sb, cp);
        }
        return sb.toString();
    }

    private static void appendFolded(StringBuilder sb, int cp) {
        if (cp <= Character.MAX_VALUE && expands((char) cp)) {
            sb.append(Table.EXPANSIONS.get((char) cp));
        } else {
            sb.appendCodePoint(Character.toUpperCase(cp));
        }
    }

    /**
     * If the already-folded {@code foldedQuery} matches {@code text} starting at {@code from}, the match's
     * <b>exclusive end offset in {@code text}</b>; otherwise -1.
     *
     * <p>Walks whole source code points, folding each as it goes, so the returned end is always on a code
     * point boundary and a query can never match part of one character's expansion — searching {@code S}
     * does not match "half of" the {@code SS} that {@code ß} folds to.
     */
    static int matchAt(String text, int from, String foldedQuery) {
        int n = text.length();
        int m = foldedQuery.length();
        int qi = 0;
        int ti = from;
        while (qi < m) {
            if (ti >= n) {
                return -1; // ran out of text mid-query
            }
            char c = text.charAt(ti);
            if (expands(c)) {
                String upper = Table.EXPANSIONS.get(c);
                if (m - qi < upper.length() || !foldedQuery.startsWith(upper, qi)) {
                    // Too little query left to consume the whole expansion ⇒ the query would end inside this
                    // character, which is not a match on it.
                    return -1;
                }
                qi += upper.length();
                ti++;
                continue;
            }
            int cp = text.codePointAt(ti);
            int upperCp = Character.toUpperCase(cp);
            int width = Character.charCount(upperCp);
            if (m - qi < width) {
                return -1;
            }
            if (width == 1) {
                if (foldedQuery.charAt(qi) != (char) upperCp) {
                    return -1;
                }
            } else if (foldedQuery.charAt(qi) != Character.highSurrogate(upperCp)
                    || foldedQuery.charAt(qi + 1) != Character.lowSurrogate(upperCp)) {
                return -1;
            }
            qi += width;
            ti += Character.charCount(cp);
        }
        return ti;
    }
}
