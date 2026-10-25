package com.editora.process;

import java.io.IOException;
import java.nio.file.Path;

import org.jspecify.annotations.Nullable;

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
    String toTargetPath(@Nullable Path path);

    /** The inverse of {@link #toTargetPath}: a path on this target, as Editora reaches it. */
    Path toPath(String targetPath);

    /**
     * A loopback port on this machine connected to {@code port} on the target (as the target itself would
     * reach it on {@code host}, normally {@code 127.0.0.1}). On the local target that is the port itself.
     */
    PortForward forwardLocal(String host, int port) throws IOException;

    /**
     * Starts the account's login shell on a pseudo-terminal, for the integrated terminal. Only targets that can
     * give one implement it; the rest say so. Never used for tool commands, which take {@link #start}.
     */
    default TerminalChannel startTerminal(TerminalSpec spec) throws IOException {
        throw new IOException(id() + " cannot run a terminal yet");
    }

    /**
     * Where Editora may keep working files on this target ({@code ~/.cache/editora} on a remote host: a
     * language server's index, a debugger's jar), as Editora reaches it; {@code null} when there is none to
     * offer (the local target keeps its own under the config folder). May ask the target: call it off the FX
     * thread. The folder is not created.
     */
    default @Nullable Path cacheDir() throws IOException {
        return null;
    }

    /**
     * Whether this machine's file watcher sees changes to this target's folders as they happen. False for a
     * folder this machine reaches through a share that does not report them (a WSL distribution's
     * {@code \\wsl.localhost}): Editora then watches it where its tools run. A remote (SFTP) folder is always
     * watched there; a container's bind mount is this machine's own folder.
     */
    default boolean localWatchSeesChanges() {
        return true;
    }

    /**
     * Editora's working folder on this target ({@link #cacheDir} through its file system, by default), or
     * {@code null} when there is none. May ask the target: call it off the FX thread.
     */
    default @Nullable TargetCache cache() throws IOException {
        Path dir = cacheDir();
        return dir == null ? null : new PathTargetCache(this, dir);
    }

    /**
     * The account's home folder on this target, as Editora reaches it, for a typed {@code ~}; {@code null}
     * when the target does not say (the local target: use {@code user.home}). May ask the target: call it off
     * the FX thread.
     */
    default @Nullable Path homeDir() throws IOException {
        return null;
    }

    /**
     * The latest instant on this machine's clock that {@code targetMillis}, a time read from the target's
     * clock (a file's modification time), can stand for; {@code -1} when the target's clock is not known. A
     * caller asking "was this file written before that moment?" can trust a yes. The local target shares
     * this machine's clock.
     */
    default long latestLocalTime(long targetMillis) {
        return targetMillis;
    }

    /**
     * A path a tool printed: taken as is when absolute, else resolved against {@code base} (the directory the
     * tool ran in).
     */
    default Path resolve(Path base, String toolOutputPath) {
        Path path = toPath(toolOutputPath);
        return base == null || path.isAbsolute() ? path : base.resolve(toolOutputPath);
    }
}
