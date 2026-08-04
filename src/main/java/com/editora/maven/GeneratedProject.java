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

    /**
     * The fully-qualified name of a {@code public static void main} class under {@code projectDir}'s
     * {@code src/main/java}, or {@code null} if there is none.
     *
     * <p>Deterministic when several exist: shallowest path first, then alphabetical, so the same project
     * always yields the same configuration rather than depending on filesystem order. Test sources are not
     * searched — a runnable main in {@code src/test/java} is not what "Run" should mean.
     */
    public static String findMainClass(Path projectDir) {
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
                return fqn;
            }
        }
        return null;
    }

    private static String mainClassIn(Path file) {
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
