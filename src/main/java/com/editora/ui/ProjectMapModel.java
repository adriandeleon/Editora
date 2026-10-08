package com.editora.ui;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import com.editora.editor.LanguageRegistry;
import com.editora.search.FuzzyMatch;

/**
 * Filesystem/model half of the Project Map. The JavaFX view asks for a bounded snapshot off the FX thread,
 * then performs hit-testing and painting without touching the filesystem. Expansion is explicit: only the
 * root and directories the user has opened are listed, so a large repository cannot turn one paint into a
 * project-wide walk.
 */
final class ProjectMapModel {

    /** Upper bound on the real (non-placeholder) rows one snapshot may hold, across every column. */
    static final int MAX_VISIBLE_ITEMS = 1_200;

    /** Rows one directory shows at first; each "+N more" activation raises that directory's limit by this. */
    static final int DIRECTORY_CHUNK = 300;

    /** File name of a placeholder row's synthetic path. Shown as-is by path-based chrome (breadcrumbs). */
    static final String PLACEHOLDER_NAME = "\u2026";

    /** What a non-file row stands for. */
    enum PlaceholderKind {
        /** The directory has {@link Placeholder#remaining()} more children than were loaded. */
        MORE,
        /** The directory was listed and has no (visible) children. */
        EMPTY,
        /** The directory could not be listed. */
        UNREADABLE
    }

    /** Marks an {@link Entry} as a row that is not a file or folder; {@code label} is its display text. */
    record Placeholder(PlaceholderKind kind, int remaining, String label) {}

    /** Supplies placeholder row text; the view passes localized strings, tests use {@link #PLAIN}. */
    interface Labels {
        Labels PLAIN = (kind, remaining) -> switch (kind) {
            case MORE -> "+" + remaining + " more\u2026";
            case EMPTY -> "Empty folder";
            case UNREADABLE -> "Cannot read this folder";
        };

