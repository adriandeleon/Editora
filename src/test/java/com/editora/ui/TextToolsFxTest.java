package com.editora.ui;

import java.util.List;

import javafx.scene.control.Label;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import com.editora.i18n.Messages;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The text tools through the real command registry: encode / decode at every selection as one edit, a decode
 * that cannot read one selection changes none, increment / decrement at every caret, and the new sorts.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TextToolsFxTest {

    private FxWindowFixture fx;
    private CommandRegistry registry;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        Messages.init("en");
        fx = FxWindowFixture.create();
        registry = FxTestSupport.field(fx.controller, "registry");
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    @Test
    void encodeAndDecodeEverySelectionAsOneEdit() throws Exception {
        EditorBuffer b = buffer("a=1 b c=2", List.of(new int[] {0, 3}, new int[] {6, 9}));
        run("edit.encode.base64");
        assertEquals("YT0x b Yz0y", text(b));
        assertEquals("YT0x", selected(b), "the result stays selected");
        run("edit.decode.base64");
        assertEquals("a=1 b c=2", text(b));
        FxTestSupport.runOnFx(() -> {
            b.getArea().undo();
            b.getArea().undo();
        });
        assertEquals("a=1 b c=2", text(b));
        assertFalse(FxTestSupport.callOnFx(b.getArea()::isUndoAvailable), "one undo step per command");
    }

    @Test
    void aDecodeThatCannotReadOneSelectionChangesNone() throws Exception {
        EditorBuffer b = buffer("%41 and 100%", List.of(new int[] {0, 3}, new int[] {8, 12}));
        run("edit.decode.url");
        assertEquals("%41 and 100%", text(b));
        assertEquals(tr("status.stringops.cannotDecode"), echo());
    }

    @Test
    void aCodecWithNothingSelectedSaysSo() throws Exception {
        EditorBuffer b = buffer("<p>", List.of(new int[] {1, 1}));
        run("edit.escape.html");
        assertEquals("<p>", text(b));
        assertEquals(tr("status.stringops.selectFirst"), echo());
    }

    @Test
    void incrementAndDecrementAtEveryCaret() throws Exception {
        EditorBuffer b =
                buffer("v1 = 9\nv2 = 0x0f\nnone\n", List.of(new int[] {5, 5}, new int[] {12, 12}, new int[] {18, 18}));
        run("edit.incrementNumber");
        assertEquals("v1 = 10\nv2 = 0x10\nnone\n", text(b), "a caret with no number is left alone");
        run("edit.decrementNumber");
        assertEquals("v1 = 9\nv2 = 0x0f\nnone\n", text(b));
    }

    @Test
    void incrementWithNoNumberSaysSo() throws Exception {
        EditorBuffer b = buffer("no digits", List.of(new int[] {0, 0}));
        run("edit.incrementNumber");
        assertEquals("no digits", text(b));
        assertEquals(tr("status.stringops.noNumber"), echo());
    }

    @Test
    void theNewSortsWorkOnTheWholeBuffer() throws Exception {
        EditorBuffer b = buffer("b\nB\na\nb\n", List.of(new int[] {0, 0}));
        run("edit.sortLinesCaseSensitive");
        assertEquals("B\na\nb\nb\n", text(b));
        run("edit.sortLinesUnique");
        assertEquals("a\nB\nb\n", text(b), "natural: case-insensitive, upper first on a tie");
    }

    @Test
    void everyNewToolIsInTheStringManipulationPicker() {
        for (String id :
                List.of("edit.encode.base64", "edit.keepLines", "edit.incrementNumber", "edit.sortLinesUnique")) {
            assertTrue(EditingCoordinator.STRING_OP_IDS.contains(id), id);
        }
    }

    // --- helpers ---

    private void run(String id) throws Exception {
        FxTestSupport.runOnFx(() -> registry.run(id));
        FxTestSupport.drainFx();
    }

    /** A plain buffer holding {@code text}, with a caret or selection at each {@code {anchor, caret}}. */
    private EditorBuffer buffer(String text, List<int[]> carets) throws Exception {
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setContent(text);
            FxTestSupport.call(
                    fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, buffer, true);
            return buffer;
        });
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> b.placeOccurrenceCarets(carets, carets.get(0)[0]));
        return b;
    }

    private static String text(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> b.getArea().getText());
    }

    private static String selected(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> b.getArea().getSelectedText());
    }

    private String echo() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            StatusBar status = FxTestSupport.field(fx.controller, "statusBar");
            return FxTestSupport.<Label>field(status, "echo").getText();
        });
    }
}
