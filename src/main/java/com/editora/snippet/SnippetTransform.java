package com.editora.snippet;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.editora.editops.StringCase;

/**
 * A VS Code snippet regex transform — the {@code /regex/format/flags} half of {@code ${1/re/fmt/flags}}.
 * Pure (no toolkit), so it is unit-tested.
 *
 * <p>A transform derives one tab stop's displayed text from another's value: the stop's text is matched
 * against {@code regex} and each match is replaced by {@code format}, with unmatched text copied through.
 * Before this existed the whole construct was parsed and discarded, so a transform rendered as a plain
 * mirror — which is not merely incomplete but <em>wrong</em>: the bundled PowerShell {@code splat} snippet
 * emitted {@code $Get-ItemParams} where it meant {@code $Get_ItemParams}, and PowerShell parses the former
 * as a subtraction.
 *
 * <p>Supported format syntax, matching VS Code's {@code FormatString}:
 * <ul>
 *   <li>group references {@code $1} and {@code ${1}};</li>
 *   <li>case modifiers {@code ${1:/upcase}}, {@code /downcase}, {@code /capitalize}, {@code /camelcase},
 *       {@code /pascalcase}, {@code /snakecase}, {@code /kebabcase};</li>
 *   <li>conditionals {@code ${1:+ifMatched}}, {@code ${1:-else}}, {@code ${1:else}} (shorthand for
 *       {@code :-}) and {@code ${1:?ifMatched:else}};</li>
 *   <li>escapes {@code \\} and {@code \/}, plus {@code \n} / {@code \t} for convenience.</li>
 * </ul>
 *
 * <p>Deliberate deviations, both chosen to keep a snippet expanding rather than failing:
 * <ul>
 *   <li>a reference to a group the pattern does not have resolves to the empty string rather than
 *       throwing (VS Code likewise treats it as undefined);</li>
 *   <li>a malformed transform makes {@link #parseAt} return {@code null}, and the caller falls back to the
 *       previous plain-mirror behaviour.</li>
 * </ul>
 *
 * <p>The four word-based modifiers delegate to {@link StringCase}, which already owns Editora's
 * word-splitting; that keeps them consistent with the {@code edit.case.*} commands, at the cost of
 * tokenising slightly differently from VS Code's {@code /[\p{L}0-9]+/gu} on exotic input.
 */
public final class SnippetTransform {

    /** Longest a transform spec may be before we decline to parse it (a runaway/unterminated body). */
    private static final int MAX_SPEC = 4096;

    private final Pattern pattern;
    private final List<Part> format;
    private final boolean global;

    private SnippetTransform(Pattern pattern, List<Part> format, boolean global) {
        this.pattern = pattern;
        this.format = format;
        this.global = global;
    }

    /** A parsed transform plus the index just past its closing {@code '}'}. */
    public record Parsed(SnippetTransform transform, int end) {}

    /**
     * Parses {@code regex/format/flags}} beginning at {@code from} — the index just past the {@code '/'}
     * that follows the stop number in {@code ${1/…}}. Returns {@code null} when the spec is malformed or
     * the regex will not compile, so the caller can fall back to a plain mirror.
     *
     * <p>Sections are split on <b>unescaped</b> {@code /} rather than by brace matching: a regex may
     * legitimately contain an unbalanced brace (a character class like {@code [}]}), which would send a
     * brace counter off the end of the body.
     */
    public static Parsed parseAt(String s, int from) {
        if (s == null || from < 0 || from > s.length()) {
            return null;
        }
        int regexEnd = findUnescaped(s, from, '/');
        if (regexEnd < 0 || regexEnd - from > MAX_SPEC) {
            return null;
        }
        int formatEnd = findFormatEnd(s, regexEnd + 1);
        if (formatEnd < 0 || formatEnd - regexEnd > MAX_SPEC) {
            return null;
        }
        int close = findUnescaped(s, formatEnd + 1, '}');
        if (close < 0 || close - formatEnd > MAX_SPEC) {
            return null;
        }
        String regex = unescapeSlashes(s.substring(from, regexEnd));
        String fmt = s.substring(regexEnd + 1, formatEnd);
        String flags = s.substring(formatEnd + 1, close);

        Pattern p;
        try {
            p = Pattern.compile(regex, patternFlags(flags));
        } catch (PatternSyntaxException bad) {
            return null;
        }
        return new Parsed(new SnippetTransform(p, parseFormat(fmt), flags.indexOf('g') >= 0), close + 1);
    }

