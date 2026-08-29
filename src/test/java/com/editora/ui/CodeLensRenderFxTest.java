package com.editora.ui;

import java.util.Map;

import javafx.scene.Scene;
import javafx.scene.layout.StackPane;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Code lenses end to end in a real buffer: the row appears, it makes the line taller, and turning the
 * feature off removes it.
 *
 * <p>The height assertion is the one that matters. A lens that renders but does not grow the row would
 * overlap the code above it, and the model-only version of this test would pass — the same gap that let
 * sticky scroll ship invisible twice.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CodeLensRenderFxTest {

    private FxWindowFixture fx;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private record Laid(EditorBuffer buffer, javafx.stage.Stage stage) {}

    private Laid laidOut(Map<Integer, String> lenses) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("java");
            b.setContent("class C {\n    void a() {\n    }\n    void b() {\n    }\n}\n");
            b.setCodeLensEnabled(true);
            b.setCodeLenses(lenses);
            StackPane host = new StackPane(b.getNode());
            javafx.stage.Stage stage = new javafx.stage.Stage();
            stage.setScene(new Scene(host, 900, 600));
            stage.show();
            host.applyCss();
            host.layout();
            return new Laid(b, stage);
        });
    }

    private static double rowHeight(EditorBuffer b, int paragraph) throws Exception {
        return FxTestSupport.callOnFx(() -> b.getArea()
                .getParagraphBoundsOnScreen(paragraph)
                .map(bounds -> bounds.getHeight())
                .orElse(-1.0));
    }

    @Test
    void aLensGivesItsLineATallerRow() throws Exception {
        Laid laid = laidOut(Map.of(1, "3 references"));
        double lensed = rowHeight(laid.buffer(), 1);
        double plain = rowHeight(laid.buffer(), 3);
        FxTestSupport.runOnFxUnchecked(() -> laid.stage().hide());
        assertTrue(lensed > plain, "the lensed row (" + lensed + ") must be taller than a plain one (" + plain + ")");
    }

    @Test
    void theDocumentIsUnchangedByALens() throws Exception {
        // A decoration annotates a file; it must never alter the text that gets saved.
        String expected = "class C {\n    void a() {\n    }\n    void b() {\n    }\n}\n";
        Laid laid = laidOut(Map.of(1, "3 references"));
        String text = FxTestSupport.callOnFx(() -> laid.buffer().getContent());
        FxTestSupport.runOnFxUnchecked(() -> laid.stage().hide());
        assertEquals(expected, text);
    }

    @Test
    void clearingTheLensesRestoresThePlainRowHeight() throws Exception {
        Laid laid = laidOut(Map.of(1, "3 references"));
        double lensed = rowHeight(laid.buffer(), 1);
        FxTestSupport.runOnFxUnchecked(() -> laid.buffer().setCodeLenses(Map.of()));
        FxTestSupport.runOnFxUnchecked(() -> {
            laid.buffer().getNode().applyCss();
            laid.buffer().getNode().layout();
        });
        double cleared = rowHeight(laid.buffer(), 1);
        FxTestSupport.runOnFxUnchecked(() -> laid.stage().hide());
        assertTrue(cleared < lensed, "clearing must give the row its height back; " + cleared + " vs " + lensed);
    }

    @Test
    void disablingClearsWhatIsShown() throws Exception {
        Laid laid = laidOut(Map.of(1, "3 references"));
        FxTestSupport.runOnFxUnchecked(() -> laid.buffer().setCodeLensEnabled(false));
        String still = FxTestSupport.callOnFx(
                () -> (String) FxTestSupport.invokeWith(laid.buffer(), "codeLensAt", int.class, 1));
        FxTestSupport.runOnFxUnchecked(() -> laid.stage().hide());
        assertNull(still, "disabling must clear the lenses, not leave them frozen on screen");
    }
}
