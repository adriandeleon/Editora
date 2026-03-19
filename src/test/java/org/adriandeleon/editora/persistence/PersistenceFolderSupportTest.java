package org.adriandeleon.editora.persistence;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PersistenceFolderSupportTest {

    @Test
    void choosesMacOpenCommand() {
        List<String> command = PersistenceFolderSupport.fallbackOpenCommand("Mac OS X", Path.of("/tmp/editora"));

        assertEquals(List.of("open", Path.of("/tmp/editora").toAbsolutePath().normalize().toString()), command);
    }

    @Test
    void choosesWindowsExplorerCommand() {
        List<String> command = PersistenceFolderSupport.fallbackOpenCommand("Windows 11", Path.of("/tmp/editora"));

        assertEquals(List.of("explorer", Path.of("/tmp/editora").toAbsolutePath().normalize().toString()), command);
    }

    @Test
    void choosesLinuxXdgOpenCommand() {
        List<String> command = PersistenceFolderSupport.fallbackOpenCommand("Linux", Path.of("/tmp/editora"));

        assertEquals(List.of("xdg-open", Path.of("/tmp/editora").toAbsolutePath().normalize().toString()), command);
    }
}

