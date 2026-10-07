package com.editora.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.editora.git.GitService.CommitFile;
import com.editora.git.StashParser.StashEntry;
import com.editora.search.FuzzyMatch;

import static com.editora.i18n.Messages.tr;

/**
 * The stash list card: a filter field over the stashes (index, message, branch, age), the files of the
 * selected stash under it, and the per-stash actions as a button row and a context menu.
 *
 * <p>Driven from the filter field, like the other pickers ({@link PickerKeys}): the keymap's line-up/down
 * chords and the arrows move, Enter shows the stash's changes, the Menu key (or Shift+F10) opens the actions
 * for the selected stash, Esc closes. Pure view — the owner supplies the entries and {@link Actions}.
 */
final class GitStashPopup {

    /** What can be done with a stash, in menu order. */
    enum Action {
        SHOW("stash.action.show"),
        APPLY("stash.action.apply"),
        POP("stash.action.pop"),
        BRANCH("stash.action.branch"),
        COPY("stash.action.copy"),
        DROP("stash.action.drop");

        final String key;

        Action(String key) {
            this.key = key;
        }
    }

    interface Actions {
        /** Fetches the files {@code entry} holds (posted on the FX thread). */
        void files(StashEntry entry, Consumer<List<CommitFile>> onFiles);

        /** Runs {@code action} on {@code entry}; the card has already been hidden. */
        void run(Action action, StashEntry entry);
    }

    private final Label title = new Label(tr("stash.list.title"));
    private final TextField search = new TextField();
    private final ListView<StashEntry> list = new ListView<>();
    private final Label filesTitle = new Label();
    private final ListView<CommitFile> files = new ListView<>();
    private final Label hint = new Label();
    private final HBox buttons = new HBox(6);
    private final ContextMenu menu = new ContextMenu();
    private final VBox content;

    private final ObservableList<StashEntry> items = FXCollections.observableArrayList();
    private List<StashEntry> all = List.of();
    private Actions actions;
    private OverlayHost overlayHost;
    private boolean showing;

    /** Files already fetched in this showing, by stash commit (or ref). */
    private final Map<String, List<CommitFile>> fileCache = new HashMap<>();
    /** Bumped per selection: a file list that arrives for an earlier selection is dropped. */
    private long filesRequest;

    GitStashPopup() {
        title.getStyleClass().add("palette-title");
        search.setPromptText(tr("stash.list.filterPrompt"));
        search.textProperty().addListener((o, was, now) -> filter(now));
        search.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        com.editora.command.TextInputKeymap.installShared(search);

        list.setItems(items);
        list.setPrefHeight(190);
        list.setFocusTraversable(false);
        list.setCellFactory(v -> new StashCell());
        list.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> showFiles(now));

        filesTitle.getStyleClass().add("palette-hint");
        files.setPrefHeight(150);
        files.setFocusTraversable(false);
        files.setMouseTransparent(true); // a read-out of the selected stash, not a second list to operate
        files.setCellFactory(v -> new FileCell());
        files.setPlaceholder(new Label(tr("stash.list.loadingFiles")));

        for (Action action : Action.values()) {
            Button button = new Button(tr(action.key));
            button.setFocusTraversable(false);
            if (action == Action.DROP) {
                button.getStyleClass().add("danger");
            } else if (action == Action.SHOW) {
                button.getStyleClass().add("accent");
            }
            button.setOnAction(e -> run(action));
            buttons.getChildren().add(button);
            if (action == Action.DROP) {
                menu.getItems().add(new SeparatorMenuItem());
            }
            MenuItem item = new MenuItem(tr(action.key));
            item.setOnAction(e -> run(action));
            menu.getItems().add(item);
        }
        buttons.setAlignment(Pos.CENTER_LEFT);
        hint.getStyleClass().add("palette-hint");

