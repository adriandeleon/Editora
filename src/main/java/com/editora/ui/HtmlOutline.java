package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.editora.text.FoldRegions.Region;
import com.editora.text.HtmlElements;

/**
 * The Structure window's outline of an HTML page without a language server (HTML review C14): its elements, each
 * labelled the way vscode-html-language-server labels them — the tag, then {@code #id} and a {@code .class} per class
 * ({@code header#hero.card}) — so the outline keeps its shape when the server starts or stops. The fold-region outline
 * it replaces named every row by the bare tag (a page of 12,000 {@code div} rows told nothing apart) and left out every
 * one-line element.
 *
 * <p>Listed: every element that spans lines, and a one-line element when it has an {@code id} or is a heading
 * ({@code h1}–{@code h6}) — the rows worth jumping to; a one-line element's listed children move up to its parent. HTML
 * rules apply: names in any case, void elements hold nothing, omitted end tags ({@code <li>}, {@code <p>}…) close
 * themselves, and the content of a {@code <script>} or {@code <style>} is not read as markup. Pure; any thread.
 */
final class HtmlOutline {

    private HtmlOutline() {}

    private static final Set<String> HEADINGS = Set.of("h1", "h2", "h3", "h4", "h5", "h6");

    /** An element being read: its row, and its children until its end tag (or an implied one) closes it. */
    private static final class Open {
        final String name;
        final String label;
        final int line;
        final boolean keepAlways;
        final List<StructureNode> children = new ArrayList<>();

        Open(String name, String label, int line, boolean keepAlways) {
            this.name = name;
            this.label = label;
            this.line = line;
            this.keepAlways = keepAlways;
        }
    }

    /** The element outline of {@code text}, reading at most the current element limit (see {@link XmlOutline}). */
    static List<StructureNode> build(String text) {
        return build(text, XmlOutline.limit());
    }

    /** The element outline of {@code text}, reading at most {@code max} elements. */
    static List<StructureNode> build(String text, int max) {
        List<StructureNode> roots = new ArrayList<>();
        List<Open> stack = new ArrayList<>();
        int n = text.length();
        int line = 0;
        int count = 0;
        int i = 0;
        while (i < n) {
            char ch = text.charAt(i);
            if (ch == '\n') {
                line++;
                i++;
                continue;
            }
            if (ch != '<' || i + 1 >= n) {
                i++;
                continue;
            }
            char next = text.charAt(i + 1);
            boolean closing = next == '/';
            if (!(Character.isLetter(next) || next == '!' || next == '?' || closing)) {
                i++; // "a < b"
                continue;
            }
            int end = XmlOutline.constructEnd(text, i);
            int startLine = line;
            line += newlines(text, i, end);
            if (next == '!' || next == '?') {
                i = Math.max(end, i + 1); // a comment, DOCTYPE or processing instruction
                continue;
            }
            String tag = text.substring(i, end);
            i = end;
            String name = name(tag, closing ? 2 : 1);
            if (name.isEmpty()) {
                continue;
            }
            if (closing) {
                close(roots, stack, name, startLine, lastContentLine(text, i - tag.length(), startLine));
                continue;
            }
            if (count >= max) {
                line += newlines(text, i, n); // full: what is still open runs to the end of the text
                break;
            }
            count++;
            Set<String> ends = HtmlElements.impliedEnds(name);
            while (ends != null && !stack.isEmpty() && ends.contains(stack.getLast().name)) {
                finish(roots, stack, lastContentLine(text, i - tag.length(), startLine)); // "<li>" ends an open "<li>"
            }
            String id = XmlOutline.attribute(tag, "id");
            boolean keep = (id != null && !id.isBlank()) || HEADINGS.contains(name);
            Open open = new Open(name, label(name, tag), startLine, keep);
            if (HtmlElements.isVoid(name) || tag.endsWith("/>")) {
                if (keep) {
                    add(roots, stack, new StructureNode(new Region(startLine, line), open.label, "tag", startLine));
                }
                continue;
            }
            stack.add(open);
            if (HtmlElements.isRawText(name)) {
                int close = rawEnd(text, i, name);
                int contentEnd = close < 0 ? n : close;
                line += newlines(text, i, contentEnd);
                i = contentEnd; // the end tag is read next, as any other
            }
        }
        while (!stack.isEmpty()) {
            finish(roots, stack, line); // unclosed at the end of the text: open until the last line
        }
        return roots;
    }

