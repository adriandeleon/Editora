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
}
