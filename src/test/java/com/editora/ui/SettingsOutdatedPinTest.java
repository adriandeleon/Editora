package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.install.PinnedVersions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Settings ▸ LSP calls an outdated copy of Editora's own server what it is, not "Installed" (re-review D8). */
class SettingsOutdatedPinTest {

    private static Path jdtls(Path home, String version) throws Exception {
        Files.createFile(Files.createDirectories(home.resolve("plugins"))
                .resolve("org.eclipse.jdt.ls.core_" + version + ".jar"));
        Path launcher =
                Files.createFile(Files.createDirectories(home.resolve("bin")).resolve("jdtls"));
        launcher.toFile().setExecutable(true);
        return launcher;
    }

    @Test
    void onlyEditorasOwnOlderCopyIsCalledOutdated(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("config");
        var pin = PinnedVersions.forServer("java").orElseThrow();
        Path own = jdtls(config.resolve(pin.destSubpath()), "1.61.0.202607301809");
        assertEquals(pin.label(), SettingsWindow.outdatedPin("java", List.of(own.toString()), config));

        Path elsewhere = jdtls(dir.resolve("opt/jdtls"), "1.61.0.202607301809");
        assertNull(
                SettingsWindow.outdatedPin("java", List.of(elsewhere.toString()), config), "not Install…'s to replace");
        assertNull(SettingsWindow.outdatedPin("java", List.of(own.toString()), null));
        assertNull(SettingsWindow.outdatedPin("json", List.of("vscode-json-language-server"), config), "no pin");
    }

    /** An install from before Editora recorded versions is said in Settings too, as Doctor says it (review 2, A7). */
    @Test
    void anInstallFromBeforeThePinIsSaidToo(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("config");
        var pin = PinnedVersions.forServer("xml").orElseThrow();
        Path lemminx =
                Files.createDirectories(config.resolve(pin.destSubpath())).resolve("lemminx-linux-x86_64");
        Files.writeString(lemminx, "");
        assertEquals(pin.label(), SettingsWindow.unknownPin("xml", List.of(lemminx.toString()), config));
        assertNull(SettingsWindow.outdatedPin("xml", List.of(lemminx.toString()), config), "not known to be older");
        assertNull(SettingsWindow.unknownPin("xml", List.of("/usr/bin/lemminx"), config), "not Editora's copy");
    }
}
