package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Resolves the workspace root for a language server. Precedence (per the design): the active Editora
 * project folder if set; otherwise the nearest ancestor of the file that contains a build-file marker
 * ({@code pom.xml}/{@code build.gradle}/…) — except the home directory or a filesystem root, which are
 * never inferred as a workspace; otherwise the file's own directory. One root → one server
 * process shared by every file beneath it.
 *
 * <p>{@link #findMarkerRoot} is pure over the filesystem (only {@link Files#isRegularFile}/
 * {@link Files#isDirectory} reads), so it is unit-testable with temp directories.
 */
public final class RootResolver {

    private RootResolver() {}

    /**
     * The workspace root for {@code filePath}. Uses {@code projectRoot} when non-null; else the nearest
     * marker-bearing ancestor; else the file's parent directory (or the file itself if it has no parent).
     */
    public static @Nullable Path resolve(@Nullable Path projectRoot, Path filePath, List<String> markers) {
        String home = System.getProperty("user.home");
        return resolve(projectRoot, filePath, markers, home == null || home.isBlank() ? null : Path.of(home));
    }

    /** {@link #resolve(Path, Path, List)} with the user's home directory supplied (tests). */
    static @Nullable Path resolve(
            @Nullable Path projectRoot, Path filePath, List<String> markers, @Nullable Path home) {
        Path abs = filePath == null ? null : filePath.toAbsolutePath().normalize();
        // Use the active project root only when the file actually lives under it — otherwise a file
        // opened outside the active project (or with the wrong project active) would be misrooted.
        if (projectRoot != null) {
            Path pr = projectRoot.toAbsolutePath().normalize();
            if (abs == null || abs.startsWith(pr) || sameTree(pr, abs)) {
                return pr;
            }
        }
        Path markerRoot = findMarkerRoot(abs, markers);
        if (markerRoot != null && !tooBroad(markerRoot, home)) {
            return markerRoot;
        }
        if (abs == null) {
            return null;
        }
        Path parent = abs.getParent();
        return parent != null ? parent : abs;
    }

    /**
     * As {@link #resolve(Path, Path, List)} with markers in tiers: the nearest ancestor holding a marker of the
     * first tier wins, else of the second, and so on; then the file's folder.
     */
    public static @Nullable Path resolveTiered(@Nullable Path projectRoot, Path filePath, List<List<String>> tiers) {
        String home = System.getProperty("user.home");
        return resolveTiered(projectRoot, filePath, tiers, home == null || home.isBlank() ? null : Path.of(home));
    }

    /** {@link #resolveTiered(Path, Path, List)} with the user's home directory supplied (tests). */
    static @Nullable Path resolveTiered(
            @Nullable Path projectRoot, Path filePath, List<List<String>> tiers, @Nullable Path home) {
        if (tiers == null || tiers.size() <= 1) {
            return resolve(projectRoot, filePath, tiers == null || tiers.isEmpty() ? List.of() : tiers.get(0), home);
        }
        Path abs = filePath == null ? null : filePath.toAbsolutePath().normalize();
        for (List<String> tier : tiers) {
            Path found = findMarkerRoot(abs, tier);
            if (found != null && !tooBroad(found, home)) {
                // The project root, when there is one and it holds the file, still wins over any marker.
                return resolve(projectRoot, filePath, tier, home);
            }
        }
        return resolve(projectRoot, filePath, List.of(), home);
    }

    /**
     * The outermost directory of the contiguous chain of {@code marker}-bearing folders that {@code root}
     * belongs to: while the parent folder also holds {@code marker} (and is neither too broad nor outside
     * {@code projectRoot}, when given and {@code root} lies in it), step up. A Maven reactor's module pom thus resolves to the reactor
     * root — the same "contiguous chain" {@code MavenReactor} uses — and one server serves every module
     * (Maven review C11). {@code root} itself is returned when it has no marker or no marker-bearing parent.
     */
    public static Path outermostContiguous(Path root, String marker, @Nullable Path projectRoot) {
        String home = System.getProperty("user.home");
        return outermostContiguous(root, marker, projectRoot, home == null || home.isBlank() ? null : Path.of(home));
    }

    /**
     * The root jdtls should import for a module at {@code root} (Java review C10): the outermost of a contiguous
     * chain of {@code pom.xml} folders (a Maven reactor), else the nearest folder at most four levels up holding a
     * {@code settings.gradle(.kts)} (a Gradle build), else {@code root}. Never above {@code projectRoot} nor a
     * folder too broad to import. Without it each module of a reactor opened outside a project started a jdtls of
     * its own (1.4 GB each) that could not see its sibling modules.
     */
    public static Path javaBuildRoot(Path root, @Nullable Path projectRoot) {
        String home = System.getProperty("user.home");
        return javaBuildRoot(root, projectRoot, home == null || home.isBlank() ? null : Path.of(home));
    }

    static Path javaBuildRoot(Path root, @Nullable Path projectRoot, @Nullable Path home) {
        if (root == null) {
            return root;
        }
        Path reactor = outermostContiguous(root, "pom.xml", projectRoot, home);
        if (!reactor.equals(root.toAbsolutePath().normalize()) || Files.isRegularFile(root.resolve("pom.xml"))) {
            return reactor;
        }
        Path pr = projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
        Path current = root.toAbsolutePath().normalize();
        for (int hops = 0; hops <= 4 && current != null; hops++) {
            if (pr != null && !current.startsWith(pr) || hops > 0 && tooBroad(current, home)) {
                break;
            }
            if (Files.isRegularFile(current.resolve("settings.gradle"))
                    || Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
                return current;
            }
            current = current.getParent();
        }
        return root;
    }

    /** {@link #outermostContiguous(Path, String, Path)} with the home directory supplied (tests). */
    static Path outermostContiguous(Path root, String marker, @Nullable Path projectRoot, @Nullable Path home) {
        if (root == null || marker == null || !Files.isRegularFile(root.resolve(marker))) {
            return root;
        }
        Path current = root.toAbsolutePath().normalize();
        // The project bounds the walk only for a root inside it: a reactor beside the project (a second checkout,
        // a library) is walked up like any loose one, or each of its modules got a server (review 2, A4).
        Path pr = projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
        if (pr != null && !current.startsWith(pr)) {
            pr = null;
        }
        for (int hops = 0; hops < 32; hops++) {
            Path parent = current.getParent();
            if (parent == null
                    || !Files.isRegularFile(parent.resolve(marker))
                    || tooBroad(parent, home)
                    || pr != null && !parent.startsWith(pr)) {
                return current;
            }
            current = parent;
        }
        return current;
    }

    /**
     * Whether {@code file} lies under {@code projectRoot} once symlinks are resolved. A project opened as
     * {@code ~/work/proj} (with {@code work} a symlink) and a location that arrives under its real path are
     * the same project: answering with the project root, in the spelling it was opened with, keeps them on
     * one server instead of starting a second one that indexes the same tree.
     */
    private static boolean sameTree(Path projectRoot, Path file) {
        Path realRoot = com.editora.config.PathKeys.canonical(projectRoot);
        Path realFile = com.editora.config.PathKeys.canonical(file);
        if (realFile.equals(file) && file.getParent() != null) {
            // The file may not exist yet (or may itself be the only unresolvable part): resolve its directory.
            realFile = com.editora.config.PathKeys.canonical(file.getParent()).resolve(file.getFileName());
        }
        return realFile.startsWith(realRoot);
    }

    /**
     * Whether {@code filePath} has no root of its own: the project (when given) does not hold it and no
     * ancestor short of the home folder or a file-system root holds any of {@code markers}. Such a file is
     * "loose" — the JavaScript/TypeScript server serves all of them from one shared session (Node review B7).
     */
    public static boolean isLoose(@Nullable Path projectRoot, Path filePath, List<String> markers) {
        String home = System.getProperty("user.home");
        return isLoose(projectRoot, filePath, markers, home == null || home.isBlank() ? null : Path.of(home));
    }

    /** {@link #isLoose(Path, Path, List)} with the user's home directory supplied (tests). */
    static boolean isLoose(@Nullable Path projectRoot, Path filePath, List<String> markers, @Nullable Path home) {
        if (filePath == null) {
            return false;
        }
        Path abs = filePath.toAbsolutePath().normalize();
        if (projectRoot != null) {
            Path pr = projectRoot.toAbsolutePath().normalize();
            if (abs.startsWith(pr) || sameTree(pr, abs)) {
                return false;
            }
        }
        Path markerRoot = findMarkerRoot(abs, markers);
        return markerRoot == null || tooBroad(markerRoot, home);
    }

    /**
     * Whether {@code root} is a folder no server should walk for one file: a filesystem root, the user's home,
     * or the temporary-files folder. A loose script directly in {@code ~} (a {@code ~/.bashrc}), in
     * {@code /tmp} or at {@code /} gets that folder as its workspace — the fallback when no marker is found —
     * and bash-language-server then globbed the whole tree, following symbolic links, for scripts to index.
     * Remote roots: only a filesystem root counts (this machine's home and temp folder mean nothing there).
     */
    public static boolean isBroadRoot(Path root) {
        if (root == null) {
            return false;
        }
        Path abs = root.toAbsolutePath().normalize();
        if (abs.getParent() == null) {
            return true;
        }
        if (com.editora.vfs.Vfs.isRemote(abs)) {
            return false;
        }
        String home = System.getProperty("user.home");
        String tmp = System.getProperty("java.io.tmpdir");
        return sameFolder(abs, home) || sameFolder(abs, tmp) || sameFolder(abs, "/tmp");
    }

    private static boolean sameFolder(Path abs, String other) {
        if (other == null || other.isBlank()) {
            return false;
        }
        Path o = Path.of(other).toAbsolutePath().normalize();
        return abs.equals(o) || abs.equals(com.editora.config.PathKeys.canonical(o));
    }

    /**
     * A marker found in the user's home directory or at a filesystem root does not make that directory a
     * workspace: a dotfiles {@code ~/.git} or a stray {@code ~/package.json} would hand a language server
     * the whole home directory to enumerate and index for one loose file in {@code ~/Downloads}.
     */
    static boolean tooBroad(Path root, @Nullable Path home) {
        if (root.getParent() == null) {
            return true;
        }
        if (home == null) {
            return false;
        }
        Path h = home.toAbsolutePath().normalize();
        return root.equals(h) || root.equals(com.editora.config.PathKeys.canonical(h));
    }

    /**
     * Walks up from {@code filePath}'s directory looking for the first ancestor that directly contains
     * any of {@code markers} (as a regular file <em>or</em> a directory); returns that ancestor, or
     * {@code null} if none is found up to the root. Pure.
     */
    public static @Nullable Path findMarkerRoot(@Nullable Path filePath, List<String> markers) {
        return findMarkerRoot(filePath, markers, false);
    }

    /**
     * As {@link #findMarkerRoot(Path, List)}, but when {@code filesOnly} is true a marker matches only a
     * regular <b>file</b>. Build markers are all files ({@code pom.xml}, {@code package.json},
     * {@code go.mod}, …), so a <em>directory</em> merely named like one — a folder called {@code pom.xml}
     * anywhere up the tree — must not root that build tool there (it then fails to parse, showing a "can't
     * read the build file" error for a project that has none). LSP markers include real directories
     * ({@code .git}/{@code .terraform}), so those callers pass {@code filesOnly=false}. Pure.
     */
    public static @Nullable Path findMarkerRoot(@Nullable Path filePath, List<String> markers, boolean filesOnly) {
        return findMarkerRoot(filePath, markers, filesOnly, null);
    }

    /**
     * As {@link #findMarkerRoot(Path, List, boolean)}, but the walk stops at {@code boundary} (inclusive) when
     * {@code filePath} is inside it — the open project's root, so a project opened inside a module never
     * roots a build at a pom <em>above</em> the folder the user opened (the wrapper lookup already stops
     * there; detection did not, and built a folder the UI never showed). {@code null}, or a path outside the
     * boundary, means unbounded as before. Pure.
     */
    public static @Nullable Path findMarkerRoot(
            @Nullable Path filePath, List<String> markers, boolean filesOnly, @Nullable Path boundary) {
        if (filePath == null || markers == null || markers.isEmpty()) {
            return null;
        }
        Path dir = Files.isDirectory(filePath) ? filePath : filePath.getParent();
        Path top = boundary == null ? null : boundary.toAbsolutePath().normalize();
        if (top != null && (dir == null || !dir.toAbsolutePath().normalize().startsWith(top))) {
            top = null;
        }
        while (dir != null) {
            for (String marker : markers) {
                Path candidate = dir.resolve(marker);
                if (Files.isRegularFile(candidate) || (!filesOnly && Files.isDirectory(candidate))) {
                    return dir;
                }
            }
            if (top != null && dir.toAbsolutePath().normalize().equals(top)) {
                return null;
            }
            dir = dir.getParent();
        }
        return null;
    }

    /** Folders whose name says they hold no build of the user's: dependencies, build output, VCS metadata. */
    private static final java.util.Set<String> SKIPPED_FOLDERS = java.util.Set.of(
            "node_modules", "target", "build", "out", "dist", ".git", ".idea", ".editora", ".gradle", ".mvn");

    /**
     * The directories at most {@code depth} levels below {@code root} (the root itself included) that hold
     * one of {@code markers} as a regular file, in breadth-first order, visiting at most {@code maxDirs}
     * folders. The "Projects" list of a build tool's tasks window: a workspace whose poms sit one folder down
     * ({@code backend/}, a monorepo) otherwise looks as if it had no build tool until a file inside a module
     * is opened. Hidden folders and the usual dependency/output folders are skipped. Pure, bounded, no
     * symlink following.
     */
    public static List<Path> findMarkerRootsBelow(@Nullable Path root, List<String> markers, int depth, int maxDirs) {
        List<Path> found = new java.util.ArrayList<>();
        if (root == null || markers == null || markers.isEmpty() || !Files.isDirectory(root)) {
            return found;
        }
        java.util.ArrayDeque<Path> queue = new java.util.ArrayDeque<>();
        java.util.ArrayDeque<Integer> depths = new java.util.ArrayDeque<>();
        queue.add(root);
        depths.add(0);
        int visited = 0;
        while (!queue.isEmpty() && visited < maxDirs) {
            Path dir = queue.poll();
            int level = depths.poll();
            visited++;
            for (String marker : markers) {
                if (Files.isRegularFile(dir.resolve(marker))) {
                    found.add(dir);
                    break;
                }
            }
            if (level >= depth) {
                continue;
            }
            try (java.util.stream.Stream<Path> children = Files.list(dir)) {
                for (Path child : (Iterable<Path>) children.sorted()::iterator) {
                    String name = child.getFileName() == null
                            ? ""
                            : child.getFileName().toString();
                    if (name.startsWith(".") || SKIPPED_FOLDERS.contains(name)) {
                        continue;
                    }
                    if (Files.isDirectory(child, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        queue.add(child);
                        depths.add(level + 1);
                    }
                }
            } catch (java.io.IOException | RuntimeException e) {
                // unreadable folder: nothing below it is listed
            }
        }
        return found;
    }
}
