package com.editora.ui;

import java.io.IOException;
import java.nio.file.CopyOption;
import java.nio.file.Path;
import java.util.List;

/** Files-backed LSP transaction operations that tests can override at one meaningful failure stage. */
class DelegatingWorkspaceFileOperations implements LspCoordinator.WorkspaceFileOperations {

    private final LspCoordinator.WorkspaceFileOperations delegate = LspCoordinator.WorkspaceFileOperations.SYSTEM;

    @Override
    public boolean exists(Path path) {
        return delegate.exists(path);
    }

    @Override
    public boolean isDirectory(Path path) {
        return delegate.isDirectory(path);
    }

    @Override
    public boolean isRegularFile(Path path) {
        return delegate.isRegularFile(path);
    }

    @Override
    public long size(Path path) throws IOException {
        return delegate.size(path);
    }

    @Override
    public LspCoordinator.WorkspaceFileIdentity identity(Path path) throws IOException {
        return delegate.identity(path);
    }

    @Override
    public void createDirectories(Path path) throws IOException {
        delegate.createDirectories(path);
    }

    @Override
    public void createFile(Path path) throws IOException {
        delegate.createFile(path);
    }

    @Override
    public void move(Path from, Path to, CopyOption... options) throws IOException {
        delegate.move(from, to, options);
    }

    @Override
    public Path createTempFile(Path directory, String prefix, String suffix) throws IOException {
        return delegate.createTempFile(directory, prefix, suffix);
    }

    @Override
    public boolean deleteIfExists(Path path) throws IOException {
        return delegate.deleteIfExists(path);
    }

    @Override
    public List<Path> list(Path path) throws IOException {
        return delegate.list(path);
    }

    @Override
    public List<Path> walk(Path path) throws IOException {
        return delegate.walk(path);
    }
}
