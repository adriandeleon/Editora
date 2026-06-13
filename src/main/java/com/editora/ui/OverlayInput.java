package com.editora.ui;

import static com.editora.i18n.Messages.tr;

import javafx.beans.value.ObservableValue;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Renders a small modal-style input form as an in-scene card in the shared {@link OverlayHost} — the
 * keyboard-friendly replacement for a {@link javafx.scene.control.Dialog} / {@link javafx.scene.control.TextInputDialog}
 * (which open a separate native window that, on Windows, doesn't reliably take OS keyboard focus). The
 * caller builds the form body (labels + fields) and reads the field values inside {@code onAccept}, which
 * runs <em>after</em> the card hides (so focus is already back in the editor). {@code Esc}/{@code C-g}
 * cancel (handled by the {@link OverlayHost}); {@code Enter} (or {@code Ctrl/Cmd+Enter} for a multi-line
 * body) accepts.
 *
 * <p>Stateless: each {@link #show} builds a fresh card. There's no blocking {@code showAndWait} — flows
 * become callback-driven.
 */
public final class OverlayInput {

    private OverlayInput() {}

    /** A secondary, left-aligned action button (e.g. <em>Delete</em> when editing an existing note). */
    public record Extra(String label, Runnable action) {}

    /**
     * A reusable single-line text prompt, so tool-window panels (which can't see the {@link OverlayHost}
     * or keymap directly) can ask {@code MainController} to show one. {@code onAccept} runs only when the
     * user accepts (Enter / OK); cancelling does nothing.
     */
    @FunctionalInterface
    public interface Prompt {
        void show(String title, String label, String initial, java.util.function.Consumer<String> onAccept);
    }

    /**
     * Shows an input form card.
     *
     * @param host               the shared overlay host
     * @param title              card title (shown bold/muted at the top)
     * @param body               the caller-built form body (labels + fields); fills the card width
     * @param focus              the field to focus on show (a {@link TextField} is select-all'd, a
     *                           {@link TextArea} gets the caret at the end)
     * @param okLabel            text for the primary (accept) button
     * @param okEnabled          nullable; when set, the primary button is enabled only while it is true
     * @param onAccept           run when the user accepts (after the card hides)
     * @param extra              nullable secondary action (e.g. Delete)
     * @param ctrlEnterToSubmit  true for a multi-line body — plain Enter inserts a newline and only
     *                           {@code Ctrl/Cmd+Enter} accepts; false — plain Enter accepts
     */
    public static void show(
            OverlayHost host,
            String title,
            Node body,
            Node focus,
            String okLabel,
            ObservableValue<? extends Boolean> okEnabled,
            Runnable onAccept,
            Extra extra,
            boolean ctrlEnterToSubmit) {
        show(host, title, body, focus, okLabel, okEnabled, onAccept, extra, ctrlEnterToSubmit, false);
    }

    /** As {@link #show} but {@code centered} places the card in the vertical middle of the overlay
     *  (instead of near the top). */
    public static void show(
            OverlayHost host,
            String title,
            Node body,
            Node focus,
            String okLabel,
            ObservableValue<? extends Boolean> okEnabled,
            Runnable onAccept,
            Extra extra,
            boolean ctrlEnterToSubmit,
            boolean centered) {
        if (host == null) {
            return;
        }
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("palette-title");

        Button ok = new Button(okLabel);
        ok.getStyleClass().add("accent");
        Button cancel = new Button(tr("dialog.cancel"));

        Runnable accept = () -> {
            if (okEnabled != null && !Boolean.TRUE.equals(okEnabled.getValue())) {
                return;
            }
            host.hide();
            onAccept.run();
        };
        ok.setOnAction(e -> accept.run());
        cancel.setOnAction(e -> host.hide());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox footer = new HBox(8);
        footer.setAlignment(Pos.CENTER_RIGHT);
        if (extra != null) {
            Button extraButton = new Button(extra.label());
            extraButton.getStyleClass().add("danger");
            extraButton.setOnAction(e -> {
                host.hide();
                extra.action().run();
            });
            footer.getChildren().add(extraButton);
        }
        footer.getChildren().addAll(spacer, cancel, ok);

        // Enable/disable the primary button as the form's validity changes (no binding so nothing is left
        // attached to a discarded card).
        if (okEnabled != null) {
            Runnable sync = () -> ok.setDisable(!Boolean.TRUE.equals(okEnabled.getValue()));
            okEnabled.addListener((o, a, b) -> sync.run());
            sync.run();
        }

        VBox card = new VBox(10, titleLabel, body, footer);
        card.getStyleClass().addAll("command-palette", "overlay-form");
        card.setPadding(new Insets(14));
        card.setMinWidth(380);
        card.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE); // hug content; don't fill the overlay
        // Keep the form's own keys (typing, caret nav) from being hijacked by the global KeyDispatcher.
        card.getProperties().put("editora.ownsKeys", Boolean.TRUE);
        card.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER) {
                if (!ctrlEnterToSubmit || e.isShortcutDown() || e.isControlDown()) {
                    accept.run();
                    e.consume();
                }
            }
        });

        host.show(
                card,
                centered,
                () -> {
                    focus.requestFocus();
                    if (focus instanceof TextField tf) {
                        tf.selectAll();
                    } else if (focus instanceof TextArea ta) {
                        ta.positionCaret(ta.getLength());
                    }
                },
                () -> {});
    }
}
