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
 * module name. A true JPMS {@code --module <mod>/<class>} launch is deferred. JavaFX is the exception: its jars
 * go on {@code --module-path} with {@code --add-modules} ({@link JavaFxModules}), or an {@code Application}
 * subclass stops with "JavaFX runtime components are missing".
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
        List<String> paths = new ArrayList<>();
        if (modulePaths != null) {
            paths.addAll(modulePaths);
        }
        if (classPaths != null) {
            paths.addAll(classPaths);
        }
        JavaFxModules.Split fx = JavaFxModules.split(paths, vmArgs);
        if (fx.any()) {
            argv.add("--module-path");
            argv.add(String.join(File.pathSeparator, fx.jars()));
            argv.add("--add-modules");
            argv.add(fx.addModules());
        }
        List<String> cp = fx.rest();
        if (!cp.isEmpty()) {
            argv.add("-cp");
            argv.add(String.join(File.pathSeparator, cp));
        }
        // jdtls names a class of a named module `<module>/<class>`; off the class path only the class part
        // is a main class (`java -cp … mod/pkg.Main` fails with "Could not find or load main class").
        argv.add(JavaMainClass.classPart(mainClass));
        if (programArgs != null) {
            argv.addAll(programArgs);
        }
        return argv;
    }
}
