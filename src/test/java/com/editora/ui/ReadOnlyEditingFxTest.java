package com.editora.ui;

import java.util.Map;

import javafx.scene.input.Clipboard;
import javafx.scene.input.DataFormat;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A buffer in View mode is read-only for every editing command, not only for typed keys: each command that
 * would change the text is run against a View-mode buffer with a selection and a non-empty clipboard and
 * kill ring, and must leave the text as it was, open no prompt, and say why.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReadOnlyEditingFxTest {

    private static final String TEXT = "  beta  line   one\n\n\nalpha line two\n\tgamma LINE three\nalpha line two\n";

    private FxWindowFixture fx;
    private CommandRegistry registry;
    private EditorBuffer buffer;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        registry = FxTestSupport.field(fx.controller, "registry");
        buffer = FxTestSupport.callOnFx(() -> {
            // Something to yank and to paste, so those commands have work they must refuse.
            EditorBuffer scratch = new EditorBuffer();
            scratch.setContent("killed text\nmore");
            FxTestSupport.call(
                    fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, scratch, true);
            scratch.getArea().moveTo(0);
            registry.run("edit.killLine");
            scratch.getArea().selectRange(0, 5);
            registry.run("edit.killRectangle");
            Clipboard.getSystemClipboard().setContent(Map.of(DataFormat.PLAIN_TEXT, "from the clipboard"));

            EditorBuffer b = new EditorBuffer();
            b.setContent(TEXT);
            FxTestSupport.call(fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, b, true);
            b.setViewMode(true);
            return b;
        });
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "edit.cut",
                "edit.paste",
                "edit.deleteChar",
                "edit.toggleComment",
                "edit.transposeChars",
                "edit.transposeWords",
                "edit.transposeLines",
                "edit.upcaseWord",
                "edit.downcaseWord",
                "edit.capitalizeWord",
                "edit.deleteIndentation",
                "edit.deleteHorizontalSpace",
                "edit.justOneSpace",
                "edit.deleteBlankLines",
                "edit.openLine",
                "edit.yankPop",
                "edit.yankFromRing",
                "edit.queryReplace",
                "edit.queryReplaceRegexp",
                "edit.killRectangle",
                "edit.deleteRectangle",
                "edit.clearRectangle",
                "edit.openRectangle",
                "edit.stringRectangle",
                "edit.numberRectangle",
                "edit.yankRectangle",
                "edit.upcaseRegion",
                "edit.downcaseRegion",
                "edit.zapToChar",
                "edit.fillParagraph",
                "edit.fillRegion",
                "edit.case.cycle",
                "edit.case.swap",
                "edit.case.camel",
                "edit.case.snake",
                "edit.alignRegexp",
                "edit.indentationToSpaces",
                "edit.indentationToTabs",
                "edit.sortLinesAsc",
                "edit.sortLinesDesc",
                "edit.sortLinesByLength",
                "edit.reverseLines",
                "edit.shuffleLines",
                "edit.removeDuplicateLines",
                "edit.removeEmptyLines",
                "edit.trimTrailingWhitespace",
                "edit.tabify",
                "edit.untabify",
                "edit.expandAbbrev",
                "edit.deleteSubwordForward",
                "edit.deleteSubwordBackward",
            })
    void aViewModeBufferRefusesTheCommand(String id) throws Exception {
        assertTrue(FxTestSupport.callOnFx(() -> registry.get(id).isPresent()), id + " is a registered command");
        FxTestSupport.runOnFx(() -> {
            buffer.getArea().selectRange(2, TEXT.indexOf("gamma") + 5); // spans lines, starts mid-line
            // So the message asserted below is this command's, not the one before it.
            FxTestSupport.invokeWith(fx.controller, "setStatus", String.class, "about to run " + id);
            registry.run(id);
        });
        boolean prompted = FxPrompts.showing(fx.controller);
        if (prompted) {
            FxTestSupport.runOnFx(() -> FxTestSupport.<OverlayHost>field(fx.controller, "overlayHost")
                    .hide());
        }
        assertEquals(
                TEXT, FxTestSupport.callOnFx(() -> buffer.getArea().getText()), id + " changed a read-only buffer");
        assertFalse(prompted, id + " asked a question it could not act on");
        String status = SaveDecisionsFxTest.lastMessage(fx);
        assertTrue(status.startsWith(tr("status.bufferReadOnly")), id + " said: " + status);
    }
}
