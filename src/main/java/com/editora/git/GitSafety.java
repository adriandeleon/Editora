package com.editora.git;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

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

    private static final Set<String> DIFF_COMMANDS =
            Set.of("diff", "show", "log", "diff-tree", "diff-index", "format-patch");
    private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)");

    /** {@code -c} overrides for the current platform; see the class comment. */
    static final List<String> BACKGROUND_CONFIG = backgroundConfig(com.editora.Os.isWindows());

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
        // No command Editora runs wants an editor: a commit gets its message with -m, and continuing a
        // rebase or concluding a merge keeps the prepared one. Left to git, those two open $EDITOR — with no
        // terminal a vi simply waits, holding the lane until the mutation ceiling. ":" is git's own "no
        // editor": the prepared message is used as it is. The environment variable outranks core.editor.
        env.put("GIT_EDITOR", ":");
        // gpg hands GPG_TTY to the agent as the terminal to ask for a passphrase on: launched from a shell, a
        // commit with commit.gpgsign=true put pinentry-curses in that shell and waited there. Without it a GUI
        // pinentry asks as usual. A terminal-only pinentry is not stopped by this: gpg-agent then falls back
        // to the terminal it was itself started from, and may still ask there (the guide recommends a GUI
        // pinentry for signing from Editora).
        env.put("GPG_TTY", null);
        String all = inherited == null ? null : inherited.get("LC_ALL");
        if (all != null && !all.isEmpty()) {
            env.put("LC_ALL", "");
            env.put("LC_CTYPE", all);
        }
        return java.util.Collections.unmodifiableMap(env);
    }

    /**
     * Extra {@code -c} overrides of the automatic fetch: no askpass program from configuration (an empty
     * value is "none" to git, where an unset one falls through to {@code SSH_ASKPASS}), and a credential
     * helper that would open a window is told not to.
     */
    static final List<String> AUTO_FETCH_CONFIG = List.of("-c", "core.askPass=", "-c", "credential.interactive=false");

    /** ssh in batch mode where the {@code ssh} on the path is the one git runs (everywhere but Windows). */
    static final String BATCH_SSH = "ssh -o BatchMode=yes";

    /**
     * Environment of the automatic background fetch: a background read's, plus everything that keeps a
     * command nobody asked for from asking for anything. {@code GIT_ASKPASS} and {@code SSH_ASKPASS} are set
     * to the empty string (git and ssh both read that as "no askpass program"), {@code SSH_ASKPASS_REQUIRE}
     * to {@code never} (OpenSSH 8.4+), and Git Credential Manager is told not to interact.
     *
     * <p>ssh itself is put in batch mode — no passphrase or host-key question, it fails instead — through
     * {@code GIT_SSH_COMMAND} ({@code batchSsh}, from {@link #batchSshCommand}; null: none can be named), but
     * only when the user has not chosen an ssh command of their own: {@code GIT_SSH_COMMAND} outranks
     * {@code core.sshCommand}, so setting it would replace theirs. {@code userSshCommand} says the repository
     * or the user's configuration has one; an exported {@code GIT_SSH_COMMAND} or {@code GIT_SSH} is read
     * from {@code inherited}. Pure.
     */
    static Map<String, String> autoFetchEnv(
            Map<String, String> inherited, boolean userSshCommand, @Nullable String batchSsh) {
        Map<String, String> env = new java.util.LinkedHashMap<>(BACKGROUND_ENV);
        env.put("GIT_ASKPASS", "");
        env.put("SSH_ASKPASS", "");
        env.put("SSH_ASKPASS_REQUIRE", "never");
        env.put("GCM_INTERACTIVE", "never");
        if (batchSsh != null && !userSsh(inherited, userSshCommand)) {
            env.put("GIT_SSH_COMMAND", batchSsh);
        }
        return java.util.Collections.unmodifiableMap(env);
    }

    /** {@link #autoFetchEnv(Map, boolean, String)} with {@link #BATCH_SSH}. Pure. */
    static Map<String, String> autoFetchEnv(Map<String, String> inherited, boolean userSshCommand) {
        return autoFetchEnv(inherited, userSshCommand, BATCH_SSH);
    }

    /**
     * Environment of a clone, fetch, pull or push the <em>user</em> asked for: {@link #userEnv}, kept from
     * asking anything on a terminal. Editora has no terminal to answer ssh on: launched from a shell, a
     * host-key question or a key passphrase was asked in that shell and the push waited there, up to the
     * network ceiling.
     *
     * <ul>
     *   <li>When a graphical askpass program can ask instead ({@link #guiAskpassUsable}: {@code SSH_ASKPASS}
     *       is set and, on Linux, there is a display), ssh is told to use it rather than a terminal
     *       ({@code SSH_ASKPASS_REQUIRE=force}, OpenSSH 8.4+) and is <em>not</em> put in batch mode, which
     *       would also switch the askpass off: its window asks for the passphrase or the host key.</li>
     *   <li>Otherwise ssh is put in batch mode ({@code GIT_SSH_COMMAND}, {@code batchSsh}) unless the user
     *       chose an ssh command of their own — the same rule as {@link #autoFetchEnv}. It then fails at once,
     *       and git's stderr ("Host key verification failed.", "Permission denied (publickey)") is what the
     *       user is shown.</li>
     * </ul>
     *
     * A key in ssh-agent (or the macOS keychain) works either way, and HTTPS credential helpers are left as
     * they are. Pure.
     */
    static Map<String, String> userNetworkEnv(
            Map<String, String> userEnv,
            Map<String, String> inherited,
            boolean userSshCommand,
            @Nullable String batchSsh,
            boolean linux) {
        Map<String, String> env = new java.util.LinkedHashMap<>(userEnv);
        if (guiAskpassUsable(inherited, linux)) {
            env.put("SSH_ASKPASS_REQUIRE", "force");
        } else if (batchSsh != null && !userSsh(inherited, userSshCommand)) {
            env.put("GIT_SSH_COMMAND", batchSsh);
        }
        return java.util.Collections.unmodifiableMap(env);
    }

    /** {@link #userNetworkEnv(Map, Map, boolean, String, boolean)} with {@link #BATCH_SSH} on Linux. Pure. */
    static Map<String, String> userNetworkEnv(
            Map<String, String> userEnv, Map<String, String> inherited, boolean userSshCommand) {
        return userNetworkEnv(userEnv, inherited, userSshCommand, BATCH_SSH, true);
    }

    /**
     * Whether ssh can ask through a graphical askpass program: {@code SSH_ASKPASS} names one, the user has not
     * forbidden it ({@code SSH_ASKPASS_REQUIRE=never}), and — on Linux, where an askpass needs an X or
     * Wayland session — {@code DISPLAY} or {@code WAYLAND_DISPLAY} is set. On macOS and Windows a set
     * {@code SSH_ASKPASS} is taken as usable. Pure.
     */
    static boolean guiAskpassUsable(@Nullable Map<String, String> inherited, boolean linux) {
        if (inherited == null || !notBlank(inherited.get("SSH_ASKPASS"))) {
            return false;
        }
        if ("never".equals(strip(inherited.get("SSH_ASKPASS_REQUIRE")))) {
            return false;
        }
        return !linux || notBlank(inherited.get("DISPLAY")) || notBlank(inherited.get("WAYLAND_DISPLAY"));
    }

    /**
     * The {@code GIT_SSH_COMMAND} that puts ssh in batch mode, or null when Editora should not name one.
     * Outside Windows that is {@link #BATCH_SSH}. On Windows a bare {@code ssh} could be the system's
     * ({@code System32\OpenSSH}) rather than the one Git for Windows bundles and would otherwise run, with
     * its own agent and known hosts; so the bundled {@code ssh.exe} is named by its path, found from
     * {@code git --exec-path} ({@code <git>/mingw64/libexec/git-core} → {@code <git>/usr/bin/ssh.exe}). When it
     * cannot be found, null: ssh is left as git would run it, and {@code GIT_TERMINAL_PROMPT=0} with no
     * console is relied on. Pure; {@code isFile} answers whether a path is a file.
     */
    static @Nullable String batchSshCommand(
            boolean windows, @Nullable String gitExecPath, java.util.function.Predicate<String> isFile) {
        if (!windows) {
            return BATCH_SSH;
        }
        if (gitExecPath == null || gitExecPath.isBlank()) {
            return null;
        }
        String dir = gitExecPath.strip().replace('\\', '/');
        while (dir.endsWith("/")) {
            dir = dir.substring(0, dir.length() - 1);
        }
        // <git>/mingw64/libexec/git-core (64-bit), <git>/mingw32/libexec/git-core, or <git>/libexec/git-core.
        for (int up = 3; up >= 2; up--) {
            String base = dir;
            for (int i = 0; i < up && base.lastIndexOf('/') > 0; i++) {
                base = base.substring(0, base.lastIndexOf('/'));
            }
            String ssh = base + "/usr/bin/ssh.exe";
            if (isFile.test(ssh)) {
                // Run by git through its sh: double quotes keep a path with spaces ("Program Files") one word.
                return ssh.contains("\"") ? null : "\"" + ssh + "\" -o BatchMode=yes";
            }
        }
        return null;
    }

    private static boolean userSsh(@Nullable Map<String, String> inherited, boolean userSshCommand) {
        return userSshCommand
                || (inherited != null
                        && (notBlank(inherited.get("GIT_SSH_COMMAND")) || notBlank(inherited.get("GIT_SSH"))));
    }

    private static String strip(@Nullable String value) {
        return value == null ? "" : value.strip();
    }

    private static boolean notBlank(@Nullable String value) {
        return value != null && !value.isBlank();
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

    /**
     * Git's global options that take their value as the <em>next</em> argument ({@code -c core.quotePath=false},
     * {@code -C dir}). That value is not the subcommand.
     */
    private static final Set<String> GLOBAL_OPTIONS_WITH_VALUE =
            Set.of("-c", "-C", "--git-dir", "--work-tree", "--namespace", "--super-prefix", "--config-env");

    /**
     * Options that make the text of a diff the same whatever the repository's configuration says, for every diff
     * Editora parses or hands back to {@code git apply}: no colour codes ({@code color.diff=always}), and the
     * {@code a/} and {@code b/} prefixes ({@code diff.noprefix}, {@code diff.mnemonicPrefix} change them).
     */
    static final List<String> PARSEABLE_DIFF = List.of("--no-color", "--src-prefix=a/", "--dst-prefix=b/");

    /**
     * {@code gitCommand} + the config overrides + {@code args}, with diff commands made driver-free and their
     * output parseable ({@link #PARSEABLE_DIFF}). The flags go right after the subcommand, which is the first
     * argument that is neither a global option nor the value of one ({@link #GLOBAL_OPTIONS_WITH_VALUE}).
     */
    static List<String> backgroundArgv(List<String> gitCommand, String... args) {
        List<String> argv = new ArrayList<>(gitCommand.size() + BACKGROUND_CONFIG.size() + args.length + 5);
        argv.addAll(gitCommand);
        argv.addAll(BACKGROUND_CONFIG);
        int subcommand = subcommandIndex(args);
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            argv.add(arg);
            if (i != subcommand) {
                continue;
            }
            if (DIFF_COMMANDS.contains(arg)) {
                argv.add("--no-ext-diff");
                argv.add("--no-textconv");
                argv.addAll(PARSEABLE_DIFF);
            } else if ("blame".equals(arg)) {
                argv.add("--no-textconv");
            }
        }
        return argv;
    }

    /** The index of git's subcommand in {@code args} (after any global options and their values), or -1. Pure. */
    static int subcommandIndex(String... args) {
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (GLOBAL_OPTIONS_WITH_VALUE.contains(arg)) {
                i++; // its value
            } else if (!arg.startsWith("-")) {
                return i;
            }
        }
        return -1;
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
