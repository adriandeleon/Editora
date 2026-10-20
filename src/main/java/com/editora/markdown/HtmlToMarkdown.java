package com.editora.markdown;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Turns the HTML a browser, a word processor or a spreadsheet puts on the clipboard into Markdown: headings,
 * paragraphs, emphasis, inline code, links, images, lists (nested, numbered, task), block quotes, code
 * blocks, rules and tables. Pure; its own small, forgiving HTML reader — clipboard HTML is fragments, often
 * unbalanced, and nothing here needs more than tags, attributes and text.
 *
 * <p>Everything else (scripts, styles, form controls, layout wrappers) is dropped or passed through as its
 * text. Styling is read only where applications use it <em>instead</em> of markup: a {@code font-weight} of
 * bold/600+ or {@code font-style: italic} on a span (Google Docs), and {@code font-weight: normal} on the
 * {@code <b>} wrapper Docs puts around everything.
 */
public final class HtmlToMarkdown {

    private static final Set<String> VOID = Set.of("br", "img", "hr", "meta", "link", "input", "col", "wbr", "source");
    private static final Set<String> SKIPPED = Set.of("head", "style", "script", "title", "template", "noscript");
    private static final Set<String> BLOCKS = Set.of(
            "p",
            "div",
            "section",
            "article",
            "header",
            "footer",
            "main",
            "aside",
            "figure",
            "figcaption",
            "ul",
            "ol",
            "li",
            "blockquote",
            "pre",
            "table",
            "thead",
            "tbody",
            "tfoot",
            "tr",
            "td",
            "th",
            "h1",
            "h2",
            "h3",
            "h4",
            "h5",
            "h6",
            "hr",
            "dl",
            "dt",
            "dd",
            "body",
            "html",
            "details",
            "summary");
    private static final Map<String, String> ENTITIES = Map.ofEntries(
            Map.entry("amp", "&"),
            Map.entry("lt", "<"),
            Map.entry("gt", ">"),
            Map.entry("quot", "\""),
            Map.entry("apos", "'"),
            Map.entry("nbsp", " "),
            Map.entry("ndash", "–"),
            Map.entry("mdash", "—"),
            Map.entry("hellip", "…"),
            Map.entry("lsquo", "‘"),
            Map.entry("rsquo", "’"),
            Map.entry("ldquo", "“"),
            Map.entry("rdquo", "”"),
            Map.entry("copy", "©"),
            Map.entry("reg", "®"),
            Map.entry("trade", "™"),
            Map.entry("euro", "€"));

    private static final Pattern ATTR =
            Pattern.compile("([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*(?:=\\s*(\"[^\"]*\"|'[^']*'|[^\\s\"'>]+))?");
    private static final Pattern ENTITY =
            Pattern.compile("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[A-Za-z][A-Za-z0-9]{1,10});");

    private HtmlToMarkdown() {}

    /** An element (or, with {@code name} null, a run of text). */
    private static final class Node {
        final @Nullable String name;
        final Map<String, String> attrs;
        final List<Node> children = new ArrayList<>();
        String text = "";

        Node(@Nullable String name, Map<String, String> attrs) {
            this.name = name;
            this.attrs = attrs;
        }

        String attr(String key) {
            return attrs.getOrDefault(key, "");
        }

