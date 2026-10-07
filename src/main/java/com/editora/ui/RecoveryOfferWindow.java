package com.editora.ui;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.editora.recovery.RecoveryStore;

import static com.editora.i18n.Messages.tr;

/**
 * The modeless "these unsaved edits were kept" window shown after Editora ended without its windows being
 * closed: one row per recovered buffer (name, location, when the copy was taken, and whether the file has
 * changed since), with Restore and Discard for the selection or for everything.
 *
 * <p>It never blocks the editor and never decides for the user: <kbd>Enter</kbd> restores (which only opens
 * unsaved tabs), <kbd>Esc</kbd> and the window's close button keep everything for later, and discarding takes
 * a click on a Discard button plus the confirmation its owner asks for.
 */
final class RecoveryOfferWindow {

    private final Stage stage = new Stage();
    private final ListView<RecoveryStore.Entry> list = new ListView<>();
    private final Label unreadableLabel = new Label();
    private final Button restoreSelected = new Button(tr("recovery.restoreSelected"));
    private final Button discardSelected = new Button(tr("recovery.discardSelected"));
    private final Button restoreAll = new Button(tr("recovery.restoreAll"));
    private final Button discardAll = new Button(tr("recovery.discardAll"));
    private final Button later = new Button(tr("recovery.later"));

    private final Supplier<List<RecoveryStore.Entry>> entries;
    private final Supplier<List<Path>> unreadable;
    private final Path recoveryDir;
    private final Consumer<List<RecoveryStore.Entry>> onRestore;
    private final Consumer<List<RecoveryStore.Entry>> onDiscard;
    private final Runnable onLater;
    private boolean built;
    /** True while the window is being hidden because nothing is left, which is not "decide later". */
    private boolean closingEmpty;

    RecoveryOfferWindow(
            Supplier<List<RecoveryStore.Entry>> entries,
            Supplier<List<Path>> unreadable,
            Path recoveryDir,
            Consumer<List<RecoveryStore.Entry>> onRestore,
            Consumer<List<RecoveryStore.Entry>> onDiscard,
            Runnable onLater) {
        this.entries = entries;
        this.unreadable = unreadable;
        this.recoveryDir = recoveryDir;
        this.onRestore = onRestore;
        this.onDiscard = onDiscard;
        this.onLater = onLater;
    }

    void show(Window owner) {
        if (!built) {
            build(owner);
            built = true;
        }
        refresh();
        if (stage.isShowing()) {
            stage.toFront();
        } else if (!list.getItems().isEmpty()) {
            WindowPlacement.centerOnOwner(stage, owner, 720, 460);
            stage.show();
            restoreAll.requestFocus();
        }
    }

    boolean isShowing() {
        return stage.isShowing();
    }

    /** Closes the window without it counting as the user's "decide later". */
    void close() {
        closingEmpty = true;
        try {
            stage.hide();
        } finally {
            closingEmpty = false;
        }
    }

    /** Re-reads the records still undecided; closes the window when none is left. */
    void refresh() {
        if (!built) {
            return;
        }
        List<RecoveryStore.Entry> selected =
                List.copyOf(list.getSelectionModel().getSelectedItems());
        list.getItems().setAll(entries.get());
        for (RecoveryStore.Entry entry : selected) {
            if (list.getItems().contains(entry)) {
                list.getSelectionModel().select(entry);
            }
        }
        List<Path> bad = unreadable.get();
        unreadableLabel.setText(bad.isEmpty() ? "" : tr("recovery.unreadable", bad.size(), recoveryDir.toString()));
        unreadableLabel.setVisible(!bad.isEmpty());
        unreadableLabel.setManaged(!bad.isEmpty());
        updateButtons();
        if (list.getItems().isEmpty() && stage.isShowing()) {
            close();
        }
    }

    // Test seams: the controls, so a headless test can read the rows and fire the buttons a user would.

    ListView<RecoveryStore.Entry> listForTest() {
        return list;
    }

    Button restoreAllForTest() {
        return restoreAll;
    }

    Button discardAllForTest() {
        return discardAll;
    }

    Button laterForTest() {
        return later;
    }

