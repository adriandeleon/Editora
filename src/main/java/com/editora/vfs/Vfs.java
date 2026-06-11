package com.editora.vfs;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * Small helpers for the virtual-filesystem abstraction. Editora keeps using {@link Path} everywhere, but a
 * path may now belong to a <em>remote</em> filesystem (an SFTP provider) instead of the local default one.
 * {@link #isLocal(Path)} is the single predicate every local-process feature (LSP, DAP, git, run, the HTTP
 * client) gates on; the storable-string helpers let recent-files/projects round-trip a remote path as a
 * URI. Pure (no toolkit), so it is unit-tested.
 */
public final class Vfs {

    /** Injected by {@code RemoteFileSystems} (once SFTP support is wired) to turn a remote URI back into a
     *  live {@link Path}; {@code null} until then (and remote URIs then deserialize to {@code null}). */
    private static volatile Function<String, Path> remoteResolver;
    /** Injected by {@code RemoteFileSystems} to turn a remote {@link Path} into its {@code sftp://} URI —
     *  we can't use {@code Path.toUri()} because MINA SSHD's implementation throws for SFTP paths. */
    private static volatile Function<Path, String> remoteStorable;

    private Vfs() {
    }

    public static void setRemoteResolver(Function<String, Path> resolver) {
        remoteResolver = resolver;
    }

    public static void setRemoteStorable(Function<Path, String> fn) {
        remoteStorable = fn;
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
        Function<Path, String> fn = remoteStorable;
        return fn != null ? fn.apply(path) : path.toString(); // never Path.toUri() — it throws for SFTP
    }

    /** Reconstructs a path from {@link #toStorableString}: a local path directly, or a remote path via the
     *  injected resolver ({@code null} when not connected / no resolver yet). */
    public static Path parseStorable(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        if (isRemoteUri(stored)) {
            Function<String, Path> resolver = remoteResolver;
            return resolver == null ? null : resolver.apply(stored);
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
