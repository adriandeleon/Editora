package com.editora.editor.structured;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import com.editora.editor.EditorBuffer;
import com.editora.editor.EditorBuffer.MarkdownViewMode;
import com.editora.editor.EditorFx;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A preview's parse error names its line and column and jumps there (XML review C15). */
@Tag("fx")
class ParseErrorViewFxTest {

    @BeforeAll
    static void boot() throws Exception {
        EditorFx.boot();
    }

    @Test
    void theErrorLinksToItsPosition() throws Exception {
        List<int[]> jumps = new ArrayList<>();
        Node node = EditorFx.callFx(() ->
                ParseErrorView.of("ETagRequired", 2, 7, (l, c) -> jumps.add(new int[] {l, c}), "svg-error"));
        VBox box = assertInstanceOf(VBox.class, node);
        Hyperlink link = assertInstanceOf(Hyperlink.class, box.getChildren().get(0));
        assertEquals("Line 2, column 7", link.getText());
        assertEquals("ETagRequired", ((Label) box.getChildren().get(1)).getText());
        EditorFx.onFx(link::fire);
        assertEquals(1, jumps.size());
        assertEquals(2, jumps.get(0)[0]);
        assertEquals(7, jumps.get(0)[1]);
        assertInstanceOf(
                Label.class, EditorFx.callFx(() -> ParseErrorView.of("no place", 0, 0, null, "x")), "no position");
        assertEquals("Line 4", ParseErrorView.position(4, 0));
    }

    @Test
    void jumpingShowsTheEditorAndPlacesTheCaret() throws Exception {
        int[] caret = EditorFx.callFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("xml");
            b.setContent("<note>\n  <to>Tove</from>\n</note>\n");
            b.setStructuredPreviewEnabled(true);
            b.setMarkdownViewMode(MarkdownViewMode.PREVIEW);
            ParseErrorView.jump(b, 2, 15);
            assertTrue(b.getMarkdownViewMode() != MarkdownViewMode.PREVIEW, "the editor is on screen again");
            return new int[] {b.getArea().getCurrentParagraph(), b.getArea().getCaretColumn()};
        });
        assertEquals(1, caret[0]);
        assertEquals(14, caret[1]);
        int[] clamped = EditorFx.callFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent("<a>\n");
            ParseErrorView.jump(b, 99, 99);
            return new int[] {b.getArea().getCurrentParagraph(), b.getArea().getCaretColumn()};
        });
        assertEquals(1, clamped[0], "past the end clamps to the last line");
        assertEquals(0, clamped[1]);
    }
}
