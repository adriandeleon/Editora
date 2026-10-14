package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.application.Platform;

import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.markdown.MarkdownLinkIndex;
import com.editora.markdown.WikiTargets;
import com.editora.search.GitignoreFilter;
import com.editora.search.ProjectWalk;
import com.editora.vfs.Vfs;

/**
 * The project's Markdown link index, kept for as long as the project is open: which document links to
 * which file ({@link MarkdownLinkIndex}), built on first use and then kept current one file at a time —
 * on save, on the project watcher's account of outside changes, on delete and on rename. It is what link
 * updates on rename, wiki-link resolution, {@code [[} completion and the Backlinks window ask.
 *
 * <p><b>Threading.</b> The index belongs to one worker thread ({@code markdown-links}): it is built there
 * (the walk and every file read), changed there and asked there. The FX thread hands the worker a job
 * ({@link #submit}) and gets its answer back with {@code Platform.runLater}; the only thing it reads
 * directly is {@link #targets()}, an immutable snapshot of the wiki names published after each change — so
 * resolving a wiki link while typing or rendering never waits for the disk.
 *
 * <p><b>Bounds.</b> The walk is the shared {@link ProjectWalk} with the ignore rules Find in Files uses,
 * capped at {@link MarkdownProjectFiles#MAX_FILES} Markdown files of at most
 * {@link MarkdownProjectFiles#MAX_BYTES} each; a project switch or disposal abandons a build under way.
 * Nothing is walked until something asks for the index.
 */
final class MarkdownLinkGraph {

    /** Every live graph (one per window), so a document can find the one whose project holds it. */
    private static final Set<MarkdownLinkGraph> LIVE =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private static final int MAX_OTHER_FILES = 20_000;

