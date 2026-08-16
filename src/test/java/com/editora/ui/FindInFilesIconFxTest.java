package com.editora.ui;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.shape.SVGPath;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Find in Files is one feature reached two ways — a toolbar button and a tool window — and both must show
 * the same glyph.
 *
 * <p>They drifted: the tool window was registered with the plain magnifier {@code Icons.find()}, which
 * belongs to the <em>in-file</em> find bar, while the toolbar carried {@code Icons.findInFiles()} (a
 * magnifier over document lines). Asserted as "these two agree" rather than "the tool window uses glyph X",
 * so the invariant survives a future change of which glyph Find in Files uses.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FindInFilesIconFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** The path data behind an {@link Icons} glyph, which wraps its {@link SVGPath} in a {@link Group}. */
    private static String pathOf(Node icon) {
        assertNotNull(icon, "no icon");
        Node inner = icon instanceof Group g && !g.getChildren().isEmpty()
                ? g.getChildren().get(0)
                : icon;
        return ((SVGPath) inner).getContent();
    }

    @Test
    void theToolbarButtonAndTheToolWindowShowTheSameGlyph() throws Exception {
        FxWindowFixture fx = FxWindowFixture.create();
        try {
            FxTestSupport.runOnFx(() -> {
                Button button = FxTestSupport.field(fx.controller, "findInFilesButton");
                ToolWindow tw = FxTestSupport.field(fx.controller, "searchToolWindow");

                assertEquals(
                        pathOf(button.getGraphic()),
                        pathOf(tw.createIcon()),
                        "the Find in Files toolbar button and tool window show different glyphs");
            });
        } finally {
            fx.dispose();
        }
    }

    /**
     * And that shared glyph is not the in-file find bar's. Without this the test above would still pass if
     * both surfaces regressed to the plain magnifier together.
     */
    @Test
    void thatGlyphIsNotThePlainInFileFindMagnifier() throws Exception {
        FxTestSupport.runOnFx(() -> assertNotEquals(pathOf(Icons.find()), pathOf(Icons.findInFiles())));
    }
}
