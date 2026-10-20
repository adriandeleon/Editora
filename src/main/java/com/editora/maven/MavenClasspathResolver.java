package com.editora.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import com.editora.process.ProcessRunner;
import com.editora.run.JdkToolchain;
import org.jspecify.annotations.Nullable;

/**
 * Resolves a Maven module's runtime classpath off the JavaFX application thread.
 *
 * <p>Locally the resolution is a {@link Plan}: the Run console runs its command as a streamed before-launch
 * step (its output shows, Stop kills it, a second Run is refused while it runs — reviews D4 and D8), then
 * {@link #finish} reads the classpath the command wrote. {@link #resolve} runs the same command silently, for
 * a project on a remote host (its output is captured there) and for callers without a console.
 */
public final class MavenClasspathResolver {

    private MavenClasspathResolver() {}

    /**
     * A resolution ready to run: {@code argv} in {@code workingDirectory} (the reactor root of a multi-module
     * build, else the module) writes the dependency classpath to {@code outputFile}; {@link #finish} turns it
     * into the launch classpath of {@code root}.
     */
    public record Plan(
            Path workingDirectory, List<String> argv, Path outputFile, Path root, MavenClasspath.Scope scope) {

        /** Whether the module is built from its reactor root with {@code -pl -am}. */
        public boolean multiModule() {
            return !workingDirectory.equals(root);
        }
    }

    /**
     * Plans the resolution of {@code root}'s classpath with Maven started by {@code executable} (the argv
     * prefix the tasks tree uses: wrapper, override or {@code mvn}).
     *
     * @throws IOException when the scratch file for the classpath cannot be created
     */
    public static Plan plan(Path root, List<String> executable, MavenClasspath.Scope scope) throws IOException {
        Path output = Files.createTempFile("editora-cp", ".txt");
        Path reactor = MavenReactor.reactorRoot(root, dir -> Files.isRegularFile(dir.resolve("pom.xml")));
        Path multiModule = reactor != null && !reactor.equals(root) ? reactor : null; // null: a lone module
        Path workingDirectory = multiModule != null ? multiModule : root;
        List<String> argv = multiModule != null
                ? MavenClasspath.reactorArgv(executable, output, MavenReactor.moduleSelector(multiModule, root), scope)
                : MavenClasspath.argv(executable, output, scope);
        return new Plan(workingDirectory, argv, output, root, scope);
    }

    /**
     * The launch classpath {@code plan}'s command wrote, or {@code null} when it wrote none (it failed before
     * {@code dependency:build-classpath} ran). Deletes the scratch file either way.
     */
    public static @Nullable List<String> finish(Plan plan) {
        try {
            if (!Files.exists(plan.outputFile())) {
                return null;
            }
            return MavenClasspath.assemble(Files.readString(plan.outputFile()), plan.root(), plan.scope());
        } catch (IOException e) {
            return null;
        } finally {
            discard(plan);
        }
    }

    /** Drops {@code plan}'s scratch file: the command never ran, or was stopped. */
    public static void discard(Plan plan) {
        try {
            Files.deleteIfExists(plan.outputFile());
        } catch (IOException | RuntimeException ignored) {
            // Best effort: the temporary file is harmless if the platform still holds it open.
        }
    }

    /** {@link #resolve(Path, String, List, MavenClasspath.Scope)} with PATH {@code mvn} and the runtime scope. */
    public static @Nullable List<String> resolve(Path root, String jdkHome) {
        return resolve(root, jdkHome, List.of("mvn"), MavenClasspath.Scope.RUNTIME);
    }

    /**
     * Compiles {@code root} with Maven and returns its launch classpath, or {@code null} when Maven fails.
     * The selected JDK is exposed to Maven through {@code JAVA_HOME} and the process {@code PATH}. Blocking,
     * with the output captured: the Run console's streamed {@link Plan} is the better path on this machine.
     */
    public static @Nullable List<String> resolve(
            Path root, String jdkHome, List<String> executable, MavenClasspath.Scope scope) {
        if (!com.editora.process.ExecTargets.runsHere(root)) {
            return resolveOnHost(root, jdkHome, executable, scope);
        }
        Plan plan;
        try {
            plan = plan(root, executable, scope);
        } catch (IOException | RuntimeException e) {
            return null;
        }
        try {
            // Maven is a JVM handed the user's paths: the C locale would make it decode them as ASCII.
            ProcessRunner.Result result = ProcessRunner.runInUserLocale(
                    plan.workingDirectory(),
                    Duration.ofMinutes(3),
                    plan.argv(),
                    JdkToolchain.environment(jdkHome, ProcessRunner.augmentedPath()));
            if (result.exit() != 0) {
                discard(plan);
                return null;
            }
            return finish(plan);
        } catch (Exception e) {
            discard(plan);
            return null;
        }
    }

    /**
     * {@link #resolve} for a project on a remote host: Maven runs there, with the host's JDK ({@code jdkHome}
     * is a path on the host, or blank for its own) and none of this machine's environment, and writes the
     * classpath into the module's {@code target/}, read back over the connection.
     */
    private static @Nullable List<String> resolveOnHost(
            Path root, String jdkHome, List<String> executable, MavenClasspath.Scope scope) {
        com.editora.process.ExecTarget target = com.editora.process.ExecTargets.forPath(root);
        Path output = null;
        try {
            Path targetDir = Files.createDirectories(root.resolve("target"));
            output = targetDir.resolve("editora-cp-" + java.util.UUID.randomUUID() + ".txt");
            Path reactor = MavenReactor.reactorRoot(root, dir -> Files.isRegularFile(dir.resolve("pom.xml")));
            Path multiModule = reactor != null && !reactor.equals(root) ? reactor : null; // null: a lone module
            String outputOnHost = target.toTargetPath(output);
            List<String> argv = multiModule != null
                    ? MavenClasspath.reactorArgv(
                            executable, outputOnHost, MavenReactor.moduleSelector(multiModule, root), ":", scope)
                    : MavenClasspath.argv(executable, outputOnHost, ":", scope);

            java.util.Map<String, String> env =
                    jdkHome == null || jdkHome.isBlank() ? java.util.Map.of() : java.util.Map.of("JAVA_HOME", jdkHome);
            ProcessRunner.Result result = ProcessRunner.runInUserLocale(
                    multiModule != null ? multiModule : root, Duration.ofMinutes(5), argv, env);
            if (result.exit() != 0) {
                return null;
            }
            String dependencyClasspath = Files.exists(output) ? Files.readString(output) : "";
            Path targetOut = root.resolve("target");
            List<String> cp = MavenClasspath.assemble(
                    dependencyClasspath, target.toTargetPath(targetOut.resolve("classes")), ":");
            if (scope == MavenClasspath.Scope.TEST) {
                List<String> withTests = new java.util.ArrayList<>(cp.size() + 1);
                withTests.add(target.toTargetPath(targetOut.resolve("test-classes")));
                withTests.addAll(cp);
                return withTests;
            }
            return cp;
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
