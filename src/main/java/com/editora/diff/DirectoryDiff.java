package com.editora.diff;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Toolkit-free recursive directory comparison used by the multi-file diff review. */
public final class DirectoryDiff {

    public static final int DEFAULT_MAX_FILES = 20_000;

    public enum Kind {
        MODIFIED,
        LEFT_ONLY,
        RIGHT_ONLY,
        UNREADABLE
    }

    public record Entry(String relativePath, Kind kind, long leftSize, long rightSize) {}

    public record Result(List<Entry> entries, int identicalFiles, boolean truncated, boolean incomplete) {
        public Result {
            entries = List.copyOf(entries);
        }
    }

    private DirectoryDiff() {}

    /**
     * Compares regular files below two roots without following symbolic links. Identical files are counted
     * but omitted from {@link Result#entries()}, leaving a review list containing only actionable differences.
     */
    public static Result compare(Path leftRoot, Path rightRoot) throws IOException {
        return compare(leftRoot, rightRoot, DEFAULT_MAX_FILES);
    }

    static Result compare(Path leftRoot, Path rightRoot, int maxFiles) throws IOException {
        if (leftRoot == null || rightRoot == null || !Files.isDirectory(leftRoot) || !Files.isDirectory(rightRoot)) {
            throw new IOException("Both comparison roots must be directories");
        }
        int limit = Math.max(1, maxFiles);
        Scan left = scan(leftRoot, limit);
        Scan right = scan(rightRoot, limit);
        TreeSet<String> paths = new TreeSet<>();
        paths.addAll(left.files().keySet());
        paths.addAll(right.files().keySet());
        boolean truncated = left.truncated() || right.truncated() || paths.size() > limit;

        List<Entry> differences = new ArrayList<>();
        int identical = 0;
        int visited = 0;
        for (String relative : paths) {
            if (visited++ >= limit) {
                break;
            }
            Path leftFile = left.files().get(relative);
            Path rightFile = right.files().get(relative);
            if (leftFile == null) {
                differences.add(new Entry(relative, Kind.RIGHT_ONLY, -1, size(rightFile)));
            } else if (rightFile == null) {
                differences.add(new Entry(relative, Kind.LEFT_ONLY, size(leftFile), -1));
            } else {
                long leftSize = size(leftFile);
                long rightSize = size(rightFile);
                try {
                    if (leftSize == rightSize && Files.mismatch(leftFile, rightFile) == -1) {
                        identical++;
                    } else {
                        differences.add(new Entry(relative, Kind.MODIFIED, leftSize, rightSize));
                    }
                } catch (IOException e) {
                    differences.add(new Entry(relative, Kind.UNREADABLE, leftSize, rightSize));
                }
            }
        }
        return new Result(differences, identical, truncated, left.incomplete() || right.incomplete());
    }

    private static Scan scan(Path root, int limit) throws IOException {
        Map<String, Path> files = new HashMap<>();
        boolean[] truncated = {false};
        boolean[] incomplete = {false};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                // walkFileTree does not follow symbolic links by default; keep that boundary explicit.
                if (!attrs.isRegularFile() || Files.isSymbolicLink(file)) {
                    return FileVisitResult.CONTINUE;
                }
                if (files.size() >= limit) {
                    truncated[0] = true;
                    return FileVisitResult.TERMINATE;
                }
                files.put(normalizedRelative(root, file), file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException error) {
                incomplete[0] = true;
                return FileVisitResult.CONTINUE;
            }
        });
        return new Scan(files, truncated[0], incomplete[0]);
    }

    private static String normalizedRelative(Path root, Path file) {
        return root.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
    }

    private static long size(Path file) {
        if (file == null) {
            return -1;
        }
        try {
            return Files.size(file);
        } catch (IOException e) {
            return -1;
        }
    }

    private record Scan(Map<String, Path> files, boolean truncated, boolean incomplete) {}
}
