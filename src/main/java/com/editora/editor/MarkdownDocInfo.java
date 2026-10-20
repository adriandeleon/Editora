package com.editora.editor;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.editora.markdown.MarkdownMath;
import com.editora.markdown.MarkdownToc;
import org.commonmark.ext.front.matter.YamlFrontMatterBlock;
import org.commonmark.ext.front.matter.YamlFrontMatterNode;
import org.commonmark.node.Code;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;
import org.jspecify.annotations.Nullable;

/**
 * Pure (no-toolkit, unit-tested) facts about a parsed Markdown document that every export states the same
 * way: its title and language, and the anchor of each heading — the {@link MarkdownToc#slug} plus {@code -1},
 * {@code -2} sequence, so a {@code [x](#heading)} link written for GitHub or by the table-of-contents command
 * lands on the same heading in the exported HTML, Word and OpenDocument files.
 */
public final class MarkdownDocInfo {

    private MarkdownDocInfo() {}

    /**
     * The document's title: the front matter's {@code title:}, else the text of its first level-1 heading,
     * else {@code fallback}.
     */
    public static @Nullable String title(Node ast, @Nullable String fallback) {
        String fromFrontMatter = frontMatter(ast, "title");
        if (fromFrontMatter != null) {
            return fromFrontMatter;
        }
        for (Node n = ast.getFirstChild(); n != null; n = n.getNext()) {
            if (n instanceof Heading h && h.getLevel() == 1) {
                String text = plainText(h).strip();
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        return fallback;
    }

    /**
     * The document's language as a BCP 47 tag from the front matter's {@code lang:} / {@code language:}
     * ({@code es}, {@code pt-BR}), or {@code fallback} when there is none or it is not shaped like a tag.
     */
    public static @Nullable String language(Node ast, @Nullable String fallback) {
        String lang = frontMatter(ast, "lang");
        if (lang == null) {
            lang = frontMatter(ast, "language");
        }
        if (lang == null) {
            return fallback;
        }
        String tag = lang.replace('_', '-');
        return tag.matches("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*") ? tag : fallback;
    }

    /** The first value of front-matter {@code key} (quotes removed), or null when absent or blank. */
    static @Nullable String frontMatter(Node ast, String key) {
        for (Node n = ast.getFirstChild(); n != null; n = n.getNext()) {
            if (!(n instanceof YamlFrontMatterBlock)) {
                continue;
            }
            for (Node c = n.getFirstChild(); c != null; c = c.getNext()) {
                if (c instanceof YamlFrontMatterNode entry && key.equalsIgnoreCase(entry.getKey())) {
                    List<String> values = entry.getValues();
                    String v = values == null || values.isEmpty()
                            ? ""
                            : String.valueOf(values.get(0)).strip();
                    if (v.length() >= 2
                            && (v.charAt(0) == '"' || v.charAt(0) == '\'')
                            && v.charAt(v.length() - 1) == v.charAt(0)) {
                        v = v.substring(1, v.length() - 1).strip();
                    }
                    return v.isEmpty() ? null : v;
                }
            }
        }
        return null;
    }

    /**
     * The anchor of every top-level heading that has text, in document order — the headings and the
     * numbering the table of contents uses. Keys are compared by identity.
     */
    public static Map<Heading, String> headingAnchors(Node ast) {
        Map<Heading, String> anchors = new IdentityHashMap<>();
        Map<String, Integer> seen = new HashMap<>();
        for (Node n = ast.getFirstChild(); n != null; n = n.getNext()) {
            if (n instanceof Heading h) {
                String text = plainText(h).strip();
                if (!text.isEmpty()) {
                    anchors.put(h, MarkdownToc.uniqueAnchor(MarkdownToc.slug(text), seen));
                }
            }
        }
        return anchors;
    }

    /**
     * The LaTeX of a block that is a formula on its own — a closed {@code $$} block, or a paragraph that is
     * nothing but one display {@code $$…$$} — or null. Every renderer asks this the same way, so the preview
     * and all exports agree on what is a centred formula and what is a formula in a line of text.
     */
    public static @Nullable String blockFormula(Node block) {
        if (block instanceof MarkdownMath.Block math) {
            return math.isClosed() ? math.getLatex() : null;
        }
        if (block instanceof org.commonmark.node.Paragraph p
                && p.getFirstChild() instanceof MarkdownMath.Inline m
                && m.getNext() == null
                && m.isDisplay()) {
            return m.getLatex();
        }
        return null;
    }

    private static final java.util.regex.Pattern HTML_COMMENT =
            java.util.regex.Pattern.compile("<!--.*?(?:-->|\\z)", java.util.regex.Pattern.DOTALL);

    /**
     * A raw-HTML block as the exports that show HTML as its source write it: without its comments, which are
     * invisible everywhere, and without the blank lines they leave. {@code <!-- note --> text} is "text"; a
     * block that is nothing but comments is "". Pure.
     */
    public static String htmlSourceShown(String literal) {
        if (literal == null) {
            return "";
        }
        String kept = HTML_COMMENT.matcher(literal).replaceAll("");
        StringBuilder out = new StringBuilder();
        for (String line : kept.split("\n", -1)) {
            if (!line.isBlank()) {
                out.append(out.isEmpty() ? "" : "\n").append(line.stripTrailing());
            }
        }
        return out.toString().stripLeading();
    }

    /** The fragment of an in-document link ({@code #Set-Up} → {@code set-up}), or null when it is not one. */
    public static @Nullable String fragment(String href) {
        if (href == null || !href.startsWith("#") || href.length() < 2) {
            return null;
        }
        return href.substring(1).toLowerCase(Locale.ROOT);
    }

    /** The text a reader sees for {@code node}: its descendants' text and code, a line break as a space. */
    public static String plainText(Node node) {
        StringBuilder sb = new StringBuilder();
        collect(node, sb);
        return sb.toString();
    }

    private static void collect(Node node, StringBuilder sb) {
        for (Node c = node.getFirstChild(); c != null; c = c.getNext()) {
            if (c instanceof Text t) {
                sb.append(t.getLiteral());
            } else if (c instanceof Code code) {
                sb.append(code.getLiteral());
            } else if (c instanceof SoftLineBreak || c instanceof HardLineBreak) {
                sb.append(' ');
            } else if (MarkdownMath.source(c) != null) {
                sb.append(MarkdownMath.source(c));
            } else {
                collect(c, sb);
            }
        }
    }
}
