package com.editora.mermaid;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.editora.process.ProcessRunner;

/**
 * Low-level facade over the Mermaid command-line tools — <b>mmdc</b> (mermaid-cli, renders/exports) and
 * <b>maid</b> ({@code probelabs/maid}, a fast pure-JS linter). All methods are <b>synchronous</b> and
 * must be called off the JavaFX thread (callers use a daemon executor — see {@code MermaidService} and
 * {@code editor/MermaidImages}).
 *
 * <p>Each tool is invoked as a <b>command line</b> (a token list), not a single executable, because the
 * usual way to run them is via {@code npx} (e.g. {@code npx -y @probelabs/maid}) — so a configured path
 * <em>or</em> a multi-word command both work ({@link #command}). The pure {@link MaidOutput} parser is
 * unit-tested.
 */
public final class Mermaid {

    private static final Duration DETECT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration RENDER_TIMEOUT = Duration.ofSeconds(60);
    /** Preview diagrams are rendered at this device scale for crispness, then shown at logical size. */
    public static final int RENDER_SCALE = 2;

    /** A render outcome: PNG {@code image} bytes on success, else {@code error} (mmdc's message). */
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

    private Mermaid() {
    }

    /**
     * The command to invoke: the user-configured value if set, else {@code defaultCommand}; either may be
     * a bare executable or a multi-token command (e.g. {@code npx -y @probelabs/maid}), split on
     * whitespace. Pure; unit-tested.
     */
    static List<String> command(String configured, String defaultCommand) {
        String raw = configured == null || configured.isBlank() ? defaultCommand : configured.strip();
        return List.of(raw.split("\\s+"));
    }

    /**
     * Whether the {@code base} command is launchable (tool present). Blocking; call off the FX thread.
     * Tries {@code --version} then {@code --help}; since a tool that runs but rejects the flag still
     * means it's installed, any clean <em>launch</em> (the runner's {@code exit != -1} failed-to-start
     * sentinel) counts as present — avoiding false negatives.
     */
    public static boolean detect(List<String> base) {
        if (ProcessRunner.run(null, DETECT_TIMEOUT, withArgs(base, "--version")).exit() != -1) {
            return true;
        }
        return ProcessRunner.run(null, DETECT_TIMEOUT, withArgs(base, "--help")).exit() != -1;
    }

    /**
     * Renders {@code source} to a PNG via mmdc (transparent background, theme by {@code dark}, at
     * {@link #RENDER_SCALE}×). Blocking. PNG (not SVG) because JavaFX/JSVG can't apply mermaid's embedded
     * CSS or render its {@code <foreignObject>} HTML labels — mmdc rasterizes faithfully via Chromium.
     */
    public static Render renderPng(List<String> mmdc, String source, boolean dark) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("editora-mermaid");
            Path in = dir.resolve("diagram.mmd");
            Path out = dir.resolve("diagram.png");
            Files.writeString(in, source);
            ProcessRunner.Result r = ProcessRunner.run(dir, RENDER_TIMEOUT, renderArgs(mmdc, in, out, dark));
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
     * Renders {@code source} straight to {@code dest} (format inferred by mmdc from the extension:
     * {@code .svg}/{@code .png}/{@code .pdf}). Blocking. Returns mmdc's raw result.
     */
    public static ProcessRunner.Result exportTo(List<String> mmdc, String source, Path dest, boolean dark) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("editora-mermaid");
            Path in = dir.resolve("diagram.mmd");
            Files.writeString(in, source);
            return ProcessRunner.run(dir, RENDER_TIMEOUT, renderArgs(mmdc, in, dest, dark));
        } catch (IOException e) {
            return new ProcessRunner.Result(-1, "", e.getMessage() == null ? "export failed" : e.getMessage());
        } finally {
            deleteRecursively(dir);
        }
    }

    /**
     * Validates {@code source} via maid ({@code --format json}), returning diagnostics (empty when valid
     * or maid is unavailable). Blocking. maid prints its JSON report on stdout regardless of exit code,
     * so we always parse it rather than gating on success.
     */
    public static List<MaidOutput.Diagnostic> validate(List<String> maid, String source) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("editora-maid");
            Path in = dir.resolve("diagram.mmd");
            Files.writeString(in, source);
            List<String> cmd = new ArrayList<>(maid);
            cmd.add("--format");
            cmd.add("json");
            cmd.add(in.toString());
            ProcessRunner.Result r = ProcessRunner.run(dir, DETECT_TIMEOUT, cmd);
            String payload = r.out() != null && !r.out().isBlank() ? r.out() : r.err();
            return MaidOutput.parse(payload);
        } catch (IOException e) {
            return List.of();
        } finally {
            deleteRecursively(dir);
        }
    }

    /** The full mmdc argument list (base command + flags; pure, unit-tested). {@code -s} affects raster only. */
    static List<String> renderArgs(List<String> mmdc, Path in, Path out, boolean dark) {
        List<String> cmd = new ArrayList<>(mmdc);
        cmd.add("-i");
        cmd.add(in.toString());
        cmd.add("-o");
        cmd.add(out.toString());
        cmd.add("-t");
        cmd.add(dark ? "dark" : "default");
        cmd.add("-b");
        cmd.add("transparent");
        cmd.add("-s");
        cmd.add(String.valueOf(RENDER_SCALE));
        return cmd;
    }

    private static List<String> withArgs(List<String> base, String... args) {
        List<String> cmd = new ArrayList<>(base);
        for (String a : args) {
            cmd.add(a);
        }
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
