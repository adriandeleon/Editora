package com.editora.run;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * How a Python program is launched — by Run File, a Python run configuration and the pytest runner — decided in
 * one pure place (unit-tested): which interpreter, the argv, the environment and the working directory.
 *
 * <ul>
 *   <li><b>Interpreter.</b> A run configuration's Interpreter wins. Otherwise a shebang that asks for something
 *       specific is honoured as {@code ./script} would: a versioned interpreter ({@code python3.12}), a
 *       non-Python runner ({@code uv run --script}), or an interpreter path that is not a plain
 *       {@code python}/{@code python3}. A plain {@code #!/usr/bin/env python3} names no particular Python, so
 *       the project's interpreter ({@link PythonInterpreters}: picked, activated, project venv, setting, PATH)
 *       runs it, with the shebang's flags ({@code #!/usr/bin/python3 -O} keeps {@code -O}). A specific
 *       interpreter that is not installed here falls back to the project's one, and the caller says so.</li>
 *   <li><b>Environment.</b> {@code PYTHONUNBUFFERED=1} so output streams as it is printed (a pipe is otherwise
 *       block-buffered: nothing showed until exit, and stderr overtook stdout), and
 *       {@code PYTHONIOENCODING=utf-8} so a Windows Python writes UTF-8 to the pipe instead of crashing on
 *       {@code print('✓')} in the ANSI code page. The project root and its {@code src/} go in front of
 *       {@code PYTHONPATH} (keeping the user's), so {@code from pkg import x} works from a file deep in the
 *       package. The configuration's own variables come last and win.</li>
 *   <li><b>Argv.</b> The interpreter (an {@code env NAME=value} prefix becomes variables, so it also works on
 *       Windows), its flags, then the script path or {@code -m module}, then the program arguments.</li>
 * </ul>
 */
public final class PythonLaunch {

    private PythonLaunch() {}

    public static final String UNBUFFERED = "PYTHONUNBUFFERED";
    public static final String IO_ENCODING = "PYTHONIOENCODING";
    public static final String PYTHONPATH = "PYTHONPATH";

    /** {@code python}, {@code python3}, {@code python3.12}, {@code pypy3}, with an optional {@code .exe}. */
    private static final Pattern PYTHON_NAME = Pattern.compile("(?:python|pypy)(\\d+(?:\\.\\d+)*)?(?:w)?");

    /**
     * What a file's first line asks for.
     *
     * @param interpreter a specific interpreter and its arguments ({@code [python3.12]}, {@code [uv, run,
     *     --script]}); empty when the line names no particular Python (or is no shebang)
     * @param flags interpreter flags to add to the project's interpreter when {@code interpreter} is empty
     * @param env variables the shebang's {@code env NAME=value} sets (a null value unsets)
     */
    public record Shebang(List<String> interpreter, List<String> flags, Map<String, String> env) {
        public static final Shebang NONE = new Shebang(List.of(), List.of(), Map.of());

        public boolean specific() {
            return !interpreter.isEmpty();
        }
    }

    /** Classifies a Python file's first line (see the class comment). */
    public static Shebang shebang(String firstLine) {
        Map<String, String> env = new LinkedHashMap<>();
        List<String> tokens = ShellCommand.shebang(firstLine, env);
        if (tokens.isEmpty()) {
            return Shebang.NONE;
        }
        String exe = tokens.get(0);
        List<String> flags = List.copyOf(tokens.subList(1, tokens.size()));
        String base = baseName(exe);
        boolean plainPython = base.equals("python") || base.equals("python3");
        if (plainPython) {
            // `#!/usr/bin/env python3`, `#!/usr/bin/python3 -O`: no particular Python — the project's, with flags.
            return new Shebang(List.of(), flags, java.util.Collections.unmodifiableMap(env));
        }
        if (!PYTHON_NAME.matcher(base).matches() && !looksLikeRunner(base)) {
            return Shebang.NONE; // a Python file with some other shebang (a template marker, …): not ours to run
        }
        List<String> interpreter = new ArrayList<>(tokens);
        return new Shebang(List.copyOf(interpreter), List.of(), java.util.Collections.unmodifiableMap(env));
    }

    /** Runners that take a Python script: {@code uv run --script}, {@code pipx run}, {@code poetry run python}. */
    private static boolean looksLikeRunner(String base) {
        return base.equals("uv") || base.equals("pipx") || base.equals("poetry") || base.equals("pdm");
    }

    /**
     * The interpreter command: the configuration's, else the shebang's specific one when it exists here, else the
     * project's ({@code resolved}) with the shebang's flags.
     *
     * @param resolved the project's interpreter ({@link PythonInterpreters#resolve})
     * @param shebang the file's first line, classified
     * @param exists whether a specific interpreter can be started here: an absolute path that exists, or a bare
     *     name on PATH. {@code null} for a host whose files cannot be checked (used as written).
     * @return the command and whether a specific shebang interpreter was missing (the caller says so)
     */
    public static Choice choose(
            PythonInterpreters.PythonInterpreter resolved, Shebang shebang, Predicate<String> exists) {
        if (resolved.source() != PythonInterpreters.Source.CONFIGURATION && shebang.specific()) {
            String exe = shebang.interpreter().get(0);
            if (exists == null || exists.test(exe)) {
                return new Choice(shebang.interpreter(), shebang.env(), false);
            }
            String base = shortName(exe);
            if (!base.equals(exe) && exists.test(base)) { // /usr/local/bin/python3.12 not here, python3.12 on PATH
                List<String> local = new ArrayList<>(shebang.interpreter());
                local.set(0, base);
                return new Choice(local, shebang.env(), false);
            }
            return new Choice(resolved.command(), Map.of(), true);
        }
        List<String> command = new ArrayList<>(resolved.command());
        command.addAll(shebang.flags());
        return new Choice(command, shebang.env(), false);
    }

    /**
     * An interpreter command and the variables that go with it.
     *
     * @param shebangMissing the file asked for a specific interpreter that is not installed here
     */
    public record Choice(List<String> command, Map<String, String> env, boolean shebangMissing) {}

    /**
     * The argv: {@code command} without an {@code env NAME=value} prefix, then {@code -m module} or the script,
     * then {@code args}.
     *
     * @param target the script path as the interpreter's machine names it, or a module name
     * @param module whether {@code target} is a module ({@code python -m target})
     */
    public static List<String> argv(List<String> command, String target, boolean module, List<String> args) {
        List<String> argv = new ArrayList<>(withoutEnvPrefix(command));
        if (module) {
            argv.add("-m");
        }
        argv.add(target);
        if (args != null) {
            argv.addAll(args);
        }
        return argv;
    }

    /** {@code command} with a leading {@code env NAME=value …} removed (see {@link #envPrefix}). */
    public static List<String> withoutEnvPrefix(List<String> command) {
        int exe = PythonInterpreters.executableIndex(command);
        return List.copyOf(command.subList(exe, command.size()));
    }

    /** The variables of a leading {@code env NAME=value …} in {@code command}. */
    public static Map<String, String> envPrefix(List<String> command) {
        int exe = PythonInterpreters.executableIndex(command);
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 1; i < exe; i++) {
            String assignment = command.get(i);
            int eq = assignment.indexOf('=');
            out.put(assignment.substring(0, eq), assignment.substring(eq + 1));
        }
        return out;
    }

    /**
     * The environment a Python run adds on top of the inherited one.
     *
     * @param interpreterEnv the interpreter's own variables (an {@code env} prefix, a shebang's {@code env} line)
     * @param pythonPath entries to put in front of {@code PYTHONPATH} (the project root and {@code src/})
     * @param inheritedPythonPath the {@code PYTHONPATH} the program would otherwise get (null/blank when none)
     * @param separator the target's path-list separator ({@code :} or {@code ;})
     * @param userEnv a run configuration's variables — they win, and its {@code PYTHONPATH} is the one kept
     */
    public static Map<String, String> environment(
            Map<String, String> interpreterEnv,
            List<String> pythonPath,
            String inheritedPythonPath,
            String separator,
            Map<String, String> userEnv) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put(UNBUFFERED, "1");
        env.put(IO_ENCODING, "utf-8");
        if (interpreterEnv != null) {
            env.putAll(interpreterEnv);
        }
        Map<String, String> user = userEnv == null ? Map.of() : userEnv;
        String kept = user.containsKey(PYTHONPATH) ? user.get(PYTHONPATH) : inheritedPythonPath;
        if (pythonPath != null && !pythonPath.isEmpty()) {
            List<String> parts = new ArrayList<>(pythonPath);
            if (kept != null && !kept.isBlank()) {
                for (String p : kept.split(Pattern.quote(separator))) {
                    if (!p.isBlank() && !parts.contains(p)) {
                        parts.add(p);
                    }
                }
            }
            env.put(PYTHONPATH, String.join(separator, parts));
        }
        for (Map.Entry<String, String> e : user.entrySet()) {
            if (!(PYTHONPATH.equals(e.getKey()) && env.containsKey(PYTHONPATH))) {
                env.put(e.getKey(), e.getValue());
            }
        }
        return env;
    }

    /** Whether {@code program} (a name or path) is a Python interpreter, the {@code py} launcher, or uv. */
    public static boolean isPythonProgram(String program) {
        String base = baseName(program);
        return PYTHON_NAME.matcher(base).matches() || base.equals("py") || base.equals("uv");
    }

    /**
     * Whether a specific interpreter can be started on this machine: an absolute path that is a file, or a bare
     * name found on PATH.
     */
    public static Predicate<String> existsLocally(Function<String, Optional<String>> onPath) {
        return exe -> {
            if (exe.indexOf('/') >= 0 || exe.indexOf('\\') >= 0) {
                try {
                    return java.nio.file.Files.isRegularFile(Path.of(exe));
                } catch (RuntimeException e) {
                    return false;
                }
            }
            Optional<String> found = onPath.apply(exe);
            return found != null && found.isPresent();
        };
    }

    /** The interpreter as a user reads it in a message: its file name ({@code python3.12}, {@code uv}). */
    public static String shortName(String exe) {
        int slash = Math.max(exe.lastIndexOf('/'), exe.lastIndexOf('\\'));
        return slash >= 0 ? exe.substring(slash + 1) : exe;
    }

    private static String baseName(String path) {
        if (path == null) {
            return "";
        }
        String base = shortName(path).toLowerCase(Locale.ROOT);
        return base.endsWith(".exe") ? base.substring(0, base.length() - 4) : base;
    }
}
