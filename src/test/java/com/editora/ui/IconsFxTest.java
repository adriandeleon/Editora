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

    /**
     * The UI Kit line glyphs are generated: the kit authors them as {@code <symbol>}s mixing
     * {@code <path>}, {@code <circle>} and {@code <rect>}, and JavaFX takes only path data — so circles
     * and rounded rects were rewritten as arc commands. A malformed arc is precisely what this parser
     * swallows silently, leaving an invisible icon, so every converted glyph is asserted here.
     */
    @Test
    void everyKitLineGlyphRendersANonEmptyShape() throws Exception {
        assertRenders("fileSheet", Icons::fileSheet);
        assertRenders("template", Icons::template);
        assertRenders("open", Icons::open);
        assertRenders("openFolder", Icons::openFolder);
        assertRenders("saveAs", Icons::saveAs);
        assertRenders("newFile", Icons::newFile);
        assertRenders("newFolder", Icons::newFolder);
        assertRenders("project", Icons::project);
        assertRenders("closeTab", Icons::closeTab);
        assertRenders("save", Icons::save);
        assertRenders("recent", Icons::recent); // circle → arc
        assertRenders("undo", Icons::undo);
        assertRenders("redo", Icons::redo);
        assertRenders("cut", Icons::cut); // two circles → arcs
        assertRenders("copy", Icons::copy); // rounded rect → arcs
        assertRenders("paste", Icons::paste); // two rounded rects
        assertRenders("find", Icons::find);
        assertRenders("findInFiles", Icons::findInFiles);
        assertRenders("palette", Icons::palette); // keyboard: key marks are 0.25-unit segments
        assertRenders("run", Icons::run);
        assertRenders("debug", Icons::debug);
        assertRenders("stopSquare", Icons::stopSquare);
        assertRenders("splitVertical", Icons::splitVertical);
        assertRenders("splitHorizontal", Icons::splitHorizontal);
        assertRenders("terminal", Icons::terminal);
        assertRenders("simpleMode", Icons::simpleMode);
        assertRenders("settings", Icons::settings);
        assertRenders("about", Icons::about);
        assertRenders("quit", Icons::quit);
        assertRenders("structure", Icons::structure);
        assertRenders("bookmark", Icons::bookmark);
        assertRenders("git", Icons::git);
        assertRenders("notes", Icons::notes);
        assertRenders("htmlPreview", Icons::htmlPreview);
        assertRenders("warning", Icons::warning);
        assertRenders("check", Icons::check);
        assertRenders("pin", Icons::pin);
        assertRenders("gitLog", Icons::gitLog); // three circles → arcs
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
