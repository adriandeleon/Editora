package com.editora.deploy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocalFilesTest {

    @TempDir
    Path root;

    @Test
    void aWalkLeavesOutExcludedFoldersAndNamesFilesFromTheRoot() throws Exception {
        Files.createDirectories(root.resolve("src/sub"));
        Files.writeString(root.resolve("src/sub/a.txt"), "a");
        Files.createDirectories(root.resolve("target"));
        Files.writeString(root.resolve("target/x.jar"), "x");
        List<LocalFiles.LocalFile> files = LocalFiles.list(root, DeployExcludes.of(""), true);
        assertEquals(
                List.of("src/sub/a.txt"),
                files.stream().map(LocalFiles.LocalFile::relative).toList());
        assertEquals(root.resolve("src/sub/a.txt"), files.get(0).path());
    }

    @Test
    @DisabledOnOs(OS.MAC) // APFS/HFS+ do not keep a decomposed name as written
    void aDecomposedNameIsSentComposedButReadFromItsOwnFile() throws Exception {
        String decomposed = Normalizer.normalize("Ñandú.txt", Normalizer.Form.NFD);
        Files.writeString(root.resolve(decomposed), "n");
        List<LocalFiles.LocalFile> files = LocalFiles.list(root, DeployExcludes.of(""), true);
        assertEquals("Ñandú.txt", files.get(0).relative(), "both sides compare the composed name");
        assertEquals("n", Files.readString(files.get(0).path()));
    }
}
