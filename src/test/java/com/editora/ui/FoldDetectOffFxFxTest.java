package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.editora.editor.fold.FoldManager;
import com.editora.text.FoldRegions.Region;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fold regions are detected off the FX thread (PERF-01, Pauta 6.2.0): an answer for a text that has changed since is
 * dropped, never shown, and the regions are detected again; a fold command asked meanwhile waits and then acts on the
 * regions of the text as it is.
 */
@Tag("fx")
class FoldDetectOffFxFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void aDetectionForAnOlderTextIsDroppedAndTheTextAsItIsThenIsDetected() throws Exception {
        List<List<Region>> applied = new CopyOnWriteArrayList<>();
        Object[] made = FxTestSupport.callOnFx(() -> {
            CodeArea area = new CodeArea("class A {\n    void m() {\n    }\n}\n");
            FoldManager folds = new FoldManager(area);
            folds.setOnRegionsChanged(() -> applied.add(new ArrayList<>(folds.regions())));
            folds.setLanguage("java");
            return new Object[] {area, folds};
        });
        CodeArea area = (CodeArea) made[0];
        FoldManager folds = (FoldManager) made[1];
        SettingsRig.awaitFx("the first regions", () -> !folds.regions().isEmpty());
        applied.clear();
        FxTestSupport.runOnFx(() -> {
            folds.setLanguage("java"); // a detection of this text...
            area.insertText(0, "\n\n"); // ...which is out of date before it lands
            area.moveTo(2, 0); // on the class header
            folds.foldAtCaret(); // waits for the regions of the text as it is
        });
        SettingsRig.awaitFx(
                "the regions of the new text", () -> folds.regions().stream().anyMatch(r -> r.startLine() == 2));
        for (List<Region> regions : applied) {
            assertFalse(regions.stream().anyMatch(r -> r.startLine() == 0), "a stale answer was shown: " + applied);
        }
        assertTrue(applied.size() >= 1);
        SettingsRig.awaitFx("the waiting fold ran", () -> area.isFolded(3));
        assertFalse(FxTestSupport.callOnFx(() -> area.isFolded(1)), "folded by the new regions, not the old");
    }

    @Test
    void foldingTheBlockCommentsCountsThemFromAScanOffTheFxThread() throws Exception {
        Object[] made = FxTestSupport.callOnFx(() -> {
            CodeArea area = new CodeArea("/*\n a\n*/\nclass A {\n}\n/*\n b\n*/\n");
            FoldManager folds = new FoldManager(area);
            folds.setLanguage("java");
            return new Object[] {area, folds};
        });
        FoldManager folds = (FoldManager) made[1];
        int[] count = {-1, -1};
        FxTestSupport.runOnFx(() -> {
            folds.foldAllBlockComments(n -> count[0] = n);
            folds.foldAllBlockComments(n -> count[1] = n); // a second press while the first scans: refused
        });
        SettingsRig.awaitFx("the comments counted", () -> count[0] >= 0);
        FxTestSupport.drainFx();
        assertEquals(2, count[0]);
        assertEquals(-1, count[1], "a second press while one scans starts nothing");
    }

    /**
     * A line command sees a collapsed fold next to the caret as one line, and only that fold is looked at: a fold far
     * off stays as it is, and a NUL far off does not stop the masking (PERF-01: no walk over the whole document).
     */
    @Test
    void lineUnitsMaskOnlyTheFoldsBesideTheCaret() throws Exception {
        String text = "a {\n  b\n  c\n}\nd\ne\nx\ny\nf {\n  g\n}\nh\u0000\n";
        String[] out = FxTestSupport.callOnFx(() -> {
            CodeArea area = new CodeArea(text);
            FoldManager folds = new FoldManager(area);
            area.foldParagraphs(0, 3); // lines 1..3 hidden
            area.foldParagraphs(8, 10); // lines 9..10 hidden
            String near = folds.linesAsUnits(area.getText(), 0);
            String none = folds.linesAsUnits(area.getText(), 5); // line 5: no fold within a line of it
            String both = folds.linesAsUnits(area.getText(), 0, 8); // the second header
            return new String[] {near, none, both};
        });
        char nul = '\u0000';
        String firstMasked = "a {" + nul + "  b" + nul + "  c" + nul + "}\nd\ne\nx\ny\nf {\n  g\n}\nh" + nul + "\n";
        assertEquals(firstMasked, out[0], "the fold at the caret masked, the far one and the far NUL left alone");
        assertEquals(text, out[1], "no fold beside the caret: the text itself");
        assertTrue(out[2].startsWith("a {" + nul) && out[2].contains("f {" + nul + "  g" + nul + "}"), out[2]);
    }
}
