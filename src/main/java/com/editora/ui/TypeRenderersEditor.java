package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import com.editora.dap.TypeRenderer;
import com.editora.editor.UiZoom;

import static com.editora.i18n.Messages.tr;

/**
 * Settings → Debugging's Type renderers: the renderers in a list — each with its switch, its type and language, and
 * what it shows — with Add…, Edit… (or a double-click, or Enter) and Remove beside it. Every change is saved at once.
 */
final class TypeRenderersEditor {

    /** What the editor borrows from the Settings window: the stored list, saving it, the form, its owner. */
    record Hooks(
            Supplier<List<TypeRenderer>> stored,
            Consumer<List<TypeRenderer>> persist,
            Editor editor,
            Supplier<Window> owner) {}

    /** The renderer form (the dialog; a test answers it without a window). */
    interface Editor {
        Optional<TypeRenderer> edit(Window owner, TypeRenderer initial, boolean isNew);
    }

    private final Hooks hooks;
    private final ObservableList<TypeRenderer> items = FXCollections.observableArrayList();
    private final ListView<TypeRenderer> list = new ListView<>(items);
    private final Button edit = new Button(tr("typerenderer.edit"));
    private final Button remove = new Button(tr("typerenderer.remove"));
    private final HBox node;

    TypeRenderersEditor(Hooks hooks) {
        this.hooks = hooks;
        UiZoom.prefSize(list, 360, 150);
        list.setAccessibleText(tr("settings.debug.typeRenderers"));
        Label empty = new Label(tr("typerenderer.empty"));
        empty.setWrapText(true);
        list.setPlaceholder(empty);
        list.setCellFactory(lv -> new Cell());
        list.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                editSelected();
            }
        });
        list.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                editSelected();
                e.consume();
            } else if (e.getCode() == KeyCode.DELETE || e.getCode() == KeyCode.BACK_SPACE) {
                removeSelected();
                e.consume();
            }
        });
        Button add = new Button(tr("typerenderer.add"));
        add.setOnAction(e -> add());
        edit.setOnAction(e -> editSelected());
        remove.setOnAction(e -> removeSelected());
        remove.getStyleClass().add("danger");
        edit.disableProperty()
                .bind(list.getSelectionModel().selectedItemProperty().isNull());
        remove.disableProperty()
                .bind(list.getSelectionModel().selectedItemProperty().isNull());
        // Its right-click menu: Edit… and Remove, as the buttons, Enter and Delete do, and Copy (MENU-05).
        ListRowCommands.install(
                list,
                ListRowCommands.buttonRow(list, ListRowCommands.OPEN, edit, null),
                ListRowCommands.copyRow(list, () -> ListRowCommands.selectedText(list, TypeRenderers::label)),
                new javafx.scene.control.SeparatorMenuItem(),
                ListRowCommands.danger(ListRowCommands.buttonRow(list, ListRowCommands.REMOVE, remove, null)));
        for (Button b : List.of(add, edit, remove)) {
            b.setMaxWidth(Double.MAX_VALUE);
        }
        VBox buttons = UiZoom.spacing(new VBox(add, edit, remove), 6);
        HBox.setHgrow(list, Priority.ALWAYS);
        node = UiZoom.spacing(new HBox(list, buttons), 8);
        node.setAlignment(Pos.TOP_LEFT);
        reload();
    }

    HBox node() {
        return node;
    }

    /** The stored renderers listed again (Settings loaded, or changed from the Variables menu). */
    void reload() {
        List<TypeRenderer> copies = new ArrayList<>();
        hooks.stored().get().forEach(r -> copies.add(r.copy()));
        items.setAll(copies);
    }

    private void add() {
        hooks.editor().edit(hooks.owner().get(), new TypeRenderer(), true).ifPresent(r -> {
            items.add(r);
            list.getSelectionModel().select(r);
            save();
        });
    }

    private void editSelected() {
        int at = list.getSelectionModel().getSelectedIndex();
        if (at < 0) {
            return;
        }
        hooks.editor().edit(hooks.owner().get(), items.get(at).copy(), false).ifPresent(r -> {
            items.set(at, r);
            list.getSelectionModel().select(at);
            save();
        });
    }

    private void removeSelected() {
        int at = list.getSelectionModel().getSelectedIndex();
        if (at >= 0) {
            items.remove(at);
            save();
        }
    }

    private void save() {
        List<TypeRenderer> copies = new ArrayList<>();
        items.forEach(r -> copies.add(r.copy()));
        hooks.persist().accept(copies);
    }

    /** A renderer's row: its switch, then its type and language, then what it shows, muted. */
    private final class Cell extends ListCell<TypeRenderer> {
        @Override
        protected void updateItem(TypeRenderer r, boolean empty) {
            super.updateItem(r, empty);
            setText(null);
            if (empty || r == null) {
                setGraphic(null);
                return;
            }
            CheckBox on = new CheckBox();
            on.setSelected(r.isEnabled());
            on.setAccessibleText(tr("typerenderer.enabled"));
            on.selectedProperty().addListener((o, was, now) -> {
                r.setEnabled(now);
                save();
            });
            Label type = new Label(TypeRenderers.label(r));
            String shows = r.getValue().isBlank() ? "" : r.getValue();
            int children = TypeRenderers.children(r.getChildren()).size();
            if (children > 0) {
                shows = (shows.isEmpty() ? "" : shows + " · ") + tr("typerenderer.childCount", children);
            }
            Label detail = new Label(shows);
            detail.getStyleClass().add("settings-note");
            HBox row = UiZoom.spacing(new HBox(on, type, detail), 8);
            row.setAlignment(Pos.CENTER_LEFT);
            setGraphic(row);
        }
    }

    // --- test reads ----------------------------------------------------------------------------------------

    ListView<TypeRenderer> list() {
        return list;
    }
}
