package com.editora.maven;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import com.editora.process.ProcessRunner;
import com.editora.run.JdkToolchain;
import org.jspecify.annotations.Nullable;

/** Resolves a Maven module's runtime classpath off the JavaFX application thread. */
public final class MavenClasspathResolver {

    private MavenClasspathResolver() {}

    /**
     * Compiles {@code root} with Maven and returns its runtime classpath, or {@code null} when Maven fails.
     * The selected JDK is exposed to Maven through {@code JAVA_HOME} and the process {@code PATH}.
     */
    public static @Nullable List<String> resolve(Path root, String jdkHome) {
        if (!com.editora.process.ExecTargets.runsHere(root)) {
            return resolveOnHost(root, jdkHome);
        }
        Path output = null;
        try {
            output = Files.createTempFile("editora-cp", ".txt");
            Path reactor = MavenReactor.reactorRoot(root, dir -> Files.isRegularFile(dir.resolve("pom.xml")));
            Path multiModule = reactor != null && !reactor.equals(root) ? reactor : null; // null: a lone module
            Path workingDirectory = multiModule != null ? multiModule : root;
            List<String> argv = multiModule != null
                    ? MavenClasspath.reactorArgv(output, MavenReactor.moduleSelector(multiModule, root))
                    : MavenClasspath.argv(output);
            // Maven is a JVM handed the user's paths: the C locale would make it decode them as ASCII.
            ProcessRunner.Result result = ProcessRunner.runInUserLocale(
                    workingDirectory,
                    Duration.ofMinutes(3),
                    argv,
                    JdkToolchain.environment(jdkHome, ProcessRunner.augmentedPath()));
            if (result.exit() != 0) {
                return null;
            }
            String dependencyClasspath = Files.exists(output) ? Files.readString(output) : "";
            return MavenClasspath.assemble(dependencyClasspath, root);
        } catch (Exception e) {
            return null;
        } finally {
            if (output != null) {
                try {
                    Files.deleteIfExists(output);
                } catch (Exception ignored) {
                    // Best effort: the temporary file is harmless if the platform still holds it open.
                }
            }
        }
    }

    /**
     * {@link #resolve} for a project on a remote host: Maven runs there, with the host's JDK ({@code jdkHome}
     * is a path on the host, or blank for its own) and none of this machine's environment, and writes the
     * classpath into the module's {@code target/}, read back over the connection.
     */
    private static @Nullable List<String> resolveOnHost(Path root, String jdkHome) {
        com.editora.process.ExecTarget target = com.editora.process.ExecTargets.forPath(root);
        Path output = null;
        try {
            Path targetDir = Files.createDirectories(root.resolve("target"));
            output = targetDir.resolve("editora-cp-" + java.util.UUID.randomUUID() + ".txt");
            Path reactor = MavenReactor.reactorRoot(root, dir -> Files.isRegularFile(dir.resolve("pom.xml")));
            Path multiModule = reactor != null && !reactor.equals(root) ? reactor : null; // null: a lone module
            String outputOnHost = target.toTargetPath(output);
            List<String> argv = multiModule != null
                    ? MavenClasspath.reactorArgv(outputOnHost, MavenReactor.moduleSelector(multiModule, root), ":")
                    : MavenClasspath.argv(outputOnHost, ":");
            java.util.Map<String, String> env =
                    jdkHome == null || jdkHome.isBlank() ? java.util.Map.of() : java.util.Map.of("JAVA_HOME", jdkHome);
            ProcessRunner.Result result = ProcessRunner.runInUserLocale(
                    multiModule != null ? multiModule : root, Duration.ofMinutes(5), argv, env);
            if (result.exit() != 0) {
                return null;
            }
            String dependencyClasspath = Files.exists(output) ? Files.readString(output) : "";
            return MavenClasspath.assemble(
                    dependencyClasspath,
                    target.toTargetPath(root.resolve("target").resolve("classes")),
                    ":");
        } catch (Exception e) {
            return null;
        } finally {
            if (output != null) {
                try {
                    Files.deleteIfExists(output);
                } catch (Exception ignored) {
                    // Best effort: a leftover file in target/ is harmless and goes with the next clean.
                }
            }
        }
    }
}