        String label(PlaceholderKind kind, int remaining);
    }

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
            boolean symbolicLink,
            Placeholder placeholder) {
        Entry {
            path = normalize(path);
            parent = normalize(parent);
        }

        Entry(Path path, Path parent, int depth, boolean directory) {
            this(path, parent, depth, directory, -1, -1, false);
        }

        Entry(
                Path path,
                Path parent,
                int depth,
                boolean directory,
                long size,
                long modifiedMillis,
                boolean symbolicLink) {
            this(path, parent, depth, directory, size, modifiedMillis, symbolicLink, null);
        }

        /**
         * A "+N more" / "Empty folder" / "Cannot read this folder" row under {@code parent}. It reports itself
         * as a directory so file-only behaviour (open, preview, type filters) never applies to it; callers that
         * act on folders must check {@link #isPlaceholder()} first.
         */
        static Entry placeholder(Path parent, int depth, Placeholder placeholder) {
            return new Entry(parent.resolve(PLACEHOLDER_NAME), parent, depth, true, -1, -1, false, placeholder);
        }

        boolean isPlaceholder() {
            return placeholder != null;
        }

        boolean isMore() {
            return placeholder != null && placeholder.kind() == PlaceholderKind.MORE;
        }

        String name() {
            if (placeholder != null) {
                return placeholder.label();
            }
            Path fileName = path == null ? null : path.getFileName();
            return fileName == null ? String.valueOf(path) : fileName.toString();
        }
    }

    record Filters(
            String query,
            boolean open,
            boolean modified,
            boolean gitChanged,
            boolean bookmarked,
            boolean personalNotes,
            TypeFilter type) {
        Filters {
            query = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
            type = type == null ? TypeFilter.ALL : type;
        }

        boolean active() {
            return !query.isEmpty()
                    || open
                    || modified
                    || gitChanged
                    || bookmarked
                    || personalNotes
                    || type != TypeFilter.ALL;
        }
    }

    /** Stable identity for one column. Several independently expanded parents can own columns at one depth. */
    record ColumnId(int depth, Path parent) {
        ColumnId {
            parent = normalize(parent);
        }
    }

    /** One Miller-style column containing the children of exactly one parent directory. */
    record Column(int depth, Path parent, List<Entry> entries, int totalEntries) {
        Column {
            parent = normalize(parent);
            entries = entries == null ? List.of() : List.copyOf(entries);
        }

        ColumnId id() {
            return new ColumnId(depth, parent);
        }

        /** Real rows on show: the placeholder row is not counted. */
        int shownEntries() {
            int shown = 0;
            for (Entry entry : entries) {
                if (!entry.isPlaceholder()) {
                    shown++;
                }
            }
            return shown;
        }

        /** Header count: the directory's child count, or "shown/total" when filters or caps hide some. */
        String countLabel() {
            int shown = shownEntries();
            return shown == totalEntries ? String.valueOf(totalEntries) : shown + "/" + totalEntries;
        }
    }

    /**
     * One load. {@code expanded} are the directories to list; {@code hiddenOverrides} carries a column's own
     * "Show hidden" choice by directory, falling back to {@code showHidden}; {@code limits} raises a
     * directory's row limit above {@link #DIRECTORY_CHUNK}; {@code pinned} paths (search matches, a revealed
     * file) are loaded first, with the rows that lead to them, wherever they sort. {@code cancelled} is
     * polled between directories.
     */
    record Request(
            Path root,
            Set<Path> expanded,
            boolean showHidden,
            Map<Path, Boolean> hiddenOverrides,
            Map<Path, Integer> limits,
            List<Path> pinned,
            Labels labels,
            BooleanSupplier cancelled) {
        Request {
            root = normalize(root);
            Set<Path> normalizedExpanded = new HashSet<>();
            if (expanded != null) {
                for (Path path : expanded) {
                    if (path != null) {
                        normalizedExpanded.add(normalize(path));
                    }
                }
            }
            expanded = Set.copyOf(normalizedExpanded);
            hiddenOverrides = hiddenOverrides == null ? Map.of() : Map.copyOf(hiddenOverrides);
            limits = limits == null ? Map.of() : Map.copyOf(limits);
            pinned = pinned == null ? List.of() : List.copyOf(pinned);
            labels = labels == null ? Labels.PLAIN : labels;
            cancelled = cancelled == null ? () -> false : cancelled;
        }

        Request(Path root, Set<Path> expanded, boolean showHidden) {
            this(root, expanded, showHidden, Map.of(), Map.of(), List.of(), Labels.PLAIN, () -> false);
        }
    }

    /** What listing one expanded directory found: its child count, how many rows were loaded, and failure. */
    record DirectoryFacts(int total, int loaded, boolean unreadable) {
        boolean truncated() {
            return loaded < total;
        }

        boolean empty() {
            return total == 0 && !unreadable;
        }
    }

    /**
     * A bounded load. {@code directories} holds every expanded directory that was listed (and so may be drawn
     * expanded); {@code skipped} the expanded directories whose children did not fit {@link
     * #MAX_VISIBLE_ITEMS}; {@code capped} whether that overall limit dropped anything at all.
     */
    record Snapshot(
            List<Entry> entries,
            Map<Path, DirectoryFacts> directories,
            Set<Path> skipped,
            boolean capped,
            int pinnedRequested,
            int pinnedLoaded) {
        static final Snapshot EMPTY = new Snapshot(List.of(), Map.of(), Set.of(), false, 0, 0);

        Snapshot {
            entries = List.copyOf(entries);
            directories = Map.copyOf(directories);
            skipped = Set.copyOf(skipped);
        }

        Set<Path> loadedDirectories() {
            return directories.keySet();
        }
    }

    private ProjectMapModel() {}

    /**
     * Returns the root plus the children of every expanded directory, breadth-first and bounded, without the
     * placeholder rows of {@link #load(Request)}. Filesystem errors are local to one directory: the rest of
     * the already-visible map remains usable.
     */
    static List<Entry> loadVisible(Path root, Set<Path> expanded, boolean showHidden) {
        Snapshot snapshot = load(new Request(root, expanded, showHidden));
        return snapshot == null
                ? List.of()
                : snapshot.entries().stream()
                        .filter(entry -> !entry.isPlaceholder())
                        .toList();
    }

    /** One child as listed: its attributes are read once and reused for ordering and for the entry. */
    private record Item(Path path, boolean directory, long size, long modifiedMillis, boolean symbolicLink) {
        String name() {
            Path fileName = path.getFileName();
            return fileName == null ? path.toString() : fileName.toString();
        }
    }

    /**
     * Loads the root and the children of every expanded directory. Two limits bound it: each directory shows
     * at most its row limit ({@link #DIRECTORY_CHUNK} unless raised), and the whole snapshot at most
     * {@link #MAX_VISIBLE_ITEMS} real rows. Pinned paths are spent first so a search match or a revealed file
     * is present even when its siblings are not; the remaining budget is handed out breadth-first. Nothing is
     * dropped silently: a truncated directory ends in a "+N more" row, an empty or unreadable one holds a
     * single stub row, and a directory that got no rows at all is reported in {@link Snapshot#skipped()}
     * rather than listed as loaded.
     *
     * @return the snapshot, or {@code null} when {@code request.cancelled()} turned true part-way
     */
    static Snapshot load(Request request) {
        Path root = request.root();
        if (root == null) {
            return Snapshot.EMPTY;
        }
        Item rootItem = stat(root);
        if (rootItem == null || !rootItem.directory()) {
            return Snapshot.EMPTY;
        }
        Set<Path> expanded = request.expanded();
        int budget = MAX_VISIBLE_ITEMS - 1; // the root row
        boolean capped = false;

        // Pinned chains first: every row between the root and a pinned path, charged once each.
        Map<Path, Map<Path, Item>> pins = new HashMap<>();
        int pinnedRequested = 0;
        int pinnedLoaded = 0;
        for (Path requested : request.pinned()) {
            if (request.cancelled().getAsBoolean()) {
                return null;
            }
            Path pinned = normalize(requested);
            if (pinned == null || pinned.equals(root) || !pinned.startsWith(root)) {
                continue;
            }
            pinnedRequested++;
            List<Path> chain = new ArrayList<>();
            for (Path current = pinned; current != null && !current.equals(root); current = current.getParent()) {
                chain.add(current);
            }
            List<Item> fresh = new ArrayList<>();
            boolean reachable = true;
            for (Path element : chain.reversed()) {
                Path directory = element.getParent();
                if (!expanded.contains(directory)) {
                    reachable = false;
                    break;
                }
                Map<Path, Item> known = pins.get(directory);
                if (known != null && known.containsKey(element)) {
                    continue;
                }
                Item item = stat(element);
                if (item == null) {
                    reachable = false; // deleted since the search ran
                    break;
                }
                fresh.add(item);
            }
            if (!reachable) {
                continue;
            }
            if (fresh.size() > budget) {
                capped = true;
                continue;
            }
            budget -= fresh.size();
            for (Item item : fresh) {
                pins.computeIfAbsent(item.path().getParent(), ignored -> new LinkedHashMap<>())
                        .put(item.path(), item);
            }
            pinnedLoaded++;
        }

        record Pending(Path directory, int depth) {}
        Deque<Pending> queue = new ArrayDeque<>();
        List<Entry> result = new ArrayList<>();
        Map<Path, DirectoryFacts> directories = new HashMap<>();
        Set<Path> skipped = new HashSet<>();
        result.add(entry(rootItem, null, 0));
        if (expanded.contains(root)) {
            queue.add(new Pending(root, 0));
        }
        while (!queue.isEmpty()) {
            if (request.cancelled().getAsBoolean()) {
                return null;
            }
            Pending pending = queue.removeFirst();
            Path directory = pending.directory();
            int childDepth = pending.depth() + 1;
            Map<Path, Item> pinnedHere = pins.getOrDefault(directory, Map.of());
            boolean showHidden = request.hiddenOverrides().getOrDefault(directory, request.showHidden());
            List<Item> listed = listDirectory(directory, showHidden);
            if (listed == null && pinnedHere.isEmpty()) {
                directories.put(directory, new DirectoryFacts(0, 0, true));
                result.add(placeholder(directory, childDepth, PlaceholderKind.UNREADABLE, 0, request.labels()));
                continue;
            }
            List<Item> all = new ArrayList<>(listed == null ? List.of() : listed);
            if (!pinnedHere.isEmpty()) {
                Set<Path> present = new HashSet<>();
                all.forEach(item -> present.add(item.path()));
                for (Item item : pinnedHere.values()) {
                    if (!present.contains(item.path())) {
                        all.add(item); // a hidden row the search still matched, or one created a moment ago
                    }
                }
            }
            all.sort(ProjectPathOrder.directoriesFirst(Item::directory, Item::name));
            int total = all.size();
            if (total == 0) {
                directories.put(directory, new DirectoryFacts(0, 0, false));
                result.add(placeholder(directory, childDepth, PlaceholderKind.EMPTY, 0, request.labels()));
                continue;
            }
            int limit = Math.max(DIRECTORY_CHUNK, request.limits().getOrDefault(directory, DIRECTORY_CHUNK));
            int loaded = 0;
            for (int index = 0; index < total; index++) {
                Item item = all.get(index);
                if (!pinnedHere.containsKey(item.path())) {
                    if (index >= limit) {
                        continue;
                    }
                    if (budget <= 0) {
                        capped = true;
                        continue;
                    }
                    budget--;
                }
                loaded++;
                result.add(entry(item, directory, childDepth));
                if (item.directory() && expanded.contains(item.path())) {
                    queue.addLast(new Pending(item.path(), childDepth));
                }
            }
            if (loaded == 0) {
                skipped.add(directory); // no budget left for even one row: not loaded, so not drawn expanded
                continue;
            }
            directories.put(directory, new DirectoryFacts(total, loaded, false));
            if (loaded < total) {
                result.add(placeholder(directory, childDepth, PlaceholderKind.MORE, total - loaded, request.labels()));
            }
        }
        return new Snapshot(result, directories, skipped, capped, pinnedRequested, pinnedLoaded);
    }

    private static Entry entry(Item item, Path parent, int depth) {
        return new Entry(
                item.path(), parent, depth, item.directory(), item.size(), item.modifiedMillis(), item.symbolicLink());
    }

    private static Entry placeholder(Path parent, int depth, PlaceholderKind kind, int remaining, Labels labels) {
        return Entry.placeholder(parent, depth, new Placeholder(kind, remaining, labels.label(kind, remaining)));
    }

    /**
     * Toggles one directory independently. Opening a sibling retains existing branches; closing a directory
     * removes its own descendant columns without affecting its siblings.
     */
    static Set<Path> toggleExpansion(Path root, Set<Path> expanded, Path directory) {
        Path normalizedRoot = normalize(root);
        Path normalizedDirectory = normalize(directory);
        if (normalizedRoot == null || normalizedDirectory == null || !normalizedDirectory.startsWith(normalizedRoot)) {
            return expanded == null ? Set.of() : Set.copyOf(expanded);
        }
        Set<Path> result = new HashSet<>();
        if (expanded != null) {
            expanded.stream()
                    .map(ProjectMapModel::normalize)
                    .filter(java.util.Objects::nonNull)
                    .forEach(result::add);
        }
        if (result.contains(normalizedDirectory)) {
            result.removeIf(path -> path.startsWith(normalizedDirectory));
            return Set.copyOf(result);
        }
        result.add(normalizedDirectory);
        return Set.copyOf(result);
    }

    /** Returns the directories that must be expanded to reveal every matching file beneath {@code root}. */
    static Set<Path> expandedAncestors(Path root, List<Path> matches) {
        Path normalizedRoot = normalize(root);
        if (normalizedRoot == null || matches == null || matches.isEmpty()) {
            return Set.of();
        }
        Set<Path> result = new HashSet<>();
        for (Path match : matches) {
            Path normalizedMatch = normalize(match);
            if (normalizedMatch == null || !normalizedMatch.startsWith(normalizedRoot)) {
                continue;
            }
            for (Path parent = normalizedMatch.getParent(); parent != null && parent.startsWith(normalizedRoot); ) {
                result.add(parent);
                if (parent.equals(normalizedRoot)) {
                    break;
                }
                parent = parent.getParent();
            }
        }
        return Set.copyOf(result);
    }

    /**
     * Builds sorted depth columns and applies each column's local name filter. Descendants of a filtered-out
     * folder are omitted too, so the remaining geometry always represents a valid visible hierarchy.
     */
    static List<Column> columns(List<Entry> entries, Map<Integer, String> columnQueries) {
        return columns(entries, columnQueries, Map.of());
    }

    static List<Column> columns(
            List<Entry> entries, Map<Integer, String> columnQueries, Map<Integer, Boolean> showHiddenByDepth) {
        Map<ColumnId, String> queries = new HashMap<>();
        Map<ColumnId, Boolean> hidden = new HashMap<>();
        if (entries != null) {
            for (Entry entry : entries) {
                ColumnId id = new ColumnId(entry.depth(), entry.parent());
                if (columnQueries != null && columnQueries.containsKey(entry.depth())) {
                    queries.put(id, columnQueries.get(entry.depth()));
                }
                if (showHiddenByDepth != null && showHiddenByDepth.containsKey(entry.depth())) {
                    hidden.put(id, showHiddenByDepth.get(entry.depth()));
                }
            }
        }
        return columnsById(entries, queries, hidden);
    }

    static List<Column> columnsById(
            List<Entry> entries, Map<ColumnId, String> columnQueries, Map<ColumnId, Boolean> showHiddenByColumn) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        Map<ColumnId, List<Entry>> grouped = new LinkedHashMap<>();
        for (Entry entry : entries) {
            grouped.computeIfAbsent(new ColumnId(entry.depth(), entry.parent()), ignored -> new ArrayList<>())
                    .add(entry);
        }
        Set<Path> visible = new HashSet<>();
        List<Column> result = new ArrayList<>();
        for (var group : grouped.entrySet()) {
            ColumnId id = group.getKey();
            int depth = id.depth();
            // The placeholder row ("+N more", "Empty folder") always closes its column.
            List<Entry> all = group.getValue().stream()
                    .sorted(java.util.Comparator.comparing(Entry::isPlaceholder)
                            .thenComparing(ProjectPathOrder.directoriesFirst(Entry::directory, Entry::name)))
                    .toList();
            if (depth > 0 && id.parent() != null && !visible.contains(id.parent())) {
                continue;
            }
            String query = columnQueries == null ? "" : columnQueries.getOrDefault(id, "");
            String normalizedQuery = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
            boolean showHidden = showHiddenByColumn == null || showHiddenByColumn.getOrDefault(id, true);
            List<Entry> shown = all.stream()
                    .filter(entry -> depth == 0 || entry.parent() == null || visible.contains(entry.parent()))
                    .filter(entry ->
                            entry.isPlaceholder() || showHidden || !entry.name().startsWith("."))
                    .filter(entry -> entry.isPlaceholder()
                            || normalizedQuery.isEmpty()
                            || FuzzyMatch.of(entry.name(), normalizedQuery) != null)
                    .toList();
            shown.forEach(entry -> visible.add(entry.path()));
            // The directory's real child count: the loaded rows plus what a "+N more" row stands for.
            int total = 0;
            for (Entry entry : all) {
                total += entry.isPlaceholder() ? entry.placeholder().remaining() : 1;
            }
            result.add(new Column(depth, id.parent(), shown, total));
        }
        return List.copyOf(result);
    }

    /**
     * One attribute read per path; a symbolic link costs a second one to learn whether it leads to a folder.
     * {@code null} when the path cannot be read at all.
     */
    private static Item stat(Path path) {
        try {
            BasicFileAttributes attributes =
                    Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            boolean link = attributes.isSymbolicLink();
            return new Item(
                    path,
                    link ? Files.isDirectory(path) : attributes.isDirectory(),
                    attributes.isRegularFile() ? attributes.size() : -1,
                    attributes.lastModifiedTime().toMillis(),
                    link);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    static boolean matches(
            Entry entry,
            Filters filters,
            boolean open,
            boolean modified,
            boolean gitChanged,
            boolean bookmarked,
            boolean personalNotes) {
        if (entry == null || filters == null) {
            return false;
        }
        if (!filters.query().isEmpty() && FuzzyMatch.of(entry.name(), filters.query()) == null) {
            return false;
        }
        // Status chips are alternatives: "Open + Modified" means either useful working set, not the much
        // narrower intersection. Type and text remain AND constraints around that set.
        boolean hasStatusFilter = filters.open()
                || filters.modified()
                || filters.gitChanged()
                || filters.bookmarked()
                || filters.personalNotes();
        if (hasStatusFilter
                && !((filters.open() && open)
                        || (filters.modified() && modified)
                        || (filters.gitChanged() && gitChanged)
                        || (filters.bookmarked() && bookmarked)
                        || (filters.personalNotes() && personalNotes))) {
            return false;
        }
        return matchesType(entry, filters.type());
    }

    /** Matching nodes plus their ancestors stay prominent; unrelated nodes fade but retain map context. */
    static Set<Path> emphasized(List<Entry> entries, Set<Path> directMatches) {
        return emphasized(entries, directMatches, null);
    }

    /**
     * As {@link #emphasized(List, Set)}, but a match need not be a loaded row: with {@code root} given, a match
     * inside a collapsed folder still lights every ancestor up to the root, so a status chip shows which
     * collapsed folders hold its files. Path arithmetic only; nothing is read from disk.
     */
    static Set<Path> emphasized(List<Entry> entries, Set<Path> directMatches, Path root) {
        Set<Path> result = new HashSet<>();
        if (entries == null || directMatches == null || directMatches.isEmpty()) {
            return result;
        }
        Path normalizedRoot = normalize(root);
        Map<Path, Path> parents = new HashMap<>();
        for (Entry entry : entries) {
            parents.put(entry.path(), entry.parent());
        }
        for (Path match : directMatches) {
            for (Path current = normalize(match); current != null && result.add(current); ) {
                Path parent = parents.get(current);
                if (parent == null
                        && normalizedRoot != null
                        && !current.equals(normalizedRoot)
                        && current.startsWith(normalizedRoot)) {
                    parent = current.getParent(); // not a loaded row: climb the path itself
                }
                current = parent;
            }
        }
        return result;
    }

    /**
     * The directories between {@code root} (inclusive) and each of {@code paths}: the folders that contain
     * them. Paths outside the root are ignored.
     */
    static Set<Path> ancestorsWithin(Path root, java.util.Collection<Path> paths) {
        Path normalizedRoot = normalize(root);
        Set<Path> result = new HashSet<>();
        if (normalizedRoot == null || paths == null) {
            return result;
        }
        for (Path path : paths) {
            Path normalized = normalize(path);
            if (normalized == null || !normalized.startsWith(normalizedRoot)) {
                continue;
            }
            for (Path parent = normalized.getParent();
                    parent != null && parent.startsWith(normalizedRoot) && result.add(parent);
                    parent = parent.getParent()) {
                // stops at the first ancestor already recorded, or above the root
            }
        }
        return result;
    }

    /**
     * Moves every path at or under {@code from} to the same place under {@code to}; other paths are returned
     * unchanged. Used to carry map state (expansion, limits, selection) across an in-app rename or move.
     */
    static Path remap(Path path, Path from, Path to) {
        Path normalized = normalize(path);
        Path source = normalize(from);
        Path target = normalize(to);
        if (normalized == null || source == null || target == null || !normalized.startsWith(source)) {
            return normalized;
        }
        return normalize(target.resolve(source.relativize(normalized)));
    }

    /**
     * What survives of a manual expansion set after a load: the root, and every directory that loaded as a
     * folder row under a parent that itself survives. A folder that was renamed, deleted or replaced by a
     * file drops out with everything beneath it, so recreating the name later does not reopen old columns.
     * Folders beneath one that could not be read are kept as they were.
     */
    static Set<Path> pruneExpansion(Path root, Set<Path> expanded, Snapshot snapshot) {
        Path normalizedRoot = normalize(root);
        Set<Path> result = new HashSet<>();
        if (normalizedRoot == null || expanded == null || snapshot == null) {
            return result;
        }
        Set<Path> folders = new HashSet<>();
        for (Entry entry : snapshot.entries()) {
            if (entry.directory() && !entry.isPlaceholder()) {
                folders.add(entry.path());
            }
        }
        // Below a folder that could not be listed nothing is known, so nothing is forgotten: a dropped
        // connection must not cost the user every branch they had open.
        Set<Path> unknown = new HashSet<>();
        List<Path> byDepth = new ArrayList<>(expanded);
        byDepth.sort(java.util.Comparator.comparingInt(Path::getNameCount));
        for (Path path : byDepth) {
            Path parent = path.getParent();
            if (path.equals(normalizedRoot)) {
                result.add(path);
            } else if (parent == null || !result.contains(parent)) {
                continue;
            } else if (unknown.contains(parent) || unreadable(snapshot, parent)) {
                result.add(path);
                unknown.add(path);
            } else if (folders.contains(path)) {
                result.add(path);
            }
        }
        return result;
    }

    private static boolean unreadable(Snapshot snapshot, Path directory) {
        DirectoryFacts facts = snapshot.directories().get(directory);
        return facts != null && facts.unreadable();
    }

    /** Language ids the editor's registry reports that count as program source. */
    private static final Set<String> SOURCE_LANGUAGES = Set.of(
            "java",
            "javascript",
            "javascriptreact",
            "typescript",
            "typescriptreact",
            "python",
            "ruby",
            "go",
            "rust",
            "c",
            "cpp",
            "csharp",
            "kotlin",
            "groovy",
            "php",
            "shell",
            "powershell",
            "batchfile",
            "lua",
            "sql",
            "proto",
            "graphql",
            "http");

    private static final Set<String> MARKUP_LANGUAGES =
            Set.of("html", "astro", "markdown", "xml", "css", "mermaid", "markwhen", "typst", "dot", "plantuml");

    /** Plain data and text the registry knows but which is neither code, markup nor configuration. */
    private static final Set<String> OTHER_LANGUAGES = Set.of(LanguageRegistry.PLAINTEXT, "log", "csv", "diff");

    /** Extensions the registry has no language for yet, so the filter still files them sensibly. */
    private static final Set<String> EXTRA_SOURCE = Set.of(
            "scala", "swift", "dart", "m", "mm", "r", "pl", "pm", "ex", "exs", "erl", "hs", "clj", "cljs", "fs", "vb",
            "zig", "nim", "jl", "asm", "s", "fish", "vue", "svelte");

    private static final Set<String> EXTRA_MARKUP =
            Set.of("scss", "sass", "less", "adoc", "asciidoc", "rst", "tex", "latex", "org", "mdx", "textile");

    private static final Set<String> EXTRA_CONFIG = Set.of("env", "editorconfig", "cmake", "plist", "lock");

    /** Build and tool files whose language would otherwise file them as source or markup. */
    private static final Set<String> CONFIG_NAMES = Set.of(
            "pom.xml",
            "cmakelists.txt",
            "jenkinsfile",
            "build.gradle",
            "settings.gradle",
            "build.gradle.kts",
            "settings.gradle.kts",
            "gradle.properties");

    /**
     * The Type filter's bucket for a file name. It asks {@link LanguageRegistry} — the registry behind the
     * file icons and the editor's language detection — so a language added there is classified here too:
     * program languages are {@link TypeFilter#SOURCE}, document and style languages {@link TypeFilter#MARKUP},
     * and every other recognized language (data formats, unit files, the name-determined config files) is
     * {@link TypeFilter#CONFIG}. A short extension table covers common types the registry does not know.
     */
    static TypeFilter classify(String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (CONFIG_NAMES.contains(name)) {
            return TypeFilter.CONFIG;
        }
        String language = LanguageRegistry.forFileName(name);
        if (SOURCE_LANGUAGES.contains(language)) {
            return TypeFilter.SOURCE;
        }
        if (MARKUP_LANGUAGES.contains(language)) {
            return TypeFilter.MARKUP;
        }
        if (!OTHER_LANGUAGES.contains(language)) {
            return TypeFilter.CONFIG;
        }
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1);
        if (EXTRA_SOURCE.contains(extension)) {
            return TypeFilter.SOURCE;
        }
        if (EXTRA_MARKUP.contains(extension)) {
            return TypeFilter.MARKUP;
        }
        if (EXTRA_CONFIG.contains(extension)) {
            return TypeFilter.CONFIG;
        }
        return TypeFilter.OTHER;
    }

    static boolean matchesType(Entry entry, TypeFilter filter) {
        if (filter == null || filter == TypeFilter.ALL) {
            return true;
        }
        if (entry.directory()) {
            return false; // folders are retained as ancestor context for matching files
        }
        return classify(entry.name()) == filter;
    }

    /**
     * The children of {@code directory}, each with its attributes, or {@code null} when it cannot be listed.
     * One attribute read per child (two for a symbolic link) serves both the ordering and the row.
     */
    private static List<Item> listDirectory(Path directory, boolean showHidden) {
        List<Item> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path child : stream) {
                Path fileName = child.getFileName();
                if (!showHidden && fileName != null && fileName.toString().startsWith(".")) {
                    continue;
                }
                Item item = stat(child);
                result.add(item == null ? new Item(child, false, -1, -1, false) : item); // vanished mid-listing
            }
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
        return result;
    }

    static Path normalize(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }
}
