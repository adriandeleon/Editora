package com.editora.ui;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import com.editora.command.TextInputKeymap;

import static com.editora.i18n.Messages.tr;

/**
 * Emacs {@code find-file}-style keyboard file opener: a path field with a live, prefix-autocompleted
 * listing of the current directory. Type to filter, {@code Tab} to complete the common prefix, Enter
 * to descend into a folder or open a file (a non-existent path opens a new buffer to be written on
 * save), ↑/↓ (or the keymap's line-up/down chords) to move, {@code Esc} (or the keymap's cancel) to cancel.
 * Keyboard-only; see {@link PickerKeys}.
 */
public class FileFinder {

    private static final String SEP = File.separator;

    private final Supplier<Path> startDir;
    private final Consumer<Path> onChoose;
    /** When true, only directories are listed and Enter chooses a folder (instead of opening a file). */
    private final boolean pickDirectory;

    private final String title;

    private final TextField input = new TextField();
    private final ListView<Path> list = new ListView<>();
    /** The key legend under the list; rebuilt from the live keymap each time the finder is shown. */
    private final Label hint = new Label();

    private final ObservableList<Path> items = FXCollections.observableArrayList();

    /** Shared in-scene overlay host (injected by MainController) + the card it shows. */
    private OverlayHost overlayHost;

    private VBox content;
    private boolean showing;

    /** Cached listing of {@link #currentDir}; re-read only when the directory part of the path changes. */
    private Path currentDir;

    /** The listing of {@link #currentDir}, once its read has landed; knows which entries are folders. */
    private DirectoryListing listing = DirectoryListing.UNREADABLE;

    private long listGeneration;

    /** Reads a directory off the FX thread; replaceable so a test can hold or count the read. */
    volatile java.util.function.BiFunction<Path, Boolean, DirectoryListing> directoryReader = DirectoryListing::read;

    public FileFinder(Supplier<Path> startDir, Consumer<Path> onChoose) {
        this(startDir, onChoose, false, tr("filefinder.title"));
    }

    public FileFinder(Supplier<Path> startDir, Consumer<Path> onChoose, boolean pickDirectory, String title) {
        this.startDir = startDir;
        this.onChoose = onChoose;
        this.pickDirectory = pickDirectory;
        this.title = title;
        build();
    }

    private void build() {
        input.setPromptText(tr("filefinder.prompt"));
        list.setItems(items);
        list.setPrefHeight(280);
        list.setCellFactory(v -> new EntryCell());

        input.textProperty().addListener((obs, old, now) -> refresh(now));
        input.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        // Emacs caret movement + basic editing in the path field. Registered after onKey so the finder's own
        // list navigation (C-n/C-p/C-g) consumes those chords first and the keymap yields to it (isConsumed).
        TextInputKeymap.installShared(input);
        Label header = new Label(title);
        header.getStyleClass().add("palette-title");
        hint.getStyleClass().add("palette-hint");
        content = new VBox(6, header, input, list, hint);
        content.getStyleClass().add("command-palette");
        content.setPrefWidth(620);
        content.setMaxSize(620, Region.USE_PREF_SIZE);
        content.getProperties().put("editora.ownsKeys", Boolean.TRUE);
    }

    /** Injects the shared overlay host used to show the picker card. */
    public void setOverlayHost(OverlayHost overlayHost) {
        this.overlayHost = overlayHost;
    }

    /** Shows the finder as a centered in-scene overlay. {@code owner} is unused (kept for call-site
     *  compatibility); the shared {@link OverlayHost} positions it within the main scene. */
    public void show(Window owner) {
        if (overlayHost == null) {
            return;
        }
        Path dir = startDir.get();
        currentDir = null;
        listing = DirectoryListing.UNREADABLE;
        listGeneration++; // a read still in flight belongs to the previous showing
        hint.setText(PickerKeys.legend(PickerKeys.hint("complete", "tab"), PickerKeys.hint("open", "↵")));
        // Pre-fill with the start directory + separator so the user types a name straight away.
        input.setText(dir.toString().endsWith(SEP) ? dir.toString() : dir + SEP);
        input.positionCaret(input.getText().length());
        refresh(input.getText());
        showing = true;
        overlayHost.show(
                content,
                () -> {
                    input.requestFocus();
                    // A TextField selects all its text on focus-gain (asynchronously), which would highlight the
                    // pre-filled path; clear the selection after focus settles and leave the caret at the end.
                    Platform.runLater(() -> {
                        input.deselect();
                        input.positionCaret(input.getText().length());
                    });
                },
                () -> showing = false);
    }

    public void hide() {
        if (overlayHost != null) {
            overlayHost.hide();
        }
    }

    public boolean isShown() {
        return showing;
    }

    /** The directory part of {@code text} (up to and including the last separator). */
    private static String dirPart(String text) {
        int slash = text.lastIndexOf(SEP);
        return slash < 0 ? "" : text.substring(0, slash + 1);
    }

    /** The name part of {@code text} (after the last separator) — what we autocomplete on. */
    private static String prefixPart(String text) {
        int slash = text.lastIndexOf(SEP);
        return slash < 0 ? text : text.substring(slash + 1);
    }

