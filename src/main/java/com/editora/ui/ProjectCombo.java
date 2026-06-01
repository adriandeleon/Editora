package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.editora.config.Project;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.util.StringConverter;

/**
 * A project switcher combobox shared by the toolbar and the Project tool window. The first entry is
 * the {@link #NO_PROJECT} sentinel ("No Project", id ""), which the caller treats as "return to the
 * global session" — selecting it doesn't close any project. Selecting any entry fires {@code onSwitch}.
 *
 * <p>When an {@code onDelete} handler is set, each project row in the dropdown shows an inline ✕ that
 * deletes that project (the handler confirms first); the "No Project" row never shows one.
 */
public class ProjectCombo extends ComboBox<Project> {

    /** Sentinel for the "No Project" entry (id "" matches the no-active-project convention). */
    public static final Project NO_PROJECT = new Project("", "No Project", "");

    private boolean loading;
    private Consumer<Project> onDelete;

    public ProjectCombo(Consumer<Project> onSwitch) {
        getStyleClass().add("project-combo");
        setConverter(new StringConverter<>() {
            @Override public String toString(Project p) {
                return p == null ? "" : p.name();
            }
            @Override public Project fromString(String s) {
                return null;
            }
        });
        // The closed combo shows just the active project's name (never a delete icon).
        setButtonCell(new ListCell<>() {
            @Override protected void updateItem(Project p, boolean empty) {
                super.updateItem(p, empty);
                setText(empty || p == null ? "" : p.name());
                setGraphic(null);
            }
        });
        // Dropdown rows: name + an inline delete ✕ for real projects (when a delete handler is set).
        setCellFactory(list -> new ProjectListCell());
        valueProperty().addListener((obs, was, now) -> {
            if (!loading && now != null) {
                onSwitch.accept(now);
            }
        });
    }

    /** Enables an inline delete ✕ on each project row in the dropdown; {@code handler} confirms + deletes. */
    public void setOnDeleteProject(Consumer<Project> handler) {
        this.onDelete = handler;
    }

    /** Populates the list ("No Project" + {@code all}) and selects {@code activeId} (or "No Project"). */
    public void setProjects(List<Project> all, String activeId) {
        loading = true;
        try {
            List<Project> items = new ArrayList<>();
            items.add(NO_PROJECT);
            items.addAll(all);
            getItems().setAll(items);
            setValue(all.stream().filter(p -> p.id().equals(activeId)).findFirst().orElse(NO_PROJECT));
        } finally {
            loading = false;
        }
    }

    private final class ProjectListCell extends ListCell<Project> {
        @Override
        protected void updateItem(Project p, boolean empty) {
            super.updateItem(p, empty);
            if (empty || p == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            if (onDelete == null || p.id().isEmpty()) { // "No Project" (or no delete handler): name only
                setText(p.name());
                setGraphic(null);
                return;
            }
            Label name = new Label(p.name());
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            Button delete = new Button();
            delete.setGraphic(Icons.closeSmall());
            delete.getStyleClass().addAll("button-icon", "flat", "recent-remove");
            delete.setFocusTraversable(false);
            delete.setTooltip(new Tooltip("Delete project"));
            // Delete on press without selecting the row / closing the popup unexpectedly.
            delete.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
                onDelete.accept(p);
                e.consume();
            });
            HBox box = new HBox(8, name, spacer, delete);
            box.setAlignment(Pos.CENTER_LEFT);
            setText(null);
            setGraphic(box);
        }
    }
}
