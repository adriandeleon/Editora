package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
            indexedRoot = null;
        }
    }

    /** Called when the window's project changes — the previous project's symbols are now meaningless. */
    void onProjectChanged() {
        generation.incrementAndGet();
        index.clear();
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
            List<Scanned> scanned = walk(root);
            Platform.runLater(() -> {
                building = false;
                close(task);
                if (gen != generation.get()) {
                    return; // a project switch or a rebuild superseded this walk
                }
                for (Scanned s : scanned) {
                    index.put(s.file(), s.symbols());
                }
                indexedRoot = root;
                if (then != null) {
                    then.run();
                }
            });
        });
    }

    private record Scanned(Path file, List<Symbol> symbols) {}

    /** The blocking half — runs on {@link #worker}, touches nothing that belongs to the FX thread. */
    private List<Scanned> walk(Path root) {
        GitignoreFilter ignore = ops.respectGitignore() ? GitignoreFilter.load(root) : GitignoreFilter.NONE;
        List<Scanned> out = new ArrayList<>();
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
        return out;
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

    void dispose() {
        worker.shutdownNow();
    }
}
