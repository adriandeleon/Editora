package com.example.editora.format;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.editora.plugin.ActiveEditor;
import com.editora.plugin.Plugin;
import com.editora.plugin.PluginContext;

import javafx.application.Platform;

/**
 * Runs an external code formatter on the active file and replaces the buffer with the result. The formatter
 * is chosen by file extension and invoked <b>stdin → stdout</b> (so the file on disk is never touched): the
 * buffer text is piped in, the formatted text comes back and is applied via {@code setText}.
 *
 * <p>The subprocess runs off the FX thread; the result is applied back on it. This is a self-contained
 * example — it augments {@code PATH} with the usual install dirs so a GUI-launched app can still find tools
 * like Homebrew/npm-global formatters. (Editora's own {@code ProcessRunner} does this more thoroughly, but
 * it isn't part of the plugin API.)
 */
public class FormatRunnerPlugin implements Plugin {

    @Override
    public void start(PluginContext ctx) {
        ctx.registerCommand("format", "Format: Format File", () -> formatActive(ctx));
    }

    private void formatActive(PluginContext ctx) {
        ActiveEditor ed = ctx.activeEditor();
        Path file = ed.filePath();
        String text = ed.text();
        if (text == null || text.isEmpty()) {
            ctx.setStatus("Nothing to format");
            return;
        }
        List<String> cmd = commandFor(file);
        if (cmd == null) {
            ctx.setStatus("No formatter configured for this file type");
            return;
        }
        String tool = cmd.get(0);
        ctx.setStatus("Formatting with " + tool + "…");
        new Thread(() -> {
            Result r = run(cmd, text);
            Platform.runLater(() -> {
                if (r.ok() && !r.out().isEmpty()) {
                    ed.setText(r.out());
                    ctx.setStatus("Formatted with " + tool);
                } else {
                    ctx.setStatus(tool + " failed: " + r.message());
                }
            });
        }, "format-runner").start();
    }

    /** The formatter argv for a file, or null if its extension isn't handled. All read stdin, write stdout. */
    private static List<String> commandFor(Path file) {
        String name = file == null ? "" : file.getFileName().toString().toLowerCase(Locale.ROOT);
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
        String filename = name.isEmpty() ? "buffer.txt" : name;
        return switch (ext) {
            case "js", "jsx", "ts", "tsx", "mjs", "cjs", "json", "css", "scss", "less", "html",
                 "md", "markdown", "yaml", "yml", "vue" ->
                    List.of("prettier", "--stdin-filepath", filename);
            case "py" -> List.of("black", "-q", "-");
            case "go" -> List.of("gofmt");
            case "rs" -> List.of("rustfmt", "--emit", "stdout");
            case "c", "cc", "cpp", "cxx", "h", "hpp", "java" ->
                    List.of("clang-format", "--assume-filename=" + filename);
            default -> null;
        };
    }

    private record Result(boolean ok, String out, String message) { }

    /** Spawns {@code cmd}, writes {@code input} to its stdin, returns its stdout (or an error). */
    private static Result run(List<String> cmd, String input) {
        try {
            List<String> resolved = new ArrayList<>(cmd);
            resolved.set(0, resolveExecutable(cmd.get(0)));
            ProcessBuilder pb = new ProcessBuilder(resolved);
            pb.environment().put("PATH", augmentedPath());
            Process p = pb.start();
            try (OutputStream os = p.getOutputStream()) {
                os.write(input.getBytes(StandardCharsets.UTF_8));
            }
            byte[] out = p.getInputStream().readAllBytes();
            byte[] err = p.getErrorStream().readAllBytes();
            int code = p.waitFor();
            if (code != 0) {
                String msg = new String(err, StandardCharsets.UTF_8).strip();
                return new Result(false, "", msg.isEmpty() ? "exit " + code : msg);
            }
            return new Result(true, new String(out, StandardCharsets.UTF_8), "");
        } catch (IOException | InterruptedException e) {
            return new Result(false, "", e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private static final String[] EXTRA_DIRS = {
        "/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin",
        System.getProperty("user.home") + "/.local/bin",
        System.getProperty("user.home") + "/.cargo/bin",
        System.getProperty("user.home") + "/go/bin"
    };

    /** The inherited PATH plus the usual install dirs (so a Finder-launched app finds Homebrew/npm tools). */
    private static String augmentedPath() {
        StringBuilder sb = new StringBuilder();
        String inherited = System.getenv("PATH");
        if (inherited != null && !inherited.isBlank()) {
            sb.append(inherited);
        }
        for (String d : EXTRA_DIRS) {
            if (sb.indexOf(d) < 0) {
                if (sb.length() > 0) {
                    sb.append(java.io.File.pathSeparatorChar);
                }
                sb.append(d);
            }
        }
        return sb.toString();
    }

    /** Resolves a bare command name to an absolute path against the augmented PATH (else returns it as-is). */
    private static String resolveExecutable(String name) {
        if (name.contains("/")) {
            return name;
        }
        for (String dir : augmentedPath().split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            Path candidate = Paths.get(dir, name);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        return name;
    }
}
