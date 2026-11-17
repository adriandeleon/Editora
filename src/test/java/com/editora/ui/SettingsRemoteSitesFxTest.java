package com.editora.ui;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

import com.editora.i18n.Messages;
import com.editora.testkit.Await;
import com.editora.vfs.RemoteConnection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Settings → Remote: editing the saved sites in a shown window, the way a user does, one field at a time. */
@Tag("fx")
class SettingsRemoteSitesFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        Messages.init("en");
    }

    static <T extends Node> List<T> all(Node root, Class<T> type, List<T> out) {
        if (type.isInstance(root)) {
            out.add(type.cast(root));
        }
        if (root instanceof Parent p) {
            p.getChildrenUnmodifiable().forEach(c -> all(c, type, out));
        }
        return out;
    }

    static Region remotePage(SettingsWindow w) {
        Map<?, Region> pages = FxTestSupport.field(w, "pages");
        return pages.entrySet().stream()
                .filter(e -> e.getKey().toString().equals("REMOTE"))
                .findFirst()
                .orElseThrow()
                .getValue();
    }

    /** Shows Settings on its Remote page, in a real window, and returns the window. */
    static SettingsWindow showRemote(FxWindowFixture fx) throws Exception {
        FxTestSupport.runOnFx(() -> {
            SettingsWindow w = fx.controller.settingsWindow;
            w.showRemote(FxTestSupport.<Stage>field(fx.controller, "stage"));
        });
        FxTestSupport.drainFx();
        return fx.controller.settingsWindow;
    }

    /** The site form's fields, in order: label, host, port, user, key file. */
    static List<TextField> formFields(SettingsWindow w) {
        GridPane form = all(remotePage(w), GridPane.class, new ArrayList<>()).get(0);
        return all(form, TextField.class, new ArrayList<>());
    }

    static void typeAndEnter(TextField field, String text) throws Exception {
        FxTestSupport.runOnFx(() -> {
            field.requestFocus();
            field.setText(text);
            field.fireEvent(new ActionEvent());
        });
        FxTestSupport.drainFx();
    }

    /**
     * With exactly one saved site, the first committed field used to clear the list's selection (JavaFX reads
     * the replacement of a one-item list as "every item removed"): the form greyed out and emptied, and every
     * later field needed the site to be clicked again.
     */
    @Test
    void theOnlySiteStaysSelectedAndEditableAcrossEdits() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            FxTestSupport.runOnFx(() -> fx.shared.putConnection(new RemoteConnection(
                    "old.example", 22, "me", RemoteConnection.AuthMethod.DEFAULT_KEYS, "", "Box", "")));
            SettingsWindow w = showRemote(fx);
            ListView<RemoteConnection> list = FxTestSupport.field(FxTestSupport.field(w, "remoteSites"), "list");
            List<TextField> f = formFields(w);
            FxTestSupport.runOnFx(() -> list.getSelectionModel().select(0));
            // The list's rows say why when grayed (KBD-04): Connect when this window cannot connect.
            FxTestSupport.runOnFx(() -> {
                javafx.scene.control.ContextMenu menu = list.getContextMenu();
                menu.getOnShowing().handle(new javafx.stage.WindowEvent(menu, javafx.stage.WindowEvent.WINDOW_SHOWING));
                javafx.scene.control.MenuItem connect = menu.getItems().get(0);
                String why = com.editora.editor.RowReasons.reasonOf(connect);
                assertEquals(connect.isDisable(), why != null, "grayed exactly when it says why");
                javafx.scene.control.Button button = button(w, Messages.tr("settings.remote.connect"));
                if (!button.isVisible()) {
                    assertEquals(Messages.tr("row.unavailable.cannotConnectHere"), why);
                }
            });

            typeAndEnter(f.get(1), "new.example");
            assertEquals(
                    0, FxTestSupport.callOnFx(() -> list.getSelectionModel().getSelectedIndex()));
            assertFalse(f.get(1).isDisabled(), "the form stays usable");
            assertEquals("new.example", f.get(1).getText());
            assertEquals("22", f.get(2).getText(), "the other fields keep showing the site");

            typeAndEnter(f.get(2), "2222");
            typeAndEnter(f.get(3), "deploy");
            RemoteConnection saved = fx.shared.getConnections().get(0);
            assertEquals("new.example", saved.host());
            assertEquals(2222, saved.port());
            assertEquals("deploy", saved.user());
            assertEquals(1, fx.shared.getConnections().size());
        }
    }

    private static boolean red(TextField field) {
        return field.getPseudoClassStates().contains(atlantafx.base.theme.Styles.STATE_DANGER);
    }

    /** A port of 70000 or a host with spaces used to be saved without a word, failing only at Connect. */
    @Test
    void aValueThatCannotWorkIsRefusedWithTheReasonAndNotSaved() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            FxTestSupport.runOnFx(() -> fx.shared.putConnection(new RemoteConnection(
                    "box.example", 22, "me", RemoteConnection.AuthMethod.DEFAULT_KEYS, "", "Box", "")));
            SettingsWindow w = showRemote(fx);
            ListView<RemoteConnection> list = FxTestSupport.field(FxTestSupport.field(w, "remoteSites"), "list");
            List<TextField> f = formFields(w);
            FxTestSupport.runOnFx(() -> list.getSelectionModel().select(0));

            typeAndEnter(f.get(2), "70000");
            assertTrue(red(f.get(2)));
            assertEquals(
                    Messages.tr("settings.remote.check.port"),
                    f.get(2).getTooltip().getText());
            assertEquals("70000", f.get(2).getText(), "what was typed stays, to be fixed");
            assertEquals(22, fx.shared.getConnections().get(0).port(), "nothing was saved");

            typeAndEnter(f.get(2), "");
            assertFalse(red(f.get(2)));
            assertEquals("22", f.get(2).getText(), "blank is the default port, shown");

            typeAndEnter(f.get(1), "my box");
            assertTrue(red(f.get(1)));
            assertEquals("box.example", fx.shared.getConnections().get(0).host());
            typeAndEnter(f.get(1), "box2.example");
            assertFalse(red(f.get(1)));
            assertEquals("box2.example", fx.shared.getConnections().get(0).host());
        }
    }

    /** The key field's own hint reads ~/.ssh/id_ed25519, but a ~ path was saved as typed and never found. */
    @Test
    void aKeyFileUnderTildeIsExpandedAndAMissingOneIsFlagged() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            FxTestSupport.runOnFx(() -> fx.shared.putConnection(
                    new RemoteConnection("box.example", 22, "me", RemoteConnection.AuthMethod.KEY, "", "Box", "")));
            SettingsWindow w = showRemote(fx);
            ListView<RemoteConnection> list = FxTestSupport.field(FxTestSupport.field(w, "remoteSites"), "list");
            List<TextField> f = formFields(w);
            FxTestSupport.runOnFx(() -> list.getSelectionModel().select(0));

            typeAndEnter(f.get(4), "~/.ssh/editora-test-no-such-key");
            String expanded = System.getProperty("user.home") + "/.ssh/editora-test-no-such-key";
            assertEquals(expanded, fx.shared.getConnections().get(0).keyPath());
            assertEquals(expanded, f.get(4).getText());
            Await.holdsWithin(() -> onFx(() -> f.get(4).getTooltip()) != null, Duration.ofSeconds(10));
            assertEquals(
                    Messages.tr("settings.remote.check.keyMissing", expanded),
                    FxTestSupport.callOnFx(() -> f.get(4).getTooltip().getText()));
        }
    }

    static javafx.scene.control.Button button(SettingsWindow w, String text) {
        return all(remotePage(w), javafx.scene.control.Button.class, new ArrayList<>()).stream()
                .filter(b -> text.equals(b.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no button " + text));
    }

    /**
     * Remove deleted a site at once and kept its host's "may run commands", so a site added again for the same
     * host was trusted without asking. It now asks "Delete “x”?" (Cancel the default) and offers to forget that.
     */
    @Test
    void removingASiteAsksFirstAndCanForgetItsHostsTrust() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            RemoteConnection site = new RemoteConnection(
                    "box.example", 22, "me", RemoteConnection.AuthMethod.DEFAULT_KEYS, "", "Box", "");
            FxTestSupport.runOnFx(() -> {
                fx.shared.putConnection(site);
                fx.shared.allowRemoteExec(site.id());
            });
            SettingsWindow w = showRemote(fx);
            RemoteSitesPane pane = FxTestSupport.field(w, "remoteSites");
            ListView<RemoteConnection> list = FxTestSupport.field(pane, "list");
            FxTestSupport.runOnFx(() -> list.getSelectionModel().select(0));
            javafx.scene.control.Button remove = button(w, Messages.tr("settings.remote.remove"));

            // The question, answered with no window: Enter does not delete, and the host question is there.
            try (DialogAnswers answers =
                    DialogAnswers.pressing(javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE)) {
                FxTestSupport.runOnFx(remove::fire);
                Dialogs.Asked asked = answers.only();
                assertEquals(RemoteSitesPane.deleteQuestion(site.displayLabel()).title(), asked.title());
                assertTrue(
                        asked.answer(javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE)
                                .isDefault(),
                        "Cancel is the default");
                Dialogs.Answer delete = asked.answer(Messages.tr("settings.remote.delete"));
                assertTrue(delete.destructive() && !delete.isDefault(), "Delete is red and never the default");
                assertTrue(FxTestSupport.callOnFx(() ->
                        all(asked.dialog().getDialogPane(), javafx.scene.control.CheckBox.class, new ArrayList<>())
                                .stream()
                                .anyMatch(c -> c.getText()
                                        .equals(Messages.tr("settings.remote.deleteForgetHost", "box.example")))));
                assertEquals(
                        RemoteSitesPane.deleteQuestion(site.displayLabel()).message() + "\n"
                                + RemoteSitesPane.forgetHostOption("box.example"),
                        asked.content());
                assertEquals(1, fx.shared.getConnections().size(), "cancelled: the site stays");
                assertTrue(fx.shared.remoteExecAllowed(site.id()), "and so does its host's agreement");

                answers.nowClosing();
                FxTestSupport.runOnFx(remove::fire);
                assertEquals(1, fx.shared.getConnections().size(), "closing the question keeps the site too");

                // Delete, with the box left ticked as it opens: the site goes and its host's trust with it.
                answers.nowPressing(Messages.tr("settings.remote.delete"));
                FxTestSupport.runOnFx(remove::fire);
                assertEquals(3, answers.asked().size());
            }
            assertEquals(List.of(), fx.shared.getConnections());
            assertFalse(fx.shared.remoteExecAllowed(site.id()), "a site added again for this host asks again");
        }
    }

    /** The question's check box is the caller's to read: unticked, the site goes and its host's agreement stays. */
    @Test
    void removingASiteWithTheBoxUntickedKeepsItsHostsTrust() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            RemoteConnection site = new RemoteConnection(
                    "box.example", 22, "me", RemoteConnection.AuthMethod.DEFAULT_KEYS, "", "Box", "");
            FxTestSupport.runOnFx(() -> {
                fx.shared.putConnection(site);
                fx.shared.allowRemoteExec(site.id());
            });
            SettingsWindow w = showRemote(fx);
            RemoteSitesPane pane = FxTestSupport.field(w, "remoteSites");
            ListView<RemoteConnection> list = FxTestSupport.field(pane, "list");
            FxTestSupport.runOnFx(() -> list.getSelectionModel().select(0));
            javafx.scene.control.Button remove = button(w, Messages.tr("settings.remote.remove"));
            try (DialogAnswers answers = DialogAnswers.with(asked -> {
                all(asked.dialog().getDialogPane(), javafx.scene.control.CheckBox.class, new ArrayList<>())
                        .get(0)
                        .setSelected(false);
                return asked.answer(javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
            })) {
                FxTestSupport.runOnFx(remove::fire);
                assertEquals(1, answers.asked().size());
            }
            assertEquals(List.of(), fx.shared.getConnections());
            assertTrue(fx.shared.remoteExecAllowed(site.id()), "the box was unticked: the host stays allowed");
        }
    }

    /** No Save button (every field saves itself); Connect… opens the Connect form for the site. */
    @Test
    void thereIsNoSaveButtonAndConnectOpensTheSite() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            FxTestSupport.runOnFx(() -> fx.shared.putConnection(new RemoteConnection(
                    "box.example", 2222, "me", RemoteConnection.AuthMethod.DEFAULT_KEYS, "", "Box", "")));
            SettingsWindow w = showRemote(fx);
            List<RemoteConnection> opened = new ArrayList<>();
            FxTestSupport.runOnFx(() -> {
                w.setRemoteConnect(opened::add);
                FxTestSupport.<ListView<RemoteConnection>>field(FxTestSupport.field(w, "remoteSites"), "list")
                        .getSelectionModel()
                        .select(0);
                assertTrue(all(remotePage(w), javafx.scene.control.Button.class, new ArrayList<>()).stream()
                        .noneMatch(b -> Messages.tr("settings.save").equals(b.getText())));
                button(w, Messages.tr("settings.remote.connect")).fire();
            });
            assertEquals("me@box.example:2222", opened.getFirst().id());
        }
    }

    /** The host section (may run commands, its tools) only matters while "Run commands on the remote host" is on. */
    @Test
    void theHostSectionFollowsRunCommandsOnTheRemoteHost() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            FxTestSupport.runOnFx(() -> fx.shared.putConnection(new RemoteConnection(
                    "box.example", 22, "me", RemoteConnection.AuthMethod.DEFAULT_KEYS, "", "Box", "")));
            SettingsWindow w = showRemote(fx);
            FxTestSupport.runOnFx(() -> {
                FxTestSupport.<ListView<RemoteConnection>>field(FxTestSupport.field(w, "remoteSites"), "list")
                        .getSelectionModel()
                        .select(0);
                javafx.scene.control.CheckBox run = FxTestSupport.field(w, "remoteExecutionCheck");
                javafx.scene.control.CheckBox allowed =
                        all(remotePage(w), javafx.scene.control.CheckBox.class, new ArrayList<>()).stream()
                                .filter(c -> Messages.tr("settings.remote.host.allowed")
                                        .equals(c.getText()))
                                .findFirst()
                                .orElseThrow();
                run.setSelected(true);
                assertFalse(allowed.isDisabled());
                run.setSelected(false);
                assertTrue(allowed.isDisabled());
                assertFalse(formFields(w).get(1).isDisabled(), "the site itself stays editable");
            });
        }
    }

    /** {@code probe}'s answer, read on the FX thread, for a condition {@link Await} checks. */
    private static <T> T onFx(java.util.concurrent.Callable<T> probe) {
        try {
            return FxTestSupport.callOnFx(probe);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
