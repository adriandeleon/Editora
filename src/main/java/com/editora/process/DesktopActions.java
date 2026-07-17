package com.editora.process;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Desktop OS integration: reveal a file/folder in the native file manager, or open a terminal at a
 * folder. The per-OS argv construction is <em>pure</em> (the OS is injected), so it's unit-tested;
 * only {@link #reveal}/{@link #openTerminal} touch the real OS, launching the command <em>detached</em>
 * on a daemon thread (fire-and-forget) so the FX thread never blocks.
 *
 * <p>Launching reuses {@link ProcessRunner#resolveExecutable}/{@link ProcessRunner#applyStandardEnv} so
 * the augmented PATH applies (a Finder-launched {@code .app} can still find {@code xdg-open} etc.).
 */
public final class DesktopActions {

    public enum Os {
        MAC,
        LINUX,
        WINDOWS
    }

    /**
     * A desktop command to launch: its {@code argv}, plus an optional {@code workingDir} that is set on the
     * child process (via {@link ProcessBuilder#directory}) instead of being interpolated into any argument.
     *
     * <p>The distinction is a security boundary. On Windows the terminal is opened through {@code cmd.exe},
     * which parses its command line — so a folder path placed <em>in</em> that command line (the old
     * {@code cmd /k "cd /d " + dir}) lets a directory named with a shell metacharacter — {@code &}, {@code |},
     * {@code ^}, all legal in Windows folder names — run arbitrary commands when the user picks "Open Terminal
     * Here" on an untrusted repo. Routing the path through {@code directory()} hands it to CreateProcess's
     * working-directory parameter, which no shell parses, so there is nothing to inject into. {@code null}
     * means the child inherits the app's working directory.
     */
    public record Command(List<String> argv, Path workingDir) {}

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "desktop-actions");
        t.setDaemon(true);
        return t;
    });

    private DesktopActions() {}

    public static Os currentOs() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return Os.MAC;
        }
        if (os.contains("win")) {
            return Os.WINDOWS;
        }
        return Os.LINUX;
    }

    /**
     * The command that reveals {@code path} in the OS file manager — selected in its containing folder
     * where the platform supports it (macOS Finder, Windows Explorer). Linux's {@code xdg-open} can't
     * select a file, so it opens the containing folder ({@code parentDir}) for a file, or the folder
     * itself for a directory. Pure — unit-tested.
     *
     * @param isDir whether {@code path} is itself a directory (the caller already knows this)
     * @param parentDir {@code path}'s containing folder (used only by the Linux file case)
     */
    public static List<String> revealArgv(Os os, Path path, boolean isDir, Path parentDir) {
        String p = path.toString();
        return switch (os) {
            case MAC -> List.of("open", "-R", p);
            case WINDOWS -> isDir ? List.of("explorer", p) : List.of("explorer", "/select," + p);
            case LINUX -> List.of("xdg-open", (isDir ? path : parentDir).toString());
        };
    }

    /**
     * The command that opens a terminal at {@code dir}. Pure.
     *
     * <p>macOS and Linux exec their launcher directly (no shell), so passing {@code dir} as an argv element is
     * safe — a metacharacter in it is just a literal character. Windows must open the terminal through
     * {@code cmd.exe}, so {@code dir} is <em>not</em> put in the command line: the argv is fixed literals and
     * the folder is delivered as the child's {@link Command#workingDir}. A new console still needs
     * {@code start} (a launched-from-a-windowless-process {@code cmd} would get no visible window); the new
     * window inherits its parent's working directory, which is why {@code directory(dir)} is enough and no
     * {@code cd} / {@code start /D <path>} appears anywhere a shell could parse it. See {@link Command}.
     */
    public static Command terminalCommand(Os os, Path dir) {
        String d = dir.toString();
        return switch (os) {
            case MAC -> new Command(List.of("open", "-a", "Terminal", d), null);
            case WINDOWS -> new Command(List.of("cmd", "/c", "start", "", "cmd", "/k"), dir);
            case LINUX -> new Command(List.of("x-terminal-emulator", "--working-directory=" + d), null);
        };
    }

    /** {@code path}'s containing folder: {@code path} itself when it's a directory, else its parent. */
    public static Path containingDir(Path path, boolean isDir) {
        if (isDir) {
            return path;
        }
        Path parent = path.getParent();
        return parent != null ? parent : path;
    }

    /**
     * Reveals {@code path} in the file manager; on failure calls {@code onError} (off the FX thread).
     *
     * <p>Every {@code revealArgv} launcher ({@code open}/{@code explorer}/{@code xdg-open}) is exec'd
     * directly via {@link ProcessBuilder} — <em>not</em> through a shell — so the path is a single argv
     * element and a metacharacter in it is inert. In particular the Windows {@code explorer /select,<path>}
     * is not a command-injection vector: {@code explorer.exe} parses its own arguments and runs no subcommand.
     */
    public static void reveal(Path path, boolean isDir, Consumer<String> onError) {
        launch(revealArgv(currentOs(), path, isDir, containingDir(path, isDir)), null, onError);
    }

    /** Opens a terminal at {@code path}'s containing folder; calls {@code onError} (off the FX thread). */
    public static void openTerminal(Path path, boolean isDir, Consumer<String> onError) {
        Command cmd = terminalCommand(currentOs(), containingDir(path, isDir));
        launch(cmd.argv(), cmd.workingDir(), onError);
    }

    private static void launch(List<String> argv, Path workingDir, Consumer<String> onError) {
        EXEC.submit(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(ProcessRunner.resolveExecutable(argv));
                ProcessRunner.applyStandardEnv(pb);
                if (workingDir != null) {
                    // The child's working directory (CreateProcess lpCurrentDirectory), never a shell argument.
                    pb.directory(workingDir.toFile());
                }
                pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                pb.redirectError(ProcessBuilder.Redirect.DISCARD);
                pb.start();
            } catch (Exception e) {
                if (onError != null) {
                    onError.accept(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                }
            }
        });
    }
}
