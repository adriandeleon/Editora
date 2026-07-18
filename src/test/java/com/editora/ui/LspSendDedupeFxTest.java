package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression for #553: dedupe of the LSP change-send. Both the completion flush (~120 ms) and the debounced edit
 * pulse (~300 ms) send the document to the server for one edit; the second used to re-materialize the whole
 * document ({@code getText()}) and re-send it at the same version. {@code EditorBuffer.sendLspChange} now skips a
 * send whose {@code docVersion} hasn't advanced since the last, dropping the redundant send — but a real edit
 * still sends.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LspSendDedupeFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void aSecondSendAtTheSameVersionIsSkippedButAnEditReSends() throws Exception {
        List<String> sent = new ArrayList<>();
        int[] counts = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLspChangeListener(sent::add);

            b.getArea().replaceText("obj."); // an edit → docVersion advances
            FxTestSupport.invoke(b, "sendLspChange"); // completion flush: sends
            int afterFlush = sent.size();

            FxTestSupport.invoke(b, "sendLspChange"); // debounced pulse, same version → skipped
            int afterPulse = sent.size();

            b.getArea().appendText("field"); // a real edit → new version
            FxTestSupport.invoke(b, "sendLspChange"); // sends again
            int afterEdit = sent.size();

            return new int[] {afterFlush, afterPulse, afterEdit};
        });

        assertEquals(1, counts[0], "the first send goes out");
        assertEquals(1, counts[1], "a second send at the same version is skipped (no redundant getText/didChange)");
        assertEquals(2, counts[2], "a real edit re-sends");
        assertEquals("obj.", sent.get(0), "the first send carries the current document");
        assertEquals("obj.field", sent.get(1), "the re-send carries the edited document");
    }
}
