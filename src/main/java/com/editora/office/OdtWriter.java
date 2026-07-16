package com.editora.office;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;

import com.editora.editor.MarkdownRenderer;
import com.editora.editor.MathImages;
import com.editora.editor.MathSpans;
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
 * Renders a Markdown document to an OpenDocument Text ({@code .odt}) file — hand-written (a ZIP of XML, via
 * {@link ZipOutputStream}) rather than via the ODF Toolkit, whose transitive Apache Jena + Xerces deps
 * break the modular/jlink build. Walks the same CommonMark AST as the PDF/DOCX writers; covers headings,
 * paragraphs (bold/italic/strikethrough/underline/inline-code/links), bullet + ordered lists (nested),
 * block quotes, code blocks, GFM tables, thematic breaks, and local/{@code data:} images. The pure
 * {@link #contentXml} is unit-tested; remote/SVG images degrade to alt text.
 */
public final class OdtWriter {

    private static final String MIMETYPE = "application/vnd.oasis.opendocument.text";

    private OdtWriter() {}

    /** An image embedded under {@code Pictures/}. */
    record Embedded(String name, byte[] bytes, String mediaType) {}

    public static void write(String markdown, Path baseDir, List<String> mmdc, Path out) throws IOException {
        List<Embedded> images = new ArrayList<>();
        String content = contentXml(markdown, baseDir, mmdc, images);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(out))) {
            // The ODF spec requires `mimetype` to be the first entry and STORED (uncompressed).
            stored(zip, "mimetype", MIMETYPE.getBytes(StandardCharsets.US_ASCII));
            deflated(zip, "content.xml", content.getBytes(StandardCharsets.UTF_8));
            deflated(zip, "styles.xml", STYLES_XML.getBytes(StandardCharsets.UTF_8));
            deflated(zip, "META-INF/manifest.xml", manifest(images).getBytes(StandardCharsets.UTF_8));
            for (Embedded e : images) {
                deflated(zip, e.name(), e.bytes());
            }
        }
    }

    // ---- content.xml (pure, unit-tested) ----

    static String contentXml(String markdown, Path baseDir, List<String> mmdc, List<Embedded> images) {
        Node ast = MarkdownRenderer.parseToDocument(markdown);
        StringBuilder b = new StringBuilder();
        b.append(CONTENT_HEAD);
        for (Node n = ast.getFirstChild(); n != null; n = n.getNext()) {
            block(b, n, baseDir, mmdc, images);
        }
        b.append(CONTENT_TAIL);
        return b.toString();
    }

    private static void block(StringBuilder b, Node n, Path baseDir, List<String> mmdc, List<Embedded> images) {
        if (n instanceof Heading h) {
            int lvl = Math.min(6, h.getLevel());
            b.append("<text:h text:style-name=\"Heading_20_")
                    .append(lvl)
                    .append("\" text:outline-level=\"")
                    .append(lvl)
                    .append("\">");
            inline(b, InlineRun.flatten(h), images);
            b.append("</text:h>");
        } else if (n instanceof Paragraph p) {
            String math = InlineRun.soleDisplayMath(p);
            byte[] mathPng = math == null ? null : OfficeImages.renderMath(math, true);
            if (mathPng != null) {
                b.append("<text:p text:style-name=\"Standard\">");
                imageFrameBytes(b, mathPng, images);
                b.append("</text:p>");
            } else if (isBlockImage(p)) {
                b.append("<text:p text:style-name=\"Standard\">");
                imageFrame(b, (Image) p.getFirstChild(), baseDir, images);
                b.append("</text:p>");
            } else {
                paragraph(b, "Standard", InlineRun.flatten(p), images);
            }
        } else if (n instanceof BulletList bl) {
            listXml(b, bl, false, baseDir, mmdc, images);
        } else if (n instanceof OrderedList ol) {
            listXml(b, ol, true, baseDir, mmdc, images);
        } else if (n instanceof BlockQuote bq) {
            for (Node c = bq.getFirstChild(); c != null; c = c.getNext()) {
                if (c instanceof Paragraph p) {
                    paragraph(b, "Quotations", InlineRun.flatten(p), images);
                } else {
                    block(b, c, baseDir, mmdc, images);
                }
            }
        } else if (n instanceof FencedCodeBlock f) {
            byte[] png = InlineRun.isMermaidInfo(f.getInfo()) ? OfficeImages.renderMermaid(mmdc, f.getLiteral()) : null;
            if (png != null) {
                b.append("<text:p text:style-name=\"Standard\">");
                imageFrameBytes(b, png, images);
                b.append("</text:p>");
            } else {
                codeBlock(b, f.getLiteral());
            }
        } else if (n instanceof IndentedCodeBlock ic) {
            codeBlock(b, ic.getLiteral());
        } else if (n instanceof ThematicBreak) {
            b.append("<text:p text:style-name=\"Horizontal_20_Line\"/>");
        } else if (n instanceof TableBlock t) {
            tableXml(b, t, images);
        }
    }

    private static void paragraph(StringBuilder b, String style, List<InlineRun> runs, List<Embedded> images) {
        b.append("<text:p text:style-name=\"").append(style).append("\">");
        inline(b, runs, images);
        b.append("</text:p>");
    }

    private static void codeBlock(StringBuilder b, String text) {
        String[] lines = text.replace("\r\n", "\n").split("\n", -1);
        int end = lines.length;
        if (end > 0 && lines[end - 1].isEmpty()) {
            end--;
        }
        for (int i = 0; i < end; i++) {
            b.append("<text:p text:style-name=\"Preformatted_20_Text\">");
            b.append(preformatted(lines[i]));
            b.append("</text:p>");
        }
    }

    private static void listXml(
            StringBuilder b, Node listNode, boolean ordered, Path baseDir, List<String> mmdc, List<Embedded> images) {
        b.append("<text:list text:style-name=\"").append(ordered ? "L2" : "L1").append("\">");
        for (Node item = listNode.getFirstChild(); item != null; item = item.getNext()) {
            if (!(item instanceof ListItem)) {
                continue;
            }
            b.append("<text:list-item>");
            for (Node c = item.getFirstChild(); c != null; c = c.getNext()) {
                if (c instanceof BulletList nb) {
                    listXml(b, nb, false, baseDir, mmdc, images);
                } else if (c instanceof OrderedList no) {
                    listXml(b, no, true, baseDir, mmdc, images);
                } else if (c instanceof Paragraph p) {
                    paragraph(b, "Standard", InlineRun.flatten(p), images);
                } else {
                    block(b, c, baseDir, mmdc, images);
                }
            }
            b.append("</text:list-item>");
        }
        b.append("</text:list>");
    }

    private static void tableXml(StringBuilder b, TableBlock t, List<Embedded> images) {
        List<List<TableCell>> rows = new ArrayList<>();
        for (Node section = t.getFirstChild(); section != null; section = section.getNext()) {
            for (Node r = section.getFirstChild(); r != null; r = r.getNext()) {
                if (!(r instanceof TableRow)) {
                    continue;
                }
                List<TableCell> cells = new ArrayList<>();
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
        b.append("<table:table table:name=\"Table1\">");
        b.append("<table:table-column table:number-columns-repeated=\"")
                .append(ncol)
                .append("\"/>");
        for (List<TableCell> row : rows) {
            b.append("<table:table-row>");
            for (int ci = 0; ci < ncol; ci++) {
                b.append("<table:table-cell office:value-type=\"string\">");
                b.append("<text:p text:style-name=\"Standard\">");
                if (ci < row.size()) {
                    inline(b, InlineRun.flatten(row.get(ci)), images);
                }
                b.append("</text:p></table:table-cell>");
            }
            b.append("</table:table-row>");
        }
        b.append("</table:table>");
    }

    /** Appends inline runs, wrapping each in nested {@code <text:span>}s per active style + {@code <text:a>} links. */
    private static void inline(StringBuilder b, List<InlineRun> runs, List<Embedded> images) {
        for (InlineRun r : runs) {
            if (r.isBreak()) {
                b.append("<text:line-break/>");
                continue;
            }
            boolean link = r.href() != null && !r.href().isBlank();
            if (link) {
                b.append("<text:a xlink:type=\"simple\" xlink:href=\"")
                        .append(escAttr(r.href()))
                        .append("\">");
            }
            int spans = 0;
            if (r.bold()) {
                b.append("<text:span text:style-name=\"TBold\">");
                spans++;
            }
            if (r.italic()) {
                b.append("<text:span text:style-name=\"TItalic\">");
                spans++;
            }
            if (r.strike()) {
                b.append("<text:span text:style-name=\"TStrike\">");
                spans++;
            }
            if (r.underline()) {
                b.append("<text:span text:style-name=\"TUnderline\">");
                spans++;
            }
            if (r.code()) {
                b.append("<text:span text:style-name=\"TCode\">");
                spans++;
            }
            emitText(b, r, images);
            for (int i = 0; i < spans; i++) {
                b.append("</text:span>");
            }
            if (link) {
                b.append("</text:a>");
            }
        }
    }

    /** Appends a run's text, splitting non-code text on inline {@code $…$}/{@code $$…$$} math → as-char frames. */
    private static void emitText(StringBuilder b, InlineRun r, List<Embedded> images) {
        if (r.code() || r.text().indexOf('$') < 0 || !MathImages.isEnabled()) {
            b.append(esc(r.text()));
            return;
        }
        for (MathSpans.Segment seg : MathSpans.segments(r.text())) {
            if (seg.text() != null) {
                b.append(esc(seg.text()));
            } else {
                byte[] png =
                        OfficeImages.renderMath(seg.span().latex(), seg.span().display());
                if (!mathFrame(b, png, images, seg.span().display())) {
                    String d = seg.span().display() ? "$$" : "$";
                    b.append(esc(d + seg.span().latex() + d));
                }
            }
        }
    }

    /** Embeds {@code png} as an as-char {@code <draw:frame>} sized to roughly the line height; false if undecodable. */
    private static boolean mathFrame(StringBuilder b, byte[] png, List<Embedded> images, boolean display) {
        if (png == null) {
            return false;
        }
        try {
            BufferedImage bi = ImageIO.read(new ByteArrayInputStream(png));
            if (bi == null || bi.getHeight() <= 0) {
                return false;
            }
            double hCm = display ? 0.85 : 0.42; // ~ line height
            double wCm = bi.getWidth() * (hCm / bi.getHeight());
            String name = "Pictures/math" + images.size() + "." + OfficeImages.extension(png);
            images.add(new Embedded(name, png, OfficeImages.mediaType(png)));
            b.append("<draw:frame text:anchor-type=\"as-char\" svg:width=\"")
                    .append(String.format(java.util.Locale.ROOT, "%.3fcm", wCm))
                    .append("\" svg:height=\"")
                    .append(String.format(java.util.Locale.ROOT, "%.3fcm", hCm))
                    .append("\"><draw:image xlink:href=\"")
                    .append(escAttr(name))
                    .append("\" xlink:type=\"simple\" xlink:show=\"embed\" xlink:actuate=\"onLoad\"/></draw:frame>");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void imageFrame(StringBuilder b, Image img, Path baseDir, List<Embedded> images) {
        byte[] bytes = OfficeImages.load(img.getDestination(), baseDir);
        if (!imageFrameBytes(b, bytes, images)) {
            String alt = InlineRun.textOf(img);
            b.append(esc(alt.isEmpty() ? img.getDestination() : alt));
        }
    }

    /** Embeds raster {@code bytes} as a {@code <draw:frame>} + a {@code Pictures/} entry; false if undecodable. */
    private static boolean imageFrameBytes(StringBuilder b, byte[] bytes, List<Embedded> images) {
        if (bytes == null) {
            return false;
        }
        try {
            BufferedImage bi = ImageIO.read(new ByteArrayInputStream(bytes));
            if (bi == null) {
                throw new IOException("undecodable");
            }
            double scale = Math.min(1.0, 480.0 / Math.max(1, bi.getWidth()));
            double wCm = bi.getWidth() * scale / 96.0 * 2.54;
            double hCm = bi.getHeight() * scale / 96.0 * 2.54;
            String name = "Pictures/img" + images.size() + "." + OfficeImages.extension(bytes);
            images.add(new Embedded(name, bytes, OfficeImages.mediaType(bytes)));
            b.append("<draw:frame text:anchor-type=\"as-char\" svg:width=\"")
                    .append(String.format(java.util.Locale.ROOT, "%.3fcm", wCm))
                    .append("\" svg:height=\"")
                    .append(String.format(java.util.Locale.ROOT, "%.3fcm", hCm))
                    .append("\"><draw:image xlink:href=\"")
                    .append(escAttr(name))
                    .append("\" xlink:type=\"simple\" xlink:show=\"embed\" xlink:actuate=\"onLoad\"/></draw:frame>");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isBlockImage(Paragraph p) {
        Node c = p.getFirstChild();
        return c instanceof Image && c.getNext() == null;
    }

    private static String manifest(List<Embedded> images) {
        StringBuilder b = new StringBuilder();
        b.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        b.append(
                "<manifest:manifest xmlns:manifest=\"urn:oasis:names:tc:opendocument:xmlns:manifest:1.0\" manifest:version=\"1.2\">");
        b.append("<manifest:file-entry manifest:full-path=\"/\" manifest:media-type=\"")
                .append(MIMETYPE)
                .append("\"/>");
        b.append("<manifest:file-entry manifest:full-path=\"content.xml\" manifest:media-type=\"text/xml\"/>");
        b.append("<manifest:file-entry manifest:full-path=\"styles.xml\" manifest:media-type=\"text/xml\"/>");
        for (Embedded e : images) {
            b.append("<manifest:file-entry manifest:full-path=\"")
                    .append(e.name())
                    .append("\" manifest:media-type=\"")
                    .append(e.mediaType())
                    .append("\"/>");
        }
        b.append("</manifest:manifest>");
        return b.toString();
    }

    // ---- ZIP helpers ----

    private static void stored(ZipOutputStream zip, String name, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setMethod(ZipEntry.STORED);
        e.setSize(data.length);
        e.setCompressedSize(data.length);
        CRC32 crc = new CRC32();
        crc.update(data);
        e.setCrc(crc.getValue());
        zip.putNextEntry(e);
        zip.write(data);
        zip.closeEntry();
    }

    private static void deflated(ZipOutputStream zip, String name, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setMethod(ZipEntry.DEFLATED);
        zip.putNextEntry(e);
        zip.write(data);
        zip.closeEntry();
    }

    // ---- escaping ----

    private static String esc(String s) {
        return stripInvalidXml(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Drops characters XML 1.0 forbids outright — the C0 controls other than tab/LF/CR, and unpaired
     * surrogates. They cannot be escaped (not even as a numeric character reference), so leaving one in
     * makes {@code content.xml} non-well-formed and the whole {@code .odt} unopenable ("file is corrupt")
     * with no error at export time. A form feed (Emacs {@code ^L} section markers) or a stray BEL/ESC from
     * pasted terminal output is enough. POI does the same for the {@code .docx} side, which is why only this
     * hand-rolled writer was exposed.
     */
    static String stripInvalidXml(String s) {
        if (s == null || s.isEmpty()) {
            return s == null ? "" : s;
        }
        StringBuilder sb = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = c == '\t'
                    || c == '\n'
                    || c == '\r'
                    || (c >= 0x20 && c <= 0xD7FF)
                    || (c >= 0xE000 && c <= 0xFFFD)
                    || Character.isSurrogate(c) && hasPair(s, i, c);
            if (ok) {
                if (sb != null) {
                    sb.append(c);
                }
            } else {
                if (sb == null) {
                    sb = new StringBuilder(s.length()).append(s, 0, i);
                }
                sb.append('�'); // same visible fallback commonmark uses for NUL
            }
        }
        return sb == null ? s : sb.toString();
    }

    /** True when the surrogate at {@code i} is part of a well-formed pair (so the code point is legal). */
    private static boolean hasPair(String s, int i, char c) {
        return Character.isHighSurrogate(c)
                ? i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))
                : i > 0 && Character.isHighSurrogate(s.charAt(i - 1));
    }

    private static String escAttr(String s) {
        return esc(s).replace("\"", "&quot;");
    }

    /** Code text with leading/runs of spaces preserved via {@code <text:s>} and tabs via {@code <text:tab/>}. */
    private static String preformatted(String line) {
        StringBuilder b = new StringBuilder();
        int i = 0;
        while (i < line.length()) {
            char ch = line.charAt(i);
            if (ch == ' ') {
                int j = i;
                while (j < line.length() && line.charAt(j) == ' ') {
                    j++;
                }
                int count = j - i;
                if (count == 1) {
                    b.append(' ');
                } else {
                    b.append("<text:s text:c=\"").append(count).append("\"/>");
                }
                i = j;
            } else if (ch == '\t') {
                b.append("<text:tab/>");
                i++;
            } else {
                int j = i;
                while (j < line.length() && line.charAt(j) != ' ' && line.charAt(j) != '\t') {
                    j++;
                }
                b.append(esc(line.substring(i, j)));
                i = j;
            }
        }
        return b.toString();
    }

    // ---- static document scaffolding ----

    private static final String CONTENT_HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<office:document-content"
            + " xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\""
            + " xmlns:style=\"urn:oasis:names:tc:opendocument:xmlns:style:1.0\""
            + " xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\""
            + " xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\""
            + " xmlns:draw=\"urn:oasis:names:tc:opendocument:xmlns:drawing:1.0\""
            + " xmlns:fo=\"urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0\""
            + " xmlns:svg=\"urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0\""
            + " xmlns:xlink=\"http://www.w3.org/1999/xlink\" office:version=\"1.2\">"
            + "<office:font-face-decls>"
            + "<style:font-face style:name=\"Mono\" svg:font-family=\"Consolas, &apos;Courier New&apos;, monospace\""
            + " style:font-pitch=\"fixed\"/>"
            + "</office:font-face-decls>"
            + "<office:automatic-styles>"
            + "<style:style style:name=\"TBold\" style:family=\"text\"><style:text-properties fo:font-weight=\"bold\"/></style:style>"
            + "<style:style style:name=\"TItalic\" style:family=\"text\"><style:text-properties fo:font-style=\"italic\"/></style:style>"
            + "<style:style style:name=\"TStrike\" style:family=\"text\"><style:text-properties style:text-line-through-style=\"solid\"/></style:style>"
            + "<style:style style:name=\"TUnderline\" style:family=\"text\"><style:text-properties style:text-underline-style=\"solid\" style:text-underline-width=\"auto\" style:text-underline-color=\"font-color\"/></style:style>"
            + "<style:style style:name=\"TCode\" style:family=\"text\"><style:text-properties style:font-name=\"Mono\"/></style:style>"
            + "<text:list-style style:name=\"L1\">"
            + listLevels(false)
            + "</text:list-style>"
            + "<text:list-style style:name=\"L2\">"
            + listLevels(true)
            + "</text:list-style>"
            + "</office:automatic-styles>"
            + "<office:body><office:text>";

    private static final String CONTENT_TAIL = "</office:text></office:body></office:document-content>";

    private static String listLevels(boolean ordered) {
        StringBuilder b = new StringBuilder();
        for (int lvl = 1; lvl <= 6; lvl++) {
            if (ordered) {
                b.append("<text:list-level-style-number text:level=\"")
                        .append(lvl)
                        .append("\" style:num-suffix=\".\" style:num-format=\"1\">")
                        .append("<style:list-level-properties text:space-before=\"")
                        .append(lvl * 0.6)
                        .append("cm\" text:min-label-width=\"0.6cm\"/></text:list-level-style-number>");
            } else {
                b.append("<text:list-level-style-bullet text:level=\"")
                        .append(lvl)
                        .append("\" text:bullet-char=\"•\">")
                        .append("<style:list-level-properties text:space-before=\"")
                        .append(lvl * 0.6)
                        .append("cm\" text:min-label-width=\"0.6cm\"/></text:list-level-style-bullet>");
            }
        }
        return b.toString();
    }

    private static final String STYLES_XML = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<office:document-styles"
            + " xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\""
            + " xmlns:style=\"urn:oasis:names:tc:opendocument:xmlns:style:1.0\""
            + " xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\""
            + " xmlns:fo=\"urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0\""
            + " xmlns:svg=\"urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0\" office:version=\"1.2\">"
            + "<office:font-face-decls>"
            + "<style:font-face style:name=\"Mono\" svg:font-family=\"Consolas, &apos;Courier New&apos;, monospace\" style:font-pitch=\"fixed\"/>"
            + "</office:font-face-decls>"
            + "<office:styles>"
            + "<style:style style:name=\"Standard\" style:family=\"paragraph\" style:class=\"text\">"
            + "<style:paragraph-properties fo:margin-top=\"0cm\" fo:margin-bottom=\"0.25cm\"/></style:style>"
            + heading(1, "20pt")
            + heading(2, "16pt")
            + heading(3, "14pt")
            + heading(4, "12pt")
            + heading(5, "11pt")
            + heading(6, "11pt")
            + "<style:style style:name=\"Preformatted_20_Text\" style:display-name=\"Preformatted Text\""
            + " style:family=\"paragraph\" style:parent-style-name=\"Standard\">"
            + "<style:paragraph-properties fo:margin-top=\"0cm\" fo:margin-bottom=\"0cm\"/>"
            + "<style:text-properties style:font-name=\"Mono\" fo:font-size=\"10pt\"/></style:style>"
            + "<style:style style:name=\"Quotations\" style:family=\"paragraph\" style:parent-style-name=\"Standard\">"
            + "<style:paragraph-properties fo:margin-left=\"1cm\" fo:margin-right=\"0.5cm\"/>"
            + "<style:text-properties fo:font-style=\"italic\"/></style:style>"
            + "<style:style style:name=\"Horizontal_20_Line\" style:display-name=\"Horizontal Line\""
            + " style:family=\"paragraph\" style:parent-style-name=\"Standard\">"
            + "<style:paragraph-properties fo:border-bottom=\"0.5pt solid #808080\" fo:padding-bottom=\"0.05cm\""
            + " fo:margin-top=\"0.25cm\" fo:margin-bottom=\"0.25cm\"/></style:style>"
            + "</office:styles></office:document-styles>";

    private static String heading(int lvl, String size) {
        return "<style:style style:name=\"Heading_20_" + lvl + "\" style:display-name=\"Heading " + lvl + "\""
                + " style:family=\"paragraph\" style:parent-style-name=\"Standard\" style:default-outline-level=\""
                + lvl + "\">"
                + "<style:paragraph-properties fo:margin-top=\"0.4cm\" fo:margin-bottom=\"0.2cm\" fo:keep-with-next=\"always\"/>"
                + "<style:text-properties fo:font-size=\"" + size + "\" fo:font-weight=\"bold\"/></style:style>";
    }
}
