package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

import com.editora.i18n.Messages;
import com.editora.vfs.RemoteConnection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Settings → Remote, below a saved site: whether its host may run commands, and the tools there. */
@Tag("fx")
class SettingsRemoteHostFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        Messages.init("en");
    }

    private static <T extends Node> List<T> all(Node root, Class<T> type, List<T> out) {
        if (type.isInstance(root)) {
            out.add(type.cast(root));
        }
        if (root instanceof Parent p) {
            p.getChildrenUnmodifiable().forEach(c -> all(c, type, out));
        }
        return out;
    }

    private static Region remotePage(SettingsWindow w) {
        Map<?, Region> pages = FxTestSupport.field(w, "pages");
        return pages.entrySet().stream()
                .filter(e -> e.getKey().toString().equals("REMOTE"))
                .findFirst()
                .orElseThrow()
                .getValue();
    }

    private static TextField fieldWithText(Region page, String text) {
        return all(page, TextField.class, new ArrayList<>()).stream()
                .filter(f -> text.equals(f.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no field reads " + text));
    }

    @Test
    void theSelectedSitesHostShowsAndSavesItsAgreementAndTools() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            RemoteConnection site = new RemoteConnection(
                    "build.example", 22, "me", RemoteConnection.AuthMethod.DEFAULT_KEYS, "", "Build", "");
            FxTestSupport.runOnFx(() -> {
                fx.shared.getSettings().setRemoteExecution(true); // the host section follows it
                fx.shared.putConnection(site);
                fx.shared.allowRemoteExec(site.id());
                fx.shared.setRemoteTools(
                        site.id(), Map.of("git", "/opt/git/bin/git", "lsp.python", "basedpyright-langserver --stdio"));
                SettingsWindow w = FxTestSupport.field(fx.controller, "settingsWindow");
                Stage owner = new Stage();
                owner.setWidth(1400);
                owner.setHeight(900);
                w.show(owner);
                Region page = remotePage(w);

                CheckBox allowed = all(page, CheckBox.class, new ArrayList<>()).stream()
                        .filter(c -> tr("settings.remote.host.allowed").equals(c.getText()))
                        .findFirst()
                        .orElseThrow();
                assertTrue(allowed.isSelected());
                TextField git = fieldWithText(page, "/opt/git/bin/git");
                TextArea servers = all(page, TextArea.class, new ArrayList<>()).get(0);
                assertEquals("python = basedpyright-langserver --stdio", servers.getText());

                // Clear git, name rg, swap the language server; Enter in a field saves the whole section.
                git.setText("");
                servers.setText("go = /home/me/go/bin/gopls");
                TextField rg = all(page, TextField.class, new ArrayList<>()).stream()
                        .filter(f -> f.getPromptText() != null
                                && f.getPromptText().equals(tr("settings.remote.tool.prompt"))
                                && f.getText().isEmpty()
                                && f != git)
                        .findFirst()
                        .orElseThrow();
                rg.setText("/usr/local/bin/rg");
                rg.fireEvent(new javafx.event.ActionEvent());
                Map<String, String> saved = fx.shared.remoteTools(site.id());
                assertFalse(saved.containsKey("git"), saved.toString());
                assertEquals("/home/me/go/bin/gopls", saved.get("lsp.go"));
                assertFalse(saved.containsKey("lsp.python"));
                assertEquals(
                        1,
                        saved.values().stream()
                                .filter("/usr/local/bin/rg"::equals)
                                .count());

                // Editing the site's port keeps its host settings with it.
                TextField port = fieldWithText(page, "22");
                port.setText("2222");
                port.fireEvent(new javafx.event.ActionEvent());
                String moved = "me@build.example:2222";
                assertTrue(fx.shared.remoteExecAllowed(moved));
                assertEquals(
                        "/home/me/go/bin/gopls", fx.shared.remoteTools(moved).get("lsp.go"));

                // And the agreement can be taken back.
                allowed.fire();
                assertFalse(fx.shared.remoteExecAllowed(moved));
                Stage stage = FxTestSupport.field(w, "stage");
                stage.hide();
                owner.hide();
            });
        }
    }
}
