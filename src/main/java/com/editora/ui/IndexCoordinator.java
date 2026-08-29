package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import javafx.application.Platform;

import com.editora.editor.EditorBuffer;
import com.editora.editor.LanguageRegistry;
import com.editora.index.DeclarationScanner;
import com.editora.index.Symbol;
import com.editora.index.SymbolIndex;
import com.editora.search.GitignoreFilter;
import com.editora.vfs.Vfs;

import static com.editora.i18n.Messages.tr;

/**
 * Drives the server-free symbol index: the project walk, the incremental updates, and the picker that
 * makes it reachable.
 *
 * <p><b>Built lazily, on first use.</b> This deliberately does not index at startup. An index that walks
 * every file the moment a project opens spends real work on behalf of a user who may never ask it
 * anything, and Editora's whole performance posture is that background work has to justify itself. Asking
 * for a symbol is the justification. The cost is that the first query pays for the walk — measured under a
 * second on this repository — announced with a status message so it does not look like a hang.
 *
 * <p>After that it is incremental: a save rescans exactly the file that changed. There is no filesystem
 * watcher here on purpose; {@code ProjectPanel} already runs one, and a second walker competing with it
 * would be the kind of duplicated background cost this class is trying to avoid.
 *
 * <p>The index is the <em>floor</em>. Where a language server is running it is better at this in every
 * respect and should be preferred; nothing here overrides or competes with LSP.
 */
final class IndexCoordinator {

    /** Files bigger than this are skipped — a generated bundle is not worth the scan or the entries. */
    private static final long MAX_FILE_BYTES = 2_000_000;

    /** Ceiling on files visited in one walk, so a pathological tree cannot spin the thread forever. */
    private static final int MAX_VISIT = 50_000;

    /** Window hooks this coordinator needs beyond the shared host. */
    interface Ops {
        /** The active project root, or {@code null} when this window has no project open. */
        Path projectRoot();

        /** Opens {@code file} and moves the caret to the 0-based line/column. */
        void openAndGoto(Path file, int line, int column);

        /** Honour the user's .gitignore when walking, as Find in Files and the project tree do. */
        boolean respectGitignore();
    }

    private final CoordinatorHost host;
    private final Ops ops;
    private final SymbolIndex index = new SymbolIndex();
    private final QuickOpen<SymbolIndex.Hit> picker;

    /** One thread: the walk is IO-bound and there is no reason for two of them to fight over the disk. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "symbol-index");
        t.setDaemon(true);
        return t;
    });

    /** Discards the results of a walk that a project switch or a rebuild has superseded. */
    private final AtomicLong generation = new AtomicLong();

    private Path indexedRoot;
    private boolean building;

    /** Every file the last walk saw — the corpus behind Search Everywhere's file results. */
    private List<Path> projectFiles = List.of();

    /**
     * {@link #projectFiles} as root-relative display strings, parallel by index.
     *
     * <p>Held rather than derived per query because {@link #searchFiles} runs on the FX thread on every
     * keystroke: relativizing and stringifying every path there allocated a {@code Path} and a
     * {@code String} per file per keystroke — up to {@link #MAX_VISIT} of each — for values that only
     * change when the walk does (#876).
     */
    private List<String> projectRelPaths = List.of();

    IndexCoordinator(CoordinatorHost host, Ops ops) {
        this.host = host;
        this.ops = ops;
        this.picker = new QuickOpen<>(
                tr("index.gotoSymbol.title"),
                tr("index.gotoSymbol.prompt"),
                () -> new ArrayList<>(lastResults),
                hit -> hit.symbol().name(),
                IndexCoordinator::detail,
                hit -> ops.openAndGoto(
                        hit.file(), hit.symbol().line(), hit.symbol().column()));
    }

    QuickOpen<SymbolIndex.Hit> pickerForTest() {
        return picker;
    }

    void setOverlayHost(OverlayHost overlayHost) {
        picker.setOverlayHost(overlayHost);
    }

    /** {@code Container.name — path:line}, so two same-named symbols are told apart in the list. */
    private static String detail(SymbolIndex.Hit hit) {
        String container = hit.symbol().container();
        String where = hit.file().getFileName() + ":" + (hit.symbol().line() + 1);
        return container.isEmpty() ? where : container + " — " + where;
    }

    private List<SymbolIndex.Hit> lastResults = List.of();

    boolean isEnabled() {
        return host.settings().isSymbolIndex() && !host.simpleModeActive();
    }

    /** Called on every settings apply: a disabled index must not keep a project's symbols in memory. */
    void applySupport() {
        if (!isEnabled()) {
            index.clear();
            projectFiles = List.of();
            projectRelPaths = List.of();
            indexedRoot = null;
        }
    }

