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
import javafx.scene.control.CheckBox;
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
import com.editora.run.RunConfigFields;

import static com.editora.i18n.Messages.tr;

/** Modeless editor for the run configurations owned by this window's project/session. */
public final class RunConfigurationsWindow {

    private static final double WIDTH = 760;
    private static final double HEIGHT = 640;

    private final ConfigManager config;
    private final Stage stage = new Stage();
    private final ObservableList<RunConfiguration> items = FXCollections.observableArrayList();
    private final ListView<RunConfiguration> list = new ListView<>(items);
    private final Supplier<String> suggestion;
    /** A Python or shell configuration for the active script, or null — what Add proposes first. */
    private final Supplier<RunConfiguration> scriptSuggestion;
    /** The window's project root (or null): where Browse starts, and what a chosen script is relative to. */
    private final Supplier<java.nio.file.Path> projectRoot;

    private final Runnable onChanged;
    private boolean built;
    private boolean loading;

    /** Set while a commit swaps a row's value, so the selection listener leaves the form alone. */
    private boolean replacingRow;

    /** Set while the rows are re-read from the workspace state. */
    private boolean reloading;

    public RunConfigurationsWindow(ConfigManager config, Supplier<String> suggestion, Runnable onChanged) {
        this(config, suggestion, null, null, onChanged);
    }

