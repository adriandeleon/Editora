package com.editora.pdf;

import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.editora.editor.MarkdownRenderer;
import com.editora.editor.MathImages;
import com.editora.editor.MathSpans;
import com.editora.editor.PreviewImageLoader;
import com.editora.editor.TextMateHighlighter;
import com.editora.mermaid.Mermaid;
import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.parser.LoaderContext;
import com.github.weisj.jsvg.parser.SVGLoader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.commonmark.ext.footnotes.FootnoteDefinition;
import org.commonmark.ext.footnotes.FootnoteReference;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.heading.anchor.IdGenerator;
import org.commonmark.ext.task.list.items.TaskListItemMarker;
import org.commonmark.node.AbstractVisitor;
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
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.fxmisc.richtext.model.StyleSpans;

/**
 * Renders Markdown to a <b>native vector</b> (searchable) PDF, mirroring the editor's preview coverage:
 * headings, inline bold/italic/code/links/strikethrough, lists (incl. task items), block quotes, code blocks, rules,
 * tables, images, and embedded Mermaid diagrams. Reuses {@link MarkdownRenderer#parseToDocument} for the
 * CommonMark AST. Body text uses the bundled Inter family (embedded + subset, matching the on-screen
 * preview on every platform); code uses the bundled JetBrains Mono; Mermaid blocks, images and formulas embed
 * as raster pictures. Characters those fonts lack (CJK, Arabic, Hebrew, Thai, …) are drawn with a system
 * fallback font ({@link PdfGlyphs}); {@code write} returns how many characters no font could draw.
 *
 * <p>The PDF is navigable: links are clickable ({@code http}, {@code https} and {@code mailto} open the
 * target; {@code #heading} jumps to that heading), the headings form the outline (bookmarks), and — unless
 * the {@link PdfPageSpec} turns it off — every page has a footer with the document name and "Page n of N".
 * It is not a <em>tagged</em> PDF: there is no structure tree, so a screen reader gets the text in drawing
 * order without heading or table semantics.
 *
 * <p>Blocking — call off the FX thread.
 */
public final class MarkdownPdfWriter {

    /** The margin used unless the {@link PdfPageSpec} sets one. */
    static final float MARGIN = 50f;

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
    /** How far a text line's box reaches below its baseline. */
    private static final float DESCENT = LEADING - ASCENT;
    /** Space between a list marker (number, bullet, checkbox) and the item text. */
    private static final float MARKER_GAP = 4f;
    /** Side of a task-list checkbox. */
    private static final float CHECKBOX = 8f;
    /** A table row taller than a page starts on the current page only if this many of its lines fit there. */
    private static final int MIN_SPLIT_LINES = 3;
    /** Padding between a table cell's border and its text, on each side. */
    static final float CELL_PAD = 4f;
    /** … and in a table so wide that its words would not fit with the usual padding. */
    static final float TIGHT_CELL_PAD = 2f;
    /** A table's header row is repeated on continuation pages unless it is taller than this share of a page. */
    private static final float MAX_REPEATED_HEADER = 1f / 3f;
    /** Tab stops inside a code block (CommonMark's own tab width). */
    private static final int CODE_TAB = 4;
    /** A fenced block longer than this is not syntax-coloured (the preview's own limit). */
    private static final int MAX_HIGHLIGHT_CHARS = 50_000;
    /** The footer's baseline sits this far below the bottom margin's edge. */
    private static final float FOOTER_DROP = 22f;
    /** An image in a line of text is at most this many times the text size tall (it stays inside the line). */
    private static final float INLINE_IMAGE_HEIGHT = 1.2f;
    /** … and sits this far below the baseline, as a fraction of the text size. */
    private static final float INLINE_IMAGE_DROP = 0.2f;

    private static final Color CODE_FG = PdfTheme.hex("#24292f");
    private static final Color LINK_FG = PdfTheme.hex("#0969da");

    private MarkdownPdfWriter() {}

    /**
     * What an export produced besides the file.
     *
     * @param unrendered characters no available font could draw (written as {@code ?})
     * @param failedDiagrams Mermaid blocks whose render failed and that were written as their source instead
     */
    public record Outcome(int unrendered, int failedDiagrams) {}

    /**
     * @param mmdcCommand the resolved mmdc command (for ```mermaid blocks), or null to render them as code.
     * @return the number of characters that no available font could draw (written as {@code ?})
     */
    public static int write(String markdown, Path baseDir, String pageSizeKey, List<String> mmdcCommand, Path out)
            throws IOException {
        return write(markdown, baseDir, PdfPageSpec.of(pageSizeKey), PdfDocMeta.NONE, mmdcCommand, out)
                .unrendered();
    }

    /**
     * Renders Markdown source on the page {@code spec} describes; {@code meta} supplies the document name
     * (the PDF's Title and the footer) and the footer's localised page label.
     */
    public static Outcome write(
            String markdown, Path baseDir, PdfPageSpec spec, PdfDocMeta meta, List<String> mmdcCommand, Path out)
            throws IOException {
        Node ast = MarkdownRenderer.parseToDocument(markdown);
        return write(ast, baseDir, spec, meta, mmdcCommand, out, SystemFontFiles.get(), MathImages.isEnabled());
    }

    /**
     * Renders an already-built CommonMark document — for content that is <em>data</em>, not Markdown source
     * (the CSV export builds its table node by node, so a cell like {@code __init__} is never re-parsed, and
     * {@code $5 to $9} is never read as a formula).
     */
    public static int write(Node ast, Path baseDir, String pageSizeKey, List<String> mmdcCommand, Path out)
            throws IOException {
        return writeData(ast, baseDir, PdfPageSpec.of(pageSizeKey), PdfDocMeta.NONE, mmdcCommand, out)
                .unrendered();
    }

    /** As {@link #write(Node, Path, String, List, Path)} on the page {@code spec} describes, with {@code meta}. */
    public static Outcome writeData(
            Node ast, Path baseDir, PdfPageSpec spec, PdfDocMeta meta, List<String> mmdcCommand, Path out)
            throws IOException {
        return write(ast, baseDir, spec, meta, mmdcCommand, out, SystemFontFiles.get(), false);
    }

    /** A parsed Markdown document with explicit fallback font files (tests pin the list). */
    static int write(
            Node ast, Path baseDir, String pageSizeKey, List<String> mmdcCommand, Path out, List<Path> fallbackFonts)
            throws IOException {
        return write(
                        ast,
                        baseDir,
                        PdfPageSpec.of(pageSizeKey),
                        PdfDocMeta.NONE,
                        mmdcCommand,
                        out,
                        fallbackFonts,
                        MathImages.isEnabled())
                .unrendered();
    }

