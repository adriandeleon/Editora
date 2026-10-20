package com.editora.build;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.editora.process.ExecTarget;
import com.editora.process.ExecTargets;
import com.editora.process.ProcessRunner;
import org.jspecify.annotations.Nullable;

/**
 * A Gradle module's runtime classpath, worked out by Gradle on the machine the project is on — a deployment's
 * copy on its host. An init script adds an {@code editoraClasspath} task to every Java project: it builds the
 * main classes and writes {@code sourceSets.main.runtimeClasspath} to a file, read back over the connection.
 * The JVM path counterpart of {@code MavenClasspathResolver.resolveOnHost}. Blocking: off the FX thread.
 */
public final class GradleHostClasspath {

    private GradleHostClasspath() {}

    /** The init script; its name carries a hash of it, so a changed script is a new file in the host's cache. */
    static final String INIT_SCRIPT = """
            allprojects {
                plugins.withId('java') {
                    tasks.register('editoraClasspath') {
                        dependsOn 'classes'
                        def out = project.findProperty('editoraClasspathOut')
                        def cp = project.sourceSets.main.runtimeClasspath
                        doLast {
                            new File(out.toString()).text = cp.files.collect { it.absolutePath }.join(':')
                        }
                    }
                }
            }
            """;

    /** The task path of the module at {@code relative} (from the build root, {@code /}-separated). */
    static String taskPath(String relative) {
        String r = relative == null ? "" : relative.replace('\\', '/').replaceAll("^/+|/+$", "");
        return r.isEmpty() ? ":editoraClasspath" : ":" + r.replace('/', ':') + ":editoraClasspath";
    }

    /** The Gradle command: the build's own wrapper when it has one, else the host's {@code gradle}. */
    static List<String> command(boolean wrapper, String initScript, String output, String task) {
        List<String> argv = new ArrayList<>(wrapper ? List.of("sh", "./gradlew") : List.of("gradle"));
        argv.addAll(List.of(
                "-q",
                "--console=plain",
                // Reaching the project from the task is what the configuration cache forbids.
                "-Dorg.gradle.configuration-cache=false",
                "-I",
                initScript,
                "-PeditoraClasspathOut=" + output,
                task));
        return argv;
    }

    /**
     * Builds the module {@code module} of the Gradle build at {@code buildRoot} (both on the host) and returns its
     * runtime classpath as the host names it; null when Gradle fails, with what it said in {@code error[0]}.
     */
    public static @Nullable List<String> resolve(
            Path buildRoot, Path module, String jdkHome, @Nullable String @Nullable [] error) {
        ExecTarget target = ExecTargets.forPath(buildRoot);
        Path output = null;
        try {
            com.editora.process.TargetCache cache = target.cache();
            if (cache == null) {
                throw new java.io.IOException("no working folder on " + buildRoot);
            }
            String script = cache.putText(
                    "gradle/editora-classpath-" + Integer.toHexString(INIT_SCRIPT.hashCode()) + ".gradle", INIT_SCRIPT);
            Path buildDir = Files.createDirectories(module.resolve("build"));
            output = buildDir.resolve("editora-cp-" + java.util.UUID.randomUUID() + ".txt");
            String relative = buildRoot.relativize(module).toString().replace('\\', '/');
            boolean wrapper = Files.isRegularFile(buildRoot.resolve("gradlew"));
            Map<String, String> env =
                    jdkHome == null || jdkHome.isBlank() ? Map.of() : Map.of("JAVA_HOME", jdkHome.strip());
            ProcessRunner.Result result = ProcessRunner.runInUserLocale(
                    buildRoot,
                    Duration.ofMinutes(10),
                    command(wrapper, script, target.toTargetPath(output), taskPath(relative)),
                    env);
            if (result.exit() != 0 || !Files.exists(output)) {
                if (error != null && error.length > 0) {
                    error[0] = reason(result.err().isBlank() ? result.out() : result.err());
                }
                return null;
            }
            List<String> cp = new ArrayList<>();
            for (String entry :
                    Files.readString(output, StandardCharsets.UTF_8).strip().split(":")) {
                if (!entry.isBlank()) {
                    cp.add(entry);
                }
            }
            return cp;
        } catch (Exception e) {
            if (error != null && error.length > 0) {
                error[0] = e.getMessage();
            }
            return null;
        } finally {
            if (output != null) {
                try {
                    Files.deleteIfExists(output);
                } catch (Exception ignored) {
                    // a leftover file in build/ is harmless and goes with the next clean
                }
            }
        }
    }

    /** What Gradle says went wrong: the line after "What went wrong:", else its first line. */
    static String reason(String text) {
        List<String> lines = text == null
                ? List.of()
                : text.lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
        for (int i = 0; i + 1 < lines.size(); i++) {
            if (lines.get(i).contains("What went wrong")) {
                return lines.get(i + 1);
            }
        }
        return lines.isEmpty() ? "" : lines.get(0);
    }
}
