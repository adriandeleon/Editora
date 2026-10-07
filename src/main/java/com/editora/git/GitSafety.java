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

    /**
     * Global option that makes every pathspec a literal path. A file name is data: without it
     * {@code a[1].txt} or a Next.js {@code [id].tsx} is a glob that also matches {@code a1.txt} / {@code i.tsx},
     * so the gutter diff, blame and file history of one file picked up its neighbours.
     */
    public static final String LITERAL_PATHSPECS = "--literal-pathspecs";

    private static final Set<String> DIFF_COMMANDS = Set.of("diff", "show", "log", "diff-tree", "diff-index");
    private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)");

    /** {@code -c} overrides for the current platform; see the class comment. */
    static final List<String> BACKGROUND_CONFIG = backgroundConfig(
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));

    /**
     * The variables that bind a git process to one particular repository — what
     * {@code git rev-parse --local-env-vars} prints. Git itself clears them before it runs a command in
     * another repository; Editora must do the same for every git child. Launched from a shell that exports
     * {@code GIT_DIR}/{@code GIT_WORK_TREE} (a bare-repository dotfiles setup, a shell started by a hook),
     * every folder otherwise resolved to that one repository, and an inherited {@code GIT_INDEX_FILE} pointed
     * every status at a foreign index.
     */
    static final List<String> REPO_LOCAL_ENV = List.of(
            "GIT_ALTERNATE_OBJECT_DIRECTORIES",
            "GIT_CONFIG",
            "GIT_CONFIG_PARAMETERS",
            "GIT_CONFIG_COUNT",
            "GIT_OBJECT_DIRECTORY",
            "GIT_DIR",
            "GIT_WORK_TREE",
            "GIT_IMPLICIT_WORK_TREE",
            "GIT_GRAFT_FILE",
            "GIT_INDEX_FILE",
            "GIT_NO_REPLACE_OBJECTS",
            "GIT_REPLACE_REF_BASE",
            "GIT_PREFIX",
            "GIT_SHALLOW_FILE",
            "GIT_COMMON_DIR");

    /**
     * Adds a {@code null} entry for each of {@link #REPO_LOCAL_ENV} to {@code env}: the process runner reads a
     * null value as "remove this variable from the child's environment".
     */
    static Map<String, String> withoutRepoLocalEnv(Map<String, String> env) {
        for (String name : REPO_LOCAL_ENV) {
            env.put(name, null);
        }
        return env;
    }

    /**
     * Environment of every background command: no optional index lock, no terminal prompt, no pager, and no
     * on-demand object fetch from a promisor remote (see the class comment).
     */
    static final Map<String, String> BACKGROUND_ENV = backgroundEnv();

    private static Map<String, String> backgroundEnv() {
        Map<String, String> env = withoutRepoLocalEnv(new java.util.LinkedHashMap<>());
        env.put("GIT_OPTIONAL_LOCKS", "0");
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GIT_PAGER", "cat");
        env.put("GIT_NO_LAZY_FETCH", "1");
        return java.util.Collections.unmodifiableMap(env);
    }

    /**
     * Environment additions for a <em>user-initiated</em> command, given the environment Editora inherited.
     *
     * <p>These commands run the repository's hooks, which are the user's own programs, so they keep the
     * user's locale: under {@code LC_ALL=C} a JVM-based hook (Spotless, google-java-format, a Gradle task)
     * decodes file names as ASCII and cannot open {@code src/año/…}, failing a commit that works in a
     * terminal. Only the <em>message</em> language is pinned ({@code LC_MESSAGES=C}, {@code LANGUAGE=C}), so
     * Git's replies stay the English text the UI recognises. An inherited {@code LC_ALL} would override
     * {@code LC_MESSAGES}; it is blanked (an empty {@code LC_ALL} counts as unset) and its value carried in
     * {@code LC_CTYPE}, which is the category that decides how file names and text are decoded.
     *
     * <p>Git's repository-binding variables are removed as for a background read ({@link #REPO_LOCAL_ENV}).
     */
    static Map<String, String> userEnv(Map<String, String> inherited) {
        Map<String, String> env = withoutRepoLocalEnv(new java.util.LinkedHashMap<>());
        env.put("GIT_OPTIONAL_LOCKS", "0");
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("COLUMNS", "1000");
        env.put("LC_MESSAGES", "C");
        env.put("LANGUAGE", "C");
        String all = inherited == null ? null : inherited.get("LC_ALL");
        if (all != null && !all.isEmpty()) {
            env.put("LC_ALL", "");
            env.put("LC_CTYPE", all);
        }
        return java.util.Collections.unmodifiableMap(env);
    }

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

    /**
     * Whether a {@code <rev>:<path>} blob spec (or a bare revision) can be handed to {@code git show}. The
     * revision half follows {@link #isSafeRevision}; the path half is a file name, where a TAB or any other
     * control character is legal and must not make a tracked file "not found". Only NUL, which cannot be
     * passed in an argument at all, is refused there.
     */
    public static boolean isSafeBlobSpec(String spec) {
        if (spec == null || spec.isBlank() || spec.charAt(0) == '-' || spec.indexOf('\0') >= 0) {
            return false;
        }
        int colon = spec.indexOf(':');
        if (colon < 0) {
            return isSafeRevision(spec);
        }
        // ":path" and ":<stage>:path" name the index: everything after the first colon is stage and path.
        return colon == 0 || isSafeRevision(spec.substring(0, colon));
    }

    /** Whether {@code git --version} output names a release with {@code --end-of-options} (2.24 or later). */
    static boolean supportsEndOfOptions(String versionOutput) {
        return versionAtLeast(versionOutput, 2, 24);
    }

    /** Whether {@code git --version} output names release {@code major.minor} or a later one. */
    public static boolean versionAtLeast(String versionOutput, int major, int minor) {
        if (versionOutput == null) {
            return false;
        }
        Matcher m = VERSION.matcher(versionOutput);
        if (!m.find()) {
            return false;
        }
        try {
            int foundMajor = Integer.parseInt(m.group(1));
            int foundMinor = Integer.parseInt(m.group(2));
            return foundMajor > major || (foundMajor == major && foundMinor >= minor);
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
