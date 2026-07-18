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
    /** {@code extra} = the number of <em>additional</em> diagrams the tool produced beyond the one shown — a
     *  PlantUML file with several {@code @startuml} blocks writes one image per diagram, but the preview shows
     *  the first; a non-zero count surfaces a "N more diagram(s)" note so the rest aren't silently dropped. */
    public record Render(byte[] image, String error, int extra) {
        public boolean ok() {
            return image != null && error == null;
        }

        static Render ok(byte[] image) {
            return new Render(image, null, 0);
        }

        static Render ok(byte[] image, int extra) {
            return new Render(image, null, Math.max(0, extra));
        }

        static Render fail(String error) {
            return new Render(null, error == null || error.isBlank() ? "render failed" : error.strip(), 0);
        }
    }

    private DiagramRenderer() {}

    /**
     * The command to invoke: the user-configured value if set, else {@code defaultCommand}. Either may be a
     * bare executable or a multi-token command (e.g. {@code npx -y @probelabs/maid}). Pure; unit-tested.
     *
     * <p>A value that <b>is an existing file</b> is taken whole — the Settings page has a Browse… button, and
     * a space in the path is normal ({@code C:\Program Files\Graphviz\bin\dot.exe},
     * {@code ~/Library/Application Support/…}); splitting it on whitespace produced an argv of
     * {@code [C:\Program, Files\…]} and the run died with "No such file or directory". Anything else is
     * tokenized quote-aware, so a multi-token command still works and a path can also be quoted by hand.
     */
    public static List<String> command(String configured, String defaultCommand) {
        String raw = configured == null || configured.isBlank() ? defaultCommand : configured.strip();
        if (isExistingFile(raw)) {
            return List.of(raw);
        }
        List<String> tokens = com.editora.run.ProgramArgs.tokenize(raw);
        return tokens.isEmpty() ? List.of(raw) : List.copyOf(tokens);
    }

    /** True when {@code raw} names a file that exists — i.e. it is a path, not a command line. */
    private static boolean isExistingFile(String raw) {
        try {
            return !raw.isBlank() && Files.isRegularFile(Path.of(raw));
        } catch (RuntimeException e) {
            return false; // not a parseable path (a command line with odd chars)
        }
    }

    /**
     * Whether the {@code base} command is launchable (tool present). Blocking; call off the FX thread.
     * Tries {@code --version} then {@code --help}.
     *
     * <p>For a <b>single-token</b> command, any clean <em>launch</em> counts (the runner's {@code exit == -1}
     * is its failed-to-start sentinel): a tool that runs but rejects the flag is still installed, so this
     * avoids a false negative. For a <b>multi-token wrapper</b> ({@code npx -y <pkg>} — the default maid
     * command) that reasoning inverts: npx itself always launches, so a launch says nothing about the
     * package. Its exit code is the only signal, and a missing package exits non-zero — so require success
     * there. Without this, maid reported "detected" on any machine with Node, and live linting fired a
     * multi-second npx on every typing pause that could never produce a diagnostic.
     */
    public static boolean detect(List<String> base) {
        boolean wrapper = base.size() > 1;
        ProcessRunner.Result version = ProcessRunner.run(null, DETECT_TIMEOUT, withArg(base, "--version"));
        if (wrapper ? version.ok() : version.exit() != -1) {
            return true;
        }
        ProcessRunner.Result help = ProcessRunner.run(null, DETECT_TIMEOUT, withArg(base, "--help"));
        return wrapper ? help.ok() : help.exit() != -1;
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
            if (!r.ok()) {
                return Render.fail(r.message());
            }
            Path produced = producedFile(dir, in, out, "png");
            if (produced == null) {
                return Render.fail(r.message().isBlank() ? "no output produced" : r.message());
            }
            // A multi-@startuml PlantUML file writes several PNGs; show the first, count the rest so the
            // preview can say "N more diagram(s)" instead of silently dropping them (#459).
            int extra = outputCount(dir, in, "png") - 1;
            return Render.ok(Files.readAllBytes(produced), extra);
        } catch (IOException e) {
            return Render.fail(e.getMessage());
        } finally {
            deleteRecursively(dir);
        }
    }

    /**
     * Renders {@code source} to {@code dest} (format inferred from the destination extension —
     * {@code svg}/{@code png}/{@code pdf}, defaulting to png). Blocking. The tool writes into a single-use
     * temp dir; {@link #producedFile} finds what it actually wrote and the file is moved to {@code dest}.
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
            if (!r.ok()) {
                return r;
            }
            Path produced = producedFile(dir, in, out, fmt);
            if (produced == null) {
                // The tool exited 0 but wrote nothing we can find. Reporting r (ok) meant the caller said
                // "Exported to …" while the temp dir — and the render — was deleted in the finally below.
                return new ProcessRunner.Result(-1, r.out(), r.err().isBlank() ? "no output produced" : r.err());
            }
            Files.move(produced, dest, StandardCopyOption.REPLACE_EXISTING);
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

    /**
     * The output the tool actually produced in the single-use temp {@code dir}: {@code out} when it exists,
     * else the (alphabetically first) other {@code .fmt} file there.
     *
     * <p>PlantUML names its output after the diagram, not the input file: {@code @startuml myclassdiagram}
     * writes {@code myclassdiagram.png}, not {@code diagram.png} — and naming a diagram is idiomatic (it is
     * required for a multi-diagram file, and used throughout the upstream docs). Reading back a fixed
     * {@code diagram.<fmt>} meant a perfectly valid named diagram rendered as "render failed", and an export
     * of one silently produced no file at all while reporting success. The dir is created per render and
     * holds only the input plus the tool's own output, so finding it is unambiguous.
     *
     * <p>A multi-diagram source produces several outputs; we take one (deterministically). That is the
     * pre-existing single-diagram limitation, not a regression.
     */
    /** The number of output files the tool produced in {@code dir} (excluding the input), by extension. */
    private static int outputCount(Path dir, Path in, String fmt) throws IOException {
        try (var files = Files.list(dir)) {
            List<String> names = files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .toList();
            return matchingOutputs(names, in.getFileName().toString(), fmt).size();
        }
    }

    /** The output file names (of extension {@code fmt}, excluding the input {@code inName}), sorted. Pure — a
     *  PlantUML multi-{@code @startuml} render produces more than one; the count drives the "N more" note. */
    static List<String> matchingOutputs(List<String> names, String inName, String fmt) {
        String suffix = "." + fmt.toLowerCase(Locale.ROOT);
        return names.stream()
                .filter(n -> n != null && !n.equals(inName))
                .filter(n -> n.toLowerCase(Locale.ROOT).endsWith(suffix))
                .sorted()
                .toList();
    }

    private static Path producedFile(Path dir, Path in, Path out, String fmt) throws IOException {
        if (Files.isRegularFile(out)) {
            return out;
        }
        String suffix = "." + fmt.toLowerCase(Locale.ROOT);
        try (var files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> !p.equals(in))
                    .filter(p ->
                            p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(suffix))
                    .sorted()
                    .findFirst()
                    .orElse(null);
        }
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
