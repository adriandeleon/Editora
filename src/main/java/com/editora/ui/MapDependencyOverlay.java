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
     * for the root itself or a path outside it. Pure, and run off the FX thread (perf review S5): a top-level folder of
     * a large project walks every edge of every file in it. An interrupted thread (a newer selection superseded this
     * one) stops early with part of the answer, which nobody reads.
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
            if (Thread.currentThread().isInterrupted()) {
                return; // superseded: the answer is dropped
            }
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

    /**
     * The overlays worked out lately, so going back to a row (an arrow key up and down a column of folders) is a lookup
     * rather than another walk. Kept for one reading of the imports: a new {@link ProjectMapDependencies} empties it.
     * Confined to the FX thread.
     */
    static final class Recent {

        /** The most overlays kept. */
        static final int KEPT = 32;

        private record Key(Path root, Path subject, boolean folder) {}

        private @Nullable ProjectMapDependencies readFrom;
        private final Map<Key, MapDependencyOverlay> known = new java.util.LinkedHashMap<>(KEPT * 2, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, MapDependencyOverlay> eldest) {
                return size() > KEPT;
            }
        };

        /** The overlay worked out for these, or null. */
        @Nullable
        MapDependencyOverlay get(ProjectMapDependencies dependencies, Path root, Path subject, boolean folder) {
            return dependencies == readFrom ? known.get(new Key(root, subject, folder)) : null;
        }

        void put(
                ProjectMapDependencies dependencies,
                Path root,
                Path subject,
                boolean folder,
                MapDependencyOverlay overlay) {
            if (dependencies != readFrom) {
                readFrom = dependencies;
                known.clear();
            }
            known.put(new Key(root, subject, folder), overlay);
        }

        /** Forgets every overlay (a new root). */
        void clear() {
            readFrom = null;
            known.clear();
        }

        int size() {
            return known.size();
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
