package com.editora.markdown;

import java.util.Locale;

/**
 * Pure (no-toolkit, unit-tested) answer to "which link is the caret on?" in Markdown source — what
 * <em>Open Link</em> and Ctrl/Cmd-click open.
 *
 * <p>Understood, in this order: an inline link or image {@code [text](dest "title")} — the destination may be
 * {@code <in angle brackets>} (the form for a path with spaces) and may contain balanced parentheses
 * ({@code …/wiki/A_(b)}); a reference link {@code [text][label]}, {@code [label][]} or {@code [label]},
 * resolved to its {@code [label]: dest} definition anywhere in the document; the definition line itself; an
 * autolink {@code <https://…>} / {@code <me@host>}; and a bare {@code http(s)://} or {@code www.} URL, without
 * the sentence punctuation that follows it. An image inside a link ({@code [![badge](img)](url)}) opens the
 * image when the caret is on the image and the outer link otherwise.
 *
 * <p>Nothing inside a code span, a code block (fenced or indented, as {@link MarkdownBlocks} reads the
 * document) or the front matter is a link: the text there is shown as written, so opening it would follow
 * something the document does not link to. (A code span inside a link's <em>text</em> is still part of
 * that link.)
 */
public final class MarkdownLinkAt {

    private MarkdownLinkAt() {}

    /** The destination of the link whose source span contains {@code caret}, or {@code null} when there is none. */
    public static String destination(String text, int caret) {
        if (text == null || caret < 0 || caret > text.length()) {
            return null;
        }
        int lineStart = text.lastIndexOf('\n', caret - 1) + 1;
        MarkdownBlocks blocks = MarkdownBlocks.scan(text);
        int caretLine = lineOf(text, lineStart);
        if (blocks.isCode(caretLine) || blocks.kind(caretLine) == MarkdownBlocks.Kind.FRONT_MATTER) {
            return null; // code and front matter are shown as written: nothing in them is a link
        }
        // The paragraph around the caret: link text may wrap, a link never crosses a blank line.
        int from = lineStart;
        while (from > 0) {
            int prev = text.lastIndexOf('\n', from - 2) + 1;
            if (isBlank(text, prev, from - 1)) {
                break;
            }
            from = prev;
        }
        int to = lineEnd(text, caret);
        while (to < text.length()) {
            int next = lineEnd(text, to + 1);
            if (isBlank(text, to + 1, next)) {
                break;
            }
            to = next;
        }
        MarkdownLinkRewrite.Link wiki = MarkdownLinkRewrite.wikiAt(text, caret);
        if (wiki != null) {
            return "[[" + wiki.destination() + "]]"; // the wiki form: resolved by whoever follows it
        }
        boolean[] code = codeSpans(text, from, to);
        String definition = definitionOn(text, lineStart, lineEnd(text, caret));
        if (definition != null) {
            return definition;
        }
        String link = bracketLink(text, blocks, from, to, caret, code);
        if (link != null) {
            return link;
        }
        return autolinkOrBareUrl(text, lineStart, lineEnd(text, caret), caret, code, from);
    }

    // --- [text](dest), [text][label], [label] -------------------------------------------------------------

    /** The innermost bracket link in {@code [from, to)} whose span contains {@code caret}. */
    private static String bracketLink(String text, MarkdownBlocks blocks, int from, int to, int caret, boolean[] code) {
        String found = null;
        for (int i = from; i < to; i++) {
            if (text.charAt(i) != '[' || isEscaped(text, i, from) || isCode(code, from, i)) {
                continue;
            }
            int close = matchingBracket(text, i, to, code, from);
            if (close < 0) {
                continue;
            }
            int start = i > from && text.charAt(i - 1) == '!' ? i - 1 : i;
            int[] end = {close + 1};
            String dest = target(text, blocks, i, close, to, end);
            if (dest != null && caret >= start && caret <= end[0]) {
                found = dest; // keep looking: a link nested in this one's text (an image) starts further right
            }
        }
        return found;
    }

