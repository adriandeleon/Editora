package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

import com.editora.editor.LspDiagnostic;
import com.editora.editor.LspDiagnostic.Severity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless-FX coverage of {@link ProblemsPanel#setProblems}: the language → file → diagnostic grouping,
 * empty-input handling, and that empty diagnostic lists are dropped. Uses a no-op {@link ProblemsPanel.Actions}.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProblemsPanelFxTest {

    private static final ProblemsPanel.Actions NOOP = (file, line, col) -> {};

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private ProblemsPanel panel() throws Exception {
        return FxTestSupport.callOnFx(() -> new ProblemsPanel(NOOP));
    }

    @SuppressWarnings("unchecked")
    private static TreeView<Object> tree(ProblemsPanel p) {
        return (TreeView<Object>) FxTestSupport.<TreeView<?>>field(p, "tree");
    }

    private static LspDiagnostic diag(int line, Severity sev, String msg) {
        return new LspDiagnostic(line, 0, line, 5, sev, msg, "E" + line, "test");
    }

    @Test
    void groupsByLanguageThenFileThenDiagnostic() throws Exception {
        ProblemsPanel p = panel();
        Path java = Path.of("/proj/Main.java");
        Path py = Path.of("/proj/app.py");
        Map<Path, List<LspDiagnostic>> byFile = Map.of(
                java, List.of(diag(2, Severity.ERROR, "boom"), diag(1, Severity.WARNING, "meh")),
                py, List.of(diag(5, Severity.INFO, "note")));
        FxTestSupport.runOnFx(() -> p.setProblems(byFile));

        TreeItem<Object> root = FxTestSupport.callOnFx(() -> tree(p).getRoot());
        assertEquals(2, root.getChildren().size(), "two language groups (Java, Python)");

        // Each language group holds exactly one file; the Java file holds both diagnostics, sorted by line.
        int totalDiagnostics = FxTestSupport.callOnFx(() -> root.getChildren().stream()
                .flatMap(lang -> lang.getChildren().stream()) // file rows
                .mapToInt(file -> file.getChildren().size())
                .sum());
        assertEquals(3, totalDiagnostics, "all diagnostics rendered as leaf rows");
    }

    @Test
    void emptyAndBlankInputsProduceNoRows() throws Exception {
        ProblemsPanel p = panel();
        FxTestSupport.runOnFx(() -> p.setProblems(Map.of()));
        assertTrue(
                FxTestSupport.callOnFx(() -> tree(p).getRoot().getChildren().isEmpty()),
                "empty map ⇒ no language groups");

        // A file mapped to an empty diagnostic list is dropped, not shown as an empty group.
        FxTestSupport.runOnFx(() -> p.setProblems(Map.of(Path.of("/proj/Clean.java"), List.of())));
        assertTrue(
                FxTestSupport.callOnFx(() -> tree(p).getRoot().getChildren().isEmpty()),
                "files with no diagnostics are filtered out");
    }
}
