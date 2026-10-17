package com.editora.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.editora.toolbar.ToolbarCatalog;

import static com.editora.i18n.Messages.tr;

/**
 * The Settings ▸ Toolbar editor: the catalog items not on the bar, the bar's own items in order, and the
 * buttons between them. Every edit is applied at once through {@link SettingsWindow.ToolbarActions}, which
 * sanitizes the layout (so the lists are re-read from it after each edit).
 *
 * <p>Keyboard and mouse: double-click or Enter on an available item adds it; double-click, Delete or Backspace
 * on a bar item removes it. Buttons that need a selection are disabled without one.
 */
final class ToolbarSettingsPane {

    private final Supplier<SettingsWindow.ToolbarActions> actions;
    private final ObservableList<String> currentItems = FXCollections.observableArrayList();
    private final ObservableList<String> availableItems = FXCollections.observableArrayList();
    private final ListView<String> available = new ListView<>(availableItems);
    private final ListView<String> current = new ListView<>(currentItems);
    private final Button add = new Button(tr("settings.toolbar.add"));
    private final Button remove = new Button(tr("settings.toolbar.remove"));
    private final Button up = new Button("▲");
    private final Button down = new Button("▼");
    private final Button separator = new Button(tr("settings.toolbar.addSeparator"));
    private final Button restore = new Button(tr("settings.toolbar.restoreDefault"));

    ToolbarSettingsPane(Supplier<SettingsWindow.ToolbarActions> actions) {
        this.actions = actions;
        available.setCellFactory(v -> cell());
        current.setCellFactory(v -> cell());
        // Tall enough for the default toolbar layout (~20 items) to be read without scrolling.
        available.setPrefSize(260, 460);
        current.setPrefSize(260, 460);
        available.setAccessibleText(tr("settings.toolbar.available"));
        current.setAccessibleText(tr("settings.toolbar.current"));
        wire();
        refresh();
    }

    /** The two lists with Add / Remove / ▲ / ▼ between them. */
    Node lists() {
        Label availLabel = new Label(tr("settings.toolbar.available"));
        Label curLabel = new Label(tr("settings.toolbar.current"));
        availLabel.getStyleClass().add("settings-section");
        curLabel.getStyleClass().add("settings-section");
        availLabel.setLabelFor(available);
        curLabel.setLabelFor(current);
        VBox availBox = new VBox(4, availLabel, available);
        VBox curBox = new VBox(4, curLabel, current);
        VBox.setVgrow(available, Priority.ALWAYS);
        VBox.setVgrow(current, Priority.ALWAYS);

        up.setAccessibleText(tr("bookmarks.moveUp"));
        up.setTooltip(new Tooltip(tr("bookmarks.moveUp")));
        down.setAccessibleText(tr("bookmarks.moveDown"));
        down.setTooltip(new Tooltip(tr("bookmarks.moveDown")));
        up.getStyleClass().addAll("flat", "reorder-button");
        down.getStyleClass().addAll("flat", "reorder-button");
        add.setMaxWidth(Double.MAX_VALUE);
        remove.setMaxWidth(Double.MAX_VALUE);
        Region midGap = new Region();
        VBox.setVgrow(midGap, Priority.ALWAYS);
        VBox mid = new VBox(6, add, remove, midGap, up, down);
        mid.setAlignment(Pos.CENTER);
        mid.setMinWidth(Region.USE_PREF_SIZE); // as wide as its (translated) buttons, never "Hinzufüg…"
        availLabel.setMinWidth(Region.USE_PREF_SIZE);
        curLabel.setMinWidth(Region.USE_PREF_SIZE);

        HBox lists = new HBox(10, availBox, mid, curBox);
        HBox.setHgrow(availBox, Priority.ALWAYS);
        HBox.setHgrow(curBox, Priority.ALWAYS);
        return lists;
    }

    /** Add Separator on the left, Restore Default Layout on the right. */
    Node bottomButtons() {
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        return new HBox(6, separator, gap, restore);
    }

    /** Re-reads the effective layout (the bar may have been rearranged by dragging on it). */
    void refresh() {
        SettingsWindow.ToolbarActions a = actions.get();
        if (a == null) {
            return;
        }
        List<String> cur = a.current();
        currentItems.setAll(cur);
        Set<String> used = new HashSet<>(cur);
        List<String> avail = new ArrayList<>();
        for (ToolbarCatalog.Item it : ToolbarCatalog.items()) {
            if (!used.contains(it.id())) {
                avail.add(it.id());
            }
        }
        availableItems.setAll(avail);
    }

