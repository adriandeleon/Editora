package com.editora.editor;

import javafx.scene.Node;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import com.editora.structured.XmlNode;

/**
 * Renders a parsed {@link XmlNode} DOM tree into a self-scrolling JavaFX {@code TreeView} for the 3-mode
 * preview — a collapsible view showing each element's tag + inline attributes, its children in document
 * order, and text-only elements inlined as {@code tag = "value"}. The DOM analogue of {@link StructuredTree}
 * (kept in {@code editor}, no {@code ui} dependency; the {@code TreeView} self-scrolls, so it's hosted
 * directly as the Split/Preview side, like the JSON/YAML/TOML tree).
 */
public final class XmlTree {

    private static final int AUTO_EXPAND_DEPTH = 1;

    private XmlTree() {}

    public static Node build(XmlNode root) {
        TreeItem<XmlNode> item = toItem(root);
        expand(item, AUTO_EXPAND_DEPTH);
        TreeView<XmlNode> tree = new TreeView<>(item);
        tree.getStyleClass().add("xml-tree");
        tree.setShowRoot(true);
        tree.setCellFactory(tv -> new XmlCell());
        return tree;
    }

    private static TreeItem<XmlNode> toItem(XmlNode n) {
        TreeItem<XmlNode> item = new TreeItem<>(n);
        for (XmlNode c : n.children()) {
            item.getChildren().add(toItem(c));
        }
        return item;
    }

    private static void expand(TreeItem<?> item, int depth) {
        if (depth < 0 || item.isLeaf()) {
            return;
        }
        item.setExpanded(true);
        for (TreeItem<?> c : item.getChildren()) {
            expand(c, depth - 1);
        }
    }

    private static final class XmlCell extends TreeCell<XmlNode> {
        @Override
        protected void updateItem(XmlNode n, boolean empty) {
            super.updateItem(n, empty);
            if (empty || n == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            setText(null);
            setGraphic(render(n));
        }
    }

    private static TextFlow render(XmlNode n) {
        TextFlow flow = new TextFlow();
        flow.getStyleClass().add("xml-row");
        switch (n.kind()) {
            case ELEMENT -> {
                flow.getChildren().add(styled(n.name(), "xml-tag"));
                for (XmlNode.Attr a : n.attributes()) {
                    flow.getChildren().add(styled(" " + a.name(), "xml-attr-name"));
                    flow.getChildren().add(styled("=", "xml-punct"));
                    flow.getChildren().add(styled('"' + oneLine(a.value()) + '"', "xml-attr-value"));
                }
                if (n.value() != null) { // text-only element: inline the text
                    flow.getChildren().add(styled(" = ", "xml-punct"));
                    flow.getChildren().add(styled('"' + oneLine(n.value()) + '"', "xml-text"));
                }
            }
            case TEXT -> flow.getChildren().add(styled(oneLine(n.value()), "xml-text"));
            case COMMENT -> flow.getChildren().add(styled("<!-- " + oneLine(n.value()) + " -->", "xml-comment"));
        }
        return flow;
    }

    private static Text styled(String text, String styleClass) {
        Text t = new Text(text);
        t.getStyleClass().add(styleClass);
        return t;
    }

    /** Collapses newlines/tabs so a multi-line value stays on one tree row. */
    private static String oneLine(String s) {
        return s == null ? "" : s.replace("\n", "\\n").replace("\r", "").replace("\t", "\\t");
    }
}
