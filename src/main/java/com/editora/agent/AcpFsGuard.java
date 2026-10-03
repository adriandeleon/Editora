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
 * </ul>
 *
 * <p>A refused request fails with a message the agent can read and report; nothing is asked of the user.
 * Filesystem-touching but otherwise pure; unit-tested against a temp directory.
 */
public final class AcpFsGuard {

    /** Largest file served to an agent in one read (text the model has to hold anyway). */
    public static final long MAX_READ_BYTES = 16L * 1024 * 1024;

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
        return target;
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
