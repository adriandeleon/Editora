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

/**
 * Decides which Node.js runs a file, and from which folder — the one answer Run, run configurations, Debug, npm
 * scripts, the TypeScript language server, the HTTP Client and Doctor all use, so a script cannot run under one
 * Node and be debugged under another.
 *
 * <p>Order, first match wins:
 * <ol>
 *   <li>the run configuration's Interpreter field;</li>
 *   <li>the user's setting (Settings → Debugging → Node.js);</li>
 *   <li>the version a {@code .nvmrc} or {@code .node-version} names, from the file's folder up to the package
 *       root, when nvm has that version installed ({@code ~/.nvm/versions/node/v<version>/bin/node});</li>
 *   <li>{@code node} on Editora's augmented PATH (the login shell's PATH, so nvm/fnm/volta defaults count).</li>
 * </ol>
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

    /** A resolved Node.js: {@code command} is the argv prefix (the executable, then any arguments given to it). */
    public record NodeRuntime(List<String> command, Source source, String versionFile) {
        public NodeRuntime {
            command = List.copyOf(command);
            if (command.isEmpty()) {
                throw new IllegalArgumentException("empty node command");
            }
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
     */
    public record Inputs(
            Path file,
            Path projectRoot,
            String configured,
            String setting,
            Path home,
            Predicate<Path> isFile,
            Function<Path, Optional<String>> readFile,
            Function<Path, List<Path>> listDir,
            Function<String, Optional<String>> onPath) {
        public Inputs {
            configured = configured == null ? "" : configured.strip();
            setting = setting == null ? "" : setting.strip();
        }

        /** Local inputs: the real file system, the user's home and Editora's augmented PATH. */
        public static Inputs local(Path file, Path projectRoot, String configured, String setting) {
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
                    PythonInterpreters::onAugmentedPath);
        }
    }

    /** Resolves for a local file with the real file system and augmented PATH. Off the FX thread. */
    public static NodeRuntime resolveLocal(Path file, Path projectRoot, String configured, String setting) {
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
        if (!in.setting().isEmpty()) {
            List<String> cmd = tokens(in.setting());
            if (!cmd.isEmpty()) {
                return new NodeRuntime(cmd, Source.SETTING, null);
            }
        }
        if (in.file() != null && in.home() != null) {
            Optional<Path> versionFile = versionFile(in);
            if (versionFile.isPresent()) {
                String wanted = in.readFile()
                        .apply(versionFile.get())
                        .map(String::strip)
                        .orElse("");
                Optional<Path> node = nvmNode(in, wanted);
                if (node.isPresent()) {
                    return new NodeRuntime(
                            List.of(node.get().toString()),
                            Source.VERSION_FILE,
                            versionFile.get().getFileName().toString());
                }
            }
        }
        String node = in.onPath().apply("node").orElse("node");
        return new NodeRuntime(List.of(node), Source.PATH, null);
    }

    /**
     * The folder a Node file runs from: the nearest folder holding a {@code package.json}, looked for from the
     * file's folder upward. A file inside {@code node_modules} counts from the folder above the outermost
     * {@code node_modules}, so a dependency's own package never becomes the root. The walk stops at the project
     * root; with no project it goes at most {@link #NO_PROJECT_LEVELS} folders and never reaches the home folder
     * (a stray {@code ~/package.json} is not a package root). Empty when none is found — callers then use the
     * file's own folder.
     */
    public static Optional<Path> packageRoot(Path file, Path projectRoot, Path home, Predicate<Path> isFile) {
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
    public static Optional<Path> packageRoot(Path file, Path projectRoot) {
        String home = System.getProperty("user.home");
        return packageRoot(file, projectRoot, home == null ? null : Path.of(home), Files::isRegularFile);
    }

    /** The working folder for a Node file: its package root, else its own folder. */
    public static Path workingDirectory(Path file, Path projectRoot) {
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

    private static final Pattern VERSION = Pattern.compile("v?(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?");

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

    private static Optional<Path> versionFile(Inputs in) {
        Path stop = packageRoot(in.file(), in.projectRoot(), in.home(), in.isFile())
                .orElse(
                        in.projectRoot() == null
                                ? null
                                : in.projectRoot().toAbsolutePath().normalize());
        Path dir = in.file().toAbsolutePath().normalize().getParent();
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
        Path versions = in.home().resolve(".nvm").resolve("versions").resolve("node");
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
