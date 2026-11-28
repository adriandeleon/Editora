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
import com.editora.index.DeclarationScanner;
import com.editora.index.Symbol;
import com.editora.index.SymbolIndex;
import com.editora.perf.BlockingSites;
import com.editora.search.GitignoreFilter;
import com.editora.search.ProjectWalk;
import com.editora.search.RemoteFiles;
import com.editora.text.LanguageRegistry;
import com.editora.vfs.Vfs;
import org.jspecify.annotations.Nullable;

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

    /**
     * Ceiling on files indexed in one walk, so a pathological tree cannot spin the thread forever. It counts
     * the files the index keeps — never the ignored ones — and reaching it is reported, not swallowed.
     */
    private static final int MAX_VISIT = 50_000;

    /** Window hooks this coordinator needs beyond the shared host. */
    interface Ops {
        /** The active project root, or {@code null} when this window has no project open. */
        Path projectRoot();

        /** Opens {@code file} and moves the caret to the 0-based line/column. */
        void openAndGoto(@Nullable Path file, int line, int column);

        /** Honour the user's .gitignore when walking, as Find in Files and the project tree do. */
        boolean respectGitignore();

        /** Keeps {@code content}, a closed file's text, in Local History before a bulk edit rewrites it. */
        default void recordHistory(Path file, String content, java.util.function.Consumer<Boolean> done) {
            done.accept(true);
        }
    }

    /** What {@link #ops} is given for {@link Ops#openAndGoto}. */
    @FunctionalInterface
    interface Opener {
        void openAndGoto(@Nullable Path file, int line, int column);
    }

    /** Builds the production adapter without another long anonymous class in {@link MainController}. */
    static Ops ops(
            java.util.function.Supplier<Path> projectRoot,
            Opener opener,
            java.util.function.BooleanSupplier respectGitignore,
            MarkdownLinkUpdater.HistoryRecorder history) {
        return new Ops() {
            @Override
            public Path projectRoot() {
                return projectRoot.get();
            }

            @Override
            public void openAndGoto(@Nullable Path file, int line, int column) {
                opener.openAndGoto(file, line, column);
            }

            @Override
            public boolean respectGitignore() {
                return respectGitignore.getAsBoolean();
            }

            @Override
            public void recordHistory(Path file, String content, java.util.function.Consumer<Boolean> done) {
                history.record(file, content, done);
            }
        };
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

    private @Nullable Path indexedRoot;
    private boolean building;

    /**
     * What each file the last walk read looked like on disk when it was read, so the next walk of the same project
     * reads only the files that changed since (perf review F5): a branch switch marks the whole index stale, and
     * re-reading up to {@link #MAX_VISIT} files to find the few that changed made the next symbol search wait.
     */
    private java.util.Map<Path, Stamp> stamps = java.util.Map.of();

    /** How many files the last walk took from the index it replaced instead of reading them. A test's. */
    int lastReused;

    /** The last walk stopped at {@link #maxFiles} files, so the index covers only part of the project. */
    private boolean truncated;

    /** The cap one walk honours; {@link #MAX_VISIT} outside tests. */
    int maxFiles = MAX_VISIT;

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

    /** Markdown links follow a rename too; it rides this coordinator's rename hook and project seams. */
    final MarkdownLinkUpdater linkUpdater;

    /** The project's Markdown link index: its own thread, its own lifetime rules (see the class). */
    final MarkdownLinkGraph linkGraph;

    /** The Backlinks tool window: what links to the active file, asked of {@link #linkGraph}. */
    final MarkdownBacklinks backlinks;

    /** Go to Heading in Project: the same project, walk and overlay as Go to Symbol. */
    final MarkdownHeadingPicker headingPicker;

    IndexCoordinator(CoordinatorHost host, Ops ops) {
        this.host = host;
        this.ops = ops;
        this.linkGraph = new MarkdownLinkGraph(host, ops::projectRoot, ops::respectGitignore);
        this.linkUpdater = new MarkdownLinkUpdater(host, linkGraph, ops::recordHistory);
        this.backlinks = new MarkdownBacklinks(host, linkGraph, ops);
        this.headingPicker = new MarkdownHeadingPicker(host, ops);
        this.picker = new QuickOpen<>(
                tr("index.gotoSymbol.title"),
                tr("index.gotoSymbol.prompt"),
                () -> new ArrayList<>(lastResults),
                hit -> hit.symbol().name(),
                IndexCoordinator::detail,
                hit -> ops.openAndGoto(
                        hit.file(), hit.symbol().line(), hit.symbol().column()));
    }

    /**
     * The file a wiki link's {@code target} names when written in {@code document}, or null (unknown,
     * ambiguous, or wiki links off). The hook the preview and the link tools resolve through; never blocks.
     */
    @Nullable
    Path resolveWikiLink(Path document, String target) {
        return WikiLinks.resolve(document, target);
    }

    QuickOpen<SymbolIndex.Hit> pickerForTest() {
        return picker;
    }

    void setOverlayHost(OverlayHost overlayHost) {
        picker.setOverlayHost(overlayHost);
        headingPicker.setOverlayHost(overlayHost);
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
            stamps = java.util.Map.of();
            truncated = false;
        }
    }

    /** Called when the window's project changes — the previous project's symbols are now meaningless. */
    void onProjectChanged() {
        linkGraph.onProjectChanged();
        headingPicker.dispose(); // a scan of the project that was is of no use now
        generation.incrementAndGet();
        index.clear();
        projectFiles = List.of();
        projectRelPaths = List.of();
        indexedRoot = null;
        stamps = java.util.Map.of();
    }

    /**
     * Rescans one saved file. No-op until the index has been built: doing it eagerly would quietly turn
     * "index on first use" into "index whatever you happen to save", which is a partial index that looks
     * like a complete one.
     */
    void onBufferSaved(EditorBuffer buffer) {
        linkGraph.onSaved(buffer); // its own gate: the link index does not depend on the symbol index
        if (!isEnabled() || indexedRoot == null || buffer == null) {
            return;
        }
        Path file = buffer.getPath();
        if (file == null || !Vfs.isLocal(file) || !file.startsWith(indexedRoot)) {
            return;
        }
        // The buffer's text is authoritative and already in memory, so this costs no disk read. Its language,
        // too: an extension-less script is shell by its #! line, which its file name does not say. It is scanned
        // on the index's own thread (PERF-01, Pauta 6.2.0), in order with every other save and walk, and kept
        // only when no project switch or rebuild came meanwhile.
        String language = buffer.getLanguage();
        String text = buffer.getContent();
        Path root = indexedRoot;
        long gen = generation.get();
        try {
            worker.submit(() -> {
                BlockingSites.enter(SAVE_SCAN_SITE);
                List<Symbol> symbols = DeclarationScanner.scan(text, language);
                Platform.runLater(() -> applySaved(gen, root, file, symbols));
            });
        } catch (java.util.concurrent.RejectedExecutionException closed) {
            // the window is closing: nothing to keep the symbols for
        }
    }

    /** Where a saved file's declarations are scanned: the index's own thread, never the FX thread. */
    static final String SAVE_SCAN_SITE = "IndexCoordinator.saveScan";

    /** Where the server-free Go to Definition scans the buffer: off the FX thread. */
    static final String DEFINITION_SITE = "IndexCoordinator.definition";

    /** Where the symbol and file corpus is searched (Search Everywhere, Go to Symbol, the Map): off the FX thread. */
    static final String SEARCH_SITE = "IndexCoordinator.search";

    /** A saved file's declarations, scanned off the FX thread: kept unless a switch or a rebuild came since. */
    private void applySaved(long gen, @Nullable Path root, Path file, List<Symbol> symbols) {
        if (gen != generation.get() || root == null || !root.equals(indexedRoot)) {
            return; // the walk that replaced this index read the saved file itself
        }
        index.put(file, symbols);
        if (!projectFiles.contains(file)) {
            // A file first saved after the walk: its symbols were found but the file itself was not, so
            // Search Everywhere offered the class and not the file it lives in.
            List<Path> files = new ArrayList<>(projectFiles);
            files.add(file);
            projectFiles = List.copyOf(files);
            projectRelPaths = relativize(root, projectFiles);
        }
    }

    /**
     * The tree changed in a way the index was not told file by file (an external program, a checkout, a
     * rename): the next use walks again instead of answering from a list of files that may be gone. Cheap to
     * call repeatedly — nothing is walked until something asks.
     */
    void markStale() {
        staleMarks++;
        if (indexedRoot != null) {
            stale = true;
        }
    }

    /** More named paths than this and one re-walk is cheaper, and easier to trust, than that many patches. */
    static final int MAX_INCREMENTAL = 256;

    /**
     * The project watcher's account of what other programs changed. When it names the files, only those are
     * re-read — marking the whole index stale for one touched file made the next Search Everywhere walk and
     * read the entire project again. Falls back to {@link #markStale} whenever the account is incomplete, too
     * long, names a directory (whose contents were not reported), or arrives while a walk is in flight.
     */
    void onExternalChanges(List<ProjectPanel.FsChange> changes, boolean complete) {
        linkGraph.onExternalChanges(changes, complete);
        Path root = indexedRoot;
        if (!complete || root == null || stale || building || changes.size() > MAX_INCREMENTAL) {
            markStale();
            return;
        }
        List<Path> touched = new ArrayList<>();
        java.util.Set<Path> created = new java.util.HashSet<>();
        for (ProjectPanel.FsChange change : changes) {
            Path path = change.path();
            if (path == null || !Vfs.isLocal(path) || !path.startsWith(root)) {
                continue;
            }
            if (change.kind() == ProjectPanel.FsKind.DELETED) {
                touched.remove(path);
                forget(path);
            } else {
                if (change.kind() == ProjectPanel.FsKind.CREATED) {
                    created.add(path);
                }
                if (!touched.contains(path)) {
                    touched.add(path);
                }
            }
        }
        if (touched.isEmpty()) {
            return;
        }
        long gen = generation.get();
        boolean gitignore = ops.respectGitignore();
        worker.submit(() -> {
            GitignoreFilter ignore = gitignore ? GitignoreFilter.load(root) : GitignoreFilter.NONE;
            List<Scanned> rescanned = new ArrayList<>();
            List<Path> gone = new ArrayList<>();
            boolean directory = false;
            for (Path file : touched) {
                try {
                    java.nio.file.attribute.BasicFileAttributes attrs = Files.readAttributes(
                            file,
                            java.nio.file.attribute.BasicFileAttributes.class,
                            java.nio.file.LinkOption.NOFOLLOW_LINKS);
                    if (attrs.isDirectory()) {
                        if (created.contains(file)) {
                            directory = true; // a new folder: nobody reported what is inside it
                            break;
                        }
                        // A folder's own "modified": Windows' watcher and the polling one send it whenever an entry
                        // inside comes, goes or changes, and that entry is reported itself where it is watched.
                        // Taken for a new folder, every save in .editora/ walked the whole project again.
                        continue;
                    }
                    if (!ProjectWalk.offers(root, file, ignore)
                            || (!attrs.isRegularFile() && !(attrs.isSymbolicLink() && Files.isRegularFile(file)))) {
                        continue;
                    }
                    rescanned.add(new Scanned(file, scanFile(file)));
                } catch (java.nio.file.NoSuchFileException e) {
                    gone.add(file); // created and removed again before we looked
                } catch (IOException | RuntimeException e) {
                    // unreadable: leave whatever the index holds for it
                }
            }
            boolean rewalk = directory;
            Platform.runLater(() -> {
                if (gen != generation.get() || indexedRoot == null) {
                    return; // a project switch or a rebuild: that walk reads these files itself
                }
                if (rewalk || building) {
                    markStale();
                    return;
                }
                gone.forEach(this::onFileDeleted);
                List<Path> files = null;
                java.util.Set<Path> known = null;
                for (Scanned s : rescanned) {
                    if (s.symbols().isEmpty()) {
                        index.remove(s.file());
                    } else {
                        index.put(s.file(), s.symbols());
                    }
                    if (known == null) {
                        known = new java.util.HashSet<>(projectFiles);
                    }
                    if (known.add(s.file())) {
                        if (files == null) {
                            files = new ArrayList<>(projectFiles);
                        }
                        files.add(s.file());
                    }
                }
                if (files != null) {
                    projectFiles = List.copyOf(files);
                    projectRelPaths = relativize(indexedRoot, projectFiles);
                }
            });
        });
    }

    /** The symbols of one file on disk, or none when it has no declaration rules or is too large to read. */
    private static List<Symbol> scanFile(Path file) throws IOException {
        String language = languageOf(file);
        if (!DeclarationScanner.supports(language) || Files.size(file) > MAX_FILE_BYTES) {
            return List.of();
        }
        return DeclarationScanner.scan(Files.readString(file), language);
    }

    /**
     * The language a file is indexed as: by its name, or — for a name with no extension that says nothing,
     * such as {@code bin/release} — by its {@code #!} line, the way the editor opens it. Only those files have
     * their first bytes read.
     */
    static String languageOf(Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        String language = LanguageRegistry.forFileName(name);
        if (!LanguageRegistry.plaintext().equals(language) || name.indexOf('.') >= 0) {
            return language;
        }
        byte[] head;
        try (java.io.InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(256);
        } catch (IOException | RuntimeException e) {
            return language;
        }
        String text = new String(head, java.nio.charset.StandardCharsets.ISO_8859_1);
        int nl = text.indexOf('\n');
        String extension = com.editora.text.Shebang.extension(nl < 0 ? text : text.substring(0, nl));
        return extension == null ? language : LanguageRegistry.forFileName("shebang." + extension);
    }

    /** {@code path} (a file, or a folder and everything under it) was deleted: stop offering it now. */
    void onFileDeleted(Path path) {
        linkGraph.onDeleted(path);
        forget(path);
    }

    /** The symbol index's half of a delete (and of a rename, which the link index follows on its own). */
    private void forget(Path path) {
        if (indexedRoot == null || path == null || !Vfs.isLocal(path)) {
            return;
        }
        List<Path> kept = new ArrayList<>(projectFiles.size());
        for (Path file : projectFiles) {
            if (file.startsWith(path)) {
                index.remove(file);
            } else {
                kept.add(file);
            }
        }
        if (kept.size() != projectFiles.size()) {
            projectFiles = List.copyOf(kept);
            projectRelPaths = relativize(indexedRoot, projectFiles);
        }
    }

    /** A rename is a delete of the old path now, and a re-walk before the new one is next asked about. */
    void onFileRenamed(Path from, Path to) {
        forget(from);
        markStale();
        linkUpdater.onRenamed(from, to);
    }

    /** Set by {@link #markStale}; cleared when a walk that started after the last mark lands. */
    private boolean stale;

    private long staleMarks;

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
        stamps = java.util.Map.of(); // asked for by name: every file is read again
        build(() -> host.setStatus(tr("status.index.built", index.symbolCount(), index.fileCount())));
    }

    /**
     * Go to Definition without a language server: the declaration of the name at the caret, from the file's
     * own declarations first, then the project's symbol index (one match jumps, several open the picker).
     * Returns false — and does nothing — for a language the index does not read, so the caller's message
     * stands.
     */
    boolean gotoDefinitionAtCaret() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || !DeclarationScanner.supports(b.getLanguage())) {
            return false;
        }
        org.fxmisc.richtext.CodeArea a = b.getFocusedArea() != null ? b.getFocusedArea() : b.getArea();
        String name = nameAt(a.getParagraph(a.getCurrentParagraph()).getText(), a.getCaretColumn(), b.getLanguage());
        if (name == null) {
            host.setStatus(tr("status.definition.noName"));
            return true;
        }
        if (definitionBusy) {
            host.setStatus(tr("status.index.building")); // the one in flight answers; a second press starts nothing
            return true;
        }
        // The buffer's own declarations are scanned off the FX thread (PERF-01, Pauta 6.2.0), on a copy of the text;
        // the jump is made only when the text and the caret are as they were.
        definitionBusy = true;
        String text = b.getContent();
        String language = b.getLanguage();
        int caret = a.getCaretPosition();
        int paragraph = a.getCurrentParagraph();
        long version = b.docVersion();
        com.editora.perf.OffFx.then(
                DEFINITION_SITE,
                () -> {
                    // JavaScript/TypeScript: a parameter or local in an enclosing scope first, as a reader resolves
                    // it (A105).
                    int scoped = com.editora.index.JsLocals.declarationOf(text, caret, name, language);
                    return scoped >= 0
                            ? localSymbol(text, scoped, name)
                            : nearestDeclaration(DeclarationScanner.scan(text, language), name, paragraph);
                },
                found -> {
                    definitionBusy = false;
                    if (host.activeBuffer() != b || b.docVersion() != version || a.getCaretPosition() != caret) {
                        return; // the user moved on: a jump now would pull them back
                    }
                    definitionFound(b, a, name, found.value());
                });
        return true;
    }

    /** A server-free Go to Definition is in flight: a second press waits for it. */
    private boolean definitionBusy;

    /** The buffer's own declaration of {@code name} when there is one, else the project index's. */
    private void definitionFound(EditorBuffer b, org.fxmisc.richtext.CodeArea a, String name, @Nullable Symbol local) {
        if (local != null) {
            if (b.getPath() != null) {
                ops.openAndGoto(b.getPath(), local.line(), local.column());
            } else {
                a.moveTo(local.line(), local.column());
                a.requestFollowCaret();
            }
            host.setStatus(tr("status.definition.fromIndex", name));
            return;
        }
        if (!isEnabled()) {
            host.setStatus(tr("status.definition.notFound", name));
            return;
        }
        if (staleButUsable()) {
            // A stale index answers now, each hit checked against its file as it is on disk, while the re-walk runs
            // behind it (perf review F5). Only "not found" waits for the re-walk: the name may have just arrived.
            Corpus corpus = corpus();
            refreshInBackground();
            definitionBusy = true;
            com.editora.perf.OffFx.then(SEARCH_SITE, () -> recheck(exactHits(corpus, name), name), answer -> {
                definitionBusy = false;
                List<SymbolIndex.Hit> exact = answer.or(List.of());
                if (exact.isEmpty()) {
                    definitionFromIndex(name);
                } else {
                    showDefinitions(name, exact);
                }
            });
            return;
        }
        definitionFromIndex(name);
    }

    /** The index's declarations of {@code name}, built (or re-walked) first when it is not. */
    private void definitionFromIndex(String name) {
        ensureBuilt(() -> {
            Corpus corpus = corpus();
            definitionBusy = true;
            com.editora.perf.OffFx.then(SEARCH_SITE, () -> exactHits(corpus, name), answer -> {
                definitionBusy = false;
                List<SymbolIndex.Hit> exact = answer.or(List.of());
                if (exact.isEmpty()) {
                    host.setStatus(tr("status.definition.notFound", name));
                } else {
                    showDefinitions(name, exact);
                }
            });
        });
    }

    private static List<SymbolIndex.Hit> exactHits(Corpus corpus, String name) {
        return corpus.searchSymbols(name, SymbolIndex.DEFAULT_LIMIT).stream()
                .filter(h -> h.symbol().name().equals(name))
                .toList();
    }

    /** One declaration jumps; several open the picker. */
    private void showDefinitions(String name, List<SymbolIndex.Hit> exact) {
        if (exact.size() == 1) {
            Symbol s = exact.get(0).symbol();
            ops.openAndGoto(exact.get(0).file(), s.line(), s.column());
            host.setStatus(tr("status.definition.fromIndex", name));
        } else {
            lastResults = exact;
            picker.show(host.window());
        }
    }

    /** Files a stale answer re-reads at most; past that it waits for the re-walk instead. */
    static final int RECHECK_FILES = 16;

    /**
     * {@code hits} from a stale index, as their files declare {@code name} now: each file is read again, a file that
     * is gone or no longer declares the name drops out, and a moved declaration answers where it is now. Empty when
     * the hits span more than {@link #RECHECK_FILES} files, so the caller waits for the re-walk. Blocking: off FX.
     */
    static List<SymbolIndex.Hit> recheck(List<SymbolIndex.Hit> hits, String name) {
        java.util.LinkedHashSet<Path> files = new java.util.LinkedHashSet<>();
        for (SymbolIndex.Hit hit : hits) {
            files.add(hit.file());
        }
        if (files.size() > RECHECK_FILES) {
            return List.of();
        }
        List<SymbolIndex.Hit> out = new ArrayList<>();
        for (Path file : files) {
            List<Symbol> now;
            try {
                now = scanFile(file);
            } catch (IOException | RuntimeException gone) {
                continue;
            }
            for (Symbol s : now) {
                if (s.name().equals(name)) {
                    out.add(new SymbolIndex.Hit(file, s, 0));
                }
            }
        }
        return List.copyOf(out);
    }

    /** The index was built for this project but changed since: it can answer while a re-walk refreshes it. */
    private boolean staleButUsable() {
        Path root = indexedRoot;
        return isEnabled() && stale && root != null && root.equals(ops.projectRoot());
    }

    /** Starts the re-walk of a stale index without parking anyone on it; a walk already running is enough. */
    private void refreshInBackground() {
        if (!building) {
            build(null);
        }
    }

    /** A symbol at {@code offset} of {@code text}, for jumping to a declaration found by offset. */
    private static Symbol localSymbol(String text, int offset, String name) {
        int line = 0;
        int lineStart = 0;
        for (int i = text.indexOf('\n'); i >= 0 && i < offset; i = text.indexOf('\n', i + 1)) {
            line++;
            lineStart = i + 1;
        }
        return new Symbol(name, com.editora.index.SymbolKind.VARIABLE, line, offset - lineStart, "");
    }

    /**
     * The declaration of {@code name} in a file that a use on {@code line} most likely means: the nearest one at or
     * above it, else the first below. Two functions that each declare a {@code parse} helper used to send a jump
     * from the second one to the first.
     */
    static @Nullable Symbol nearestDeclaration(List<Symbol> declarations, String name, int line) {
        Symbol above = null;
        Symbol below = null;
        for (Symbol s : declarations) {
            if (!s.name().equals(name)) {
                continue;
            }
            if (s.line() <= line) {
                above = s; // in document order, so the last one wins
            } else if (below == null) {
                below = s;
            }
        }
        return above != null ? above : below;
    }

    /**
     * The name at {@code column} of {@code line}: identifier characters, and in shell also {@code . : -}, which
     * its function names use ({@code pkg::fn}, {@code my-fn}); null when the caret is on none.
     */
    static @Nullable String nameAt(String line, int column, String language) {
        boolean shell = "shell".equals(language);
        boolean dollar = com.editora.editops.IdentifierChars.dollarIdentifiers(language); // `$el`, `#count` in JS
        java.util.function.IntPredicate part = c -> com.editora.editops.IdentifierChars.isPart((char) c, dollar)
                || (shell && (c == '.' || c == ':' || c == '-'));
        int start = Math.min(column, line.length());
        while (start > 0 && part.test(line.charAt(start - 1))) {
            start--;
        }
        int end = Math.min(column, line.length());
        while (end < line.length() && part.test(line.charAt(end))) {
            end++;
        }
        // Trailing/leading punctuation is not part of a name: `fn:` or `--flag`.
        while (start < end && !Character.isLetterOrDigit(line.charAt(start)) && line.charAt(start) != '_') {
            start++;
        }
        while (end > start && !Character.isLetterOrDigit(line.charAt(end - 1)) && line.charAt(end - 1) != '_') {
            end--;
        }
        if (dollar) { // `$` may start or end a JS name, and `#` starts a private one
            while (start > 0 && line.charAt(start - 1) == '$') {
                start--;
            }
            while (end < line.length() && line.charAt(end) == '$') {
                end++;
            }
            if (start > 0 && line.charAt(start - 1) == '#') {
                start--;
            }
        }
        return start < end ? line.substring(start, end) : null;
    }

    /** {@code index.gotoSymbol}: prompt for a name and jump to the declaration. */
    void gotoSymbol() {
        if (!isEnabled()) {
            host.setStatus(tr("status.index.disabled"));
            return;
        }
        if (isBuilt()) {
            promptForSymbol();
            return;
        }
        if (staleButUsable()) {
            // The prompt opens now; the search runs when the name is entered, on the re-walked index if it has landed.
            refreshInBackground();
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
    private void build(@Nullable Runnable then) {
        Path root = ops.projectRoot();
        if (root == null || !RemoteExec.runsFor(root)) {
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
        long marks = staleMarks;
        // A re-walk of the same project keeps what has not changed on disk; anything else reads every file.
        Prior prior = root.equals(indexedRoot) ? new Prior(stamps, index.freeze()) : Prior.NONE;
        AutoCloseable task = host.startBackgroundTask(tr("status.index.building"));
        worker.submit(() -> {
            Walked walked = walk(root, () -> gen != generation.get(), prior);
            Platform.runLater(() -> {
                building = false;
                close(task);
                if (gen != generation.get()) {
                    settleWaiters(); // they asked for "when it lands", and it has — with nothing to show
                    return; // a project switch or a rebuild superseded this walk
                }
                index.clear(); // a re-walk of a stale index replaces it; entries of vanished files must go
                stale = marks != staleMarks; // changed again while this walk ran: it may have missed it
                java.util.Map<Path, Stamp> kept = new java.util.HashMap<>(walked.stamps());
                for (Scanned s : walked.scanned()) {
                    if (!index.put(s.file(), s.symbols())) {
                        kept.remove(s.file()); // a cap refused it: the next walk must not take "nothing" as its answer
                    }
                }
                stamps = java.util.Map.copyOf(kept);
                lastReused = walked.reused();
                projectFiles = walked.files();
                projectRelPaths = relativize(root, projectFiles);
                indexedRoot = root;
                truncated = walked.truncated();
                if (then != null) {
                    then.run();
                }
                settleWaiters();
                if (truncated) {
                    // Last, so it is what the status bar is left showing: a partial index that looks
                    // complete sends the user hunting for a symbol that was simply never read.
                    host.setStatus(tr("status.index.truncated", projectFiles.size()));
                }
            });
        });
    }

    /**
     * One walk's yield: the files it saw, the symbols it found in them, whether the cap cut it short, the
     * {@link Stamp} of each file it read or reused, and how many it reused.
     */
    record Walked(
            List<Path> files, List<Scanned> scanned, boolean truncated, java.util.Map<Path, Stamp> stamps, int reused) {
        Walked(List<Path> files, List<Scanned> scanned, boolean truncated) {
            this(files, scanned, truncated, java.util.Map.of(), 0);
        }
    }

    /**
     * A file as it was on disk when its declarations were read: its modification time, its size, and the language
     * it was read as. An equal stamp on the next walk means the symbols from last time still stand.
     */
    record Stamp(java.nio.file.attribute.FileTime modified, long size, String language) {}

    /**
     * A file modified this close to the moment it was read may change again within the file system's clock
     * resolution without its stamp changing, so its stamp is not kept and the next walk reads it again (Git's
     * "racily clean" rule).
     */
    static final long SETTLE_MILLIS = 2_000;

    /** The index a re-walk replaces: the stamps it recorded and the symbols it holds, both unchanging. */
    record Prior(java.util.Map<Path, Stamp> stamps, SymbolIndex.Frozen symbols) {
        static final Prior NONE = new Prior(java.util.Map.of(), SymbolIndex.Frozen.EMPTY);

        /** {@code file}'s symbols from last time when it is unchanged since, else null: read it again. */
        @Nullable
        List<Symbol> reuse(Path file, Stamp now) {
            Stamp was = stamps.get(file);
            return was != null && was.equals(now) ? symbols.symbolsIn(file) : null;
        }
    }

    record Scanned(Path file, List<Symbol> symbols) {}

    /** The blocking half — runs on {@link #worker}, touches nothing that belongs to the FX thread. */
    private Walked walk(Path root, java.util.function.BooleanSupplier superseded, Prior prior) {
        if (superseded.getAsBoolean()) {
            return new Walked(List.of(), List.of(), false);
        }
        if (Vfs.isRemote(root)) {
            return remoteWalk(root, ops.respectGitignore(), maxFiles);
        }
        GitignoreFilter ignore = ops.respectGitignore() ? GitignoreFilter.load(root) : GitignoreFilter.NONE;
        return walk(root, ignore, maxFiles, superseded, prior);
    }

    /**
     * Walks {@code root} through the shared {@link ProjectWalk}, so an ignored directory is pruned before
     * anything under it is listed, read, or counted: {@code target/} and {@code node_modules/} used to be
     * walked in full, read file by file, offered in Search Everywhere, and charged against the cap — which a
     * large {@code node_modules} exhausted, leaving the project's own sources unindexed without a word.
     */
    static Walked walk(Path root, GitignoreFilter ignore, int maxFiles) {
        return walk(root, ignore, maxFiles, () -> false);
    }

    /**
     * As above, ending early once {@code superseded}: a project switch or a rebuild has already discarded
     * this walk's result, and the walk that replaces it is waiting for the same single thread.
     */
    static Walked walk(Path root, GitignoreFilter ignore, int maxFiles, java.util.function.BooleanSupplier superseded) {
        return walk(root, ignore, maxFiles, superseded, Prior.NONE);
    }

    /**
     * As above, taking from {@code prior} the symbols of every file whose {@link Stamp} is unchanged instead of
     * reading it again: a re-walk after a branch switch costs a stat per file plus a read of the ones that changed.
     */
    static Walked walk(
            Path root,
            GitignoreFilter ignore,
            int maxFiles,
            java.util.function.BooleanSupplier superseded,
            Prior prior) {
        List<Scanned> out = new ArrayList<>();
        java.util.Map<Path, Stamp> stamps = new java.util.HashMap<>();
        int[] reused = {0};
        // Every file the walk sees, not only the ones with symbols: Search Everywhere needs to offer
        // files too, and this walk is already paying for the traversal. Doing it separately would mean a
        // second pass over the same tree for the same information.
        List<Path> files = new ArrayList<>();
        ProjectWalk.Visitor scan = (p, rel, attrs) -> {
            // A symlink to a file is still a file to open (the walk reads attributes without
            // following links, so it reports the link itself).
            if (!attrs.isRegularFile() && !(attrs.isSymbolicLink() && Files.isRegularFile(p))) {
                return ProjectWalk.Verdict.SKIP;
            }
            files.add(p);
            String language = languageOf(p);
            // No declaration rules means no symbols whatever the file says: don't read 2 MB of a
            // lock file, an image or a minified bundle to learn that.
            if (!DeclarationScanner.supports(language)) {
                return ProjectWalk.Verdict.ACCEPT;
            }
            try {
                // Of the target, when p is a link: the walk's attributes describe the link itself.
                java.nio.file.attribute.BasicFileAttributes target = attrs.isRegularFile()
                        ? attrs
                        : Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes.class);
                if (target.size() > MAX_FILE_BYTES) {
                    return ProjectWalk.Verdict.ACCEPT;
                }
                Stamp stamp = new Stamp(target.lastModifiedTime(), target.size(), language);
                List<Symbol> symbols = prior.reuse(p, stamp);
                if (symbols != null) {
                    reused[0]++;
                    stamps.put(p, stamp);
                } else {
                    long readAt = System.currentTimeMillis();
                    symbols = DeclarationScanner.scan(Files.readString(p), language);
                    if (readAt - stamp.modified().toMillis() > SETTLE_MILLIS) {
                        stamps.put(p, stamp);
                    }
                }
                if (!symbols.isEmpty()) {
                    out.add(new Scanned(p, symbols));
                }
            } catch (IOException | RuntimeException ex) {
                // An unreadable or non-UTF-8 file is skipped, not fatal: the rest of the tree is
                // still worth indexing, and a navigation index has no business failing loudly.
            }
            return ProjectWalk.Verdict.ACCEPT;
        };
        if (ignore.prunesDependencies()) {
            ProjectWalk.Outcome outcome = ProjectWalk.walk(
                    root, new ProjectWalk.Options(Integer.MAX_VALUE, maxFiles, ignore, superseded), scan);
            return new Walked(List.copyOf(files), out, outcome.truncated(), stamps, reused[0]);
        }
        // Dependency folders walked too ("Respect .gitignore" off): the project's own files first, then what the cap
        // leaves for node_modules. In one walk node_modules came before src/ and took all 50,000 places (JS E107).
        ProjectWalk.Outcome own = ProjectWalk.walk(
                root,
                new ProjectWalk.Options(Integer.MAX_VALUE, maxFiles, ignore, superseded),
                new ProjectWalk.Visitor() {
                    @Override
                    public ProjectWalk.Verdict file(Path p, String rel, java.nio.file.attribute.BasicFileAttributes a) {
                        return scan.file(p, rel, a);
                    }

                    @Override
                    public boolean enter(Path dir, String rel) {
                        return !com.editora.search.DependencyDirs.isDependencyDir(rel);
                    }
                });
        if (own.truncated() || superseded.getAsBoolean()) {
            return new Walked(List.copyOf(files), out, own.truncated(), stamps, reused[0]);
        }
        ProjectWalk.Outcome deps = ProjectWalk.walk(
                root,
                new ProjectWalk.Options(Integer.MAX_VALUE, maxFiles - own.accepted(), ignore, superseded),
                (p, rel, attrs) -> com.editora.search.DependencyDirs.inDependencyDir(rel)
                        ? scan.file(p, rel, attrs)
                        : ProjectWalk.Verdict.SKIP);
        return new Walked(List.copyOf(files), out, deps.truncated(), stamps, reused[0]);
    }

    /**
     * The index of a project on a remote host: its files as the host lists them ({@link RemoteFiles}), in one
     * command instead of a directory listing per folder over SFTP. No symbols: finding them means reading
     * every source file across the network. Go to File works; Go to Symbol has nothing to offer.
     */
    static Walked remoteWalk(Path root, boolean respectGitignore, int maxFiles) {
        RemoteFiles.Listing listing = RemoteFiles.list(root, respectGitignore, maxFiles);
        if (listing == null) {
            return sftpWalk(root, respectGitignore, maxFiles);
        }
        List<Path> files = new ArrayList<>(listing.paths().size());
        for (String relative : listing.paths()) {
            files.add(root.resolve(relative));
        }
        return new Walked(List.copyOf(files), List.of(), listing.truncated());
    }

    /**
     * The last resort for a remote project, when its host lists nothing because it runs no commands (remote
     * commands are switched off, or the account has no shell): the folder walked over SFTP with the same rules
     * as a local project, a directory listing per folder, names only. Slow on a large tree, but Go to File
     * offered nothing at all before.
     */
    static Walked sftpWalk(Path root, boolean respectGitignore, int maxFiles) {
        List<Path> files = new ArrayList<>();
        GitignoreFilter ignore = respectGitignore ? GitignoreFilter.load(root) : GitignoreFilter.NONE;
        ProjectWalk.Outcome outcome = ProjectWalk.walk(
                root, new ProjectWalk.Options(Integer.MAX_VALUE, maxFiles, ignore, () -> false), (p, rel, attrs) -> {
                    if (!attrs.isRegularFile()) {
                        return ProjectWalk.Verdict.SKIP;
                    }
                    files.add(p);
                    return ProjectWalk.Verdict.ACCEPT;
                });
        return new Walked(List.copyOf(files), List.of(), outcome.truncated());
    }

    private void promptForSymbol() {
        if (index.symbolCount() == 0) {
            host.setStatus(tr("status.index.empty"));
            return;
        }
        host.promptText(tr("index.gotoSymbol.title"), tr("index.gotoSymbol.prompt"), "", query -> {
            // Searched off the FX thread (PERF-01, Pauta 6.2.0) on the index as it is now; the picker opens when the
            // answer lands.
            Corpus corpus = corpus();
            com.editora.perf.OffFx.then(
                    SEARCH_SITE, () -> corpus.searchSymbols(query, SymbolIndex.DEFAULT_LIMIT), answer -> {
                        lastResults = answer.or(List.of());
                        if (lastResults.isEmpty()) {
                            host.setStatus(tr("status.index.noMatch", query));
                            return;
                        }
                        picker.show(host.window());
                    });
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
        return indexedRoot != null && !stale;
    }

    /** {@link #ensureBuilt} callbacks parked on the walk in flight; run when it lands, superseded or not. */
    private final List<Runnable> waiters = new ArrayList<>();

    private void settleWaiters() {
        List<Runnable> due = List.copyOf(waiters);
        waiters.clear(); // first: a callback may call ensureBuilt again
        due.forEach(Runnable::run);
    }

    /**
     * Builds if needed, then runs {@code then} — the entry point for a caller that wants results now.
     *
     * <p>{@code then} always runs, exactly once: at once when the index is built or cannot be (switched off,
     * no local project), else when the walk — this call's or one already in flight — lands. A caller that
     * was dropped on those exits never refreshed: Search Everywhere kept the previous query's rows, and Enter
     * ran whatever they happened to start with.
     */
    void ensureBuilt(Runnable then) {
        if (!isEnabled() || isBuilt()) {
            then.run();
            return;
        }
        Path root = ops.projectRoot();
        if (root == null || !RemoteExec.runsFor(root)) {
            host.setStatus(tr("status.index.noProject"));
            then.run();
            return;
        }
        waiters.add(then);
        if (building) {
            host.setStatus(tr("status.index.building"));
        } else {
            build(null);
        }
    }

    /**
     * Where Search Everywhere's file rows come from: the Project panel's project index, the list and the ranking
     * the Map search and the tree's filter use (Map refactor stage 2). Without one, this index's own walk.
     */
    private @Nullable ProjectIndex fileIndex;

    void setFileIndex(@Nullable ProjectIndex index) {
        fileIndex = index;
    }

    /**
     * {@link #ensureBuilt}, then the project index's file list too, so Search Everywhere's file rows are there: runs
     * {@code then} exactly once, at once when both are in hand.
     */
    void ensureSearchable(Runnable then) {
        ensureBuilt(() -> {
            ProjectIndex files = fileIndex;
            Path root = ops.projectRoot();
            if (files == null || root == null || !isEnabled()) {
                then.run();
            } else {
                files.whenListed(root, then);
            }
        });
    }

    /**
     * The index as it is now, to search off the FX thread (PERF-01, Pauta 6.2.0): taken on the FX thread, where the
     * index changes, it copies no symbol (see {@link SymbolIndex#freeze}) and the file lists are unchanging already.
     */
    Corpus corpus() {
        return new Corpus(
                isEnabled(),
                indexedRoot,
                ops.projectRoot(),
                projectFiles,
                projectRelPaths,
                isEnabled() ? index.freeze() : SymbolIndex.Frozen.EMPTY,
                fileIndex);
    }

    /**
     * The files and symbols one moment of the index knows, searched on any thread: every list in it is unchanging,
     * and the project index ({@link ProjectIndex#searchListed}) is thread-safe.
     */
    record Corpus(
            boolean enabled,
            @Nullable Path indexedRoot,
            @Nullable Path projectRoot,
            List<Path> files,
            List<String> relPaths,
            SymbolIndex.Frozen symbols,
            @Nullable ProjectIndex fileIndex) {

        /** Search Everywhere's file rows for {@code query}: the name, then its folder relative to the root. */
        List<com.editora.search.SearchEverywhere.Item> fileItems(String query) {
            BlockingSites.enter(SEARCH_SITE);
            ProjectIndex listed = fileIndex;
            if (listed != null) {
                Path root = projectRoot;
                return !enabled || query == null || query.isBlank() || root == null
                        ? List.of()
                        : IndexCoordinator.fileItems(
                                listed.searchListed(root, query, ProjectIndex.Kinds.FILES, FILE_ROWS * 2), query);
            }
            List<com.editora.search.SearchEverywhere.Item> out = new ArrayList<>();
            for (FileHit hit : com.editora.search.ProjectWalk.sourcesFirst(
                    searchFiles(query, FILE_ROWS * 2), FileHit::relativePath, FILE_ROWS)) {
                String name = hit.file().getFileName().toString();
                String parent = hit.relativePath().contains("/")
                        ? hit.relativePath().substring(0, hit.relativePath().lastIndexOf('/'))
                        : "";
                out.add(new com.editora.search.SearchEverywhere.Item(
                        com.editora.search.SearchEverywhere.Kind.FILE, name, parent, hit.score(), hit.file()));
            }
            return out;
        }

        /** Search Everywhere's symbol rows for {@code query}: the name, then its container and place. */
        List<com.editora.search.SearchEverywhere.Item> symbolItems(String query) {
            List<com.editora.search.SearchEverywhere.Item> out = new ArrayList<>();
            for (SymbolIndex.Hit hit : searchSymbols(query, 40)) {
                String container = hit.symbol().container();
                String where = hit.file().getFileName() + ":" + (hit.symbol().line() + 1);
                out.add(new com.editora.search.SearchEverywhere.Item(
                        com.editora.search.SearchEverywhere.Kind.SYMBOL,
                        hit.symbol().name(),
                        container.isEmpty() ? where : container + " — " + where,
                        hit.score() + SymbolRanking.exactNameBonus(hit.symbol().name(), query), // as library rows
                        hit));
            }
            return out;
        }

        /** Ranked symbol hits for {@code query}; empty when the index has not been built. */
        List<SymbolIndex.Hit> searchSymbols(String query, int limit) {
            BlockingSites.enter(SEARCH_SITE);
            return enabled ? symbols.search(query, limit) : List.of();
        }

        /**
         * Ranked project files for {@code query}, matched on the path relative to the root so a query can name a
         * directory as well as a file name.
         *
         * <p>Bounded selection over cached relative paths, for the reasons spelled out on {@code SymbolIndex.search}:
         * sorting every match to discard all but {@code limit} was the part that scaled worst (#876). The ranking is
         * a total order already — two files cannot share a relative path — so unlike the symbol side it needs no
         * scan-index key to reproduce the old output exactly.
         */
        List<FileHit> searchFiles(String query, int limit) {
            BlockingSites.enter(SEARCH_SITE);
            if (!enabled || query == null || query.isBlank() || indexedRoot == null || limit <= 0) {
                return List.of();
            }
            // Ordered worst-first, so the head is the entry a better candidate evicts.
            PriorityQueue<FileHit> keep = new PriorityQueue<>(FILE_RANK.reversed());
            for (int i = 0; i < relPaths.size(); i++) {
                String rel = relPaths.get(i);
                // scoreOfPath, not ofPath: a FileHit carries only a score, and the picker re-derives the
                // highlight at render time for the rows it draws.
                int score = com.editora.search.FuzzyMatch.scoreOfPath(rel, query);
                if (score == com.editora.search.FuzzyMatch.NO_SCORE) {
                    continue;
                }
                if (keep.size() == limit) {
                    FileHit worst = keep.peek();
                    // >= 0, not > 0: on an exact tie the incumbent stays, which is what the stable sort this
                    // replaces did.
                    if (score < worst.score() || (score == worst.score() && rel.compareTo(worst.relativePath()) >= 0)) {
                        continue;
                    }
                    keep.poll();
                }
                keep.add(new FileHit(files.get(i), rel, score));
            }
            List<FileHit> best = new ArrayList<>(keep);
            best.sort(FILE_RANK);
            return List.copyOf(best);
        }

        /**
         * The indexed project files {@code keep} accepts, at most {@code limit}, in walk order — Related File's pages
         * that may link a stylesheet (HTML review C16). Empty while the index is off or not built.
         */
        List<Path> filesMatching(java.util.function.Predicate<Path> keep, int limit) {
            BlockingSites.enter(SEARCH_SITE);
            if (!enabled || indexedRoot == null) {
                return List.of();
            }
            List<Path> out = new ArrayList<>();
            for (Path f : files) {
                if (out.size() >= limit) {
                    break;
                }
                if (keep.test(f)) {
                    out.add(f);
                }
            }
            return List.copyOf(out);
        }
    }

    /**
     * The project index's answer as Search Everywhere's rows, in its order. A row's score only orders it in its
     * group ({@link com.editora.search.SearchEverywhere#merge} sorts by score): the group competes with commands and
     * symbols on the best fuzzy score of its paths, as it did, and each row after the first scores one less.
     */
    static List<com.editora.search.SearchEverywhere.Item> fileItems(ProjectMapSearchResult found, String query) {
        // Build output after the sources (asked for twice the rows so it cannot crowd them out of the cut).
        List<ProjectMapSearchResult.Match> matches = com.editora.search.ProjectWalk.sourcesFirst(
                found.matches(), ProjectMapSearchResult.Match::relativePath, FILE_ROWS);
        int best = com.editora.search.FuzzyMatch.NO_SCORE;
        for (ProjectMapSearchResult.Match match : matches) {
            best = Math.max(best, com.editora.search.FuzzyMatch.scoreOfPath(match.relativePath(), query));
        }
        int top = best == com.editora.search.FuzzyMatch.NO_SCORE ? 0 : best;
        List<com.editora.search.SearchEverywhere.Item> out = new ArrayList<>();
        for (ProjectMapSearchResult.Match match : matches) {
            String rel = match.relativePath();
            int slash = rel.lastIndexOf('/');
            out.add(new com.editora.search.SearchEverywhere.Item(
                    com.editora.search.SearchEverywhere.Kind.FILE,
                    match.label(),
                    slash < 0 ? "" : rel.substring(0, slash),
                    top - out.size(),
                    match.path()));
        }
        return out;
    }

    /** As {@link Corpus#fileItems} on the index as it is now. A test's; the app searches a corpus off FX. */
    List<com.editora.search.SearchEverywhere.Item> fileItems(String query) {
        return corpus().fileItems(query);
    }

    /** As {@link Corpus#symbolItems} on the index as it is now. A test's; the app searches a corpus off FX. */
    List<com.editora.search.SearchEverywhere.Item> symbolItems(String query) {
        return corpus().symbolItems(query);
    }

    /** As {@link Corpus#searchSymbols} on the index as it is now. A test's; the app searches a corpus off FX. */
    List<SymbolIndex.Hit> searchSymbols(String query, int limit) {
        return corpus().searchSymbols(query, limit);
    }

    /** As {@link Corpus#searchFiles} on the index as it is now. A test's; the app searches a corpus off FX. */
    List<FileHit> searchFiles(String query, int limit) {
        return corpus().searchFiles(query, limit);
    }

    /** The project folder the index was built from, or null. */
    @Nullable
    Path indexedRoot() {
        return indexedRoot;
    }

    /** How many files Search Everywhere lists for a query. */
    static final int FILE_ROWS = 40;

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
        linkUpdater.dispose();
        linkGraph.dispose();
        headingPicker.dispose();
    }
}
