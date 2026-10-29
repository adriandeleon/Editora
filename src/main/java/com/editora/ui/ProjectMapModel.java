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

import com.editora.search.FuzzyMatch;
import com.editora.text.LanguageRegistry;
import org.jspecify.annotations.Nullable;

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

    /** The most single-child folders one expansion opens on its own ("Compact folder chains"). */
    static final int MAX_CHAIN = 32;

    /** What a non-file row stands for. */
    enum PlaceholderKind {
        /** The directory has {@link Placeholder#remaining()} more children than were loaded. */
        MORE,
        /** The directory was listed and has no (visible) children. */
        EMPTY,
        /** The directory could not be listed. */
        UNREADABLE,
        /**
         * The directory is open, but the overall {@link #MAX_VISIBLE_ITEMS} limit left no room for its rows. It
         * stays open and loads again once a column is closed; {@link Placeholder#remaining()} is its child count.
         */
        OVER_LIMIT,
        /** Listing the directory did not finish within the listing timeout (a stalled mount or connection). */
        NOT_RESPONDING
    }

    /** Marks an {@link Entry} as a row that is not a file or folder; {@code label} is its display text. */
    record Placeholder(PlaceholderKind kind, int remaining, String label) {}

    /** Supplies placeholder row text; the view passes localized strings, tests use {@link #PLAIN}. */
    interface Labels {
        Labels PLAIN = (kind, remaining) -> switch (kind) {
            case MORE -> "+" + remaining + " more\u2026";
            case EMPTY -> "Empty folder";
            case UNREADABLE -> "Cannot read this folder";
            case OVER_LIMIT -> "Not loaded \u2014 over the " + MAX_VISIBLE_ITEMS + "-row limit";
            case NOT_RESPONDING -> "Not responding";
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
            @Nullable Path parent,
            int depth,
            boolean directory,
            long size,
            long modifiedMillis,
            boolean symbolicLink,
            @Nullable Placeholder placeholder,
            @Nullable Symbol symbol) {
        Entry {
            path = normalize(path);
            parent = normalize(parent);
        }

        Entry(Path path, @Nullable Path parent, int depth, boolean directory) {
            this(path, parent, depth, directory, -1, -1, false);
        }

        Entry(
                Path path,
                @Nullable Path parent,
                int depth,
                boolean directory,
                long size,
                long modifiedMillis,
                boolean symbolicLink) {
            this(path, parent, depth, directory, size, modifiedMillis, symbolicLink, null, null);
        }

        /**
         * A "+N more" / "Empty folder" / "Cannot read this folder" row under {@code parent}. It reports itself
         * as a directory so file-only behaviour (open, preview, type filters) never applies to it; callers that
         * act on folders must check {@link #isPlaceholder()} first.
         */
        static Entry placeholder(Path parent, int depth, Placeholder placeholder) {
            return new Entry(parent.resolve(PLACEHOLDER_NAME), parent, depth, true, -1, -1, false, placeholder, null);
        }

        /**
         * A row of a file's structure column: one declaration of the file (or of the declaration that is
         * {@code parent}). Its path is made up — {@code symbol.file()} followed by one segment per declaration
         * down to this one (see {@link ProjectMapStructure}) — and never exists on disk. It is not a directory,
         * so no file-management action applies to it; callers that act on files must check {@link #isSymbol()}.
         */
        static Entry symbol(Path path, Path parent, int depth, Symbol symbol) {
            return new Entry(path, parent, depth, false, -1, -1, false, null, symbol);
        }

        boolean isPlaceholder() {
            return placeholder != null;
        }

        boolean isMore() {
            return placeholder != null && placeholder.kind() == PlaceholderKind.MORE;
        }

        boolean isSymbol() {
            return symbol != null;
        }

        /** A real file: not a folder, a placeholder row or a declaration. */
        boolean isFile() {
            return !directory && symbol == null;
        }

        String name() {
            if (placeholder != null) {
                return placeholder.label();
            }
            if (symbol != null) {
                return symbol.label();
            }
            Path fileName = path == null ? null : path.getFileName();
            return fileName == null ? String.valueOf(path) : fileName.toString();
        }
    }

    /**
     * One declaration of a file as a structure row shows it.
     *
     * @param file the file it is declared in
     * @param label what the Structure tool window calls it ({@code bar(int)}, a heading's text)
     * @param kind the Structure kind ({@code method}, {@code class}, {@code heading}…), or null
     * @param line the 0-based line it is declared on (where Open puts the caret)
     * @param startLine the 0-based first line of what its card shows: the block, with its doc comment and
     *     annotations
     * @param endLine the 0-based last line of what its card shows
     * @param block the fold block that Open Narrowed narrows the editor to, or null when it has none
     * @param branches whether it holds declarations of its own (it opens a column, like a folder)
     */
    record Symbol(
            Path file,
            String label,
            @Nullable String kind,
            int line,
            int startLine,
            int endLine,
            com.editora.text.FoldRegions.@Nullable Region block,
            boolean branches) {}

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
    record ColumnId(int depth, @Nullable Path parent) {
        ColumnId {
            parent = normalize(parent);
        }
    }

    /**
     * One Miller-style column containing the children of exactly one parent directory. {@code narrowed} counts
     * the rows the map-wide filters took out of it while the Map hides what does not match. {@code chain} is
     * empty for an ordinary column. For a compacted one ("Compact folder chains") it lists the folders the
     * column stands for, from the row it hangs from down to {@code parent}: {@code src}, {@code main}, …,
     * {@code acme} for the column of {@code src/main/java/com/acme}'s children. The one-row columns between
     * them are not drawn; paths and depths stay those of the real folders.
     */
    record Column(
            int depth, @Nullable Path parent, List<Entry> entries, int totalEntries, int narrowed, List<Path> chain) {
        Column {
            parent = normalize(parent);
            entries = entries == null ? List.of() : List.copyOf(entries);
            narrowed = Math.max(0, narrowed);
            chain = chain == null ? List.of() : List.copyOf(chain);
        }

        Column(int depth, @Nullable Path parent, List<Entry> entries, int totalEntries, int narrowed) {
            this(depth, parent, entries, totalEntries, narrowed, List.of());
        }

        Column(int depth, @Nullable Path parent, List<Entry> entries, int totalEntries) {
            this(depth, parent, entries, totalEntries, 0);
        }

        ColumnId id() {
            return new ColumnId(depth, parent);
        }

        /** Whether this column stands for a chain of single-child folders. */
        boolean compacted() {
            return !chain.isEmpty();
        }

        /** The row the column hangs from: its parent, or for a compacted column the first folder of the chain. */
        @Nullable
        Path anchor() {
            return chain.isEmpty() ? parent : chain.getFirst();
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
     * file) are loaded first, with the rows that lead to them, wherever they sort. {@code columnQueries} holds
     * a column's own filter by the directory it lists: in a folder longer than its row limit the rows that
     * filter matches are loaded too, so the filter searches the whole folder rather than the rows that
     * happened to load. {@code cancelled} is polled between directories (and while a listing is awaited).
     * With {@code compactChains} a listed folder (not the root) whose only child is a folder lists that child
     * too, as if it were expanded (not through a symbolic link, and at most {@link #MAX_CHAIN} folders deep): one
     * click on {@code src} opens {@code src/main/java/com/acme}, which the Map draws as one column.
     */
    record Request(
            Path root,
            Set<Path> expanded,
            boolean showHidden,
            Map<Path, Boolean> hiddenOverrides,
            Map<Path, Integer> limits,
            List<Path> pinned,
            Labels labels,
            BooleanSupplier cancelled,
            Map<Path, String> columnQueries,
            boolean compactChains) {
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
            Map<Path, String> queries = new HashMap<>();
            if (columnQueries != null) {
                columnQueries.forEach((directory, query) -> {
                    Path normalized = normalize(directory);
                    String value = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
                    if (normalized != null && !value.isEmpty()) {
                        queries.put(normalized, value);
                    }
                });
            }
            columnQueries = Map.copyOf(queries);
        }

        Request(
                Path root,
                Set<Path> expanded,
                boolean showHidden,
                Map<Path, Boolean> hiddenOverrides,
                Map<Path, Integer> limits,
                List<Path> pinned,
                Labels labels,
                BooleanSupplier cancelled) {
            this(root, expanded, showHidden, hiddenOverrides, limits, pinned, labels, cancelled, Map.of(), false);
        }

        Request(
                Path root,
                Set<Path> expanded,
                boolean showHidden,
                Map<Path, Boolean> hiddenOverrides,
                Map<Path, Integer> limits,
                List<Path> pinned,
                Labels labels,
                BooleanSupplier cancelled,
                Map<Path, String> columnQueries) {
            this(root, expanded, showHidden, hiddenOverrides, limits, pinned, labels, cancelled, columnQueries, false);
        }

        Request(
                Path root,
                Set<Path> expanded,
                boolean showHidden,
                Map<Path, Boolean> hiddenOverrides,
                Map<Path, Integer> limits,
                List<Path> pinned,
                Labels labels,
                BooleanSupplier cancelled,
                boolean compactChains) {
            this(
                    root,
                    expanded,
                    showHidden,
                    hiddenOverrides,
                    limits,
                    pinned,
                    labels,
                    cancelled,
                    Map.of(),
                    compactChains);
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
     * A bounded load. {@code directories} holds every expanded directory that was listed (and so is drawn
     * expanded); {@code skipped} the expanded directories whose children did not fit {@link
     * #MAX_VISIBLE_ITEMS} at all — still listed in {@code directories}, drawn open with an "over the limit"
     * stub row and kept open; {@code capped} whether that overall limit dropped anything at all.
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

    private static final java.util.Comparator<Item> ITEM_ORDER =
            ProjectPathOrder.directoriesFirst(Item::directory, Item::name);

    /** How long one directory listing may take before its folder is drawn as "Not responding". */
    static final long LISTING_TIMEOUT_MILLIS = 10_000;

    /**
     * One directory's listing as {@link Listings#list} answers it: the sorted children, or {@code null} when
     * the folder cannot be read; {@code timedOut} when the listing did not finish in time; {@code cancelled}
     * when the load was superseded while it waited.
     */
    private record Listed(@Nullable List<Item> items, boolean timedOut, boolean cancelled, boolean cached) {
        static final Listed CANCELLED = new Listed(null, false, true, false);
        static final Listed TIMED_OUT = new Listed(null, true, false, false);

        Listed(@Nullable List<Item> items, boolean timedOut, boolean cancelled) {
            this(items, timedOut, cancelled, false);
        }
    }

    /**
     * Where a view's loads get their directory listings. Two things keep a load from costing a full re-list
     * of every open folder per click:
     *
     * <ul>
     *   <li>A per-directory cache, used only for the folders the Project watcher follows ({@link
     *       #setWatched}): a watcher event for a folder ({@link #invalidate}), an in-app change or a
     *       focus-regain refresh ({@link #invalidateAll}) drops its entry, and a folder that stops being
     *       watched is dropped too. A remote root has no local watcher, so it keeps listing afresh.
     *   <li>A timeout per listing: the listing runs on a worker, and a folder that does not answer in time
     *       is reported as not responding instead of holding up the loader (one hung listing used to block
     *       every later load of the window). When it does answer, {@link #setOnRecovered} hears so.
     * </ul>
     *
     * Thread-safe: the loader lists, the FX thread invalidates and sets the watched set.
     */
    static final class Listings implements AutoCloseable {

        /** No cache, no worker: lists on the calling thread (pure loads and tests). */
        static final Listings DIRECT = new Listings(0, null);

        private record Key(Path directory, boolean showHidden) {}

        /** A listing on a worker, and when it began; {@code late} once some load gave up waiting for it. */
        private static final class InFlight {
            final java.util.concurrent.CompletableFuture<@Nullable List<Item>> future;
            final long startedNanos = System.nanoTime();
            volatile boolean late;

            InFlight(java.util.concurrent.CompletableFuture<@Nullable List<Item>> future) {
                this.future = future;
            }
        }

        private final long timeoutMillis;
        private final java.util.function.@Nullable Consumer<Path> beforeListForTest;
        private final Map<Key, List<Item>> cache = new java.util.concurrent.ConcurrentHashMap<>();
        private final Map<Key, InFlight> inFlight = new java.util.concurrent.ConcurrentHashMap<>();
        private final Map<Path, Long> versions = new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.concurrent.atomic.AtomicLong epoch = new java.util.concurrent.atomic.AtomicLong();
        private volatile Set<Path> watched = Set.of();
        private volatile Runnable onRecovered = () -> {};
        private java.util.concurrent.@Nullable ExecutorService workers;
        private volatile boolean closed;
        /** How many directories were actually listed (not answered from the cache); for tests. */
        final java.util.concurrent.atomic.AtomicInteger listedForTest = new java.util.concurrent.atomic.AtomicInteger();

        /**
         * @param timeoutMillis how long one listing may take; {@code <= 0} lists on the calling thread
         * @param beforeListForTest runs on the listing thread before each listing (a test stalls it there)
         */
        Listings(long timeoutMillis, java.util.function.@Nullable Consumer<Path> beforeListForTest) {
            this.timeoutMillis = timeoutMillis;
            this.beforeListForTest = beforeListForTest;
        }

        /** The folders the Project watcher follows now; only these are cached, the rest are dropped. */
        void setWatched(Set<Path> directories) {
            Set<Path> next = new HashSet<>();
            if (directories != null) {
                for (Path directory : directories) {
                    Path normalized = normalize(directory);
                    if (normalized != null) {
                        next.add(normalized);
                    }
                }
            }
            watched = Set.copyOf(next);
            cache.keySet().removeIf(key -> !next.contains(key.directory()));
        }

        /** Runs (on a worker thread) when a listing that had been reported as not responding finishes. */
        void setOnRecovered(Runnable action) {
            onRecovered = action == null ? () -> {} : action;
        }

        /** Something in these folders came or went, or changed: their next load lists them again. */
        void invalidate(java.util.Collection<Path> directories) {
            for (Path directory : directories) {
                Path normalized = normalize(directory);
                if (normalized == null) {
                    continue;
                }
                versions.merge(normalized, 1L, Long::sum);
                cache.remove(new Key(normalized, true));
                cache.remove(new Key(normalized, false));
            }
        }

        /** Anything may have changed (a focus regain, an in-app file operation, a new root). */
        void invalidateAll() {
            epoch.incrementAndGet();
            cache.clear();
        }

        boolean cached(Path directory, boolean showHidden) {
            Path normalized = normalize(directory);
            return normalized != null && cache.containsKey(new Key(normalized, showHidden));
        }

        private Listed list(Path directory, boolean showHidden, BooleanSupplier cancelled) {
            Key key = new Key(directory, showHidden);
            boolean cacheable = !closed && watched.contains(directory);
            if (cacheable) {
                List<Item> known = cache.get(key);
                if (known != null) {
                    return new Listed(known, false, false, true);
                }
            }
            long epochBefore = epoch.get();
            long versionBefore = versions.getOrDefault(directory, 0L);
            @Nullable List<Item> items;
            if (timeoutMillis <= 0) {
                items = listSorted(directory, showHidden);
            } else {
                Listed awaited = await(key, cancelled);
                if (awaited.cancelled() || awaited.timedOut()) {
                    return awaited;
                }
                items = awaited.items();
            }
            // Cached only when nothing invalidated the folder while it was being listed, and it was watched
            // from before the listing began: an event in between is then never lost.
            if (cacheable
                    && items != null
                    && epoch.get() == epochBefore
                    && versions.getOrDefault(directory, 0L) == versionBefore
                    && watched.contains(directory)) {
                cache.put(key, items);
            }
            return new Listed(items, false, false);
        }

        private Listed await(Key key, BooleanSupplier cancelled) {
            InFlight running = inFlight.get(key);
            if (running == null) {
                InFlight fresh = new InFlight(new java.util.concurrent.CompletableFuture<>());
                InFlight prior = inFlight.putIfAbsent(key, fresh);
                running = prior != null ? prior : fresh;
                if (prior == null) {
                    try {
                        start(key, fresh); // registered first, so finishing always removes it again
                    } catch (java.util.concurrent.RejectedExecutionException shutDown) {
                        inFlight.remove(key, fresh);
                        return Listed.CANCELLED;
                    }
                }
            }
            long deadline = running.startedNanos + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    running.late = true; // the folder's next load says "Not responding" without waiting again
                    return Listed.TIMED_OUT;
                }
                try {
                    @Nullable
                    List<Item> items = running.future.get(
                            Math.min(remaining, java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(100)),
                            java.util.concurrent.TimeUnit.NANOSECONDS);
                    return new Listed(items, false, false);
                } catch (java.util.concurrent.TimeoutException stillListing) {
                    if (cancelled.getAsBoolean()) {
                        return Listed.CANCELLED; // the listing goes on; the next load picks its answer up
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return Listed.CANCELLED;
                } catch (java.util.concurrent.ExecutionException | RuntimeException failed) {
                    return new Listed(null, false, false);
                }
            }
        }

        private void start(Key key, InFlight running) {
            java.util.concurrent.CompletableFuture<@Nullable List<Item>> future = running.future;
            workers().execute(() -> {
                try {
                    future.complete(listSorted(key.directory(), key.showHidden()));
                } catch (RuntimeException failed) {
                    ProjectMapLog.unexpected("listing a folder", failed);
                    future.complete(null);
                } finally {
                    inFlight.remove(key, running);
                    if (running.late && !closed) {
                        onRecovered.run();
                    }
                }
            });
        }

        private synchronized java.util.concurrent.ExecutorService workers() {
            if (closed) {
                throw new java.util.concurrent.RejectedExecutionException("closed");
            }
            if (workers == null) {
                workers = java.util.concurrent.Executors.newCachedThreadPool(runnable -> {
                    Thread thread = new Thread(runnable, "project-map-listing");
                    thread.setDaemon(true);
                    return thread;
                });
            }
            return workers;
        }

        private @Nullable List<Item> listSorted(Path directory, boolean showHidden) {
            if (beforeListForTest != null) {
                beforeListForTest.accept(directory);
            }
            listedForTest.incrementAndGet();
            List<Item> items = listDirectory(directory, showHidden);
            if (items == null) {
                return null;
            }
            items.sort(ITEM_ORDER);
            return List.copyOf(items);
        }

        @Override
        public synchronized void close() {
            closed = true;
            cache.clear();
            if (workers != null) {
                workers.shutdownNow(); // a hung listing's thread is a daemon; it is not waited for
            }
        }
    }

    /**
     * Loads the root and the children of every expanded directory. Two limits bound it: each directory shows
     * at most its row limit ({@link #DIRECTORY_CHUNK} unless raised), and the whole snapshot at most
     * {@link #MAX_VISIBLE_ITEMS} real rows. Pinned paths are spent first so a search match or a revealed file
     * is present even when its siblings are not; the remaining budget is handed out breadth-first. Nothing is
     * dropped silently: a truncated directory ends in a "+N more" row, an empty or unreadable one holds a
     * single stub row, and a directory that got no rows at all holds an "over the limit" stub and is reported
     * in {@link Snapshot#skipped()}.
     *
     * @return the snapshot, or {@code null} when {@code request.cancelled()} turned true part-way
     */
    static @Nullable Snapshot load(Request request) {
        return load(request, Listings.DIRECT);
    }

    /**
     * As {@link #load(Request)}, listing directories through {@code listings}: a view's cache of the folders
     * the Project watcher follows, and its listing timeout. A folder whose listing does not finish in time
     * holds a "Not responding" stub and the load moves on to the next.
     */
    static @Nullable Snapshot load(Request request, Listings listings) {
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

        record Pending(Path directory, int depth, int chain) {}
        Deque<Pending> queue = new ArrayDeque<>();
        List<Entry> result = new ArrayList<>();
        Map<Path, DirectoryFacts> directories = new HashMap<>();
        Set<Path> skipped = new HashSet<>();
        result.add(entry(rootItem, null, 0));
        if (expanded.contains(root)) {
            queue.add(new Pending(root, 0, 0));
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
            Listed listing = listings.list(directory, showHidden, request.cancelled());
            if (listing.cached() && missesKnownChild(listing.items(), directory, expanded, pinnedHere)) {
                // The watcher has not reported a child that is open or being revealed yet: a cached listing taken
                // before it appeared would drop it from the open set, so this folder is listed again.
                listings.invalidate(List.of(directory));
                listing = listings.list(directory, showHidden, request.cancelled());
            }
            if (listing.cancelled()) {
                return null;
            }
            if (listing.timedOut()) {
                // Nothing is known below it, so nothing beneath is forgotten (pruneExpansion keeps it).
                directories.put(directory, new DirectoryFacts(0, 0, true));
                result.add(placeholder(directory, childDepth, PlaceholderKind.NOT_RESPONDING, 0, request.labels()));
                continue;
            }
            List<Item> listed = listing.items();
            if (listed == null && pinnedHere.isEmpty()) {
                directories.put(directory, new DirectoryFacts(0, 0, true));
                result.add(placeholder(directory, childDepth, PlaceholderKind.UNREADABLE, 0, request.labels()));
                continue;
            }
            List<Item> all = listed == null ? new ArrayList<>() : listed; // sorted already
            if (!pinnedHere.isEmpty()) {
                all = new ArrayList<>(all);
                Set<Path> present = new HashSet<>();
                all.forEach(item -> present.add(item.path()));
                boolean added = false;
                for (Item item : pinnedHere.values()) {
                    if (!present.contains(item.path())) {
                        all.add(item); // a hidden row the search still matched, or one created a moment ago
                        added = true;
                    }
                }
                if (added) {
                    all.sort(ITEM_ORDER);
                }
            }
            String columnQuery = request.columnQueries().get(directory);
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
                    if (index >= limit && (columnQuery == null || FuzzyMatch.of(item.name(), columnQuery) == null)) {
                        continue; // beyond the row limit, and not a row the column's own filter is looking for
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
                    queue.addLast(new Pending(item.path(), childDepth, 0));
                } else if (item.directory()
                        && request.compactChains()
                        && total == 1
                        && !directory.equals(root)
                        && !item.symbolicLink()
                        && pending.chain() < MAX_CHAIN) {
                    // The folder's only child is a folder: it opens with it (a compacted column). Not the root's
                    // only child: the root's children are the first column, and that row must still close.
                    queue.addLast(new Pending(item.path(), childDepth, pending.chain() + 1));
                }
            }
            if (loaded == 0) {
                // No budget left for even one row. The folder stays open (the user opened it) with a stub that
                // says why it is empty; it fills again as soon as a column is closed and the budget allows.
                skipped.add(directory);
                directories.put(directory, new DirectoryFacts(total, 0, false));
                result.add(placeholder(directory, childDepth, PlaceholderKind.OVER_LIMIT, total, request.labels()));
                continue;
            }
            directories.put(directory, new DirectoryFacts(total, loaded, false));
            if (loaded < total) {
                result.add(placeholder(directory, childDepth, PlaceholderKind.MORE, total - loaded, request.labels()));
            }
        }
        return new Snapshot(result, directories, skipped, capped, pinnedRequested, pinnedLoaded);
    }

    private static Entry entry(Item item, @Nullable Path parent, int depth) {
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
    static Set<Path> toggleExpansion(@Nullable Path root, Set<Path> expanded, @Nullable Path directory) {
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
    static Set<Path> expandedAncestors(@Nullable Path root, List<Path> matches) {
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
        return columnsById(entries, columnQueries, showHiddenByColumn, null);
    }

    /**
     * As {@link #columnsById(List, Map, Map)}, and with {@code keep} given only those rows stay: the Map's
     * "Hide unmatched" mode, where a row the filters fade is taken out instead. A placeholder row ("+N more",
     * "Empty folder") stays, since it says something about the folder rather than being one of its files,
     * and a folder taken out takes its open columns with it, as a column's own filter does.
     */
    static List<Column> columnsById(
            List<Entry> entries,
            Map<ColumnId, String> columnQueries,
            Map<ColumnId, Boolean> showHiddenByColumn,
            @Nullable Set<Path> keep) {
        return columnsById(entries, columnQueries, showHiddenByColumn, keep, false);
    }

    /**
     * As {@link #columnsById(List, Map, Map, Set)}, and with {@code compactChains} a run of one-row columns
     * is folded into the column it leads to ("Compact folder chains"): a folder column whose only child is an
     * open folder — nothing filtered or capped away, no filter of its own — is left out, and the column it
     * opens records the run in {@link Column#chain()}. {@code src} › {@code main} › {@code java} › {@code com}
     * › {@code acme} becomes one column of {@code acme}'s children, hanging from the {@code src} row. The
     * root's children are always a column of their own.
     */
    static List<Column> columnsById(
            List<Entry> entries,
            Map<ColumnId, String> columnQueries,
            Map<ColumnId, Boolean> showHiddenByColumn,
            @Nullable Set<Path> keep,
            boolean compactChains) {
        List<Column> columns = columnsByIdUncompacted(entries, columnQueries, showHiddenByColumn, keep);
        return compactChains ? compactChains(columns, columnQueries) : columns;
    }

    private static List<Column> compactChains(List<Column> columns, @Nullable Map<ColumnId, String> columnQueries) {
        Set<Path> parents = new HashSet<>();
        for (Column column : columns) {
            if (column.parent() != null) {
                parents.add(column.parent());
            }
        }
        // Parents come before their children (the listing is breadth-first), so a run is carried down in order.
        Map<Path, List<Path>> runs = new HashMap<>();
        List<Column> result = new ArrayList<>(columns.size());
        for (Column column : columns) {
            Path parent = column.parent();
            List<Path> run = parent == null ? null : runs.remove(parent);
            Entry only = column.entries().size() == 1 ? column.entries().getFirst() : null;
            String query = columnQueries == null ? null : columnQueries.get(column.id());
            // The root's row and its children's column (the Map's first column) are never folded away.
            boolean folds = parent != null
                    && column.depth() > 1
                    && only != null
                    && only.directory()
                    && !only.isPlaceholder()
                    && !only.isSymbol()
                    && column.totalEntries() == 1
                    && column.narrowed() == 0
                    && (query == null || query.isBlank())
                    && parents.contains(only.path());
            if (folds && only != null) {
                List<Path> longer = new ArrayList<>(run == null ? List.of() : run);
                longer.add(parent);
                runs.put(only.path(), longer);
                continue;
            }
            if (run == null) {
                result.add(column);
            } else {
                List<Path> chain = new ArrayList<>(run);
                chain.add(parent);
                result.add(new Column(
                        column.depth(), parent, column.entries(), column.totalEntries(), column.narrowed(), chain));
            }
        }
        return List.copyOf(result);
    }

    private static List<Column> columnsByIdUncompacted(
            List<Entry> entries,
            Map<ColumnId, String> columnQueries,
            Map<ColumnId, Boolean> showHiddenByColumn,
            @Nullable Set<Path> keep) {
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
            // The placeholder row ("+N more", "Empty folder") always closes its column. A structure column
            // keeps the order its declarations are written in (the sort is stable).
            boolean structure = group.getValue().stream().anyMatch(Entry::isSymbol);
            java.util.Comparator<Entry> order = java.util.Comparator.comparing(Entry::isPlaceholder);
            List<Entry> all = group.getValue().stream()
                    .sorted(
                            structure
                                    ? order
                                    : order.thenComparing(
                                            ProjectPathOrder.directoriesFirst(Entry::directory, Entry::name)))
                    .toList();
            if (depth > 0 && id.parent() != null && !visible.contains(id.parent())) {
                continue;
            }
            String query = columnQueries == null ? "" : columnQueries.getOrDefault(id, "");
            String normalizedQuery = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
            boolean showHidden = showHiddenByColumn == null || showHiddenByColumn.getOrDefault(id, true);
            List<Entry> shown = all.stream()
                    .filter(entry -> depth == 0 || entry.parent() == null || visible.contains(entry.parent()))
                    .filter(entry -> entry.isPlaceholder()
                            || entry.isSymbol()
                            || showHidden
                            || !entry.name().startsWith("."))
                    .filter(entry -> entry.isPlaceholder()
                            || normalizedQuery.isEmpty()
                            || FuzzyMatch.of(entry.name(), normalizedQuery) != null)
                    .toList();
            int narrowed = 0;
            if (keep != null) {
                List<Entry> kept = new ArrayList<>(shown.size());
                for (Entry entry : shown) {
                    if (entry.isPlaceholder() || keep.contains(entry.path())) {
                        kept.add(entry);
                    } else {
                        narrowed++;
                    }
                }
                shown = kept;
            }
            shown.forEach(entry -> visible.add(entry.path()));
            // The directory's real child count: the loaded rows plus what a "+N more" row stands for.
            int total = 0;
            for (Entry entry : all) {
                Placeholder placeholder = entry.placeholder();
                total += placeholder != null ? placeholder.remaining() : 1; // entry.isPlaceholder()
            }
            result.add(new Column(depth, id.parent(), shown, total, narrowed));
        }
        return List.copyOf(result);
    }

    /**
     * One attribute read per path; a symbolic link costs a second one to learn whether it leads to a folder.
     * {@code null} when the path cannot be read at all.
     */
    private static @Nullable Item stat(Path path) {
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
    static Set<Path> emphasized(List<Entry> entries, Set<Path> directMatches, @Nullable Path root) {
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
    static @Nullable Path remap(Path path, Path from, Path to) {
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
     * Folders beneath one that could not be read, or that the overall row limit left unloaded, are kept as
     * they were.
     */
    static Set<Path> pruneExpansion(@Nullable Path root, Set<Path> expanded, Snapshot snapshot) {
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
            } else if (unknown.contains(parent)
                    || unreadable(snapshot, parent)
                    || snapshot.skipped().contains(parent)) {
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
            "scala",
            "clojure",
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
            "swift", "dart", "m", "mm", "r", "pl", "pm", "ex", "exs", "erl", "hs", "fs", "vb", "zig", "nim", "jl",
            "asm", "s", "fish", "vue", "svelte");

    private static final Set<String> EXTRA_MARKUP =
            Set.of("scss", "sass", "less", "adoc", "asciidoc", "rst", "tex", "latex", "org", "mdx", "textile");

    /**
     * Configuration extensions, checked before the registry: it may know the format ({@code .plist} is XML) and
     * would file it under the format's bucket instead.
     */
    private static final Set<String> EXTRA_CONFIG = Set.of("env", "editorconfig", "cmake", "plist", "lock");

    /** Build and tool files whose language would otherwise file them as source, markup or other. */
    private static final Set<String> CONFIG_NAMES = Set.of(
            "pom.xml",
            "cmakelists.txt",
            "jenkinsfile",
            "build.gradle",
            "settings.gradle",
            "build.gradle.kts",
            "settings.gradle.kts",
            "gradle.properties",
            "build.sbt",
            "build.mill",
            "deps.edn",
            "bb.edn",
            "project.clj",
            "go.mod",
            "go.sum",
            "go.work",
            "gemfile",
            "rakefile",
            "vagrantfile",
            "procfile",
            "codeowners",
            "requirements.txt",
            "mvnw",
            "mvnw.cmd",
            "gradlew",
            "gradlew.bat");

    /**
     * Extension-less project documents. Plain text the registry knows only as {@code plaintext}, which would
     * otherwise land in Other; {@code .txt} files are documents too.
     */
    private static final Set<String> DOCUMENT_NAMES =
            Set.of("readme", "license", "licence", "changelog", "notice", "authors", "copying");

    /**
     * The Type filter's bucket for a file name. It asks {@link LanguageRegistry} — the registry behind the
     * file icons and the editor's language detection — so a language added there is classified here too:
     * program languages are {@link TypeFilter#SOURCE}, document and style languages {@link TypeFilter#MARKUP},
     * and every other recognized language (data formats, unit files, the name-determined config files) is
     * {@link TypeFilter#CONFIG}. A short extension table covers common types the registry does not know.
     * Build and tool files ({@code CONFIG_NAMES}, {@code *.gradle}) and configuration extensions are pinned to
     * Config before the registry is asked, and plain-text documents ({@code .txt}, README, LICENSE…) are Markup.
     */
    static TypeFilter classify(String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1);
        if (CONFIG_NAMES.contains(name)
                || name.endsWith(".gradle")
                || name.endsWith(".gradle.kts")
                || EXTRA_CONFIG.contains(extension)) {
            return TypeFilter.CONFIG;
        }
        if (DOCUMENT_NAMES.contains(name) || extension.equals("txt")) {
            return TypeFilter.MARKUP; // plain-text documents: README, LICENSE, notes.txt
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
        if (EXTRA_SOURCE.contains(extension)) {
            return TypeFilter.SOURCE;
        }
        if (EXTRA_MARKUP.contains(extension)) {
            return TypeFilter.MARKUP;
        }
        return TypeFilter.OTHER;
    }

    static boolean matchesType(Entry entry, TypeFilter filter) {
        if (filter == null || filter == TypeFilter.ALL) {
            return true;
        }
        if (!entry.isFile()) {
            return false; // folders (and declarations) are retained as context for matching files
        }
        return classify(entry.name()) == filter;
    }

    /**
     * The children of {@code directory}, each with its attributes, or {@code null} when it cannot be listed.
     * One attribute read per child (two for a symbolic link) serves both the ordering and the row.
     */
    private static @Nullable List<Item> listDirectory(Path directory, boolean showHidden) {
        List<Item> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path child : stream) {
                Path fileName = child.getFileName();
                if (fileName != null && ProjectWatchChanges.isInternalName(fileName.toString())) {
                    continue; // a save's temporary sibling: gone in a moment, and its removal raises no event
                }
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

    /** Whether a cached listing lacks a folder the user keeps open below it, or a row a reveal pinned there. */
    private static boolean missesKnownChild(
            @Nullable List<Item> items, Path directory, Set<Path> expanded, Map<Path, Item> pinnedHere) {
        if (items == null) {
            return false;
        }
        Set<Path> present = new HashSet<>();
        items.forEach(item -> present.add(item.path()));
        for (Path open : expanded) {
            if (directory.equals(open.getParent()) && !present.contains(open)) {
                return true;
            }
        }
        for (Path pinned : pinnedHere.keySet()) {
            if (!present.contains(pinned)) {
                return true;
            }
        }
        return false;
    }

    static @Nullable Path normalize(@Nullable Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }
}
