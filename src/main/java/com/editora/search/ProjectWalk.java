package com.editora.search;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import java.util.function.BooleanSupplier;

/**
 * The one pruned project walk every "look at the files of this project" feature goes through: Find in Files,
 * the TODO scan, the symbol index, the test-source lookups.
 *
 * <p>Pruning happens <b>on the directory</b>, before anything beneath it is read. Filtering files after an
 * unpruned walk gets {@code .gitignore} wrong in two ways at once: a directory-only rule ({@code target/})
 * never applies to a file, and a slash-less rule ({@code node_modules}) only ever matches a base name — so
 * nothing <em>under</em> an ignored directory is excluded, and the walk still pays to enumerate all of it.
 *
 * <p>What it guarantees:
 *
 * <ul>
 *   <li>a dot-directory ({@code .git}, {@code .idea}) and a {@code .gitignore}d directory are skipped whole;
 *   <li>dot-files and {@code .gitignore}d files are never offered;
 *   <li>an unreadable file or directory is counted and stepped over, never fatal;
 *   <li>the cap counts <b>accepted</b> files only, so a big ignored tree cannot exhaust it;
 *   <li>hitting the cap or the depth limit is reported as {@link Outcome#truncated()}, never silent.
 * </ul>
 *
 * <p>Blocking: call it off the JavaFX thread.
 */
public final class ProjectWalk {

    private ProjectWalk() {}

    /** What a visitor wants done with one offered file. */
    public enum Verdict {
        /** Keep it; it counts toward the cap. */
        ACCEPT,
        /** Not interesting; costs nothing against the cap. */
        SKIP,
        /** The caller has what it came for — end the walk (this is not a truncation). */
        STOP
    }

    /** Receives each surviving entry. Paths are as the walk produced them; {@code rel} is root-relative, '/'-separated. */
    @FunctionalInterface
    public interface Visitor {
        Verdict file(Path file, String rel, BasicFileAttributes attrs);

        /** Whether to descend into {@code dir}, which already passed the dot-directory and gitignore pruning. */
        default boolean enter(Path dir, String rel) {
            return true;
        }
    }

    /**
     * How a walk ended.
     *
     * @param accepted files the visitor accepted
     * @param capped the cap was reached with at least one more candidate left unvisited
     * @param depthLimited a directory at the depth limit was left unvisited
     * @param unreadable entries that could not be read and were stepped over
     */
    public record Outcome(int accepted, boolean capped, boolean depthLimited, int unreadable) {

        /** The cap or the depth limit cut the walk short, so the caller saw a partial tree. */
        public boolean truncated() {
            return capped || depthLimited;
        }
    }

    /** The limits and filters of one walk. */
    public record Options(int maxDepth, int maxAccepted, GitignoreFilter gitignore, BooleanSupplier cancelled) {

        public Options {
            gitignore = gitignore == null ? GitignoreFilter.NONE : gitignore;
            cancelled = cancelled == null ? () -> false : cancelled;
        }

        public Options(int maxDepth, int maxAccepted, GitignoreFilter gitignore) {
            this(maxDepth, maxAccepted, gitignore, null);
        }
    }

