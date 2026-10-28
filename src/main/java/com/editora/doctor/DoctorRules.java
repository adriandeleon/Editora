package com.editora.doctor;

import com.editora.install.VersionFloor;
import org.jspecify.annotations.Nullable;

/**
 * Pure classification rules for probe results — the decisions that turn raw exit codes / parsed values
 * into a {@link DoctorStatus}. Kept toolkit- and process-free so they are trivially unit-tested; the
 * subprocess side lives in {@link DoctorProbes}.
 */
public final class DoctorRules {

    private DoctorRules() {}

    /**
     * Whether a {@code --version} probe proves the tool present. For a <b>single-token</b> command any
     * clean launch counts ({@code exit != -1}, the runner's failed-to-start sentinel — a tool that rejects
     * the flag is still installed). For a <b>multi-token wrapper</b> ({@code npx -y <pkg>}) that inverts:
     * the wrapper itself always launches, so only a successful exit proves the wrapped package — the same
     * rule {@code mermaid/Mermaid.detect} uses for maid.
     */
    public static boolean presentFrom(int exit, boolean wrapper) {
        return wrapper ? exit == 0 : exit != -1;
    }

    /** gh's two-step health: absent → MISSING; present but unauthenticated → WARN; both → OK. */
    public static DoctorStatus ghStatus(boolean found, boolean authenticated) {
        if (!found) {
            return DoctorStatus.MISSING;
        }
        return authenticated ? DoctorStatus.OK : DoctorStatus.WARN;
    }

    /**
     * The Run feature's JDK health: compact-source run needs {@link VersionFloor#JAVA_RUN} (JDK 25) → OK; an older
     * JDK runs other languages but not Java → WARN; no parseable java at all → MISSING.
     */
    public static DoctorStatus javaRunStatus(int major) {
        if (major >= VersionFloor.JAVA_RUN.major()) {
            return DoctorStatus.OK;
        }
        return major >= 1 ? DoctorStatus.WARN : DoctorStatus.MISSING;
    }

    /**
     * What blocks Python debugging, or {@code ""} when nothing does. The debugger launches debugpy through
     * the configured interpreter (a located debugpy bundle only goes on its {@code PYTHONPATH}), so health
     * is the interpreter importing it — a located bundle alone proves nothing. When the import fails, the
     * missing piece is the interpreter itself if it won't launch, else debugpy.
     */
    public static String debugpyBlocker(boolean importable, boolean interpreterRuns, String interpreter) {
        if (importable) {
            return "";
        }
        return interpreterRuns ? "debugpy" : interpreter;
    }

    /** The first non-blank line of {@code out}, else of {@code err}, else {@code ""} — the version line. */
    public static String firstLine(String out, @Nullable String err) {
        String line = firstNonBlankLine(out);
        return line.isEmpty() ? firstNonBlankLine(err) : line;
    }

    private static String firstNonBlankLine(@Nullable String text) {
        if (text == null) {
            return "";
        }
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) {
                return line.strip();
            }
        }
        return "";
    }

    /**
     * {@code name version} from the npm {@code package.json} of the package a launcher belongs to — for a
     * server that answers {@code --version} with an error, like {@code pyright-langserver}. The launcher's real
     * path is followed (npm links {@code bin/pyright-langserver} into the package) and up to three folders
     * above it are looked at. "" when there is none.
     */
    public static String npmPackageVersion(java.nio.file.Path launcher) {
        try {
            java.nio.file.Path dir = launcher.toRealPath().getParent();
            for (int i = 0; i < 3 && dir != null; i++, dir = dir.getParent()) {
                java.nio.file.Path pkg = dir.resolve("package.json");
                if (java.nio.file.Files.isRegularFile(pkg)) {
                    return packageJsonVersion(java.nio.file.Files.readString(pkg));
                }
            }
        } catch (java.io.IOException | RuntimeException e) {
            // unreadable: no version, the path alone is still shown
        }
        return "";
    }

    /**
     * The version in {@code named}, what {@link #npmPackageVersion} said ({@code "bash-language-server 5.8.1"}), when
     * it names {@code pkg}; else null. Pure.
     */
    public static @Nullable String versionOf(@Nullable String named, @Nullable String pkg) {
        if (named == null || pkg == null || !named.startsWith(pkg + " ")) {
            return null;
        }
        String version = named.substring(pkg.length() + 1).strip();
        return version.isEmpty() ? null : version;
    }

    /**
     * The Node.js {@code launcher} (its real path) is run by when it is a script for one — {@code node} for npm's
     * {@code #!/usr/bin/env node}, the path for {@code #!/usr/bin/node} — else null: a binary, a Windows {@code .cmd}
     * shim, another interpreter, anything unreadable. Such a launcher cannot start where that {@code node} is not
     * found (Bash review A3). Reads the first line only.
     */
    public static @Nullable String launcherNode(java.nio.file.Path launcher) {
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(launcher.toRealPath())) {
            byte[] head = in.readNBytes(160);
            int end = 0;
            while (end < head.length && head[end] != '\n' && head[end] != '\r') {
                end++;
            }
            return shebangNode(new String(head, 0, end, java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Pure: the Node.js a script's first line names as its interpreter — the bare name after {@code env}, else the
     * interpreter's path — or null when it names another one or none.
     */
    static @Nullable String shebangNode(@Nullable String firstLine) {
        if (firstLine == null || !firstLine.startsWith("#!")) {
            return null;
        }
        for (String word : firstLine.substring(2).strip().split("\\s+")) {
            String name = word.substring(word.lastIndexOf('/') + 1);
            if (name.equals("node") || name.equals("nodejs")) {
                return word;
            }
            if (!name.equals("env") && !name.startsWith("-")) {
                return null; // another interpreter (sh, python): not Node.js
            }
        }
        return null;
    }

    /** Pure: {@code "name version"} from a {@code package.json}'s text, or "" without a version. */
    static String packageJsonVersion(String json) {
        var name =
                java.util.regex.Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        var version = java.util.regex.Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(json);
        if (!version.find()) {
            return "";
        }
        return name.find() ? name.group(1) + " " + version.group(1) : version.group(1);
    }
}
