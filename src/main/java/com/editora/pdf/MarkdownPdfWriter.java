package com.editora.pdf;

import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import com.editora.editor.MarkdownRenderer;
import com.editora.editor.MathImages;
import com.editora.editor.MathSpans;
import com.editora.mermaid.Mermaid;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;

/**
 * Renders Markdown to a <b>native vector</b> (searchable) PDF, mirroring the editor's preview coverage:
 * headings, inline bold/italic/code/links, lists (incl. task items), block quotes, code blocks, rules,
 * tables, images, and embedded Mermaid diagrams. Reuses {@link MarkdownRenderer#parseToDocument} for the
 * CommonMark AST. Body text uses the bundled Inter family (embedded + subset, matching the on-screen
 * preview on every platform); code uses the bundled JetBrains Mono; Mermaid blocks and images embed as
 * raster pictures. Blocking — call off the FX thread.
 */
public final class MarkdownPdfWriter {

    private static final float MARGIN = 50f;
    private static final float BODY = 11f;
    private static final float LEADING = 15f;
    private static final float PARA_GAP = 8f;

    private MarkdownPdfWriter() {}

    /**
     * @param mmdcCommand the resolved mmdc command (for ```mermaid blocks), or null to render them as code.
     */
    public static void write(String markdown, Path baseDir, String pageSizeKey, List<String> mmdcCommand, Path out)
            throws IOException {
        Node ast = MarkdownRenderer.parseToDocument(markdown);
        try (PDDocument doc = new PDDocument()) {
            Cur c = new Cur(doc, CodePdfWriter.pageRectangle(pageSizeKey), baseDir, mmdcCommand);
            for (Node n = ast.getFirstChild(); n != null; n = n.getNext()) {
                c.block(n, MARGIN);
            }
            c.close();
            doc.save(out.toFile());
        }
    }

    /** A styled, space-separated word (or a forced line break) in an inline flow. */
    /**
     * A flowed inline token: a text word (the common case) or an inline math image ({@code img != null},
     * sized {@code imgW}×{@code imgH} in points). {@code font} stays set on a math word so the flow can
     * measure the leading space before it.
     */
    private record Word(
            String text,
            PDFont font,
            float size,
            Color color,
            boolean underline,
            boolean brk,
            PDImageXObject img,
            float imgW,
            float imgH) {
        static Word of(String text, PDFont font, float size, Color color, boolean underline, boolean brk) {
            return new Word(text, font, size, color, underline, brk, null, 0, 0);
        }

        static Word math(PDImageXObject img, float w, float h, PDFont font, float size) {
            return new Word("", font, size, null, false, false, img, w, h);
        }
    }

    /** Cursor: the document + current page/stream + the y baseline, with block/inline layout helpers. */
    private static final class Cur {
        final PDDocument doc;
        final PDRectangle size;
        final Path baseDir;
        final List<String> mmdc;
        final PDFont body;
        final PDFont bodyBold;
        final PDFont bodyItalic;
        final PDFont bodyBoldItalic;
        final PDType0Font mono;
        PDPage page;
        PDPageContentStream cs;
        float y;

        Cur(PDDocument doc, PDRectangle size, Path baseDir, List<String> mmdc) throws IOException {
            this.doc = doc;
            this.size = size;
            this.baseDir = baseDir;
            this.mmdc = mmdc;
            // Prose uses the bundled Inter (embedded + subset), matching the on-screen Markdown preview
            // on every platform, instead of the built-in Standard-14 Helvetica. Embedding also gives
            // full Unicode coverage (em dashes, curly quotes, etc.) that WinAnsi Helvetica lacked.
            this.body = embed(doc, "inter/Inter-Regular");
            this.bodyBold = embed(doc, "inter/Inter-Bold");
            this.bodyItalic = embed(doc, "inter/Inter-Italic");
            this.bodyBoldItalic = embed(doc, "inter/Inter-BoldItalic");
            this.mono = embed(doc, "jetbrains-mono/JetBrainsMono-Regular");
            newPage();
        }

