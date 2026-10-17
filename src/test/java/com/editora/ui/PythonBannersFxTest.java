package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The banners on a Python file in a real window: with language servers off (the default) the first Python
 * file offers to turn them on (Python review B15); a configured server command that is missing leads to
 * Settings instead of offering to install Pyright, which would leave that setting broken (B8).
 */
@Tag("fx")
class PythonBannersFxTest {

    @TempDir
    Path work;

    /** The install banner's title, or null while it is not shown. */
    private static String bannerTitle(MainController c) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            Node bar = c.stage.getScene().getRoot().lookup(".lsp-install-bar");
            if (bar == null || !bar.isVisible() || bar.getScene() == null) {
                return null;
            }
            for (Node n : bar.lookupAll(".label")) {
                if (n instanceof Label l
                        && l.getText() != null
                        && !l.getText().isBlank()
                        && !(l.getParent() instanceof Button)) {
                    return l.getText();
                }
            }
            return null;
        });
    }

    private static String waitForBanner(MainController c, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        String now = bannerTitle(c);
        while (!expected.equals(now) && System.nanoTime() < deadline) {
            FxTestSupport.drainFx();
            now = bannerTitle(c);
        }
        return now;
    }

    private static Button button(MainController c, String text) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            for (Node n :
                    c.stage.getScene().getRoot().lookup(".lsp-install-bar").lookupAll(".button")) {
                if (n instanceof Button b && text.equals(b.getText())) {
                    return b;
                }
            }
            throw new AssertionError("no button " + text);
        });
    }

    @Test
    void theFirstPythonFileOffersToTurnOnLanguageServers() throws Exception {
        FxTestSupport.bootToolkit();
        Path app = Files.writeString(Files.createDirectories(work.resolve("p")).resolve("app.py"), "import os\n");
        try (FxWindowFixture fx = FxWindowFixture.create(Files.createDirectories(work.resolve("cfg")), shared -> {})) {
            MainController c = fx.controller;
            FxTestSupport.runOnFx(() -> c.openAndNavigate(app, 1));
            assertEquals(tr("install.banner.enablePython"), waitForBanner(c, tr("install.banner.enablePython")));
            Button turnOn = button(c, tr("install.banner.turnOn"));
            FxTestSupport.runOnFx(turnOn::fire);
            assertTrue(fx.shared.getSettings().isLspSupport(), "Turn on switched language servers on");
        }
    }

    @Test
    void aMissingCustomServerLeadsToSettingsNotToInstallingPyright() throws Exception {
        FxTestSupport.bootToolkit();
        Path app = Files.writeString(Files.createDirectories(work.resolve("p")).resolve("app.py"), "import os\n");
        String command = work.resolve("nowhere/bin/pylsp").toString();
        try (FxWindowFixture fx = FxWindowFixture.create(Files.createDirectories(work.resolve("cfg")), shared -> {
            shared.getSettings().setLspSupport(true);
            shared.getSettings().setPythonLspCommand(command);
        })) {
            MainController c = fx.controller;
            FxTestSupport.runOnFx(() -> c.openAndNavigate(app, 1));
            String expected = tr("install.banner.commandMissing", command);
            assertEquals(expected, waitForBanner(c, expected));
            button(c, tr("install.banner.openSettings")); // the action is Settings…, not Install
        }
    }
}
