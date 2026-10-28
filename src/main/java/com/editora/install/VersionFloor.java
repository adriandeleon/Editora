package com.editora.install;

import org.jspecify.annotations.Nullable;

/**
 * The oldest version of each external tool Editora works with, all in one table: the tools it finds on the
 * machine rather than installs. A tool older than its floor still runs; the feature named here does not, and
 * Doctor says so. The exact versions Editora installs itself are {@link PinnedVersions}. Pure.
 *
 * <p>To add a floor: one constant here, then compare with {@link #metBy(String)} where the tool's version is
 * read, and let Doctor's row for the tool warn with {@link #minimum()}.
 */
public enum VersionFloor {
    /** A JDK that runs a single-file source program with compact source (JEP 512): what Run does for Java. */
    JAVA_RUN("java", "25"),
    /** {@code gh pr checks --json}, which the CI-checks roll-up reads; everything else Editora calls is 2.16. */
    GH("gh", "2.50"),
    /** The Node.js that runs vscode-js-debug ({@code dapDebugServer.js}). */
    NODE_JS_DEBUG("node", "18"),
    /** HTTP Client JavaScript handlers: the permission model behind {@code --experimental-permission}. */
    NODE_HTTP_SCRIPTS("node", "20"),
    /** HTTP Client JavaScript handlers with the stable {@code --permission} flag. */
    NODE_HTTP_PERMISSION("node", "22.13"),
    /**
     * The Node.js that runs the bash-language-server Editora installs ({@code engines.node} of 5.8): its install
     * refuses an older one (Bash review A6).
     */
    NODE_BASH_LS("node", "20"),
    /**
     * The oldest bash-language-server Editora's shell support is tested with: 5.7 fixed an out-of-memory crash on
     * cyclic symlinks in background analysis and bounded ShellCheck runs. An older copy found on the machine gets a
     * Doctor warning with Update (Bash review D4).
     */
    BASH_LANGUAGE_SERVER("bash-language-server", "5.7");

    private final String tool;
    private final String minimum;

    VersionFloor(String tool, String minimum) {
        this.tool = tool;
        this.minimum = minimum;
    }

    /** The command the floor is about ({@code java}, {@code gh}, {@code node}). */
    public String tool() {
        return tool;
    }

    /** The oldest version that works, as shown to the user ({@code 2.50}). */
    public String minimum() {
        return minimum;
    }

    /** The floor's major version ({@code 25} for Java). */
    public int major() {
        return part(0);
    }

    /** The floor's minor version, 0 when it names none. */
    public int minor() {
        return part(1);
    }

    /**
     * Whether the tool that printed {@code versionLine} is at least the floor. A line with no version in it (a
     * wrapper, a development build) counts as new enough: an unknown version must not switch a feature off.
     */
    public boolean metBy(@Nullable String versionLine) {
        String number = Versions.firstNumber(versionLine);
        return number == null || Versions.compare(number, minimum) >= 0;
    }

    /** Whether {@code {major, minor, …}} is at least the floor; a missing version counts as new enough. */
    public boolean metBy(int @Nullable [] version) {
        if (version == null || version.length == 0) {
            return true;
        }
        StringBuilder text = new StringBuilder();
        for (int part : version) {
            text.append(text.isEmpty() ? "" : ".").append(part);
        }
        return Versions.compare(text.toString(), minimum) >= 0;
    }

    private int part(int index) {
        String[] parts = minimum.split("\\.");
        return index < parts.length ? Integer.parseInt(parts[index]) : 0;
    }
}
