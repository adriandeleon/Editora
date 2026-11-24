package com.editora.ui;

import java.util.List;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Inlay hints end-to-end through a real {@link EditorBuffer} (#824): they must render <em>at their column</em>
 * and leave the document alone.
 *
 * <p>The fork already proves the index translation ({@code InlayIndexTest}, {@code InlayTest}); what is
 * asserted here is Editora's own wiring — that {@code setInlayHints} reaches the area's inlay factory, that
 * clearing removes them again, and above all that nothing about the buffer's text or selection changes,
 * since a hint leaking into the document would corrupt a file on save.
 */
@Tag("fx")
class InlayHintRenderFxTest {

    private static final String TEXT = "copy(true);";

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static EditorBuffer buffer() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent(TEXT);
            return b;
        });
    }

    @Test
    void hintsNeverEnterTheDocument() throws Exception {
        EditorBuffer b = buffer();
        FxTestSupport.runOnFx(() -> b.setInlayHints(List.of(new EditorBuffer.InlayHint(0, 5, "overwrite:"))));

        assertEquals(TEXT, FxTestSupport.callOnFx(b::getContent), "the hint must not reach the document");
        assertEquals(TEXT.length(), FxTestSupport.callOnFx(() -> b.getContent().length()));
    }

    @Test
    void aSelectionAcrossAHintYieldsOnlyDocumentText() throws Exception {
        EditorBuffer b = buffer();
        FxTestSupport.runOnFx(() -> {
            b.setInlayHints(List.of(new EditorBuffer.InlayHint(0, 5, "overwrite:")));
            b.getArea().selectAll();
        });
        // The label is scenery: copying the line must not pick it up.
        assertEquals(TEXT, FxTestSupport.callOnFx(() -> b.getArea().getSelectedText()));
    }

    /**
     * Every typing pause asks the server for the hints again, and most answers are what is shown: such an answer
     * does not refresh the areas, which re-laid out every line that has a hint (perf review 2026-11-23 L2).
     */
    @Test
    void theSameHintsAgainRefreshNothing() throws Exception {
        EditorBuffer b = buffer();
        int[] changes = FxTestSupport.callOnFx(() -> {
            b.setInlayHints(List.of(new EditorBuffer.InlayHint(0, 5, "overwrite:")));
            int[] count = {0};
            b.getArea().inlayFactoryProperty().addListener((o, was, now) -> count[0]++);
            b.setInlayHints(List.of(new EditorBuffer.InlayHint(0, 5, "overwrite:")));
            int same = count[0];
            b.setInlayHints(List.of(new EditorBuffer.InlayHint(0, 5, "other:")));
            return new int[] {same, count[0]};
        });
        assertEquals(0, changes[0], "an equal answer leaves the areas alone");
        assertTrue(changes[1] > 0, "a different one refreshes them");
    }

    /**
     * A different answer swaps the factory once, never through null: the null in between cleared every hinted line
     * and the real factory rebuilt it, two re-layouts per line per answer (perf review 2026-11-23 E6).
     */
    @Test
    void aNewAnswerRefreshesOnceWithoutANullFactory() throws Exception {
        EditorBuffer b = buffer();
        List<Object> seen = FxTestSupport.callOnFx(() -> {
            b.setInlayHints(List.of(new EditorBuffer.InlayHint(0, 5, "overwrite:")));
            List<Object> values = new java.util.ArrayList<>();
            b.getArea().inlayFactoryProperty().addListener((o, was, now) -> values.add(now == null ? "null" : now));
            b.setInlayHints(List.of(new EditorBuffer.InlayHint(0, 5, "other:")));
            return values;
        });
        assertEquals(1, seen.size(), "one change per answer: " + seen);
        assertTrue(!"null".equals(seen.get(0)), "never through a null factory");
        assertEquals(
                "other:",
                FxTestSupport.callOnFx(
                        () -> b.getArea().getInlayFactory().apply(0).get(0).getText()));
    }

    @Test
    void clearingRemovesThem() throws Exception {
        EditorBuffer b = buffer();
        FxTestSupport.runOnFx(() -> {
            b.setInlayHints(List.of(new EditorBuffer.InlayHint(0, 5, "overwrite:")));
            b.setInlayHints(null);
        });
        assertEquals(TEXT, FxTestSupport.callOnFx(b::getContent));
        assertTrue(FxTestSupport.callOnFx(() -> b.getArea().getInlayFactory() == null
                || b.getArea().getInlayFactory().apply(0) == null
                || b.getArea().getInlayFactory().apply(0).isEmpty()));
    }

    @Test
    void theFactoryReportsHintsOnTheRightLineOnly() throws Exception {
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer x = new EditorBuffer();
            x.setContent("first();\nsecond(true);");
            return x;
        });
        FxTestSupport.runOnFx(() -> b.setInlayHints(List.of(new EditorBuffer.InlayHint(1, 7, "flag:"))));

        assertEquals(0, FxTestSupport.callOnFx(() -> {
            var on0 = b.getArea().getInlayFactory().apply(0);
            return on0 == null ? 0 : on0.size();
        }));
        assertEquals(
                1,
                FxTestSupport.callOnFx(
                        () -> b.getArea().getInlayFactory().apply(1).size()));
        assertEquals(
                7,
                FxTestSupport.callOnFx(
                        () -> b.getArea().getInlayFactory().apply(1).get(0).getColumn()));
    }
}
