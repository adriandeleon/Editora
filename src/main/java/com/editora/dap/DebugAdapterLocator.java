package com.editora.dap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Locates the Microsoft <b>java-debug</b> plugin jar ({@code com.microsoft.java.debug.plugin-*.jar}) that
 * teaches a running jdtls how to debug. It is <em>not</em> part of jdtls and can't be auto-installed, so
 * we either use a path the user configured (a jar, or a directory to scan) or auto-detect common install
 * locations — VS Code's Java extension, nvim <b>mason</b>, etc. When several versions are found the newest
 * is chosen.
 *
 * <p>The name match, version comparison, and candidate-directory construction are pure (unit-tested);
 * {@link #locate} touches the filesystem.
 */
public final class DebugAdapterLocator {

    /** A java-debug plugin jar is named {@code com.microsoft.java.debug.plugin-<version>.jar}. */
    private static final String PREFIX = "com.microsoft.java.debug.plugin-";

    private static final String SUFFIX = ".jar";

    private DebugAdapterLocator() {}

    /** True if {@code fileName} looks like a java-debug plugin jar. Pure. */
    public static boolean matches(String fileName) {
        return fileName != null
                && fileName.startsWith(PREFIX)
                && fileName.endsWith(SUFFIX)
                && fileName.length() > PREFIX.length() + SUFFIX.length();
    }

    /** The {@code <version>} part of a plugin jar file name (e.g. {@code 0.53.1}), or {@code ""}. Pure. */
    static String versionOf(String fileName) {
        if (!matches(fileName)) {
            return "";
        }
        return fileName.substring(PREFIX.length(), fileName.length() - SUFFIX.length());
    }

    /**
     * Picks the newest plugin jar from {@code candidates} (file names or paths), comparing the embedded
     * version numerically (dot-separated, numeric-aware; non-numeric segments compared lexically).
     * Non-matching entries are ignored. Returns the input string of the winner, or {@code null}. Pure.
     */
    public static String selectNewest(List<String> candidates) {
        String best = null;
        String bestVer = null;
        for (String c : candidates == null ? List.<String>of() : candidates) {
            if (c == null) {
                continue;
            }
            String name = Path.of(c).getFileName().toString();
            if (!matches(name)) {
                continue;
            }
            String ver = versionOf(name);
            if (best == null || compareVersions(ver, bestVer) > 0) {
                best = c;
                bestVer = ver;
            }
        }
        return best;
    }

    /** Compares two dot-separated versions; numeric segments numerically, else lexically. Pure. */
    static int compareVersions(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            String sa = i < pa.length ? pa[i] : "";
            String sb = i < pb.length ? pb[i] : "";
            Integer ia = parseIntOrNull(sa);
            Integer ib = parseIntOrNull(sb);
            int cmp;
            if (ia != null && ib != null) {
                cmp = Integer.compare(ia, ib);
            } else {
                cmp = sa.compareTo(sb);
            }
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    private static Integer parseIntOrNull(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Resolves the plugin jar. If {@code configuredPath} is non-blank it wins: a {@code .jar} file is
     * used directly, a directory is scanned for the newest matching jar. Otherwise the common install
     * locations under {@code home} are scanned. Returns the jar path, or empty if none is found.
     */
    public static Optional<Path> locate(String configuredPath, Path home) {
        if (configuredPath != null && !configuredPath.isBlank()) {
            Path p = Path.of(configuredPath);
            if (Files.isRegularFile(p)) {
                return Optional.of(p); // user pointed at a specific jar (trust it even if oddly named)
            }
            if (Files.isDirectory(p)) {
                return scanNewest(List.of(p));
            }
            return Optional.empty();
        }
        return scanNewest(candidateServerDirs(home));
    }

    /** Collects matching jars across {@code dirs} and returns the newest. */
    private static Optional<Path> scanNewest(List<Path> dirs) {
        List<String> jars = new ArrayList<>();
        for (Path dir : dirs) {
            if (dir == null || !Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(Files::isRegularFile)
                        .filter(f -> matches(f.getFileName().toString()))
                        .forEach(f -> jars.add(f.toString()));
            } catch (IOException | RuntimeException ignored) {
                // unreadable dir — skip
            }
        }
        String newest = selectNewest(jars);
        return newest == null ? Optional.empty() : Optional.of(Path.of(newest));
    }

    /**
     * Common directories that hold the java-debug plugin's {@code server/} folder, given a user home.
     * Existing-only filtering happens in {@link #scanNewest}; the VS Code extension dirs are globbed for
     * the {@code vscjava.vscode-java-debug-*} extension (which actually ships the plugin) — and, as a
     * fallback, {@code redhat.java-*}. Pure path construction (plus a directory listing for the glob).
     */
    static List<Path> candidateServerDirs(Path home) {
        List<Path> dirs = new ArrayList<>();
        // VS Code (desktop / remote-server / OSS) — the plugin jar ships in the "Debugger for Java"
        // extension (vscjava.vscode-java-debug-<ver>/server/), NOT the redhat.java language-server one.
        for (Path ext : List.of(
                home.resolve(".vscode").resolve("extensions"),
                home.resolve(".vscode-server").resolve("extensions"),
                home.resolve(".vscode-oss").resolve("extensions"))) {
            addExtensionServers(dirs, ext, "vscjava.vscode-java-debug-");
            addExtensionServers(dirs, ext, "redhat.java-"); // fallback (some packs nest it here)
        }
        // nvim mason: java-debug-adapter/extension/server/
        dirs.add(home.resolve(".local")
                .resolve("share")
                .resolve("nvim")
                .resolve("mason")
                .resolve("packages")
                .resolve("java-debug-adapter")
                .resolve("extension")
                .resolve("server"));
        // Editora's own plugin dir, where scripts/install-java-debug.sh drops the jar (prod + --dev).
        dirs.add(home.resolve(".editora").resolve("plugins").resolve("dap").resolve("java"));
        dirs.add(home.resolve(".editora-dev").resolve("plugins").resolve("dap").resolve("java"));
        return dirs;
    }

    /** Adds the {@code server} dir of each installed extension under {@code extensionsDir} whose folder
     *  name starts with {@code prefix} (e.g. {@code vscjava.vscode-java-debug-}). */
    private static void addExtensionServers(List<Path> dirs, Path extensionsDir, String prefix) {
        if (!Files.isDirectory(extensionsDir)) {
            return;
        }
        try (Stream<Path> s = Files.list(extensionsDir)) {
            s.filter(Files::isDirectory)
                    .filter(d -> d.getFileName().toString().startsWith(prefix))
                    .map(d -> d.resolve("server"))
                    .forEach(dirs::add);
        } catch (IOException | RuntimeException ignored) {
            // unreadable — skip
        }
    }

    // --- vscode-js-debug (Node) -----------------------------------------------------------------

    /** The DAP entry point of the vscode-js-debug bundle. */
    private static final String JS_DEBUG_ENTRY = "dapDebugServer.js";

    /**
     * Resolves the vscode-js-debug DAP entry point ({@code dapDebugServer.js}). If {@code configuredPath}
     * is non-blank it wins: a file named {@code dapDebugServer.js} is used directly, any other file or a
     * directory is searched (bounded depth) for that entry. Otherwise the common install roots under
     * {@code home} are searched — Editora's own {@code plugins/dap/javascript/} (where
     * {@code install-js-debug.sh} extracts the release → {@code js-debug/src/dapDebugServer.js}), nvim
     * mason's {@code js-debug-adapter}, and VS Code's {@code ms-vscode.js-debug-*} extension. The newest
     * (by an embedded version in the path, else any) is returned. Filesystem-touching like {@link #locate}.
     */
    public static Optional<Path> locateJsDebugServer(String configuredPath, Path home) {
        if (configuredPath != null && !configuredPath.isBlank()) {
            Path p = Path.of(configuredPath);
            if (Files.isRegularFile(p) && p.getFileName().toString().equals(JS_DEBUG_ENTRY)) {
                return Optional.of(p);
            }
            Path root = Files.isDirectory(p) ? p : p.getParent();
            return findNewestEntry(root == null ? List.of() : List.of(root));
        }
        return findNewestEntry(jsDebugRoots(home));
    }

    /** Common roots that contain a {@code dapDebugServer.js} somewhere beneath them. Pure construction. */
    static List<Path> jsDebugRoots(Path home) {
        List<Path> roots = new ArrayList<>();
        roots.add(home.resolve(".editora").resolve("plugins").resolve("dap").resolve("javascript"));
        roots.add(home.resolve(".editora-dev").resolve("plugins").resolve("dap").resolve("javascript"));
        roots.add(home.resolve(".local")
                .resolve("share")
                .resolve("nvim")
                .resolve("mason")
                .resolve("packages")
                .resolve("js-debug-adapter"));
        for (Path ext : List.of(
                home.resolve(".vscode").resolve("extensions"),
                home.resolve(".vscode-server").resolve("extensions"),
                home.resolve(".vscode-oss").resolve("extensions"))) {
            if (Files.isDirectory(ext)) {
                try (Stream<Path> s = Files.list(ext)) {
                    s.filter(Files::isDirectory)
                            .filter(d -> d.getFileName().toString().startsWith("ms-vscode.js-debug"))
                            .forEach(roots::add);
                } catch (IOException | RuntimeException ignored) {
                    // unreadable — skip
                }
            }
        }
        return roots;
    }

    /** Searches each root (bounded depth) for {@code dapDebugServer.js} and returns the preferred one (see
     *  {@link #selectPreferredEntry}). */
    private static Optional<Path> findNewestEntry(List<Path> roots) {
        List<Path> found = new ArrayList<>();
        for (Path root : roots) {
            if (root == null || !Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> s = Files.walk(root, 6)) {
                s.filter(Files::isRegularFile)
                        .filter(f -> f.getFileName().toString().equals(JS_DEBUG_ENTRY))
                        .forEach(found::add);
            } catch (IOException | RuntimeException ignored) {
                // unreadable — skip
            }
        }
        String best = selectPreferredEntry(found.stream().map(Path::toString).toList());
        return best == null ? Optional.empty() : Optional.of(Path.of(best));
    }

    /**
     * Picks the preferred {@code dapDebugServer.js} path. <b>Editora's own {@code plugins/dap/} install wins
     * unconditionally</b> — it is the copy Editora placed there at the user's request (the in-app installer /
     * {@code install-js-debug.sh}), and its path carries no version, so the old newest-by-{@link #pathVersion}
     * rule always lost it to any VS Code extension dir (even a much older one), making the installer's effect
     * invisible. {@link #pathVersion} now only breaks ties <em>within</em> a class (among Editora-owned
     * installs, or — when none is Editora-owned — among the external roots). Pure. (#474)
     */
    static String selectPreferredEntry(List<String> paths) {
        String best = null;
        String bestVer = null;
        boolean bestOwned = false;
        for (String p : paths) {
            if (p == null) {
                continue;
            }
            boolean owned = isEditoraOwned(p);
            String ver = pathVersion(p);
            boolean take = best == null
                    || (owned && !bestOwned) // an Editora-owned install beats any external copy
                    || (owned == bestOwned && compareVersions(ver, bestVer) > 0); // newer within the same class
            if (take) {
                best = p;
                bestVer = ver;
                bestOwned = owned;
            }
        }
        return best;
    }

    /** Whether {@code path} lives under an Editora-managed {@code plugins/dap/} install root. Pure. */
    static boolean isEditoraOwned(String path) {
        if (path == null) {
            return false;
        }
        String norm = path.replace('\\', '/');
        return norm.contains("/plugins/dap/") || norm.startsWith("plugins/dap/");
    }

    /** Extracts the first {@code vMAJOR.MINOR[.PATCH]} version found in a path, else {@code ""}. Pure. */
    static String pathVersion(String path) {
        if (path == null) {
            return "";
        }
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("v?(\\d+\\.\\d+(?:\\.\\d+)?)").matcher(path);
        return m.find() ? m.group(1) : "";
    }

    // --- debugpy (Python) -----------------------------------------------------------------------

    /**
     * Resolves a directory to put on {@code PYTHONPATH} so {@code python -m debugpy.adapter} works, i.e. a
     * dir that contains a {@code debugpy/} package. If {@code configuredPath} is a dir containing
     * {@code debugpy/} it wins; otherwise Editora's {@code plugins/dap/python/} (where
     * {@code install-debugpy.sh} pip-installs {@code --target}) is checked. Empty when none found — the
     * caller then relies on the configured/PATH {@code python} importing debugpy directly (probed
     * separately via {@code python -c "import debugpy"}). Filesystem-touching.
     */
    public static Optional<Path> locateDebugpy(String configuredPath, Path home) {
        if (configuredPath != null && !configuredPath.isBlank()) {
            Path p = Path.of(configuredPath);
            if (hasDebugpyPackage(p)) {
                return Optional.of(p);
            }
        }
        for (Path dir : List.of(
                home.resolve(".editora").resolve("plugins").resolve("dap").resolve("python"),
                home.resolve(".editora-dev").resolve("plugins").resolve("dap").resolve("python"))) {
            if (hasDebugpyPackage(dir)) {
                return Optional.of(dir);
            }
        }
        return Optional.empty();
    }

    /** True if {@code dir} is a directory containing a {@code debugpy} package. Pure (one fs check). */
    static boolean hasDebugpyPackage(Path dir) {
        return dir != null && Files.isDirectory(dir) && Files.isDirectory(dir.resolve("debugpy"));
    }
}
