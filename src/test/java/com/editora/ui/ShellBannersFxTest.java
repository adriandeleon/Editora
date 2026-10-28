package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;

import com.editora.install.InstallCatalog;
import com.editora.install.VersionFloor;
import com.editora.process.ProcessRunner;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The banners on a shell script in a real window (Bash review C8/A4/A13/A5): with language servers off the first
 * script offers to turn them on; with the server missing the banner says what Install brings (the pinned server,
 * which needs Node.js 20, and ShellCheck and shfmt); with the server there but its tools missing, a banner whose
 * Install fetches them replaces the echo line that the server's start-up status overwrote; and a missing command the
 * user chose leads to Settings, not to an npm install that would leave it broken.
 */
@Tag("fx")
class ShellBannersFxTest {

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

    private static List<String> buttons(MainController c) throws Exception {
        return FxTestSupport.callOnFx(
                () -> c.stage.getScene().getRoot().lookup(".lsp-install-bar").lookupAll(".button").stream()
                        .filter(n -> n instanceof Button)
                        .map(n -> ((Button) n).getText())
                        .toList());
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

    /** Waits until the Bash server's detection has answered missing (the command named does not exist). */
    private static void awaitBashMissing(MainController c) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!FxTestSupport.callOnFx(() -> c.lspCoordinator.isServerMissing("bash"))
                && System.nanoTime() < deadline) {
            FxTestSupport.drainFx();
        }
        assertTrue(FxTestSupport.callOnFx(() -> c.lspCoordinator.isServerMissing("bash")), "detection answered");
    }

    private Path script(String name) throws Exception {
        return Files.writeString(
                Files.createDirectories(work.resolve("p")).resolve(name), "#!/usr/bin/env bash\necho \"$1\"\n");
    }

    @Test
    void theFirstShellScriptOffersToTurnOnLanguageServers() throws Exception {
        FxTestSupport.bootToolkit();
        Path deploy = script("deploy.sh");
        try (FxWindowFixture fx = FxWindowFixture.create(Files.createDirectories(work.resolve("cfg")), shared -> {})) {
            MainController c = fx.controller;
            FxTestSupport.runOnFx(() -> c.openAndNavigate(deploy, 1));
            assertEquals(tr("install.banner.enableShell"), waitForBanner(c, tr("install.banner.enableShell")));
            FxTestSupport.runOnFx(button(c, tr("install.banner.turnOn"))::fire);
            assertTrue(fx.shared.getSettings().isLspSupport(), "Turn on switched language servers on");
        }
    }

    @Test
    void theServerBannerSaysWhatInstallBringsAndNeeds() throws Exception {
        assumeTrue(
                ProcessRunner.resolveExecutable(List.of("bash-language-server"))
                        .get(0)
                        .equals("bash-language-server"),
                "bash-language-server is on this machine's PATH");
        FxTestSupport.bootToolkit();
        Path deploy = script("deploy.sh");
        try (FxWindowFixture fx = FxWindowFixture.create(Files.createDirectories(work.resolve("cfg")), shared -> {
            shared.getSettings().setLspSupport(true);
        })) {
            MainController c = fx.controller;
            FxTestSupport.runOnFx(() -> c.openAndNavigate(deploy, 1));
            String expected = tr(
                    "install.banner.bashServer", InstallCatalog.BASH_LS_VERSION, VersionFloor.NODE_BASH_LS.minimum());
            assertEquals(expected, waitForBanner(c, expected));
            assertTrue(buttons(c).contains(tr("install.banner.install")));
        }
    }

    @Test
    void aMissingCustomBashCommandLeadsToSettingsNotToInstall() throws Exception {
        FxTestSupport.bootToolkit();
        Path deploy = script("deploy.sh");
        String command = work.resolve("nonexistent/bash-language-server") + " start";
        try (FxWindowFixture fx = FxWindowFixture.create(Files.createDirectories(work.resolve("cfg")), shared -> {
            shared.getSettings().setLspSupport(true);
            shared.getSettings().setBashLspCommand(command);
        })) {
            MainController c = fx.controller;
            FxTestSupport.runOnFx(() -> c.openAndNavigate(deploy, 1));
            String expected = tr("install.banner.commandMissing", command);
            assertEquals(expected, waitForBanner(c, expected));
            assertFalse(buttons(c).contains(tr("install.banner.install")), "no npm install that leaves it broken (A5)");
        }
    }

    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS) // a sh "shellcheck"
    void aServerWithoutItsToolsGetsABannerThatInstallsThem() throws Exception {
        FxTestSupport.bootToolkit();
        Path deploy = script("deploy.sh");
        String shellcheck = work.resolve("none/shellcheck").toString();
        String shfmt = work.resolve("none/shfmt").toString();
        try (FxWindowFixture fx = FxWindowFixture.create(Files.createDirectories(work.resolve("cfg")), shared -> {
            shared.getSettings().setLspSupport(true);
            shared.getSettings().setBashLspCommand(work.resolve("nonexistent/bash-language-server") + " start");
            shared.getSettings().setBashShellcheckPath(shellcheck);
            shared.getSettings().setBashShfmtPath(shfmt);
        })) {
            MainController c = fx.controller;
            FxTestSupport.runOnFx(() -> c.openAndNavigate(deploy, 1));
            awaitBashMissing(c);
            // the server is there (as if found): what is missing is ShellCheck and shfmt
            FxTestSupport.runOnFx(() -> {
                c.lspCoordinator.setServerAvailableForTest("bash", true);
                c.installPrompts.maybeOfferInstall(c.activeBuffer());
            });
            assertEquals(tr("install.banner.shellTools"), waitForBanner(c, tr("install.banner.shellTools")));
            assertTrue(buttons(c).contains(tr("install.banner.install")));

            // only shfmt missing: the banner says what that costs
            Path found = Files.createDirectories(work.resolve("bin")).resolve("shellcheck");
            Files.writeString(found, "#!/bin/sh\n");
            assertTrue(found.toFile().setExecutable(true));
            FxTestSupport.runOnFx(() -> {
                fx.shared.getSettings().setBashShellcheckPath(found.toString());
                c.installPrompts.maybeOfferInstall(c.activeBuffer());
            });
            assertEquals(tr("install.banner.shfmtMissing"), waitForBanner(c, tr("install.banner.shfmtMissing")));

            FxTestSupport.runOnFx(button(c, tr("install.banner.dismiss"))::fire);
            FxTestSupport.runOnFx(() -> c.installPrompts.maybeOfferInstall(c.activeBuffer()));
            assertEquals(null, bannerTitle(c), "Not now holds for the window");
        }
    }
}