    /**
     * Filters the listed entries by the typed prefix, and — when the directory part of the path changed —
     * asks for that directory's listing. The listing is a {@code readdir} plus a {@code stat} per entry, so
     * it is read on a worker: typing a path into a large or slow (network, FUSE) folder used to freeze the
     * window at the keystroke that completed the folder's name. The list is empty until the read lands.
     */
    private void refresh(String text) {
        Path dir = dirPart(text).isEmpty() ? currentDir : Path.of(dirPart(text));
        if (dir != null && !dir.equals(currentDir)) {
            currentDir = dir;
            listing = DirectoryListing.UNREADABLE;
            long generation = ++listGeneration;
            java.util.function.BiFunction<Path, Boolean, DirectoryListing> reader = directoryReader;
            Thread.startVirtualThread(() -> {
                DirectoryListing read = reader.apply(dir, pickDirectory);
                Platform.runLater(() -> {
                    if (generation != listGeneration) {
                        return; // the path field names another directory by now
                    }
                    listing = read;
                    applyFilter(input.getText());
                });
            });
        }
        applyFilter(text);
    }

    private void applyFilter(String text) {
        String prefix = prefixPart(text);
        boolean wantHidden = prefix.startsWith(".");
        String q = prefix.toLowerCase(Locale.ROOT);
        List<Path> matches = new ArrayList<>();
        for (Path p : listing.entries()) {
            String name = p.getFileName().toString();
            if (!wantHidden && name.startsWith(".")) {
                continue; // hide dotfiles unless the user is typing a leading dot
            }
            if (name.toLowerCase(Locale.ROOT).startsWith(q)) {
                matches.add(p);
            }
        }
        items.setAll(matches);
        if (!items.isEmpty()) {
            list.getSelectionModel().select(0);
        }
    }

    private void onKey(KeyEvent e) {
        PickerKeys.Action action = PickerKeys.action(e);
        switch (action) {
            case CANCEL -> hide();
            case ACCEPT -> chooseSelected();
            default -> {
                if (e.getCode() == javafx.scene.input.KeyCode.TAB) {
                    autocomplete();
                } else if (!PickerKeys.navigate(list, action)) {
                    return;
                }
            }
        }
        e.consume();
    }

    /**
     * Enter: in directory-pick mode, choose the target folder; otherwise descend into a directory or
     * hand a file/new path to {@code onChoose}. (In directory mode, {@code Tab} is used to descend.)
     */
    private void chooseSelected() {
        Path target = targetPath();
        if (target == null) {
            return;
        }
        if (pickDirectory) {
            if (Files.isDirectory(target)) {
                hide();
                onChoose.accept(target);
            }
            return;
        }
        if (Files.isDirectory(target)) {
            descendInto(target);
        } else {
            hide();
            onChoose.accept(target);
        }
    }

    /** The selected list entry, or the literal typed path when nothing is selected. */
    private Path targetPath() {
        Path selected = list.getSelectionModel().getSelectedItem();
        if (selected != null) {
            return selected;
        }
        return typedPath(input.getText(), currentDir);
    }

    /**
     * The path a typed text names: a bare name belongs to the directory being listed, exactly as the list
     * shows it filtered — not to the process working directory, where {@code Path.of("notes.txt")} put a new
     * buffer and its first save.
     */
    static Path typedPath(String text, Path currentDir) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Path typed = Path.of(text);
        return typed.isAbsolute() || currentDir == null ? typed : currentDir.resolve(typed);
    }

    private void descendInto(Path dir) {
        String text = dir.toString();
        input.setText(text.endsWith(SEP) ? text : text + SEP);
        input.positionCaret(input.getText().length());
    }

    /** Tab: extend the field to the longest common prefix of the matches; lone dir match → descend. */
    private void autocomplete() {
        if (items.isEmpty()) {
            return;
        }
        if (pickDirectory) {
            // Folder picker: Tab descends into the highlighted folder (Enter chooses it).
            Path selected = list.getSelectionModel().getSelectedItem();
            if (selected != null && listing.isDirectory(selected)) {
                descendInto(selected);
            }
            return;
        }
        String lcp = items.get(0).getFileName().toString();
        for (Path p : items) {
            lcp = commonPrefix(lcp, p.getFileName().toString());
        }
        String dir = dirPart(input.getText());
        String current = prefixPart(input.getText());
        if (lcp.length() > current.length()) {
            input.setText(dir + lcp);
            input.positionCaret(input.getText().length());
        } else if (items.size() == 1 && listing.isDirectory(items.get(0))) {
            descendInto(items.get(0));
        }
    }

    private static String commonPrefix(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && Character.toLowerCase(a.charAt(i)) == Character.toLowerCase(b.charAt(i))) {
            i++;
        }
        return a.substring(0, i);
    }

    private final class EntryCell extends ListCell<Path> {
        private final Label label = new Label();
        private final HBox box = new HBox(8, label);

        EntryCell() {
            box.setAlignment(Pos.CENTER_LEFT);
            setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && !isEmpty() && getItem() != null) {
                    getListView().getSelectionModel().select(getItem());
                    chooseSelected();
                }
            });
        }

        @Override
        protected void updateItem(Path item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            boolean dir = listing.isDirectory(item); // known from the listing: no stat per rendered row
            label.setText(item.getFileName() + (dir ? SEP : ""));
            Path fileName = item.getFileName();
            box.getChildren()
                    .setAll(
                            dir
                                    ? Icons.project()
                                    : FileIcons.forFileName(fileName == null ? item.toString() : fileName.toString()),
                            label);
            setGraphic(box);
        }
    }
}
