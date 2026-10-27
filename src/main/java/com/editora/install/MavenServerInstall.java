package com.editora.install;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

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
    // --- Editora's own copy (XML/pom review 2, A3) ---------------------------------------------------------------

    /** Where Editora installs the pom.xml server under {@code configDir}. */
    public static Path folder(Path configDir) {
        return configDir.resolve(InstallCatalog.LEMMINX_MAVEN_DIR);
    }

    /**
     * The launch command of Editora's own complete pom.xml server under {@code configDir}
     * ({@code java -cp "<dir>/*" …}, a bare {@code java} the Maven JDK then replaces), or null when it has none.
     */
    public static @Nullable String ownCommand(@Nullable Path configDir) {
        if (configDir == null) {
            return null;
        }
        Path dir = folder(configDir);
        return Files.isDirectory(dir) && complete(dir)
                ? InstallCatalog.jvmClasspathCommand(dir, InstallCatalog.LEMMINX_MAIN_CLASS)
                : null;
    }

    /**
     * The pom.xml server command that runs: {@code configured} as it is, unless it is blank — then Editora's own
     * complete bundle, when there is one. The installer used to store its absolute {@code java -cp} command as an
     * override of a blank default, so "Reset to default" (or settings imported from another machine) disconnected
     * a bundle still sitting in the config folder and only a second download brought it back (review 2, A3).
     */
    public static String effectiveCommand(@Nullable String configured, @Nullable Path configDir) {
        String raw = configured == null ? "" : configured.strip();
        if (!raw.isEmpty()) {
            return raw;
        }
        String own = ownCommand(configDir);
        return own == null ? raw : own;
    }

    /**
     * Whether {@code configured} is not the user's own choice of pom server: blank, or a {@code java -cp} into
     * Editora's own folder (what an earlier install wrote). Install replaces such a command, so the install banner
     * may offer it; any other command is the user's, and a missing one leads to Settings instead.
     */
    public static boolean editorasOwn(@Nullable String configured, @Nullable Path configDir) {
        String raw = configured == null ? "" : configured.strip();
        if (raw.isEmpty()) {
            return true;
        }
        if (configDir == null) {
            return false;
        }
        return classpathDir(com.editora.run.ProgramArgs.commandLine(raw))
                .map(dir -> sameFolder(dir, folder(configDir)))
                .orElse(false);
    }

    /**
     * Whether an installer's command for the pom server is Editora's own bundle (a {@code java -cp} into a
     * {@code plugins/lsp/maven} folder), which the setting need not hold. Pure.
     */
    public static boolean isOwnInstall(@Nullable String installedCommand) {
        if (installedCommand == null || installedCommand.isBlank()) {
            return false;
        }
        return classpathDir(com.editora.run.ProgramArgs.commandLine(installedCommand))
                .map(dir -> {
                    try {
                        return dir.endsWith(Path.of(InstallCatalog.LEMMINX_MAVEN_DIR));
                    } catch (RuntimeException e) {
                        return false;
                    }
                })
                .orElse(false);
    }

    private static boolean sameFolder(Path a, Path b) {
        try {
            return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
        } catch (RuntimeException e) {
            return false;
        }
    }
}
