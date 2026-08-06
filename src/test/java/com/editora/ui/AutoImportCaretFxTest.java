package com.editora.ui;

import java.util.List;

import com.editora.editor.EditorBuffer;
import com.editora.editor.LspTextEdit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Accepting a completion that carries an auto-import must not move the caret (#834).
 *
 * <p>The import lands <em>above</em> the caret, and both of {@code applyLspEdits}' apply paths leave the caret
 * at the end of what they inserted — so the caret was thrown onto the newly inserted {@code import} line,
 * away from the identifier the user was in the middle of typing. Driven through the real
 * {@code applyCompletionAdditionalEdits} rather than the pure helper, because the defect was in what
 * {@code replaceText} does to the caret, not in the arithmetic.
 */
@Tag("fx")
class AutoImportCaretFxTest {

    private static final String SOURCE = "package demo;\n"
            + "\n"
            + "import java.util.ArrayList;\n"
            + "import java.util.List;\n"
            + "import java.util.Optional;\n"
            + "\n"
            + "public class Inventory2 {\n"
            + "\n"
            + "    private final List<Item\n"
            + "}\n";

    private static final String IMPORT_LINE = "import demo.Inventory.Item;\n";

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** A buffer holding SOURCE with the caret just after `List<Item`, as in the report. */
    private static EditorBuffer atItem() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent(SOURCE);
            b.getArea().moveTo(SOURCE.indexOf("List<Item") + "List<Item".length());
            return b;
        });
    }

    @Test
    void theCaretStaysOnTheIdentifierWhenTheImportIsInsertedAboveIt() throws Exception {
        EditorBuffer b = atItem();
        int before = FxTestSupport.callOnFx(() -> b.getArea().getCaretPosition());

        // What jdtls sends back from completionItem/resolve: insert the import line at line 5, col 0.
        FxTestSupport.runOnFx(
                () -> b.applyCompletionAdditionalEdits(List.of(new LspTextEdit(5, 0, 5, 0, IMPORT_LINE))));

        assertEquals(
                before + IMPORT_LINE.length(),
                FxTestSupport.callOnFx(() -> b.getArea().getCaretPosition()),
                "the caret must follow its own text down, not land on the inserted import");
    }

    @Test
    void theCaretIsStillRightAfterTheTypedIdentifier() throws Exception {
        EditorBuffer b = atItem();
        FxTestSupport.runOnFx(
                () -> b.applyCompletionAdditionalEdits(List.of(new LspTextEdit(5, 0, 5, 0, IMPORT_LINE))));

        // Expressed as text rather than an offset: whatever the numbers, the caret sits just past "List<Item".
        String content = FxTestSupport.callOnFx(b::getContent);
        int caret = FxTestSupport.callOnFx(() -> b.getArea().getCaretPosition());
        assertEquals("List<Item", content.substring(caret - "List<Item".length(), caret));
    }

    @Test
    void theImportIsStillActuallyInserted() throws Exception {
        EditorBuffer b = atItem();
        FxTestSupport.runOnFx(
                () -> b.applyCompletionAdditionalEdits(List.of(new LspTextEdit(5, 0, 5, 0, IMPORT_LINE))));

        // Guard against "fixing" the caret by not applying the edit at all.
        assertEquals(
                1,
                FxTestSupport.callOnFx(() -> b.getContent().split("import demo\\.Inventory\\.Item;", -1).length - 1));
    }

    @Test
    void formatDocumentEditsAreUnaffected() throws Exception {
        // applyLspEdits keeps its old behaviour: only the auto-import path opts into caret preservation, so
        // this change cannot alter where Format Document leaves the caret.
        EditorBuffer b = atItem();
        FxTestSupport.runOnFx(() -> b.applyLspEdits(List.of(new LspTextEdit(5, 0, 5, 0, IMPORT_LINE))));
        // Line 5 is the blank line after the Optional import, so the insert starts there and the caret is
        // left at its end — dragged off "List<Item", which is precisely the behaviour the auto-import path
        // now opts out of.
        String upToLine5 = SOURCE.substring(
                0, SOURCE.indexOf("import java.util.Optional;\n") + "import java.util.Optional;\n".length());
        assertEquals(
                upToLine5.length() + IMPORT_LINE.length(),
                FxTestSupport.callOnFx(() -> b.getArea().getCaretPosition()),
                "unchanged: replaceText still leaves the caret at the end of its insertion");
    }
}
