package com.editora.config;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Map;

import com.editora.vfs.Vfs;

/**
 * A stand-in for a connected SFTP host: a zip filesystem registered with {@link Vfs} under an
 * {@code sftp://} authority, so its paths store and resolve exactly as a real connection's do. Closing it is
 * the host being disconnected.
 */
final class FakeRemoteHost implements Vfs.RemoteProvider, AutoCloseable {

    private final String authority;
    private final FileSystem fs;

    FakeRemoteHost(Path dir, String authority) throws IOException {
        this.authority = authority;
        this.fs = FileSystems.newFileSystem(
                URI.create("jar:"
                        + dir.resolve(authority.replaceAll("\\W", "_") + ".zip").toUri()),
                Map.of("create", "true"));
        Vfs.registerRemoteProvider(this);
    }

    Path path(String absolute) {
        return fs.getPath(absolute);
    }

    @Override
    public Path resolve(String uri) {
        String prefix = "sftp://" + authority;
        return uri.startsWith(prefix + "/") ? fs.getPath(uri.substring(prefix.length())) : null;
    }

    @Override
    public String storable(Path path) {
        return path.getFileSystem() == fs
                ? "sftp://" + authority + path.toAbsolutePath().normalize()
                : null;
    }

    @Override
    public void close() throws IOException {
        Vfs.unregisterRemoteProvider(this);
        fs.close();
    }
}
