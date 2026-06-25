package com.editora.ui;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;

import com.editora.command.KeymapManager;
import com.editora.command.TextInputKeymap;
import com.editora.vfs.RemoteConnection;
import com.editora.vfs.RemoteFileSystems;
import com.editora.vfs.Vfs;

import static com.editora.i18n.Messages.tr;

/**
 * Remote file access (SFTP) — the connect form, the saved-connection picker, mounting a remote folder as the
 * Project tree root, and single-file open from an {@code sftp://} URI. Extracted from {@link MainController}
 * via the {@link CoordinatorHost} pattern. Owns the {@link RemoteFileSystems} engine (the SSH client +
 * {@code Vfs} resolver), the {@link RemoteConnectionsPanel}, and the {@code activeRemoteAuthority} mount
 * state. {@code MainController} keeps the {@code remote} {@code ToolWindow} (built with {@link #panel()}),
 * queries {@link #isMounted()} for its current-folder view, and wires the {@code remote.*}/{@code tool.remote}
 * commands here.
 */
final class RemoteCoordinator {

    /** Window hooks beyond {@link CoordinatorHost} that the remote flows need. */
    interface Ops {
        /** This window's active keymap (for installing caret navigation on the form's text fields). */
        KeymapManager keymap();

        /** Opens (or focuses) the tab for {@code file} (used for a single {@code sftp://} open). */
        void openPath(Path file);

        /** Roots the Project tree at {@code root} (a mounted remote folder, or back to the local project). */
        void setProjectRoot(Path root);

        /** Opens the Project tool window (so the mounted remote folder is visible). */
        void openProjectToolWindow();

        /** The active local project's root, or null — used to restore the tree on disconnect. */
        Path activeProjectRoot();

        /** Shows a scrollable error dialog (reuses the Git error reporter) + a short status summary. */
        void reportError(String summary, String detail);

        /** The saved SFTP connections (metadata only — no secrets). */
        List<RemoteConnection> connections();

        /** Remembers a just-used connection (most-recent-first), no secret stored. */
        void putConnection(RemoteConnection conn);

        /** Forgets a saved connection by id. */
        void removeConnection(String id);
    }

    private final CoordinatorHost host;
    private final Ops ops;
    private final RemoteConnectionsPanel panel;
    private RemoteFileSystems remoteFs; // lazily created on first remote use
    private String activeRemoteAuthority; // the connection backing the mounted remote root

    RemoteCoordinator(CoordinatorHost host, Ops ops) {
        this.host = host;
        this.ops = ops;
        this.panel = new RemoteConnectionsPanel(ops::connections, new RemoteConnectionsPanel.Actions() {
            @Override
            public void connect(RemoteConnection c) {
                RemoteCoordinator.this.connect(c);
            }

            @Override
            public void remove(RemoteConnection c) {
                ops.removeConnection(c.id());
            }

            @Override
            public void newConnection() {
                RemoteCoordinator.this.connect();
            }
        });
    }

    /** The Remote Sites tool-window content (the {@code ToolWindow} itself stays in {@code MainController}). */
    RemoteConnectionsPanel panel() {
        return panel;
    }

    /** Whether a remote folder is currently mounted (the Project tree shows it instead of a local project). */
    boolean isMounted() {
        return activeRemoteAuthority != null;
    }

    /** Re-fetches the saved-connection list shown in the panel (e.g. before opening the tool window). */
    void refreshPanel() {
        panel.refresh();
    }

    private RemoteFileSystems remoteFs() {
        if (remoteFs == null) {
            remoteFs = new RemoteFileSystems(); // starts the SSH client + wires Vfs resolver
        }
        return remoteFs;
    }

    void connect() {
        connect(null);
    }

