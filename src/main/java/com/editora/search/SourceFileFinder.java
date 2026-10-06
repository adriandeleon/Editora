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
 *
 * <p>A class need not live in the directory its package names — {@code javac} and Surefire accept
 * {@code package com.acme.flat;} in {@code src/test/java/FlatTest.java}. When no path matches, a file of the
 * right <em>name</em> that declares the right package is the answer; the name alone is not, since two
 * packages may each have a {@code FooTest.java}.
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
        int slash = relPath.lastIndexOf('/');
        String name = relPath.substring(slash + 1);
        String packageName = slash < 0 ? null : relPath.substring(0, slash).replace('/', '.');
        Path[] found = {null};
        Path[] byPackage = {null};
        ProjectWalk.walk(
                root,
                new ProjectWalk.Options(MAX_DEPTH, Integer.MAX_VALUE, GitignoreFilter.load(root), cancelled),
                new ProjectWalk.Visitor() {
                    @Override
                    public boolean enter(Path dir, String rel) {
                        // A directory called out/, build/ or target/ that has its own src/ is a module that
                        // happens to carry the name, not build output.
                        return !ProjectWalk.isBuildOutputDir(rel) || Files.isDirectory(dir.resolve("src"));
                    }

                    @Override
                    public ProjectWalk.Verdict file(
                            Path file, String rel, java.nio.file.attribute.BasicFileAttributes attrs) {
                        if (!attrs.isRegularFile()) {
                            return ProjectWalk.Verdict.SKIP;
                        }
                        if (rel.equals(relPath) || rel.endsWith(suffix)) {
                            found[0] = file;
                            return ProjectWalk.Verdict.STOP;
                        }
                        if (byPackage[0] == null
                                && packageName != null
                                && attrs.size() <= MAX_DECLARATION_FILE_BYTES
                                && file.getFileName().toString().equals(name)
                                && declaresPackage(file, packageName)) {
                            byPackage[0] = file; // kept in reserve: an exact path further on still wins
                        }
                        return ProjectWalk.Verdict.SKIP;
                    }
                });
        return found[0] != null ? found[0] : byPackage[0];
    }

    private static final long MAX_DECLARATION_FILE_BYTES = 2L * 1024 * 1024;

    /** Whether {@code file} opens with {@code package <packageName>} (Java, Kotlin, Groovy, Scala spelling). */
    static boolean declaresPackage(Path file, String packageName) {
        try {
            String text = Files.readString(file);
            java.util.regex.Matcher m = PACKAGE_DECLARATION.matcher(text);
            return m.find() && m.group(1).equals(packageName);
        } catch (java.io.IOException | RuntimeException unreadable) {
            return false;
        }
    }

    private static final java.util.regex.Pattern PACKAGE_DECLARATION =
            java.util.regex.Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)");
}
