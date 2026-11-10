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
 * Something the Map works out from the project's files, read on a worker thread of its own: the dependencies between
 * them (gap 14, {@link MapDependencyIndex}), their sizes and complexity (gap 15, {@link MapMetricsIndex}). Each {@link
 * #load} lists the project again and reads again only the files changed since the last one (by size and modification
 * time), so a card opened again, or refreshed, after an edit shows it at the cost of a look at each file's attributes.
 * What each file says ({@code F}) is cached; what they say together ({@code T}) is worked out afresh. The answer comes on
 * the FX thread; one for an earlier root is dropped.
 */
abstract class MapFileReader<F, T> {

    /** The most files read; a project with more is read in part, and the card says so. */
    static final int MAX_FILES = 20_000;
    /** A larger file is not read (a generated or bundled one, most likely). */
    static final long MAX_FILE_BYTES = 512 * 1024;

    private record Read<F>(
            long size, long modified, @Nullable F parsed) {}

    private final String threadName;

    private final AtomicLong generation = new AtomicLong();
    private @Nullable ExecutorService worker;
    private @Nullable Path root;
    private Function<Path, ProjectFileList.@Nullable Snapshot> files = MapFileReader::listFiles;
    /** Worker only: what each file said when last read. */
    private final Map<String, Read<F>> cache = new HashMap<>();

    private @Nullable T latest;
    private boolean loading;
    private boolean again;
    private final List<Consumer<T>> waiting = new ArrayList<>();

    /** A reader whose worker thread is called {@code threadName}. */
    MapFileReader(String threadName) {
        this.threadName = threadName;
    }

    /** Whether the file at {@code rel} is read (and counted against {@link #MAX_FILES}). */
    protected abstract boolean wanted(String rel);

    /** Whether the file at {@code rel} is a small one read every time and handed to {@link #finish} whole. */
    protected boolean side(String rel) {
        return false;
    }

    /** What the file at {@code rel} says, from its {@code text}; null for nothing. On the worker. */
    protected abstract @Nullable F read(String rel, String text);

    /**
     * What the files say together: {@code read} each read file's answer, {@code sides} each side file's text, {@code
     * truncated} whether the project had more files than were read. On the worker.
     */
    protected abstract T finish(Map<String, F> read, Map<String, String> sides, boolean truncated);

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
        files = source == null ? MapFileReader::listFiles : source;
    }

    /** The last answer worked out, or null before the first. */
    @Nullable
    T latest() {
        return latest;
    }

    /** Whether a reading is under way. */
    boolean loading() {
        return loading;
    }

    /**
     * Works the answer out afresh (reading only what changed) and hands it to {@code then} on the FX thread.
     * Asked while a reading is under way, one more follows it, and every caller gets its answer.
     */
    void load(Consumer<T> then) {
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
            T computed = generation.get() == expected ? scan(base, source) : null;
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
                T answer = latest;
                List<Consumer<T>> done = List.copyOf(waiting);
                waiting.clear();
                if (answer != null) {
                    done.forEach(callback -> callback.accept(answer));
                }
            });
        });
    }

    /** Lists the project and reads its files (changed ones only); on the worker. Null when it could not be listed. */
    private @Nullable T scan(Path base, Function<Path, ProjectFileList.@Nullable Snapshot> source) {
        ProjectFileList.Snapshot list;
        try {
            list = source.apply(base);
        } catch (RuntimeException failed) {
            ProjectMapLog.unexpected("listing the project for " + threadName, failed);
            return null;
        }
        if (list == null) {
            return null;
        }
        Map<String, F> parsed = new HashMap<>();
        Map<String, String> sides = new HashMap<>();
        Map<String, Read<F>> seen = new HashMap<>();
        boolean truncated = list.truncated();
        int count = 0;
        for (String rel : list.files()) {
            if (side(rel)) {
                String text = readSmall(base.resolve(rel));
                if (text != null) {
                    sides.put(rel, text);
                }
                continue;
            }
            if (!wanted(rel)) {
                continue;
            }
            if (++count > MAX_FILES) {
                truncated = true;
                break;
            }
            Read<F> read = readFile(base, rel);
            if (read == null) {
                continue;
            }
            seen.put(rel, read);
            F said = read.parsed();
            if (said != null) {
                parsed.put(rel, said);
            }
        }
        cache.clear();
        cache.putAll(seen);
        return finish(parsed, sides, truncated);
    }

    /** What the file at {@code rel} says: from the cache when its size and time are unchanged. */
    private @Nullable Read<F> readFile(Path base, String rel) {
        Path file = base.resolve(rel);
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            long size = attributes.size();
            long modified = attributes.lastModifiedTime().toMillis();
            Read<F> known = cache.get(rel);
            if (known != null && known.size() == size && known.modified() == modified) {
                return known;
            }
            if (!attributes.isRegularFile() || size > MAX_FILE_BYTES) {
                return new Read<>(size, modified, null);
            }
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            return new Read<>(size, modified, read(rel, text));
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
                Thread thread = new Thread(runnable, threadName);
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
