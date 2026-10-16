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
        return FxTestSupport.callOnFx(() -> {
            ProblemsPanel panel = new ProblemsPanel(NOOP);
            // An open tool window is in a scene; out of one the panel defers its rebuilds (see
            // ProblemsPanelHiddenFxTest).
            new javafx.scene.Scene(new javafx.scene.layout.StackPane(panel), 300, 400);
            return panel;
        });
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

    /**
     * A row shows the rule's code, and its context menu opens the rule's documentation and copies the code
     * (shell review C9).
     */
    @Test
    void aRowNamesItsRuleAndItsMenuOpensTheDocsAndCopiesTheCode() throws Exception {
        List<String> opened = new java.util.ArrayList<>();
        LspDiagnostic d = new LspDiagnostic(
                2,
                5,
                2,
                7,
                Severity.INFO,
                "Double quote to prevent globbing and word splitting.",
                "SC2086",
                "shellcheck",
                "https://www.shellcheck.net/wiki/SC2086",
                false);
        assertEquals("3:  Double quote to prevent globbing and word splitting.  [SC2086]", ProblemsPanel.rowText(d));

        javafx.scene.control.ContextMenu menu =
                FxTestSupport.callOnFx(() -> new ProblemsPanel(new ProblemsPanel.Actions() {
                            @Override
                            public void open(Path file, int line, int col) {}

                            @Override
                            public void openUrl(String url) {
                                opened.add(url);
                            }
                        })
                        .contextMenuFor(d));
        assertEquals(
                List.of(
                        com.editora.i18n.Messages.tr("problems.menu.openRuleDocs"),
                        com.editora.i18n.Messages.tr("problems.menu.copyCode")),
                FxTestSupport.callOnFx(
                        () -> menu.getItems().stream().map(i -> i.getText()).toList()));
        FxTestSupport.runOnFx(() -> menu.getItems().get(0).fire());
        assertEquals(List.of("https://www.shellcheck.net/wiki/SC2086"), opened);
        FxTestSupport.runOnFx(() -> menu.getItems().get(1).fire());
        assertEquals(
                "SC2086",
                FxTestSupport.callOnFx(
                        () -> javafx.scene.input.Clipboard.getSystemClipboard().getString()));

        LspDiagnostic plain = new LspDiagnostic(0, 0, 0, 1, Severity.ERROR, "boom", null, "x");
        assertEquals("1:  boom", ProblemsPanel.rowText(plain));
        assertEquals(null, FxTestSupport.callOnFx(() -> new ProblemsPanel(NOOP).contextMenuFor(plain)));
    }
}
