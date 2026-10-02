package com.editora.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * "Is this path really inside that folder?" — answered on the <b>canonical</b> paths, not the spelled ones.
 *
 * <p>{@code root.resolve(ref).normalize().startsWith(root)} is a statement about strings. A folder the user
 * merely opened can contain {@code payload.json -> ~/.ssh/id_rsa} or {@code logs -> /etc}, and every lexical
 * check passes while the read or write lands somewhere else entirely. Each trust boundary that confines
 * content-supplied paths to a folder (the {@code .http} file references, the agent's file reads/writes,
 * Markdown links opened in the editor) asks here instead, so there is one definition of "inside".
 *
 * <p>Touches the filesystem ({@code toRealPath}); unit-tested against a temp directory.
 */
public final class PathContainment {

    private PathContainment() {}

    /**
     * {@code path} with its longest <em>existing</em> prefix canonicalized ({@code toRealPath()}: symlinks
     * resolved, case and {@code ..} normalized) and the not-yet-existing remainder re-appended — so a path
     * that is about to be created can still be placed. A dangling symbolic link counts as existing (so it is
     * resolved, and throws) rather than as a plain missing name.
     */
    public static Path realOrNearest(Path path) throws IOException {
        Path abs = path.toAbsolutePath().normalize();
        Path existing = abs;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return abs;
        }
        Path real = existing.toRealPath();
        return existing.equals(abs) ? real : real.resolve(existing.relativize(abs));
    }

    /**
     * Whether {@code target}'s canonical location is {@code root}'s canonical location or below it. False for
     * a null argument and for anything that cannot be vouched for (a dangling link, an unreadable parent).
     */
    public static boolean isWithin(Path root, Path target) {
        if (root == null || target == null) {
            return false;
        }
        try {
            return realOrNearest(target).startsWith(realOrNearest(root));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
