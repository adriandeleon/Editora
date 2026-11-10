package com.editora.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

import javafx.application.Platform;

import com.editora.search.ProjectFileList;
import org.jspecify.annotations.Nullable;

/**
 * The Map's dependencies between the project's files (Map code browser gap 14), read on a worker thread of their own.
 * Each {@link #load} lists the project again and reads again only the files changed since the last one (by size and
 * modification time), so the Dependencies card opened again, or refreshed, after an edit shows it at the cost of a look
 * at each file's attributes. The answer comes on the FX thread; one for an earlier root is dropped.
 */
final class MapDependencyIndex {

    /** The most files read; a project with more is read in part, and the card says so. */
    static final int MAX_FILES = 20_000;
    /** A larger file is not read (a generated or bundled one, most likely). */
    static final long MAX_FILE_BYTES = 512 * 1024;

    private record Read(long size, long modified, ProjectMapImports.@Nullable Parsed parsed) {}

    private final AtomicLong generation = new AtomicLong();
    private @Nullable ExecutorService worker;
    private @Nullable Path root;
    private Function<Path, ProjectFileList.@Nullable Snapshot> files = MapDependencyIndex::listFiles;
    /** Worker only: what each file said when last read. */
    private final Map<String, Read> cache = new HashMap<>();

    private @Nullable ProjectMapDependencies latest;
    private boolean loading;
    private boolean again;
    private final List<Consumer<ProjectMapDependencies>> waiting = new ArrayList<>();

    /** Forgets the previous root's files. */
    void setRoot(@Nullable Path root) {
        if (java.util.Objects.equals(this.root, root)) {
            return;
        }
        generation.incrementAndGet();
        this.root = root;
        latest = null;
        loading = false;
        again = false;
        waiting.clear();
        submit(cache::clear);
    }

    /** Where the project's file list comes from (the Map search's, when the panel gives it); called on the worker. */
    void setFileSource(@Nullable Function<Path, ProjectFileList.@Nullable Snapshot> source) {
        files = source == null ? MapDependencyIndex::listFiles : source;
    }

    /** The last dependencies worked out, or null before the first. */
    @Nullable
    ProjectMapDependencies latest() {
        return latest;
    }

    /** Whether a reading is under way. */
    boolean loading() {
        return loading;
    }

    /**
     * Works the dependencies out afresh (reading only what changed) and hands them to {@code then} on the FX thread.
     * Asked while a reading is under way, one more follows it, and every caller gets its answer.
     */
    void load(Consumer<ProjectMapDependencies> then) {
        if (root == null) {
            return;
        }
        waiting.add(then);
        if (loading) {
            again = true;
        } else {
            start();
        }
    }

    private void start() {
        Path base = root;
        if (base == null) {
            return;
        }
        loading = true;
        long expected = generation.get();
        Function<Path, ProjectFileList.@Nullable Snapshot> source = files;
        submit(() -> {
            ProjectMapDependencies computed = generation.get() == expected ? scan(base, source) : null;
            Platform.runLater(() -> {
                if (generation.get() != expected) {
                    return;
                }
                loading = false;
                if (computed != null) {
                    latest = computed;
                }
                if (again) {
                    again = false; // asked again while reading: the files may have changed since
                    start();
                    return;
                }
                ProjectMapDependencies answer = latest;
                List<Consumer<ProjectMapDependencies>> done = List.copyOf(waiting);
                waiting.clear();
                if (answer != null) {
                    done.forEach(callback -> callback.accept(answer));
                }
            });
        });
    }

    /** Lists the project and reads its files (changed ones only); on the worker. Null when it could not be listed. */
    private @Nullable ProjectMapDependencies scan(
            Path base, Function<Path, ProjectFileList.@Nullable Snapshot> source) {
        ProjectFileList.Snapshot list;
        try {
            list = source.apply(base);
        } catch (RuntimeException failed) {
            ProjectMapLog.unexpected("listing the project for its dependencies", failed);
            return null;
        }
        if (list == null) {
            return null;
        }
        Map<String, ProjectMapImports.Parsed> parsed = new HashMap<>();
        Map<String, String> goModules = new HashMap<>();
        Map<String, Read> seen = new HashMap<>();
        boolean truncated = list.truncated();
        int count = 0;
        for (String rel : list.files()) {
            if (rel.equals("go.mod") || rel.endsWith("/go.mod")) {
                String text = readSmall(base.resolve(rel));
                String module = text == null ? null : ProjectMapImports.goModule(text);
                if (module != null) {
                    goModules.put(ProjectMapImports.folderOf(rel), module);
                }
                continue;
            }
            if (ProjectMapImports.languageOf(rel) == null) {
                continue;
            }
            if (++count > MAX_FILES) {
                truncated = true;
                break;
            }
            Read read = read(base, rel);
            if (read == null) {
                continue;
            }
            seen.put(rel, read);
            ProjectMapImports.Parsed said = read.parsed();
            if (said != null) {
                parsed.put(rel, said);
            }
        }
        cache.clear();
        cache.putAll(seen);
        List<ProjectMapImports.Edge> edges = ProjectMapImports.resolve(parsed, goModules);
        return new ProjectMapDependencies(parsed.keySet(), edges, truncated);
    }

    /** What the file at {@code rel} says: from the cache when its size and time are unchanged. */
    private @Nullable Read read(Path base, String rel) {
        Path file = base.resolve(rel);
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            long size = attributes.size();
            long modified = attributes.lastModifiedTime().toMillis();
            Read known = cache.get(rel);
            if (known != null && known.size() == size && known.modified() == modified) {
                return known;
            }
            if (!attributes.isRegularFile() || size > MAX_FILE_BYTES) {
                return new Read(size, modified, null);
            }
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            return new Read(size, modified, ProjectMapImports.parse(rel, text));
        } catch (IOException | RuntimeException e) {
            return null; // gone, or unreadable: left out
        }
    }

    private static @Nullable String readSmall(Path file) {
        try {
            return Files.size(file) > MAX_FILE_BYTES ? null : Files.readString(file);
        } catch (IOException | RuntimeException e) {
            return null;
        }
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
                Thread thread = new Thread(runnable, "project-map-dependencies");
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
}
