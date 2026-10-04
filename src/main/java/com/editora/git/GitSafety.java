package com.editora.git;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure rules that keep the Git commands Editora runs <em>on its own initiative</em> from executing code or
 * options chosen by the repository rather than by the user. Toolkit-free and unit-tested.
 *
 * <p><b>Background commands.</b> Status and the gutter diff run on every tab activation, so opening a file
 * from an extracted archive, a shared folder or a USB stick used to run whatever program that folder's
 * {@code .git/config} named in {@code core.fsmonitor}, {@code diff.external} or a {@code textconv} driver.
 * {@link #backgroundArgv} puts command-line config overrides (which outrank every config file) in front of
 * each such command and adds {@code --no-ext-diff} / {@code --no-textconv} to the diff-producing ones.
 * {@code diff.external} is disabled with the flag rather than an empty {@code -c diff.external=} because Git
 * treats the empty value as a program to run and fails. {@code credential.helper} is left alone.
 *
 * <p><b>No on-demand fetch.</b> A partial-clone ("promisor") repository fetches a missing object the first
 * time a command needs it, through the transport program its own config names
 * ({@code remote.<name>.uploadpack}, {@code core.sshCommand}). A diff, {@code show} or blame of a file whose
 * blob is absent would therefore run that program. {@code remote.<name>.*} cannot be overridden wholesale
 * with {@code -c}, so {@link #BACKGROUND_ENV} sets {@code GIT_NO_LAZY_FETCH=1} instead: the read fails
 * cleanly ("lazy fetching disabled") and the object arrives with the user's next fetch, pull or checkout.
 * Git older than the release that introduced the variable (2.45, and the 2.39.4–2.44.1 maintenance
 * releases) ignores it.
 *
 * <p><b>User commands</b> (commit, checkout, push, pull, …) are <em>not</em> hardened: the user asked for
 * them in this repository, and their own hooks must run. That split is explicit in {@code GitService}.
 *
 * <p><b>Not covered:</b> {@code filter.<name>.clean}/{@code process} drivers selected through
 * {@code .gitattributes} still run when Git has to re-hash a modified file. They cannot be switched off
 * without also breaking legitimate Git LFS and end-of-line conversion, so they need a trust decision rather
 * than a config override.
 *
 * <p><b>Revisions.</b> Tag and branch names come from repository data. A ref whose name starts with
 * {@code -} would be parsed as an option ({@code --output=…} overwrites a file), so {@link #isSafeRevision}
 * rejects it outright and, where the installed Git understands it (2.24+), {@code --end-of-options} is passed
 * as well.
 */
public final class GitSafety {

    private GitSafety() {}

    /** The marker after which Git treats every argument as a revision or path, never an option. */
    public static final String END_OF_OPTIONS = "--end-of-options";

    private static final Set<String> DIFF_COMMANDS = Set.of("diff", "show", "log", "diff-tree", "diff-index");
    private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)");

    /** {@code -c} overrides for the current platform; see the class comment. */
    static final List<String> BACKGROUND_CONFIG = backgroundConfig(
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));

    /**
     * Environment of every background command: no optional index lock, no terminal prompt, no pager, and no
     * on-demand object fetch from a promisor remote (see the class comment).
     */
    static final Map<String, String> BACKGROUND_ENV =
            Map.of("GIT_OPTIONAL_LOCKS", "0", "GIT_TERMINAL_PROMPT", "0", "GIT_PAGER", "cat", "GIT_NO_LAZY_FETCH", "1");

    /** {@code -c key=value} pairs that neutralise repository-controlled program execution. */
    static List<String> backgroundConfig(boolean windows) {
        String nullDevice = windows ? "NUL" : "/dev/null";
        return List.of(
                "-c", "core.fsmonitor=false",
                "-c", "core.hooksPath=" + nullDevice,
                "-c", "core.pager=cat",
                "-c", "log.showSignature=false",
                "-c", "protocol.ext.allow=never");
    }

    /** {@code gitCommand} + the config overrides + {@code args}, with diff commands made driver-free. */
    static List<String> backgroundArgv(List<String> gitCommand, String... args) {
        List<String> argv = new ArrayList<>(gitCommand.size() + BACKGROUND_CONFIG.size() + args.length + 2);
        argv.addAll(gitCommand);
        argv.addAll(BACKGROUND_CONFIG);
        boolean subcommandSeen = false;
        for (String arg : args) {
            argv.add(arg);
            if (subcommandSeen || arg.startsWith("-")) {
                continue;
            }
            subcommandSeen = true;
            if (DIFF_COMMANDS.contains(arg)) {
                argv.add("--no-ext-diff");
                argv.add("--no-textconv");
            } else if ("blame".equals(arg)) {
                argv.add("--no-textconv");
            }
        }
        return argv;
    }

    /**
     * Whether {@code revision} (a ref name, a hash, or a {@code <rev>:<path>} blob spec) can be handed to Git
     * as a positional argument. Rejects blank values, anything starting with {@code -}, and control
     * characters — none of which {@code git check-ref-format} accepts in a name a porcelain command created.
     */
    public static boolean isSafeRevision(String revision) {
        if (revision == null || revision.isBlank() || revision.charAt(0) == '-') {
            return false;
        }
        for (int i = 0; i < revision.length(); i++) {
            char c = revision.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return false;
            }
        }
        return true;
    }

    /** Whether {@code git --version} output names a release with {@code --end-of-options} (2.24 or later). */
    static boolean supportsEndOfOptions(String versionOutput) {
        if (versionOutput == null) {
            return false;
        }
        Matcher m = VERSION.matcher(versionOutput);
        if (!m.find()) {
            return false;
        }
        try {
            int major = Integer.parseInt(m.group(1));
            int minor = Integer.parseInt(m.group(2));
            return major > 2 || (major == 2 && minor >= 24);
        } catch (NumberFormatException tooLong) {
            return false;
        }
    }

    /** {@code [--end-of-options] revisions…} — the marker only when the installed Git supports it. */
    static List<String> revisionArgs(boolean endOfOptions, String... revisions) {
        List<String> out = new ArrayList<>(revisions.length + 1);
        if (endOfOptions) {
            out.add(END_OF_OPTIONS);
        }
        out.addAll(List.of(revisions));
        return out;
    }
}
