package com.editora.typst;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for the Typst CLI facade: command resolution, argv builders, output naming, page sorting. */
class TypstRendererTest {

    @Test
    void command_usesDefaultWhenBlankOrNull() {
        assertEquals(List.of("typst"), TypstRenderer.command(""));
        assertEquals(List.of("typst"), TypstRenderer.command(null));
        assertEquals(List.of("typst"), TypstRenderer.command("   "));
    }

    @Test
    void command_tokenizesConfiguredValue() {
        assertEquals(List.of("/opt/typst"), TypstRenderer.command("/opt/typst"));
        assertEquals(List.of("typst", "--verbose"), TypstRenderer.command("typst --verbose"));
    }

    @Test
    void formatFor_byExtensionDefaultingToPdf() {
        assertEquals("png", TypstRenderer.formatFor("out.png"));
        assertEquals("svg", TypstRenderer.formatFor("out.SVG"));
        assertEquals("pdf", TypstRenderer.formatFor("out.pdf"));
        assertEquals("pdf", TypstRenderer.formatFor("out.bmp")); // unsupported → pdf
        assertEquals("pdf", TypstRenderer.formatFor("noext"));
    }

    @Test
    void renderArgs_useRootPngAndPpiWithPageTemplate() {
        List<String> a = TypstRenderer.renderArgs(
                List.of("typst"), Path.of("/proj"), Path.of("/proj/in.typ"), Path.of("/out/page-{p}.png"), 192);
        assertEquals(
                List.of(
                        "typst",
                        "compile",
                        "--root",
                        "/proj",
                        "-f",
                        "png",
                        "--ppi",
                        "192",
                        "/proj/in.typ",
                        "/out/page-{p}.png"),
                a);
    }

    @Test
    void exportArgs_pdfOmitsPpi_pngKeepsIt() {
        List<String> pdf =
                TypstRenderer.exportArgs(List.of("typst"), Path.of("/proj"), Path.of("/proj/in.typ"), "/o/r.pdf", 192);
        assertEquals(List.of("typst", "compile", "--root", "/proj", "-f", "pdf", "/proj/in.typ", "/o/r.pdf"), pdf);
        List<String> png = TypstRenderer.exportArgs(
                List.of("typst"), Path.of("/proj"), Path.of("/proj/in.typ"), "/o/r-{p}.png", 192);
        assertTrue(png.contains("--ppi"));
        assertEquals("png", png.get(png.indexOf("-f") + 1));
    }

    @Test
    void exportOutput_pdfStaysSingle_imageGetsPageTemplate() {
        assertEquals(Path.of("/o/report.pdf").toString(), TypstRenderer.exportOutput(Path.of("/o/report.pdf")));
        assertEquals(Path.of("/o/report-{p}.png").toString(), TypstRenderer.exportOutput(Path.of("/o/report.png")));
        assertEquals(Path.of("/o/report-{p}.svg").toString(), TypstRenderer.exportOutput(Path.of("/o/report.svg")));
    }

    @Test
    void effectiveRoot_usesRootWhenItContainsInputDirElseTheInputDir() {
        Path in = Path.of("/proj/chapters");
        assertEquals(Path.of("/proj"), TypstRenderer.effectiveRoot(in, Path.of("/proj"))); // root contains input
        assertEquals(in, TypstRenderer.effectiveRoot(in, null)); // no root → the input dir
        assertEquals(in, TypstRenderer.effectiveRoot(in, Path.of("/other"))); // root doesn't contain → input dir
        assertEquals(in, TypstRenderer.effectiveRoot(in, in)); // equal → that dir
    }

    @Test
    void sortPageFiles_ordersNumericallyNotLexically(@TempDir Path dir) throws IOException {
        // Lexical order would put page-10 before page-2; numeric order must not.
        for (int i : new int[] {1, 2, 10, 3}) {
            Files.writeString(dir.resolve("page-" + i + ".png"), "x");
        }
        Files.writeString(dir.resolve("notes.txt"), "ignored");
        List<Path> sorted = TypstRenderer.sortPageFiles(dir);
        assertEquals(
                List.of("page-1.png", "page-2.png", "page-3.png", "page-10.png"),
                sorted.stream().map(p -> p.getFileName().toString()).toList());
    }
}
