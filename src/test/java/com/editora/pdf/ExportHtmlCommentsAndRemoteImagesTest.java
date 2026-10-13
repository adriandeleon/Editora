package com.editora.pdf;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.editora.editor.MarkdownDocInfo;
import com.editora.editor.PreviewImageLoader;
import com.editora.office.DocxWriter;
import com.editora.office.OdtWriter;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two things DOCX, ODT and PDF decide the same way: an HTML comment is never written but the text sharing
 * its block is, and a remote image that is not fetched (the setting is off) is written as its alt text.
 */
class ExportHtmlCommentsAndRemoteImagesTest {

    @TempDir
    Path dir;

    private String docx(String md) throws Exception {
        Path out = dir.resolve("o.docx");
        DocxWriter.write(md, dir, null, out);
        try (InputStream in = Files.newInputStream(out);
                XWPFDocument doc = new XWPFDocument(in);
                var text = new org.apache.poi.xwpf.extractor.XWPFWordExtractor(doc)) {
            assertEquals(0, doc.getAllPictures().size(), "no picture was embedded");
            return text.getText();
        }
    }

    private String odt(String md) throws Exception {
        Path out = dir.resolve("o.odt");
        OdtWriter.write(md, dir, null, out);
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(out))) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                assertFalse(e.getName().startsWith("Pictures/"), "no picture was embedded");
                if (e.getName().equals("content.xml")) {
                    String xml = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                    return xml.substring(xml.indexOf("<office:body")).replaceAll("<[^>]*>", " ");
                }
            }
        }
        throw new AssertionError("no content.xml");
    }

    private String pdf(String md) throws Exception {
        Path out = dir.resolve("o.pdf");
        MarkdownPdfWriter.write(md, dir, "letter", null, out);
        assertEquals(0, PdfProbe.images(out).size(), "no picture was embedded");
        return PdfProbe.text(out);
    }

    @Test
    void aCommentIsNeverWrittenAndTheTextBesideItIs() throws Exception {
        String md = "before\n\n<!-- hidden note --> shown beside\n\n<!-- only a comment -->\n\n"
                + "<!--\nmulti\nline\n-->\n\n<div>kept markup</div> <!-- trailing -->\n\nInline <!-- gone --> text.\n\nafter\n";
        for (String out : new String[] {docx(md), odt(md), pdf(md)}) {
            String text = out.replaceAll("\\s+", " ");
            assertTrue(text.contains("shown beside"), text);
            assertTrue(text.contains("kept markup"), "raw HTML is still shown as its source: " + text);
            assertTrue(text.contains("Inline") && text.contains("text."), text);
            assertTrue(text.contains("before") && text.contains("after"), text);
            for (String hidden :
                    new String[] {"hidden note", "only a comment", "multi", "trailing", "gone", "<!--", "-->"}) {
                assertFalse(text.contains(hidden), "'" + hidden + "' must not be written: " + text);
            }
        }
    }

    @Test
    void whatIsShownOfARawHtmlBlock() {
        assertEquals("text", MarkdownDocInfo.htmlSourceShown("<!-- c --> text\n"));
        assertEquals("", MarkdownDocInfo.htmlSourceShown("<!-- a -->\n<!-- b -->\n"));
        assertEquals("", MarkdownDocInfo.htmlSourceShown("<!-- never closed\nto the end"));
        assertEquals("<div>\n  x\n</div>", MarkdownDocInfo.htmlSourceShown("<div>\n<!-- c -->\n  x\n</div>\n"));
        assertEquals("", MarkdownDocInfo.htmlSourceShown(null));
    }

    @Test
    void aRemoteImageThatIsNotFetchedIsItsAltText() throws Exception {
        boolean was = PreviewImageLoader.remoteImages();
        PreviewImageLoader.setRemoteImages(false);
        try {
            String md = "![the build badge](https://example.invalid/badge.png)\n\n"
                    + "In a line ![inline alt](https://example.invalid/x.png) too.\n";
            for (String out : new String[] {docx(md), odt(md), pdf(md)}) {
                String text = out.replaceAll("\\s+", " ");
                assertTrue(text.contains("the build badge"), text);
                assertTrue(text.contains("inline alt"), text);
            }
        } finally {
            PreviewImageLoader.setRemoteImages(was);
        }
    }
}
