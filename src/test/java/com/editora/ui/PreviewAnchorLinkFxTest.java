package com.editora.ui;

import java.util.function.Consumer;

import javafx.stage.Stage;

import com.editora.editor.EditorBuffer;
import com.editora.editor.EditorBuffer.MarkdownViewMode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A link to a heading clicked in the preview takes the preview there. With only the preview showing that is
 * the whole effect a reader can see; the entries of a generated table of contents are such links.
 */
@Tag("fx")
class PreviewAnchorLinkFxTest {

    private static FxWindowFixture fx;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        FxTestSupport.runOnFx(() -> {
            Stage stage = FxTestSupport.field(fx.controller, "stage");
            stage.setWidth(1000);
            stage.setHeight(700);
            stage.show();
        });
        FxTestSupport.drainFx();
    }

    @AfterAll
    static void dispose() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private static String document() {
        StringBuilder md = new StringBuilder("# Contents\n\n- [Marker 30](#marker-30)\n- [Nowhere](#nowhere)\n\n");
        for (int i = 1; i <= 40; i++) {
            md.append("## Marker ").append(i).append("\n\n");
            md.append("Paragraph ").append(i).append(" line one.\n\nAnd a second paragraph.\n\n");
        }
        return md.toString();
    }

    private static double topLine(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            Object preview = FxTestSupport.field(b, "markdownPreview");
            return preview == null ? -1.0 : (Double) FxTestSupport.call(preview, "topLine", new Class<?>[] {});
        });
    }

    private static void settle() throws Exception {
        for (int i = 0; i < 4; i++) {
            FxTestSupport.drainFx();
            Thread.sleep(120);
        }
        FxTestSupport.drainFx();
    }

    @Test
    void anAnchorLinkClickedInThePreviewScrollsThePreviewAndMovesTheCaret() throws Exception {
        String doc = document();
        int target = doc.substring(0, doc.indexOf("## Marker 30\n")).split("\n", -1).length - 1;
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setContent(doc);
            buffer.setLanguageOverride("markdown");
            FxTestSupport.call(
                    fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, buffer, true);
            buffer.getArea().moveTo(0);
            buffer.setMarkdownViewMode(MarkdownViewMode.PREVIEW);
            return buffer;
        });
        // Wait for the first render: until then there is nothing to scroll.
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (!FxTestSupport.callOnFx(() -> b.scrollPreviewToAnchor("contents"))) {
            assertTrue(System.nanoTime() < deadline, "the preview never rendered");
            Thread.sleep(50);
        }
        settle();
        assertTrue(topLine(b) < 3, "starts at the top: " + topLine(b));

        // What a click on a rendered link does: the buffer's link handler gets the link's destination.
        Consumer<String> click = FxTestSupport.field(b, "openUrlHandler");
        FxTestSupport.runOnFx(() -> click.accept("#marker-30"));
        settle();
        assertEquals(target, topLine(b), 1.0, "the preview is at the heading");
        assertEquals(
                target,
                (int) FxTestSupport.callOnFx(() -> b.getArea().getCurrentParagraph()),
                "and so is the caret, for when the editor is shown again");

        FxTestSupport.runOnFx(() -> click.accept("#nowhere"));
        settle();
        assertEquals(tr("status.link.noAnchor", "#nowhere"), SaveDecisionsFxTest.lastMessage(fx));
        assertEquals(target, topLine(b), 1.0, "and nothing moved");
        FxTestSupport.runOnFx(b::markClean);
    }
}
