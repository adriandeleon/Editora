package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
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
import com.editora.editor.fold.FoldRegions.Region;
import com.editora.lsp.SymbolNode;
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
    private record Stamp(long size, long modifiedMillis, int textHash, int textLength, boolean buffer) {}

    /** Why a file has no outline to show. */
    enum Problem {
        NONE,
        LOADING,
        UNREADABLE
    }

    /** One file's outline: its declarations by path, and how its source looked. */
    private record Outline(@Nullable Stamp stamp, List<Node> roots, Map<Path, Node> byPath, Problem problem) {
        static final Outline LOADING = new Outline(null, List.of(), Map.of(), Problem.LOADING);
    }

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
    private final Function<Path, ProjectMapPreview.@Nullable Content> content;
    private Function<Path, @Nullable List<SymbolNode>> serverSymbols = path -> null;
    private Runnable onChanged = () -> {};
    private long requestSequence;
    private boolean disposed;

    ProjectMapStructure(Function<Path, ProjectMapPreview.@Nullable Content> content) {
        this.content = content == null ? path -> null : content;
    }

    /** Where an open buffer's server outline comes from (the Structure window's, for its own buffer). */
    void setServerSymbols(Function<Path, @Nullable List<SymbolNode>> symbols) {
        serverSymbols = symbols == null ? path -> null : symbols;
    }

    /** Told on the FX thread when an outline arrives or changes: the rows and cards need to follow. */
    void setOnChanged(Runnable callback) {
        onChanged = callback == null ? () -> {} : callback;
    }

    /** Whether a file of this name gets a Structure icon. By name only; the answer is kept per name. */
    boolean supports(ProjectMapModel.Entry entry) {
        if (!entry.isFile()) {
            return false;
        }
        String name = entry.name().toLowerCase(Locale.ROOT);
        Boolean known = supported.get(name);
        if (known == null) {
            if (supported.size() > 4_096) {
                supported.clear();
            }
            known = StructureOutline.supports(entry.name());
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

    /** Closes everything (a new root). */
    void clear() {
        open.clear();
        outlines.clear();
        requests.clear();
        autoExpand.clear();
    }

    /** Carries open columns across an in-app rename or move; the moved file is outlined again. */
    void pathRenamed(Path from, Path to) {
        List<Path> moved = open.stream()
                .map(path -> ProjectMapModel.remap(path, from, to))
                .filter(java.util.Objects::nonNull)
                .toList();
        open.clear();
        open.addAll(moved);
        outlines.keySet().removeIf(path -> path.startsWith(from));
        requests.keySet().removeIf(path -> path.startsWith(from));
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
        Set<Path> files = new LinkedHashSet<>(openFiles());
        files.addAll(alsoFiles);
        for (Path file : files) {
            request(file, outlines.get(file));
        }
    }

    /** Asks for a file's outline (again); {@code known} is what is held, so an unchanged source costs a stat. */
    private void request(Path file, @Nullable Outline known) {
        if (disposed) {
            return;
        }
        ProjectMapPreview.Content current = contentFor(file);
        String text = current != null && current.open() ? current.text() : null;
        boolean buffer = text != null;
        Stamp knownStamp = known == null ? null : known.stamp();
        if (text != null
                && knownStamp != null
                && knownStamp.buffer()
                && knownStamp.textLength() == text.length()
                && knownStamp.textHash() == text.hashCode()) {
            return; // the buffer has not changed since it was outlined
        }
        List<SymbolNode> server = buffer ? serverSymbolsFor(file) : null;
        String charset = current == null ? null : current.editorConfigCharset();
        long id = ++requestSequence;
        requests.put(file, id);
        if (!outlines.containsKey(file)) {
            outlines.put(file, Outline.LOADING);
        }
        try {
            loader.submit(() -> {
                Outline outline =
                        text != null ? outline(file, text, server, true) : readAndOutline(file, charset, knownStamp);
                Platform.runLater(() -> apply(file, id, outline));
            });
        } catch (RejectedExecutionException shutDown) {
            // disposed meanwhile
        }
    }

    /** Asks for the outline of {@code file} when it is not held: a card shows one of its declarations. */
    void ensure(Path file) {
        if (!outlines.containsKey(file)) {
            request(file, null);
        }
    }

    private ProjectMapPreview.@Nullable Content contentFor(Path file) {
        try {
            return content.apply(file);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private @Nullable List<SymbolNode> serverSymbolsFor(Path file) {
        try {
            return serverSymbols.apply(file);
        } catch (RuntimeException ignored) {
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
        outlines.put(file, outline);
        if (autoExpand.remove(file)
                && outline.roots().size() == 1
                && outline.roots().getFirst().symbol().branches()) {
            open.add(outline.roots().getFirst().path()); // most Java files: one class, its members are the point
        }
        open.removeIf(path -> path.startsWith(file)
                && !path.equals(file)
                && outline.problem() == Problem.NONE
                && !outline.byPath().containsKey(path));
        onChanged.run();
    }

    void dispose() {
        disposed = true;
        loader.shutdownNow();
        clear();
    }

    /** Off the FX thread: the file's outline, or null when its size and time are still {@code known}'s. */
    private static @Nullable Outline readAndOutline(Path file, @Nullable String charset, @Nullable Stamp known) {
        Stamp stamp = stamp(file);
        if (stamp != null && stamp.equals(known)) {
            return null;
        }
        String text = ProjectMapPreview.readText(file, charset);
        if (text == null) {
            return new Outline(stamp, List.of(), Map.of(), Problem.UNREADABLE);
        }
        Outline outline = outline(file, text, null, false);
        return new Outline(stamp, outline.roots(), outline.byPath(), outline.problem());
    }

    private static @Nullable Stamp stamp(Path file) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            return new Stamp(attributes.size(), attributes.lastModifiedTime().toMillis(), 0, 0, false);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** Off the FX thread: the outline of {@code text}, the file's content. */
    private static Outline outline(Path file, String text, @Nullable List<SymbolNode> server, boolean buffer) {
        List<StructureNode> roots;
        try {
            roots = server != null
                    ? StructureOutline.fromLsp(server)
                    : StructureOutline.build(StructureOutline.Source.file(PathDisplay.fileName(file), text)
                            .analyzed());
        } catch (RuntimeException | LinkageError failed) {
            roots = List.of(); // a grammar that would not compile: no structure rather than no map
        }
        List<String> lines = List.of(text.split("\n", -1));
        Map<Path, Node> byPath = new LinkedHashMap<>();
        List<Node> nodes = nodes(file, file, roots, lines, lines.size() - 1, byPath);
        Stamp stamp = buffer ? new Stamp(-1, -1, text.hashCode(), text.length(), true) : null;
        return new Outline(stamp, nodes, Map.copyOf(byPath), Problem.NONE);
    }

    private static List<Node> nodes(
            Path file,
            Path parent,
            List<StructureNode> source,
            List<String> lines,
            int parentEnd,
            Map<Path, Node> out) {
        List<Node> result = new ArrayList<>(source.size());
        Map<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < source.size(); i++) {
            StructureNode node = source.get(i);
            if (node.line() < 0 || node.line() >= lines.size()) {
                continue;
            }
            String stem = segmentStem(node.label());
            int occurrence = seen.merge(stem, 1, Integer::sum) - 1;
            Path path = parent.resolve(SEGMENT_PREFIX + stem + "~" + occurrence);
            int nextStart = i + 1 < source.size() ? source.get(i + 1).line() : -1;
            int[] range = range(node, lines, nextStart, parentEnd);
            List<Node> children = nodes(file, path, node.children(), lines, range[1], out);
            Region block = node.region() != null
                    ? new Region(
                            Math.min(node.line(), node.region().startLine()),
                            node.region().endLine())
                    : new Region(node.line(), range[1]);
            ProjectMapModel.Symbol symbol = new ProjectMapModel.Symbol(
                    file, displayLabel(node), node.kind(), node.line(), range[0], range[1], block, !children.isEmpty());
            Node built = new Node(path.toAbsolutePath().normalize(), symbol, List.copyOf(children));
            out.put(built.path(), built);
            result.add(built);
        }
        return result;
    }

    /** A row's label: the Structure window's, with {@code ()} after a callable that carries no parameters. */
    private static String displayLabel(StructureNode node) {
        String label = node.label() == null ? "" : node.label().strip().replaceAll("\\s+", " ");
        String kind = node.kind();
        boolean callable = "method".equals(kind) || "function".equals(kind) || "constructor".equals(kind);
        if (!callable || label.indexOf('(') >= 0) {
            return label;
        }
        String detail = node.detail() == null ? "" : node.detail().strip();
        if (detail.startsWith("[")) {
            return label + " " + detail; // a Clojure parameter vector
        }
        return label + (detail.startsWith("(") ? detail : detail.isEmpty() ? "()" : "(" + detail + ")");
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
        int last = Math.max(0, lines.size() - 1);
        int line = Math.max(0, Math.min(node.line(), last));
        Region region = node.region();
        int start = region == null ? line : Math.min(line, region.startLine());
        int end;
        if (region != null && region.endLine() >= start) {
            end = region.endLine();
        } else if ("heading".equals(node.kind()) || !node.children().isEmpty()) {
            end = nextSiblingLine > line ? nextSiblingLine - 1 : parentEnd;
            while (end > line && lines.get(Math.min(end, last)).isBlank()) {
                end--; // the blank lines before the next section belong to neither
            }
        } else {
            end = line;
        }
        end = Math.max(start, Math.min(end, last));
        return new int[] {leadingDocStart(lines, start), end};
    }

    /**
     * The first line of the doc comment, attributes and annotations directly above {@code start} — a Javadoc or
     * other block comment, consecutive {@code //} lines, {@code @Annotation}s and decorators, Rust's {@code #[…]}
     * — or {@code start} when there is none. A blank line ends the search.
     */
    static int leadingDocStart(List<String> lines, int start) {
        int first = start;
        int line = start - 1;
        while (line >= 0) {
            String text = lines.get(line).strip();
            if (text.startsWith("@") || text.startsWith("#[") || text.startsWith("//")) {
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
