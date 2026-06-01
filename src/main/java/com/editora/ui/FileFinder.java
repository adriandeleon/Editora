package com.editora.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.Window;

/**
 * Emacs {@code find-file}-style keyboard file opener: a path field with a live, prefix-autocompleted
 * listing of the current directory. Type to filter, {@code Tab} to complete the common prefix, Enter
 * to descend into a folder or open a file (a non-existent path opens a new buffer to be written on
 * save), {@code C-n}/{@code C-p} or ↑/↓ to move, {@code Esc}/{@code C-g} to cancel. Keyboard-only.
 */
public class FileFinder {

    private static final boolean IS_MAC =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    private static final String SEP = File.separator;

    private final Supplier<Path> startDir;
    private final Consumer<Path> onChoose;
    /** When true, only directories are listed and Enter chooses a folder (instead of opening a file). */
    private final boolean pickDirectory;
    private final String title;

    private final Popup popup = new Popup();
    private final TextField input = new TextField();
    private final ListView<Path> list = new ListView<>();
    private final ObservableList<Path> items = FXCollections.observableArrayList();

    /** Cached listing of {@link #currentDir}; re-read only when the directory part of the path changes. */
    private Path currentDir;
    private List<Path> dirEntries = List.of();
    private boolean swallowNextTyped;

    public FileFinder(Supplier<Path> startDir, Consumer<Path> onChoose) {
        this(startDir, onChoose, false, "Find File");
    }

    public FileFinder(Supplier<Path> startDir, Consumer<Path> onChoose, boolean pickDirectory,
                      String title) {
        this.startDir = startDir;
        this.onChoose = onChoose;
        this.pickDirectory = pickDirectory;
        this.title = title;
        build();
    }

    private void build() {
        input.setPromptText("Type a path…");
        list.setItems(items);
        list.setPrefHeight(280);
        list.setCellFactory(v -> new EntryCell());

        input.textProperty().addListener((obs, old, now) -> refresh(now));
        input.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        input.addEventFilter(KeyEvent.KEY_TYPED, e -> {
            if (swallowNextTyped) {
                swallowNextTyped = false;
                e.consume();
                return;
            }
            if (IS_MAC && (e.isAltDown() || e.isMetaDown() || e.isControlDown() || e.isShortcutDown())) {
                e.consume();
            }
        });

        Label header = new Label(title);
        header.getStyleClass().add("palette-title");
        VBox content = new VBox(6, header, input, list);
        content.getStyleClass().add("command-palette");
        content.setPrefWidth(620);
        popup.getContent().add(content);
        popup.setAutoHide(true);
    }

    public void show(Window owner) {
        Path dir = startDir.get();
        currentDir = null;
        dirEntries = List.of();
        swallowNextTyped = true;
        // Pre-fill with the start directory + separator so the user types a name straight away.
        input.setText(dir.toString().endsWith(SEP) ? dir.toString() : dir + SEP);
        input.positionCaret(input.getText().length());
        refresh(input.getText());

        double width = 620;
        double x = owner.getX() + (owner.getWidth() - width) / 2;
        double y = owner.getY() + 90;
        popup.show(owner, x, y);
        input.requestFocus();
        input.positionCaret(input.getText().length());
        Platform.runLater(() -> swallowNextTyped = false);
    }

    public void hide() {
        popup.hide();
    }

    public boolean isShown() {
        return popup.isShowing();
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

    /** Re-lists the directory (only if it changed) and filters its entries by the typed prefix. */
    private void refresh(String text) {
        Path dir = dirPart(text).isEmpty() ? currentDir : Path.of(dirPart(text));
        if (dir != null && !dir.equals(currentDir)) {
            currentDir = dir;
            dirEntries = listDir(dir);
        }
        String prefix = prefixPart(text);
        boolean wantHidden = prefix.startsWith(".");
        String q = prefix.toLowerCase(Locale.ROOT);
        List<Path> matches = new ArrayList<>();
        for (Path p : dirEntries) {
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

    /** Directory children, sorted directories-first then files, case-insensitive; empty if unreadable. */
    private List<Path> listDir(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Path> dirs = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> entries = Files.list(dir)) {
            entries.forEach(p -> {
                if (Files.isDirectory(p)) {
                    dirs.add(p);
                } else if (!pickDirectory) {
                    files.add(p); // in directory-pick mode, list only folders
                }
            });
        } catch (IOException | RuntimeException ex) {
            return List.of();
        }
        Comparator<Path> byName =
                Comparator.comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER);
        dirs.sort(byName);
        files.sort(byName);
        List<Path> all = new ArrayList<>(dirs.size() + files.size());
        all.addAll(dirs);
        all.addAll(files);
        return all;
    }

    private void onKey(KeyEvent e) {
        switch (e.getCode()) {
            case ESCAPE -> {
                hide();
                e.consume();
            }
            case ENTER -> {
                chooseSelected();
                e.consume();
            }
            case TAB -> {
                autocomplete();
                e.consume();
            }
            case DOWN -> {
                move(1);
                e.consume();
            }
            case UP -> {
                move(-1);
                e.consume();
            }
            case N -> {
                if (e.isControlDown()) {
                    move(1);
                    e.consume();
                }
            }
            case P -> {
                if (e.isControlDown()) {
                    move(-1);
                    e.consume();
                }
            }
            case G -> {
                if (e.isControlDown()) {
                    hide();
                    e.consume();
                }
            }
            default -> {
            }
        }
    }

    private void move(int delta) {
        int size = items.size();
        if (size == 0) {
            return;
        }
        int idx = Math.floorMod(list.getSelectionModel().getSelectedIndex() + delta, size);
        list.getSelectionModel().select(idx);
        list.scrollTo(idx);
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
        String text = input.getText();
        return text.isBlank() ? null : Path.of(text);
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
            if (selected != null && Files.isDirectory(selected)) {
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
        } else if (items.size() == 1 && Files.isDirectory(items.get(0))) {
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
            boolean dir = Files.isDirectory(item);
            label.setText(item.getFileName() + (dir ? SEP : ""));
            box.getChildren().setAll(dir ? Icons.project() : Icons.fileSheet(), label);
            setGraphic(box);
        }
    }
}
