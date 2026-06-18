package com.editora.search;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import javafx.application.Platform;

import com.editora.process.ProcessRunner;
import com.editora.vfs.Vfs;

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

    /** ripgrep timeout — generous so a big tree finishes, but bounded so a hung process can't pin the thread. */
    private static final Duration RG_TIMEOUT = Duration.ofSeconds(60);

    /** When true, on-disk search uses ripgrep (set from {@code MainController.applyRipgrepSupport}). */
    private volatile boolean useRipgrep = false;

    private volatile List<String> rgCommand = List.of(Ripgrep.DEFAULT_COMMAND);

    /**
     * Selects the on-disk search backend: ripgrep when {@code useRipgrep} (and the root is local), else the
     * built-in Java walker. The open-buffer overlay + caps are applied identically either way.
     */
    public void setBackend(boolean useRipgrep, List<String> rgCommand) {
        this.useRipgrep = useRipgrep;
        this.rgCommand =
                (rgCommand == null || rgCommand.isEmpty()) ? List.of(Ripgrep.DEFAULT_COMMAND) : List.copyOf(rgCommand);
    }

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
        boolean haveRoot = scopeRoot != null && Files.isDirectory(scopeRoot);
        // On-disk results: ripgrep when enabled + local root, else the Java walker. rg falls back to the
        // walker on a pattern/IO error (exit 2) or if it failed to launch (null), so nothing regresses.
        List<FileResult> disk = null;
        if (haveRoot && useRipgrep && Vfs.isLocal(scopeRoot)) {
            disk = ripgrepDisk(query, scopeRoot);
        }
        if (disk == null) {
            disk = haveRoot ? walkDisk(query, scopeRoot) : new ArrayList<>();
        }
        return overlay(disk, query, open);
    }

    /** On-disk search via ripgrep, paths resolved to absolute. Returns null to signal "fall back to the walker". */
    private List<FileResult> ripgrepDisk(SearchQuery query, Path root) {
        List<String> cmd = new ArrayList<>(rgCommand);
        cmd.addAll(RipgrepArgs.build(query, true, MAX_FILE_BYTES)); // respect .gitignore (rg default)
        cmd.add(".");
        ProcessRunner.Result r;
        try {
            r = ProcessRunner.run(root, RG_TIMEOUT, cmd);
        } catch (RuntimeException e) {
            return null;
        }
        // rg: 0 = matches, 1 = no matches (both valid), 2 = pattern/IO error, -1 = failed to launch.
        if (r.exit() != 0 && r.exit() != 1) {
            return null;
        }
        List<FileResult> out = new ArrayList<>();
        for (FileResult fr : RipgrepOutput.parse(r.out())) {
            out.add(new FileResult(root.resolve(fr.file().toString()).normalize(), fr.matches()));
        }
        return out;
    }

    /** On-disk search via the built-in walker (dot-dir/oversize/binary skipping). */
    private List<FileResult> walkDisk(SearchQuery query, Path root) {
        Set<Path> candidates = new LinkedHashSet<>();
        collect(root, candidates);
        List<FileResult> out = new ArrayList<>();
        for (Path file : candidates) {
            String content = readText(file);
            if (content == null || content.indexOf('\0') >= 0) {
                continue; // unreadable or binary
            }
            List<LineMatch> ms = MultiFileSearch.matchesInText(content, query);
            if (!ms.isEmpty()) {
                out.add(new FileResult(file, ms));
            }
        }
        return out;
    }

    /**
     * Overlay open buffers on the on-disk results (their in-memory text wins for any open path, and open
     * files outside the root are included), then sort by path and apply the match cap.
     */
    private Outcome overlay(List<FileResult> disk, SearchQuery query, Map<Path, String> open) {
        Set<Path> openKeys = new HashSet<>();
        for (Path p : open.keySet()) {
            openKeys.add(p.toAbsolutePath().normalize());
        }
        List<FileResult> all = new ArrayList<>();
        for (FileResult fr : disk) {
            if (!openKeys.contains(fr.file().toAbsolutePath().normalize())) {
                all.add(fr); // a disk hit not shadowed by an open buffer
            }
        }
        for (Map.Entry<Path, String> e : open.entrySet()) {
            String content = e.getValue();
            if (content == null || content.indexOf('\0') >= 0) {
                continue;
            }
            List<LineMatch> ms = MultiFileSearch.matchesInText(content, query);
            if (!ms.isEmpty()) {
                all.add(new FileResult(e.getKey(), ms));
            }
        }
        all.sort((a, b) -> a.file().toString().compareToIgnoreCase(b.file().toString()));

        List<FileResult> results = new ArrayList<>();
        int total = 0;
        boolean truncated = false;
        for (FileResult fr : all) {
            List<LineMatch> ms = fr.matches();
            if (total + ms.size() > MAX_MATCHES) {
                ms = ms.subList(0, Math.max(0, MAX_MATCHES - total));
                truncated = true;
            }
            if (!ms.isEmpty()) {
                results.add(new FileResult(fr.file(), ms));
                total += ms.size();
            }
            if (truncated) {
                break;
            }
        }
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