    /** Applies the transform to a tab stop's current text. Never throws. */
    public String apply(String input) {
        String in = input == null ? "" : input;
        Matcher m = pattern.matcher(in);
        StringBuilder out = new StringBuilder(in.length());
        int last = 0;
        while (m.find()) {
            out.append(in, last, m.start());
            for (Part part : format) {
                out.append(part.resolve(m));
            }
            last = m.end();
            if (!global) {
                break;
            }
        }
        out.append(in, last, in.length());
        return out.toString();
    }

    // --- format-string model ---

    private sealed interface Part permits Literal, Group {
        String resolve(Matcher m);
    }

    private record Literal(String text) implements Part {
        @Override
        public String resolve(Matcher m) {
            return text;
        }
    }

    /**
     * A {@code $n} reference, optionally carrying a case modifier or a conditional. {@code ifText} is used
     * when the group matched something, {@code elseText} when it did not; either may be null.
     */
    private record Group(int index, Modifier modifier, String ifText, String elseText) implements Part {
        @Override
        public String resolve(Matcher m) {
            String value = groupOrEmpty(m, index);
            boolean has = !value.isEmpty();
            if (modifier != Modifier.NONE) {
                return has ? modifier.apply(value) : "";
            }
            if (ifText != null && has) {
                return ifText;
            }
            if (elseText != null && !has) {
                return elseText;
            }
            return value;
        }
    }

    /** A group the pattern does not define, or one that did not participate, resolves to empty. */
    private static String groupOrEmpty(Matcher m, int index) {
        if (index < 0 || index > m.groupCount()) {
            return "";
        }
        String g = m.group(index);
        return g == null ? "" : g;
    }

    private enum Modifier {
        NONE,
        UPCASE,
        DOWNCASE,
        CAPITALIZE,
        CAMELCASE,
        PASCALCASE,
        SNAKECASE,
        KEBABCASE;

        String apply(String v) {
            return switch (this) {
                case UPCASE -> v.toUpperCase(Locale.ROOT);
                case DOWNCASE -> v.toLowerCase(Locale.ROOT);
                // VS Code's /capitalize touches only the first character; the rest is left exactly as-is.
                case CAPITALIZE -> capitalizeFirst(v);
                case CAMELCASE -> StringCase.to(StringCase.Style.CAMEL, v);
                case PASCALCASE -> StringCase.to(StringCase.Style.PASCAL, v);
                case SNAKECASE -> StringCase.to(StringCase.Style.SNAKE, v);
                case KEBABCASE -> StringCase.to(StringCase.Style.KEBAB, v);
                case NONE -> v;
            };
        }

        static Modifier byName(String name) {
            return switch (name) {
                case "upcase" -> UPCASE;
                case "downcase" -> DOWNCASE;
                case "capitalize" -> CAPITALIZE;
                case "camelcase" -> CAMELCASE;
                case "pascalcase" -> PASCALCASE;
                case "snakecase" -> SNAKECASE;
                case "kebabcase" -> KEBABCASE;
                default -> NONE;
            };
        }
    }

    /** First code point upper-cased, remainder untouched. Code-point aware so a surrogate pair survives. */
    private static String capitalizeFirst(String s) {
        if (s.isEmpty()) {
            return s;
        }
        int cp = s.codePointAt(0);
        int up = Character.toUpperCase(cp);
        return up == cp ? s : new String(Character.toChars(up)) + s.substring(Character.charCount(cp));
    }

    // --- format-string parsing ---

    private static List<Part> parseFormat(String fmt) {
        List<Part> parts = new ArrayList<>();
        StringBuilder lit = new StringBuilder();
        int i = 0;
        while (i < fmt.length()) {
            char c = fmt.charAt(i);
            if (c == '\\' && i + 1 < fmt.length()) {
                lit.append(unescape(fmt.charAt(i + 1)));
                i += 2;
                continue;
            }
            if (c == '$') {
                int consumed = parseGroupRef(fmt, i, parts, lit);
                if (consumed > 0) {
                    i += consumed;
                    continue;
                }
            }
            lit.append(c);
            i++;
        }
        flush(lit, parts);
        return parts;
    }

