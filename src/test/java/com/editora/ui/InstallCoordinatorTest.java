package com.editora.ui;

import java.util.Map;
import java.util.function.Function;

import com.editora.config.Settings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards that every binary-archive LSP server has a persist case in
 * {@link InstallCoordinator#applyServerCommand}. A missing case (the tinymist/{@code typst} bug) leaves the
 * command PATH-only, so detection never finds the just-installed binary → the install banner never clears.
 */
class InstallCoordinatorTest {

    @Test
    void everyBinaryArchiveServerPersistsItsInstalledCommand() {
        // The archive-installable servers (see InstallCatalog.serverInstall's ARCHIVE case) → their getter.
        Map<String, Function<Settings, String>> readers = Map.of(
                "clangd", Settings::getClangdLspCommand,
                "kotlin", Settings::getKotlinLspCommand,
                "lua", Settings::getLuaLspCommand,
                "xml", Settings::getXmlLspCommand,
                "terraform", Settings::getTerraformLspCommand,
                "typst", Settings::getTypstLspCommand);
        readers.forEach((id, reader) -> {
            Settings s = new Settings();
            String cmd = "/opt/tools/" + id + "-bin arg";
            InstallCoordinator.applyServerCommand(s, id, cmd);
            assertEquals(cmd, reader.apply(s), id + ": installed command not persisted (missing case?)");
            // Each is a real binary archive (sanity: the recipe exists).
            assertEquals(
                    true, com.editora.install.InstallCatalog.archiveSpec(id).isPresent(), id + ": no archiveSpec");
        });
    }

    /**
     * The installer's stored command is a command line, so the binary path must be quoted when the config dir
     * has a space in it ({@code C:\Users\Jane Doe\.editora}). Stored bare, the registry split it at the space
     * and the server that had just been installed was reported missing.
     */
    @Test
    void anInstalledBinaryUnderAPathWithASpaceIsStillOneArgumentToTheServerRegistry() {
        java.nio.file.Path binary =
                java.nio.file.Path.of("/Users/Jane Doe/.editora/plugins/lsp/terraform/terraform-ls");
        Settings s = new Settings();

        InstallCoordinator.applyServerCommand(
                s, "terraform", com.editora.install.InstallCatalog.binaryCommand(binary, " serve"));

        assertEquals(
                java.util.List.of(binary.toString(), "serve"),
                com.editora.lsp.LspServerRegistry.commandFor(
                        "terraform", Map.of("terraform", s.getTerraformLspCommand())));
    }
}