    private void build(Window owner) {
        stage.setTitle(tr("recovery.title"));
        if (owner != null) {
            stage.initOwner(owner); // stays above its editor window without being modal
        }
        Label heading = new Label(tr("recovery.heading"));
        heading.setWrapText(true);
        heading.getStyleClass().add("text-bold");
        Label note = new Label(tr("recovery.note"));
        note.setWrapText(true);
        note.getStyleClass().add("text-muted");
        unreadableLabel.setWrapText(true);
        unreadableLabel.getStyleClass().add("warning");

        list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        list.setCellFactory(view -> new EntryCell());
        list.getSelectionModel()
                .getSelectedItems()
                .addListener((javafx.collections.ListChangeListener<RecoveryStore.Entry>) change -> updateButtons());
        VBox.setVgrow(list, Priority.ALWAYS);

        restoreSelected.setOnAction(e -> onRestore.accept(selection()));
        discardSelected.setOnAction(e -> onDiscard.accept(selection()));
        restoreAll.setOnAction(e -> onRestore.accept(List.copyOf(list.getItems())));
        discardAll.setOnAction(e -> onDiscard.accept(List.copyOf(list.getItems())));
        later.setOnAction(e -> stage.hide());
        // Enter takes the action that cannot lose anything; Escape keeps everything exactly as it is.
        restoreAll.setDefaultButton(true);
        later.setCancelButton(true);
        stage.setOnHidden(e -> {
            if (!closingEmpty) {
                onLater.run();
            }
        });

        Region spacer = WrapRow.setGrow(new Region()); // pushes the two closing actions to the right
        WrapRow buttons = new WrapRow(8, 8, restoreSelected, discardSelected, discardAll, spacer, later, restoreAll);

        VBox root = new VBox(10, heading, note, list, unreadableLabel, buttons);
        root.setPadding(new Insets(14));
        Scene scene = new Scene(root, 720, 460);
        scene.getStylesheets()
                .add(RecoveryOfferWindow.class
                        .getResource("/com/editora/styles/app.css")
                        .toExternalForm());
        stage.setScene(scene);
    }

    private List<RecoveryStore.Entry> selection() {
        return List.copyOf(list.getSelectionModel().getSelectedItems());
    }

    private void updateButtons() {
        boolean none = list.getSelectionModel().getSelectedItems().isEmpty();
        restoreSelected.setDisable(none);
        discardSelected.setDisable(none);
        boolean empty = list.getItems().isEmpty();
        restoreAll.setDisable(empty);
        discardAll.setDisable(empty);
    }

    /** The second line of a row: where the buffer belongs and when its copy was taken. */
    static String detail(RecoveryStore.Entry entry) {
        String where = entry.record().untitled()
                ? tr("recovery.item.untitled")
                : com.editora.config.PathDisplay.collapseHome(entry.record().path());
        String when = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(entry.record().savedAtMillis()));
        return where + "  ·  " + tr("recovery.item.savedAt", when);
    }

    /** The warning line of a row, or {@code null} when the file is as the edits left it. */
    static String warning(RecoveryStore.Entry entry) {
        return switch (entry.disk()) {
            case CHANGED -> tr("recovery.item.diskChanged");
            case MISSING -> tr("recovery.item.diskMissing");
            case REMOTE -> tr("recovery.item.remote");
            case NO_FILE, UNCHANGED, UNKNOWN -> null;
        };
    }

    private static final class EntryCell extends ListCell<RecoveryStore.Entry> {
        private final Label title = new Label();
        private final Label detail = new Label();
        private final Label warning = new Label();
        private final VBox box = new VBox(2, title, detail, warning);

        EntryCell() {
            title.getStyleClass().add("text-bold");
            detail.getStyleClass().add("text-muted");
            warning.getStyleClass().add("warning");
            warning.setWrapText(true);
        }

        @Override
        protected void updateItem(RecoveryStore.Entry entry, boolean empty) {
            super.updateItem(entry, empty);
            if (empty || entry == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            title.setText(entry.record().title());
            detail.setText(detail(entry));
            String warn = warning(entry);
            warning.setText(warn == null ? "" : warn);
            warning.setVisible(warn != null);
            warning.setManaged(warn != null);
            setGraphic(box);
            setText(null);
        }
    }
}
