package com.editora.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.util.List;

/**
 * Carries a file's metadata over to the staged file that is about to replace it.
 *
 * <p>A staged replacement is a <em>new</em> file. Only its permission bits and group used to be copied, so
 * the first save of a file silently dropped everything else attached to the old one: extended attributes
 * (Finder tags and comments, {@code user.*} attributes, NTFS alternate data streams), the setuid, setgid and
 * sticky bits, and on Windows an explicit access-control list and the Hidden/System flags.
 *
 * <p>What can be copied is copied. What cannot be reproduced on the staged file makes {@link #carry} answer
 * false, and the caller then overwrites the file in place — behind a backup — because that is the one write
 * that keeps the file itself.
 */
final class FileMetadata {

    /** setuid, setgid and sticky. */
    private static final int SPECIAL_BITS = 07000;

    private FileMetadata() {}

    /**
     * Copies {@code from}'s extended attributes — and, off POSIX, its access-control list and DOS flags —
     * onto {@code to}.
     *
     * @return false when {@code from} has such metadata and it could not be put on {@code to}
     */
    static boolean carry(Path from, Path to) {
        if (!Files.exists(from, LinkOption.NOFOLLOW_LINKS)) {
            return true; // a brand-new file has nothing to lose
        }
        boolean posix = Files.getFileAttributeView(from, PosixFileAttributeView.class) != null;
        return carryUserAttributes(from, to) && (posix || (carryAcl(from, to) && carryDosFlags(from, to)));
    }

    private static boolean carryUserAttributes(Path from, Path to) {
        List<String> names;
        UserDefinedFileAttributeView source;
        try {
            source = Files.getFileAttributeView(from, UserDefinedFileAttributeView.class);
            if (source == null) {
                return true;
            }
            names = source.list();
        } catch (IOException | RuntimeException notSupportedHere) {
            return true; // the filesystem keeps none, so there are none to drop
        }
        if (names.isEmpty()) {
            return true;
        }
        try {
            UserDefinedFileAttributeView target = Files.getFileAttributeView(to, UserDefinedFileAttributeView.class);
            if (target == null) {
                return false;
            }
            for (String name : names) {
                ByteBuffer value = ByteBuffer.allocate(source.size(name));
                source.read(name, value);
                value.flip();
                target.write(name, value);
            }
            return true;
        } catch (IOException | RuntimeException notCopied) {
            return false;
        }
    }

    /** Windows (and other ACL-only stores): the staged file inherits the folder's list, not the file's own. */
    private static boolean carryAcl(Path from, Path to) {
        try {
            AclFileAttributeView source = Files.getFileAttributeView(from, AclFileAttributeView.class);
            AclFileAttributeView target = Files.getFileAttributeView(to, AclFileAttributeView.class);
            if (source == null || target == null) {
                return true;
            }
            List<AclEntry> wanted = source.getAcl();
            if (!wanted.equals(target.getAcl())) {
                target.setAcl(wanted);
            }
            return true;
        } catch (IOException | RuntimeException notCopied) {
            return false;
        }
    }

    private static boolean carryDosFlags(Path from, Path to) {
        try {
            DosFileAttributeView source = Files.getFileAttributeView(from, DosFileAttributeView.class);
            DosFileAttributeView target = Files.getFileAttributeView(to, DosFileAttributeView.class);
            if (source == null || target == null) {
                return true;
            }
            DosFileAttributes flags = source.readAttributes();
            if (flags.isHidden()) {
                target.setHidden(true);
            }
            if (flags.isSystem()) {
                target.setSystem(true);
            }
            return true;
        } catch (IOException | RuntimeException notCopied) {
            return false;
        }
    }

    /** {@code file}'s setuid/setgid/sticky bits together with its permission bits, or -1 where there are none. */
    static int specialMode(Path file) {
        try {
            return Files.getAttribute(file, "unix:mode") instanceof Integer mode && (mode & SPECIAL_BITS) != 0
                    ? mode & 07777
                    : -1;
        } catch (IOException | RuntimeException notUnix) {
            return -1;
        }
    }

    /**
     * Gives {@code file} the mode {@link #specialMode} read. Call it after the bytes are written: the kernel
     * clears setuid and setgid whenever an unprivileged process writes to a file. Best effort, like the
     * permission bits themselves.
     */
    static void applySpecialMode(Path file, int mode) {
        if (mode < 0) {
            return;
        }
        try {
            Files.setAttribute(file, "unix:mode", mode);
        } catch (IOException | RuntimeException notPermitted) {
            // A save that keeps the plain mode still beats a save that does not happen.
        }
    }
}
