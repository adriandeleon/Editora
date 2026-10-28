package com.editora.lsp;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The start backstop for the plain XML server (XML/pom review 3, A21): an {@code xml} server whose command would load
 * lemminx-maven is not started.
 *
 * <p>lemminx-maven is a ServiceLoader extension: any lemminx whose classpath holds its jar loads it, and it then
 * builds every pom's Maven model — fetching the parents, BOMs, plugins and extensions the pom names and running
 * their code. Folder trust keeps an untrusted pom on the {@code xml} server for exactly that reason, so the {@code
 * xml} server must be Maven-free whatever its command came from: Editora's Java fallback, an old stored command, or
 * a user's own. Reads the command and, for a {@code <dir>/*} classpath entry here, that folder's file names.
 */
public final class LemminxMavenGuard {

    private LemminxMavenGuard() {}

    /**
     * Whether {@code argv} would load lemminx-maven: a token naming it ({@code lemminx-maven-0.12.0.jar}, a {@code
     * -jar} of it), or a classpath ({@code -cp}, {@code -classpath}, {@code --class-path}, {@code
     * -Djava.class.path=}) with a {@code <dir>/*} entry whose folder holds a {@code lemminx-maven*.jar}. Folders are
     * only listed when {@code local} (a remote command names the host's files).
     */
    public static boolean loadsLemminxMaven(List<String> argv, boolean local) {
        if (argv == null) {
            return false;
        }
        for (int i = 0; i < argv.size(); i++) {
            String arg = argv.get(i);
            if (arg == null) {
                continue;
            }
            if (namesLemminxMaven(arg)) {
                return true;
            }
            String cp = null;
            if ((arg.equals("-cp") || arg.equals("-classpath") || arg.equals("--class-path")) && i + 1 < argv.size()) {
                cp = argv.get(i + 1);
            } else if (arg.startsWith("--class-path=")) {
                cp = arg.substring("--class-path=".length());
            } else if (arg.startsWith("-Djava.class.path=")) {
                cp = arg.substring("-Djava.class.path=".length());
            }
            if (cp != null && local && wildcardHoldsLemminxMaven(cp)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code token} names lemminx-maven's jar or folder (case-insensitive). Pure. */
    static boolean namesLemminxMaven(String token) {
        return token.toLowerCase(Locale.ROOT).contains("lemminx-maven");
    }

    private static boolean wildcardHoldsLemminxMaven(String cp) {
        for (String entry : cp.split(Pattern.quote(File.pathSeparator))) {
            String e = entry.strip();
            if (!e.endsWith("*")) {
                continue;
            }
            String folder = e.substring(0, e.length() - 1);
            try {
                Path dir = folder.isEmpty() ? Path.of("") : Path.of(folder);
                if (Files.isDirectory(dir) && holdsLemminxMaven(dir)) {
                    return true;
                }
            } catch (InvalidPathException e2) {
                // not a folder this JVM could read either
            }
        }
        return false;
    }

    private static boolean holdsLemminxMaven(Path dir) {
        try (DirectoryStream<Path> jars = Files.newDirectoryStream(dir, "*.jar")) {
            for (Path jar : jars) {
                if (namesLemminxMaven(String.valueOf(jar.getFileName()))) {
                    return true;
                }
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
        return false;
    }
}
