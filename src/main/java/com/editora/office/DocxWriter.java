package com.editora.office;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;

import com.editora.editor.MarkdownRenderer;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.Borders;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.UnderlinePatterns;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHyperlinkRun;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.ThematicBreak;

/**
 * Renders a Markdown document to a MS Word {@code .docx} via Apache POI's XWPF API, walking the same
 * CommonMark AST ({@link MarkdownRenderer#parseToDocument}) as the PDF/preview renderers. Covers headings,
 * paragraphs (bold/italic/strikethrough/underline/inline-code/links), bullet + ordered lists (one level of
 * nesting, prefix-numbered to avoid the heavy XWPF numbering API), block quotes, fenced/indented code
 * blocks, GFM tables, thematic breaks, and local/{@code data:} images. Mermaid/unknown fenced blocks fall
 * back to a monospace code block; remote/SVG images degrade to alt text.
 */
public final class DocxWriter {

    private static final String MONO = "Consolas";
    private static final String LINK_COLOR = "0563C1";
    private static final int MAX_IMAGE_PX = 480;

    private DocxWriter() {}

    public static void write(String markdown, Path baseDir, List<String> mmdc, Path out) throws IOException {
        Node ast = MarkdownRenderer.parseToDocument(markdown);
        try (XWPFDocument doc = new XWPFDocument()) {
            for (Node n = ast.getFirstChild(); n != null; n = n.getNext()) {
                block(doc, n, baseDir, mmdc, 0);
            }
            try (OutputStream os = Files.newOutputStream(out)) {
                doc.write(os);
            }
        }
    }

    private static void block(XWPFDocument doc, Node n, Path baseDir, List<String> mmdc, int indentLevel) {
        if (n instanceof Heading h) {
            XWPFParagraph p = doc.createParagraph();
            p.setStyle("Heading" + Math.min(6, h.getLevel()));
            int size =
                    switch (h.getLevel()) {
                        case 1 -> 22;
                        case 2 -> 18;
                        case 3 -> 15;
                        case 4 -> 13;
                        default -> 12;
                    };
            addRuns(p, InlineRun.flatten(h), baseDir, true, size);
        } else if (n instanceof Paragraph p) {
            String math = InlineRun.soleDisplayMath(p);
            if (math != null && embedBytes(doc.createParagraph(), OfficeImages.renderMath(math, true), null)) {
                return; // rendered the $$…$$ block as an image
            }
            if (isBlockImage(p)) {
                embedImage(doc.createParagraph(), (Image) p.getFirstChild(), baseDir);
            } else {
                XWPFParagraph par = doc.createParagraph();
                if (indentLevel > 0) {
                    par.setIndentationLeft(indentLevel * 360);
                }
                addRuns(par, InlineRun.flatten(p), baseDir, false, 0);
            }
        } else if (n instanceof BulletList bl) {
            list(doc, bl, baseDir, indentLevel, false, 0);
        } else if (n instanceof OrderedList ol) {
            list(doc, ol, baseDir, indentLevel, true, ol.getMarkerStartNumber());
        } else if (n instanceof BlockQuote bq) {
            for (Node c = bq.getFirstChild(); c != null; c = c.getNext()) {
                int before = doc.getParagraphs().size();
                block(doc, c, baseDir, mmdc, indentLevel + 1);
                for (int i = before; i < doc.getParagraphs().size(); i++) {
                    doc.getParagraphs().get(i).setIndentationLeft((indentLevel + 1) * 480);
                }
            }
        } else if (n instanceof FencedCodeBlock f) {
            if (InlineRun.isMermaidInfo(f.getInfo())
                    && embedBytes(doc.createParagraph(), OfficeImages.renderMermaid(mmdc, f.getLiteral()), null)) {
                return; // rendered the ```mermaid block as a diagram image
            }
            codeBlock(doc, f.getLiteral());
        } else if (n instanceof IndentedCodeBlock ic) {
            codeBlock(doc, ic.getLiteral());
        } else if (n instanceof ThematicBreak) {
            XWPFParagraph p = doc.createParagraph();
            p.setBorderBottom(Borders.SINGLE);
        } else if (n instanceof TableBlock t) {
            table(doc, t, baseDir);
        }
    }

    private static void list(
            XWPFDocument doc, Node listNode, Path baseDir, int indentLevel, boolean ordered, int start) {
        int idx = ordered ? Math.max(1, start) : 0;
        for (Node item = listNode.getFirstChild(); item != null; item = item.getNext()) {
            if (!(item instanceof ListItem)) {
                continue;
            }
            String marker = ordered ? (idx++ + ". ") : "• ";
            boolean first = true;
            for (Node c = item.getFirstChild(); c != null; c = c.getNext()) {
                if (c instanceof BulletList nestedB) {
                    list(doc, nestedB, baseDir, indentLevel + 1, false, 0);
                } else if (c instanceof OrderedList nestedO) {
                    list(doc, nestedO, baseDir, indentLevel + 1, true, nestedO.getMarkerStartNumber());
                } else if (c instanceof Paragraph p) {
                    XWPFParagraph par = doc.createParagraph();
                    par.setIndentationLeft((indentLevel + 1) * 360);
                    if (first) {
                        XWPFRun m = par.createRun();
                        m.setText(marker);
                    }
                    addRuns(par, InlineRun.flatten(p), baseDir, false, 0);
                    first = false;
                }
            }
        }
    }

