package com.editora.mermaid;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Finds an installed {@code @probelabs/maid} <b>by its files alone</b> — no process, so no npm registry.
 *
 * <p>The default maid command is {@code npx -y @probelabs/maid}. Probing it ({@code --version}) to learn whether
 * maid is there makes npx look the package up on the npm registry and download it when missing: seconds of
 * network on every Doctor refresh, every Settings visit and every start-up with Mermaid on, and a download
 * nobody asked for. With that default, presence is decided here instead; a command the user typed is still
 * probed by running it ({@link Mermaid#detect}).
 *
 * <p>Where npx would find it, first match wins:
 * <ol>
 *   <li>a global install ({@code npm install -g @probelabs/maid}) — the {@code node_modules} of the Settings
 *       Node.js, of {@code node} on PATH, of an npm {@code prefix} ({@code npm_config_prefix},
 *       {@code ~/.npmrc}, {@code <node prefix>/etc/npmrc}, {@code /etc/npmrc}), {@code /usr/local} and, on
 *       Windows, {@code %APPDATA%\npm};</li>
 *   <li>a {@code maid} on PATH that belongs to that package (another program called maid does not count);</li>
 *   <li>npx's own cache, where an earlier {@code npx -y @probelabs/maid} left it
 *       ({@code <npm cache>/_npx/<hash>/node_modules/@probelabs/maid}).</li>
 * </ol>
 *
 * <p>Pure apart from the {@link Inputs} lookups, so a test decides what exists.
 */
public final class MaidLocator {

    /** The npm package of the maid linter. */
    public static final String PACKAGE = "@probelabs/maid";

    private static final Pattern NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern VERSION = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]*)\"");
    /** How far up from a resolved {@code maid} executable its {@code package.json} is looked for. */
    private static final int PACKAGE_LEVELS = 4;

    private MaidLocator() {}

    /**
     * An installed maid.
     *
     * @param packageDir the package's folder ({@code …/node_modules/@probelabs/maid})
     * @param version its {@code package.json} version ("" when it names none)
     */
    public record Found(Path packageDir, String version) {
        /** "0.0.29 · /path/to/@probelabs/maid" (the folder alone without a version) — the detail Doctor shows. */
        public String detail() {
            return version.isBlank() ? packageDir.toString() : version + " · " + packageDir;
        }
    }

    /**
     * What a search looks at.
     *
     * @param home the user's home folder (may be null)
     * @param windows whether the layout is Windows' ({@code <prefix>\node_modules}, {@code %APPDATA%\npm})
     * @param env an environment variable's value, or null
     * @param nodeSetting the Settings Node.js ("" when none)
     * @param onPath the absolute path of a bare command name found on PATH, if any
     * @param readFile a file's text, or empty when it is not a readable file
     * @param listDir a folder's entries, or an empty list
     * @param realPath a path with its links followed (the path itself when that fails)
     */
    public record Inputs(
            @Nullable Path home,
            boolean windows,
            Function<String, @Nullable String> env,
            String nodeSetting,
            Function<String, Optional<String>> onPath,
            Function<Path, Optional<String>> readFile,
            Function<Path, List<Path>> listDir,
            Function<Path, Path> realPath) {
        public Inputs {
            nodeSetting = nodeSetting == null ? "" : nodeSetting.strip();
        }

        /** This machine: the real file system, environment, home and Editora's augmented PATH. */
        public static Inputs local(String nodeSetting) {
            String home = System.getProperty("user.home", "");
            Path homePath;
            try {
                homePath = home.isBlank() ? null : Path.of(home);
            } catch (RuntimeException e) {
                homePath = null;
            }
            return new Inputs(
                    homePath,
                    com.editora.Os.isWindows(),
                    System::getenv,
                    nodeSetting,
                    MaidLocator::onAugmentedPath,
                    MaidLocator::read,
                    MaidLocator::list,
                    MaidLocator::real);
        }
    }

    /**
     * Whether {@code command} is the default {@code npx -y @probelabs/maid} — the one command whose presence is
     * decided by {@link #locate} rather than by running it.
     */
    public static boolean isDefault(List<String> command) {
        return Mermaid.command("", MermaidService.DEFAULT_MAID).equals(command);
    }

    /** {@link #locate(Inputs)} on this machine with {@code nodeSetting} as the Settings Node.js. Files only. */
    public static Optional<Found> locateLocal(String nodeSetting) {
        return locate(Inputs.local(nodeSetting));
    }

    /** The installed maid npx would run, per the order in the class comment; empty when there is none. */
    public static Optional<Found> locate(Inputs in) {
        for (Path modules : globalModuleDirs(in)) {
            Optional<Found> found = packageIn(in, modules);
            if (found.isPresent()) {
                return found;
            }
        }
        Optional<Found> onPath = onPath(in);
        if (onPath.isPresent()) {
            return onPath;
        }
        Path cache = npmCache(in);
        if (cache != null) {
            List<Path> entries = new ArrayList<>(in.listDir().apply(cache.resolve("_npx")));
            entries.sort(null); // the same answer on every run when two cached copies exist
            for (Path entry : entries) {
                Optional<Found> found = packageIn(in, entry.resolve("node_modules"));
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    /** The {@code node_modules} folders a global install may be in, most specific first. */
    static List<Path> globalModuleDirs(Inputs in) {
        LinkedHashSet<Path> prefixes = new LinkedHashSet<>();
        List<Path> npmrcs = new ArrayList<>();
        Path home = in.home();
        String configuredPrefix =
                firstNonBlank(in.env().apply("npm_config_prefix"), in.env().apply("NPM_CONFIG_PREFIX"));
        if (configuredPrefix != null) {
            addPath(prefixes, configuredPrefix, home);
        }
        if (home != null) {
            npmrcs.add(home.resolve(".npmrc"));
        }
        for (Path node : nodeExecutables(in)) {
            Path bin = node.getParent();
            if (bin == null) {
                continue;
            }
            // Windows keeps node.exe in the prefix itself; elsewhere it is <prefix>/bin/node.
            Path prefix = in.windows() ? bin : bin.getParent();
            if (prefix != null) {
                npmrcs.add(prefix.resolve("etc").resolve("npmrc"));
                prefixes.add(prefix);
            }
            prefixes.add(bin); // nvm-windows and a Windows-style layout elsewhere
        }
        if (!in.windows()) {
            npmrcs.add(Path.of("/etc/npmrc"));
        }
        for (Path npmrc : npmrcs) {
            String prefix = npmrcValue(in.readFile().apply(npmrc).orElse(""), "prefix");
            if (prefix != null) {
                addPath(prefixes, prefix, home);
            }
        }
        if (in.windows()) {
            String appData = in.env().apply("APPDATA");
            if (appData != null && !appData.isBlank()) {
                addPath(prefixes, Path.of(appData.strip()).resolve("npm").toString(), home);
            }
        } else {
            prefixes.add(Path.of("/usr/local"));
        }
        LinkedHashSet<Path> dirs = new LinkedHashSet<>();
        for (Path prefix : prefixes) {
            dirs.add(
                    in.windows()
                            ? prefix.resolve("node_modules")
                            : prefix.resolve("lib").resolve("node_modules"));
            dirs.add(in.windows() ? prefix.resolve("lib").resolve("node_modules") : prefix.resolve("node_modules"));
        }
        return List.copyOf(dirs);
    }

    /** The Settings Node.js (when it is a path, or a name PATH resolves) and {@code node} on PATH. */
    private static List<Path> nodeExecutables(Inputs in) {
        LinkedHashSet<Path> nodes = new LinkedHashSet<>();
        if (!in.nodeSetting().isEmpty()) {
            List<String> tokens = com.editora.run.ProgramArgs.tokenize(in.nodeSetting());
            String first = tokens.isEmpty() ? in.nodeSetting() : tokens.get(0);
            Optional<String> exe = first.indexOf('/') >= 0 || first.indexOf('\\') >= 0
                    ? Optional.of(first)
                    : in.onPath().apply(first);
            exe.flatMap(MaidLocator::path).ifPresent(nodes::add);
        }
        in.onPath().apply("node").flatMap(MaidLocator::path).ifPresent(nodes::add);
        return List.copyOf(nodes);
    }

    /** A {@code maid} on PATH that is this package's: its link leads into it, or (Windows' {@code maid.cmd}
     *  shim) the package sits in the {@code node_modules} beside it. */
    private static Optional<Found> onPath(Inputs in) {
        Optional<Path> maid = in.onPath().apply("maid").flatMap(MaidLocator::path);
        if (maid.isEmpty()) {
            return Optional.empty();
        }
        Path dir = maid.get().getParent();
        if (dir != null) {
            Optional<Found> beside = packageIn(in, dir.resolve("node_modules"));
            if (beside.isPresent()) {
                return beside;
            }
        }
        Path p = in.realPath().apply(maid.get()).getParent();
        for (int i = 0; p != null && i < PACKAGE_LEVELS; i++, p = p.getParent()) {
            Optional<Found> found = packageAt(in, p);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /** {@code <modules>/@probelabs/maid}, when its {@code package.json} says it is that package. */
    private static Optional<Found> packageIn(Inputs in, Path modules) {
        return packageAt(in, modules.resolve("@probelabs").resolve("maid"));
    }

    private static Optional<Found> packageAt(Inputs in, Path dir) {
        Optional<String> json = in.readFile().apply(dir.resolve("package.json"));
        if (json.isEmpty() || !PACKAGE.equals(field(NAME, json.get()))) {
            return Optional.empty();
        }
        String version = field(VERSION, json.get());
        return Optional.of(new Found(dir, version == null ? "" : version));
    }

    /** npm's cache folder: {@code npm_config_cache}, {@code ~/.npmrc}'s {@code cache=}, else npm's default. */
    static @Nullable Path npmCache(Inputs in) {
        Path home = in.home();
        String configured =
                firstNonBlank(in.env().apply("npm_config_cache"), in.env().apply("NPM_CONFIG_CACHE"));
        if (configured == null && home != null) {
            configured = npmrcValue(in.readFile().apply(home.resolve(".npmrc")).orElse(""), "cache");
        }
        if (configured != null) {
            return path(expandHome(configured, home)).orElse(null);
        }
        if (in.windows()) {
            String local = in.env().apply("LOCALAPPDATA");
            if (local != null && !local.isBlank()) {
                return path(local.strip()).map(p -> p.resolve("npm-cache")).orElse(null);
            }
        }
        return home == null ? null : home.resolve(".npm");
    }

    /** {@code key}'s value in an npmrc's text ({@code key=value} lines; {@code ;} and {@code #} comments), or null. */
    static @Nullable String npmrcValue(String npmrc, String key) {
        String value = null;
        for (String raw : npmrc.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq > 0 && line.substring(0, eq).strip().equalsIgnoreCase(key)) {
                String v = line.substring(eq + 1).strip();
                if (v.length() >= 2
                        && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"))) {
                    v = v.substring(1, v.length() - 1);
                }
                value = v.isBlank() ? null : v; // npm lets a later line win
            }
        }
        return value;
    }

    private static @Nullable String field(Pattern pattern, String json) {
        Matcher m = pattern.matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static @Nullable String firstNonBlank(@Nullable String a, @Nullable String b) {
        if (a != null && !a.isBlank()) {
            return a.strip();
        }
        return b != null && !b.isBlank() ? b.strip() : null;
    }

    private static void addPath(LinkedHashSet<Path> into, String value, @Nullable Path home) {
        path(expandHome(value, home)).ifPresent(into::add);
    }

    private static String expandHome(String value, @Nullable Path home) {
        if (home != null && (value.equals("~") || value.startsWith("~/") || value.startsWith("~\\"))) {
            return home + value.substring(1);
        }
        return value;
    }

    private static Optional<Path> path(String value) {
        try {
            return Optional.of(Path.of(value));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Optional<String> onAugmentedPath(String name) {
        List<String> resolved = com.editora.process.ProcessRunner.resolveExecutable(List.of(name));
        String exe = resolved.isEmpty() ? name : resolved.get(0);
        return exe.equals(name) ? Optional.empty() : Optional.of(exe);
    }

    private static Optional<String> read(Path file) {
        try {
            // A package.json is a few KB; anything far larger is not the one looked for.
            return Files.isRegularFile(file) && Files.size(file) <= 1 << 20
                    ? Optional.of(Files.readString(file))
                    : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static List<Path> list(Path dir) {
        try (var s = Files.list(dir)) {
            return s.toList();
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    private static Path real(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException | RuntimeException e) {
            return p;
        }
    }
}
