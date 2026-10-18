package com.editora.run;

/**
 * Which fields of a run configuration its {@code type} actually launches with.
 *
 * <p>Every type honours the name, program arguments, working directory, environment and before-launch step.
 * The rest splits in two: a Java main class is resolved through jdtls and takes a main class, module, VM
 * arguments and a JDK, while a script type ({@link ScriptRunCommand}) takes only a script path, make target or
 * NPM script name. The Run Configurations form disables the half that the selected type ignores.
 *
 * <p>An unknown type — one written by a newer build, or by hand into a shared file — uses both halves, so
 * nothing in such an entry is locked away from being read or corrected.
 */
public final class RunConfigFields {

    private RunConfigFields() {}

    /** Whether {@code type} launches the script path, make target or NPM script name. */
    public static boolean usesTarget(String type) {
        return !ScriptRunCommand.JAVA.equals(type);
    }

    /** Whether {@code type}'s target is a script file (Python or shell), which Browse can pick. */
    public static boolean usesScriptFile(String type) {
        return ScriptRunCommand.PYTHON.equals(type)
                || ScriptRunCommand.SHELL.equals(type)
                || ScriptRunCommand.GROOVY.equals(type);
    }

    /**
     * Whether {@code type} has an interpreter to override: a shell script's (normally from its shebang), or a
     * Python script's or module's (normally the project's venv, else the system Python).
     */
    public static boolean usesInterpreter(String type) {
        return ScriptRunCommand.SHELL.equals(type) || ScriptRunCommand.isPython(type);
    }

    /** Whether {@code type} launches with the main class and module (a Java main class, resolved by jdtls). */
    public static boolean usesJavaFields(String type) {
        return !ScriptRunCommand.isScript(type);
    }

    /** Whether {@code type} runs on a JVM whose arguments and JDK the configuration sets: Java and Groovy. */
    public static boolean usesJvmFields(String type) {
        return usesJavaFields(type) || ScriptRunCommand.GROOVY.equals(type);
    }
}
