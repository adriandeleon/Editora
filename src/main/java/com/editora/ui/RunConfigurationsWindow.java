package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.StringConverter;

import com.editora.config.ConfigManager;
import com.editora.config.RunConfiguration;
import com.editora.run.RunConfigDefaults;

import static com.editora.i18n.Messages.tr;

/** Modeless editor for the run configurations owned by this window's project/session. */
public final class RunConfigurationsWindow {

    private static final double WIDTH = 760;
    private static final double HEIGHT = 560;

    private final ConfigManager config;
    private final Stage stage = new Stage();
    private final ObservableList<RunConfiguration> items = FXCollections.observableArrayList();
    private final ListView<RunConfiguration> list = new ListView<>(items);
    private final Supplier<String> suggestion;
    private final Runnable onChanged;
    private boolean built;
    private boolean loading;

    public RunConfigurationsWindow(ConfigManager config, Supplier<String> suggestion, Runnable onChanged) {
        this.config = config;
        this.suggestion = suggestion;
        this.onChanged = onChanged == null ? () -> {} : onChanged;
    }

    /** Opens the editor and selects {@code selectName} when that configuration exists. */
    public void show(String selectName, Window owner) {
        if (!built) {
            build(owner);
            built = true;
        }
        reload(selectName);
        if (stage.isShowing()) {
            stage.toFront();
        } else {
            centerOn(owner);
            stage.show();
        }
    }

