package com.editora.process;

import java.io.IOException;
import java.nio.file.Path;

/**
 * A machine that can run a command: this one, or the host behind a remote folder. A feature that shells out
 * asks {@link ExecTargets#forPath} for the target of the file or folder it is working on, so the same code
 * runs {@code git} here for a local checkout and on the server for a remote one.
 *
 * <p>Paths cross the boundary as strings. A {@link Path} names a file <em>as Editora reaches it</em>; the
 * command needs the name the file has <em>on the target</em>. {@link #toTargetPath} and {@link #toPath} are
 * the only conversions, so an argument handed to a tool and a path read back from its output always agree.
 */
public interface ExecTarget {

    /** {@code "local"}, or the remote connection ({@code user@host:port}). */
    String id();

    boolean isLocal();

    /** Whether a command can be started right now: always for the local target, while connected for a remote one. */
    boolean isAvailable();

    /**
     * Starts {@code spec}. A remote target returns a {@link RemoteProcess}, which has streams, an exit code
     * and {@code onExit} like any other, but no {@link ProcessHandle}.
     */
    Process start(ExecSpec spec) throws IOException;

    /** The name of {@code path} on this target, for a working directory or a file argument. */
    String toTargetPath(Path path);

    /** The inverse of {@link #toTargetPath}: a path on this target, as Editora reaches it. */
    Path toPath(String targetPath);

    /**
     * A path a tool printed: taken as is when absolute, else resolved against {@code base} (the directory the
     * tool ran in).
     */
    default Path resolve(Path base, String toolOutputPath) {
        Path path = toPath(toolOutputPath);
        return base == null || path.isAbsolute() ? path : base.resolve(toolOutputPath);
    }
}
