package com.editora.dap;

import java.nio.file.Path;

import com.editora.process.ExecTarget;

/**
 * How a debug adapter names files, against how Editora reaches them. A local adapter shares this machine's
 * paths; one running on a remote host knows a file by its path <em>there</em>, which is the same text as the
 * SFTP path but must come back as a path on that connection, not as a local file of the same name.
 *
 * <p>Every {@code Source.path} a session sends or reads crosses here: breakpoints and goto targets out,
 * stack frames and breakpoint events in.
 */
public interface DapPaths {

    /** This machine: a path is its own string. */
    DapPaths LOCAL = new DapPaths() {
        @Override
        public String toAdapter(Path file) {
            return file.toString();
        }

        @Override
        public boolean owns(Path file) {
            return file.getFileSystem() == java.nio.file.FileSystems.getDefault();
        }

        @Override
        public Path fromAdapter(String path) {
            try {
                return Path.of(path);
            } catch (java.nio.file.InvalidPathException e) {
                return null;
            }
        }
    };

    /** Whether {@code file} is on the adapter's machine, so it can be named to it at all. */
    boolean owns(Path file);

    /** The name the adapter knows {@code file} by. */
    String toAdapter(Path file);

    /**
     * The file an adapter-reported path names, or {@code null} for one that is not a file path at all
     * ({@code <node_internals>/…}, {@code jdt://…}, {@code <frozen importlib>}).
     */
    Path fromAdapter(String path);

    /**
     * Paths for an adapter running on {@code target}, whose files Editora reaches through {@code fileSystem}
     * (a connection's, or this machine's for a folder whose tools run elsewhere: a container, WSL);
     * {@link #LOCAL} for this machine. It owns the files whose tools run on that same target.
     */
    static DapPaths on(ExecTarget target, java.nio.file.FileSystem fileSystem) {
        if (target == null || target.isLocal()) {
            return LOCAL;
        }
        String id = target.id();
        return new DapPaths() {
            @Override
            public boolean owns(Path file) {
                return file.getFileSystem() == fileSystem
                        && id.equals(
                                com.editora.process.ExecTargets.forPath(file).id());
            }

            @Override
            public String toAdapter(Path file) {
                return target.toTargetPath(file);
            }

            @Override
            public Path fromAdapter(String path) {
                // The host is POSIX: anything that is not an absolute path is an adapter's pseudo-source.
                if (path == null || !path.startsWith("/") || path.indexOf('\0') >= 0) {
                    return null;
                }
                try {
                    return target.toPath(path);
                } catch (RuntimeException e) {
                    return null;
                }
            }
        };
    }
}
