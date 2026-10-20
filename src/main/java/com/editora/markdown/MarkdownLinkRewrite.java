package com.editora.markdown;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * The relative links of a Markdown document, and their rewrite when a file or folder is renamed or moved.
 *
 * <p>Pure: paths are resolved lexically, nothing is read from disk. A link is an inline destination
 * ({@code [text](dest)}, {@code ![alt](dest "title")}, {@code (<dest with spaces>)}) or a reference
 * definition ({@code [label]: dest}); anything inside code, front matter or an HTML block is not a link (see
 * {@link MarkdownBlocks}). Only relative destinations are ever rewritten: a URL with a scheme, an absolute
 * path and a bare {@code #anchor} are left exactly as written, and so is every link whose target did not
 * move relative to the document.
 */
public final class MarkdownLinkRewrite {

    private MarkdownLinkRewrite() {}

    /**
     * One destination: {@code [start, end)} is the destination as written, angle brackets included, and
     * {@code destination} its text without them.
     */
    public record Link(int start, int end, String destination, boolean angle, Kind kind, int line) {

        Link(int start, int end, String destination, boolean angle) {
            this(start, end, destination, angle, Kind.INLINE, 0);
        }

        /** A wiki link or embed: its target is a note's name, resolved through {@link WikiTargets}. */
        public boolean isWiki() {
            return kind == Kind.WIKI || kind == Kind.WIKI_EMBED;
        }

        /** The destination without its {@code #fragment} or {@code ?query}. */
        public String path() {
            return destination.substring(0, suffixStart(destination));
        }

        /** The {@code #fragment} without the {@code #}, or "" when there is none. */
        public String fragment() {
            int hash = destination.indexOf('#');
            return hash < 0 ? "" : destination.substring(hash + 1);
        }
    }

    /** How a link is written. */
    public enum Kind {
        /** {@code [text](destination)} and {@code ![alt](destination)}. */
        INLINE,
        /** {@code [label]: destination}. */
        DEFINITION,
        /** {@code [[note]]}: {@code [start, end)} is the note's name only; a heading is in the destination. */
        WIKI,
        /** {@code ![[image.png]]}. */
        WIKI_EMBED,
        /** {@code <img src="…">} or {@code <a href="…">}: {@code [start, end)} is the attribute's value. */
        HTML
    }

    private static final java.util.regex.Pattern HTML_TARGET = java.util.regex.Pattern.compile(
            "<(?:img|a|source|video|audio)\\b[^<>]*?\\b(?:src|href)\\s*=\\s*(?:\"([^\"<>]*)\"|'([^'<>]*)')",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /** The rewritten document and how many destinations changed; {@code text} is the input when none did. */
    public record Result(String text, int count) {}

    /** Every link destination in {@code text}, in document order, wiki links included. */
    public static List<Link> links(String text) {
        return links(text, true);
    }

    /**
     * Every link destination in {@code text}, in document order: inline links and images, reference
     * definitions, the {@code src} / {@code href} of raw HTML and, when {@code wiki}, {@code [[wiki links]]}.
     */
    public static List<Link> links(String text, boolean wiki) {
        List<Link> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].endsWith("\r")) {
                lines[i] = lines[i].substring(0, lines[i].length() - 1);
            }
        }
        MarkdownBlocks blocks = MarkdownBlocks.scan(lines);
        int offset = 0;
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n];
            int before = out.size();
            boolean text0 = blocks.isText(n);
            if (text0 && (line.indexOf("](") >= 0 || line.indexOf("]:") >= 0)) {
                scanLine(line, offset, out);
            }
            if (text0 && wiki && line.indexOf("[[") >= 0) {
                scanWiki(line, offset, out);
            }
            if ((text0 || blocks.kind(n) == MarkdownBlocks.Kind.HTML_BLOCK) && line.indexOf('<') >= 0) {
                scanHtml(line, offset, text0, out);
            }
            if (out.size() > before) {
                for (int k = before; k < out.size(); k++) {
                    Link l = out.get(k);
                    out.set(k, new Link(l.start(), l.end(), l.destination(), l.angle(), l.kind(), n));
                }
                if (out.size() - before > 1) {
                    out.subList(before, out.size()).sort(java.util.Comparator.comparingInt(Link::start));
                }
            }
            int newline = text.indexOf('\n', offset);
            if (newline < 0) {
                break;
            }
            offset = newline + 1;
        }
        if (wiki) {
            dropDefinedReferences(text, out);
        }
        return out;
    }

    /**
     * {@code [[note]]} is a reference link in brackets, not a wiki link, when the document defines
     * {@code [note]: …} — the parser's rule; such an entry is taken out again.
     */
    private static void dropDefinedReferences(String text, List<Link> out) {
        java.util.Set<String> labels = null;
        for (Link link : out) {
            if (link.kind() == Kind.DEFINITION) {
                int lineStart = text.lastIndexOf('\n', link.start() - 1) + 1;
                int open = text.indexOf('[', lineStart);
                int close = text.indexOf("]:", open);
                if (open >= 0 && close > open && close < link.start()) {
                    if (labels == null) {
                        labels = new java.util.HashSet<>();
                    }
                    labels.add(text.substring(open + 1, close)
                            .strip()
                            .replaceAll("\\s+", " ")
                            .toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        if (labels == null) {
            return;
        }
        java.util.Set<String> defined = labels;
        out.removeIf(link -> {
            if (!link.isWiki()) {
                return false;
            }
            int close = text.indexOf("]]", link.start());
            int open = text.lastIndexOf("[[", link.start());
            return open >= 0
                    && close > open
                    && defined.contains(text.substring(open + 2, close)
                            .strip()
                            .replaceAll("\\s+", " ")
                            .toLowerCase(java.util.Locale.ROOT));
        });
    }

    /**
     * The wiki link whose {@code [[…]]} contains {@code caret}, or null. Never one in code, front matter or
     * raw HTML.
     */
    public static @Nullable Link wikiAt(String text, int caret) {
        if (text == null || caret < 0 || caret > text.length() || text.indexOf("[[") < 0) {
            return null;
        }
        int lineStart = text.lastIndexOf('\n', caret - 1) + 1;
        int lineEnd = text.indexOf('\n', caret);
        String line = text.substring(lineStart, lineEnd < 0 ? text.length() : lineEnd);
        if (line.indexOf("[[") < 0) {
            return null;
        }
        for (Link link : links(text, true)) {
            if (link.isWiki() && link.start() >= lineStart && link.start() <= lineStart + line.length()) {
                int open = line.lastIndexOf("[[", link.start() - lineStart);
                int close = line.indexOf("]]", link.start() - lineStart);
                if (open >= 0 && close >= 0 && caret >= lineStart + open && caret <= lineStart + close + 2) {
                    return link;
                }
            }
        }
        return null;
    }

    /** {@code [[target]]}, {@code [[target#heading|label]]} and {@code ![[embed]]} outside code spans. */
    private static void scanWiki(String line, int offset, List<Link> out) {
        int[] code = MarkdownBlocks.codeSpans(line);
        int from = 0;
        while (true) {
            int open = line.indexOf("[[", from);
            if (open < 0) {
                return;
            }
            int close = line.indexOf("]]", open + 2);
            if (close < 0) {
                return;
            }
            from = open + 2;
            String inner = line.substring(open + 2, close);
            // The parser's rules (MarkdownWikiLinks): not glued to a word, not the text of an inline link
            // ("[[note]](url)" stays what it was), never in code.
            int lead0 = open > 0 && line.charAt(open - 1) == '!' ? open - 1 : open;
            if (MarkdownBlocks.inRanges(code, open)
                    || isEscaped(line, open)
                    || inner.isBlank()
                    || inner.indexOf('[') >= 0
                    || inner.indexOf(']') >= 0
                    || (lead0 > 0 && Character.isLetterOrDigit(line.charAt(lead0 - 1)))
                    || (close + 2 < line.length() && line.charAt(close + 2) == '(')) {
                continue;
            }
            int bar = inner.indexOf('|');
            String target = bar < 0 ? inner : inner.substring(0, bar);
            if (target.endsWith("\\")) {
                target = target.substring(0, target.length() - 1); // "\|": the pipe as a table cell writes it
            }
            int hash = target.indexOf('#');
            String name = hash < 0 ? target : target.substring(0, hash);
            int lead = name.length() - name.stripLeading().length();
            String shown = name.strip();
            boolean embed = open > 0 && line.charAt(open - 1) == '!' && !isEscaped(line, open - 1);
            int start = offset + open + 2 + lead;
            out.add(new Link(
                    start,
                    start + shown.length(),
                    shown + (hash < 0 ? "" : "#" + target.substring(hash + 1).strip()),
                    false,
                    embed ? Kind.WIKI_EMBED : Kind.WIKI,
                    0));
            from = close + 2;
        }
    }

    /** The {@code src} / {@code href} values of raw HTML on a line (outside code spans on a text line). */
    private static void scanHtml(String line, int offset, boolean text, List<Link> out) {
        java.util.regex.Matcher m = HTML_TARGET.matcher(line);
        int[] code = text ? MarkdownBlocks.codeSpans(line) : null;
        while (m.find()) {
            int group = m.group(1) != null ? 1 : 2;
            if (m.group(group).isBlank() || (code != null && MarkdownBlocks.inRanges(code, m.start()))) {
                continue;
            }
            out.add(new Link(offset + m.start(group), offset + m.end(group), m.group(group), false, Kind.HTML, 0));
        }
    }

    private static void scanLine(String line, int offset, List<Link> out) {
        int[] code = MarkdownBlocks.codeSpans(line);
        int definition = definitionDestinationStart(line);
        if (definition >= 0) {
            Link link = destinationAt(line, definition, line.length(), offset, false);
            if (link != null) {
                out.add(new Link(link.start(), link.end(), link.destination(), link.angle(), Kind.DEFINITION, 0));
            }
            return;
        }
        int from = 0;
        while (true) {
            int at = line.indexOf("](", from);
            if (at < 0) {
                return;
            }
            from = at + 2;
            if (MarkdownBlocks.inRanges(code, at) || isEscaped(line, at) || !hasOpeningBracket(line, at, code)) {
                continue;
            }
            Link link = destinationAt(line, at + 2, line.length(), offset, true);
            if (link != null) {
                out.add(link);
                from = link.end() - offset;
            }
        }
    }

    /** The index just past {@code [label]:} when {@code line} is a reference definition, else -1. */
    private static int definitionDestinationStart(String line) {
        int i = 0;
        while (i < line.length() && i < 4 && line.charAt(i) == ' ') {
            i++;
        }
        if (i > 3 || i >= line.length() || line.charAt(i) != '[' || line.startsWith("[^", i)) {
            return -1;
        }
        int close = line.indexOf(']', i + 1);
        if (close <= i + 1 || close + 1 >= line.length() || line.charAt(close + 1) != ':') {
            return -1;
        }
        return close + 2;
    }

    private static @Nullable Link destinationAt(String line, int from, int to, int offset, boolean inline) {
        int i = from;
        while (i < to && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        if (i >= to) {
            return null;
        }
        if (line.charAt(i) == '<') {
            int gt = i + 1;
            while (gt < to && line.charAt(gt) != '>' && line.charAt(gt) != '<') {
                gt++;
            }
            if (gt >= to || line.charAt(gt) != '>' || gt == i + 1) {
                return null;
            }
            return new Link(offset + i, offset + gt + 1, line.substring(i + 1, gt), true);
        }
        int start = i;
        int depth = 0;
        while (i < to) {
            char c = line.charAt(i);
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
        i = Math.min(i, to);
        if (i == start) {
            return null;
        }
        if (inline) {
            // "[x](path with space)" is not a link: after the destination only a title and ")" may follow.
            int j = i;
            while (j < to && (line.charAt(j) == ' ' || line.charAt(j) == '\t')) {
                j++;
            }
            if (j >= to) {
                return null;
            }
            char next = line.charAt(j);
            if (next != ')' && next != '"' && next != '\'' && next != '(') {
                return null;
            }
        }
        return new Link(offset + start, offset + i, line.substring(start, i), false);
    }

    /** Whether the {@code ]} at {@code close} closes an unescaped {@code [} earlier on the line. */
    private static boolean hasOpeningBracket(String line, int close, int[] code) {
        int depth = 0;
        for (int i = close - 1; i >= 0; i--) {
            char c = line.charAt(i);
            if ((c != '[' && c != ']') || isEscaped(line, i) || MarkdownBlocks.inRanges(code, i)) {
                continue;
            }
            if (c == ']') {
                depth++;
            } else if (depth-- == 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean isEscaped(String line, int index) {
        int slashes = 0;
        for (int i = index - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
            slashes++;
        }
        return (slashes & 1) == 1;
    }

    /**
     * Rewrites the relative links of a document for the rename or move of {@code from} to {@code to} (a
     * file, or a folder with everything under it).
     *
     * @param docOld where the document was when its links were written
     * @param docNew where it is now — different from {@code docOld} only when the document itself moved,
     *     in which case every relative link is recomputed from the new place
     */
    public static Result rewrite(String text, Path docOld, Path docNew, Path from, Path to) {
        return rewrite(text, docOld, docNew, from, to, null);
    }

    /**
     * As {@link #rewrite(String, Path, Path, Path, Path)}, also rewriting wiki links: {@code wiki} is the
     * project's notes as they were <em>before</em> the rename (null leaves wiki links alone). A wiki link
     * written as a bare name follows its note's new name and is untouched by a mere move; one written with a
     * folder is recomputed like a path.
     */
    public static Result rewrite(
            String text, Path docOld, Path docNew, Path from, Path to, @Nullable WikiTargets wiki) {
        if (text == null || docOld == null || docNew == null || from == null || to == null) {
            return new Result(text, 0);
        }
        Path oldDir = docOld.toAbsolutePath().normalize().getParent();
        Path newDir = docNew.toAbsolutePath().normalize().getParent();
        if (oldDir == null || newDir == null) {
            return new Result(text, 0);
        }
        Path source = from.toAbsolutePath().normalize();
        Path target = to.toAbsolutePath().normalize();
        List<Link> links = links(text, wiki != null);
        StringBuilder out = null;
        int copied = 0;
        int count = 0;
        Path oldDoc = docOld.toAbsolutePath().normalize();
        for (Link link : links) {
            String replacement = link.isWiki()
                    ? (wiki == null ? null : rewrittenWiki(link, oldDoc, newDir, source, target, wiki))
                    : rewritten(link, oldDir, newDir, source, target);
            if (replacement == null) {
                continue;
            }
            if (out == null) {
                out = new StringBuilder(text.length() + 32);
            }
            out.append(text, copied, link.start()).append(replacement);
            copied = link.end();
            count++;
        }
        if (out == null) {
            return new Result(text, 0);
        }
        return new Result(out.append(text, copied, text.length()).toString(), count);
    }

    /** The new note name for a wiki link, or null when it stays as written. */
    private static @Nullable String rewrittenWiki(
            Link link, Path oldDoc, Path newDir, Path from, Path to, WikiTargets wiki) {
        String written = link.path();
        WikiTargets.Resolved was = wiki.resolve(oldDoc, written);
        if (was == null || written.isEmpty()) {
            return null;
        }
        Path now = was.file().startsWith(from)
                ? to.resolve(from.relativize(was.file())).normalize()
                : was.file();
        boolean keepExtension = WikiTargets.hasExtension(written);
        String next;
        if (was.base() == null) {
            // A bare name: it follows the note's name and nothing else.
            if (now.getFileName().equals(was.file().getFileName())) {
                return null;
            }
            next = WikiTargets.name(now, keepExtension);
        } else {
            // Written with a folder: relative to the document, or to the project root, as it was.
            Path base = was.base().equals(oldDoc.getParent()) ? newDir : was.base();
            String rel;
            try {
                rel = base.relativize(now).toString().replace('\\', '/');
            } catch (IllegalArgumentException e) {
                return null;
            }
            next = keepExtension ? rel : WikiTargets.stripMarkdownExtension(rel);
        }
        return next.equals(written) ? null : next;
    }

    /** The new text for {@code link}, or null when it stays as written. */
    private static @Nullable String rewritten(Link link, Path oldDir, Path newDir, Path from, Path to) {
        String dest = link.destination();
        if (!isRelative(dest)) {
            return null;
        }
        int cut = suffixStart(dest);
        String pathPart = dest.substring(0, cut);
        String suffix = dest.substring(cut);
        String decoded = link.angle() ? pathPart : decode(unescape(pathPart));
        if (decoded.isBlank()) {
            return null;
        }
        Path was;
        Path now;
        Path oldRel;
        Path newRel;
        try {
            was = oldDir.resolve(decoded).normalize();
            now = was.startsWith(from) ? to.resolve(from.relativize(was)).normalize() : was;
            oldRel = oldDir.relativize(was);
            newRel = newDir.relativize(now);
        } catch (IllegalArgumentException e) {
            return null; // not a path this platform can name, or on another root: leave it alone
        }
        if (oldRel.equals(newRel)) {
            return null;
        }
        String rel = newRel.toString().replace('\\', '/');
        if (rel.isEmpty()) {
            rel = ".";
        }
        if (decoded.startsWith("./") && !rel.startsWith(".")) {
            rel = "./" + rel;
        }
        if ((decoded.endsWith("/") || decoded.endsWith("\\")) && !rel.endsWith("/")) {
            rel += "/";
        }
        return link.angle() ? "<" + rel + suffix + ">" : encode(rel) + suffix;
    }

    /** False for a URL with a scheme, an absolute path, a protocol-relative URL and a bare anchor or query. */
    static boolean isRelative(String dest) {
        if (dest.isEmpty()
                || dest.charAt(0) == '#'
                || dest.charAt(0) == '?'
                || dest.charAt(0) == '/'
                || dest.charAt(0) == '\\') {
            return false;
        }
        for (int i = 0; i < dest.length(); i++) {
            char c = dest.charAt(i);
            if (c == ':') {
                return i == 0; // "mailto:", "https:", "C:" — never a relative path
            }
            boolean schemeChar = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (i > 0 && ((c >= '0' && c <= '9') || c == '+' || c == '.' || c == '-'));
            if (!schemeChar) {
                return true;
            }
        }
        return true;
    }

    static int suffixStart(String dest) {
        for (int i = 0; i < dest.length(); i++) {
            char c = dest.charAt(i);
            if (c == '#' || c == '?') {
                return i;
            }
        }
        return dest.length();
    }

    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length() && isAsciiPunctuation(s.charAt(i + 1))) {
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static boolean isAsciiPunctuation(char c) {
        return (c >= '!' && c <= '/') || (c >= ':' && c <= '@') || (c >= '[' && c <= '`') || (c >= '{' && c <= '~');
    }

    /** Percent-decodes {@code s} as UTF-8, leaving a malformed escape as written. */
    static String decode(String s) {
        if (s.indexOf('%') < 0) {
            return s;
        }
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length() && hex(s.charAt(i + 1)) >= 0 && hex(s.charAt(i + 2)) >= 0) {
                bytes.write(hex(s.charAt(i + 1)) * 16 + hex(s.charAt(i + 2)));
                i += 3;
            } else {
                int cp = s.codePointAt(i);
                bytes.writeBytes(new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8));
                i += Character.charCount(cp);
            }
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static int hex(char c) {
        return Character.digit(c, 16);
    }

    /** A destination that needs no angle brackets: spaces and the characters Markdown would misread are escaped. */
    static String encode(String path) {
        boolean balanced = balancedParens(path);
        StringBuilder sb = new StringBuilder(path.length() + 8);
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            switch (c) {
                case ' ' -> sb.append("%20");
                case '%' -> sb.append("%25");
                case '#' -> sb.append("%23");
                case '?' -> sb.append("%3F");
                case '<' -> sb.append("%3C");
                case '>' -> sb.append("%3E");
                case '(' -> sb.append(balanced ? "(" : "%28");
                case ')' -> sb.append(balanced ? ")" : "%29");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean balancedParens(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth < 0) {
                return false;
            }
        }
        return depth == 0;
    }
}
