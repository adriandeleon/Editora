package com.editora.doctor;

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
     * The Run feature's JDK health: compact-source run needs JDK 25+ → OK; an older JDK runs other
     * languages but not Java → WARN; no parseable java at all → MISSING.
     */
    public static DoctorStatus javaRunStatus(int major) {
        if (major >= 25) {
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
