package com.editora.ui;

import static com.editora.i18n.Messages.tr;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import com.editora.editor.LanguageRegistry;
import com.editora.editor.LspDiagnostic;

import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * The "Problems" tool window: LSP diagnostics across open buffers, grouped <b>by programming language →
 * file → diagnostic</b> (Enter / double-click jumps to one; a language/file header toggles). Mirrors
 * {@link SearchPanel} ({@link ToolWindowContent}); jumps are routed back to the controller via
 * {@link Actions}. The controller pushes the current diagnostics map via {@link #setProblems}.
 */
public final class ProblemsPanel extends VBox implements ToolWindowContent {

    /** Controller callback to open a file at a diagnostic position (0-based line/col). */
    public interface Actions {
        void open(Path file, int line, int col);
    }

    /**
     * A tree row, one of three kinds: a <b>language header</b> ({@code language != null}, no file/diag),
     * a <b>file header</b> ({@code file != null}, no diag), or a <b>diagnostic</b> ({@code diag != null}).
     */
    private record Row(String language, Path file, LspDiagnostic diag) {
    }

    private final Actions actions;
    private final Label summary = new Label();
    private final TreeView<Row> tree = new TreeView<>();

    public ProblemsPanel(Actions actions) {
        this.actions = actions;
        getStyleClass().add("problems-panel");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setSpacing(6);
        setPadding(new Insets(6));
        build();
    }

    private void build() {
        summary.getStyleClass().add("search-summary");
        tree.setShowRoot(false);
        tree.setRoot(new TreeItem<>());
        tree.setCellFactory(t -> new RowCell());
        tree.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                activateSelected();
            }
        });
        VBox.setVgrow(tree, Priority.ALWAYS);
        addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        getChildren().addAll(summary, tree);
        setProblems(Map.of());
    }

    private void onKey(KeyEvent e) {
        switch (e.getCode()) {
            case ENTER -> { activateSelected(); e.consume(); }
            case DOWN -> { moveSelection(1); e.consume(); }
            case UP -> { moveSelection(-1); e.consume(); }
            default -> {
                if (!e.isControlDown()) {
                    return;
                }
                switch (e.getCode()) {
                    case N -> { moveSelection(1); e.consume(); }
                    case P -> { moveSelection(-1); e.consume(); }
                    case M -> { activateSelected(); e.consume(); }
                    default -> { }
                }
            }
        }
    }

    private void moveSelection(int delta) {
        int rows = tree.getExpandedItemCount();
        if (rows == 0) {
            return;
        }
        int i = tree.getSelectionModel().getSelectedIndex();
        int next = Math.max(0, Math.min(rows - 1, (i < 0 ? 0 : i) + delta));
        tree.getSelectionModel().select(next);
        tree.scrollTo(next);
    }

    private void activateSelected() {
        TreeItem<Row> item = tree.getSelectionModel().getSelectedItem();
        if (item == null) {
            return;
        }
        Row row = item.getValue();
        if (row.diag() != null) {
            actions.open(row.file(), row.diag().startLine(), row.diag().startCol());
        } else {
            item.setExpanded(!item.isExpanded()); // language or file header
        }
    }

    /** Rebuilds the tree, grouping the per-file diagnostics by language → file (called on the FX thread). */
    public void setProblems(Map<Path, List<LspDiagnostic>> byFile) {
        // Bucket the non-empty files by display language name (TreeMap keeps the language headers sorted).
        Map<String, List<Path>> byLanguage = new TreeMap<>();
        Map<Path, List<LspDiagnostic>> kept = new HashMap<>();
        int total = 0;
        int files = 0;
        for (var entry : byFile.entrySet()) {
            List<LspDiagnostic> diags = entry.getValue();
            if (diags == null || diags.isEmpty()) {
                continue;
            }
            files++;
            total += diags.size();
            kept.put(entry.getKey(), diags);
            byLanguage.computeIfAbsent(languageOf(entry.getKey()), k -> new ArrayList<>())
                    .add(entry.getKey());
        }
        TreeItem<Row> root = new TreeItem<>();
        for (var langEntry : byLanguage.entrySet()) {
            TreeItem<Row> langItem = new TreeItem<>(new Row(langEntry.getKey(), null, null));
            langItem.setExpanded(true);
            List<Path> paths = langEntry.getValue();
            paths.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)));
            for (Path file : paths) {
                List<LspDiagnostic> sorted = new ArrayList<>(kept.get(file));
                sorted.sort((a, b) -> a.startLine() != b.startLine()
                        ? Integer.compare(a.startLine(), b.startLine())
                        : Integer.compare(a.startCol(), b.startCol()));
                TreeItem<Row> fileItem = new TreeItem<>(new Row(null, file, null));
                fileItem.setExpanded(true);
                for (LspDiagnostic d : sorted) {
                    fileItem.getChildren().add(new TreeItem<>(new Row(null, file, d)));
                }
                langItem.getChildren().add(fileItem);
            }
            root.getChildren().add(langItem);
        }
        tree.setRoot(root);
        summary.setText(total == 0 ? tr("problems.none") : tr("problems.summary", total, files));
    }

    /** Friendly display name for a file's programming language (proper nouns, deliberately untranslated). */
    private static String languageOf(Path file) {
        String name = LanguageRegistry.forFileName(file.toString());
        return switch (name) {
            case "java" -> "Java";
            case "javascript" -> "JavaScript";
            case "javascriptreact" -> "JavaScript (JSX)";
            case "typescript" -> "TypeScript";
            case "typescriptreact" -> "TypeScript (TSX)";
            default -> name == null || name.isEmpty()
                    ? "Other"
                    : Character.toUpperCase(name.charAt(0)) + name.substring(1);
        };
    }

    @Override
    public void focusFirstItem() {
        tree.requestFocus();
        if (tree.getExpandedItemCount() > 0 && tree.getSelectionModel().isEmpty()) {
            tree.getSelectionModel().select(0);
        }
    }

    /** Renders a language header, a file header (name + count), or a diagnostic (severity glyph + line). */
    private static final class RowCell extends TreeCell<Row> {
        @Override
        protected void updateItem(Row row, boolean empty) {
            super.updateItem(row, empty);
            getStyleClass().removeAll("search-file-row", "problem-lang-row", "problem-error",
                    "problem-warning", "problem-info");
            if (empty || row == null) {
                setText(null);
                return;
            }
            if (row.diag() != null) {
                LspDiagnostic d = row.diag();
                String msg = d.message().replace('\n', ' ').strip();
                if (msg.length() > 200) {
                    msg = msg.substring(0, 200) + "…";
                }
                setText(glyph(d.severity()) + "  " + (d.startLine() + 1) + ":  " + msg);
                getStyleClass().add(switch (d.severity()) {
                    case ERROR -> "problem-error";
                    case WARNING -> "problem-warning";
                    default -> "problem-info";
                });
            } else if (row.file() != null) {
                int count = getTreeItem() == null ? 0 : getTreeItem().getChildren().size();
                setText(row.file().getFileName() + "  (" + count + ")");
                getStyleClass().add("search-file-row");
            } else {
                setText(row.language() + "  (" + diagnosticCount() + ")");
                getStyleClass().add("problem-lang-row");
            }
        }

        /** Total diagnostics under a language header = sum of its file children's diagnostic counts. */
        private int diagnosticCount() {
            int count = 0;
            if (getTreeItem() != null) {
                for (TreeItem<Row> fileItem : getTreeItem().getChildren()) {
                    count += fileItem.getChildren().size();
                }
            }
            return count;
        }

        private static String glyph(LspDiagnostic.Severity s) {
            return switch (s) {
                case ERROR -> "✖";   // ✖
                case WARNING -> "⚠"; // ⚠
                default -> "ℹ";      // ℹ
            };
        }
    }
}
