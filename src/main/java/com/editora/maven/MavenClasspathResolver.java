package com.editora.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.editora.process.ExecTarget;
import com.editora.process.ExecTargets;
import com.editora.process.ProcessRunner;
import com.editora.run.JdkToolchain;

/**
 * Resolves a Maven module's runtime classpath off the JavaFX application thread.
 *
 * <p>The resolution is a {@link Plan}: the Run console runs its command as a streamed before-launch step (its
 * output shows, Stop kills it, a second Run is refused while it runs — reviews D4 and D8), then {@link #finish}
 * reads the classpath the command wrote. A project on a remote host plans the same way — Maven runs there,
 * with the host's paths and separator, and writes the classpath into the host's temp folder when Editora can
 * read it back from there (else the module's {@code target/}) — so its build streams into the Run console too
 * (review D10). {@link #resolve} runs a plan silently, for callers without a console.
 */
public final class MavenClasspathResolver {

    private MavenClasspathResolver() {}

    /**
     * A resolution ready to run: {@code argv} in {@code workingDirectory} (the reactor root of a multi-module
     * build, else the module) writes the dependency classpath to {@code outputFile}; {@link #finish} turns it
     * into the launch classpath of {@code root}. {@code reactor} is the module's reactor when the build runs
     * from it with {@code -pl -am}, else {@code null}.
     */
    public record Plan(
            Path workingDirectory,
            List<String> argv,
            Path outputFile,
            Path root,
            MavenClasspath.Scope scope,
            MavenReactor.Reactor reactor,
            List<String> executable,
            List<String> toggleArgs) {
        /** Whether the module is built from its reactor root with {@code -pl -am}. */
        public boolean multiModule() {
            return reactor != null;
        }

        /** Whether Maven runs on another machine (the module is on a remote host). */
        public boolean onHost() {
            return !ExecTargets.runsHere(root);
        }

        /**
         * The same resolution run in the module alone — the retry when Maven answered that its reactor does
         * not hold the selected module ({@link MavenReactor#isNotInReactor}; review D5). Shares the scratch
         * file.
         */
        public Plan singleModule() {
            if (reactor == null) {
                return this;
            }
            ExecTarget target = ExecTargets.forPath(root);
            boolean onHost = onHost();
            List<String> argv = MavenClasspath.argv(
                    executable,
                    onHost ? target.toTargetPath(outputFile) : outputFile.toString(),
                    onHost ? ":" : java.io.File.pathSeparator,
                    scope);
            return new Plan(
                    root,
                    MavenClasspath.withToggles(argv, toggleArgs),
                    outputFile,
                    root,
                    scope,
                    null,
                    executable,
                    toggleArgs);
        }
    }

    /** {@link #plan(Path, Path, List, List, MavenClasspath.Scope)} with no boundary and no toggle args. */
    public static Plan plan(Path root, List<String> executable, MavenClasspath.Scope scope) throws IOException {
        return plan(root, null, executable, List.of(), scope);
    }

    /**
     * Plans the resolution of {@code root}'s classpath with Maven started by {@code executable} (the argv
     * prefix the tasks tree uses: wrapper, override or {@code mvn}), plus the tasks tree's active toggle args
     * (profiles and flags; the reactor toggle is dropped, see {@link MavenClasspath#withoutReactorArgs}). The
     * module's reactor is found through its ancestors' {@code <modules>} ({@link MavenReactor#describe}), the
     * walk bounded by {@code boundary} (the open project's root, or {@code null}): a module the reactor
     * really lists builds from the root with {@code -pl -am}; anything else — a top-level project, a project
     * under some unrelated pom — builds alone.
     *
     * @throws IOException when the scratch file for the classpath cannot be created
     */
    public static Plan plan(
            Path root, Path boundary, List<String> executable, List<String> toggleArgs, MavenClasspath.Scope scope)
            throws IOException {
        List<String> toggles = toggleArgs == null ? List.of() : List.copyOf(toggleArgs);
        MavenReactor.Reactor reactor = MavenReactor.describeOnDisk(root, boundary);
        boolean onHost = !ExecTargets.runsHere(root);
        ExecTarget target = ExecTargets.forPath(root);
        Path output = onHost ? hostScratchFile(target, root) : Files.createTempFile("editora-cp", ".txt");
        String outputName = onHost ? target.toTargetPath(output) : output.toString();
        String separator = onHost ? ":" : java.io.File.pathSeparator;
        Path workingDirectory = reactor != null ? reactor.root() : root;
        List<String> argv = reactor != null
                ? MavenClasspath.reactorArgv(executable, outputName, reactor.moduleSelector(), separator, scope)
                : MavenClasspath.argv(executable, outputName, separator, scope);
        return new Plan(
                workingDirectory,
                MavenClasspath.withToggles(argv, toggles),
                output,
                root,
                scope,
                reactor,
                List.copyOf(executable),
                toggles);
    }

