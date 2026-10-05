package com.editora.ui;

import java.nio.file.Files;

import javafx.scene.control.Tab;

import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reload from disk keeps the user's place. It used to restore the caret offset only: the viewport was back
 * at the top of the file with the caret far below it, and every collapsed fold was open again.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReloadKeepsViewFxTest {

    private EditingFx e;

    @BeforeAll
    void setUp() throws Exception {
        e = EditingFx.create();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (e != null) {
            e.dispose();
        }
    }

    private static String bigClass() {
        StringBuilder sb = new StringBuilder("class Big {\n");
        for (int m = 0; m < 10; m++) {
            sb.append("    void m").append(m).append("() {\n");
            for (int i = 0; i < 30; i++) {
                sb.append("        call").append(i).append("();\n");
            }
            sb.append("    }\n");
        }
        return sb.append("}\n").toString();
    }

    private void reload(EditorBuffer b) throws Exception {
        Object workflows = FxTestSupport.field(e.fx.controller, "fileWorkflows");
        FxTestSupport.runOnFx(() -> {
            Tab tab = (Tab) FxTestSupport.call(
                    e.fx.controller, "tabForPath", new Class<?>[] {java.nio.file.Path.class}, b.getPath());
            FxTestSupport.call(workflows, "reloadFromDisk", new Class<?>[] {Tab.class, EditorBuffer.class}, tab, b);
        });
    }

    @Test
    void reloadKeepsTheScrollPositionTheCaretLineAndTheFolds() throws Exception {
        String text = bigClass();
        EditorBuffer b = e.open("Big.java", text);
        CodeArea area = b.getArea();
        FxTestSupport.runOnFx(() -> {
            b.getFoldManager().recompute();
            area.moveTo(33, 4); // "void m1() {" — fold that method
        });
        e.run("view.fold");
        assertTrue(FxTestSupport.callOnFx(() -> b.getFoldManager().isCollapsed(33)), "folded before the reload");
        FxTestSupport.runOnFx(() -> {
            area.moveTo(200, 6);
            area.showParagraphAtTop(190);
        });
        assertTrue(e.await(() -> area.firstVisibleParToAllParIndex() == 190), "scrolled: " + e.viewport(area));

        Files.writeString(b.getPath(), text.replace("call7();", "call7(); // changed"));
        reload(b);
        assertTrue(e.await(() -> area.getText().contains("// changed")), "the copy on disk was loaded");
        e.pulses(20);

        assertEquals(200, (int) FxTestSupport.callOnFx(area::getCurrentParagraph), "caret line");
        assertEquals(6, (int) FxTestSupport.callOnFx(area::getCaretColumn), "caret column");
        assertTrue(FxTestSupport.callOnFx(() -> b.getFoldManager().isCollapsed(33)), "the fold is still collapsed");
        assertEquals(
                190,
                (int) FxTestSupport.callOnFx(area::firstVisibleParToAllParIndex),
                "the view is where the user left it: " + e.viewport(area));
    }

    /** The captured place is a document position: a reload widens, so a region-relative one would be wrong. */
    @Test
    void reloadingANarrowedBufferKeepsTheCaretOnItsDocumentLine() throws Exception {
        String text = EditingFx.lines("line", 400);
        EditorBuffer b = e.open("narrow-reload.txt", text);
        CodeArea area = b.getArea();
        FxTestSupport.runOnFx(() -> area.selectRange(
                area.getAbsolutePosition(199, 0), area.getAbsolutePosition(209, area.getParagraphLength(209))));
        e.run("edit.narrowToRegion");
        FxTestSupport.runOnFx(() -> area.moveTo(4, 2)); // region line 5 = document line 204 ("line 204")
        assertEquals(
                "line 204", FxTestSupport.callOnFx(() -> area.getParagraph(4).getText()));

        Files.writeString(b.getPath(), text + "appended\n");
        reload(b);
        assertTrue(e.await(() -> area.getText().contains("appended")), "the copy on disk was loaded");
        e.pulses(20);

        assertEquals(
                "line 204",
                FxTestSupport.callOnFx(
                        () -> area.getParagraph(area.getCurrentParagraph()).getText()));
        assertTrue(e.caretVisible(area), "and it is on screen: " + e.viewport(area));
    }
}
