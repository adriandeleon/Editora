package com.editora.editor.markdown;

import javafx.animation.PauseTransition;
import javafx.beans.value.ObservableBooleanValue;
import javafx.scene.control.Button;
import javafx.util.Duration;

import com.editora.editor.MenuIcons;
import com.editora.i18n.Messages;

/**
 * The copy button in a preview code block's top right corner, as on GitHub: it shows while the pointer is
 * over the block, copies the block's code with one click, and turns into a check mark for a moment to say
 * it did. The right-click menu's Copy Code Block does the same, but nobody looks for it there.
 *
 * <p>It is a {@link Button}, so the preview's text selection and Find, which skip controls, never see it,
 * and it is out of the Tab order like the preview's other floating buttons.
 */
final class CodeCopyButton {

    /** Style class on the button; {@link #COPIED} is added while it shows the check mark. */
    static final String STYLE = "md-code-copy";

    static final String COPIED = "md-code-copied";

    /** How long the check mark stays before the copy glyph comes back. */
    static final Duration CONFIRM = Duration.millis(1500);

    private CodeCopyButton() {}

    /** A button copying {@code code}, shown only while {@code shown} is true (the block is hovered). */
    static Button create(String code, ObservableBooleanValue shown) {
        Button button = new Button();
        button.getStyleClass().addAll(STYLE, "flat");
        button.setFocusTraversable(false);
        button.setGraphic(MenuIcons.copy());
        PreviewButtons.name(button, Messages.tr("previewmenu.copyCode"));
        PauseTransition back = new PauseTransition(CONFIRM);
        back.setOnFinished(e -> {
            button.getStyleClass().remove(COPIED);
            button.setGraphic(MenuIcons.copy());
            PreviewButtons.name(button, Messages.tr("previewmenu.copyCode"));
        });
        button.setOnAction(e -> {
            MarkdownRenderer.copyToClipboard(code);
            if (!button.getStyleClass().contains(COPIED)) {
                button.getStyleClass().add(COPIED);
                button.setGraphic(MenuIcons.check());
                PreviewButtons.name(button, Messages.tr("markdown.preview.codeCopied"));
            }
            back.playFromStart();
        });
        // Hidden, it takes no room either: the language beside it keeps its place in the corner.
        button.visibleProperty().bind(shown);
        button.managedProperty().bind(button.visibleProperty());
        return button;
    }
}
