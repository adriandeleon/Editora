package com.example.editora.ongithub;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import com.editora.plugin.ActiveEditor;
import com.editora.plugin.Plugin;
import com.editora.plugin.PluginContext;

import javafx.application.Platform;

/**
 * Opens the active file (at the caret line) on its remote's web UI. Shells out to {@code git} off the FX
 * thread to find the repo root, the {@code origin} remote, and the current branch, builds a {@code blob}
 * URL ({@link GitHubUrl}), and opens it via {@link PluginContext#openUrl}.
 */
public class OpenOnGitHubPlugin implements Plugin {

    @Override
    public void start(PluginContext ctx) {
        ctx.registerCommand("open", "Git: Open File on GitHub", () -> {
            ActiveEditor ed = ctx.activeEditor();
            Path file = ed.filePath();
            if (file == null) {
                ctx.setStatus("Save the file first");
                return;
            }
            int line = ed.caretLine();
            ctx.setStatus("Resolving GitHub URL…");
            new Thread(() -> {
                try {
                    Path dir = file.toAbsolutePath().getParent();
                    String top = git(dir, "rev-parse", "--show-toplevel");
                    String remote = git(dir, "remote", "get-url", "origin");
                    String branch = git(dir, "rev-parse", "--abbrev-ref", "HEAD");
                    String rel = Paths.get(top).relativize(file.toAbsolutePath()).toString();
                    String url = GitHubUrl.build(remote, branch, rel, line);
                    Platform.runLater(() -> ctx.openUrl(url));
                } catch (Exception e) {
                    Platform.runLater(() -> ctx.setStatus("Not a git repo with an 'origin' remote: "
                            + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())));
                }
            }, "open-on-github").start();
        });
    }

    /** Runs {@code git <args>} in {@code dir} and returns trimmed stdout; throws on a non-zero exit. */
    private static String git(Path dir, String... args) throws IOException, InterruptedException {
        List<String> cmd = new java.util.ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile());
        Process p = pb.start();
        byte[] out = p.getInputStream().readAllBytes();
        byte[] err = p.getErrorStream().readAllBytes();
        if (p.waitFor() != 0) {
            throw new IOException(new String(err, StandardCharsets.UTF_8).strip());
        }
        return new String(out, StandardCharsets.UTF_8).strip();
    }
}
