package com.editora.ui;

import java.util.List;

import javafx.scene.Node;
import javafx.scene.layout.AnchorPane;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every overlay drawn on the text shares the scroll pane's rectangle — with the minimap shown <em>and</em>
 * hidden. Only the whitespace overlay used to follow the minimap toggle; the rest kept the minimap's 90 px
 * right inset while it was hidden, so search highlights, squiggles and note marks stopped short of the edge.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OverlayAnchorsFxTest {

    /** The overlays built with the buffer. */
    private static final List<String> EAGER =
            List.of("whitespace", "spellOverlay", "mdLintOverlay", "inlineValues", "todoOverlay", "noteOverlay");

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static double rightAnchor(EditorBuffer b, String field) {
        Node node = FxTestSupport.field(b, field);
        assertNotNull(node, field + " is attached");
        return AnchorPane.getRightAnchor(node);
    }

    @Test
    void theOverlaysFollowTheTextRectangleWhenTheMinimapIsToggled() throws Exception {
        FxTestSupport.runOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent("alpha beta\n");
            b.getNode();

            b.setMinimapVisible(true);
            double withMinimap = rightAnchor(b, "scrollPane");
            assertTrue(withMinimap > 0, "precondition: the minimap takes room on the right");
            for (String overlay : EAGER) {
                assertEquals(withMinimap, rightAnchor(b, overlay), overlay + " stops where the text does");
            }

            b.setMinimapVisible(false);
            assertEquals(0d, rightAnchor(b, "scrollPane"), "precondition: the text now reaches the edge");
            for (String overlay : EAGER) {
                assertEquals(0d, rightAnchor(b, overlay), overlay + " reaches the edge with the text");
            }

            // An overlay first attached while the minimap is hidden must start on the same rectangle…
            b.setSearchMatches(List.of(new int[] {0, 5}), 0);
            assertEquals(0d, rightAnchor(b, "searchOverlay"));

            // …and move with everything else when it comes back.
            b.setMinimapVisible(true);
            assertEquals(withMinimap, rightAnchor(b, "searchOverlay"));
            for (String overlay : EAGER) {
                assertEquals(withMinimap, rightAnchor(b, overlay), overlay);
            }
        });
    }
}
