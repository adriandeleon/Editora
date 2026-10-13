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
 *   <li>An alert ({@code > [!NOTE]}) becomes a block quote that opens with its title in bold.
 *   <li>A definition list becomes, per entry, the term as a bold paragraph followed by its descriptions'
 *       own blocks.
 * </ul>
 *
 * Inline nodes (emoji, highlight, superscript, subscript) are left for the writers, which style them.
 */
public final class MarkdownExportTree {

    private MarkdownExportTree() {}

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
                BlockQuote quote = new BlockQuote();
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
                for (Node part = list.getFirstChild(); part != null; ) {
                    Node next = part.getNext();
                    if (part instanceof MarkdownDefinitions.Term term) {
                        Paragraph p = new Paragraph();
                        StrongEmphasis strong = new StrongEmphasis();
                        moveChildren(term, strong);
                        p.appendChild(strong);
                        anchor.insertAfter(p);
                        anchor = p;
                    } else if (part.getFirstChild() != null
                            && !(part.getFirstChild() instanceof org.commonmark.node.Block)) {
                        Paragraph p = new Paragraph(); // a one-line description holds its text directly
                        moveChildren(part, p);
                        anchor.insertAfter(p);
                        anchor = p;
                    } else {
                        for (Node block = part.getFirstChild(); block != null; ) {
                            Node following = block.getNext();
                            anchor.insertAfter(block);
                            anchor = block;
                            block = following;
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
