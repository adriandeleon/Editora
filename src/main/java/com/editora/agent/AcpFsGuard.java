package com.editora.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import com.editora.io.PathContainment;

/**
 * Confines the agent's {@code fs/read_text_file} / {@code fs/write_text_file} requests to the session folder.
 *
 * <p>These two methods are the editor doing file I/O <em>on the agent's behalf</em>, with the editor's own
 * privileges and no permission prompt: the agent names a path and the client reads or overwrites it. The
 * agent's own tools sit behind its permission system; this channel did not, so a prompt-injected agent could
 * ask for {@code ~/.ssh/id_rsa} or rewrite {@code ~/.bashrc} through it. The rule here is the one the session
 * was started with: the session's working directory (the project root) is the agent's world.
 *
 * <ul>
 *   <li>The path must be canonically inside the session root ({@link PathContainment} — a symlink in the
 *       project that points outside it does not count as inside).
 *   <li>A read must be of a regular file no larger than {@link #MAX_READ_BYTES}.
 *   <li>A write must not go through a symbolic link, and never lands in the editor's own configuration
 *       directory — even when that sits inside the session root (a session started in the home folder):
 *       settings, keymaps and plugins there can make the editor run commands.
 *   <li>A write never lands in version-control metadata ({@code .git/}, {@code .hg/}, …) anywhere under the
 *       session root: hooks and config there run commands too, and a rewritten {@code HEAD} or index is the
 *       repository's history, not a project file. Reading it stays allowed.
 * </ul>
 *
 * <p>Both checks return the absolute path they vetted. <b>That path, not the agent's string, is what the
 * caller must read or write</b>: a relative path is relative to the session folder here, and resolving the
 * raw string a second time would place it under the editor's own working directory instead.
 *
 * <p>A refused request fails with a message the agent can read and report; nothing is asked of the user.
 * Filesystem-touching but otherwise pure; unit-tested against a temp directory.
 */
public final class AcpFsGuard {

    /** Largest file served to an agent in one read (text the model has to hold anyway). */
    public static final long MAX_READ_BYTES = 16L * 1024 * 1024;

    /** Directory (or gitlink file) names that hold a repository's own state. Compared ignoring case. */
    private static final java.util.Set<String> VCS_METADATA =
            java.util.Set.of(".git", ".hg", ".svn", ".bzr", ".jj", ".sl", "_darcs");

    private AcpFsGuard() {}

    /** The absolute path a read request names, or an {@link IOException} explaining why it is refused. */
    public static Path checkRead(Path root, String path) throws IOException {
        Path target = resolveInside(root, path);
        if (Files.exists(target)) {
            if (!Files.isRegularFile(target)) {
                throw new IOException("Refused: not a regular file: " + path);
            }
            if (Files.size(target) > MAX_READ_BYTES) {
                throw new IOException(
                        "Refused: " + path + " is larger than " + (MAX_READ_BYTES / (1024 * 1024)) + " MB");
            }
        }
        return target;
    }

    /** The absolute path a write request names, or an {@link IOException} explaining why it is refused. */
    public static Path checkWrite(Path root, Path protectedDir, String path) throws IOException {
        Path target = resolveInside(root, path);
        if (Files.isSymbolicLink(target)) {
            throw new IOException("Refused: " + path + " is a symbolic link");
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(target)) {
            throw new IOException("Refused: not a regular file: " + path);
        }
        if (protectedDir != null && PathContainment.isWithin(protectedDir, target)) {
            throw new IOException("Refused: " + path + " is inside the editor's configuration directory");
        }
        if (insideVcsMetadata(root, target)) {
            throw new IOException("Refused: " + path + " is version-control metadata, which the editor does not"
                    + " write on an agent's behalf");
        }
        return target;
    }

    /**
     * Whether {@code target} is, or lies under, a version-control metadata entry below {@code root} — by the
     * name the agent used or by where that name really leads (a link to {@code .git} under another name).
     */
    static boolean insideVcsMetadata(Path root, Path target) {
        if (hasVcsComponent(root.toAbsolutePath().normalize(), target)) {
            return true;
        }
        try {
            return hasVcsComponent(PathContainment.realOrNearest(root), PathContainment.realOrNearest(target));
        } catch (IOException | RuntimeException e) {
            return true; // cannot be vouched for
        }
    }

    /**
     * Whether {@code target} is, or lies under, a version-control metadata entry <em>anywhere</em> on its
     * path — for a channel that is not confined to one folder (the MCP bridge writes through whatever buffer
     * the user has open). By the name used and by where that name really leads, as above.
     */
    public static boolean isVcsMetadata(Path target) {
        if (target == null) {
            return false;
        }
        Path spelled = target.toAbsolutePath().normalize();
        if (hasVcsMetadataName(spelled)) {
            return true;
        }
        try {
            return hasVcsMetadataName(PathContainment.realOrNearest(spelled));
        } catch (IOException | RuntimeException e) {
            return true; // cannot be vouched for
        }
    }

    /** Whether one of {@code path}'s own name elements is a version-control metadata name. No I/O. */
    public static boolean hasVcsMetadataName(Path path) {
        if (path == null) {
            return false;
        }
        for (Path part : path) {
            if (VCS_METADATA.contains(part.toString().toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasVcsComponent(Path root, Path target) {
        if (!target.startsWith(root)) {
            return false;
        }
        for (Path part : root.relativize(target)) {
            if (VCS_METADATA.contains(part.toString().toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static Path resolveInside(Path root, String path) throws IOException {
        if (path == null || path.isBlank()) {
            throw new IOException("Refused: no path given");
        }
        if (root == null) {
            throw new IOException("Refused: the session has no working directory to confine " + path + " to");
        }
        Path target;
        try {
            target = root.toAbsolutePath().resolve(path).normalize();
        } catch (RuntimeException e) {
            throw new IOException("Refused: not a usable path: " + path);
        }
        if (!PathContainment.isWithin(root, target)) {
            throw new IOException("Refused: " + path + " is outside the session folder " + root);
        }
        return target;
    }
}
