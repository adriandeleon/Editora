package com.editora.office;

import java.util.ArrayList;
import java.util.List;

import com.editora.editor.MarkdownDocInfo;
import com.editora.markdown.MarkdownMath;
import org.commonmark.ext.footnotes.FootnoteDefinition;
import org.commonmark.ext.footnotes.FootnoteReference;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.ins.Ins;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.jspecify.annotations.Nullable;

/**
 * A single styled run of inline text — the shared, pure (no-toolkit, unit-tested) intermediate the
 * {@code .docx} (POI) and hand-rolled {@code .odt} writers both consume. {@link #flatten(Node)} walks a
 * block's inline children (CommonMark + GFM) into a flat list, carrying bold/italic/strikethrough/
 * underline/code styling, an optional link {@code href} and, for an inline picture, its {@code image}
 * destination (the text is then its alt text) or, for a formula, its {@code latex} (the text is then its
 * source); a hard break becomes a {@code "\n"} run, a soft
 * break (a source line ending inside a paragraph) a space.
 */
public record InlineRun(
        String text,
        boolean bold,
        boolean italic,
        boolean strike,
        boolean underline,
        boolean code,
        @Nullable String href,
        @Nullable String image,
        @Nullable String latex,
        boolean highlight,
        int script) {

    /** A run with no highlight, on the baseline. */
    public InlineRun(
            String text,
            boolean bold,
            boolean italic,
            boolean strike,
            boolean underline,
            boolean code,
            @Nullable String href,
            @Nullable String image,
            @Nullable String latex) {
        this(text, bold, italic, strike, underline, code, href, image, latex, false, 0);
    }

    /** {@code ^superscript^} text ({@link #script} &gt; 0). */
    public boolean isSuperscript() {
        return script > 0;
    }

    /** {@code ~subscript~} text ({@link #script} &lt; 0). */
    public boolean isSubscript() {
        return script < 0;
    }

    /** A run of text (no picture). */
    public InlineRun(
            String text,
            boolean bold,
            boolean italic,
            boolean strike,
            boolean underline,
            boolean code,
            @Nullable String href) {
        this(text, bold, italic, strike, underline, code, href, null, null);
    }

    /** A formula in a line of text ({@code latex} non-null); its {@link #text} is its source, the fallback. */
    public boolean isMath() {
        return latex != null;
    }

    /**
     * Whether a link may be written into a document as a live hyperlink: a web or mail address, or a
     * relative reference. Anything else ({@code javascript:}, {@code file:}, {@code vbscript:}, a custom
     * scheme) is written as its text only — the reader of an exported document did not write the link.
     */
    public static boolean isSafeHref(String href) {
        if (href == null || href.isBlank()) {
            return false;
        }
        String h = href.strip();
        int colon = h.indexOf(':');
        int stop = indexOfAny(h, "/?#");
        if (colon < 0 || (stop >= 0 && stop < colon)) {
            return true; // no scheme: relative
        }
        String scheme = h.substring(0, colon).toLowerCase(java.util.Locale.ROOT);
        return scheme.equals("http") || scheme.equals("https") || scheme.equals("mailto");
    }

    private static int indexOfAny(String s, String chars) {
        for (int i = 0; i < s.length(); i++) {
            if (chars.indexOf(s.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    /** A plain (unstyled, no-link) run. */
    static InlineRun plain(String text) {
        return new InlineRun(text, false, false, false, false, false, null);
    }

    /** A hard line break within a paragraph. */
    boolean isBreak() {
        return "\n".equals(text);
    }

    /** Flattens the inline children of {@code parent} into styled runs. */
    public static List<InlineRun> flatten(Node parent) {
        List<InlineRun> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNext()) {
            collect(n, false, false, false, false, false, null, out);
        }
        return out;
    }

    /** The runs {@code out} gained since {@code from}, restyled: highlighted, or raised / lowered. */
    private static void mark(List<InlineRun> out, int from, boolean highlight, int script) {
        for (int k = from; k < out.size(); k++) {
            InlineRun r = out.get(k);
            out.set(
                    k,
                    new InlineRun(
                            r.text(),
                            r.bold(),
                            r.italic(),
                            r.strike(),
                            r.underline(),
                            r.code(),
                            r.href(),
                            r.image(),
                            r.latex(),
                            r.highlight() || highlight,
                            script != 0 ? script : r.script()));
        }
    }

    private static void collect(
            Node n,
            boolean b,
            boolean i,
            boolean s,
            boolean u,
            boolean code,
            @Nullable String href,
            List<InlineRun> out) {
        if (n instanceof Text t) {
            out.add(new InlineRun(t.getLiteral(), b, i, s, u, code, href));
        } else if (n instanceof Code c) {
            out.add(new InlineRun(c.getLiteral(), b, i, s, u, true, href));
        } else if (n instanceof StrongEmphasis) {
            children(n, true, i, s, u, code, href, out);
        } else if (n instanceof Emphasis) {
            children(n, b, true, s, u, code, href, out);
        } else if (n instanceof Strikethrough) {
            children(n, b, i, true, u, code, href, out);
        } else if (n instanceof Ins) {
            children(n, b, i, s, true, code, href, out);
        } else if (n instanceof Link link) {
            children(n, b, i, s, u, code, link.getDestination(), out);
        } else if (n instanceof Image img) {
            // A picture in the middle of a line (a badge row, a screenshot in a sentence): the run names the
            // picture and carries its alt text, which the writers fall back to when it cannot be embedded.
            String alt = textOf(img);
            String dest = img.getDestination();
            if (!alt.isEmpty() || (dest != null && !dest.isBlank())) {
                out.add(new InlineRun(alt, b, i, s, u, code, href, dest == null || dest.isBlank() ? null : dest, null));
            }
        } else if (n instanceof MarkdownMath.Inline math) {
            // A formula the parser recognised (as the preview's does): the LaTeX exactly as written.
            // source() is null only for a node that is not math
            String source = java.util.Objects.requireNonNullElse(MarkdownMath.source(math), "");
            out.add(new InlineRun(source, b, i, s, u, code, href, null, math.getLatex()));
        } else if (n instanceof com.editora.markdown.MarkdownEmoji.Emoji emoji) {
            out.add(new InlineRun(emoji.getEmoji(), b, i, s, u, code, href)); // the character, not ":name:"
        } else if (n instanceof com.editora.markdown.MarkdownMarks.Highlight) {
            int from = out.size();
            children(n, b, i, s, u, code, href, out);
            mark(out, from, true, 0);
        } else if (n instanceof com.editora.markdown.MarkdownMarks.Superscript) {
            int from = out.size();
            children(n, b, i, s, u, code, href, out);
            mark(out, from, false, 1);
        } else if (n instanceof com.editora.markdown.MarkdownMarks.Subscript) {
            int from = out.size();
            children(n, b, i, s, u, code, href, out);
            mark(out, from, false, -1);
        } else if (n instanceof FootnoteReference ref) {
            out.add(new InlineRun(footnoteMarker(ref.getLabel()), b, i, s, u, code, href)); // was dropped
        } else if (n instanceof HardLineBreak) {
            out.add(new InlineRun("\n", b, i, s, u, code, href));
        } else if (n instanceof SoftLineBreak) {
            // A source line ending inside a paragraph is a space (CommonMark), not a break: written as one, a
            // hard-wrapped paragraph came out of Word and LibreOffice with a manual break on every line.
            out.add(new InlineRun(" ", b, i, s, u, code, href));
        } else {
            children(n, b, i, s, u, code, href, out); // unknown wrapper: descend
        }
    }

    private static void children(
            Node n,
            boolean b,
            boolean i,
            boolean s,
            boolean u,
            boolean code,
            @Nullable String href,
            List<InlineRun> out) {
        for (Node c = n.getFirstChild(); c != null; c = c.getNext()) {
            collect(c, b, i, s, u, code, href, out);
        }
    }

    /** The visible marker for a footnote label — the same {@code [label]} the on-screen preview shows. */
    static String footnoteMarker(String label) {
        return "[" + (label == null ? "" : label) + "]";
    }

    /**
     * Every footnote definition under {@code root}, in document order. The office writers render them
     * together at the end of the document (where a reader expects notes), not wherever the source happened
     * to define them.
     */
    static List<FootnoteDefinition> footnotes(Node root) {
        List<FootnoteDefinition> out = new ArrayList<>();
        collectFootnotes(root, out);
        return out;
    }

    private static void collectFootnotes(Node n, List<FootnoteDefinition> out) {
        for (Node c = n.getFirstChild(); c != null; c = c.getNext()) {
            if (c instanceof FootnoteDefinition def) {
                out.add(def);
            } else {
                collectFootnotes(c, out);
            }
        }
    }

    /** True if a fenced code block's info string is {@code mermaid} (optionally followed by params). */
    public static boolean isMermaidInfo(String info) {
        return info != null && info.strip().split("\\s+", 2)[0].equalsIgnoreCase("mermaid");
    }

    /**
     * If {@code block} is a formula on its own — a closed {@code $$} block or a paragraph that is exactly one
     * display {@code $$…$$} — its LaTeX; else null (so the caller renders it as a normal paragraph). The
     * rule every renderer shares: {@link MarkdownDocInfo#blockFormula}.
     */
    public static String soleDisplayMath(Node block) {
        return MarkdownDocInfo.blockFormula(block);
    }

    /** The concatenated plain text of a node's descendants (for image alt text). */
    static String textOf(Node n) {
        StringBuilder sb = new StringBuilder();
        for (Node c = n.getFirstChild(); c != null; c = c.getNext()) {
            if (c instanceof Text t) {
                sb.append(t.getLiteral());
            } else if (c instanceof Code code) {
                sb.append(code.getLiteral());
            } else if (c instanceof com.editora.markdown.MarkdownEmoji.Emoji emoji) {
                sb.append(emoji.getEmoji());
            } else {
                sb.append(textOf(c));
            }
        }
        return sb.toString();
    }
}