    /** The destination of the link whose text is {@code [open, close]}; {@code end[0]} is set past the link. */
    private static String target(String text, MarkdownBlocks blocks, int open, int close, int to, int[] end) {
        int next = close + 1;
        if (next < to && text.charAt(next) == '(') {
            String inline = inlineDestination(text, next, to, end);
            if (inline != null) {
                return inline;
            }
        }
        String label = text.substring(open + 1, close);
        if (next < to && text.charAt(next) == '[') {
            int labelEnd = text.indexOf(']', next + 1);
            if (labelEnd > 0 && labelEnd < to) {
                String explicit = text.substring(next + 1, labelEnd);
                String dest = definition(text, blocks, explicit.isBlank() ? label : explicit);
                if (dest != null) {
                    end[0] = labelEnd + 1;
                    return dest;
                }
            }
        }
        if (next < text.length() && text.charAt(next) == ':') {
            return null; // "[label]: …" is the definition, read by definitionOn
        }
        end[0] = close + 1;
        return definition(text, blocks, label);
    }

    /** Parses {@code (dest "title")} starting at the {@code (} at {@code open}; null when it is not one. */
    private static String inlineDestination(String text, int open, int to, int[] end) {
        int i = skipSpace(text, open + 1, to);
        String dest;
        if (i < to && text.charAt(i) == '<') {
            int gt = i + 1;
            while (gt < to && text.charAt(gt) != '>' && text.charAt(gt) != '\n' && text.charAt(gt) != '<') {
                gt++;
            }
            if (gt >= to || text.charAt(gt) != '>') {
                return null;
            }
            dest = text.substring(i + 1, gt);
            i = gt + 1;
        } else {
            int depth = 0;
            int startDest = i;
            while (i < to) {
                char c = text.charAt(i);
                if (c == '\\' && i + 1 < to) {
                    i += 2;
                    continue;
                }
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    if (depth == 0) {
                        break;
                    }
                    depth--;
                } else if (Character.isWhitespace(c)) {
                    break;
                }
                i++;
            }
            dest = unescape(text.substring(startDest, Math.min(i, to)));
        }
        i = skipSpace(text, i, to);
        if (i < to && (text.charAt(i) == '"' || text.charAt(i) == '\'' || text.charAt(i) == '(')) {
            char closer = text.charAt(i) == '(' ? ')' : text.charAt(i);
            int titleEnd = i + 1;
            while (titleEnd < to && text.charAt(titleEnd) != closer) {
                titleEnd += text.charAt(titleEnd) == '\\' ? 2 : 1;
            }
            if (titleEnd >= to) {
                return null;
            }
            i = skipSpace(text, titleEnd + 1, to);
        }
        if (i >= to || text.charAt(i) != ')' || dest.isEmpty()) {
            return null;
        }
        end[0] = i + 1;
        return dest;
    }

    /** The {@code ]} that closes the {@code [} at {@code open}, counting nested brackets; -1 when unclosed. */
    private static int matchingBracket(String text, int open, int to, boolean[] code, int from) {
        int depth = 0;
        for (int i = open; i < to; i++) {
            char c = text.charAt(i);
            if (isCode(code, from, i) || isEscaped(text, i, from)) {
                continue;
            }
            if (c == '[') {
                depth++;
            } else if (c == ']' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    // --- reference definitions ----------------------------------------------------------------------------

    /** The destination defined for {@code label} ({@code [label]: dest}), or null. Footnotes are not links. */
    static String definition(String text, MarkdownBlocks blocks, String label) {
        String wanted = normalize(label);
        if (wanted.isEmpty() || wanted.startsWith("^")) {
            return null;
        }
        int line = 0;
        for (int start = 0; start <= text.length(); line++) {
            int end = lineEnd(text, start);
            if (blocks.isText(line)) { // a "definition" inside code, a comment or front matter defines nothing
                int close = definitionLabelEnd(text, start, end);
                if (close > 0
                        && normalize(text.substring(text.indexOf('[', start) + 1, close))
                                .equals(wanted)) {
                    String dest = definitionDestination(text, close + 2, end);
                    if (dest != null) {
                        return dest;
                    }
                }
            }
            start = end + 1;
        }
        return null;
    }

    /** A link reference definition: its label as written and its destination. */
    public record Definition(String label, String destination, int line) {}

    /** Every {@code [label]: destination} of {@code text}, in order (not footnotes, not inside code). */
    public static java.util.List<Definition> definitions(String text) {
        java.util.List<Definition> out = new java.util.ArrayList<>();
        if (text == null) {
            return out;
        }
        MarkdownBlocks blocks = MarkdownBlocks.scan(text);
        int line = 0;
        for (int start = 0; start <= text.length(); line++) {
            int end = lineEnd(text, start);
            if (blocks.isText(line)) {
                int close = definitionLabelEnd(text, start, end);
                int open = close < 0 ? -1 : text.indexOf('[', start);
                if (close > 0 && text.charAt(open + 1) != '^') {
                    String dest = definitionDestination(text, close + 2, end);
                    if (dest != null) {
                        out.add(new Definition(text.substring(open + 1, close).strip(), dest, line));
                    }
                }
            }
            start = end + 1;
        }
        return out;
    }

    /** The destination when {@code [start, end)} is a definition line {@code [label]: dest}, else null. */
    private static String definitionOn(String text, int start, int end) {
        int close = definitionLabelEnd(text, start, end);
        if (close < 0 || text.charAt(text.indexOf('[', start) + 1) == '^') {
            return null;
        }
        return definitionDestination(text, close + 2, end);
    }

    /** Index of the {@code ]} of a line that opens {@code [label]:} (indented at most three spaces), or -1. */
    private static int definitionLabelEnd(String text, int start, int end) {
        int i = start;
        while (i < end && i - start < 4 && text.charAt(i) == ' ') {
            i++;
        }
        if (i >= end || i - start > 3 || text.charAt(i) != '[') {
            return -1;
        }
        int close = text.indexOf(']', i + 1);
        return close > i + 1 && close + 1 < end && text.charAt(close + 1) == ':' ? close : -1;
    }

    private static String definitionDestination(String text, int from, int end) {
        int i = skipSpace(text, from, end);
        if (i >= end) {
            return null;
        }
        if (text.charAt(i) == '<') {
            int gt = text.indexOf('>', i);
            return gt > i + 1 && gt < end ? text.substring(i + 1, gt) : null;
        }
        int stop = i;
        while (stop < end && !Character.isWhitespace(text.charAt(stop))) {
            stop++;
        }
        return unescape(text.substring(i, stop));
    }

    /** Labels match case-insensitively with runs of whitespace collapsed (CommonMark). */
    private static String normalize(String label) {
        return label.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    // --- <autolinks> and bare URLs ------------------------------------------------------------------------

    private static String autolinkOrBareUrl(String text, int start, int end, int caret, boolean[] code, int from) {
        for (int i = start; i < end; i++) {
            if (isCode(code, from, i)) {
                continue;
            }
            char c = text.charAt(i);
            if (c == '<') {
                int gt = text.indexOf('>', i);
                if (gt > i && gt < end) {
                    String inner = text.substring(i + 1, gt);
                    String dest = autolink(inner);
                    if (dest != null) {
                        if (caret >= i && caret <= gt + 1) {
                            return dest;
                        }
                        i = gt;
                    }
                }
                continue;
            }
            int scheme = bareUrlStart(text, i, end);
            if (scheme > 0 && (i == start || !isUrlWordChar(text.charAt(i - 1)))) {
                int stop = i;
                while (stop < end
                        && !Character.isWhitespace(text.charAt(stop))
                        && "<>\"".indexOf(text.charAt(stop)) < 0) {
                    stop++;
                }
                stop = trimTrailingPunctuation(text, i, stop);
                if (stop > i + scheme && caret >= i && caret <= stop) {
                    String url = text.substring(i, stop);
                    return text.charAt(i) == 'w' || text.charAt(i) == 'W' ? "http://" + url : url;
                }
                i = Math.max(i, stop - 1);
            }
        }
        return null;
    }

    /** {@code <scheme:…>} or {@code <user@host>} without spaces; null for anything else (an HTML tag). */
    private static String autolink(String inner) {
        if (inner.isEmpty() || inner.chars().anyMatch(Character::isWhitespace)) {
            return null;
        }
        if (inner.matches("[A-Za-z][A-Za-z0-9+.-]{1,31}:[^<>]*")) {
            return inner;
        }
        return inner.matches("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+") ? "mailto:" + inner : null;
    }

    /** Length of the {@code http://} / {@code https://} / {@code www.} prefix starting at {@code i}, or 0. */
    private static int bareUrlStart(String text, int i, int end) {
        for (String prefix : new String[] {"https://", "http://", "www."}) {
            if (i + prefix.length() < end && text.regionMatches(true, i, prefix, 0, prefix.length())) {
                return prefix.length();
            }
        }
        return 0;
    }

    private static boolean isUrlWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '/' || c == '.' || c == '-' || c == '_';
    }

    /**
     * GFM's extended-autolink rule: trailing {@code ?!.,:*_~'"} are sentence punctuation, and a trailing
     * {@code )} belongs to the URL only while the URL's own parentheses are balanced.
     */
    private static int trimTrailingPunctuation(String text, int start, int stop) {
        while (stop > start) {
            char last = text.charAt(stop - 1);
            if ("?!.,:;*_~'\"".indexOf(last) >= 0) {
                stop--;
            } else if (last == ')') {
                int open = 0;
                int close = 0;
                for (int i = start; i < stop; i++) {
                    open += text.charAt(i) == '(' ? 1 : 0;
                    close += text.charAt(i) == ')' ? 1 : 0;
                }
                if (close <= open) {
                    break;
                }
                stop--;
            } else {
                break;
            }
        }
        return stop;
    }

    // --- code -------------------------------------------------------------------------------------------

    /** The 0-based line that starts at offset {@code lineStart}. */
    private static int lineOf(String text, int lineStart) {
        int line = 0;
        for (int i = text.indexOf('\n'); i >= 0 && i < lineStart; i = text.indexOf('\n', i + 1)) {
            line++;
        }
        return line;
    }

    /** Marks the characters of {@code [from, to)} that are inside a backtick code span (delimiters included). */
    private static boolean[] codeSpans(String text, int from, int to) {
        boolean[] code = new boolean[to - from];
        int i = from;
        while (i < to) {
            if (text.charAt(i) != '`' || isEscaped(text, i, from)) {
                i++;
                continue;
            }
            int run = 1;
            while (i + run < to && text.charAt(i + run) == '`') {
                run++;
            }
            int close = -1;
            for (int j = i + run; j < to; j++) {
                if (text.charAt(j) != '`') {
                    continue;
                }
                int r = 1;
                while (j + r < to && text.charAt(j + r) == '`') {
                    r++;
                }
                if (r == run) {
                    close = j;
                    break;
                }
                j += r - 1;
            }
            if (close < 0) {
                i += run;
                continue;
            }
            java.util.Arrays.fill(code, i - from, close + run - from, true);
            i = close + run;
        }
        return code;
    }

    private static boolean isCode(boolean[] code, int from, int index) {
        int k = index - from;
        return k >= 0 && k < code.length && code[k];
    }

    // --- small helpers ------------------------------------------------------------------------------------

    private static boolean isEscaped(String text, int index, int from) {
        int slashes = 0;
        for (int i = index - 1; i >= from && text.charAt(i) == '\\'; i--) {
            slashes++;
        }
        return (slashes & 1) == 1;
    }

    private static String unescape(String s) {
        return s.indexOf('\\') < 0 ? s : s.replaceAll("\\\\(\\p{Punct})", "$1");
    }

    private static int skipSpace(String text, int i, int to) {
        while (i < to && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int lineEnd(String text, int from) {
        int nl = text.indexOf('\n', Math.min(from, text.length()));
        return nl < 0 ? text.length() : nl;
    }

    private static boolean isBlank(String text, int start, int end) {
        for (int i = start; i < end && i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