    private static void codeBlock(XWPFDocument doc, String text) {
        String[] lines = text.replace("\r\n", "\n").split("\n", -1);
        // drop a trailing empty line from the fence
        int end = lines.length;
        if (end > 0 && lines[end - 1].isEmpty()) {
            end--;
        }
        for (int i = 0; i < end; i++) {
            XWPFParagraph p = doc.createParagraph();
            p.setSpacingAfter(0);
            XWPFRun r = p.createRun();
            r.setFontFamily(MONO);
            r.setFontSize(10);
            r.setText(lines[i]);
        }
    }

    private static void table(XWPFDocument doc, TableBlock t, Path baseDir) {
        List<List<TableCell>> rows = new java.util.ArrayList<>();
        for (Node section = t.getFirstChild(); section != null; section = section.getNext()) {
            for (Node r = section.getFirstChild(); r != null; r = r.getNext()) {
                if (!(r instanceof TableRow)) {
                    continue;
                }
                List<TableCell> cells = new java.util.ArrayList<>();
                for (Node c = r.getFirstChild(); c != null; c = c.getNext()) {
                    if (c instanceof TableCell tc) {
                        cells.add(tc);
                    }
                }
                rows.add(cells);
            }
        }
        if (rows.isEmpty()) {
            return;
        }
        int ncol = rows.stream().mapToInt(List::size).max().orElse(1);
        XWPFTable table = doc.createTable(rows.size(), ncol);
        for (int ri = 0; ri < rows.size(); ri++) {
            XWPFTableRow row = table.getRow(ri);
            List<TableCell> cells = rows.get(ri);
            for (int ci = 0; ci < ncol; ci++) {
                XWPFTableCell cell = row.getCell(ci);
                XWPFParagraph p = cell.getParagraphs().get(0);
                if (ci < cells.size()) {
                    addRuns(p, InlineRun.flatten(cells.get(ci)), baseDir, ri == 0, 0);
                }
            }
        }
    }

    /** Emits the styled inline runs into {@code p}; {@code forceBold}/{@code size} apply to headings/headers. */
    private static void addRuns(XWPFParagraph p, List<InlineRun> runs, Path baseDir, boolean forceBold, int size) {
        XWPFRun last = null;
        for (InlineRun ir : runs) {
            if (ir.isBreak()) {
                if (last == null) {
                    last = p.createRun();
                }
                last.addBreak();
                continue;
            }
            XWPFRun r;
            if (ir.href() != null && !ir.href().isBlank()) {
                XWPFHyperlinkRun hr = p.createHyperlinkRun(ir.href());
                hr.setColor(LINK_COLOR);
                hr.setUnderline(UnderlinePatterns.SINGLE);
                r = hr;
            } else {
                r = p.createRun();
            }
            r.setText(ir.text());
            if (ir.bold() || forceBold) {
                r.setBold(true);
            }
            if (ir.italic()) {
                r.setItalic(true);
            }
            if (ir.strike()) {
                r.setStrikeThrough(true);
            }
            if (ir.underline() && !(r instanceof XWPFHyperlinkRun)) {
                r.setUnderline(UnderlinePatterns.SINGLE);
            }
            if (ir.code()) {
                r.setFontFamily(MONO);
            }
            if (size > 0) {
                r.setFontSize(size);
            }
            last = r;
        }
        if (forceBold) {
            p.setAlignment(ParagraphAlignment.LEFT);
        }
    }

    private static boolean isBlockImage(Paragraph p) {
        Node c = p.getFirstChild();
        return c instanceof Image && c.getNext() == null;
    }

    private static void embedImage(XWPFParagraph p, Image img, Path baseDir) {
        byte[] bytes = OfficeImages.load(img.getDestination(), baseDir);
        String alt = InlineRun.textOf(img);
        String fallback = alt.isEmpty() ? img.getDestination() : alt;
        if (!embedBytes(p, bytes, fallback)) {
            XWPFRun r = p.createRun();
            r.setText(fallback);
            r.setItalic(true);
        }
    }

    /**
     * Embeds raster {@code bytes} as a scaled picture in {@code p}; returns false (so the caller can fall
     * back) when {@code bytes} is null or undecodable. When {@code altOnFail} is non-null a failed embed
     * writes it as italic text and still returns true (used by image embedding, which has its own fallback).
     */
    private static boolean embedBytes(XWPFParagraph p, byte[] bytes, String altOnFail) {
        if (bytes == null) {
            return false;
        }
        try {
            BufferedImage bi = ImageIO.read(new ByteArrayInputStream(bytes));
            if (bi == null) {
                throw new IOException("undecodable image");
            }
            int type = OfficeImages.poiPictureType(bytes);
            double scale = Math.min(1.0, (double) MAX_IMAGE_PX / Math.max(1, bi.getWidth()));
            int w = (int) Math.round(bi.getWidth() * scale);
            int h = (int) Math.round(bi.getHeight() * scale);
            XWPFRun r = p.createRun();
            r.addPicture(new ByteArrayInputStream(bytes), type, "image", Units.toEMU(w), Units.toEMU(h));
            return true;
        } catch (Exception e) {
            if (altOnFail != null) {
                XWPFRun r = p.createRun();
                r.setText(altOnFail);
                r.setItalic(true);
                return true;
            }
            return false;
        }
    }
}