        void newPage() throws IOException {
            if (cs != null) {
                cs.close();
            }
            page = new PDPage(size);
            doc.addPage(page);
            cs = new PDPageContentStream(doc, page);
            y = size.getHeight() - MARGIN;
        }

        void close() throws IOException {
            if (cs != null) {
                cs.close();
            }
        }

        /** Loads a bundled TTF as an embedded, subset {@link PDType0Font} (full Unicode, small output). */
        private static PDType0Font embed(PDDocument doc, String relPath) throws IOException {
            try (InputStream in =
                    MarkdownPdfWriter.class.getResourceAsStream("/com/editora/fonts/" + relPath + ".ttf")) {
                if (in == null) {
                    throw new IOException("Missing bundled font: " + relPath);
                }
                return PDType0Font.load(doc, in);
            }
        }

        float contentRight() {
            return size.getWidth() - MARGIN;
        }

        void need(float h) throws IOException {
            if (y - h < MARGIN) {
                newPage();
            }
        }

        // --- blocks -------------------------------------------------------------------------------

        void block(Node n, float left) throws IOException {
            if (n instanceof Heading h) {
                heading(h, left);
            } else if (n instanceof Paragraph p) {
                String displayMath = MathImages.isEnabled() ? soleDisplayMath(p) : null;
                if (displayMath != null) {
                    blockMath(displayMath, left, p);
                } else if (isBlockImage(p)) {
                    image((Image) p.getFirstChild(), left);
                } else {
                    paragraph(p, left, BODY);
                    y -= PARA_GAP;
                }
            } else if (n instanceof BulletList bl) {
                list(bl, left, false, 0);
            } else if (n instanceof OrderedList ol) {
                list(ol, left, true, ol.getMarkerStartNumber() == null ? 1 : ol.getMarkerStartNumber());
            } else if (n instanceof BlockQuote bq) {
                quote(bq, left);
            } else if (n instanceof FencedCodeBlock f) {
                if (isMermaid(f.getInfo()) && mmdc != null) {
                    mermaid(f.getLiteral(), left);
                } else {
                    codeBlock(f.getLiteral(), left);
                }
            } else if (n instanceof IndentedCodeBlock i) {
                codeBlock(i.getLiteral(), left);
            } else if (n instanceof ThematicBreak) {
                rule(left);
            } else if (n instanceof TableBlock t) {
                table(t, left);
            } else if (n instanceof org.commonmark.node.HtmlBlock hb
                    && com.editora.editor.MarkdownRenderer.isHtmlComment(hb.getLiteral())) {
                // HTML comments are invisible — skip (matches the on-screen preview).
            } else {
                // HtmlBlock(non-comment) / unknown: render its textual content as a paragraph if any.
                String text = plain(n);
                if (!text.isBlank()) {
                    flow(
                            List.of(Word.of(text.strip(), body, BODY, PdfTheme.DEFAULT_FG, false, false)),
                            left,
                            contentRight(),
                            LEADING);
                    y -= PARA_GAP;
                }
            }
        }

        void heading(Heading h, float left) throws IOException {
            float size =
                    switch (h.getLevel()) {
                        case 1 -> 20f;
                        case 2 -> 16f;
                        case 3 -> 14f;
                        default -> 12f;
                    };
            y -= 6f;
            float leading = size * 1.3f;
            List<Word> words = new ArrayList<>();
            inline(h, words, bodyBold, bodyBold, bodyBold, size, PdfTheme.DEFAULT_FG, false);
            flow(words, left, contentRight(), leading);
            if (h.getLevel() <= 2) {
                // flow() left y one leading below the last heading baseline; draw the rule just under
                // that baseline (within the line box) so it stays attached to the heading rather than
                // colliding with the following paragraph.
                float ruleY = y + leading - size * 0.5f;
                cs.setStrokingColor(PdfTheme.RULE);
                cs.setLineWidth(0.6f);
                cs.moveTo(left, ruleY);
                cs.lineTo(contentRight(), ruleY);
                cs.stroke();
            }
            y -= PARA_GAP;
        }

