package com.editora.run;

import java.util.ArrayList;
import java.util.List;

/**
 * The launch argv for a run configuration that is <b>not</b> a Java main class.
 *
 * <p>Java configurations go through jdtls to resolve a classpath, which is why they need a project and a
 * language server. Everything else Editora can already run — a Python script, a shell script, a make target —
 * needs none of that: an interpreter and a path is the whole story. Keeping those in a pure builder means the
 * argv is unit-testable, and that a script configuration launches with no project, no language server and no
 * open Java file anywhere. NPM configurations name a package script and forward optional arguments after
 * npm's {@code --} separator.
 *
 * <p>Mirrors what {@code RunCoordinator.buildRunCommand} does for the active file, but takes its target from
 * the configuration rather than from whatever is on screen.
 */
public final class ScriptRunCommand {

    /** Configuration types this builder handles. Java is deliberately absent — it takes the jdtls path. */
    public static final String PYTHON = "python";

    /** A Python module run as {@code python -m <target>} (packages, {@code __main__.py}, relative imports). */
    public static final String PYTHON_MODULE = "python-module";

    public static final String SHELL = "shell";
    public static final String MAKE = "make";
    public static final String NPM = "npm";

    /** A Groovy script, run by {@code groovy} on the configuration's JDK ({@link JvmScriptRun}). */
    public static final String GROOVY = "groovy";

    /**
     * A Clojure file, run by the Clojure CLI, Leiningen or Babashka ({@link ClojureRun}) on the configuration's
     * JDK.
     */
    public static final String CLOJURE = "clojure";

    public static final String JAVA = "java";

    private ScriptRunCommand() {}

    /** Whether {@code type} is a script type this builder can launch (i.e. anything but {@code java}). */
    public static boolean isScript(String type) {
        return isPython(type)
                || SHELL.equals(type)
                || MAKE.equals(type)
                || NPM.equals(type)
                || GROOVY.equals(type)
                || CLOJURE.equals(type);
    }

    /** Whether {@code type} is a Python script or module (launched through {@code PythonRunPlan}). */
    public static boolean isPython(String type) {
        return PYTHON.equals(type) || PYTHON_MODULE.equals(type);
    }

    /**
     * Whether {@code type} needs a target to be launchable.
     *
     * <p>Make is the exception: a blank target means the default goal, which is exactly what a bare
     * {@code make} does, so it is a valid configuration rather than an incomplete one.
     */
    public static boolean needsTarget(String type) {
        return isPython(type) || SHELL.equals(type) || NPM.equals(type) || GROOVY.equals(type) || CLOJURE.equals(type);
    }

    /**
     * Builds the argv.
     *
     * @param type one of {@link #PYTHON}, {@link #SHELL}, {@link #MAKE}, or {@link #NPM}
     * @param target the script path, make target ({@code ""} = make's default goal), or NPM script name
     * @param args already-tokenized program arguments
     * @return the argv, or an empty list when the type is unknown or a required target is missing — the
     *     caller reports that rather than launching something arbitrary
     */
    public static List<String> build(String type, String target, List<String> args) {
        return build(type, target, args, null);
    }

    /**
     * As {@link #build(String, String, List)}, with a shell script's interpreter decided by
     * {@link ShellCommand#launch} (its shebang, or the configuration's Interpreter). {@code null} — or any
     * non-shell type — uses the default: {@code bash}, or {@code zsh} for a {@code .zsh} file.
     */
    public static List<String> build(String type, String target, List<String> args, ShellCommand.Launch shell) {
        String t = target == null ? "" : target.strip();
        List<String> argv = new ArrayList<>();
        switch (type == null ? "" : type) {
            case PYTHON, PYTHON_MODULE -> {
                if (t.isEmpty()) {
                    return List.of();
                }
                // The shape only — whether the configuration is complete. The real interpreter, flags and
                // environment come from PythonRunPlan (the project's venv, the configuration's Interpreter …).
                argv.add("python3");
                if (PYTHON_MODULE.equals(type)) {
                    argv.add("-m");
                }
                argv.add(t);
            }
            case SHELL -> {
                if (t.isEmpty()) {
                    return List.of();
                }
                argv.addAll(shell == null ? List.of(ShellCommand.defaultInterpreter(t)) : shell.interpreter());
                argv.add(t);
            }
            case GROOVY -> {
                if (t.isEmpty()) {
                    return List.of();
                }
                // The shape only: the real tool and JDK come from JvmTools and the configuration's JDK.
                argv.add(JvmScriptRun.tool(GROOVY));
                argv.add(t);
            }
            case CLOJURE -> {
                if (t.isEmpty()) {
                    return List.of();
                }
                // The shape only: the real tool (clojure, lein or bb) comes from the file and its project.
                argv.add(JvmScriptRun.tool(CLOJURE));
                argv.add("-M");
                argv.add(t);
            }
            case MAKE -> {
                argv.add("make");
                if (!t.isEmpty()) {
                    argv.add(t);
                }
            }
            case NPM -> {
                if (t.isEmpty()) {
                    return List.of();
                }
                argv.add("npm");
                argv.add("run");
                argv.add(t);
                if (args != null && !args.isEmpty()) {
                    argv.add("--");
                }
            }
            default -> {
                return List.of();
            }
        }
        if (args != null) {
            argv.addAll(args);
        }
        return argv;
    }
}
