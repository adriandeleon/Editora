package com.editora.ui;

import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPIKE for #824 — measures whether a node can be injected into a {@link TextFlow} to displace the glyphs
 * after it <em>without</em> shifting the character indices JavaFX reports, which is what inline inlay hints
 * would need. Not a behavioural test of Editora; it pins the platform constraint the design rests on.
 */
@Tag("fx")
class InlayPlacementSpikeFxTest {

    @org.junit.jupiter.api.BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** Lays out a TextFlow off-screen and returns it, so rangeShape/hitTest have real geometry. */
    private static TextFlow laidOut(javafx.scene.Node... children) {
        TextFlow flow = new TextFlow(children);
        flow.setPrefWidth(2000); // no wrapping: everything stays on one line
        StackPane root = new StackPane(flow);
        new Scene(root, 2000, 200);
        root.applyCss();
        root.layout();
        return flow;
    }

    private static double startX(TextFlow flow, int from, int to) {
        var shape = flow.rangeShape(from, to);
        assertTrue(shape.length > 0, "expected a non-empty range shape");
        return ((javafx.scene.shape.MoveTo) shape[0]).getX();
    }

    @Test
    void anInjectedNodeDisplacesTheTextAfterIt() throws Exception {
        FxTestSupport.runOnFx(() -> {
            // Where 't' of "true" sits with no hint: index 5.
            TextFlow plain = laidOut(new Text("copy("), new Text("true);"));
            double plainT = startX(plain, 5, 6);

            // With the hint injected, the node itself takes index 5 and 't' has moved to index 6 —
            // which is exactly the desync this spike exists to measure. Follow the glyph, not the index.
            TextFlow withHint = laidOut(new Text("copy("), new Label("overwrite:"), new Text("true);"));
            double hintedT = startX(withHint, 6, 7);

            // The displacement is the whole point of doing this in the layout rather than on a canvas.
            assertTrue(hintedT > plainT, "an injected node must push the following glyphs right");
            // ...and at the *same* index the hinted flow now reports the hint, not the text.
            assertEquals(plainT, startX(withHint, 5, 6), 1e-6, "index 5 became the hint's own position");
        });
    }

    @Test
    void anInjectedNonTextNodeCostsExactlyOneCharacterOfIndex() throws Exception {
        FxTestSupport.runOnFx(() -> {
            // "copy(" = chars 0..4, so char 5 is 't' of "true" when there is no injected node.
            TextFlow plain = laidOut(new Text("copy("), new Text("true);"));
            double plainT = startX(plain, 5, 6);

            TextFlow withHint = laidOut(new Text("copy("), new Label("overwrite:"), new Text("true);"));
            // If the Label were index-free, index 5 would still be 't'. Measure where index 5 and 6 land.
            double idx5 = startX(withHint, 5, 6);
            double idx6 = startX(withHint, 6, 7);
            double hintWidth = idx6 - idx5;

            assertNotEquals(plainT, idx6, 1e-6, "sanity: the hinted flow's geometry differs from the plain one");
            assertTrue(hintWidth > 0, "index 5 must span the injected node itself, i.e. it consumed an index");
        });
    }

    @Test
    void anInjectedTextNodeCostsItsFullLengthInIndex() throws Exception {
        FxTestSupport.runOnFx(() -> {
            TextFlow flow = laidOut(new Text("copy("), new Text("overwrite:"), new Text("true);"));
            // "copy(" (5) + "overwrite:" (10) → 't' of "true" is at index 15, not 5.
            var hit = flow.hitTest(new javafx.geometry.Point2D(startX(flow, 15, 16) + 1, 8));
            assertEquals(15, hit.getCharIndex(), "a Text child contributes every one of its characters");
        });
    }

    @Test
    void anUnmanagedChildIsIndexFreeButAlsoDisplacesNothing() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Label hint = new Label("overwrite:");
            hint.setManaged(false); // the only way to keep it out of the index...
            TextFlow flow = laidOut(new Text("copy("), hint, new Text("true);"));
            TextFlow plain = laidOut(new Text("copy("), new Text("true);"));

            // ...and it buys nothing: the glyphs after it do not move, which is the whole requirement.
            assertEquals(startX(plain, 5, 6), startX(flow, 5, 6), 1e-6);
        });
    }
}
