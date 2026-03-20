package org.adriandeleon.editora.session;

import org.adriandeleon.editora.persistence.EditoraPersistence;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class BookmarkManager {
    private static final String SCHEMA_VERSION_KEY = "schemaVersion";
    private static final String BOOKMARKS_KEY = "bookmarks";
    private static final String FILE_KEY = "file";
    private static final String LINES_KEY = "lines";

    private BookmarkManager() {
    }

    public static Map<Path, List<Integer>> loadBookmarks() {
        Optional<Map<String, Object>> storedBookmarks = EditoraPersistence.readJsonObject(EditoraPersistence.bookmarksFile());
        return storedBookmarks.map(BookmarkManager::bookmarksFromJson)
                .orElseGet(Map::of);
    }

    public static void saveBookmarks(Map<Path, ? extends Collection<Integer>> bookmarksByFile) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(SCHEMA_VERSION_KEY, EditoraPersistence.SCHEMA_VERSION);
        values.put(BOOKMARKS_KEY, encodeBookmarks(bookmarksByFile));
        EditoraPersistence.writeJsonObject(EditoraPersistence.bookmarksFile(), values);
    }

    public static Path bookmarksFile() {
        return EditoraPersistence.bookmarksFile();
    }

    private static Map<Path, List<Integer>> bookmarksFromJson(Map<String, Object> values) {
        Object rawBookmarks = values.get(BOOKMARKS_KEY);
        if (!(rawBookmarks instanceof List<?> list)) {
            return Map.of();
        }

        LinkedHashMap<Path, LinkedHashSet<Integer>> bookmarksByFile = new LinkedHashMap<>();
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> entryMap)) {
                continue;
            }
            Object rawFileValue = entryMap.get(FILE_KEY);
            String rawFile = rawFileValue == null ? "" : String.valueOf(rawFileValue).trim();
            if (rawFile.isBlank()) {
                continue;
            }
            Path file = Path.of(rawFile).toAbsolutePath().normalize();
            List<Integer> lines = normalizeLines(entryMap.get(LINES_KEY));
            if (lines.isEmpty()) {
                continue;
            }
            bookmarksByFile.computeIfAbsent(file, ignored -> new LinkedHashSet<>()).addAll(lines);
        }

        LinkedHashMap<Path, List<Integer>> normalized = new LinkedHashMap<>();
        bookmarksByFile.forEach((file, lines) -> normalized.put(file, List.copyOf(lines)));
        return Map.copyOf(normalized);
    }

    private static List<Map<String, Object>> encodeBookmarks(Map<Path, ? extends Collection<Integer>> bookmarksByFile) {
        if (bookmarksByFile == null || bookmarksByFile.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> encoded = new ArrayList<>();
        bookmarksByFile.forEach((file, lines) -> {
            if (file == null) {
                return;
            }
            List<Integer> normalizedLines = normalizeLines(lines);
            if (normalizedLines.isEmpty()) {
                return;
            }
            Map<String, Object> bookmark = new LinkedHashMap<>();
            bookmark.put(FILE_KEY, file.toAbsolutePath().normalize().toString());
            bookmark.put(LINES_KEY, normalizedLines);
            encoded.add(bookmark);
        });
        return List.copyOf(encoded);
    }

    private static List<Integer> normalizeLines(Object rawLines) {
        if (!(rawLines instanceof Collection<?> collection)) {
            return List.of();
        }
        LinkedHashSet<Integer> normalized = new LinkedHashSet<>();
        for (Object rawLine : collection) {
            Integer parsed = parseLine(rawLine);
            if (parsed != null && parsed >= 0) {
                normalized.add(parsed);
            }
        }
        return List.copyOf(normalized);
    }

    private static List<Integer> normalizeLines(Collection<Integer> lines) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Integer> normalized = new LinkedHashSet<>();
        for (Integer line : lines) {
            if (line != null && line >= 0) {
                normalized.add(line);
            }
        }
        return List.copyOf(normalized);
    }

    private static Integer parseLine(Object rawLine) {
        if (rawLine instanceof Number number) {
            return number.intValue();
        }
        if (rawLine instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}