    private final CoordinatorHost host;
    private final Supplier<Path> projectRoot;
    private final BooleanSupplier respectGitignore;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "markdown-links");
        t.setDaemon(true);
        return t;
    });

    // --- worker-confined ---
    private MarkdownLinkIndex index;
    private boolean stale;

    // --- read from any thread ---
    private volatile boolean disposed;
    private volatile boolean wanted;
    private volatile long epoch;
    private volatile WikiTargets targets = WikiTargets.EMPTY;
    private volatile boolean truncated;

    /** Run on the FX thread whenever the published wiki names changed (a preview re-resolves its links). */
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    /** How many times the project was walked (tests: an edit or a rename must not walk again). */
    volatile int builds;

    MarkdownLinkGraph(CoordinatorHost host, Supplier<Path> projectRoot, BooleanSupplier respectGitignore) {
        this.host = host;
        this.projectRoot = projectRoot;
        this.respectGitignore = respectGitignore;
        LIVE.add(this);
    }

    /** The graph of the project that holds {@code document}, or null when no open project does. */
    static MarkdownLinkGraph forDocument(Path document) {
        if (document == null || !Vfs.isLocal(document)) {
            return null;
        }
        Path doc = document.toAbsolutePath().normalize();
        List<MarkdownLinkGraph> live;
        synchronized (LIVE) {
            live = new ArrayList<>(LIVE);
        }
        for (MarkdownLinkGraph graph : live) {
            Path root = graph.disposed ? null : graph.rootOrNull();
            if (root != null && doc.startsWith(root)) {
                return graph;
            }
        }
        return null;
    }

    /** The open buffers of every window, by path — a file open elsewhere is edited there, not on disk. */
    static Map<Path, EditorBuffer> openBuffers() {
        Map<Path, EditorBuffer> out = new LinkedHashMap<>();
        List<MarkdownLinkGraph> live;
        synchronized (LIVE) {
            live = new ArrayList<>(LIVE);
        }
        for (MarkdownLinkGraph graph : live) {
            if (!graph.disposed) {
                graph.host.forEachBuffer(buffer -> {
                    Path path = buffer.getPath();
                    if (path != null && Vfs.isLocal(path)) {
                        out.putIfAbsent(path.toAbsolutePath().normalize(), buffer);
                    }
                });
            }
        }
        return out;
    }

    Path rootOrNull() {
        try {
            Path root = projectRoot.get();
            return root == null || !Vfs.isLocal(root)
                    ? null
                    : root.toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private boolean wikiLinksOn() {
        Settings s = host.settings();
        return s == null || s.isMarkdownWikiLinks();
    }

    void addListener(Runnable onTargetsChanged) {
        listeners.add(onTargetsChanged);
    }

    /**
     * Runs {@code job} on the worker with the index of the current project — built or rebuilt first when
     * there is none, it is stale, or the wiki-link setting changed — or with null when no project is open.
     * Call on the FX thread; the returned future completes on the worker when the job has run.
     */
    CompletableFuture<Void> submit(Consumer<MarkdownLinkIndex> job) {
        Path root = rootOrNull();
        boolean ignore = respectGitignore.getAsBoolean();
        boolean wiki = wikiLinksOn();
        long at = epoch;
        wanted = true;
        return enqueue(() -> {
            MarkdownLinkIndex current = at == epoch ? ensure(root, ignore, wiki, at) : null;
            job.accept(current);
            publish();
        });
    }

    /** As {@link #submit}, but only when the index is already in use: a save must not start a project walk. */
    private void update(Consumer<MarkdownLinkIndex> change) {
        if (!wanted || disposed) {
            return;
        }
        long at = epoch;
        enqueue(() -> {
            if (index != null && at == epoch) {
                change.accept(index);
                publish();
            }
        });
    }

    private CompletableFuture<Void> enqueue(Runnable work) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        try {
            worker.execute(() -> {
                try {
                    if (!disposed) {
                        work.run();
                    }
                } catch (RuntimeException | Error e) {
                    // a failed job must not kill the thread the index lives on
                } finally {
                    done.complete(null);
                }
            });
        } catch (RuntimeException rejected) {
            done.complete(null);
        }
        return done;
    }

    /** Worker: the index for {@code root}, walking the project when it has to. */
    private MarkdownLinkIndex ensure(Path root, boolean ignore, boolean wiki, long at) {
        if (root == null) {
            index = null;
            return null;
        }
        if (index != null && root.equals(index.root()) && !stale && index.wikiLinks() == wiki) {
            return index;
        }
        BooleanSupplier cancelled = () -> disposed || at != epoch;
        List<Path> markdown = new ArrayList<>();
        List<Path> assets = new ArrayList<>();
        GitignoreFilter filter = ignore ? GitignoreFilter.load(root) : GitignoreFilter.NONE;
        ProjectWalk.Outcome outcome = ProjectWalk.walk(
                root,
                new ProjectWalk.Options(Integer.MAX_VALUE, MarkdownProjectFiles.MAX_FILES, filter, cancelled),
                (p, rel, attrs) -> {
                    if (!attrs.isRegularFile()) {
                        return ProjectWalk.Verdict.SKIP;
                    }
                    if (MarkdownProjectFiles.isMarkdown(p)) {
                        if (attrs.size() > MarkdownProjectFiles.MAX_BYTES) {
                            return ProjectWalk.Verdict.SKIP;
                        }
                        markdown.add(p);
                        return ProjectWalk.Verdict.ACCEPT;
                    }
                    if (assets.size() < MAX_OTHER_FILES) {
                        assets.add(p); // any file may be named by a wiki link, as the linter's listing has it
                    }
                    return ProjectWalk.Verdict.SKIP; // only Markdown files count against the cap
                });
        if (cancelled.getAsBoolean()) {
            return null;
        }
        MarkdownLinkIndex built = new MarkdownLinkIndex(root);
        built.setWikiLinks(wiki);
        List<Path> all = new ArrayList<>(markdown);
        all.addAll(assets);
        built.setFiles(all);
        for (Path file : markdown) {
            if (cancelled.getAsBoolean()) {
                return null;
            }
            String text = MarkdownProjectFiles.read(file);
            if (text != null) {
                built.put(file, text);
            }
        }
        index = built;
        stale = false;
        truncated = outcome.truncated();
        builds++;
        return built;
    }

    /** Worker: publishes the wiki names when they changed and tells the listeners. */
    private void publish() {
        WikiTargets now = index == null ? WikiTargets.EMPTY : index.wikiTargets();
        if (now != targets) {
            targets = now;
            if (!listeners.isEmpty()) {
                Platform.runLater(() -> listeners.forEach(Runnable::run));
            }
        }
    }

    /**
     * The wiki names of the project as last published — empty until the index has been built, which asking
     * starts (listeners hear when it lands). Any thread; never blocks.
     */
    WikiTargets targets() {
        if (!wanted && !disposed) {
            wanted = true;
            if (Platform.isFxApplicationThread()) {
                submit(ix -> {});
            } else {
                Platform.runLater(() -> submit(ix -> {})); // the project root is the FX thread's to read
            }
        }
        return targets;
    }

    /** Whether the last walk stopped at the cap, so the index covers only part of the project. */
    boolean truncated() {
        return truncated;
    }

    // --- keeping it current (FX thread) -----------------------------------------------------------------

    void onProjectChanged() {
        epoch++;
        targets = WikiTargets.EMPTY;
        enqueue(() -> {
            index = null;
            stale = false;
        });
        if (!listeners.isEmpty()) {
            listeners.forEach(Runnable::run);
        }
    }

    /** The buffer's text is what the file now holds: no disk read. */
    void onSaved(EditorBuffer buffer) {
        Path file = buffer == null ? null : buffer.getPath();
        if (!wanted || file == null || !Vfs.isLocal(file) || !MarkdownProjectFiles.isMarkdown(file)) {
            return;
        }
        Path doc = file.toAbsolutePath().normalize();
        String text = buffer.getContent();
        update(ix -> {
            if (doc.startsWith(ix.root())) {
                ix.put(doc, text);
            }
        });
    }

    /** More named paths than this and one re-walk is cheaper than that many patches. */
    static final int MAX_INCREMENTAL = 256;

    /** The project watcher's account of what other programs changed; see IndexCoordinator.onExternalChanges. */
    void onExternalChanges(List<ProjectPanel.FsChange> changes, boolean complete) {
        if (!wanted || changes == null) {
            return;
        }
        if (!complete || changes.size() > MAX_INCREMENTAL) {
            markStale();
            return;
        }
        List<ProjectPanel.FsChange> named = List.copyOf(changes);
        update(ix -> {
            for (ProjectPanel.FsChange change : named) {
                Path p = change.path().toAbsolutePath().normalize();
                if (!p.startsWith(ix.root())) {
                    continue;
                }
                if (change.kind() == ProjectPanel.FsKind.DELETED) {
                    ix.remove(p);
                } else if (Files.isDirectory(p)) {
                    stale = true; // its contents were not reported
                } else if (MarkdownProjectFiles.isMarkdown(p)) {
                    String text = MarkdownProjectFiles.read(p);
                    if (text != null) {
                        ix.put(p, text);
                    }
                } else if (Files.isRegularFile(p)) {
                    ix.addFile(p);
                }
            }
        });
    }

    void onDeleted(Path path) {
        if (path != null && Vfs.isLocal(path)) {
            Path p = path.toAbsolutePath().normalize();
            update(ix -> ix.remove(p));
        }
    }

    /** A rename nobody rewrites links for: the index still has to know the files moved. */
    void onRenamed(Path from, Path to) {
        update(ix -> ix.renamed(from, to));
    }

    void markStale() {
        if (wanted) {
            enqueue(() -> stale = true);
        }
    }

    void dispose() {
        disposed = true;
        LIVE.remove(this);
        worker.shutdownNow();
    }
}