    /** The label vscode-html-language-server gives an element: {@code tag#id.class1.class2}. */
    static String label(String name, String tag) {
        StringBuilder b = new StringBuilder(name);
        String id = XmlOutline.attribute(tag, "id");
        if (id != null && !id.isBlank()) {
            b.append('#').append(id.strip().replace("\"", "").replace("'", ""));
        }
        String classes = XmlOutline.attribute(tag, "class");
        if (classes != null) {
            for (String c : classes.strip().split("\\s+")) {
                if (!c.isEmpty()) {
                    b.append('.').append(c.replace("\"", "").replace("'", ""));
                }
            }
        }
        String label = b.toString();
        return label.length() > 2 * XmlOutline.MAX_VALUE_CHARS
                ? label.substring(0, 2 * XmlOutline.MAX_VALUE_CHARS - 1) + "…"
                : label;
    }

    /**
     * Closes the innermost open element named {@code name} on {@code endLine}, and what is left open inside it (an
     * omitted end tag) on {@code impliedEndLine}; a stray end tag is skipped.
     */
    private static void close(
            List<StructureNode> roots, List<Open> stack, String name, int endLine, int impliedEndLine) {
        int at = -1;
        for (int k = stack.size() - 1; k >= 0; k--) {
            if (stack.get(k).name.equals(name)) {
                at = k;
                break;
            }
        }
        if (at < 0) {
            return;
        }
        while (stack.size() > at + 1) {
            finish(roots, stack, impliedEndLine); // "<li>two" then "</ul>": the li ends with its own text
        }
        finish(roots, stack, endLine);
    }

    /**
     * Pops the innermost open element, ending at {@code endLine}, into its parent (or the roots): as a row when it spans
     * lines or is worth listing on its own, else its listed children take its place.
     */
    private static void finish(List<StructureNode> roots, List<Open> stack, int endLine) {
        Open o = stack.removeLast();
        int last = Math.max(o.line, endLine);
        if (last > o.line || o.keepAlways) {
            StructureNode node = new StructureNode(new Region(o.line, last), o.label, "tag", o.line);
            node.children().addAll(o.children);
            add(roots, stack, node);
        } else {
            for (StructureNode child : o.children) {
                add(roots, stack, child);
            }
        }
    }

    private static void add(List<StructureNode> roots, List<Open> stack, StructureNode node) {
        if (stack.isEmpty()) {
            roots.add(node);
        } else {
            stack.getLast().children.add(node);
        }
    }

    /**
     * The last line of an element whose end tag was left out, ending before the tag at {@code at} (on {@code line}):
     * the line before when the tag begins its own line, else that line.
     */
    private static int lastContentLine(String text, int at, int line) {
        int k = at - 1;
        while (k >= 0 && (text.charAt(k) == ' ' || text.charAt(k) == '\t')) {
            k--;
        }
        return k < 0 || text.charAt(k) == '\n' ? Math.max(0, line - 1) : line;
    }

    /** The lower-cased element name of a tag, from {@code from} (past {@code <} or {@code </}). */
    private static String name(String tag, int from) {
        int k = from;
        while (k < tag.length()) {
            char c = tag.charAt(k);
            if (!(Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == ':' || c == '.')) {
                break;
            }
            k++;
        }
        return k == from || !Character.isLetter(tag.charAt(from))
                ? ""
                : tag.substring(from, k).toLowerCase(Locale.ROOT);
    }

    /** The start of {@code </name} (any case) after a raw-text element's start tag ending at {@code from}, or -1. */
    private static int rawEnd(String text, int from, String name) {
        int k = from;
        while (true) {
            int e = text.indexOf("</", k);
            if (e < 0) {
                return -1;
            }
            int after = e + 2 + name.length();
            if (text.regionMatches(true, e + 2, name, 0, name.length())
                    && (after >= text.length() || !Character.isLetterOrDigit(text.charAt(after)))) {
                return e;
            }
            k = e + 2;
        }
    }

    private static int newlines(String text, int from, int to) {
        int count = 0;
        for (int k = from; k < to; k++) {
            if (text.charAt(k) == '\n') {
                count++;
            }
        }
        return count;
    }
}