    /** Walks {@code root}, offering every surviving regular or special file to {@code visitor}. */
    public static Outcome walk(Path root, Options options, Visitor visitor) {
        int[] accepted = {0};
        int[] unreadable = {0};
        boolean[] capped = {false};
        boolean[] depthLimited = {false};
        if (root == null || !Files.isDirectory(root)) {
            return new Outcome(0, false, false, 0);
        }
        // A root that is itself a symbolic link (~/proj -> /data/proj) is walked through its target: without
        // FOLLOW_LINKS the walker reports the link as one non-directory entry and the project looks empty.
        // Links below the root are still not followed. Every path offered is mapped back under the root as it
        // was given, so results keep equalling the paths of the open buffers.
        Path real;
        try {
            real = Files.isSymbolicLink(root) ? root.toRealPath() : root;
        } catch (IOException | RuntimeException e) {
            return new Outcome(0, false, false, 1);
        }
        Path given = root;
        java.util.function.UnaryOperator<Path> shown =
                real == given ? p -> p : p -> given.resolve(real.relativize(p).toString());
        try {
            Files.walkFileTree(
                    real, EnumSet.noneOf(FileVisitOption.class), options.maxDepth(), new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            if (options.cancelled().getAsBoolean()) {
                                return FileVisitResult.TERMINATE;
                            }
                            if (dir.equals(real)) {
                                return FileVisitResult.CONTINUE;
                            }
                            if (hidden(dir)) {
                                return FileVisitResult.SKIP_SUBTREE; // .git, .idea, …
                            }
                            String rel = relativize(real, dir);
                            if (options.gitignore().ignored(rel, true) || !visitor.enter(shown.apply(dir), rel)) {
                                return FileVisitResult.SKIP_SUBTREE; // target/, node_modules/, …
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (options.cancelled().getAsBoolean()) {
                                return FileVisitResult.TERMINATE;
                            }
                            if (attrs.isDirectory()) {
                                // walkFileTree hands a directory to visitFile only at the depth limit.
                                if (!hidden(file) && !options.gitignore().ignored(relativize(real, file), true)) {
                                    depthLimited[0] = true;
                                }
                                return FileVisitResult.CONTINUE;
                            }
                            if (hidden(file)) {
                                return FileVisitResult.CONTINUE;
                            }
                            String rel = relativize(real, file);
                            if (options.gitignore().ignored(rel, false)) {
                                return FileVisitResult.CONTINUE;
                            }
                            if (accepted[0] >= options.maxAccepted()) {
                                capped[0] = true; // one more candidate than the cap allows
                                return FileVisitResult.TERMINATE;
                            }
                            Verdict verdict = visitor.file(shown.apply(file), rel, attrs);
                            if (verdict == Verdict.STOP) {
                                return FileVisitResult.TERMINATE;
                            }
                            if (verdict == Verdict.ACCEPT) {
                                accepted[0]++;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException e) {
                            unreadable[0]++; // one locked directory must not cost the rest of the tree
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException | RuntimeException e) {
            unreadable[0]++; // best-effort: whatever was collected so far is still worth having
        }
        return new Outcome(accepted[0], capped[0], depthLimited[0], unreadable[0]);
    }

    /**
     * Whether the directory at root-relative {@code relDir} is build output or a dependency tree that a
     * search for <em>sources</em> should not enter: {@code target}, {@code build}, {@code out},
     * {@code node_modules}. For walks rooted at a module, whose own {@code .gitignore} — the usual thing
     * that prunes these — lives further up and is never read. A directory of that name <em>inside</em> a
     * source tree is a package ({@code src/test/java/com/acme/build}), not build output, and is kept.
     * Separator-agnostic: {@code relDir} is '/'-separated on every platform.
     */
    public static boolean isBuildOutputDir(String relDir) {
        if (relDir == null || relDir.isEmpty()) {
            return false;
        }
        int slash = relDir.lastIndexOf('/');
        String name = relDir.substring(slash + 1);
        if (name.equals("node_modules")) {
            return true;
        }
        if (!(name.equals("target") || name.equals("build") || name.equals("out"))) {
            return false;
        }
        return !("/" + relDir.substring(0, slash + 1)).contains("/src/");
    }

    private static boolean hidden(Path path) {
        Path name = path.getFileName();
        return name != null && name.toString().startsWith(".");
    }

    /** Root-relative, '/'-separated on every platform; the bare file name when {@code file} is not under {@code root}. */
    public static String relativize(Path root, Path file) {
        try {
            return root.relativize(file).toString().replace('\\', '/');
        } catch (IllegalArgumentException differentRoot) {
            Path name = file.getFileName();
            return name == null ? file.toString() : name.toString();
        }
    }
}