    /**
     * Where a host's Maven writes the classpath: a file in the host's {@code /tmp} when Editora reaches that
     * folder (an SSH or WSL host — its whole file system is in view), else in the module's {@code target/}
     * (a container: only its mounts are in view), which the next {@code clean} sweeps. Never created here.
     */
    private static Path hostScratchFile(ExecTarget target, Path root) throws IOException {
        String name = "editora-cp-" + UUID.randomUUID() + ".txt";
        try {
            Path tmp = target.toPath("/tmp");
            if (tmp != null && !tmp.equals(Path.of("/tmp")) && Files.isDirectory(tmp)) {
                return tmp.resolve(name);
            }
        } catch (RuntimeException notReachable) {
            // The module's target/ below
        }
        return Files.createDirectories(root.resolve("target")).resolve(name);
    }

    /**
     * The launch classpath {@code plan}'s command wrote, or {@code null} when it wrote none (it failed before
     * {@code dependency:build-classpath} ran). Deletes the scratch file either way.
     */
    public static List<String> finish(Plan plan) {
        try {
            if (!Files.exists(plan.outputFile())) {
                return null;
            }
            String dependencyClasspath = Files.readString(plan.outputFile());
            if (!plan.onHost()) {
                return MavenClasspath.assemble(dependencyClasspath, plan.root(), plan.scope());
            }
            ExecTarget target = ExecTargets.forPath(plan.root());
            Path targetOut = plan.root().resolve("target");
            List<String> cp = MavenClasspath.assemble(
                    dependencyClasspath, target.toTargetPath(targetOut.resolve("classes")), ":");
            if (plan.scope() == MavenClasspath.Scope.TEST) {
                List<String> withTests = new java.util.ArrayList<>(cp.size() + 1);
                withTests.add(target.toTargetPath(targetOut.resolve("test-classes")));
                withTests.addAll(cp);
                return withTests;
            }
            return cp;
        } catch (IOException | RuntimeException e) {
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
    public static List<String> resolve(Path root, String jdkHome) {
        return resolve(root, jdkHome, List.of("mvn"), MavenClasspath.Scope.RUNTIME);
    }

    /**
     * Compiles {@code root} with Maven and returns its launch classpath, or {@code null} when Maven fails.
     * Here the selected JDK is exposed to Maven through {@code JAVA_HOME} and the process {@code PATH}; on a
     * host, {@code jdkHome} is a path there (or blank for its own java) and none of this machine's environment
     * goes along. Blocking, with the output captured: the Run console's streamed {@link Plan} is the better
     * path for a launch.
     */
    public static List<String> resolve(Path root, String jdkHome, List<String> executable, MavenClasspath.Scope scope) {
        Plan plan;
        try {
            plan = plan(root, null, executable, List.of(), scope);
        } catch (IOException | RuntimeException e) {
            return null;
        }
        boolean onHost = plan.onHost();
        try {
            java.util.Map<String, String> env = onHost
                    ? (jdkHome == null || jdkHome.isBlank()
                            ? java.util.Map.of()
                            : java.util.Map.of("JAVA_HOME", jdkHome))
                    : JdkToolchain.environment(jdkHome, ProcessRunner.augmentedPath());
            // Maven is a JVM handed the user's paths: the C locale would make it decode them as ASCII.
            ProcessRunner.Result result = ProcessRunner.runInUserLocale(
                    plan.workingDirectory(), Duration.ofMinutes(onHost ? 5 : 3), plan.argv(), env);
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
}