    private void wire() {
        var availSel = available.getSelectionModel();
        var curSel = current.getSelectionModel();
        add.disableProperty().bind(availSel.selectedItemProperty().isNull());
        remove.disableProperty().bind(curSel.selectedItemProperty().isNull());
        up.disableProperty().bind(curSel.selectedIndexProperty().lessThan(1));
        down.disableProperty()
                .bind(Bindings.createBooleanBinding(
                        () -> curSel.getSelectedIndex() < 0 || curSel.getSelectedIndex() >= currentItems.size() - 1,
                        curSel.selectedIndexProperty(),
                        currentItems));
        // A second separator next to one already there is dropped by the layout sanitizer: offer none.
        BooleanBinding separatorWouldBeDropped = Bindings.createBooleanBinding(
                () -> isSeparatorNextTo(insertionIndex()), curSel.selectedIndexProperty(), currentItems);
        separator.disableProperty().bind(separatorWouldBeDropped);

        add.setOnAction(e -> addSelected());
        remove.setOnAction(e -> removeSelected());
        up.setOnAction(e -> move(-1));
        down.setOnAction(e -> move(1));
        separator.setOnAction(e -> {
            if (!live() || isSeparatorNextTo(insertionIndex())) {
                return;
            }
            int at = insertionIndex();
            currentItems.add(at, ToolbarCatalog.SEPARATOR);
            commit(at);
        });
        restore.setOnAction(e -> {
            SettingsWindow.ToolbarActions a = actions.get();
            if (a != null) {
                a.restoreDefault();
            }
            refresh();
        });

        available.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                addSelected();
            }
        });
        current.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                removeSelected();
            }
        });
        available.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER && availSel.getSelectedItem() != null) {
                addSelected();
                e.consume();
            }
        });
        current.setOnKeyPressed(e -> {
            if ((e.getCode() == KeyCode.DELETE || e.getCode() == KeyCode.BACK_SPACE)
                    && curSel.getSelectedItem() != null) {
                removeSelected();
                e.consume();
            }
        });
    }

    /** Whether a toolbar is behind the page; without one an edit would change nothing anywhere. */
    private boolean live() {
        return actions.get() != null;
    }

    /** Where a new item goes: after the selected bar item, else at the end. */
    private int insertionIndex() {
        int at = current.getSelectionModel().getSelectedIndex();
        // The selection can lag a list change by one event while a binding re-evaluates: stay in range.
        return at < 0 ? currentItems.size() : Math.min(at + 1, currentItems.size());
    }

    private boolean isSeparatorNextTo(int at) {
        return (at > 0 && ToolbarCatalog.SEPARATOR.equals(currentItems.get(at - 1)))
                || (at < currentItems.size() && ToolbarCatalog.SEPARATOR.equals(currentItems.get(at)));
    }

    private void addSelected() {
        int from = available.getSelectionModel().getSelectedIndex();
        String sel = available.getSelectionModel().getSelectedItem();
        if (sel == null || !live()) {
            return;
        }
        int at = insertionIndex();
        currentItems.add(at, sel);
        commit(at);
        // The next catalog item is selected, so Add can be pressed again for the one below.
        if (!availableItems.isEmpty()) {
            available.getSelectionModel().select(Math.min(from, availableItems.size() - 1));
        }
    }

    private void removeSelected() {
        int idx = current.getSelectionModel().getSelectedIndex();
        if (idx < 0 || !live()) {
            return;
        }
        currentItems.remove(idx);
        commit(Math.min(idx, currentItems.size() - 1));
    }

    private void move(int delta) {
        int idx = current.getSelectionModel().getSelectedIndex();
        int to = idx + delta;
        if (idx < 0 || to < 0 || to >= currentItems.size() || !live()) {
            return;
        }
        Collections.swap(currentItems, idx, to);
        commit(to);
    }

    /** Applies the working list, re-reads the effective layout, and selects (and shows) {@code reselect}. */
    private void commit(int reselect) {
        actions.get().apply(new ArrayList<>(currentItems));
        refresh();
        if (reselect >= 0 && reselect < currentItems.size()) {
            current.getSelectionModel().select(reselect);
            current.scrollTo(reselect);
        }
    }

    /** A cell showing a toolbar item's icon + label (a divider label for the separator token). */
    private static ListCell<String> cell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(String token, boolean empty) {
                super.updateItem(token, empty);
                if (empty || token == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                setText(ToolbarCoordinator.labelFor(token));
                setGraphic(ToolbarCoordinator.iconFor(token));
            }
        };
    }
}
