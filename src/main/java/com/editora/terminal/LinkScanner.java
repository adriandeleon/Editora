package com.editora.terminal;

import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * Finds the link under a position on a line of terminal text.
 *
 * <p><b>One position, not one line.</b> The obvious API scans a whole line for every link on it,
 * and this deliberately does not: a path only becomes a link if it names a file that exists, and
 * asking the filesystem is the caller's job. Scanning a line would mean either a stat per token per
 * line — on the hover path, which fires with every mouse move — or reporting matches that turn out
 * not to be links. Examining only the token under the pointer costs one stat, no matter how much
 * text is on screen.
 *
 * <p>URLs are recognised by their scheme, from a fixed allowlist. Everything that is not a URL is
 * offered as a <em>candidate</em> path with no shape test at all, because the shape tests one might
 * write — must contain a slash, must have an extension — throw away the two most useful cases,
 * clicking a bare filename out of {@code ls} output and a directory name out of a path. Existence
 * is a far better filter than any pattern: prose words do not name files, and the ones that do are
 * exactly the ones worth clicking.
 */
public final class LinkScanner {

    /**
     * Schemes we will open.
     *
     * <p>An allowlist, never "anything before a colon". Terminal output is untrusted text — it is
     * whatever a program printed — and a registered URL handler is a program on this machine, so
     * {@code javascript:}, {@code data:} and any {@code app://} handler are a click away from being
     * a way to run something. These six are the ones with an unsurprising meaning.
     */
    public static final Set<String> SCHEMES = Set.of("http", "https", "ftp", "ftps", "file", "mailto");

    /** Sentence punctuation that a URL at the end of a sentence collects and does not own. */
    private static final String TRAILING_PUNCTUATION = ".,;:!?'\"";

    private static final String OPENERS = "([{<\"'";

    private LinkScanner() {}

    /**
     * The link at {@code index}, or null when there is nothing there.
     *
     * @param text one logical line — already joined across any soft wrap by the caller, since a URL
     *     that wraps at the right edge is one link and not two
     * @param index a character index into it
     */
    public static @Nullable LinkMatch at(String text, int index) {
        if (text == null || index < 0 || index >= text.length()) return null;
        if (Character.isWhitespace(text.charAt(index))) return null;

        int start = index;
        int end = index;
        while (start > 0 && !Character.isWhitespace(text.charAt(start - 1))) start--;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) end++;

        while (start < end && OPENERS.indexOf(text.charAt(start)) >= 0) start++;
        end = trimTrailing(text, start, end);
        // The pointer was on the punctuation either side, not on the link.
        if (index < start || index >= end) return null;
        if (start >= end) return null;

        String token = text.substring(start, end);
        String scheme = schemeOf(token);
        if (scheme != null) {
            return new LinkMatch(LinkMatch.Kind.URL, token, start, end, token, 0, 0);
        }
        return path(token, start, end);
    }

    /**
     * Drops trailing characters the link does not own.
     *
     * <p>Two rules, and the second is the one people notice: a closing bracket is kept when the
     * token opened it — Wikipedia's {@code …/Foo_(disambiguation)} is a real URL and cutting it at
     * the first {@code )} produces a link that 404s — and dropped when it did not, which is the
     * {@code (see https://example.com)} case.
     */
    private static int trimTrailing(String text, int start, int end) {
        while (end > start) {
            char last = text.charAt(end - 1);
            if (TRAILING_PUNCTUATION.indexOf(last) >= 0) {
                end--;
                continue;
            }
            char opener = openerFor(last);
            if (opener != 0 && !balanced(text, start, end, opener, last)) {
                end--;
                continue;
            }
            break;
        }
        return end;
    }

    private static char openerFor(char closer) {
        return switch (closer) {
            case ')' -> '(';
            case ']' -> '[';
            case '}' -> '{';
            case '>' -> '<';
            default -> 0;
        };
    }

    /** Whether the span opens the bracket it ends with as many times as it closes it. */
    private static boolean balanced(String text, int start, int end, char opener, char closer) {
        int depth = 0;
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (c == opener) depth++;
            else if (c == closer) depth--;
        }
        return depth >= 0;
    }

    /** The token's scheme when it has one we will open, else null. */
    private static @Nullable String schemeOf(String token) {
        int colon = token.indexOf(':');
        if (colon <= 0 || colon + 1 >= token.length()) return null;
        String scheme = token.substring(0, colon).toLowerCase(java.util.Locale.ROOT);
        for (int i = 0; i < scheme.length(); i++) {
            if (!Character.isLetter(scheme.charAt(i))) return null;
        }
        if (!SCHEMES.contains(scheme)) return null;
        // mailto: takes an address directly; the rest are hierarchical and need the slashes. This
        // is what stops a Windows path (C:\src) and a stack-trace frame (Foo.java:42) being read as
        // schemes — neither "c" nor "java" is on the list, but the check costs nothing and says so.
        if ("mailto".equals(scheme)) return scheme;
        return token.regionMatches(true, colon, "://", 0, 3) ? scheme : null;
    }

    /**
     * A path candidate, with any {@code :line} or {@code :line:column} suffix split off.
     *
     * <p>The suffix is parsed from the end rather than by matching the whole token, so a Windows
     * {@code C:\src\Foo.cs:42} keeps its drive letter: only digits immediately before the end, each
     * preceded by a colon, can be a position.
     */
    private static @Nullable LinkMatch path(String token, int start, int end) {
        String file = token;
        int line = 0;
        int column = 0;

        int cut = positionCut(file);
        if (cut > 0) {
            int value = Integer.parseInt(file.substring(cut + 1));
            String head = file.substring(0, cut);
            int second = positionCut(head);
            if (second > 0) {
                line = Integer.parseInt(head.substring(second + 1));
                column = value;
                file = head.substring(0, second);
            } else {
                line = value;
                file = head;
            }
        }
        if (file.isEmpty()) return null;
        return new LinkMatch(LinkMatch.Kind.PATH, token, start, end, file, line, column);
    }

    /**
     * The index of the colon introducing a trailing all-digit run, or -1.
     *
     * <p>Bounded, so a token ending in a long run of digits cannot be read as a line number: no
     * file is opened at line 10,000,000 by a click, and a digest or an id ending a token is far
     * likelier than that.
     */
    private static int positionCut(String token) {
        int i = token.length();
        while (i > 0 && Character.isDigit(token.charAt(i - 1))) i--;
        if (i == token.length()) return -1; // does not end in digits
        if (i == 0 || token.charAt(i - 1) != ':') return -1;
        if (token.length() - i > MAX_POSITION_DIGITS) return -1;
        if (i - 1 == 0) return -1; // ":42" names no file
        return i - 1;
    }

    private static final int MAX_POSITION_DIGITS = 7;
}