        boolean is(String... names) {
            for (String n : names) {
                if (n.equals(name)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** The Markdown for {@code html}; "" when it holds no text. */
    public static String convert(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        blocks(parse(html).children, out, "");
        return out.toString().strip().replaceAll("\n{3,}", "\n\n");
    }

    /**
     * Whether {@code html} carries structure worth converting — a table, a link, a list or a heading — and
     * is not program text: something copied out of a code editor arrives as styled spans in a
     * {@code white-space: pre} wrapper or a {@code <pre>}, and belongs in the document as the plain text it is.
     */
    public static boolean hasStructure(String html) {
        if (html == null) {
            return false;
        }
        String h = html.toLowerCase(Locale.ROOT);
        if (h.matches("(?s).*white-space:\\s*pre.*")
                || h.matches("(?s)\\s*(<!--.*?-->|<meta[^>]*>|<html>|<body>)*\\s*<pre\\b.*")) {
            return false;
        }
        return Pattern.compile("<(table|ul|ol|h[1-6])\\b|<a\\s[^>]*href")
                .matcher(h)
                .find();
    }

    /** Whether plain text already reads as Markdown, so that it — not a conversion of the HTML — is pasted. */
    public static boolean looksLikeMarkdown(@Nullable String plain) {
        return plain != null
                && Pattern.compile(
                                "(?m)^#{1,6} \\S|^\\s*(?:[-*+]|\\d+[.)]) \\S|\\]\\([^)\\s]+\\)|^\\|.*\\|\\s*$|^```|\\*\\*\\S[^*]*\\*\\*")
                        .matcher(plain)
                        .find();
    }

    // ---- reading ----

    private static Node parse(String html) {
        Node root = new Node("#root", Map.of());
        List<Node> stack = new ArrayList<>(List.of(root));
        int i = 0;
        int n = html.length();
        while (i < n) {
            int lt = html.indexOf('<', i);
            if (lt < 0) {
                text(stack, html.substring(i));
                break;
            }
            if (lt > i) {
                text(stack, html.substring(i, lt));
            }
            if (html.startsWith("<!--", lt)) {
                int end = html.indexOf("-->", lt + 4);
                i = end < 0 ? n : end + 3;
                continue;
            }
            int gt = html.indexOf('>', lt);
            if (gt < 0
                    || lt + 1 >= n
                    || !(Character.isLetter(html.charAt(lt + 1))
                            || html.charAt(lt + 1) == '/'
                            || html.charAt(lt + 1) == '!'
                            || html.charAt(lt + 1) == '?')) {
                text(stack, "<");
                i = lt + 1;
                continue;
            }
            String tag = html.substring(lt + 1, gt);
            i = gt + 1;
            if (tag.startsWith("!") || tag.startsWith("?")) {
                continue; // doctype, processing instruction
            }
            if (tag.startsWith("/")) {
                String name = tag.substring(1).strip().toLowerCase(Locale.ROOT);
                for (int k = stack.size() - 1; k > 0; k--) {
                    if (name.equals(stack.get(k).name)) {
                        while (stack.size() > k) {
                            stack.remove(stack.size() - 1);
                        }
                        break;
                    }
                }
                continue;
            }
            int nameEnd = 0;
            while (nameEnd < tag.length()
                    && !Character.isWhitespace(tag.charAt(nameEnd))
                    && tag.charAt(nameEnd) != '/') {
                nameEnd++;
            }
            String name = tag.substring(0, nameEnd).toLowerCase(Locale.ROOT);
            Map<String, String> attrs = new java.util.HashMap<>();
            Matcher m = ATTR.matcher(tag.substring(nameEnd));
            while (m.find()) {
                String v = m.group(2) == null ? "" : m.group(2);
                if (v.length() >= 2 && (v.charAt(0) == '"' || v.charAt(0) == '\'')) {
                    v = v.substring(1, v.length() - 1);
                }
                attrs.put(m.group(1).toLowerCase(Locale.ROOT), decode(v));
            }
            if (SKIPPED.contains(name)) {
                int close = html.toLowerCase(Locale.ROOT).indexOf("</" + name, i);
                int closeEnd = close < 0 ? -1 : html.indexOf('>', close);
                i = closeEnd < 0 ? n : closeEnd + 1;
                continue;
            }
            // A new <li>, <tr> or cell ends the open one of its kind: clipboard fragments leave them unclosed.
            boolean entry = name.equals("dt") || name.equals("dd");
            if (entry
                    || name.equals("li")
                    || name.equals("tr")
                    || name.equals("td")
                    || name.equals("th")
                    || name.equals("p")) {
                Set<String> same = name.equals("td") || name.equals("th")
                        ? Set.of("td", "th")
                        : entry ? Set.of("dt", "dd") : Set.of(name);
                Set<String> stop = name.equals("li")
                        ? Set.of("ul", "ol")
                        : entry
                                ? Set.of("dl")
                                : name.equals("p")
                                        ? BLOCKS
                                        : (name.equals("tr")
                                                ? Set.of("table", "thead", "tbody", "tfoot")
                                                : Set.of("table", "thead", "tbody", "tfoot", "tr"));
                for (int k = stack.size() - 1; k > 0; k--) {
                    String open = stack.get(k).name;
                    if (same.contains(open)) {
                        while (stack.size() > k) {
                            stack.remove(stack.size() - 1);
                        }
                        break;
                    }
                    if (stop.contains(open)) {
                        break;
                    }
                }
            }
            Node node = new Node(name, attrs);
            stack.get(stack.size() - 1).children.add(node);
            if (!VOID.contains(name) && !tag.endsWith("/")) {
                stack.add(node);
            }
        }
        return root;
    }

    private static void text(List<Node> stack, String raw) {
        Node t = new Node(null, Map.of());
        t.text = decode(raw);
        stack.get(stack.size() - 1).children.add(t);
    }

    private static String decode(String s) {
        if (s.indexOf('&') < 0) {
            return s;
        }
        Matcher m = ENTITY.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String e = m.group(1);
            String r;
            if (e.charAt(0) == '#') {
                boolean hex = e.charAt(1) == 'x' || e.charAt(1) == 'X';
                int cp = Integer.parseInt(e.substring(hex ? 2 : 1), hex ? 16 : 10);
                r = Character.isValidCodePoint(cp) && cp != 0 ? new String(Character.toChars(cp)) : m.group();
                r = cp == 160 ? " " : r;
            } else {
                r = ENTITIES.getOrDefault(e, m.group());
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(r));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ---- writing ----

    private static void blocks(List<Node> nodes, StringBuilder out, String prefix) {
        List<Node> inlineRun = new ArrayList<>();
        for (Node n : nodes) {
            String name = n.name;
            if (name != null && BLOCKS.contains(name)) {
                flush(inlineRun, out, prefix);
                block(n, name, out, prefix);
            } else {
                inlineRun.add(n);
            }
        }
        flush(inlineRun, out, prefix);
    }

    private static void flush(List<Node> run, StringBuilder out, String prefix) {
        String text = inline(run, false).strip();
        run.clear();
        if (!text.isEmpty()) {
            paragraph(out, prefix, text);
        }
    }

    private static void paragraph(StringBuilder out, String prefix, String text) {
        for (String line : text.split("\n", -1)) {
            out.append(prefix).append(line).append('\n');
        }
        out.append(prefix.stripTrailing()).append('\n');
    }

    private static void block(Node n, String name, StringBuilder out, String prefix) {
        switch (name) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                String t = inline(n.children, false).strip().replace('\n', ' ');
                if (!t.isEmpty()) {
                    paragraph(out, prefix, "#".repeat(name.charAt(1) - '0') + " " + t);
                }
            }
            case "ul", "ol" -> {
                list(n, out, prefix);
                out.append(prefix.stripTrailing()).append('\n');
            }
            case "blockquote" -> {
                blocks(n.children, out, prefix + "> ");
                String quotedBlank = prefix + ">\n"; // the line after the quote's last block: outside the quote
                if (out.toString().endsWith(quotedBlank)) {
                    out.setLength(out.length() - quotedBlank.length());
                    out.append(prefix.stripTrailing()).append('\n');
                }
            }
            case "pre" -> {
                String code = plain(n).replaceAll("^\n|\n$", "");
                Node codeEl = n.children.stream()
                        .filter(c -> "code".equals(c.name))
                        .findFirst()
                        .orElse(n);
                Matcher lang = Pattern.compile("(?:language|lang)-([\\w+#.-]+)").matcher(codeEl.attr("class"));
                String fence = code.contains("```") ? "````" : "```";
                paragraph(out, prefix, fence + (lang.find() ? lang.group(1) : "") + "\n" + code + "\n" + fence);
            }
            case "hr" -> paragraph(out, prefix, "---");
            case "table" -> table(n, out, prefix);
            case "dl" -> definitions(n, out, prefix);
            default -> blocks(n.children, out, prefix);
        }
    }

    private static void list(Node list, StringBuilder out, String prefix) {
        boolean ordered = list.is("ol");
        long number = 1;
        try {
            number = ordered && !list.attr("start").isEmpty()
                    ? Long.parseLong(list.attr("start").strip())
                    : 1;
        } catch (NumberFormatException ignored) {
            // an unreadable start attribute: count from 1
        }
        for (Node li : list.children) {
            if (!li.is("li")) {
                continue;
            }
            String marker = ordered ? number++ + ". " : "- ";
            List<Node> own = new ArrayList<>();
            List<Node> nested = new ArrayList<>();
            String box = "";
            for (Node c : li.children) {
                if (c.is("ul", "ol")) {
                    nested.add(c);
                } else if (c.is("input") && c.attr("type").equalsIgnoreCase("checkbox")) {
                    box = c.attrs.containsKey("checked") ? "[x] " : "[ ] ";
                } else if (c.is("p", "div")) {
                    own.addAll(c.children); // a loose item's paragraph: its text is the item's text
                    own.add(lineBreak());
                } else {
                    own.add(c);
                }
            }
            String text = inline(own, false).strip().replace("\n", "\n" + prefix + " ".repeat(marker.length()));
            out.append(prefix).append(marker).append(box).append(text).append('\n');
            for (Node sub : nested) {
                list(sub, out, prefix + " ".repeat(marker.length()));
            }
        }
    }

    private static Node lineBreak() {
        Node t = new Node(null, Map.of());
        t.text = " ";
        return t;
    }

    private static void table(Node table, StringBuilder out, String prefix) {
        List<List<String>> rows = new ArrayList<>();
        rows(table, rows);
        if (rows.isEmpty()) {
            return;
        }
        int cols = rows.stream().mapToInt(List::size).max().orElse(1);
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < rows.size(); r++) {
            sb.append('|');
            for (int c = 0; c < cols; c++) {
                sb.append(' ')
                        .append(c < rows.get(r).size() ? rows.get(r).get(c) : "")
                        .append(" |");
            }
            sb.append('\n');
            if (r == 0) {
                sb.append("|").append(" --- |".repeat(cols)).append('\n');
            }
        }
        paragraph(out, prefix, MarkdownTable.reflow(sb.toString().stripTrailing()));
    }

    private static void rows(Node n, List<List<String>> rows) {
        rows(n, rows, new java.util.HashMap<>());
    }

    /**
     * Collects the rows under {@code n}. A merged cell has no Markdown form: its text goes in the first of
     * the cells it covers and the others are left empty, so every later cell stays under its own heading.
     * {@code open} carries the columns a {@code rowspan} from above still covers (column → rows left).
     */
    private static void rows(Node n, List<List<String>> rows, Map<Integer, Integer> open) {
        for (Node c : n.children) {
            if (c.is("tr")) {
                List<String> cells = new ArrayList<>();
                for (Node cell : c.children) {
                    if (!cell.is("td", "th")) {
                        continue;
                    }
                    skipCovered(cells, open);
                    cells.add(inline(cell.children, true)
                            .strip()
                            .replaceAll("\\s*\n\\s*", " ")
                            .replace("|", "\\|"));
                    int colspan = span(cell.attr("colspan"));
                    int rowspan = span(cell.attr("rowspan"));
                    for (int k = 0; k < colspan; k++) {
                        if (rowspan > 1) {
                            open.put(cells.size() - 1, rowspan - 1);
                        }
                        if (k + 1 < colspan) {
                            cells.add("");
                        }
                    }
                }
                skipCovered(cells, open);
                if (!cells.isEmpty()) {
                    rows.add(cells);
                }
            } else if (c.name != null && !c.is("table")) {
                rows(c, rows, open);
            }
        }
    }

    /** Leaves empty the columns, from where {@code cells} ends, that a cell of an earlier row still spans. */
    private static void skipCovered(List<String> cells, Map<Integer, Integer> open) {
        Integer left;
        while ((left = open.get(cells.size())) != null) {
            if (left <= 1) {
                open.remove(cells.size());
            } else {
                open.put(cells.size(), left - 1);
            }
            cells.add("");
        }
    }

    private static int span(String attribute) {
        try {
            return attribute.isBlank() ? 1 : Math.max(1, Math.min(100, Integer.parseInt(attribute.strip())));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** A {@code <dl>} as a definition list: each term on a line of its own, each description under it after ": ". */
    private static void definitions(Node dl, StringBuilder out, String prefix) {
        boolean any = false;
        boolean lastWasDescription = false;
        for (Node c : dl.children) {
            if (!c.is("dt", "dd")) {
                continue;
            }
            String text = inline(c.children, true).strip().replaceAll("\\s*\n\\s*", " ");
            if (text.isEmpty()) {
                continue;
            }
            if (c.is("dt") && lastWasDescription) {
                out.append(prefix.stripTrailing()).append('\n'); // a blank line between one entry and the next
            }
            out.append(prefix).append(c.is("dt") ? "" : ": ").append(text).append('\n');
            lastWasDescription = c.is("dd");
            any = true;
        }
        if (any) {
            out.append(prefix.stripTrailing()).append('\n');
        }
    }

    /** The text of everything under {@code n}, as it is (for a {@code <pre>}). */
    private static String plain(Node n) {
        if (n.name == null) {
            return n.text;
        }
        if (n.is("br")) {
            return "\n";
        }
        StringBuilder sb = new StringBuilder();
        for (Node c : n.children) {
            sb.append(plain(c));
        }
        return sb.toString();
    }

    private static String inline(List<Node> nodes, boolean cell) {
        StringBuilder sb = new StringBuilder();
        for (Node n : nodes) {
            inline(n, sb, cell);
        }
        // Whitespace is collapsed as a browser would; a <br> survives as a line break.
        return sb.toString()
                .replaceAll("[ \\t\\r\\f\\u00a0]*\n[ \\t\\r\\f]*", "\n")
                .replaceAll("[ \\t\\r\\f]{2,}", " ");
    }

    private static void inline(Node n, StringBuilder sb, boolean cell) {
        if (n.name == null) {
            sb.append(escape(n.text.replaceAll("\\s+", " ")));
            return;
        }
        String style = n.attr("style").toLowerCase(Locale.ROOT).replace(" ", "");
        boolean styledBold = style.matches(".*font-weight:(bold|[6-9]00).*");
        boolean plainWeight = style.contains("font-weight:normal") || style.contains("font-weight:400");
        boolean styledItalic = style.contains("font-style:italic");
        switch (n.name) {
            case "br" -> sb.append(cell ? " " : "\n");
            case "img" -> {
                if (!n.attr("src").isEmpty()) {
                    sb.append(MarkdownImagePaste.snippet(n.attr("src"), n.attr("alt")));
                }
            }
            case "a" -> {
                String href = n.attr("href").strip();
                String text = inline(n.children, cell).strip().replace('\n', ' ');
                if (href.isEmpty() || href.toLowerCase(Locale.ROOT).startsWith("javascript:")) {
                    sb.append(text);
                } else if (text.isEmpty() || text.equals(href)) {
                    sb.append('<').append(href).append('>');
                } else {
                    sb.append('[')
                            .append(text)
                            .append("](")
                            .append(MarkdownImagePaste.destination(href))
                            .append(')');
                }
            }
            case "code", "kbd", "samp", "tt" -> {
                String code = plain(n).replace('\n', ' ');
                if (!code.isBlank()) {
                    String ticks = code.contains("`") ? "``" : "`";
                    String pad = code.startsWith("`") || code.endsWith("`") ? " " : "";
                    sb.append(ticks).append(pad).append(code).append(pad).append(ticks);
                }
            }
            case "strong", "b" -> wrap(sb, plainWeight ? "" : "**", inline(n.children, cell));
            case "em", "i", "cite", "dfn", "var" -> wrap(sb, "*", inline(n.children, cell));
            case "del", "s", "strike" -> wrap(sb, "~~", inline(n.children, cell));
            default -> {
                String inner = inline(n.children, cell);
                if (styledBold && styledItalic) {
                    wrap(sb, "***", inner);
                } else if (styledBold) {
                    wrap(sb, "**", inner);
                } else if (styledItalic) {
                    wrap(sb, "*", inner);
                } else {
                    sb.append(inner);
                }
                if (BLOCKS.contains(n.name)) {
                    sb.append(cell ? " " : "\n"); // a block inside inline context (a <p> in a cell or an item)
                }
            }
        }
    }

    /** {@code inner} in {@code marker}, its outer whitespace kept outside (a marker next to a blank does not open). */
    private static void wrap(StringBuilder sb, String marker, String inner) {
        String core = inner.strip();
        if (core.isEmpty() || marker.isEmpty()) {
            sb.append(inner);
            return;
        }
        int lead = inner.indexOf(core.charAt(0));
        sb.append(inner, 0, lead)
                .append(marker)
                .append(core)
                .append(marker)
                .append(inner.substring(lead + core.length()));
    }

    /** Text with what Markdown would read as markup backslash-escaped. */
    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean word = i > 0
                    && i + 1 < s.length()
                    && Character.isLetterOrDigit(s.charAt(i - 1))
                    && Character.isLetterOrDigit(s.charAt(i + 1));
            if (c == '\\' || c == '*' || c == '`' || c == '[' || c == ']' || c == '<' || (c == '_' && !word)) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
