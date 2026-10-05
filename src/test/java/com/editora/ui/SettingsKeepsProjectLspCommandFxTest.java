package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.config.Project;
import com.editora.lsp.LspManager;
import com.editora.lsp.LspTestHooks;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Opening Settings must not change which language-server command is in force.
 *
 * <p>The Settings window used to configure the window's {@code LspManager} with the <em>global</em>
 * commands in order to probe them for its status labels. For a trusted project that overrides a command in
 * {@code .editora/settings.json} that counted as a command change: the project's running server was shut
 * down and the next start used the global command — while the status bar and Doctor still showed the
 * project's.
 */
@Tag("fx")
class SettingsKeepsProjectLspCommandFxTest {

    @TempDir
    Path work;

    @Test
    void openingSettingsLeavesATrustedProjectOverrideConfigured() throws Exception {
        FxTestSupport.bootToolkit();
        Path proj = Files.createDirectories(work.resolve("projP"));
        Files.createDirectories(proj.resolve(".editora"));
        String override = "/usr/bin/env project-pinned-pyright --stdio";
        Files.writeString(
                proj.resolve(".editora/settings.json"), "{\"lspCommands\":{\"python\":\"" + override + "\"}}\n");

        FxWindowFixture fx = FxWindowFixture.create(Files.createTempDirectory(work, "cfg"), shared -> {});
        try {
            Project p = fx.shared.projects().createOrGet("projP", proj);
            fx.shared.getTrustStore().trust(proj);
            fx.shared.getSettings().setLspSupport(true);
            FxTestSupport.runOnFx(() -> fx.windowManager.openOrFocus(p));
            FxTestSupport.drainFx();
            MainController window = controllerFor(fx, p.id());
            LspManager manager = FxTestSupport.field(window, "lspManager");
            LspCoordinator coordinator = FxTestSupport.field(window, "lspCoordinator");
            FxTestSupport.runOnFx(() -> {
                LspTestHooks.useFakeSessions(manager);
                coordinator.applySupport();
            });
            assertEquals(override, LspTestHooks.configuredCommand(manager, "python"), "precondition");

            SettingsWindow settings = FxTestSupport.field(window, "settingsWindow");
            javafx.stage.Stage stage = FxTestSupport.field(window, "stage");
            FxTestSupport.runOnFx(() -> settings.show(stage)); // the user opens Settings and changes nothing
            Thread.sleep(300);
            FxTestSupport.drainFx();

            assertEquals(
                    override,
                    LspTestHooks.configuredCommand(manager, "python"),
                    "the project's command must still be the one the manager launches");
            javafx.stage.Stage settingsStage = FxTestSupport.field(settings, "stage");
            FxTestSupport.runOnFx(settingsStage::close);
        } finally {
            fx.dispose();
        }
    }

    /**
     * Revoking trust must reach the manager. It used to edit only the trust store: labels and Doctor showed
     * the user's own command while every later start still ran the project-supplied one.
     */
    @Test
    void revokingTrustStopsTheProjectCommandFromBeingLaunched() throws Exception {
        FxTestSupport.bootToolkit();
        Path proj = Files.createDirectories(work.resolve("projR"));
        Files.createDirectories(proj.resolve(".editora"));
        String override = "/usr/bin/env project-pinned-pyright --stdio";
        Files.writeString(
                proj.resolve(".editora/settings.json"), "{\"lspCommands\":{\"python\":\"" + override + "\"}}\n");

        FxWindowFixture fx = FxWindowFixture.create(Files.createTempDirectory(work, "cfg"), shared -> {});
        try {
            Project p = fx.shared.projects().createOrGet("projR", proj);
            fx.shared.getTrustStore().trust(proj);
            fx.shared.getSettings().setLspSupport(true);
            FxTestSupport.runOnFx(() -> fx.windowManager.openOrFocus(p));
            FxTestSupport.drainFx();
            MainController window = controllerFor(fx, p.id());
            LspManager manager = FxTestSupport.field(window, "lspManager");
            LspCoordinator coordinator = FxTestSupport.field(window, "lspCoordinator");
            com.editora.command.CommandRegistry registry = FxTestSupport.field(window, "registry");
            FxTestSupport.runOnFx(() -> {
                LspTestHooks.useFakeSessions(manager);
                coordinator.applySupport();
            });
            assertEquals(override, LspTestHooks.configuredCommand(manager, "python"), "precondition");

            FxTestSupport.runOnFx(() -> registry.run("workspace.revokeTrust"));
            FxTestSupport.drainFx();

            assertEquals(
                    com.editora.lsp.LspServerRegistry.defaultCommandFor("python"),
                    LspTestHooks.configuredCommand(manager, "python"),
                    "an untrusted folder no longer chooses what runs");
        } finally {
            fx.dispose();
        }
    }

    private static MainController controllerFor(FxWindowFixture fx, String key) {
        List<?> holders = FxTestSupport.field(fx.windowManager, "windows");
        for (Object h : holders) {
            if (key.equals(FxTestSupport.call(h, "key", new Class<?>[] {}))) {
                return (MainController) FxTestSupport.call(h, "controller", new Class<?>[] {});
            }
        }
        throw new IllegalStateException("no window for " + key);
    }
}