    /** {@code math}: whether {@code $…$} in text is a formula (Markdown source) or literal text (data). */
    static Outcome write(
            Node ast,
            Path baseDir,
            PdfPageSpec spec,
            PdfDocMeta meta,
            List<String> mmdcCommand,
            Path out,
            List<Path> fallbackFonts,
            boolean math)
            throws IOException {
        try {
            return render(ast, baseDir, spec, meta, mmdcCommand, out, fallbackFonts, math);
        } catch (IOException | RuntimeException e) {
            if (fallbackFonts.isEmpty()) {
                throw e;
            }
            // An arbitrary system font is the one input here that is not under our control: if PDFBox cannot
            // embed it after all, export with the bundled fonts (and an honest missing-glyph count) rather
            // than fail. A failure that has nothing to do with fonts simply happens again and propagates.
            return render(ast, baseDir, spec, meta, mmdcCommand, out, List.of(), math);
        }
    }

    private static Outcome render(
            Node ast,
            Path baseDir,
            PdfPageSpec spec,
            PdfDocMeta meta,
            List<String> mmdcCommand,
            Path out,
            List<Path> fallbackFonts,
            boolean math)
            throws IOException {
        try (PDDocument doc = new PDDocument();
                PdfGlyphs glyphs = new PdfGlyphs(doc, fallbackFonts)) {
            Cur c = new Cur(doc, spec, baseDir, mmdcCommand, glyphs, math, headingIds(ast));
            for (Node n = ast.getFirstChild(); n != null; n = n.getNext()) {
                c.block(n, c.margin);
            }
            c.finish(meta);
            doc.save(out.toFile());
            return new Outcome(glyphs.missing(), c.failedDiagrams);
        }
    }

    /**
     * The anchor id of every heading in {@code ast}, in document order. The ids are the HTML export's
     * ({@code MarkdownHtmlExport} renders with commonmark's heading-anchor extension, whose generator this
     * is, fed the same text), so {@code [x](#some-heading)} lands on the same heading in both exports — and
     * they match the links {@code MarkdownToc} writes for a table of contents.
     */
    static Map<Heading, String> headingIds(Node ast) {
        Map<Heading, String> ids = new IdentityHashMap<>();
        IdGenerator generator = IdGenerator.builder().build();
        ast.accept(new AbstractVisitor() {
            @Override
            public void visit(Heading heading) {
                StringBuilder text = new StringBuilder();
                heading.accept(new AbstractVisitor() {
                    @Override
                    public void visit(Text t) {
                        text.append(t.getLiteral());
                    }

                    @Override
                    public void visit(Code c) {
                        text.append(c.getLiteral());
                    }
                });
                ids.put(heading, generator.generateId(text.toString().trim().toLowerCase()));
            }
        });
        return ids;
    }

    /**
     * The widths of a table's columns, which add up to {@code total}. {@code natural[i]} is the width column
     * {@code i} needs to show its widest cell on one line and {@code least[i]} the width of its widest
     * unbreakable word (both including cell padding).
     *
     * <p>When everything fits, the columns share the width in proportion to their natural widths. When it
     * does not, each column first gets its floor — its widest word, so short words are not broken mid-word
     * the way equal columns broke them in a wide table — and what is left goes to the columns that still
     * want more, in proportion to how much more: long cells wrap, short ones stay whole. One very long word
     * (a URL) cannot claim more than {@code maxFloor} of the table. If even the floors do not fit, the widest
     * are cut back to a common cap, so only the longest words break rather than a letter off every column.
     */
    static float[] columnWidths(float[] natural, float[] least, float total) {
        int n = natural.length;
        float[] w = new float[n];
        float sumNatural = 0f;
        for (float v : natural) {
            sumNatural += v;
        }
        if (sumNatural <= total) {
            for (int i = 0; i < n; i++) {
                w[i] = sumNatural > 0f ? natural[i] / sumNatural * total : total / n;
            }
            return w;
        }
        float maxFloor = Math.max(total / n, total * 0.4f);
        float[] floor = new float[n];
        float sumFloor = 0f;
        for (int i = 0; i < n; i++) {
            floor[i] = Math.min(Math.min(least[i], natural[i]), maxFloor);
            sumFloor += floor[i];
        }
        if (sumFloor >= total) {
            // Find the cap c with sum(min(floor, c)) == total: walk the floors from the narrowest up, giving
            // each its full width while the rest could still get at least as much.
            float[] sorted = floor.clone();
            java.util.Arrays.sort(sorted);
            float left = total;
            float cap = total / n;
            for (int i = 0; i < n; i++) {
                cap = left / (n - i);
                if (sorted[i] > cap) {
                    break;
                }
                left -= sorted[i];
            }
            for (int i = 0; i < n; i++) {
                w[i] = Math.min(floor[i], cap);
            }
            return w;
        }
        float want = sumNatural - sumFloor; // > 0: the naturals exceed the total, the floors do not
        for (int i = 0; i < n; i++) {
            w[i] = floor[i] + (total - sumFloor) * (natural[i] - floor[i]) / want;
        }
        return w;
    }

    /** Where a link leads: an external URI, or the id of a heading in this document. */
    private record LinkRef(String uri, String anchor) {}

