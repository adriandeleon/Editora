package com.editora.editor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The stylesheet scaler behind the UI zoom: lengths are multiplied, and nothing else is touched. */
class UiZoomCssTest {

    private static final Path STYLES = Path.of("src/main/resources/com/editora/styles");

    private static String rule(String declarations) {
        return ".x {\n    " + declarations + "\n}\n";
    }

    private static String scaled(String declarations, double factor) {
        String out = UiZoomCss.scaleSheet(rule(declarations), factor);
        return out.substring(".x {\n    ".length(), out.length() - "\n}\n".length());
    }

    @Test
    void pixelAndBareLengthsScale() {
        assertEquals("-fx-font-size: 21px;", scaled("-fx-font-size: 14px;", 1.5));
        assertEquals("-fx-padding: 12 18 12 18;", scaled("-fx-padding: 8 12 8 12;", 1.5));
        assertEquals("-fx-padding: 0 0 0 9px;", scaled("-fx-padding: 0 0 0 6px;", 1.5));
        assertEquals("-fx-background-radius: 10px, 8.75px;", scaled("-fx-background-radius: 8px, 7px;", 1.25));
        assertEquals("-fx-min-width: 28.5;", scaled("-fx-min-width: 19;", 1.5));
        assertEquals("-fx-spacing: 6; -fx-hgap: 3;", scaled("-fx-spacing: 4; -fx-hgap: 2;", 1.5));
        assertEquals("-fx-stroke-width: 1.95;", scaled("-fx-stroke-width: 1.3;", 1.5));
        assertEquals("-fx-border-insets: 15 0 15 -18;", scaled("-fx-border-insets: 10 0 10 -12;", 1.5));
    }

    @Test
    void relativeSizesAndNonLengthsAreLeftAlone() {
        for (String same : List.of(
                "-fx-font-size: 0.85em;",
                "-fx-opacity: 0.55;",
                "-fx-font-weight: 700;",
                "-fx-scale-x: 1.5;",
                "-fx-pref-column-count: 6;",
                "-fx-tab-size: 4;",
                "-fx-background-color: rgba(27, 30, 42, 0.28);",
                "-color-accent-muted: rgba(201, 168, 76, 0.3);",
                "-fx-text-fill: derive(-color-fg-default, 20%);",
                "-fx-background-color: #1b1e2a;",
                "-fx-text-fill: -color-base-4;",
                "-fx-shape: \"M2,0 L5,4 L8,0 Z\";",
                "-fx-indeterminate-bar-animation-time: 2;",
                "-fx-max-width: infinity;",
                "-fx-pref-width: -1;",
                "-fx-cell-size: 2em;")) {
            assertEquals(same, scaled(same, 1.5), same);
        }
    }

    @Test
    void hairlinesStayOnePixel() {
        assertEquals("-fx-border-width: 1;", scaled("-fx-border-width: 1;", 1.5));
        assertEquals("-fx-border-width: 0 0 0 4.5;", scaled("-fx-border-width: 0 0 0 3;", 1.5));
        assertEquals("-fx-background-insets: 0, 1, 3;", scaled("-fx-background-insets: 0, 1, 2;", 1.5));
        assertEquals("-fx-border-insets: -4.5;", scaled("-fx-border-insets: -3;", 1.5));
    }

    @Test
    void aShadowScalesItsRadiusAndOffsetsButNotItsColourOrSpread() {
        assertEquals(
                "-fx-effect: dropshadow(gaussian, rgba(27, 30, 42, 0.28), 24, 0.16, 0, 24);",
                scaled("-fx-effect: dropshadow(gaussian, rgba(27, 30, 42, 0.28), 16, 0.16, 0, 16);", 1.5));
        assertEquals(
                "-fx-effect: innershadow(gaussian, -color-accent-muted, 6, 0.1, 0, 0);",
                scaled("-fx-effect: innershadow(gaussian, -color-accent-muted, 4, 0.1, 0, 0);", 1.5));
    }

    @Test
    void theFontShorthandScalesItsPixelSizeOnly() {
        assertEquals("-fx-font: 700 21px \"Inter\";", scaled("-fx-font: 700 14px \"Inter\";", 1.5));
    }

