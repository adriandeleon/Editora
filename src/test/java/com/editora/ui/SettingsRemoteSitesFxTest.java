package com.editora.ui;

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
import com.editora.vfs.RemoteConnection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
            ListView<RemoteConnection> list = FxTestSupport.field(w, "remoteList");
            List<TextField> f = formFields(w);
            FxTestSupport.runOnFx(() -> list.getSelectionModel().select(0));

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
}
