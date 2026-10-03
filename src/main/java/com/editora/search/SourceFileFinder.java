package com.editora.search;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Finds a source file in a project from the part of its path that is known: {@code com/foo/Bar.java} (from
 * a stack frame's qualified class or a test's class name), or a bare {@code Bar.java}.
 *
 * <p>The standard source roots are tried first — a handful of {@code stat} calls, which settle the
 * single-module case without listing anything. Only then is the tree walked, through {@link ProjectWalk}, so
 * build output, dependency trees, dot-directories and {@code .gitignore}d paths are never entered; the walk
 * stops at the first match. Blocking: call it off the JavaFX thread.
 */
public final class SourceFileFinder {

    /** Deep enough for {@code module/sub/src/test/java/a/b/c/d/e/F.java}; a bound, not a tuning knob. */
    private static final int MAX_DEPTH = 25;

    private static final List<String> SOURCE_ROOTS =
            List.of("src/main/java", "src/test/java", "src/main/kotlin", "src/test/kotlin", "src", "");

    private SourceFileFinder() {}

    /**
     * The file under {@code root} whose root-relative path is {@code relPath} or ends with
     * {@code /relPath} ('/'-separated, so it means the same on Windows), or {@code null}.
     */
    public static Path find(Path root, String relPath, BooleanSupplier cancelled) {
        if (root == null || relPath == null || relPath.isBlank() || !Files.isDirectory(root)) {
            return null;
        }
        for (String sourceRoot : SOURCE_ROOTS) {
            Path direct = root.resolve(sourceRoot).resolve(relPath);
            if (Files.isRegularFile(direct)) {
                return direct;
            }
        }
        String suffix = "/" + relPath;
        Path[] found = {null};
        ProjectWalk.walk(
                root,
                new ProjectWalk.Options(MAX_DEPTH, Integer.MAX_VALUE, GitignoreFilter.load(root), cancelled),
                new ProjectWalk.Visitor() {
                    @Override
                    public boolean enter(Path dir, String rel) {
                        return !ProjectWalk.isBuildOutputDir(rel);
                    }

                    @Override
                    public ProjectWalk.Verdict file(
                            Path file, String rel, java.nio.file.attribute.BasicFileAttributes attrs) {
                        if (attrs.isRegularFile() && (rel.equals(relPath) || rel.endsWith(suffix))) {
                            found[0] = file;
                            return ProjectWalk.Verdict.STOP;
                        }
                        return ProjectWalk.Verdict.SKIP;
                    }
                });
        return found[0];
    }
}