    private void build(Window owner) {
        stage.setTitle(tr("run.config.title"));
        stage.initOwner(owner);
        stage.initModality(Modality.NONE);

        list.setPrefWidth(220);
        list.setMinWidth(180);
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(RunConfiguration value, boolean empty) {
                super.updateItem(value, empty);
                setText(
                        empty || value == null
                                ? null
                                : value.name().isBlank() ? tr("settings.runConfig.unnamed") : value.name());
            }
        });

        TextField name = new TextField();
        ComboBox<String> type =
                new ComboBox<>(FXCollections.observableArrayList("java", "python", "shell", "make", "npm"));
        type.setConverter(new StringConverter<>() {
            @Override
            public String toString(String value) {
                return value == null ? "" : tr("settings.runConfig.type." + value);
            }

            @Override
            public String fromString(String value) {
                return value;
            }
        });
        TextField target = field("settings.runConfig.targetPrompt");
        TextField mainClass = new TextField();
        TextField projectName = field("settings.runConfig.projectNamePrompt");
        TextField args = new TextField();
        TextField vmArgs = new TextField();
        TextField workingDir = field("settings.runConfig.workingDirPrompt");
        TextField env = field("settings.runConfig.envPrompt");
        TextField beforeLaunch = field("settings.runConfig.beforeLaunchPrompt");

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(8);
        formRow(form, 0, "settings.runConfig.name", name);
        formRow(form, 1, "settings.runConfig.type", type);
        formRow(form, 2, "settings.runConfig.target", target);
        formRow(form, 3, "settings.runConfig.mainClass", mainClass);
        formRow(form, 4, "settings.runConfig.projectName", projectName);
        formRow(form, 5, "settings.runConfig.args", args);
        formRow(form, 6, "settings.runConfig.vmArgs", vmArgs);
        formRow(form, 7, "settings.runConfig.workingDir", workingDir);
        formRow(form, 8, "settings.runConfig.env", env);
        formRow(form, 9, "settings.runConfig.beforeLaunch", beforeLaunch);
        form.setDisable(true);

        Runnable commit = () -> {
            int index = list.getSelectionModel().getSelectedIndex();
            if (index < 0 || loading) {
                return;
            }
            items.set(
                    index,
                    new RunConfiguration(
                            name.getText(),
                            type.getValue() == null ? "java" : type.getValue(),
                            target.getText(),
                            mainClass.getText(),
                            projectName.getText(),
                            args.getText(),
                            vmArgs.getText(),
                            workingDir.getText(),
                            env.getText(),
                            beforeLaunch.getText()));
            list.refresh();
            persist();
        };
        type.valueProperty().addListener((o, was, now) -> commit.run());
        for (TextField value :
                List.of(name, target, mainClass, projectName, args, vmArgs, workingDir, env, beforeLaunch)) {
            value.setOnAction(e -> commit.run());
            value.focusedProperty().addListener((o, was, now) -> {
                if (!now) {
                    commit.run();
                }
            });
        }

        list.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            loading = true;
            try {
                form.setDisable(now == null);
                name.setText(now == null ? "" : now.name());
                type.setValue(now == null ? "java" : now.type());
                target.setText(now == null ? "" : now.target());
                mainClass.setText(now == null ? "" : now.mainClass());
                projectName.setText(now == null ? "" : now.projectName());
                args.setText(now == null ? "" : now.args());
                vmArgs.setText(now == null ? "" : now.vmArgs());
                workingDir.setText(now == null ? "" : now.workingDir());
                env.setText(now == null ? "" : now.env());
                beforeLaunch.setText(now == null ? "" : now.beforeLaunch());
            } finally {
                loading = false;
            }
        });

        Button add = new Button(tr("settings.runConfig.add"));
        add.setOnAction(e -> {
            String suggested = suggestion == null ? null : suggestion.get();
            List<String> taken = items.stream().map(RunConfiguration::name).toList();
            RunConfiguration value =
                    RunConfigDefaults.newConfiguration(suggested, taken, tr("settings.runConfig.newName"));
            items.add(value);
            persist();
            list.getSelectionModel().select(value);
            Platform.runLater(() -> (suggested == null ? mainClass : name).requestFocus());
        });
        Button remove = new Button(tr("settings.runConfig.remove"));
        remove.disableProperty()
                .bind(list.getSelectionModel().selectedItemProperty().isNull());
        remove.setOnAction(e -> {
            int index = list.getSelectionModel().getSelectedIndex();
            if (index >= 0) {
                items.remove(index);
                persist();
            }
        });
        Button save = new Button(tr("settings.save"));
        save.disableProperty().bind(form.disabledProperty());
        save.setOnAction(e -> commit.run());
        Button close = new Button(tr("settings.close"));
        close.getStyleClass().add("accent");
        close.setOnAction(e -> stage.close());

        VBox left = new VBox(8, list);
        VBox.setVgrow(list, Priority.ALWAYS);
        VBox right = new VBox(8, form);
        HBox.setHgrow(right, Priority.ALWAYS);
        HBox editor = new HBox(12, left, right);
        VBox.setVgrow(editor, Priority.ALWAYS);
        HBox buttons = new HBox(8, add, remove, spacer(), save, close);
        buttons.setAlignment(Pos.CENTER_LEFT);
        VBox root = new VBox(12, editor, buttons);
        root.setPadding(new Insets(16));
        root.setPrefSize(WIDTH, HEIGHT);

        Scene scene = new Scene(root, WIDTH, HEIGHT);
        scene.getStylesheets()
                .add(RunConfigurationsWindow.class
                        .getResource("/com/editora/styles/app.css")
                        .toExternalForm());
        stage.setScene(scene);
        stage.setMinWidth(640);
        stage.setMinHeight(460);
    }

    private static TextField field(String promptKey) {
        TextField field = new TextField();
        field.setPromptText(tr(promptKey));
        return field;
    }

    private static void formRow(GridPane form, int row, String labelKey, javafx.scene.Node control) {
        Label label = new Label(tr(labelKey));
        form.add(label, 0, row);
        form.add(control, 1, row);
        GridPane.setHgrow(control, Priority.ALWAYS);
        if (control instanceof Region region) {
            region.setMaxWidth(Double.MAX_VALUE);
        }
    }

    private static Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    private void reload(String selectName) {
        String previous = selectName;
        if ((previous == null || previous.isBlank()) && list.getSelectionModel().getSelectedItem() != null) {
            previous = list.getSelectionModel().getSelectedItem().name();
        }
        items.setAll(config.getWorkspaceState().getRunConfigurations());
        RunConfiguration selected = null;
        if (previous != null) {
            for (RunConfiguration value : items) {
                if (previous.equals(value.name())) {
                    selected = value;
                    break;
                }
            }
        }
        if (selected == null && !items.isEmpty()) {
            selected = items.get(0);
        }
        list.getSelectionModel().select(selected);
        if (selected != null) {
            list.scrollTo(selected);
        }
    }

    private void persist() {
        config.getWorkspaceState().setRunConfigurations(new ArrayList<>(items));
        config.save();
        onChanged.run();
    }

    private void centerOn(Window owner) {
        if (owner != null) {
            stage.setX(owner.getX() + (owner.getWidth() - WIDTH) / 2);
            stage.setY(owner.getY() + (owner.getHeight() - HEIGHT) / 2);
        }
    }
}
