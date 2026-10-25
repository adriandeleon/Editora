package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;

import com.editora.config.PathDisplay;
import org.fxmisc.richtext.model.StyleSpan;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The "References" tool window: the results of Find References ({@code textDocument/references}), grouped
 * <b>by file</b> and browsable (unlike the transient picker). Enter / double-click jumps to a reference; a
 * file header toggles. Mirrors {@link ProblemsPanel} ({@link ToolWindowContent}); jumps route back through
 * {@link Actions}. The coordinator pushes results via {@link #setReferences}.
 */
public final class ReferencesPanel extends VBox implements ToolWindowContent {

    /** Controller callback to open a file at a 0-based reference position. */
    public interface Actions {
        void open(@Nullable Path file, int line, int col);
    }

    /**
     * One reference occurrence: a file + 0-based line/col + a one-line preview ({@code ""} when unavailable).
     * {@code runs} is the same preview cut into syntax-styled pieces (see {@link #previewRuns}); empty when
     * the line's highlighting is unknown, and the preview then shows as plain text.
     */
    public record Reference(@Nullable Path file, int line, int col, String preview, List<Run> runs, boolean write) {
        public Reference {
            runs = runs == null ? List.of() : List.copyOf(runs);
        }

        public Reference(@Nullable Path file, int line, int col, String preview, List<Run> runs) {
            this(file, line, col, preview, runs, false);
        }

        public Reference(Path file, int line, int col, String preview) {
            this(file, line, col, preview, List.of());
        }
    }

    /**
     * Whether the occurrence of a {@code length}-character name at {@code col} of {@code line} (the whole source
     * line) writes it: assigned ({@code =}, {@code +=} … but not {@code ==}), or incremented or decremented,
     * before or after. A text rule, as jdtls's references carry no kind; IntelliJ marks these rows the same way
     * (Java review B6). Pure.
     */
    static boolean writes(@Nullable String line, int col, int length) {
        if (line == null || col < 0 || length <= 0 || col + length > line.length()) {
            return false;
        }
        String before = line.substring(0, col).stripTrailing();
        if (before.endsWith("++") || before.endsWith("--")) {
            return true;
        }
        String after = line.substring(col + length).stripLeading();
        if (after.startsWith("++") || after.startsWith("--")) {
            return true;
        }
        for (String op : List.of(">>>=", "<<=", ">>=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=")) {
            if (after.startsWith(op)) {
                return true;
            }
        }
        return after.startsWith("=") && !after.startsWith("==");
    }

    /** A piece of a preview and the editor syntax classes ({@code keyword}, {@code string}, …) it carries. */
    public record Run(String text, List<String> styles) {}

    /** A tree row: a <b>file header</b> ({@code ref == null}) or a <b>reference</b> ({@code ref != null}). */
    private record Row(Path file, @Nullable Reference ref) {}

    private final Actions actions;
    private final Label summary = new Label();
    private final TreeView<Row> tree = new TreeView<>();
    /** Bumped by every {@link #setReferences}, so a late preview fill for an older list is dropped. */
    private long generation;
    /** The folder a file header's location is shown relative to (the project), or null for the full parent. */
    private @Nullable Path base;
    /** The symbol the list is about, whose length tells a write from a read in a late-filled preview. */
    private @Nullable String symbol;

    public ReferencesPanel(Actions actions) {
        this.actions = actions;
        getStyleClass().add("references-panel");
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
        setReferences(List.of());
    }

    private void onKey(KeyEvent e) {
        switch (e.getCode()) {
            case ENTER -> {
                activateSelected();
                e.consume();
            }
            case DOWN -> {
                moveSelection(1);
                e.consume();
            }
            case UP -> {
                moveSelection(-1);
                e.consume();
            }
            default -> {
                if (!e.isControlDown()) {
                    return;
                }
                switch (e.getCode()) {
                    case N -> {
                        moveSelection(1);
                        e.consume();
                    }
                    case P -> {
                        moveSelection(-1);
                        e.consume();
                    }
                    case M -> {
                        activateSelected();
                        e.consume();
                    }
                    default -> {}
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
        if (row.ref() != null) {
            actions.open(row.ref().file(), row.ref().line(), row.ref().col());
        } else {
            item.setExpanded(!item.isExpanded());
        }
    }

    /** Rebuilds the tree, grouping {@code refs} by file (called on the FX thread). Selects the first row. */
    public void setReferences(List<Reference> refs) {
        setReferences(refs, null, null);
    }

    /**
     * As above, with the symbol the list is about ({@code area()}) in the summary and each file's folder,
     * relative to {@code base}, on its header: two {@code Main.java} in different packages looked the same,
     * and after a second search nothing said whose references these were (Java review B6).
     */
    public void setReferences(List<Reference> refs, @Nullable String symbol, @Nullable Path base) {
        generation++;
        this.base = base;
        this.symbol = symbol;
        Map<Path, List<Reference>> byFile = new LinkedHashMap<>();
        for (Reference r : refs) {
            byFile.computeIfAbsent(r.file(), k -> new ArrayList<>()).add(r);
        }
        List<Map.Entry<Path, List<Reference>>> files = new ArrayList<>(byFile.entrySet());
        files.sort(Comparator.comparing(e -> PathDisplay.fileName(e.getKey()).toLowerCase(Locale.ROOT)));

        TreeItem<Row> root = new TreeItem<>();
        for (Map.Entry<Path, List<Reference>> entry : files) {
            Path file = entry.getKey();
            List<Reference> list = entry.getValue();
            list.sort((a, b) ->
                    a.line() != b.line() ? Integer.compare(a.line(), b.line()) : Integer.compare(a.col(), b.col()));
            TreeItem<Row> fileItem = new TreeItem<>(new Row(file, null));
            fileItem.setExpanded(true);
            for (Reference r : list) {
                fileItem.getChildren().add(new TreeItem<>(new Row(file, r)));
            }
            root.getChildren().add(fileItem);
        }
        tree.setRoot(root);
        summary.setText(
                refs.isEmpty()
                        ? tr("references.none")
                        : symbol == null || symbol.isBlank()
                                ? tr("references.summary", refs.size(), files.size())
                                : tr("references.summaryOf", refs.size(), symbol, files.size()));
        if (tree.getExpandedItemCount() > 0) {
            tree.getSelectionModel().select(0);
        }
    }

    /** The current list's generation, for {@link #fillPreviews}. */
    public long generation() {
        return generation;
    }

    /**
     * Fills in the previews of rows that have none — files without a tab, read from disk off the FX thread —
     * when the list is still {@code forGeneration}. {@code lineOf} answers a row's whole source line, or empty;
     * a row that writes the symbol is marked from it.
     */
    public void fillPreviews(long forGeneration, java.util.function.Function<Reference, String> lineOf) {
        if (forGeneration != generation) {
            return;
        }
        for (TreeItem<Row> fileItem : tree.getRoot().getChildren()) {
            for (TreeItem<Row> item : fileItem.getChildren()) {
                Reference r = item.getValue().ref();
                if (r == null || !r.preview().isEmpty()) {
                    continue;
                }
                String line = lineOf.apply(r);
                String preview = line == null ? "" : line.strip();
                if (!preview.isEmpty()) {
                    boolean write = symbol != null && writes(line, r.col(), symbol.length());
                    item.setValue(new Row(
                            item.getValue().file(),
                            new Reference(r.file(), r.line(), r.col(), preview, List.of(), write)));
                }
            }
        }
    }

    /**
     * A file header's folder: relative to {@code base} when inside it, else the whole parent path; none without
     * a base (a list not shown for a project). Pure.
     */
    static String folderOf(@Nullable Path file, @Nullable Path base) {
        Path parent = file == null ? null : file.getParent();
        if (parent == null || base == null) {
            return "";
        }
        if (parent.startsWith(base)) {
            Path rel = base.relativize(parent);
            return rel.toString().isEmpty() ? "" : rel.toString().replace('\\', '/');
        }
        return parent.toString();
    }

    /**
     * Cuts {@code line} (a whole source line) into the runs of its stripped preview, styled by {@code spans}
     * — the editor's own style spans for that line, so the preview is colored exactly as the editor colors
     * it. Only syntax classes are kept; editor-state classes (a matched brace, a diagnostic) are dropped.
     * Returns an empty list when there is nothing to style. Pure; unit-tested.
     */
    static List<Run> previewRuns(String line, Iterable<StyleSpan<Collection<String>>> spans) {
        if (line == null || spans == null) {
            return List.of();
        }
        int from = 0;
        int to = line.length();
        while (from < to && Character.isWhitespace(line.charAt(from))) {
            from++;
        }
        while (to > from && Character.isWhitespace(line.charAt(to - 1))) {
            to--;
        }
        List<Run> runs = new ArrayList<>();
        int pos = 0;
        for (StyleSpan<Collection<String>> span : spans) {
            int start = Math.max(pos, from);
            pos += span.getLength();
            int end = Math.min(pos, to);
            if (end > start) {
                addRun(runs, line.substring(start, end), syntaxStyles(span.getStyle()));
            }
        }
        if (runs.isEmpty()) {
            return List.of();
        }
        if (Math.max(pos, from) < to) {
            addRun(runs, line.substring(Math.max(pos, from), to), List.of());
        }
        return List.copyOf(runs);
    }

    private static void addRun(List<Run> runs, String text, List<String> styles) {
        if (!runs.isEmpty() && runs.getLast().styles().equals(styles)) {
            Run previous = runs.removeLast();
            runs.add(new Run(previous.text() + text, styles));
        } else {
            runs.add(new Run(text, styles));
        }
    }

    private static List<String> syntaxStyles(Collection<String> styles) {
        if (styles == null || styles.isEmpty()) {
            return List.of();
        }
        return styles.stream().filter(ReferencesPanel::isSyntaxStyle).toList();
    }

    private static boolean isSyntaxStyle(String style) {
        if (style == null) {
            return false;
        }
        if (style.startsWith("sem-") || style.startsWith("bracket-depth-")) {
            return true;
        }
        return switch (style) {
            case "annotation",
                    "attribute",
                    "bold",
                    "code",
                    "comment",
                    "constant",
                    "escape",
                    "function",
                    "heading",
                    "italic",
                    "keyword",
                    "link",
                    "number",
                    "operator",
                    "property",
                    "punct",
                    "regexp",
                    "string",
                    "tag",
                    "type",
                    "variable" -> true;
            default -> false;
        };
    }

    @Override
    public void focusFirstItem() {
        tree.requestFocus();
        if (tree.getExpandedItemCount() > 0 && tree.getSelectionModel().isEmpty()) {
            tree.getSelectionModel().select(0);
        }
    }

    /** Renders a file header (name + count + folder) or a reference (line + syntax-colored preview). */
    private final class RowCell extends TreeCell<Row> {
        @Override
        protected void updateItem(Row row, boolean empty) {
            super.updateItem(row, empty);
            getStyleClass().removeAll("search-file-row");
            setContentDisplay(ContentDisplay.LEFT);
            if (empty || row == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            if (row.ref() != null) {
                Reference r = row.ref();
                String position =
                        (r.line() + 1) + ":" + (r.col() + 1) + (r.write() ? "  " + tr("references.write") : "");
                String preview = r.preview() == null ? "" : r.preview().strip();
                if (preview.isEmpty() || r.runs().isEmpty()) {
                    setText(position + (preview.isEmpty() ? "" : "  " + preview));
                    setGraphic(null);
                } else {
                    // The position stays the cell's text; the code follows it as the graphic.
                    setText(position + "  ");
                    setGraphic(code(r.runs()));
                    setContentDisplay(ContentDisplay.RIGHT);
                }
            } else {
                int count =
                        getTreeItem() == null ? 0 : getTreeItem().getChildren().size();
                String folder = folderOf(row.file(), base);
                setText(row.file().getFileName() + "  (" + count + ")" + (folder.isEmpty() ? "" : "  ·  " + folder));
                setGraphic(FileIcons.forFileName(String.valueOf(row.file().getFileName())));
                getStyleClass().add("search-file-row");
            }
        }

        private static HBox code(List<Run> runs) {
            HBox box = new HBox();
            box.setAlignment(Pos.CENTER_LEFT);
            for (Run run : runs) {
                Text text = new Text(run.text());
                // `text` + the syntax classes are what syntax.css and every editor theme target, so the
                // preview follows the editor color theme with no rules of its own.
                text.getStyleClass().addAll("reference-code", "text");
                text.getStyleClass().addAll(run.styles());
                box.getChildren().add(text);
            }
            return box;
        }
    }
}
