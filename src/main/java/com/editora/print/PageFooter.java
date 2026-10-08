package com.editora.print;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.text.Font;
import javafx.scene.text.Text;

import com.editora.i18n.Messages;

/**
 * The line at the foot of a printed text page: the document's name on the left, "Page n of N" on the
 * right, in the small grey type the code PDF uses for its page number. Without it a printed stack carries
 * nothing that says which file a sheet came from or where it belongs once the stack has been dropped.
 *
 * <p>A layout that is given a footer paginates against {@link #bodyHeight} and puts {@link #line} at the
 * bottom of each page; given none it uses the whole page, as it did before there was one.
 */
public final class PageFooter {

    /** Height taken off the bottom of the printable area: the line of type and the gap above it. */
    static final double HEIGHT = 20;

    private static final double FONT_SIZE = 8;
    private static final String FAMILY = "Inter";
    private static final String COLOR = "#8c959f"; // PdfTheme.LINE_NUMBER
    /** The share of the page width the document name may take before it is cut short. */
    private static final double NAME_SHARE = 0.7;

    private final String name;

    private PageFooter(String name) {
        this.name = name == null ? "" : name.strip();
    }

    /** A footer naming {@code documentName} (null or blank: the page number only). */
    public static PageFooter of(String documentName) {
        return new PageFooter(documentName);
    }

    /** {@link #of} when {@code enabled}, else null — which every layout reads as "no footer". */
    public static PageFooter of(String documentName, boolean enabled) {
        return enabled ? of(documentName) : null;
    }

    /** What is left of a page {@code pageHeight} tall for its content once the footer has its room. */
    public static double bodyHeight(double pageHeight) {
        return Math.max(1, pageHeight - HEIGHT);
    }

    /** The localised "Page n of N". */
    static String pageLabel(int page, int count) {
        return Messages.tr("print.footer.page", page, count);
    }

    /** The footer of page {@code page} (1-based) of {@code count}: a {@code width}-wide row, {@link #HEIGHT} tall. */
    public Node line(int page, int count, double width) {
        return line(page, count, width, 0);
    }

    /**
     * As {@link #line(int, int, double)}, with the text set in by {@code inset} on both sides — for a page
     * whose content has side padding of its own, so the footer lines up with the text above it.
     */
    public Node line(int page, int count, double width, double inset) {
        Font font = Font.font(FAMILY, FONT_SIZE);
        Text left = text(shorten(name, Math.max(0, (width - 2 * inset) * NAME_SHARE), font), font);
        Text right = text(pageLabel(page, count), font);
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox row = new HBox(left, gap, right);
        row.getStyleClass().add("print-page-footer");
        row.setAlignment(Pos.BOTTOM_LEFT);
        row.setPadding(new javafx.geometry.Insets(0, Math.max(0, inset), 0, Math.max(0, inset)));
        row.setMinSize(width, HEIGHT);
        row.setPrefSize(width, HEIGHT);
        row.setMaxSize(width, HEIGHT);
        return row;
    }

    /**
     * Font and colour are set inline as well as on the node: a Markdown page carries the preview stylesheet,
     * and a font set only from code loses to the one the page's CSS hands down.
     */
    private static Text text(String s, Font font) {
        Text t = new Text(s);
        t.setFont(font);
        t.setStyle("-fx-font-family: \"" + FAMILY + "\"; -fx-font-size: " + FONT_SIZE + "px; -fx-fill: " + COLOR
                + "; -fx-font-weight: normal;");
        return t;
    }

    /** {@code s}, or its start and an ellipsis when it is wider than {@code maxWidth} in {@code font}. */
    static String shorten(String s, double maxWidth, Font font) {
        if (s.isEmpty() || width(s, font) <= maxWidth) {
            return s;
        }
        int lo = 0;
        int hi = s.length() - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (width(s.substring(0, mid) + "…", font) <= maxWidth) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return s.substring(0, lo).stripTrailing() + "…";
    }

    private static double width(String s, Font font) {
        Text probe = new Text(s);
        probe.setFont(font);
        return probe.getLayoutBounds().getWidth();
    }
}
