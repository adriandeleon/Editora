package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.editora.text.LspDiagnostic;
import org.jspecify.annotations.Nullable;

/**
 * The problems the Project Map marks (Map code browser round 2): the errors and warnings the editor's Problems window
 * holds, counted per file and added up into every folder above it, below the Map's folder; and the problems one after
 * another, file by file and line by line, for Next and Previous Problem on the Map. Pure and immutable.
 */
final class ProjectMapProblems {

    /** How many errors and warnings a file has, or the files in a folder have. */
    record Count(int errors, int warnings) {
        static final Count ZERO = new Count(0, 0);

        boolean any() {
            return errors > 0 || warnings > 0;
        }

        Count plus(Count other) {
            return new Count(errors + other.errors, warnings + other.warnings);
        }
    }

    /** One problem: its file, 0-based line and column, whether it is an error, and what it says. */
    record Place(Path file, int line, int column, boolean error, String message) {}

    static final ProjectMapProblems NONE = new ProjectMapProblems(Map.of(), null);

    private final Map<Path, Count> counts = new HashMap<>();
    private final List<Place> places = new ArrayList<>();
    private final List<Path> files = new ArrayList<>();

    /**
     * From the Problems window's {@code byFile} (each file's diagnostics); only errors and warnings count, and only
     * files in {@code root} (when given) are added up into its folders.
     */
    ProjectMapProblems(Map<Path, List<LspDiagnostic>> byFile, @Nullable Path root) {
        Path base = ProjectMapModel.normalize(root);
        Map<Path, List<Place>> sorted = new TreeMap<>();
        for (Map.Entry<Path, List<LspDiagnostic>> entry : byFile.entrySet()) {
            Path file = ProjectMapModel.normalize(entry.getKey());
            if (file == null || base != null && !file.startsWith(base)) {
                continue;
            }
            int errors = 0;
            int warnings = 0;
            List<Place> mine = new ArrayList<>();
            for (LspDiagnostic diagnostic : entry.getValue()) {
                boolean error = diagnostic.severity() == LspDiagnostic.Severity.ERROR;
                if (!error && diagnostic.severity() != LspDiagnostic.Severity.WARNING) {
                    continue;
                }
                if (error) {
                    errors++;
                } else {
                    warnings++;
                }
                mine.add(new Place(file, diagnostic.startLine(), diagnostic.startCol(), error, diagnostic.message()));
            }
            if (mine.isEmpty()) {
                continue;
            }
            Count count = new Count(errors, warnings);
            counts.merge(file, count, Count::plus);
            for (Path folder = file.getParent();
                    folder != null && (base == null ? folder.getParent() != null : folder.startsWith(base));
                    folder = folder.getParent()) {
                counts.merge(folder, count, Count::plus);
            }
            mine.sort(Comparator.comparingInt(Place::line).thenComparingInt(Place::column));
            sorted.merge(file, mine, (a, b) -> {
                List<Place> both = new ArrayList<>(a);
                both.addAll(b);
                both.sort(Comparator.comparingInt(Place::line).thenComparingInt(Place::column));
                return both;
            });
        }
        sorted.forEach((file, mine) -> {
            files.add(file);
            places.addAll(mine);
        });
    }

    /** The errors and warnings of the file or folder at {@code path}. */
    Count of(@Nullable Path path) {
        Path key = ProjectMapModel.normalize(path);
        return key == null ? Count.ZERO : counts.getOrDefault(key, Count.ZERO);
    }

    /** Whether the file or folder at {@code path} has an error or a warning. */
    boolean has(@Nullable Path path) {
        return of(path).any();
    }

    /** The files with problems, in path order. */
    List<Path> files() {
        return List.copyOf(files);
    }

    /** Every problem, file by file in path order and line by line. */
    List<Place> places() {
        return List.copyOf(places);
    }

    /** Whether there is no problem at all. */
    boolean isEmpty() {
        return places.isEmpty();
    }

    /**
     * The problem after ({@code forward}) or before the place {@code file}'s 0-based {@code line} and {@code column}
     * — the first (or last) with no place — going round past the end; null with none.
     */
    @Nullable
    Place step(@Nullable Path file, int line, int column, boolean forward) {
        if (places.isEmpty()) {
            return null;
        }
        Path at = ProjectMapModel.normalize(file);
        if (at == null) {
            return forward ? places.get(0) : places.get(places.size() - 1);
        }
        Comparator<Place> order =
                Comparator.comparing(Place::file).thenComparingInt(Place::line).thenComparingInt(Place::column);
        Place here = new Place(at, line, column, false, "");
        if (forward) {
            for (Place place : places) {
                if (order.compare(place, here) > 0) {
                    return place;
                }
            }
            return places.get(0);
        }
        for (int i = places.size() - 1; i >= 0; i--) {
            if (order.compare(places.get(i), here) < 0) {
                return places.get(i);
            }
        }
        return places.get(places.size() - 1);
    }

    /** Where {@code place} stands among all problems, 1-based. */
    int indexOf(Place place) {
        return places.indexOf(place) + 1;
    }
}
