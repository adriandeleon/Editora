package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

/**
 * Where a {@code .http} file's environments come from: the nearest folder, from the file's own folder up,
 * that holds {@code http-client.env.json} or {@code http-client.private.env.json} — stopping at the
 * project root when there is one (JetBrains looks beside the file, then further up the project). The
 * environment names offered are those of both files, public first. Pure apart from the existence checks,
 * which are injected for the tests.
 */
final class HttpEnvFiles {

    static final String PUBLIC = "http-client.env.json";
    static final String PRIVATE = "http-client.private.env.json";

    private HttpEnvFiles() {}

    /**
     * The folder whose env files apply to a request file in {@code dir}: the first of {@code dir} and its
     * parents holding either env file, never above {@code stopAt} (when {@code stopAt} contains {@code dir}).
     * Falls back to {@code dir} itself when none is found (its files, if any appear later, are then used).
     */
    static Path envDir(Path dir, Path stopAt, Predicate<Path> exists) {
        if (dir == null) {
            return null;
        }
        Path start = dir.toAbsolutePath().normalize();
        Path stop = stopAt == null ? null : stopAt.toAbsolutePath().normalize();
        boolean bounded = stop != null && start.startsWith(stop);
        for (Path d = start; d != null; d = d.getParent()) {
            if (exists.test(d.resolve(PUBLIC)) || exists.test(d.resolve(PRIVATE))) {
                return d;
            }
            if (bounded && d.equals(stop)) {
                break;
            }
        }
        return start;
    }

    /** {@link #envDir(Path, Path, Predicate)} against the real file system. */
    static Path envDir(Path dir, Path stopAt) {
        return envDir(dir, stopAt, Files::exists);
    }

    /** The name to select for a file whose remembered choice is {@code remembered}: it, if still offered; else none. */
    static String choose(String remembered, List<String> names) {
        return remembered != null && !remembered.isEmpty() && names.contains(remembered) ? remembered : "";
    }
}
