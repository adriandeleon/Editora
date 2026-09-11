package com.editora.io;

import java.io.IOException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;

/** Real-filesystem operations that a test can override at one specific failure boundary. */
public class DelegatingFileOperations implements AtomicFileWrite.FileOperations {

    @Override
    public boolean isDirectory(Path path) {
        return Files.isDirectory(path);
    }

    @Override
    public boolean exists(Path path, LinkOption... options) {
        return Files.exists(path, options);
    }

    @Override
    public void createDirectories(Path path) throws IOException {
        Files.createDirectories(path);
    }

    @Override
    public Path createTempFile(Path directory, String prefix, String suffix, FileAttribute<?>... attributes)
            throws IOException {
        return Files.createTempFile(directory, prefix, suffix, attributes);
    }

    @Override
    public Path createTempFile(String prefix, String suffix, FileAttribute<?>... attributes) throws IOException {
        return Files.createTempFile(prefix, suffix, attributes);
    }

    @Override
    public void write(Path path, byte[] content) throws IOException {
        Files.write(path, content);
    }

    @Override
    public void writeNew(Path path, byte[] content) throws IOException {
        Files.write(path, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
        Files.move(source, target, options);
    }

    @Override
    public byte[] readAllBytes(Path path) throws IOException {
        return Files.readAllBytes(path);
    }

    @Override
    public boolean deleteIfExists(Path path) throws IOException {
        return Files.deleteIfExists(path);
    }
}
