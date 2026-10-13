package com.editora.process;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Map;

/** A path on a filesystem that is not the default one (a zip), standing in for a remote folder. */
final class NonDefaultPaths {

    private NonDefaultPaths() {}

    static FileSystem open(Path dir) throws IOException {
        return FileSystems.newFileSystem(
                URI.create("jar:" + dir.resolve("remote.zip").toUri()), Map.of("create", "true"));
    }
}
