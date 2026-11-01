package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.editora.search.ProjectFileList;
import org.jspecify.annotations.Nullable;

/**
 * The {@link ProjectIndex} over {@link ProjectFileList}: the file lists of the roots in use (the Project's, and the
 * Map's when it shows another folder), listed on first use on the index's own thread and kept current there from
 * what the Project panel reports — each path an in-app operation or a watch event touched ({@link #changed}), and a
 * focus regain or lost events ({@link #markStale}). A list marked out of date keeps answering while it is listed
 * again in the background; {@code onRelisted} hears when that changed it.
 *
 * <p>Thread-safe. The lists are touched on the index's thread; {@link #searchListed} reads the one held.
 */
final class ProjectFileIndex implements ProjectIndex {

    /** At most this many roots keep a list: the project's and one other the Map shows. */
    static final int MAX_LISTS = 2;

    private final ExecutorService executor;
    private final Executor ui;
    private final Supplier<ProjectFileList.Settings> settings;
    private final BooleanSupplier closed;
    private final Consumer<Path> onRelisted;
    /** The lists held, the most recently used last. Guarded by {@code this}. */
    private final List<ProjectFileList> lists = new ArrayList<>();

    /**
     * @param executor the thread lists are made and changed on (the Project panel's search thread)
     * @param ui where {@link #whenListed} callbacks run (the FX thread)
     * @param settings the Project settings that shape a list, read at each use
     * @param closed whether the owner went away: listing stops
     * @param onRelisted told (on the index's thread) the root whose stale list was listed again and changed
     */
    ProjectFileIndex(
            ExecutorService executor,
            Executor ui,
            Supplier<ProjectFileList.Settings> settings,
            BooleanSupplier closed,
            Consumer<Path> onRelisted) {
        this.executor = executor;
        this.ui = ui;
        this.settings = settings;
        this.closed = closed;
        this.onRelisted = onRelisted;
    }

    // --- ProjectIndex ----------------------------------------------------------------------------------------

    @Override
    public @Nullable ProjectMapSearchResult search(
            Path root, String query, Kinds kinds, int max, Freshness freshness, BooleanSupplier cancelled) {
        ProjectFileList list = list(root);
        if (list == null || cancelled.getAsBoolean()) {
            return null;
        }
        if (list.stale() && freshness == Freshness.CURRENT) {
            ProjectFileList fresh = relist(list);
            list = fresh == null ? list : fresh;
        }
        ProjectMapSearchResult found = rank(root, list, query, kinds, max, cancelled);
        if (list.stale()) {
            relistLater(list); // answered from the list as it was; listed again once this answer is out
        }
        return found;
    }

    @Override
    public ProjectMapSearchResult searchListed(Path root, String query, Kinds kinds, int max) {
        ProjectFileList list = held(root);
        if (list == null) {
            return new ProjectMapSearchResult(query, List.of(), 0, false);
        }
        if (list.stale()) {
            relistLater(list);
        }
        ProjectMapSearchResult found = rank(root, list, query, kinds, max, () -> false);
        return found == null ? new ProjectMapSearchResult(query, List.of(), 0, list.truncated()) : found;
    }

