package com.editora.diagram;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.editora.process.ProcessRunner;

/**
 * Low-level facade over the diagram-as-code CLIs ({@code dot}, {@code plantuml}). The generic analogue of
 * {@code mermaid/Mermaid}: all methods are <b>synchronous</b> and must be called off the JavaFX thread
 * (callers use a daemon executor — see {@link DiagramService} and {@code editor/DiagramImages}).
 *
 * <p>A tool is invoked as a <b>command line</b> (a token list), not a single executable, so a configured
 * path <em>or</em> a multi-word command both work ({@link #command}). The pure {@link DiagramKind} argv
 * builders are unit-tested via this class.
 */
public final class DiagramRenderer {

    private static final Duration DETECT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration RENDER_TIMEOUT = Duration.ofSeconds(60);

    /** A render outcome: PNG {@code image} bytes on success, else the tool's {@code error} message. */
    public record Render(byte[] image, String error) {
        public boolean ok() {
            return image != null && error == null;
        }

        static Render ok(byte[] image) {
            return new Render(image, null);
        }

        static Render fail(String error) {
            return new Render(null, error == null || error.isBlank() ? "render failed" : error.strip());
        }
    }

    private DiagramRenderer() {}

    /**
     * The command to invoke: the user-configured value if set, else {@code defaultCommand}; either may be
     * a bare executable or a multi-token command, split on whitespace. Pure; unit-tested.
     */
    public static List<String> command(String configured, String defaultCommand) {
        String raw = configured == null || configured.isBlank() ? defaultCommand : configured.strip();
        return List.of(raw.split("\\s+"));
    }

    /**
     * Whether {@code base} is launchable (tool present). Blocking; call off the FX thread. Tries
     * {@code --version} then {@code --help}; any clean <em>launch</em> (runner {@code exit != -1}) counts
     * as present, since a tool that runs but rejects the flag is still installed.
     */
    public static boolean detect(List<String> base) {
        if (ProcessRunner.run(null, DETECT_TIMEOUT, withArg(base, "--version")).exit() != -1) {
            return true;
        }
        return ProcessRunner.run(null, DETECT_TIMEOUT, withArg(base, "--help")).exit() != -1;
    }

    /**
     * Renders {@code source} to a PNG via the {@code kind} tool. Blocking. DOT/PlantUML rasterize PNG
     * natively (no headless browser).
     */
    public static Render renderPng(DiagramKind kind, List<String> cmd, String source, boolean dark) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("editora-diagram");
            Path in = dir.resolve("diagram." + kind.sourceExtension());
            Path out = dir.resolve("diagram.png");
            Files.writeString(in, source);
            ProcessRunner.Result r = ProcessRunner.run(dir, RENDER_TIMEOUT, kind.args(cmd, in, out, "png", dark));
            if (!r.ok() || !Files.isRegularFile(out)) {
                return Render.fail(r.message());
            }
            return Render.ok(Files.readAllBytes(out));
        } catch (IOException e) {
            return Render.fail(e.getMessage());
        } finally {
            deleteRecursively(dir);
        }
    }

    /**
     * Renders {@code source} to {@code dest} (format inferred from the destination extension —
     * {@code svg}/{@code png}/{@code pdf}, defaulting to png). Blocking. The tool writes into a temp dir
     * (both DOT and PlantUML name the output {@code diagram.<fmt>}); the file is then moved to {@code dest}.
     */
    public static ProcessRunner.Result exportTo(
            DiagramKind kind, List<String> cmd, String source, Path dest, boolean dark) {
        Path dir = null;
        try {
            String fmt = formatFor(dest);
            dir = Files.createTempDirectory("editora-diagram");
            Path in = dir.resolve("diagram." + kind.sourceExtension());
            Path out = dir.resolve("diagram." + fmt);
            Files.writeString(in, source);
            ProcessRunner.Result r = ProcessRunner.run(dir, RENDER_TIMEOUT, kind.args(cmd, in, out, fmt, dark));
            if (r.ok() && Files.isRegularFile(out)) {
                Files.move(out, dest, StandardCopyOption.REPLACE_EXISTING);
            }
            return r;
        } catch (IOException e) {
            return new ProcessRunner.Result(-1, "", e.getMessage() == null ? "export failed" : e.getMessage());
        } finally {
            deleteRecursively(dir);
        }
    }

    /** The output format for {@code dest} by its extension (svg/png/pdf), defaulting to png. Pure. */
    static String formatFor(Path dest) {
        String name = dest.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1) : "";
        return switch (ext) {
            case "svg", "pdf" -> ext;
            default -> "png";
        };
    }

    private static List<String> withArg(List<String> base, String arg) {
        List<String> cmd = new java.util.ArrayList<>(base);
        cmd.add(arg);
        return cmd;
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort temp cleanup
                }
            });
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
