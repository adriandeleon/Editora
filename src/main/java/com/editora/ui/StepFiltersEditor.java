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

import com.editora.dap.StepFilter;
import com.editora.editor.UiZoom;

import static com.editora.i18n.Messages.tr;

/**
 * Settings → Debugging's Don't step into list: the filters — each with its switch, its pattern and language — with
 * Add…, Edit… (or a double-click, or Enter) and Remove beside it. Every change is saved at once.
 */
final class StepFiltersEditor {

    /** What the editor borrows from the Settings window: the stored list, saving it, the form, its owner. */
    record Hooks(
            Supplier<List<StepFilter>> stored,
            Consumer<List<StepFilter>> persist,
            Editor editor,
            Supplier<Window> owner) {}

    /** The filter form (the dialog; a test answers it without a window). */
    interface Editor {
        Optional<StepFilter> edit(Window owner, StepFilter initial, boolean isNew);
    }

    private final Hooks hooks;
    private final ObservableList<StepFilter> items = FXCollections.observableArrayList();
    private final ListView<StepFilter> list = new ListView<>(items);
    private final Button edit = new Button(tr("stepfilter.edit"));
    private final Button remove = new Button(tr("stepfilter.remove"));
    private final HBox node;

    StepFiltersEditor(Hooks hooks) {
        this.hooks = hooks;
        UiZoom.prefSize(list, 360, 130);
        list.setAccessibleText(tr("settings.debug.stepFilterList"));
        Label empty = new Label(tr("stepfilter.empty"));
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
        Button add = FocusCommands.press(new Button(tr("stepfilter.add")), "settings.addStepFilter");
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
                ListRowCommands.copyRow(list, () -> ListRowCommands.selectedText(list, StepFilter::getPattern)),
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

    /** The stored filters listed again (Settings loaded, or one added from the call stack). */
    void reload() {
        List<StepFilter> copies = new ArrayList<>();
        hooks.stored().get().forEach(f -> copies.add(f.copy()));
        items.setAll(copies);
    }

    private void add() {
        hooks.editor().edit(hooks.owner().get(), new StepFilter(), true).ifPresent(f -> {
            items.add(f);
            list.getSelectionModel().select(f);
            save();
        });
    }

    private void editSelected() {
        int at = list.getSelectionModel().getSelectedIndex();
        if (at < 0) {
            return;
        }
        hooks.editor().edit(hooks.owner().get(), items.get(at).copy(), false).ifPresent(f -> {
            items.set(at, f);
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
        List<StepFilter> copies = new ArrayList<>();
        items.forEach(f -> copies.add(f.copy()));
        hooks.persist().accept(copies);
    }

    /** A filter's row: its switch, its pattern, then its language, muted. */
    private final class Cell extends ListCell<StepFilter> {
        @Override
        protected void updateItem(StepFilter f, boolean empty) {
            super.updateItem(f, empty);
            setText(null);
            if (empty || f == null) {
                setGraphic(null);
                return;
            }
            CheckBox on = new CheckBox();
            on.setSelected(f.isEnabled());
            on.setAccessibleText(tr("stepfilter.enabled"));
            on.selectedProperty().addListener((o, was, now) -> {
                f.setEnabled(now);
                save();
            });
            Label pattern = new Label(f.getPattern());
            Label language = new Label(StepFilterDialog.languageName(f.getLanguage()));
            language.getStyleClass().add("settings-note");
            HBox row = UiZoom.spacing(new HBox(on, pattern, language), 8);
            row.setAlignment(Pos.CENTER_LEFT);
            setGraphic(row);
        }
    }

    // --- test reads ----------------------------------------------------------------------------------------

    ListView<StepFilter> list() {
        return list;
    }
}
