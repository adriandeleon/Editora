package com.editora.ui;

import java.util.concurrent.atomic.AtomicReference;

import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EditorBuffer#requestLspPasteImports}'s buffer-side contract (#742): the gates that keep it
 * silent, the positions it derives from the pasted span, and the {@code stillValid} supplier that turns
 * a later edit into a dropped answer. The wire itself is covered in {@code LspPasteEventFxTest}.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PasteImportsBufferFxTest {

    private record Captured(int startLine, int startChar, int endLine, int endChar, String text) {}

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static final String SRC = "class A {\n    void m() {\n    }\n}\n";

    /** A java-ish buffer with the paste requester stubbed; returns [buffer, captured, validHolder]. */
    private EditorBuffer buffer(
            AtomicReference<Captured> captured, AtomicReference<java.util.function.BooleanSupplier> valid)
            throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("java");
            b.getNode();
            b.setContent(SRC);
            b.setLspActive(true);
            b.setLspPasteImportsRequester((sl, sc, el, ec, text, stillValid) -> {
                captured.set(new Captured(sl, sc, el, ec, text));
                valid.set(stillValid);
            });
            return b;
        });
    }

    @Test
    void reportsThePostPasteSpanAsPositionsAndText() throws Exception {
        var captured = new AtomicReference<Captured>();
        var valid = new AtomicReference<java.util.function.BooleanSupplier>();
        EditorBuffer b = buffer(captured, valid);

        FxTestSupport.runOnFx(() -> {
            CodeArea area = FxTestSupport.field(b, "area");
            int at = SRC.indexOf("    }\n"); // start of line 2
            String pasted = "        int x;\n";
            area.insertText(at, pasted);
            b.requestLspPasteImports(at, at + pasted.length());
        });

        Captured c = captured.get();
        assertTrue(c != null, "the requester fired");
        assertEquals(2, c.startLine());
        assertEquals(0, c.startChar());
        assertEquals(3, c.endLine());
        assertEquals(0, c.endChar());
        assertEquals("        int x;\n", c.text());
        assertTrue(valid.get().getAsBoolean(), "no edit since the paste — the answer would apply");
    }

    @Test
    void stillValidFlipsFalseOnceTheUserKeepsTyping() throws Exception {
        var captured = new AtomicReference<Captured>();
        var valid = new AtomicReference<java.util.function.BooleanSupplier>();
        EditorBuffer b = buffer(captured, valid);

        FxTestSupport.runOnFx(() -> {
            CodeArea area = FxTestSupport.field(b, "area");
            area.insertText(0, "// pasted\n");
            b.requestLspPasteImports(0, 10);
        });
        assertTrue(valid.get().getAsBoolean());

        FxTestSupport.runOnFx(() -> {
            CodeArea area = FxTestSupport.field(b, "area");
            area.insertText(0, "x"); // the round trip lost the race
        });
        assertFalse(valid.get().getAsBoolean(), "an edit after the request must invalidate the answer");
    }

    @Test
    void theGatesKeepItSilent() throws Exception {
        var captured = new AtomicReference<Captured>();
        var valid = new AtomicReference<java.util.function.BooleanSupplier>();
        EditorBuffer b = buffer(captured, valid);

        FxTestSupport.runOnFx(() -> {
            b.setLspPasteImportsEnabled(false); // the Settings gate
            b.requestLspPasteImports(0, 5);
        });
        assertNull(captured.get(), "disabled → no request");

        FxTestSupport.runOnFx(() -> {
            b.setLspPasteImportsEnabled(true);
            b.setLspActive(false); // no live server for this buffer
            b.requestLspPasteImports(0, 5);
        });
        assertNull(captured.get(), "no LSP → no request");

        FxTestSupport.runOnFx(() -> {
            b.setLspActive(true);
            b.setViewMode(true); // read-only
            b.requestLspPasteImports(0, 5);
        });
        assertNull(captured.get(), "read-only → no request");

        FxTestSupport.runOnFx(() -> {
            b.setViewMode(false);
            b.requestLspPasteImports(5, 5); // empty span
        });
        assertNull(captured.get(), "empty span → no request");

        FxTestSupport.runOnFx(() -> {
            CodeArea area = FxTestSupport.field(b, "area");
            area.selectRange(1, 0, 2, 0);
            b.narrowTo(area.getSelection().getStart(), area.getSelection().getEnd());
            b.requestLspPasteImports(0, 3);
        });
        assertNull(captured.get(), "narrowed → coordinates are region-relative, so no request");
    }
}