    /** Called when the window's project changes — the previous project's symbols are now meaningless. */
    void onProjectChanged() {
        generation.incrementAndGet();
        index.clear();
        projectFiles = List.of();
        projectRelPaths = List.of();
        indexedRoot = null;
    }

    /**
     * Rescans one saved file. No-op until the index has been built: doing it eagerly would quietly turn
     * "index on first use" into "index whatever you happen to save", which is a partial index that looks
     * like a complete one.
     */
    void onBufferSaved(EditorBuffer buffer) {
        if (!isEnabled() || indexedRoot == null || buffer == null) {
            return;
        }
        Path file = buffer.getPath();
        if (file == null || !Vfs.isLocal(file) || !file.startsWith(indexedRoot)) {
            return;
        }
        // The buffer's text is authoritative and already in memory, so this costs no disk read.
        String language = LanguageRegistry.forFileName(file.getFileName().toString());
        index.put(file, DeclarationScanner.scan(buffer.getContent(), language));
    }

    /** {@code index.rebuild}: forget everything and walk again, for when the tree changed underneath us. */
    void rebuild() {
        if (!isEnabled()) {
            host.setStatus(tr("status.index.disabled"));
            return;
        }
        generation.incrementAndGet();
        index.clear();
        projectFiles = List.of();
        projectRelPaths = List.of();
        indexedRoot = null;
        build(() -> host.setStatus(tr("status.index.built", index.symbolCount(), index.fileCount())));
    }

    /** {@code index.gotoSymbol}: prompt for a name and jump to the declaration. */
    void gotoSymbol() {
        if (!isEnabled()) {
            host.setStatus(tr("status.index.disabled"));
            return;
        }
        if (indexedRoot != null) {
            promptForSymbol();
            return;
        }
        build(this::promptForSymbol);
    }

    /**
     * Walks the project off the FX thread and calls {@code then} on the FX thread when it lands.
     *
     * <p>Guarded against a second walk while one is running: the picker is a keystroke, and hammering it
     * would otherwise queue one full project walk per press.
     */
    private void build(Runnable then) {
        Path root = ops.projectRoot();
        if (root == null || !Vfs.isLocal(root)) {
            host.setStatus(tr("status.index.noProject"));
            return;
        }
        if (building) {
            host.setStatus(tr("status.index.building"));
            return;
        }
        building = true;
        host.setStatus(tr("status.index.building"));
        long gen = generation.incrementAndGet();
        AutoCloseable task = host.startBackgroundTask(tr("status.index.building"));
        worker.submit(() -> {
            Walked walked = walk(root);
            Platform.runLater(() -> {
                building = false;
                close(task);
                if (gen != generation.get()) {
                    return; // a project switch or a rebuild superseded this walk
                }
                for (Scanned s : walked.scanned()) {
                    index.put(s.file(), s.symbols());
                }
                projectFiles = walked.files();
                projectRelPaths = relativize(root, projectFiles);
                indexedRoot = root;
                if (then != null) {
                    then.run();
                }
            });
        });
    }

    /** One walk's yield: the files it saw, and the symbols it found in them. */
    private record Walked(List<Path> files, List<Scanned> scanned) {}

    private record Scanned(Path file, List<Symbol> symbols) {}