    /**
     * @param suggestion the active Java main class, or null
     * @param scriptSuggestion a configuration for the active Python or shell script, or null
     * @param projectRoot the window's project root, or null
     */
    public RunConfigurationsWindow(
            ConfigManager config,
            Supplier<String> suggestion,
            Supplier<RunConfiguration> scriptSuggestion,
            Supplier<java.nio.file.Path> projectRoot,
            Runnable onChanged) {
        this.config = config;
        this.suggestion = suggestion;
        this.scriptSuggestion = scriptSuggestion;
        this.projectRoot = projectRoot;
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
        ComboBox<String> type = new ComboBox<>(
                FXCollections.observableArrayList("java", "python", "python-module", "shell", "groovy", "make", "npm"));
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
        TextField interpreter = field("settings.runConfig.interpreterPrompt");
        TextField interpreterOptions = field("settings.runConfig.interpreterOptionsPrompt");
        TextField mainClass = field("settings.runConfig.mainClassPrompt");
        TextField projectName = field("settings.runConfig.projectNamePrompt");
        TextField args = new TextField();
        TextField vmArgs = new TextField();
        TextField workingDir = field("settings.runConfig.workingDirPrompt");
        TextField env = field("settings.runConfig.envPrompt");
        TextField beforeLaunch = field("settings.runConfig.beforeLaunchPrompt");
        ComboBox<JdkChoice> jdk = new ComboBox<>();
        JdkChoice.configure(jdk, tr("settings.runConfig.jdk.global"));
        jdk.setId("run-config-jdk");
        CheckBox inTerminal = new CheckBox(tr("settings.runConfig.runInTerminal"));
        inTerminal.setId("run-config-in-terminal");
        inTerminal.setTooltip(new javafx.scene.control.Tooltip(tr("settings.runConfig.runInTerminal.hint")));

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(8);
        formRow(form, 0, "settings.runConfig.name", name);
        formRow(form, 1, "settings.runConfig.type", type);
        // A script's path can be picked rather than typed (Python and shell only: make and npm name targets).
        Button browse = new Button(tr("settings.runConfig.browse"));
        browse.setId("run-config-browse");
        browse.setMinWidth(Region.USE_PREF_SIZE);
        HBox targetRow = new HBox(6, target, browse);
        targetRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(target, Priority.ALWAYS);
        target.setMaxWidth(Double.MAX_VALUE);
        formRow(form, 2, "settings.runConfig.target", targetRow);
        formRow(form, 3, "settings.runConfig.interpreter", interpreter);
        formRow(form, 4, "settings.runConfig.interpreterOptions", interpreterOptions);
        // A main class can be picked as its .java file: its fully qualified name is not something to type.
        Button browseClass = new Button(tr("settings.runConfig.browse"));
        browseClass.setId("run-config-browse-main-class");
        browseClass.setMinWidth(Region.USE_PREF_SIZE);
        HBox mainClassRow = new HBox(6, mainClass, browseClass);
        mainClassRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(mainClass, Priority.ALWAYS);
        mainClass.setMaxWidth(Double.MAX_VALUE);
        formRow(form, 5, "settings.runConfig.mainClass", mainClassRow);
        formRow(form, 6, "settings.runConfig.projectName", projectName);
        formRow(form, 7, "settings.runConfig.args", args);
        formRow(form, 8, "settings.runConfig.vmArgs", vmArgs);
        formRow(form, 9, "settings.runConfig.workingDir", workingDir);
        formRow(form, 10, "settings.runConfig.env", env);
        formRow(form, 11, "settings.runConfig.beforeLaunch", beforeLaunch);
        formRow(form, 12, "settings.runConfig.jdk", jdk);
        formRow(form, 13, "settings.runConfig.console", inTerminal);
        form.setDisable(true);

        name.setId("run-config-name");
        type.setId("run-config-type");
        target.setId("run-config-target");
        interpreter.setId("run-config-interpreter");
        interpreterOptions.setId("run-config-interpreter-options");
        mainClass.setId("run-config-main-class");
        projectName.setId("run-config-module");
        vmArgs.setId("run-config-vm-args");
        args.setId("run-config-args");
        // Only the fields the selected type launches with are editable: a script has no main class, module,
        // VM arguments or JDK, and a Java main class has no script/target. A disabled field keeps its text, so
        // switching the type back and forth loses nothing.
        // The rows a type does not use are hidden as well, so a shell configuration is not a form of greyed-out
        // Java fields.
        Runnable applyType = () -> {
            String value = type.getValue();
            setRowDisabled(form, !RunConfigFields.usesTarget(value), targetRow);
            boolean scriptFile = com.editora.run.RunConfigFields.usesScriptFile(value);
            browse.setVisible(scriptFile);
            browse.setManaged(scriptFile);
            setRowDisabled(form, !RunConfigFields.usesInterpreter(value), interpreter, interpreterOptions);
            setRowDisabled(form, !RunConfigFields.usesJavaFields(value), mainClassRow, projectName);
            setRowDisabled(form, !RunConfigFields.usesJvmFields(value), vmArgs, jdk);
            // The prompts say what a blank field means for this type (a Python script runs in its own folder).
            boolean python = com.editora.run.ScriptRunCommand.isPython(value);
            interpreter.setPromptText(
                    tr(python ? "settings.runConfig.interpreterPromptPython" : "settings.runConfig.interpreterPrompt"));
            interpreterOptions.setPromptText(tr(
                    python
                            ? "settings.runConfig.interpreterOptionsPromptPython"
                            : "settings.runConfig.interpreterOptionsPrompt"));
            workingDir.setPromptText(tr(
                    com.editora.run.ScriptRunCommand.PYTHON.equals(value)
                            ? "settings.runConfig.workingDirPromptPython"
                            : "settings.runConfig.workingDirPrompt"));
            target.setPromptText(tr(
                    com.editora.run.ScriptRunCommand.PYTHON_MODULE.equals(value)
                            ? "settings.runConfig.targetPromptModule"
                            : "settings.runConfig.targetPrompt"));
        };
        java.util.function.Supplier<RunConfiguration> fromForm = () -> new RunConfiguration(
                name.getText(),
                type.getValue() == null ? "java" : type.getValue(),
                target.getText(),
                mainClass.getText(),
                projectName.getText(),
                args.getText(),
                vmArgs.getText(),
                workingDir.getText(),
                env.getText(),
                beforeLaunch.getText(),
                jdk.getValue() == null ? "" : jdk.getValue().home(),
                interpreter.getText(),
                interpreterOptions.getText(),
                inTerminal.isSelected());
        Runnable commit = () -> {
            int index = list.getSelectionModel().getSelectedIndex();
            if (index < 0 || loading) {
                return;
            }
            replaceRow(index, fromForm.get(), true);
            list.refresh();
            persist();
        };
        type.valueProperty().addListener((o, was, now) -> {
            applyType.run();
            commit.run();
        });
        jdk.valueProperty().addListener((o, was, now) -> commit.run());
        inTerminal.selectedProperty().addListener((o, was, now) -> commit.run());
        for (TextField value : List.of(
                name,
                target,
                interpreter,
                interpreterOptions,
                mainClass,
                projectName,
                args,
                vmArgs,
                workingDir,
                env,
                beforeLaunch)) {
            value.setOnAction(e -> commit.run());
            value.focusedProperty().addListener((o, was, now) -> {
                if (!now) {
                    commit.run();
                }
            });
        }

        list.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            if (replacingRow) {
                return; // a commit swapping the row's value: the form already shows it
            }
            // A field commits on focus loss, which arrives after a click on another row has already moved the
            // selection — so the pending edit was written over by that row's values and lost. Commit it into
            // the row being left, before the form is reloaded.
            int left = was == null || reloading ? -1 : identityIndexOf(was);
            if (left >= 0) {
                RunConfiguration edited = fromForm.get();
                if (!edited.equals(was)) {
                    replaceRow(left, edited, false);
                    list.refresh();
                    persist();
                }
            }
            loading = true;
            try {
                form.setDisable(now == null);
                name.setText(now == null ? "" : now.name());
                type.setValue(now == null ? "java" : now.type());
                target.setText(now == null ? "" : now.target());
                interpreter.setText(now == null ? "" : now.interpreter());
                interpreterOptions.setText(now == null ? "" : now.interpreterOptions());
                mainClass.setText(now == null ? "" : now.mainClass());
                projectName.setText(now == null ? "" : now.projectName());
                args.setText(now == null ? "" : now.args());
                vmArgs.setText(now == null ? "" : now.vmArgs());
                workingDir.setText(now == null ? "" : now.workingDir());
                env.setText(now == null ? "" : now.env());
                beforeLaunch.setText(now == null ? "" : now.beforeLaunch());
                JdkChoice.select(jdk, now == null ? "" : now.jdkHome());
                inTerminal.setSelected(now != null && now.runInTerminal());
                applyType.run(); // the type listener is silent when two rows share a type
            } finally {
                loading = false;
            }
        });

        browse.setOnAction(e -> {
            javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
            chooser.setTitle(tr("settings.runConfig.browseTitle"));
            java.nio.file.Path root = projectRoot == null ? null : projectRoot.get();
            if (root != null && root.getFileSystem() == java.nio.file.FileSystems.getDefault()) {
                chooser.setInitialDirectory(root.toFile());
            }
            java.io.File chosen = chooser.showOpenDialog(stage);
            if (chosen != null) {
                target.setText(chosenTarget(type.getValue(), chosen.toPath(), root));
                commit.run();
            }
        });

        browseClass.setOnAction(e -> {
            javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
            chooser.setTitle(tr("settings.runConfig.browseMainClassTitle"));
            chooser.getExtensionFilters()
                    .add(new javafx.stage.FileChooser.ExtensionFilter(
                            tr("settings.runConfig.javaSourceFilter"), "*.java"));
            java.nio.file.Path root = projectRoot == null ? null : projectRoot.get();
            if (root != null && root.getFileSystem() == java.nio.file.FileSystems.getDefault()) {
                chooser.setInitialDirectory(root.toFile());
            }
            java.io.File chosen = chooser.showOpenDialog(stage);
            if (chosen != null) {
                pickMainClass(chosen.toPath(), name, mainClass);
                commit.run();
            }
        });

        Button add = new Button(tr("settings.runConfig.add"));
        add.setOnAction(e -> {
            List<String> taken = items.stream().map(RunConfiguration::name).toList();
            // The active Python or shell script first: Add used to make an empty Java configuration whatever
            // was open, and focus Main class for a .sh.
            RunConfiguration script = scriptSuggestion == null ? null : scriptSuggestion.get();
            String suggested = script != null || suggestion == null ? null : suggestion.get();
            RunConfiguration value = script != null
                    ? RunConfigDefaults.uniquelyNamed(script, taken)
                    : RunConfigDefaults.newConfiguration(suggested, taken, tr("settings.runConfig.newName"));
            items.add(value);
            persist();
            list.getSelectionModel().select(value);
            Platform.runLater(() -> (script != null ? args : suggested == null ? mainClass : name).requestFocus());
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
        save.getStyleClass().add("success");
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
        stage.setMinHeight(540);
    }

    private static TextField field(String promptKey) {
        TextField field = new TextField();
        field.setPromptText(tr(promptKey));
        return field;
    }

    /** The Script / target text for a file picked with Browse: relative to the project when inside it. */
    static String chosenTarget(String type, java.nio.file.Path chosen, java.nio.file.Path root) {
        RunConfiguration c = RunConfigDefaults.forScript(type, chosen, root);
        return c == null ? chosen.toString() : c.target();
    }

    /**
     * Fills Main class from a picked {@code .java} file, and renames the selected configuration after the class
     * while it is still named after nothing in particular ("New Configuration", or the class it launched).
     */
    void pickMainClass(java.nio.file.Path file, TextField name, TextField mainClass) {
        String source;
        try {
            source = java.nio.file.Files.readString(file);
        } catch (java.nio.charset.CharacterCodingException e) {
            source = readLatin1(file); // a Latin-1 source still declares an ASCII package
        } catch (java.io.IOException e) {
            source = ""; // unreadable: the file name (in the default package) is all there is
        }
        String fileName = file.getFileName() == null ? "" : file.getFileName().toString();
        String fqn = RunConfigDefaults.mainClassForSource(source, fileName);
        if (fqn == null) {
            return; // not a .java file
        }
        int index = list.getSelectionModel().getSelectedIndex();
        List<String> others = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (i != index) {
                others.add(items.get(i).name());
            }
        }
        name.setText(RunConfigDefaults.renamedForMainClass(
                name.getText(), mainClass.getText(), fqn, tr("settings.runConfig.newName"), others));
        mainClass.setText(fqn);
    }

    private static String readLatin1(java.nio.file.Path file) {
        try {
            return java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.ISO_8859_1);
        } catch (java.io.IOException e) {
            return "";
        }
    }

    private static void formRow(GridPane form, int row, String labelKey, javafx.scene.Node control) {
        Label label = new Label(tr(labelKey));
        label.setMinWidth(Region.USE_PREF_SIZE); // "Program arguments", not "Program arg…"
        form.add(label, 0, row);
        form.add(control, 1, row);
        GridPane.setHgrow(control, Priority.ALWAYS);
        if (control instanceof Region region) {
            region.setMaxWidth(Double.MAX_VALUE);
        }
    }

    /** Disables and hides (or re-enables and shows) each control together with the label on its row. */
    private static void setRowDisabled(GridPane form, boolean disabled, javafx.scene.Node... controls) {
        for (javafx.scene.Node control : controls) {
            show(control, !disabled);
            Integer row = GridPane.getRowIndex(control);
            for (javafx.scene.Node node : form.getChildren()) {
                if (node instanceof Label && java.util.Objects.equals(GridPane.getRowIndex(node), row)) {
                    show(node, !disabled);
                }
            }
        }
    }

    private static void show(javafx.scene.Node node, boolean used) {
        node.setDisable(!used);
        node.setVisible(used);
        node.setManaged(used);
    }

    private static Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    /**
     * Replaces the row at {@code index} without the selection listener reloading (or blanking) the form.
     * {@code ListView} clears the selection when the selected element is replaced — always, when it is the only
     * one — which disabled the form and emptied every field after each committed edit; {@code keepSelected}
     * puts the selection back.
     */
    private void replaceRow(int index, RunConfiguration value, boolean keepSelected) {
        replacingRow = true;
        try {
            items.set(index, value);
            if (keepSelected) {
                list.getSelectionModel().select(index);
            }
        } finally {
            replacingRow = false;
        }
    }

    private int identityIndexOf(RunConfiguration value) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) == value) {
                return i;
            }
        }
        return -1;
    }

    private void reload(String selectName) {
        String previous = selectName;
        if ((previous == null || previous.isBlank()) && list.getSelectionModel().getSelectedItem() != null) {
            previous = list.getSelectionModel().getSelectedItem().name();
        }
        reloading = true; // the rows are being replaced from disk: nothing in the form is a pending edit
        try {
            items.setAll(config.getWorkspaceState().getRunConfigurations());
        } finally {
            reloading = false;
        }
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
        WindowPlacement.centerOnOwner(stage, owner, WIDTH, HEIGHT); // kept on the owner's screen
    }
}
