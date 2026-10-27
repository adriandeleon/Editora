package com.editora.terminal;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.jspecify.annotations.Nullable;

/**
 * Turns a link target into a path on this machine.
 *
 * <p>Pure: it decides what path a token names, never whether that path exists. The existence check
 * is the caller's, because it is the one part that touches the disk and the one part that must not
 * run while painting.
 */
public final class LinkPaths {

    private LinkPaths() {}

    /**
     * The file a path token names, resolved against the shell's directory, or null.
     *
     * <p>Relative to {@code cwd} rather than to Editora's own working directory, which is the whole
     * reason this is useful: {@code src/main/java/Foo.java} in a build's output means that file in
     * the directory the build ran in, and every tab has its own.
     */
    public static @Nullable Path resolve(String token, @Nullable Path cwd) {
        if (token == null || token.isBlank()) return null;
        try {
            String expanded = expandHome(token);
            Path path = Paths.get(expanded);
            if (path.isAbsolute()) return path.normalize();
            if (cwd == null) return null;
            return cwd.resolve(path).normalize();
        } catch (RuntimeException e) {
            // A token with a NUL or an illegal character for this filesystem is not a path. The
            // pointer is simply over ordinary text.
            return null;
        }
    }

    /**
     * The file a path token names for a shell on another machine: resolved against {@code cwd} as that shell names
     * it, then mapped to a path Editora can open by {@code toPath} (the host's file over SFTP, the folder a
     * container mounts). Null when it is not a path or there is no directory to resolve a relative one against.
     * {@code ~} is not expanded: it is that machine's home, not this one's.
     */
    public static @Nullable Path resolveOn(String token, String cwd, java.util.function.Function<String, Path> toPath) {
        if (token == null || token.isBlank()) return null;
        try {
            if (token.startsWith("/")) return toPath.apply(token).normalize();
            if (cwd == null || cwd.isBlank() || token.startsWith("~")) return null;
            return toPath.apply(cwd).resolve(token).normalize();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** {@code ~} and {@code ~/…} against the running user's home. {@code ~other} is left alone. */
    static String expandHome(String token) {
        if (token.equals("~")) return home();
        if (token.startsWith("~/") || token.startsWith("~\\")) return home() + token.substring(1);
        return token;
    }

    private static String home() {
        return System.getProperty("user.home", "");
    }

    /**
     * The file a {@code file:} URL names, or null when it is not one or names another host.
     *
     * <p>Kept apart from {@link #resolve} because a URL is percent-encoded and a path is not:
     * {@code file:///tmp/a%20b} is one file whose name has a space in it, and treating those bytes
     * as a path would look for a file called {@code a%20b}.
     */
    public static @Nullable Path fromFileUri(String target) {
        if (target == null || !target.regionMatches(true, 0, "file:", 0, 5)) return null;
        try {
            URI uri = new URI(target);
            String host = uri.getHost();
            // file://host/share is someone else's filesystem. Not ours to open — unless the host is this machine,
            // which is how ls --hyperlink and every OSC 7 spell a local file.
            if (host != null && !ShellIntegration.isThisMachine(host)) return null;
            if (uri.getPath() == null) return null;
            return Paths.get(uri.getPath()).normalize();
        } catch (Exception e) {
            return null;
        }
    }
}
