package com.editora.run;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Decides which Node.js runs a file, and from which folder — the one answer Run, run configurations, Debug, npm
 * scripts, the TypeScript language server, the HTTP Client and Doctor all use, so a script cannot run under one
 * Node and be debugged under another.
 *
 * <p>Order, first match wins:
 * <ol>
 *   <li>the run configuration's Interpreter field;</li>
 *   <li>the version a {@code .nvmrc} or {@code .node-version} names, from the file's folder up to the project
 *       root (with no project, the package root), when nvm has that version installed ({@code $NVM_DIR/versions/node/v<version>/bin/node}, nvm's
 *       folder being {@code $NVM_DIR}, else {@code $XDG_CONFIG_HOME/nvm} when that exists, else {@code ~/.nvm})
 *       — a project's own pin beats the global setting;</li>
 *   <li>the user's setting (Settings → Build Tools → Node.js);</li>
 *   <li>{@code node} on Editora's augmented PATH (the login shell's PATH, so nvm/fnm/volta defaults count).</li>
 * </ol>
 *
 * <p>A version file that names a version nvm does not have (or an alias such as {@code lts/*}) is skipped, and
 * the result says so ({@link NodeRuntime#unmetVersion}) so the caller can tell the user.
 *
 * <p>Pure apart from the {@link Inputs#isFile}, {@link Inputs#readFile}, {@link Inputs#listDir} and
 * {@link Inputs#onPath} lookups the caller supplies, so a test (or a remote host) decides what exists. A command
 * may carry arguments ({@code node --no-warnings}); {@link NodeRuntime#command()} keeps them as separate tokens.
 */
public final class NodeRuntimes {

    private NodeRuntimes() {}

    /** Version files looked for, nearest first; the first one found names the version. */
    public static final List<String> VERSION_FILES = List.of(".nvmrc", ".node-version");

    /** With no project root, how many folders (the file's own included) are searched for a package root. */
    static final int NO_PROJECT_LEVELS = 6;

    /** Where a Node.js came from — shown beside it so the user knows why it was chosen. */
    public enum Source {
        CONFIGURATION,
        SETTING,
        VERSION_FILE,
        PATH
    }

    /**
     * A resolved Node.js.
     *
     * @param command the argv prefix (the executable, then any arguments given to it)
     * @param source why it was chosen
     * @param versionFile the version file's name ({@code .nvmrc}) when {@link Source#VERSION_FILE} chose it, or the
     *     file that named an {@link #unmetVersion}
     * @param unmetVersion what a version file asked for when nvm does not have it (so another Node runs), else null
     */
    public record NodeRuntime(
            List<String> command,
            Source source,
            @Nullable String versionFile,
            @Nullable String unmetVersion) {
        public NodeRuntime {
            command = List.copyOf(command);
            if (command.isEmpty()) {
                throw new IllegalArgumentException("empty node command");
            }
        }

        public NodeRuntime(List<String> command, Source source, @Nullable String versionFile) {
            this(command, source, versionFile, null);
        }

        public String executable() {
            return command.get(0);
        }

        /** The arguments after the executable (empty for a plain {@code node}). */
        public List<String> args() {
            return command.subList(1, command.size());
        }

        /** The command followed by {@code more}. */
        public List<String> argv(List<String> more) {
            List<String> out = new ArrayList<>(command);
            out.addAll(more);
            return out;
        }

        /** The folder holding the executable when it is an absolute path ({@code PATH} prepend for npm, tsls). */
        public Optional<Path> binDir() {
            try {
                Path exe = Path.of(executable());
                return exe.isAbsolute() && exe.getParent() != null ? Optional.of(exe.getParent()) : Optional.empty();
            } catch (java.nio.file.InvalidPathException e) {
                return Optional.empty();
            }
        }
    }

    /**
     * What a resolution looks at.
     *
     * @param file the file being run or debugged (may be null for a project-wide question)
     * @param projectRoot the Editora project root, or null with no project
     * @param configured a run configuration's Interpreter field ("" when none)
     * @param setting the user's Node.js setting ("" when none)
     * @param home the user's home folder, where nvm keeps its versions (may be null)
     * @param isFile whether a path is an existing regular file
     * @param readFile a small file's text, or empty when unreadable
     * @param listDir a folder's entries, or an empty list
     * @param onPath the absolute path of a bare command name found on PATH, if any
     * @param nvmDir nvm's own folder ({@code $NVM_DIR}), or null for {@code <home>/.nvm}
     */
    public record Inputs(
            @Nullable Path file,
            @Nullable Path projectRoot,
            String configured,
            String setting,
            @Nullable Path home,
            Predicate<Path> isFile,
            Function<Path, Optional<String>> readFile,
            Function<Path, List<Path>> listDir,
            Function<String, Optional<String>> onPath,
            @Nullable Path nvmDir) {
        public Inputs {
            configured = configured == null ? "" : configured.strip();
            setting = setting == null ? "" : setting.strip();
        }

        /** Without an {@code $NVM_DIR}: nvm lives in {@code <home>/.nvm}. */
        public Inputs(
                @Nullable Path file,
                @Nullable Path projectRoot,
                String configured,
                String setting,
                @Nullable Path home,
                Predicate<Path> isFile,
                Function<Path, Optional<String>> readFile,
                Function<Path, List<Path>> listDir,
                Function<String, Optional<String>> onPath) {
            this(file, projectRoot, configured, setting, home, isFile, readFile, listDir, onPath, null);
        }

        /** The folder holding nvm's {@code versions/node}, or null with neither a home nor an nvm folder. */
        @Nullable
        Path nvmRoot() {
            return nvmDir != null ? nvmDir : home == null ? null : home.resolve(".nvm");
        }

        /** Local inputs: the real file system, the user's home and Editora's augmented PATH. */
        public static Inputs local(@Nullable Path file, @Nullable Path projectRoot, String configured, String setting) {
            String home = System.getProperty("user.home");
            return new Inputs(
                    file,
                    projectRoot,
                    configured,
                    setting,
                    home == null ? null : Path.of(home),
                    Files::isRegularFile,
                    NodeRuntimes::readSmall,
                    NodeRuntimes::list,
                    PythonInterpreters::onAugmentedPath,
                    NodeRuntimes.nvmDir(
                            System.getenv("NVM_DIR"), System.getenv("XDG_CONFIG_HOME"), Files::isDirectory));
        }
    }

    /**
     * nvm's folder from the environment: {@code $NVM_DIR} when set, else {@code $XDG_CONFIG_HOME/nvm} when that
     * folder exists (nvm's installer uses it when {@code XDG_CONFIG_HOME} is set), else null ({@code ~/.nvm}).
     */
    public static @Nullable Path nvmDir(
            @Nullable String nvmDirVar, @Nullable String xdgConfigHome, Predicate<Path> isDirectory) {
        try {
            if (nvmDirVar != null && !nvmDirVar.isBlank()) {
                return Path.of(nvmDirVar.strip());
            }
            if (xdgConfigHome != null && !xdgConfigHome.isBlank()) {
                Path xdg = Path.of(xdgConfigHome.strip()).resolve("nvm");
                if (isDirectory.test(xdg)) {
                    return xdg;
                }
            }
        } catch (java.nio.file.InvalidPathException e) {
            return null;
        }
        return null;
    }

    /** Resolves for a local file with the real file system and augmented PATH. Off the FX thread. */
    public static NodeRuntime resolveLocal(
            @Nullable Path file, @Nullable Path projectRoot, String configured, String setting) {
        return resolve(Inputs.local(file, projectRoot, configured, setting));
    }

    /** Resolves per the order in the class comment. Never null: the last resort is a bare {@code node}. */
    public static NodeRuntime resolve(Inputs in) {
        if (!in.configured().isEmpty()) {
            List<String> cmd = tokens(in.configured());
            if (!cmd.isEmpty()) {
                return new NodeRuntime(cmd, Source.CONFIGURATION, null);
            }
        }
        // A project's own pin beats the global setting (a run configuration's Interpreter beats both).
        Path file = in.file();
        Path home = in.home();
        String unmet = null;
        String unmetFile = null;
        if (file != null && home != null) {
            Optional<Path> versionFile = versionFile(in, file, home);
            if (versionFile.isPresent()) {
                String wanted = firstLine(in.readFile().apply(versionFile.get()).orElse(""));
                Optional<Path> node = nvmNode(in, wanted);
                String name = String.valueOf(versionFile.get().getFileName());
                if (node.isPresent()) {
                    return new NodeRuntime(List.of(node.get().toString()), Source.VERSION_FILE, name);
                }
                if (!wanted.isEmpty()) {
                    unmet = wanted;
                    unmetFile = name;
                }
            }
        }
        if (!in.setting().isEmpty()) {
            List<String> cmd = tokens(in.setting());
            if (!cmd.isEmpty()) {
                return new NodeRuntime(cmd, Source.SETTING, unmetFile, unmet);
            }
        }
        String node = in.onPath().apply("node").orElse("node");
        return new NodeRuntime(List.of(node), Source.PATH, unmetFile, unmet);
    }

    /**
     * A Node.js for one of Editora's own tools (the JavaScript debug adapter) that must not depend on what a user
     * picked for their programs: the newest nvm-installed Node of at least {@code minMajor}, else {@code node} on
     * PATH. Neither the setting nor a project's version file is consulted.
     */
    public static NodeRuntime resolveForTool(Inputs in, int minMajor) {
        Path nvm = in.nvmRoot();
        if (nvm != null) {
            Optional<Path> newest = in.listDir().apply(nvm.resolve("versions").resolve("node")).stream()
                    .filter(d -> parseVersion(String.valueOf(d.getFileName()))
                            .map(v -> v[0] >= minMajor)
                            .orElse(false))
                    .sorted(Comparator.comparing(
                                    (Path d) -> parseVersion(String.valueOf(d.getFileName()))
                                            .orElseThrow(),
                                    NodeRuntimes::compareVersions)
                            .reversed())
                    .map(d -> d.resolve("bin").resolve("node"))
                    .filter(in.isFile())
                    .findFirst();
            if (newest.isPresent()) {
                return new NodeRuntime(List.of(newest.get().toString()), Source.PATH, null);
            }
        }
        return new NodeRuntime(List.of(in.onPath().apply("node").orElse("node")), Source.PATH, null);
    }

    /** {@link #resolveForTool(Inputs, int)} on this machine. Off the FX thread. */
    public static NodeRuntime resolveForTool(int minMajor) {
        return resolveForTool(Inputs.local(null, null, "", ""), minMajor);
    }

    /** A version file's first non-blank, non-comment line, stripped ("" when none). */
    static String firstLine(String text) {
        for (String line : text.split("\\R")) {
            String l = line.strip();
            if (!l.isEmpty() && !l.startsWith("#")) {
                return l;
            }
        }
        return "";
    }

    /**
     * The folder a Node file runs from: the nearest folder holding a {@code package.json}, looked for from the
     * file's folder upward. A file inside {@code node_modules} counts from the folder above the outermost
     * {@code node_modules}, so a dependency's own package never becomes the root. The walk stops at the project
     * root; with no project it goes at most {@link #NO_PROJECT_LEVELS} folders and never reaches the home folder
     * (a stray {@code ~/package.json} is not a package root). Empty when none is found — callers then use the
     * file's own folder.
     */
    public static Optional<Path> packageRoot(
            @Nullable Path file, @Nullable Path projectRoot, @Nullable Path home, Predicate<Path> isFile) {
        if (file == null) {
            return Optional.empty();
        }
        Path start = outsideNodeModules(file.toAbsolutePath().normalize());
        Path dir = start.equals(file.toAbsolutePath().normalize()) ? start.getParent() : start;
        Path stop = projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
        Path homeDir = home == null ? null : home.toAbsolutePath().normalize();
        int levels = 0;
        while (dir != null) {
            if (stop != null && !dir.startsWith(stop)) {
                break;
            }
            if (stop == null && (levels++ >= NO_PROJECT_LEVELS || dir.equals(homeDir))) {
                break;
            }
            if (isFile.test(dir.resolve("package.json"))) {
                return Optional.of(dir);
            }
            if (dir.equals(stop)) {
                break;
            }
            dir = dir.getParent();
        }
        return Optional.empty();
    }

    /** {@link #packageRoot} on the real file system. */
    public static Optional<Path> packageRoot(Path file, @Nullable Path projectRoot) {
        String home = System.getProperty("user.home");
        return packageRoot(file, projectRoot, home == null ? null : Path.of(home), Files::isRegularFile);
    }

    /** The working folder for a Node file: its package root, else its own folder. */
    public static Path workingDirectory(Path file, @Nullable Path projectRoot) {
        return packageRoot(file, projectRoot)
                .orElseGet(() -> file.toAbsolutePath().getParent());
    }

    /** Whether {@code path} has a {@code node_modules} segment. */
    public static boolean inNodeModules(Path path) {
        if (path == null) {
            return false;
        }
        for (Path part : path) {
            if ("node_modules".equals(part.toString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code path} itself, or — when it lies inside {@code node_modules} — the folder that holds the outermost
     * {@code node_modules} ({@code /p/node_modules/a/node_modules/b/x.js} → {@code /p}).
     */
    public static Path outsideNodeModules(Path path) {
        Path root = path.getRoot();
        Path built = root;
        for (Path part : path) {
            if ("node_modules".equals(part.toString())) {
                return built == null ? path : built;
            }
            built = built == null ? part : built.resolve(part);
        }
        return path;
    }

    private static final Pattern VERSION = Pattern.compile("v?(\\d{1,9})(?:\\.(\\d{1,9}))?(?:\\.(\\d{1,9}))?");

    /** {@code [major, minor, patch]} from {@code "v24.18.1"}, {@code "Node.js 22.6"} or {@code "20"}; empty if none. */
    public static Optional<int[]> parseVersion(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher m = VERSION.matcher(text.strip());
        if (!m.find()) {
            return Optional.empty();
        }
        return Optional.of(new int[] {
            Integer.parseInt(m.group(1)),
            m.group(2) == null ? 0 : Integer.parseInt(m.group(2)),
            m.group(3) == null ? 0 : Integer.parseInt(m.group(3))
        });
    }

    /**
     * Whether a Node of {@code version} runs {@code .ts} files directly (type stripping on by default: 22.18+ on
     * the 22 line, 23.6+ and every later major). Unknown versions answer false.
     */
    public static boolean stripsTypes(String version) {
        Optional<int[]> v = parseVersion(version);
        if (v.isEmpty()) {
            return false;
        }
        int major = v.get()[0];
        int minor = v.get()[1];
        return major > 23 || (major == 23 && minor >= 6) || (major == 22 && minor >= 18);
    }

    /** Whether {@code version} is at least {@code major.minor}. Unknown versions answer false. */
    public static boolean atLeast(String version, int major, int minor) {
        Optional<int[]> v = parseVersion(version);
        return v.isPresent() && (v.get()[0] > major || (v.get()[0] == major && v.get()[1] >= minor));
    }

    // ------------------------------------------------------------------------------------------------- internals

    private static Optional<Path> versionFile(Inputs in, Path file, Path home) {
        // Up to the project root (a monorepo keeps its .nvmrc at the top, above each member's package.json);
        // with no project, up to the package root.
        Path stop = in.projectRoot() != null
                ? in.projectRoot().toAbsolutePath().normalize()
                : packageRoot(file, null, home, in.isFile()).orElse(null);
        Path dir = file.toAbsolutePath().normalize().getParent();
        int levels = 0;
        while (dir != null && levels++ < 32) {
            for (String name : VERSION_FILES) {
                Path candidate = dir.resolve(name);
                if (in.isFile().test(candidate)) {
                    return Optional.of(candidate);
                }
            }
            if (stop == null || dir.equals(stop) || !dir.startsWith(stop)) {
                break;
            }
            dir = dir.getParent();
        }
        return Optional.empty();
    }

    /**
     * The newest nvm-installed {@code node} matching {@code wanted} — {@code 20}, {@code 20.11}, {@code v20.11.1};
     * aliases such as {@code lts/*} or {@code node} are not resolved here (empty).
     */
    static Optional<Path> nvmNode(Inputs in, String wanted) {
        Optional<int[]> want = parseVersion(wanted);
        if (want.isEmpty() || !wanted.strip().matches("v?\\d+(\\.\\d+){0,2}")) {
            return Optional.empty();
        }
        int parts = wanted.strip().replaceFirst("^v", "").split("\\.").length;
        Path nvm = in.nvmRoot();
        if (nvm == null) {
            return Optional.empty();
        }
        Path versions = nvm.resolve("versions").resolve("node");
        return in.listDir().apply(versions).stream()
                .filter(d -> {
                    Optional<int[]> have = parseVersion(d.getFileName().toString());
                    if (have.isEmpty()) {
                        return false;
                    }
                    for (int i = 0; i < parts; i++) {
                        if (have.get()[i] != want.get()[i]) {
                            return false;
                        }
                    }
                    return true;
                })
                .sorted(Comparator.comparing(
                                (Path d) ->
                                        parseVersion(d.getFileName().toString()).orElseThrow(),
                                NodeRuntimes::compareVersions)
                        .reversed())
                .map(d -> d.resolve("bin").resolve("node"))
                .filter(in.isFile())
                .findFirst();
    }

    private static int compareVersions(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) {
                return Integer.compare(a[i], b[i]);
            }
        }
        return 0;
    }

    private static List<String> tokens(String text) {
        try {
            return ProgramArgs.commandLine(text);
        } catch (IllegalArgumentException e) {
            return List.of(text);
        }
    }

    private static Optional<String> readSmall(Path p) {
        try {
            return Files.size(p) > 4096 ? Optional.empty() : Optional.of(Files.readString(p));
        } catch (java.io.IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static List<Path> list(Path dir) {
        try (var s = Files.list(dir)) {
            return s.toList();
        } catch (java.io.IOException | RuntimeException e) {
            return List.of();
        }
    }
}
