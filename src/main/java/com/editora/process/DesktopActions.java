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

    /** The command that opens a terminal with its working directory set to {@code dir}. Pure. */
    public static List<String> terminalArgv(Os os, Path dir) {
        String d = dir.toString();
        return switch (os) {
            case MAC -> List.of("open", "-a", "Terminal", d);
            case WINDOWS -> List.of("cmd", "/c", "start", "cmd", "/k", "cd /d " + d);
            case LINUX -> List.of("x-terminal-emulator", "--working-directory=" + d);
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

    /** Reveals {@code path} in the file manager; on failure calls {@code onError} (off the FX thread). */
    public static void reveal(Path path, boolean isDir, Consumer<String> onError) {
        launch(revealArgv(currentOs(), path, isDir, containingDir(path, isDir)), onError);
    }

    /** Opens a terminal at {@code path}'s containing folder; calls {@code onError} (off the FX thread). */
    public static void openTerminal(Path path, boolean isDir, Consumer<String> onError) {
        launch(terminalArgv(currentOs(), containingDir(path, isDir)), onError);
    }

    private static void launch(List<String> argv, Consumer<String> onError) {
        EXEC.submit(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(ProcessRunner.resolveExecutable(argv));
                ProcessRunner.applyStandardEnv(pb);
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
