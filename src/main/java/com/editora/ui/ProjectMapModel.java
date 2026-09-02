package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.editora.search.FuzzyMatch;

/**
 * Filesystem/model half of the Project Map. The JavaFX view asks for a bounded snapshot off the FX thread,
 * then performs hit-testing and painting without touching the filesystem. Expansion is explicit: only the
 * root and directories the user has opened are listed, so a large repository cannot turn one paint into a
 * project-wide walk.
 */
final class ProjectMapModel {

    static final int MAX_VISIBLE_ITEMS = 1_200;

    enum TypeFilter {
        ALL,
        SOURCE,
        MARKUP,
        CONFIG,
        OTHER
    }

    record Entry(
            Path path,
            Path parent,
            int depth,
            boolean directory,
            long size,
            long modifiedMillis,
            boolean symbolicLink) {
        Entry {
            path = normalize(path);
            parent = normalize(parent);
        }

        Entry(Path path, Path parent, int depth, boolean directory) {
            this(path, parent, depth, directory, -1, -1, false);
        }

        String name() {
            Path fileName = path == null ? null : path.getFileName();
            return fileName == null ? String.valueOf(path) : fileName.toString();
        }
    }

    record Filters(String query, boolean open, boolean modified, boolean gitChanged, TypeFilter type) {
        Filters {
            query = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
            type = type == null ? TypeFilter.ALL : type;
        }

        boolean active() {
            return !query.isEmpty() || open || modified || gitChanged || type != TypeFilter.ALL;
        }
    }

    private ProjectMapModel() {}

    /**
     * Returns the root plus the children of every expanded directory, breadth-first and bounded. Filesystem
     * errors are local to one directory: the rest of the already-visible map remains usable.
     */
    static List<Entry> loadVisible(Path root, Set<Path> expanded, boolean showHidden) {
        Path normalizedRoot = normalize(root);
        if (normalizedRoot == null || !Files.isDirectory(normalizedRoot)) {
            return List.of();
        }
        Set<Path> normalizedExpanded = new HashSet<>();
        if (expanded != null) {
            expanded.stream().map(ProjectMapModel::normalize).forEach(normalizedExpanded::add);
        }
        normalizedExpanded.add(normalizedRoot);

        record Pending(Path path, Path parent, int depth) {}
        Deque<Pending> queue = new ArrayDeque<>();
        queue.add(new Pending(normalizedRoot, null, 0));
        List<Entry> result = new ArrayList<>();
        while (!queue.isEmpty() && result.size() < MAX_VISIBLE_ITEMS) {
            Pending pending = queue.removeFirst();
            Entry entry = readEntry(pending.path(), pending.parent(), pending.depth());
            boolean directory = entry.directory();
            result.add(entry);
            if (!directory || !normalizedExpanded.contains(pending.path())) {
                continue;
            }
            for (Path child : listDirectory(pending.path(), showHidden)) {
                if (result.size() + queue.size() >= MAX_VISIBLE_ITEMS) {
                    break;
                }
                queue.addLast(new Pending(child, pending.path(), pending.depth() + 1));
            }
        }
        return List.copyOf(result);
    }

    private static Entry readEntry(Path path, Path parent, int depth) {
        try {
            BasicFileAttributes attributes =
                    Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return new Entry(
                    path,
                    parent,
                    depth,
                    attributes.isDirectory(),
                    attributes.isRegularFile() ? attributes.size() : -1,
                    attributes.lastModifiedTime().toMillis(),
                    attributes.isSymbolicLink());
        } catch (IOException | RuntimeException ignored) {
            return new Entry(path, parent, depth, Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS));
        }
    }

    static boolean matches(Entry entry, Filters filters, boolean open, boolean modified, boolean gitChanged) {
        if (entry == null || filters == null) {
            return false;
        }
        if (!filters.query().isEmpty() && FuzzyMatch.of(entry.name(), filters.query()) == null) {
            return false;
        }
        // Status chips are alternatives: "Open + Modified" means either useful working set, not the much
        // narrower intersection. Type and text remain AND constraints around that set.
        boolean hasStatusFilter = filters.open() || filters.modified() || filters.gitChanged();
        if (hasStatusFilter
                && !((filters.open() && open)
                        || (filters.modified() && modified)
                        || (filters.gitChanged() && gitChanged))) {
            return false;
        }
        return matchesType(entry, filters.type());
    }

    /** Matching nodes plus their ancestors stay prominent; unrelated nodes fade but retain map context. */
    static Set<Path> emphasized(List<Entry> entries, Set<Path> directMatches) {
        Set<Path> result = new HashSet<>();
        if (entries == null || directMatches == null || directMatches.isEmpty()) {
            return result;
        }
        java.util.Map<Path, Path> parents = new java.util.HashMap<>();
        for (Entry entry : entries) {
            parents.put(entry.path(), entry.parent());
        }
        for (Path match : directMatches) {
            for (Path current = normalize(match);
                    current != null && result.add(current);
                    current = parents.get(current)) {
                // walk the visible parent chain
            }
        }
        return result;
    }

    static boolean matchesType(Entry entry, TypeFilter filter) {
        if (filter == null || filter == TypeFilter.ALL) {
            return true;
        }
        if (entry.directory()) {
            return false; // folders are retained as ancestor context for matching files
        }
        String name = entry.name().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1);
        return switch (filter) {
            case ALL -> true;
            case SOURCE ->
                Set.of(
                                "java", "kt", "kts", "scala", "groovy", "js", "jsx", "ts", "tsx", "py", "rb", "rs",
                                "go", "c", "h", "cc", "cpp", "cs", "swift", "php", "sh")
                        .contains(ext);
            case MARKUP ->
                Set.of("md", "markdown", "html", "htm", "css", "scss", "less", "xml", "svg", "adoc")
                        .contains(ext);
            case CONFIG ->
                Set.of("json", "yaml", "yml", "toml", "properties", "ini", "conf", "gradle")
                                .contains(ext)
                        || Set.of("pom.xml", "dockerfile", "makefile", ".gitignore", ".editorconfig")
                                .contains(name);
            case OTHER ->
                !matchesType(entry, TypeFilter.SOURCE)
                        && !matchesType(entry, TypeFilter.MARKUP)
                        && !matchesType(entry, TypeFilter.CONFIG);
        };
    }

    private static List<Path> listDirectory(Path directory, boolean showHidden) {
        List<Path> result = new ArrayList<>();
        try (var stream = Files.list(directory)) {
            stream.filter(path -> showHidden || !path.getFileName().toString().startsWith("."))
                    .forEach(result::add);
        } catch (IOException | RuntimeException ignored) {
            return List.of();
        }
        Comparator<Path> order = Comparator.<Path, Boolean>comparing(
                        path -> !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                .thenComparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER);
        result.sort(order);
        return result;
    }

    static Path normalize(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }
}
