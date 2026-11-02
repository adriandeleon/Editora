package com.editora.ui;

import java.util.List;
import java.util.Locale;

/**
 * The whole Project Map as an SVG document (Map explore, position 16): the column cards, every row with its
 * name as real {@code <text>} (searchable, selectable and scalable, unlike the PNG and the printed pages), and
 * the connectors from a folder row to the column it opens. Everything is in world units, the map at 100%,
 * in the light palette the printed output uses. Pure: the surface hands over the layout, this writes it.
 */
final class ProjectMapSvg {

    /** A column card and its title. */
    record Column(String title, String count, boolean structure, double x, double y, double width, double height) {}

    /**
     * A row: {@code label} is what fits the row (the name cut where it differs from its siblings), {@code name}
     * the whole name, kept as the row's {@code <title>}. {@code labelX}/{@code baseline} place the label.
     */
    record Row(
            String name,
            String label,
            boolean folder,
            boolean symbol,
            boolean stub,
            boolean selected,
            boolean dimmed,
            double x,
            double y,
            double width,
            double height,
            double labelX,
            double baseline) {}

    /** A connector from a parent row's edge to a child row's edge, curving along the flow. */
    record Link(double fromX, double fromY, double toX, double toY, boolean vertical) {}

    /** The colours, as {@code #rrggbb}. */
    record Palette(
            String background,
            String card,
            String cardBorder,
            String structureCard,
            String row,
            String rowBorder,
            String text,
            String muted,
            String accent,
            String folder,
            String file,
            String symbol) {}

    /** The font the map draws with, then the fallbacks a viewer has when it lacks it. */
    static final String FONT_FAMILY = "Inter, 'Segoe UI', 'Helvetica Neue', Arial, sans-serif";

    private ProjectMapSvg() {}

    /** The SVG document for {@code bounds} ({@code {x, y, width, height}}) holding these columns, rows and links. */
    static String write(
            double[] bounds, List<Column> columns, List<Row> rows, List<Link> links, Palette palette, String title) {
        StringBuilder svg = new StringBuilder(256 + rows.size() * 220);
        svg.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" version=\"1.1\"")
                .append(" width=\"")
                .append(n(bounds[2]))
                .append("\" height=\"")
                .append(n(bounds[3]))
                .append("\" viewBox=\"")
                .append(n(bounds[0]))
                .append(' ')
                .append(n(bounds[1]))
                .append(' ')
                .append(n(bounds[2]))
                .append(' ')
                .append(n(bounds[3]))
                .append("\" font-family=\"")
                .append(escape(FONT_FAMILY))
                .append("\">\n");
        svg.append("<title>").append(escape(title)).append("</title>\n");
        svg.append("<rect x=\"")
                .append(n(bounds[0]))
                .append("\" y=\"")
                .append(n(bounds[1]))
                .append("\" width=\"")
                .append(n(bounds[2]))
                .append("\" height=\"")
                .append(n(bounds[3]))
                .append("\" fill=\"")
                .append(palette.background())
                .append("\"/>\n");

        svg.append("<g class=\"columns\">\n");
        for (Column column : columns) {
            svg.append("<g><rect x=\"")
                    .append(n(column.x()))
                    .append("\" y=\"")
                    .append(n(column.y()))
                    .append("\" width=\"")
                    .append(n(column.width()))
                    .append("\" height=\"")
                    .append(n(column.height()))
                    .append("\" rx=\"6\" fill=\"")
                    .append(column.structure() ? palette.structureCard() : palette.card())
                    .append("\" stroke=\"")
                    .append(palette.cardBorder())
                    .append("\"/>");
            double baseline = column.y() + 16.5;
            svg.append("<text x=\"")
                    .append(n(column.x() + 10))
                    .append("\" y=\"")
                    .append(n(baseline))
                    .append("\" font-size=\"11\" font-weight=\"600\" fill=\"")
                    .append(palette.text())
                    .append("\">")
                    .append(escape(column.title()))
                    .append("</text>");
            if (!column.count().isEmpty()) {
                svg.append("<text x=\"")
                        .append(n(column.x() + column.width() - 8))
                        .append("\" y=\"")
                        .append(n(baseline))
                        .append("\" font-size=\"9\" text-anchor=\"end\" fill=\"")
                        .append(palette.muted())
                        .append("\">")
                        .append(escape(column.count()))
                        .append("</text>");
            }
            svg.append("</g>\n");
        }
        svg.append("</g>\n");

