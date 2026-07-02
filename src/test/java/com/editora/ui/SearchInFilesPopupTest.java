package com.editora.ui;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SearchInFilesPopupTest {

    @Test
    void relativeWhenUnderRoot() {
        Path root = Path.of("/proj");
        assertEquals("src/Main.java", SearchInFilesPopup.displayPath(root, Path.of("/proj/src/Main.java")));
    }

    @Test
    void fileNameWhenEqualToRoot() {
        Path root = Path.of("/proj");
        assertEquals("proj", SearchInFilesPopup.displayPath(root, Path.of("/proj")));
    }

    @Test
    void absoluteWhenOutsideRoot() {
        Path root = Path.of("/proj");
        assertEquals(
                Path.of("/other/x.txt").toAbsolutePath().normalize().toString(),
                SearchInFilesPopup.displayPath(root, Path.of("/other/x.txt")));
    }

    @Test
    void absoluteWhenNoRoot() {
        assertEquals(
                Path.of("/a/b/c.md").toAbsolutePath().normalize().toString(),
                SearchInFilesPopup.displayPath(null, Path.of("/a/b/c.md")));
    }
}
