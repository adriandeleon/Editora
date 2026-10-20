package com.editora.install;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * What a complete install of the Maven-aware pom.xml server looks like on disk, read from its launch command.
 *
 * <p>The server runs as {@code java -cp "<dir>/*" org.eclipse.lemminx.XMLServerLauncher}, so "the command's
 * executable resolves" only says that {@code java} is on PATH: an emptied folder, or the upstream bundle without
 * the jars it leaves out ({@link InstallCatalog#LEMMINX_MAVEN_EXTRA_JARS}), looked installed in Settings and
 * Doctor while the server could not answer a request (Maven review C1, C3). This checks the folder itself:
 * lemminx core, the Maven extension and the three extra jars must all be there. Pure (file lookups only).
 */
public final class MavenServerInstall {

    private MavenServerInstall() {}

    /**
     * The jars a complete install holds, as file-name prefixes so a version bump of any of them still counts:
     * lemminx core, lemminx-maven, and the three jars the upstream bundle omits.
     */
    public static final List<String> REQUIRED_JAR_PREFIXES =
            List.of("org.eclipse.lemminx-", "lemminx-maven-", "guava-", "commons-lang3-", "jsoup-");

    /**
     * The folder a {@code java -cp "<dir>/*" …} argv loads its jars from, or empty when {@code argv} is not that
     * shape (blank, no {@code -cp}/{@code -classpath}, a classpath that is not one wildcard folder). A command
     * of another shape — a user's own launcher script — is not this install and gets no folder check.
     */
    public static Optional<Path> classpathDir(List<String> argv) {
        if (argv == null || argv.size() < 3) {
            return Optional.empty();
        }
        for (int i = 1; i + 1 < argv.size(); i++) {
            String a = argv.get(i);
            if (a.equals("-cp") || a.equals("-classpath") || a.equals("--class-path")) {
                String cp = argv.get(i + 1).strip();
                if (cp.length() < 2 || !cp.endsWith("*") || severalEntries(cp)) {
                    return Optional.empty();
                }
                char sep = cp.charAt(cp.length() - 2);
                if (sep != '/' && sep != '\\') {
                    return Optional.empty();
                }
                String dir = cp.substring(0, cp.length() - 2);
                if (dir.isBlank()) {
                    return Optional.empty();
                }
                try {
                    return Optional.of(Path.of(dir));
                } catch (RuntimeException e) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Whether {@code cp} lists more than one classpath entry. The separator is {@code :} on POSIX, where a
     * Windows drive letter ({@code C:\…}) in a stored command must not read as two entries. Pure.
     */
    static boolean severalEntries(String cp) {
        String sep = java.io.File.pathSeparator;
        int at = cp.indexOf(sep);
        while (at >= 0) {
            boolean driveColon = ":".equals(sep) && at == 1 && Character.isLetter(cp.charAt(0));
            if (!driveColon) {
                return true;
            }
            at = cp.indexOf(sep, at + 1);
        }
        return false;
    }

    /**
     * The required jars {@code dir} lacks, named by their prefix ({@code guava-*.jar}): empty when the install is
     * complete, every one of them when {@code dir} is missing or unreadable.
     */
    public static List<String> missingJars(Path dir) {
        List<String> names = new ArrayList<>();
        if (dir != null && Files.isDirectory(dir)) {
            try (Stream<Path> list = Files.list(dir)) {
                list.map(p -> p.getFileName().toString()).forEach(names::add);
            } catch (IOException | java.io.UncheckedIOException e) {
                names.clear();
            }
        }
        List<String> missing = new ArrayList<>();
        for (String prefix : REQUIRED_JAR_PREFIXES) {
            boolean present = names.stream().anyMatch(n -> n.startsWith(prefix) && n.endsWith(".jar"));
            if (!present) {
                missing.add(prefix + "*.jar");
            }
        }
        return missing;
    }

    /** Whether {@code dir} holds every required jar. */
    public static boolean complete(Path dir) {
        return missingJars(dir).isEmpty();
    }

    /**
     * The required jars missing from the install {@code argv} launches: empty when {@code argv} is not a
     * {@code java -cp "<dir>/*"} command (nothing to check) or the folder is complete.
     */
    public static List<String> missingJars(List<String> argv) {
        return classpathDir(argv).map(MavenServerInstall::missingJars).orElse(List.of());
    }
}
