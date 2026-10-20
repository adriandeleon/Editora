package com.editora.maven;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import com.editora.run.MainMethodScanner;
import org.jspecify.annotations.Nullable;

/**
 * Reads a freshly generated Maven project to decide what a ready-to-run configuration should launch.
 *
 * <p>The main class cannot be derived from the coordinates: {@code maven-archetype-quickstart} produces
 * {@code <package>.App}, but a webapp or plugin archetype produces <b>no</b> main class at all, and a
 * third-party archetype can name it anything. So this looks at what was actually written to disk rather than
 * guessing — and returns {@code null} when there is nothing to run, because a configuration that cannot
 * launch is worse than no configuration (it fails at the click, having promised otherwise).
 *
 * <p>Bounded on both counts: a generated tree is small, but this runs on the FX thread right after the
 * archetype finishes, and an archetype is third-party code that can write whatever it likes.
 */
public final class GeneratedProject {

    static final int MAX_FILES = 300;
    static final long MAX_FILE_BYTES = 256 * 1024;

    private GeneratedProject() {}

    /** A discovered entry point: its fully-qualified name and the source file that declares it. */
    public record MainClass(String fqn, Path file) {}

    /** The FQN only — see {@link #findMain(Path)} when the source file is also needed. */
    public static @Nullable String findMainClass(Path projectDir) {
        MainClass m = findMain(projectDir);
        return m == null ? null : m.fqn();
    }

    /**
     * The {@code public static void main} class under {@code projectDir}'s {@code src/main/java}, with the
     * file that declares it, or {@code null} if there is none.
     *
     * <p>Deterministic when several exist: shallowest path first, then alphabetical, so the same project
     * always yields the same configuration rather than depending on filesystem order. Test sources are not
     * searched — a runnable main in {@code src/test/java} is not what "Run" should mean.
     */
    public static @Nullable MainClass findMain(Path projectDir) {
        if (projectDir == null) {
            return null;
        }
        Path sources = projectDir.resolve("src").resolve("main").resolve("java");
        if (!Files.isDirectory(sources)) {
            return null;
        }
        List<Path> candidates = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(sources)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .limit(MAX_FILES)
                    .forEach(candidates::add);
        } catch (IOException e) {
            return null;
        }
        candidates.sort(Comparator.<Path>comparingInt(Path::getNameCount).thenComparing(Path::toString));
        for (Path file : candidates) {
            String fqn = mainClassIn(file);
            if (fqn != null) {
                return new MainClass(fqn, file);
            }
        }
        return null;
    }

    /**
     * The Maven goal a generated project is meant to be <em>run</em> with, when its pom says so: {@code
     * javafx:run} for a pom that declares {@code org.openjfx:javafx-maven-plugin}, {@code spring-boot:run}
     * for {@code spring-boot-maven-plugin}; null for a plain main class.
     *
     * <p>Matters for what gets seeded: a JavaFX archetype's {@code App} launched as a plain classpath main
     * dies with "JavaFX runtime components are missing" — the module path the plugin sets up is the launch.
     * Spring Boot's main runs fine on the classpath, so the goal there is a hint, not a replacement.
     */
    public static @Nullable String mavenRunGoal(@Nullable String pom) {
        if (pom == null || pom.isBlank()) {
            return null;
        }
        for (PomEdits.VersionSite site : PomEdits.versionSites(pom)) {
            if (!site.isPlugin()) {
                continue;
            }
            if ("org.openjfx:javafx-maven-plugin".equals(site.ga())) {
                return "javafx:run";
            }
            if ("org.springframework.boot:spring-boot-maven-plugin".equals(site.ga())) {
                return "spring-boot:run";
            }
        }
        return null;
    }

    /** {@link #mavenRunGoal(String)} for the pom under {@code projectDir}; null when there is none to read. */
    public static @Nullable String mavenRunGoal(@Nullable Path projectDir) {
        if (projectDir == null) {
            return null;
        }
        try {
            Path pom = projectDir.resolve("pom.xml");
            if (!Files.isRegularFile(pom) || Files.size(pom) > MAX_FILE_BYTES) {
                return null;
            }
            return mavenRunGoal(Files.readString(pom, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Whether a plain {@code java} main-class launch of this project is known to fail (a JavaFX module app). */
    public static boolean needsMavenToRun(@Nullable String goal) {
        return "javafx:run".equals(goal);
    }

    private static @Nullable String mainClassIn(Path file) {
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                return null;
            }
            String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            List<MainMethodScanner.MainMethod> mains = MainMethodScanner.scan(source);
            return mains.isEmpty() ? null : mains.get(0).fqn();
        } catch (IOException | RuntimeException e) {
            return null; // an unreadable or undecodable file simply isn't the entry point
        }
    }
}
