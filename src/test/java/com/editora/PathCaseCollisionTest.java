package com.editora;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No two paths in the project may differ only by letter case. Windows and (by default) macOS file systems are case
 * insensitive, so a checkout there keeps one of the pair and shows the other as a modification of it: the lost file
 * silently drops out of the build. {@code EditOpsCommandsFxTest} and {@code EditopsCommandsFxTest} did exactly that.
 * Only a case-sensitive file system can hold both, so this guard bites on the Linux lanes.
 */
@Tag("guard")
class PathCaseCollisionTest {
    /** Build output, VCS data and installed dependencies: not part of the checkout. */
    private static final Set<String> SKIPPED_DIRS = Set.of(".git", "target", "node_modules");

    @Test
    void noTwoPathsDifferOnlyByCase() throws IOException {
        Path root = Path.of("");
        assertTrue(Files.isRegularFile(root.resolve("pom.xml")), "Run from the Maven project root");
        var byFolded = new TreeMap<String, String>();
        var collisions = new ArrayList<String>();
        Files.walkFileTree(root.toAbsolutePath(), new SimpleFileVisitor<>() {
            private final Path base = root.toAbsolutePath();

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(base) && SKIPPED_DIRS.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                record(dir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                record(file);
                return FileVisitResult.CONTINUE;
            }

            private void record(Path path) {
                String relative = base.relativize(path).toString().replace('\\', '/');
                if (relative.isEmpty()) {
                    return;
                }
                String previous = byFolded.putIfAbsent(relative.toLowerCase(Locale.ROOT), relative);
                if (previous != null && !previous.equals(relative)) {
                    collisions.add(previous + "  <->  " + relative);
                }
            }
        });
        assertTrue(
                collisions.isEmpty(),
                () -> "Paths that differ only by case break Windows and macOS checkouts; rename one of each pair:\n"
                        + String.join("\n", collisions));
    }
}
