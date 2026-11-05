package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;

import javafx.application.Platform;

import com.editora.config.PathDisplay;
import com.editora.lsp.SymbolNode;
import com.editora.maven.PomFiles;
import com.editora.text.FoldRegions.Region;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The Project Map's structure columns. A file row's Structure icon opens a column of the file's declarations —
 * the outline the Structure tool window shows ({@link StructureOutline}) — and a declaration that holds others
 * (a class's members, a heading's subsections) opens a further column, as a folder does.
 *
 * <p>This class keeps which files and declarations have a column open, the outline of each such file, and turns
 * them into rows the map lays out like any other: {@link ProjectMapModel.Entry#symbol} entries whose made-up
 * paths sit below their file, one segment per declaration ({@code App.java/…sApp~0/…smain~0}). A segment is the
 * declaration's name and its place among same-named siblings, not its line, so an open column and an open card
 * survive an edit that moves the declaration. A file has no children on disk, so no such path can name a real
 * file.
 *
 * <p>Outlines are built off the FX thread on one daemon {@code project-map-outline} thread, from the open
 * buffer's text (unsaved edits included, the language server's symbols when the Structure window has them for
 * that buffer) or from the file decoded as a preview card reads it. Each result is checked against the newest
 * request for its file before it is applied.
 */
final class ProjectMapStructure {

    /** Rows one structure column shows; a longer outline ends in a row that says how many were left out. */
    static final int MAX_ROWS = 1_000;
    /** Characters of a declaration's name kept in its path segment. */
    private static final int SEGMENT_STEM_LIMIT = 60;
    /** Prefix of a declaration's path segment. */
    static final String SEGMENT_PREFIX = "…s";

    /** A declaration of an outlined file: its path on the map, what its row shows, and its own declarations. */
    record Node(Path path, ProjectMapModel.Symbol symbol, List<Node> children) {}

    /** What a file looked like when it was outlined: its size and time on disk, or its open buffer's text. */
    private record Stamp(long size, long modifiedMillis, int textHash, int textLength, boolean buffer, long version) {
        Stamp withVersion(long newVersion) {
            return new Stamp(size, modifiedMillis, textHash, textLength, buffer, newVersion);
        }
    }

    /** Why a file has no outline to show. */
    enum Problem {
        NONE,
        LOADING,
        UNREADABLE
    }

    /**
     * One file's outline: its declarations by path, and how its source looked.
     *
     * @param partial the source was cut at the read cap: declarations past it are not in the outline
     */
    private record Outline(
            @Nullable Stamp stamp, List<Node> roots, Map<Path, Node> byPath, Problem problem, boolean partial) {
        static final Outline LOADING = new Outline(null, List.of(), Map.of(), Problem.LOADING, false);

        Outline withStamp(Stamp newStamp) {
            return new Outline(newStamp, roots, byPath, problem, partial);
        }
    }

