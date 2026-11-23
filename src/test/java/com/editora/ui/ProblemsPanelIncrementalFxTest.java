package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

import com.editora.text.LspDiagnostic;
import com.editora.text.LspDiagnostic.Severity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A republish of one file replaces that file's branch of the Problems tree, not the whole tree: with a project's
 * worth of problems listed, each typing pause used to rebuild every row (perf review 2026-11-23 L1). The tree it
 * leaves is the one a fresh build makes.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProblemsPanelIncrementalFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static ProblemsPanel shownPanel() {
        ProblemsPanel panel = new ProblemsPanel((file, line, col) -> {});
        new javafx.scene.Scene(new javafx.scene.layout.StackPane(panel), 300, 400);
        return panel;
    }

    @SuppressWarnings("unchecked")
    private static TreeView<Object> tree(ProblemsPanel p) {
        return (TreeView<Object>) FxTestSupport.<TreeView<?>>field(p, "tree");
    }

    private static List<LspDiagnostic> problems(String... messages) {
        List<LspDiagnostic> out = new ArrayList<>();
        for (int i = 0; i < messages.length; i++) {
            out.add(new LspDiagnostic(i, 0, i, 3, Severity.ERROR, messages[i], null, null));
        }
        return List.copyOf(out);
    }

    /** The tree as text: language, file and row values, in order. */
    private static List<String> outline(ProblemsPanel p) {
        List<String> out = new ArrayList<>();
        for (TreeItem<Object> language : tree(p).getRoot().getChildren()) {
            out.add(String.valueOf(language.getValue()));
            for (TreeItem<Object> file : language.getChildren()) {
                out.add("  " + file.getValue());
                for (TreeItem<Object> row : file.getChildren()) {
                    out.add("    " + row.getValue());
                }
            }
        }
        return out;
    }

    private static String summary(ProblemsPanel p) {
        return FxTestSupport.<javafx.scene.control.Label>field(p, "summary").getText();
    }

    @Test
    void oneFilesRepublishReplacesOnlyItsBranch() throws Exception {
        FxTestSupport.runOnFx(() -> {
            ProblemsPanel panel = shownPanel();
            Map<Path, List<LspDiagnostic>> live = new LinkedHashMap<>();
            for (int i = 0; i < 200; i++) {
                live.put(Path.of("/proj/F" + i + ".java"), problems("a" + i, "b" + i));
            }
            live.put(Path.of("/proj/app.py"), problems("py"));
            panel.setProblems(live);
            int full = panel.fullRebuildCount();
            TreeItem<Object> untouched =
                    tree(panel).getRoot().getChildren().get(0).getChildren().get(0);

            live.put(Path.of("/proj/F7.java"), problems("changed")); // a typing pause in F7
            live.put(Path.of("/proj/New.java"), problems("new")); // a file newly with problems
            live.remove(Path.of("/proj/app.py")); // a file fixed: its language goes too
            int updates = panel.rebuildCount();
            panel.setProblems(live);

            assertEquals(full, panel.fullRebuildCount(), "not rebuilt whole");
            assertEquals(updates + 1, panel.rebuildCount());
            assertSame(
                    untouched,
                    tree(panel).getRoot().getChildren().get(0).getChildren().get(0),
                    "another file's branch is the same");
            List<String> incremental = outline(panel);
            String incrementalSummary = summary(panel);

            ProblemsPanel fresh = shownPanel();
            fresh.setProblems(live);
            assertEquals(outline(fresh), incremental, "the same tree a fresh build makes");
            assertEquals(summary(fresh), incrementalSummary);
            assertTrue(incremental.stream().noneMatch(l -> l.contains("app.py")));
            assertTrue(incremental.stream().anyMatch(l -> l.contains("changed")));
        });
    }

    @Test
    void manyChangedFilesAreBuiltAfresh() throws Exception {
        FxTestSupport.runOnFx(() -> {
            ProblemsPanel panel = shownPanel();
            Map<Path, List<LspDiagnostic>> live = new LinkedHashMap<>();
            live.put(Path.of("/proj/A.java"), problems("a"));
            panel.setProblems(live);
            int full = panel.fullRebuildCount();
            for (int i = 0; i < ProblemsPanel.INCREMENTAL_FILES + 1; i++) {
                live.put(Path.of("/proj/G" + i + ".java"), problems("g"));
            }
            panel.setProblems(live);
            assertEquals(full + 1, panel.fullRebuildCount(), "a burst of new files: one build of the whole tree");
        });
    }

    @Test
    void changedFilesComparesListsNotTheirIdentityAlone() {
        Path a = Path.of("/a");
        Path b = Path.of("/b");
        Path c = Path.of("/c");
        List<String> one = List.of("x");
        Map<Path, List<String>> shown = Map.of(a, one, b, List.of("y"));
        Map<Path, List<String>> latest = new LinkedHashMap<>();
        latest.put(a, List.of("x")); // equal, not the same list
        latest.put(c, List.of("z")); // new
        latest.put(Path.of("/d"), List.of()); // empty and never shown
        assertEquals(List.of(c, b), ProblemsPanel.changedFiles(shown, latest), "b is gone");
        latest.put(b, List.of());
        assertEquals(List.of(c, b), ProblemsPanel.changedFiles(shown, latest), "b emptied");
        assertTrue(ProblemsPanel.changedFiles(shown, Map.of(a, one, b, List.of("y")))
                .isEmpty());
    }

    @Test
    void aFewChangedFilesOrUnderAQuarterGoBranchByBranch() {
        assertTrue(ProblemsPanel.incremental(1, 1));
        assertTrue(ProblemsPanel.incremental(ProblemsPanel.INCREMENTAL_FILES, 0));
        assertTrue(ProblemsPanel.incremental(250, 1000));
        assertFalse(ProblemsPanel.incremental(251, 1000));
    }
}
