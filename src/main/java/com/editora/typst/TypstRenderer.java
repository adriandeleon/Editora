package com.editora.typst;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.editora.process.ProcessRunner;

/**
 * Low-level facade over the {@code typst} CLI. The multi-page analogue of {@code diagram/DiagramRenderer}:
 * all methods are <b>synchronous</b> and must be called off the JavaFX thread (callers use a daemon
 * executor — see {@link TypstService} and {@code editor/TypstImages}). Unlike a single-image diagram tool,
 * a Typst document has <em>N pages</em>, so {@link #renderPages} rasterizes one PNG per page and returns
 * the ordered list of image bytes.
 *
 * <p><b>Relative-path root.</b> Typst documents commonly {@code #image("logo.png")} / {@code #import} a
 * sibling file, resolved against the project <em>root</em>. So when the buffer is saved, the caller passes
 * the file's parent directory as {@code srcDir}: the live text is written to a throwaway {@code .typ} file
 * <em>inside</em> that directory and compiled with {@code --root <srcDir>}, so relative references resolve.
 * For an untitled buffer ({@code srcDir == null}) an isolated temp dir is used (relative refs won't
 * resolve, which is acceptable for an unsaved scratch document). The throwaway input file is always
 * deleted afterward, so nothing is left behind in the user's folder.
 *
 * <p>The pure argv builders ({@link #renderArgs}/{@link #exportArgs}) and the page-file sort
 * ({@link #sortPageFiles}) are unit-tested ({@code TypstRendererTest}).
 */
public final class TypstRenderer {

    /** Supersampling factor: render at {@code 96 * RENDER_SCALE} PPI, then display at logical (÷scale) size
     *  so the preview is crisp on HiDPI without looking oversized. Mirrors {@code MermaidImages.RENDER_SCALE}. */
    public static final int RENDER_SCALE = 2;

    private static final int BASE_PPI = 96;
    private static final java.time.Duration DETECT_TIMEOUT = java.time.Duration.ofSeconds(20);
    private static final java.time.Duration RENDER_TIMEOUT = java.time.Duration.ofSeconds(60);
    private static final Pattern PAGE_NUM = Pattern.compile("page-(\\d+)\\.png$");

    /** A multi-page render outcome: the ordered per-page PNG {@code pages} on success, else {@code error}. */
    public record Pages(List<byte[]> pages, String error) {
        public boolean ok() {
            return error == null && pages != null && !pages.isEmpty();
        }

        static Pages ok(List<byte[]> pages) {
            return new Pages(List.copyOf(pages), null);
        }

        static Pages fail(String error) {
            return new Pages(List.of(), error == null || error.isBlank() ? "render failed" : error.strip());
        }
    }

    private TypstRenderer() {}

