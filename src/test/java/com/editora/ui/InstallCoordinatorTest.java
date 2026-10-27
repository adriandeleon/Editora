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
                "lua", Settings::getLuaLspCommand,
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
        // lemminx: Editora's own copy needs no setting — blank finds it, so Reset no longer disconnects it (XML A9);
        // the Java lemminx it falls back to where the native one cannot run is kept as a command of its own (A4).
        Settings xml = new Settings();
        xml.setXmlLspCommand("/old/path/lemminx-linux-x86_64");
        InstallCoordinator.applyServerCommand(xml, "xml", "/home/u/.editora/plugins/lsp/xml/lemminx");
        assertEquals("", xml.getXmlLspCommand());
        String java = "java -cp \"/home/u/.editora/plugins/lsp/maven/*\" org.eclipse.lemminx.XMLServerLauncher";
        InstallCoordinator.applyServerCommand(xml, "xml", java);
        assertEquals(java, xml.getXmlLspCommand());
        // JetBrains' Kotlin LSP is a pinned distribution, not an archive recipe: its launcher persists the same way.
        Settings s = new Settings();
        InstallCoordinator.applyServerCommand(s, "kotlin", "/cfg/plugins/lsp/kotlin/bin/intellij-server --stdio");
        assertEquals("/cfg/plugins/lsp/kotlin/bin/intellij-server --stdio", s.getKotlinLspCommand());
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
