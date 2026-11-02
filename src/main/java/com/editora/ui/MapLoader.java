package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * What the Project Map lists, and the listing itself (Map refactor stage 2, review 3 D). It holds which folders
 * are open — by hand ({@link #expanded}), and on top of those for the current search (the matches' folders and
 * what was opened or closed by hand during it) — each folder's row limit, the paths kept loaded past those limits,
 * and what the last few roots had open; it builds the {@link ProjectMapModel.Request} from them, runs it on the
 * background {@link Executor} through its {@link ListingService}, and hands a load that is still current back on
 * the UI one. A newer request supersedes an older one by {@link #generation}: a superseded load is skipped while
 * queued, cancelled while listing, and dropped when it lands.
 *
 * <p>The view is its {@link Host}: the window's state, the surface's per-column settings, and where a load lands.
 * Everything here runs on the UI thread except the load itself. Both executors and the listing clock are given,
 * so a test drives superseded loads, races and renames step by step.
 */
final class MapLoader {

    /** Roots whose open folders are remembered for when the view returns to them. */
    static final int MAX_REMEMBERED_ROOTS = 8;
    /** Revealed paths kept loaded past their folder's row limit; the oldest go first. */
    static final int MAX_REVEAL_PINS = 64;

    /** What the loader needs of the view. Called on the UI thread. */
    interface Host {
        /** Whether the Map's window is showing: nothing is listed while it is not. */
        boolean active();

        /** Whether a query is active: its folders open on top of the manual ones. */
        boolean searching();

        /** The Project "show hidden files" setting, each column's default. */
        boolean showHidden();

        /** Columns whose "Show hidden" differs from the default, by folder. */
        Map<Path, Boolean> hiddenOverrides();

        /** Each column's own filter text, by folder. */
        Map<Path, String> columnFilterQueries();

        /** Whether "Compact folder chains" is on. */
        boolean compactChains();

        /** The folder whose column holds the selected row, or null. */
        @Nullable
        Path selectedColumn();

        /** A load began (the view shows "Loading…" if it lasts). */
        void loadStarted();

        /** No load is running any more (it landed, was dropped, or none could start). */
        void loadFinished();

        /** There is no root: the map is empty. */
        void cleared();

        /**
         * A current load landed: {@link #snapshot()} is it, the folders that did not load have left the manual
         * set ({@code expansionChanged}) and the limits and pins of folders that went are dropped.
         */
        void loaded(
                Path requestedRoot, ProjectMapModel.Snapshot loaded, Set<Path> loadedPaths, boolean expansionChanged);
    }

    /** What the view keeps of a folder it is leaving. Local folders only: their paths compare safely. */
    private record RootMemory(
            Set<Path> expanded,
            Map<Path, Integer> limits,
            @Nullable Path selected) {}

    /** After "+N more" brought rows: the folder, and how many rows it had loaded before. */
    record Raised(Path directory, int previousLoaded) {}

    private final Host host;
    private final Executor background;
    private final Executor ui;
    /** Directory listings: cached for the folders the Project watcher follows, each one with a timeout. */
    final ListingService listings;
    /** Bumped by every request: a load that is no longer the newest one is dropped. */
    final AtomicLong generation = new AtomicLong();
    /** How many queued loads actually began listing (the rest were superseded first). */
    final AtomicInteger loadsStartedForTest = new AtomicInteger();

    /** The folders the user opened by hand. A search never writes here, so clearing it restores this set. */
    final Set<Path> expanded = new HashSet<>();
    /** While a query is active: the ancestors of its opened matches, opened on top of {@link #expanded}. */
    private Set<Path> searchExpanded = Set.of();
    /** The matches whose folders the map opens (the best few), loaded before anything else. */
    private List<Path> searchMatches = List.of();
    /** Every match of the current answer, best first: those in an open folder stay loaded too. */
    private List<Path> searchAll = List.of();
    /** Folders opened / closed by hand while a query is active. Dropped with the query. */
    private final Set<Path> searchOpened = new HashSet<>();

    private final Set<Path> searchClosed = new HashSet<>();
    /** The folder match the search landed on: opened, on top of the ancestors of every match. */
    private @Nullable Path searchFocusFolder;
    /** A match stepped to beyond the opened ones: its folders are opened as well. */
    private @Nullable Path searchCurrent;
    /** Row limits raised by "+N more", by directory. */
    private final Map<Path, Integer> directoryLimits = new HashMap<>();
    /** Revealed paths that must stay loaded even when they sort beyond their directory's row limit. */
    private final Set<Path> revealPins = new LinkedHashSet<>();
    /** Expansion and selection of the folders this loader showed before, so returning to one restores them. */
    private final Map<String, RootMemory> rootMemory = new LinkedHashMap<>(16, 0.75f, true);

    private @Nullable Path root;
    private ProjectMapModel.Snapshot snapshot = ProjectMapModel.Snapshot.EMPTY;
    private boolean disposed;
    /** The folder whose rows "+N more" raised last; the load pays it before the shallower folders (A10). */
    private @Nullable Path lastRaised;
    /** The "+N more" waiting for its load, its folder's limit before, and the rows it had loaded then. */
    private @Nullable Path loadMoreDirectory;

    private int loadMorePreviousLimit;
    private int loadMorePreviousLoaded;

    /**
     * @param background where loads run (one thread: a later load waits behind the one listing)
     * @param ui where a finished load is handed back (the FX thread)
     * @param clockMillis the wall clock the listing cache stamps its listings with
     */
    MapLoader(Host host, Executor background, Executor ui, LongSupplier clockMillis) {
        this(host, background, ui, new ListingService(ListingService.LISTING_TIMEOUT_MILLIS, null, clockMillis));
    }

    /** As above, listing through {@code listings} (a test's direct one). */
    MapLoader(Host host, Executor background, Executor ui, ListingService listings) {
        this.host = host;
        this.background = background;
        this.ui = ui;
        this.listings = listings;
        // A folder that stopped answering and then answers is listed again by the next load.
        listings.setOnRecovered(() -> ui.execute(this::reload));
    }

    /**
     * Whether a root's columns list their whole folders (Map explore, position 15): local ones do, where a
     * listing is cheap and names are read without attributes; a remote root keeps its rows behind "+N more",
     * each costing a round trip. Tests of "+N more" on a local folder turn it off.
     */
    java.util.function.Predicate<Path> canvasWholeFolders = com.editora.vfs.Vfs::isLocal;

    private boolean canvasWholeFolders(Path root) {
        return canvasWholeFolders.test(root);
    }

    // --- the root ------------------------------------------------------------------------------------------

    @Nullable
    Path root() {
        return root;
    }

    /** The last load's rows and folders; empty before the first and after a root change. */
    ProjectMapModel.Snapshot snapshot() {
        return snapshot;
    }

    /**
     * Remembers what the root being left had open, with {@code selected}, for a return to it. In the window with
     * no project the root follows the active tab's folder, so every tab switch to another folder is a root
     * change; remembering the last few folders means switching back does not start that folder's map from
     * nothing. Local roots only: their paths compare safely.
     */
    void rememberRoot(@Nullable Path selected) {
        Path current = root;
        if (current == null || !com.editora.vfs.Vfs.isLocal(current)) {
            return;
        }
        rootMemory.put(current.toString(), new RootMemory(Set.copyOf(expanded), Map.copyOf(directoryLimits), selected));
        while (rootMemory.size() > MAX_REMEMBERED_ROOTS) {
            rootMemory.remove(rootMemory.keySet().iterator().next());
        }
    }

    /**
     * Starts over at {@code next}: nothing open but the root, no limits, pins or search folders, and what was
     * open there last time back. Nothing is loaded yet. The selection remembered for it, or null.
     */
    @Nullable
    Path setRoot(@Nullable Path next) {
        root = next;
        expanded.clear();
        clearSearch();
        directoryLimits.clear();
        revealPins.clear();
        snapshot = ProjectMapModel.Snapshot.EMPTY;
        loadMoreDirectory = null;
        if (next == null) {
            return null;
        }
        expanded.add(next);
        RootMemory memory = com.editora.vfs.Vfs.isLocal(next) ? rootMemory.get(next.toString()) : null;
        if (memory == null) {
            return null;
        }
        expanded.addAll(memory.expanded()); // pruned against the disk by the load that follows
        directoryLimits.putAll(memory.limits());
        Path selected = memory.selected();
        return selected != null && selected.startsWith(next) ? selected : null;
    }

    // --- loading -------------------------------------------------------------------------------------------

    /**
     * Lists what is open now, in the background, superseding any load before it. Nothing while the window is
     * hidden (showing it reloads) or after {@link #dispose}.
     */
    void reload() {
        if (disposed) {
            return; // a late callback after the window closed: the loader is gone
        }
        if (!host.active()) {
            return; // the Map window's next show reloads
        }
        long requested = generation.incrementAndGet();
        Path requestedRoot = root;
        if (requestedRoot == null) {
            host.loadFinished();
            snapshot = ProjectMapModel.Snapshot.EMPTY;
            host.cleared();
            return;
        }
        boolean searching = host.searching();
        List<Path> pinned = new ArrayList<>(searching ? searchMatches : List.<Path>of());
        if (searching && searchCurrent != null) {
            pinned.add(searchCurrent); // a match stepped to beyond the opened ones
        }
        Set<Path> openDirectories = expandedDirectories();
        if (searching) {
            pinned.addAll(searchPins(openDirectories)); // B16: "+N more" in a search hides no match
        }
        pinned.addAll(revealPins);
        ProjectMapModel.Request request = new ProjectMapModel.Request(
                requestedRoot,
                openDirectories,
                host.showHidden(),
                host.hiddenOverrides(),
                directoryLimits,
                pinned,
                searching ? MapLoader::searchPlaceholderLabel : MapLoader::placeholderLabel,
                () -> requested != generation.get(),
                host.columnFilterQueries(),
                // A search opens exactly the folders that lead to its matches: no chain opens on its own.
                host.compactChains() && !searching,
                priorityFolders(),
                canvasWholeFolders(requestedRoot));
        host.loadStarted();
        try {
            background.execute(() -> {
                if (requested != generation.get()) {
                    return; // superseded while queued: ten quick clicks list the folders once, not ten times
                }
                loadsStartedForTest.incrementAndGet();
                ProjectMapModel.Snapshot loaded;
                try {
                    loaded = ProjectMapModel.load(request, listings);
                } catch (RuntimeException unreadable) {
                    ProjectMapLog.unexpected("loading the map", unreadable);
                    // a closed SFTP file system throws unchecked; the map must still hear back
                    loaded = ProjectMapModel.Snapshot.EMPTY;
                }
                if (loaded == null) {
                    return; // cancelled part-way by a newer request
                }
                ProjectMapModel.Snapshot result = loaded;
                ui.execute(() -> apply(requested, requestedRoot, result));
            });
        } catch (RejectedExecutionException shutDown) {
            host.loadFinished();
        }
    }

    /** A load landed: dropped unless it is still the newest request; the view shows it otherwise. */
    private void apply(long requested, Path requestedRoot, ProjectMapModel.Snapshot loaded) {
        if (disposed || requested != generation.get()) {
            return;
        }
        host.loadFinished();
        snapshot = loaded;
        Set<Path> loadedPaths = new HashSet<>();
        for (ProjectMapModel.Entry entry : loaded.entries()) {
            loadedPaths.add(entry.path());
        }
        boolean expansionChanged = false;
        if (!loaded.entries().isEmpty()) {
            if (!host.searching()) {
                // Folders that did not load (renamed, deleted, replaced by a file) leave the manual set. One the
                // overall limit left without a single row stays: it is drawn open with an "over the limit" stub
                // and loads again when a column closes.
                Set<Path> kept = ProjectMapModel.pruneExpansion(root, expanded, loaded);
                if (!kept.equals(expanded)) {
                    expanded.clear();
                    expanded.addAll(kept);
                    expansionChanged = true;
                }
            }
            directoryLimits.keySet().retainAll(loaded.loadedDirectories());
        }
        revealPins.retainAll(loadedPaths);
        host.loaded(requestedRoot, loaded, loadedPaths, expansionChanged);
    }

    /** Anything on disk may have changed (an in-app file operation): every folder is listed again. */
    void refresh() {
        listings.invalidateAll();
        reload();
    }

    /**
     * Editora came back from another program (a real focus regain): each watched folder's cached listing is
     * kept when the folder's modification time did not move, which the load reads off the UI thread; only the
     * folders that changed, and the unwatched ones, are listed again (position 22 of Map review 3).
     */
    void revalidate() {
        listings.revalidateAll();
        reload();
    }

    /** The Project watcher saw entries come or go in {@code directories}: those are listed again, the rest kept. */
    void refresh(java.util.Collection<Path> directories) {
        listings.invalidate(directories);
        reload();
    }

    /**
     * Files in {@code directories} were rewritten (sizes and times changed, no entry came or went): their next
     * listing reads them again, but nothing is reloaded for it now.
     */
    void listingsChanged(java.util.Collection<Path> directories) {
        listings.invalidate(directories);
    }

    /** The folders the Project watcher follows now: only their listings may be reused between loads. */
    void setWatchedDirectories(Set<Path> directories) {
        listings.setWatched(directories);
    }

    /**
     * Carries the open folders, limits and pins across an in-app rename or move, so a renamed open folder stays
     * open, and lists the new tree. A load listing the old tree may be in flight: its snapshot would prune the
     * folders just remapped (they are not in it) and close them, so it must not land.
     */
    void pathRenamed(Path from, Path to) {
        remapAll(expanded, from, to);
        remapAll(searchOpened, from, to);
        remapAll(searchClosed, from, to);
        remapAll(revealPins, from, to);
        Map<Path, Integer> limits = new HashMap<>();
        directoryLimits.forEach((path, limit) -> limits.put(ProjectMapModel.remap(path, from, to), limit));
        directoryLimits.clear();
        directoryLimits.putAll(limits);
        listings.invalidateAll(); // the folders on both sides of the move are listed afresh
        if (host.active()) {
            reload();
        } else {
            generation.incrementAndGet();
            host.loadFinished();
        }
    }

    private static void remapAll(Set<Path> paths, Path from, Path to) {
        List<Path> moved = paths.stream()
                .map(path -> ProjectMapModel.remap(path, from, to))
                .toList();
        paths.clear();
        paths.addAll(moved);
    }

    /** Stops: nothing more is loaded or handed back, and the listing workers go. */
    void dispose() {
        disposed = true;
        generation.incrementAndGet();
        listings.close();
    }

    // --- what is open ----------------------------------------------------------------------------------------

    /**
     * The folders whose children the map lists now. Without a query that is the manual set. With one it is the
     * manual set plus the matches' ancestors, as adjusted by hand during the search: a folder closed there hides
     * its whole branch, and none of it reaches the manual set.
     */
    Set<Path> expandedDirectories() {
        Set<Path> result = new HashSet<>(expanded);
        if (host.searching()) {
            result.addAll(searchExpanded);
            result.addAll(searchOpened);
            if (searchFocusFolder != null) {
                result.add(searchFocusFolder); // the folder match the search landed on
            }
            if (searchCurrent != null) {
                result.addAll(ProjectMapModel.expandedAncestors(root, List.of(searchCurrent)));
            }
            if (!searchClosed.isEmpty()) {
                result.removeIf(path -> searchClosed.stream().anyMatch(path::startsWith));
            }
        }
        return Set.copyOf(result);
    }

    /**
     * Opens or closes one folder. During a search this edits the search's own view of the tree — closing a
     * folder the search opened really closes it — and never the manual set the search will hand back.
     */
    void toggle(Path path) {
        if (host.searching()) {
            if (expandedDirectories().contains(path)) {
                closeDuringSearch(path);
            } else {
                searchClosed.remove(path);
                searchOpened.add(path);
            }
        } else {
            Set<Path> nextExpansion = ProjectMapModel.toggleExpansion(root, expanded, path);
            expanded.clear();
            expanded.addAll(nextExpansion);
        }
    }

    /** Closes {@code folder} and everything open below it (during a search, in the search's own view). */
    void close(Path folder) {
        if (host.searching()) {
            closeDuringSearch(folder);
        } else {
            expanded.removeIf(path -> path.startsWith(folder));
        }
    }

    private void closeDuringSearch(Path directory) {
        searchOpened.removeIf(path -> path.startsWith(directory));
        searchClosed.removeIf(path -> path.startsWith(directory));
        searchClosed.add(directory);
    }

    /**
     * Collapse All and Close Other Branches (Map explore, position 13): closes every open folder that is not on
     * the way to {@code row}, so only the columns that lead to it stay. With {@code keepOwn} a folder row's own
     * column stays open too. The root's column always stays. Returns the topmost folder of each branch that
     * closed, so the caller can close what hangs from them (cards); empty: nothing changed.
     */
    List<Path> canvasCloseOthers(@Nullable Path row, boolean keepOwn) {
        Path current = root;
        if (current == null) {
            return List.of();
        }
        Path target = row != null && row.startsWith(current) ? row : current;
        Set<Path> off = new HashSet<>();
        for (Path open : expandedDirectories()) {
            boolean onPath = open.equals(current) || target.startsWith(open) && (keepOwn || !open.equals(target));
            if (!onPath) {
                off.add(open);
            }
        }
        List<Path> topmost = new ArrayList<>();
        for (Path folder : off) {
            Path parent = folder.getParent();
            boolean under = false;
            for (Path above = parent; above != null && !under; above = above.getParent()) {
                under = off.contains(above);
            }
            if (!under) {
                topmost.add(folder);
            }
        }
        topmost.sort(null);
        topmost.forEach(this::close);
        return List.copyOf(topmost);
    }

    /**
     * Opens the way to {@code target} ({@code chain}: its folders) and keeps it loaded past its folder's row
     * limit; every other open branch stays as it is.
     */
    void openTo(Set<Path> chain, Path target) {
        if (host.searching()) {
            searchClosed.removeIf(target::startsWith);
            searchOpened.addAll(chain);
        } else {
            expanded.addAll(chain);
        }
        pin(target);
    }

    /** Keeps {@code path} loaded past its folder's row limit; whether it was not pinned already. */
    boolean pin(Path path) {
        if (!revealPins.add(path)) {
            return false;
        }
        while (revealPins.size() > MAX_REVEAL_PINS) {
            revealPins.remove(revealPins.iterator().next());
        }
        return true;
    }

    /**
     * "Compact folder chains" went off: the folders such a chain opened stay open, so the columns stay where
     * they are. Whether that opened any. Not during a search, whose folders are its own.
     */
    boolean keepLoadedFoldersOpen() {
        return !host.searching() && expanded.addAll(snapshot.loadedDirectories());
    }

    /** Whether every open folder is {@code folder} or one of the folders that lead to it. */
    boolean onlyAncestorsOpen(Path folder) {
        for (Path open : expandedDirectories()) {
            if (!folder.startsWith(open)) {
                return false;
            }
        }
        return true;
    }

    // --- row limits ------------------------------------------------------------------------------------------

    /**
     * "+N more": raises one truncated directory's row limit by a chunk (the overall limit still applies). False
     * for no directory; the caller reloads otherwise.
     */
    boolean loadMore(@Nullable Path directory) {
        Path path = ProjectMapModel.normalize(directory);
        if (path == null) {
            return false;
        }
        ProjectMapModel.DirectoryFacts facts = snapshot.directories().get(path);
        loadMorePreviousLimit = directoryLimits.getOrDefault(path, ProjectMapModel.DIRECTORY_CHUNK);
        loadMorePreviousLoaded = facts == null ? 0 : facts.loaded();
        loadMoreDirectory = path;
        lastRaised = path;
        directoryLimits.put(
                path, Math.max(loadMorePreviousLimit, loadMorePreviousLoaded) + ProjectMapModel.DIRECTORY_CHUNK);
        return true;
    }

    /**
     * After the load that followed "+N more": the folder and its earlier row count when rows arrived, or null —
     * nothing was raised, or the overall limit gave it none, and then the raise is undone.
     */
    @Nullable
    Raised finishLoadMore(ProjectMapModel.Snapshot loaded) {
        Path directory = loadMoreDirectory;
        loadMoreDirectory = null;
        if (directory == null) {
            return null;
        }
        ProjectMapModel.DirectoryFacts facts = loaded.directories().get(directory);
        if (facts == null || facts.loaded() <= loadMorePreviousLoaded) {
            directoryLimits.put(directory, loadMorePreviousLimit);
            return null;
        }
        return new Raised(directory, loadMorePreviousLoaded);
    }

    /** The folder whose rows "+N more" raised last (forgotten once a load finds its raise gone); else null. */
    @Nullable
    Path lastRaised() {
        return lastRaised;
    }

    /**
     * A column's own filter changed. In a folder with more rows than its limit loads, the filter has to see the
     * rows that did not load as well, so the folder is listed again with the filter (B8); the listing cache
     * makes that cheap. A folder that loaded whole is filtered where it is.
     */
    void columnFilterChanged(@Nullable Path directory) {
        Path path = ProjectMapModel.normalize(directory);
        ProjectMapModel.DirectoryFacts facts =
                path == null ? null : snapshot.directories().get(path);
        if (facts == null) {
            return;
        }
        int limit = Math.max(
                ProjectMapModel.DIRECTORY_CHUNK, directoryLimits.getOrDefault(path, ProjectMapModel.DIRECTORY_CHUNK));
        Path current = root;
        boolean whole = current != null && canvasWholeFolders(current);
        // A folder that loaded whole is filtered where it is, however long (position 15).
        if (facts.truncated() || facts.loaded() > limit && !whole) {
            reload();
        }
    }

    /**
     * The open folders a load pays first when the overall limit is near: the one the selection is in, then the
     * one whose rows were raised last. The folder being read keeps its rows when another column opens.
     */
    private List<Path> priorityFolders() {
        List<Path> first = new ArrayList<>(2);
        Path selectedColumn = host.selectedColumn();
        if (selectedColumn != null) {
            first.add(selectedColumn);
        }
        Path raised = lastRaised;
        if (raised != null && directoryLimits.containsKey(raised) && !raised.equals(selectedColumn)) {
            first.add(raised);
        } else if (raised != null && !directoryLimits.containsKey(raised)) {
            lastRaised = null; // its column closed, or its raise was undone
        }
        return first;
    }

    static String placeholderLabel(ProjectMapModel.PlaceholderKind kind, int remaining) {
        return switch (kind) {
            case MORE -> tr("project.map.row.more", remaining);
            case EMPTY -> tr("project.map.row.emptyFolder");
            case UNREADABLE -> tr("project.map.row.unreadableFolder");
            case OVER_LIMIT -> tr("project.map.row.overLimit", ProjectMapModel.MAX_VISIBLE_ITEMS);
            case NOT_RESPONDING -> tr("project.map.row.notResponding");
        };
    }

    /**
     * During a search, a folder's "+N more" row stands for siblings that do not match: every match in an open
     * folder is pinned (see {@link #searchPins}), so none is behind it (B16).
     */
    static String searchPlaceholderLabel(ProjectMapModel.PlaceholderKind kind, int remaining) {
        return kind == ProjectMapModel.PlaceholderKind.MORE
                ? tr("project.map.row.moreNotMatching", remaining)
                : placeholderLabel(kind, remaining);
    }

    // --- the search's folders --------------------------------------------------------------------------------

    /** Drops the search's folders (the query was cleared, or the root changed). */
    void clearSearch() {
        searchExpanded = Set.of();
        searchMatches = List.of();
        searchAll = List.of();
        searchFocusFolder = null;
        searchCurrent = null;
        searchOpened.clear();
        searchClosed.clear();
    }

    /** Drops what was opened or closed by hand during the search: it belonged to the previous query's results. */
    void clearSearchEdits() {
        searchOpened.clear();
        searchClosed.clear();
    }

    /** Whether the search opened or closed anything (so clearing it has folders to give back). */
    boolean hasSearchFolders() {
        return !searchExpanded.isEmpty() || !searchOpened.isEmpty() || !searchClosed.isEmpty();
    }

    /**
     * A search's answer: {@code all} its matches, best first; {@code opened} the best few whose folders the map
     * opens; {@code focus} the folder match it lands on, if any. A new query drops the match stepped to. Whether
     * the folders to open changed (the caller reloads then).
     */
    boolean setSearchMatches(List<Path> all, List<Path> opened, @Nullable Path focus, boolean newQuery) {
        Set<Path> nextExpansion = ProjectMapModel.expandedAncestors(root, opened);
        boolean changed = !searchExpanded.equals(nextExpansion)
                || !searchMatches.equals(opened)
                || !Objects.equals(searchFocusFolder, focus);
        searchExpanded = nextExpansion;
        searchMatches = List.copyOf(opened);
        searchAll = List.copyOf(all);
        searchFocusFolder = focus;
        if (newQuery || searchCurrent != null && !all.contains(searchCurrent)) {
            searchCurrent = null;
        }
        return changed;
    }

    /** The matches whose folders the map opens, best first. */
    List<Path> openedMatches() {
        return searchMatches;
    }

    @Nullable
    Path searchFocusFolder() {
        return searchFocusFolder;
    }

    /**
     * Steps to the match at {@code target} ({@code folder}: a folder match, which is opened): a match past the
     * opened ones gets its own folders opened. Whether what is open changed (the caller reloads then).
     */
    boolean showMatch(Path target, boolean folder) {
        boolean refocus = folder && !target.equals(searchFocusFolder);
        if (refocus) {
            searchFocusFolder = target;
        }
        Path beyond = searchMatches.contains(target) ? null : target;
        if (!Objects.equals(beyond, searchCurrent)) {
            searchCurrent = beyond; // past the opened ones: its own folders open for it
            refocus = true;
        }
        return refocus;
    }

    /**
     * The query is being cleared with the selection on {@code row} (a file or folder under {@code currentRoot}):
     * its folders, and the ones opened by hand during the search, become ordinary open folders, and a match stays
     * loaded past its folder's limit (Map review 3 B2). Whether anything changed. Call before {@link #clearSearch}.
     */
    boolean keepSearchPlace(Path currentRoot, Path row) {
        Set<Path> promoted = new HashSet<>(searchOpened);
        promoted.addAll(ProjectMapModel.ancestorsWithin(currentRoot, List.of(row)));
        if (row.equals(searchFocusFolder)) {
            promoted.add(row); // the folder match the search opened, and the user is still on it
        }
        promoted.removeIf(path -> !path.startsWith(currentRoot) || searchClosed.contains(path));
        boolean changed = expanded.addAll(promoted);
        boolean match = row.equals(searchCurrent) || searchAll.contains(row);
        if (match && pin(row)) {
            changed = true; // a match, pinned past its folder's row limit by the search, stays loaded
        }
        return changed;
    }

    /** The matches past the opened ones whose folder is open anyway: kept loaded, so "+N more" hides none. */
    private List<Path> searchPins(Set<Path> open) {
        List<Path> pins = new ArrayList<>();
        for (Path path : searchAll) {
            Path parent = path.getParent();
            if (parent != null && (open.contains(parent) || parent.equals(root)) && !searchMatches.contains(path)) {
                pins.add(path);
            }
        }
        return pins;
    }
}
