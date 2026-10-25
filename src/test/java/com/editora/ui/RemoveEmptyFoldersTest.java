package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Undoing Move ▸ New package takes back the folders it made, never one with something in it (third review BT4). */
class RemoveEmptyFoldersTest {

    @Test
    void theNewPackagesEmptyFoldersGoAndTheRestStays(@TempDir Path root) throws Exception {
        Path made = Files.createDirectories(root.resolve("com/example/text"));
        Files.writeString(root.resolve("com/Kept.java"), "class Kept {}");
        LspCoordinator.removeEmptyFolders(made, root);
        assertFalse(Files.exists(made));
        assertFalse(Files.exists(root.resolve("com/example")), "its empty parent too");
        assertTrue(Files.exists(root.resolve("com/Kept.java")), "a folder with a file stays");
        assertTrue(Files.exists(root), "never the source root itself");
    }
}
