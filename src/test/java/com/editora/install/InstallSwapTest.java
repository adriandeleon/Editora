package com.editora.install;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An install that cannot be moved aside is refused and left whole, never deleted in place (third review CT6). */
class InstallSwapTest {

    @Test
    void anInstallThatCannotBeMovedAsideIsLeftWhole(@TempDir Path dir) throws Exception {
        Path dest = Files.createDirectories(dir.resolve("java"));
        Files.writeString(dest.resolve("core.jar"), "old");
        Path aside = Files.createDirectories(dir.resolve("java.old")); // in the way, like a file held open
        Files.writeString(aside.resolve("x"), "x");

        IOException refused = assertThrows(IOException.class, () -> InstallService.moveAside(dest, aside));

        assertTrue(refused.getMessage().contains("in use"), refused.getMessage());
        assertEquals("old", Files.readString(dest.resolve("core.jar")), "the old install is untouched");
    }

    @Test
    void aFreeInstallIsMovedAside(@TempDir Path dir) throws Exception {
        Path dest = Files.createDirectories(dir.resolve("java"));
        Files.writeString(dest.resolve("core.jar"), "old");
        Path aside = dir.resolve("java.old");
        InstallService.moveAside(dest, aside);
        assertEquals("old", Files.readString(aside.resolve("core.jar")));
    }
}
