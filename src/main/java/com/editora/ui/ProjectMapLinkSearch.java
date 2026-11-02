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
        String given = request.text();
        if (given != null) {
            located(root, request, given, answer);
            return;
        }
        run(() -> {
            String text = read(request.file());
            ui.execute(() -> {
                if (text == null) {
                    answer.accept(MapCardLinks.Answer.none(
                            MapCardLinks.Via.NAME,
                            tr(
                                    "project.map.links.unreadable",
                                    com.editora.config.PathDisplay.fileName(request.file()))));
                } else {
                    located(root, request, text, answer);
                }
            });
        });
    }

    /** With the file's text in hand: where the name is, then the server's answer or the name match. FX thread. */
    private void located(Path root, MapCardLinks.Request request, String text, Consumer<MapCardLinks.Answer> answer) {
        String[] lines = text.split("\\R", -1);
        int line = Math.max(0, Math.min(request.line(), lines.length - 1));
        String lineText = lines.length == 0 ? "" : lines[line];
        String language = LanguageRegistry.forFileName(
                request.file().getFileName() == null
                        ? ""
                        : request.file().getFileName().toString());
        String name = request.name();
        int column = request.column();
        if (column < 0) {
            column = name == null ? -1 : wordAt(lineText, name);
        } else if (name == null || name.isBlank()) {
            name = IndexCoordinator.nameAt(lineText, column, language);
        }
        if (name == null || name.isBlank()) {
            answer.accept(MapCardLinks.Answer.none(MapCardLinks.Via.NAME, tr("project.map.links.noName")));
            return;
        }
        if (column < 0) {
            column = Math.max(0, lineText.indexOf(name));
        }
        String found = name;
        MapCardLinks.Request at = new MapCardLinks.Request(request.kind(), request.file(), line, column, found, text);
        if (!askServer(at, text, answer)) {
            String note = lsp != null && lsp.runsFor(request.file())
                    ? tr("project.map.links.serverCannot")
                    : tr("project.map.links.noServer");
            byName(root, at, language, note, answer);
        }
    }

    /**
     * Asks the running server, the file lent to it while it answers. False when no server runs for the file or it
     * does not answer this request: the caller matches by name.
     */
    private boolean askServer(MapCardLinks.Request at, String text, Consumer<MapCardLinks.Answer> answer) {
        LspManager servers = lsp;
        if (servers == null) {
            return false;
        }
        Runnable loan;
        try {
            loan = servers.lendDocument(at.file(), text);
        } catch (RuntimeException unavailable) {
            ProjectMapLog.unexpected("lending a file to its language server", unavailable);
            loan = null;
        }
        if (loan == null) {
            return false;
        }
        Runnable release = loan;
        Path file = at.file();
        boolean callers = at.kind() == MapCardLinks.Kind.CALLERS;
        if (callers ? !servers.supportsCallHierarchy(file) : !servers.supportsReferences(file)) {
            release.run();
            return false;
        }
        String name = at.name();
        if (callers) {
            servers.prepareCallHierarchy(file, at.line(), at.column(), items -> {
                if (items.isEmpty()) {
                    release.run();
                    answer.accept(new MapCardLinks.Answer(List.of(), MapCardLinks.Via.SERVER, false, null, name));
                    return;
                }
                servers.callHierarchyChildren(file, items.getFirst().raw(), true, calls -> {
                    release.run();
                    List<MapCardLinks.Hit> hits = new ArrayList<>();
                    for (LspManager.HierarchyNode caller : calls) {
                        Path from = caller.file();
                        if (from != null) { // a library's caller has no file on the map
                            hits.add(new MapCardLinks.Hit(
                                    from, caller.line(), caller.col(), caller.calls(), caller.name()));
                        }
                    }
                    answer.accept(new MapCardLinks.Answer(hits, MapCardLinks.Via.SERVER, false, null, name));
                });
            });
        } else {
            servers.references(
                    file,
                    at.line(),
                    at.column(),
                    targets -> {
                        release.run();
                        List<MapCardLinks.Hit> hits = new ArrayList<>();
                        for (LspManager.Target target : targets) {
                            Path to = target.file();
                            if (to != null) {
                                hits.add(new MapCardLinks.Hit(to, target.line(), target.character(), 1, null));
                            }
                        }
                        answer.accept(new MapCardLinks.Answer(hits, MapCardLinks.Via.SERVER, false, null, name));
                    },
                    why -> {
                        release.run();
                        answer.accept(MapCardLinks.Answer.none(
                                MapCardLinks.Via.SERVER, tr("project.map.links.serverFailed", why)));
                    });
        }
        return true;
    }

    /** The name matched as a whole word in the project's files of {@code language}, off the FX thread. */
    private void byName(
            Path root, MapCardLinks.Request at, String language, String note, Consumer<MapCardLinks.Answer> answer) {
        String name = at.name() == null ? "" : at.name();
        run(() -> {
            MapCardLinks.Answer found;
            try {
                found = scan(root, name, language, at.kind() == MapCardLinks.Kind.CALLERS, note);
            } catch (RuntimeException failed) {
                ProjectMapLog.unexpected("matching a declaration's name", failed);
                found = MapCardLinks.Answer.none(MapCardLinks.Via.NAME, note);
            }
            MapCardLinks.Answer done = found;
            ui.execute(() -> answer.accept(done));
        });
    }

    /** The name match itself. Blocking. */
    MapCardLinks.Answer scan(Path root, String name, String language, boolean calls, String note) {
        com.editora.search.ProjectFileList.Snapshot listed = files.snapshot(root);
        if (listed == null || name.isBlank()) {
            return MapCardLinks.Answer.none(MapCardLinks.Via.NAME, note);
        }
        Pattern word = namePattern(name, calls);
        long deadline = clock.getAsLong() + BUDGET_MILLIS;
        List<MapCardLinks.Hit> hits = new ArrayList<>();
        boolean partial = listed.truncated();
        int read = 0;
        for (String relative : listed.files()) {
            String fileName = relative.substring(relative.lastIndexOf('/') + 1);
            if (!language.equals(LanguageRegistry.forFileName(fileName))) {
                continue;
            }
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
