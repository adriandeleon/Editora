package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import com.editora.http.HttpEnv;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The one place that finds, reads and merges a {@code .http} file's environment files ({@code http-client.env.json}
 * and its private twin): the run, the environment pill and menu and the {@code {{variable}}} marks all go through it (the
 * FX thread through {@link HttpEnvCache}, which reads in the background), so they see the same folder and the same
 * values.
 *
 * <p><b>Which folder.</b> The nearest folder, from the file's own up, that holds either env file — but only within
 * a boundary: the open project's root when the file is inside it; otherwise the root of the version-control
 * repository the file is in ({@code .git}, {@code .hg} or {@code .svn}); outside any repository, the file's own
 * folder alone. The user's home folder and the file-system root are never used unless the file itself is there:
 * a {@code .http} file downloaded or opened on its own must not pick up a private env file from a parent folder
 * the user never associated with it (its values would go to the file's hosts without any prompt). An untitled
 * buffer uses the project root's files, else none. Pure apart from the file-system checks, which are injected for
 * the tests.
 */
final class HttpEnvironments {

    /** What marks a version-control working tree's root. {@code .git} is a folder, or a file in a worktree. */
    static final List<String> VCS_MARKERS = List.of(".git", ".hg", ".svn");

    private HttpEnvironments() {}

    /**
     * Both env files of one folder as read ({@code null} for a file that is absent or unreadable); a {@code null}
     * {@code dir} has none.
     */
    record EnvFiles(
            @Nullable Path dir,
            @Nullable String publicJson,
            @Nullable String privateJson) {

        /** The variables of {@code environment}, merged as JetBrains does (see {@link HttpEnv#merged}). */
        Map<String, String> variables(String environment) {
            return HttpEnv.merged(orBlank(publicJson), orBlank(privateJson), environment);
        }

        /** The environment names of both files, public first, then private-only ones. */
        List<String> names() {
            return HttpEnv.environmentNames(orBlank(publicJson), orBlank(privateJson));
        }

        /** An absent file as HttpEnv reads it: a blank one contributes nothing, the same as none. */
        private static String orBlank(@Nullable String json) {
            return json == null ? "" : json;
        }

        /**
         * One line per env file that exists but contributes nothing because it cannot be read as JSON ("{@code
         * http-client.private.env.json} could not be read: line 3, column 5: …"), so the user hears about the file
         * instead of every variable in it being reported as undefined. Empty when both files are usable.
         */
        List<String> problems() {
            List<String> out = new java.util.ArrayList<>();
            String pub = publicJson == null ? null : HttpEnv.problem(publicJson);
            if (pub != null) {
                out.add(tr("http.env.unreadable", HttpEnv.PUBLIC_FILE, pub));
            }
            String priv = privateJson == null ? null : HttpEnv.problem(privateJson);
            if (priv != null) {
                out.add(tr("http.env.unreadable", HttpEnv.PRIVATE_FILE, priv));
            }
            return out;
        }
    }

    /** Reads both env files of {@code dir} (none when {@code dir} is null). */
    static EnvFiles read(@Nullable Path dir) {
        if (dir == null) {
            return new EnvFiles(null, null, null);
        }
        return new EnvFiles(
                dir, readOrNull(dir.resolve(HttpEnv.PUBLIC_FILE)), readOrNull(dir.resolve(HttpEnv.PRIVATE_FILE)));
    }

    /**
     * The folder whose env files apply to a buffer: for a saved file, {@link #envDir(Path, Path)} of its folder;
     * for an untitled one ({@code file == null}), the project root, else null.
     */
    static @Nullable Path forFile(@Nullable Path file, @Nullable Path projectRoot) {
        if (file == null) {
            return projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
        }
        Path dir = file.toAbsolutePath().getParent();
        return dir == null ? null : envDir(dir, projectRoot);
    }

    /** {@link #envDir(Path, Path, Path, Predicate)} against the real file system and the user's home folder. */
    static @Nullable Path envDir(Path dir, @Nullable Path projectRoot) {
        String home = System.getProperty("user.home");
        return envDir(dir, projectRoot, home == null || home.isBlank() ? null : Path.of(home), Files::exists);
    }

    /**
     * The folder whose env files apply to a request file in {@code dir}: the first of {@code dir} and its parents,
     * up to the boundary (see the class comment), holding either env file; {@code dir} itself when none does (its
     * files, if any appear later, are then used). {@code home} is never used as a parent, and neither is the
     * file-system root.
     */
    static @Nullable Path envDir(Path dir, @Nullable Path projectRoot, @Nullable Path home, Predicate<Path> exists) {
        if (dir == null) {
            return null;
        }
        Path start = dir.toAbsolutePath().normalize();
        Path homeDir = home == null ? null : home.toAbsolutePath().normalize();
        Path project = projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
        Path stop = project != null && start.startsWith(project) ? project : repositoryRoot(start, homeDir, exists);
        if (stop == null) {
            stop = start; // outside any project or repository: the file's own folder only
        }
        for (Path d = start; d != null; d = d.getParent()) {
            boolean usable = d.equals(start) || (!d.equals(homeDir) && d.getParent() != null);
            if (usable
                    && (exists.test(d.resolve(HttpEnv.PUBLIC_FILE)) || exists.test(d.resolve(HttpEnv.PRIVATE_FILE)))) {
                return d;
            }
            if (d.equals(stop)) {
                break;
            }
        }
        return start;
    }

    /**
     * The nearest of {@code start} and its parents that is a repository's root, or null. The home folder and the
     * file-system root do not count (a home folder kept under Git is not a project).
     */
    static @Nullable Path repositoryRoot(Path start, @Nullable Path home, Predicate<Path> exists) {
        for (Path d = start; d != null && d.getParent() != null && !d.equals(home); d = d.getParent()) {
            for (String marker : VCS_MARKERS) {
                if (exists.test(d.resolve(marker))) {
                    return d;
                }
            }
        }
        return null;
    }

    /** The name to select for a file whose remembered choice is {@code remembered}: it, if still offered; else none. */
    static String choose(String remembered, List<String> names) {
        return remembered != null && !remembered.isEmpty() && names.contains(remembered) ? remembered : "";
    }

    private static @Nullable String readOrNull(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readString(file);
        } catch (IOException | RuntimeException unreadable) {
            return null; // an unreadable env file contributes nothing
        }
    }
}