    /**
     * Tries to read a {@code $n} / {@code ${n…}} reference at {@code i}. Returns the number of characters
     * consumed, or 0 when this {@code $} is literal text.
     */
    private static int parseGroupRef(String fmt, int i, List<Part> parts, StringBuilder lit) {
        int j = i + 1;
        if (j < fmt.length() && Character.isDigit(fmt.charAt(j))) {
            int k = j;
            while (k < fmt.length() && Character.isDigit(fmt.charAt(k))) {
                k++;
            }
            Integer n = parseIndex(fmt, j, k);
            if (n == null) {
                return 0;
            }
            flush(lit, parts);
            parts.add(new Group(n, Modifier.NONE, null, null));
            return k - i;
        }
        if (j < fmt.length() && fmt.charAt(j) == '{') {
            int close = findUnescaped(fmt, j + 1, '}');
            if (close < 0) {
                return 0;
            }
            Group g = parseBracedGroup(fmt.substring(j + 1, close));
            if (g == null) {
                return 0;
            }
            flush(lit, parts);
            parts.add(g);
            return close + 1 - i;
        }
        return 0;
    }

    /** Parses the inside of a {@code ${…}} reference: {@code n}, {@code n:/mod}, {@code n:+if}, etc. */
    private static Group parseBracedGroup(String body) {
        int k = 0;
        while (k < body.length() && Character.isDigit(body.charAt(k))) {
            k++;
        }
        if (k == 0) {
            return null; // not a group reference
        }
        Integer n = parseIndex(body, 0, k);
        if (n == null) {
            return null;
        }
        if (k == body.length()) {
            return new Group(n, Modifier.NONE, null, null); // ${1}
        }
        if (body.charAt(k) != ':') {
            return null;
        }
        String rest = body.substring(k + 1);
        if (rest.startsWith("/")) {
            return new Group(n, Modifier.byName(rest.substring(1)), null, null); // ${1:/upcase}
        }
        if (rest.startsWith("+")) {
            return new Group(n, Modifier.NONE, unescapeAll(rest.substring(1)), null); // ${1:+if}
        }
        if (rest.startsWith("-")) {
            return new Group(n, Modifier.NONE, null, unescapeAll(rest.substring(1))); // ${1:-else}
        }
        if (rest.startsWith("?")) {
            String body2 = rest.substring(1);
            int sep = findUnescaped(body2, 0, ':');
            if (sep < 0) {
                return null;
            }
            return new Group(
                    n,
                    Modifier.NONE,
                    unescapeAll(body2.substring(0, sep)),
                    unescapeAll(body2.substring(sep + 1))); // ${1:?if:else}
        }
        return new Group(n, Modifier.NONE, null, unescapeAll(rest)); // ${1:else} — shorthand for :-
    }

    /** A group index, or null when the digit run overflows an int (treat the {@code $…} as literal). */
    private static Integer parseIndex(String s, int from, int to) {
        try {
            return Integer.parseInt(s.substring(from, to));
        } catch (NumberFormatException overflow) {
            return null;
        }
    }

    private static void flush(StringBuilder lit, List<Part> parts) {
        if (lit.length() > 0) {
            parts.add(new Literal(lit.toString()));
            lit.setLength(0);
        }
    }

    // --- lexing helpers ---

    /**
     * Index of the {@code '/'} ending the <b>format</b> section, or -1.
     *
     * <p>Not a plain unescaped-character scan: a case modifier carries its own slash
     * ({@code ${1:/upcase}}), so a naive split cuts the format in half and leaves {@code upcase}} as the
     * flags. Group constructs are therefore skipped over by depth.
     */
    private static int findFormatEnd(String s, int from) {
        int depth = 0;
        for (int i = Math.max(0, from); i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '$' && i + 1 < s.length() && s.charAt(i + 1) == '{') {
                depth++;
                i++;
            } else if (c == '}' && depth > 0) {
                depth--;
            } else if (c == '/' && depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /** Index of the first unescaped {@code target} at or after {@code from}, or -1. */
    private static int findUnescaped(String s, int from, char target) {
        for (int i = Math.max(0, from); i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == target) {
                return i;
            }
        }
        return -1;
    }

    /** {@code \/} → {@code /} inside the regex section; every other escape is left for the regex engine. */
    private static String unescapeSlashes(String s) {
        if (s.indexOf('\\') < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                out.append('/');
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String unescapeAll(String s) {
        if (s.indexOf('\\') < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                out.append(unescape(s.charAt(++i)));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static char unescape(char c) {
        return switch (c) {
            case 'n' -> '\n';
            case 't' -> '\t';
            default -> c; // \\ \/ \$ \} … → the character itself
        };
    }

    private static int patternFlags(String flags) {
        int f = 0;
        if (flags.indexOf('i') >= 0) {
            f |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        }
        if (flags.indexOf('m') >= 0) {
            f |= Pattern.MULTILINE;
        }
        if (flags.indexOf('s') >= 0) {
            f |= Pattern.DOTALL;
        }
        return f;
    }
}