        void paragraph(Paragraph p, float left, float size) throws IOException {
            List<Word> words = new ArrayList<>();
            inline(p, words, body, bodyBold, bodyItalic, size, PdfTheme.DEFAULT_FG, false);
            flow(words, left, contentRight(), LEADING);
        }

        void list(Node listNode, float left, boolean ordered, int start) throws IOException {
            float indent = left + 18f;
            int idx = start;
            for (Node item = listNode.getFirstChild(); item != null; item = item.getNext()) {
                if (!(item instanceof ListItem)) {
                    continue;
                }
                String marker = ordered ? (idx++ + ".") : "•";
                need(LEADING);
                drawText(marker, body, BODY, PdfTheme.DEFAULT_FG, left + 4f, y);
                // Item content: render child blocks at the indented margin; first paragraph shares the row.
                float savedY = y;
                boolean firstChild = true;
                for (Node child = item.getFirstChild(); child != null; child = child.getNext()) {
                    if (firstChild && child instanceof Paragraph p) {
                        paragraph(p, indent, BODY);
                    } else {
                        block(child, indent);
                    }
                    firstChild = false;
                }
                if (y == savedY) {
                    y -= LEADING; // empty item
                }
                y -= 2f;
            }
            y -= PARA_GAP - 2f;
        }

        void quote(BlockQuote bq, float left) throws IOException {
            float top = y;
            float inset = left + 12f;
            for (Node child = bq.getFirstChild(); child != null; child = child.getNext()) {
                block(child, inset);
            }
            // Left bar spanning the quote (single-page approximation).
            float barTop = Math.min(top, size.getHeight() - MARGIN);
            if (y < barTop) {
                cs.setStrokingColor(PdfTheme.RULE);
                cs.setLineWidth(2.5f);
                cs.moveTo(left + 2f, barTop);
                cs.lineTo(left + 2f, y + LEADING - 3f);
                cs.stroke();
            }
        }

        void codeBlock(String literal, float left) throws IOException {
            String[] lines = stripTrailing(literal).split("\n", -1);
            float boxH = lines.length * LEADING + 8f;
            need(Math.min(boxH, size.getHeight() - 2 * MARGIN));
            for (String line : lines) {
                need(LEADING);
                // light gray background strip
                cs.setNonStrokingColor(PdfTheme.CODE_BG);
                cs.addRect(left, y - 3f, contentRight() - left, LEADING);
                cs.fill();
                drawText(line.isEmpty() ? " " : line, mono, BODY - 1f, PdfTheme.hex("#24292f"), left + 5f, y);
                y -= LEADING;
            }
            y -= PARA_GAP;
        }

        void mermaid(String source, float left) throws IOException {
            Mermaid.Render r = Mermaid.renderPng(mmdc, source, false);
            if (r.ok()) {
                try {
                    PDImageXObject img = PDImageXObject.createFromByteArray(doc, r.image(), "mermaid");
                    drawImage(img, left);
                    return;
                } catch (IOException | RuntimeException ignored) {
                    // fall through to code rendering
                }
            }
            codeBlock(source, left);
        }

        void blockMath(String latex, float left, Paragraph fallback) throws IOException {
            byte[] png = MathImages.renderPng(latex, true, 30f, false); // PDF is always light
            if (png != null) {
                try {
                    drawImage(PDImageXObject.createFromByteArray(doc, png, "math"), left);
                    return;
                } catch (IOException | RuntimeException ignored) {
                    // fall through to text rendering
                }
            }
            paragraph(fallback, left, BODY);
            y -= PARA_GAP;
        }

