package com.editora.ui;

import java.util.List;
import java.util.Map;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;

import com.editora.config.ConfigManager;
import com.editora.vfs.RemoteConnection;
import com.editora.vfs.SftpUri;

import static com.editora.i18n.Messages.tr;

/**
 * The Settings → Remote site editor: the saved SFTP sites on the left, and for the selected one its connection
 * details and — below — whether its host may run commands and with which tools. Every field saves when it is
 * left or Enter is pressed. Built once by {@link SettingsWindow}, which re-reads it through {@link #reload()}
 * when the saved sites change elsewhere.
 */
final class RemoteSitesPane {

    private final ConfigManager config;
    private final Runnable apply;

    /** Working copy of the saved SFTP connections, edited live. */
    private final ObservableList<RemoteConnection> items = FXCollections.observableArrayList();

    private final ListView<RemoteConnection> list = new ListView<>(items);
    private final Node node;

    private boolean loading;
    /** Set while an edited site replaces its list entry; see {@link #commit}. */
    private boolean replacing;
    /** The uncommitted text in the form, asked for before the list is re-read. */
    private java.util.function.Supplier<PendingFormEdit> formEdit = PendingFormEdit::none;

    private final TextField label = new TextField();
    private final TextField host = new TextField();
    private final TextField port = new TextField();
    private final TextField user = new TextField();
    private final ComboBox<RemoteConnection.AuthMethod> auth =
            new ComboBox<>(FXCollections.observableArrayList(RemoteConnection.AuthMethod.values()));
    private final TextField keyPath = new TextField();

    RemoteSitesPane(ConfigManager config, Runnable apply) {
        this.config = config;
        this.apply = apply;
        this.node = build();
    }

    /** The editor, to be placed in the Remote page's card. */
    Node node() {
        return node;
    }

