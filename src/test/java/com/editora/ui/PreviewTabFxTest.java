package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import javafx.scene.control.Tab;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Preview tabs: one reusable slot, so browsing costs one tab rather than one per glance. The promotions
 * are what make it safe — a tab you edited, or asked for explicitly, must stop being disposable, or
 * browsing would close work.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PreviewTabFxTest {

    private FxWindowFixture fx;

    @TempDir
    Path dir;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private Path file(String name) throws Exception {
        Path p = dir.resolve(name);
        Files.writeString(p, "class " + name.replace(".java", "") + " {\n}\n");
        return p;
    }

    private void preview(Path p) {
        FxTestSupport.runOnFxUnchecked(() -> FxTestSupport.invokeWith(fx.controller, "openPathPreview", Path.class, p));
    }

    private void open(Path p) {
        FxTestSupport.runOnFxUnchecked(() -> FxTestSupport.invokeWith(fx.controller, "openPath", Path.class, p));
    }

    private int tabCount() throws Exception {
        return FxTestSupport.callOnFx(() -> FxTestSupport.<EditorArea>field(fx.controller, "editorArea")
                .tabs()
                .size());
    }

    private Tab previewTab() throws Exception {
        return FxTestSupport.callOnFx(() -> FxTestSupport.field(fx.controller, "previewTab"));
    }

    @Test
    void previewingASecondFileReusesTheSlot() throws Exception {
        int before = tabCount();
        preview(file("Alpha.java"));
        preview(file("Beta.java"));
        assertEquals(before + 1, tabCount(), "browsing two files must leave one tab, not two");
    }

    @Test
    void editingPromotesTheTabSoTheNextPreviewDoesNotCloseIt() throws Exception {
        Path kept = file("Edited.java");
        preview(kept);
        FxTestSupport.runOnFxUnchecked(() -> {
            Tab t = FxTestSupport.field(fx.controller, "previewTab");
            EditorArea area = FxTestSupport.field(fx.controller, "editorArea");
            com.editora.editor.EditorBuffer b =
                    (com.editora.editor.EditorBuffer) FxTestSupport.invokeWith(fx.controller, "bufferOf", Tab.class, t);
            b.getArea().appendText("// touched\n");
        });
        int after = tabCount();
        preview(file("Other.java"));
        assertEquals(after + 1, tabCount(), "an edited tab must survive the next preview");
    }

    @Test
    void openingExplicitlyPromotesThePreviewTab() throws Exception {
        Path p = file("Chosen.java");
        preview(p);
        assertTrue(previewTab() != null);
        open(p); // the same file, asked for deliberately
        assertTrue(previewTab() == null, "asking for a file explicitly is a choice, not a glance");
    }

    @Test
    void anAlreadyOpenFileIsNotDemotedIntoTheSlot() throws Exception {
        Path p = file("Permanent.java");
        open(p);
        preview(p);
        // Assert about THIS file's tab rather than about the slot being empty: the fixture is shared
        // across the class, so an earlier test may legitimately have left something in the slot.
        Tab own = FxTestSupport.callOnFx(
                () -> (Tab) FxTestSupport.invokeWith(fx.controller, "tabForPath", Path.class, p));
        assertTrue(own != null);
        assertFalse(
                own == previewTab(),
                "a tab opened deliberately must not become disposable because it was previewed later");
    }

    @Test
    void previewingTheSameFileTwiceIsStable() throws Exception {
        Path p = file("Same.java");
        preview(p);
        int after = tabCount();
        preview(p);
        assertEquals(after, tabCount());
        assertFalse(previewTab() == null);
    }
}