    /**
     * The declarations of the last outline that replaced another under a new path — a method whose parameter list
     * was edited — each mapped to the path it has now (see {@link #successor}). Kept until the next outline.
     */
    private Map<Path, Path> successors = Map.of();

    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "project-map-outline");
        thread.setDaemon(true);
        return thread;
    });
    /** Files and declarations whose structure column is open. */
    private final Set<Path> open = new LinkedHashSet<>();

    private final Map<Path, Outline> outlines = new HashMap<>();
    /** The newest outline request per file; an older result is dropped. */
    private final Map<Path, Long> requests = new HashMap<>();
    /** Files whose column was just opened: a lone top-level declaration with members opens its column too. */
    private final Set<Path> autoExpand = new HashSet<>();

    private final Map<String, Boolean> supported = new HashMap<>();
    /** Buffers past {@link #DEBOUNCE_CHARS} waiting for their text to settle before they are outlined again. */
    private final Map<Path, javafx.animation.PauseTransition> debounced = new HashMap<>();
    /** The text version each {@link #debounced} buffer waits with: a newer one starts the wait again. */
    private final Map<Path, Long> debouncedVersion = new HashMap<>();
    /** The project's main language was warmed for this root (see {@link #warmFor}). */
    private boolean warmed;

    private final Function<Path, ProjectMapPreview.@Nullable Content> content;
    private Function<Path, @Nullable List<SymbolNode>> serverSymbols = path -> null;
    private Runnable onChanged = () -> {};
    private java.util.function.Consumer<Path> onFileChanged = file -> {};
    private long requestSequence;
    private boolean disposed;

    ProjectMapStructure(Function<Path, ProjectMapPreview.@Nullable Content> content) {
        this.content = content == null ? path -> null : content;
    }

    /** Where an open buffer's server outline comes from (the Structure window's, for its own buffer). */
    void setServerSymbols(Function<Path, @Nullable List<SymbolNode>> symbols) {
        serverSymbols = symbols == null ? path -> null : symbols;
    }

    /**
     * A language server's outline of a file no tab has open (Map explore round, position 10): asked only of a server
     * that already runs in this window, never one started for it.
     */
    interface ClosedFileSymbols {
        /** Whether a server running now would outline {@code file}. FX thread; never starts a server. */
        boolean serves(Path file);

        /** Asks that server for {@code file}'s outline of {@code text}; {@code found} on the FX thread, null for none. */
        void symbols(Path file, String text, java.util.function.Consumer<@Nullable List<SymbolNode>> found);
    }

    private static final ClosedFileSymbols NO_CLOSED_SYMBOLS = new ClosedFileSymbols() {
        @Override
        public boolean serves(Path file) {
            return false;
        }

        @Override
        public void symbols(Path file, String text, java.util.function.Consumer<@Nullable List<SymbolNode>> found) {
            found.accept(null);
        }
    };

    private ClosedFileSymbols closedSymbols = NO_CLOSED_SYMBOLS;

    /** How long a closed file's outline waits for a running server's before the heuristic one is shown. */
    static final int SERVER_WAIT_MILLIS = 1500;

    /** A closed file longer than this is outlined by the heuristic only: it is not sent to a server. */
    static final int MAX_SERVER_OUTLINE_CHARS = 1_000_000;

    /** The stamp on disk each closed file's server was last asked at: asked again only when the file changed. */
    private final Map<Path, @Nullable Stamp> serverAsked = new HashMap<>();

    /** The closed files whose outline came from a running server. For tests and the spec. */
    private final Set<Path> serverOutlined = new HashSet<>();

    /** Where the outlines of files with no tab come from when a server for their language runs (position 10). */
    void setClosedFileSymbols(@Nullable ClosedFileSymbols symbols) {
        closedSymbols = symbols == null ? NO_CLOSED_SYMBOLS : symbols;
    }

    /** Whether {@code file}'s outline, a file with no tab, came from a running language server. */
    boolean outlinedByServer(Path file) {
        return serverOutlined.contains(file);
    }

    /** Told on the FX thread when an outline arrives or changes: the rows and cards need to follow. */
    void setOnChanged(Runnable callback) {
        onChanged = callback == null ? () -> {} : callback;
    }

    /**
     * Told on the FX thread, with the file, when its outline arrives or changes: what shows only that file's outline
     * (a card's Structure column, its declaration cards) follows it alone (review 3, D8).
     */
    void setOnFileChanged(java.util.function.Consumer<Path> callback) {
        onFileChanged = callback == null ? file -> {} : callback;
    }

    /** Whether a file of this name gets a Structure icon. By name only; the answer is kept per name. */
    boolean supports(ProjectMapModel.Entry entry) {
        return entry.isFile() && supports(entry.name());
    }

    /** Whether the file at {@code file} gets a Structure button on its card (see {@link #supports(String)}). */
    boolean supports(@Nullable Path file) {
        Path name = file == null ? null : file.getFileName();
        return name != null && supports(name.toString());
    }

    private boolean supports(String fileName) {
        String name = fileName.toLowerCase(Locale.ROOT);
        Boolean known = supported.get(name);
        if (known == null) {
            if (supported.size() > 4_096) {
                supported.clear();
            }
            known = StructureOutline.supports(fileName);
            supported.put(name, known);
        }
        return known;
    }

    /** Whether the file or declaration at {@code path} has its column open. */
    boolean isOpen(@Nullable Path path) {
        return path != null && open.contains(path);
    }

    boolean hasOpenColumns() {
        return !open.isEmpty();
    }

    /** The files whose structure column is open. */
    Set<Path> openFiles() {
        Set<Path> files = new LinkedHashSet<>();
        for (Path path : open) {
            if (!outlines.containsKey(path) && fileOf(path) != null) {
                continue; // a declaration
            }
            files.add(path);
        }
        return files;
    }

    /**
     * Opens or closes the column of a file row or a declaration row. Closing one closes every column below
     * it. Returns whether it is open now.
     */
    boolean toggle(ProjectMapModel.Entry entry) {
        Path path = entry.path();
        if (open.contains(path)) {
            close(path);
            return false;
        }
        if (entry.isSymbol()) {
            ProjectMapModel.Symbol symbol = entry.symbol();
            if (symbol == null || !symbol.branches()) {
                return false;
            }
            open.add(path);
            return true;
        }
        if (!supports(entry)) {
            return false;
        }
        open.add(path);
        autoExpand.add(path);
        request(path, null);
        return true;
    }

    /** Closes the column of {@code path} and every column below it. */
    void close(Path path) {
        open.removeIf(candidate -> candidate.startsWith(path));
        autoExpand.remove(path);
    }

    /**
     * Collapse All / Close Other Branches (Map explore, position 13): closes every structure column that is not
     * on the way to {@code target} ({@code keepOwn}: the target's own column stays as well). Whether any closed.
     */
    boolean canvasCloseOthers(Path target, boolean keepOwn) {
        List<Path> off = new ArrayList<>();
        for (Path path : open) {
            if (!(target.startsWith(path) && (keepOwn || !path.equals(target)))) {
                off.add(path);
            }
        }
        off.forEach(this::close);
        return !off.isEmpty();
    }

    /** Closes everything (a new root). */
    void clear() {
        open.clear();
        outlines.clear();
        requests.clear();
        autoExpand.clear();
        debounced.values().forEach(javafx.animation.PauseTransition::stop);
        debounced.clear();
        debouncedVersion.clear();
        warmed = false;
        serverAsked.clear();
        serverOutlined.clear();
    }

    /** Carries open columns across an in-app rename or move; the moved file is outlined again. */
    void pathRenamed(Path from, Path to) {
        List<Path> movedFiles = new ArrayList<>();
        for (Path file : openFiles()) {
            Path moved = file.startsWith(from) ? ProjectMapModel.remap(file, from, to) : null;
            if (moved != null) {
                movedFiles.add(moved);
            }
        }
        List<Path> moved = open.stream()
                .map(path -> ProjectMapModel.remap(path, from, to))
                .filter(java.util.Objects::nonNull)
                .toList();
        open.clear();
        open.addAll(moved);
        outlines.keySet().removeIf(path -> path.startsWith(from));
        requests.keySet().removeIf(path -> path.startsWith(from));
        for (Path file : movedFiles) {
            request(file, null); // its declarations' paths moved with it; a new name may be another language
        }
    }

    /**
     * The map's rows: {@code loaded} followed by the rows of every open structure column whose file is among
     * them. A file still being outlined, one that cannot be read and one with no declarations show one
     * explanatory row instead.
     */
    List<ProjectMapModel.Entry> withStructure(List<ProjectMapModel.Entry> loaded) {
        if (open.isEmpty()) {
            return loaded;
        }
        List<ProjectMapModel.Entry> rows = new ArrayList<>(loaded);
        for (ProjectMapModel.Entry entry : loaded) {
            if (!entry.isFile() || !open.contains(entry.path())) {
                continue;
            }
            Path file = entry.path();
            Outline outline = outlines.getOrDefault(file, Outline.LOADING);
            int depth = entry.depth() + 1;
            if (outline.problem() != Problem.NONE || outline.roots().isEmpty()) {
                String label = switch (outline.problem()) {
                    case LOADING -> tr("project.map.structure.loading");
                    case UNREADABLE -> tr("project.map.structure.unreadable");
                    case NONE -> tr("project.map.structure.none");
                };
                rows.add(ProjectMapModel.Entry.placeholder(
                        file, depth, new ProjectMapModel.Placeholder(ProjectMapModel.PlaceholderKind.EMPTY, 0, label)));
                continue;
            }
            addRows(rows, outline.roots(), file, depth);
            if (outline.partial()) {
                // The file is longer than the read cap: say so instead of passing the first part off as all.
                rows.add(ProjectMapModel.Entry.placeholder(
                        file,
                        depth,
                        new ProjectMapModel.Placeholder(
                                ProjectMapModel.PlaceholderKind.EMPTY, 0, tr("project.map.structure.partial"))));
            }
        }
        return rows;
    }

    private void addRows(List<ProjectMapModel.Entry> rows, List<Node> nodes, Path parent, int depth) {
        int shown = Math.min(nodes.size(), MAX_ROWS);
        for (int i = 0; i < shown; i++) {
            Node node = nodes.get(i);
            rows.add(ProjectMapModel.Entry.symbol(node.path(), parent, depth, node.symbol()));
        }
        if (nodes.size() > shown) {
            rows.add(ProjectMapModel.Entry.placeholder(
                    parent,
                    depth,
                    new ProjectMapModel.Placeholder(
                            ProjectMapModel.PlaceholderKind.EMPTY,
                            nodes.size() - shown,
                            tr("project.map.structure.more", nodes.size() - shown))));
        }
        for (int i = 0; i < shown; i++) {
            Node node = nodes.get(i);
            if (node.symbol().branches() && open.contains(node.path())) {
                addRows(rows, node.children(), node.path(), depth + 1);
            }
        }
    }

    /** The open columns among {@code rows}: their rows' parents, for the map to draw those rows as open. */
    Set<Path> openAmong(List<ProjectMapModel.Entry> rows) {
        Set<Path> result = new HashSet<>();
        for (ProjectMapModel.Entry row : rows) {
            if ((row.isSymbol() || row.isPlaceholder()) && row.parent() != null && open.contains(row.parent())) {
                result.add(row.parent());
            }
        }
        return result;
    }

    /**
     * Drops the columns of files that are no longer rows (their folder was closed, they were deleted) and of
     * declarations their file no longer has, and forgets the outlines of files nothing shows any more.
     * {@code keep} are files a card still shows a declaration of.
     */
    void prune(Set<Path> loadedFiles, Set<Path> keep) {
        warmFor(loadedFiles);
        open.removeIf(path -> {
            if (outlines.containsKey(path) || requests.containsKey(path)) {
                return !loadedFiles.contains(path); // a file
            }
            Path file = fileOf(path);
            if (file == null || !loadedFiles.contains(file) || !open.contains(file)) {
                return true;
            }
            Outline outline = outlines.get(file);
            return outline != null
                    && outline.problem() == Problem.NONE
                    && !outline.byPath().containsKey(path);
        });
        outlines.keySet().removeIf(file -> !open.contains(file) && !keep.contains(file));
        requests.keySet().removeIf(file -> !open.contains(file) && !keep.contains(file));
    }

    /** The declaration at {@code path}, as last outlined; null when it is not one or is gone. */
    @Nullable
    Node node(@Nullable Path path) {
        Path file = fileOf(path);
        Outline outline = file == null ? null : outlines.get(file);
        return outline == null ? null : outline.byPath().get(path);
    }

    /** The file a declaration path belongs to, or null when {@code path} is not below an outlined file. */
    @Nullable
    Path fileOf(@Nullable Path path) {
        for (Path current = path == null ? null : path.getParent(); current != null; current = current.getParent()) {
            if (outlines.containsKey(current) || requests.containsKey(current)) {
                return current;
            }
        }
        return null;
    }

    /** Whether {@code path} is a declaration's made-up path (the last segment says so). */
    static boolean isSymbolPath(@Nullable Path path) {
        Path name = path == null ? null : path.getFileName();
        return name != null && name.toString().startsWith(SEGMENT_PREFIX);
    }

    /**
     * What a card's Structure column shows for {@code file}: its declarations as last outlined, or why there are
     * none; loading while no outline is held.
     *
     * @param partial the file is longer than the read cap: declarations past it are left out
     */
    record View(List<Node> roots, Problem problem, boolean partial) {
        static final View LOADING = new View(List.of(), Problem.LOADING, false);
    }

    View view(Path file) {
        Outline outline = outlines.get(file);
        return outline == null ? View.LOADING : new View(outline.roots(), outline.problem(), outline.partial());
    }

    /** Whether the outline of {@code file} is held (open, or kept for a card). */
    boolean hasOutline(Path file) {
        return outlines.containsKey(file);
    }

    /**
     * Brings the outlines of the open files (and of {@code alsoFiles}, cards' files) up to date: an open
     * buffer whose text changed is outlined again, and a closed file is stat'ed and outlined again when its
     * size or time changed. Cheap when nothing did.
     */
    void refresh(Set<Path> alsoFiles) {
        refresh(alsoFiles, this::contentFor);
    }

    /**
     * As {@link #refresh(Set)}, with the editor's view of each file from {@code tick}: the cards' refresh hands
     * over the same {@link ProjectMapPreview.Content} it gave the cards, so a buffer's text is read once a poll.
     */
    void refresh(Set<Path> alsoFiles, Function<Path, ProjectMapPreview.@Nullable Content> tick) {
        Set<Path> files = new LinkedHashSet<>(openFiles());
        files.addAll(alsoFiles);
        for (Path file : files) {
            request(file, outlines.get(file), tick);
        }
    }

    private void request(Path file, @Nullable Outline known) {
        request(file, known, this::contentFor);
    }

    private void request(
            Path file, @Nullable Outline known, Function<Path, ProjectMapPreview.@Nullable Content> contents) {
        request(file, known, contents, false);
    }

    /**
     * Asks for a file's outline (again); {@code known} is what is held, so an unchanged source costs a stat. An open
     * buffer past {@link #DEBOUNCE_CHARS} that already has an outline is outlined again only once its text has not
     * changed for {@link #DEBOUNCE_MILLIS} ({@code settled}: that wait is over).
     */
    private void request(
            Path file,
            @Nullable Outline known,
            Function<Path, ProjectMapPreview.@Nullable Content> contents,
            boolean settled) {
        if (disposed) {
            return;
        }
        ProjectMapPreview.Content current = contentOf(contents, file);
        Stamp knownStamp = known == null ? null : known.stamp();
        if (current != null
                && current.open()
                && current.version() >= 0
                && knownStamp != null
                && knownStamp.buffer()
                && knownStamp.version() == current.version()) {
            return; // the buffer was not edited since it was outlined: its text is not even read
        }
        String text = current != null && current.open() ? current.text() : null;
        boolean buffer = text != null;
        long version = current == null ? -1 : current.version();
        if (text != null
                && knownStamp != null
                && knownStamp.buffer()
                && knownStamp.textLength() == text.length()
                && knownStamp.textHash() == text.hashCode()) {
            if (known != null && version != knownStamp.version()) {
                outlines.put(file, known.withStamp(knownStamp.withVersion(version))); // same text, newer counter
            }
            return; // the buffer has not changed since it was outlined
        }
        if (text != null && known != null && !settled && debounces(text.length())) {
            debounce(file, version >= 0 ? version : text.hashCode());
            return;
        }
        List<SymbolNode> server = buffer ? serverSymbolsFor(file) : null;
        String charset = current == null ? null : current.editorConfigCharset();
        // A closed file outlined before its language's server ran is read once more for that server (position 10).
        Stamp unchangedSince = !buffer
                        && knownStamp != null
                        && !java.util.Objects.equals(serverAsked.get(file), knownStamp)
                        && closedServes(file)
                ? null
                : knownStamp;
        long id = ++requestSequence;
        requests.put(file, id);
        if (!outlines.containsKey(file)) {
            outlines.put(file, Outline.LOADING);
        }
        try {
            loader.submit(() -> {
                boolean cut = current != null && current.truncated();
                if (text != null) {
                    Outline outline = outline(file, text, server, true, version, cut);
                    Platform.runLater(() -> apply(file, id, outline));
                    return;
                }
                ClosedRead read = readForOutline(file, charset, unchangedSince);
                Platform.runLater(() -> closedOutlineRead(file, id, read));
            });
        } catch (RejectedExecutionException shutDown) {
            // disposed meanwhile
        }
    }

    /** Whether a running server would outline {@code file}; false when asking fails. */
    private boolean closedServes(Path file) {
        try {
            return closedSymbols.serves(file);
        } catch (RuntimeException failed) {
            ProjectMapLog.unexpected("asking whether a server outlines a closed file", failed);
            return false;
        }
    }

    /**
     * A closed file was read and outlined by the heuristic (FX thread). Without a running server for it the
     * heuristic outline is the outline. With one, the server is asked for its outline of the same text: it replaces
     * the heuristic one, which is shown only if the server has not answered within {@link #SERVER_WAIT_MILLIS} or
     * answers with nothing (position 10).
     */
    private void closedOutlineRead(Path file, long id, @Nullable ClosedRead read) {
        if (disposed || !java.util.Objects.equals(requests.get(file), id)) {
            return;
        }
        if (read == null) {
            return; // unchanged on disk
        }
        String text = read.text();
        if (text == null
                || read.cut()
                || text.length() > MAX_SERVER_OUTLINE_CHARS
                || read.outline().problem() != Problem.NONE
                || !closedServes(file)) {
            serverOutlined.remove(file);
            apply(file, id, read.outline());
            return;
        }
        serverAsked.put(file, read.outline().stamp());
        boolean[] heuristicShown = {false};
        boolean[] answered = {false};
        javafx.animation.PauseTransition wait =
                new javafx.animation.PauseTransition(javafx.util.Duration.millis(SERVER_WAIT_MILLIS));
        wait.setOnFinished(event -> {
            if (!answered[0] && !heuristicShown[0]) {
                heuristicShown[0] = true;
                serverOutlined.remove(file);
                apply(file, id, read.outline());
            }
        });
        wait.play();
        java.util.function.Consumer<@Nullable List<SymbolNode>> found = symbols -> {
            answered[0] = true;
            wait.stop();
            if (disposed || !java.util.Objects.equals(requests.get(file), id)) {
                return;
            }
            if (symbols == null || symbols.isEmpty()) {
                if (!heuristicShown[0]) {
                    heuristicShown[0] = true;
                    serverOutlined.remove(file);
                    apply(file, id, read.outline());
                }
                return;
            }
            try {
                loader.submit(() -> {
                    Outline served = outline(file, text, symbols, false, -1, false);
                    Outline stamped = new Outline(
                            read.outline().stamp(),
                            served.roots(),
                            served.byPath(),
                            served.problem(),
                            served.partial());
                    Platform.runLater(() -> {
                        if (!disposed && java.util.Objects.equals(requests.get(file), id)) {
                            serverOutlined.add(file);
                            apply(file, id, stamped);
                        }
                    });
                });
            } catch (RejectedExecutionException shutDown) {
                // disposed meanwhile
            }
        };
        try {
            closedSymbols.symbols(file, text, found);
        } catch (RuntimeException failed) {
            ProjectMapLog.unexpected("asking a server for a closed file's outline", failed);
            found.accept(null);
        }
    }

    /** Open buffers longer than this are outlined again only once their text settles (C16). */
    static final int DEBOUNCE_CHARS = 200_000;
    /** How long a big buffer's text must stay the same before it is outlined again. */
    static final int DEBOUNCE_MILLIS = 900;

    /**
     * Whether a buffer of {@code length} characters waits for its text to settle before it is outlined again: a
     * 480 KB file took about a second per outline on the single outline thread, once per edit, and every other
     * card's outline queued behind it (review 3, C16).
     */
    static boolean debounces(int length) {
        return length > DEBOUNCE_CHARS;
    }

    /** (Re)starts the wait of a big buffer whose text is now at {@code version}. FX thread. */
    private void debounce(Path file, long version) {
        javafx.animation.PauseTransition pending = debounced.get(file);
        if (pending != null && java.util.Objects.equals(debouncedVersion.get(file), version)) {
            return; // the same text as when the wait began: let it run out
        }
        if (pending != null) {
            pending.stop();
        }
        javafx.animation.PauseTransition wait =
                new javafx.animation.PauseTransition(javafx.util.Duration.millis(DEBOUNCE_MILLIS));
        wait.setOnFinished(event -> {
            if (debounced.get(file) != wait) {
                return;
            }
            debounced.remove(file);
            debouncedVersion.remove(file);
            if (!disposed && (open.contains(file) || outlines.containsKey(file))) {
                request(file, outlines.get(file), this::contentFor, true);
            }
        });
        debounced.put(file, wait);
        debouncedVersion.put(file, version);
        wait.play();
    }

    /** Whether a big buffer's new outline is waiting for its text to settle. For tests. */
    boolean isDebouncing(Path file) {
        return debounced.containsKey(file);
    }

    /**
     * Compiles the grammar of the project's main language on the outline thread, once per root, so the first
     * Structure column in a Go or Java project does not wait for it: a cold Go grammar made the first outline of
     * {@code kubelet.go} take 2.5 s (C16). The language is the one a build file at the root names ({@code go.mod},
     * {@code pom.xml}, …), else the most common one among {@code files}.
     */
    void warmFor(Set<Path> files) {
        if (warmed || disposed || files.isEmpty()) {
            return;
        }
        warmed = true;
        String sample = dominantOutlineFile(files);
        if (sample == null) {
            return;
        }
        try {
            loader.submit(() -> {
                try {
                    StructureOutline.build(
                            StructureOutline.Source.file(sample, "x\n").analyzed());
                } catch (RuntimeException | LinkageError failed) {
                    ProjectMapLog.unexpected("warming the outline of " + sample, failed);
                }
            });
        } catch (RejectedExecutionException shutDown) {
            // disposed meanwhile
        }
    }

    /** A file name in the project's main outlined language, or null when none of {@code files} has an outline. */
    static @Nullable String dominantOutlineFile(Set<Path> files) {
        Map<String, Integer> byExtension = new HashMap<>();
        String marked = null;
        for (Path file : files) {
            String name = PathDisplay.fileName(file).toLowerCase(Locale.ROOT);
            String byMarker = switch (name) {
                case "go.mod", "go.work" -> "main.go";
                case "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts" ->
                    "Main.java";
                case "cargo.toml" -> "main.rs";
                case "pyproject.toml", "setup.py", "requirements.txt" -> "main.py";
                case "package.json", "tsconfig.json" -> "index.ts";
                default -> null;
            };
            if (byMarker != null && marked == null) {
                marked = byMarker;
            }
            int dot = name.lastIndexOf('.');
            if (dot > 0 && dot < name.length() - 1) {
                byExtension.merge(name.substring(dot + 1), 1, Integer::sum);
            }
        }
        if (marked != null) {
            return marked;
        }
        String best = null;
        int most = 0;
        for (Map.Entry<String, Integer> e : byExtension.entrySet()) {
            String sample = "x." + e.getKey();
            if (e.getValue() > most
                    && !"markdown".equals(com.editora.text.LanguageRegistry.forFileName(sample))
                    && StructureOutline.supports(sample)) {
                best = sample;
                most = e.getValue();
            }
        }
        return best;
    }

    /** Asks for the outline of {@code file} when it is not held: a card shows one of its declarations. */
    void ensure(Path file) {
        if (!outlines.containsKey(file)) {
            request(file, null);
        }
    }

    private ProjectMapPreview.@Nullable Content contentFor(Path file) {
        return contentOf(content, file);
    }

    private static ProjectMapPreview.@Nullable Content contentOf(
            Function<Path, ProjectMapPreview.@Nullable Content> contents, Path file) {
        try {
            return contents.apply(file);
        } catch (RuntimeException failed) {
            ProjectMapLog.unexpected("reading a file to outline", failed);
            return null;
        }
    }

    private @Nullable List<SymbolNode> serverSymbolsFor(Path file) {
        try {
            return serverSymbols.apply(file);
        } catch (RuntimeException failed) {
            ProjectMapLog.unexpected("asking for a file's server symbols", failed);
            return null;
        }
    }

    private void apply(Path file, long id, @Nullable Outline outline) {
        if (disposed || !java.util.Objects.equals(requests.get(file), id)) {
            return; // a newer request for the file is under way, or the file is no longer shown
        }
        if (outline == null) {
            return; // unchanged on disk
        }
        Outline before = outlines.get(file);
        successors = before == null ? Map.of() : successorsOf(before, outline);
        if (!successors.isEmpty()) {
            List<Path> moved = new ArrayList<>(open);
            open.clear();
            for (Path path : moved) {
                open.add(successors.getOrDefault(path, path)); // an open column follows its declaration
            }
        }
        outlines.put(file, outline);
        Node lone = autoExpand.remove(file) ? loneBranch(outline.roots()) : null;
        if (lone != null) {
            open.add(lone.path()); // most Java files: one class, its members are the point
        }
        open.removeIf(path -> path.startsWith(file)
                && !path.equals(file)
                && outline.problem() == Problem.NONE
                && !outline.byPath().containsKey(path));
        onChanged.run();
        onFileChanged.accept(file);
    }

    /**
     * The file's lone top-level declaration with members (the class of a Java file), or null. A leaf {@code package}
     * line beside it does not count: jdtls lists the package as a root of its own, which kept the class closed.
     * Any other second root does (two Markdown headings, two classes): then neither opens by itself.
     */
    static @Nullable Node loneBranch(List<Node> roots) {
        List<Node> counted = roots.stream()
                .filter(root -> root.symbol().branches()
                        || !PACKAGE_KINDS.contains(root.symbol().kind()))
                .toList();
        return counted.size() == 1 && counted.getFirst().symbol().branches() ? counted.getFirst() : null;
    }

    /** Server symbol kinds that only name where a file's declarations live (see {@link #loneBranch}). */
    private static final Set<String> PACKAGE_KINDS = Set.of("package", "namespace", "module");

    /**
     * Where the declaration at {@code path} went in the last outline of its file when its path changed: the
     * same-named declaration, among its old siblings' new ones, that is not an old one, nearest its old line —
     * the method whose parameter list was edited. Null when none qualifies (it was deleted or renamed).
     */
    @Nullable
    Path successor(@Nullable Path path) {
        return path == null ? null : successors.get(path);
    }

    /** Old paths gone from {@code after}, each to its successor (see {@link #successor}). Parents first. */
    private static Map<Path, Path> successorsOf(Outline before, Outline after) {
        if (before.problem() != Problem.NONE || after.problem() != Problem.NONE) {
            return Map.of();
        }
        List<Node> gone = before.byPath().values().stream()
                .filter(node -> !after.byPath().containsKey(node.path()))
                .sorted(java.util.Comparator.comparingInt(node -> node.path().getNameCount()))
                .toList();
        if (gone.isEmpty()) {
            return Map.of();
        }
        Map<Path, Path> moved = new HashMap<>();
        Set<Path> taken = new HashSet<>();
        for (Node old : gone) {
            Path oldParent = old.path().getParent();
            Path parent = moved.getOrDefault(oldParent, oldParent);
            String name = segmentStem(old.symbol().label());
            Node best = null;
            for (Node candidate : after.byPath().values()) {
                if (before.byPath().containsKey(candidate.path())
                        || taken.contains(candidate.path())
                        || !java.util.Objects.equals(candidate.path().getParent(), parent)
                        || !name.equals(segmentStem(candidate.symbol().label()))) {
                    continue;
                }
                if (best == null
                        || Math.abs(candidate.symbol().line() - old.symbol().line())
                                < Math.abs(best.symbol().line() - old.symbol().line())) {
                    best = candidate;
                }
            }
            if (best != null) {
                moved.put(old.path(), best.path());
                taken.add(best.path());
            }
        }
        return moved;
    }

    void dispose() {
        disposed = true;
        loader.shutdownNow();
        clear();
    }

    /**
     * A closed file as read for its outline: the heuristic outline, and the text it came from (null when unreadable)
     * for a running server to outline instead.
     */
    private record ClosedRead(Outline outline, @Nullable String text, boolean cut) {}

    /**
     * Off the FX thread: the file's heuristic outline and the text it read, or null when its size and time are still
     * {@code known}'s.
     */
    private static @Nullable ClosedRead readForOutline(Path file, @Nullable String charset, @Nullable Stamp known) {
        Stamp stamp = stamp(file);
        if (stamp != null && stamp.equals(known)) {
            return null;
        }
        ProjectMapPreview.ReadText read = ProjectMapPreview.readOutlineText(file, charset); // past the card cap
        if (read == null) {
            return new ClosedRead(new Outline(stamp, List.of(), Map.of(), Problem.UNREADABLE, false), null, false);
        }
        Outline outline = outline(file, read.text(), null, false, -1, read.truncated());
        return new ClosedRead(
                new Outline(stamp, outline.roots(), outline.byPath(), outline.problem(), outline.partial()),
                read.text(),
                read.truncated());
    }

    private static @Nullable Stamp stamp(Path file) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            return new Stamp(attributes.size(), attributes.lastModifiedTime().toMillis(), 0, 0, false, -1);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** Off the FX thread: the outline of {@code text}, the file's content. */
    private static Outline outline(
            Path file, String text, @Nullable List<SymbolNode> server, boolean buffer, long version, boolean cut) {
        List<StructureNode> roots;
        List<String> lines = List.of(text.split("\n", -1));
        String language = "";
        try {
            language = com.editora.text.LanguageRegistry.forFileName(PathDisplay.fileName(file));
            boolean pom = "xml".equals(language)
                    && PomFiles.isPom(
                            PathDisplay.fileName(file),
                            text.substring(0, Math.min(text.length(), PomFiles.SNIFF_CHARS)));
            roots = server != null
                    ? StructureOutline.fromLsp(server, language, pom, lines::get, lines.size())
                    : StructureOutline.build(StructureOutline.Source.file(PathDisplay.fileName(file), text)
                            .analyzed());
        } catch (RuntimeException | LinkageError failed) {
            ProjectMapLog.unexpected("outlining " + PathDisplay.fileName(file), failed);
            roots = List.of(); // a grammar that would not compile: no structure rather than no map
        }
        Map<Path, Node> byPath = new LinkedHashMap<>();
        Source source = new Source(lines, lineCommentOf(language), language == null ? "" : language, cut);
        List<Node> nodes = nodes(file, file, roots, source, lines.size() - 1, byPath);
        Stamp stamp = buffer ? new Stamp(-1, -1, text.hashCode(), text.length(), true, version) : null;
        return new Outline(stamp, nodes, Collections.unmodifiableMap(byPath), Problem.NONE, cut);
    }

    /**
     * What a declaration's lines and label are read from.
     *
     * @param lineComment the language's line-comment token ({@code //}, {@code #}, …), or "" for none
     * @param cut the text stops at the read cap
     */
    record Source(List<String> lines, String lineComment, String language, boolean cut) {}

    private static String lineCommentOf(@Nullable String language) {
        try {
            String token = com.editora.editops.Commenter.styleFor(language == null ? "" : language)
                    .line();
            return token == null ? "" : token.strip();
        } catch (RuntimeException unknown) {
            return "";
        }
    }

    private static List<Node> nodes(
            Path file, Path parent, List<StructureNode> source, Source text, int parentEnd, Map<Path, Node> out) {
        List<String> lines = text.lines();
        List<Node> result = new ArrayList<>(source.size());
        Map<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < source.size(); i++) {
            StructureNode node = source.get(i);
            if (node.line() < 0 || node.line() >= lines.size()) {
                continue;
            }
            String label = displayLabel(node, lines.get(node.line()), text.language());
            // The segment is the label with its parameter types, so overloads have paths of their own: inserting
            // an overload used to shift every later one's index and switch an open card to another method.
            String stem = signatureStem(label);
            int occurrence = seen.merge(stem, 1, Integer::sum) - 1;
            Path path = parent.resolve(SEGMENT_PREFIX + stem + "~" + occurrence);
            int nextStart = i + 1 < source.size() ? source.get(i + 1).line() : -1;
            int[] range = range(node, text, nextStart, parentEnd);
            List<Node> children = nodes(file, path, node.children(), text, range[1], out);
            Region block = node.region() != null
                    ? new Region(
                            Math.min(node.line(), node.region().startLine()),
                            node.region().endLine())
                    : new Region(node.line(), range[1]);
            ProjectMapModel.Symbol symbol = new ProjectMapModel.Symbol(
                    file, label, node.kind(), node.line(), range[0], range[1], block, !children.isEmpty());
            Node built = new Node(path.toAbsolutePath().normalize(), symbol, List.copyOf(children));
            out.put(built.path(), built);
            result.add(built);
        }
        return result;
    }

    /**
     * A row's label: the Structure window's, and for a callable its parameter list — the detail the outline
     * carries, else the parameter types read from its declaration line ({@code add(int, int, int)}), else the bare
     * name. It used to append {@code ()} to every callable without a detail, so all of a class's overloads read
     * {@code add()} and claimed no parameters.
     */
    static String displayLabel(StructureNode node, @Nullable String declarationLine, @Nullable String language) {
        String label = node.label() == null ? "" : node.label().strip().replaceAll("\\s+", " ");
        String kind = node.kind();
        boolean callable = "method".equals(kind) || "function".equals(kind) || "constructor".equals(kind);
        String detail = node.detail() == null ? "" : node.detail().strip();
        if (callable && "go".equals(language) && !label.endsWith(")")) {
            // Go: the receiver in the name ((*Kubelet).Run, as gopls labels it) and "(…)" for the parameters — Go
            // has no overloads, so their names only made the row too long to read (review 3, C15).
            if (detail.equals(GoOutline.NO_PARAMETERS) || detail.equals(GoOutline.SOME_PARAMETERS)) {
                return label + detail;
            }
            int dot = label.lastIndexOf('.');
            String parameters = parameterTypes(declarationLine, dot >= 0 ? label.substring(dot + 1) : label, language);
            return label + (parameters == null || !parameters.isEmpty() ? GoOutline.SOME_PARAMETERS : "()");
        }
        if (!callable || label.indexOf('(') >= 0) {
            return label;
        }
        if (detail.startsWith("[")) {
            return label + " " + detail; // a Clojure parameter vector
        }
        if (!detail.isEmpty()) {
            return label + (detail.startsWith("(") ? detail : "(" + detail + ")");
        }
        String parameters = parameterTypes(declarationLine, label, language);
        return parameters == null ? label : label + "(" + parameters + ")";
    }

    /**
     * The parameter list of {@code name}'s declaration on {@code line}, shortened to what tells overloads apart:
     * the types where the language writes them ({@code int a} → {@code int}, {@code a: Int} → {@code Int}), the
     * names where it does not (Python's {@code a, b=1} → {@code a, b}), defaults, annotations and a leading
     * {@code self} left out. Null when the list does not open and close on the line. Pure.
     */
    static @Nullable String parameterTypes(@Nullable String line, String name, @Nullable String language) {
        if (line == null || name.isEmpty()) {
            return null;
        }
        int open = -1;
        for (int at = line.indexOf(name); at >= 0; at = line.indexOf(name, at + 1)) {
            boolean wordStart = at == 0 || !Character.isJavaIdentifierPart(line.charAt(at - 1));
            int i = at + name.length();
            if (i < line.length() && line.charAt(i) == '<') { // a generic method name: foo<T>(…)
                int depth = 0;
                for (; i < line.length(); i++) {
                    char c = line.charAt(i);
                    depth += c == '<' ? 1 : c == '>' ? -1 : 0;
                    if (depth == 0) {
                        i++;
                        break;
                    }
                }
            }
            while (i < line.length() && line.charAt(i) == ' ') {
                i++;
            }
            if (wordStart && i < line.length() && line.charAt(i) == '(') {
                open = i;
                break;
            }
        }
        if (open < 0) {
            return null;
        }
        int depth = 0;
        int close = -1;
        for (int i = open; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                close = i;
                break;
            }
        }
        if (close < 0) {
            return null; // the list goes on to the next line: the bare name
        }
        String inside = line.substring(open + 1, close).strip();
        if (inside.isEmpty()) {
            return "";
        }
        if ("go".equals(language)) {
            return "…"; // no overloads to tell apart: the list's names and types are only width (C15)
        }
        List<String> types = new ArrayList<>();
        for (String parameter : splitTopLevel(inside)) {
            String shown = parameterType(parameter);
            if (!shown.isEmpty()) {
                types.add(shown);
            }
        }
        if (!types.isEmpty() && types.getFirst().matches("&?(mut )?(self|cls)")) {
            types.removeFirst(); // a Python or Rust method's receiver
        }
        return String.join(", ", types);
    }

    private static List<String> splitTopLevel(String list) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < list.length(); i++) {
            char c = list.charAt(i);
            if (c == '(' || c == '<' || c == '[' || c == '{') {
                depth++;
            } else if ((c == ')' || c == '>' && (i == 0 || list.charAt(i - 1) != '=') || c == ']' || c == '}')
                    && depth > 0) {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(list.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(list.substring(start));
        return parts;
    }

    /** One parameter as a label shows it: its type, or its name when it has no type. */
    private static String parameterType(String parameter) {
        String text = parameter.strip().replaceAll("@[\\w.]+(\\([^)]*\\))?\\s*", ""); // annotations
        int depth = 0;
        for (int i = 0; i < text.length(); i++) { // a default value
            char c = text.charAt(i);
            depth += c == '(' || c == '<' || c == '[' ? 1 : c == ')' || c == '>' || c == ']' ? -1 : 0;
            if (c == '=' && depth == 0 && (i + 1 >= text.length() || text.charAt(i + 1) != '>')) {
                text = text.substring(0, i).strip();
                break;
            }
        }
        text = text.replaceFirst("^(final|var|val|const|readonly|mut|in|out|ref|params)\\s+", "");
        int colon = -1;
        depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            depth += c == '(' || c == '<' || c == '[' ? 1 : c == ')' || c == '>' || c == ']' ? -1 : 0;
            if (c == ':'
                    && depth == 0
                    && (i + 1 >= text.length() || text.charAt(i + 1) != ':')
                    && (i == 0 || text.charAt(i - 1) != ':')) {
                colon = i;
                break;
            }
        }
        if (colon >= 0) { // name: Type (Kotlin, Scala, TypeScript, Python, Rust, Swift)
            String type = text.substring(colon + 1).strip();
            return type.isEmpty() ? text.substring(0, colon).strip() : type.replaceAll("\\s+", " ");
        }
        String[] words = text.split("\\s+");
        if (words.length < 2) {
            return text; // a name alone (Python, JavaScript) or a type alone
        }
        String last = words[words.length - 1];
        StringBuilder type = new StringBuilder(String.join(" ", java.util.Arrays.copyOf(words, words.length - 1)));
        for (int i = 0; i < last.length() && (last.charAt(i) == '*' || last.charAt(i) == '&'); i++) {
            type.append(last.charAt(i)); // char *name → char*
        }
        return type.toString();
    }

    /** A declaration's path segment: its whole label (with parameter types), file-name safe, at most 60 chars. */
    static String signatureStem(@Nullable String label) {
        String value = label == null ? "" : label.strip();
        StringBuilder stem = new StringBuilder(Math.min(value.length(), SEGMENT_STEM_LIMIT));
        for (int i = 0; i < value.length() && stem.length() < SEGMENT_STEM_LIMIT; i++) {
            char c = value.charAt(i);
            stem.append(Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '$' ? c : '_');
        }
        return stem.isEmpty() ? "_" : stem.toString();
    }

    /** The name part of a declaration's path segment: its label up to the parameter list, file-name safe. */
    static String segmentStem(@Nullable String label) {
        String value = label == null ? "" : label.strip();
        int signature = value.indexOf('(');
        if (signature > 0) {
            value = value.substring(0, signature).strip();
        }
        StringBuilder stem = new StringBuilder(Math.min(value.length(), SEGMENT_STEM_LIMIT));
        for (int i = 0; i < value.length() && stem.length() < SEGMENT_STEM_LIMIT; i++) {
            char c = value.charAt(i);
            stem.append(Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '$' ? c : '_');
        }
        return stem.isEmpty() ? "_" : stem.toString();
    }

    /**
     * The 0-based lines a declaration's card shows: its block — from its declaration (or its block's first line,
     * when that comes first) to its block's last line — with the doc comment and annotations written above it.
     * A declaration with no block of its own (a heading, a field) runs to the line before its next sibling
     * when it is a heading or holds declarations, and is its own line otherwise.
     */
    static int[] range(StructureNode node, List<String> lines, int nextSiblingLine, int parentEnd) {
        return range(node, new Source(lines, "//", "", false), nextSiblingLine, parentEnd);
    }

    /**
     * As above, for {@code source}'s language: its line comments count as a doc comment ({@code #} in Python, shell
     * and YAML), a {@code .http} request runs to the line before the next one like a heading (its headers and body,
     * not only its request line), and when the text was cut at the read cap a declaration whose block the cap cut
     * off runs to the next declaration or the end of the text read, rather than being one line.
     */
    static int[] range(StructureNode node, Source source, int nextSiblingLine, int parentEnd) {
        List<String> lines = source.lines();
        int last = Math.max(0, lines.size() - 1);
        int line = Math.max(0, Math.min(node.line(), last));
        Region region = node.region();
        int start = region == null ? line : Math.min(line, region.startLine());
        int end;
        boolean request = StructureOutline.HTTP_REQUEST_KIND.equals(node.kind());
        if (region != null && region.endLine() >= start) {
            end = region.endLine();
        } else if ("heading".equals(node.kind())
                || request
                || !node.children().isEmpty()
                || source.cut() && opensBlock(lines.get(line))) {
            end = nextSiblingLine > line ? nextSiblingLine - 1 : parentEnd;
            while (end > line
                    && (lines.get(Math.min(end, last)).isBlank()
                            || request && lines.get(Math.min(end, last)).strip().startsWith("###"))) {
                end--; // the blank lines (and a request's ### separator) before the next section belong to neither
            }
        } else {
            end = line;
        }
        end = Math.max(start, Math.min(end, last));
        return new int[] {leadingDocStart(lines, start, source.lineComment()), end};
    }

    /** Whether a declaration line opens a block that goes on below it ({@code void big() {}, {@code def f():}). */
    private static boolean opensBlock(String line) {
        String text = line.strip();
        return text.endsWith("{") || text.endsWith(":") || text.endsWith("(");
    }

    /**
     * The first line of the doc comment, attributes and annotations directly above {@code start} — a Javadoc or
     * other block comment, consecutive {@code //} lines, {@code @Annotation}s and decorators, Rust's {@code #[…]}
     * — or {@code start} when there is none. A blank line ends the search.
     */
    static int leadingDocStart(List<String> lines, int start) {
        return leadingDocStart(lines, start, "//");
    }

    /**
     * As above, with the language's own line comments too ({@code lineComment}: {@code #} for Python, shell, YAML
     * and the like — not a {@code #!} line).
     */
    static int leadingDocStart(List<String> lines, int start, @Nullable String lineComment) {
        String comment = lineComment == null || lineComment.isBlank() || "//".equals(lineComment) ? null : lineComment;
        int first = start;
        int line = start - 1;
        while (line >= 0) {
            String text = lines.get(line).strip();
            if (text.startsWith("@")
                    || text.startsWith("#[")
                    || text.startsWith("//")
                    || comment != null && text.startsWith(comment) && !text.startsWith("#!")) {
                first = line--;
            } else if (text.endsWith("*/")) {
                int open = line;
                while (open >= 0 && !lines.get(open).contains("/*")) {
                    open--;
                }
                if (open < 0) {
                    break; // not a comment we can see the start of
                }
                first = open;
                line = open - 1;
            } else {
                break;
            }
        }
        return first;
    }
}
