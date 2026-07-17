package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Resolves the workspace root for a language server. Precedence (per the design): the active Editora
 * project folder if set; otherwise the nearest ancestor of the file that contains a build-file marker
 * ({@code pom.xml}/{@code build.gradle}/…); otherwise the file's own directory. One root → one server
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
        Path abs = filePath == null ? null : filePath.toAbsolutePath().normalize();
        // Use the active project root only when the file actually lives under it — otherwise a file
        // opened outside the active project (or with the wrong project active) would be misrooted.
        if (projectRoot != null) {
            Path pr = projectRoot.toAbsolutePath().normalize();
            if (abs == null || abs.startsWith(pr)) {
                return pr;
            }
        }
        Path markerRoot = findMarkerRoot(abs, markers);
        if (markerRoot != null) {
            return markerRoot;
        }
        if (abs == null) {
            return null;
        }
        Path parent = abs.getParent();
        return parent != null ? parent : abs;
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
