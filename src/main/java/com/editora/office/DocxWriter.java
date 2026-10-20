package com.editora.office;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

import com.editora.editor.MarkdownDocInfo;
import com.editora.editor.MarkdownRenderer;
import com.editora.markdown.MarkdownMath;
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
import org.commonmark.ext.footnotes.FootnoteDefinition;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.task.list.items.TaskListItemMarker;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.ThematicBreak;
import org.jspecify.annotations.Nullable;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBookmark;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBorder;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTHyperlink;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STBorder;

/**
 * Renders a Markdown document to a MS Word {@code .docx} via Apache POI's XWPF API, walking the same
 * CommonMark AST ({@link MarkdownRenderer#parseToDocument}) as the PDF/preview renderers. Covers headings
 * (real {@code Heading1}–{@code Heading6} styles with outline levels, each bookmarked with its anchor),
 * paragraphs (bold/italic/strikethrough/underline/inline-code/links/inline pictures), bullet + ordered
 * lists (real Word lists, nested, each ordered list with its own start number; task items keep their
 * checkbox), block quotes (indented behind a rule, whatever they contain), fenced/indented code blocks, GFM
 * tables with their column alignment, thematic breaks, and local/{@code data:} images. Mermaid/unknown fenced
 * blocks fall back to a monospace code block; remote/SVG images degrade to alt text.
 *
 * <p>Nothing with text in it is skipped: a list item's non-paragraph children (a code block, a quote, a
 * table) go through the same block renderer, raw HTML is written as its source (as the preview shows it), a
 * block type this writer does not know becomes a plain paragraph, and footnote definitions are written at the
 * end of the document.
 *
 * <p>A link is live only when it is a web or mail address or a relative reference
 * ({@link InlineRun#isSafeHref}); {@code [x](#heading)} becomes an internal link to that heading's bookmark.
 */
public final class DocxWriter {

    private static final String MONO = "Consolas";
    private static final String LINK_COLOR = "0563C1";
    private static final String QUOTE_RULE = "BFBFBF";
    private static final int MAX_IMAGE_PX = 480;
    /** Twips a block quote moves its content right, per level of quoting. */
    private static final int QUOTE_INDENT = 480;
    /** Twips from a list item's number or bullet to its text. */
    private static final int LIST_HANGING = 360;

    private DocxWriter() {}

    /** What every block needs: the document, where pictures resolve, and the heading bookmarks. */
    private record Ctx(
            XWPFDocument doc,
            @Nullable Path baseDir,
            @Nullable List<String> mmdc,
            DocxScaffold scaffold,
            Map<Heading, String> anchors,
            Map<String, String> bookmarks) {}

    public static void write(String markdown, @Nullable Path baseDir, @Nullable List<String> mmdc, Path out)
            throws IOException {
        // The preview's parse (math is nodes), with alerts and definition lists as blocks this writer knows.
        Node ast = com.editora.markdown.MarkdownExportTree.lower(
                MarkdownRenderer.parseForRender(markdown), com.editora.editor.MarkdownHtmlExport.wikiPaths(baseDir));
        try (XWPFDocument doc = new XWPFDocument()) {
            Map<Heading, String> anchors = MarkdownDocInfo.headingAnchors(ast);
            Map<String, String> bookmarks = new HashMap<>();
            for (String anchor : anchors.values()) {
                bookmarks.put(anchor, "h_" + (bookmarks.size() + 1)); // a bookmark name allows no hyphen
            }
            Ctx ctx = new Ctx(doc, baseDir, mmdc, new DocxScaffold(doc), anchors, bookmarks);
            var core = doc.getProperties().getCoreProperties();
            core.setCreator("Editora");
            String title = MarkdownDocInfo.title(ast, fileTitle(out));
            if (title != null) {
                core.setTitle(title);
            }
            for (Node n = ast.getFirstChild(); n != null; n = n.getNext()) {
                block(ctx, n, 0, 0);
            }
            for (FootnoteDefinition def : InlineRun.footnotes(ast)) {
                footnote(ctx, def);
            }
            try (OutputStream os = Files.newOutputStream(out)) {
                doc.write(os);
            }
        }
    }

