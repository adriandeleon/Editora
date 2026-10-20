package com.editora.deploy;

import java.nio.file.Path;

import org.jspecify.annotations.Nullable;

/**
 * Maps between a local project and its deployed copy: a file under the project root is the file at the same
 * relative path under the deployment's folder on the host, and back. Pure.
 *
 * @param localRoot the project root on this machine
 * @param remoteRoot the deployment's folder, as a path on the host's connection
 */
public record DeployPaths(Path localRoot, Path remoteRoot) {

    /** {@code local}'s copy on the host, or null when it is not inside the project. */
    public @Nullable Path toHost(Path local) {
        String relative = relative(localRoot, local);
        if (relative == null) {
            return null;
        }
        return relative.isEmpty() ? remoteRoot : remoteRoot.resolve(relative);
    }

    /** The local file {@code remote} is the copy of, or null when it is not inside the deployment's folder. */
    public @Nullable Path toLocal(Path remote) {
        String relative = relative(remoteRoot, remote);
        if (relative == null) {
            return null;
        }
        return relative.isEmpty() ? localRoot : localRoot.resolve(relative); // "/" is a separator on Windows too
    }

    /**
     * A configuration field naming a local path ({@code text}), as the deployed run reads it: an absolute path
     * inside the project becomes relative to it (so it names the copy), anything else is left as written —
     * a relative path is relative to the project root on both sides.
     */
    public String hostField(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        try {
            Path p = localRoot.getFileSystem().getPath(text.strip());
            if (!p.isAbsolute()) {
                return text;
            }
            String relative = relative(localRoot, p);
            return relative == null ? text : relative;
        } catch (java.nio.file.InvalidPathException e) {
            return text;
        }
    }

    /** {@code path} relative to {@code root} with {@code /} separators; "" for the root, null when outside it. */
    public static @Nullable String relative(Path root, Path path) {
        if (root == null || path == null || root.getFileSystem() != path.getFileSystem()) {
            return null;
        }
        Path r = root.toAbsolutePath().normalize();
        Path p = path.toAbsolutePath().normalize();
        if (!p.startsWith(r)) {
            return null;
        }
        return r.relativize(p).toString().replace('\\', '/');
    }
}
