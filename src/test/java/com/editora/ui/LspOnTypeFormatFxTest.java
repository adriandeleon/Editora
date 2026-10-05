package com.editora.ui;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import javafx.scene.input.KeyEvent;

import com.editora.editor.EditorBuffer;
import com.editora.editor.LspTextEdit;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * On-type formatting is requested for — and judged against — the line <em>with</em> the typed character.
 *
 * <p>The request used to leave from the KEY_TYPED filter, before the character was inserted: it named a
 * position in the old line, and the answer was then rejected as stale because the line no longer equalled
 * the pre-insert snapshot. By construction, no answer could ever be applied.
 */
@Tag("fx")
class LspOnTypeFormatFxTest {

    @Test
    void theAnswerToATypedTriggerIsApplied() throws Exception {
        FxTestSupport.bootToolkit();
        FxWindowFixture fx = FxWindowFixture.create();
        try {
            AtomicReference<String> lineAtRequest = new AtomicReference<>();
            AtomicReference<int[]> asked = new AtomicReference<>();
            // Line 1 is over-indented by 12 spaces; the "server" says it should be 4.
            List<LspTextEdit> answer = List.of(new LspTextEdit(1, 0, 1, 12, "    "));
            EditorBuffer b = FxTestSupport.callOnFx(() -> {
                EditorBuffer buf = new EditorBuffer();
                buf.setLanguageOverride("java");
                FxTestSupport.call(
                        fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, buf, true);
                buf.setContent("class A {\n            foo()\n}\n");
                buf.setLspActive(true);
                buf.setOnTypeFormattingEnabled(true);
                buf.setLspOnTypeTriggers(Set.of(';'));
                buf.setLspOnTypeFormatter((line, character, ch, cb) -> {
                    lineAtRequest.set(buf.getArea().getParagraph(line).getText());
                    asked.set(new int[] {line, character});
                    javafx.application.Platform.runLater(() -> cb.accept(answer)); // a later pulse, as in production
                });
                return buf;
            });

            FxTestSupport.runOnFx(() -> {
                CodeArea area = b.getArea();
                area.moveTo(1, 17);
                area.fireEvent(new KeyEvent(KeyEvent.KEY_TYPED, ";", ";", null, false, false, false, false));
            });
            FxTestSupport.drainFx();
            Thread.sleep(300);
            FxTestSupport.drainFx();

            assertEquals("            foo();", lineAtRequest.get(), "the server is asked about the line as typed");
            assertArrayEquals(new int[] {1, 18}, asked.get(), "at the position after the trigger character");
            assertEquals(
                    "class A {\n    foo();\n}\n", FxTestSupport.callOnFx(b::getContent), "and its indent is adopted");
        } finally {
            fx.dispose();
        }
    }
}