        void image(Image node, float left) throws IOException {
            byte[] bytes = fetch(node.getDestination());
            if (bytes != null) {
                try {
                    // PDFBox can't decode SVG (shields.io/GitHub badges are image/svg+xml); rasterize it
                    // to PNG first via the same JSVG path the on-screen preview uses.
                    if (com.editora.editor.PreviewImageLoader.looksLikeSvg(bytes)) {
                        byte[] png = com.editora.editor.PreviewImageLoader.svgToPng(bytes);
                        if (png != null) {
                            bytes = png;
                        }
                    }
                    PDImageXObject img = PDImageXObject.createFromByteArray(doc, bytes, "img");
                    drawImage(img, left);
                    return;
                } catch (IOException | RuntimeException ignored) {
                    // unfetchable / unsupported / undecodable format — fall back to alt text below.
                    // (createFromByteArray throws IllegalArgumentException for unknown image types, which
                    // previously escaped and aborted the whole export.)
                }
            }
            String alt = plain(node);
            flow(
                    List.of(Word.of(
                            "[" + (alt.isBlank() ? "image" : alt) + "]",
                            bodyItalic,
                            BODY,
                            PdfTheme.LINE_NUMBER,
                            false,
                            false)),
                    left,
                    contentRight(),
                    LEADING);
            y -= PARA_GAP;
        }

        void drawImage(PDImageXObject img, float left) throws IOException {
            float maxW = contentRight() - left;
            float w = Math.min(img.getWidth(), maxW);
            float h = w / img.getWidth() * img.getHeight();
            float maxH = size.getHeight() - 2 * MARGIN;
            if (h > maxH) {
                h = maxH;
                w = h / img.getHeight() * img.getWidth();
            }
            need(h);
            y -= h;
            cs.drawImage(img, left, y, w, h);
            y -= PARA_GAP;
        }

        void rule(float left) throws IOException {
            y -= PARA_GAP;
            need(2f);
            cs.setStrokingColor(PdfTheme.RULE);
            cs.setLineWidth(0.8f);
            cs.moveTo(left, y);
            cs.lineTo(contentRight(), y);
            cs.stroke();
            y -= PARA_GAP;
        }

        void table(TableBlock t, float left) throws IOException {
            List<List<String>> rows = new ArrayList<>();
            List<Boolean> header = new ArrayList<>();
            for (Node section = t.getFirstChild(); section != null; section = section.getNext()) {
                boolean head = section instanceof org.commonmark.ext.gfm.tables.TableHead;
                for (Node r = section.getFirstChild(); r != null; r = r.getNext()) {
                    if (!(r instanceof TableRow)) {
                        continue;
                    }
                    List<String> cells = new ArrayList<>();
                    for (Node cell = r.getFirstChild(); cell != null; cell = cell.getNext()) {
                        if (cell instanceof TableCell) {
                            cells.add(plain(cell).strip());
                        }
                    }
                    rows.add(cells);
                    header.add(head);
                }
            }
            if (rows.isEmpty()) {
                return;
            }
            int cols = rows.stream().mapToInt(List::size).max().orElse(1);
            float tableW = contentRight() - left;
            float colW = tableW / cols;
            float pad = 4f;
            for (int ri = 0; ri < rows.size(); ri++) {
                List<String> cells = rows.get(ri);
                boolean head = header.get(ri);
                // measure row height by the tallest wrapped cell
                int maxLines = 1;
                List<List<String>> wrapped = new ArrayList<>();
                for (int ci = 0; ci < cols; ci++) {
                    String text = ci < cells.size() ? cells.get(ci) : "";
                    List<String> wl = wrapPlain(text, head ? bodyBold : body, BODY, colW - 2 * pad);
                    wrapped.add(wl);
                    maxLines = Math.max(maxLines, wl.size());
                }
                float rowH = maxLines * LEADING + 4f;
                need(rowH);
                float rowTop = y;
                if (head) {
                    cs.setNonStrokingColor(PdfTheme.CODE_BG);
                    cs.addRect(left, rowTop - rowH, tableW, rowH);
                    cs.fill();
                }
                for (int ci = 0; ci < cols; ci++) {
                    float cx = left + ci * colW;
                    float ty = rowTop - LEADING;
                    for (String wl : wrapped.get(ci)) {
                        drawText(wl, head ? bodyBold : body, BODY, PdfTheme.DEFAULT_FG, cx + pad, ty);
                        ty -= LEADING;
                    }
                }
                // borders
                cs.setStrokingColor(PdfTheme.RULE);
                cs.setLineWidth(0.5f);
                cs.addRect(left, rowTop - rowH, tableW, rowH);
                cs.stroke();
                for (int ci = 1; ci < cols; ci++) {
                    cs.moveTo(left + ci * colW, rowTop);
                    cs.lineTo(left + ci * colW, rowTop - rowH);
                    cs.stroke();
                }
                y = rowTop - rowH;
            }
            y -= PARA_GAP;
        }

