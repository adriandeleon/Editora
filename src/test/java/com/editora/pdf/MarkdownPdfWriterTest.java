package com.editora.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarkdownPdfWriterTest {

    @Test
    void writesMarkdownPdfWithCommonElements(@TempDir Path dir) throws Exception {
        String md = """
                # Title

                A paragraph with **bold**, *italic*, `code`, and a [link](https://example.com) that is
                long enough to wrap across more than a single line in the output document for sure.

                - bullet one
                - bullet two

                1. first
                2. second

                > a block quote

                | A | B |
                |---|---|
                | 1 | 2 |

                ```java
                int x = 1;
                ```

                ```mermaid
                flowchart TD
                  A --> B
                ```

                ---
                """;
        Path out = dir.resolve("md.pdf");
        // mmdcCommand=null => the mermaid block degrades to a code block (no external tool needed).
        MarkdownPdfWriter.write(md, dir, "letter", null, out);

        assertTrue(Files.exists(out));
        byte[] bytes = Files.readAllBytes(out);
        assertEquals("%PDF", new String(bytes, 0, 4));
        assertTrue(bytes.length > 800, "non-trivial PDF: " + bytes.length);
    }

    @Test
    void emptyMarkdownStillProducesValidPdf(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("empty.pdf");
        MarkdownPdfWriter.write("", null, "a4", null, out);
        assertEquals("%PDF", new String(Files.readAllBytes(out), 0, 4));
    }

    @Test
    void unicodeSymbolsDoNotAbortExport(@TempDir Path dir) throws Exception {
        // Characters outside WinAnsi (arrows, checks, math) previously threw in width measurement.
        // Note: `phase‑N` uses a NON-BREAKING HYPHEN (U+2011) inside inline code — the embedded
        // mono font has no glyph for it and threw at draw time even though width measurement passed.
        String md = """
                # Flow A → B ⇒ C

                Steps: up ↑, down ↓, ok ✓, fail ✗, ≠ ≤ ≥, 3 × 4, an em… ellipsis.

                Inline code with a non‑breaking hyphen: `phase‑N‑tasks.md`.

                | Stage | Result |
                |-------|--------|
                | parse → render | ✓ |
                """;
        Path out = dir.resolve("uni.pdf");
        MarkdownPdfWriter.write(md, dir, "letter", null, out);
        assertEquals("%PDF", new String(Files.readAllBytes(out), 0, 4));
    }

    @Test
    void encodableReplacesUnsupportedCharsWithAsciiFallback() throws Exception {
        var font = new org.apache.pdfbox.pdmodel.font.PDType1Font(
                org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA);
        assertEquals("A -> B", MarkdownPdfWriter.encodable(font, "A → B"));
        assertEquals("plain ascii", MarkdownPdfWriter.encodable(font, "plain ascii"));
        // An unmapped, unencodable char (CJK) degrades to '?'.
        assertEquals("x?y", MarkdownPdfWriter.encodable(font, "x中y"));
    }
}
