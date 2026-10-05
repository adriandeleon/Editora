package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
    public static Path resolve(Path projectRoot, Path filePath, List<String> markers) {
        String home = System.getProperty("user.home");
        return resolve(projectRoot, filePath, markers, home == null || home.isBlank() ? null : Path.of(home));
    }

    /** {@link #resolve(Path, Path, List)} with the user's home directory supplied (tests). */
    static Path resolve(Path projectRoot, Path filePath, List<String> markers, Path home) {
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
     * A marker found in the user's home directory or at a filesystem root does not make that directory a
     * workspace: a dotfiles {@code ~/.git} or a stray {@code ~/package.json} would hand a language server
     * the whole home directory to enumerate and index for one loose file in {@code ~/Downloads}.
     */
    static boolean tooBroad(Path root, Path home) {
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
    public static Path findMarkerRoot(Path filePath, List<String> markers) {
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
    public static Path findMarkerRoot(Path filePath, List<String> markers, boolean filesOnly) {
        if (filePath == null || markers == null || markers.isEmpty()) {
            return null;
        }
        Path dir = Files.isDirectory(filePath) ? filePath : filePath.getParent();
        while (dir != null) {
            for (String marker : markers) {
                Path candidate = dir.resolve(marker);
                if (Files.isRegularFile(candidate) || (!filesOnly && Files.isDirectory(candidate))) {
                    return dir;
                }
            }
            dir = dir.getParent();
        }
        return null;
    }
}
