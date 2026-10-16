package com.editora.process;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Finds the {@link ExecTarget} for a path. A path on another filesystem (an SFTP folder) is offered to the
 * registered {@link Resolver}s, the same per-window registry shape as {@code Vfs.RemoteProvider}. A path on
 * this machine's own filesystem runs here, unless a <em>mapped</em> resolver claims it: a folder whose files
 * are this machine's but whose tools run elsewhere (bind-mounted into a container, inside a WSL distribution).
 * This package knows nothing about SSH, containers or WSL: each engine registers itself.
 *
 * <p>So where a command runs is the target's question ({@link ExecTarget#isLocal()}), not the filesystem's:
 * a mapped folder is read and written like any local one, and its tools are not this machine's.
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

    /** Resolvers for folders of this machine's own filesystem whose tools run elsewhere; asked in order. */
    private static final Set<Resolver> MAPPED = new CopyOnWriteArraySet<>();

    /** Whether commands may run anywhere but here: the user's {@code remoteExecution} setting. Off by default. */
    private static volatile BooleanSupplier remoteEnabled = () -> false;

    /** Whether the user has agreed to commands on one particular host, by {@link ExecTarget#id()}. */
    private static volatile Predicate<String> hostAllowed = id -> true;

    /** Per-host tool commands: (target id, tool id) → command, or null for the host's PATH. */
    private static volatile BiFunction<String, String, String> remoteTools = (id, tool) -> null;

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
     * Registers an engine that runs the tools of some folders of this machine's own filesystem elsewhere. Its
     * {@link Resolver#forPath} is asked about every local path a command runs for, so it must answer from
     * memory (a prefix match), without I/O.
     */
    public static void registerMapped(Resolver resolver) {
        if (resolver != null) {
            MAPPED.add(resolver);
        }
    }

    public static void unregisterMapped(Resolver resolver) {
        MAPPED.remove(resolver);
    }

    /** Whether a tool for {@code path} runs on this machine: its target's {@link ExecTarget#isLocal()}. No I/O. */
    public static boolean runsHere(Path path) {
        return forPath(path).isLocal();
    }

    /**
     * Supplies the user's choice to let commands run on remote hosts. Asked on every lookup, so a change of
     * the setting takes effect at once. While it answers false, a remote path has no target that can start
     * anything, exactly as before remote execution existed.
     */
    public static void setRemoteEnabled(BooleanSupplier enabled) {
        remoteEnabled = enabled == null ? () -> false : enabled;
    }

    /**
     * Supplies the user's per-host agreement. The setting says commands may run on remote hosts at all; this
     * says they may on <em>this</em> one. A host it refuses has no target that can start anything.
     */
    public static void setRemoteHostAllowed(Predicate<String> allowed) {
        hostAllowed = allowed == null ? id -> true : allowed;
    }

    /** Supplies the command the user configured for a tool on a host, when its PATH does not have the right one. */
    public static void setRemoteTools(BiFunction<String, String, String> tools) {
        remoteTools = tools == null ? (id, tool) -> null : tools;
    }

    /**
     * The command configured for {@code toolId} on the host that owns {@code path}, or {@code null}: for a
     * local path (local tools have their own settings), and when nothing is configured for that host.
     */
    public static String remoteTool(Path path, String toolId) {
        ExecTarget target = forPath(path);
        if (target.isLocal()) {
            return null;
        }
        String command = remoteTools.apply(target.id(), toolId);
        return command == null || command.isBlank() ? null : command;
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
        if (path == null) {
            return LOCAL;
        }
        if (path.getFileSystem() == FileSystems.getDefault()) {
            // A mapped folder's files are this machine's: while its own target may not be used (the setting is
            // off, or the user has not allowed that container or distribution), its tools run here, as before.
            for (Resolver resolver : MAPPED) {
                ExecTarget target = resolver.forPath(path);
                if (target != null) {
                    return remoteEnabled.getAsBoolean() && hostAllowed.test(target.id()) ? target : LOCAL;
                }
            }
            return LOCAL;
        }
        for (Resolver resolver : RESOLVERS) {
            ExecTarget target = resolver.forPath(path);
            if (target != null) {
                return gated(path, target);
            }
        }
        return remoteEnabled.getAsBoolean()
                ? new Unavailable(path, "no connection to run commands on")
                : new Unavailable(path, "running commands on remote hosts is turned off");
    }

    /** {@code target}, while the user lets commands run elsewhere at all and on it in particular. */
    private static ExecTarget gated(Path path, ExecTarget target) {
        if (!remoteEnabled.getAsBoolean()) {
            return new Unavailable(path, "running commands on remote hosts is turned off");
        }
        return hostAllowed.test(target.id())
                ? target
                : new Unavailable(path, "running commands on " + target.id() + " has not been allowed");
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
        public PortForward forwardLocal(String host, int port) throws IOException {
            throw new IOException(reason);
        }

        @Override
        public String toTargetPath(Path path) {
            return path.toAbsolutePath().normalize().toString();
        }

        @Override
        public long latestLocalTime(long targetMillis) {
            return -1;
        }

        @Override
        public Path toPath(String targetPath) {
            return origin.getFileSystem().getPath(targetPath);
        }
    }
}
