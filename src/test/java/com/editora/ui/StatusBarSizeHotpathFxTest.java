package com.editora.ui;

import javafx.scene.control.Label;

import com.editora.command.CommandRegistry;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Performance regression: the status bar's file-size segment materializes the whole document (an O(n) String
 * + byte[]) to count UTF-8 bytes. It must NOT run on a caret move — the size doesn't change when the caret
 * moves, and doing it per arrow-key/keystroke allocated the entire document twice on the FX thread. This
 * asserts a caret move updates Ln/Col but leaves the size segment untouched.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StatusBarSizeHotpathFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void aCaretMoveDoesNotRecomputeTheFileSize() throws Exception {
        boolean[] ok = FxTestSupport.callOnFx(() -> {
            Settings settings = new Settings();
            EditorBuffer buffer = new EditorBuffer();
            buffer.setContent("hello world\nsecond line\nthird\n");
            StatusBar sb = new StatusBar(() -> buffer, new CommandRegistry(), () -> settings);
            sb.attach(buffer); // full refresh computes the real size

            Label size = FxTestSupport.field(sb, "size");
            Label position = FxTestSupport.field(sb, "position");
            boolean sizeComputed = !size.getText().isBlank();

            // Poison the size label; a caret move must leave it untouched (no O(n) recompute).
            size.setText("SENTINEL");
            buffer.getArea().moveTo(4); // fires caretPositionProperty → caretListener

            boolean sizeUntouched = "SENTINEL".equals(size.getText());
            boolean lnColUpdated = position.getText().contains("Col");
            return new boolean[] {sizeComputed, sizeUntouched, lnColUpdated};
        });

        assertTrue(ok[0], "the size segment is computed on attach (full refresh)");
        assertEquals(true, ok[1], "a caret move must NOT recompute the file size (stayed SENTINEL)");
        assertTrue(ok[2], "Ln/Col still updates on a caret move");
    }
}
