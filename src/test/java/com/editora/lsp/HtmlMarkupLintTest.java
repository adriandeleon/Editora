package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.markdown.MarkdownLint;
import com.editora.text.ColorSwatch;
import com.editora.text.LspDiagnostic;
import org.eclipse.lsp4j.Color;
import org.eclipse.lsp4j.ColorInformation;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The markup check as diagnostics (HTML review D5) and the server's colours as swatches (D9). */
class HtmlMarkupLintTest {

    private static final LspDiagnostic CSS =
            new LspDiagnostic(8, 35, 8, 39, LspDiagnostic.Severity.WARNING, "Unknown property: 'colr'", null, "css");

    @Test
    void theChecksProblemsJoinTheHtmlServersOwn(@TempDir Path dir) throws Exception {
        Path page = dir.resolve("index.html");
        Files.writeString(dir.resolve("site.css"), "");
        String text =
                "<link href=\"site.css\" rel=stylesheet>\n<main>\n  <div><a href=\"missing.html\">x</a>\n</main>\n";
        List<LspDiagnostic> merged = HtmlMarkupLint.merged("html", page, text, dir, List.of(CSS), true);
        assertEquals(3, merged.size(), merged.toString());
        assertSame(CSS, merged.get(0), "the server's own come first, untouched");
        LspDiagnostic div = merged.get(1);
        assertEquals(2, div.startLine());
        assertEquals(3, div.startCol());
        assertEquals(6, div.endCol());
        assertEquals(LspDiagnostic.Severity.WARNING, div.severity());
        assertEquals(HtmlMarkupLint.SOURCE, div.source());
        assertEquals("unclosed-element", div.code());
        assertEquals(tr("html.check.unclosed", "div"), div.message());
        LspDiagnostic missing = merged.get(2);
        assertEquals("missing-file", missing.code());
        assertEquals(tr("html.check.missingFile", "missing.html"), missing.message());
        assertEquals(text.split("\n")[2].indexOf("missing.html"), missing.startCol());
    }

    @Test
    void otherServersOtherFilesAnUnchangedReportAndTheSwitchLeaveItAlone(@TempDir Path dir) {
        String text = "<div>";
        List<LspDiagnostic> server = List.of(CSS);
        assertSame(server, HtmlMarkupLint.merged("css", dir.resolve("a.html"), text, dir, server, true));
        assertSame(server, HtmlMarkupLint.merged("html", dir.resolve("a.njk"), text, dir, server, true));
        assertSame(server, HtmlMarkupLint.merged("html", dir.resolve("a.html"), text, dir, server, false));
        assertSame(server, HtmlMarkupLint.merged("html", dir.resolve("a.html"), null, dir, server, true));
        assertNull(HtmlMarkupLint.merged("html", dir.resolve("a.html"), text, dir, null, true));
        assertTrue(HtmlMarkupLint.applies(dir.resolve("A.HTM")));
        assertFalse(HtmlMarkupLint.applies(dir.resolve("page.xhtml")), "XHTML is XML: lemminx checks it");
        assertFalse(HtmlMarkupLint.applies(null));
    }

    @Test
    void withoutAServerTheyAreTheBuffersOwnOneBasedMarks() {
        List<MarkdownLint.Diagnostic> marks = HtmlMarkupLint.marks("<p>\n <span>x</div>", null, null);
        assertEquals(2, marks.size(), marks.toString());
        MarkdownLint.Diagnostic stray = marks.get(1);
        assertEquals(2, stray.line());
        assertEquals(9, stray.column());
        assertEquals("</div>".length(), stray.length());
        assertEquals(HtmlMarkupLint.SOURCE, stray.code());
        assertEquals(tr("html.check.strayEnd", "div"), stray.message());
        assertEquals(tr("html.check.unclosed", "span"), marks.get(0).message());
    }

    @Test
    void theServersColoursBecomeSwatchesInDocumentOrder() {
        ColorInformation red = new ColorInformation(range(8, 41), new Color(1, 0, 0, 1));
        ColorInformation translucent = new ColorInformation(range(2, 4), new Color(0, 0.5, 1, 0.5));
        List<ColorSwatch> swatches = LspManager.swatches(List.of(red, translucent));
        assertEquals(2, swatches.size());
        assertEquals(2, swatches.get(0).line());
        assertEquals("0080ff80", swatches.get(0).hex());
        assertEquals(8, swatches.get(1).line());
        assertEquals(41, swatches.get(1).column());
        assertEquals("ff0000ff", swatches.get(1).hex());
        assertEquals(List.of(), LspManager.swatches(null));
        assertEquals("000000ff", new ColorSwatch(0, 0, -1, Double.NaN, 0, 2).hex(), "channels are clamped");
    }

    private static Range range(int line, int character) {
        return new Range(new Position(line, character), new Position(line, character + 3));
    }
}
