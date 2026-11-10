package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * What one Project Map search found, best first: files, folders and declarations, as many as were kept of
 * {@code total}, and whether the project's file list was itself cut short ({@code partial}: the list hit its cap
 * or depth limit, so a match may exist that the search never saw).
 *
 * <p>The search hands this to {@link ProjectMapView#setSearchMatches(ProjectMapSearchResult)}; the map opens the
 * folders that lead to the matches and lands on the first one. Ranked by {@link ProjectMapSearch}.
 *
 * @param query the query as typed, sigil included ({@code @} for declarations, {@code #} for files only)
 * @param matches the kept matches, best first
 * @param total how many matched in all ({@code >= matches.size()})
 * @param partial the file list did not cover the whole project
 */
record ProjectMapSearchResult(String query, List<Match> matches, int total, boolean partial) {

    /** What a match is. */
    enum Kind {
        FILE,
        FOLDER,
        SYMBOL,
        /** A line of a file that holds the text searched for ({@code %text}, Map code browser gap 10). */
        TEXT
    }

    /**
     * One match.
     *
     * @param kind a file, a folder, or a declaration in a file
     * @param path the file or folder (for a declaration: its file), absolute and normalized
     * @param relativePath {@code path} relative to the Map's root, {@code /}-separated
     * @param symbol the declaration's name, for {@link Kind#SYMBOL}; else null
     * @param line the declaration's 0-based line, for {@link Kind#SYMBOL}; the line found, for {@link Kind#TEXT};
     *     else -1
     * @param detail what the declaration is and where (its kind or container), for a list row; the line's text,
     *     for {@link Kind#TEXT}; else ""
     */
    record Match(
            Kind kind,
            Path path,
            String relativePath,
            @Nullable String symbol,
            int line,
            String detail) {
        Match {
            path = path.toAbsolutePath().normalize();
            relativePath = relativePath == null ? "" : relativePath;
            detail = detail == null ? "" : detail;
        }

        static Match file(Path path, String relativePath) {
            return new Match(Kind.FILE, path, relativePath, null, -1, "");
        }

        static Match folder(Path path, String relativePath) {
            return new Match(Kind.FOLDER, path, relativePath, null, -1, "");
        }

        static Match symbol(Path file, String relativePath, String name, int line, String detail) {
            return new Match(Kind.SYMBOL, file, relativePath, name, Math.max(0, line), detail);
        }

        /** The 0-based {@code line} of {@code file} that holds the text searched for; {@code text} is the line's. */
        static Match text(Path file, String relativePath, int line, String text) {
            return new Match(Kind.TEXT, file, relativePath, null, Math.max(0, line), text);
        }

        /** The text a results row shows first: the declaration's name, else the last segment of the path. */
        String label() {
            if (symbol != null) {
                return symbol;
            }
            Path name = path.getFileName();
            return name == null ? relativePath : name.toString();
        }
    }

    static final ProjectMapSearchResult EMPTY = new ProjectMapSearchResult("", List.of(), 0, false);

    ProjectMapSearchResult {
        query = query == null ? "" : query;
        matches = matches == null ? List.of() : List.copyOf(matches);
        total = Math.max(total, matches.size());
    }

    /** A file-name search's answer in the old shape: every path a file. */
    static ProjectMapSearchResult ofFiles(String query, @Nullable List<Path> files, int total, boolean partial) {
        List<Match> matches = new ArrayList<>();
        if (files != null) {
            for (Path file : files) {
                if (file != null) {
                    Path name = file.getFileName();
                    matches.add(Match.file(file, name == null ? file.toString() : name.toString()));
                }
            }
        }
        return new ProjectMapSearchResult(query, matches, total, partial);
    }

    /** The files and folders the matches stand on, best first, each once (a declaration stands on its file). */
    List<Path> paths() {
        Set<Path> paths = new LinkedHashSet<>();
        for (Match match : matches) {
            paths.add(match.path());
        }
        return List.copyOf(paths);
    }

    /** The first match, or null. */
    @Nullable
    Match first() {
        return matches.isEmpty() ? null : matches.getFirst();
    }

    boolean isEmpty() {
        return matches.isEmpty();
    }
}
