package com.editora.install;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Editora's own TypeScript server copy (JS review B101/B104): where it is, when the command uses it, and which
 * TypeScript versions the server may be left on.
 */
class TypeScriptServerInstallTest {

    @Test
    void aBlankOrDefaultCommandRunsEditorasOwnCopyAndAnyOtherCommandStays() {
        Path own = Path.of("/cfg/plugins/lsp/typescript/node_modules/.bin/typescript-language-server");
        String ownCommand = InstallCatalog.quoteCommandPath(own.toString()) + " --stdio";
        assertEquals(ownCommand, TypeScriptServerInstall.commandWith("", own));
        assertEquals(ownCommand, TypeScriptServerInstall.commandWith(null, own));
        assertEquals(ownCommand, TypeScriptServerInstall.commandWith("typescript-language-server  --stdio", own));
        assertEquals("npx tsls --stdio", TypeScriptServerInstall.commandWith("npx tsls --stdio", own));
        // not installed: the default is looked up on PATH as before, a blank one stays blank
        assertEquals(
                "typescript-language-server --stdio",
                TypeScriptServerInstall.commandWith("typescript-language-server --stdio", null));
        assertEquals("", TypeScriptServerInstall.commandWith("", null));
    }

    @Test
    void theOwnCopyIsFoundOnlyWhenNpmPutItThere(@TempDir Path cfg) throws Exception {
        assertNull(TypeScriptServerInstall.ownLauncher(cfg, false));
        assertNull(TypeScriptServerInstall.ownTsserver(cfg));
        assertNull(TypeScriptServerInstall.ownSdk(cfg));
        Path prefix = TypeScriptServerInstall.folder(cfg);
        Path bin = Files.createDirectories(prefix.resolve("node_modules/.bin"));
        Files.writeString(bin.resolve("typescript-language-server"), "");
        Files.writeString(bin.resolve("typescript-language-server.cmd"), "");
        Path lib = Files.createDirectories(prefix.resolve("node_modules/typescript/lib"));
        Files.writeString(lib.resolve("tsserver.js"), "");
        Files.writeString(lib.resolve("typescript.js"), "");
        assertEquals(bin.resolve("typescript-language-server"), TypeScriptServerInstall.ownLauncher(cfg, false));
        assertEquals(bin.resolve("typescript-language-server.cmd"), TypeScriptServerInstall.ownLauncher(cfg, true));
        assertEquals(lib.resolve("tsserver.js"), TypeScriptServerInstall.ownTsserver(cfg));
        assertEquals(lib, TypeScriptServerInstall.ownSdk(cfg));
        assertNull(TypeScriptServerInstall.ownTsserver(null));
        Path pkg = Files.createDirectories(prefix.resolve("node_modules/typescript-language-server"));
        Files.writeString(
                pkg.resolve("package.json"), "{ \"name\": \"typescript-language-server\", \"version\": \"5.3.0\" }");
        assertEquals("5.3.0", TypeScriptServerInstall.installedVersion(prefix));
    }

    @Test
    void onlyATypescriptFiveWithATsserverIsSuitable() {
        assertTrue(TypeScriptServerInstall.suitable("5.9.3", true));
        assertTrue(TypeScriptServerInstall.suitable("4.9.5", true));
        assertFalse(TypeScriptServerInstall.suitable("6.0.3", true), "TypeScript 6 leaves @types out");
        assertFalse(TypeScriptServerInstall.suitable("7.0.2", true));
        assertFalse(TypeScriptServerInstall.suitable("5.9.3", false), "no tsserver");
        assertFalse(TypeScriptServerInstall.suitable("", true), "unknown version");
        assertEquals(5, TypeScriptServerInstall.major("5.9.3"));
        assertEquals(6, TypeScriptServerInstall.major("v6.0.0-beta"));
        assertEquals(0, TypeScriptServerInstall.major("next"));
        assertEquals(0, TypeScriptServerInstall.major(null));
    }

    @Test
    void theInstallIsPinnedAndNeverGlobal() {
        assertEquals(
                java.util.List.of("typescript-language-server@5.3.0", "typescript@5.9.3"),
                TypeScriptServerInstall.packages());
    }
}
