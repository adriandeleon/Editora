package com.editora.ui;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;

import com.editora.command.KeymapManager;
import com.editora.command.TextInputKeymap;
import com.editora.config.Project;
import com.editora.vfs.ConnectFailure;
import com.editora.vfs.LoginPrompt;
import com.editora.vfs.RemoteConnection;
import com.editora.vfs.RemoteFileSystems;
import com.editora.vfs.SftpUri;
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

        /** This window's local project root, or null — used to restore the tree on disconnect. */
        Path activeProjectRoot();

        /** Shows a scrollable error dialog (reuses the Git error reporter) + a short status summary. */
        void reportError(String summary, String detail);

        /** The saved SFTP connections (metadata only — no secrets). */
        List<RemoteConnection> connections();

        /** Remembers a just-used connection (most-recent-first), no secret stored. */
        void putConnection(RemoteConnection conn);

        /** Forgets a saved connection by id. */
        void removeConnection(String id);

        /** Invalidates writes captured against a path that is about to be rebound. */
        void invalidatePendingWrite(Path path);

        /** The project this window edits, or null (the No Project window). */
        default Project windowProject() {
            return null;
        }

        /** The shared project list, or null when {@link #projectsEnabled} is false. */
        default com.editora.config.ProjectManager projects() {
            return null;
        }

        /** Whether projects are in use at all; without them a remote folder is only ever shown in this window. */
        default boolean projectsEnabled() {
            return false;
        }

        /**
         * Opens (or focuses) {@code project}'s own window and gives it the connection: {@code live}, the
         * session this window logged in with, when it could be handed over; otherwise the window logs in for
         * itself with {@code secret}, which it wipes. A window holds its own connections, so the one that
         * shows a remote project is the one that must be connected to its host.
         */
        default void openRemoteProject(
                Project project, RemoteConnection conn, RemoteFileSystems.LiveConnection live, char[] secret) {
            if (live != null) {
                live.close();
            }
            if (secret != null) {
                java.util.Arrays.fill(secret, '\0');
            }
        }

        /** The Project panel, or null while it has not been built (it is built on first use). */
        default ProjectPanel projectPanel() {
            return null;
        }

        /** Reopens the session's tabs that were waiting for this window's host to be connected. */
        default void restoreSessionTabs() {}

        /** Whether the user has agreed that commands may run on the host of connection {@code id}. */
        default boolean remoteExecAllowed(String id) {
            return false;
        }

        /** Records that agreement. */
        default void allowRemoteExec(String id) {}

        /** The tool commands configured for the host of connection {@code id}. */
        default java.util.Map<String, String> remoteTools(String id) {
            return java.util.Map.of();
        }

        /** What can run on this window's host has changed: features that run tools there look again. */
        default void remoteExecChanged() {}

        /** The window is connected to {@code authority} again, its tabs rebound to the new connection. */
        default void remoteHostConnected(String authority) {}
    }

    private final CoordinatorHost host;
    private final Ops ops;
    /** The Remote Sites panel, built on its first use (the tool window's first open). */
    private RemoteConnectionsPanel panel;

    private RemoteFileSystems remoteFs; // lazily created on first remote use
    private String activeRemoteAuthority; // the connection backing the mounted remote root
    /** A connect started for this window's own remote project is under way: do not also ask the user. */
    private boolean connecting;

    private boolean listening;
    /** The connection behind the mounted folder (for the question about running commands on its host). */
    private RemoteConnection activeConnection;
    /** Hosts the user said "not now" about, so the question is put once per session and not on every connect. */
    private final Set<String> execDeclined = new java.util.HashSet<>();
    /** Asks whether commands may be run on a host; a dialog in the app, replaced in tests. */
    java.util.function.Predicate<RemoteConnection> execConsent = this::askToRunCommands;
    /** The not-connected state of this window's remote project, kept for a Project panel not built yet. */
    private java.util.function.Consumer<ProjectPanel> disconnectedState;

    RemoteCoordinator(CoordinatorHost host, Ops ops) {
        this(host, ops, null);
    }

    /** Connection-engine injection used by an already-connected host and deterministic integration tests. */
    RemoteCoordinator(CoordinatorHost host, Ops ops, RemoteFileSystems remoteFs) {
        this.host = host;
        this.ops = ops;
        this.remoteFs = remoteFs;
    }

    /** The Remote Sites tool-window content (the {@code ToolWindow} itself stays in {@code MainController}). */
    RemoteConnectionsPanel panel() {
        if (panel == null) {
            panel = new RemoteConnectionsPanel(ops::connections, new RemoteConnectionsPanel.Actions() {
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
        return panel;
    }

    /** Whether a remote folder is currently mounted (the Project tree shows it instead of a local project). */
    boolean isMounted() {
        return activeRemoteAuthority != null;
    }

    /** Re-fetches the saved-connection list shown in the panel (e.g. before opening the tool window). */
    void refreshPanel() {
        if (panel != null) {
            panel.refresh(); // an unbuilt panel lists the saved connections when it is built
        }
    }

    /** How long a host-key question may sit unanswered before the connection is refused. */
    private static final long HOST_KEY_ANSWER_TIMEOUT_SECONDS = 120;

    private RemoteFileSystems remoteFs() {
        if (remoteFs == null) {
            // Starts the SSH client + wires the Vfs resolver. The prompts are the trust-on-first-use decision
            // (without one, RemoteFileSystems refuses unknown hosts rather than trusting them) and the login's
            // own questions: a password, a key's passphrase, a 2FA code.
            remoteFs = new RemoteFileSystems(this::confirmHostKey, this::answerLogin);
        }
        return remoteFs;
    }

    /**
     * Trust-on-first-use: asked on an SSH I/O thread about a host {@code known_hosts} has never seen, answered
     * on the FX thread. Blocking the caller is the contract — the SSH handshake cannot proceed until the user
     * decides. Anything but an explicit "Connect" is a refusal, including running out of patience.
     */
    private boolean confirmHostKey(String host, int port, String keyType, String fingerprint) {
        if (Platform.isFxApplicationThread()) {
            return askHostKey(host, port, keyType, fingerprint); // already on FX — asking directly can't deadlock
        }
        AtomicBoolean accepted = new AtomicBoolean(false);
        CountDownLatch answered = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                accepted.set(askHostKey(host, port, keyType, fingerprint));
            } finally {
                answered.countDown();
            }
        });
        try {
            // Bounded, so an unanswered dialog can't park an SSH I/O thread for the rest of the session.
            // Timing out means "not confirmed" — it fails closed.
            if (!answered.await(HOST_KEY_ANSWER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return accepted.get();
    }

    /** The question itself (FX thread) — the same one {@code ssh} asks at the terminal. */
    private boolean askHostKey(String host, int port, String keyType, String fingerprint) {
        ButtonType connect = new ButtonType(tr("dialog.hostKey.connect"), ButtonBar.ButtonData.OK_DONE);
        Alert alert = new Alert(
                Alert.AlertType.CONFIRMATION,
                tr("dialog.hostKey.content", host + ":" + port, keyType, fingerprint),
                ButtonType.CANCEL,
                connect);
        alert.initOwner(this.host.window());
        alert.setTitle(tr("dialog.hostKey.title"));
        alert.setHeaderText(tr("dialog.hostKey.header", host));
        alert.getDialogPane().setMinWidth(560);
        return alert.showAndWait().orElse(ButtonType.CANCEL) == connect;
    }

    /** How long a login question (a password, a 2FA code) may sit unanswered before the login is cancelled. */
    private static final long LOGIN_ANSWER_TIMEOUT_SECONDS = 120;

    /**
     * A login question, asked on an SSH I/O thread and answered on the FX thread, as {@link #confirmHostKey} is.
     * Null — cancelled, or left unanswered too long — cancels that question.
     */
    private char[][] answerLogin(LoginPrompt.Challenge challenge) {
        if (Platform.isFxApplicationThread()) {
            return askLogin(challenge);
        }
        AtomicReference<char[][]> answers = new AtomicReference<>();
        CountDownLatch answered = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                answers.set(askLogin(challenge));
            } finally {
                answered.countDown();
            }
        });
        try {
            if (!answered.await(LOGIN_ANSWER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return answers.get();
    }

    /** Asks a login question (FX thread); replaced in tests. */
    java.util.function.Function<LoginPrompt.Challenge, char[][]> loginDialog = this::showLoginDialog;

    private char[][] askLogin(LoginPrompt.Challenge challenge) {
        return loginDialog.apply(challenge);
    }

    /**
     * The login dialog: a password, a passphrase, or the server's own questions, each in its own field — hidden
     * unless the server says the answer may be shown. Cancel cancels.
     */
    private char[][] showLoginDialog(LoginPrompt.Challenge challenge) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(host.window());
        dialog.setTitle(tr("dialog.login.title"));
        dialog.setHeaderText(
                switch (challenge.kind()) {
                    case PASSWORD -> tr("dialog.login.password.header", challenge.subject());
                    case PASSPHRASE -> tr("dialog.login.passphrase.header", challenge.subject());
                    case INTERACTIVE ->
                        challenge.name().isBlank()
                                ? tr("dialog.login.interactive.header", challenge.subject())
                                : challenge.name();
                });
        ButtonType logIn = new ButtonType(tr("dialog.login.button"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, logIn);
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        int row = 0;
        if (challenge.attempt() > 1) {
            grid.add(new Label(tr("dialog.login.retry")), 0, row++, 2, 1);
        }
        if (!challenge.instruction().isBlank()) {
            Label instruction = new Label(challenge.instruction().strip());
            instruction.setWrapText(true);
            instruction.setMaxWidth(420);
            grid.add(instruction, 0, row++, 2, 1);
        }
        List<TextField> fields = new ArrayList<>();
        for (LoginPrompt.Question q : challenge.questions()) {
            TextField field = q.echo() ? new TextField() : new PasswordField();
            field.setPrefColumnCount(24);
            String label =
                    switch (challenge.kind()) {
                        case PASSWORD -> tr("dialog.login.password");
                        case PASSPHRASE -> tr("dialog.login.passphrase");
                        case INTERACTIVE -> q.text().strip();
                    };
            grid.add(new Label(label), 0, row);
            grid.add(field, 1, row++);
            fields.add(field);
        }
        dialog.getDialogPane().setContent(grid);
        if (!fields.isEmpty()) {
            Platform.runLater(fields.getFirst()::requestFocus);
        }
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != logIn) {
            return null;
        }
        char[][] answers = new char[fields.size()][];
        for (int i = 0; i < fields.size(); i++) {
            answers[i] = fields.get(i).getText().toCharArray();
            fields.get(i).clear();
        }
        return answers;
    }

    /**
     * Says why a connect failed, the way a person can act on it: a dialog for what needs explaining (an
     * unreachable host, a refused login, a changed host key), only the status bar for what the person did
     * themselves (cancelled, or declined the host's key).
     */
    void reportConnectFailure(RemoteConnection conn, RemoteFileSystems.Result result) {
        String label = conn.displayLabel();
        ConnectFailure failure = result.failure() != null
                ? result.failure()
                : new ConnectFailure(ConnectFailure.Kind.OTHER, result.error());
        if (failure.kind() == ConnectFailure.Kind.CANCELLED
                || failure.kind() == ConnectFailure.Kind.HOST_KEY_DECLINED) {
            host.setStatus(tr("status.remote.cancelled", label));
            return;
        }
        String knownHostsName = conn.port() == 22 ? conn.host() : "[" + conn.host() + "]:" + conn.port();
        String header;
        String advice;
        switch (failure.kind()) {
            case UNREACHABLE -> {
                header = tr("remote.error.unreachable", label);
                advice = tr("remote.error.unreachable.detail");
            }
            case TIMED_OUT -> {
                header = tr("remote.error.timedOut", label);
                advice = tr("remote.error.timedOut.detail");
            }
            case HOST_KEY_CHANGED -> {
                header = tr("remote.error.hostKeyChanged", label);
                advice = tr("remote.error.hostKeyChanged.detail", knownHostsName);
            }
            case AUTH_FAILED -> {
                header = tr("remote.error.authFailed", label);
                advice = tr("remote.error.authFailed.detail");
            }
            default -> {
                header = tr("status.remote.failed", label);
                advice = "";
            }
        }
        host.setStatus(header);
        String detail = failure.detail() == null ? "" : failure.detail().strip();
        String body = advice.isEmpty() ? detail : detail.isEmpty() ? advice : advice + "\n\n" + detail;
        if (connectFailureShown != null) {
            connectFailureShown.accept(header, body);
            return;
        }
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(host.window());
        alert.setTitle(tr("dialog.remote.title"));
        alert.setHeaderText(header);
        TextArea area = new TextArea(body);
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefColumnCount(52);
        area.setPrefRowCount(Math.min(14, (int) body.lines().count() + 1));
        area.getStyleClass().add("git-error-text"); // the error-detail style the Git dialog uses
        alert.getDialogPane().setContent(area);
        alert.showAndWait();
    }

    /** Takes the connect-failure dialog's header and body instead of showing it (tests). */
    java.util.function.BiConsumer<String, String> connectFailureShown;

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
            keyField.setDisable(!key);
            keyBrowse.setDisable(!key);
        };
        authCombo.valueProperty().addListener((o, a, b) -> syncAuth.run());
        if (prefill != null) { // reconnecting a saved connection — a password is asked for during the login
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
        grid.add(new Label(tr("remote.path")), 0, 4);
        grid.add(pathField, 1, 4, 3, 1);
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
                    host.setStatus(tr("status.remote.connecting", conn.displayLabel()));
                    // No secret up front: what the login needs is asked for while it runs (answerLogin).
                    remoteFs().connect(conn, null, r -> {
                        if (r.ok()) {
                            connected(conn, r.root(), null);
                        } else {
                            reportConnectFailure(conn, r);
                        }
                    });
                },
                null,
                false);
    }

    private static void wipe(char[] secret) {
        if (secret != null) {
            java.util.Arrays.fill(secret, '\0');
        }
    }

    /**
     * A connect from the form succeeded. The folder is a project, and a project has its own window: when
     * that is this window (it was reopened disconnected, or its connection dropped) the folder is mounted
     * here; otherwise the project's window is opened and takes this window's connection over, so the one
     * login serves it (a second would ask for another password or 2FA code). When this window's tabs still
     * use that connection it stays here, and the project's window logs in for itself.
     */
    void connected(RemoteConnection conn, Path root, char[] secret) {
        if (!ops.projectsEnabled()) {
            wipe(secret);
            mount(conn, root);
            return;
        }
        Project project = ops.projects().createOrGet(projectName(conn, root), root);
        ops.projects().save();
        Project mine = ops.windowProject();
        if (mine != null && mine.id().equals(project.id())) {
            wipe(secret);
            mount(conn, root);
            return;
        }
        SftpUri location = SftpUri.parse(project.root());
        RemoteConnection at = withPath(conn, location == null ? conn.lastPath() : location.path());
        ops.putConnection(at);
        refreshPanel();
        RemoteFileSystems.LiveConnection live = null;
        if (!conn.id().equals(activeRemoteAuthority)
                && buffersOn(Set.of(conn.id()), false).isEmpty()) {
            live = remoteFs.handOff(conn.id(), root);
        }
        if (live != null) {
            wipe(secret); // the window takes over the logged-in session: nothing to log in with again
            secret = null;
        }
        host.setStatus(tr("status.remote.openingProject", project.name()));
        ops.openRemoteProject(project, at, live, secret);
    }

    private static String projectName(RemoteConnection conn, Path root) {
        Path name = root.getFileName();
        return name == null || name.toString().isBlank() ? conn.displayLabel() : name.toString();
    }

    private static RemoteConnection withPath(RemoteConnection conn, String path) {
        return new RemoteConnection(
                conn.host(), conn.port(), conn.user(), conn.auth(), conn.keyPath(), conn.label(), path);
    }

    /**
     * Mounts this window's own remote project over a connection another window logged in and handed over. One
     * that ended on the way leaves the window in its not-connected state, with Reconnect.
     */
    void adoptForWindowProject(RemoteConnection conn, RemoteFileSystems.LiveConnection live) {
        Path root = remoteFs().adopt(live);
        if (root != null) {
            mount(conn, root);
        } else {
            showDisconnected();
            host.setStatus(tr("status.remote.lost", conn.id()));
        }
    }

    /**
     * Connects this window to its own remote project's host with credentials another window collected, and
     * mounts the folder. {@code secret} is wiped.
     */
    void connectForWindowProject(RemoteConnection conn, char[] secret) {
        connecting = true;
        host.setStatus(tr("status.remote.connecting", conn.displayLabel()));
        remoteFs().connect(conn, secret, r -> {
            connecting = false;
            if (r.ok()) {
                mount(conn, r.root());
            } else {
                showDisconnected();
                reportConnectFailure(conn, r);
            }
        });
    }

    /**
     * For a window whose project is on a host it is not connected to (reopened from the project list or at
     * startup): says so where the tree would be, and brings up the connect form filled in for that host.
     * Cancelling the form leaves the window open, with the reconnect button.
     */
    void promptReconnectIfNeeded() {
        Project project = ops.windowProject();
        if (project == null || !project.remote() || connecting || project.rootPath() != null) {
            return;
        }
        showDisconnected();
        connect(connectionFor(project));
    }

    /** The saved connection for {@code project}'s host, else one built from its address; rooted at the project. */
    private RemoteConnection connectionFor(Project project) {
        SftpUri location = SftpUri.parse(project.root());
        if (location == null) {
            return null;
        }
        RemoteConnection base = ops.connections().stream()
                .filter(c -> c.id().equals(location.authority()))
                .findFirst()
                .orElseGet(() -> new RemoteConnection(
                        location.host(),
                        location.port(),
                        location.user(),
                        RemoteConnection.AuthMethod.DEFAULT_KEYS,
                        "",
                        null,
                        null));
        return withPath(base, location.path());
    }

    /** Shows the not-connected state when this window's project is a remote one; otherwise nothing. */
    private void showDisconnected() {
        Project project = ops.windowProject();
        SftpUri location = project == null || !project.remote() ? null : SftpUri.parse(project.root());
        if (location == null) {
            return;
        }
        String message = tr("remote.project.disconnected", location.authority());
        disconnectedState = panel ->
                panel.showDisconnected(message, tr("remote.project.reconnect"), () -> connect(connectionFor(project)));
        panelBuilt(ops.projectPanel());
    }

    /** Whether this window is saying its remote project is not connected (tests). */
    boolean showsDisconnected() {
        return disconnectedState != null;
    }

    /** The Project panel exists now (or still does): give it the not-connected state, if that is the state. */
    void panelBuilt(ProjectPanel built) {
        if (built != null && disconnectedState != null) {
            disconnectedState.accept(built);
        }
    }

    private void clearDisconnected() {
        disconnectedState = null;
        ProjectPanel built = ops.projectPanel();
        if (built != null) {
            built.clearDisconnected();
        }
    }

    /** The connection under this window's mounted folder ended: by the network, the server, or Disconnect. */
    private void connectionChanged(String authority, boolean up) {
        if (up || !authority.equals(activeRemoteAuthority)) {
            return;
        }
        Project project = ops.windowProject();
        if (project != null && project.remote()) {
            activeRemoteAuthority = null;
            showDisconnected(); // first, so the emptied tree shows it at once and not "No project open"
            host.setStatus(tr("status.remote.lost", authority));
            ops.setProjectRoot(null);
        }
    }

    /** Mounts a connected remote folder as the Project tree root + opens the Project tool window. */
    private void mount(RemoteConnection conn, Path root) {
        if (!listening) {
            listening = true;
            remoteFs().addConnectionListener(this::connectionChanged);
        }
        clearDisconnected();
        String previous = activeRemoteAuthority;
        activeRemoteAuthority = conn.id();
        if (previous != null
                && !previous.equals(conn.id())
                && remoteFs != null
                && buffersOn(Set.of(previous), false).isEmpty()) {
            // Nothing shows the earlier host any more: without this its SSH session stayed authenticated and
            // open until the window closed. With tabs still on it, it stays up so they can be saved.
            remoteFs.disconnect(previous);
        }
        ops.putConnection(conn); // remember the connection (metadata only — no secret) for next time
        refreshPanel(); // surface the just-used site at the top of the list
        rebindOpenBuffers(conn.id());
        ops.setProjectRoot(root);
        ops.openProjectToolWindow();
        ops.restoreSessionTabs();
        ops.remoteHostConnected(conn.id());
        host.setStatus(tr("status.remote.connected", conn.displayLabel()));
        activeConnection = conn;
        execSignature = null;
        recheckExecution(); // the baseline later Settings edits are compared with
        Platform.runLater(this::offerExecution); // after the folder is on screen, not in front of it
    }

    /** Reconnects open tabs for this authority to the new live SFTP filesystem, retaining their dirty text. */
    private void rebindOpenBuffers(String authority) {
        host.forEachBuffer(buffer -> {
            Path old = buffer.getPath();
            if (!Vfs.isRemote(old)) {
                return;
            }
            String stored = Vfs.toStorableString(old);
            SftpUri location = SftpUri.parse(stored);
            if (location == null || !authority.equals(location.authority())) {
                return;
            }
            Path rebound = remoteFs.resolve(stored);
            if (rebound != null) {
                ops.invalidatePendingWrite(old);
                buffer.setPath(rebound);
            }
        });
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

    /** The names of open remote files on {@code authorities} — all of them, or only those with unsaved edits. */
    private List<String> buffersOn(Set<String> authorities, boolean unsavedOnly) {
        List<String> names = new ArrayList<>();
        host.forEachBuffer(buffer -> {
            String authority = Vfs.authorityOf(buffer.getPath());
            if (authority != null && authorities.contains(authority) && (!unsavedOnly || buffer.isDirty())) {
                names.add(Vfs.displayLabel(buffer.getPath()));
            }
        });
        return names;
    }

    /** Asks before closing connections that unsaved remote tabs still need for their next save. */
    private boolean confirmDisconnect(List<String> unsaved) {
        ButtonType disconnect = new ButtonType(tr("dialog.remoteDisconnect.confirm"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(tr("dialog.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(
                Alert.AlertType.CONFIRMATION,
                tr("dialog.remoteDisconnect.content", String.join("\n", unsaved), tr("command.file.saveAs")),
                disconnect,
                cancel);
        alert.initOwner(host.window());
        alert.setTitle(tr("dialog.remoteDisconnect.title"));
        alert.setHeaderText(tr("dialog.remoteDisconnect.header"));
        return Dialogs.styled(alert).showAndWait().orElse(cancel) == disconnect;
    }

    /**
     * Closes every remote connection of this window — the mounted folder's, and any earlier host that was
     * kept open for its tabs — and returns the Project tree to the active local project. Tabs on those
     * hosts stay open; when any has unsaved changes the user is asked first.
     */
    void disconnect() {
        Set<String> connected = remoteFs == null ? Set.of() : remoteFs.connectedAuthorities();
        if (activeRemoteAuthority == null && connected.isEmpty()) {
            host.setStatus(tr("status.remote.notConnected"));
            return;
        }
        List<String> unsaved = buffersOn(connected, true);
        if (!unsaved.isEmpty() && !confirmDisconnect(unsaved)) {
            return;
        }
        connected.forEach(remoteFs::disconnect);
        activeRemoteAuthority = null;
        Project project = ops.windowProject();
        if (project != null && project.remote()) {
            showDisconnected();
            ops.setProjectRoot(null); // this window is the remote folder's: there is no local tree to go back to
        } else {
            Path activeRoot = ops.activeProjectRoot();
            if (activeRoot != null) {
                ops.setProjectRoot(activeRoot);
            }
        }
        host.setStatus(tr("status.remote.disconnected"));
    }

    /**
     * Closes every SFTP session and the SSH client, and releases the app-wide {@code Vfs} resolver this
     * window installed. Without it a closed window's {@code RemoteFileSystems} stays strongly reachable from
     * a static field forever — holding its SSH client, its NIO threads, and every open remote session.
     */
    public void shutdown() {
        if (remoteFs != null) {
            remoteFs.shutdown();
            remoteFs = null;
        }
    }

    /**
     * Puts the one-time question for the mounted folder's host: may Editora run the tools it drives there.
     * Only when the user's setting allows remote commands at all, the host has not been allowed yet, and the
     * user has not already said "not now" this session. Yes is remembered with the saved connections.
     */
    void offerExecution() {
        RemoteConnection conn = activeConnection;
        com.editora.config.Settings settings = host.settings();
        if (conn == null
                || !conn.id().equals(activeRemoteAuthority)
                || settings == null
                || !settings.isRemoteExecution()
                || ops.remoteExecAllowed(conn.id())
                || execDeclined.contains(conn.id())) {
            return;
        }
        if (execConsent.test(conn)) {
            ops.allowRemoteExec(conn.id());
            ops.remoteExecChanged();
        } else {
            execDeclined.add(conn.id());
        }
    }

    private boolean askToRunCommands(RemoteConnection conn) {
        ButtonType allow = new ButtonType(tr("dialog.remoteExec.allow"), ButtonBar.ButtonData.OK_DONE);
        ButtonType notNow = new ButtonType(tr("dialog.remoteExec.notNow"), ButtonBar.ButtonData.CANCEL_CLOSE);
        String user = conn.user() == null || conn.user().isBlank() ? "?" : conn.user();
        Alert alert = new Alert(
                Alert.AlertType.CONFIRMATION, tr("dialog.remoteExec.content", conn.host(), user), allow, notNow);
        alert.initOwner(host.window());
        alert.setTitle(tr("dialog.remoteExec.title"));
        alert.setHeaderText(tr("dialog.remoteExec.header", conn.host()));
        return Dialogs.styled(alert).showAndWait().orElse(notNow) == allow;
    }

    /** What {@link #recheckExecution} last saw: the setting, this window's host's agreement and its tools. */
    private String execSignature;

    /**
     * Something that decides what runs on this window's host may have changed — the setting, or the host's
     * agreement or tool commands, edited in Settings by this window or another. When it did, the features
     * that run tools there look again; nothing happens otherwise (this runs on every settings apply).
     */
    void recheckExecution() {
        com.editora.config.Settings settings = host.settings();
        RemoteConnection conn = activeConnection;
        String id = conn == null || !conn.id().equals(activeRemoteAuthority) ? null : conn.id();
        String now = (settings != null && settings.isRemoteExecution()) + "|"
                + (id != null && ops.remoteExecAllowed(id)) + "|"
                + (id == null ? "" : ops.remoteTools(id));
        String before = execSignature;
        execSignature = now;
        if (before != null && !before.equals(now)) {
            ops.remoteExecChanged();
        }
    }

    /** Flips {@code Settings.remoteExecution}: whether commands for a remote folder may run on its host. */
    void toggleExecution() {
        com.editora.config.Settings s = host.settings();
        s.setRemoteExecution(!s.isRemoteExecution());
        host.requestSave();
        host.syncSettingsWindow();
        if (s.isRemoteExecution()) {
            execDeclined.clear(); // turning it on is asking for it: put the question again
            offerExecution();
        }
        ops.remoteExecChanged();
        host.setStatus(tr("status.toggle.remoteExecution", tr(s.isRemoteExecution() ? "common.on" : "common.off")));
    }
}
