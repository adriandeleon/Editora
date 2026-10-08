package com.editora.pdf;

import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.commonmark.ext.footnotes.FootnoteDefinition;
import org.commonmark.ext.footnotes.FootnoteReference;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.task.list.items.TaskListItemMarker;
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
 * headings, inline bold/italic/code/links/strikethrough, lists (incl. task items), block quotes, code blocks, rules,
 * tables, images, and embedded Mermaid diagrams. Reuses {@link MarkdownRenderer#parseToDocument} for the
 * CommonMark AST. Body text uses the bundled Inter family (embedded + subset, matching the on-screen
 * preview on every platform); code uses the bundled JetBrains Mono; Mermaid blocks and images embed as
 * raster pictures. Characters those fonts lack (CJK, Arabic, Hebrew, Thai, …) are drawn with a system
 * fallback font ({@link PdfGlyphs}); {@code write} returns how many characters no font could draw.
 * Blocking — call off the FX thread.
 */
public final class MarkdownPdfWriter {

    private static final float MARGIN = 50f;
    private static final float BODY = 11f;
    private static final float LEADING = 15f;
    private static final float PARA_GAP = 8f;
    /**
     * How far a text line's box rises above its baseline (it reaches {@code LEADING - ASCENT} below). The
     * cursor {@code y} is always the <em>baseline</em> of the next text line, so the next block's top edge is
     * {@code y + ASCENT}: a boxed block (table, image, rule) starts there, and leaves {@code y} one
     * {@link #PARA_GAP} plus this ascent under its bottom edge — the same gap two paragraphs get.
     */
    private static final float ASCENT = LEADING - 3f;
    /** Space between a list marker (number, bullet, checkbox) and the item text. */
    private static final float MARKER_GAP = 4f;
    /** Side of a task-list checkbox. */
    private static final float CHECKBOX = 8f;
    /** A table row taller than a page starts on the current page only if this many of its lines fit there. */
    private static final int MIN_SPLIT_LINES = 3;
    /** Tab stops inside a code block (CommonMark's own tab width). */
    private static final int CODE_TAB = 4;

    private static final Color CODE_FG = PdfTheme.hex("#24292f");

    private MarkdownPdfWriter() {}

    /**
     * @param mmdcCommand the resolved mmdc command (for ```mermaid blocks), or null to render them as code.
     * @return the number of characters that no available font could draw (written as {@code ?})
     */
    public static int write(String markdown, Path baseDir, String pageSizeKey, List<String> mmdcCommand, Path out)
            throws IOException {
        return write(MarkdownRenderer.parseToDocument(markdown), baseDir, pageSizeKey, mmdcCommand, out);
    }

    /**
     * Renders an already-built CommonMark document — for content that is <em>data</em>, not Markdown source
     * (the CSV export builds its table node by node, so a cell like {@code __init__} is never re-parsed).
     */
    public static int write(Node ast, Path baseDir, String pageSizeKey, List<String> mmdcCommand, Path out)
            throws IOException {
        return write(ast, baseDir, pageSizeKey, mmdcCommand, out, SystemFontFiles.get());
    }

    /** As above with explicit fallback font files (tests pin the list; production uses the system's). */
    static int write(
            Node ast, Path baseDir, String pageSizeKey, List<String> mmdcCommand, Path out, List<Path> fallbackFonts)
            throws IOException {
        try {
            return render(ast, baseDir, pageSizeKey, mmdcCommand, out, fallbackFonts);
        } catch (IOException | RuntimeException e) {
            if (fallbackFonts.isEmpty() || PdfExportService.abandoned(e)) { // a cancel is not a bad font
                throw e;
            }
            // An arbitrary system font is the one input here that is not under our control: if PDFBox cannot
            // embed it after all, export with the bundled fonts (and an honest missing-glyph count) rather
            // than fail. A failure that has nothing to do with fonts simply happens again and propagates.
            return render(ast, baseDir, pageSizeKey, mmdcCommand, out, List.of());
        }
    }

    private static int render(
            Node ast, Path baseDir, String pageSizeKey, List<String> mmdcCommand, Path out, List<Path> fallbackFonts)
            throws IOException {
        try (PDDocument doc = new PDDocument();
                PdfGlyphs glyphs = new PdfGlyphs(doc, fallbackFonts)) {
            Cur c = new Cur(doc, CodePdfWriter.pageRectangle(pageSizeKey), baseDir, mmdcCommand, glyphs);
            for (Node n = ast.getFirstChild(); n != null; n = n.getNext()) {
                c.block(n, MARGIN);
            }
            c.close();
            doc.save(out.toFile());
            return glyphs.missing();
        }
    }

    /**
     * A flowed inline token: a text word (the common case), a forced line break ({@code brk}), or an inline
     * math image ({@code img != null}, sized {@code imgW}×{@code imgH} in points). {@code font} stays set on
     * a math word so the flow can measure the space before it.
     *
     * <p>{@code space} records whether the source had whitespace before this token. Inline styling splits
     * text into several tokens with <em>no</em> whitespace between them — {@code **world**!},
     * {@code un*real*ly}, {@code `Ctrl`+`C`}, {@code (see [docs](u))} — so the flow may only insert a space
     * (and only break a line) where this is set.
     */
    private record Word(
            String text,
            PDFont font,
            float size,
            Color color,
            boolean underline,
            boolean strike,
            boolean brk,
            boolean space,
            PDImageXObject img,
            float imgW,
            float imgH) {
        static Word of(
                String text, PDFont font, float size, Color color, boolean underline, boolean brk, boolean space) {
            return new Word(text, font, size, color, underline, false, brk, space, null, 0, 0);
        }

        static Word math(PDImageXObject img, float w, float h, PDFont font, float size, boolean space) {
            return new Word("", font, size, null, false, false, false, space, img, w, h);
        }

        /** This word struck through ({@code ~~text~~}). */
        Word struck() {
            return new Word(text, font, size, color, underline, true, brk, space, img, imgW, imgH);
        }
    }

    /** A block quote's left bar: its x, and where it starts on the current page (it restarts on every page). */
    private static final class Bar {
        final float x;
        float top;

        Bar(float x, float top) {
            this.x = x;
            this.top = top;
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
        final PdfGlyphs glyphs;
        PDPage page;
        PDPageContentStream cs;
        float y;
        /** Inline-collection state: whitespace was seen since the last word (see {@link Word#space()}). */
        boolean pendingSpace;
        /** The block quotes being laid out, outermost first — each owes a bar segment to every page it touches. */
        final List<Bar> bars = new ArrayList<>();

        Cur(PDDocument doc, PDRectangle size, Path baseDir, List<String> mmdc, PdfGlyphs glyphs) throws IOException {
            this.doc = doc;
            this.size = size;
            this.baseDir = baseDir;
            this.mmdc = mmdc;
            this.glyphs = glyphs;
            // Prose uses the bundled Inter (embedded + subset), matching the on-screen Markdown preview
            // on every platform, instead of the built-in Standard-14 Helvetica. Inter covers Latin, Greek
            // and Cyrillic (plus em dashes, curly quotes, etc.) — NOT CJK, Arabic, Hebrew, Thai or Indic
            // scripts; those come from a system fallback font via PdfGlyphs, or are counted as missing.
            this.body = embed(doc, "inter/Inter-Regular");
            this.bodyBold = embed(doc, "inter/Inter-Bold");
            this.bodyItalic = embed(doc, "inter/Inter-Italic");
            this.bodyBoldItalic = embed(doc, "inter/Inter-BoldItalic");
            this.mono = embed(doc, "jetbrains-mono/JetBrainsMono-Regular");
            newPage();
        }

        void newPage() throws IOException {
            if (cs != null) {
                // y is the baseline the next line would have had: the last line drawn ends at y + ASCENT.
                for (Bar bar : bars) {
                    strokeBar(bar, y + ASCENT);
                }
                cs.close();
            }
            page = new PDPage(size);
            doc.addPage(page);
            PdfExportService.pageStarted(); // progress, and where a cancelled export stops
            cs = new PDPageContentStream(doc, page);
            y = size.getHeight() - MARGIN;
            for (Bar bar : bars) {
                bar.top = boxTop();
            }
        }

        /** Whether nothing has been laid out on the current page yet. */
        boolean atPageTop() {
            return y >= size.getHeight() - MARGIN;
        }

        void close() throws IOException {
            if (cs != null) {
                cs.close();
            }
        }

        /** Loads a bundled TTF as an embedded, subset {@link PDType0Font} (Unicode-encoded, small output). */
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

        /** The top edge of the next block (see {@link #ASCENT}). */
        float boxTop() {
            return y + ASCENT;
        }

        /** Starts a new page unless a box {@code h} tall fits between {@link #boxTop} and the bottom margin. */
        void needBox(float h) throws IOException {
            if (boxTop() - h < MARGIN) {
                newPage();
            }
        }

        /** Leaves the cursor for the block that follows a box whose bottom edge is at {@code bottom}. */
        void afterBox(float bottom) {
            y = bottom - PARA_GAP - ASCENT;
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
            } else if (n instanceof org.commonmark.node.HtmlBlock hb) {
                // HTML comments are invisible; other raw HTML is shown as its source text. Both match the
                // on-screen preview. (An HtmlBlock has no child nodes — its text is the literal.)
                if (!MarkdownRenderer.isHtmlComment(hb.getLiteral())) {
                    codeBlock(hb.getLiteral(), left);
                }
            } else if (n instanceof FootnoteDefinition def) {
                footnote(def, left);
            } else {
                // Unknown block: render its textual content as a paragraph if any.
                String text = plain(n);
                if (!text.isBlank()) {
                    flow(words(text, body, BODY, PdfTheme.DEFAULT_FG), left, contentRight(), LEADING);
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
            float leading = size * 1.3f;
            if (h.getNext() != null) {
                // Keep with next: a heading goes to the next page unless it and two body lines still fit,
                // so it is never the last thing on a page with its section starting overleaf.
                need(6f + leading + PARA_GAP + 2 * LEADING);
            }
            y -= 6f;
            List<Word> words = new ArrayList<>();
            pendingSpace = false;
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
            pendingSpace = false;
            inline(p, words, body, bodyBold, bodyItalic, size, PdfTheme.DEFAULT_FG, false);
            flow(words, left, contentRight(), LEADING);
        }

        /** {@code text} as plain flow words, split at whitespace (so it wraps like a paragraph). */
        List<Word> words(String text, PDFont font, float size, Color color) {
            List<Word> out = new ArrayList<>();
            pendingSpace = false;
            addWords(text, out, font, size, color, false);
            return out;
        }

        void list(Node listNode, float left, boolean ordered, int start) throws IOException {
            float indent = left + 18f;
            if (ordered) {
                // The indent fits the widest number in this list ("100." is far wider than "1."); the
                // numbers are right-aligned against it so their dots line up.
                int n = start;
                for (Node item = listNode.getFirstChild(); item != null; item = item.getNext()) {
                    if (item instanceof ListItem) {
                        indent = Math.max(indent, left + 4f + glyphs.width(body, n++ + ".", BODY) + MARKER_GAP);
                    }
                }
            }
            int idx = start;
            for (Node item = listNode.getFirstChild(); item != null; item = item.getNext()) {
                if (!(item instanceof ListItem)) {
                    continue;
                }
                // commonmark-java inserts a task item's marker as the item's first child.
                TaskListItemMarker task = item.getFirstChild() instanceof TaskListItemMarker m ? m : null;
                need(LEADING);
                float textLeft = indent;
                if (ordered) {
                    String marker = idx++ + ".";
                    float markerX = indent - MARKER_GAP - glyphs.width(body, marker, BODY);
                    drawText(marker, body, BODY, PdfTheme.DEFAULT_FG, markerX, y);
                    if (task != null) { // a numbered task keeps its number; the box leads the first line
                        checkbox(task.isChecked(), indent);
                        textLeft = indent + CHECKBOX + MARKER_GAP;
                    }
                } else if (task != null) {
                    checkbox(task.isChecked(), left + 4f); // the box stands in for the bullet
                } else {
                    drawText("•", body, BODY, PdfTheme.DEFAULT_FG, left + 4f, y);
                }
                // Item content: render child blocks at the indented margin; first paragraph shares the row.
                float savedY = y;
                boolean firstChild = true;
                for (Node child = item.getFirstChild(); child != null; child = child.getNext()) {
                    if (child == task) {
                        continue;
                    }
                    if (firstChild && child instanceof Paragraph p) {
                        paragraph(p, textLeft, BODY);
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

        /**
         * A task-list checkbox on the current line with its left edge at {@code x}: a stroked square, plus a
         * tick when {@code checked}. Drawn as vector paths, so it never depends on a font having ☐/☑. The
         * state is also written as invisible text ({@code [x]} / {@code [ ]}, the Markdown source form) over
         * the box, so text extraction, search and copy/paste keep it.
         */
        void checkbox(boolean checked, float x) throws IOException {
            float bottom = y - 0.5f;
            cs.setStrokingColor(PdfTheme.DEFAULT_FG);
            cs.setLineWidth(0.8f);
            cs.addRect(x, bottom, CHECKBOX, CHECKBOX);
            cs.stroke();
            if (checked) {
                cs.setLineWidth(1.2f);
                cs.moveTo(x + 1.8f, bottom + 4.2f);
                cs.lineTo(x + 3.4f, bottom + 2.2f);
                cs.lineTo(x + 6.3f, bottom + 6.2f);
                cs.stroke();
            }
            cs.saveGraphicsState();
            cs.setRenderingMode(RenderingMode.NEITHER);
            // Body size, so extractors read it as part of the item's line; condensed to the box's width.
            String state = checked ? "[x]" : "[ ]";
            cs.setHorizontalScaling(100f * CHECKBOX / glyphs.width(body, state, BODY));
            glyphs.show(cs, body, BODY, state, x, y);
            cs.restoreGraphicsState();
        }

        /** A footnote definition: its {@code [label]} marker, then the definition's blocks beside it. */
        void footnote(FootnoteDefinition def, float left) throws IOException {
            String marker = "[" + def.getLabel() + "]";
            need(LEADING);
            drawText(marker, body, BODY - 1f, PdfTheme.LINE_NUMBER, left, y);
            float indent = left + glyphs.width(body, marker + " ", BODY - 1f);
            float savedY = y;
            boolean firstChild = true;
            for (Node child = def.getFirstChild(); child != null; child = child.getNext()) {
                if (firstChild && child instanceof Paragraph p) {
                    paragraph(p, indent, BODY);
                } else {
                    block(child, indent);
                }
                firstChild = false;
            }
            if (y == savedY) {
                y -= LEADING; // empty definition
            }
            y -= 2f;
        }

        void quote(BlockQuote bq, float left) throws IOException {
            // The left bar runs from the top of the quote's first line to the bottom of its last, one
            // segment per page: newPage() strokes the segment for the page being left and restarts the bar.
            Bar bar = new Bar(left + 2f, boxTop());
            bars.add(bar);
            float inset = left + 12f;
            for (Node child = bq.getFirstChild(); child != null; child = child.getNext()) {
                block(child, inset);
            }
            bars.remove(bar);
            strokeBar(bar, boxTop() + PARA_GAP); // the last child left its paragraph gap below it
        }

        /** Strokes {@code bar} on the current page down to {@code bottom}, if the quote has content there. */
        void strokeBar(Bar bar, float bottom) throws IOException {
            if (bar.top - bottom < LEADING / 2f) {
                return; // nothing of the quote on this page (it starts on the next one)
            }
            cs.setStrokingColor(PdfTheme.RULE);
            cs.setLineWidth(2.5f);
            cs.moveTo(bar.x, bar.top);
            cs.lineTo(bar.x, bottom);
            cs.stroke();
        }

        /**
         * A code block, laid out by the same helpers as the code PDF ({@link PdfText}): tabs expand to
         * column-aware stops (there is no glyph for U+0009) and a line longer than the box wraps onto
         * continuation lines instead of running off the page edge.
         */
        void codeBlock(String literal, float left) throws IOException {
            float fontSize = BODY - 1f;
            float cell = mono.getStringWidth("M") / 1000f * fontSize;
            int maxCols = Math.max(1, (int) Math.floor((contentRight() - left - 10f) / cell));
            List<String> lines = new ArrayList<>();
            for (List<PdfText.Run> line : PdfText.splitIntoLineRuns(stripTrailing(literal), null, CODE_TAB)) {
                for (List<PdfText.Run> visual : PdfText.wrap(line, maxCols)) {
                    StringBuilder sb = new StringBuilder();
                    visual.forEach(r -> sb.append(r.text()));
                    lines.add(sb.toString());
                }
            }
            float boxH = lines.size() * LEADING + 8f;
            need(Math.min(boxH, size.getHeight() - 2 * MARGIN));
            for (String line : lines) {
                need(LEADING);
                // light gray background strip
                cs.setNonStrokingColor(PdfTheme.CODE_BG);
                cs.addRect(left, y - 3f, contentRight() - left, LEADING);
                cs.fill();
                cs.setNonStrokingColor(CODE_FG);
                glyphs.showOnGrid(cs, mono, fontSize, cell, line, left + 5f, y);
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
                    words("[" + (alt.isBlank() ? "image" : alt) + "]", bodyItalic, BODY, PdfTheme.LINE_NUMBER),
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
            needBox(h);
            float bottom = boxTop() - h;
            cs.drawImage(img, left, bottom, w, h);
            afterBox(bottom);
        }

        void rule(float left) throws IOException {
            // A rule is a box PARA_GAP tall with the line through its middle, so it sits evenly between the
            // blocks around it instead of on the next block's baseline.
            needBox(PARA_GAP);
            float ruleY = boxTop() - PARA_GAP / 2f;
            cs.setStrokingColor(PdfTheme.RULE);
            cs.setLineWidth(0.8f);
            cs.moveTo(left, ruleY);
            cs.lineTo(contentRight(), ruleY);
            cs.stroke();
            afterBox(boxTop() - PARA_GAP);
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
            // The tallest box a fresh page holds: a row that fits in it is kept whole, a taller one is split.
            float pageBox = size.getHeight() - 2 * MARGIN + ASCENT;
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
                // Rows stack edge to edge: throughout this loop boxTop() is the top edge of the next band.
                int from = 0;
                while (from < maxLines) {
                    int remaining = maxLines - from;
                    int fit = (int) Math.floor((boxTop() - MARGIN - 4f) / LEADING);
                    if (fit < remaining && !atPageTop()) {
                        // Not all of it fits here. A row a page can hold moves there whole; a taller one
                        // starts here only if a few lines fit, then continues in page-sized bands.
                        if (remaining * LEADING + 4f <= pageBox || fit < MIN_SPLIT_LINES) {
                            newPage();
                            continue;
                        }
                    }
                    int n = Math.max(1, Math.min(remaining, fit));
                    y -= rowBand(wrapped, from, n, head, left, colW, pad);
                    from += n;
                    if (from < maxLines) {
                        newPage();
                    }
                }
            }
            y -= PARA_GAP;
        }

        /**
         * Draws lines {@code [from, from + n)} of a table row's wrapped cells as one bordered band whose top
         * edge is {@link #boxTop}, and returns the band's height. A row is normally a single band; one taller
         * than a page is drawn as several, each closed by its own border.
         */
        float rowBand(List<List<String>> wrapped, int from, int n, boolean head, float left, float colW, float pad)
                throws IOException {
            int cols = wrapped.size();
            float tableW = colW * cols;
            float rowTop = boxTop();
            float rowH = n * LEADING + 4f;
            if (head) {
                cs.setNonStrokingColor(PdfTheme.CODE_BG);
                cs.addRect(left, rowTop - rowH, tableW, rowH);
                cs.fill();
            }
            for (int ci = 0; ci < cols; ci++) {
                List<String> lines = wrapped.get(ci);
                float ty = rowTop - LEADING;
                for (int li = from; li < Math.min(from + n, lines.size()); li++) {
                    drawText(
                            lines.get(li),
                            head ? bodyBold : body,
                            BODY,
                            PdfTheme.DEFAULT_FG,
                            left + ci * colW + pad,
                            ty);
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
            return rowH;
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
                    addWords(c.getLiteral(), out, mono, size - 0.5f, PdfTheme.hex("#0a3069"), underline);
                } else if (n instanceof Link link) {
                    inline(n, out, reg, bold, italic, size, PdfTheme.hex("#0969da"), true);
                } else if (n instanceof SoftLineBreak) {
                    pendingSpace = true; // a soft break is whitespace between the words around it
                } else if (n instanceof HardLineBreak) {
                    out.add(Word.of("", reg, size, color, false, true, false));
                    pendingSpace = false;
                } else if (n instanceof Image) {
                    addWords("[" + plain(n) + "]", out, italic, size, PdfTheme.LINE_NUMBER, false);
                } else if (n instanceof FootnoteReference ref) {
                    out.add(Word.of(
                            "[" + ref.getLabel() + "]", reg, size - 2f, PdfTheme.LINE_NUMBER, false, false, false));
                } else if (n instanceof org.commonmark.node.HtmlInline html) {
                    if (!MarkdownRenderer.isHtmlComment(html.getLiteral())) { // shown as source, like the preview
                        addWords(html.getLiteral(), out, mono, size - 0.5f, PdfTheme.hex("#0a3069"), underline);
                    }
                } else if (n instanceof Strikethrough) {
                    int first = out.size();
                    inline(n, out, reg, bold, italic, size, color, underline);
                    for (int k = first; k < out.size(); k++) {
                        out.set(k, out.get(k).struck());
                    }
                } else {
                    // Inserted text (++ins++) and anything unknown: render the text plainly.
                    inline(n, out, reg, bold, italic, size, color, underline);
                }
            }
        }

        /** Splits a text literal into flow words, turning inline {@code $…$}/{@code $$…$$} math into image words. */
        void addInlineText(String literal, List<Word> out, PDFont reg, float size, Color color, boolean underline)
                throws IOException {
            if (!MathImages.isEnabled() || literal.indexOf('$') < 0) {
                addWords(literal, out, reg, size, color, underline);
                return;
            }
            for (MathSpans.Segment seg : MathSpans.segments(literal)) {
                if (seg.text() != null) {
                    addWords(seg.text(), out, reg, size, color, underline);
                } else {
                    out.add(mathWord(seg.span().latex(), seg.span().display(), reg, size, color, underline));
                    pendingSpace = false;
                }
            }
        }

        /**
         * Appends {@code literal}'s whitespace-separated words. The first word is marked as preceded by a
         * space only if the literal (or whatever came before it — a soft break, the previous literal's
         * trailing blank) really had one; a literal that starts mid-word, like the {@code !} after
         * {@code **world**}, stays glued to its neighbour.
         */
        void addWords(String literal, List<Word> out, PDFont font, float size, Color color, boolean underline) {
            int n = literal.length();
            int i = 0;
            while (i < n) {
                int j = i;
                while (j < n && isBlank(literal.charAt(j))) {
                    j++;
                }
                if (j > i) {
                    pendingSpace = true;
                }
                i = j;
                while (j < n && !isBlank(literal.charAt(j))) {
                    j++;
                }
                if (j > i) {
                    out.add(Word.of(literal.substring(i, j), font, size, color, underline, false, pendingSpace));
                    pendingSpace = false;
                }
                i = j;
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
                    return Word.math(xo, w, h, reg, size, pendingSpace);
                }
            }
            String d = display ? "$$" : "$";
            return Word.of(d + latex + d, reg, size, color, underline, false, pendingSpace);
        }

        /**
         * Greedy word-wrap flow of styled words at the current y; advances y per visual line. A space is
         * drawn — and a line may break — only before a word whose {@link Word#space()} is set; the words
         * between two such points form one unbreakable cluster ({@code un}+{@code real}+{@code ly}) that
         * moves to the next line as a whole. A single word wider than the line (a long URL) is hard-broken.
         */
        void flow(List<Word> words, float left, float right, float leading) throws IOException {
            need(leading);
            float x = left;
            boolean started = false;
            boolean prevUnderline = false;
            boolean prevStrike = false;
            int i = 0;
            while (i < words.size()) {
                Word w = words.get(i);
                if (w.brk()) {
                    y -= leading;
                    need(leading);
                    x = left;
                    started = false;
                    prevUnderline = false;
                    prevStrike = false;
                    i++;
                    continue;
                }
                int end = i + 1; // the cluster [i, end): w plus everything glued to it
                while (end < words.size()
                        && !words.get(end).brk()
                        && !words.get(end).space()) {
                    end++;
                }
                float cluster = 0f;
                for (int k = i; k < end; k++) {
                    cluster += wordWidth(words.get(k));
                }
                float sp = started && w.space() ? glyphs.width(w.font(), " ", w.size()) : 0f;
                if (started && x + sp + cluster > right) {
                    y -= leading;
                    need(leading);
                    x = left;
                    sp = 0f;
                    prevUnderline = false;
                    prevStrike = false;
                }
                if (sp > 0f) {
                    // A real space glyph, not just a gap: text extraction, search and copy/paste then see the
                    // word boundary exactly where the source had one.
                    glyphs.show(cs, w.font(), w.size(), " ", x, y);
                    if (prevUnderline && w.underline()) {
                        underline(w.color(), x, x + sp); // keep a multi-word link's underline continuous
                    }
                    if (prevStrike && w.strike()) {
                        strike(w, x, x + sp); // and a struck phrase's line
                    }
                }
                x += sp;
                for (int k = i; k < end; k++) {
                    Word part = words.get(k);
                    x = part.img() != null ? drawMath(part, x) : drawWord(part, x, left, right, leading);
                    prevUnderline = part.underline();
                    prevStrike = part.strike();
                }
                started = true;
                i = end;
            }
            y -= leading;
        }

        float wordWidth(Word w) throws IOException {
            return w.img() != null ? w.imgW() : glyphs.width(w.font(), w.text(), w.size());
        }

        float drawMath(Word w, float x) throws IOException {
            cs.drawImage(w.img(), x, y - w.imgH() * 0.25f, w.imgW(), w.imgH()); // sit ~on the baseline
            return x + w.imgW();
        }

        /**
         * Draws one text word at {@code x} and returns the x after it. A word that does not fit the rest of
         * the line moves to a fresh line; one wider than a whole line is split character by character across
         * as many lines as it needs, so nothing is ever drawn past {@code right}.
         */
        float drawWord(Word w, float x, float left, float right, float leading) throws IOException {
            String text = w.text();
            float ww = glyphs.width(w.font(), text, w.size());
            if (x + ww <= right + 0.01f) {
                return drawPiece(w, text, x);
            }
            if (ww <= right - left && x > left) {
                y -= leading;
                need(leading);
                return drawPiece(w, text, left);
            }
            int from = 0;
            while (from < text.length()) {
                int to = fitEnd(w.font(), w.size(), text, from, right - x);
                if (to == from) {
                    if (x <= left) {
                        to = from + Character.charCount(text.codePointAt(from)); // never loop on a 1-glyph line
                    } else {
                        y -= leading;
                        need(leading);
                        x = left;
                        continue;
                    }
                }
                x = drawPiece(w, text.substring(from, to), x);
                from = to;
                if (from < text.length()) {
                    y -= leading;
                    need(leading);
                    x = left;
                }
            }
            return x;
        }

        float drawPiece(Word w, String text, float x) throws IOException {
            cs.setNonStrokingColor(w.color());
            float end = x + glyphs.show(cs, w.font(), w.size(), text, x, y);
            if (w.underline()) {
                underline(w.color(), x, end);
            }
            if (w.strike()) {
                strike(w, x, end);
            }
            return end;
        }

        /** The strikethrough line for {@code w}'s text between {@code from} and {@code to}, at mid x-height. */
        void strike(Word w, float from, float to) throws IOException {
            float sy = y + w.size() * 0.28f;
            cs.setStrokingColor(w.color() == null ? PdfTheme.DEFAULT_FG : w.color()); // a math word has no color
            cs.setLineWidth(Math.max(0.6f, w.size() * 0.06f));
            cs.moveTo(from, sy);
            cs.lineTo(to, sy);
            cs.stroke();
        }

        void underline(Color color, float from, float to) throws IOException {
            cs.setStrokingColor(color);
            cs.setLineWidth(0.5f);
            cs.moveTo(from, y - 1.5f);
            cs.lineTo(to, y - 1.5f);
            cs.stroke();
        }

        /** The largest end index such that {@code text[from, end)} is no wider than {@code avail}. */
        int fitEnd(PDFont font, float size, String text, int from, float avail) throws IOException {
            int end = from;
            float used = 0f;
            while (end < text.length()) {
                int next = end + Character.charCount(text.codePointAt(end));
                float cw = glyphs.width(font, text.substring(end, next), size);
                if (used + cw > avail) {
                    break;
                }
                used += cw;
                end = next;
            }
            return end;
        }

        void drawText(String s, PDFont font, float size, Color color, float x, float y) throws IOException {
            cs.setNonStrokingColor(color);
            glyphs.show(cs, font, size, s, x, y);
        }

        /**
         * Wraps plain text (a table cell) to {@code maxW}. Breaks at spaces; a single word wider than the
         * column — a URL, a long identifier — is split so it stays inside its cell.
         */
        List<String> wrapPlain(String text, PDFont font, float size, float maxW) throws IOException {
            List<String> out = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            for (String word : text.split(" ", -1)) {
                String candidate = line.length() == 0 ? word : line + " " + word;
                if (glyphs.width(font, candidate, size) <= maxW) {
                    line = new StringBuilder(candidate);
                    continue;
                }
                if (line.length() > 0) {
                    out.add(line.toString());
                    line = new StringBuilder();
                }
                String rest = word;
                while (glyphs.width(font, rest, size) > maxW) {
                    int cut = Math.max(fitEnd(font, size, rest, 0, maxW), Character.charCount(rest.codePointAt(0)));
                    out.add(rest.substring(0, cut));
                    rest = rest.substring(cut);
                }
                line.append(rest);
            }
            out.add(line.toString());
            return out;
        }

        byte[] fetch(String url) {
            // The preview's guarded fetcher: internal-address block re-checked per redirect hop, UNC refusal,
            // regular files only, and a byte cap — exporting a document must not reach what previewing refuses.
            return com.editora.editor.PreviewImageLoader.fetchForExport(url, baseDir);
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
            } else if (c instanceof SoftLineBreak || c instanceof HardLineBreak) {
                sb.append(' '); // a $$…$$ block spans lines as soft breaks — join them, don't bail
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

    private static boolean isBlank(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
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
