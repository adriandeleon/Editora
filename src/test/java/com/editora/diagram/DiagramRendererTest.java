package com.editora.diagram;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Pure tests for the diagram CLI facade: command resolution, output-format inference, and per-kind argv. */
class DiagramRendererTest {

    @Test
    void command_usesDefaultWhenBlankOrNull() {
        assertEquals(List.of("dot"), DiagramRenderer.command("", "dot"));
        assertEquals(List.of("dot"), DiagramRenderer.command(null, "dot"));
        assertEquals(List.of("plantuml"), DiagramRenderer.command("   ", "plantuml"));
    }

    @Test
    void command_tokenizesConfiguredValue() {
        assertEquals(List.of("/opt/dot"), DiagramRenderer.command("/opt/dot", "dot"));
        assertEquals(
                List.of("java", "-jar", "plantuml.jar"), DiagramRenderer.command("java -jar plantuml.jar", "plantuml"));
    }

    @Test
    void formatFor_byExtensionDefaultingToPng() {
        assertEquals("svg", DiagramRenderer.formatFor(Path.of("/t/out.svg")));
        assertEquals("pdf", DiagramRenderer.formatFor(Path.of("/t/out.PDF")));
        assertEquals("png", DiagramRenderer.formatFor(Path.of("/t/out.png")));
        assertEquals("png", DiagramRenderer.formatFor(Path.of("/t/out.bmp"))); // unsupported → png
        assertEquals("png", DiagramRenderer.formatFor(Path.of("/t/noext")));
    }

    @Test
    void dotArgs_specifyOutputFile() {
        List<String> args = DiagramKind.DOT.args(
                List.of("dot"), Path.of("/t/diagram.dot"), Path.of("/t/diagram.png"), "png", false);
        assertEquals(List.of("dot", "-Tpng", "-o", "/t/diagram.png", "/t/diagram.dot"), args);
    }

    @Test
    void plantumlArgs_writeBesideInput() {
        List<String> args = DiagramKind.PLANTUML.args(
                List.of("plantuml"), Path.of("/t/diagram.puml"), Path.of("/t/diagram.svg"), "svg", false);
        // PlantUML derives the output name from the input, so no -o flag is emitted.
        assertEquals(List.of("plantuml", "-tsvg", "/t/diagram.puml"), args);
    }

    @Test
    void fromLanguage_mapsOnlyDiagramLanguages() {
        assertSame(DiagramKind.DOT, DiagramKind.fromLanguage("dot"));
        assertSame(DiagramKind.PLANTUML, DiagramKind.fromLanguage("plantuml"));
        assertNull(DiagramKind.fromLanguage("mermaid")); // Mermaid stays on its own path
        assertNull(DiagramKind.fromLanguage("markdown"));
        assertNull(DiagramKind.fromLanguage(null));
    }

    /**
     * A tool path with a space is normal — {@code C:\Program Files\Graphviz\bin\dot.exe}, or anything under
     * {@code ~/Library/Application Support/} — and the Settings page has a Browse… button, so it is trivially
     * reachable. Splitting the value on whitespace turned it into two argv tokens and the run died with "No
     * such file or directory". A value that IS a file is now taken whole; anything else still tokenizes, so a
     * multi-token command keeps working.
     */
    @Test
    void aConfiguredPathContainingASpaceIsNotSplit(@TempDir java.nio.file.Path tmp) throws java.io.IOException {
        java.nio.file.Path dir = java.nio.file.Files.createDirectories(tmp.resolve("My Tools"));
        java.nio.file.Path exe = java.nio.file.Files.writeString(dir.resolve("plantuml"), "#!/bin/sh\n");
        assertEquals(List.of(exe.toString()), DiagramRenderer.command(exe.toString(), "plantuml"));
    }

    @Test
    void aMultiTokenCommandStillTokenizes() {
        assertEquals(List.of("npx", "-y", "some-renderer"), DiagramRenderer.command("npx -y some-renderer", "dot"));
        assertEquals(List.of("dot"), DiagramRenderer.command("", "dot"), "blank falls back to the default");
        assertEquals(List.of("dot"), DiagramRenderer.command(null, "dot"));
    }

    /** A quoted path works too (tokenize is quote-aware), for a path that doesn't exist yet. */
    @Test
    void aQuotedPathIsOneToken() {
        assertEquals(List.of("/opt/My Tools/plantuml"), DiagramRenderer.command("\"/opt/My Tools/plantuml\"", "x"));
    }
}