        // --- inline flow --------------------------------------------------------------------------

        void inline(
                Node parent,
                List<Word> out,
                PDFont reg,
                PDFont bold,
                PDFont italic,
                float size,
                Color color,
                boolean underline)
                throws IOException {
            for (Node n = parent.getFirstChild(); n != null; n = n.getNext()) {
                if (n instanceof Text t) {
                    addInlineText(t.getLiteral(), out, reg, size, color, underline);
                } else if (n instanceof StrongEmphasis) {
                    inline(n, out, bold, bold, bold, size, color, underline);
                } else if (n instanceof Emphasis) {
                    inline(n, out, italic, bold, italic, size, color, underline);
                } else if (n instanceof Code c) {
                    out.add(Word.of(c.getLiteral(), mono, size - 0.5f, PdfTheme.hex("#0a3069"), underline, false));
                } else if (n instanceof Link link) {
                    inline(n, out, reg, bold, italic, size, PdfTheme.hex("#0969da"), true);
                } else if (n instanceof SoftLineBreak) {
                    // word boundary — flow inserts spaces between words already
                    out.add(Word.of("", reg, size, color, false, false));
                } else if (n instanceof HardLineBreak) {
                    out.add(Word.of("", reg, size, color, false, true));
                } else if (n instanceof Image) {
                    out.add(Word.of("[" + plain(n) + "]", italic, size, PdfTheme.LINE_NUMBER, false, false));
                } else {
                    // Strikethrough, html inline, etc.: render their text plainly.
                    inline(n, out, reg, bold, italic, size, color, underline);
                }
            }
        }

        /** Splits a text literal into flow words, turning inline {@code $…$}/{@code $$…$$} math into image words. */
        void addInlineText(String literal, List<Word> out, PDFont reg, float size, Color color, boolean underline)
                throws IOException {
            if (!MathImages.isEnabled() || literal.indexOf('$') < 0) {
                for (String w : literal.split(" ", -1)) {
                    if (!w.isEmpty()) {
                        out.add(Word.of(w, reg, size, color, underline, false));
                    }
                }
                return;
            }
            for (MathSpans.Segment seg : MathSpans.segments(literal)) {
                if (seg.text() != null) {
                    for (String w : seg.text().split(" ", -1)) {
                        if (!w.isEmpty()) {
                            out.add(Word.of(w, reg, size, color, underline, false));
                        }
                    }
                } else {
                    out.add(mathWord(seg.span().latex(), seg.span().display(), reg, size, color, underline));
                }
            }
        }

        /** A math image word sized to the line, or a literal {@code $…$} text fallback when rendering fails. */
        Word mathWord(String latex, boolean display, PDFont reg, float size, Color color, boolean underline)
                throws IOException {
            byte[] png = MathImages.renderPng(latex, display, display ? 30f : 16f, false);
            if (png != null) {
                PDImageXObject xo = PDImageXObject.createFromByteArray(doc, png, "math");
                if (xo.getHeight() > 0) {
                    float h = display ? size * 1.8f : size * 1.05f;
                    float w = (float) xo.getWidth() / xo.getHeight() * h;
                    return Word.math(xo, w, h, reg, size);
                }
            }
            String d = display ? "$$" : "$";
            return Word.of(d + latex + d, reg, size, color, underline, false);
        }