    /** The file name without its extension — the title of a document that states none. */
    static @Nullable String fileTitle(Path out) {
        if (out == null || out.getFileName() == null) {
            return null;
        }
        String name = out.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /**
     * Writes block {@code n} with its left edge {@code indent} twips in, inside {@code quotes} levels of
     * block quote. Indentation is additive — a list in a quote in a list item ends up where it belongs —
     * rather than set per construct, which flattened whatever a quote contained to one depth.
     */
    private static void block(Ctx ctx, Node n, int indent, int quotes) {
        XWPFDocument doc = ctx.doc();
        if (n instanceof Heading h) {
            XWPFParagraph p = paragraph(ctx, indent, quotes);
            int level = Math.min(6, h.getLevel());
            p.setStyle("Heading" + level);
            String bookmark = ctx.bookmarks().get(ctx.anchors().get(h));
            if (bookmark != null) {
                BigInteger id = BigInteger.valueOf(Integer.parseInt(bookmark.substring(2)));
                CTBookmark start = p.getCTP().addNewBookmarkStart();
                start.setName(bookmark);
                start.setId(id);
                p.getCTP().addNewBookmarkEnd().setId(id);
            }
            addRuns(ctx, p, InlineRun.flatten(h), true, DocxScaffold.HEADING_POINTS[level - 1]);
        } else if (n instanceof MarkdownMath.Block math) {
            // A $$ block: a picture when it is a finished formula that renders, else what was typed.
            if (!math.isClosed() || !embedBlock(doc, OfficeImages.renderMath(math.getLatex(), true))) {
                paragraph(ctx, indent, quotes).createRun().setText(MarkdownMath.source(math));
            }
        } else if (n instanceof Paragraph p) {
            String math = InlineRun.soleDisplayMath(p);
            if (math != null && embedBlock(doc, OfficeImages.renderMath(math, true))) {
                return; // rendered the $$…$$ block as an image
            }
            if (isBlockImage(p)) {
                embedImage(paragraph(ctx, indent, quotes), (Image) p.getFirstChild(), ctx.baseDir());
            } else {
                addRuns(ctx, paragraph(ctx, indent, quotes), InlineRun.flatten(p), false, 0);
            }
        } else if (n instanceof BulletList bl) {
            list(ctx, bl, indent, quotes, 0);
        } else if (n instanceof OrderedList ol) {
            list(ctx, ol, indent, quotes, 0);
        } else if (n instanceof BlockQuote bq) {
            // An alert is a quote whose rule and title carry its kind's colour.
            String tint = bq instanceof com.editora.markdown.MarkdownExportTree.AlertQuote alert
                    ? com.editora.markdown.MarkdownExportTree.color(alert.getKind())
                    : null;
            String outer = ALERT_RULE.get();
            int first = doc.getParagraphs().size();
            if (tint != null) {
                ALERT_RULE.set(tint);
            }
            try {
                for (Node c = bq.getFirstChild(); c != null; c = c.getNext()) {
                    block(ctx, c, indent + QUOTE_INDENT, quotes + 1);
                }
            } finally {
                if (outer == null) {
                    ALERT_RULE.remove();
                } else {
                    ALERT_RULE.set(outer);
                }
            }
            if (tint != null && doc.getParagraphs().size() > first) {
                doc.getParagraphs().get(first).getRuns().forEach(r -> r.setColor(tint)); // the title line
            }
        } else if (n instanceof com.editora.markdown.MarkdownExportTree.Indented descriptions) {
            for (Node c = descriptions.getFirstChild(); c != null; c = c.getNext()) {
                block(ctx, c, indent + QUOTE_INDENT, quotes);
            }
        } else if (n instanceof FencedCodeBlock f) {
            if (InlineRun.isMermaidInfo(f.getInfo())
                    && embedBlock(doc, OfficeImages.renderMermaid(ctx.mmdc(), f.getLiteral()))) {
                return; // rendered the ```mermaid block as a diagram image
            }
            codeBlock(ctx, f.getLiteral(), indent, quotes);
        } else if (n instanceof IndentedCodeBlock ic) {
            codeBlock(ctx, ic.getLiteral(), indent, quotes);
        } else if (n instanceof ThematicBreak) {
            XWPFParagraph p = doc.createParagraph();
            p.setBorderBottom(Borders.SINGLE);
        } else if (n instanceof TableBlock t) {
            table(ctx, t);
        } else if (n instanceof HtmlBlock hb) {
            // An HTML comment is invisible; other raw HTML is shown as its source, like the preview.
            String shown = MarkdownDocInfo.htmlSourceShown(hb.getLiteral()); // "<!-- c --> text" keeps the text
            if (!shown.isEmpty()) {
                codeBlock(ctx, shown, indent, quotes);
            }
        } else if (n instanceof FootnoteDefinition) {
            // written at the end of the document by write()
        } else {
            // A block type with no dedicated rendering: keep its text rather than dropping it.
            List<InlineRun> runs = InlineRun.flatten(n);
            if (runs.stream().anyMatch(r -> !r.text().isBlank())) {
                addRuns(ctx, paragraph(ctx, indent, quotes), runs, false, 0);
            }
        }
    }

    /** The rule colour of the alert being written (null in a plain quote); a document is written by one thread. */
    private static final ThreadLocal<String> ALERT_RULE = new ThreadLocal<>();

    /** A new body paragraph {@code indent} twips in; inside a block quote it carries the quote's rule. */
    private static XWPFParagraph paragraph(Ctx ctx, int indent, int quotes) {
        XWPFParagraph p = ctx.doc().createParagraph();
        if (indent > 0) {
            p.setIndentationLeft(indent);
        }
        if (quotes > 0) {
            CTPPr ppr = p.getCTP().isSetPPr() ? p.getCTP().getPPr() : p.getCTP().addNewPPr();
            CTBorder left = (ppr.isSetPBdr() ? ppr.getPBdr() : ppr.addNewPBdr()).addNewLeft();
            left.setVal(STBorder.SINGLE);
            left.setSz(BigInteger.valueOf(12));
            left.setSpace(BigInteger.valueOf(8));
            String alert = ALERT_RULE.get();
            left.setColor(alert == null ? QUOTE_RULE : alert);
            if (alert != null) {
                left.setSz(BigInteger.valueOf(24)); // a heavier rule than a plain quote's
            }
        }
        return p;
    }

    /** A footnote definition: its {@code [label]} marker leading the first paragraph, then any further blocks. */
    private static void footnote(Ctx ctx, FootnoteDefinition def) {
        String marker = InlineRun.footnoteMarker(def.getLabel()) + " ";
        boolean first = true;
        for (Node c = def.getFirstChild(); c != null; c = c.getNext()) {
            if (first && c instanceof Paragraph p) {
                XWPFParagraph par = ctx.doc().createParagraph();
                par.createRun().setText(marker);
                addRuns(ctx, par, InlineRun.flatten(p), false, 0);
            } else {
                if (first) {
                    ctx.doc().createParagraph().createRun().setText(marker);
                }
                block(ctx, c, LIST_HANGING, 0);
            }
            first = false;
        }
        if (first) {
            ctx.doc().createParagraph().createRun().setText(marker); // an empty definition still shows its label
        }
    }

    /**
     * A list as a Word list: each item's first paragraph carries the list's numbering at {@code level}, and
     * everything else the item holds — further paragraphs, a code block, a quote, a table, a nested list —
     * lines up with that paragraph's text.
     */
    private static void list(Ctx ctx, Node listNode, int indent, int quotes, int level) {
        boolean ordered = listNode instanceof OrderedList;
        int ilvl = Math.min(level, DocxScaffold.MAX_LEVEL);
        BigInteger numId = ordered
                ? ctx.scaffold().numberedList(ilvl, ((OrderedList) listNode).getMarkerStartNumber())
                : ctx.scaffold().bulletList();
        int textLeft = indent + 2 * LIST_HANGING;
        for (Node item = listNode.getFirstChild(); item != null; item = item.getNext()) {
            if (!(item instanceof ListItem)) {
                continue;
            }
            String task = "";
            boolean first = true;
            for (Node c = item.getFirstChild(); c != null; c = c.getNext()) {
                if (c instanceof TaskListItemMarker marker) {
                    task = marker.isChecked() ? "☑ " : "☐ "; // "- [x] done" keeps its state
                } else if (c instanceof BulletList || c instanceof OrderedList) {
                    if (first) {
                        listParagraph(ctx, numId, ilvl, textLeft, quotes, task);
                        first = false;
                    }
                    list(ctx, c, textLeft - LIST_HANGING, quotes, level + 1);
                } else if (c instanceof Paragraph p && !isBlockImage(p) && InlineRun.soleDisplayMath(p) == null) {
                    XWPFParagraph par = first
                            ? listParagraph(ctx, numId, ilvl, textLeft, quotes, task)
                            : paragraph(ctx, textLeft, quotes);
                    addRuns(ctx, par, InlineRun.flatten(p), false, 0);
                    first = false;
                } else {
                    // Anything else in the item — a code block, a quote, a table, an image, a rule — used to be
                    // skipped, so "1. Run:" followed by a fenced command lost the command.
                    if (first) {
                        listParagraph(ctx, numId, ilvl, textLeft, quotes, task);
                        first = false;
                    }
                    block(ctx, c, textLeft, quotes);
                }
            }
            if (first) {
                listParagraph(ctx, numId, ilvl, textLeft, quotes, task); // an empty item still counts
            }
        }
    }

    /** The numbered (or bulleted) paragraph that opens a list item, with the item's checkbox when it has one. */
    private static XWPFParagraph listParagraph(
            Ctx ctx, BigInteger numId, int ilvl, int textLeft, int quotes, String task) {
        XWPFParagraph p = paragraph(ctx, textLeft, quotes);
        p.setNumID(numId);
        p.setNumILvl(BigInteger.valueOf(ilvl));
        p.setIndentationLeft(textLeft); // after the numbering: an explicit indent wins over the level's own
        p.setIndentationHanging(LIST_HANGING);
        if (!task.isEmpty()) {
            p.createRun().setText(task);
        }
        return p;
    }

    private static void codeBlock(Ctx ctx, String text, int indent, int quotes) {
        String[] lines = text.replace("\r\n", "\n").split("\n", -1);
        // drop a trailing empty line from the fence
        int end = lines.length;
        if (end > 0 && lines[end - 1].isEmpty()) {
            end--;
        }
        for (int i = 0; i < end; i++) {
            XWPFParagraph p = paragraph(ctx, indent, quotes); // stays under its list item / quote
            p.setSpacingAfter(0);
            XWPFRun r = p.createRun();
            r.setFontFamily(MONO);
            r.setFontSize(10);
            r.setText(lines[i]);
        }
    }

    private static void table(Ctx ctx, TableBlock t) {
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
        XWPFTable table = ctx.doc().createTable(rows.size(), ncol);
        for (int ri = 0; ri < rows.size(); ri++) {
            XWPFTableRow row = table.getRow(ri);
            List<TableCell> cells = rows.get(ri);
            for (int ci = 0; ci < ncol; ci++) {
                XWPFTableCell cell = row.getCell(ci);
                XWPFParagraph p = cell.getParagraphs().get(0);
                if (ci < cells.size()) {
                    addRuns(ctx, p, InlineRun.flatten(cells.get(ci)), ri == 0, 0);
                    p.setAlignment(alignment(cells.get(ci).getAlignment()));
                }
            }
        }
    }

    /** The column alignment of a {@code | :--- | :---: | ---: |} delimiter row; unmarked columns read left. */
    private static ParagraphAlignment alignment(TableCell.Alignment a) {
        if (a == TableCell.Alignment.CENTER) {
            return ParagraphAlignment.CENTER;
        }
        return a == TableCell.Alignment.RIGHT ? ParagraphAlignment.RIGHT : ParagraphAlignment.LEFT;
    }

    /** Emits the styled inline runs into {@code p}; {@code forceBold}/{@code size} apply to headings/headers. */
    private static void addRuns(Ctx ctx, XWPFParagraph p, List<InlineRun> runs, boolean forceBold, int size) {
        XWPFRun last = null;
        for (InlineRun ir : runs) {
            if (ir.isBreak()) {
                if (last == null) {
                    last = p.createRun();
                }
                last.addBreak();
                continue;
            }
            if (ir.image() != null) {
                // A picture inside a line. What cannot be embedded is written as its alt text, as before.
                XWPFRun picture = p.createRun();
                if (pictureInto(picture, OfficeImages.load(ir.image(), ctx.baseDir()), "image")) {
                    last = picture;
                    continue;
                }
                p.removeRun(p.getRuns().indexOf(picture));
                if (ir.text().isEmpty()) {
                    continue;
                }
            }
            if (ir.isMath()) {
                // A formula in the line: a picture, or its source when it cannot be drawn (or math is off).
                boolean display = ir.text().startsWith("$$");
                XWPFRun m = embedInlineMath(p, OfficeImages.renderMath(ir.latex(), display), display);
                if (m != null) {
                    last = m;
                    continue;
                }
            }
            last = emitTextRun(ctx, p, ir, ir.text(), forceBold, size);
        }
    }

    /** Creates one styled run carrying {@code text} (reusing {@code ir}'s styling/link). */
    private static XWPFRun emitTextRun(
            Ctx ctx, XWPFParagraph p, InlineRun ir, @Nullable String text, boolean forceBold, int size) {
        XWPFRun linked = linkRun(ctx, p, ir.href());
        boolean link = linked != null;
        XWPFRun r;
        if (linked != null) {
            linked.setColor(LINK_COLOR);
            linked.setUnderline(UnderlinePatterns.SINGLE);
            r = linked;
        } else {
            r = p.createRun();
        }
        r.setText(text);
        if (ir.bold() || forceBold) {
            r.setBold(true);
        }
        if (ir.italic()) {
            r.setItalic(true);
        }
        if (ir.strike()) {
            r.setStrikeThrough(true);
        }
        if (ir.highlight()) {
            r.setTextHighlightColor("yellow");
        }
        if (ir.script() != 0) {
            r.setSubscript(
                    ir.script() > 0
                            ? org.apache.poi.xwpf.usermodel.VerticalAlign.SUPERSCRIPT
                            : org.apache.poi.xwpf.usermodel.VerticalAlign.SUBSCRIPT);
        }
        if (ir.underline() && !link) {
            r.setUnderline(UnderlinePatterns.SINGLE);
        }
        if (ir.code()) {
            r.setFontFamily(MONO);
        }
        if (size > 0) {
            r.setFontSize(size);
        }
        return r;
    }

    /**
     * A hyperlink run for {@code href}, or null when the text is to be written plain: no link, a link to a
     * heading this document does not have, or a scheme a document must not carry live.
     */
    private static @Nullable XWPFRun linkRun(Ctx ctx, XWPFParagraph p, @Nullable String href) {
        if (href == null || href.isBlank()) {
            return null;
        }
        String fragment = MarkdownDocInfo.fragment(href.strip());
        if (fragment != null) {
            String bookmark = ctx.bookmarks().get(fragment);
            if (bookmark == null) {
                return null;
            }
            CTHyperlink link = p.getCTP().addNewHyperlink();
            link.setAnchor(bookmark);
            XWPFHyperlinkRun run = new XWPFHyperlinkRun(link, link.addNewR(), p);
            p.addRun(run);
            return run;
        }
        if (!InlineRun.isSafeHref(href)) {
            return null;
        }
        try {
            return p.createHyperlinkRun(href.strip().replace(" ", "%20"));
        } catch (RuntimeException e) {
            return null; // not a URI the package can hold a relationship to: keep the text
        }
    }

    /** Embeds {@code png} as an inline picture sized to roughly the line height; null when it can't decode. */
    private static @Nullable XWPFRun embedInlineMath(XWPFParagraph p, byte @Nullable [] png, boolean display) {
        if (png == null) {
            return null;
        }
        try {
            BufferedImage bi = ImageIO.read(new ByteArrayInputStream(png));
            if (bi == null || bi.getHeight() <= 0) {
                return null;
            }
            double maxH = display ? 30.0 : 14.0; // px treated as points (matches embedBytes' sizing model)
            double scale = Math.min(1.0, maxH / bi.getHeight());
            int w = (int) Math.round(bi.getWidth() * scale);
            int h = (int) Math.round(bi.getHeight() * scale);
            XWPFRun r = p.createRun();
            r.addPicture(
                    new ByteArrayInputStream(png),
                    OfficeImages.poiPictureType(png),
                    "math",
                    Units.toEMU(w),
                    Units.toEMU(h));
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isBlockImage(Paragraph p) {
        Node c = p.getFirstChild();
        return c instanceof Image && c.getNext() == null;
    }

    private static void embedImage(XWPFParagraph p, Image img, @Nullable Path baseDir) {
        byte[] bytes = OfficeImages.load(img.getDestination(), baseDir);
        String alt = InlineRun.textOf(img);
        String fallback = alt.isEmpty() ? img.getDestination() : alt;
        if (!embedBytes(p, bytes, fallback)) {
            XWPFRun r = p.createRun();
            r.setText(fallback);
            r.setItalic(true);
        }
    }

    /** Puts raster {@code bytes} into {@code run}, scaled to the page; false when they cannot be decoded. */
    private static boolean pictureInto(XWPFRun run, byte @Nullable [] bytes, String name) {
        if (bytes == null) {
            return false;
        }
        try {
            BufferedImage bi = ImageIO.read(new ByteArrayInputStream(bytes));
            if (bi == null) {
                return false;
            }
            double scale = Math.min(1.0, (double) MAX_IMAGE_PX / Math.max(1, bi.getWidth()));
            int w = (int) Math.round(bi.getWidth() * scale);
            int h = (int) Math.round(bi.getHeight() * scale);
            run.addPicture(
                    new ByteArrayInputStream(bytes),
                    OfficeImages.poiPictureType(bytes),
                    name,
                    Units.toEMU(w),
                    Units.toEMU(h));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Embeds {@code bytes} as a picture paragraph of its own. Nothing is added to the document when there are
     * no bytes or they cannot be decoded, so the caller's fallback (the block as text) is not preceded by an
     * empty paragraph.
     */
    private static boolean embedBlock(XWPFDocument doc, byte @Nullable [] bytes) {
        if (bytes == null) {
            return false;
        }
        XWPFParagraph p = doc.createParagraph();
        if (embedBytes(p, bytes, null)) {
            return true;
        }
        doc.removeBodyElement(doc.getPosOfParagraph(p));
        return false;
    }

    /**
     * Embeds raster {@code bytes} as a scaled picture in {@code p}; returns false (so the caller can fall
     * back) when {@code bytes} is null or undecodable. When {@code altOnFail} is non-null a failed embed
     * writes it as italic text and still returns true (used by image embedding, which has its own fallback).
     */
    private static boolean embedBytes(XWPFParagraph p, byte @Nullable [] bytes, @Nullable String altOnFail) {
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