    /**
     * The target of a link to {@code destination}, or null when the PDF gives it no action: only
     * {@code http}, {@code https} and {@code mailto} URIs and in-document {@code #heading} links are live.
     * Anything else — a relative file path, {@code file:}, {@code javascript:} — keeps its link styling but
     * does nothing when clicked.
     */
    private static LinkRef linkRef(String destination) {
        if (destination == null) {
            return null;
        }
        String d = destination.strip();
        if (d.length() > 1 && d.charAt(0) == '#') {
            return new LinkRef(null, d.substring(1));
        }
        String lower = d.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:")) {
            return new LinkRef(asciiUri(d), null);
        }
        return null;
    }

    /** {@code uri} with spaces, controls and non-ASCII characters percent-encoded (a PDF URI is 7-bit). */
    private static String asciiUri(String uri) {
        StringBuilder sb = new StringBuilder(uri.length());
        for (byte b : uri.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if (c <= 0x20 || c >= 0x7F) {
                sb.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)));
                sb.append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            } else {
                sb.append((char) c);
            }
        }
        return sb.toString();
    }

    /**
     * A flowed inline token: a text word (the common case), a forced line break ({@code brk}), or an inline
     * picture — a formula or an image ({@code img != null}, sized {@code imgW}×{@code imgH} in points and
     * reaching {@code imgDrop} below the baseline). {@code font} stays set on a picture word so the flow can
     * measure the space before it. {@code link} is set on every word of a live link.
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
            LinkRef link,
            PDImageXObject img,
            float imgW,
            float imgH,
            float imgDrop) {
        static Word of(
                String text, PDFont font, float size, Color color, boolean underline, boolean brk, boolean space) {
            return new Word(text, font, size, color, underline, false, brk, space, null, null, 0, 0, 0);
        }

        static Word picture(PDImageXObject img, float w, float h, float drop, PDFont font, float size, boolean space) {
            return new Word("", font, size, null, false, false, false, space, null, img, w, h, drop);
        }

        /** This word struck through ({@code ~~text~~}). */
        Word struck() {
            return new Word(text, font, size, color, underline, true, brk, space, link, img, imgW, imgH, imgDrop);
        }

        /** This word as part of the link {@code ref}. */
        Word linked(LinkRef ref) {
            return new Word(text, font, size, color, underline, strike, brk, space, ref, img, imgW, imgH, imgDrop);
        }
    }

    /**
     * One placed fragment of a laid-out line: a word (or the part of a broken word) at {@code x} from the
     * line's left edge, {@code width} wide. A {@code space} piece is the gap before a word, drawn as a real
     * space glyph; its decorations say whether the underline, strike or link of the words around it runs
     * through. A picture piece is drawn at {@code scale} times its word's size.
     */
    private record Piece(
            Word word,
            String text,
            float x,
            float width,
            boolean space,
            boolean underline,
            boolean strike,
            LinkRef link,
            float scale) {}

    /** A laid-out line: its pieces, its width, and how far its pictures reach above and below the baseline. */
    private static final class Line {
        final List<Piece> pieces = new ArrayList<>();
        float width;
        float above;
        float below;
    }

    /** A loaded picture and its natural size in points. */
    private record Pic(PDImageXObject image, float width, float height) {}

    /** A link's rectangle on a page, waiting for the final pass (a {@code #heading} may be defined later). */
    private record LinkBox(PDPage page, float x1, float y1, float x2, float y2, LinkRef ref) {}

    /** A heading as the outline and {@code #anchor} links see it: where it starts. */
    private record Mark(int level, String title, PDPage page, float top) {}

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
        final PdfPageSpec spec;
        /** The page margin, and the lowest baseline content may use (the margin, or above the footer). */
        final float margin;

        final float bottom;
        final float footerY;
        final Path baseDir;
        final List<String> mmdc;
        final boolean math;
        final PDFont body;
        final PDFont bodyBold;
        final PDFont bodyItalic;
        final PDFont bodyBoldItalic;
        final PDType0Font mono;
        /** The styled faces of the code font, embedded only when a highlighted block needs them. */
        PDType0Font monoBold;

        PDType0Font monoItalic;
        final PdfGlyphs glyphs;
        PDPage page;
        PDPageContentStream cs;
        float y;
        /** Inline-collection state: whitespace was seen since the last word (see {@link Word#space()}). */
        boolean pendingSpace;
        /** The block quotes being laid out, outermost first — each owes a bar segment to every page it touches. */
        final List<Bar> bars = new ArrayList<>();
        /** Mermaid blocks that were attempted and written as source because the render failed. */
        int failedDiagrams;
        /** Every heading's anchor id ({@link #headingIds}), the headings met so far, and where each id leads. */
        final Map<Heading, String> ids;

        final List<Mark> marks = new ArrayList<>();
        final Map<String, Mark> anchors = new HashMap<>();
        final List<LinkBox> links = new ArrayList<>();
        /** Pictures by destination (a badge repeated in a table is fetched and embedded once); null = failed. */
        final Map<String, Pic> pictures = new HashMap<>();
        /** The cell padding of the table being laid out ({@link #CELL_PAD}, or tighter when it is crowded). */
        float cellPad = CELL_PAD;
        /** Formulas by source, style, size and colour — a table lays its cells out twice. */
        final Map<String, Word> formulas = new HashMap<>();

        Cur(
                PDDocument doc,
                PdfPageSpec spec,
                Path baseDir,
                List<String> mmdc,
                PdfGlyphs glyphs,
                boolean math,
                Map<Heading, String> ids)
                throws IOException {
            this.doc = doc;
            this.spec = spec;
            this.size = spec.rectangle();
            this.margin = spec.marginOr(MARGIN);
            // The footer sits in the bottom margin. With the default margin the text area is what it always
            // was; only a margin too small to hold the footer gives up a line of it.
            this.footerY = Math.max(12f, margin - FOOTER_DROP);
            this.bottom = spec.footer() ? Math.max(margin, footerY + PdfChrome.FOOTER_SIZE + 8f) : margin;
            this.baseDir = baseDir;
            this.mmdc = mmdc;
            this.glyphs = glyphs;
            this.math = math;
            this.ids = ids;
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
            cs = new PDPageContentStream(doc, page);
            y = size.getHeight() - margin;
            for (Bar bar : bars) {
                bar.top = boxTop();
            }
        }

        /** Whether nothing has been laid out on the current page yet. */
        boolean atPageTop() {
            return y >= size.getHeight() - margin;
        }

        /** The height of the tallest box a fresh page holds. */
        float pageBox() {
            return size.getHeight() - margin + ASCENT - bottom;
        }

        /**
         * Ends the document: closes the last page, then the passes that need the whole document — link
         * annotations (a {@code #heading} link may point forward), the outline, the document information and
         * the footers (the label needs the page count).
         */
        void finish(PdfDocMeta meta) throws IOException {
            if (cs != null) {
                cs.close();
                cs = null;
            }
            annotateLinks();
            buildOutline();
            PdfChrome.describe(doc, meta);
            if (spec.footer()) {
                PdfChrome.footers(doc, glyphs, body, meta, margin, contentRight(), footerY);
            }
        }

        /** One invisible link annotation per recorded rectangle whose target exists. */
        void annotateLinks() throws IOException {
            for (LinkBox box : links) {
                PDAnnotationLink link = new PDAnnotationLink();
                if (box.ref().uri() != null) {
                    PDActionURI action = new PDActionURI();
                    action.setURI(box.ref().uri());
                    link.setAction(action);
                    link.setContents(box.ref().uri());
                } else {
                    Mark target = anchor(box.ref().anchor());
                    if (target == null) {
                        continue; // a link to a heading this document does not have
                    }
                    PDActionGoTo action = new PDActionGoTo();
                    action.setDestination(destination(target));
                    link.setAction(action);
                }
                link.setRectangle(new PDRectangle(box.x1(), box.y1(), box.x2() - box.x1(), box.y2() - box.y1()));
                // No visible border: the text is already styled as a link. Both spellings, for old readers.
                PDBorderStyleDictionary border = new PDBorderStyleDictionary();
                border.setWidth(0f);
                link.setBorderStyle(border);
                COSArray none = new COSArray();
                none.add(COSInteger.ZERO);
                none.add(COSInteger.ZERO);
                none.add(COSInteger.ZERO);
                link.setBorder(none);
                box.page().getAnnotations().add(link);
            }
        }

        /** The heading {@code fragment} names: as written, then percent-decoded, then lower-cased. */
        Mark anchor(String fragment) {
            Mark m = anchors.get(fragment);
            if (m == null) {
                try {
                    String decoded = URLDecoder.decode(fragment, StandardCharsets.UTF_8);
                    m = anchors.get(decoded);
                    if (m == null) {
                        m = anchors.get(decoded.toLowerCase());
                    }
                } catch (IllegalArgumentException e) {
                    // a stray % in the fragment: no such heading
                }
            }
            return m;
        }

        static PDPageXYZDestination destination(Mark mark) {
            PDPageXYZDestination dest = new PDPageXYZDestination();
            dest.setPage(mark.page());
            dest.setTop(Math.round(mark.top()));
            return dest; // left and zoom unset: the reader keeps its current zoom
        }

        /** The outline (bookmarks): one item per heading, nested by level. */
        void buildOutline() {
            if (marks.isEmpty()) {
                return;
            }
            PDDocumentOutline outline = new PDDocumentOutline();
            List<Mark> path = new ArrayList<>(); // the open ancestors, outermost first
            List<PDOutlineItem> items = new ArrayList<>();
            for (Mark mark : marks) {
                while (!path.isEmpty() && path.get(path.size() - 1).level() >= mark.level()) {
                    path.remove(path.size() - 1);
                    items.remove(items.size() - 1);
                }
                PDOutlineItem item = new PDOutlineItem();
                item.setTitle(mark.title());
                item.setDestination(destination(mark));
                if (items.isEmpty()) {
                    outline.addLast(item);
                } else {
                    items.get(items.size() - 1).addLast(item);
                }
                path.add(mark);
                items.add(item);
            }
            outline.openNode(); // top-level entries visible; their children stay folded
            doc.getDocumentCatalog().setDocumentOutline(outline);
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
            return size.getWidth() - margin;
        }

        void need(float h) throws IOException {
            if (y - h < bottom) {
                newPage();
            }
        }

        /** The top edge of the next block (see {@link #ASCENT}). */
        float boxTop() {
            return y + ASCENT;
        }

        /** Starts a new page unless a box {@code h} tall fits between {@link #boxTop} and the bottom margin. */
        void needBox(float h) throws IOException {
            if (boxTop() - h < bottom) {
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
                String displayMath = math ? soleDisplayMath(p) : null;
                Image alone = blockImage(p);
                if (displayMath != null) {
                    blockMath(displayMath, left, p);
                } else if (alone != null) {
                    image(alone, left);
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
                    codeBlock(f.getLiteral(), f.getInfo(), left);
                }
            } else if (n instanceof IndentedCodeBlock i) {
                codeBlock(i.getLiteral(), null, left);
            } else if (n instanceof ThematicBreak) {
                rule(left);
            } else if (n instanceof TableBlock t) {
                table(t, left);
            } else if (n instanceof org.commonmark.node.HtmlBlock hb) {
                // HTML comments are invisible; other raw HTML is shown as its source text. Both match the
                // on-screen preview. (An HtmlBlock has no child nodes — its text is the literal.)
                if (!MarkdownRenderer.isHtmlComment(hb.getLiteral())) {
                    codeBlock(hb.getLiteral(), null, left);
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
            need(leading); // the page the heading lands on is the page its bookmark and anchor point to
            Mark mark = new Mark(h.getLevel(), plain(h).strip(), page, y + leading);
            marks.add(mark);
            String id = ids.get(h);
            if (id != null) {
                anchors.putIfAbsent(id, mark);
            }
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
                    if (firstChild && child instanceof Paragraph p && blockImage(p) == null) {
                        paragraph(p, textLeft, BODY);
                    } else {
                        // (an item that is just a picture gets the picture, beside its marker)
                        block(child, firstChild ? textLeft : indent);
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
         * continuation lines instead of running off the page edge. A fenced block whose info string names a
         * bundled grammar is syntax-coloured with the code PDF's palette.
         */
        void codeBlock(String literal, String info, float left) throws IOException {
            float fontSize = BODY - 1f;
            float cell = mono.getStringWidth("M") / 1000f * fontSize;
            int maxCols = Math.max(1, (int) Math.floor((contentRight() - left - 10f) / cell));
            String code = stripTrailing(literal);
            List<List<PdfText.Run>> lines = new ArrayList<>();
            for (List<PdfText.Run> line : PdfText.splitIntoLineRuns(code, highlight(code, info), CODE_TAB)) {
                lines.addAll(PdfText.wrap(line, maxCols));
            }
            float boxH = lines.size() * LEADING + 8f;
            need(Math.min(boxH, size.getHeight() - margin - bottom));
            for (List<PdfText.Run> line : lines) {
                need(LEADING);
                // light gray background strip
                cs.setNonStrokingColor(PdfTheme.CODE_BG);
                cs.addRect(left, y - 3f, contentRight() - left, LEADING);
                cs.fill();
                int col = 0;
                for (PdfText.Run r : line) {
                    cs.setNonStrokingColor(r.color().equals(PdfTheme.DEFAULT_FG) ? CODE_FG : r.color());
                    col += glyphs.showOnGrid(cs, monoFace(r), fontSize, cell, r.text(), left + 5f + col * cell, y);
                }
                y -= LEADING;
            }
            y -= PARA_GAP;
        }

        /** The token styles of a fenced block, or null when its language has no bundled grammar. */
        StyleSpans<Collection<String>> highlight(String code, String info) {
            if (info == null || info.isBlank() || code.isEmpty() || code.length() > MAX_HIGHLIGHT_CHARS) {
                return null;
            }
            try {
                IGrammar grammar = MarkdownRenderer.grammarForInfo(info);
                return grammar == null ? null : TextMateHighlighter.compute(code, grammar);
            } catch (RuntimeException | LinkageError e) {
                return null; // colour is a nicety: the block still prints, plain
            }
        }

        /** The code font face for a run: bold and italic are embedded the first time one is drawn. */
        PDType0Font monoFace(PdfText.Run r) throws IOException {
            if (r.bold()) {
                if (monoBold == null) {
                    monoBold = embed(doc, "jetbrains-mono/JetBrainsMono-Bold");
                }
                return monoBold;
            }
            if (r.italic()) {
                if (monoItalic == null) {
                    monoItalic = embed(doc, "jetbrains-mono/JetBrainsMono-Italic");
                }
                return monoItalic;
            }
            return mono;
        }

        void mermaid(String source, float left) throws IOException {
            Mermaid.Render r = Mermaid.renderPng(mmdc, source, false);
            if (r.ok()) {
                try {
                    PDImageXObject img = PDImageXObject.createFromByteArray(doc, r.image(), "mermaid");
                    // mmdc rasterises at RENDER_SCALE device pixels per CSS pixel: the diagram's own size is
                    // its pixel size divided by that, or a five-node flowchart fills a page.
                    drawImage(
                            img,
                            left,
                            (float) img.getWidth() / Mermaid.RENDER_SCALE,
                            (float) img.getHeight() / Mermaid.RENDER_SCALE,
                            null);
                    return;
                } catch (IOException | RuntimeException ignored) {
                    // fall through to code rendering
                }
            }
            // The source is printed so nothing is lost, but the reader asked for a diagram: count it, so the
            // export can say that some were not rendered.
            failedDiagrams++;
            codeBlock(source, null, left);
        }

        /** The em size of a formula set beside text in {@code font} at {@code size} (x-heights matched). */
        float mathEm(PDFont font, float size) {
            float xHeight = font.getFontDescriptor() == null
                    ? 0f
                    : font.getFontDescriptor().getXHeight() / 1000f;
            return PdfMath.emFor(size, xHeight);
        }

        void blockMath(String latex, float left, Paragraph fallback) throws IOException {
            PdfMath.Formula f = PdfMath.render(latex, true, mathEm(body, BODY), PdfTheme.DEFAULT_FG);
            if (f != null) {
                try {
                    drawImage(LosslessFactory.createFromImage(doc, f.image()), left, f.width(), f.height(), null);
                    return;
                } catch (IOException | RuntimeException ignored) {
                    // fall through to text rendering
                }
            }
            paragraph(fallback, left, BODY);
            y -= PARA_GAP;
        }

        /** A picture on its own: at its natural size, shrunk to the width. Its link, if any, covers it. */
        void image(Image node, float left) throws IOException {
            Pic pic = picture(node.getDestination());
            if (pic != null) {
                LinkRef ref = node.getParent() instanceof Link link ? linkRef(link.getDestination()) : null;
                drawImage(pic.image(), left, pic.width(), pic.height(), ref);
                return;
            }
            String alt = plain(node);
            flow(
                    words("[" + (alt.isBlank() ? "image" : alt) + "]", bodyItalic, BODY, PdfTheme.LINE_NUMBER),
                    left,
                    contentRight(),
                    LEADING);
            y -= PARA_GAP;
        }

        /**
         * The picture at {@code destination}, fetched and embedded once per document; null when it cannot be
         * fetched or decoded (the caller prints the alt text).
         */
        Pic picture(String destination) {
            if (pictures.containsKey(destination)) {
                return pictures.get(destination);
            }
            Pic pic = null;
            byte[] bytes = fetch(destination);
            if (bytes != null) {
                try {
                    float scale = 1f;
                    // PDFBox can't decode SVG (shields.io/GitHub badges are image/svg+xml); rasterize it
                    // to PNG first via the same JSVG path the on-screen preview uses. That bitmap is
                    // oversampled, so the picture's size is the SVG's own, not the bitmap's.
                    if (PreviewImageLoader.looksLikeSvg(bytes)) {
                        float logicalWidth = svgWidth(bytes);
                        byte[] png = PreviewImageLoader.svgToPng(bytes);
                        if (png != null) {
                            bytes = png;
                            PDImageXObject img = PDImageXObject.createFromByteArray(doc, bytes, "img");
                            if (logicalWidth > 0f && img.getWidth() > logicalWidth) {
                                scale = logicalWidth / img.getWidth();
                            }
                            pic = new Pic(img, img.getWidth() * scale, img.getHeight() * scale);
                        }
                    }
                    if (pic == null) {
                        PDImageXObject img = PDImageXObject.createFromByteArray(doc, bytes, "img");
                        pic = new Pic(img, img.getWidth(), img.getHeight());
                    }
                    if (pic.width() <= 0f || pic.height() <= 0f) {
                        pic = null;
                    }
                } catch (IOException | RuntimeException ignored) {
                    // unsupported / undecodable format — the caller falls back to alt text.
                    // (createFromByteArray throws IllegalArgumentException for unknown image types, which
                    // previously escaped and aborted the whole export.)
                    pic = null;
                }
            }
            pictures.put(destination, pic);
            return pic;
        }

        /** Draws {@code img}, {@code natW}×{@code natH} points at its natural size, shrunk to fit the page. */
        void drawImage(PDImageXObject img, float left, float natW, float natH, LinkRef link) throws IOException {
            float maxW = contentRight() - left;
            float w = Math.min(natW, maxW);
            float h = w / natW * natH;
            float maxH = size.getHeight() - margin - bottom;
            if (h > maxH) {
                h = maxH;
                w = h / natH * natW;
            }
            needBox(h);
            float bottomEdge = boxTop() - h;
            cs.drawImage(img, left, bottomEdge, w, h);
            if (link != null) {
                links.add(new LinkBox(page, left, bottomEdge, left + w, bottomEdge + h, link));
            }
            afterBox(bottomEdge);
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

        /** A table row ready to draw: each cell's lines and alignment, and the row's height in lines. */
        private record Row(List<List<Line>> cells, List<TableCell.Alignment> aligns, boolean head, int lines) {}

        /**
         * A table. Cells go through the same inline path as a paragraph, so bold, code, links, strikethrough
         * and pictures survive, and are aligned as the delimiter row says ({@code :---:}, {@code ---:}).
         * Columns are sized from their content ({@link #columnWidths}). The header row is drawn again at the
         * top of every page the table continues on — and above each band of a row too tall for one page.
         */
        void table(TableBlock t, float left) throws IOException {
            List<TableRow> rows = new ArrayList<>();
            int cols = 0;
            for (Node section = t.getFirstChild(); section != null; section = section.getNext()) {
                for (Node r = section.getFirstChild(); r != null; r = r.getNext()) {
                    if (r instanceof TableRow row) {
                        rows.add(row);
                        int n = 0;
                        for (Node cell = r.getFirstChild(); cell != null; cell = cell.getNext()) {
                            if (cell instanceof TableCell) {
                                n++;
                            }
                        }
                        cols = Math.max(cols, n);
                    }
                }
            }
            if (rows.isEmpty() || cols == 0) {
                return;
            }
            // Pass 1: what each column would like, and the least it can take without breaking a word.
            float[] natural = new float[cols];
            float[] least = new float[cols];
            java.util.Arrays.fill(natural, BODY);
            java.util.Arrays.fill(least, BODY);
            for (TableRow row : rows) {
                int ci = 0;
                for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
                    if (cell instanceof TableCell tc) {
                        float[] m = measure(cellWords(tc));
                        natural[ci] = Math.max(natural[ci], m[0] + 0.5f);
                        least[ci] = Math.max(least[ci], m[1] + 0.5f);
                        ci++;
                    }
                }
            }
            float tableW = contentRight() - left;
            float words = 0f;
            for (float v : least) {
                words += v;
            }
            // A table with many columns gives up half its padding before it breaks a word.
            cellPad = words + cols * 2 * CELL_PAD > tableW ? TIGHT_CELL_PAD : CELL_PAD;
            for (int ci = 0; ci < cols; ci++) {
                natural[ci] += 2 * cellPad;
                least[ci] += 2 * cellPad;
            }
            float[] colW = columnWidths(natural, least, tableW);
            float[] colX = new float[cols + 1];
            colX[0] = left;
            for (int ci = 0; ci < cols; ci++) {
                colX[ci + 1] = colX[ci] + colW[ci];
            }

            // The header rows are laid out once: they are drawn again on every page the table reaches.
            List<Row> header = new ArrayList<>();
            float headerH = 0f;
            int body = 0;
            for (TableRow row : rows) {
                if (row.getParent() instanceof TableHead) {
                    Row r = layoutRow(row, colW);
                    header.add(r);
                    headerH += r.lines() * LEADING + 4f;
                } else {
                    body++;
                }
            }
            boolean repeat = !header.isEmpty() && body > 0 && headerH <= pageBox() * MAX_REPEATED_HEADER;
            if (repeat) {
                needBox(headerH + LEADING + 4f); // a header is never left alone at the foot of a page
            }
            // The baseline right under a freshly repeated header: nothing of the table's body is on that page.
            float fresh = Float.NaN;
            int hi = 0;
            for (TableRow source : rows) {
                boolean head = source.getParent() instanceof TableHead;
                Row row = head ? header.get(hi++) : layoutRow(source, colW);
                float carried = repeat && !head ? headerH : 0f;
                // Rows stack edge to edge: throughout this loop boxTop() is the top edge of the next band.
                int from = 0;
                while (from < row.lines()) {
                    int remaining = row.lines() - from;
                    int fit = (int) Math.floor((boxTop() - bottom - 4f) / LEADING);
                    if (fit < remaining && !atPageTop() && y != fresh) {
                        // Not all of it fits here. A row a page can hold moves there whole; a taller one
                        // starts here only if a few lines fit, then continues in page-sized bands.
                        if (remaining * LEADING + 4f <= pageBox() - carried || fit < MIN_SPLIT_LINES) {
                            fresh = tablePage(header, repeat && !head, colX);
                            continue;
                        }
                    }
                    int n = Math.max(1, Math.min(remaining, fit));
                    y -= rowBand(row, from, n, colX);
                    from += n;
                    if (from < row.lines()) {
                        fresh = tablePage(header, repeat && !head, colX);
                    }
                }
            }
            y -= PARA_GAP;
        }

        /** Continues a table on a new page, under its header when {@code repeat}; returns the cursor after it. */
        float tablePage(List<Row> header, boolean repeat, float[] colX) throws IOException {
            newPage();
            if (repeat) {
                for (Row h : header) {
                    y -= rowBand(h, 0, h.lines(), colX);
                }
            }
            return y;
        }

        /** A cell's content as flow words; a header cell is bold. */
        List<Word> cellWords(TableCell cell) throws IOException {
            List<Word> words = new ArrayList<>();
            pendingSpace = false;
            if (cell.isHeader()) {
                inline(cell, words, bodyBold, bodyBold, bodyBoldItalic, BODY, PdfTheme.DEFAULT_FG, false);
            } else {
                inline(cell, words, body, bodyBold, bodyItalic, BODY, PdfTheme.DEFAULT_FG, false);
            }
            return words;
        }

        /**
         * {@code words}' natural width (set on one line, hard breaks respected) and the width of their widest
         * unbreakable cluster.
         */
        float[] measure(List<Word> words) throws IOException {
            float natural = 0f;
            float least = 0f;
            float line = 0f;
            float cluster = 0f;
            for (Word w : words) {
                if (w.brk()) {
                    line = 0f;
                    cluster = 0f;
                    continue;
                }
                float ww = wordWidth(w);
                if (w.space() && line > 0f) {
                    line += glyphs.width(w.font(), " ", w.size());
                    cluster = 0f;
                }
                line += ww;
                cluster += ww;
                natural = Math.max(natural, line);
                least = Math.max(least, cluster);
            }
            return new float[] {natural, least};
        }

        Row layoutRow(TableRow source, float[] colW) throws IOException {
            List<List<Line>> cells = new ArrayList<>();
            List<TableCell.Alignment> aligns = new ArrayList<>();
            int lines = 1;
            boolean head = source.getParent() instanceof TableHead;
            for (Node cell = source.getFirstChild();
                    cell != null && cells.size() < colW.length;
                    cell = cell.getNext()) {
                if (cell instanceof TableCell tc) {
                    List<Line> wrapped = layoutLines(cellWords(tc), colW[cells.size()] - 2 * cellPad, true);
                    lines = Math.max(lines, wrapped.size());
                    cells.add(wrapped);
                    aligns.add(tc.getAlignment());
                }
            }
            return new Row(cells, aligns, head, lines);
        }

        /**
         * Draws lines {@code [from, from + n)} of a table row's cells as one bordered band whose top edge is
         * {@link #boxTop}, and returns the band's height. A row is normally a single band; one taller than a
         * page is drawn as several, each closed by its own border.
         */
        float rowBand(Row row, int from, int n, float[] colX) throws IOException {
            int cols = colX.length - 1;
            float left = colX[0];
            float tableW = colX[cols] - left;
            float rowTop = boxTop();
            float rowH = n * LEADING + 4f;
            if (row.head()) {
                cs.setNonStrokingColor(PdfTheme.CODE_BG);
                cs.addRect(left, rowTop - rowH, tableW, rowH);
                cs.fill();
            }
            for (int ci = 0; ci < row.cells().size(); ci++) {
                List<Line> lines = row.cells().get(ci);
                float inner = colX[ci + 1] - colX[ci] - 2 * cellPad;
                float ty = rowTop - LEADING;
                for (int li = from; li < Math.min(from + n, lines.size()); li++) {
                    Line line = lines.get(li);
                    float slack = Math.max(0f, inner - line.width);
                    float shift = row.aligns().get(ci) == TableCell.Alignment.RIGHT
                            ? slack
                            : row.aligns().get(ci) == TableCell.Alignment.CENTER ? slack / 2f : 0f;
                    drawLine(line, colX[ci] + cellPad + shift, ty);
                    ty -= LEADING;
                }
            }
            // borders
            cs.setStrokingColor(PdfTheme.RULE);
            cs.setLineWidth(0.5f);
            cs.addRect(left, rowTop - rowH, tableW, rowH);
            cs.stroke();
            for (int ci = 1; ci < cols; ci++) {
                cs.moveTo(colX[ci], rowTop);
                cs.lineTo(colX[ci], rowTop - rowH);
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
                    int first = out.size();
                    inline(n, out, reg, bold, italic, size, LINK_FG, true);
                    LinkRef ref = linkRef(link.getDestination());
                    if (ref != null) {
                        for (int k = first; k < out.size(); k++) {
                            out.set(k, out.get(k).linked(ref));
                        }
                    }
                } else if (n instanceof SoftLineBreak) {
                    pendingSpace = true; // a soft break is whitespace between the words around it
                } else if (n instanceof HardLineBreak) {
                    out.add(Word.of("", reg, size, color, false, true, false));
                    pendingSpace = false;
                } else if (n instanceof Image image) {
                    // A picture among text (a badge row, a linked badge) sits in the line at line height.
                    Pic pic = picture(image.getDestination());
                    if (pic != null) {
                        float h = Math.min(pic.height(), size * INLINE_IMAGE_HEIGHT);
                        float w = pic.width() / pic.height() * h;
                        out.add(Word.picture(pic.image(), w, h, size * INLINE_IMAGE_DROP, reg, size, pendingSpace));
                        pendingSpace = false;
                    } else {
                        addWords("[" + plain(n) + "]", out, italic, size, PdfTheme.LINE_NUMBER, false);
                    }
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
            if (!math || literal.indexOf('$') < 0) {
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

        /**
         * A formula as a picture word at its real size — set so its x-height matches the text around it, and
         * placed on the baseline by its own depth — or a literal {@code $…$} text fallback when the LaTeX
         * does not parse.
         */
        Word mathWord(String latex, boolean display, PDFont reg, float size, Color color, boolean underline)
                throws IOException {
            Color ink = color == null ? PdfTheme.DEFAULT_FG : color;
            float em = mathEm(reg, size);
            String key = (display ? "D" : "I") + em + ":" + ink.getRGB() + ":" + latex;
            Word cached = formulas.get(key);
            if (cached == null && !formulas.containsKey(key)) {
                PdfMath.Formula f = PdfMath.render(latex, display, em, ink);
                if (f != null) {
                    PDImageXObject xo = LosslessFactory.createFromImage(doc, f.image());
                    cached = Word.picture(xo, f.width(), f.height(), f.depth(), reg, size, false);
                }
                formulas.put(key, cached);
            }
            if (cached != null) {
                return new Word(
                        "",
                        reg,
                        size,
                        null,
                        false,
                        false,
                        false,
                        pendingSpace,
                        null,
                        cached.img(),
                        cached.imgW(),
                        cached.imgH(),
                        cached.imgDrop());
            }
            String d = display ? "$$" : "$";
            return Word.of(d + latex + d, reg, size, color, underline, false, pendingSpace);
        }

        /**
         * Greedy word-wrap flow of styled words at the current y; advances y per visual line. A line that
         * holds a picture taller than the text (a fraction, an integral) is given the extra room it needs.
         */
        void flow(List<Word> words, float left, float right, float leading) throws IOException {
            for (Line line : layoutLines(words, right - left, false)) {
                float rise = Math.max(0f, line.above - (leading - DESCENT));
                float sink = Math.max(0f, line.below - DESCENT);
                need(leading + rise + sink);
                y -= rise;
                drawLine(line, left, y);
                y -= leading + sink;
            }
        }

        /**
         * Breaks {@code words} into lines at most {@code maxW} wide. A space is placed — and a line may
         * break — only before a word whose {@link Word#space()} is set; the words between two such points
         * form one unbreakable cluster ({@code un}+{@code real}+{@code ly}) that moves to the next line as a
         * whole. A single word wider than the line (a long URL) is hard-broken. There is always at least one
         * line. With {@code uniform} every line must be exactly one line box tall (a table cell), so a
         * picture too tall for it is scaled down instead of pushing the lines apart.
         */
        List<Line> layoutLines(List<Word> words, float maxW, boolean uniform) throws IOException {
            List<Line> lines = new ArrayList<>();
            Line cur = new Line();
            boolean started = false;
            Word prev = null; // the word before the next space on this line, whose decorations may continue
            int i = 0;
            while (i < words.size()) {
                Word w = words.get(i);
                if (w.brk()) {
                    lines.add(cur);
                    cur = new Line();
                    started = false;
                    prev = null;
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
                if (started && cur.width + sp + cluster > maxW) {
                    lines.add(cur);
                    cur = new Line();
                    sp = 0f;
                    prev = null;
                }
                if (sp > 0f) {
                    // A real space glyph, not just a gap: text extraction, search and copy/paste then see the
                    // word boundary exactly where the source had one. A multi-word link keeps its underline
                    // (and its clickable area) across it; a struck phrase its line.
                    cur.pieces.add(new Piece(
                            w,
                            " ",
                            cur.width,
                            sp,
                            true,
                            prev != null && prev.underline() && w.underline(),
                            prev != null && prev.strike() && w.strike(),
                            prev != null && prev.link() == w.link() ? w.link() : null,
                            1f));
                    cur.width += sp;
                }
                for (int k = i; k < end; k++) {
                    Word part = words.get(k);
                    if (part.img() != null) {
                        placePicture(part, cur, maxW, uniform);
                    } else {
                        cur = placeText(part, cur, lines, maxW);
                    }
                    prev = part;
                }
                started = true;
                i = end;
            }
            lines.add(cur);
            return lines;
        }

        void placePicture(Word w, Line line, float maxW, boolean uniform) {
            float scale = 1f;
            if (w.imgW() > maxW) {
                scale = maxW / w.imgW(); // never wider than the line (or the cell)
            }
            if (uniform) {
                float above = w.imgH() - w.imgDrop();
                if (above * scale > ASCENT) {
                    scale = ASCENT / above;
                }
                if (w.imgDrop() * scale > DESCENT) {
                    scale = DESCENT / w.imgDrop();
                }
            }
            line.pieces.add(new Piece(w, "", line.width, w.imgW() * scale, false, false, w.strike(), w.link(), scale));
            line.width += w.imgW() * scale;
            line.above = Math.max(line.above, (w.imgH() - w.imgDrop()) * scale);
            line.below = Math.max(line.below, w.imgDrop() * scale);
        }

        /**
         * Places one text word on {@code cur} and returns the line that is current afterwards. A word that
         * does not fit the rest of the line moves to a fresh line; one wider than a whole line is split
         * character by character across as many lines as it needs, so nothing is ever placed past
         * {@code maxW}.
         */
        Line placeText(Word w, Line cur, List<Line> lines, float maxW) throws IOException {
            String text = w.text();
            float ww = glyphs.width(w.font(), text, w.size());
            if (cur.width + ww <= maxW + 0.01f) {
                addText(cur, w, text, ww);
                return cur;
            }
            if (ww <= maxW && cur.width > 0f) {
                lines.add(cur);
                cur = new Line();
                addText(cur, w, text, ww);
                return cur;
            }
            int from = 0;
            while (from < text.length()) {
                int to = fitEnd(w.font(), w.size(), text, from, maxW - cur.width);
                if (to == from) {
                    if (cur.width <= 0f) {
                        to = from + Character.charCount(text.codePointAt(from)); // never loop on a 1-glyph line
                    } else {
                        lines.add(cur);
                        cur = new Line();
                        continue;
                    }
                }
                String piece = text.substring(from, to);
                addText(cur, w, piece, glyphs.width(w.font(), piece, w.size()));
                from = to;
                if (from < text.length()) {
                    lines.add(cur);
                    cur = new Line();
                }
            }
            return cur;
        }

        void addText(Line line, Word w, String text, float width) {
            line.pieces.add(new Piece(w, text, line.width, width, false, w.underline(), w.strike(), w.link(), 1f));
            line.width += width;
        }

        float wordWidth(Word w) throws IOException {
            return w.img() != null ? w.imgW() : glyphs.width(w.font(), w.text(), w.size());
        }

        /**
         * Draws a laid-out line with its left edge at {@code x0} on baseline {@code base}, and records one
         * link rectangle per run of pieces that belong to the same link — so a link that wraps gets one
         * rectangle per line fragment.
         */
        void drawLine(Line line, float x0, float base) throws IOException {
            LinkRef open = null;
            float linkLeft = 0f;
            float linkRight = 0f;
            float linkBottom = 0f;
            float linkTop = 0f;
            for (Piece p : line.pieces) {
                Word w = p.word();
                float x = x0 + p.x();
                float low;
                float high;
                if (w.img() != null && !p.space()) { // (the space before a picture is still a space glyph)
                    float h = w.imgH() * p.scale();
                    low = base - w.imgDrop() * p.scale();
                    high = low + h;
                    cs.drawImage(w.img(), x, low, p.width(), h);
                } else {
                    low = base - w.size() * 0.25f;
                    high = base + w.size() * 0.8f;
                    if (w.color() != null) {
                        cs.setNonStrokingColor(w.color());
                    }
                    glyphs.show(cs, w.font(), w.size(), p.text(), x, base);
                }
                if (p.underline()) {
                    underline(w.color() == null ? PdfTheme.DEFAULT_FG : w.color(), x, x + p.width(), base);
                }
                if (p.strike()) {
                    strike(w, x, x + p.width(), base);
                }
                if (p.link() != open) { // the same link is the same LinkRef instance
                    if (open != null) {
                        links.add(new LinkBox(page, linkLeft, linkBottom, linkRight, linkTop, open));
                    }
                    open = p.link();
                    linkLeft = x;
                    linkBottom = low;
                    linkTop = high;
                }
                if (open != null) {
                    linkRight = x + p.width();
                    linkBottom = Math.min(linkBottom, low);
                    linkTop = Math.max(linkTop, high);
                }
            }
            if (open != null) {
                links.add(new LinkBox(page, linkLeft, linkBottom, linkRight, linkTop, open));
            }
        }

        /** The strikethrough line for {@code w}'s text between {@code from} and {@code to}, at mid x-height. */
        void strike(Word w, float from, float to, float base) throws IOException {
            float sy = base + w.size() * 0.28f;
            cs.setStrokingColor(w.color() == null ? PdfTheme.DEFAULT_FG : w.color()); // a math word has no color
            cs.setLineWidth(Math.max(0.6f, w.size() * 0.06f));
            cs.moveTo(from, sy);
            cs.lineTo(to, sy);
            cs.stroke();
        }

        void underline(Color color, float from, float to, float base) throws IOException {
            cs.setStrokingColor(color);
            cs.setLineWidth(0.5f);
            cs.moveTo(from, base - 1.5f);
            cs.lineTo(to, base - 1.5f);
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

        byte[] fetch(String url) {
            // The preview's guarded fetcher: internal-address block re-checked per redirect hop, UNC refusal,
            // regular files only, and a byte cap — exporting a document must not reach what previewing refuses.
            return com.editora.editor.PreviewImageLoader.fetchForExport(url, baseDir);
        }
    }

    // --- small helpers -----------------------------------------------------------------------------

    /**
     * The picture a paragraph consists of — alone, or as the only content of a link
     * ({@code [![shot](shot.png)](shot.png)}) — or null when the paragraph has anything else in it. Such a
     * picture is a block at its natural size; one among text is set in the line ({@code Cur.inline}).
     */
    private static Image blockImage(Paragraph p) {
        Node c = p.getFirstChild();
        if (c == null || c.getNext() != null) {
            return null;
        }
        if (c instanceof Link && c.getFirstChild() instanceof Image inner && inner.getNext() == null) {
            return inner;
        }
        return c instanceof Image image ? image : null;
    }

    /** The declared width of an SVG in CSS pixels (its bitmap is oversampled), or 0 when it cannot be read. */
    private static float svgWidth(byte[] svg) {
        try {
            SVGDocument doc =
                    new SVGLoader().load(new java.io.ByteArrayInputStream(svg), null, LoaderContext.createDefault());
            return doc == null ? 0f : doc.size().width;
        } catch (RuntimeException | LinkageError e) {
            return 0f;
        }
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
