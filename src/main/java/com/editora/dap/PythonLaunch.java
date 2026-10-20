package com.editora.dap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * The {@code launch} request debugpy is sent for a Python program — a script, or a module such as {@code pytest}.
 * Pure shaping, unit-tested; {@link DapManager} decides the inputs.
 *
 * <ul>
 *   <li>The program runs under the resolved interpreter, taken apart the way debugpy takes it: {@code python}
 *       (the executable), {@code pythonArgs} (its own arguments, such as {@code -X dev}) and {@code env} (an
 *       {@code env NAME=value …} prefix). Handed the whole command, debugpy ran the program with {@code env}
 *       and waited forever for a launcher that never started.</li>
 *   <li>{@code subProcess: false}: a child Python process ({@code multiprocessing}, a pool) runs without the
 *       debugger. With debugpy's default ({@code true}) every child waited for a debugger to attach through
 *       a request Editora does not serve, so the program hung.</li>
 *   <li>{@code PYTHONPATH} is the project root and its {@code src/} folder (when given), then the user's own
 *       {@code PYTHONPATH} — always set, so the folder Editora puts on the <em>adapter's</em> path to find
 *       debugpy does not leak into the program and its children.</li>
 *   <li>With {@code programConsole}, debugpy hands the program's command line back ({@code runInTerminal})
 *       and Editora starts it, which gives it a standard input; output is then unbuffered so a prompt shows
 *       before the program waits.</li>
 * </ul>
 */
public final class PythonLaunch {

    private PythonLaunch() {}

    /** What runs: a script file ({@code program}) or a module ({@code python -m module}), its arguments and folder. */
    public record Target(
            @Nullable String program,
            @Nullable String module,
            List<String> args,
            @Nullable String cwd,
            @Nullable Target fallback) {
        public Target {
            args = args == null ? List.of() : List.copyOf(args);
        }

        public static Target script(String program, List<String> args, @Nullable String cwd) {
            return new Target(program, null, args, cwd, null);
        }

        public static Target module(String module, List<String> args, @Nullable String cwd) {
            return new Target(null, module, args, cwd, null);
        }

        /** This target, with {@code other} to run instead when its module is missing (pytest → unittest). */
        public Target orElse(@Nullable Target other) {
            return new Target(program, module, args, cwd, other);
        }
    }

    /**
     * The launch body.
     *
     * @param target what to run
     * @param interpreter the program's interpreter, taken apart ({@link DapManager.HostPython#parse})
     * @param pythonPath entries to put first on {@code PYTHONPATH} (the project root, {@code src/}); may be empty
     * @param inheritedPythonPath the user's own {@code PYTHONPATH} ("" for none; null when it is not known here,
     *     as on a remote host, where it is then left alone unless there are entries to add)
     * @param separator the path-list separator where the program runs ({@code :} or {@code ;})
     * @param programConsole whether Editora starts the program itself (it then has a standard input)
     */
    public static Map<String, Object> request(
            Target target,
            DapManager.HostPython interpreter,
            List<String> pythonPath,
            @Nullable String inheritedPythonPath,
            String separator,
            boolean programConsole) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "python");
        m.put("name", "Editora (Launch)");
        m.put("request", "launch");
        if (target.module() != null && !target.module().isBlank()) {
            m.put("module", target.module());
        } else {
            m.put("program", target.program() == null ? "" : target.program());
        }
        if (target.cwd() != null && !target.cwd().isBlank()) {
            m.put("cwd", target.cwd());
        }
        if (!target.args().isEmpty()) {
            m.put("args", target.args());
        }
        if (interpreter != null) {
            m.put("python", interpreter.executable());
            if (!interpreter.args().isEmpty()) {
                m.put("pythonArgs", interpreter.args());
            }
        }
        m.put("env", environment(interpreter, pythonPath, inheritedPythonPath, separator, programConsole));
        m.put("console", programConsole ? "integratedTerminal" : "internalConsole");
        m.put("subProcess", false);
        m.put("stopOnEntry", false);
        return m;
    }

    /** The program's environment changes: the interpreter's own, {@code PYTHONPATH}, unbuffered output. */
    static Map<String, String> environment(
            DapManager.HostPython interpreter,
            List<String> pythonPath,
            @Nullable String inheritedPythonPath,
            String separator,
            boolean programConsole) {
        Map<String, String> env = new LinkedHashMap<>();
        if (interpreter != null) {
            env.putAll(interpreter.env());
        }
        // The command's own PYTHONPATH (env PYTHONPATH=… python3) stands for the user's; else the inherited one.
        String own = env.containsKey("PYTHONPATH") ? env.get("PYTHONPATH") : inheritedPythonPath;
        boolean entries = pythonPath != null && !pythonPath.isEmpty();
        if (own != null || entries) { // null and no entries: a host's own PYTHONPATH, unknown here, is left alone
            env.put("PYTHONPATH", joinPath(pythonPath, own, separator));
        }
        if (programConsole) {
            env.putIfAbsent("PYTHONUNBUFFERED", "1");
        }
        return env;
    }

    /** {@code entries} then {@code rest}, joined by {@code separator}, without duplicates or blanks. */
    static String joinPath(List<String> entries, @Nullable String rest, String separator) {
        List<String> parts = new ArrayList<>();
        for (String e : entries == null ? List.<String>of() : entries) {
            if (e != null && !e.isBlank() && !parts.contains(e)) {
                parts.add(e);
            }
        }
        if (rest != null) {
            for (String e : rest.split(java.util.regex.Pattern.quote(separator), -1)) {
                if (!e.isBlank() && !parts.contains(e)) {
                    parts.add(e);
                }
            }
        }
        return String.join(separator, parts);
    }

    /**
     * The command line the program runs as, for the console's first line — written as Run writes its own
     * ({@link com.editora.run.RunService#commandLine}): the interpreter and its arguments, the script or {@code -m
     * module}, the program's arguments. debugpy's own launcher, which sits in between, is left out.
     */
    public static String commandLine(DapManager.HostPython interpreter, Target target) {
        List<String> words = new ArrayList<>();
        words.add(interpreter.executable());
        words.addAll(interpreter.args());
        if (target.module() != null && !target.module().isBlank()) {
            words.add("-m");
            words.add(target.module());
        } else {
            words.add(target.program() == null ? "" : target.program());
        }
        words.addAll(target.args());
        return com.editora.run.RunService.commandLine(words);
    }
}
