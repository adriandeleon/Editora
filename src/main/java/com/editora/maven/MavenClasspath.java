package com.editora.maven;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Builds the {@code mvn} command that dumps a project's runtime classpath, and assembles the full classpath
 * for launching a main class without the Java language server. Used by the Run feature's build-tool fallback
 * (jdtls unavailable): {@code mvn dependency:build-classpath} writes the <em>dependency</em> classpath to a
 * file; the module's own compiled output ({@code target/classes}) is prepended. Pure and unit-tested; the
 * subprocess itself runs elsewhere (off the FX thread).
 *
 * <p>Uses the PATH {@code mvn} (not the project's {@code ./mvnw} wrapper) — consistent with how Run/Debug
 * always launch a user-controlled interpreter rather than a repo-supplied script.
 */
public final class MavenClasspath {

    private MavenClasspath() {}

    /**
     * The {@code mvn} argv that compiles the module and writes its dependency classpath
     * (pathSeparator-joined) to {@code outputFile}. {@code compile} runs first so {@code target/classes} is
     * fresh before the run.
     */
    public static List<String> argv(Path outputFile) {
        return argv(outputFile.toString(), File.pathSeparator);
    }

    /** As {@link #argv(Path)}, naming the file and the separator as the machine Maven runs on does. */
    public static List<String> argv(String outputFile, String pathSeparator) {
        return List.of(
                "mvn",
                "-q",
                "compile",
                "dependency:build-classpath",
                "-Dmdep.outputFile=" + outputFile,
                "-Dmdep.pathSeparator=" + pathSeparator);
    }

    /**
     * Like {@link #argv} but for a submodule in a multi-module build, run from the reactor root:
     * {@code -pl <moduleSelector> -am} ("also make") builds the sibling modules the target depends on, so
     * their compiled output lands in {@code outputFile}'s classpath. {@code outputFile} must be an absolute
     * path (the working directory is the reactor root, not the module).
     */
    public static List<String> reactorArgv(Path outputFile, String moduleSelector) {
        return reactorArgv(outputFile.toString(), moduleSelector, File.pathSeparator);
    }

    /** As {@link #reactorArgv(Path, String)}, naming the file and the separator as Maven's machine does. */
    public static List<String> reactorArgv(String outputFile, String moduleSelector, String pathSeparator) {
        return List.of(
                "mvn",
                "-q",
                "-pl",
                moduleSelector,
                "-am",
                "compile",
                "dependency:build-classpath",
                "-Dmdep.outputFile=" + outputFile,
                "-Dmdep.pathSeparator=" + pathSeparator);
    }

    /**
     * Full launch classpath = the module's compiled output (when present) followed by the dependency
     * classpath from {@code dependencyClasspath} (a {@code File.pathSeparator}-joined string). Blank/empty
     * entries are dropped. {@code root} is the Maven module root ({@code root/target/classes}).
     */
    public static List<String> assemble(String dependencyClasspath, Path root) {
        return assemble(
                dependencyClasspath,
                root == null ? null : root.resolve("target").resolve("classes").toString(),
                File.pathSeparator);
    }

    /**
     * As {@link #assemble(String, Path)}, with the module's output folder and the separator named as the
     * machine the program runs on names them (a remote host's paths, {@code :}).
     */
    public static List<String> assemble(
            String dependencyClasspath, @Nullable String classesFolder, String pathSeparator) {
        List<String> cp = new ArrayList<>();
        if (classesFolder != null) {
            cp.add(classesFolder);
        }
        if (dependencyClasspath != null) {
            for (String e : dependencyClasspath.split(java.util.regex.Pattern.quote(pathSeparator))) {
                String t = e.strip();
                if (!t.isEmpty()) {
                    cp.add(t);
                }
            }
        }
        return cp;
    }
}
