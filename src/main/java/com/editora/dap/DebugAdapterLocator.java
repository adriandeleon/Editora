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

    private DebugAdapterLocator() {
    }

    /** True if {@code fileName} looks like a java-debug plugin jar. Pure. */
    public static boolean matches(String fileName) {
        return fileName != null && fileName.startsWith(PREFIX) && fileName.endsWith(SUFFIX)
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
        dirs.add(home.resolve(".local").resolve("share").resolve("nvim").resolve("mason")
                .resolve("packages").resolve("java-debug-adapter").resolve("extension").resolve("server"));
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
}