    @Override
    public void whenListed(Path root, Runnable then) {
        if (held(root) != null) {
            then.run();
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    list(root);
                } catch (RuntimeException failed) {
                    ProjectMapLog.unexpected("listing the project's files", failed);
                } finally {
                    ui.execute(then);
                }
            });
        } catch (RejectedExecutionException shutDown) {
            then.run();
        }
    }

    @Override
    public ProjectFileList.@Nullable Snapshot snapshot(Path root) {
        ProjectFileList list = list(root);
        return list == null ? null : list.snapshot();
    }

    // --- the lists -------------------------------------------------------------------------------------------

    /**
     * The list of {@code root} for the settings of now, listed on this thread when there is none (or the settings
     * that shape it changed). Blocking. Null when the owner went away meanwhile.
     */
    @Nullable
    ProjectFileList list(Path root) {
        ProjectFileList.Settings now = settings.get();
        ProjectFileList known = held(root, now);
        if (known != null) {
            return known;
        }
        ProjectFileList built = ProjectFileList.build(root, now, closed);
        if (built != null) {
            keep(built, null);
        }
        return built;
    }

    /** The list held for {@code root} with the settings of now, or null; never lists. */
    @Nullable
    ProjectFileList held(Path root) {
        return held(root, settings.get());
    }

    private synchronized @Nullable ProjectFileList held(Path root, ProjectFileList.Settings now) {
        for (int index = lists.size() - 1; index >= 0; index--) {
            ProjectFileList list = lists.get(index);
            if (list.isFor(root, now)) {
                lists.remove(index);
                lists.add(list); // the most recently used last
                return list;
            }
        }
        return null;
    }

    /** The list most recently held for {@code root}, whatever its settings (tests); null for none. */
    synchronized @Nullable ProjectFileList heldForTest(Path root) {
        Path normalized = root.toAbsolutePath().normalize();
        for (int index = lists.size() - 1; index >= 0; index--) {
            if (lists.get(index).root().equals(normalized)) {
                return lists.get(index);
            }
        }
        return null;
    }

    /** Holds {@code list} in place of {@code replacing} (or of an older list of its root), dropping the oldest. */
    private synchronized void keep(ProjectFileList list, @Nullable ProjectFileList replacing) {
        lists.removeIf(old -> old == replacing || old.root().equals(list.root()));
        lists.add(list);
        while (lists.size() > MAX_LISTS) {
            lists.removeFirst();
        }
    }

    private synchronized boolean holds(ProjectFileList list) {
        return lists.contains(list);
    }

    private synchronized List<ProjectFileList> all() {
        return List.copyOf(lists);
    }

    /** Lists {@code stale}'s root again; the fresh list, or null when it was replaced or the owner went away. */
    private @Nullable ProjectFileList relist(ProjectFileList stale) {
        ProjectFileList fresh = ProjectFileList.build(stale.root(), stale.settings(), closed);
        if (fresh == null || !holds(stale)) {
            return null; // gone, or replaced by a list for other settings
        }
        keep(fresh, stale);
        if (!fresh.snapshot().equals(stale.snapshot())) {
            onRelisted.accept(fresh.root());
        }
        return fresh;
    }

    private void relistLater(ProjectFileList stale) {
        submit(() -> {
            if (stale.stale() && holds(stale)) {
                relist(stale);
            }
        });
    }

    // --- what the Project panel reports ----------------------------------------------------------------------

    /**
     * Each path an in-app operation or a watch event touched is looked at again, on the index's thread (it
     * touches the disk). Any thread.
     */
    void changed(Collection<Path> paths) {
        List<ProjectFileList> held = all();
        if (held.isEmpty() || paths.isEmpty()) {
            return;
        }
        List<Path> copy = List.copyOf(paths);
        submit(() -> held.forEach(list -> list.changed(copy)));
    }

    /** Something changed that no event reported (a focus regain, lost events): every list is listed again soon. */
    void markStale() {
        all().forEach(ProjectFileList::markStale);
    }

    private void submit(Runnable task) {
        if (closed.getAsBoolean()) {
            return;
        }
        try {
            executor.execute(task);
        } catch (RejectedExecutionException shutDown) {
            // the owner is going away: nothing to keep current
        }
    }

    /** {@code query} ranked over {@code list} of {@code root} ({@link ProjectMapSearch}); null when cancelled. */
    private static @Nullable ProjectMapSearchResult rank(
            Path root, ProjectFileList list, String query, Kinds kinds, int max, BooleanSupplier cancelled) {
        ProjectMapSearch.Query parsed = ProjectMapSearch.Query.parse(query);
        if (kinds == Kinds.FILES) {
            parsed = parsed.onlyFiles();
        }
        return ProjectMapSearch.search(root, list.snapshot(), parsed, max, cancelled);
    }
}
