package com.editora.editor;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import org.jspecify.annotations.Nullable;

/**
 * A banner above the editor: a message, one action button, a dismiss button and a busy spinner — the
 * IntelliJ-style strip the "install language support?" offer and the shell line-ending notice use. Generic
 * (strings and runnables), so {@code editor} stays decoupled from whoever wires the actions.
 */
final class NoticeBar {

    private final HBox node;
    private final Label message = new Label();
    private final Button action = new Button();
    private final Button dismiss;
    private final ProgressIndicator progress = new ProgressIndicator();
    private @Nullable Runnable onAction;
    private @Nullable Runnable onDismiss;

    NoticeBar(String dismissLabel, String... styleClasses) {
        message.getStyleClass().add("lsp-install-title");
        message.setWrapText(true);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        progress.setPrefSize(16, 16);
        progress.setVisible(false);
        progress.setManaged(false);
        action.getStyleClass().add("accent");
        action.setOnAction(e -> {
            if (onAction != null) {
                onAction.run();
            }
        });
        dismiss = new Button(dismissLabel);
        dismiss.getStyleClass().add("lsp-install-dismiss");
        dismiss.setOnAction(e -> {
            if (onDismiss != null) {
                onDismiss.run();
            }
        });
        node = new HBox(10, message, spacer, progress, action, dismiss);
        node.getStyleClass().addAll(styleClasses);
        node.setAlignment(Pos.CENTER_LEFT);
    }

    HBox node() {
        return node;
    }

    /** Sets the content and actions; a null runnable makes its button do nothing. */
    void set(String text, String actionLabel, Runnable onActionRun, Runnable onDismissRun) {
        message.setText(text);
        action.setText(actionLabel);
        this.onAction = onActionRun;
        this.onDismiss = onDismissRun;
    }

    /** Reflects work in progress: spins the indicator and disables the buttons. */
    void setBusy(boolean busy) {
        action.setDisable(busy);
        dismiss.setDisable(busy);
        progress.setVisible(busy);
        progress.setManaged(busy);
    }

    String text() {
        return message.getText();
    }
}
