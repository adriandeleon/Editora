package com.editora.ui;

import static com.editora.i18n.Messages.tr;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * A small popup listing the {@link MessageLog} (the status-bar echo messages from this session),
 * newest first, each with an {@code HH:mm:ss} time indicator. Scrollable when the messages overflow.
 * Anchored just above the status-bar echo area; Esc or a click outside closes it. A footer "Clear"
 * action empties the session log.
 *
 * <p>Pure view: the owner supplies the {@link MessageLog} via {@link #show}.
 */
public final class MessageLogPopup {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** Shared in-scene overlay host (injected by MainController) + shown state. */
    private OverlayHost overlayHost;

    private boolean showing;
    /** When the popup last hid — used so a click on the echo that auto-hid it doesn't immediately reopen. */
    private long lastHiddenAt;

    private final ListView<MessageLog.Entry> list = new ListView<>();
    private final VBox root;
    private MessageLog log;

    public MessageLogPopup() {
        Label header = new Label(tr("messagelog.title"));
        header.getStyleClass().add("message-log-header");

        list.getStyleClass().add("message-log-list");
        list.setPrefSize(552, 280); // 20% wider than the original 460
        // Rows are selectable (multiple) so they can be copied to the clipboard.
        list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        Label empty = new Label(tr("messagelog.empty"));
        empty.getStyleClass().add("message-log-empty");
        list.setPlaceholder(empty);
        list.setCellFactory(v -> new ListCell<>() {
            private final Label time = new Label();
            private final Label msg = new Label();
            private final HBox box = new HBox(10, time, msg);

            {
                time.getStyleClass().add("message-log-time");
                time.setMinWidth(Region.USE_PREF_SIZE); // never clip the timestamp
                msg.getStyleClass().add("message-log-text");
                msg.setWrapText(true); // long messages wrap (row grows in height) instead of scrolling sideways
                box.setAlignment(Pos.TOP_LEFT); // keep the timestamp at the top of a wrapped block
                HBox.setHgrow(msg, Priority.ALWAYS);
                // wrapText needs a DEFINITE width to compute its (multi-line) height and grow the row;
                // bind the message to the list viewport minus the time column + gaps + scrollbar/padding,
                // so even an unbroken long path wraps within the popup and never scrolls sideways.
                msg.maxWidthProperty().bind(list.widthProperty().subtract(110));
                msg.setMinHeight(Region.USE_PREF_SIZE);
                setPrefWidth(0);
            }

            @Override
            protected void updateItem(MessageLog.Entry item, boolean isEmpty) {
                super.updateItem(item, isEmpty);
                if (isEmpty || item == null) {
                    setGraphic(null);
                    return;
                }
                time.setText(
                        TIME_FMT.format(Instant.ofEpochMilli(item.epochMillis()).atZone(ZoneId.systemDefault())));
                msg.setText(item.text());
                setGraphic(box);
            }
        });

        // Copy button: the discoverable replacement for the old per-row right-click menu. Copies the
        // selected messages, or all of them when nothing is selected.
        Button copy = new Button(tr("messagelog.copy"));
        copy.setGraphic(Icons.copy());
        copy.getStyleClass().addAll("flat");
        copy.setFocusTraversable(false);
        copy.setOnAction(e -> {
            if (list.getSelectionModel().getSelectedItems().isEmpty()) {
                list.getSelectionModel().selectAll();
            }
            copySelection();
        });

        Button clear = new Button(tr("messagelog.clear"));
        clear.getStyleClass().addAll("flat");
        clear.setFocusTraversable(false);
        clear.setOnAction(e -> {
            if (log != null) {
                log.clear();
            }
            refreshItems();
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label hint = new Label(tr("messagelog.hint"));
        hint.getStyleClass().add("message-log-hint");
        HBox footer = new HBox(8, copy, clear, spacer, hint);
        footer.getStyleClass().add("message-log-footer");
        footer.setAlignment(Pos.CENTER_LEFT);

        root = new VBox(6, header, list, footer);
        root.getStyleClass().add("message-log");
        root.setMaxSize(552, Region.USE_PREF_SIZE); // hug content; don't stretch to fill the overlay
        root.getProperties().put("editora.ownsKeys", Boolean.TRUE); // keep nav/copy keys for the popup
        root.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            // Close on Esc, C-g (the app's keyboard-quit), or M-g. (The OverlayHost also handles Esc/C-g,
            // but keep this so M-g closes it too.)
            if (e.getCode() == KeyCode.ESCAPE || (e.getCode() == KeyCode.G && (e.isControlDown() || e.isAltDown()))) {
                hide();
                e.consume();
                return;
            }
            // Copy the selected message(s) with the platform copy shortcut (⌘C / Ctrl+C).
            if (e.getCode() == KeyCode.C && e.isShortcutDown()) {
                copySelection();
                e.consume();
            }
        });
    }

    /** Injects the shared overlay host used to show the message-log card. */
    public void setOverlayHost(OverlayHost overlayHost) {
        this.overlayHost = overlayHost;
    }

    /**
     * Toggles the popup: shows it if hidden, hides it if shown. A click on the status echo while the
     * popup is open first triggers autoHide (which hides it), then the click handler — so we also treat a
     * very recent auto-hide as "was open" and don't reopen, giving a clean toggle.
     */
    public void toggle(Window owner, Node anchor, MessageLog log) {
        if (showing) {
            hide();
            return;
        }
        if (System.currentTimeMillis() - lastHiddenAt < 250) {
            return; // the backdrop click just closed it via this same click — leave it closed
        }
        show(owner, anchor, log);
    }

    /** Copies the selected messages (one per line) to the system clipboard. */
    private void copySelection() {
        String text = list.getSelectionModel().getSelectedItems().stream()
                .filter(java.util.Objects::nonNull)
                .map(MessageLog.Entry::text)
                .collect(Collectors.joining("\n"));
        if (text.isEmpty()) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    /** Shows the log (newest first) anchored just above {@code anchor}. {@code owner} is unused (kept
     *  for call-site compatibility); the shared {@link OverlayHost} positions it within the main scene. */
    public void show(Window owner, Node anchor, MessageLog log) {
        if (overlayHost == null) {
            return;
        }
        this.log = log;
        refreshItems();
        if (!list.getItems().isEmpty()) {
            list.getSelectionModel().clearAndSelect(0); // newest, so ⌘C copies the latest by default
        }
        showing = true;
        overlayHost.show(root, anchor, list::requestFocus, () -> {
            showing = false;
            lastHiddenAt = System.currentTimeMillis();
        });
    }

    private void refreshItems() {
        list.getItems().setAll(log == null ? java.util.List.of() : log.entries());
    }

    public void hide() {
        if (overlayHost != null) {
            overlayHost.hide();
        }
    }

    public boolean isShown() {
        return showing;
    }
}
