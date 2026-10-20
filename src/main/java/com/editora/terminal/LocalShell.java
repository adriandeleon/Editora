package com.editora.terminal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.editora.config.PathDisplay;
import com.editora.process.ProcessRunner;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import org.jspecify.annotations.Nullable;

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
     * @param override the shell setting: a path or a name found on PATH, then any arguments ({@code /bin/bash
     *     --norc}, {@code pwsh -NoLogo}), or blank to use the user's own. With no arguments a Unix shell (and Git
     *     Bash or zsh on Windows) starts as a login shell. An override that cannot be run is ignored rather than
     *     failing the launch — a stale settings entry should not leave someone unable to open a terminal at all;
     *     {@link #overrideProblem} says why, for the status line.
     */
    public static List<String> shellCommand(String override) {
        List<String> chosen = overrideCommand(override);
        if (chosen != null) {
            return chosen;
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

    /** The override as a command line, or null when it is blank or cannot be run. */
    static @Nullable List<String> overrideCommand(String override) {
        if (override == null || override.isBlank()) {
            return null;
        }
        String[] words = override.strip().split("\\s+");
        String program = resolveProgram(words[0]);
        if (program == null) {
            return null;
        }
        List<String> command = new java.util.ArrayList<>();
        command.add(program);
        if (words.length > 1) {
            command.addAll(List.of(words).subList(1, words.length));
        } else {
            String name = PathDisplay.fileName(Path.of(program)).toLowerCase(Locale.ROOT);
            boolean loginShell = !isWindows() || name.equals("bash.exe") || name.equals("zsh.exe");
            if (loginShell) command.add("-l");
        }
        return List.copyOf(command);
    }

    /** A program named by a path, or by a name found on PATH; null when it is neither. */
    private static @Nullable String resolveProgram(String word) {
        try {
            Path path = Path.of(word);
            if (path.isAbsolute() || word.contains("/") || word.contains(java.io.File.separator)) {
                return Files.isExecutable(path) ? path.toString() : null;
            }
        } catch (RuntimeException notAPath) {
            return null;
        }
        String found = findOnPath(word);
        if (found == null && isWindows() && !word.toLowerCase(Locale.ROOT).endsWith(".exe")) {
            found = findOnPath(word + ".exe");
        }
        return found;
    }

    /** Why the shell setting is not used (null when it is, or is blank), for the status line. */
    public static @Nullable String overrideProblem(String override) {
        if (override == null || override.isBlank() || overrideCommand(override) != null) {
            return null;
        }
        return override.strip().split("\\s+")[0];
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

    private static @Nullable String findOnPath(String exe) {
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
        overrides.put("TERM_PROGRAM_VERSION", com.editora.AppInfo.VERSION);
        Map<String, String> env = ProcessRunner.applyUserEnv(new HashMap<>(System.getenv()), overrides);
        if (!isWindows()) {
            defaultLocale(env, isMac());
        }
        // Inherited from our own launch (and a launching terminal's size); meaningless to the child, and
        // COLUMNS/LINES would override what the pseudo-terminal reports.
        env.remove("_");
        env.remove("COLUMNS");
        env.remove("LINES");
        dropLaunchEnvironment(env);
        isolateForTests(env, System.getProperty(TEST_HOME));
        return env;
    }

    /**
     * Variables that describe where Editora itself was started, not the terminal it now provides: started from a
     * tmux pane, a shell's own tmux refused to nest and `tmux new-window` acted on the outer server; VTE_VERSION
     * made shells source vte.sh; the AppImage's variables told other AppImages and scripts they were inside
     * Editora's.
     */
    static final java.util.Set<String> LAUNCH_VARIABLES =
            java.util.Set.of("TMUX", "TMUX_PANE", "STY", "VTE_VERSION", "APPDIR", "APPIMAGE", "ARGV0", "OWD");

    /** Removes {@link #LAUNCH_VARIABLES} and the AppImage runtime's {@code APPRUN_*}. */
    static void dropLaunchEnvironment(Map<String, String> env) {
        env.keySet().removeIf(name -> LAUNCH_VARIABLES.contains(name) || name.startsWith("APPRUN_"));
    }

    /**
     * A UTF-8 locale when the user has expressed no preference ({@code LANG}, {@code LC_ALL} and {@code LC_CTYPE}
     * all unset): without one the child emits Latin-1 and every non-ASCII glyph is mojibake. {@code C.UTF-8} on
     * Linux, which every current glibc has whatever was generated (a forced {@code en_US.UTF-8} that was never
     * generated made every program print setlocale warnings); {@code en_US.UTF-8} on macOS, which has it built in.
     */
    static void defaultLocale(Map<String, String> env, boolean mac) {
        if (env.get("LANG") == null && env.get("LC_ALL") == null && env.get("LC_CTYPE") == null) {
            env.put("LANG", mac ? "en_US.UTF-8" : "C.UTF-8");
        }
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
            List<String> command, String directory, int columns, int rows, @Nullable Path integration)
            throws IOException {
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
     * ends the terminal. The executable is found on the user's augmented PATH, as Run finds it. A {@code directory}
     * that is not there fails the start.
     */
    public static LocalPtyConnector startProgram(
            List<String> argv, String directory, int columns, int rows, Map<String, String> extraEnv)
            throws IOException {
        if (argv == null || argv.isEmpty()) {
            throw new IOException("no program to run");
        }
        // Unlike a shell, a program does not fall back to the home folder: its relative paths would land there.
        // Console Run refuses a missing folder too.
        if (directory != null && !directory.isBlank() && !Files.isDirectory(Path.of(directory.trim()))) {
            throw new IOException("folder not found: " + directory);
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
    static List<String> integrated(List<String> login, @Nullable Path integration, Map<String, String> env) {
        // Not on Windows: Git Bash given as the shell starts as a login shell, but the scripts' paths are this
        // machine's, which it does not read as its own.
        if (integration == null || isWindows() || login.size() != 2 || !"-l".equals(login.get(1))) {
            return login;
        }
        String shell = login.get(0);
        String name = PathDisplay.fileName(Path.of(shell));
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
