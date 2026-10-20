package com.editora.run;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.editora.build.BuildTool;
import org.jspecify.annotations.Nullable;

/**
 * The build a Java launch runs first in a Maven or Gradle project (review D2): what the "Build before Run and
 * Debug" setting runs, the symbolic before-launch step a configuration may name, and the hint a launch that
 * skipped the build prints when the JVM cannot find the class. Pure.
 *
 * <p>jdtls runs with autobuild off, and the launch it resolves runs whatever {@code target/classes} holds —
 * nothing after a clone, stale code after an edit. The build tool is the authority on how the project
 * compiles (annotation processors, generated sources, filtered resources), so the launch asks it first:
 * Maven's {@code compile} ({@code test-compile} for a class under {@code src/test}), Gradle's
 * {@code compileJava} ({@code compileTestJava}), through the same executable the tasks tree uses.
 */
public final class BuildBeforeLaunch {

    private BuildBeforeLaunch() {}

    /** The build tool of {@code root}: {@link BuildTool#MAVEN} for a {@code pom.xml}, {@link BuildTool#GRADLE} for a Gradle script, else null. */
    public static @Nullable BuildTool toolAt(Path root) {
        if (root == null) {
            return null;
        }
        try {
            for (BuildTool tool : List.of(BuildTool.MAVEN, BuildTool.GRADLE)) {
                for (String marker : tool.markers()) {
                    if (Files.isRegularFile(root.resolve(marker))) {
                        return tool;
                    }
                }
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    /** Whether {@code source} is under the module {@code root}'s {@code src/test} tree. */
    public static boolean isTestSource(Path source, Path root) {
        return com.editora.maven.MavenClasspath.Scope.forSource(source, root)
                == com.editora.maven.MavenClasspath.Scope.TEST;
    }

    /** The goals/tasks that compile what a launch of a {@code src/main} ({@code testScope} false) or {@code src/test} class runs. */
    public static List<String> compileGoals(BuildTool tool, boolean testScope) {
        return switch (tool) {
            case MAVEN -> List.of(testScope ? "test-compile" : "compile");
            case GRADLE -> List.of(testScope ? "compileTestJava" : "compileJava");
            default -> List.of();
        };
    }

    /** The quiet compile command: {@code executable} (the tasks tree's argv prefix) + {@code -q} + {@link #compileGoals}. */
    public static List<String> compileArgv(BuildTool tool, List<String> executable, boolean testScope) {
        return withGoals(executable, compileGoals(tool, testScope));
    }

    /** {@code executable -q goals…}. */
    public static List<String> withGoals(List<String> executable, List<String> goals) {
        List<String> argv = new ArrayList<>(executable);
        argv.add("-q");
        argv.addAll(goals);
        return List.copyOf(argv);
    }

    /**
     * A before-launch step that names a build tool rather than a program: {@code maven:compile},
     * {@code gradle:compileJava build}. Resolved at run time through the tool's executable (wrapper, override
     * or PATH command — with the same trust gate as the tasks tree), so a saved configuration never freezes a
     * command that rots when the user's Maven moves (review D3).
     */
    public record Symbolic(BuildTool tool, List<String> goals) {
        /** The text a configuration stores for this step. */
        public String text() {
            return tool.id() + ":" + String.join(" ", goals);
        }
    }

    /** The symbolic step a configuration's before-launch text names, or null for a plain command (or none). */
    public static @Nullable Symbolic symbolic(String beforeLaunch) {
        if (beforeLaunch == null) {
            return null;
        }
        String text = beforeLaunch.strip();
        int colon = text.indexOf(':');
        if (colon <= 0 || text.indexOf(' ') >= 0 && text.indexOf(' ') < colon) {
            return null;
        }
        String id = text.substring(0, colon).toLowerCase(Locale.ROOT);
        BuildTool tool = null;
        for (BuildTool candidate : BuildTool.values()) {
            if (candidate.id().equals(id)) {
                tool = candidate;
                break;
            }
        }
        if (tool == null) {
            return null;
        }
        List<String> goals = ProgramArgs.tokenize(text.substring(colon + 1));
        return goals.isEmpty() ? null : new Symbolic(tool, goals);
    }

    /** The step a generated Maven project's configuration stores: {@code maven:compile}. */
    public static String seededStep(BuildTool tool) {
        return new Symbolic(tool, compileGoals(tool, false)).text();
    }

    /**
     * Whether the automatic build runs before a launch: the setting is on, the configuration has no step of
     * its own (its step is the user's build — never doubled), and the project is built by Maven or Gradle.
     */
    public static boolean wanted(boolean setting, String configuredStep, BuildTool tool) {
        return setting && (configuredStep == null || configuredStep.isBlank()) && tool != null;
    }

    /** How many of a program's first output lines are read for {@link #missingClass}. */
    public static final int HINT_WINDOW = 8;

    /**
     * Whether {@code line}, among a program's first lines, says the JVM never found {@code mainClass} —
     * {@code Error: Could not find or load main class X} or {@code ClassNotFoundException: X} — the symptom
     * of a launch that ran before any build (review D15). The hint that follows says to build.
     */
    public static boolean missingClass(String line, String mainClass) {
        if (line == null || mainClass == null || mainClass.isBlank()) {
            return false;
        }
        return line.contains("Could not find or load main class " + mainClass)
                || line.contains("ClassNotFoundException: " + mainClass);
    }
}
