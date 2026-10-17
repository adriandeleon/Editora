package com.editora.run;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Decides which Python runs a file — the one answer Run, run configurations, Debug and the Python language
 * server all use, so a script cannot work under Debug and fail under Run, or show red imports that run fine.
 *
 * <p>Order, first match wins:
 * <ol>
 *   <li>the run configuration's Interpreter field;</li>
 *   <li>the interpreter picked for this project (the status-bar picker);</li>
 *   <li>an activated environment Editora was started from: {@code $VIRTUAL_ENV}, then {@code $CONDA_PREFIX};</li>
 *   <li>a virtual environment in the project: a {@code .venv}, {@code venv}, {@code env} or {@code .env}
 *       folder holding {@code pyvenv.cfg}, looked for from the file's folder up to the project root;</li>
 *   <li>the user's global setting (Settings → Debugging → Python interpreter);</li>
 *   <li>the system Python on PATH: {@code python3} then {@code python} on POSIX; {@code py -3}, then
 *       {@code python}, then {@code python3} on Windows, never the Microsoft Store stub under
 *       {@code WindowsApps}.</li>
 * </ol>
 *
 * <p>Pure apart from the {@link Inputs#isFile} and {@link Inputs#onPath} lookups the caller supplies, so a
 * test (or a remote host) decides what exists. A command may carry arguments ({@code python3 -X dev}) or an
 * {@code env} prefix; {@link PythonInterpreter#command()} keeps them as separate tokens.
 */
public final class PythonInterpreters {

    private PythonInterpreters() {}

    /** Folder names a project's own virtual environment is looked for under, in this order. */
    public static final List<String> VENV_DIRS = List.of(".venv", "venv", "env", ".env");

    /** With no project root, how many folders (the file's own included) are searched for an environment. */
    static final int NO_PROJECT_LEVELS = 4;

    /** Files that mark the top of a Python project when there is no Editora project root to stop at. */
    static final List<String> PROJECT_MARKERS =
            List.of(".git", "pyproject.toml", "setup.py", "setup.cfg", "requirements.txt", "Pipfile");

    /** Where an interpreter came from — shown beside it so the user knows why it was chosen. */
    public enum Source {
        CONFIGURATION,
        PROJECT_CHOICE,
        VIRTUAL_ENV,
        PROJECT_VENV,
        SETTING,
        PATH
    }

    /**
     * A resolved interpreter: {@code command} is the argv prefix (the executable, then any arguments the
     * user gave it); {@code venv} is the environment folder when the interpreter lives in one.
     */
    public record PythonInterpreter(List<String> command, Source source, Path venv) {
        public PythonInterpreter {
            command = List.copyOf(command);
            if (command.isEmpty()) {
                throw new IllegalArgumentException("empty interpreter command");
            }
        }

        public String executable() {
            return command.get(0);
        }

        /** The arguments after the executable (empty for a plain interpreter). */
        public List<String> args() {
            return command.subList(1, command.size());
        }

        /** The command followed by {@code more}. */
        public List<String> argv(List<String> more) {
            List<String> out = new ArrayList<>(command);
            out.addAll(more);
            return out;
        }
    }

    /**
     * What a resolution looks at.
     *
     * @param file the file being run, debugged or analysed (may be null for a project-wide question)
     * @param projectRoot the Editora project root, or null with no project
     * @param configured a run configuration's Interpreter field ("" when none)
     * @param projectChoice the interpreter picked for this project ("" when none); a relative path is
     *     resolved against {@code projectRoot}
     * @param setting the user's global interpreter setting ("" when none)
     * @param env the environment Editora was started with
     * @param windows whether the interpreter runs on Windows
     * @param isFile whether a path is an existing regular file (or directory, for {@code pyvenv.cfg} holders)
     * @param onPath the absolute path of a bare command name found on PATH, if any
     */
    public record Inputs(
            Path file,
            Path projectRoot,
            String configured,
            String projectChoice,
            String setting,
            Map<String, String> env,
            boolean windows,
            Predicate<Path> isFile,
            Function<String, Optional<String>> onPath) {
        public Inputs {
            configured = configured == null ? "" : configured.strip();
            projectChoice = projectChoice == null ? "" : projectChoice.strip();
            setting = setting == null ? "" : setting.strip();
            env = env == null ? Map.of() : env;
        }

        /** Local inputs: the real file system and {@code onPath}, this JVM's environment and OS. */
        public static Inputs local(
                Path file,
                Path projectRoot,
                String configured,
                String projectChoice,
                String setting,
                Function<String, Optional<String>> onPath) {
            return new Inputs(
                    file,
                    projectRoot,
                    configured,
                    projectChoice,
                    setting,
                    System.getenv(),
                    isWindows(),
                    Files::isRegularFile,
                    onPath);
        }
    }

    /**
     * The absolute path of {@code name} on Editora's augmented PATH (login shell PATH, inherited PATH, the
     * usual install folders), or empty — the {@link Inputs#onPath} for a local resolution.
     */
    public static Optional<String> onAugmentedPath(String name) {
        List<String> resolved = com.editora.process.ProcessRunner.resolveExecutable(List.of(name));
        String exe = resolved.isEmpty() ? name : resolved.get(0);
        return exe.equals(name) ? Optional.empty() : Optional.of(exe);
    }

    /** Resolves for a local file with the real file system, environment, OS and augmented PATH. */
    public static PythonInterpreter resolveLocal(
            Path file, Path projectRoot, String configured, String projectChoice, String setting) {
        return resolve(Inputs.local(
                file, projectRoot, configured, projectChoice, setting, PythonInterpreters::onAugmentedPath));
    }

    private static Path userHome() {
        String home = System.getProperty("user.home", "");
        try {
            return home.isBlank() ? null : Path.of(home).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Whether this JVM runs on Windows. */
    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** Resolves the interpreter for {@code in}. Never null: the last step always answers. */
    public static PythonInterpreter resolve(Inputs in) {
        if (!in.configured().isEmpty()) {
            return explicit(in.configured(), in, Source.CONFIGURATION);
        }
        if (!in.projectChoice().isEmpty()) {
            return explicit(in.projectChoice(), in, Source.PROJECT_CHOICE);
        }
        Optional<PythonInterpreter> active = activated(in);
        if (active.isPresent()) {
            return active.get();
        }
        Optional<Path> venv = findProjectVenv(in.file(), in.projectRoot(), in.isFile(), in.windows());
        if (venv.isPresent()) {
            Path python = venvPython(venv.get(), in.windows());
            return new PythonInterpreter(List.of(python.toString()), Source.PROJECT_VENV, venv.get());
        }
        if (!in.setting().isEmpty()) {
            return explicit(in.setting(), in, Source.SETTING);
        }
        return systemPython(in.windows(), in.onPath());
    }

    /** A user-written command: tokenized, a relative executable path taken from the project root. */
    static PythonInterpreter explicit(String text, Inputs in, Source source) {
        List<String> tokens = ProgramArgs.tokenize(text);
        if (tokens.isEmpty()) {
            return systemPython(in.windows(), in.onPath());
        }
        List<String> command = new ArrayList<>(tokens);
        int exe = executableIndex(command);
        String first = command.get(exe);
        if (in.projectRoot() != null && looksRelativePath(first)) {
            command.set(exe, in.projectRoot().resolve(first).normalize().toString());
        }
        return new PythonInterpreter(command, source, venvOf(Path.of(command.get(exe)), in.isFile()));
    }

    /** {@code env NAME=value … python} → the index of {@code python}; otherwise 0. */
    static int executableIndex(List<String> command) {
        if (command.isEmpty() || !"env".equals(command.get(0))) {
            return 0;
        }
        int i = 1;
        while (i < command.size() && command.get(i).matches("[A-Za-z_][A-Za-z0-9_]*=.*")) {
            i++;
        }
        return Math.min(i, command.size() - 1);
    }

    private static boolean looksRelativePath(String s) {
        boolean hasSeparator = s.indexOf('/') >= 0 || s.indexOf('\\') >= 0;
        if (!hasSeparator) {
            return false; // a bare command name: left for PATH
        }
        try {
            return !Path.of(s).isAbsolute() && !s.startsWith("~");
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** {@code $VIRTUAL_ENV}, then {@code $CONDA_PREFIX}, when its interpreter exists. */
    static Optional<PythonInterpreter> activated(Inputs in) {
        for (String var : List.of("VIRTUAL_ENV", "CONDA_PREFIX")) {
            String dir = in.env().get(var);
            if (dir == null || dir.isBlank()) {
                continue;
            }
            Path root;
            try {
                root = Path.of(dir.strip());
            } catch (RuntimeException e) {
                continue;
            }
            for (Path python : candidatesIn(root, in.windows())) {
                if (in.isFile().test(python)) {
                    return Optional.of(new PythonInterpreter(List.of(python.toString()), Source.VIRTUAL_ENV, root));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The interpreter inside an environment folder: {@code Scripts\python.exe} on Windows, {@code bin/python}
     * elsewhere (conda's Windows layout keeps {@code python.exe} at the top, which {@link #candidatesIn}
     * also tries).
     */
    public static Path venvPython(Path venv, boolean windows) {
        return windows
                ? venv.resolve("Scripts").resolve("python.exe")
                : venv.resolve("bin").resolve("python");
    }

    static List<Path> candidatesIn(Path root, boolean windows) {
        return windows
                ? List.of(root.resolve("Scripts").resolve("python.exe"), root.resolve("python.exe"))
                : List.of(
                        root.resolve("bin").resolve("python"),
                        root.resolve("bin").resolve("python3"));
    }

    /** Whether {@code dir} is a virtual environment with an interpreter: {@code pyvenv.cfg} plus the binary. */
    public static boolean isVenv(Path dir, Predicate<Path> isFile, boolean windows) {
        return dir != null && isFile.test(dir.resolve("pyvenv.cfg")) && isFile.test(venvPython(dir, windows));
    }

    /**
     * The nearest project virtual environment for {@code file}: each folder from the file's own up to
     * {@code projectRoot} (inclusive) is checked for {@link #VENV_DIRS}. With no project root the walk stops
     * at the first folder holding a {@link #PROJECT_MARKERS project marker}, after
     * {@link #NO_PROJECT_LEVELS} folders, or below the home folder: an environment in the home folder itself
     * is never taken for a loose script.
     */
    public static Optional<Path> findProjectVenv(Path file, Path projectRoot, Predicate<Path> isFile, boolean windows) {
        Path dir = file == null ? projectRoot : file.getParent();
        Path stop = projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
        Path home = projectRoot == null ? userHome() : null;
        int levels = 0;
        while (dir != null && levels++ < 64) {
            if (home != null && home.startsWith(dir.toAbsolutePath().normalize())) {
                break; // a loose script never picks up an environment in (or above) the home folder
            }
            for (String name : VENV_DIRS) {
                Path candidate = dir.resolve(name);
                if (isVenv(candidate, isFile, windows)) {
                    return Optional.of(candidate);
                }
            }
            Path here = dir.toAbsolutePath().normalize();
            if (stop != null) {
                if (here.equals(stop) || !here.startsWith(stop)) {
                    break;
                }
            } else if (levels >= NO_PROJECT_LEVELS
                    || PROJECT_MARKERS.stream()
                            .anyMatch(m -> isFile.test(here.resolve(m)) || Files.isDirectory(here.resolve(m)))) {
                break;
            }
            dir = dir.getParent();
        }
        return Optional.empty();
    }

    /**
     * Every virtual environment the picker should offer for {@code root}: the {@link #VENV_DIRS} at the root
     * and any direct sub-folder that is an environment (a {@code .venv-py312} next to {@code .venv}), in a
     * stable order. Filesystem-touching (lists one directory).
     */
    public static List<Path> listVenvs(Path root, boolean windows) {
        if (root == null) {
            return List.of();
        }
        Set<Path> found = new LinkedHashSet<>();
        for (String name : VENV_DIRS) {
            Path candidate = root.resolve(name);
            if (isVenv(candidate, Files::isRegularFile, windows)) {
                found.add(candidate);
            }
        }
        try (var children = Files.list(root)) {
            children.filter(Files::isDirectory)
                    .sorted()
                    .filter(d -> isVenv(d, Files::isRegularFile, windows))
                    .forEach(found::add);
        } catch (java.io.IOException | RuntimeException e) {
            // unreadable root: the fixed names above are all there is
        }
        return List.copyOf(found);
    }

    /** The environment folder an interpreter path lives in ({@code …/.venv/bin/python} → {@code …/.venv}). */
    static Path venvOf(Path executable, Predicate<Path> isFile) {
        Path bin = executable.getParent();
        Path root = bin == null ? null : bin.getParent();
        if (root != null && isFile.test(root.resolve("pyvenv.cfg"))) {
            return root;
        }
        Path sibling = bin; // conda on Windows: python.exe at the top of the environment
        return sibling != null && isFile.test(sibling.resolve("pyvenv.cfg")) ? sibling : null;
    }

    /**
     * The system Python: {@code python3}/{@code python} on POSIX; on Windows the {@code py} launcher
     * ({@code py -3}), then {@code python}, then {@code python3} — skipping the Microsoft Store alias under
     * {@code WindowsApps}, which prints "Python was not found" or opens the Store. When nothing is found the
     * bare name is returned, so the launch fails with the name the user would type.
     */
    public static PythonInterpreter systemPython(boolean windows, Function<String, Optional<String>> onPath) {
        if (windows) {
            Optional<String> py = real(onPath.apply("py"));
            if (py.isPresent()) {
                return new PythonInterpreter(List.of(py.get(), "-3"), Source.PATH, null);
            }
            for (String name : List.of("python", "python3")) {
                Optional<String> found = real(onPath.apply(name));
                if (found.isPresent()) {
                    return new PythonInterpreter(List.of(found.get()), Source.PATH, null);
                }
            }
            return new PythonInterpreter(List.of("python"), Source.PATH, null);
        }
        for (String name : List.of("python3", "python")) {
            Optional<String> found = onPath.apply(name);
            if (found != null && found.isPresent()) {
                return new PythonInterpreter(List.of(found.get()), Source.PATH, null);
            }
        }
        return new PythonInterpreter(List.of("python3"), Source.PATH, null);
    }

    /** Drops the Microsoft Store App Execution Alias ({@code …\WindowsApps\python.exe}). */
    static Optional<String> real(Optional<String> found) {
        if (found == null || found.isEmpty()) {
            return Optional.empty();
        }
        String lower = found.get().replace('/', '\\').toLowerCase(Locale.ROOT);
        return lower.contains("\\windowsapps\\") ? Optional.empty() : found;
    }

    /**
     * {@code PYTHONPATH} entries for a run: the project root, plus its {@code src/} folder when it has one
     * (the src layout), so {@code from pkg import x} works from a file deep in the package. Empty with no
     * project. Pure apart from one directory check.
     */
    public static List<String> pythonPathEntries(Path projectRoot, Predicate<Path> isDirectory) {
        if (projectRoot == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        out.add(projectRoot.toString());
        Path src = projectRoot.resolve("src");
        if (isDirectory.test(src)) {
            out.add(src.toString());
        }
        return List.copyOf(out);
    }
}
