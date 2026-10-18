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
        return env;
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
        List<String> argv = command == null || command.isEmpty() ? shellCommand() : command;
        PtyProcess process = new PtyProcessBuilder(argv.toArray(String[]::new))
                .setEnvironment(environment())
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
}
