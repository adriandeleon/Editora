package com.editora.run;

import java.nio.file.Path;
import java.util.List;

/**
 * Picks the file a saved run configuration should be resolved against.
 *
 * <p>Launching a Java configuration goes through jdtls, and jdtls is keyed <b>per project root</b>: the file
 * handed to {@code resolveClasspath} only selects which language-server session answers. It does not have to
 * be the class being run, and it does not have to be the file on screen.
 *
 * <p>It used to be the active buffer, and the launch refused outright unless that buffer was a Java file — so
 * saving a configuration for {@code MyApp}, opening {@code README.md} and running it reported "needs a Java
 * file". That defeats the purpose of a <em>named</em> configuration, which should not care what you are
 * currently looking at.
 *
 * <p>So any open Java file in the right project will do. Preference order:
 *
 * <ol>
 *   <li>a Java file under the configuration's own working directory, when it sets one — with several projects
 *       open this is the only signal that says which one the configuration belongs to;
 *   <li>the active buffer, if it is a Java file — the common case, and keeps the previous behaviour exactly;
 *   <li>any other open Java file.
 * </ol>
 *
 * <p>Returns {@code null} only when no Java file is open at all, which is the one case the caller genuinely
 * cannot resolve — and it can then say so precisely instead of blaming the current tab.
 *
 * <p>Deliberately does not search the disk for a candidate. That would be I/O on the FX thread to cover a
 * case (a Java project open with no Java file anywhere in it) that barely occurs, and jdtls would not have
 * indexed the project in that state anyway.
 */
public final class RunConfigRouting {

    private RunConfigRouting() {}

    /**
     * @param openJavaFiles every open, local Java file, in tab order
     * @param activeJavaFile the active buffer's path when it is a local Java file, else null
     * @param workingDir the configuration's working directory, blank when it has none
     * @return the file to resolve against, or null when no Java file is open
     */
    public static Path pick(List<Path> openJavaFiles, Path activeJavaFile, String workingDir) {
        return pick(openJavaFiles, activeJavaFile, workingDir, p -> false);
    }

    /**
     * As above, but breaks ties towards a file the language server has <b>already opened</b>.
     *
     * <p>Only a tie-break: it never overrides the working-directory or active-file preferences above, because
     * those encode which project the configuration belongs to and {@code managed} does not. Where the choice
     * was otherwise arbitrary — "any other open Java file" — an already-open one avoids starting a second
     * server for an unrelated root just to answer one {@code resolveClasspath}.
     *
     * <p>Correctness does not depend on this. The caller opens whatever comes back on the server first (see
     * {@code LspCoordinator.ensureManaged}); this only makes the common case cheaper.
     *
     * @param managed whether a path is currently open on a language server
     */
    public static Path pick(
            List<Path> openJavaFiles,
            Path activeJavaFile,
            String workingDir,
            java.util.function.Predicate<Path> managed) {
        if (openJavaFiles == null || openJavaFiles.isEmpty()) {
            return activeJavaFile;
        }
        java.util.function.Predicate<Path> isManaged = managed == null ? p -> false : managed;
        if (workingDir != null && !workingDir.isBlank()) {
            Path dir = normalize(Path.of(workingDir));
            // The active file wins among equally-valid candidates inside the configuration's own project.
            if (activeJavaFile != null && under(activeJavaFile, dir)) {
                return activeJavaFile;
            }
            Path firstUnder = null;
            for (Path candidate : openJavaFiles) {
                if (under(candidate, dir)) {
                    if (isManaged.test(candidate)) {
                        return candidate;
                    }
                    if (firstUnder == null) {
                        firstUnder = candidate;
                    }
                }
            }
            if (firstUnder != null) {
                return firstUnder;
            }
            // No open file under it: fall through rather than refuse. The working directory may simply be
            // somewhere else (an output folder, a sandbox), which says nothing about where the sources are.
        }
        if (activeJavaFile != null) {
            return activeJavaFile;
        }
        for (Path candidate : openJavaFiles) {
            if (isManaged.test(candidate)) {
                return candidate;
            }
        }
        return openJavaFiles.get(0);
    }

    private static boolean under(Path file, Path dir) {
        return normalize(file).startsWith(dir);
    }

    private static Path normalize(Path p) {
        return p.toAbsolutePath().normalize();
    }
}
