package com.editora.maven;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
        return List.of(
                "mvn",
                "-q",
                "compile",
                "dependency:build-classpath",
                "-Dmdep.outputFile=" + outputFile,
                "-Dmdep.pathSeparator=" + File.pathSeparator);
    }

    /**
     * Full launch classpath = the module's compiled output (when present) followed by the dependency
     * classpath from {@code dependencyClasspath} (a {@code File.pathSeparator}-joined string). Blank/empty
     * entries are dropped. {@code root} is the Maven module root ({@code root/target/classes}).
     */
    public static List<String> assemble(String dependencyClasspath, Path root) {
        List<String> cp = new ArrayList<>();
        if (root != null) {
            cp.add(root.resolve("target").resolve("classes").toString());
        }
        if (dependencyClasspath != null) {
            for (String e : dependencyClasspath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                String t = e.strip();
                if (!t.isEmpty()) {
                    cp.add(t);
                }
            }
        }
        return cp;
    }
}
