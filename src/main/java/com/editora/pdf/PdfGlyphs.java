package com.editora.pdf;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.fontbox.ttf.OS2WindowsMetricsTable;
import org.apache.fontbox.ttf.OpenTypeFont;
import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType2;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.util.Matrix;

/**
 * Glyph coverage for one PDF document: splits text into runs by the font that can actually draw each
 * character, and counts what nothing could draw.
 *
 * <p>The bundled Inter / JetBrains Mono cover Latin, Greek and Cyrillic only, so CJK, Arabic, Hebrew, Thai or
 * Devanagari text used to come out as a row of {@code ?} under a plain "Exported". A character missing from
 * the primary font is now looked up in a chain of <b>system</b> fonts ({@link SystemFontFiles}) — loaded
 * lazily, one at a time, only when a document needs them, and embedded as a subset like the bundled ones.
 * When no font has the glyph a readable ASCII stand-in is used where one exists ({@code →} as {@code ->}),
 * otherwise {@code ?} — and that last case is counted ({@link #missing()}) so the export can say how many
 * characters it could not render instead of pretending it was complete.
 *
 * <p>Fallback glyphs are placed as-is: there is no shaping or bidirectional reordering, so Arabic and Indic
 * text is legible character by character but not typeset. Not thread-safe; one instance per document.
 */
final class PdfGlyphs implements Closeable {

    /** A stretch of text drawn with one font. */
    record Run(PDFont font, String text) {}

    /**
     * Readable ASCII substitutes for symbols no available font has — so {@code →} renders as {@code ->}
     * rather than {@code ?}. Invisible format characters map to nothing.
     */
    private static final Map<Integer, String> ASCII = Map.ofEntries(
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
            Map.entry(0x0009, " "),
            Map.entry(0x200B, ""),
            Map.entry(0x200C, ""),
            Map.entry(0x200D, ""),
            Map.entry(0x2060, ""),
            Map.entry(0xFEFF, ""),
            Map.entry(0xFE0E, ""),
            Map.entry(0xFE0F, ""),
            Map.entry(0x00AD, ""),
            Map.entry(0x2261, "="),
            Map.entry(0x2260, "!="),
            Map.entry(0x2264, "<="),
            Map.entry(0x2265, ">="),
            Map.entry(0x00D7, "x"),
            Map.entry(0x2022, "-"));

    private final PDDocument doc;
    private final List<Path> candidates;
    private int nextCandidate;
    private final List<PDFont> loaded = new ArrayList<>();
    private final List<Closeable> open = new ArrayList<>();
    /** Code point → the fallback font that covers it; a present key with a null value means "none does". */
    private final Map<Integer, PDFont> fallbackFor = new HashMap<>();

    private int missing;

    /** {@code fallbackFonts}: font files to try, in priority order, for characters the primary font lacks. */
    PdfGlyphs(PDDocument doc, List<Path> fallbackFonts) {
        this.doc = doc;
        this.candidates = fallbackFonts == null ? List.of() : fallbackFonts;
    }

    /** How many characters were drawn as {@code ?} because no font had a glyph for them. */
    int missing() {
        return missing;
    }

