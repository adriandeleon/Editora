package com.editora.editor;

import static com.editora.i18n.Messages.tr;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.util.StringConverter;

/**
 * The floating IntelliJ-style format bar shown above a non-empty selection in a Markdown buffer. It is a
 * plain (non-focus-traversable, free-positioned) {@link HBox} of flat buttons plus a Normal/H1–H6
 * paragraph-style dropdown; each control calls back into the owning {@link EditorBuffer}'s format
 * methods. Kept in the {@code editor} package (no dependency on {@code ui}), like {@code CompletionPopup}.
 * {@link EditorBuffer} owns its show/hide/position lifecycle.
 */
final class MarkdownFormatBar {

    private final HBox node = new HBox();

    MarkdownFormatBar(EditorBuffer buffer) {
        node.getStyleClass().add("markdown-format-bar");
        node.setManaged(false); // free-positioned by EditorBuffer via resizeRelocate
        node.setFocusTraversable(false);
        node.setSpacing(2);
        node.setPadding(new Insets(3));
        node.setAlignment(Pos.CENTER_LEFT);

        node.getChildren()
                .addAll(
                        button("B", "tooltip.markdown.bold", "md-fmt-bold", () -> buffer.formatInline("**")),
                        button("I", "tooltip.markdown.italic", "md-fmt-italic", () -> buffer.formatInline("*")),
                        button("S", "tooltip.markdown.strikethrough", "md-fmt-strike", () -> buffer.formatInline("~~")),
                        button("</>", "tooltip.markdown.code", null, () -> buffer.formatInline("`")),
                        button("Link", "tooltip.markdown.link", null, buffer::formatLinkFromClipboard),
                        button("List", "tooltip.markdown.bulletList", null, buffer::formatBulletList),
                        new Separator(javafx.geometry.Orientation.VERTICAL),
                        headingBox(buffer));
    }

    Node node() {
        return node;
    }

    private static Button button(String text, String tooltipKey, String styleClass, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().addAll("flat", "md-fmt-btn");
        if (styleClass != null) {
            b.getStyleClass().add(styleClass);
        }
        b.setFocusTraversable(false);
        b.setTooltip(new Tooltip(tr(tooltipKey)));
        b.setOnAction(e -> action.run());
        return b;
    }

    private static ComboBox<Integer> headingBox(EditorBuffer buffer) {
        ComboBox<Integer> box = new ComboBox<>(FXCollections.observableArrayList(0, 1, 2, 3, 4, 5, 6));
        box.getStyleClass().add("md-fmt-heading");
        box.setFocusTraversable(false);
        box.setPromptText(tr("markdown.heading.style"));
        box.setConverter(new StringConverter<>() {
            @Override
            public String toString(Integer n) {
                if (n == null) {
                    return "";
                }
                return n == 0 ? tr("markdown.heading.normal") : tr("markdown.heading.h" + n);
            }

            @Override
            public Integer fromString(String s) {
                return null;
            }
        });
        box.setOnAction(e -> {
            Integer v = box.getValue();
            if (v != null) {
                buffer.setHeadingLevel(v);
            }
        });
        return box;
    }
}