        content = new VBox(6, title, search, list, filesTitle, files, buttons, hint);
        content.getStyleClass().addAll("command-palette", "stash-popup");
        content.setPrefWidth(640);
        content.setMaxSize(640, Region.USE_PREF_SIZE);
        content.getProperties().put("editora.ownsKeys", Boolean.TRUE); // keep C-n/C-p for the list
    }

    /** Shows the card with {@code entries} (newest first, as git lists them). */
    void show(OverlayHost host, List<StashEntry> entries, Actions actions) {
        if (host == null) {
            return;
        }
        this.overlayHost = host;
        this.actions = actions;
        this.all = List.copyOf(entries);
        fileCache.clear();
        hint.setText(
                PickerKeys.legend(PickerKeys.hint("open", "↵"), PickerKeys.hint("actions", tr("stash.list.menuKey"))));
        search.clear();
        filter("");
        showing = true;
        host.show(content, search::requestFocus, () -> {
            showing = false;
            menu.hide();
        });
    }

    void hide() {
        if (overlayHost != null && showing) {
            overlayHost.hide();
        }
    }

    boolean isShown() {
        return showing;
    }

    /** The rows now listed (after filtering). */
    List<StashEntry> entries() {
        return List.copyOf(items);
    }

    StashEntry selected() {
        return list.getSelectionModel().getSelectedItem();
    }

    /** The files shown for the selected stash. */
    List<CommitFile> shownFiles() {
        return List.copyOf(files.getItems());
    }

    boolean menuShowing() {
        return menu.isShowing();
    }

    void setFilter(String text) {
        search.setText(text);
    }

    TextField filterField() {
        return search;
    }

    /** Hides the card and runs {@code action} on the selected stash; nothing when no stash is selected. */
    void run(Action action) {
        StashEntry entry = selected();
        if (entry == null || actions == null) {
            return;
        }
        Actions target = actions;
        hide();
        target.run(action, entry);
    }

    private void filter(String query) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        List<StashEntry> out = new ArrayList<>();
        for (StashEntry entry : all) {
            String key = entry.ref() + " " + entry.subject() + " " + entry.branch();
            if (q.isEmpty() || FuzzyMatch.of(key, q) != null) {
                out.add(entry);
            }
        }
        items.setAll(out);
        if (out.isEmpty()) {
            list.getSelectionModel().clearSelection();
            showFiles(null);
        } else {
            list.getSelectionModel().select(0);
            list.scrollTo(0);
        }
    }

    private void showFiles(StashEntry entry) {
        long request = ++filesRequest;
        if (entry == null) {
            files.getItems().clear();
            filesTitle.setText("");
            return;
        }
        String key = entry.hash().isBlank() ? entry.ref() : entry.hash();
        List<CommitFile> cached = fileCache.get(key);
        if (cached != null) {
            setFiles(entry, cached);
            return;
        }
        files.getItems().clear();
        filesTitle.setText(tr("stash.list.files", entry.ref()));
        if (actions != null) {
            actions.files(entry, loaded -> {
                fileCache.put(key, loaded);
                if (request == filesRequest) {
                    setFiles(entry, loaded);
                }
            });
        }
    }

    private void setFiles(StashEntry entry, List<CommitFile> loaded) {
        files.getItems().setAll(loaded);
        filesTitle.setText(tr("stash.list.filesCount", entry.ref(), loaded.size()));
    }

    private void onKey(KeyEvent e) {
        if (e.getCode() == KeyCode.CONTEXT_MENU || (e.getCode() == KeyCode.F10 && e.isShiftDown())) {
            showMenu();
            e.consume();
            return;
        }
        PickerKeys.Action action = PickerKeys.action(e);
        switch (action) {
            case CANCEL -> hide();
            case ACCEPT -> run(Action.SHOW);
            default -> {
                if (!PickerKeys.navigate(list, action)) {
                    return;
                }
            }
        }
        e.consume();
    }

    /** Opens the actions of the selected stash under the list (the Menu key, Shift+F10, or a right-click). */
    void showMenu() {
        if (selected() != null && list.getScene() != null) {
            menu.show(list, Side.BOTTOM, 24, 0);
        }
    }

    /** {@code stash@{N}} · message · branch · age. */
    private final class StashCell extends ListCell<StashEntry> {
        StashCell() {
            setOnMouseClicked(e -> {
                if (isEmpty() || getItem() == null) {
                    return;
                }
                if (e.getButton() == MouseButton.SECONDARY) {
                    list.getSelectionModel().select(getItem());
                    menu.show(this, e.getScreenX(), e.getScreenY());
                } else if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                    run(Action.SHOW);
                }
            });
        }

        @Override
        protected void updateItem(StashEntry entry, boolean empty) {
            super.updateItem(entry, empty);
            if (empty || entry == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            Label ref = new Label(entry.ref());
            ref.getStyleClass().add("menu-item-chord");
            ref.setMinWidth(Region.USE_PREF_SIZE);
            Label subject = new Label(entry.subject().isBlank() ? tr("stash.list.noMessage") : entry.subject());
            subject.getStyleClass().add("menu-item-title");
            subject.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(subject, Priority.ALWAYS);
            Label branch = new Label(entry.branch());
            branch.getStyleClass().add("branch-upstream");
            branch.setMinWidth(Region.USE_PREF_SIZE);
            String age = entry.epochSeconds() <= 0
                    ? ""
                    : GitBlameCoordinator.relativeTimeLabel(entry.epochSeconds(), System.currentTimeMillis() / 1000L);
            Label when = new Label(age);
            when.getStyleClass().add("branch-upstream");
            when.setMinWidth(Region.USE_PREF_SIZE);
            HBox row = new HBox(10, ref, subject, branch, when);
            row.setAlignment(Pos.CENTER_LEFT);
            setText(null);
            setGraphic(row);
            setAccessibleText(entry.ref() + ", " + subject.getText() + ", " + entry.branch() + ", " + age);
        }
    }

    /** Status letter and path of one file in the selected stash. */
    private static final class FileCell extends ListCell<CommitFile> {
        @Override
        protected void updateItem(CommitFile file, boolean empty) {
            super.updateItem(file, empty);
            if (empty || file == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            Label status = new Label(String.valueOf(file.status()));
            status.getStyleClass().add("patch-file-status");
            status.setMinWidth(Region.USE_PREF_SIZE);
            boolean renamed = file.origPath() != null && !file.origPath().isBlank();
            Label path = new Label(renamed ? file.origPath() + " → " + file.path() : file.path());
            HBox row = new HBox(8, status, path);
            row.setAlignment(Pos.CENTER_LEFT);
            setText(null);
            setGraphic(row);
        }
    }
}
