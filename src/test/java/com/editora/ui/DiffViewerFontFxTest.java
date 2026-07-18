package com.editora.ui;

import java.util.List;

import com.editora.diff.DiffEngine;
import com.editora.diff.DiffModels.DiffModel;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for #533: text zoom must resize the Diff view's areas. {@code DiffViewerPane.setFont} rebuilds
 * the inline font style and re-applies it to the currently-built areas, so the zoom the controller pushes on
 * a settings/zoom change takes effect inside a diff tab (it previously kept its fixed construction font).
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DiffViewerFontFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void setFontResizesTheSideBySideDiffAreas() throws Exception {
        DiffModel model = DiffEngine.compute(List.of("a", "b"), List.of("a", "c"));
        DiffViewerPane pane = FxTestSupport.callOnFx(() -> new DiffViewerPane(
                "t", null, null, "L.txt", "R.txt", "a\nb\n", "a\nc\n", model, "Monospaced", 13, true, null));

        // Constructed at 13px; a zoom pushes 22px.
        FxTestSupport.runOnFx(() -> pane.setFont("Monospaced", 22));

        CodeArea left = FxTestSupport.field(pane, "leftArea");
        CodeArea right = FxTestSupport.field(pane, "rightArea");
        assertTrue(FxTestSupport.callOnFx(left::getStyle).contains("-fx-font-size: 22px"), "left area resized");
        assertTrue(FxTestSupport.callOnFx(right::getStyle).contains("-fx-font-size: 22px"), "right area resized");
    }
}
