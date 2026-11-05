package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Java debug review 3, A207: the sources written just before a launch build are told to jdtls first. */
class FreshJavaSourcesTest {

    @Test
    void onlyJavaSourcesWrittenSinceTheMomentGivenOutsideOutputFoldersAreFound(@TempDir Path root) throws Exception {
        long now = System.currentTimeMillis();
        Path fresh = write(root.resolve("src/demo/Helper.java"), now);
        write(root.resolve("src/demo/Old.java"), now - 60_000);
        write(root.resolve("src/demo/notes.txt"), now);
        write(root.resolve("target/generated/Gen.java"), now);
        write(root.resolve(".git/x/Hidden.java"), now);
        write(root.resolve("bin/demo/Out.java"), now);

        List<LspManager.WatchedFile> found = FreshJavaSources.since(root, now - FreshJavaSources.WINDOW_MS, 1000);

        assertEquals(List.of(new LspManager.WatchedFile(fresh, LspManager.WatchedKind.CHANGED)), found);
    }

    @Test
    void aWalkIsBoundedAndAMissingFolderHasNothing(@TempDir Path root) throws Exception {
        long now = System.currentTimeMillis();
        for (int i = 0; i < 20; i++) {
            write(root.resolve("p" + i + "/A.java"), now);
        }
        assertTrue(FreshJavaSources.since(root, 0, 5).size() < 5);
        assertTrue(FreshJavaSources.since(root.resolve("nope"), 0, 100).isEmpty());
        assertTrue(FreshJavaSources.skipped(".venv"));
        assertTrue(FreshJavaSources.skipped("node_modules"));
        assertFalse(FreshJavaSources.skipped("src"));
    }

    private static Path write(Path file, long modified) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class X {}\n");
        Files.setLastModifiedTime(file, FileTime.fromMillis(modified));
        return file;
    }
}