    /**
     * The command to invoke: the user-configured value if set, else {@code "typst"}; either may be a bare
     * executable or a multi-token command, split on whitespace. Pure; unit-tested.
     */
    public static List<String> command(String configured) {
        String raw = configured == null || configured.isBlank() ? "typst" : configured.strip();
        // A value that IS a file is taken whole: Settings has a Browse… button, and the installer writes an
        // absolute path — so a home directory with a space (/Users/John Smith/…) made every render die with
        // Cannot run program "/Users/John". Anything else tokenizes quote-aware, so a multi-token command
        // still works. (Same fix as DiagramRenderer.command.)
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
            return false;
        }
    }

    /**
     * Whether {@code typst} is launchable (present). Blocking; call off the FX thread.
     *
     * <p>For a single-token command any clean <em>launch</em> counts (the runner's {@code exit == -1} is its
     * failed-to-start sentinel) — a tool that runs but rejects the flag is still installed. For a multi-token
     * wrapper the reasoning inverts: the wrapper always launches, so only its exit code says whether the tool
     * is really there. (Same fix as DiagramRenderer.detect.)
     */
    public static boolean detect(List<String> base) {
        List<String> cmd = new ArrayList<>(base);
        cmd.add("--version");
        ProcessRunner.Result r = ProcessRunner.run(null, DETECT_TIMEOUT, cmd);
        return base.size() > 1 ? r.ok() : r.exit() != -1;
    }

    /** The PPI a render targets ({@code 96 * RENDER_SCALE}). */
    public static int renderPpi() {
        return BASE_PPI * RENDER_SCALE;
    }

    /** Where the throwaway {@code .typ} goes ({@code inputDir}) and the {@code --root} sandbox to use. */
    private record Prep(Path inputDir, Path root, boolean temp) {}

    /**
     * Renders {@code source} to one PNG per page. Blocking. {@code fileDir} is the saved file's own folder
     * (where the throwaway input is written, so relative {@code #image}/{@code #import} resolve as they do
     * on disk); {@code root} is the {@code --root} sandbox — the same as {@code fileDir}, or a higher
     * project root so a multi-file project's up-references resolve. Either may be {@code null} for an
     * untitled/remote buffer (→ isolated temp).
     */
    public static Pages renderPages(List<String> cmd, String source, Path fileDir, Path root) {
        return renderPages(cmd, source, fileDir, root, null);
    }

    /**
     * As {@link #renderPages(List, String, Path, Path)}, but a non-null {@code displayName} rewrites the
     * throwaway {@code .editora-typst-<uuid>.typ} basename in a compile-error message to the buffer's real
     * name — otherwise every Typst typo shows the internal UUID temp filename, not the user's file (#462).
     */
    public static Pages renderPages(List<String> cmd, String source, Path fileDir, Path root, String displayName) {
        Prep p = null;
        Path input = null;
        Path outDir = null;
        try {
            p = prepare(fileDir, root);
            input = p.inputDir().resolve(".editora-typst-" + UUID.randomUUID() + ".typ");
            outDir = Files.createTempDirectory("editora-typst-out");
            Files.writeString(input, source);
            Path outTemplate = outDir.resolve("page-{p}.png");
            ProcessRunner.Result r = ProcessRunner.run(
                    p.root(), RENDER_TIMEOUT, renderArgs(cmd, p.root(), input, outTemplate, renderPpi()));
            List<byte[]> pages = readPages(outDir);
            if (pages.isEmpty()) {
                return Pages.fail(r.ok() ? "no pages rendered" : friendlyMessage(r.message(), displayName));
            }
            return Pages.ok(pages);
        } catch (IOException e) {
            return Pages.fail(friendlyMessage(e.getMessage(), displayName));
        } finally {
            deleteIfExists(input);
            deleteRecursively(outDir);
            if (p != null && p.temp()) {
                deleteRecursively(p.inputDir());
            }
        }
    }

    /** Matches the throwaway input filename (with any leading path typst may print) in a diagnostic. */
    private static final Pattern TEMP_INPUT = Pattern.compile("\\S*\\.editora-typst-[0-9a-fA-F-]+\\.typ");

    /**
     * Rewrites the throwaway {@code .editora-typst-<uuid>.typ} filename in a typst diagnostic to
     * {@code displayName} (or a neutral {@code document.typ} when none is given), so the user sees their own
     * file name in an error, not an internal UUID. Line/column are untouched. Pure; unit-tested.
     */
    static String friendlyMessage(String message, String displayName) {
        if (message == null || message.isBlank()) {
            return message;
        }
        String name = displayName == null || displayName.isBlank() ? "document.typ" : displayName;
        return TEMP_INPUT.matcher(message).replaceAll(Matcher.quoteReplacement(name));
    }

    /**
     * Exports {@code source} to {@code dest} (format inferred from the destination extension). PDF is a
     * single native file (Typst's natural output); PNG/SVG multi-page docs write one numbered file per page
     * ({@code dest-1.png}, {@code dest-2.png}, …). {@code fileDir}/{@code root} as in {@link #renderPages}.
     * Blocking.
     */
    public static ProcessRunner.Result exportTo(List<String> cmd, String source, Path dest, Path fileDir, Path root) {
        Prep p = null;
        Path input = null;
        try {
            p = prepare(fileDir, root);
            input = p.inputDir().resolve(".editora-typst-" + UUID.randomUUID() + ".typ");
            Files.writeString(input, source);
            String output = exportOutput(dest);
            return ProcessRunner.run(p.root(), RENDER_TIMEOUT, exportArgs(cmd, p.root(), input, output, renderPpi()));
        } catch (IOException e) {
            return new ProcessRunner.Result(-1, "", e.getMessage() == null ? "export failed" : e.getMessage());
        } finally {
            deleteIfExists(input);
            if (p != null && p.temp()) {
                deleteRecursively(p.inputDir());
            }
        }
    }

    private static Prep prepare(Path fileDir, Path root) throws IOException {
        if (fileDir != null && Files.isDirectory(fileDir)) {
            Path validRoot = (root != null && Files.isDirectory(root)) ? root : null;
            return new Prep(fileDir, effectiveRoot(fileDir, validRoot), false);
        }
        Path tmp = Files.createTempDirectory("editora-typst-root");
        return new Prep(tmp, tmp, true);
    }

    /**
     * The {@code --root} to use for a temp input written under {@code inputDir}: {@code root} when it is a
     * real directory that <em>contains</em> {@code inputDir} (typst requires the input to sit inside the
     * root), otherwise {@code inputDir} itself. Pure; unit-tested.
     */
    static Path effectiveRoot(Path inputDir, Path root) {
        if (root == null) {
            return inputDir;
        }
        Path in = inputDir.toAbsolutePath().normalize();
        Path rt = root.toAbsolutePath().normalize();
        return in.startsWith(rt) ? rt : inputDir;
    }

    /** The argv to render {@code input} → per-page PNGs at {@code outTemplate} (with a {@code {p}} placeholder). Pure. */
    static List<String> renderArgs(List<String> cmd, Path root, Path input, Path outTemplate, int ppi) {
        List<String> a = new ArrayList<>(cmd);
        a.add("compile");
        a.add("--root");
        a.add(root.toString());
        a.add("-f");
        a.add("png");
        a.add("--ppi");
        a.add(Integer.toString(ppi));
        a.add(input.toString());
        a.add(outTemplate.toString());
        return a;
    }

    /** The argv to export {@code input} → {@code output} (format by output extension). Pure. */
    static List<String> exportArgs(List<String> cmd, Path root, Path input, String output, int ppi) {
        List<String> a = new ArrayList<>(cmd);
        a.add("compile");
        a.add("--root");
        a.add(root.toString());
        String fmt = formatFor(output);
        a.add("-f");
        a.add(fmt);
        if (!"pdf".equals(fmt)) {
            a.add("--ppi");
            a.add(Integer.toString(ppi));
        }
        a.add(input.toString());
        a.add(output);
        return a;
    }

    /**
     * The files an export to {@code dest} actually produced, in page order — {@code dest} itself for a PDF,
     * else the {@code base-<n>.ext} files {@link #exportOutput}'s {@code {p}} template makes typst write.
     *
     * <p>Needed because the caller cannot name the result: typst rewrites {@code report.png} to
     * {@code report-1.png} — <b>even for a single-page document</b> — so reporting the chooser's own path
     * told the user "Exported to …/report.png" about a file that does not exist. An empty result also means
     * the tool exited 0 having written nothing, which must not read as success.
     */
    public static List<Path> exportedFiles(Path dest) {
        String fmt = formatFor(dest.getFileName().toString());
        if ("pdf".equals(fmt)) {
            return Files.isRegularFile(dest) ? List.of(dest) : List.of();
        }
        Path parent = dest.toAbsolutePath().getParent();
        if (parent == null) {
            return List.of();
        }
        String name = dest.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot >= 0 ? name.substring(0, dot) : name;
        String ext = dot >= 0 ? name.substring(dot) : "." + fmt;
        Pattern numbered = Pattern.compile(Pattern.quote(base) + "-(\\d+)" + Pattern.quote(ext) + "$");
        try (var files = Files.list(parent)) {
            List<Path> out = new ArrayList<>(files.filter(Files::isRegularFile)
                    .filter(f -> numbered.matcher(f.getFileName().toString()).find())
                    .toList());
            out.sort(java.util.Comparator.comparingInt(f -> pageNumber(numbered, f)));
            return List.copyOf(out);
        } catch (IOException e) {
            return List.of();
        }
    }

    private static int pageNumber(Pattern numbered, Path file) {
        Matcher m = numbered.matcher(file.getFileName().toString());
        try {
            return m.find() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE;
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * The output path string for {@code dest}. A single-file PDF stays as-is; a PNG/SVG export becomes a
     * {@code {p}}-templated name ({@code report.png} → {@code report-{p}.png}) so a multi-page document
     * writes one numbered file per page. Pure.
     */
    static String exportOutput(Path dest) {
        String fmt = formatFor(dest.getFileName().toString());
        if ("pdf".equals(fmt)) {
            return dest.toString();
        }
        String name = dest.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot >= 0 ? name.substring(0, dot) : name;
        String ext = dot >= 0 ? name.substring(dot) : "." + fmt;
        Path parent = dest.getParent();
        String templated = base + "-{p}" + ext;
        return parent == null ? templated : parent.resolve(templated).toString();
    }

    /** The output format for a name by its extension (pdf/png/svg), defaulting to pdf. Pure. */
    static String formatFor(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        String ext = dot >= 0 ? n.substring(dot + 1) : "";
        return switch (ext) {
            case "png", "svg" -> ext;
            default -> "pdf";
        };
    }

    private static List<byte[]> readPages(Path outDir) throws IOException {
        List<byte[]> pages = new ArrayList<>();
        for (Path p : sortPageFiles(outDir)) {
            pages.add(Files.readAllBytes(p));
        }
        return pages;
    }

    /** Lists {@code page-N.png} files in {@code dir} sorted by their numeric page index. Pure over the FS. */
    static List<Path> sortPageFiles(Path dir) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        try (var s = Files.list(dir)) {
            return s.filter(p -> PAGE_NUM.matcher(p.getFileName().toString()).find())
                    .sorted(Comparator.comparingInt(TypstRenderer::pageIndex))
                    .toList();
        }
    }

    private static int pageIndex(Path p) {
        Matcher m = PAGE_NUM.matcher(p.getFileName().toString());
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE;
    }

    private static void deleteIfExists(Path p) {
        if (p == null) {
            return;
        }
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // best-effort
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