    /**
     * Splits {@code text} into font runs. {@code drawing} is true when the result is about to be painted —
     * only then are unrenderable characters counted, so measuring a word and then drawing it counts once.
     */
    List<Run> runs(PDFont primary, String text, boolean drawing) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        if (encodes(primary, text)) {
            return List.of(new Run(primary, text)); // whole string encodable — the overwhelmingly common case
        }
        List<Run> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        PDFont current = primary;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            String s = Character.toString(cp);
            PDFont font = primary;
            String piece = s;
            if (!encodes(primary, s)) {
                PDFont fb = fallback(cp, s);
                if (fb != null) {
                    font = fb;
                } else {
                    String ascii = ASCII.get(cp);
                    if (ascii != null && encodes(primary, ascii)) {
                        piece = ascii;
                    } else if (Character.isISOControl(cp)) {
                        piece = ""; // a stray control character draws nothing
                    } else {
                        piece = "?";
                        if (drawing) {
                            missing++;
                        }
                    }
                }
            }
            if (font != current && buf.length() > 0) {
                out.add(new Run(current, buf.toString()));
                buf.setLength(0);
            }
            current = font;
            buf.append(piece);
        }
        if (buf.length() > 0) {
            out.add(new Run(current, buf.toString()));
        }
        return out;
    }

    /** The plain text {@link #runs} would draw for {@code text} (substitutes applied), without counting. */
    String rendered(PDFont primary, String text) {
        StringBuilder sb = new StringBuilder();
        for (Run r : runs(primary, text, false)) {
            sb.append(r.text());
        }
        return sb.toString();
    }

    /** The width of {@code text} at {@code size} points, measured with the fonts that will draw it. */
    float width(PDFont primary, String text, float size) throws IOException {
        float w = 0;
        for (Run r : runs(primary, text, false)) {
            w += r.font().getStringWidth(r.text()) / 1000f * size;
        }
        return w;
    }

    /** Draws {@code text} at {@code (x, y)} in the current non-stroking color; returns its width. */
    float show(PDPageContentStream cs, PDFont primary, float size, String text, float x, float y) throws IOException {
        float cx = x;
        for (Run r : runs(primary, text, true)) {
            if (r.text().isEmpty()) {
                continue;
            }
            cs.beginText();
            cs.setFont(r.font(), size);
            cs.newLineAtOffset(cx, y);
            cs.showText(r.text());
            cs.endText();
            cx += r.font().getStringWidth(r.text()) / 1000f * size;
        }
        return cx - x;
    }

    /**
     * Draws {@code text} on a monospace grid whose cell is {@code cell} points wide, starting at
     * {@code (x, y)}; returns the number of columns it occupies ({@link PdfText#columns}). Text in the primary
     * (monospace) font flows normally; a glyph from a fallback font has its own advance, so each one is placed
     * on its column — a wide (CJK) character takes two — to keep the following code aligned.
     */
    int showOnGrid(PDPageContentStream cs, PDFont primary, float size, float cell, String text, float x, float y)
            throws IOException {
        int col = 0;
        for (Run r : runs(primary, text, true)) {
            if (r.text().isEmpty()) {
                continue;
            }
            cs.beginText();
            cs.setFont(r.font(), size);
            if (r.font() == primary) {
                cs.newLineAtOffset(x + col * cell, y);
                cs.showText(r.text());
                col += PdfText.columns(r.text());
            } else {
                String t = r.text();
                for (int i = 0; i < t.length(); ) {
                    int cp = t.codePointAt(i);
                    i += Character.charCount(cp);
                    cs.setTextMatrix(Matrix.getTranslateInstance(x + col * cell, y));
                    cs.showText(Character.toString(cp));
                    col += PdfText.columns(cp);
                }
            }
            cs.endText();
        }
        return col;
    }

    /** The first fallback font with a glyph for {@code cp}, loading further candidates only as needed. */
    private PDFont fallback(int cp, String s) {
        if (fallbackFor.containsKey(cp)) {
            return fallbackFor.get(cp);
        }
        PDFont hit = null;
        for (PDFont f : loaded) {
            if (encodes(f, s)) {
                hit = f;
                break;
            }
        }
        while (hit == null && nextCandidate < candidates.size()) {
            PDFont f = load(candidates.get(nextCandidate++));
            if (f != null) {
                loaded.add(f);
                if (encodes(f, s)) {
                    hit = f;
                }
            }
        }
        fallbackFor.put(cp, hit);
        return hit;
    }

    /**
     * Loads a system font file for subset embedding, or returns null when PDFBox cannot embed it — a font
     * with PostScript (CFF) outlines, one whose licence bits forbid embedding, or a file that fails to parse.
     */
    private PDFont load(Path file) {
        try {
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.endsWith(".ttc") || name.endsWith(".otc")) {
                TrueTypeCollection collection = new TrueTypeCollection(file.toFile());
                open.add(collection); // its fonts read from the collection until the document is saved
                TrueTypeFont[] first = new TrueTypeFont[1];
                collection.processAllFonts(ttf -> {
                    if (first[0] == null
                            && !(ttf instanceof OpenTypeFont otf && otf.isPostScript())
                            && permitsSubsetting(ttf)) {
                        first[0] = ttf;
                    }
                });
                return first[0] == null ? null : PDType0Font.load(doc, first[0], true);
            }
            PDType0Font font = PDType0Font.load(doc, file.toFile());
            boolean usable = !(font.getDescendantFont() instanceof PDCIDFontType2 cid)
                    || permitsSubsetting(cid.getTrueTypeFont());
            return usable ? font : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Whether the font's licence bits allow subsetting. PDFBox checks this only when the document is saved —
     * where a refusal would fail the whole export — so such a font is skipped up front instead.
     */
    private static boolean permitsSubsetting(TrueTypeFont ttf) throws IOException {
        OS2WindowsMetricsTable os2 = ttf.getOS2Windows();
        return os2 == null || (os2.getFsType() & OS2WindowsMetricsTable.FSTYPE_NO_SUBSETTING) == 0;
    }

    /**
     * Whether {@code font} can render {@code s}. Probes with {@link PDFont#encode} — the exact operation
     * {@code showText} performs — rather than {@code getStringWidth}, because an embedded subset TrueType
     * font can return a width for a character it has no glyph for and then throw only at draw time (e.g.
     * U+2011 non-breaking hyphen in JetBrains Mono).
     */
    static boolean encodes(PDFont font, String s) {
        try {
            font.encode(s);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Releases the font collections opened for fallback; call after the document has been saved. */
    @Override
    public void close() {
        for (Closeable c : open) {
            try {
                c.close();
            } catch (IOException ignore) {
                // nothing to recover
            }
        }
        open.clear();
    }
}
