package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import javafx.scene.Node;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.util.Duration;

import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Linked reference cards (Project Map explore, position 5): "Show References as Cards" and "Show Callers as Cards" on
 * a code card or a declaration row ask the language server that runs for the file — the file lent to it without an
 * editor ({@link com.editora.lsp.LspManager#lendDocument}) — for the declaration's references or incoming calls, or,
 * with no server, match its name in the project's files of its language. The places found are grouped by the
 * declaration that holds them (the file's outline, as the Map's Structure columns read it); the first
 * {@link #MAX_CARDS} groups open as declaration cards beside the card they were asked from, each joined to it by a
 * labelled edge — "calls", "references", or a dashed "by name" — and the rest are listed by a "+N more" chip under it.
 *
 * <p>The edges of one question are an {@link EdgeSet}, a {@link CardAttachment} of the card asked from: they are drawn
 * on the cards' connector canvas with the other connectors, rise and close with that card, and a result card closed or
 * evicted takes its own edge away. The result cards are ordinary declaration cards; nothing else about them differs.
 */
final class MapCardLinks {

    /** Result cards opened per question; the rest are listed by the "+N more" chip. */
    static final int MAX_CARDS = 6;
    /** Files whose outline is read to group their places by declaration; places in later files group per file. */
    static final int MAX_OUTLINED_FILES = 12;
    /** How long a question waits for those outlines before it groups what it has. */
    static final Duration OUTLINE_WAIT = Duration.seconds(4);
    /** How wide a result card opens at most, unzoomed: six of them fit around the card they were found from. */
    static final double CARD_WIDTH = 460;
    /** Groups the "+N more" chip's menu lists. */
    static final int MAX_MORE_ITEMS = 200;

    /** What is asked for. */
    enum Kind {
        /** Every place that names the declaration. */
        REFERENCES,
        /** The declarations that call it. */
        CALLERS
    }

    /** How the places were found: by a language server, or by matching the name (edges dashed, "by name"). */
    enum Via {
        SERVER,
        NAME
    }

    /**
     * A question: {@code kind} of the declaration at the 0-based {@code line} and {@code column} of {@code file} — a
     * negative column means "{@code name} on that line" — with the editor's {@code text} of the file when a tab has
     * it open (else it is read from disk).
     */
    record Request(
            Kind kind,
            Path file,
            int line,
            int column,
            @Nullable String name,
            @Nullable String text) {}

    /**
     * One place found: a reference's position, or a caller's declaration ({@code name} is then the caller's, and
     * {@code count} its call sites).
     */
    record Hit(
            Path file,
            int line,
            int column,
            int count,
            @Nullable String name) {}

    /**
     * What a {@link Source} found: the places, how, whether it stopped short (a budget), and what the user should be
     * told besides (no server ran, a request failed).
     */
    record Answer(
            List<Hit> hits,
            Via via,
            boolean partial,
            @Nullable String note,
            @Nullable String name) {
        Answer {
            hits = hits == null ? List.of() : List.copyOf(hits);
        }

        /** Nothing found, with why. */
        static Answer none(Via via, @Nullable String note) {
            return none(via, note, null);
        }

        /** Nothing found for {@code name}, with why (review 4, C12: the status names what was asked about). */
        static Answer none(Via via, @Nullable String note, @Nullable String name) {
            return new Answer(List.of(), via, false, note, name);
        }
    }

    /** Where the places come from; {@link ProjectMapLinkSearch} is the editor's. */
    @FunctionalInterface
    interface Source {
        /** Answers {@code request} about a file under {@code root} by calling {@code answer} once, on the FX thread. */
        void find(Path root, Request request, Consumer<Answer> answer);
    }

    /**
     * A card to be: the declaration that holds the places (its node in its file's outline), or — for places outside
     * any declaration, or in a file with no outline — the file at its first place. {@code count} places in all;
     * {@code line} is the first (0-based).
     */
    record Group(Path file, ProjectMapStructure.@Nullable Node declaration, String label, int line, int count) {
        /** The key its card is open under. */
        Path key() {
            ProjectMapStructure.Node node = declaration;
            return node != null ? node.path() : file;
        }
    }

    /** What the links need of the cards ({@link MapCards} implements it). */
    interface Cards {
        @Nullable
        Path root();

        /** Opens (or brings up) the card of a declaration of an outlined file. */
        @Nullable
        ProjectMapPreview openDeclaration(ProjectMapStructure.Node node);

        /** Opens (or brings up) the card of a whole file; null when it was refused (every card is pinned). */
        @Nullable
        ProjectMapPreview openFile(Path file);

        /** Whether {@code card} is still open. */
        boolean isOpen(ProjectMapPreview card);

        /** The code card open under {@code key}, or null (review 4: a card open before the question is not its own). */
        @Nullable
        ProjectMapPreview card(Path key);

        /** Counts as use of {@code card}: it is not the one evicted to make room for its results. */
        void touch(ProjectMapPreview card);

        void status(String message);

        /** Something the edges meet moved: the connectors are drawn once for the pulse. */
        void connectorsMoved();

        Color accent();

        /** Every column on screen as {@code {x, y, w, h, 1}}. */
        List<double[]> columns();

        void openAt(Path file, int line);
    }

    private final Cards cards;
    private final ProjectMapStructure structure;
    private final Pane canvasHost;
    /** The connector canvas: edge labels go just above it, under every card. */
    private final Node connectorLayer;

    private @Nullable Source source;
    private long requestSequence;
    /** The newest question per card and kind: an older answer is dropped. */
    private final Map<ProjectMapPreview, Map<Kind, Long>> asked = new IdentityHashMap<>();
    /** The edge sets, by the card they were asked from. */
    private final Map<ProjectMapPreview, Map<Kind, EdgeSet>> sets = new IdentityHashMap<>();
    /** Answers waiting for their files' outlines. */
    private final List<Pending> pending = new ArrayList<>();
    /** Result cards to place beside the card they were asked from, by key, until placed. */
    private final Map<Path, ProjectMapPreview> placeBeside = new HashMap<>();
    /** The answer whose result card is opening now: its card and its other results are not evicted for it (C4). */
    private @Nullable EdgeSet filling;

    MapCardLinks(Cards cards, ProjectMapStructure structure, Pane canvasHost, Node connectorLayer) {
        this.cards = cards;
        this.structure = structure;
        this.canvasHost = canvasHost;
        this.connectorLayer = connectorLayer;
    }

    /** Where questions are answered; null takes the menu items away. */
    void setSource(@Nullable Source source) {
        this.source = source;
    }

    /** Whether questions can be asked (the menu items are shown). */
    boolean available() {
        return source != null;
    }

    /**
     * Asks {@code request} for the card {@code from}: the answer opens result cards beside it, with edges to it. A
     * question asked again of the same card and kind replaces the earlier one's edges (its cards stay open).
     */
    void show(ProjectMapPreview from, Request request) {
        Source answering = source;
        Path root = cards.root();
        if (answering == null || root == null || !cards.isOpen(from)) {
            return;
        }
        long id = ++requestSequence;
        asked.computeIfAbsent(from, ignored -> new java.util.EnumMap<>(Kind.class))
                .put(request.kind(), id);
        String name = request.name();
        boolean callers = request.kind() == Kind.CALLERS;
        // A file card's name is found only once its text is read: until then the status names no file (C12).
        cards.status(
                name == null || name.isBlank()
                        ? tr(
                                callers
                                        ? "project.map.links.findingCallersUnnamed"
                                        : "project.map.links.findingReferencesUnnamed")
                        : tr(
                                callers ? "project.map.links.findingCallers" : "project.map.links.findingReferences",
                                name));
        answering.find(root, request, answer -> answered(from, request, id, answer));
    }

    private void answered(ProjectMapPreview from, Request request, long id, Answer answer) {
        Map<Kind, Long> newest = asked.get(from);
        if (newest == null || !java.util.Objects.equals(newest.get(request.kind()), id) || !cards.isOpen(from)) {
            return; // asked again since, or the card was closed
        }
        String name = answer.name() != null ? answer.name() : request.name();
        if (answer.hits().isEmpty()) {
            boolean callers = request.kind() == Kind.CALLERS;
            String none = name == null || name.isBlank()
                    ? tr(callers ? "project.map.links.noCallersUnnamed" : "project.map.links.noReferencesUnnamed")
                    : tr(callers ? "project.map.links.noCallers" : "project.map.links.noReferences", name);
            String note = answer.note();
            cards.status(note == null ? none : note + " " + none);
            return;
        }
        Pending waiting = new Pending(from, request, id, answer, name);
        Path root = cards.root();
        for (Path file : filesInOrder(answer.hits(), root)) {
            if (waiting.outlined.size() >= MAX_OUTLINED_FILES) {
                break;
            }
            if (!structure.supports(file)) {
                continue;
            }
            waiting.outlined.add(file);
            structure.ensure(file);
            if (structure.view(file).problem() == ProjectMapStructure.Problem.LOADING) {
                waiting.waitingFor.add(file);
            }
        }
        if (waiting.waitingFor.isEmpty()) {
            finish(waiting);
            return;
        }
        pending.add(waiting);
        waiting.timeout.setOnFinished(event -> {
            if (pending.remove(waiting)) {
                finish(waiting);
            }
        });
        waiting.timeout.playFromStart();
    }

    /** The files an answer's places are in, in path order (the order its groups are listed in). */
    private static List<Path> filesInOrder(List<Hit> hits, @Nullable Path root) {
        Set<Path> files = new LinkedHashSet<>();
        sorted(hits, root).forEach(hit -> files.add(hit.file()));
        return List.copyOf(files);
    }

    private static List<Hit> sorted(List<Hit> hits, @Nullable Path root) {
        List<Hit> ordered = new ArrayList<>(hits);
        ordered.sort(Comparator.comparing((Hit hit) -> relative(root, hit.file()))
                .thenComparingInt(Hit::line)
                .thenComparingInt(Hit::column));
        return ordered;
    }

    private static String relative(@Nullable Path root, Path file) {
        return root != null && file.startsWith(root)
                ? com.editora.search.ProjectWalk.relativize(root, file)
                : file.toString();
    }

    /** The outline of {@code file} arrived: the questions that waited for it go on once they have all theirs. */
    void outlineChanged(Path file) {
        if (pending.isEmpty() || structure.view(file).problem() == ProjectMapStructure.Problem.LOADING) {
            return;
        }
        for (Pending waiting : List.copyOf(pending)) {
            if (waiting.waitingFor.remove(file) && waiting.waitingFor.isEmpty()) {
                pending.remove(waiting);
                waiting.timeout.stop();
                finish(waiting);
            }
        }
    }

    /** The files whose outlines the links need kept: those of the questions still waiting. */
    Set<Path> outlineFiles() {
        Set<Path> files = new LinkedHashSet<>();
        pending.forEach(waiting -> files.addAll(waiting.outlined));
        return files;
    }

    private void finish(Pending waiting) {
        ProjectMapPreview from = waiting.from;
        Map<Kind, Long> newest = asked.get(from);
        if (newest == null
                || !java.util.Objects.equals(newest.get(waiting.request.kind()), waiting.id)
                || !cards.isOpen(from)) {
            return;
        }
        Path root = cards.root();
        List<Group> groups = group(
                waiting.answer.hits(),
                waiting.answer.via(),
                waiting.request,
                waiting.name,
                file -> waiting.outlined.contains(file) ? outlineOf(file) : null,
                root);
        Path fromKey = from.range() == null ? from.path() : null;
        groups = linksRank(
                groups.stream()
                        .filter(group -> !group.key().equals(fromKey) && !isCardOf(from, group))
                        .toList(),
                waiting.request.file(),
                root);
        EdgeSet set = replaceSet(from, waiting.request.kind(), waiting.answer.via(), waiting.name);
        int opened = 0;
        boolean refused = false;
        for (Group group : groups) {
            if (opened < MAX_CARDS && !refused) {
                Opened done = set.open(group);
                if (done == Opened.OPENED) {
                    opened++;
                } else if (done == Opened.REFUSED) {
                    refused = true; // no room (pinned cards): it and every later place go on the chip (C4)
                    set.more.add(group);
                }
            } else {
                set.more.add(group);
            }
        }
        set.updateChip();
        int total = groups.stream().mapToInt(Group::count).sum();
        String summary = tr(
                waiting.request.kind() == Kind.CALLERS
                        ? "project.map.links.foundCallersChip"
                        : "project.map.links.foundReferencesChip",
                total,
                waiting.name == null ? "" : waiting.name,
                groups.size(),
                set.more.size());
        String note = waiting.answer.note();
        String partial = waiting.answer.partial() ? " " + tr("project.map.links.partial") : "";
        String noRoom = refused ? " " + tr("project.map.links.noRoom") : "";
        cards.status((note == null ? "" : note + " ") + summary + noRoom + partial);
        cards.connectorsMoved();
    }

    /**
     * The groups in the order their cards open (review 4, C9): those outside test folders and test files before the
     * rest (unless the question was asked from a test), then nearest the asked file's folder — the most path
     * segments in common — then in path and line order. A popular name's first cards are the code beside it, not
     * whatever the file walk met first. Pure.
     */
    static List<Group> linksRank(List<Group> groups, Path asked, @Nullable Path root) {
        boolean fromTest = linksLooksLikeTest(relative(root, asked));
        Path near = asked.getParent();
        List<Group> ranked = new ArrayList<>(groups);
        ranked.sort(Comparator.comparingInt(
                        (Group group) -> !fromTest && linksLooksLikeTest(relative(root, group.file())) ? 1 : 0)
                .thenComparingInt(group -> -linksShared(near, group.file().getParent())));
        return ranked; // a stable sort: path and line order within each rank
    }

    /** How many leading path segments {@code a} and {@code b} share (0 when either is null). Pure. */
    static int linksShared(@Nullable Path a, @Nullable Path b) {
        if (a == null || b == null) {
            return 0;
        }
        int shared = 0;
        int count = Math.min(a.getNameCount(), b.getNameCount());
        while (shared < count && a.getName(shared).equals(b.getName(shared))) {
            shared++;
        }
        return shared;
    }

    /** Folder names that hold tests. */
    private static final Set<String> TEST_FOLDERS =
            Set.of("test", "tests", "__tests__", "spec", "specs", "testdata", "testing");

    /**
     * Whether a project-relative path ({@code /}-separated) looks like a test: a folder named for tests on the way, or
     * a file named {@code FooTest.java}, {@code foo_test.go}, {@code test_foo.py}, {@code foo.test.ts},
     * {@code foo.spec.js}. Pure.
     */
    static boolean linksLooksLikeTest(String relative) {
        String[] parts = relative.replace('\\', '/').split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if (TEST_FOLDERS.contains(parts[i].toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        String name = parts.length == 0 ? "" : parts[parts.length - 1];
        int dot = name.indexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String rest = dot > 0 ? name.substring(dot) : "";
        return base.length() > 4 && (base.endsWith("Test") || base.endsWith("Tests") || base.endsWith("Spec"))
                || base.endsWith("_test")
                || base.startsWith("test_")
                || rest.startsWith(".test.")
                || rest.startsWith(".spec.");
    }

    /** Whether {@code group} is the very declaration {@code from} shows (a recursive call, the declaration itself). */
    private static boolean isCardOf(ProjectMapPreview from, Group group) {
        ProjectMapPreview.Range shown = from.range();
        ProjectMapStructure.Node node = group.declaration();
        return shown != null
                && node != null
                && group.file().equals(from.path())
                && node.symbol().line() == shown.line();
    }

    private @Nullable List<ProjectMapStructure.Node> outlineOf(Path file) {
        ProjectMapStructure.View view = structure.view(file);
        return view.problem() == ProjectMapStructure.Problem.NONE ? view.roots() : null;
    }

    /**
     * The places of an answer grouped by the declaration that holds them — the deepest of {@code outline}'s
     * declarations whose lines hold the place — in path and line order, each with how many places it holds. A file
     * whose outline is not given (null) or a place outside every declaration groups by file. Matched by name, a
     * place that is itself the declaration of that name (or the asked declaration's own line) is no use and is left
     * out. Pure.
     */
    static List<Group> group(
            List<Hit> hits,
            Via via,
            Request request,
            @Nullable String name,
            Function<Path, @Nullable List<ProjectMapStructure.Node>> outline,
            @Nullable Path root) {
        Map<Path, Group> byKey = new LinkedHashMap<>();
        for (Hit hit : sorted(hits, root)) {
            if (via == Via.NAME && hit.file().equals(request.file()) && hit.line() == request.line()) {
                continue; // the declaration asked about
            }
            List<ProjectMapStructure.Node> roots = outline.apply(hit.file());
            ProjectMapStructure.Node node = roots == null ? null : enclosing(roots, hit.line());
            if (via == Via.NAME
                    && node != null
                    && node.symbol().line() == hit.line()
                    && name != null
                    && name.equals(ProjectMapView.followName(node.symbol().label()))) {
                continue; // another declaration of that name: not a use of it
            }
            String caller = hit.name();
            String label = node != null
                    ? node.symbol().label()
                    : caller != null && !caller.isBlank()
                            ? caller
                            : com.editora.config.PathDisplay.fileName(hit.file());
            Group group = new Group(hit.file(), node, label, hit.line(), Math.max(1, hit.count()));
            byKey.merge(
                    group.key(),
                    group,
                    (held, more) -> new Group(
                            held.file(), held.declaration(), held.label(), held.line(), held.count() + more.count()));
        }
        return List.copyOf(byKey.values());
    }

    /**
     * The innermost declaration among {@code roots} and all their descendants whose own lines hold {@code line} — the
     * one starting nearest above it, then the shortest — or null. The whole outline is searched, not only inside
     * parents that hold the line: a Go type's lines are its struct's, and its methods, listed under it, lie outside
     * them (review 4, C1).
     */
    static ProjectMapStructure.@Nullable Node enclosing(List<ProjectMapStructure.Node> roots, int line) {
        ProjectMapStructure.Node found = null;
        int foundStart = Integer.MIN_VALUE;
        int foundEnd = Integer.MAX_VALUE;
        java.util.ArrayDeque<ProjectMapStructure.Node> todo = new java.util.ArrayDeque<>(roots);
        while (!todo.isEmpty()) {
            ProjectMapStructure.Node node = todo.pop();
            ProjectMapModel.Symbol symbol = node.symbol();
            int start = Math.min(symbol.startLine(), symbol.line());
            int end = Math.max(symbol.endLine(), symbol.line());
            if (start <= line && line <= end && (start > foundStart || start == foundStart && end <= foundEnd)) {
                found = node; // a tie goes to the later, deeper one: a child listed after its parent
                foundStart = start;
                foundEnd = end;
            }
            List<ProjectMapStructure.Node> children = node.children();
            if (children != null) {
                for (int i = children.size() - 1; i >= 0; i--) {
                    todo.push(children.get(i));
                }
            }
        }
        return found;
    }

    /** The edge set of {@code kind} asked from {@code from}, new: one asked before is taken off (its cards stay). */
    private EdgeSet replaceSet(ProjectMapPreview from, Kind kind, Via via, @Nullable String name) {
        Map<Kind, EdgeSet> of = sets.computeIfAbsent(from, ignored -> new java.util.EnumMap<>(Kind.class));
        EdgeSet old = of.remove(kind);
        EdgeSet set = new EdgeSet(from, kind, via, name);
        if (old != null) {
            old.owned.stream().filter(cards::isOpen).forEach(set.owned::add); // asked again: still its cards
            old.dispose();
        }
        of.put(kind, set);
        return set;
    }

    /** The edge sets stuck on {@code card} (its questions' answers). */
    List<CardAttachment> attachments(ProjectMapPreview card) {
        Map<Kind, EdgeSet> of = sets.get(card);
        return of == null ? List.of() : List.copyOf(of.values());
    }

    /** Every edge set. */
    List<CardAttachment> allAttachments() {
        List<CardAttachment> all = new ArrayList<>();
        sets.values().forEach(of -> all.addAll(of.values()));
        return all;
    }

    /**
     * {@code card} closed: its edge sets go, any question it asked is forgotten, and the edge to it from another
     * card's answer goes. Returns the result cards its answers opened, which close with it unless pinned (review 4,
     * C11: they no longer linger until an unrelated reload closes them all at once).
     */
    List<ProjectMapPreview> cardClosed(ProjectMapPreview card) {
        List<ProjectMapPreview> results = linksEndQuestions(card);
        for (Map<Kind, EdgeSet> of : sets.values()) {
            for (EdgeSet set : of.values()) {
                set.owned.remove(card);
                set.linksDropEdge(card);
            }
        }
        placeBeside.values().removeIf(from -> from == card);
        return results;
    }

    /**
     * {@code card} now shows another declaration (‹ › stepping, review 4 C5): what its questions found was about the
     * declaration it showed, so their edges go and the result cards they opened are returned to be closed, as when it
     * closes; and the edge another card's answer drew to it goes with its link text, though it stays that answer's
     * card (it closes with that answer's card).
     */
    List<ProjectMapPreview> cardChanged(ProjectMapPreview card) {
        List<ProjectMapPreview> results = linksEndQuestions(card);
        for (Map<Kind, EdgeSet> of : sets.values()) {
            for (EdgeSet set : of.values()) {
                set.linksDropEdge(card);
            }
        }
        cards.connectorsMoved();
        return results;
    }

    /** Ends the questions asked from {@code card}: their edge sets go; the result cards they opened are returned. */
    private List<ProjectMapPreview> linksEndQuestions(ProjectMapPreview card) {
        asked.remove(card);
        List<ProjectMapPreview> results = new ArrayList<>();
        Map<Kind, EdgeSet> of = sets.remove(card);
        if (of != null) {
            for (EdgeSet set : of.values()) {
                set.owned.stream()
                        .filter(result -> result != card && cards.isOpen(result) && !results.contains(result))
                        .forEach(results::add);
                set.dispose();
            }
        }
        pending.removeIf(waiting -> {
            if (waiting.from == card) {
                waiting.timeout.stop();
                return true;
            }
            return false;
        });
        return results;
    }

    /**
     * Whether a card being opened now for an answer may not be evicted to make room: the card that answer was asked
     * from and the answer's other results (review 4, C4: a question no longer evicts its own first results).
     */
    boolean linksKeepsWhileOpening(Region card) {
        EdgeSet set = filling;
        return set != null && (card == set.from || set.owned.contains(card) || set.linksTargets(card));
    }

    /** Puts every open answer's "+N more" chip back above the cards (one rose or opened over it: C3). */
    void linksRaiseChips() {
        sets.values()
                .forEach(of -> of.values().forEach(set -> {
                    if (set.chip.isVisible() || !set.more.isEmpty()) {
                        set.chip.toFront();
                    }
                }));
    }

    /** Every card closed (a root change, the window's dispose). */
    void closeAll() {
        sets.values().forEach(of -> of.values().forEach(EdgeSet::dispose));
        sets.clear();
        asked.clear();
        pending.forEach(waiting -> waiting.timeout.stop());
        pending.clear();
        placeBeside.clear();
    }

    /** Whether {@code card} is the result card of an open edge set: it stays while that set's card does. */
    boolean isResult(@Nullable Region card) {
        if (card == null) {
            return false;
        }
        for (Map<Kind, EdgeSet> of : sets.values()) {
            for (EdgeSet set : of.values()) {
                if (set.owned.contains(card) || set.linksTargets(card)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether {@code key}'s card is a result card still to be placed beside the card it was asked from. Unlike {@link
     * #besideSource} it takes nothing: a check before the placement does not move the card from beside its source.
     */
    boolean placesBeside(Path key) {
        return placeBeside.containsKey(key);
    }

    /**
     * Where a result card opened for a question goes: beside the card it was asked from, as a card goes beside its
     * row's column. Null for any other card, and once the card has been placed: this takes it, so only the placement
     * itself asks ({@link #placesBeside} only checks).
     */
    MapCards.@Nullable Site besideSource(Path key) {
        ProjectMapPreview from = placeBeside.remove(key);
        if (from == null || !cards.isOpen(from) || !from.isVisible() || from.getWidth() <= 0) {
            return null;
        }
        double[] drawn = MapCards.drawnBounds(from);
        return new MapCards.Site(drawn, drawn, drawn, cards.columns());
    }

    /** How opening a result card went. */
    enum Opened {
        OPENED,
        /** No room: every card that could go is pinned or holds this answer. */
        REFUSED,
        /** Nothing to open: the card asked from closed, or the place is that card. */
        SKIPPED
    }

    /** One edge: a result card, what joins it to the card asked from, and its label. */
    private static final class Edge {
        final ProjectMapPreview target;
        final Group group;
        final Label label = new Label();
        double[] drawn = new double[0];

        Edge(ProjectMapPreview target, Group group) {
            this.target = target;
            this.group = group;
        }
    }

    /**
     * The answer to one question, stuck on the card it was asked from: an edge to each result card, and the "+N
     * more" chip under the card listing the groups that did not get one.
     */
    final class EdgeSet implements CardAttachment {
        final ProjectMapPreview from;
        final Kind kind;
        final Via via;
        final @Nullable String name;
        final List<Edge> edges = new ArrayList<>();
        final List<Group> more = new ArrayList<>();
        final Button chip = new Button();
        /** The result cards this answer opened (not those open before it): they close with its card (C11). */
        final Set<ProjectMapPreview> owned = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

        EdgeSet(ProjectMapPreview from, Kind kind, Via via, @Nullable String name) {
            this.from = from;
            this.kind = kind;
            this.via = via;
            this.name = name;
            chip.getStyleClass().add("project-map-link-more");
            chip.setManaged(false);
            chip.setVisible(false);
            chip.setFocusTraversable(true);
            chip.setMnemonicParsing(false);
            chip.setOnAction(event -> showMore());
            canvasHost.getChildren().add(chip);
        }

        /** Opens the card of {@code group} beside the card asked from, with its edge, and says how that went. */
        Opened open(Group group) {
            ProjectMapStructure.Node node = group.declaration();
            if (!cards.isOpen(from)) {
                return Opened.SKIPPED;
            }
            boolean existed = cards.card(group.key()) != null;
            placeBeside.put(group.key(), from);
            ProjectMapPreview card;
            EdgeSet outer = filling;
            filling = this; // the card asked from and this answer's cards are not the ones evicted for it (C4)
            try {
                card = node != null ? cards.openDeclaration(node) : cards.openFile(group.file());
            } finally {
                filling = outer;
            }
            if (card != null && card != from) {
                // At the place itself — the call or the use, selected as find selects a match — now, or once a new
                // card's text is shown and its opening line applied (C2: a declaration card opened at its top).
                card.linksRevealPlace(
                        group.line(),
                        name == null || name.isBlank()
                                ? null
                                : ProjectMapLinkSearch.namePattern(name, kind == Kind.CALLERS));
            }
            cards.touch(from); // used: never the card evicted to make room for the next result
            boolean placedHere = !placeBeside.containsKey(group.key()); // a new card, placed beside it
            placeBeside.remove(group.key());
            if (card == null) {
                return Opened.REFUSED;
            }
            if (card == from) {
                return Opened.SKIPPED;
            }
            if (!existed) {
                owned.add(card);
            }
            if (placedHere) {
                card.keepPlacedWidth();
            }
            Edge edge = new Edge(card, group);
            edge.label.getStyleClass().addAll("project-map-link-label", via == Via.NAME ? "by-name" : "by-server");
            edge.label.setManaged(false);
            edge.label.setMouseTransparent(true);
            edge.label.setText(edgeText(kind, via, group.count()));
            String description = edgeDescription(kind, via, group.label(), from.title(), group.count());
            edge.label.setAccessibleText(description);
            card.setLinkDescription(description); // after its name, and in its title's tooltip
            int layer = canvasHost.getChildren().indexOf(connectorLayer);
            // Under every card, as the edge itself is: a label never covers a card's text.
            canvasHost.getChildren().add(layer < 0 ? canvasHost.getChildren().size() : layer + 1, edge.label);
            edges.add(edge);
            return Opened.OPENED;
        }

        /** Whether an edge of this answer runs to {@code card}. */
        boolean linksTargets(Region card) {
            return edges.stream().anyMatch(edge -> edge.target == card);
        }

        /** Takes the edge to {@code card} away, with its label and the card's link text. */
        void linksDropEdge(ProjectMapPreview card) {
            edges.removeIf(edge -> {
                if (edge.target != card) {
                    return false;
                }
                canvasHost.getChildren().remove(edge.label);
                card.setLinkDescription(null);
                return true;
            });
        }

        void updateChip() {
            chip.setText(tr("project.map.links.more", more.size()));
            chip.setAccessibleText(tr(
                    kind == Kind.CALLERS ? "project.map.links.moreCallers" : "project.map.links.moreReferences",
                    more.size(),
                    name == null ? "" : name));
            chip.setVisible(!more.isEmpty() && from.isVisible());
            if (!more.isEmpty()) {
                chip.toFront(); // over the result cards that just opened (C3)
            }
            cards.connectorsMoved();
        }

        /** The chip's menu: every group without a card; choosing one opens its card with its edge. */
        private void showMore() {
            ContextMenu menu = new ContextMenu();
            Path root = cards.root();
            for (Group group : more.subList(0, Math.min(more.size(), MAX_MORE_ITEMS))) {
                MenuItem item = new MenuItem(tr(
                        "project.map.links.moreItem",
                        group.label(),
                        relative(root, group.file()),
                        group.line() + 1,
                        group.count()));
                item.setMnemonicParsing(false);
                item.setOnAction(event -> {
                    more.remove(group);
                    if (open(group) == Opened.REFUSED) {
                        more.addFirst(group); // no room: it stays listed (C4)
                        cards.status(tr("project.map.links.noRoom"));
                    }
                    updateChip();
                });
                menu.getItems().add(item);
            }
            if (more.size() > MAX_MORE_ITEMS) {
                MenuItem rest = new MenuItem(tr("project.map.links.moreLeft", more.size() - MAX_MORE_ITEMS));
                rest.setDisable(true);
                menu.getItems().add(rest);
            }
            menu.show(chip, javafx.geometry.Side.BOTTOM, 0, 0);
        }

        @Override
        public ProjectMapPreview card() {
            return from;
        }

        @Override
        public double reserved(Side side) {
            return 0; // the edges cross the map; the chip sits under the card
        }

        @Override
        public void raise() {
            chip.toFront(); // the labels stay under the cards, with the edges
        }

        @Override
        public boolean contains(@Nullable Node node) {
            for (Node current = node; current != null; current = current.getParent()) {
                if (current == chip) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public double @Nullable [] bounds() {
            return chip.isVisible() && chip.getWidth() > 0
                    ? new double[] {chip.getLayoutX(), chip.getLayoutY(), chip.getWidth(), chip.getHeight()}
                    : null;
        }

        @Override
        public void drawConnectors(GraphicsContext g) {
            edges.removeIf(edge -> {
                if (!cards.isOpen(edge.target)) {
                    canvasHost.getChildren().remove(edge.label); // its card was closed or evicted
                    return true;
                }
                return false;
            });
            boolean shown = from.isVisible() && from.getWidth() > 0;
            double[] a = MapCards.drawnBounds(from);
            placeChip(shown, a);
            g.save();
            g.setStroke(cards.accent());
            g.setFill(cards.accent());
            g.setLineWidth(1.5);
            for (Edge edge : edges) {
                boolean visible = shown && edge.target.isVisible() && edge.target.getWidth() > 0;
                edge.label.setVisible(visible);
                if (!visible) {
                    edge.drawn = new double[0];
                    continue;
                }
                double[] b = MapCards.drawnBounds(edge.target);
                double[] ends = edgeEnds(b, a); // from the result card to the card asked from: "X calls this"
                edge.drawn = ends;
                g.setLineDashes(via == Via.NAME ? new double[] {6, 5} : null); // by name: dashed
                g.strokeLine(ends[0], ends[1], ends[2], ends[3]);
                arrowHead(g, ends);
                edge.label.applyCss();
                edge.label.autosize();
                double mx = (ends[0] + ends[2]) / 2;
                double my = (ends[1] + ends[3]) / 2;
                edge.label.relocate(mx - edge.label.getWidth() / 2, my - edge.label.getHeight() / 2);
            }
            g.restore();
        }

        private void placeChip(boolean shown, double[] a) {
            boolean visible = shown && !more.isEmpty();
            chip.setVisible(visible);
            if (!visible) {
                return;
            }
            chip.applyCss();
            chip.autosize();
            double y = a[1] - chip.getHeight() - 4; // on the card's top edge, by its title
            if (y < 0) {
                y = a[1] + a[3] + 4; // no room over the card: under it
            }
            chip.relocate(a[0] + 8, y);
        }

        @Override
        public void dispose() {
            if (filling == this) {
                filling = null;
            }
            canvasHost.getChildren().remove(chip);
            edges.forEach(edge -> {
                canvasHost.getChildren().remove(edge.label);
                if (cards.isOpen(edge.target)) {
                    edge.target.setLinkDescription(null); // nothing joins it to that card any more
                }
            });
            edges.clear();
            more.clear();
            cards.connectorsMoved();
        }

        /** Each edge's result card and line as last drawn ({@code {x1, y1, x2, y2}}, empty when hidden). For tests. */
        Map<ProjectMapPreview, double[]> drawnForTest() {
            Map<ProjectMapPreview, double[]> drawn = new IdentityHashMap<>();
            edges.forEach(edge -> drawn.put(edge.target, edge.drawn));
            return drawn;
        }

        /** The labels of the edges, in order. For tests. */
        List<Label> labelsForTest() {
            return edges.stream().map(edge -> edge.label).toList();
        }
    }

    /** The edge sets asked from {@code card}, by kind. For tests. */
    Map<Kind, EdgeSet> setsForTest(ProjectMapPreview card) {
        Map<Kind, EdgeSet> of = sets.get(card);
        return of == null ? Map.of() : Map.copyOf(of);
    }

    /** What an edge's label says: "calls", "references" or "by name", with the count when more than one. */
    static String edgeText(Kind kind, Via via, int count) {
        String word = tr(
                via == Via.NAME
                        ? "project.map.links.edge.byName"
                        : kind == Kind.CALLERS ? "project.map.links.edge.calls" : "project.map.links.edge.references");
        return count > 1 ? tr("project.map.links.edge.count", word, count) : word;
    }

    /** What a screen reader hears of an edge, on its label and on its result card. */
    static String edgeDescription(Kind kind, Via via, String target, String source, int count) {
        String key = via == Via.NAME
                ? "project.map.links.accessible.byName"
                : kind == Kind.CALLERS
                        ? "project.map.links.accessible.calls"
                        : "project.map.links.accessible.references";
        return tr(key, target, source, count);
    }

    /**
     * The line between two boxes {@code {x, y, w, h}}: from the edge of {@code from} to the edge of {@code to}, along
     * the line between their middles, as {@code {x1, y1, x2, y2}}. Boxes that overlap give their middles. Pure.
     */
    static double[] edgeEnds(double[] from, double[] to) {
        double fx = from[0] + from[2] / 2;
        double fy = from[1] + from[3] / 2;
        double tx = to[0] + to[2] / 2;
        double ty = to[1] + to[3] / 2;
        double[] start = boxExit(from, fx, fy, tx - fx, ty - fy);
        double[] end = boxExit(to, tx, ty, fx - tx, fy - ty);
        boolean overlap = from[0] < to[0] + to[2]
                && to[0] < from[0] + from[2]
                && from[1] < to[1] + to[3]
                && to[1] < from[1] + from[3];
        return overlap ? new double[] {fx, fy, tx, ty} : new double[] {start[0], start[1], end[0], end[1]};
    }

    /** Where a ray from the middle {@code cx, cy} of {@code box} going {@code dx, dy} leaves the box. */
    private static double[] boxExit(double[] box, double cx, double cy, double dx, double dy) {
        double half = box[2] / 2;
        double halfHeight = box[3] / 2;
        double scale = Double.MAX_VALUE;
        if (Math.abs(dx) > 1e-9) {
            scale = Math.min(scale, half / Math.abs(dx));
        }
        if (Math.abs(dy) > 1e-9) {
            scale = Math.min(scale, halfHeight / Math.abs(dy));
        }
        if (scale == Double.MAX_VALUE) {
            return new double[] {cx, cy};
        }
        return new double[] {cx + dx * scale, cy + dy * scale};
    }

    /** An arrow head at the end of {@code ends}, pointing along it. */
    private static void arrowHead(GraphicsContext g, double[] ends) {
        double angle = Math.atan2(ends[3] - ends[1], ends[2] - ends[0]);
        double size = 8;
        double x = ends[2];
        double y = ends[3];
        g.setLineDashes((double[]) null);
        g.fillPolygon(
                new double[] {x, x - size * Math.cos(angle - 0.45), x - size * Math.cos(angle + 0.45)},
                new double[] {y, y - size * Math.sin(angle - 0.45), y - size * Math.sin(angle + 0.45)},
                3);
    }

    /** An answer waiting for the outlines of its first files. */
    private static final class Pending {
        final ProjectMapPreview from;
        final Request request;
        final long id;
        final Answer answer;
        final @Nullable String name;
        final Set<Path> outlined = new LinkedHashSet<>();
        final Set<Path> waitingFor = new LinkedHashSet<>();
        final javafx.animation.PauseTransition timeout = new javafx.animation.PauseTransition(OUTLINE_WAIT);

        Pending(ProjectMapPreview from, Request request, long id, Answer answer, @Nullable String name) {
            this.from = from;
            this.request = request;
            this.id = id;
            this.answer = answer;
            this.name = name;
        }
    }
}
