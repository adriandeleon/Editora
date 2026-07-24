package com.editora.run;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the {@code java} argv to run a project main class: {@code [javaExec, vmArgs…, -cp <paths>,
 * mainClass, programArgs…]}. Pure and unit-tested.
 *
 * <p>Module and class paths are combined onto a single {@code -cp} (classpath). This runs the common
 * classpath-based Maven/Gradle app correctly, and runs a modular project's main class off the classpath too
 * (the module system falls back to the unnamed module) — good enough to "just run it" without needing the
 * module name. A true JPMS {@code --module <mod>/<class>} launch is deferred.
 */
public final class JavaRunCommand {

    private JavaRunCommand() {}

    public static List<String> build(
            String javaExec,
            List<String> modulePaths,
            List<String> classPaths,
            String mainClass,
            List<String> vmArgs,
            List<String> programArgs) {
        List<String> argv = new ArrayList<>();
        argv.add(javaExec == null || javaExec.isBlank() ? "java" : javaExec);
        if (vmArgs != null) {
            argv.addAll(vmArgs);
        }
        List<String> cp = new ArrayList<>();
        if (modulePaths != null) {
            cp.addAll(modulePaths);
        }
        if (classPaths != null) {
            cp.addAll(classPaths);
        }
        if (!cp.isEmpty()) {
            argv.add("-cp");
            argv.add(String.join(File.pathSeparator, cp));
        }
        argv.add(mainClass);
        if (programArgs != null) {
            argv.addAll(programArgs);
        }
        return argv;
    }
}