    /**
     * Shows the SFTP connection form (optionally pre-filled from a saved connection); on success, mounts the
     * remote folder in the Project tool window.
     */
    void connect(RemoteConnection prefill) {
        KeymapManager keymap = ops.keymap();
        TextField hostField = new TextField();
        hostField.setPromptText(tr("remote.hostPrompt"));
        hostField.setPrefColumnCount(24);
        TextInputKeymap.install(hostField, keymap);
        TextField portField = new TextField("22");
        portField.setPrefColumnCount(5);
        TextField userField = new TextField(System.getProperty("user.name", ""));
        userField.setPrefColumnCount(16);
        TextInputKeymap.install(userField, keymap);
        TextField pathField = new TextField();
        pathField.setPromptText(tr("remote.pathPrompt"));
        TextInputKeymap.install(pathField, keymap);

        ComboBox<RemoteConnection.AuthMethod> authCombo = new ComboBox<>();
        authCombo.getItems().setAll(RemoteConnection.AuthMethod.values());
        authCombo.setValue(RemoteConnection.AuthMethod.DEFAULT_KEYS);
        authCombo.setConverter(new StringConverter<RemoteConnection.AuthMethod>() {
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
        PasswordField secretField = new PasswordField();
        secretField.setPromptText(tr("remote.secretPrompt"));
        TextField keyField = new TextField();
        keyField.setPromptText(tr("remote.keyPrompt"));
        TextInputKeymap.install(keyField, keymap);
        Button keyBrowse = new Button(tr("dialog.clone.browse"));
        keyBrowse.setFocusTraversable(false);
        keyBrowse.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle(tr("remote.keyPrompt"));
            File f = fc.showOpenDialog(host.window());
            if (f != null) {
                keyField.setText(f.getAbsolutePath());
            }
        });
        // Show only the fields relevant to the chosen auth method.
        Runnable syncAuth = () -> {
            var m = authCombo.getValue();
            boolean key = m == RemoteConnection.AuthMethod.KEY;
            boolean pwd = m == RemoteConnection.AuthMethod.PASSWORD;
            keyField.setDisable(!key);
            keyBrowse.setDisable(!key);
            secretField.setDisable(!key && !pwd);
            secretField.setPromptText(pwd ? tr("remote.secretPrompt") : tr("remote.passphrasePrompt"));
        };
        authCombo.valueProperty().addListener((o, a, b) -> syncAuth.run());
        if (prefill != null) { // reconnecting a saved connection — fill everything but the secret
            hostField.setText(prefill.host());
            portField.setText(String.valueOf(prefill.port()));
            userField.setText(prefill.user() == null ? "" : prefill.user());
            authCombo.setValue(prefill.auth());
            keyField.setText(prefill.keyPath() == null ? "" : prefill.keyPath());
            pathField.setText(prefill.lastPath() == null ? "" : prefill.lastPath());
        }
        syncAuth.run();

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.add(new Label(tr("remote.host")), 0, 0);
        grid.add(hostField, 1, 0);
        grid.add(new Label(tr("remote.port")), 2, 0);
        grid.add(portField, 3, 0);
        grid.add(new Label(tr("remote.user")), 0, 1);
        grid.add(userField, 1, 1);
        grid.add(new Label(tr("remote.auth")), 0, 2);
        grid.add(authCombo, 1, 2, 3, 1);
        grid.add(new Label(tr("remote.key")), 0, 3);
        grid.add(keyField, 1, 3, 2, 1);
        grid.add(keyBrowse, 3, 3);
        grid.add(new Label(tr("remote.secret")), 0, 4);
        grid.add(secretField, 1, 4, 3, 1);
        grid.add(new Label(tr("remote.path")), 0, 5);
        grid.add(pathField, 1, 5, 3, 1);
        GridPane.setHgrow(hostField, Priority.ALWAYS);

        BooleanProperty valid = new SimpleBooleanProperty(false);
        Runnable revalidate = () ->
                valid.set(!hostField.getText().isBlank() && !userField.getText().isBlank());
        hostField.textProperty().addListener((o, a, b) -> revalidate.run());
        userField.textProperty().addListener((o, a, b) -> revalidate.run());
        revalidate.run();

        OverlayInput.show(
                host.overlayHost(),
                tr("remote.connect.title"),
                grid,
                hostField,
                tr("remote.connect.button"),
                valid,
                () -> {
                    int port = 22;
                    try {
                        port = Integer.parseInt(portField.getText().strip());
                    } catch (NumberFormatException ignore) {
                        // keep the default port
                    }
                    String path = pathField.getText().strip();
                    RemoteConnection conn = new RemoteConnection(
                            hostField.getText().strip(),
                            port,
                            userField.getText().strip(),
                            authCombo.getValue(),
                            keyField.getText().strip(),
                            null,
                            path.isEmpty() ? null : path);
                    char[] secret = secretField.getText().isEmpty()
                            ? null
                            : secretField.getText().toCharArray();
                    host.setStatus(tr("status.remote.connecting", conn.displayLabel()));
                    remoteFs().connect(conn, secret, r -> {
                        if (r.ok()) {
                            mount(conn, r.root());
                        } else {
                            ops.reportError(tr("status.remote.failed", conn.displayLabel()), r.error());
                        }
                    });
                },
                null,
                false);
    }

    /** Mounts a connected remote folder as the Project tree root + opens the Project tool window. */
    private void mount(RemoteConnection conn, Path root) {
        activeRemoteAuthority = conn.id();
        ops.putConnection(conn); // remember the connection (metadata only — no secret) for next time
        panel.refresh(); // surface the just-used site at the top of the list
        ops.setProjectRoot(root);
        ops.openProjectToolWindow();
        host.setStatus(tr("status.remote.connected", conn.displayLabel()));
    }

    /** A picker over the saved SFTP connections; choosing one re-opens the connect form pre-filled. */
    void manageConnections() {
        var saved = ops.connections();
        if (saved.isEmpty()) {
            connect(); // nothing saved yet — go straight to a fresh connection
            return;
        }
        QuickOpen<RemoteConnection> picker = new QuickOpen<>(
                tr("remote.manage.title"),
                tr("remote.manage.prompt"),
                () -> List.copyOf(ops.connections()),
                RemoteConnection::displayLabel,
                RemoteConnection::id,
                this::connect);
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.window());
    }

    /** Opens a single remote file from an {@code sftp://user@host/path} URI (its connection must be open). */
    void openFile() {
        host.promptText(tr("remote.openFile.title"), tr("remote.openFile.label"), "sftp://", uri -> {
            Path p = Vfs.parseStorable(uri.strip());
            if (p == null) {
                host.setStatus(tr("status.remote.notConnected"));
                return;
            }
            ops.openPath(p);
        });
    }

    /** Disconnects the mounted remote folder and returns the Project tree to the active local project. */
    void disconnect() {
        if (activeRemoteAuthority == null || remoteFs == null) {
            host.setStatus(tr("status.remote.notConnected"));
            return;
        }
        remoteFs.disconnect(activeRemoteAuthority);
        activeRemoteAuthority = null;
        Path activeRoot = ops.activeProjectRoot();
        if (activeRoot != null) {
            ops.setProjectRoot(activeRoot);
        }
        host.setStatus(tr("status.remote.disconnected"));
    }
}
