package com.editora.ui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.IntFunction;

import com.editora.lsp.SymbolNode;
import com.editora.text.FoldRegions.Region;
import org.jspecify.annotations.Nullable;

/**
 * The Structure window's outline of an XML document (XML review C4, B13/C11).
 *
 * <p>Without a language server it is built here from the text: every element, leaves included, labelled by its
 * qualified name and its naming attribute — {@code xs:element name="note"}, {@code xsl:template match="/"},
 * {@code bean id="dataSource"}. The fold-region outline it replaces named a row by the first tag token on the
 * line, which in a namespaced document is the prefix ({@code xs}), and it left out every one-line element.
 *
 * <p>With lemminx it tidies the server's symbols the same way: an element row gets its naming attribute (a
 * schema's outline was a column of nameless {@code xs:element} rows), and the "Grammars" node — the bound
 * schema's raw {@code file:} URI with "Cache: false" and "Binding: …" rows — is left out, being no part of the
 * document. Pure; any thread.
 */
final class XmlOutline {

    /** At most this many elements are listed by default; lemminx's own default symbol limit. */
    static final int MAX_NODES = com.editora.config.Settings.XML_SYMBOL_LIMIT_DEFAULT;

    /**
     * The limit in use: Settings → LSP → XML's "Structure element limit", which caps the server's outline too, so
     * the outline without the server follows it as well (XML review 2 C9). Set when settings are applied.
     */
    private static volatile int limit = MAX_NODES;

    /** Attributes that name an element, in the order they are preferred. */
    private static final List<String> NAMING_ATTRIBUTES = List.of("name", "id", "xml:id", "match", "ref");

    /** A naming attribute's value longer than this is cut, so an XPath does not fill the row. */
    static final int MAX_VALUE_CHARS = 40;

    /** How many lines below a start tag's first line are read to find the tag's end. */
    private static final int MAX_TAG_LINES = 20;

    private XmlOutline() {}

    /** Sets the most elements {@link #build(String)} lists; below 1 = the default. */
    static void setLimit(int max) {
        limit = max < 1 ? MAX_NODES : max;
    }

    /** The most elements an outline built without the server lists (Settings → LSP → XML's limit). */
    static int limit() {
        return limit;
    }

    /** The element outline of {@code text}, at most the current limit of elements. */
    static List<StructureNode> build(String text) {
        return build(text, limit);
    }

