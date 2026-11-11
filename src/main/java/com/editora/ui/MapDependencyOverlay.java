package com.editora.ui;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * The Map's dependency overlay (Map code browser round 2): with the selection a file or a folder, which rows it uses
 * and which use it — each file, and every folder above it below the Map's folder that does not hold the selection —
 * from {@link ProjectMapDependencies}. Pure and immutable.
 */
final class MapDependencyOverlay {

    /** A row the selection uses. */
    static final int USES = 1;
    /** A row that uses the selection. */
    static final int USED_BY = 2;
    /** A row on both sides. */
    static final int BOTH = USES | USED_BY;

    static final MapDependencyOverlay NONE = new MapDependencyOverlay(null, false, Map.of(), 0, 0, false);

    private final @Nullable Path subject;
    private final boolean read;
    private final Map<Path, Integer> marks;
    private final int uses;
    private final int users;
    private final boolean truncated;

    private MapDependencyOverlay(
            @Nullable Path subject, boolean read, Map<Path, Integer> marks, int uses, int users, boolean truncated) {
        this.subject = subject;
        this.read = read;
        this.marks = marks;
        this.uses = uses;
        this.users = users;
        this.truncated = truncated;
    }

    /**
     * What is around {@code subject} — a file, or a folder when {@code folder} — in the project at {@code root}; none
     * for the root itself or a path outside it.
     */
    static MapDependencyOverlay of(ProjectMapDependencies dependencies, Path root, Path subject, boolean folder) {
        Path base = root.toAbsolutePath().normalize();
        Path at = subject.toAbsolutePath().normalize();
        if (at.equals(base) || !at.startsWith(base)) {
            return NONE;
        }
        String rel = base.relativize(at).toString().replace('\\', '/');
        ProjectMapDependencies.Neighbours around = dependencies.neighbours(rel, folder);
        Map<Path, Integer> marks = new HashMap<>();
        mark(marks, base, at, around.uses(), USES);
        mark(marks, base, at, around.users(), USED_BY);
        boolean read = folder || dependencies.read(rel);
        return new MapDependencyOverlay(
                at,
                read,
                Map.copyOf(marks),
                around.uses().size(),
                around.users().size(),
                dependencies.truncated());
    }

    private static void mark(Map<Path, Integer> marks, Path base, Path subject, Set<String> files, int side) {
        for (String file : files) {
            Path path = base.resolve(file).normalize();
            marks.merge(path, side, (a, b) -> a | b);
            for (Path folder = path.getParent();
                    folder != null && folder.startsWith(base) && !folder.equals(base);
                    folder = folder.getParent()) {
                if (!subject.startsWith(folder)) { // a folder holding the selection is on its path already
                    marks.merge(folder, side, (a, b) -> a | b);
                }
            }
        }
    }

    /** {@link #USES}, {@link #USED_BY}, {@link #BOTH}, or 0 for a row the selection has nothing to do with. */
    int mark(@Nullable Path path) {
        return path == null ? 0 : marks.getOrDefault(path, 0);
    }

    /** The file or folder the overlay is around; null for none. */
    @Nullable
    Path subject() {
        return subject;
    }

    /** Whether the subject's imports were read: a folder, or a file in a language the Map reads. */
    boolean read() {
        return read;
    }

    /** How many files the subject uses. */
    int uses() {
        return uses;
    }

    /** How many files use the subject. */
    int users() {
        return users;
    }

    /** Whether the project had more files than were read: some uses may be missing. */
    boolean truncated() {
        return truncated;
    }
}
