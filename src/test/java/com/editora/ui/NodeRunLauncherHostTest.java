package com.editora.ui;

import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * JS review C109: on a host (here a non-default file system standing in for SFTP) a Node program runs from its
 * package root, as it does locally — not from the file's own folder.
 */
class NodeRunLauncherHostTest {

    @Test
    void theHostsPackageRootIsFoundThroughTheFilesOwnFileSystem(@TempDir Path tmp) throws Exception {
        URI uri = URI.create("jar:" + tmp.resolve("host.zip").toUri());
        try (FileSystem host = FileSystems.newFileSystem(uri, Map.of("create", "true"))) {
            Path pkg = host.getPath("/srv/app");
            Files.createDirectories(pkg.resolve("src/lib"));
            Files.writeString(pkg.resolve("package.json"), "{}");
            Path file = Files.writeString(pkg.resolve("src/lib/main.js"), "");
            assertEquals(pkg, NodeRunLauncher.hostPackageRoot(file, null));
            assertEquals(pkg, NodeRunLauncher.hostPackageRoot(file, host.getPath("/srv")));
            Path loose = Files.writeString(host.getPath("/tmp-script.js"), "");
            assertEquals(loose.getParent(), NodeRunLauncher.hostPackageRoot(loose, null), "no package: its folder");
        }
    }
}
