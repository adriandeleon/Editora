package com.editora.office;

import java.util.ArrayList;
import java.util.List;

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

/**
 * A single styled run of inline text — the shared, pure (no-toolkit, unit-tested) intermediate the
 * {@code .docx} (POI) and hand-rolled {@code .odt} writers both consume. {@link #flatten(Node)} walks a
 * block's inline children (CommonMark + GFM) into a flat list, carrying bold/italic/strikethrough/
 * underline/code styling and an optional link {@code href}; soft/hard breaks become {@code "\n"} runs and
 * inline images degrade to their alt text (block images are handled by the writers).
 */
public record InlineRun(
        String text, boolean bold, boolean italic, boolean strike, boolean underline, boolean code, String href) {

    /** A plain (unstyled, no-link) run. */
    static InlineRun plain(String text) {
        return new InlineRun(text, false, false, false, false, false, null);
    }

    /** A hard/soft line break within a paragraph. */
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

    private static void collect(
            Node n, boolean b, boolean i, boolean s, boolean u, boolean code, String href, List<InlineRun> out) {
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
            // Inline image → alt text (block-level images are embedded by the writers).
            String alt = textOf(img);
            if (!alt.isEmpty()) {
                out.add(new InlineRun(alt, b, i, s, u, code, href));
            }
        } else if (n instanceof SoftLineBreak || n instanceof HardLineBreak) {
            out.add(new InlineRun("\n", b, i, s, u, code, href));
        } else {
            children(n, b, i, s, u, code, href, out); // unknown wrapper: descend
        }
    }

    private static void children(
            Node n, boolean b, boolean i, boolean s, boolean u, boolean code, String href, List<InlineRun> out) {
        for (Node c = n.getFirstChild(); c != null; c = c.getNext()) {
            collect(c, b, i, s, u, code, href, out);
        }
    }

    /** True if a fenced code block's info string is {@code mermaid} (optionally followed by params). */
    public static boolean isMermaidInfo(String info) {
        return info != null && info.strip().split("\\s+", 2)[0].equalsIgnoreCase("mermaid");
    }

    /**
     * If {@code paragraph} is exactly one {@code $$…$$} display-math span, returns its LaTeX; else null
     * (so the caller renders it as a normal paragraph). Mirrors the PDF writer's {@code soleDisplayMath}.
     */
    public static String soleDisplayMath(Node paragraph) {
        StringBuilder sb = new StringBuilder();
        for (Node c = paragraph.getFirstChild(); c != null; c = c.getNext()) {
            if (c instanceof Text t) {
                sb.append(t.getLiteral());
            } else if (c instanceof SoftLineBreak || c instanceof HardLineBreak) {
                sb.append(' '); // a $$…$$ block spans lines as soft breaks — join them, don't bail
            } else {
                return null;
            }
        }
        String trimmed = sb.toString().strip();
        java.util.List<com.editora.editor.MathSpans.Span> spans = com.editora.editor.MathSpans.find(trimmed);
        if (spans.size() == 1) {
            com.editora.editor.MathSpans.Span s = spans.get(0);
            if (s.display() && s.start() == 0 && s.end() == trimmed.length()) {
                return s.latex();
            }
        }
        return null;
    }

    /** The concatenated plain text of a node's descendants (for image alt text). */
    static String textOf(Node n) {
        StringBuilder sb = new StringBuilder();
        for (Node c = n.getFirstChild(); c != null; c = c.getNext()) {
            if (c instanceof Text t) {
                sb.append(t.getLiteral());
            } else if (c instanceof Code code) {
                sb.append(code.getLiteral());
            } else {
                sb.append(textOf(c));
            }
        }
        return sb.toString();
    }
}
