package com.example.editora.reveal;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import com.editora.plugin.ActiveEditor;
import com.editora.plugin.Plugin;
import com.editora.plugin.PluginContext;

/**
 * Reveals the active file in the OS file manager, or opens a terminal at its folder. Per-OS commands
 * (macOS / Windows / Linux), spawned detached via {@link ProcessBuilder}.
 */
public class RevealPlugin implements Plugin {

    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    private static final boolean MAC = OS.contains("mac");
    private static final boolean WIN = OS.contains("win");

    @Override
    public void start(PluginContext ctx) {
        ctx.registerCommand("fileManager", "Reveal in File Manager", () -> revealInFiles(ctx));
        ctx.registerCommand("terminal", "Open Terminal Here", () -> openTerminal(ctx));
    }

    private void revealInFiles(PluginContext ctx) {
        Path file = file(ctx);
        if (file == null) {
            return;
        }
        String f = file.toString();
        String dir = parent(file);
        List<String> cmd = MAC ? List.of("open", "-R", f)
                : WIN ? List.of("explorer", "/select,", f)
                : List.of("xdg-open", dir);
        run(ctx, cmd);
    }

    private void openTerminal(PluginContext ctx) {
        Path file = file(ctx);
        if (file == null) {
            return;
        }
        String dir = parent(file);
        List<String> cmd = MAC ? List.of("open", "-a", "Terminal", dir)
                : WIN ? List.of("cmd", "/c", "start", "cmd", "/k", "cd /d " + dir)
                : List.of("x-terminal-emulator", "--working-directory=" + dir);
        run(ctx, cmd);
    }

    private static Path file(PluginContext ctx) {
        ActiveEditor ed = ctx.activeEditor();
        Path f = ed.filePath();
        if (f == null) {
            ctx.setStatus("Save the file first");
            return null;
        }
        return f.toAbsolutePath();
    }

    private static String parent(Path file) {
        Path p = file.getParent();
        return (p == null ? file : p).toString();
    }

    private void run(PluginContext ctx, List<String> cmd) {
        try {
            new ProcessBuilder(cmd).start();
        } catch (Exception e) {
            ctx.setStatus("Failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }
}
