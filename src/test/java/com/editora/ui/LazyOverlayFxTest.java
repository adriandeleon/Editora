package com.editora.ui;

import java.util.List;

import javafx.scene.Node;
import javafx.scene.layout.AnchorPane;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the lazy feature-overlay attachment in {@link EditorBuffer}: an off-feature buffer never
 * builds the search / log / mermaid-lint / LSP / AceJump overlays (no Canvas, no subscriptions), and
 * activating a feature attaches its overlay <em>in the correct z-order</em> — just below its fixed eager
 * sibling. This is the reusable "feature ⇄ overlay" harness pattern for the buffer surface.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LazyOverlayFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static EditorBuffer newBuffer() throws Exception {
        return FxTestSupport.callOnFx(() -> new EditorBuffer());
    }

    @Test
    void offFeatureBufferBuildsNoLazyOverlays() throws Exception {
        EditorBuffer b = newBuffer();
        for (String f : List.of("searchOverlay", "logOverlay", "lintOverlay", "lspOverlay", "aceJump")) {
            assertNull(FxTestSupport.field(b, f), f + " must not be built for an off-feature buffer");
        }
    }

    @Test
    void searchActivationAttachesOverlayBelowTodo() throws Exception {
        EditorBuffer b = newBuffer();
        FxTestSupport.runOnFx(() -> b.setSearchMatches(List.of(new int[] {0, 0}), -1));

        Node search = FxTestSupport.field(b, "searchOverlay");
        Node todo = FxTestSupport.field(b, "todoOverlay");
        AnchorPane root = FxTestSupport.field(b, "root");
        assertNotNull(search, "searching attaches the search overlay");
        assertTrue(root.getChildren().contains(search), "search overlay attached to the editor pane");
        assertTrue(
                root.getChildren().indexOf(search) < root.getChildren().indexOf(todo),
                "search overlay sits below the todo overlay (original z-order preserved)");
    }

    @Test
    void aceJumpActivationAttachesOverlay() throws Exception {
        EditorBuffer b = newBuffer();
        assertNull(FxTestSupport.field(b, "aceJump"));

        FxTestSupport.runOnFx(b::startAceJump);

        Node ace = FxTestSupport.field(b, "aceJump");
        AnchorPane root = FxTestSupport.field(b, "root");
        assertNotNull(ace, "AceJump attaches its overlay on first use");
        assertTrue(root.getChildren().contains(ace), "AceJump overlay attached to the editor pane");
    }

    @Test
    void logActivationAttachesOverlayBelowWhitespace() throws Exception {
        EditorBuffer b = newBuffer();
        FxTestSupport.runOnFx(() -> {
            b.setLogViewForced(true); // make isLog() true without a file
            b.setLogHighlightEnabled(true);
        });

        Node log = FxTestSupport.field(b, "logOverlay");
        Node whitespace = FxTestSupport.field(b, "whitespace");
        AnchorPane root = FxTestSupport.field(b, "root");
        assertNotNull(log, "log highlighting on a log buffer attaches the overlay");
        assertTrue(
                root.getChildren().indexOf(log) < root.getChildren().indexOf(whitespace),
                "log overlay sits below the whitespace overlay (original z-order preserved)");
    }

    @Test
    void disablingAnUnbuiltOverlayStaysNull() throws Exception {
        EditorBuffer b = newBuffer();
        // Turning a feature OFF on a buffer that never had it must not allocate the overlay.
        FxTestSupport.runOnFx(() -> {
            b.setLogHighlightEnabled(false);
            b.setLspActive(false);
            b.clearSearchMatches();
        });
        assertNull(FxTestSupport.field(b, "logOverlay"));
        assertNull(FxTestSupport.field(b, "lspOverlay"));
        assertNull(FxTestSupport.field(b, "searchOverlay"));
    }
}
