package com.editora.ui;

import java.util.function.Supplier;

import javafx.geometry.Bounds;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.shape.SVGPath;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards that the toolbar/stripe glyphs actually PARSE and render — JavaFX's {@link SVGPath} parser is
 * stricter than browsers (it can't read SVGO's packed elliptical-arc flags, e.g. {@code a1 1 0 000-.5}),
 * so a path vendored from Simple Icons in that compact form fails silently and renders an empty (invisible)
 * shape. The build-tool stripe icons (Cargo/Go/Gradle) hit exactly that and showed blank. This asserts each
 * build-tool glyph produces a non-empty, finite bounding box, which a failed parse would not.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IconsFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void everyBuildToolGlyphRendersANonEmptyShape() throws Exception {
        assertRenders("maven", Icons::maven);
        assertRenders("npm", Icons::npm);
        assertRenders("cargo", Icons::cargo);
        assertRenders("go", Icons::go);
        assertRenders("gradle", Icons::gradle);
    }

    private static void assertRenders(String name, Supplier<Node> glyph) throws Exception {
        Bounds b = FxTestSupport.callOnFx(() -> {
            Node node = glyph.get();
            // Icons.of wraps the SVGPath in a Group; a failed parse leaves the path (and Group) empty.
            SVGPath svg = (SVGPath) ((Group) node).getChildren().get(0);
            return svg.getBoundsInLocal();
        });
        assertTrue(
                b.getWidth() > 0 && b.getHeight() > 0 && !Double.isNaN(b.getWidth()) && !Double.isNaN(b.getHeight()),
                name + " glyph parsed to an empty shape (bounds " + b.getWidth() + "x" + b.getHeight()
                        + ") — its SVG path likely uses packed arc flags JavaFX can't parse");
    }
}
