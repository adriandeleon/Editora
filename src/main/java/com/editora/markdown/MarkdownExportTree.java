package com.editora.markdown;

import java.util.ArrayList;
import java.util.List;

import org.commonmark.node.BlockQuote;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;

/**
 * A parsed document with the newer block syntax rewritten as blocks every exporter already draws, for the
 * writers that walk the tree themselves (DOCX, ODT, PDF). HTML needs none of this: its renderers know the
 * nodes.
 *
 * <ul>
 *   <li>An alert ({@code > [!NOTE]}) becomes an {@link AlertQuote}: a block quote that opens with its
 *       title in bold and knows its kind, so a writer can colour its rule and title.
 *   <li>A definition list becomes, per entry, the term as a bold paragraph followed by an
 *       {@link Indented} block holding its descriptions.
 * </ul>
 *
 * Inline nodes (emoji, highlight, superscript, subscript) are left for the writers, which style them.
 */
public final class MarkdownExportTree {

    private MarkdownExportTree() {}

    /** An alert as the block quote it is drawn as; a writer that knows it colours the rule and the title. */
    public static final class AlertQuote extends BlockQuote {
        private final MarkdownAlerts.Kind kind;

        AlertQuote(MarkdownAlerts.Kind kind) {
            this.kind = kind;
        }

        public MarkdownAlerts.Kind getKind() {
            return kind;
        }
    }

    /** The descriptions of a definition-list entry: blocks to set in from their term. */
    public static final class Indented extends org.commonmark.node.CustomBlock {}

    /** The colour of an alert kind's rule and title, as six hex digits (GitHub's light palette). */
    public static String color(MarkdownAlerts.Kind kind) {
        return switch (kind) {
            case NOTE -> "0969DA";
            case TIP -> "1A7F37";
            case IMPORTANT -> "8250DF";
            case WARNING -> "9A6700";
            case CAUTION -> "CF222E";
        };
    }

    /** Rewrites {@code root} in place and returns it. */
    public static Node lower(Node root) {
        if (root == null) {
            return null;
        }
        List<Node> children = new ArrayList<>();
        for (Node c = root.getFirstChild(); c != null; c = c.getNext()) {
            children.add(c);
        }
        for (Node child : children) {
            lower(child); // innermost first: an alert in a list item, a definition list in an alert
            if (child instanceof MarkdownAlerts.Alert alert) {
                BlockQuote quote = new AlertQuote(alert.getKind());
                Paragraph title = new Paragraph();
                StrongEmphasis strong = new StrongEmphasis();
                strong.appendChild(new Text(alert.getKind().title()));
                title.appendChild(strong);
                quote.appendChild(title);
                moveChildren(alert, quote);
                alert.insertAfter(quote);
                alert.unlink();
            } else if (child instanceof MarkdownDefinitions.DefinitionList list) {
                Node anchor = list;
                Indented descriptions = null;
                for (Node part = list.getFirstChild(); part != null; ) {
                    Node next = part.getNext();
                    if (part instanceof MarkdownDefinitions.Term term) {
                        Paragraph p = new Paragraph();
                        StrongEmphasis strong = new StrongEmphasis();
                        moveChildren(term, strong);
                        p.appendChild(strong);
                        anchor.insertAfter(p);
                        anchor = p;
                        descriptions = null;
                    } else {
                        if (descriptions == null) {
                            descriptions = new Indented();
                            anchor.insertAfter(descriptions);
                            anchor = descriptions;
                        }
                        if (part.getFirstChild() != null
                                && !(part.getFirstChild() instanceof org.commonmark.node.Block)) {
                            Paragraph p = new Paragraph(); // a one-line description holds its text directly
                            moveChildren(part, p);
                            descriptions.appendChild(p);
                        } else {
                            moveChildren(part, descriptions);
                        }
                    }
                    part = next;
                }
                list.unlink();
            }
        }
        return root;
    }

    private static void moveChildren(Node from, Node to) {
        for (Node c = from.getFirstChild(); c != null; ) {
            Node next = c.getNext();
            to.appendChild(c);
            c = next;
        }
    }
}
