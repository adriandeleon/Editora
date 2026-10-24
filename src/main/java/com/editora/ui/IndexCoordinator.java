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
        // too: an extension-less script is shell by its #! line, which its file name does not say.
        String language = buffer.getLanguage();
        index.put(file, DeclarationScanner.scan(buffer.getContent(), language));
        if (!projectFiles.contains(file)) {
            // A file first saved after the walk: its symbols were found but the file itself was not, so
            // Search Everywhere offered the class and not the file it lives in.
            List<Path> files = new ArrayList<>(projectFiles);
            files.add(file);
            projectFiles = List.copyOf(files);
            projectRelPaths = relativize(indexedRoot, projectFiles);
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
        for (ProjectPanel.FsChange change : changes) {
            Path path = change.path();
            if (path == null || !Vfs.isLocal(path) || !path.startsWith(root)) {
                continue;
            }
            if (change.kind() == ProjectPanel.FsKind.DELETED) {
                touched.remove(path);
                forget(path);
            } else if (!touched.contains(path)) {
                touched.add(path);
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
                        directory = true; // a new folder: nobody reported what is inside it
                        break;
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
        Symbol local = nearestDeclaration(
                DeclarationScanner.scan(b.getContent(), b.getLanguage()), name, a.getCurrentParagraph());
        if (local != null) {
            if (b.getPath() != null) {
                ops.openAndGoto(b.getPath(), local.line(), local.column());
            } else {
                a.moveTo(local.line(), local.column());
                a.requestFollowCaret();
            }
            host.setStatus(tr("status.definition.fromIndex", name));
            return true;
        }
        if (!isEnabled()) {
            host.setStatus(tr("status.definition.notFound", name));
            return true;
        }
        ensureBuilt(() -> {
            List<SymbolIndex.Hit> exact = index.search(name, SymbolIndex.DEFAULT_LIMIT).stream()
                    .filter(h -> h.symbol().name().equals(name))
                    .toList();
            if (exact.isEmpty()) {
                host.setStatus(tr("status.definition.notFound", name));
            } else if (exact.size() == 1) {
                Symbol s = exact.get(0).symbol();
                ops.openAndGoto(exact.get(0).file(), s.line(), s.column());
                host.setStatus(tr("status.definition.fromIndex", name));
            } else {
                lastResults = exact;
                picker.show(host.window());
            }
        });
        return true;
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
        AutoCloseable task = host.startBackgroundTask(tr("status.index.building"));
        worker.submit(() -> {
            Walked walked = walk(root, () -> gen != generation.get());
            Platform.runLater(() -> {
                building = false;
                close(task);
                if (gen != generation.get()) {
                    settleWaiters(); // they asked for "when it lands", and it has — with nothing to show
                    return; // a project switch or a rebuild superseded this walk
                }
                index.clear(); // a re-walk of a stale index replaces it; entries of vanished files must go
                stale = marks != staleMarks; // changed again while this walk ran: it may have missed it
                for (Scanned s : walked.scanned()) {
                    index.put(s.file(), s.symbols());
                }
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

    /** One walk's yield: the files it saw, the symbols it found in them, and whether the cap cut it short. */
    record Walked(List<Path> files, List<Scanned> scanned, boolean truncated) {}

    record Scanned(Path file, List<Symbol> symbols) {}

    /** The blocking half — runs on {@link #worker}, touches nothing that belongs to the FX thread. */
    private Walked walk(Path root, java.util.function.BooleanSupplier superseded) {
        if (superseded.getAsBoolean()) {
            return new Walked(List.of(), List.of(), false);
        }
        if (Vfs.isRemote(root)) {
            return remoteWalk(root, ops.respectGitignore(), maxFiles);
        }
        GitignoreFilter ignore = ops.respectGitignore() ? GitignoreFilter.load(root) : GitignoreFilter.NONE;
        return walk(root, ignore, maxFiles, superseded);
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
        List<Scanned> out = new ArrayList<>();
        // Every file the walk sees, not only the ones with symbols: Search Everywhere needs to offer
        // files too, and this walk is already paying for the traversal. Doing it separately would mean a
        // second pass over the same tree for the same information.
        List<Path> files = new ArrayList<>();
        ProjectWalk.Outcome outcome = ProjectWalk.walk(
                root, new ProjectWalk.Options(Integer.MAX_VALUE, maxFiles, ignore, superseded), (p, rel, attrs) -> {
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
                        if (Files.size(p) > MAX_FILE_BYTES) { // of the target, when p is a link
                            return ProjectWalk.Verdict.ACCEPT;
                        }
                        List<Symbol> symbols = DeclarationScanner.scan(Files.readString(p), language);
                        if (!symbols.isEmpty()) {
                            out.add(new Scanned(p, symbols));
                        }
                    } catch (IOException | RuntimeException ex) {
                        // An unreadable or non-UTF-8 file is skipped, not fatal: the rest of the tree is
                        // still worth indexing, and a navigation index has no business failing loudly.
                    }
                    return ProjectWalk.Verdict.ACCEPT;
                });
        return new Walked(List.copyOf(files), out, outcome.truncated());
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
        linkUpdater.dispose();
        linkGraph.dispose();
        headingPicker.dispose();
    }
}
