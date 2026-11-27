package com.editora.editor;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import org.jspecify.annotations.Nullable;

/**
 * A banner above the editor: a message, an optional details link, one action button, a dismiss button and a busy
 * spinner — the
 * IntelliJ-style strip the "install language support?" offer and the shell line-ending notice use. Generic
 * (strings and runnables), so {@code editor} stays decoupled from whoever wires the actions.
 *
 * <p>The buttons never shrink: the message wraps onto more lines instead. Squeezed by a long message, both
 * buttons used to read "…", so a new XML user could not tell Install from Not now (XML review 2 C5).
 */
final class NoticeBar {

    private final HBox node;
    private final Label message = new Label();
    private final Button action = new Button();
    private final Button dismiss;
    private final ProgressIndicator progress = new ProgressIndicator();
    /** A link after the message to what the action does in full; hidden unless {@link #setDetails} sets one. */
    private final Hyperlink details = new Hyperlink();

    private @Nullable Runnable onAction;
    private @Nullable Runnable onDetails;
    private @Nullable Runnable onDismiss;

    NoticeBar(String dismissLabel, String... styleClasses) {
        message.getStyleClass().add("lsp-install-title");
        message.setWrapText(true);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        UiZoom.prefSize(progress, 16, 16);
        progress.setVisible(false);
        progress.setManaged(false);
        action.getStyleClass().add("accent");
        action.setMinWidth(Region.USE_PREF_SIZE);
        action.setOnAction(e -> {
            if (onAction != null) {
                onAction.run();
            }
        });
        dismiss = new Button(dismissLabel);
        dismiss.getStyleClass().add("lsp-install-dismiss");
        dismiss.setMinWidth(Region.USE_PREF_SIZE);
        dismiss.setOnAction(e -> {
            if (onDismiss != null) {
                onDismiss.run();
            }
        });
        details.setMinWidth(Region.USE_PREF_SIZE);
        details.setVisible(false);
        details.setManaged(false);
        details.setOnAction(e -> {
            if (onDetails != null) {
                onDetails.run();
            }
        });
        node = UiZoom.spacing(new HBox(message, details, spacer, progress, action, dismiss), 10);
        // The notice's action, from the keyboard: refused while the notice is hidden.
        MenuChords.offerButton(node, "buffer.noticeAction", action);
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

    /** Shows a link after the message ({@code label}) that runs {@code run}; a null label hides it. */
    void setDetails(@Nullable String label, @Nullable Runnable run) {
        boolean shown = label != null && !label.isBlank();
        details.setText(shown ? label : "");
        details.setVisible(shown);
        details.setManaged(shown);
        onDetails = shown ? run : null;
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

    /** TEST SEAM: the details link. */
    Hyperlink detailsForTest() {
        return details;
    }

    /** TEST SEAM: the action and dismiss buttons. */
    Button[] buttonsForTest() {
        return new Button[] {action, dismiss};
    }
}
