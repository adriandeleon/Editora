package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import com.editora.config.PathKeys;

/**
 * Where {@code git clone} should put the repository, from what was typed in the clone form.
 *
 * <p>The text is resolved like every other typed path ({@link PathKeys#resolveUserInput}: {@code ~} is the
 * home folder, a relative path is relative to {@code base}) — it used to go straight to {@code Path.of}, so
 * {@code ~/x} became a folder literally named {@code ~} and an unparsable path threw. git creates the target
 * folder but not the folders above it, so a missing parent is created here; and git accepts a target that
 * exists as long as it is an empty folder, so that is accepted too.
 *
 * @param path the absolute target, or {@code null} when it cannot be used
 * @param errorKey the message key saying why not ({@code null} on success); its one argument is {@link #shown}
 * @param shown the path (or the raw text) to name in that message
 */
record CloneDestination(Path path, String errorKey, String shown) {

    boolean ok() {
        return path != null;
    }

    /** Resolves and validates the target without touching the disk beyond reading it. */
    static CloneDestination resolve(String text, Path base, String userHome) {
        String typed = text == null ? "" : text.strip();
        Path target = PathKeys.resolveUserInput(typed, base, userHome);
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
    static CloneDestination prepare(String text, Path base, String userHome) {
        CloneDestination destination = resolve(text, base, userHome);
        if (!destination.ok()) {
            return destination;
        }
        Path parent = destination.path().getParent();
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
