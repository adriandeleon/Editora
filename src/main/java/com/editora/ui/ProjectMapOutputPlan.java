package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure page geometry for Project Map print and PDF output.
 *
 * <p>Everything is measured in <em>world</em> pixels (the map at 100% zoom, where a row label is
 * {@value #LABEL_SIZE} px) and paper points. The plan fits the whole map on one page while a label stays at
 * least {@value #MIN_LABEL_POINTS} pt tall; a larger map keeps that minimum scale and is tiled across a grid
 * of pages instead, cutting between columns and rows where one of the candidate positions allows it. The
 * raster scale is then chosen under a per-page dimension cap and a total pixel budget; a map that cannot keep
 * a printable resolution inside them is reported as {@link Plan#detailReduced()} rather than shrunk silently.
 */
final class ProjectMapOutputPlan {

    /** Height of a row label in world pixels. */
    static final double LABEL_SIZE = 12;

    static final double MIN_LABEL_POINTS = 7;

    /** Paper points per world pixel below which the output is tiled instead of shrunk further. */
    static final double MIN_PAPER_SCALE = MIN_LABEL_POINTS / LABEL_SIZE;

    /** The map is never enlarged beyond one point per world pixel. */
    static final double MAX_PAPER_SCALE = 1.0;

    /** Image pixels per world pixel when the budgets allow it. */
    static final double PREFERRED_RENDER_SCALE = 2.0;

    static final double MAX_PAGE_DIMENSION = 8_192;
    static final double MAX_TOTAL_PIXELS = 12_000_000;

    /** Image pixels per paper point (96 dpi) under which small text stops being readable. */
    static final double MIN_PIXELS_PER_POINT = 96.0 / 72.0;

    /** A page cut may move back by at most this share of a page to land in a gap. */
    private static final double CUT_SEARCH_SHARE = 0.4;

    /** Distance kept between a cut and the box edge it follows. */
    private static final double CUT_CLEARANCE = 3;

    private static final double EPSILON = 0.001;

    private ProjectMapOutputPlan() {}

    record Box(double x, double y, double width, double height) {
        double maxX() {
            return x + width;
        }

        double maxY() {
            return y + height;
        }
    }

    /** One output page: the world rectangle it shows and its position in the page grid. */
    record Cell(double x, double y, double width, double height, int column, int row) {}

    /**
     * @param pages the non-empty pages in reading order
     * @param paperScale paper points per world pixel
     * @param renderScale image pixels per world pixel
     * @param columns pages across, including any left out because nothing falls on them
     * @param rows pages down, counted the same way
     * @param detailReduced the budgets forced a resolution under {@link #MIN_PIXELS_PER_POINT}
     */
    record Plan(List<Cell> pages, double paperScale, double renderScale, int columns, int rows, boolean detailReduced) {

        boolean tiled() {
            return columns * rows > 1;
        }

        /** A single page that had to shrink the map to fit it. */
        boolean scaledDown() {
            return !tiled() && paperScale < MAX_PAPER_SCALE - EPSILON;
        }

        /** Paper points covered by one image pixel. */
        double pointsPerPixel() {
            return paperScale / renderScale;
        }
    }

    /**
     * Plans the pages for {@code content} on a page with a {@code pageWidth} × {@code pageHeight} printable
     * area in points. {@code obstacles} are the column cards and rows a page cut should avoid crossing.
     */
    static Plan plan(Box content, List<Box> obstacles, double pageWidth, double pageHeight) {
        double width = Math.max(1, content.width());
        double height = Math.max(1, content.height());
        double usableWidth = pageWidth > 0 ? pageWidth : width;
        double usableHeight = pageHeight > 0 ? pageHeight : height;
        double fit = Math.min(usableWidth / width, usableHeight / height);
        double paperScale = Math.min(MAX_PAPER_SCALE, Math.max(MIN_PAPER_SCALE, fit));

        double[] columnCuts = {content.x(), content.x() + width};
        double[] rowCuts = {content.y(), content.y() + height};
        if (fit < MIN_PAPER_SCALE - EPSILON) {
            List<double[]> horizontal = new ArrayList<>(obstacles.size());
            List<double[]> vertical = new ArrayList<>(obstacles.size());
            for (Box box : obstacles) {
                horizontal.add(new double[] {box.x(), box.maxX()});
                vertical.add(new double[] {box.y(), box.maxY()});
            }
            columnCuts = cuts(content.x(), content.x() + width, usableWidth / paperScale, horizontal);
            rowCuts = cuts(content.y(), content.y() + height, usableHeight / paperScale, vertical);
        }

        List<Cell> pages = new ArrayList<>();
        double area = 0;
        double longestSide = 1;
        for (int row = 0; row + 1 < rowCuts.length; row++) {
            for (int column = 0; column + 1 < columnCuts.length; column++) {
                Cell cell = new Cell(
                        columnCuts[column],
                        rowCuts[row],
                        columnCuts[column + 1] - columnCuts[column],
                        rowCuts[row + 1] - rowCuts[row],
                        column,
                        row);
                if (obstacles.isEmpty() || touches(cell, obstacles)) {
                    pages.add(cell);
                    area += cell.width() * cell.height();
                    longestSide = Math.max(longestSide, Math.max(cell.width(), cell.height()));
                }
            }
        }
        double renderScale = Math.min(
                PREFERRED_RENDER_SCALE,
                Math.min(MAX_PAGE_DIMENSION / longestSide, Math.sqrt(MAX_TOTAL_PIXELS / Math.max(1, area))));
        boolean detailReduced = renderScale < paperScale * MIN_PIXELS_PER_POINT - EPSILON;
        return new Plan(
                List.copyOf(pages), paperScale, renderScale, columnCuts.length - 1, rowCuts.length - 1, detailReduced);
    }

    /**
     * Splits {@code start..end} into runs no longer than {@code extent}. Each boundary is the position in the
     * last {@value #CUT_SEARCH_SHARE} of the run that crosses the fewest {@code spans} (a gap between two
     * columns or rows when there is one), preferring the latest such position.
     */
    static double[] cuts(double start, double end, double extent, List<double[]> spans) {
        List<Double> result = new ArrayList<>();
        result.add(start);
        double step = Math.max(1, extent);
        double position = start;
        while (end - position > step + EPSILON) {
            double limit = position + step;
            double earliest = limit - step * CUT_SEARCH_SHARE;
            double best = limit;
            int bestCrossings = crossings(limit, spans);
            if (bestCrossings > 0) {
                for (double[] span : spans) {
                    for (double candidate : new double[] {span[0] - CUT_CLEARANCE, span[1] + CUT_CLEARANCE}) {
                        if (candidate < earliest || candidate >= limit) {
                            continue;
                        }
                        int count = crossings(candidate, spans);
                        if (count < bestCrossings || count == bestCrossings && best != limit && candidate > best) {
                            best = candidate;
                            bestCrossings = count;
                        }
                    }
                }
            }
            result.add(best);
            position = best;
        }
        result.add(end);
        double[] cuts = new double[result.size()];
        for (int i = 0; i < cuts.length; i++) {
            cuts[i] = result.get(i);
        }
        return cuts;
    }

    private static int crossings(double position, List<double[]> spans) {
        int count = 0;
        for (double[] span : spans) {
            if (span[0] < position && position < span[1]) {
                count++;
            }
        }
        return count;
    }

    private static boolean touches(Cell cell, List<Box> boxes) {
        for (Box box : boxes) {
            if (box.x() < cell.x() + cell.width()
                    && box.maxX() > cell.x()
                    && box.y() < cell.y() + cell.height()
                    && box.maxY() > cell.y()) {
                return true;
            }
        }
        return false;
    }
}
