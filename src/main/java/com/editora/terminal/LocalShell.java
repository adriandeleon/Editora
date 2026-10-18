package com.editora.terminal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.editora.process.ProcessRunner;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;

/**
 * Chooses the shell to run and starts it attached to a pseudo-terminal.
 *
 * <p>The OS split here is not cosmetic: on Unix pty4j opens a real PTY, while on Windows it drives
 * ConPTY (or winpty on older builds), and the two disagree about what a "command" is.
 */
public final class LocalShell {

    private LocalShell() {}

    /** Terminal type advertised to the child. 256-color because the renderer supports it. */
    private static final String TERM = "xterm-256color";

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac");
    }

    /**
     * The command line for the user's shell.
     *
     * <p>Unix shells are started as <em>login</em> shells (-l). A GUI process inherits a stripped
     * PATH — on macOS roughly {@code /usr/bin:/bin:/usr/sbin:/sbin}, with no Homebrew and no
     * version-manager (nvm/asdf/rbenv) directories — so a non-login shell would leave the user
     * unable to run most of what they have installed. The login shell reads the profile that puts
     * those directories back.
     */
    public static List<String> shellCommand() {
        return shellCommand("");
    }

    /**
     * @param override an explicit shell path, or blank to use the user's own. An override that is
     *     not executable is ignored rather than failing the launch — a stale settings entry should
     *     not leave someone unable to open a terminal at all.
     */
    public static List<String> shellCommand(String override) {
        if (override != null && !override.isBlank()) {
            Path path = Path.of(override.trim());
            if (Files.isExecutable(path)) {
                return isWindows() ? List.of(path.toString()) : List.of(path.toString(), "-l");
            }
        }
        if (isWindows()) {
            String pwsh = findOnPath("pwsh.exe");
            if (pwsh != null) return List.of(pwsh, "-NoLogo");
            String powershell = findOnPath("powershell.exe");
            if (powershell != null) return List.of(powershell, "-NoLogo");
            String comspec = System.getenv("COMSPEC");
            return List.of(comspec != null && !comspec.isBlank() ? comspec : "cmd.exe");
        }
        String shell = System.getenv("SHELL");
        if (shell == null || shell.isBlank() || !Files.isExecutable(Path.of(shell))) {
            shell = isMac() ? "/bin/zsh" : "/bin/bash";
            if (!Files.isExecutable(Path.of(shell))) shell = "/bin/sh";
        }
        return List.of(shell, "-l");
    }

    /**
     * Where to start the shell.
     *
     * <p>A directory that is not there falls back to home rather than failing the launch: the
     * argument is usually supplied by another program — a file manager, an editor — and a stale one
     * should not mean no terminal.
     */
    static String workingDirectory(String requested) {
        if (requested != null && !requested.isBlank()) {
            Path path = Path.of(requested.trim());
            if (Files.isDirectory(path)) return path.toAbsolutePath().toString();
        }
        return System.getProperty("user.home");
    }

    private static String findOnPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) continue;
            Path p = Path.of(dir, exe);
            if (Files.isExecutable(p)) return p.toString();
        }
        return null;
    }

    /**
     * Environment for the child: Editora's user environment (the augmented PATH, the user's locale), plus the
     * terminal identity a shell and its prompt look for.
     */
    public static Map<String, String> environment() {
        Map<String, String> overrides = new HashMap<>();
        overrides.put("TERM", TERM);
        // Signals to shells and prompt frameworks that a full-featured terminal is present.
        overrides.put("COLORTERM", "truecolor");
        overrides.put("TERM_PROGRAM", "Editora");
        Map<String, String> env = ProcessRunner.applyUserEnv(new HashMap<>(System.getenv()), overrides);
        if (!isWindows()) {
            // Without a UTF-8 locale the child emits Latin-1 and every non-ASCII glyph is mojibake.
            // Only fill in when the user has expressed no preference of their own.
            if (env.get("LANG") == null && env.get("LC_ALL") == null) {
                env.put("LANG", "en_US.UTF-8");
            }
        }
        // Inherited from our own launch (and a launching terminal's size); meaningless to the child, and
        // COLUMNS/LINES would override what the pseudo-terminal reports.
        env.remove("_");
        env.remove("COLUMNS");
        env.remove("LINES");
        isolateForTests(env, System.getProperty(TEST_HOME));
        return env;
    }

    /**
     * System property naming a HOME for every shell started here, set by the build's test runs: a test shell then
     * reads no profile of the developer's and writes no history (it used to append its commands to their
     * {@code ~/.bash_history}).
     */
    static final String TEST_HOME = "editora.terminal.home";

    /** With a test {@code home}: that HOME (created), no history file, and none of the user's startup-file paths. */
    static void isolateForTests(Map<String, String> env, String home) {
        if (home == null || home.isBlank()) {
            return;
        }
        try {
            Files.createDirectories(Path.of(home));
        } catch (IOException | RuntimeException e) {
            // the shell falls back to / for a HOME that is not there
        }
        env.put("HOME", home);
        env.put("HISTFILE", "/dev/null");
        env.remove("ZDOTDIR");
        env.remove("ENV");
        env.remove("BASH_ENV");
    }

    /**
     * Starts {@code command} — the user's shell when empty — on a pseudo-terminal sized to {@code columns} x
     * {@code rows}, in {@code directory} (home when missing).
     *
     * <p>An explicit command replaces the shell rather than running inside it, so quitting it ends the terminal.
     * On Linux pty4j sets the size just after the child starts: a command that asks for it at once can read 0×0
     * (see {@code TerminalImageCheck}); a shell gets the real size with the first resize.
     */
    public static LocalPtyConnector start(List<String> command, String directory, int columns, int rows)
            throws IOException {
        return start(command, directory, columns, rows, null);
    }

    /**
     * As above, starting a bash or zsh login shell with Editora's shell integration from {@code integration} (see
     * {@link ShellIntegration}); any other command, or a null folder, starts as it is.
     */
    public static LocalPtyConnector start(
            List<String> command, String directory, int columns, int rows, Path integration) throws IOException {
        List<String> login = command == null || command.isEmpty() ? shellCommand() : command;
        Map<String, String> env = environment();
        List<String> argv = integrated(login, integration, env);
        PtyProcess process = new PtyProcessBuilder(argv.toArray(String[]::new))
                .setEnvironment(env)
                .setDirectory(workingDirectory(directory))
                .setInitialColumns(columns)
                .setInitialRows(rows)
                // false = a real PTY. Console mode is Windows' "attach to a console screen buffer"
                // path, which does not deliver the escape sequences the emulator needs.
                .setConsole(false)
                .setUseWinConPty(true)
                .setWindowsAnsiColorEnabled(true)
                .start();
        return new LocalPtyConnector(process, argv);
    }

    /**
     * Starts a program (Run in Terminal, a debugged program) on a pseudo-terminal: {@code argv} in {@code directory}
     * with {@code extraEnv} over the user's environment, a null value unsetting a variable. Quitting the program
     * ends the terminal. The executable is found on the user's augmented PATH, as Run finds it.
     */
    public static LocalPtyConnector startProgram(
            List<String> argv, String directory, int columns, int rows, Map<String, String> extraEnv)
            throws IOException {
        if (argv == null || argv.isEmpty()) {
            throw new IOException("no program to run");
        }
        Map<String, String> env = environment();
        if (extraEnv != null) {
            extraEnv.forEach((name, value) -> {
                if (value == null) {
                    env.remove(name);
                } else {
                    env.put(name, value);
                }
            });
        }
        List<String> command = com.editora.process.ProcessRunner.resolveExecutable(argv);
        PtyProcess process = new PtyProcessBuilder(command.toArray(String[]::new))
                .setEnvironment(env)
                .setDirectory(workingDirectory(directory))
                .setInitialColumns(columns)
                .setInitialRows(rows)
                .setConsole(false)
                .setUseWinConPty(true)
                .setWindowsAnsiColorEnabled(true)
                .start();
        return new LocalPtyConnector(process, command, true);
    }

    /**
     * {@code login} ({@code <shell> -l}) with the integration scripts when the shell is bash or zsh and they are
     * there: {@code bash --init-file <dir>/editora.bash -i}, or zsh with {@code ZDOTDIR=<dir>/zsh} and the user's own
     * {@code ZDOTDIR} kept in {@code USER_ZDOTDIR}. Anything else is returned as it is. Pure apart from reading
     * whether the scripts exist; adds to {@code env}.
     */
    static List<String> integrated(List<String> login, Path integration, Map<String, String> env) {
        if (integration == null || login.size() != 2 || !"-l".equals(login.get(1))) {
            return login;
        }
        String shell = login.get(0);
        String name = Path.of(shell).getFileName().toString();
        if (name.equals("bash") && Files.isReadable(integration.resolve("editora.bash"))) {
            return List.of(
                    shell, "--init-file", integration.resolve("editora.bash").toString(), "-i");
        }
        if (name.equals("zsh") && Files.isReadable(integration.resolve("zsh").resolve(".zshrc"))) {
            String own = env.get("ZDOTDIR");
            env.put("USER_ZDOTDIR", own == null || own.isBlank() ? env.getOrDefault("HOME", "") : own);
            env.put("ZDOTDIR", integration.resolve("zsh").toString());
        }
        return login;
    }
}
