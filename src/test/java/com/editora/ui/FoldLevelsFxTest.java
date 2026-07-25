package com.editora.ui;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the fold-level / recursive-fold / fold-navigation commands (#633) through the real
 * {@link CommandRegistry} against a Java buffer with a three-deep brace nest, asserting the collapsed
 * state and caret placement the pure {@code FoldTree} math feeds.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FoldLevelsFxTest {

    // outer {0..6}  ⊃  method {1..5}  ⊃  if {2..4}
    private static final String SRC = "class A {\n" + "    void m() {\n" + "        if (x) {\n" + "            y();\n"
            + "        }\n" + "    }\n" + "}\n";

    private FxWindowFixture fx;
    private CommandRegistry registry;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        registry = FxTestSupport.field(fx.controller, "registry");
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private void run(String id) throws Exception {
        FxTestSupport.runOnFx(() -> registry.run(id));
    }

    private EditorBuffer addJavaBuffer() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent(SRC);
            FxTestSupport.call(fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, b, true);
            b.getFoldManager().setLanguage("java");
            b.getFoldManager().recompute();
            return b;
        });
    }

    private boolean collapsed(EditorBuffer b, int startLine) throws Exception {
        return FxTestSupport.callOnFx(() -> b.getFoldManager().isCollapsed(startLine));
    }

    private void caretTo(EditorBuffer b, int line) throws Exception {
        CodeArea area = FxTestSupport.field(b, "area");
        FxTestSupport.runOnFx(() -> area.moveTo(line, 0));
    }

    @Test
    void foldLevelCollapsesExactlyThatNestingLevel() throws Exception {
        EditorBuffer b = addJavaBuffer();
        assertEquals(
                3, FxTestSupport.callOnFx(() -> b.getFoldManager().regions().size()), "three nested regions");

        run("view.foldLevel2");
        assertFalse(collapsed(b, 0), "outer (level 1) stays open");
        assertTrue(collapsed(b, 1), "method (level 2) is folded");

        run("view.foldLevel1");
        assertTrue(collapsed(b, 0), "outer (level 1) is folded");

        run("view.unfoldAll");
        assertFalse(collapsed(b, 0));
        closeActiveTab(b);
    }

    @Test
    void recursiveFoldAndUnfoldCoverTheWholeNest() throws Exception {
        EditorBuffer b = addJavaBuffer();
        caretTo(b, 0); // inside the outer region

        run("view.foldRecursively");
        assertTrue(collapsed(b, 0), "outer folded");
        assertTrue(collapsed(b, 1), "method folded");
        assertTrue(collapsed(b, 2), "if folded");

        run("view.unfoldRecursively");
        assertFalse(collapsed(b, 0));
        assertFalse(collapsed(b, 1));
        assertFalse(collapsed(b, 2));
        closeActiveTab(b);
    }

    @Test
    void foldNavigationWalksHeaders() throws Exception {
        EditorBuffer b = addJavaBuffer();
        CodeArea area = FxTestSupport.field(b, "area");

        caretTo(b, 0);
        run("view.gotoNextFold");
        assertEquals(1, FxTestSupport.callOnFx(area::getCurrentParagraph), "next fold is the method header");
        run("view.gotoNextFold");
        assertEquals(2, FxTestSupport.callOnFx(area::getCurrentParagraph), "next fold is the if header");

        caretTo(b, 4); // deep in the if body
        run("view.gotoPreviousFold");
        assertEquals(2, FxTestSupport.callOnFx(area::getCurrentParagraph), "previous fold is the if header");

        caretTo(b, 3); // body of the if
        run("view.gotoParentFold");
        assertEquals(2, FxTestSupport.callOnFx(area::getCurrentParagraph), "parent fold is the if header");
        run("view.gotoParentFold");
        assertEquals(1, FxTestSupport.callOnFx(area::getCurrentParagraph), "then the enclosing method header");

        closeActiveTab(b);
    }

    private void closeActiveTab(EditorBuffer b) throws Exception {
        FxTestSupport.runOnFx(() -> {
            b.markClean();
            javafx.scene.control.TabPane tabPane = FxTestSupport.field(fx.controller, "tabPane");
            javafx.scene.control.Tab sel = tabPane.getSelectionModel().getSelectedItem();
            if (sel != null) {
                FxTestSupport.call(fx.controller, "closeTab", new Class[] {javafx.scene.control.Tab.class}, sel);
            }
        });
    }
}
