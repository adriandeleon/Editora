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
 * open Java file anywhere.
 *
 * <p>Mirrors what {@code RunCoordinator.buildRunCommand} does for the active file, but takes its target from
 * the configuration rather than from whatever is on screen.
 */
public final class ScriptRunCommand {

    /** Configuration types this builder handles. Java is deliberately absent — it takes the jdtls path. */
    public static final String PYTHON = "python";

    public static final String SHELL = "shell";
    public static final String MAKE = "make";
    public static final String JAVA = "java";

    private ScriptRunCommand() {}

    /** Whether {@code type} is a script type this builder can launch (i.e. anything but {@code java}). */
    public static boolean isScript(String type) {
        return PYTHON.equals(type) || SHELL.equals(type) || MAKE.equals(type);
    }

    /**
     * Whether {@code type} needs a target to be launchable.
     *
     * <p>Make is the exception: a blank target means the default goal, which is exactly what a bare
     * {@code make} does, so it is a valid configuration rather than an incomplete one.
     */
    public static boolean needsTarget(String type) {
        return PYTHON.equals(type) || SHELL.equals(type);
    }

    /**
     * Builds the argv.
     *
     * @param type one of {@link #PYTHON}, {@link #SHELL}, {@link #MAKE}
     * @param target the script path, or the make target ({@code ""} = make's default goal)
     * @param args already-tokenized program arguments
     * @return the argv, or an empty list when the type is unknown or a required target is missing — the
     *     caller reports that rather than launching something arbitrary
     */
    public static List<String> build(String type, String target, List<String> args) {
        String t = target == null ? "" : target.strip();
        List<String> argv = new ArrayList<>();
        switch (type == null ? "" : type) {
            case PYTHON -> {
                if (t.isEmpty()) {
                    return List.of();
                }
                argv.add("python3");
                argv.add(t);
            }
            case SHELL -> {
                if (t.isEmpty()) {
                    return List.of();
                }
                argv.add("bash");
                argv.add(t);
            }
            case MAKE -> {
                argv.add("make");
                if (!t.isEmpty()) {
                    argv.add(t);
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
