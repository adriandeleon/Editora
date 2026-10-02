package com.editora.todo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import javafx.application.Platform;

import com.editora.search.GitignoreFilter;
import com.editora.search.ProjectWalk;

/**
 * Scans for TODO/highlight pattern matches off the JavaFX thread (the {@code SearchService} idiom: a
 * single daemon executor + a generation guard so a superseded scan is dropped). Walks a scope root
 * (skipping dot-dirs, {@code .gitignore}d paths when enabled, oversized and binary files) and scans
 * each file; <b>open buffers</b> are scanned
 * from their in-memory text (passed in) so unsaved edits count, and open files outside the root are
 * included too. Results are capped and posted back via {@link Platform#runLater}.
 */
public final class TodoService {

    /** All matches in one file, in document order. */
    public record FileTodos(Path file, List<TodoMatch> matches) {}

    /** The result set, with caps so a huge tree can't freeze the UI. */
    public record Outcome(List<FileTodos> files, int totalMatches, int fileCount, boolean truncated) {}

    private static final int MAX_DEPTH = 25;
    private static final int MAX_FILES_SCANNED = 20_000;
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    private static final int MAX_MATCHES = 5_000;

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "todo-scan");
        t.setDaemon(true);
        return t;
    });
    private final AtomicLong gen = new AtomicLong();
    /** When true, the tree walk excludes {@code .gitignore}d files/folders (target/, node_modules/, …),
     *  mirroring Find in Files. Driven by {@code Settings.searchRespectGitignore}. */
    private volatile boolean respectGitignore = true;

    /** Sets whether the tree walk honors {@code .gitignore} (the shared "exclude .gitignore" setting). */
    public void setRespectGitignore(boolean respect) {
        this.respectGitignore = respect;
    }

    /**
     * Scans {@code scopeRoot} (may be null) plus all {@code openContents} (path → in-memory text) with
     * the compiled {@code patterns} and posts the {@link Outcome} on the FX thread, unless a newer scan
     * has since started.
     */
    public void scan(
            List<TodoPatterns.Compiled> patterns,
            Path scopeRoot,
            Map<Path, String> openContents,
            Consumer<Outcome> onResult) {
        long g = gen.incrementAndGet();
        Map<Path, String> open = openContents == null ? Map.of() : Map.copyOf(openContents);
        exec.submit(() -> {
            Outcome outcome = run(patterns, scopeRoot, open);
            if (g == gen.get()) {
                Platform.runLater(() -> onResult.accept(outcome));
            }
        });
    }

    private Outcome run(List<TodoPatterns.Compiled> patterns, Path scopeRoot, Map<Path, String> open) {
        if (patterns == null || patterns.isEmpty()) {
            return new Outcome(List.of(), 0, 0, false);
        }
        Set<Path> candidates = new LinkedHashSet<>();
        if (scopeRoot != null && Files.isDirectory(scopeRoot)) {
            GitignoreFilter gitignore = respectGitignore ? GitignoreFilter.load(scopeRoot) : GitignoreFilter.NONE;
            collect(scopeRoot, candidates, gitignore);
        }
        candidates.addAll(open.keySet()); // open buffers, even outside the root

        List<FileTodos> results = new ArrayList<>();
        int total = 0;
        boolean truncated = false;
        for (Path file : candidates) {
            String content = open.get(file);
            if (content == null) {
                content = readText(file);
            }
            if (content == null || content.indexOf('\0') >= 0) {
                continue; // unreadable or binary
            }
            List<TodoMatch> ms = TodoScanner.scan(content, patterns);
            if (ms.isEmpty()) {
                continue;
            }
            if (total + ms.size() > MAX_MATCHES) {
                ms = ms.subList(0, Math.max(0, MAX_MATCHES - total));
                truncated = true;
            }
            if (!ms.isEmpty()) {
                results.add(new FileTodos(file, ms));
                total += ms.size();
            }
            if (truncated) {
                break;
            }
        }
        results.sort((a, b) -> a.file().toString().compareToIgnoreCase(b.file().toString()));
        return new Outcome(results, total, results.size(), truncated);
    }

    /** Collects the scan candidates through the shared pruned walk (the same one Find in Files uses). */
    private void collect(Path root, Set<Path> out, GitignoreFilter gitignore) {
        ProjectWalk.walk(root, new ProjectWalk.Options(MAX_DEPTH, MAX_FILES_SCANNED, gitignore), (file, rel, attrs) -> {
            if (!attrs.isRegularFile() || attrs.size() > MAX_FILE_BYTES) {
                return ProjectWalk.Verdict.SKIP;
            }
            out.add(file);
            return ProjectWalk.Verdict.ACCEPT;
        });
    }

    private static String readText(Path file) {
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                return null;
            }
            return Files.readString(file); // UTF-8; throws on invalid bytes → treated as binary
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Stops the background scan thread (called when the owning window closes). */
    public void shutdown() {
        exec.shutdownNow();
    }
}
