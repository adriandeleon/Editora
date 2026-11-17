package com.editora.ui;

import java.nio.file.Path;

import com.editora.editor.EditorBuffer;
import com.editora.editor.TypewriterMode;
import com.editora.editor.markdown.MarkdownFormatting;
import com.editora.editor.markdown.MarkdownFormatting.Outcome;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Format Document / Selection, heading numbers and typewriter mode on a real buffer. */
@Tag("fx")
@org.junit.jupiter.api.extension.ExtendWith(OffFxInPlace.class) // the formatter answers in place
class MarkdownFormattingFxTest {

    /** A command's outcome, landed in place. */
    private static Outcome now(
            java.util.function.BiConsumer<EditorBuffer, java.util.function.Consumer<Outcome>> c, EditorBuffer b) {
        Outcome[] out = {null};
        c.accept(b, o -> out[0] = o);
        assertTrue(out[0] != null, "answered in place");
        return out[0];
    }

    private static final String MESSY = "Title\n=====\ntext   \n* a\n- b\n\n## Part\nmore\n";

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static EditorBuffer buffer(String name, String text) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setPath(Path.of("/tmp/format", name).toAbsolutePath());
            b.setContent(text);
            b.getArea().getUndoManager().forgetHistory();
            return b;
        });
    }

    @Test
    void formatDocumentIsOneUndoStepAndKeepsTheCaretOnItsLine() throws Exception {
        EditorBuffer b = buffer("a.md", MESSY);
        FxTestSupport.runOnFx(() -> b.getArea().moveTo(7, 2)); // on "more"
        assertEquals(Outcome.CHANGED, FxTestSupport.callOnFx(() -> now(MarkdownFormatting::formatDocument, b)));
        String formatted = "# Title\n\ntext\n\n* a\n* b\n\n## Part\n\nmore\n";
        assertEquals(formatted, FxTestSupport.callOnFx(() -> b.getArea().getText()));
        assertEquals(9, (int) FxTestSupport.callOnFx(() -> b.getArea().getCurrentParagraph()));
        assertEquals(2, (int) FxTestSupport.callOnFx(() -> b.getArea().getCaretColumn()));
        assertEquals(Outcome.UNCHANGED, FxTestSupport.callOnFx(() -> now(MarkdownFormatting::formatDocument, b)));
        FxTestSupport.runOnFx(() -> b.getArea().undo());
        assertEquals(MESSY, FxTestSupport.callOnFx(() -> b.getArea().getText()));
        assertFalse(FxTestSupport.callOnFx(() -> b.getArea().isUndoAvailable()), "one step");
    }

    @Test
    void formatSelectionLeavesTheOtherLines() throws Exception {
        EditorBuffer b = buffer("a.md", MESSY);
        FxTestSupport.runOnFx(() -> b.getArea().selectRange(3, 0, 5, 0)); // the two list lines
        assertEquals(Outcome.CHANGED, FxTestSupport.callOnFx(() -> now(MarkdownFormatting::formatSelection, b)));
        assertEquals(
                "Title\n=====\ntext   \n\n* a\n* b\n\n## Part\nmore\n",
                FxTestSupport.callOnFx(() -> b.getArea().getText()));
    }

    @Test
    void headingNumbersAreAddedUpdatedAndRemoved() throws Exception {
        EditorBuffer b = buffer("a.md", "# T\n\n## A\n\n## B\n");
        assertEquals(Outcome.CHANGED, FxTestSupport.callOnFx(() -> now(MarkdownFormatting::numberHeadings, b)));
        assertEquals(
                "# T\n\n## 1. A\n\n## 2. B\n",
                FxTestSupport.callOnFx(() -> b.getArea().getText()));
        assertEquals(Outcome.UNCHANGED, FxTestSupport.callOnFx(() -> now(MarkdownFormatting::numberHeadings, b)));
        assertEquals(Outcome.CHANGED, FxTestSupport.callOnFx(() -> now(MarkdownFormatting::removeHeadingNumbers, b)));
        assertEquals(
                "# T\n\n## A\n\n## B\n",
                FxTestSupport.callOnFx(() -> b.getArea().getText()));
        FxTestSupport.runOnFx(() -> {
            b.getArea().undo();
            b.getArea().undo();
        });
        assertEquals(
                "# T\n\n## A\n\n## B\n",
                FxTestSupport.callOnFx(() -> b.getArea().getText()));
    }

    @Test
    void otherBuffersReadOnlyAndPreviewOnlyAreRefused() throws Exception {
        EditorBuffer java = buffer("A.java", MESSY);
        assertEquals(Outcome.REFUSED, FxTestSupport.callOnFx(() -> now(MarkdownFormatting::formatDocument, java)));
        EditorBuffer readOnly = buffer("ro.md", MESSY);
        FxTestSupport.runOnFx(() -> readOnly.setReadOnly(true));
        assertEquals(Outcome.REFUSED, FxTestSupport.callOnFx(() -> now(MarkdownFormatting::formatDocument, readOnly)));
        assertEquals(Outcome.REFUSED, FxTestSupport.callOnFx(() -> now(MarkdownFormatting::numberHeadings, readOnly)));
        assertEquals(MESSY, FxTestSupport.callOnFx(() -> readOnly.getArea().getText()));
        assertEquals(Outcome.REFUSED, FxTestSupport.callOnFx(() -> now(MarkdownFormatting::formatDocument, null)));
    }

    @Test
    void typewriterModeIsInstalledAndRemovedPerEditor() throws Exception {
        EditorBuffer b = buffer("a.md", "line\n".repeat(200));
        assertFalse(FxTestSupport.callOnFx(() -> TypewriterMode.isOn(b.getArea())));
        FxTestSupport.runOnFx(() -> {
            TypewriterMode.apply(b, true);
            TypewriterMode.apply(b, true); // idempotent: one listener
        });
        assertTrue(FxTestSupport.callOnFx(() -> TypewriterMode.isOn(b.getArea())));
        FxTestSupport.runOnFx(() -> b.getArea().moveTo(100, 0)); // not in a scene: nothing to centre, no error
        FxTestSupport.runOnFx(() -> {});
        FxTestSupport.runOnFx(() -> TypewriterMode.apply(b, false));
        assertFalse(FxTestSupport.callOnFx(() -> TypewriterMode.isOn(b.getArea())));
        TypewriterMode.apply((EditorBuffer) null, true);
    }
}