    /** The element outline of {@code text}, at most {@code max} elements. */
    static List<StructureNode> build(String text, int max) {
        List<StructureNode> roots = new ArrayList<>();
        Deque<Open> stack = new ArrayDeque<>();
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
            if (ch != '<') {
                i++;
                continue;
            }
            int end = constructEnd(text, i);
            int startLine = line;
            line += newlines(text, i, end);
            if (end <= i + 1 || text.startsWith("<!", i) || text.startsWith("<?", i)) {
                i = Math.max(end, i + 1); // a comment, CDATA, DOCTYPE or processing instruction
                continue;
            }
            String tag = text.substring(i, end);
            i = end;
            if (tag.startsWith("</")) {
                String name = qualifiedName(tag, 2);
                close(roots, stack, name, startLine);
                continue;
            }
            String name = qualifiedName(tag, 1);
            if (name.isEmpty()) {
                continue; // a stray '<' in text
            }
            boolean selfClosing = tag.endsWith("/>");
            if (count >= max) {
                // Full: what is still open runs to the end of the text, and the rest is not read — a 6 MB file is
                // outlined in the time its first elements take (the Structure window rebuilds on the FX thread).
                line += newlines(text, i, n);
                break;
            }
            count++;
            String label = label(name, tag);
            if (selfClosing) {
                add(roots, stack, new StructureNode(new Region(startLine, line), label, "tag", startLine));
            } else {
                stack.push(new Open(name, new Pending(label, startLine)));
            }
        }
        int last = line;
        while (!stack.isEmpty()) {
            finish(roots, stack, last); // unclosed at the end of the text: open until the last line
        }
        return roots;
    }

    /** An element whose end tag has not been seen yet. */
    private record Open(String name, Pending node) {}

    /** An open element's row, its children gathered until its end tag gives its last line. */
    private static final class Pending {
        final String label;
        final int line;
        final List<StructureNode> children = new ArrayList<>();

        Pending(String label, int line) {
            this.label = label;
            this.line = line;
        }
    }

    /** Closes the innermost open element named {@code name}, and any left open inside it. */
    private static void close(List<StructureNode> roots, Deque<Open> stack, String name, int endLine) {
        boolean open = false;
        for (Open o : stack) {
            if (o.name().equals(name)) {
                open = true;
                break;
            }
        }
        if (!open) {
            return; // a stray end tag
        }
        while (!stack.isEmpty()) {
            boolean match = stack.getFirst().name().equals(name);
            finish(roots, stack, endLine);
            if (match) {
                return;
            }
        }
    }

    /** Pops the innermost open element as a node ending at {@code endLine}, into its parent (or the roots). */
    private static void finish(List<StructureNode> roots, Deque<Open> stack, int endLine) {
        Pending p = stack.pop().node();
        StructureNode node = new StructureNode(new Region(p.line, Math.max(p.line, endLine)), p.label, "tag", p.line);
        node.children().addAll(p.children);
        add(roots, stack, node);
    }

    private static void add(List<StructureNode> roots, Deque<Open> stack, StructureNode node) {
        if (stack.isEmpty()) {
            roots.add(node);
        } else {
            stack.getFirst().node().children.add(node);
        }
    }

    /**
     * The server's outline with the "Grammars" pseudo-node left out and each element row named by its naming
     * attribute, read from the document's lines ({@code lineAt}, {@code lineCount} of them).
     */
    static List<StructureNode> tidyLsp(List<SymbolNode> symbols, IntFunction<String> lineAt, int lineCount) {
        List<StructureNode> out = new ArrayList<>();
        for (SymbolNode s : symbols) {
            if (s == null || isGrammarsNode(s)) {
                continue;
            }
            int line = Math.max(0, s.line());
            Region range = s.endLine() >= line ? new Region(line, s.endLine()) : null;
            String label = s.name();
            String tag = startTag(lineAt, lineCount, line, s.name());
            if (tag != null) {
                label = label(s.name(), tag);
            }
            StructureNode node = new StructureNode(range, label, s.kind(), line, s.detail());
            node.children().addAll(tidyLsp(s.children(), lineAt, lineCount));
            out.add(node);
        }
        return out;
    }

    /**
     * lemminx's referenced-grammars node ({@code xml.symbols.showReferencedGrammars}): named "Grammars", its rows
     * the schemas' URIs with "Cache: …" and "Binding: …" under them. An element of that name has no such rows.
     */
    static boolean isGrammarsNode(SymbolNode s) {
        if (!"Grammars".equals(s.name()) || s.children().isEmpty()) {
            return false;
        }
        for (SymbolNode grammar : s.children()) {
            String name = grammar.name();
            boolean uri = name.startsWith("file:") || name.startsWith("http:") || name.startsWith("https:");
            if (!uri && grammar.children().stream().noneMatch(c -> c.name().startsWith("Binding:"))) {
                return false;
            }
        }
        return true;
    }

    /** The start tag of element {@code name} beginning on {@code line} (it may span lines), or null. */
    private static @Nullable String startTag(IntFunction<String> lineAt, int lineCount, int line, String name) {
        if (name.isEmpty() || line >= lineCount) {
            return null;
        }
        String first = lineAt.apply(line);
        if (first == null) {
            return null;
        }
        int at = first.indexOf("<" + name);
        if (at < 0) {
            return null;
        }
        StringBuilder tag = new StringBuilder(first.substring(at));
        for (int l = line + 1; l < Math.min(lineCount, line + MAX_TAG_LINES); l++) {
            if (endsTag(tag)) {
                break;
            }
            String next = lineAt.apply(l);
            if (next == null) {
                break;
            }
            tag.append('\n').append(next);
        }
        int end = constructEnd(tag, 0);
        return tag.substring(0, Math.min(end, tag.length()));
    }

    /** Whether the tag text read so far reaches its closing {@code >}. */
    private static boolean endsTag(CharSequence tag) {
        int end = constructEnd(tag, 0);
        return end > 0 && tag.charAt(end - 1) == '>';
    }

    /** {@code name} with its naming attribute, read from its start tag: {@code xs:element name="note"}. */
    static String label(String name, String tag) {
        for (String attribute : NAMING_ATTRIBUTES) {
            String value = attribute(tag, attribute);
            if (value != null && !value.isBlank()) {
                String shown = value.strip().replaceAll("\\s+", " ");
                if (shown.length() > MAX_VALUE_CHARS) {
                    shown = shown.substring(0, MAX_VALUE_CHARS - 1) + "…";
                }
                return name + " " + attribute + "=\"" + shown + "\"";
            }
        }
        return name;
    }

    /** The value of attribute {@code name} in a start tag, or null. */
    static @Nullable String attribute(String tag, String name) {
        int i = 1;
        int n = tag.length();
        while (i < n && !isSpace(tag.charAt(i)) && tag.charAt(i) != '>' && tag.charAt(i) != '/') {
            i++; // past the element name
        }
        while (i < n) {
            while (i < n && (isSpace(tag.charAt(i)) || tag.charAt(i) == '/')) {
                i++;
            }
            int start = i;
            while (i < n && tag.charAt(i) != '=' && !isSpace(tag.charAt(i)) && tag.charAt(i) != '>') {
                i++;
            }
            if (i >= n || tag.charAt(i) == '>' || start == i) {
                return null;
            }
            String attr = tag.substring(start, i);
            while (i < n && isSpace(tag.charAt(i))) {
                i++;
            }
            if (i >= n || tag.charAt(i) != '=') {
                continue; // an attribute without a value
            }
            i++;
            while (i < n && isSpace(tag.charAt(i))) {
                i++;
            }
            if (i >= n) {
                return null;
            }
            char quote = tag.charAt(i);
            String value;
            if (quote == '"' || quote == '\'') {
                int close = tag.indexOf(quote, i + 1);
                if (close < 0) {
                    return null;
                }
                value = tag.substring(i + 1, close);
                i = close + 1;
            } else {
                int s = i;
                while (i < n && !isSpace(tag.charAt(i)) && tag.charAt(i) != '>') {
                    i++;
                }
                value = tag.substring(s, i);
            }
            if (attr.equals(name)) {
                return value;
            }
        }
        return null;
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    /** The qualified name of a tag, from {@code from} (past {@code <} or {@code </}). */
    private static String qualifiedName(String tag, int from) {
        int i = from;
        while (i < tag.length()) {
            char c = tag.charAt(i);
            if (isSpace(c) || c == '>' || c == '/') {
                break;
            }
            i++;
        }
        String name = tag.substring(from, i);
        return name.isEmpty() || !isNameStart(name.charAt(0)) ? "" : name;
    }

    private static boolean isNameStart(char c) {
        return Character.isLetter(c) || c == '_' || c == ':';
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

    /**
     * The index just past the markup construct at {@code at}: a comment, CDATA section, DOCTYPE (with its
     * internal subset), processing instruction or tag, quotes respected; the text's length when it never ends.
     */
    static int constructEnd(CharSequence s, int at) {
        int n = s.length();
        if (startsWith(s, at, "<!--")) {
            return indexOf(s, "-->", at + 4, 3);
        }
        if (startsWith(s, at, "<![CDATA[")) {
            return indexOf(s, "]]>", at + 9, 3);
        }
        if (startsWith(s, at, "<?")) {
            return indexOf(s, "?>", at + 2, 2);
        }
        char quote = 0;
        int brackets = 0;
        for (int k = at + 1; k < n; k++) {
            char c = s.charAt(k);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '[') {
                brackets++;
            } else if (c == ']') {
                brackets = Math.max(0, brackets - 1);
            } else if (c == '>' && brackets == 0) {
                return k + 1;
            } else if (c == '<' && brackets == 0 && !startsWith(s, at, "<!")) {
                return k; // an unterminated tag: stop before the next one
            }
        }
        return n;
    }

    private static boolean startsWith(CharSequence s, int at, String prefix) {
        if (at + prefix.length() > s.length()) {
            return false;
        }
        for (int k = 0; k < prefix.length(); k++) {
            if (s.charAt(at + k) != prefix.charAt(k)) {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(CharSequence s, String needle, int from, int length) {
        int found = s.toString().indexOf(needle, from);
        return found < 0 ? s.length() : found + length;
    }
}
