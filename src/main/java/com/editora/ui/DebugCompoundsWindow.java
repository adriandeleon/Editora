package com.editora.ui;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.CheckBoxListCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.editora.config.ConfigManager;
import com.editora.config.RunCompound;
import com.editora.config.RunConfiguration;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Modeless editor for a project's compounds ({@link RunCompound}): run configurations debugged together, each in a
 * session of its own. A compound has a name, the saved configurations it starts (ticked, started in the order the run
 * configurations are listed) and whether stopping one stops them all. Saved to the workspace state at every change.
 */
final class DebugCompoundsWindow {

    private static final double WIDTH = 640;
    private static final double HEIGHT = 440;

    private final ConfigManager config;
    private final Consumer<RunCompound> debug;
    private final Stage stage = new Stage();
    private final ObservableList<RunCompound> items = FXCollections.observableArrayList();
    private final ListView<RunCompound> list = new ListView<>(items);
    private final TextField name = new TextField();
    private final ListView<String> members = new ListView<>();
    private final CheckBox stopAll = new CheckBox(tr("debug.compound.stopAll"));

    /** Whether each configuration is ticked in the compound shown. */
    private final java.util.Map<String, BooleanProperty> ticked = new java.util.LinkedHashMap<>();

    private boolean built;

    /** Set while the form is filled from a compound, so that filling it saves nothing. */
    private boolean loading;

    /** @param debug debugs a compound (the window's Debug button) */
    DebugCompoundsWindow(ConfigManager config, Consumer<RunCompound> debug) {
        this.config = config;
        this.debug = debug;
    }

    /** Opens the editor on compound {@code selectName}, or on a new one when it is null. */
    void show(@Nullable String selectName, Window owner) {
        if (!built) {
            build(owner);
            built = true;
        }
        items.setAll(config.getWorkspaceState().getRunCompounds());
        if (selectName == null) {
            add();
        } else {
            items.stream()
                    .filter(c -> c.name().equals(selectName))
                    .findFirst()
                    .ifPresentOrElse(
                            list.getSelectionModel()::select,
                            () -> list.getSelectionModel().selectFirst());
        }
        if (stage.isShowing()) {
            stage.toFront();
        } else {
            WindowPlacement.centerOnOwner(stage, owner, WIDTH, HEIGHT);
            stage.show();
        }
    }

    /** The editor's stage (for tests). */
    Stage stage() {
        return stage;
    }

    /** The compounds listed (for tests). */
    List<RunCompound> compounds() {
        return List.copyOf(items);
    }

    /** Ticks or unticks configuration {@code configuration} in the compound shown (for tests, as a click does). */
    void tick(String configuration, boolean on) {
        BooleanProperty p = ticked.get(configuration);
        if (p != null) {
            p.set(on);
        }
    }

    /** The compound's name field and its Stop All box (for tests). */
    TextField nameField() {
        return name;
    }

    CheckBox stopAllBox() {
        return stopAll;
    }

