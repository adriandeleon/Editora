package com.editora.process;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.BooleanSupplier;

/**
 * Finds the {@link ExecTarget} for a path. A path on the default filesystem (or no path at all) runs here;
 * any other is offered to the registered {@link Resolver}s, the same per-window registry shape as
 * {@code Vfs.RemoteProvider}. This package knows nothing about SSH: the remote engine registers itself.
 */
public final class ExecTargets {

    /** A remote engine that can run commands for the paths it owns. */
    @FunctionalInterface
    public interface Resolver {
        /** The target for {@code path}, or {@code null} when it is not on one of this engine's filesystems. */
        ExecTarget forPath(Path path);
    }

    private static final ExecTarget LOCAL = new LocalExecTarget();

    private static final Set<Resolver> RESOLVERS = new CopyOnWriteArraySet<>();

    /** Whether commands may run anywhere but here: the user's {@code remoteExecution} setting. Off by default. */
    private static volatile BooleanSupplier remoteEnabled = () -> false;

    private ExecTargets() {}

    public static void register(Resolver resolver) {
        if (resolver != null) {
            RESOLVERS.add(resolver);
        }
    }

    public static void unregister(Resolver resolver) {
        RESOLVERS.remove(resolver);
    }

    /**
     * Supplies the user's choice to let commands run on remote hosts. Asked on every lookup, so a change of
     * the setting takes effect at once. While it answers false, a remote path has no target that can start
     * anything, exactly as before remote execution existed.
     */
    public static void setRemoteEnabled(BooleanSupplier enabled) {
        remoteEnabled = enabled == null ? () -> false : enabled;
    }

    public static ExecTarget local() {
        return LOCAL;
    }

    /**
     * The target that runs commands for {@code path}. {@code null} is local, so a probe with no working
     * directory never leaves this machine. A remote path nobody owns gets a target that cannot start
     * anything, rather than silently running the command here against the wrong files.
     */
    public static ExecTarget forPath(Path path) {
        if (path == null || path.getFileSystem() == FileSystems.getDefault()) {
            return LOCAL;
        }
        if (!remoteEnabled.getAsBoolean()) {
            return new Unavailable(path, "running commands on remote hosts is turned off");
        }
        for (Resolver resolver : RESOLVERS) {
            ExecTarget target = resolver.forPath(path);
            if (target != null) {
                return target;
            }
        }
        return new Unavailable(path, "no connection to run commands on");
    }

    /** A path on a filesystem no engine runs commands for (never connected, or its window has closed). */
    private record Unavailable(Path origin, String reason) implements ExecTarget {
        @Override
        public String id() {
            return "unavailable";
        }

        @Override
        public boolean isLocal() {
            return false;
        }

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public Process start(ExecSpec spec) throws IOException {
            throw new IOException(reason);
        }

        @Override
        public String toTargetPath(Path path) {
            return path.toAbsolutePath().normalize().toString();
        }

        @Override
        public Path toPath(String targetPath) {
            return origin.getFileSystem().getPath(targetPath);
        }
    }
}
