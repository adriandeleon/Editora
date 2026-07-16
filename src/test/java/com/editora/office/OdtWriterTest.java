package com.editora.office;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OdtWriterTest {

    private static final String MD = "# Title\n\n"
            + "Some **bold**, *italic*, ~~strike~~, `code`, and a [link](https://example.com).\n\n"
            + "- one\n- two\n\n"
            + "1. first\n2. second\n\n"
            + "> a quote\n\n"
            + "| H1 | H2 |\n|----|----|\n| a | b |\n\n"
            + "```\ncode line\n```\n\n"
            + "---\n";

    @Test
    void contentXmlCoversTheCoreBlocks() {
        String xml = OdtWriter.contentXml(MD, null, null, new ArrayList<>());
        assertTrue(xml.contains("<text:h text:style-name=\"Heading_20_1\""), "heading");
        assertTrue(xml.contains("text:style-name=\"TBold\""), "bold span");
        assertTrue(xml.contains("text:style-name=\"TItalic\""), "italic span");
        assertTrue(xml.contains("text:style-name=\"TStrike\""), "strike span");
        assertTrue(xml.contains("text:style-name=\"TCode\""), "inline code span");
        assertTrue(xml.contains("xlink:href=\"https://example.com\""), "link");
        assertTrue(xml.contains("<text:list text:style-name=\"L1\""), "bullet list");
        assertTrue(xml.contains("<text:list text:style-name=\"L2\""), "ordered list");
        assertTrue(xml.contains("text:style-name=\"Quotations\""), "block quote");
        assertTrue(xml.contains("<table:table"), "table");
        assertTrue(xml.contains("text:style-name=\"Preformatted_20_Text\""), "code block");
        assertTrue(xml.contains("text:style-name=\"Horizontal_20_Line\""), "thematic break");
    }

    @Test
    void contentXmlIsWellFormed() {
        String xml = OdtWriter.contentXml(MD, null, null, new ArrayList<>());
        assertDoesNotThrow(() -> {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        });
    }

    @Test
    void embedsADataUriImage() {
        // 1×1 transparent PNG
        String png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";
        java.util.List<OdtWriter.Embedded> imgs = new ArrayList<>();
        String xml = OdtWriter.contentXml("![alt](data:image/png;base64," + png + ")", null, null, imgs);
        assertTrue(xml.contains("<draw:frame"), "draw:frame emitted");
        assertTrue(imgs.size() == 1, "one image collected");
        assertTrue(imgs.get(0).name().startsWith("Pictures/img0."), "Pictures entry");
    }

    @Test
    void escapesSpecialCharacters() {
        String xml = OdtWriter.contentXml("a < b & c > d", null, null, new ArrayList<>());
        assertTrue(xml.contains("a &lt; b &amp; c &gt; d"));
    }

    /**
     * A control character in the source used to go straight into {@code content.xml}. XML 1.0 forbids them
     * outright — they can't even be written as a numeric character reference — so the {@code .odt} was
     * produced without error and then refused as corrupt by LibreOffice/Word. A form feed (Emacs {@code ^L}
     * section markers) or a BEL/ESC out of pasted terminal output is enough to trigger it. The DOCX side was
     * never exposed: POI sanitizes these for us; only this hand-rolled writer had to.
     */
    @Test
    void controlCharactersDoNotProduceNonWellFormedXml() {
        for (char c : new char[] {0x0B, 0x0C, 0x07, 0x1B, 0x01}) {
            for (String md : new String[] {
                "before" + c + "after",
                "| H |\n|---|\n| a" + c + "b |",
                "```\ncode" + c + "here\n```",
                "[link](https://example.com/a" + c + "b)"
            }) {
                String xml = OdtWriter.contentXml(md, null, null, new ArrayList<>());
                assertDoesNotThrow(
                        () -> {
                            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
                            f.setNamespaceAware(true);
                            f.newDocumentBuilder()
                                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
                        },
                        "U+" + Integer.toHexString(c) + " in: " + md.replace("\n", "\\n"));
            }
        }
    }

    /** Tab/newline are legal XML and must survive, as must a code point outside the BMP. */
    @Test
    void legalCharactersAreNotStripped() {
        assertEquals("a\tb\nc", OdtWriter.stripInvalidXml("a\tb\nc"));
        assertEquals("emoji 😀 ok", OdtWriter.stripInvalidXml("emoji 😀 ok"), "surrogate pair");
        assertEquals("a�b", OdtWriter.stripInvalidXml("ab"), "a control char is replaced, not kept");
    }
}
