package com.editora.ui;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javafx.scene.Scene;
import javafx.scene.layout.StackPane;

import com.editora.editor.LspDiagnostic;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The Problems tree was compared, copied and rebuilt for every change to the diagnostics whether or not the
 * tool window was open — and diagnostics change on every typing pause. While the panel is out of the scene
 * (a closed tool window) it must do none of that, and catch up in one rebuild when shown.
 */
@Tag("fx")
class ProblemsPanelHiddenFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static List<LspDiagnostic> one(String message) {
        return List.of(new LspDiagnostic(0, 0, 0, 5, LspDiagnostic.Severity.ERROR, message, null, "test"));
    }

    private static int diagnosticRows(ProblemsPanel panel) {
        javafx.scene.control.TreeView<?> tree = FxTestSupport.field(panel, "tree");
        int rows = 0;
        for (var language : tree.getRoot().getChildren()) {
            for (var file : language.getChildren()) {
                rows += file.getChildren().size();
            }
        }
        return rows;
    }

    @Test
    void aHiddenPanelDoesNotRebuildAndCatchesUpOnceWhenShown() throws Exception {
        int[] result = FxTestSupport.callOnFx(() -> {
            ProblemsPanel panel = new ProblemsPanel((file, line, col) -> {});
            Map<Path, List<LspDiagnostic>> live = new HashMap<>(); // the coordinator hands in its live map
            int atStart = panel.rebuildCount();

            for (int i = 0; i < 50; i++) {
                live.put(Path.of("/proj/F" + i + ".java"), one("boom " + i));
                panel.setProblems(live);
            }
            panel.setActiveFile(Path.of("/proj/F3.java"));
            int whileHidden = panel.rebuildCount() - atStart;
            int rowsWhileHidden = diagnosticRows(panel);

            new Scene(new StackPane(panel), 300, 400); // the tool window opens
            int afterShow = panel.rebuildCount() - atStart;
            int rowsAfterShow = diagnosticRows(panel);

            panel.setProblems(live); // unchanged content while visible: still nothing to do
            return new int[] {whileHidden, rowsWhileHidden, afterShow, rowsAfterShow, panel.rebuildCount() - atStart};
        });

        assertEquals(0, result[0], "no rebuild while the window is closed");
        assertEquals(0, result[1], "the tree is not built while hidden");
        assertEquals(1, result[2], "fifty changes and a tab switch cost one rebuild on show");
        assertEquals(50, result[3], "showing what the map holds now");
        assertEquals(1, result[4]);
    }

    @Test
    void showingAnUnchangedPanelRebuildsNothing() throws Exception {
        int rebuilds = FxTestSupport.callOnFx(() -> {
            ProblemsPanel panel = new ProblemsPanel((file, line, col) -> {});
            Scene scene = new Scene(new StackPane(panel), 300, 400);
            Map<Path, List<LspDiagnostic>> live = new HashMap<>(Map.of(Path.of("/proj/A.java"), one("boom")));
            panel.setProblems(live);
            int before = panel.rebuildCount();

            scene.setRoot(new StackPane()); // closed
            panel.setProblems(live); // the same diagnostics, republished
            new Scene(new StackPane(panel), 300, 400); // reopened
            return panel.rebuildCount() - before;
        });

        assertEquals(0, rebuilds, "nothing changed while it was closed");
    }
}
