package org.adriandeleon.editora.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PersistenceFolderSupportTest {

    @TempDir
    Path tempDir;

    @Test
    void choosesMacOpenCommand() {
        Path folder = tempDir.resolve("editora").toAbsolutePath().normalize();
        List<String> command = PersistenceFolderSupport.fallbackOpenCommand("Mac OS X", folder);

        assertEquals(List.of("open", folder.toString()), command);
    }

    @Test
    void choosesWindowsExplorerCommand() {
        Path folder = tempDir.resolve("editora").toAbsolutePath().normalize();
        List<String> command = PersistenceFolderSupport.fallbackOpenCommand("Windows 11", folder);

        assertEquals(List.of("explorer", folder.toString()), command);
    }

    @Test
    void choosesLinuxXdgOpenCommand() {
        Path folder = tempDir.resolve("editora").toAbsolutePath().normalize();
        List<String> command = PersistenceFolderSupport.fallbackOpenCommand("Linux", folder);

        assertEquals(List.of("xdg-open", folder.toString()), command);
    }
}