    /** The blocking half — runs on {@link #worker}, touches nothing that belongs to the FX thread. */
    private Walked walk(Path root) {
        GitignoreFilter ignore = ops.respectGitignore() ? GitignoreFilter.load(root) : GitignoreFilter.NONE;
        List<Scanned> out = new ArrayList<>();
        // Every file the walk sees, not only the ones with symbols: Search Everywhere needs to offer
        // files too, and this walk is already paying for the traversal. Doing it separately would mean a
        // second pass over the same tree for the same information.
        List<Path> files = new ArrayList<>();
        int[] visited = {0};
        try (var stream = Files.walk(root)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (++visited[0] > MAX_VISIT) {
                    break;
                }
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                String rel = root.relativize(p).toString().replace(java.io.File.separatorChar, '/');
                if (rel.startsWith(".") || rel.contains("/.") || ignore.ignored(rel, false)) {
                    continue; // dot-dirs and .gitignore'd paths, matching Find in Files
                }
                files.add(p);
                String language = LanguageRegistry.forFileName(p.getFileName().toString());
                try {
                    if (Files.size(p) > MAX_FILE_BYTES) {
                        continue;
                    }
                    List<Symbol> symbols = DeclarationScanner.scan(Files.readString(p), language);
                    if (!symbols.isEmpty()) {
                        out.add(new Scanned(p, symbols));
                    }
                } catch (IOException | RuntimeException ex) {
                    // An unreadable or non-UTF-8 file is skipped, not fatal: the rest of the tree is
                    // still worth indexing, and a navigation index has no business failing loudly.
                }
            }
        } catch (IOException | RuntimeException ex) {
            // Same: a partial index beats none.
        }
        return new Walked(List.copyOf(files), out);
    }

    private void promptForSymbol() {
        if (index.symbolCount() == 0) {
            host.setStatus(tr("status.index.empty"));
            return;
        }
        host.promptText(tr("index.gotoSymbol.title"), tr("index.gotoSymbol.prompt"), "", query -> {
            lastResults = index.search(query);
            if (lastResults.isEmpty()) {
                host.setStatus(tr("status.index.noMatch", query));
                return;
            }
            picker.show(host.window());
        });
    }

    private static void close(AutoCloseable task) {
        try {
            if (task != null) {
                task.close();
            }
        } catch (Exception ignored) {
            // The progress chip is cosmetic; failing to close it must not sink the result.
        }
    }

    /** True once a walk has landed, so a caller can decide whether to trigger one. */
    boolean isBuilt() {
        return indexedRoot != null;
    }

    /** Builds if needed, then runs {@code then} — the entry point for a caller that wants results now. */
    void ensureBuilt(Runnable then) {
        if (!isEnabled()) {
            return;
        }
        if (isBuilt()) {
            then.run();
        } else {
            build(then);
        }
    }

    /** Ranked symbol hits for {@code query}; empty when the index has not been built. */
    List<SymbolIndex.Hit> searchSymbols(String query, int limit) {
        return isEnabled() ? index.search(query, limit) : List.of();
    }

    /**
     * Ranked project files for {@code query}, matched on the path relative to the root so a query can name
     * a directory as well as a file name.
     *
     * <p>Bounded selection over cached relative paths, for the reasons spelled out on
     * {@code SymbolIndex.search}: this runs on the FX thread per keystroke, and sorting every match to
     * discard all but {@code limit} was the part that scaled worst (#876). The ranking is a total order
     * already — two files cannot share a relative path — so unlike the symbol side it needs no scan-index
     * key to reproduce the old output exactly.
     */
    List<FileHit> searchFiles(String query, int limit) {
        if (!isEnabled() || query == null || query.isBlank() || indexedRoot == null || limit <= 0) {
            return List.of();
        }
        // Ordered worst-first, so the head is the entry a better candidate evicts.
        PriorityQueue<FileHit> keep = new PriorityQueue<>(FILE_RANK.reversed());
        for (int i = 0; i < projectRelPaths.size(); i++) {
            String rel = projectRelPaths.get(i);
            // scoreOfPath, not ofPath: a FileHit carries only a score, and the picker re-derives the
            // highlight at render time for the rows it draws.
            int score = com.editora.search.FuzzyMatch.scoreOfPath(rel, query);
            if (score == com.editora.search.FuzzyMatch.NO_SCORE) {
                continue;
            }
            if (keep.size() == limit) {
                FileHit worst = keep.peek();
                // >= 0, not > 0: on an exact tie the incumbent stays, which is what the stable sort this
                // replaces did. Paths are unique in practice, so this only ever matters if a walk were to
                // yield one twice — but "first seen wins" is then still the old behaviour rather than a coin
                // flip.
                if (score < worst.score() || (score == worst.score() && rel.compareTo(worst.relativePath()) >= 0)) {
                    continue;
                }
                keep.poll();
            }
            keep.add(new FileHit(projectFiles.get(i), rel, score));
        }
        List<FileHit> best = new ArrayList<>(keep);
        best.sort(FILE_RANK);
        return List.copyOf(best);
    }

    /** Display order for file hits: best score first, then path, which together are already total. */
    private static final java.util.Comparator<FileHit> FILE_RANK =
            java.util.Comparator.comparingInt(FileHit::score).reversed().thenComparing(FileHit::relativePath);

    /** The root-relative display form of each file, in the same order. */
    private static List<String> relativize(Path root, List<Path> files) {
        List<String> rels = new ArrayList<>(files.size());
        for (Path f : files) {
            rels.add(root.relativize(f).toString().replace(java.io.File.separatorChar, '/'));
        }
        return List.copyOf(rels);
    }

    /** A project file that matched, with the path as it should be shown. */
    record FileHit(Path file, String relativePath, int score) {}

    void dispose() {
        worker.shutdownNow();
    }
}
