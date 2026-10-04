package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.Scene;
import javafx.scene.layout.VBox;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** The debugger's execution-line highlight on a real {@link EditorBuffer}, through edits and folds. */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExecutionLineFxTest {

    private static final String SRC = "class A {\n    void m() {\n        a();\n        b();\n        c();\n    }\n"
            + "    void n() {\n        d();\n    }\n}\n";

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static EditorBuffer buffer() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("java");
            b.setContent(SRC);
            new Scene(new VBox(b.getNode()), 800, 600);
            b.getFoldManager().setLanguage("java");
            b.getFoldManager().recompute();
            return b;
        });
    }

    private static List<Integer> highlighted(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            List<Integer> out = new ArrayList<>();
            for (int i = 0; i < b.getArea().getParagraphs().size(); i++) {
                if (b.getArea().getParagraph(i).getParagraphStyle().contains("exec-line")) {
                    out.add(i);
                }
            }
            return out;
        });
    }

    private static List<Integer> hidden(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            List<Integer> out = new ArrayList<>();
            for (int i = 0; i < b.getArea().getParagraphs().size(); i++) {
                if (b.getArea().isFolded(i)) {
                    out.add(i);
                }
            }
            return out;
        });
    }

    @Test
    void theHighlightIsRemovedAfterALineWasTypedAboveItWhilePaused() throws Exception {
        EditorBuffer b = buffer();
        FxTestSupport.runOnFx(() -> {
            b.setExecutionLine(3);
            b.getArea().insertText(0, "// fixing it\n"); // the highlighted statement is line 4 now
        });
        assertEquals(List.of(4), highlighted(b), "the style travels with its paragraph");
        FxTestSupport.runOnFx(b::clearExecutionLine);
        assertEquals(List.of(), highlighted(b), "Continue/Stop must not leave the highlight behind");
    }

    @Test
    void movingToAnotherLineAfterAnEditLeavesOneHighlight() throws Exception {
        EditorBuffer b = buffer();
        FxTestSupport.runOnFx(() -> {
            b.setExecutionLine(3);
            b.getArea().insertText(0, "// fixing it\n");
            b.setExecutionLine(7);
        });
        assertEquals(List.of(7), highlighted(b));
    }

    @Test
    void aStopInsideACollapsedFoldRevealsTheWholeFold() throws Exception {
        EditorBuffer b = buffer();
        FxTestSupport.runOnFx(() ->
                b.getFoldManager().fold(b.getFoldManager().regionStartingAt(1).orElseThrow()));
        FxTestSupport.drainFx();
        assertFalse(hidden(b).isEmpty(), "m() is collapsed");

        // Writing the paragraph style over the fold's own entry un-hid only this line and left the rest
        // hidden with no fold the chevron could expand.
        FxTestSupport.runOnFx(() -> b.setExecutionLine(3));
        assertEquals(List.of(), hidden(b));
        assertEquals(List.of(3), highlighted(b));
    }

    @Test
    void theHighlightDoesNotEraseTheOtherStylesOfItsLine() throws Exception {
        EditorBuffer b = buffer();
        FxTestSupport.runOnFx(() -> {
            // The header of a collapsed fold is shaded through the same paragraph-style list.
            b.getFoldManager().fold(b.getFoldManager().regionStartingAt(6).orElseThrow());
            b.setExecutionLine(6);
        });
        List<String> styles = FxTestSupport.callOnFx(
                () -> List.copyOf(b.getArea().getParagraph(6).getParagraphStyle()));
        assertEquals(List.of("fold-header-line", "exec-line"), styles);
        FxTestSupport.runOnFx(b::clearExecutionLine);
        assertEquals(
                List.of("fold-header-line"),
                FxTestSupport.callOnFx(
                        () -> List.copyOf(b.getArea().getParagraph(6).getParagraphStyle())));
        assertFalse(hidden(b).isEmpty(), "n() is still collapsed");
    }
}
