package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Labeled;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.editora.command.KeymapManager;
import com.editora.vfs.LoginPrompt;
import com.editora.vfs.RemoteConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Remote Sites panel and the connection form up to — never past — the Connect button: the saved sites,
 * what each control does, how a saved site pre-fills the form, the fields each authentication method needs,
 * and the unknown-host question. No SSH connection is attempted.
 */
@Tag("fx")
class RemoteSitesFxTest {

    private static final RemoteConnection BUILD = new RemoteConnection(
            "build.example.test", 2222, "ci", RemoteConnection.AuthMethod.KEY, "/keys/ci_ed25519", null, "/srv/app");
    private static final RemoteConnection DOCS = new RemoteConnection(
            "docs.example.test", 22, "ada", RemoteConnection.AuthMethod.PASSWORD, null, null, null);

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path sshDir;

    private Host host;
    private Ops ops;
    private RemoteCoordinator coordinator;
    private RemoteConnectionsPanel panel;
    private StackPane overlayRoot;
    private Stage stage;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @BeforeEach
    void setUp() throws Exception {
        host = new Host();
        ops = new Ops();
        ops.saved.addAll(List.of(BUILD, DOCS));
        FxTestSupport.runOnFx(() -> {
            // Its own ~/.ssh: the form reads ssh's config, and must not read the one of whoever runs the tests.
            coordinator = new RemoteCoordinator(
                    host,
                    ops,
                    new com.editora.vfs.RemoteFileSystems(null, null, com.editora.vfs.SshEnvironment.at(sshDir)));
            panel = coordinator.panel();
            overlayRoot = new StackPane(panel);
            host.overlay.install(overlayRoot);
            stage = new Stage();
            stage.setScene(new Scene(overlayRoot, 900, 700));
            stage.show();
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        FxTestSupport.runOnFx(() -> {
            host.overlay.hide();
            stage.hide();
            coordinator.shutdown();
        });
    }

    private ListView<RemoteConnection> list() {
        return FxTestSupport.field(panel, "list");
    }

    private Button button(Parent root, String text) {
        for (Node n : root.lookupAll(".button")) {
            if (n instanceof Button b && text.equals(b.getText())) {
                return b;
            }
        }
        throw new AssertionError("no button \"" + text + "\"");
    }

    /** The connection form on show, or null. */
    private Parent form() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            if (!host.overlay.isShowing()) {
                return null;
            }
            overlayRoot.applyCss();
            overlayRoot.layout();
            return (Parent) overlayRoot.lookup(".overlay-form");
        });
    }

    /** The form's text fields in order: host, port, user, key file, secret, path. */
    private List<TextField> fields(Parent form) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            List<TextField> out = new ArrayList<>();
            for (Node n : form.lookupAll(".text-field")) {
                if (n instanceof TextField f && !(f.getParent() instanceof ComboBox)) {
                    out.add(f);
                }
            }
            return out;
        });
    }

    private List<String> values(Parent form) throws Exception {
        List<TextField> fields = fields(form);
        return FxTestSupport.callOnFx(
                () -> fields.stream().map(TextField::getText).toList());
    }

    private static void collect(Node node, StringBuilder out) {
        if (node instanceof Labeled labeled) {
            out.append(labeled.getText()).append('|');
        } else if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collect(child, out);
            }
        }
    }

    @Test
    void thePanelListsTheSavedSitesWithWhereEachLeads() throws Exception {
        List<String> rows = FxTestSupport.callOnFx(() -> {
            overlayRoot.applyCss();
            overlayRoot.layout();
            TreeMap<Integer, String> out = new TreeMap<>();
            for (Node n : list().lookupAll(".list-cell")) {
                ListCell<?> cell = (ListCell<?>) n;
                if (!cell.isEmpty() && cell.getGraphic() != null) {
                    StringBuilder sb = new StringBuilder();
                    collect(cell.getGraphic(), sb);
                    out.put(cell.getIndex(), sb + cell.getTooltip().getText());
                }
            }
            return new ArrayList<>(out.values());
        });
        assertEquals(
                List.of(
                        BUILD.displayLabel() + "|" + BUILD.id() + "|sftp://" + BUILD.id() + "\n/srv/app",
                        DOCS.displayLabel() + "|" + DOCS.id() + "|sftp://" + DOCS.id()),
                rows);

        // Connect and Remove need a selection.
        Button connect = FxTestSupport.callOnFx(() -> button(panel, tr("remotePanel.connect")));
        Button remove = FxTestSupport.callOnFx(() -> button(panel, tr("remotePanel.remove")));
        assertTrue(FxTestSupport.callOnFx(connect::isDisabled));
        assertTrue(FxTestSupport.callOnFx(remove::isDisabled));
        FxTestSupport.runOnFx(panel::focusFirstItem);
        assertEquals(
                BUILD, FxTestSupport.callOnFx(() -> list().getSelectionModel().getSelectedItem()));
        assertFalse(FxTestSupport.callOnFx(connect::isDisabled));
    }

    @Test
    void connectingASavedSitePreFillsTheFormAndAsksForNoSecret() throws Exception {
        FxTestSupport.runOnFx(() -> {
            list().getSelectionModel().select(BUILD);
            button(panel, tr("remotePanel.connect")).fire();
        });
        Parent form = form();
        assertNotNull(form, "the connection form");
        assertEquals(List.of("build.example.test", "2222", "ci", "/keys/ci_ed25519", "/srv/app"), values(form));
        Button connect = FxTestSupport.callOnFx(() -> button(form, tr("remote.connect.button")));
        assertFalse(FxTestSupport.callOnFx(connect::isDisabled));

        // A key file is named, so the key field is in play; its passphrase, if any, is asked for while logging in.
        List<TextField> fields = fields(form);
        assertFalse(FxTestSupport.callOnFx(() -> fields.get(3).isDisabled()));
        assertTrue(fields.stream().noneMatch(PasswordField.class::isInstance), "no secret is typed into the form");

        // Double-click and Enter on a row do the same as the button.
        FxTestSupport.runOnFx(() -> {
            host.overlay.hide();
            list().getSelectionModel().select(DOCS);
            Event.fireEvent(list(), click(1));
        });
        assertNull(form(), "a single click only selects");
        FxTestSupport.runOnFx(() -> Event.fireEvent(list(), click(2)));
        assertEquals("docs.example.test", values(form()).get(0));
        FxTestSupport.runOnFx(() -> {
            host.overlay.hide();
            list().getSelectionModel().select(BUILD);
            Event.fireEvent(
                    list(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
        });
        assertEquals("build.example.test", values(form()).get(0));

        // And so does the right-click menu.
        FxTestSupport.runOnFx(() -> {
            host.overlay.hide();
            list().getSelectionModel().select(DOCS);
            list().getContextMenu().getItems().get(0).fire();
        });
        assertEquals("docs.example.test", values(form()).get(0));
    }

    private static MouseEvent click(int count) {
        return new MouseEvent(
                MouseEvent.MOUSE_CLICKED,
                5,
                5,
                5,
                5,
                MouseButton.PRIMARY,
                count,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                null);
    }

    @Test
    void theSshConfigsHostsCanBePickedAndFillTheUserAndPortUntilYouTypeThem() throws Exception {
        java.nio.file.Files.writeString(
                sshDir.resolve("config"),
                "Host box\n  HostName 10.1.2.3\n  Port 2222\n  User ana\n  ProxyJump bastion\nHost *.corp\n  User x\n");
        FxTestSupport.runOnFx(() -> button(panel, tr("remotePanel.new")).fire());
        Parent form = form();
        javafx.scene.control.MenuButton fromConfig = FxTestSupport.callOnFx(() -> (javafx.scene.control.MenuButton)
                form.lookupAll(".menu-button").stream().findFirst().orElse(null));
        assertNotNull(fromConfig, "the config's hosts are offered");
        assertEquals(
                List.of("box"),
                FxTestSupport.callOnFx(() ->
                        fromConfig.getItems().stream().map(i -> i.getText()).toList()));

        FxTestSupport.runOnFx(() -> fromConfig.getItems().getFirst().fire());

        assertEquals(List.of("box", "2222", "ana", "", ""), values(form));
        String shown = FxTestSupport.callOnFx(() -> {
            StringBuilder text = new StringBuilder();
            collect(form, text);
            return text.toString();
        });
        assertTrue(shown.contains("HostName 10.1.2.3") && shown.contains("ProxyJump bastion"), shown);

        // A user typed in is yours: another host does not replace it, and a host the config does not know
        // puts back only what the config had filled in.
        List<TextField> fields = fields(form);
        FxTestSupport.runOnFx(() -> fields.get(2).setText("me"));
        FxTestSupport.runOnFx(() -> fields.get(0).setText("plain.example.test"));
        assertEquals(List.of("plain.example.test", "22", "me", "", ""), values(form));
    }

    @Test
    void aNewConnectionStartsFromDefaultsAndNeedsAHostAndAUser() throws Exception {
        FxTestSupport.runOnFx(() -> button(panel, tr("remotePanel.new")).fire());
        Parent form = form();
        assertNotNull(form);
        List<TextField> fields = fields(form);
        assertEquals(List.of("", "22", System.getProperty("user.name", ""), "", ""), values(form));
        Button connect = FxTestSupport.callOnFx(() -> button(form, tr("remote.connect.button")));
        assertTrue(FxTestSupport.callOnFx(connect::isDisabled), "no host yet");

        FxTestSupport.runOnFx(() -> fields.get(0).setText("new.example.test"));
        assertFalse(FxTestSupport.callOnFx(connect::isDisabled));
        FxTestSupport.runOnFx(() -> fields.get(2).setText("  "));
        assertTrue(FxTestSupport.callOnFx(connect::isDisabled), "no user");

        // Each authentication method enables only the fields it uses.
        @SuppressWarnings("unchecked")
        ComboBox<RemoteConnection.AuthMethod> auth =
                (ComboBox<RemoteConnection.AuthMethod>) FxTestSupport.callOnFx(() -> form.lookup(".combo-box"));
        TextField key = fields.get(3);
        assertEquals(RemoteConnection.AuthMethod.DEFAULT_KEYS, FxTestSupport.callOnFx(auth::getValue));
        assertTrue(FxTestSupport.callOnFx(key::isDisabled));

        FxTestSupport.runOnFx(() -> auth.setValue(RemoteConnection.AuthMethod.PASSWORD));
        assertTrue(FxTestSupport.callOnFx(key::isDisabled), "the password is asked for while logging in");

        FxTestSupport.runOnFx(() -> auth.setValue(RemoteConnection.AuthMethod.KEY));
        assertFalse(FxTestSupport.callOnFx(key::isDisabled));

        // The picker names each method.
        assertEquals(
                List.of(tr("remote.auth.defaultKeys"), tr("remote.auth.key"), tr("remote.auth.password"), ""),
                FxTestSupport.callOnFx(() -> {
                    List<String> names = new ArrayList<>();
                    for (RemoteConnection.AuthMethod m : RemoteConnection.AuthMethod.values()) {
                        names.add(auth.getConverter().toString(m));
                    }
                    names.add(auth.getConverter().toString(null));
                    return names;
                }));

        // The right-click menu's "New" opens the same blank form.
        FxTestSupport.runOnFx(() -> {
            host.overlay.hide();
            list().getContextMenu().getItems().get(1).fire();
        });
        assertEquals("", values(form()).get(0));
    }

    @Test
    void removingASiteForgetsItAndKeepsTheOtherSelected() throws Exception {
        FxTestSupport.runOnFx(() -> {
            list().getSelectionModel().select(BUILD);
            button(panel, tr("remotePanel.remove")).fire();
        });
        assertEquals(List.of(BUILD.id()), ops.removed);
        assertEquals(List.of(DOCS), FxTestSupport.callOnFx(() -> List.copyOf(list().getItems())));

        // A refresh keeps the selection when the site is still there.
        FxTestSupport.runOnFx(() -> {
            list().getSelectionModel().select(DOCS);
            ops.saved.add(0, BUILD);
            coordinator.refreshPanel();
        });
        assertEquals(List.of(BUILD, DOCS), FxTestSupport.callOnFx(() -> List.copyOf(list().getItems())));
        assertEquals(
                DOCS, FxTestSupport.callOnFx(() -> list().getSelectionModel().getSelectedItem()));

        FxTestSupport.runOnFx(() -> {
            MenuItem remove = list().getContextMenu().getItems().get(3);
            remove.fire();
        });
        assertEquals(List.of(BUILD.id(), DOCS.id()), ops.removed);

        // With nothing selected the buttons' actions do nothing.
        FxTestSupport.runOnFx(() -> {
            list().getSelectionModel().clearSelection();
            list().getContextMenu().getItems().get(0).fire();
            list().getContextMenu().getItems().get(3).fire();
        });
        assertEquals(2, ops.removed.size());
        assertNull(form());
    }

    @Test
    void manageConnectionsPicksASavedSiteOrGoesStraightToANewOne() throws Exception {
        FxTestSupport.runOnFx(coordinator::manageConnections);
        Node picker = FxTestSupport.callOnFx(() -> overlayRoot.lookup(".command-palette"));
        assertNotNull(picker);
        ListView<?> rows = (ListView<?>) FxTestSupport.callOnFx(() -> picker.lookup(".list-view"));
        assertEquals(List.of(BUILD, DOCS), FxTestSupport.callOnFx(() -> List.copyOf(rows.getItems())));
        FxTestSupport.runOnFx(() -> {
            rows.getSelectionModel().select(1);
            Event.fireEvent(
                    picker.lookup(".text-field"),
                    new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
        });
        assertEquals("docs.example.test", values(form()).get(0), "the chosen site, pre-filled");

        // Nothing saved: there is nothing to pick from.
        FxTestSupport.runOnFx(() -> {
            host.overlay.hide();
            ops.saved.clear();
            coordinator.manageConnections();
        });
        assertEquals("", values(form()).get(0));
        assertFalse(coordinator.isMounted());
    }

    @Test
    void openingARemoteFileNeedsItsConnectionToBeOpen() throws Exception {
        FxTestSupport.runOnFx(coordinator::openFile);
        assertEquals(tr("remote.openFile.title"), host.promptTitle);
        assertEquals("sftp://", host.promptInitial);
        FxTestSupport.runOnFx(() -> host.promptAccept.accept("  sftp://nobody@unconnected.example.test/etc/motd  "));
        assertEquals(tr("status.remote.notConnected"), host.last);
        assertEquals(List.of(), ops.opened);
    }

    @Test
    void anUnknownHostKeyIsAcceptedOnlyByTheConnectButton() throws Exception {
        assertTrue(answerHostKey(tr("dialog.hostKey.connect"), true));
        assertFalse(answerHostKey(null, true), "closing the question refuses the key");
        // Asked from the connection's own thread, as SSH does: the same question, the same answers.
        assertTrue(answerHostKey(tr("dialog.hostKey.connect"), false));
        assertFalse(answerHostKey(null, false));
    }

    @Test
    void anUnansweredLoginQuestionClosesItsDialogWhenItTimesOut() throws Exception {
        coordinator.loginAnswerTimeoutMillis = 300;
        LoginPrompt.Challenge challenge = new LoginPrompt.Challenge(
                LoginPrompt.Kind.PASSWORD,
                "ada@docs.example.test",
                "",
                "",
                List.of(new LoginPrompt.Question("", false)),
                1);

        boolean stillShowing = unansweredQuestionLeavesItsDialogShowing(
                "answerLogin", new Class<?>[] {LoginPrompt.Challenge.class}, challenge);

        assertFalse(stillShowing, "the login dialog stayed on screen after its question gave up");
    }

    @Test
    void anUnansweredHostKeyQuestionClosesItsDialogWhenItTimesOut() throws Exception {
        coordinator.hostKeyAnswerTimeoutMillis = 300;

        boolean stillShowing = unansweredQuestionLeavesItsDialogShowing(
                "confirmHostKey",
                new Class<?>[] {String.class, int.class, String.class, String.class},
                "new.example.test",
                2222,
                "ssh-ed25519",
                "SHA256:abcdef");

        assertFalse(stillShowing, "the host-key dialog stayed on screen after its question gave up");
    }

    /**
     * Asks a question from a worker, as SSH does, and leaves it unanswered: it must give up with a refusal
     * (null, or false for a host key) once its time is up. Returns whether its dialog is still on screen two
     * seconds later.
     */
    private boolean unansweredQuestionLeavesItsDialogShowing(String method, Class<?>[] types, Object... args)
            throws Exception {
        CompletableFuture<Window> shown = new CompletableFuture<>();
        ListChangeListener<Window> watcher = change -> {
            while (change.next()) {
                for (Window w : change.getAddedSubList()) {
                    if (w.getScene() != null && w.getScene().getRoot() instanceof DialogPane) {
                        shown.complete(w);
                    }
                }
            }
        };
        FxTestSupport.runOnFx(() -> Window.getWindows().addListener(watcher));
        CompletableFuture<Object> answer = new CompletableFuture<>();
        try {
            Thread worker = new Thread(
                    () -> {
                        try {
                            answer.complete(FxTestSupport.call(coordinator, method, types, args));
                        } catch (Throwable t) {
                            answer.completeExceptionally(t);
                        }
                    },
                    "test-ssh-connect");
            worker.setDaemon(true);
            worker.start();
            Window dialog = shown.get(30, TimeUnit.SECONDS);
            Object result = answer.get(30, TimeUnit.SECONDS);
            assertTrue(result == null || Boolean.FALSE.equals(result), "an unanswered question was taken as " + result);
            for (int i = 0; i < 100 && FxTestSupport.callOnFx(dialog::isShowing); i++) {
                Thread.sleep(20);
            }
            return FxTestSupport.callOnFx(dialog::isShowing);
        } finally {
            FxTestSupport.runOnFx(() -> {
                Window.getWindows().removeListener(watcher);
                for (Window w : List.copyOf(Window.getWindows())) {
                    if (w.getScene() != null && w.getScene().getRoot() instanceof DialogPane) {
                        w.hide(); // a dialog this test left open must not block the next one
                    }
                }
            });
        }
    }

    /**
     * Asks the host-key question — on the FX thread or from a worker — and answers it by pressing the button
     * named {@code press}, or by closing the dialog when {@code press} is null.
     */
    private boolean answerHostKey(String press, boolean onFxThread) throws Exception {
        CompletableFuture<DialogPane> shown = new CompletableFuture<>();
        ListChangeListener<Window> watcher = change -> {
            while (change.next()) {
                for (Window w : change.getAddedSubList()) {
                    if (w.getScene() != null && w.getScene().getRoot() instanceof DialogPane pane) {
                        shown.complete(pane);
                    }
                }
            }
        };
        FxTestSupport.runOnFx(() -> Window.getWindows().addListener(watcher));
        CompletableFuture<Boolean> answer = new CompletableFuture<>();
        Runnable ask = () -> {
            try {
                answer.complete((Boolean) FxTestSupport.call(
                        coordinator,
                        "confirmHostKey",
                        new Class<?>[] {String.class, int.class, String.class, String.class},
                        "new.example.test",
                        2222,
                        "ssh-ed25519",
                        "SHA256:abcdef"));
            } catch (Throwable t) {
                answer.completeExceptionally(t);
            }
        };
        try {
            if (onFxThread) {
                Platform.runLater(ask);
            } else {
                Thread worker = new Thread(ask, "test-ssh-connect");
                worker.setDaemon(true);
                worker.start();
            }
            DialogPane pane = shown.get(30, TimeUnit.SECONDS);
            // Runs inside the dialog's own event loop.
            Platform.runLater(() -> {
                assertTrue(pane.getContentText().contains("new.example.test:2222"), pane.getContentText());
                assertTrue(pane.getContentText().contains("SHA256:abcdef"), pane.getContentText());
                if (press == null) {
                    pane.getScene().getWindow().hide();
                } else {
                    button(pane, press).fire();
                }
            });
            return answer.get(30, TimeUnit.SECONDS);
        } finally {
            FxTestSupport.runOnFx(() -> Window.getWindows().removeListener(watcher));
        }
    }

    private static final class Host extends CoordinatorHostStub {
        final OverlayHost overlay = new OverlayHost();
        volatile String last;
        volatile String promptTitle;
        volatile String promptInitial;
        volatile Consumer<String> promptAccept;

        @Override
        public OverlayHost overlayHost() {
            return overlay;
        }

        @Override
        public void setStatus(String message) {
            last = message;
        }

        @Override
        public void promptText(String title, String label, String initial, Consumer<String> onAccept) {
            promptTitle = title;
            promptInitial = initial;
            promptAccept = onAccept;
        }
    }

    private static final class Ops implements RemoteCoordinator.Ops {
        final List<RemoteConnection> saved = new CopyOnWriteArrayList<>();
        final List<String> removed = new CopyOnWriteArrayList<>();
        final List<Path> opened = new CopyOnWriteArrayList<>();

        @Override
        public KeymapManager keymap() {
            return null;
        }

        @Override
        public void openPath(Path file) {
            opened.add(file);
        }

        @Override
        public void setProjectRoot(Path root) {}

        @Override
        public void openProjectToolWindow() {}

        @Override
        public Path activeProjectRoot() {
            return null;
        }

        @Override
        public void reportError(String summary, String detail) {}

        @Override
        public List<RemoteConnection> connections() {
            return saved;
        }

        @Override
        public void putConnection(RemoteConnection conn) {}

        @Override
        public void removeConnection(String id) {
            removed.add(id);
            saved.removeIf(c -> c.id().equals(id));
        }

        @Override
        public void invalidatePendingWrite(Path path) {}
    }
}
