package com.editora.diff;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import com.editora.search.GitignoreFilter;

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
        GitignoreFilter leftIgnore = GitignoreFilter.load(leftRoot);
        GitignoreFilter rightIgnore = GitignoreFilter.load(rightRoot);
        TreeMap<String, FilePair> files = new TreeMap<>();
        Scan left = scan(leftRoot, limit, leftIgnore, rightIgnore, true, files);
        Scan right = scan(rightRoot, limit, leftIgnore, rightIgnore, false, files);
        boolean truncated = left.truncated() || right.truncated() || files.size() > limit;

        List<Entry> differences = new ArrayList<>();
        int identical = 0;
        int visited = 0;
        for (var item : files.entrySet()) {
            if (visited++ >= limit) {
                break;
            }
            String relative = item.getKey();
            FileInfo leftFile = item.getValue().left;
            FileInfo rightFile = item.getValue().right;
            if (leftFile == null) {
                differences.add(new Entry(relative, Kind.RIGHT_ONLY, -1, rightFile.size()));
            } else if (rightFile == null) {
                differences.add(new Entry(relative, Kind.LEFT_ONLY, leftFile.size(), -1));
            } else {
                long leftSize = leftFile.size();
                long rightSize = rightFile.size();
                try {
                    if (leftSize == rightSize && Files.mismatch(leftFile.path(), rightFile.path()) == -1) {
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

    private static Scan scan(
            Path root,
            int limit,
            GitignoreFilter leftIgnore,
            GitignoreFilter rightIgnore,
            boolean left,
            TreeMap<String, FilePair> files)
            throws IOException {
        int[] found = {0};
        boolean[] truncated = {false};
        boolean[] incomplete = {false};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                if (!directory.equals(root)
                        && ".git".equals(directory.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                String relative = normalizedRelative(root, directory);
                return ignored(leftIgnore, rightIgnore, relative, true)
                        ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                // walkFileTree does not follow symbolic links by default; keep that boundary explicit.
                if (!attrs.isRegularFile() || Files.isSymbolicLink(file)) {
                    return FileVisitResult.CONTINUE;
                }
                String relative = normalizedRelative(root, file);
                if (ignored(leftIgnore, rightIgnore, relative, false)) {
                    return FileVisitResult.CONTINUE;
                }
                if (found[0]++ >= limit) {
                    truncated[0] = true;
                    return FileVisitResult.TERMINATE;
                }
                FilePair pair = files.computeIfAbsent(relative, relativePath -> new FilePair());
                FileInfo info = new FileInfo(file, attrs.size());
                if (left) {
                    pair.left = info;
                } else {
                    pair.right = info;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException error) {
                incomplete[0] = true;
                return FileVisitResult.CONTINUE;
            }
        });
        return new Scan(truncated[0], incomplete[0]);
    }

    private static boolean ignored(
            GitignoreFilter leftIgnore, GitignoreFilter rightIgnore, String relative, boolean directory) {
        return leftIgnore.ignored(relative, directory) || rightIgnore.ignored(relative, directory);
    }

    private static String normalizedRelative(Path root, Path file) {
        return root.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
    }

    private record FileInfo(Path path, long size) {}

    private static final class FilePair {
        private FileInfo left;
        private FileInfo right;
    }

    private record Scan(boolean truncated, boolean incomplete) {}
}
