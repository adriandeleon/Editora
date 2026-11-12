package com.editora.ui;

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
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import com.editora.editor.UiZoom;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

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
    private @Nullable OverlayHost overlayHost;

    private boolean showing;
    /** When the popup last hid — used so a click on the echo that auto-hid it doesn't immediately reopen. */
    private long lastHiddenAt;

    /** Card width. */
    private static final double WIDTH = 773;
    /** List height. */
    private static final double LIST_HEIGHT = 336;

    private final ListView<MessageLog.Entry> list = new ListView<>();
    /** Master list; {@link #list} renders a filtered view of it so filtering keeps row identity. */
    private final javafx.collections.ObservableList<MessageLog.Entry> allEntries =
            javafx.collections.FXCollections.observableArrayList();

    private final javafx.scene.control.TextField filter = new javafx.scene.control.TextField();
    private final Button doctor = new Button(tr("messagelog.doctor"));
    private @Nullable Runnable onOpenDoctor;
    private final VBox root;
    private @Nullable MessageLog log;

    public MessageLogPopup() {
        Label header = new Label(tr("messagelog.title"));
        header.getStyleClass().add("message-log-header");

        list.getStyleClass().add("message-log-list");
        UiZoom.prefSize(list, WIDTH, LIST_HEIGHT);
        // Rows are selectable (multiple) so they can be copied to the clipboard.
        list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        Label empty = new Label(tr("messagelog.empty"));
        empty.getStyleClass().add("message-log-empty");
        list.setPlaceholder(empty);
        list.setCellFactory(v -> new ListCell<>() {
            private final Label time = new Label();
            private final Label msg = new Label();
            private final HBox box = UiZoom.spacing(new HBox(time, msg), 10);

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
                msg.maxWidthProperty()
                        .bind(list.widthProperty()
                                .subtract(UiZoom.factorProperty().multiply(110)));
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
                // Severity on the message, not the row: the time indicator stays muted so the colour marks
                // what went wrong rather than washing the whole line.
                msg.getStyleClass().removeAll("message-log-warn", "message-log-error");
                if (item.severity() == MessageLog.Severity.ERROR) {
                    msg.getStyleClass().add("message-log-error");
                } else if (item.severity() == MessageLog.Severity.WARN) {
                    msg.getStyleClass().add("message-log-warn");
                }
                setGraphic(box);
            }
        });

        // Copy button: the discoverable replacement for the old per-row right-click menu. Copies the
        // selected messages, or all of them when nothing is selected.
        // A row's menu: Copy is a command's (Message Log: Copy Selected Messages), and the keys that copy here
        // are the platform's shortcut and the keymap's own Copy chord, which is the one the row shows.
        RowMenus.install(
                list,
                () -> !list.getSelectionModel().getSelectedItems().isEmpty(),
                RowMenus.row("messageLog.copySelected", "edit.copy", Icons.copy(), this::copySelection));
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
        clear.setOnAction(e -> clear());
        // A message about a missing tool or a server that would not start is where Doctor helps, and messages
        // carry no actions of their own, so the log offers it (review C4).
        doctor.setGraphic(Icons.doctor());
        doctor.getStyleClass().addAll("flat", "message-log-doctor");
        doctor.setFocusTraversable(false);
        doctor.managedProperty().bind(doctor.visibleProperty());
        doctor.setVisible(false);
        doctor.setOnAction(e -> {
            hide();
            if (onOpenDoctor != null) {
                onOpenDoctor.run();
            }
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label hint = new Label(tr("messagelog.hint"));
        hint.getStyleClass().add("message-log-hint");
        HBox footer = UiZoom.spacing(new HBox(copy, clear, doctor, spacer, hint), 8);
        footer.getStyleClass().add("message-log-footer");
        footer.setAlignment(Pos.CENTER_LEFT);

        // Live filter over the session's messages — the log fills up fast (every save, search and status
        // echo lands here), so finding the one line you want by scrolling is the slow path.
        filter.getStyleClass().add("message-log-filter");
        filter.setPromptText(tr("messagelog.filter"));
        filter.textProperty().addListener((o, a, b) -> applyFilter());
        // Down / Enter move into the results without leaving the field, matching the filterable tool
        // windows; the list's own keys (⌘C, Esc) still work once focus is there.
        FilterFieldNav.install(filter, list, () -> {});

        root = UiZoom.spacing(new VBox(header, filter, list, footer), 6);
        root.getStyleClass().add("message-log");
        UiZoom.maxSize(root, WIDTH, Region.USE_PREF_SIZE); // hug content; don't stretch to fill the overlay
        root.getProperties().put("editora.ownsKeys", Boolean.TRUE); // keep nav/copy keys for the popup
        root.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            // Close on Esc or on the chord the active keymap binds to cancel (C-g in Emacs). Not on a fixed
            // Ctrl+G: in the CUA keymap that chord is Go to Line.
            if (PickerKeys.isCancel(e)) {
                hide();
                e.consume();
                return;
            }
            // Copy the selected message(s) on the platform's copy shortcut (⌘C / Ctrl+C) in every keymap, and
            // on the chord the active keymap binds to copy as well (M-w in Emacs).
            if (copies(
                    e.getCode() == KeyCode.C && e.isShortcutDown(),
                    keymapCommand(e, com.editora.command.TextInputKeymap.sharedKeymap()))) {
                copySelection();
                e.consume();
            }
        });
    }

    /** The command the active keymap binds to this key press, or null (no keymap, a bare modifier, unbound). */
    static @Nullable String keymapCommand(KeyEvent e, com.editora.command.@Nullable KeymapManager keymap) {
        String chord = com.editora.command.KeyDispatcher.chord(e);
        return keymap == null || chord == null ? null : keymap.commandFor(chord);
    }

    /** Whether a key press copies: the platform's copy shortcut, or the keymap's own copy chord. Pure. */
    static boolean copies(boolean platformCopy, @Nullable String keymapCommand) {
        return platformCopy || "edit.copy".equals(keymapCommand);
    }

    /** Shows an "Open Doctor" button in the footer that runs {@code action} (null hides it). */
    public void setOnOpenDoctor(Runnable action) {
        onOpenDoctor = action;
        doctor.setVisible(action != null);
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

    /**
     * Copies the selected messages (one per line) to the system clipboard; false when the log is not showing
     * or no message is selected.
     */
    boolean copySelection() {
        if (!showing) {
            return false;
        }
        String text = list.getSelectionModel().getSelectedItems().stream()
                .filter(java.util.Objects::nonNull)
                .map(MessageLog.Entry::text)
                .collect(Collectors.joining("\n"));
        return RowMenus.copy(text);
    }

    /** Shows the log (newest first) anchored just above {@code anchor}. {@code owner} is unused (kept
     *  for call-site compatibility); the shared {@link OverlayHost} positions it within the main scene. */
    public void show(Window owner, Node anchor, MessageLog log) {
        if (overlayHost == null) {
            return;
        }
        this.log = log;
        filter.clear(); // a query from the last time it was open would hide the messages that just arrived
        refreshItems();
        if (!list.getItems().isEmpty()) {
            list.getSelectionModel().clearAndSelect(0); // newest, so ⌘C copies the latest by default
        }
        showing = true;
        // Focus the filter, not the list: typing narrows straight away, and Down/Enter step into the rows.
        overlayHost.show(root, anchor, filter::requestFocus, () -> {
            showing = false;
            lastHiddenAt = System.currentTimeMillis();
        });
    }

    /** Message Log: Clear: empties the log this popup last showed, and the list if it is open. */
    void clear() {
        if (log != null) {
            log.clear();
        }
        refreshItems();
    }

    private void refreshItems() {
        allEntries.setAll(log == null ? java.util.List.of() : log.entries());
        applyFilter();
    }

    /**
     * Narrows the visible rows to those whose message contains the filter text (case-insensitive). Kept as
     * a plain re-population of the view list rather than a {@code FilteredList}: the list's items are
     * replaced wholesale on every refresh, and a bound FilteredList would have to be rebuilt alongside it.
     */
    private void applyFilter() {
        String q = filter.getText() == null ? "" : filter.getText().trim().toLowerCase(java.util.Locale.ROOT);
        if (q.isEmpty()) {
            list.getItems().setAll(allEntries);
            return;
        }
        list.getItems()
                .setAll(allEntries.stream()
                        .filter(e -> e.text() != null
                                && e.text().toLowerCase(java.util.Locale.ROOT).contains(q))
                        .toList());
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
