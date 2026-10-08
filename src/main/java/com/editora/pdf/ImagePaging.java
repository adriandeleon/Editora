package com.editora.pdf;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * Pure geometry for putting raster images on pages — shared by the PDF writer ({@link ImagePdfWriter}) and
 * the print paginator ({@code print/PrintService}), so an exported PDF and a printed page break in the same
 * places. No PDFBox, no FX; unit-tested.
 *
 * <p>An image has a size in pixels and a {@linkplain Source#pixelScale() pixel scale}: how many of its
 * pixels make one logical pixel. A snapshot taken at 2× is laid out at its logical size (one logical pixel
 * per point at most) and its extra pixels are resolution, not size.
 */
public final class ImagePaging {

    /**
     * Below this fit scale an image is not shrunk to the page width any more but tiled across pages at
     * {@link #TILE_SCALE}. It sits under 0.5 on purpose: the fixed-width previews (crontab, systemd,
     * Markwhen — 1100 logical pixels) fit a portrait page at 0.43–0.49, and they must stay on one column.
     */
    public static final double MIN_LEGIBLE_SCALE = 0.4;

    /** The scale a tiled image is drawn at: half size, where 12 px labels are still 6 pt. */
    public static final double TILE_SCALE = 0.5;

    /** How far above a page's end, as a fraction of the page, a blank pixel row is looked for to cut at. */
    static final double LOOK_BACK = 0.25;

    /**
     * Content that overruns its last page by no more than this factor is shrunk to fit instead: an A4 Typst
     * page fitted to the width of Letter paper is 6% too tall, and used to print its bottom margin on a page
     * of its own.
     */
    static final double SQUEEZE = 1.15;

    private static final int[] NO_CUTS = new int[0];

    private ImagePaging() {}

    /**
     * One image to lay out.
     *
     * @param pixelScale image pixels per logical pixel (2 for a 2× snapshot); a bad value counts as 1
     * @param safeCuts   ascending pixel rows at which the image may be cut without splitting a row of
     *                   content (the boundaries between the rows of a tree snapshot); empty when unknown
     * @param continues  the image carries on from the one before it (the next chunk of the same row list),
     *                   so it follows on the same page instead of starting a new one
     */
    public record Source(int pixelWidth, int pixelHeight, double pixelScale, int[] safeCuts, boolean continues) {
        public Source {
            pixelScale = pixelScale > 0 && Double.isFinite(pixelScale) ? pixelScale : 1;
            safeCuts = safeCuts == null ? NO_CUTS : safeCuts;
        }

        /** An image with no known scale or row boundaries: one image pixel per logical pixel. */
        public static Source plain(int pixelWidth, int pixelHeight) {
            return new Source(pixelWidth, pixelHeight, 1, NO_CUTS, false);
        }

        public double logicalWidth() {
            return pixelWidth / pixelScale;
        }

        public double logicalHeight() {
            return pixelHeight / pixelScale;
        }

        boolean empty() {
            return pixelWidth < 1 || pixelHeight < 1;
        }
    }

    /**
     * A rectangle of image {@code image} (in its pixels) and where it is drawn: {@code x}/{@code y} from the
     * top-left corner of the printable area, in points.
     */
    public record Slice(
            int image,
            int srcX,
            int srcY,
            int srcWidth,
            int srcHeight,
            double x,
            double y,
            double width,
            double height) {}

    /** One page: its slices, and whether the page is turned to landscape (only when the caller allowed it). */
    public record Page(boolean landscape, List<Slice> slices) {}

    /** Tells whether a pixel row of an image is blank — one colour from edge to edge. Asked rarely. */
    @FunctionalInterface
    public interface BlankRows {
        boolean blank(int image, int row);

        BlankRows NONE = (image, row) -> false;
    }

    /** Points per logical pixel that fit a {@code logicalWidth} image to the width, never enlarging it. */
    public static double fitScale(double logicalWidth, double availW) {
        if (!(logicalWidth > 0)) {
            return 1.0;
        }
        return Math.min(availW / logicalWidth, 1.0);
    }

    /** As {@link #fitScale} in both directions: the whole image on one page, never enlarged. */
    public static double fitWithin(double logicalWidth, double logicalHeight, double availW, double availH) {
        if (!(logicalHeight > 0)) {
            return fitScale(logicalWidth, availW);
        }
        return Math.min(fitScale(logicalWidth, availW), availH / logicalHeight);
    }

    /** How many source pixel rows one page holds, at {@code scale} points per source pixel; at least one. */
    public static int rowsPerPage(double availH, double scale) {
        return Math.max(1, (int) Math.floor(availH / scale + 1e-6));
    }

    /**
     * Whether a landscape page shows the image larger than a portrait one: it is wider than tall, and too
     * wide for the portrait width. {@code availW}/{@code availH} are the portrait printable area.
     */
    public static boolean landscapeFitsBetter(double logicalWidth, double logicalHeight, double availW, double availH) {
        return logicalWidth > logicalHeight && fitScale(logicalWidth, availH) > fitScale(logicalWidth, availW);
    }

    /** How many page-wide columns an image of {@code logicalWidth} needs at {@code scale}; at least one. */
    public static int tileColumns(double logicalWidth, double scale, double availW) {
        return Math.max(1, (int) Math.ceil(logicalWidth * scale / availW - 1e-6));
    }

    /**
     * Where to end a slice that starts at pixel row {@code from} when {@code room} rows are left on the
     * page. Returns {@code from} to say "nothing here — start a new page". In order of preference: the rest
     * of the image; the last {@code safeCuts} boundary that fits; a new page, when the next whole unit would
     * fit on one; a blank row within the look-back window above the page end; the page end itself.
     *
     * @param pageRows rows on an empty page
     * @param fresh    the page is empty, so a slice must be placed (the result is then greater than
     *                 {@code from})
     * @param total    the image height
     */
    public static int chooseCut(
            int from, int room, int pageRows, boolean fresh, int total, int[] safeCuts, IntPredicate blankRow) {
        int limit = from + Math.max(0, room);
        if (limit >= total) {
            return total;
        }
        int next = -1; // the first row boundary below `from`
        int best = -1; // the last one that still fits
        for (int cut : safeCuts) {
            if (cut <= from || cut >= total) {
                continue;
            }
            if (next < 0) {
                next = cut;
            }
            if (cut > limit) {
                break;
            }
            best = cut;
        }
        if (best > 0) {
            return best;
        }
        int unit = (next > 0 ? next : total) - from; // what has to stay together
        if (!fresh && (unit <= pageRows || room < pageRows * LOOK_BACK)) {
            return from;
        }
        int lookBack = Math.max(1, (int) (pageRows * LOOK_BACK));
        for (int cut = limit; cut > from && cut > limit - lookBack; cut--) {
            if (blankRow.test(cut - 1)) {
                return cut;
            }
        }
        return limit;
    }

    /**
     * Lays {@code sources} out on pages of {@code availW × availH} printable points (portrait). An image is
     * fitted to the page width and never enlarged past one logical pixel per point; a tall one runs over
     * several pages, cut by {@link #chooseCut}; consecutive {@linkplain Source#continues() continuing}
     * images share one scale and flow on from each other; content that is only slightly taller than one
     * page is shrunk onto it ({@link #SQUEEZE}). An image that would be shrunk below
     * {@link #MIN_LEGIBLE_SCALE} is tiled instead: page-wide columns left to right, then the next band down.
     *
     * @param mayRotate turn the page to landscape for an image that is wider than tall and gains from it —
     *                  for a PDF; a printed page keeps the orientation of the layout the user chose
     */
    public static List<Page> layout(
            List<Source> sources, double availW, double availH, boolean mayRotate, BlankRows blankRows) {
        List<Page> pages = new ArrayList<>();
        int i = 0;
        while (i < sources.size()) {
            int end = i + 1;
            while (end < sources.size() && sources.get(end).continues()) {
                end++;
            }
            layoutGroup(sources, i, end, availW, availH, mayRotate, blankRows, pages);
            i = end;
        }
        return pages;
    }

    private static void layoutGroup(
            List<Source> sources,
            int start,
            int end,
            double availW,
            double availH,
            boolean mayRotate,
            BlankRows blankRows,
            List<Page> pages) {
        double width = 0;
        double height = 0;
        for (int i = start; i < end; i++) {
            if (!sources.get(i).empty()) {
                width = Math.max(width, sources.get(i).logicalWidth());
                height += sources.get(i).logicalHeight();
            }
        }
        if (width <= 0) {
            return;
        }
        boolean landscape = mayRotate && landscapeFitsBetter(width, height, availW, availH);
        double pageW = landscape ? availH : availW;
        double pageH = landscape ? availW : availH;
        double scale = fitScale(width, pageW);
        int columns = 1;
        if (scale < MIN_LEGIBLE_SCALE) {
            scale = TILE_SCALE;
            columns = tileColumns(width, scale, pageW);
        } else if (height * scale > pageH && height * scale <= pageH * SQUEEZE) {
            scale = pageH / height; // a few percent smaller beats a second page holding the last sliver
        }
        List<Slice> current = new ArrayList<>();
        double cursor = 0; // points used on the current page
        for (int i = start; i < end; i++) {
            Source s = sources.get(i);
            if (s.empty()) {
                continue;
            }
            int image = i;
            double pxPerPt = s.pixelScale() / scale;
            int pageRows = rowsPerPage(pageH, 1 / pxPerPt);
            int y = 0;
            while (y < s.pixelHeight()) {
                boolean fresh = current.isEmpty();
                int room = fresh ? pageRows : (int) Math.floor((pageH - cursor) * pxPerPt + 1e-6);
                int cut = chooseCut(
                        y, room, pageRows, fresh, s.pixelHeight(), s.safeCuts(), row -> blankRows.blank(image, row));
                if (cut <= y) {
                    pages.add(new Page(landscape, current));
                    current = new ArrayList<>();
                    cursor = 0;
                    continue;
                }
                double drawH = (cut - y) / pxPerPt;
                if (columns == 1) {
                    current.add(
                            new Slice(i, 0, y, s.pixelWidth(), cut - y, 0, cursor, s.pixelWidth() / pxPerPt, drawH));
                    cursor += drawH;
                } else {
                    int tileW = Math.max(1, (int) Math.floor(pageW * pxPerPt + 1e-6));
                    for (int x = 0; x < s.pixelWidth(); x += tileW) {
                        int w = Math.min(tileW, s.pixelWidth() - x);
                        pages.add(
                                new Page(landscape, List.of(new Slice(i, x, y, w, cut - y, 0, 0, w / pxPerPt, drawH))));
                    }
                }
                y = cut;
                if (y < s.pixelHeight() && !current.isEmpty()) { // the image goes on: its next slice opens a page
                    pages.add(new Page(landscape, current));
                    current = new ArrayList<>();
                    cursor = 0;
                }
            }
        }
        if (!current.isEmpty()) {
            pages.add(new Page(landscape, current));
        }
    }
}
