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

    /** A discovered entry point: its fully-qualified name and the source file that declares it. */
    public record MainClass(String fqn, Path file) {}

    /** The FQN only — see {@link #findMain(Path)} when the source file is also needed. */
    public static String findMainClass(Path projectDir) {
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
    public static MainClass findMain(Path projectDir) {
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
