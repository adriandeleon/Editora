package com.editora.maven;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import com.editora.process.ProcessRunner;
import com.editora.run.JdkToolchain;

/** Resolves a Maven module's runtime classpath off the JavaFX application thread. */
public final class MavenClasspathResolver {

    private MavenClasspathResolver() {}

    /**
     * Compiles {@code root} with Maven and returns its runtime classpath, or {@code null} when Maven fails.
     * The selected JDK is exposed to Maven through {@code JAVA_HOME} and the process {@code PATH}.
     */
    public static List<String> resolve(Path root, String jdkHome) {
        Path output = null;
        try {
            output = Files.createTempFile("editora-cp", ".txt");
            Path reactor = MavenReactor.reactorRoot(root, dir -> Files.isRegularFile(dir.resolve("pom.xml")));
            boolean multiModule = reactor != null && !reactor.equals(root);
            Path workingDirectory = multiModule ? reactor : root;
            List<String> argv = multiModule
                    ? MavenClasspath.reactorArgv(output, MavenReactor.moduleSelector(reactor, root))
                    : MavenClasspath.argv(output);
            ProcessRunner.Result result = ProcessRunner.run(
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
}
