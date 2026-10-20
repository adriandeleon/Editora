package com.editora.run;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

/**
 * The compiled jar of a Kotlin program that Run and Debug start ({@link KotlinRun}): where it is, whether it has
 * to be compiled first, and putting a finished compile in place. One jar per source file is kept — an edit
 * compiles a new one and {@link #finish} deletes the old — so the cache holds one program per file run.
 *
 * <p>Reads the source and lists the cache folder: small, local I/O.
 */
public final class KotlinBuild {

    private static final Logger LOG = Logger.getLogger(KotlinBuild.class.getName());

    private KotlinBuild() {}

    /**
     * @param jar the program's jar, there once {@link #finish} has run (or already)
     * @param partial where {@code compile} writes, renamed to {@code jar} when it succeeds
     * @param compile the {@code kotlinc} command, empty when {@code jar} is already this content's
     */
    public record Plan(Path jar, Path partial, List<String> compile) {}

    /**
     * The plan for running {@code source} as it is on disk, compiled by {@code kotlinc}, cached under
     * {@code configDir} (a temporary folder when that is null).
     */
    public static Plan plan(Path source, String kotlinc, @Nullable Path configDir) throws IOException {
        String content = Files.readString(source);
        Path cache = KotlinRun.cacheDir(configDir, Path.of(System.getProperty("java.io.tmpdir")));
        String name = KotlinRun.jarName(source.toAbsolutePath().normalize().toString(), content, kotlinc);
        Path jar = cache.resolve(name);
        Path partial = cache.resolve(KotlinRun.partialName(name));
        if (Files.isRegularFile(jar)) {
            return new Plan(jar, partial, List.of());
        }
        Files.createDirectories(cache);
        return new Plan(jar, partial, KotlinRun.compileArgv(kotlinc, source.toString(), partial.toString()));
    }

    /** Puts a successful compile's jar in place and deletes the older jars of the same source. */
    public static void finish(Plan plan) throws IOException {
        if (Files.isRegularFile(plan.partial())) {
            Files.move(plan.partial(), plan.jar(), StandardCopyOption.REPLACE_EXISTING);
        }
        if (!Files.isRegularFile(plan.jar())) {
            throw new IOException("kotlinc wrote no jar: " + plan.jar());
        }
        String current = plan.jar().getFileName().toString();
        try (Stream<Path> files = Files.list(plan.jar().getParent())) {
            for (Path p : files.toList()) {
                if (KotlinRun.isStaleJar(current, p.getFileName().toString())) {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        LOG.log(Level.FINE, "Could not delete " + p, e); // a running program's jar on Windows
                    }
                }
            }
        }
    }
}
