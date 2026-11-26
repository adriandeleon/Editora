package com.editora.testkit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Makes folders refuse new files, so a test can see what a failed write does, and gives them back. On a POSIX
 * system the folder loses its write bits ({@code r-xr-xr-x}); Windows ignores the read-only flag on a folder, so
 * there the current user is denied adding, changing and removing entries by an ACL entry put first.
 */
public final class UnwritableDirs {

    private UnwritableDirs() {}

    private static final EnumSet<AclEntryPermission> DENIED = EnumSet.of(
            AclEntryPermission.ADD_FILE,
            AclEntryPermission.ADD_SUBDIRECTORY,
            AclEntryPermission.WRITE_DATA,
            AclEntryPermission.APPEND_DATA,
            AclEntryPermission.DELETE_CHILD);

    /** Locks {@code dirs}; the returned action unlocks them again. */
    public static Runnable lock(List<Path> dirs) throws IOException {
        List<Runnable> undo = new ArrayList<>();
        for (Path dir : dirs) {
            undo.add(lock(dir));
        }
        return () -> undo.forEach(Runnable::run);
    }

    /** Locks {@code dir}; the returned action unlocks it again. */
    public static Runnable lock(Path dir) throws IOException {
        if (dir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"));
            return () -> {
                try {
                    Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            };
        }
        AclFileAttributeView view = Files.getFileAttributeView(dir, AclFileAttributeView.class);
        if (view == null) {
            throw new UnsupportedOperationException("no POSIX modes and no ACLs on " + dir);
        }
        UserPrincipal me = dir.getFileSystem()
                .getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        AclEntry deny = AclEntry.newBuilder()
                .setType(AclEntryType.DENY)
                .setPrincipal(me)
                .setPermissions(DENIED)
                .build();
        List<AclEntry> acl = new ArrayList<>(view.getAcl());
        acl.add(0, deny);
        view.setAcl(acl);
        return () -> {
            try {
                List<AclEntry> now = new ArrayList<>(view.getAcl());
                now.remove(deny);
                view.setAcl(now);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }
}
