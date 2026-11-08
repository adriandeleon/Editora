package com.editora.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javafx.application.Platform;

import com.editora.lsp.LspManager;
import com.editora.text.LanguageRegistry;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Where the Project Map's linked reference cards find their places ({@link MapCardLinks.Source}, Project Map explore
 * position 5). With a language server that already runs for the file in this window, the file is lent to it — no
 * editor tab is opened or raised — and asked {@code textDocument/references}, or {@code prepareCallHierarchy} then
 * {@code callHierarchy/incomingCalls}; the loan ends with the answer. Nothing starts a server. Without one (or one
 * that does not answer that request) the declaration's name is matched as a whole word in the project's files of
 * the same language — for callers, only where it is followed by {@code (} — within a budget, and the answer says
 * the places were found by name.
 */
final class ProjectMapLinkSearch implements MapCardLinks.Source {

    /** Files a name match reads at most. */
    static final int MAX_FILES = 20_000;
    /** A file larger than this is not read for a name match. */
    static final long MAX_FILE_BYTES = 1_000_000;
    /** Places a name match collects at most. */
    static final int MAX_HITS = 2_000;
    /** How long a name match reads files for. */
    static final long BUDGET_MILLIS = 2_000;

    private final @Nullable LspManager lsp;
    private final ProjectIndex files;
    private final Executor background;
    private final Executor ui;
    private final LongSupplier clock;

    /**
     * @param lsp the window's language servers, or null for name matching only
     * @param files the project's file list (the Map's), which a name match reads
     * @param background where files are read
     * @param ui the FX thread
     */
    ProjectMapLinkSearch(
            @Nullable LspManager lsp, ProjectIndex files, Executor background, Executor ui, LongSupplier clock) {
        this.lsp = lsp;
        this.files = files;
        this.background = background;
        this.ui = ui;
        this.clock = clock;
    }

    /** The editor's: files read on a daemon thread of its own, answers on the FX thread. */
    ProjectMapLinkSearch(@Nullable LspManager lsp, ProjectIndex files) {
        this(
                lsp,
                files,
                new java.util.concurrent.ThreadPoolExecutor( // one thread, gone when idle: nothing to shut down
                        0,
                        1,
                        30,
                        java.util.concurrent.TimeUnit.SECONDS,
                        new java.util.concurrent.LinkedBlockingQueue<>(),
                        runnable -> {
                            Thread thread = new Thread(runnable, "project-map-links");
                            thread.setDaemon(true);
                            return thread;
                        }),
                Platform::runLater,
                System::currentTimeMillis);
    }

    @Override
    public void find(Path root, MapCardLinks.Request request, Consumer<MapCardLinks.Answer> answer) {
        withText(request, answer::accept, text -> located(root, request, text, answer));
    }

    /**
     * {@code then} with the file's text — the editor's when given, else read off the FX thread — or {@code
     * unreadable} told why it could not be read. FX thread.
     */
    private void withText(
            MapCardLinks.Request request, Consumer<MapCardLinks.Answer> unreadable, Consumer<String> then) {
        String given = request.text();
        if (given != null) {
            then.accept(given);
            return;
        }
        run(() -> {
            String text = read(request.file());
            ui.execute(() -> {
                if (text == null) {
                    unreadable.accept(MapCardLinks.Answer.none(
                            MapCardLinks.Via.NAME,
                            tr(
                                    "project.map.links.unreadable",
                                    com.editora.config.PathDisplay.fileName(request.file()))));
                } else {
                    then.accept(text);
                }
            });
        });
    }

    /**
     * The request with its place settled from the file's text: the column of its name on its line (a negative
     * column), or the name at its column; null when there is no name there. Pure.
     */
    static MapCardLinks.@Nullable Request settle(MapCardLinks.Request request, String text) {
        String[] lines = text.split("\\R", -1);
        int line = Math.max(0, Math.min(request.line(), lines.length - 1));
        String lineText = lines.length == 0 ? "" : lines[line];
        String language = languageOf(request.file());
        String name = request.name();
        int column = request.column();
        if (column < 0) {
            column = name == null ? -1 : wordAt(lineText, name);
        } else if (name == null || name.isBlank()) {
            name = IndexCoordinator.nameAt(lineText, column, language);
        }
        if (name == null || name.isBlank()) {
            return null;
        }
        if (column < 0) {
            column = Math.max(0, lineText.indexOf(name));
        }
        return new MapCardLinks.Request(request.kind(), request.file(), line, column, name, text);
    }

    private static String languageOf(Path file) {
        return LanguageRegistry.forFileName(
                file.getFileName() == null ? "" : file.getFileName().toString());
    }

    /**
     * Quick Documentation at the request's place: the running server's hover, the file lent to it while it answers.
     * Without a server the answer has no text and says why. Answered once, on the FX thread.
     */
    @Override
    public void hover(Path root, MapCardLinks.Request request, Consumer<MapCardLinks.Hover> answer) {
        withText(request, none -> answer.accept(new MapCardLinks.Hover(null, none.note(), request.name())), text -> {
            MapCardLinks.Request at = settle(request, text);
            if (at == null) {
                answer.accept(new MapCardLinks.Hover(null, tr("project.map.links.noName"), null));
                return;
            }
            LspManager servers = lsp;
            Runnable loan = servers == null ? null : lend(servers, at.file(), text);
            if (servers == null || loan == null) {
                answer.accept(new MapCardLinks.Hover(null, tr("project.map.links.hover.noServer"), at.name()));
                return;
            }
            servers.hover(
                    at.file(),
                    at.line(),
                    at.column(),
                    markdown -> {
                        loan.run();
                        answer.accept(new MapCardLinks.Hover(
                                markdown == null || markdown.isBlank() ? null : markdown, null, at.name()));
                    },
                    why -> {
                        loan.run();
                        answer.accept(
                                new MapCardLinks.Hover(null, tr("project.map.links.hover.failed", why), at.name()));
                    });
        });
    }

    /** {@code file} lent to its running server, or null when none runs for it. FX thread. */
    private static @Nullable Runnable lend(LspManager servers, Path file, String text) {
        try {
            return servers.lendDocument(file, text);
        } catch (RuntimeException unavailable) {
            ProjectMapLog.unexpected("lending a file to its language server", unavailable);
            return null;
        }
    }

    /** With the file's text in hand: where the name is, then the server's answer or the name match. FX thread. */
    private void located(Path root, MapCardLinks.Request request, String text, Consumer<MapCardLinks.Answer> answer) {
        MapCardLinks.Request at = settle(request, text);
        if (at == null) {
            answer.accept(MapCardLinks.Answer.none(MapCardLinks.Via.NAME, tr("project.map.links.noName")));
            return;
        }
        String language = languageOf(request.file());
        boolean matchable = byNameKinds(at.kind());
        // A server that fails or times out is not taken to mean "nothing there": the name is matched instead, and
        // the status says the server did not answer (review 4, C6). What only a server can answer says so.
        Consumer<String> failed = why -> {
            if (matchable) {
                byName(root, at, language, tr("project.map.links.serverFailedByName", why), answer);
            } else {
                answer.accept(MapCardLinks.Answer.none(
                        MapCardLinks.Via.SERVER, tr("project.map.links.serverFailed", why), at.name()));
            }
        };
        if (!askServer(at, text, answer, failed)) {
            boolean runs = lsp != null && lsp.runsFor(request.file());
            if (matchable) {
                byName(
                        root,
                        at,
                        language,
                        tr(runs ? "project.map.links.serverCannot" : "project.map.links.noServer"),
                        answer);
            } else {
                answer.accept(MapCardLinks.Answer.none(
                        MapCardLinks.Via.SERVER,
                        tr(runs ? "project.map.links.serverCannotAnswer" : "project.map.links.needsServer"),
                        at.name()));
            }
        }
    }

    /**
     * Whether {@code kind} can be answered without a server: references and callers by the name's uses, a definition
     * or declaration by the declarations of that name. Pure.
     */
    static boolean byNameKinds(MapCardLinks.Kind kind) {
        return switch (kind) {
            case REFERENCES, CALLERS, DEFINITION, DECLARATION -> true;
            default -> false;
        };
    }

    /**
     * Asks the running server, the file lent to it while it answers. False when no server runs for the file or it
     * does not answer this request: the caller matches by name.
     */
    private boolean askServer(
            MapCardLinks.Request at, String text, Consumer<MapCardLinks.Answer> answer, Consumer<String> failed) {
        LspManager servers = lsp;
        if (servers == null) {
            return false;
        }
        Runnable loan = lend(servers, at.file(), text);
        if (loan == null) {
            return false;
        }
        Runnable release = loan;
        Path file = at.file();
        MapCardLinks.Kind kind = at.kind();
        if (!answers(servers, kind, file)) {
            release.run();
            return false;
        }
        String name = at.name();
        Consumer<String> failedThenReleased = why -> {
            release.run();
            failed.accept(why);
        };
        Consumer<List<LspManager.HierarchyNode>> nodesFound = nodes -> {
            release.run();
            List<MapCardLinks.Hit> hits = new ArrayList<>();
            for (LspManager.HierarchyNode node : nodes) {
                Path from = node.file();
                if (from != null) { // a library's caller or type has no file on the map
                    hits.add(new MapCardLinks.Hit(from, node.line(), node.col(), node.calls(), node.name()));
                }
            }
            String note = hits.isEmpty() && !nodes.isEmpty() ? tr("project.map.links.inLibrary") : null;
            answer.accept(new MapCardLinks.Answer(hits, MapCardLinks.Via.SERVER, false, note, name));
        };
        Consumer<List<LspManager.Target>> targetsFound = targets -> {
            release.run();
            List<MapCardLinks.Hit> hits = new ArrayList<>();
            for (LspManager.Target target : targets) {
                Path to = target.file();
                if (to != null) {
                    hits.add(new MapCardLinks.Hit(to, target.line(), target.character(), 1, null));
                }
            }
            String note = hits.isEmpty() && !targets.isEmpty() ? tr("project.map.links.inLibrary") : null;
            answer.accept(new MapCardLinks.Answer(hits, MapCardLinks.Via.SERVER, false, note, name));
        };
        int line = at.line();
        int column = at.column();
        switch (kind) {
            case CALLERS, CALLEES, SUPERTYPES, SUBTYPES -> {
                boolean calls = kind == MapCardLinks.Kind.CALLERS || kind == MapCardLinks.Kind.CALLEES;
                Consumer<List<LspManager.HierarchyNode>> prepared = items -> {
                    if (items.isEmpty()) {
                        release.run();
                        answer.accept(new MapCardLinks.Answer(List.of(), MapCardLinks.Via.SERVER, false, null, name));
                    } else if (calls) {
                        servers.callHierarchyChildren(
                                file,
                                items.getFirst().raw(),
                                kind == MapCardLinks.Kind.CALLERS, // incoming; the callees are the outgoing calls
                                nodesFound,
                                failedThenReleased);
                    } else {
                        servers.typeHierarchyChildren(
                                file, items.getFirst().raw(), kind == MapCardLinks.Kind.SUPERTYPES, nodesFound);
                    }
                };
                if (calls) {
                    servers.prepareCallHierarchy(file, line, column, prepared, failedThenReleased);
                } else {
                    servers.prepareTypeHierarchy(file, line, column, prepared);
                }
            }
            case REFERENCES -> servers.references(file, line, column, targetsFound, failedThenReleased);
            case DEFINITION -> servers.definition(file, line, column, targetsFound, failedThenReleased);
            case DECLARATION -> servers.declarationAt(file, null, line, column, targetsFound, failedThenReleased);
            case TYPE_DEFINITION ->
                servers.typeDefinitionAt(file, null, line, column, targetsFound, failedThenReleased);
            case IMPLEMENTATIONS ->
                servers.implementationAt(file, null, line, column, targetsFound, failedThenReleased);
            case SUPER_METHOD -> servers.superMethodAt(file, null, line, column, targetsFound, failedThenReleased);
        }
        return true;
    }

    /** Whether {@code file}'s running server answers {@code kind} (a definition: every server does). */
    private static boolean answers(LspManager servers, MapCardLinks.Kind kind, Path file) {
        return switch (kind) {
            case REFERENCES -> servers.supportsReferences(file);
            case CALLERS, CALLEES -> servers.supportsCallHierarchy(file);
            case DEFINITION -> true;
            case DECLARATION -> servers.supportsDeclaration(file);
            case TYPE_DEFINITION -> servers.supportsTypeDefinition(file);
            case IMPLEMENTATIONS -> servers.supportsImplementation(file);
            case SUPER_METHOD -> servers.supportsSuperMethod(file);
            case SUPERTYPES, SUBTYPES -> servers.supportsTypeHierarchy(file);
        };
    }

    /** The name matched as a whole word in the project's files of {@code language}, off the FX thread. */
    private void byName(
            Path root, MapCardLinks.Request at, String language, String note, Consumer<MapCardLinks.Answer> answer) {
        String name = at.name() == null ? "" : at.name();
        run(() -> {
            MapCardLinks.Answer found;
            try {
                found = at.kind().leads
                        ? declarations(root, at, language, note)
                        : scan(root, name, language, at.kind() == MapCardLinks.Kind.CALLERS, note, at.file());
            } catch (RuntimeException failed) {
                ProjectMapLog.unexpected("matching a declaration's name", failed);
                found = MapCardLinks.Answer.none(MapCardLinks.Via.NAME, note, name);
            }
            MapCardLinks.Answer done = found;
            ui.execute(() -> answer.accept(done));
        });
    }

    /**
     * Go to Definition without a server: the declaration of the name that the asked place most likely means — in its
     * own file, a JavaScript or TypeScript local of an enclosing scope first, then the nearest declaration at or above
     * it — else every declaration of that name in the project's files of its language, nearest the asked file first,
     * within the name match's budget. Blocking.
     */
    MapCardLinks.Answer declarations(Path root, MapCardLinks.Request at, String language, @Nullable String note) {
        String name = at.name() == null ? "" : at.name();
        String text = at.text();
        if (name.isBlank() || !com.editora.index.DeclarationScanner.supports(language)) {
            return MapCardLinks.Answer.none(MapCardLinks.Via.NAME, note, name.isBlank() ? null : name);
        }
        if (text != null) {
            int offset = offsetOf(text, at.line(), at.column());
            int local = com.editora.index.JsLocals.declarationOf(text, offset, name, language);
            if (local >= 0) {
                int[] place = placeOf(text, local);
                return new MapCardLinks.Answer(
                        List.of(new MapCardLinks.Hit(at.file(), place[0], place[1], 1, name)),
                        MapCardLinks.Via.NAME,
                        false,
                        note,
                        name);
            }
            com.editora.index.Symbol own = IndexCoordinator.nearestDeclaration(
                    com.editora.index.DeclarationScanner.scan(text, language), name, at.line());
            if (own != null) {
                return new MapCardLinks.Answer(
                        List.of(new MapCardLinks.Hit(at.file(), own.line(), own.column(), 1, name)),
                        MapCardLinks.Via.NAME,
                        false,
                        note,
                        name);
            }
        }
        com.editora.search.ProjectFileList.Snapshot listed = files.snapshot(root);
        if (listed == null) {
            return MapCardLinks.Answer.none(MapCardLinks.Via.NAME, note, name);
        }
        long deadline = clock.getAsLong() + BUDGET_MILLIS;
        List<MapCardLinks.Hit> hits = new ArrayList<>();
        boolean partial = listed.truncated();
        int read = 0;
        for (String relative : linksScanOrder(listed.files(), language, root, at.file())) {
            Path file = root.resolve(relative);
            if (read++ >= MAX_FILES || hits.size() >= MAX_HITS || clock.getAsLong() > deadline) {
                partial = true;
                break;
            }
            if (file.equals(at.file())) {
                continue; // read above
            }
            String body = read(file);
            if (body == null || body.indexOf(name) < 0) {
                continue;
            }
            for (com.editora.index.Symbol symbol : com.editora.index.DeclarationScanner.scan(body, language)) {
                if (symbol.name().equals(name) && hits.size() < MAX_HITS) {
                    hits.add(new MapCardLinks.Hit(file, symbol.line(), symbol.column(), 1, name));
                }
            }
        }
        return new MapCardLinks.Answer(hits, MapCardLinks.Via.NAME, partial, note, name);
    }

    /** The offset of the 0-based {@code line} and {@code column} in {@code text}, clamped to it. Pure. */
    static int offsetOf(String text, int line, int column) {
        int start = 0;
        for (int at = 0; at < line; at++) {
            int next = text.indexOf('\n', start);
            if (next < 0) {
                return text.length();
            }
            start = next + 1;
        }
        int end = text.indexOf('\n', start);
        return Math.min(start + Math.max(0, column), end < 0 ? text.length() : end);
    }

    /** The 0-based line and column of {@code offset} in {@code text}. Pure. */
    static int[] placeOf(String text, int offset) {
        int line = 0;
        int lineStart = 0;
        for (int i = text.indexOf('\n'); i >= 0 && i < offset; i = text.indexOf('\n', i + 1)) {
            line++;
            lineStart = i + 1;
        }
        return new int[] {line, offset - lineStart};
    }

    /** The name match itself, in the file list's order. Blocking. */
    MapCardLinks.Answer scan(Path root, String name, String language, boolean calls, @Nullable String note) {
        return scan(root, name, language, calls, note, null);
    }

    /**
     * The name match itself, the files nearest {@code near} (the asked file) read first — its own folder, then its
     * module's, then the rest, tests after the code — so that a popular name's capped answer is the code around the
     * declaration rather than whatever the list met first (review 4, C9). Blocking.
     */
    MapCardLinks.Answer scan(
            Path root, String name, String language, boolean calls, @Nullable String note, @Nullable Path near) {
        com.editora.search.ProjectFileList.Snapshot listed = files.snapshot(root);
        if (listed == null || name.isBlank()) {
            return MapCardLinks.Answer.none(MapCardLinks.Via.NAME, note, name.isBlank() ? null : name);
        }
        Pattern word = namePattern(name, calls);
        long deadline = clock.getAsLong() + BUDGET_MILLIS;
        List<MapCardLinks.Hit> hits = new ArrayList<>();
        boolean partial = listed.truncated();
        int read = 0;
        for (String relative : linksScanOrder(listed.files(), language, root, near)) {
            Path file = root.resolve(relative);
            if (read++ >= MAX_FILES || hits.size() >= MAX_HITS || clock.getAsLong() > deadline) {
                partial = true;
                break;
            }
            String text = read(file);
            if (text == null || text.indexOf(name) < 0) {
                continue;
            }
            matches(file, text, word, hits);
        }
        return new MapCardLinks.Answer(hits, MapCardLinks.Via.NAME, partial, note, name);
    }

    /**
     * The listed files of {@code language} in the order a name match reads them: nearest {@code near}'s folder first
     * (the most leading folders in common), code before tests unless {@code near} is a test, else in list order.
     * Pure.
     */
    static List<String> linksScanOrder(List<String> listed, String language, Path root, @Nullable Path near) {
        List<String> ofLanguage = new ArrayList<>();
        for (String relative : listed) {
            String fileName = relative.substring(relative.lastIndexOf('/') + 1);
            if (language.equals(LanguageRegistry.forFileName(fileName))) {
                ofLanguage.add(relative);
            }
        }
        if (near == null || !near.startsWith(root)) {
            return ofLanguage;
        }
        String nearRelative = com.editora.search.ProjectWalk.relativize(root, near);
        String[] nearFolders = folders(nearRelative);
        boolean fromTest = MapCardLinks.linksLooksLikeTest(nearRelative);
        record Ranked(String relative, int test, int shared) {}
        List<Ranked> ranked = new ArrayList<>(ofLanguage.size());
        for (String relative : ofLanguage) {
            String[] at = folders(relative);
            int shared = 0;
            while (shared < at.length && shared < nearFolders.length && at[shared].equals(nearFolders[shared])) {
                shared++;
            }
            ranked.add(new Ranked(relative, !fromTest && MapCardLinks.linksLooksLikeTest(relative) ? 1 : 0, shared));
        }
        ranked.sort(java.util.Comparator.comparingInt(Ranked::test)
                .thenComparing(java.util.Comparator.comparingInt(Ranked::shared).reversed()));
        return ranked.stream().map(Ranked::relative).toList();
    }

    /** The folders of a {@code /}-separated relative path, without its file name. */
    private static String[] folders(String relative) {
        int slash = relative.lastIndexOf('/');
        return slash < 0 ? new String[0] : relative.substring(0, slash).split("/");
    }

    /** Whole-word occurrences of a name; for {@code calls} only where a {@code (} follows. */
    static Pattern namePattern(String name, boolean calls) {
        return Pattern.compile(
                "(?<![\\p{L}\\p{N}_$])" + Pattern.quote(name) + "(?![\\p{L}\\p{N}_$])" + (calls ? "(?=\\s*\\()" : ""));
    }

    /** Adds each of {@code word}'s matches in {@code text} (of {@code file}) as a place, up to {@link #MAX_HITS}. */
    static void matches(Path file, String text, Pattern word, List<MapCardLinks.Hit> hits) {
        Matcher matcher = word.matcher(text);
        int line = 0;
        int lineStart = 0;
        int scanned = 0;
        while (matcher.find() && hits.size() < MAX_HITS) {
            for (int i = scanned; i < matcher.start(); i++) {
                if (text.charAt(i) == '\n') {
                    line++;
                    lineStart = i + 1;
                }
            }
            scanned = matcher.start();
            hits.add(new MapCardLinks.Hit(file, line, matcher.start() - lineStart, 1, null));
        }
    }

    /** The column where {@code name} stands as a whole word in {@code line}, or -1. */
    static int wordAt(String line, String name) {
        Matcher matcher = namePattern(name, false).matcher(line);
        return matcher.find() ? matcher.start() : -1;
    }

    private static @Nullable String read(Path file) {
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                return null;
            }
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private void run(Runnable task) {
        try {
            background.execute(task);
        } catch (RejectedExecutionException shutDown) {
            // the window is closing
        }
    }
}