    private void build(Window owner) {
        stage.setTitle(tr("debug.compound.window"));
        stage.initOwner(owner);
        stage.initModality(Modality.NONE);

        list.setId("compounds-list");
        list.setPrefWidth(200);
        list.setMinWidth(160);
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(RunCompound value, boolean empty) {
                super.updateItem(value, empty);
                setText(
                        empty || value == null
                                ? null
                                : value.name().isBlank() ? tr("settings.runConfig.unnamed") : value.name());
            }
        });
        list.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> fill(now));

        name.setId("compound-name");
        name.setPromptText(tr("debug.compound.namePrompt"));
        name.setOnAction(e -> commit());
        name.focusedProperty().addListener((o, was, now) -> {
            if (!now) {
                commit();
            }
        });
        members.setCellFactory(CheckBoxListCell.forListView(this::tickedProperty));
        members.setPlaceholder(new Label(tr("debug.compound.noConfigurations")));
        stopAll.setOnAction(e -> commit());

        Label membersLabel = new Label(tr("debug.compound.members"));
        VBox form = new VBox(8, new Label(tr("debug.compound.name")), name, membersLabel, members, stopAll);
        VBox.setVgrow(members, Priority.ALWAYS);
        form.disableProperty()
                .bind(list.getSelectionModel().selectedItemProperty().isNull());

        Button add = new Button(tr("settings.runConfig.add"));
        add.setId("compound-add");
        add.setOnAction(e -> add());
        Button remove = new Button(tr("settings.runConfig.remove"));
        remove.setId("compound-remove");
        remove.disableProperty()
                .bind(list.getSelectionModel().selectedItemProperty().isNull());
        remove.setOnAction(e -> {
            int index = list.getSelectionModel().getSelectedIndex();
            if (index >= 0) {
                items.remove(index);
                persist();
            }
        });
        Button debugButton = new Button(tr("debug.compound.debug"));
        debugButton.setId("compound-debug");
        debugButton.disableProperty().bind(form.disabledProperty());
        debugButton.setOnAction(e -> {
            commit();
            RunCompound selected = list.getSelectionModel().getSelectedItem();
            if (selected != null) {
                debug.accept(selected);
            }
        });
        Button close = new Button(tr("settings.close"));
        close.getStyleClass().add("accent");
        close.setOnAction(e -> {
            commit();
            stage.close();
        });

        VBox left = new VBox(8, list);
        VBox.setVgrow(list, Priority.ALWAYS);
        HBox.setHgrow(form, Priority.ALWAYS);
        HBox editor = new HBox(12, left, form);
        VBox.setVgrow(editor, Priority.ALWAYS);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, add, remove, spacer, debugButton, close);
        buttons.setAlignment(Pos.CENTER_LEFT);
        VBox root = new VBox(12, editor, buttons);
        root.setPadding(new Insets(16));
        root.setPrefSize(WIDTH, HEIGHT);
        Scene scene = new Scene(root, WIDTH, HEIGHT);
        scene.getStylesheets()
                .add(DebugCompoundsWindow.class
                        .getResource("/com/editora/styles/app.css")
                        .toExternalForm());
        stage.setScene(scene);
        stage.setMinWidth(520);
        stage.setMinHeight(360);
    }

    private BooleanProperty tickedProperty(String configuration) {
        return ticked.computeIfAbsent(configuration, k -> {
            BooleanProperty p = new SimpleBooleanProperty();
            p.addListener((o, was, now) -> commit());
            return p;
        });
    }

    /** A new compound, named so that it does not clash, selected for its form to be filled in. */
    private void add() {
        List<String> taken = items.stream().map(RunCompound::name).toList();
        RunCompound value = new RunCompound(
                com.editora.run.RunConfigDefaults.uniqueName(tr("debug.compound.newName"), taken), List.of(), false);
        items.add(value);
        persist();
        list.getSelectionModel().select(value);
        Platform.runLater(name::requestFocus);
    }

    /** Shows {@code compound} in the form: its name, its configurations ticked among the saved ones, Stop All. */
    private void fill(@Nullable RunCompound compound) {
        loading = true;
        try {
            List<String> saved = new ArrayList<>();
            for (RunConfiguration c : config.getWorkspaceState().getRunConfigurations()) {
                saved.add(c.name());
            }
            if (compound != null) {
                for (String n : compound.configurations()) {
                    if (!saved.contains(n)) {
                        saved.add(n); // a configuration since renamed or removed: shown, so it can be unticked
                    }
                }
            }
            Set<String> on = compound == null ? Set.of() : new LinkedHashSet<>(compound.configurations());
            for (String n : saved) {
                tickedProperty(n).set(on.contains(n));
            }
            members.getItems().setAll(saved);
            name.setText(compound == null ? "" : compound.name());
            stopAll.setSelected(compound != null && compound.stopAll());
        } finally {
            loading = false;
        }
    }

    /** Saves the form into the compound shown. */
    private void commit() {
        int index = list.getSelectionModel().getSelectedIndex();
        if (loading || index < 0) {
            return;
        }
        List<String> chosen = new ArrayList<>();
        for (String n : members.getItems()) {
            BooleanProperty p = ticked.get(n);
            if (p != null && p.get()) {
                chosen.add(n);
            }
        }
        RunCompound now = new RunCompound(name.getText().strip(), chosen, stopAll.isSelected());
        if (now.equals(items.get(index))) {
            return;
        }
        loading = true; // replacing the row selects it again: nothing to fill
        try {
            items.set(index, now);
            list.getSelectionModel().select(index);
        } finally {
            loading = false;
        }
        persist();
    }

    private void persist() {
        config.getWorkspaceState().setRunCompounds(new ArrayList<>(items));
        config.save();
    }
}
