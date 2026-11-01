package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

import javafx.application.Platform;

import com.editora.search.ProjectFileList;
import org.jspecify.annotations.Nullable;

/**
 * What the Project Map knows about a project beyond its listings, worked out off the FX thread (review 3): why rows
 * are dimmed ({@link ProjectMapIgnored}, A11) and the project's facts for the Overview card, the module badges and
 * the folder tooltips ({@link ProjectMapOverview}, B4). One worker thread; results are applied on the FX thread and
 * announced with {@code onChanged}. Everything else is FX-thread only.
 */
final class ProjectMapOrientation {

    /** The largest README or package.json read for the overview. */
    private static final long MAX_READ = 256 * 1024;

    private final Runnable onChanged;
    private @Nullable ExecutorService worker;
    private final AtomicLong generation = new AtomicLong();
    private @Nullable Path root;
    /** Read on the worker only. */
    private volatile @Nullable ProjectMapIgnored rules;

    private final Map<Path, ProjectMapIgnored.Reason> reasons = new HashMap<>();
    private final Set<Path> submitted = new HashSet<>();
    private ProjectMapOverview.@Nullable Facts facts;
    private boolean factsRequested;
    private final List<Consumer<ProjectMapOverview.Facts>> factsWaiting = new ArrayList<>();
    private int version;
    /** Where the project's file list comes from (the Map search's, when the panel gives it); called on the worker. */
    private Function<Path, ProjectFileList.@Nullable Snapshot> files = ProjectMapOrientation::listFiles;

    ProjectMapOrientation(Runnable onChanged) {
        this.onChanged = onChanged;
    }

    /** Forgets everything about the previous root; nothing is read until asked for. */
    void setRoot(@Nullable Path root) {
        generation.incrementAndGet();
        this.root = root;
        rules = null;
        reasons.clear();
        submitted.clear();
        facts = null;
        factsRequested = false;
        factsWaiting.clear();
        version++;
    }

    /** See {@link #files}. */
    void setFileSource(@Nullable Function<Path, ProjectFileList.@Nullable Snapshot> source) {
        files = source == null ? ProjectMapOrientation::listFiles : source;
    }

    /** Bumped whenever more rows were found dimmed (or the root changed): the layout's key for {@link #reason}. */
    int version() {
        return version;
    }

    /** Why the row at {@code path} is dimmed, as far as known yet; null for none (or not yet known). */
    ProjectMapIgnored.@Nullable Reason reason(Path path) {
        return reasons.get(path);
    }

    /** Whether {@code path} or a folder above it (below the root) is dimmed, as far as known yet. */
    boolean dimmedOrInside(Path path) {
        Path current = path;
        Path base = root;
        while (current != null && base != null && current.startsWith(base) && !current.equals(base)) {
            if (reasons.containsKey(current)) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    /** Works out, on the worker, why each of these rows not looked at yet is dimmed. */
    void classify(Collection<ProjectMapModel.Entry> entries) {
        Path base = root;
        if (base == null) {
            return;
        }
        List<Path> folders = new ArrayList<>();
        List<Path> plain = new ArrayList<>();
        for (ProjectMapModel.Entry entry : entries) {
            if (entry.isPlaceholder() || entry.isSymbol() || !submitted.add(entry.path())) {
                continue;
            }
            (entry.directory() ? folders : plain).add(entry.path());
        }
        if (folders.isEmpty() && plain.isEmpty()) {
            return;
        }
        long expected = generation.get();
        submit(() -> {
            ProjectMapIgnored loaded = rules;
            if (loaded == null || !loaded.root().equals(base)) {
                loaded = ProjectMapIgnored.load(base);
                rules = loaded;
            }
            Map<Path, ProjectMapIgnored.Reason> found = new HashMap<>();
            for (Path folder : folders) {
                ProjectMapIgnored.Reason reason = loaded.classify(folder, true);
                if (reason != null) {
                    found.put(folder, reason);
                }
            }
            for (Path file : plain) {
                ProjectMapIgnored.Reason reason = loaded.classify(file, false);
                if (reason != null) {
                    found.put(file, reason);
                }
            }
            if (!found.isEmpty()) {
                Platform.runLater(() -> {
                    if (generation.get() == expected) {
                        reasons.putAll(found);
                        version++;
                        onChanged.run();
                    }
                });
            }
        });
    }

    /** The project's facts once known; null before. */
    ProjectMapOverview.@Nullable Facts facts() {
        return facts;
    }

    /**
     * Works the project's facts out (once per root, unless {@code fresh}) and hands them to {@code then} on the FX
     * thread; at once when they are known.
     */
    void loadFacts(boolean fresh, @Nullable Consumer<ProjectMapOverview.Facts> then) {
        Path base = root;
        if (base == null) {
            return;
        }
        ProjectMapOverview.Facts known = facts;
        if (known != null && !fresh) {
            if (then != null) {
                then.accept(known);
            }
            return;
        }
        if (then != null) {
            factsWaiting.add(then);
        }
        if (factsRequested && !fresh) {
            return;
        }
        factsRequested = true;
        long expected = generation.get();
        BooleanSupplier stale = () -> generation.get() != expected;
        Function<Path, ProjectFileList.@Nullable Snapshot> source = files;
        submit(() -> {
            ProjectFileList.Snapshot list = stale.getAsBoolean() ? null : source.apply(base);
            if (list == null || stale.getAsBoolean()) {
                return;
            }
            ProjectMapOverview.Facts computed = ProjectMapOverview.analyse(list, rel -> read(base, rel));
            Platform.runLater(() -> {
                if (generation.get() != expected) {
                    return;
                }
                facts = computed;
                version++;
                List<Consumer<ProjectMapOverview.Facts>> waiting = List.copyOf(factsWaiting);
                factsWaiting.clear();
                onChanged.run();
                waiting.forEach(callback -> callback.accept(computed));
            });
        });
    }

    void dispose() {
        generation.incrementAndGet();
        ExecutorService running = worker;
        if (running != null) {
            running.shutdownNow();
        }
    }

    private void submit(Runnable task) {
        ExecutorService running = worker;
        if (running == null) {
            running = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "project-map-orientation");
                thread.setDaemon(true);
                return thread;
            });
            worker = running;
        }
        try {
            running.execute(task);
        } catch (RejectedExecutionException e) {
            // disposed
        }
    }

    /** The default file list: the project walked once, {@code .gitignore} and dependency folders left out. */
    private static ProjectFileList.@Nullable Snapshot listFiles(Path root) {
        ProjectFileList list = ProjectFileList.build(root, new ProjectFileList.Settings(true, false), () -> false);
        return list == null ? null : list.snapshot();
    }

    private static @Nullable String read(Path root, String rel) {
        try {
            Path file = root.resolve(rel);
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_READ) {
                return null;
            }
            return Files.readString(file);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
