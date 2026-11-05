package com.editora.lsp;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The Java sources of a folder written on disk in the last moments before a launch build (Java debug review 3,
 * A207). A change made outside Editora — {@code git checkout}, {@code git stash pop}, a terminal, another editor —
 * reaches jdtls through a file watcher, after it has gathered the burst ({@link RootWatcher#COALESCE_MS}) and jdtls
 * has taken it in: an F5 within about half a second built and ran the old code. The launch build tells jdtls about
 * these files itself first. Telling it twice is harmless.
 */
final class FreshJavaSources {

    private FreshJavaSources() {}

    /** How far back a file's modification counts as possibly not yet seen by jdtls. */
    static final long WINDOW_MS = 10_000;

    /** The most folder entries one scan visits: a huge folder costs a bounded walk. */
    static final int MAX_VISITED = 20_000;

    /** Folders that hold no sources a launch builds: build outputs, dependencies, tool state. */
    private static final Set<String> SKIPPED = Set.of("node_modules", "target", "build", "bin", "out", "__pycache__");

    /** Whether a folder named {@code name} is left out of the scan: hidden, or an output/dependency folder. */
    static boolean skipped(String name) {
        return name.startsWith(".") || SKIPPED.contains(name);
    }

    /**
     * The {@code .java} files under {@code root} modified after {@code sinceMillis}, as watched-file changes
     * ({@link LspManager.WatchedKind#CHANGED}: jdtls refreshes the file either way, created or changed). Visits at
     * most {@code maxVisited} entries; an unreadable folder is skipped. Never throws.
     */
    static List<LspManager.WatchedFile> since(Path root, long sinceMillis, int maxVisited) {
        List<LspManager.WatchedFile> out = new ArrayList<>();
        if (root == null || !Files.isDirectory(root)) {
            return out;
        }
        int[] visited = {0};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (++visited[0] > maxVisited) {
                        return FileVisitResult.TERMINATE;
                    }
                    Path name = dir.getFileName();
                    return !dir.equals(root) && name != null && skipped(name.toString())
                            ? FileVisitResult.SKIP_SUBTREE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (++visited[0] > maxVisited) {
                        return FileVisitResult.TERMINATE;
                    }
                    Path name = file.getFileName();
                    if (attrs.isRegularFile()
                            && name != null
                            && name.toString().endsWith(".java")
                            && attrs.lastModifiedTime().toMillis() > sinceMillis) {
                        out.add(new LspManager.WatchedFile(file, LspManager.WatchedKind.CHANGED));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException e) {
            // what was found so far is still worth telling
        }
        return out;
    }
}