        /** Greedy word-wrap flow of styled words at the current y; advances y per visual line. */
        void flow(List<Word> words, float left, float right, float leading) throws IOException {
            need(leading);
            float x = left;
            boolean started = false;
            for (Word w : words) {
                if (w.brk()) {
                    y -= leading;
                    need(leading);
                    x = left;
                    started = false;
                    continue;
                }
                if (w.img() != null) {
                    float iw = w.imgW();
                    float isp = started ? w.font().getStringWidth(" ") / 1000f * w.size() : 0f;
                    if (started && x + isp + iw > right) {
                        y -= leading;
                        need(leading);
                        x = left;
                        started = false;
                        isp = 0f;
                    }
                    float ix = x + isp;
                    cs.drawImage(w.img(), ix, y - w.imgH() * 0.25f, w.imgW(), w.imgH()); // sit ~on the baseline
                    x = ix + iw;
                    started = true;
                    continue;
                }
                if (w.text().isEmpty()) {
                    continue;
                }
                String text = encodable(w.font(), w.text());
                float ww = w.font().getStringWidth(text) / 1000f * w.size();
                float sp = started ? w.font().getStringWidth(" ") / 1000f * w.size() : 0f;
                if (started && x + sp + ww > right) {
                    y -= leading;
                    need(leading);
                    x = left;
                    started = false;
                    sp = 0f;
                }
                float wx = x + sp;
                drawText(text, w.font(), w.size(), w.color(), wx, y);
                if (w.underline()) {
                    cs.setStrokingColor(w.color());
                    cs.setLineWidth(0.5f);
                    cs.moveTo(wx, y - 1.5f);
                    cs.lineTo(wx + ww, y - 1.5f);
                    cs.stroke();
                }
                x = wx + ww;
                started = true;
            }
            y -= leading;
        }

        void drawText(String s, PDFont font, float size, Color color, float x, float y) throws IOException {
            cs.beginText();
            cs.setFont(font, size);
            cs.setNonStrokingColor(color);
            cs.newLineAtOffset(x, y);
            cs.showText(encodable(font, s));
            cs.endText();
        }

        List<String> wrapPlain(String text, PDFont font, float size, float maxW) throws IOException {
            List<String> out = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            for (String word : encodable(font, text).split(" ", -1)) {
                String candidate = line.length() == 0 ? word : line + " " + word;
                if (font.getStringWidth(candidate) / 1000f * size > maxW && line.length() > 0) {
                    out.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(candidate);
                }
            }
            out.add(line.toString());
            return out;
        }

