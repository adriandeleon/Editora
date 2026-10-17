package com.editora.run;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A planned Python launch: the argv, the environment on top of the inherited one, and the interpreter that was
 * chosen (for the console's first line and for messages). Built by {@link #plan} from a {@link Request} and the
 * machine the program runs on ({@link Host}); pure apart from the lookups the host supplies, so a test decides
 * what exists. For a remote host those lookups are network round trips: plan off the FX thread.
 *
 * @param argv the full command line
 * @param env variables to set on top of the inherited environment
 * @param interpreter the project's interpreter as resolved (before a shebang overrode it)
 * @param missingShebang the specific interpreter the file's shebang asked for when it is not installed here
 *     ({@code python3.12}), else null — the caller says it ran the project's interpreter instead
 */
public record PythonRunPlan(
        List<String> argv,
        Map<String, String> env,
        PythonInterpreters.PythonInterpreter interpreter,
        String missingShebang) {

    /**
     * What to run.
     *
     * @param file the script (in its own file system), or null for a module run
     * @param projectRoot this window's project root, or null
     * @param target the script path as the interpreter's machine names it, or the module name
     * @param module whether {@code target} is a module ({@code python -m})
     * @param args program arguments
     * @param firstLine the script's first line (its shebang), or null
     * @param configured a run configuration's Interpreter ("" when none)
     * @param interpreterOptions a run configuration's Interpreter options, already tokenized
     * @param projectChoice the interpreter picked for this project ("" when none)
     * @param setting the global interpreter setting, or a remote host's {@code python} tool ("" when none)
     * @param userEnv a run configuration's variables (they win)
     */
    public record Request(
            Path file,
            Path projectRoot,
            String target,
            boolean module,
            List<String> args,
            String firstLine,
            String configured,
            List<String> interpreterOptions,
            String projectChoice,
            String setting,
            Map<String, String> userEnv) {
        public Request {
            args = args == null ? List.of() : List.copyOf(args);
            interpreterOptions = interpreterOptions == null ? List.of() : List.copyOf(interpreterOptions);
            userEnv = userEnv == null ? Map.of() : userEnv;
        }

        /** A plain Run File request: no configuration fields. */
        public static Request file(
                Path file,
                Path projectRoot,
                String target,
                List<String> args,
                String firstLine,
                String choice,
                String setting) {
            return new Request(
                    file, projectRoot, target, false, args, firstLine, "", List.of(), choice, setting, Map.of());
        }
    }

    /**
     * The machine the program runs on.
     *
     * @param local whether it is this machine (its PATH and environment apply)
     * @param windows whether it runs on Windows
     * @param separator its path-list separator
     * @param env its environment, as far as it is known (empty for a remote host)
     * @param isFile whether a path (in the file's own file system) is an existing file
     * @param isDirectory whether a path is an existing directory
     * @param onPath the absolute path of a bare command name on its PATH (empty when unknown)
     * @param toTarget a path as that machine names it
     */
    public record Host(
            boolean local,
            boolean windows,
            String separator,
            Map<String, String> env,
            Predicate<Path> isFile,
            Predicate<Path> isDirectory,
            Function<String, Optional<String>> onPath,
            Function<Path, String> toTarget) {

        /** This machine. */
        public static Host here() {
            return new Host(
                    true,
                    PythonInterpreters.isWindows(),
                    File.pathSeparator,
                    System.getenv(),
                    Files::isRegularFile,
                    Files::isDirectory,
                    PythonInterpreters::onAugmentedPath,
                    Path::toString);
        }

        /** A remote host, WSL distribution or container: POSIX, its environment unknown, paths translated. */
        public static Host elsewhere(Function<Path, String> toTarget) {
            return new Host(
                    false,
                    false,
                    ":",
                    Map.of(),
                    Files::isRegularFile,
                    Files::isDirectory,
                    name -> Optional.empty(),
                    toTarget);
        }

        /** This host with every file lookup answering "no" — for when its files cannot be reached. */
        public Host withoutLookups() {
            return new Host(local, windows, separator, env, p -> false, p -> false, name -> Optional.empty(), toTarget);
        }
    }

    /** Plans the launch (see {@link PythonLaunch}). */
    public static PythonRunPlan plan(Request r, Host host) {
        PythonInterpreters.Inputs inputs = new PythonInterpreters.Inputs(
                r.file(),
                r.projectRoot(),
                r.configured(),
                r.projectChoice(),
                r.setting(),
                host.env(),
                host.windows(),
                host.isFile(),
                host.onPath());
        PythonInterpreters.PythonInterpreter resolved = translate(PythonInterpreters.resolve(inputs), r, host);
        PythonLaunch.Shebang shebang = r.module() ? PythonLaunch.Shebang.NONE : PythonLaunch.shebang(r.firstLine());
        PythonLaunch.Choice choice =
                PythonLaunch.choose(resolved, shebang, host.local() ? PythonLaunch.existsLocally(host.onPath()) : null);
        List<String> command = new ArrayList<>(choice.command());
        command.addAll(r.interpreterOptions());
        Map<String, String> interpreterEnv = new LinkedHashMap<>(PythonLaunch.envPrefix(command));
        interpreterEnv.putAll(choice.env());
        List<String> pythonPath = PythonInterpreters.pythonPathEntries(r.projectRoot(), host.isDirectory()).stream()
                .map(p -> host.toTarget().apply(r.projectRoot().getFileSystem().getPath(p)))
                .toList();
        Map<String, String> env = PythonLaunch.environment(
                interpreterEnv, pythonPath, host.env().get(PythonLaunch.PYTHONPATH), host.separator(), r.userEnv());
        List<String> argv = PythonLaunch.argv(command, r.target(), r.module(), r.args());
        String missing = choice.shebangMissing()
                ? PythonLaunch.shortName(shebang.interpreter().get(0))
                : null;
        return new PythonRunPlan(List.copyOf(argv), env, resolved, missing);
    }

    /**
     * The resolved interpreter as the target names it: a venv found in the project, or an explicit path under
     * the project root, is a path in Editora's view of the files ({@code \\wsl$\…}, an SFTP path); the program
     * runs where {@code toTarget} says. Local runs are unchanged.
     */
    static PythonInterpreters.PythonInterpreter translate(
            PythonInterpreters.PythonInterpreter interpreter, Request r, Host host) {
        if (host.local()) {
            return interpreter;
        }
        List<String> command = new ArrayList<>(interpreter.command());
        int exe = PythonInterpreters.executableIndex(command);
        if (interpreter.source() == PythonInterpreters.Source.PROJECT_VENV && interpreter.venv() != null) {
            command.set(exe, host.toTarget().apply(PythonInterpreters.venvPython(interpreter.venv(), false)));
        } else if (r.projectRoot() != null
                && command.get(exe).startsWith(r.projectRoot().toString())) {
            command.set(
                    exe, host.toTarget().apply(r.projectRoot().getFileSystem().getPath(command.get(exe))));
        }
        return new PythonInterpreters.PythonInterpreter(command, interpreter.source(), interpreter.venv());
    }
}