    @Test
    void commentsSelectorsAndStringsAreNotRead() {
        String css =
                "/* 14px here */\n.a:hover > .b-2 { -fx-padding: 4; /* 8px */ }\n@font-face { src: url(\"a 12px.ttf\"); }\n";
        assertEquals(
                "/* 14px here */\n.a:hover > .b-2 { -fx-padding: 6; /* 8px */ }\n@font-face { src: url(\"a 12px.ttf\"); }\n",
                UiZoomCss.scaleSheet(css, 1.5));
    }

    @Test
    void anInlineStyleScalesTheSameWay() {
        assertEquals(
                "-fx-padding: 5 10; -fx-font-size: 13.75px; -fx-opacity: 0.5",
                UiZoomCss.scaleDeclarations("-fx-padding: 4 8; -fx-font-size: 11px; -fx-opacity: 0.5", 1.25));
    }

    @Test
    void atOneHundredPercentASheetIsItself() throws IOException {
        for (Path sheet : sheets()) {
            String css = Files.readString(sheet);
            assertEquals(css, UiZoomCss.scaleSheet(css, 1.0), sheet.toString());
        }
    }

    /** Scaling changes numbers only: with every number blanked, the zoomed sheet is the sheet. */
    @Test
    void everyBundledSheetKeepsItsShapeWhenZoomed() throws IOException {
        Pattern number = Pattern.compile("-?\\d*\\.?\\d+");
        for (Path sheet : sheets()) {
            String css = Files.readString(sheet);
            String zoomed = UiZoomCss.scaleSheet(css, 1.5);
            assertEquals(
                    number.matcher(css).replaceAll("#"), number.matcher(zoomed).replaceAll("#"), sheet.toString());
        }
    }

    /** MENU-16: a menu's font sizes are pixels, so the font of the node it opened from cannot reach it. */
    @Test
    void theMenuFontSizesArePixelsAndScaleWithTheZoom() throws IOException {
        String css = Files.readString(STYLES.resolve("app.css"));
        Matcher menu = Pattern.compile(
                        "\\.context-menu,\\s*\\.context-menu \\.menu-item,\\s*\\.context-menu \\.menu-item \\.label \\{([^}]*)}")
                .matcher(css);
        assertTrue(menu.find(), "the rule that pins a menu's font");
        assertTrue(menu.group(1).contains("-fx-font-size: 14px;"), menu.group(1));
        assertTrue(menu.group(1).contains("\"Inter\""), menu.group(1));

        String zoomed = UiZoomCss.scaleSheet(css, 1.5);
        Matcher zoomedMenu = Pattern.compile(
                        "\\.context-menu,\\s*\\.context-menu \\.menu-item,\\s*\\.context-menu \\.menu-item \\.label \\{([^}]*)}")
                .matcher(zoomed);
        assertTrue(zoomedMenu.find());
        assertTrue(zoomedMenu.group(1).contains("-fx-font-size: 21px;"), zoomedMenu.group(1));
        assertFalse(
                zoomed.contains(
                        ".accelerator-text {\n    -fx-font-family: \"Inter\", \"System\", \"sans-serif\";\n    -fx-font-size: 11px;"));
        assertTrue(zoomed.contains("-fx-font-size: 16.5px;"), "the chord's 11 px at 150%");
    }

    @Test
    void lengthPropertiesAreRecognisedByName() {
        for (String yes : List.of(
                "-fx-padding",
                "-fx-label-padding",
                "-fx-background-insets",
                "-fx-border-radius",
                "-fx-pref-width",
                "-fx-max-height",
                "-fx-font-size",
                "-fx-cell-size",
                "-fx-graphic-text-gap",
                "-fx-effect",
                "-fx-size")) {
            assertTrue(UiZoomCss.isLength(yes), yes);
        }
        for (String no : List.of(
                "-fx-opacity",
                "-fx-font-weight",
                "-fx-background-color",
                "-fx-tab-size",
                "-color-bg-default",
                "-rtfx-underline-width",
                "font-size",
                "-fx-scale-x",
                "-fx-shape")) {
            assertFalse(UiZoomCss.isLength(no), no);
        }
    }

    private static List<Path> sheets() throws IOException {
        try (Stream<Path> files = Files.walk(STYLES)) {
            return files.filter(p -> p.toString().endsWith(".css")).sorted().toList();
        }
    }
}
