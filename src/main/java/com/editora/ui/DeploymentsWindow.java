package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
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
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
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
import com.editora.config.Deployment;
import com.editora.config.RunConfiguration;
import com.editora.deploy.DeployExcludes;
import com.editora.deploy.DeployRoot;
import com.editora.vfs.RemoteConnection;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Modeless editor for a project's deployments: where its run configurations can run (see {@link Deployment}).
 * Every field is saved as it is left, like the Run Configurations window; renaming a deployment renames it in
 * the configurations that run on it.
 */
public final class DeploymentsWindow {

    private static final double WIDTH = 780;
    private static final double HEIGHT = 620;

    private final ConfigManager config;
    private final Supplier<List<RemoteConnection>> savedSites;
    private final Consumer<String> deployNow;
    private final Runnable onChanged;
    private final Stage stage = new Stage();
    private final ObservableList<Deployment> items = FXCollections.observableArrayList();
    private final ListView<Deployment> list = new ListView<>(items);
    private boolean built;
    private boolean loading;
    private boolean replacingRow;
    private boolean reloading;

    /**
     * @param savedSites the saved remote sites, to fill a deployment's connection from
     * @param deployNow deploys the named deployment (the window's Deploy Now button)
     * @param onChanged told after every saved change (the run configurations refresh their Run on)
     */
    public DeploymentsWindow(
            ConfigManager config,
            Supplier<List<RemoteConnection>> savedSites,
            Consumer<String> deployNow,
            Runnable onChanged) {
        this.config = config;
        this.savedSites = savedSites == null ? List::of : savedSites;
        this.deployNow = deployNow == null ? n -> {} : deployNow;
        this.onChanged = onChanged == null ? () -> {} : onChanged;
    }

