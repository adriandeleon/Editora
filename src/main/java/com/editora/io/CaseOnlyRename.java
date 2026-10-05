package com.editora.io;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Renaming a file to the same name in another case ({@code readme.md} → {@code README.md}) on a volume that
 * ignores case (the macOS and Windows defaults).
 *
 * <p>There the new name already "exists" — it resolves to the file being renamed — so a plain existence check
 * refuses the rename, and {@code Files.move} itself treats a move onto the same file as nothing to do. The
 * rename therefore goes through a temporary name.
 *
 * <p>{@link #isAlias} is deliberately narrow, because "same file" is also true of two hard links. Only when
 * the directory entry the new name resolves to is <em>spelled like the old name</em> is there a single entry
 * under two spellings; a second entry that merely shares the inode is a different file name and is left alone.
 * On a case-sensitive volume the new name either does not exist or is its own entry, so this is never true.
 */
public final class CaseOnlyRename {

    private CaseOnlyRename() {}

    /** True when {@code target} is {@code old}'s own directory entry, addressed in a different case. */
    public static boolean isAlias(Path old, Path target) {
        try {
            String from = String.valueOf(old.getFileName());
            String to = String.valueOf(target.getFileName());
            if (from.equals(to)
                    || !from.equalsIgnoreCase(to)
                    || !Objects.equals(
                            old.toAbsolutePath().getParent(),
                            target.toAbsolutePath().getParent())
                    || !Files.isSameFile(old, target)) {
                return false;
            }
            Path entry = target.toRealPath(LinkOption.NOFOLLOW_LINKS).getFileName();
            return entry != null && entry.toString().equals(from);
        } catch (IOException | RuntimeException cannotTell) {
            return false;
        }
    }

    /**
     * Moves {@code old} to {@code target} by way of a free temporary name beside it. If the second step
     * fails the file is moved back under its old name before the failure is reported.
     */
    public static void move(Path old, Path target) throws IOException {
        Path parked = null;
        for (int attempt = 0; parked == null; attempt++) {
            Path candidate = old.resolveSibling("." + AtomicFileWrite.boundedName(old) + "."
                    + Long.toUnsignedString(ThreadLocalRandom.current().nextLong()) + ".editora-tmp");
            try {
                Files.move(old, candidate);
                parked = candidate;
            } catch (FileAlreadyExistsException taken) {
                if (attempt >= 8) {
                    throw taken;
                }
            }
        }
        try {
            Files.move(parked, target);
        } catch (IOException | RuntimeException failed) {
            try {
                Files.move(parked, old);
            } catch (IOException | RuntimeException stranded) {
                failed.addSuppressed(stranded);
            }
            throw failed;
        }
    }
}
