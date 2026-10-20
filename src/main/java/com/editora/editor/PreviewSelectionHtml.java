package com.editora.editor;

import java.util.List;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import org.jspecify.annotations.Nullable;

/**
 * The selected part of a preview as an HTML fragment, read off the rendered nodes: what is bold, a link, code,
 * a heading, a list item or a table cell in the preview is that in the fragment. Used for a selection that
 * cuts through a block; one made of whole blocks is rendered from their Markdown source instead
 * ({@link MarkdownClipboardHtml}), which is exact.
 *
 * <p>Semantic tags only, every character of the document escaped, and a link kept only when it leads to the
 * web or to mail: nothing the document wrote reaches the clipboard as markup. Nested lists come out as one
 * level; a picture or a formula inside the selection is left out.
 */
final class PreviewSelectionHtml {

    private PreviewSelectionHtml() {}

    static String of(List<PreviewSelection.Part> parts) {
        StringBuilder sb = new StringBuilder();
        String list = null; // "ul" / "ol" while inside one
        GridPane table = null;
        Integer row = null;
        for (PreviewSelection.Part part : parts) {
            Region leaf = part.leaf();
            GridPane grid =
                    leaf.getParent() instanceof GridPane g && g.getStyleClass().contains("md-table") ? g : null;
            String itemList = grid == null ? listOf(leaf) : null;
            if (table != null && grid != table) {
                sb.append("</tr></table>\n");
                table = null;
            }
            if (list != null && !list.equals(itemList)) {
                sb.append("</").append(list).append(">\n");
                list = null;
            }
            if (grid != null) {
                Integer r = GridPane.getRowIndex(leaf);
                if (table == null) {
                    sb.append("<table><tr>");
                    table = grid;
                } else if (!java.util.Objects.equals(r, row)) {
                    sb.append("</tr><tr>");
                }
                row = r;
                String cell = leaf.getStyleClass().contains("md-table-header") ? "th" : "td";
                sb.append('<')
                        .append(cell)
                        .append('>')
                        .append(inline(part))
                        .append("</")
                        .append(cell)
                        .append('>');
                continue;
            }
            if (itemList != null) {
                if (list == null) {
                    sb.append('<').append(itemList).append(">\n");
                    list = itemList;
                }
                sb.append("<li>").append(inline(part)).append("</li>\n");
                continue;
            }
            String tag = blockTag(leaf);
            sb.append('<').append(tag).append('>');
            if (tag.equals("pre")) {
                String text = PreviewSelection.textOf(leaf);
                sb.append("<code>")
                        .append(escape(text.substring(
                                Math.min(part.from(), text.length()), Math.min(part.to(), text.length()))))
                        .append("</code>");
            } else {
                sb.append(inline(part));
            }
            sb.append("</").append(tag).append(">\n");
        }
        if (table != null) {
            sb.append("</tr></table>\n");
        }
        if (list != null) {
            sb.append("</").append(list).append(">\n");
        }
        return sb.toString();
    }

    private static String blockTag(Region leaf) {
        for (String c : leaf.getStyleClass()) {
            if (c.length() == 5 && c.startsWith("md-h") && Character.isDigit(c.charAt(4))) {
                return "h" + c.charAt(4);
            }
        }
        return leaf.getStyleClass().contains("md-code-block") ? "pre" : "p";
    }

    /** "ul" or "ol" when {@code leaf} is the text of a list item, else null. */
    private static @Nullable String listOf(Node leaf) {
        for (Node n = leaf.getParent(); n != null; n = n.getParent()) {
            if (n.getStyleClass().contains("md-list-item") && n instanceof javafx.scene.Parent item) {
                for (Node child : item.getChildrenUnmodifiable()) {
                    if (child instanceof Label marker && marker.getStyleClass().contains("md-list-marker")) {
                        return marker.getText() != null && marker.getText().matches("\\d+\\.") ? "ol" : "ul";
                    }
                }
                return "ul"; // a task item: its box stands where the marker would
            }
        }
        return null;
    }

    private static String inline(PreviewSelection.Part part) {
        if (part.leaf() instanceof Label label) {
            String text = label.getText();
            return escape(text.substring(Math.min(part.from(), text.length()), Math.min(part.to(), text.length())));
        }
        StringBuilder sb = new StringBuilder();
        int seen = 0;
        for (Node child : ((TextFlow) part.leaf()).getChildren()) {
            String text = child instanceof Text t ? t.getText() : child instanceof Label l ? l.getText() : null;
            int len = text == null ? 1 : text.length();
            int from = Math.max(part.from(), seen) - seen;
            int to = Math.min(part.to(), seen + len) - seen;
            seen += len;
            if (text == null || to <= from) {
                continue;
            }
            // A run is taken where it is cut; an inline label (code, a key) is all or nothing in the
            // highlight, and so here.
            String piece = child instanceof Text ? text.substring(from, to) : text;
            sb.append(wrap(child, escape(piece).replace("\n", "<br>")));
        }
        return sb.toString();
    }

    private static String wrap(Node run, String html) {
        List<String> c = run.getStyleClass();
        if (run instanceof Label && c.contains("md-kbd")) {
            html = "<kbd>" + html + "</kbd>";
        } else if (c.contains("md-inline-code") || c.contains("md-html-code")) {
            html = "<code>" + html + "</code>";
        }
        if (c.contains("md-mark-word") || c.contains("md-mark")) {
            html = "<mark>" + html + "</mark>";
        }
        if (c.contains("md-sup")) {
            html = "<sup>" + html + "</sup>";
        }
        if (c.contains("md-sub")) {
            html = "<sub>" + html + "</sub>";
        }
        if (c.contains("md-strike")) {
            html = "<del>" + html + "</del>";
        }
        if (c.contains("md-ins")) {
            html = "<ins>" + html + "</ins>";
        }
        if (c.contains("md-italic")) {
            html = "<em>" + html + "</em>";
        }
        if (c.contains("md-bold")) {
            html = "<strong>" + html + "</strong>";
        }
        Object link = run.hasProperties() ? run.getProperties().get(MarkdownRenderer.LINK_KEY) : null;
        if (link instanceof String href && portable(href)) {
            html = "<a href=\"" + escape(href) + "\">" + html + "</a>";
        }
        return html;
    }

    /** Whether a link means something where the fragment is pasted: the web and mail, nothing else. */
    static boolean portable(String href) {
        String lower = href.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("https://") || lower.startsWith("http://") || lower.startsWith("mailto:");
    }

    static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '￼' -> {}
                default -> sb.append(ch);
            }
        }
        return sb.toString();
    }
}
