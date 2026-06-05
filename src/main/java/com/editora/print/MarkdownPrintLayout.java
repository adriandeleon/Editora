package com.editora.print;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.editora.editor.MarkdownRenderer;

import javafx.print.PageLayout;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Builds printable JavaFX page nodes for the rendered Markdown preview — the {@code javafx.print}
 * companion to {@code com.editora.pdf.MarkdownPdfWriter}. Reuses {@link MarkdownRenderer} (parse +
 * native-node render) and the same light preview theme ({@code md-light} + {@code app.css}/{@code
 * syntax.css}) the live preview uses.
 *
 * <p><b>Block-aware pagination:</b> each top-level block (heading, paragraph, list, table, code
 * block, image, …) is measured at the printable width, then whole blocks are greedily packed into
 * pages so nothing is split across a page boundary (the pure, unit-tested {@link #packBlocks}). A
 * single block taller than a full page gets its own page, uniformly scaled down to fit.
 *
 * <p>Everything except {@link #packBlocks} needs the JavaFX toolkit and runs on the FX thread.
 */
public final class MarkdownPrintLayout {

    private MarkdownPrintLayout() {
    }

    /**
     * Greedily packs block {@code heights} into pages no taller than {@code pageHeight}, never
     * splitting a block. A block taller than a page gets its own (single-block) page. Returns the
     * block indices for each page (always at least one page).
     */
    public static List<List<Integer>> packBlocks(List<Double> heights, double pageHeight) {
        List<List<Integer>> pages = new ArrayList<>();
        boolean validPage = pageHeight > 0 && Double.isFinite(pageHeight);
        List<Integer> cur = new ArrayList<>();
        double used = 0;
        for (int i = 0; i < heights.size(); i++) {
            double h = Math.max(0, heights.get(i));
            if (validPage && h > pageHeight) { // taller than any page → its own page (scaled to fit)
                if (!cur.isEmpty()) {
                    pages.add(cur);
                    cur = new ArrayList<>();
                    used = 0;
                }
                pages.add(new ArrayList<>(List.of(i)));
                continue;
            }
            if (validPage && !cur.isEmpty() && used + h > pageHeight) {
                pages.add(cur);
                cur = new ArrayList<>();
                used = 0;
            }
            cur.add(i);
            used += h;
        }
        if (!cur.isEmpty()) {
            pages.add(cur);
        }
        if (pages.isEmpty()) {
            pages.add(new ArrayList<>());
        }
        return pages;
    }

    /**
     * Renders {@code ast} (light theme), measures its blocks at {@code layout}'s printable width, and
     * returns one printable page {@link Node} (a {@code pw×ph} root, CSS attached) per page.
     */
    public static List<Node> paginate(org.commonmark.node.Node ast, Path baseDir, PageLayout layout) {
        double pw = layout.getPrintableWidth();
        double ph = layout.getPrintableHeight();

        // Render to native nodes, then pull out the inner ".markdown-preview" VBox of blocks.
        Node wrap = MarkdownRenderer.renderDocument(ast, baseDir);
        VBox content = (VBox) ((StackPane) wrap).getChildren().get(0);
        content.setMaxWidth(pw);
        content.setPrefWidth(pw);

        // Measure offscreen at the printable width, with the light preview theme + stylesheets.
        StackPane measureRoot = new StackPane(content);
        measureRoot.getStyleClass().add("md-light");
        measureRoot.setPrefWidth(pw);
        measureRoot.setMaxWidth(pw);
        Scene measureScene = new Scene(new Group(measureRoot));
        attachStyles(measureScene);
        measureRoot.applyCss();
        measureRoot.layout();

        List<Node> blocks = new ArrayList<>(content.getChildrenUnmodifiable());
        List<Double> heights = new ArrayList<>();
        for (Node b : blocks) {
            heights.add(b.getLayoutBounds().getHeight());
        }
        List<List<Integer>> packed = packBlocks(heights, ph);

        // Detach the blocks so they can be re-parented into per-page containers.
        content.getChildren().clear();

        List<Node> pages = new ArrayList<>();
        for (List<Integer> idxs : packed) {
            VBox pageContent = new VBox();
            pageContent.getStyleClass().add("markdown-preview");
            pageContent.setMaxWidth(pw);
            pageContent.setPrefWidth(pw);
            for (int i : idxs) {
                pageContent.getChildren().add(blocks.get(i));
            }
            Node body = pageContent;
            // A single over-tall block: scale it down uniformly to fit the page height.
            if (idxs.size() == 1) {
                double h = heights.get(idxs.get(0));
                if (h > ph && ph > 0) {
                    double s = ph / h;
                    pageContent.setScaleX(s);
                    pageContent.setScaleY(s);
                    body = new Group(pageContent);
                }
            }
            StackPane pageRoot = new StackPane(body);
            pageRoot.getStyleClass().add("md-light");
            pageRoot.setStyle("-fx-background-color: white;");
            StackPane.setAlignment(body, javafx.geometry.Pos.TOP_LEFT);
            pageRoot.setPrefSize(pw, ph);
            pageRoot.setMinSize(pw, ph);
            pageRoot.setMaxSize(pw, ph);
            Scene pageScene = new Scene(pageRoot, pw, ph);
            attachStyles(pageScene);
            pageRoot.applyCss();
            pageRoot.layout();
            pages.add(pageRoot);
        }
        return pages;
    }

    private static void attachStyles(Scene scene) {
        addStylesheet(scene, "/com/editora/styles/app.css");
        addStylesheet(scene, "/com/editora/styles/syntax.css");
    }

    private static void addStylesheet(Scene scene, String resource) {
        java.net.URL url = MarkdownPrintLayout.class.getResource(resource);
        if (url != null) {
            scene.getStylesheets().add(url.toExternalForm());
        }
    }
}
