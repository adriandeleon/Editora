package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.editora.config.Project;

import javafx.scene.control.ComboBox;
import javafx.util.StringConverter;

/**
 * A project switcher combobox shared by the toolbar and the Project tool window. The first entry is
 * the {@link #NO_PROJECT} sentinel ("No Project", id ""), which the caller treats as "return to the
 * global session" — selecting it doesn't close any project. Selecting any entry fires {@code onSwitch}.
 */
public class ProjectCombo extends ComboBox<Project> {

    /** Sentinel for the "No Project" entry (id "" matches the no-active-project convention). */
    public static final Project NO_PROJECT = new Project("", "No Project", "");

    private boolean loading;

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
        valueProperty().addListener((obs, was, now) -> {
            if (!loading && now != null) {
                onSwitch.accept(now);
            }
        });
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
}
