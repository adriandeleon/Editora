package com.editora.doctor;

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

    /** The first non-blank line of {@code out}, else of {@code err}, else {@code ""} — the version line. */
    public static String firstLine(String out, String err) {
        String line = firstNonBlankLine(out);
        return line.isEmpty() ? firstNonBlankLine(err) : line;
    }

    private static String firstNonBlankLine(String text) {
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
}
