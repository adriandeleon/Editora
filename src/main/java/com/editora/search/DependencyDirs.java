package com.editora.search;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Folders that hold a project's installed dependencies rather than its own files — today only
 * {@code node_modules}. A project walk skips them while "respect .gitignore" is on, even when no
 * {@code .gitignore} names them: a Node folder made with {@code npm init} and {@code npm install} has no
 * {@code .gitignore} until {@code git init}, and its {@code node_modules} (often 50,000+ files) filled the
 * index cap and the Find in Files budget before the walk reached {@code src/} (review E1). The remote listing
 * ({@link RemoteFiles}) and the language-server watcher already skipped it. Pure.
 *
 * <p>Two ways back in: a {@code .gitignore} rule that re-includes the folder ({@code !node_modules/}) — the
 * built-in rule has the lowest precedence — and an include glob that names the folder in Find in Files.
 */
public final class DependencyDirs {

    /** The folder names skipped. */
    public static final Set<String> NAMES = Set.of("node_modules");

    private DependencyDirs() {}

    /**
     * {@link #NAMES} plus a caller's own folder names ({@code target}, {@code .venv}, {@code vendor}…), dependency
     * folders first: every list of "folders to skip" takes its dependency folders from here, so a new one (a
     * {@code bower_components}, a {@code jspm_packages}) is added once instead of in nine places (JS review E110).
     */
    public static Set<String> with(String... more) {
        java.util.LinkedHashSet<String> all =
                new java.util.LinkedHashSet<>(NAMES.stream().sorted().toList());
        all.addAll(List.of(more));
        return java.util.Collections.unmodifiableSet(all);
    }

    /** Whether the directory at root-relative {@code relDir} ('/'-separated) is a dependency folder. */
    public static boolean isDependencyDir(String relDir) {
        if (relDir == null || relDir.isEmpty()) {
            return false;
        }
        String trimmed = relDir.endsWith("/") ? relDir.substring(0, relDir.length() - 1) : relDir;
        int slash = trimmed.lastIndexOf('/');
        return NAMES.contains(trimmed.substring(slash + 1));
    }

    /** Whether root-relative {@code relPath} ('/'-separated) lies inside a dependency folder at any depth. */
    public static boolean inDependencyDir(String relPath) {
        if (relPath == null) {
            return false;
        }
        int start = 0;
        for (int slash = relPath.indexOf('/'); slash >= 0; slash = relPath.indexOf('/', start)) {
            if (NAMES.contains(relPath.substring(start, slash))) {
                return true;
            }
            start = slash + 1;
        }
        return false;
    }

    /**
     * Whether one of the include globs names a dependency folder ({@code node_modules/**},
     * {@code **}{@code /node_modules/react/**}): the user asked to look inside, so nothing is skipped.
     */
    public static boolean namedBy(List<String> includeGlobs) {
        if (includeGlobs == null) {
            return false;
        }
        for (String glob : includeGlobs) {
            if (glob == null) {
                continue;
            }
            String g = glob.toLowerCase(Locale.ROOT);
            for (String name : NAMES) {
                if (g.contains(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The ripgrep exclude globs that skip the dependency folders at any depth ({@code !node_modules/}). */
    public static List<String> ripgrepExcludes() {
        return NAMES.stream().sorted().map(n -> "!" + n + "/").toList();
    }
}