    /** Opens the editor and selects {@code selectName} when that deployment exists. */
    public void show(@Nullable String selectName, Window owner) {
        if (!built) {
            build(owner);
            built = true;
        }
        reload(selectName);
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

    private void build(Window owner) {
        stage.setTitle(tr("deploy.window.title"));
        stage.initOwner(owner);
        stage.initModality(Modality.NONE);

        list.setPrefWidth(200);
        list.setMinWidth(160);
        list.setId("deployments-list");
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Deployment value, boolean empty) {
                super.updateItem(value, empty);
                setText(
                        empty || value == null
                                ? null
                                : value.name().isBlank() ? tr("settings.runConfig.unnamed") : value.name());
            }
        });

        TextField name = new TextField();
        name.setId("deploy-name");
        TextField hostField = field("deploy-host", "deploy.field.hostPrompt");
        MenuButton fromSite = new MenuButton(tr("deploy.field.fromSite"));
        fromSite.setId("deploy-from-site");
        TextField port = new TextField(String.valueOf(Deployment.DEFAULT_PORT));
        port.setId("deploy-port");
        port.setPrefColumnCount(5);
        TextField user = field("deploy-user", "deploy.field.userPrompt");
        ComboBox<RemoteConnection.AuthMethod> auth =
                new ComboBox<>(FXCollections.observableArrayList(RemoteConnection.AuthMethod.values()));
        auth.setId("deploy-auth");
        auth.setConverter(new StringConverter<>() {
            @Override
            public String toString(RemoteConnection.AuthMethod value) {
                return value == null
                        ? ""
                        : switch (value) {
                            case DEFAULT_KEYS -> tr("remote.auth.defaultKeys");
                            case KEY -> tr("remote.auth.key");
                            case PASSWORD -> tr("remote.auth.password");
                        };
            }

            @Override
            public RemoteConnection.@Nullable AuthMethod fromString(String value) {
                return null;
            }
        });
        TextField keyPath = field("deploy-key", "deploy.field.keyPrompt");
        Button keyBrowse = new Button(tr("settings.runConfig.browse"));
        TextField remoteRoot = field("deploy-root", "deploy.field.rootPrompt");
        Label rootProblem = new Label();
        rootProblem.getStyleClass().add("deploy-root-problem");
        rootProblem.setStyle("-fx-text-fill: -color-danger-fg;");
        rootProblem.managedProperty().bind(rootProblem.textProperty().isNotEmpty());
        rootProblem.visibleProperty().bind(rootProblem.managedProperty());
        TextArea excludes = new TextArea();
        excludes.setId("deploy-excludes");
        excludes.setPromptText(tr("deploy.field.excludesPrompt"));
        excludes.setPrefRowCount(5);
        excludes.setTooltip(new Tooltip(tr("deploy.field.excludesHint", String.join("  ", DeployExcludes.DEFAULTS))));
        CheckBox gitignore = new CheckBox(tr("deploy.field.useGitignore"));
        gitignore.setId("deploy-gitignore");
        CheckBox deleteRemoved = new CheckBox(tr("deploy.field.deleteRemoved"));
        deleteRemoved.setId("deploy-delete-removed");
        deleteRemoved.setTooltip(new Tooltip(tr("deploy.field.deleteRemovedHint")));
        CheckBox uploadOnSave = new CheckBox(tr("deploy.field.uploadOnSave"));
        uploadOnSave.setId("deploy-upload-on-save");

        HBox hostRow = new HBox(6, hostField, new Label(tr("remote.port")), port, fromSite);
        hostRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(hostField, Priority.ALWAYS);
        HBox keyRow = new HBox(6, keyPath, keyBrowse);
        keyRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(keyPath, Priority.ALWAYS);
        VBox rootBox = new VBox(4, remoteRoot, rootProblem);

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(8);
        row(form, 0, "deploy.field.name", name);
        row(form, 1, "remote.host", hostRow);
        row(form, 2, "remote.user", user);
        row(form, 3, "remote.auth", auth);
        row(form, 4, "remote.key", keyRow);
        row(form, 5, "deploy.field.root", rootBox);
        row(form, 6, "deploy.field.excludes", excludes);
        VBox options = new VBox(6, gitignore, deleteRemoved, uploadOnSave);
        row(form, 7, "deploy.field.options", options);
        Label note = new Label(tr("deploy.window.note"));
        note.setWrapText(true);
        note.getStyleClass().add("text-muted");
        form.add(note, 1, 8);
        form.setDisable(true);

        Runnable applyAuth = () -> {
            boolean key = auth.getValue() == RemoteConnection.AuthMethod.KEY;
            keyPath.setDisable(!key);
            keyBrowse.setDisable(!key);
        };
        Runnable checkRoot = () -> {
            DeployRoot.Problem problem = DeployRoot.problem(remoteRoot.getText(), "");
            rootProblem.setText(
                    problem == null || problem == DeployRoot.Problem.EMPTY
                            ? ""
                            : tr(
                                    "deploy.root." + problem.name().toLowerCase(java.util.Locale.ROOT),
                                    remoteRoot.getText().strip()));
        };
        Supplier<Deployment> fromForm = () -> {
            int p;
            try {
                p = Integer.parseInt(port.getText().strip());
            } catch (NumberFormatException e) {
                p = Deployment.DEFAULT_PORT;
            }
            return new Deployment(
                    name.getText().strip(),
                    hostField.getText(),
                    p,
                    user.getText(),
                    auth.getValue(),
                    keyPath.getText().strip(),
                    remoteRoot.getText(),
                    excludes.getText(),
                    gitignore.isSelected(),
                    deleteRemoved.isSelected(),
                    uploadOnSave.isSelected());
        };
        Runnable commit = () -> {
            int index = list.getSelectionModel().getSelectedIndex();
            if (index < 0 || loading) {
                return;
            }
            Deployment before = items.get(index);
            Deployment edited = fromForm.get();
            if (edited.equals(before)) {
                return;
            }
            replaceRow(index, edited);
            list.refresh();
            persist(before.name(), edited.name());
        };
        auth.valueProperty().addListener((o, was, now) -> {
            applyAuth.run();
            commit.run();
        });
        remoteRoot.textProperty().addListener((o, was, now) -> checkRoot.run());
        for (CheckBox box : List.of(gitignore, deleteRemoved, uploadOnSave)) {
            box.selectedProperty().addListener((o, was, now) -> commit.run());
        }
        for (javafx.scene.control.TextInputControl value :
                List.of(name, hostField, port, user, keyPath, remoteRoot, excludes)) {
            if (value instanceof TextField tf) {
                tf.setOnAction(e -> commit.run());
            }
            value.focusedProperty().addListener((o, was, now) -> {
                if (!now) {
                    commit.run();
                }
            });
        }
        fromSite.setOnShowing(e -> {
            fromSite.getItems().clear();
            for (RemoteConnection site : savedSites.get()) {
                MenuItem item = new MenuItem(site.displayLabel() + "  (" + site.id() + ")");
                item.setOnAction(a -> {
                    hostField.setText(site.host());
                    port.setText(String.valueOf(site.port()));
                    user.setText(site.user() == null ? "" : site.user());
                    auth.setValue(site.auth() == null ? RemoteConnection.AuthMethod.DEFAULT_KEYS : site.auth());
                    keyPath.setText(site.keyPath() == null ? "" : site.keyPath());
                    if (remoteRoot.getText().isBlank()
                            && site.lastPath() != null
                            && site.lastPath().startsWith("/")) {
                        remoteRoot.setText(site.lastPath());
                    }
                    commit.run();
                });
                fromSite.getItems().add(item);
            }
            if (fromSite.getItems().isEmpty()) {
                MenuItem none = new MenuItem(tr("deploy.field.noSites"));
                none.setDisable(true);
                fromSite.getItems().add(none);
            }
        });
        keyBrowse.setOnAction(e -> {
            javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
            chooser.setTitle(tr("remote.key"));
            java.io.File ssh = new java.io.File(System.getProperty("user.home"), ".ssh");
            if (ssh.isDirectory()) {
                chooser.setInitialDirectory(ssh);
            }
            java.io.File chosen = chooser.showOpenDialog(stage);
            if (chosen != null) {
                keyPath.setText(chosen.getAbsolutePath());
                commit.run();
            }
        });

        list.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            if (replacingRow) {
                return;
            }
            int left = was == null || reloading ? -1 : identityIndexOf(was);
            if (left >= 0) { // a field left by clicking another row commits into the row being left
                Deployment edited = fromForm.get();
                if (!edited.equals(was)) {
                    replaceRow(left, edited);
                    list.refresh();
                    persist(was.name(), edited.name());
                }
            }
            loading = true;
            try {
                form.setDisable(now == null);
                Deployment d = now == null ? Deployment.named("") : now;
                name.setText(d.name());
                hostField.setText(d.host());
                port.setText(String.valueOf(d.port()));
                user.setText(d.user());
                auth.setValue(d.auth());
                keyPath.setText(d.keyPath());
                remoteRoot.setText(d.remoteRoot());
                excludes.setText(d.excludes());
                gitignore.setSelected(d.useGitignore());
                deleteRemoved.setSelected(d.deleteRemoved());
                uploadOnSave.setSelected(d.uploadOnSave());
                applyAuth.run();
                checkRoot.run();
            } finally {
                loading = false;
            }
        });

        Button add = new Button(tr("settings.runConfig.add"));
        add.setId("deploy-add");
        add.setOnAction(e -> {
            List<String> taken = items.stream().map(Deployment::name).toList();
            Deployment value =
                    Deployment.named(com.editora.run.RunConfigDefaults.uniqueName(tr("deploy.newName"), taken));
            items.add(value);
            persist(null, null);
            list.getSelectionModel().select(value);
            Platform.runLater(hostField::requestFocus);
        });
        Button remove = new Button(tr("settings.runConfig.remove"));
        remove.disableProperty()
                .bind(list.getSelectionModel().selectedItemProperty().isNull());
        remove.setOnAction(e -> {
            int index = list.getSelectionModel().getSelectedIndex();
            if (index >= 0) {
                items.remove(index);
                persist(null, null);
            }
        });
        Button deploy = new Button(tr("deploy.window.deployNow"));
        deploy.setId("deploy-now");
        deploy.disableProperty().bind(form.disabledProperty());
        deploy.setOnAction(e -> {
            commit.run();
            Deployment selected = list.getSelectionModel().getSelectedItem();
            if (selected != null) {
                deployNow.accept(selected.name());
            }
        });
        Button close = new Button(tr("settings.close"));
        close.getStyleClass().add("accent");
        close.setOnAction(e -> {
            commit.run();
            stage.close();
        });

        VBox left = new VBox(8, list);
        VBox.setVgrow(list, Priority.ALWAYS);
        VBox right = new VBox(8, form);
        HBox.setHgrow(right, Priority.ALWAYS);
        HBox editor = new HBox(12, left, right);
        VBox.setVgrow(editor, Priority.ALWAYS);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, add, remove, spacer, deploy, close);
        buttons.setAlignment(Pos.CENTER_LEFT);
        VBox root = new VBox(12, editor, buttons);
        root.setPadding(new Insets(16));
        root.setPrefSize(WIDTH, HEIGHT);

        Scene scene = new Scene(root, WIDTH, HEIGHT);
        scene.getStylesheets()
                .add(DeploymentsWindow.class
                        .getResource("/com/editora/styles/app.css")
                        .toExternalForm());
        stage.setScene(scene);
        stage.setMinWidth(640);
        stage.setMinHeight(520);
    }

    private static TextField field(String id, String promptKey) {
        TextField field = new TextField();
        field.setId(id);
        field.setPromptText(tr(promptKey));
        return field;
    }

    private static void row(GridPane form, int row, String labelKey, javafx.scene.Node control) {
        Label label = new Label(tr(labelKey));
        label.setMinWidth(Region.USE_PREF_SIZE);
        form.add(label, 0, row);
        form.add(control, 1, row);
        GridPane.setHgrow(control, Priority.ALWAYS);
        if (control instanceof Region region) {
            region.setMaxWidth(Double.MAX_VALUE);
        }
    }

    private void replaceRow(int index, Deployment value) {
        replacingRow = true;
        try {
            items.set(index, value);
            list.getSelectionModel().select(index);
        } finally {
            replacingRow = false;
        }
    }

    private int identityIndexOf(Deployment value) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) == value) {
                return i;
            }
        }
        return -1;
    }

    private void reload(@Nullable String selectName) {
        String previous = selectName;
        if ((previous == null || previous.isBlank()) && list.getSelectionModel().getSelectedItem() != null) {
            previous = list.getSelectionModel().getSelectedItem().name();
        }
        reloading = true;
        try {
            items.setAll(config.getWorkspaceState().getDeployments());
        } finally {
            reloading = false;
        }
        Deployment selected = null;
        for (Deployment d : items) {
            if (d.name().equals(previous)) {
                selected = d;
            }
        }
        if (selected == null && !items.isEmpty()) {
            selected = items.get(0);
        }
        list.getSelectionModel().select(selected);
    }

    /** Saves the list; a rename from {@code oldName} to {@code newName} carries over to the configurations. */
    private void persist(@Nullable String oldName, @Nullable String newName) {
        config.getWorkspaceState().setDeployments(new ArrayList<>(items));
        if (oldName != null && !oldName.isBlank() && newName != null && !oldName.equals(newName)) {
            config.getWorkspaceState()
                    .setRunConfigurations(renamed(config.getWorkspaceState().getRunConfigurations(), oldName, newName));
        }
        config.save();
        onChanged.run();
    }

    /** {@code configs} with every one that runs on {@code oldName} running on {@code newName}. */
    static List<RunConfiguration> renamed(List<RunConfiguration> configs, String oldName, String newName) {
        List<RunConfiguration> out = new ArrayList<>();
        for (RunConfiguration c : configs) {
            out.add(oldName.equals(c.deployment()) ? c.withDeployment(newName) : c);
        }
        return out;
    }
}