        byte[] fetch(String url) {
            try {
                if (url == null || url.isBlank()) {
                    return null;
                }
                if (url.startsWith("data:")) {
                    int comma = url.indexOf(',');
                    return comma < 0 ? null : Base64.getMimeDecoder().decode(url.substring(comma + 1));
                }
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    URLConnection con = URI.create(url).toURL().openConnection();
                    con.setConnectTimeout(5000);
                    con.setReadTimeout(5000);
                    con.setRequestProperty("User-Agent", "Editora");
                    try (InputStream in = con.getInputStream()) {
                        return in.readAllBytes();
                    }
                }
                Path p = url.startsWith("file:")
                        ? Path.of(URI.create(url))
                        : (baseDir == null ? Path.of(url) : baseDir.resolve(url));
                return Files.isRegularFile(p) ? Files.readAllBytes(p) : null;
            } catch (Exception e) {
                return null;
            }
        }
    }

    // --- small helpers -----------------------------------------------------------------------------

    private static boolean isBlockImage(Paragraph p) {
        Node c = p.getFirstChild();
        return c instanceof Image && c.getNext() == null;
    }

    /** If paragraph {@code p} is exactly one {@code $$…$$} display-math span, its LaTeX; else null. */
    private static String soleDisplayMath(Paragraph p) {
        StringBuilder sb = new StringBuilder();
        for (Node c = p.getFirstChild(); c != null; c = c.getNext()) {
            if (c instanceof Text t) {
                sb.append(t.getLiteral());
            } else {
                return null;
            }
        }
        String trimmed = sb.toString().strip();
        List<MathSpans.Span> spans = MathSpans.find(trimmed);
        if (spans.size() == 1) {
            MathSpans.Span s = spans.get(0);
            if (s.display() && s.start() == 0 && s.end() == trimmed.length()) {
                return s.latex();
            }
        }
        return null;
    }

    private static boolean isMermaid(String info) {
        return info != null && info.strip().split("\\s+", 2)[0].equalsIgnoreCase("mermaid");
    }

    /**
     * Readable ASCII substitutes for common Unicode symbols the Standard-14 (WinAnsi) prose font can't
     * encode — so {@code →} renders as {@code ->} rather than {@code ?}. Anything not listed and not
     * encodable falls back to {@code ?}.
     */
    private static final Map<Integer, String> FALLBACK = Map.ofEntries(
            Map.entry(0x2192, "->"),
            Map.entry(0x2190, "<-"),
            Map.entry(0x2194, "<->"),
            Map.entry(0x2191, "^"),
            Map.entry(0x2193, "v"),
            Map.entry(0x21D2, "=>"),
            Map.entry(0x21D0, "<="),
            Map.entry(0x21D4, "<=>"),
            Map.entry(0x2713, "v"),
            Map.entry(0x2714, "v"),
            Map.entry(0x2717, "x"),
            Map.entry(0x2718, "x"),
            Map.entry(0x2026, "..."),
            Map.entry(0x2011, "-"),
            Map.entry(0x2012, "-"),
            Map.entry(0x00A0, " "),
            Map.entry(0x202F, " "),
            Map.entry(0x2009, " "),
            Map.entry(0x200B, ""),
            Map.entry(0x2261, "="),
            Map.entry(0x2260, "!="),
            Map.entry(0x2264, "<="),
            Map.entry(0x2265, ">="),
            Map.entry(0x00D7, "x"),
            Map.entry(0x2022, "-"));

    /**
     * Returns {@code text} with every character {@code font} cannot encode replaced by a readable ASCII
     * substitute (or {@code ?}), so width measurement and drawing never throw. Fast-paths an entirely
     * encodable string (the common case). Pure given the font's glyph set.
     */
    static String encodable(PDFont font, String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        if (encodes(font, text)) {
            return text; // whole string encodable — the overwhelmingly common case
        }
        StringBuilder sb = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            String s = new String(Character.toChars(cp));
            if (encodes(font, s)) {
                sb.append(s);
            } else {
                String f = FALLBACK.get(cp);
                sb.append(f != null && encodes(font, f) ? f : "?");
            }
        });
        return sb.toString();
    }

    /**
     * Whether {@code font} can render {@code s}. Probes with {@link PDFont#encode} — the exact operation
     * {@code showText} performs — rather than {@code getStringWidth}, because an embedded subset TrueType
     * font ({@code PDType0Font}, used for code) can return a width for a character it has no glyph for and
     * then throw only at draw time (e.g. U+2011 non-breaking hyphen in JetBrains Mono).
     */
    private static boolean encodes(PDFont font, String s) {
        try {
            font.encode(s);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String stripTrailing(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) {
            end--;
        }
        return s.substring(0, end);
    }

    /** Concatenates all descendant text + code literals of {@code n}. */
    private static String plain(Node n) {
        StringBuilder sb = new StringBuilder();
        for (Node c = n.getFirstChild(); c != null; c = c.getNext()) {
            if (c instanceof Text t) {
                sb.append(t.getLiteral());
            } else if (c instanceof Code code) {
                sb.append(code.getLiteral());
            } else if (c instanceof SoftLineBreak || c instanceof HardLineBreak) {
                sb.append(' ');
            } else {
                sb.append(plain(c));
            }
        }
        return sb.toString();
    }
}
