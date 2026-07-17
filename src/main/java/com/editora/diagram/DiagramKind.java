package com.editora.diagram;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The rendered "diagram-as-code" tools that get an IntelliJ-style 3-mode preview via an external CLI —
 * <b>Graphviz DOT</b> and <b>PlantUML</b>. This mirrors the Mermaid seam ({@code mermaid/Mermaid} +
 * {@code editor/MermaidImages}) but generically: each kind maps a buffer language id to its CLI, the
 * temp-file extension its source is written to, its default command, and the pure argv builder for a
 * render/export (format chosen by extension). Both DOT and PlantUML rasterize to <b>PNG natively</b>
 * (no headless browser), unlike mmdc/D2 — which is why they're the first two.
 *
 * <p>The render always writes the temp source to {@code diagram.<sourceExtension>} and reads the output
 * back from {@code diagram.<format>} in the same temp dir, so the arg builder need only place flags — the
 * facade owns the paths. Pure and unit-tested ({@code DiagramRendererTest}); Mermaid is deliberately
 * <em>not</em> part of this enum (it keeps its own shipped path).
 */
public enum DiagramKind {
    /** Graphviz DOT ({@code .dot}/{@code .gv}). Renders {@code dot -T<fmt> -o <out> <in>}. */
    DOT("dot", "dot", "dot", false) {
        @Override
        List<String> args(List<String> cmd, Path in, Path out, String fmt, boolean dark) {
            List<String> a = new ArrayList<>(cmd);
            a.add("-T" + fmt);
            a.add("-o");
            a.add(out.toString());
            a.add(in.toString());
            return a;
        }
    },
    /** PlantUML ({@code .puml}/{@code .plantuml}/{@code .pu}/{@code .iuml}). Renders {@code plantuml -t<fmt> <in>},
     *  which writes beside the input — but named after the <b>diagram</b>, not the input file: an
     *  {@code @startuml myclassdiagram} produces {@code myclassdiagram.<fmt>}, not {@code diagram.<fmt>}. The
     *  renderer therefore finds the produced file rather than assuming its name. */
    PLANTUML("plantuml", "plantuml", "puml", false) {
        @Override
        List<String> args(List<String> cmd, Path in, Path out, String fmt, boolean dark) {
            List<String> a = new ArrayList<>(cmd);
            a.add("-t" + fmt);
            a.add(in.toString());
            return a;
        }
    };

    private final String languageId;
    private final String defaultCommand;
    private final String sourceExtension;
    private final boolean themeSensitive;

    DiagramKind(String languageId, String defaultCommand, String sourceExtension, boolean themeSensitive) {
        this.languageId = languageId;
        this.defaultCommand = defaultCommand;
        this.sourceExtension = sourceExtension;
        this.themeSensitive = themeSensitive;
    }

    /** The command when the user leaves the path blank (a bare binary on PATH). */
    public String defaultCommand() {
        return defaultCommand;
    }

    /** The extension of the temp source file (drives the tool's output naming). */
    public String sourceExtension() {
        return sourceExtension;
    }

    /**
     * Whether the rendered image depends on the app light/dark theme. DOT/PlantUML render a fixed
     * (light) image today, so the render cache key omits the theme bit for them — a theme toggle is a
     * cache hit, not a re-render. Kept as a per-kind property so a future themed backend flips one flag.
     */
    public boolean themeSensitive() {
        return themeSensitive;
    }

    /** The argv (base command + flags) to render {@code in} → {@code out} as {@code fmt}. Pure. */
    abstract List<String> args(List<String> cmd, Path in, Path out, String fmt, boolean dark);

    /** The rendered diagram kind for a buffer {@code language}, or {@code null} if it isn't one. */
    public static DiagramKind fromLanguage(String language) {
        if (language == null) {
            return null;
        }
        for (DiagramKind k : values()) {
            if (k.languageId.equals(language)) {
                return k;
            }
        }
        return null;
    }
}
