package com.editora.install;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a blank XML command runs (XML review A9): Editora's own lemminx when it has one — under today's name, or the
 * per-platform name an install from before the pin kept — so resetting the setting no longer disconnects it; a
 * user's own command is left alone.
 */
class XmlServerInstallTest {

    @TempDir
    Path config;

    private Path program(String name) throws IOException {
        Path dir = Files.createDirectories(config.resolve("plugins/lsp/xml"));
        return Files.writeString(dir.resolve(name), "");
    }

    private static String pinnedName() {
        return InstallCatalog.toolFileName(InstallCatalog.LEMMINX_STEP, InstallCatalog.currentPlatform());
    }

    private static String legacyName() {
        return InstallCatalog.pinnedTool(InstallCatalog.LEMMINX_STEP)
                .orElseThrow()
                .assetFor(InstallCatalog.currentPlatform())
                .member();
    }

    @Test
    void withoutACopyABlankCommandStaysBlank() {
        assertNull(XmlServerInstall.ownCopy(config));
        assertNull(XmlServerInstall.ownCopy(null));
        assertEquals("", XmlServerInstall.effectiveCommand("", config));
        assertEquals("lemminx", XmlServerInstall.effectiveCommand("lemminx", config), "then PATH decides");
    }

    @Test
    void aBlankOrDefaultCommandRunsEditorasCopy() throws IOException {
        Path own = program(pinnedName());

        assertEquals(own, XmlServerInstall.ownCopy(config));
        String expected = InstallCatalog.quoteCommandPath(own.toString());
        assertEquals(expected, XmlServerInstall.effectiveCommand("", config));
        assertEquals(expected, XmlServerInstall.effectiveCommand(null, config));
        assertEquals(expected, XmlServerInstall.effectiveCommand("  lemminx ", config));
    }

    @Test
    void aCopyFromBeforeThePinIsFoundByItsPlatformName() throws IOException {
        Path legacy = program(legacyName());

        assertEquals(legacy, XmlServerInstall.ownCopy(config));
        Path pinned = program(pinnedName());
        assertEquals(pinned, XmlServerInstall.ownCopy(config), "the pinned install wins over a leftover");
    }

    @Test
    void aStalePathIntoEditorasFolderRunsTheCopyThatIsThereNow() throws IOException {
        Path own = program(pinnedName());
        // What an install from before the pin wrote into the setting; its update renamed the program.
        String stale = config.resolve("plugins/lsp/xml").resolve(legacyName()).toString();

        assertEquals(InstallCatalog.quoteCommandPath(own.toString()), XmlServerInstall.effectiveCommand(stale, config));
        assertTrue(XmlServerInstall.editorasOwn(stale, config), "not the user's own choice: Install may replace it");
    }

    @Test
    void theUsersOwnCommandIsLeftAlone() throws IOException {
        program(pinnedName());
        Path elsewhere = Files.writeString(config.resolve("my-lemminx"), "");
        String mine = elsewhere + " -Dfoo=bar";

        assertEquals(mine, XmlServerInstall.effectiveCommand(mine, config));
        assertEquals("/opt/missing/lemminx", XmlServerInstall.effectiveCommand("/opt/missing/lemminx", config));
        assertFalse(XmlServerInstall.editorasOwn("/opt/missing/lemminx", config));
        assertTrue(XmlServerInstall.editorasOwn("", config));
        assertTrue(XmlServerInstall.editorasOwn("lemminx", config));
    }

    @Test
    void anInstalledCommandIsTheNativeCopyOnlyInsideTheXmlFolder() {
        assertTrue(XmlServerInstall.isNativeInstall(
                config.resolve("plugins/lsp/xml/lemminx").toString()));
        assertTrue(XmlServerInstall.isNativeInstall(InstallCatalog.quoteCommandPath(
                config.resolve("with space/plugins/lsp/xml/lemminx").toString())));
        assertFalse(XmlServerInstall.isNativeInstall("java -cp \"" + config.resolve("plugins/lsp/maven") + "/*\" X"));
        assertFalse(XmlServerInstall.isNativeInstall("lemminx"));
        assertFalse(XmlServerInstall.isNativeInstall(null));
    }
}