    private Node build() {
        items.setAll(config.getConnections());

        list.setPrefSize(210, 380);
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(RemoteConnection c, boolean empty) {
                super.updateItem(c, empty);
                setText(empty || c == null ? null : c.displayLabel());
            }
        });

        host.setPromptText(tr("remote.hostPrompt"));
        auth.setConverter(new StringConverter<>() {
            @Override
            public String toString(RemoteConnection.AuthMethod m) {
                return m == null
                        ? ""
                        : switch (m) {
                            case DEFAULT_KEYS -> tr("remote.auth.defaultKeys");
                            case KEY -> tr("remote.auth.key");
                            case PASSWORD -> tr("remote.auth.password");
                        };
            }

            @Override
            public RemoteConnection.AuthMethod fromString(String s) {
                return null;
            }
        });
        keyPath.setPromptText(tr("remote.keyPrompt"));
        keyPath.setMinWidth(160);
        Button keyBrowse = new Button(tr("dialog.clone.browse"));
        keyBrowse.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle(tr("remote.keyPrompt"));
            java.io.File ssh = new java.io.File(System.getProperty("user.home", ""), ".ssh");
            if (ssh.isDirectory()) {
                fc.setInitialDirectory(ssh); // where keys live
            }
            java.io.File f = fc.showOpenDialog(keyBrowse.getScene().getWindow());
            if (f != null) {
                keyPath.setText(f.getAbsolutePath());
            }
        });
        // Wraps: beside a wide (German) Browse button the field was left about 60px in a narrow window. The
        // button now moves below the field instead, which then has the row to itself.
        WrapRow keyRow = new WrapRow(6, 6, WrapRow.setGrow(keyPath), keyBrowse);
        // The key-file row only applies to "Private key file" auth.
        auth.valueProperty().addListener((o, a, b) -> keyRow.setDisable(b != RemoteConnection.AuthMethod.KEY));
        // A value that cannot work turns its field red with the reason as it is typed; it is not saved until fixed.
        host.textProperty().addListener((o, a, b) -> mark(host, RemoteSiteCheck.host(b)));
        port.textProperty().addListener((o, a, b) -> mark(port, RemoteSiteCheck.port(b)));
        user.textProperty().addListener((o, a, b) -> mark(user, RemoteSiteCheck.user(b)));

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(6);
        formRow(form, 0, tr("settings.remote.label"), label);
        formRow(form, 1, tr("remote.host"), host);
        formRow(form, 2, tr("remote.port"), port);
        formRow(form, 3, tr("remote.user"), user);
        formRow(form, 4, tr("remote.auth"), auth);
        formRow(form, 5, tr("remote.key"), keyRow);
        form.setDisable(true);
        HBox.setHgrow(form, Priority.ALWAYS);

        // --- the selected site's host: may Editora run commands there, and with which tools ---
        CheckBox hostAllowed = new CheckBox(tr("settings.remote.host.allowed"));
        hostAllowed.setWrapText(true);
        Map<String, TextField> toolFields = new java.util.LinkedHashMap<>();
        // Each label above its field: beside them, the longer tool names widened the page past the smallest
        // Settings window, which then scrolled sideways.
        VBox toolsGrid = new VBox(2);
        for (HostTools.Tool tool : HostTools.NAMED) {
            TextField field = new TextField();
            field.setPromptText(tr("settings.remote.tool.prompt"));
            toolFields.put(tool.id(), field);
            Label name = new Label(tr(tool.labelKey()));
            VBox.setMargin(name, new Insets(4, 0, 0, 0));
            toolsGrid.getChildren().addAll(name, field);
        }
        TextArea servers = new TextArea();
        servers.setPromptText(tr("settings.remote.tool.servers.prompt"));
        servers.setPrefRowCount(3);
        servers.setPrefColumnCount(20);
        servers.setWrapText(false);
        Label serversName = new Label(tr("settings.remote.tool.servers"));
        VBox.setMargin(serversName, new Insets(4, 0, 0, 0));
        toolsGrid.getChildren().addAll(serversName, servers);
        Label hostTitle = new Label(tr("settings.remote.host.title"));
        hostTitle.getStyleClass().add("settings-card-title");
        Label hostNote = hint(tr("settings.remote.host.note"));
        VBox hostBox = new VBox(6, new Separator(), hostTitle, hostAllowed, toolsGrid, hostNote);
        hostBox.disableProperty().bind(form.disabledProperty());
        hostAllowed.setOnAction(e -> {
            RemoteConnection cur = list.getSelectionModel().getSelectedItem();
            if (cur != null && !loading) {
                config.setRemoteExecAllowed(cur.id(), hostAllowed.isSelected());
            }
        });
        Runnable commitTools = () -> {
            RemoteConnection cur = list.getSelectionModel().getSelectedItem();
            if (cur == null || loading) {
                return;
            }
            Map<String, String> named = new java.util.LinkedHashMap<>();
            toolFields.forEach((id, field) -> named.put(id, field.getText()));
            Map<String, String> previous = config.remoteTools(cur.id());
            Map<String, String> merged = HostTools.merge(previous, named, servers.getText());
            if (!merged.equals(previous)) {
                config.setRemoteTools(cur.id(), merged);
            }
        };
        toolFields.values().forEach(field -> {
            field.setOnAction(e -> commitTools.run());
            field.focusedProperty().addListener((o, was, now) -> {
                if (!now) {
                    commitTools.run();
                }
            });
        });
        servers.focusedProperty().addListener((o, was, now) -> {
            if (!now) {
                commitTools.run();
            }
        });

        auth.valueProperty().addListener((o, a, b) -> commit());
        for (TextField tf : List.of(label, host, port, user, keyPath)) {
            tf.setOnAction(e -> commit());
            tf.focusedProperty().addListener((o, was, now) -> {
                if (!now) {
                    commit();
                }
            });
        }
        formEdit = () -> {
            RemoteConnection cur = list.getSelectionModel().getSelectedItem();
            return cur == null
                    ? PendingFormEdit.none()
                    : PendingFormEdit.capture(
                            List.of(label, host, port, user, keyPath),
                            List.of(
                                    nullToEmpty(cur.label()),
                                    nullToEmpty(cur.host()),
                                    String.valueOf(cur.port()),
                                    nullToEmpty(cur.user()),
                                    nullToEmpty(cur.keyPath())));
        };

        list.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            if (replacing) {
                return;
            }
            loading = true;
            try {
                form.setDisable(now == null);
                label.setText(now == null ? "" : nullToEmpty(now.label()));
                host.setText(now == null ? "" : nullToEmpty(now.host()));
                port.setText(now == null ? "" : String.valueOf(now.port()));
                user.setText(now == null ? "" : nullToEmpty(now.user()));
                auth.setValue(now == null ? null : now.auth());
                keyPath.setText(now == null ? "" : nullToEmpty(now.keyPath()));
                keyRow.setDisable(now == null || now.auth() != RemoteConnection.AuthMethod.KEY);
                Map<String, String> tools = now == null ? Map.of() : config.remoteTools(now.id());
                hostAllowed.setSelected(now != null && config.remoteExecAllowed(now.id()));
                toolFields.forEach((id, field) -> field.setText(tools.getOrDefault(id, "")));
                servers.setText(HostTools.serversText(tools));
            } finally {
                loading = false;
            }
            if (now != null) {
                checkKeyFile(now);
            }
        });

        Button add = new Button(tr("settings.remote.add"));
        add.setOnAction(e -> {
            RemoteConnection c = new RemoteConnection(
                    "",
                    SftpUri.DEFAULT_PORT,
                    System.getProperty("user.name", ""),
                    RemoteConnection.AuthMethod.DEFAULT_KEYS,
                    "",
                    tr("settings.remote.newName"),
                    "");
            items.add(c);
            persist();
            list.getSelectionModel().select(c);
        });
        Button remove = new Button(tr("settings.remote.remove"));
        remove.disableProperty()
                .bind(list.getSelectionModel().selectedItemProperty().isNull());
        remove.setOnAction(e -> {
            int i = list.getSelectionModel().getSelectedIndex();
            if (i >= 0) {
                items.remove(i);
                persist();
            }
        });
        HBox buttons = new HBox(6, add, remove);
        VBox left = new VBox(6, list, buttons);
        keepWidth(add, remove, left, keyBrowse);

        // Explicit Save (edits also auto-save on Enter / focus-loss, so nothing is lost on row switch).
        Button save = new Button(tr("settings.save"));
        save.getStyleClass().add("success");
        save.disableProperty().bind(form.disabledProperty());
        save.setOnAction(e -> commit());
        HBox saveRow = new HBox(save);
        saveRow.setAlignment(Pos.CENTER_RIGHT);
        VBox right = new VBox(8, form, saveRow, hostBox);
        VBox.setVgrow(form, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);

        if (!items.isEmpty()) {
            list.getSelectionModel().select(0);
        }
        HBox box = new HBox(12, left, right);
        box.setAlignment(Pos.TOP_LEFT);
        return box;
    }

    /** Writes the form into the selected site (a field was left, or Enter pressed). */
    private void commit() {
        int i = list.getSelectionModel().getSelectedIndex();
        RemoteConnection cur = list.getSelectionModel().getSelectedItem();
        if (cur == null || i < 0 || loading) {
            return;
        }
        if (RemoteSiteCheck.host(host.getText()) != null
                || RemoteSiteCheck.port(port.getText()) != null
                || RemoteSiteCheck.user(user.getText()) != null) {
            return; // the field says what is wrong; the site keeps its last good values until it is fixed
        }
        String portText = port.getText().strip();
        String key = RemoteSiteCheck.expandHome(keyPath.getText(), System.getProperty("user.home", ""));
        RemoteConnection updated = new RemoteConnection(
                host.getText().strip(),
                portText.isEmpty() ? 0 : Integer.parseInt(portText), // the record makes 0 the default SFTP port
                user.getText().strip(),
                auth.getValue() == null ? RemoteConnection.AuthMethod.DEFAULT_KEYS : auth.getValue(),
                key,
                label.getText().strip(),
                cur.lastPath()); // preserve the remembered path; not edited here
        // What was decided for the host (may run commands, its tools) follows the connection when its host,
        // user or port is edited: it is kept by id, and the new id would otherwise start with none.
        config.renameRemoteHost(cur.id(), updated.id());
        // Replacing the only item of a list clears its selection (JavaFX reads it as "all removed"), which
        // greyed and emptied the form after every field. The form already shows what was typed: keep it.
        replacing = true;
        try {
            items.set(i, updated);
            list.getSelectionModel().select(i);
        } finally {
            replacing = false;
        }
        list.refresh();
        persist();
        loading = true; // show what was saved: the default port for a blank one, the key path with ~ expanded
        try {
            port.setText(String.valueOf(updated.port()));
            keyPath.setText(updated.keyPath());
        } finally {
            loading = false;
        }
        checkKeyFile(updated);
    }

    /** Marks {@code field} red with {@code problemKey}'s text as its tooltip, or clears that. */
    private static void mark(TextField field, String problemKey) {
        field.pseudoClassStateChanged(atlantafx.base.theme.Styles.STATE_DANGER, problemKey != null);
        field.setTooltip(problemKey == null ? null : new javafx.scene.control.Tooltip(tr(problemKey)));
    }

    /**
     * Warns (amber, not refused: the key may live on a drive that is not mounted yet) when a "Private key file"
     * site names a file that cannot be read. The check touches the disk, so it runs off the FX thread.
     */
    private void checkKeyFile(RemoteConnection site) {
        keyPath.pseudoClassStateChanged(atlantafx.base.theme.Styles.STATE_WARNING, false);
        keyPath.setTooltip(null);
        if (site.auth() != RemoteConnection.AuthMethod.KEY) {
            return;
        }
        String path = site.keyPath();
        java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> !path.isBlank() && java.nio.file.Files.isReadable(java.nio.file.Path.of(path)))
                .exceptionally(e -> false)
                .thenAccept(readable -> javafx.application.Platform.runLater(() -> {
                    if (!readable && path.equals(keyPath.getText())) {
                        keyPath.pseudoClassStateChanged(atlantafx.base.theme.Styles.STATE_WARNING, true);
                        keyPath.setTooltip(new javafx.scene.control.Tooltip(
                                path.isBlank()
                                        ? tr("settings.remote.check.keyNone")
                                        : tr("settings.remote.check.keyMissing", path)));
                    }
                }));
    }

    private void persist() {
        config.setConnections(new java.util.ArrayList<>(items));
        apply.run();
    }

    /** Re-reads the saved sites (Connect remembers one; the Remote Sites panel removes one), keeping the selection. */
    void reload() {
        if (items.equals(config.getConnections())) {
            return;
        }
        RemoteConnection selected = list.getSelectionModel().getSelectedItem();
        String selectedId = selected == null ? null : selected.id();
        PendingFormEdit typing = formEdit.get(); // before the items change: that reloads the form
        items.setAll(config.getConnections());
        if (selectedId != null) {
            for (RemoteConnection c : items) {
                if (selectedId.equals(c.id())) {
                    list.getSelectionModel().select(c);
                    typing.restore(); // the site being edited is still there: what was typed stays typed
                    break;
                }
            }
        }
    }

    private static void formRow(GridPane form, int rowIndex, String labelText, Node field) {
        Label l = new Label(labelText);
        // A Label's computed min width is just an ellipsis, so a tight GridPane collapses it to "…".
        l.setMinWidth(Region.USE_PREF_SIZE);
        form.add(l, 0, rowIndex);
        form.add(field, 1, rowIndex);
        if (field instanceof Region r) {
            r.setMinWidth(180);
        }
    }

    private static Label hint(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("settings-hint");
        l.setWrapText(true);
        l.setMaxWidth(460);
        return l;
    }

    private static void keepWidth(Region... controls) {
        for (Region control : controls) {
            control.setMinWidth(Region.USE_PREF_SIZE);
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
