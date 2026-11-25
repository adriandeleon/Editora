package com.editora.install;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.lsp.LspServerRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PowerShell's language server command: PSES found where the debugger finds it, started on pwsh. */
class PowerShellServerInstallTest {

    @Test
    void aBlankCommandStartsPsesFromEditorasInstallOnPwsh(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("config");
        Path start = config.resolve("plugins/dap/powershell/PowerShellEditorServices/Start-EditorServices.ps1");
        Files.createDirectories(start.getParent());
        Files.writeString(start, "");
        String command =
                PowerShellServerInstall.effectiveCommand("", config, dir.resolve("home"), dir, 3, n -> true, false);
        List<String> argv = LspServerRegistry.tokenize(command);
        assertEquals("pwsh", argv.getFirst());
        assertEquals(start.toString(), argv.get(argv.indexOf("-File") + 1));
        assertTrue(argv.containsAll(List.of("-LanguageServiceOnly", "-Stdio")), argv.toString());
        assertEquals(
                command,
                PowerShellServerInstall.effectiveCommand("pwsh", config, dir.resolve("home"), dir, 3, n -> true, false),
                "the bare default is found the same way");
    }

    @Test
    void aCommandOfTheUsersOwnIsKeptAndAMissingServerIsNamed(@TempDir Path dir) {
        assertEquals(
                "my-pses --stdio",
                PowerShellServerInstall.effectiveCommand("my-pses --stdio", dir, dir, dir, 1, n -> true, false));
        assertEquals(
                "Start-EditorServices.ps1",
                PowerShellServerInstall.effectiveCommand("", dir, dir.resolve("nohome"), dir, 1, n -> true, false));
    }

    @Test
    void vsCodesPowerShellExtensionIsFound(@TempDir Path dir) throws Exception {
        Path home = dir.resolve("home");
        Path start = home.resolve(
                ".vscode/extensions/ms-vscode.powershell-2025.4.0/modules/PowerShellEditorServices/Start-EditorServices.ps1");
        Files.createDirectories(start.getParent());
        Files.writeString(start, "");
        String command =
                PowerShellServerInstall.effectiveCommand("", dir.resolve("config"), home, dir, 1, n -> false, false);
        assertTrue(LspServerRegistry.tokenize(command).contains(start.toString()), command);
    }
}