        svg.append("<g class=\"links\" fill=\"none\" stroke=\"")
                .append(palette.accent())
                .append("\" stroke-width=\"1.15\" stroke-opacity=\"0.45\">\n");
        for (Link link : links) {
            svg.append("<path d=\"").append(curve(link)).append("\"/>\n");
        }
        svg.append("</g>\n");

        svg.append("<g class=\"rows\" font-size=\"12\">\n");
        for (Row row : rows) {
            svg.append("<g");
            if (row.dimmed()) {
                svg.append(" opacity=\"0.45\"");
            }
            svg.append("><title>").append(escape(row.name())).append("</title>");
            svg.append("<rect x=\"")
                    .append(n(row.x()))
                    .append("\" y=\"")
                    .append(n(row.y()))
                    .append("\" width=\"")
                    .append(n(row.width()))
                    .append("\" height=\"")
                    .append(n(row.height()))
                    .append("\" rx=\"4\" fill=\"")
                    .append(palette.row())
                    .append("\" stroke=\"")
                    .append(row.selected() ? palette.accent() : palette.rowBorder())
                    .append(row.selected() ? "\" stroke-width=\"1.8\"/>" : "\"/>");
            // A small mark where the painted map has its icon: folders, files and declarations apart.
            String mark = row.symbol() ? palette.symbol() : row.folder() ? palette.folder() : palette.file();
            double markSize = Math.min(12, row.height() - 8);
            if (!row.stub()) {
                svg.append("<rect x=\"")
                        .append(n(row.labelX() - 21))
                        .append("\" y=\"")
                        .append(n(row.baseline() - markSize + 1))
                        .append("\" width=\"")
                        .append(n(markSize))
                        .append("\" height=\"")
                        .append(n(markSize))
                        .append(row.folder() ? "\" rx=\"2\"" : "\" rx=\"1\"")
                        .append(" fill=\"")
                        .append(mark)
                        .append(row.folder() ? "\"/>" : "\" fill-opacity=\"0.55\"/>");
            }
            svg.append("<text x=\"")
                    .append(n(row.labelX()))
                    .append("\" y=\"")
                    .append(n(row.baseline()))
                    .append("\" fill=\"")
                    .append(palette.text())
                    .append(row.selected() ? "\" font-weight=\"600\">" : "\">")
                    .append(escape(row.label()))
                    .append("</text></g>\n");
        }
        svg.append("</g>\n</svg>\n");
        return svg.toString();
    }

    /** A cubic from one edge to the other, its handles half-way along the flow. */
    private static String curve(Link link) {
        double fx = link.fromX();
        double fy = link.fromY();
        double tx = link.toX();
        double ty = link.toY();
        if (link.vertical()) {
            double mid = (fy + ty) / 2;
            return "M" + n(fx) + ' ' + n(fy) + " C" + n(fx) + ' ' + n(mid) + ' ' + n(tx) + ' ' + n(mid) + ' ' + n(tx)
                    + ' ' + n(ty);
        }
        double mid = (fx + tx) / 2;
        return "M" + n(fx) + ' ' + n(fy) + " C" + n(mid) + ' ' + n(fy) + ' ' + n(mid) + ' ' + n(ty) + ' ' + n(tx) + ' '
                + n(ty);
    }

    /** A coordinate with at most two decimals and no exponent. */
    static String n(double value) {
        if (!Double.isFinite(value)) {
            return "0";
        }
        double rounded = Math.round(value * 100) / 100.0;
        if (rounded == Math.rint(rounded) && Math.abs(rounded) < 1e15) {
            return Long.toString((long) rounded);
        }
        return String.format(Locale.ROOT, "%.2f", rounded);
    }

    /** Text and attribute values with XML's five specials escaped and the characters XML 1.0 forbids dropped. */
    static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> {
                    if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') {
                        if (c != 0xFFFE && c != 0xFFFF) {
                            out.append(c);
                        }
                    }
                }
            }
        }
        return out.toString();
    }

    /** {@code #rrggbb} for a colour (its alpha left out). */
    static String hex(javafx.scene.paint.Color color) {
        return String.format(
                Locale.ROOT,
                "#%02x%02x%02x",
                (int) Math.round(color.getRed() * 255),
                (int) Math.round(color.getGreen() * 255),
                (int) Math.round(color.getBlue() * 255));
    }
}
