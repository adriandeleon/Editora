package com.editora.editor;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;

import static com.editora.i18n.Messages.tr;

/**
 * The floating bar shown above a non-empty selection offering the AI actions that apply to it —
 * currently Explain and Rewrite (extend here as more selection-scoped AI actions are added). Modeled
 * exactly on {@link MarkdownFormatBar}: a non-focus-traversable, free-positioned {@link HBox} of flat
 * icon buttons. Kept in the {@code editor} package (no dependency on {@code ui}) — the actual API calls
 * live in {@code ui.AiCoordinator} and reach here only via the {@link EditorBuffer} handlers it injects.
 * {@link EditorBuffer} owns the show/hide/position lifecycle.
 */
final class AiActionsBar {

    private final HBox node = new HBox();
    private final Button rewriteButton;

    AiActionsBar(EditorBuffer buffer) {
        node.getStyleClass().add("ai-actions-bar");
        node.setManaged(false); // free-positioned by EditorBuffer via resizeRelocate
        node.setFocusTraversable(false);
        node.setSpacing(2);
        node.setPadding(new Insets(3));
        node.setAlignment(Pos.CENTER_LEFT);

        Button explainButton =
                button(MenuIcons.explain(), "tooltip.ai.explainSelection", buffer::requestExplainSelection);
        rewriteButton = button(MenuIcons.rewrite(), "tooltip.ai.rewriteSelection", buffer::requestRewriteSelection);
        node.getChildren().addAll(explainButton, rewriteButton);
    }

    Node node() {
        return node;
    }

    /** Hides the Rewrite button in a read-only buffer (Explain still applies to a selection). */
    void setEditable(boolean editable) {
        rewriteButton.setVisible(editable);
        rewriteButton.setManaged(editable);
    }

    private static Button button(Node icon, String tooltipKey, Runnable action) {
        Button b = new Button();
        b.setGraphic(icon);
        b.getStyleClass().addAll("flat", "ai-actions-btn");
        b.setFocusTraversable(false);
        b.setTooltip(new Tooltip(tr(tooltipKey)));
        b.setOnAction(e -> action.run());
        return b;
    }
}
