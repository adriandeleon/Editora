package com.editora.search;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
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

/**
 * Runs a multi-file search off the JavaFX thread (the {@code GitService} idiom: a single daemon
 * executor + a generation guard so a superseded search is dropped). Walks a scope root (skipping
 * dot-dirs, oversized and binary files) and searches each file; <b>open buffers</b> are searched from
 * their in-memory text (passed in) so unsaved edits are respected, and open files outside the root are
 * included too. Results are capped and posted back via {@link Platform#runLater}.
 */
public final class SearchService {

    /** The result set, with caps so a huge tree can't freeze the UI. */
    public record Outcome(List<FileResult> files, int totalMatches, int fileCount, boolean truncated) {}

    private static final int MAX_DEPTH = 25;
    private static final int MAX_FILES_SCANNED = 20_000;
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    private static final int MAX_MATCHES = 5_000;

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "search");
        t.setDaemon(true);
        return t;
    });
    private final AtomicLong gen = new AtomicLong();

    /**
     * Searches {@code scopeRoot} (may be null) plus all {@code openContents} (path → in-memory text)
     * and posts the {@link Outcome} on the FX thread, unless a newer search has since started.
     */
    public void search(SearchQuery query, Path scopeRoot, Map<Path, String> openContents, Consumer<Outcome> onResult) {
        long g = gen.incrementAndGet();
        Map<Path, String> open = openContents == null ? Map.of() : Map.copyOf(openContents);
        exec.submit(() -> {
            Outcome outcome = run(query, scopeRoot, open);
            if (g == gen.get()) {
                Platform.runLater(() -> onResult.accept(outcome));
            }
        });
    }

    private Outcome run(SearchQuery query, Path scopeRoot, Map<Path, String> open) {
        if (query == null || query.text() == null || query.text().isEmpty()) {
            return new Outcome(List.of(), 0, 0, false);
        }
        Set<Path> candidates = new LinkedHashSet<>();
        if (scopeRoot != null && Files.isDirectory(scopeRoot)) {
            collect(scopeRoot, candidates);
        }
        candidates.addAll(open.keySet()); // open buffers, even outside the root

        List<FileResult> results = new ArrayList<>();
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
            List<LineMatch> ms = MultiFileSearch.matchesInText(content, query);
            if (ms.isEmpty()) {
                continue;
            }
            if (total + ms.size() > MAX_MATCHES) {
                ms = ms.subList(0, Math.max(0, MAX_MATCHES - total));
                truncated = true;
            }
            if (!ms.isEmpty()) {
                results.add(new FileResult(file, ms));
                total += ms.size();
            }
            if (truncated) {
                break;
            }
        }
        results.sort((a, b) -> a.file().toString().compareToIgnoreCase(b.file().toString()));
        return new Outcome(results, total, results.size(), truncated);
    }

    private void collect(Path root, Set<Path> out) {
        try {
            int[] scanned = {0};
            Files.walkFileTree(
                    root,
                    java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class),
                    MAX_DEPTH,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                            if (!dir.equals(root)
                                    && dir.getFileName().toString().startsWith(".")) {
                                return FileVisitResult.SKIP_SUBTREE; // .git, .idea, etc.
                            }
                            return scanned[0] > MAX_FILES_SCANNED
                                    ? FileVisitResult.TERMINATE
                                    : FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes a) {
                            if (++scanned[0] > MAX_FILES_SCANNED) {
                                return FileVisitResult.TERMINATE;
                            }
                            String name = file.getFileName().toString();
                            if (!name.startsWith(".") && a.isRegularFile() && a.size() <= MAX_FILE_BYTES) {
                                out.add(file);
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException e) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException ignored) {
            // best-effort walk
        }
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

    /** Stops the background search thread (called when the owning window closes). */
    public void shutdown() {
        exec.shutdownNow();
    }
}
