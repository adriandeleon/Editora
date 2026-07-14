package com.editora.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * Writes a <b>document</b> (the user's file) as safely as the platform allows: to a temp file in the same
 * directory, then moved into place — so the real file is only ever replaced by a complete one.
 *
 * <p>{@code Files.write(file, bytes)} truncates the target and then streams into it: a crash, a full disk, or
 * any I/O error partway through leaves the user's file truncated or half-written, with no copy to recover
 * from. The editor still holds the text in memory, but a power cut doesn't care.
 *
 * <p>Two things a naive temp-and-move gets wrong, both of which would be worse than the bug it fixes:
 *
 * <ul>
 *   <li><b>Symlinks.</b> Moving over a symlink <em>replaces the link with a regular file</em>. Editing a
 *       dotfile that's symlinked into a dotfiles repo (a very normal setup) would quietly detach it. So the
 *       link is resolved first and the target is written.
 *   <li><b>Permissions.</b> A fresh temp file gets default permissions, so a shell script would silently lose
 *       its executable bit and any group/other access. The existing file's POSIX permissions are copied onto
 *       the temp file before the move.
 * </ul>
 *
 * <p>If the platform can't do any of that (a filesystem without atomic move, a directory we can't create a
 * temp file in — e.g. a read-only dir holding a writable file), it falls back to a plain in-place write, which
 * is what the editor did before: no worse than the status quo, and the save still happens.
 */
public final class AtomicFileWrite {

    private AtomicFileWrite() {}

    /** Writes {@code bytes} to {@code file}, replacing it atomically where the platform supports it. */
    public static void write(Path file, byte[] bytes) throws IOException {
        Path target = resolveLink(file);
        Path dir = target.getParent();
        if (dir == null || !Files.isDirectory(dir)) {
            Files.write(target, bytes); // no directory to stage in — write in place
            return;
        }
        Path tmp;
        try {
            tmp = Files.createTempFile(dir, "." + target.getFileName() + ".", ".editora-tmp");
        } catch (IOException cannotStage) {
            Files.write(target, bytes); // e.g. a read-only directory holding a writable file
            return;
        }
        try {
            Files.write(tmp, bytes);
            copyPermissions(target, tmp);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Files.deleteIfExists(tmp); // never leave litter next to the user's file
            throw e;
        }
    }

    /**
     * The real file behind {@code file} when it is a symlink — writing through the link keeps it a link.
     * A broken link, or any resolution failure, falls back to the path as given.
     */
    static Path resolveLink(Path file) {
        try {
            return Files.isSymbolicLink(file) ? file.toRealPath() : file;
        } catch (IOException brokenLink) {
            return file;
        }
    }

    /** Copies {@code from}'s POSIX permissions onto {@code to}, so the saved file keeps its mode (e.g. +x). */
    private static void copyPermissions(Path from, Path to) {
        try {
            if (!Files.exists(from, LinkOption.NOFOLLOW_LINKS)) {
                return; // a brand-new file: the temp file's defaults are correct
            }
            PosixFileAttributeView view = Files.getFileAttributeView(from, PosixFileAttributeView.class);
            if (view == null) {
                return; // not a POSIX filesystem (Windows) — nothing to carry over
            }
            Set<PosixFilePermission> perms = view.readAttributes().permissions();
            Files.setPosixFilePermissions(to, perms);
        } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
            // Best effort: a save that keeps the wrong mode still beats a save that doesn't happen.
        }
    }
}
