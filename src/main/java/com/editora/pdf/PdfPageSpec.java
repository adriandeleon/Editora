package com.editora.pdf;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.apache.pdfbox.pdmodel.common.PDRectangle;

/**
 * The page a text PDF is laid out on: paper size, orientation, margin and code font size. Immutable; the
 * writers take one instead of reading constants, so a caller that wants a different page only builds a
 * different spec. {@link #of(String)} is the page every export used before this record existed.
 *
 * @param pageSize the paper size key, {@code "letter"} or {@code "a4"} (already normalised — see {@link #of})
 * @param landscape whether the page is turned on its side
 * @param margin the page margin in points, or {@link #WRITER_MARGIN} for each writer's own default
 * @param codeFontSize the monospace size of the code PDF, in points
 * @param footer whether every page gets the footer (document name and "Page n of N"); without it a PDF has
 *     no page furniture at all
 */
public record PdfPageSpec(String pageSize, boolean landscape, float margin, float codeFontSize, boolean footer) {

    /** "Use the writer's own margin" (40 pt for code, 50 pt for Markdown and tables). */
    public static final float WRITER_MARGIN = -1f;

    public static final float DEFAULT_CODE_FONT_SIZE = 9f;

    private static final Logger LOG = Logger.getLogger(PdfPageSpec.class.getName());
    /** Unknown size keys already reported, so a bad setting is logged once and not on every export. */
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    public PdfPageSpec {
        pageSize = normalise(pageSize);
        if (!(margin >= 0f)) {
            margin = WRITER_MARGIN;
        }
        if (!(codeFontSize > 0f)) {
            codeFontSize = DEFAULT_CODE_FONT_SIZE;
        }
    }

    /** Portrait {@code pageSize} with each writer's default margin, the 9 pt code font and the footer. */
    public static PdfPageSpec of(String pageSize) {
        return new PdfPageSpec(pageSize, false, WRITER_MARGIN, DEFAULT_CODE_FONT_SIZE, true);
    }

    /** This spec with the page footer switched on or off. */
    public PdfPageSpec withFooter(boolean on) {
        return new PdfPageSpec(pageSize, landscape, margin, codeFontSize, on);
    }

    /** The page rectangle, turned when {@link #landscape}. */
    public PDRectangle rectangle() {
        PDRectangle r = "a4".equals(pageSize) ? PDRectangle.A4 : PDRectangle.LETTER;
        return landscape ? new PDRectangle(r.getHeight(), r.getWidth()) : r;
    }

    /** The margin in points: this spec's, or {@code writerDefault} when it leaves the choice to the writer. */
    public float marginOr(float writerDefault) {
        return margin >= 0f ? margin : writerDefault;
    }

    /**
     * {@code "a4"} or {@code "letter"}. Anything else (a hand-edited {@code "legal"}, a typo) falls back to
     * Letter as it always has, but is logged the first time so the fallback is not silent.
     */
    private static String normalise(String key) {
        String k = key == null ? "" : key.strip().toLowerCase(Locale.ROOT);
        if (k.equals("a4") || k.equals("letter")) {
            return k;
        }
        if (!k.isEmpty() && REPORTED.add(k)) {
            LOG.warning("Unknown PDF page size \"" + key + "\"; using Letter");
        }
        return "letter";
    }
}
