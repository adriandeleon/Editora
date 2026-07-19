package com.editora.vfs;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Small helpers for the virtual-filesystem abstraction. Editora keeps using {@link Path} everywhere, but a
 * path may now belong to a <em>remote</em> filesystem (an SFTP provider) instead of the local default one.
 * {@link #isLocal(Path)} is the single predicate every local-process feature (LSP, DAP, git, run, the HTTP
 * client) gates on; the storable-string helpers let recent-files/projects round-trip a remote path as a
 * URI. Pure (no toolkit), so it is unit-tested.
 */
public final class Vfs {

    /**
     * A remote-filesystem engine ({@code RemoteFileSystems}) that can resolve the URIs / paths it owns. There is
     * one <b>per window</b>, so this is a <b>registry</b>, not a single slot: window B connecting to a second host
     * must not clobber window A's resolution, and closing B must not strand A's still-live remote paths (#436).
     * Resolution dispatches to the provider that owns the authority (for a URI) or the {@code FileSystem} (for a
     * path); a provider returns {@code null} for anything it doesn't own.
     */
    public interface RemoteProvider {
        /** A live {@link Path} for a remote URI whose authority this provider has connected, else {@code null}. */
        Path resolve(String uri);

        /** The {@code sftp://} storable for a {@link Path} on a filesystem this provider owns, else {@code null}. */
        String storable(Path path);
    }

    /** Live remote engines (one per window). Copy-on-write: registration/lookup races are harmless and rare. */
    private static final Set<RemoteProvider> providers = new CopyOnWriteArraySet<>();

    private Vfs() {}

    /** Registers a window's remote engine so its {@code sftp://} paths resolve (see {@link RemoteProvider}). */
    public static void registerRemoteProvider(RemoteProvider provider) {
        if (provider != null) {
            providers.add(provider);
        }
    }

    /**
     * Deregisters a window's remote engine (on window close / app exit). The registry is {@code static}, so
     * without this a closed window's engine (its SSH client, NIO threads, open sessions) would stay reachable
     * for the process's life. Only this window's provider is removed — every other window's stays live.
     */
    public static void unregisterRemoteProvider(RemoteProvider provider) {
        providers.remove(provider);
    }

    /** True when {@code path} is on the local default filesystem (a {@code null} path — untitled — counts
     *  as local). Features that shell out to a local process must check this. */
    public static boolean isLocal(Path path) {
        return path == null || path.getFileSystem() == FileSystems.getDefault();
    }

    /** True when {@code path} is on a remote (non-default) filesystem. */
    public static boolean isRemote(Path path) {
        return !isLocal(path);
    }

    /** A stable string for persisting a path: the plain path string for a local file (back-compat with the
     *  existing recent-files/projects JSON), or its URI for a remote file. */
    public static String toStorableString(Path path) {
        if (path == null) {
            return "";
        }
        if (isLocal(path)) {
            return path.toString();
        }
        // Dispatch to the provider that owns this path's FileSystem (never Path.toUri() — it throws for SFTP).
        for (RemoteProvider p : providers) {
            String s = p.storable(path);
            if (s != null) {
                return s;
            }
        }
        return path.toString(); // no live owner (disconnected) — best-effort
    }

    /** Reconstructs a path from {@link #toStorableString}: a local path directly, or a remote path via whichever
     *  registered engine owns its authority ({@code null} when none is connected to that host). */
    public static Path parseStorable(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        if (isRemoteUri(stored)) {
            for (RemoteProvider p : providers) {
                Path r = p.resolve(stored);
                if (r != null) {
                    return r;
                }
            }
            return null;
        }
        return Path.of(stored);
    }

    /** Whether {@code stored} is a remote URI (has a non-{@code file} scheme like {@code sftp://}). */
    public static boolean isRemoteUri(String stored) {
        int scheme = stored == null ? -1 : stored.indexOf("://");
        return scheme > 0 && !stored.regionMatches(true, 0, "file:", 0, 5);
    }

    /** A short label for the UI: {@code host:/path} for a remote file, the path string for a local one. */
    public static String displayLabel(Path path) {
        if (path == null) {
            return "";
        }
        if (isLocal(path)) {
            return path.toString();
        }
        SftpUri uri = SftpUri.parse(toStorableString(path)); // host:/path, via the storable (not toUri)
        return uri != null ? uri.label() : path.toString();
    }
}
