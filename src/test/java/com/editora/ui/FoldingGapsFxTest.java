package com.editora.ui;

import java.util.List;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import com.editora.editor.FoldRegions.Region;
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
 * Drives the #727 folding additions through the real {@link CommandRegistry}: manual fold ranges (create
 * from selection, shift through edits, persist), fold-all-except, block-comment folding, and marker
 * regions. The line arithmetic and detectors are unit-tested ({@code ManualFoldsTest} /
 * {@code FoldRegionsTest}); what only a live buffer proves is the wiring — selection → region, the
 * per-change shift subscription, the merge into the chevron set, and the WorkspaceState round-trip.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FoldingGapsFxTest {

    // Lines: 0 class, 1 /**, 2 * doc, 3 */, 4 //#region tools, 5 void m() {, 6 x();, 7 }, 8 //#endregion,
    // 9 int a;, 10 int b;, 11 int c;, 12 }
    private static final String SRC = "class A {\n"
            + "    /*\n"
            + "     * doc\n"
            + "     */\n"
            + "    //#region tools\n"
            + "    void m() {\n"
            + "        x();\n"
            + "    }\n"
            + "    //#endregion\n"
            + "    int a;\n"
            + "    int b;\n"
            + "    int c;\n"
            + "}\n";

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

    private CodeArea area(EditorBuffer b) {
        return FxTestSupport.field(b, "area");
    }

    @Test
    void commentAndMarkerRegionsJoinTheFoldableSet() throws Exception {
        EditorBuffer b = addJavaBuffer();
        List<Region> regions = FxTestSupport.callOnFx(() -> b.getFoldManager().regions());
        assertTrue(regions.contains(new Region(1, 3)), "the /* */ doc comment is foldable: " + regions);
        assertTrue(regions.contains(new Region(4, 8)), "the //#region span is foldable: " + regions);
        assertTrue(regions.contains(new Region(0, 12)), "the brace region is still there: " + regions);
    }

    @Test
    void foldAllBlockCommentsFoldsExactlyTheComment() throws Exception {
        EditorBuffer b = addJavaBuffer();
        run("view.foldAllBlockComments");
        assertTrue(collapsed(b, 1), "the doc comment folded");
        assertFalse(collapsed(b, 0), "the class did not");
        assertFalse(collapsed(b, 4), "the marker region did not");
    }

    @Test
    void markerRegionsFoldAndUnfoldOnCommand() throws Exception {
        EditorBuffer b = addJavaBuffer();
        run("view.foldAllMarkerRegions");
        assertTrue(collapsed(b, 4), "the //#region span folded");
        assertFalse(collapsed(b, 1), "the comment did not");
        run("view.unfoldAllMarkerRegions");
        assertFalse(collapsed(b, 4), "and unfolded again");
    }

    @Test
    void foldAllExceptKeepsTheCaretChainOpen() throws Exception {
        EditorBuffer b = addJavaBuffer();
        // Caret inside m()'s body (line 6): the class (its ancestor) stays open, everything else folds.
        FxTestSupport.runOnFx(() -> area(b).moveTo(6, 2));
        run("view.foldAllExcept");
        assertFalse(collapsed(b, 0), "the class contains the caret — open");
        assertFalse(collapsed(b, 5), "the method contains the caret — open");
        assertTrue(collapsed(b, 1), "the doc comment folded");
        // The marker region [4..8] contains line 6, so it stays open too.
        assertFalse(collapsed(b, 4), "the marker span contains the caret — open");
    }

    @Test
    void unfoldAllExceptUnfoldsEverythingNotUnderTheCaret() throws Exception {
        EditorBuffer b = addJavaBuffer();
        run("view.foldAllBlockComments");
        run("view.foldAllMarkerRegions");
        assertTrue(collapsed(b, 1));
        assertTrue(collapsed(b, 4));
        // Caret on the marker fold's header: that fold stays collapsed, the comment opens.
        FxTestSupport.runOnFx(() -> area(b).moveTo(4, 0));
        run("view.unfoldAllExcept");
        assertTrue(collapsed(b, 4), "the fold under the caret stays");
        assertFalse(collapsed(b, 1), "the rest unfolds");
    }

    @Test
    void manualFoldFromSelectionFoldsAndSurvivesEditsAboveIt() throws Exception {
        EditorBuffer b = addJavaBuffer();
        // Select the three int lines (9..11) — no syntactic region covers them.
        FxTestSupport.runOnFx(() -> area(b).selectRange(9, 0, 11, 10));
        run("view.createFoldFromSelection");
        assertTrue(collapsed(b, 9), "the manual range folded");
        assertEquals(
                List.of(new Region(9, 11)),
                FxTestSupport.callOnFx(() -> b.getFoldManager().manualRegions()));

        // An edit above shifts it: insert a line at the top. The fold now heads line 10.
        FxTestSupport.runOnFx(() -> area(b).insertText(0, "// header\n"));
        assertEquals(
                List.of(new Region(10, 12)),
                FxTestSupport.callOnFx(() -> b.getFoldManager().manualRegions()),
                "the manual range followed the edit");

        run("view.removeManualFolds");
        assertTrue(
                FxTestSupport.callOnFx(() -> b.getFoldManager().manualRegions().isEmpty()));
        assertFalse(collapsed(b, 10), "removing manual ranges unfolds them");
    }

    @Test
    void aSelectionUnderTwoLinesRefusesToFold() throws Exception {
        EditorBuffer b = addJavaBuffer();
        FxTestSupport.runOnFx(() -> area(b).selectRange(9, 0, 9, 5));
        run("view.createFoldFromSelection");
        assertTrue(
                FxTestSupport.callOnFx(() -> b.getFoldManager().manualRegions().isEmpty()));
    }

    @Test
    void manualFoldsRoundTripThroughWorkspaceState() throws Exception {
        // The persistence seam, without a full restart: persistFolds writes the flattened pairs under the
        // file key; restoreFolds re-installs them and re-folds. Drive both directly (they are the exact
        // methods session restore uses), against a buffer with a real path so the key exists.
        java.nio.file.Path file = java.nio.file.Files.createTempFile("manual-folds", ".java");
        java.nio.file.Files.writeString(file, SRC);
        try {
            EditorBuffer b = FxTestSupport.callOnFx(() -> {
                try {
                    FxTestSupport.call(fx.controller, "openPath", new Class[] {java.nio.file.Path.class}, file);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class[] {});
            });
            FxTestSupport.runOnFx(() -> area(b).selectRange(9, 0, 11, 10));
            run("view.createFoldFromSelection");
            assertTrue(collapsed(b, 9));

            // Re-open the same file fresh: restoreFolds must find the manual range and re-fold it.
            EditorBuffer fresh = FxTestSupport.callOnFx(() -> {
                EditorBuffer nb = new EditorBuffer();
                nb.setPath(file);
                nb.setContent(SRC);
                nb.getFoldManager().setLanguage("java");
                try {
                    FxTestSupport.call(fx.controller, "restoreFolds", new Class[] {EditorBuffer.class}, nb);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return nb;
            });
            assertEquals(
                    List.of(new Region(9, 11)),
                    FxTestSupport.callOnFx(() -> fresh.getFoldManager().manualRegions()),
                    "the manual range came back from WorkspaceState");
            assertTrue(collapsed(fresh, 9), "and is collapsed again");
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }
}
