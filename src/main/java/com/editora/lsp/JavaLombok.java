package com.editora.lsp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

/**
 * Lombok for jdtls. A project that uses Lombok compiles in Maven or Gradle, but jdtls sees none of the members
 * Lombok generates unless its JVM runs with Lombok as a Java agent: every {@code getName()} of an {@code @Data}
 * class was an error and completion had nothing to offer (a gap the Java review lists; vscode-java adds the agent
 * itself). Editora adds {@code -javaagent:} for a project whose build declares Lombok, with the jar its build
 * already downloaded to the local Maven or Gradle cache. Nothing is downloaded here; a project built for the
 * first time gets the agent at the server's next start.
 */
public final class JavaLombok {

    private JavaLombok() {}

    /** The build files read for a Lombok dependency: the root's and its immediate modules'. */
    private static final List<String> BUILD_FILES = List.of("pom.xml", "build.gradle", "build.gradle.kts");

    /** The jar to run jdtls with for {@code root}, or null when its build declares no Lombok or none is cached. */
    public static @Nullable Path agentFor(Path root, Path home) {
        return declaresLombok(root) ? cachedJar(home) : null;
    }

    /** Whether {@code root}'s build, or one of its modules' one level down, depends on Lombok. */
    static boolean declaresLombok(Path root) {
        if (mentionsLombok(root)) {
            return true;
        }
        try (Stream<Path> children = Files.list(root)) {
            return children.filter(Files::isDirectory).limit(200).anyMatch(JavaLombok::mentionsLombok);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static boolean mentionsLombok(Path dir) {
        for (String name : BUILD_FILES) {
            Path file = dir.resolve(name);
            try {
                if (Files.isRegularFile(file)
                        && Files.size(file) < 2_000_000
                        && mentionsLombok(Files.readString(file))) {
                    return true;
                }
            } catch (IOException | RuntimeException e) {
                // unreadable: as if absent
            }
        }
        return false;
    }

    /** Pure: whether build-file text depends on Lombok (Maven coordinates or the Freefair Gradle plugin). */
    static boolean mentionsLombok(String buildFile) {
        return buildFile.contains("org.projectlombok") || buildFile.contains("io.freefair.lombok");
    }

    /** The newest Lombok jar in the local Maven repository, else in Gradle's module cache; null when neither has one. */
    static @Nullable Path cachedJar(Path home) {
        List<Path> jars = new ArrayList<>();
        Path maven = home.resolve(".m2/repository/org/projectlombok/lombok");
        for (Path version : children(maven)) {
            Path jar = version.resolve("lombok-" + version.getFileName() + ".jar");
            if (Files.isRegularFile(jar)) {
                jars.add(jar);
            }
        }
        if (jars.isEmpty()) {
            Path gradle = home.resolve(".gradle/caches/modules-2/files-2.1/org.projectlombok/lombok");
            for (Path version : children(gradle)) {
                for (Path hash : children(version)) {
                    Path jar = hash.resolve("lombok-" + version.getFileName() + ".jar");
                    if (Files.isRegularFile(jar)) {
                        jars.add(jar);
                    }
                }
            }
        }
        return jars.stream()
                .max((a, b) -> compareVersions(versionOf(a), versionOf(b)))
                .orElse(null);
    }

    private static String versionOf(Path jar) {
        String name = jar.getFileName().toString();
        return name.substring("lombok-".length(), name.length() - ".jar".length());
    }

    /** Pure: compares dotted versions numerically ({@code 1.18.34} after {@code 1.18.9}); a non-number sorts low. */
    static int compareVersions(String a, String b) {
        String[] x = a.split("[.-]");
        String[] y = b.split("[.-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? number(x[i]) : 0;
            int q = i < y.length ? number(y[i]) : 0;
            if (p != q) {
                return Integer.compare(p, q);
            }
        }
        return 0;
    }

    private static int number(String part) {
        try {
            return Integer.parseInt(part);
        } catch (NumberFormatException e) {
            return -1; // "edge-SNAPSHOT" and the like: below any release
        }
    }

    private static List<Path> children(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isDirectory).toList();
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    /**
     * {@code command} running {@code jar} as a Java agent — {@code --jvm-arg=-javaagent:…} for jdtls's launcher,
     * {@code -javaagent:…} after a {@code java} executable — unless it names a Lombok agent already or is a
     * command of another shape.
     */
    static List<String> withAgent(
            List<String> command, @Nullable Path jar, java.util.function.Predicate<String> isLauncher) {
        if (jar == null
                || command.isEmpty()
                || command.stream().anyMatch(a -> a.contains("-javaagent:") && a.contains("lombok"))) {
            return command;
        }
        String agent = "-javaagent:" + jar;
        String exe = command.get(0);
        List<String> out = new ArrayList<>(command);
        if (JavaServerEnvironment.isLauncherName(exe) && isLauncher.test(exe)) {
            out.add(1, "--jvm-arg=" + agent);
        } else if (JavaServerEnvironment.isJavaExecutable(exe)) {
            out.add(1, agent);
        } else {
            return command;
        }
        return List.copyOf(out);
    }
}
