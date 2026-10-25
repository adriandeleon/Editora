package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import com.editora.config.PathKeys;
import org.jspecify.annotations.Nullable;

/**
 * Where {@code git clone} should put the repository, from what was typed in the clone form.
 *
 * <p>The text is resolved like every other typed path ({@link PathKeys#resolveUserInput}: {@code ~} is the
 * home folder, a relative path is relative to {@code base}) — it used to go straight to {@code Path.of}, so
 * {@code ~/x} became a folder literally named {@code ~} and an unparsable path threw. git creates the target
 * folder but not the folders above it, so a missing parent is created here; and git accepts a target that
 * exists as long as it is an empty folder, so that is accepted too.
 *
 * <p>On a remote host the text is a path there ({@code base} is on the host's file system, {@code userHome}
 * the account's home there), and checking or creating folders is SFTP: call it off the FX thread.
 *
 * @param path the absolute target, or {@code null} when it cannot be used
 * @param errorKey the message key saying why not ({@code null} on success); its one argument is {@link #shown}
 * @param shown the path (or the raw text) to name in that message
 */
record CloneDestination(@Nullable Path path, @Nullable String errorKey, String shown) {

    boolean ok() {
        return path != null;
    }

    /** Resolves and validates the target without touching the disk beyond reading it. */
    static CloneDestination resolve(String text, @Nullable Path base, @Nullable String userHome) {
        String typed = text == null ? "" : text.strip();
        // No home (a host that did not say): "~" names nothing, as resolveUserInputOn has it on a host.
        boolean noHome = userHome == null && (typed.equals("~") || typed.startsWith("~/"));
        Path target = noHome ? null : PathKeys.resolveUserInputOn(typed, base, userHome == null ? "" : userHome);
        if (target == null || target.getParent() == null) {
            return new CloneDestination(null, "status.clone.invalidDest", typed);
        }
        if (Files.exists(target) && !emptyDirectory(target)) {
            return new CloneDestination(null, "status.destExists", target.toString());
        }
        Path parent = target.getParent();
        if (Files.exists(parent) && !Files.isDirectory(parent)) {
            return new CloneDestination(null, "status.clone.parentFailed", parent.toString());
        }
        return new CloneDestination(target, null, target.toString());
    }

    /** {@link #resolve}, then creates the folders above the target when they are missing. */
    static CloneDestination prepare(String text, @Nullable Path base, @Nullable String userHome) {
        CloneDestination destination = resolve(text, base, userHome);
        Path target = destination.path();
        // resolve() only returns a path that has a parent
        Path parent = target == null ? null : target.getParent();
        if (parent == null) {
            return destination;
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException | SecurityException e) {
            return new CloneDestination(null, "status.clone.parentFailed", parent.toString());
        }
        return destination;
    }

    private static boolean emptyDirectory(Path dir) {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.findAny().isEmpty();
        } catch (IOException | SecurityException e) {
            return false;
        }
    }
}
