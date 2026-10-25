package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.Clipboard;
import javafx.scene.input.DataFormat;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.editora.editor.markdown.MarkdownLintText;
import com.editora.markdown.MarkdownLint;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The "Markdown Lint" tool window: a flat list of the active Markdown buffer's lint diagnostics
 * (Enter / double-click jumps to one; the row menu fixes it, silences it, opens its rule's documentation or
 * copies it). Mirrors {@link TodoPanel} ({@link ToolWindowContent}); work is routed back to the controller via
 * {@link Actions} and {@link IssueActions}. Scoped to the active buffer (single file), whose name it shows.
 */
public final class MarkdownLintPanel extends VBox implements ToolWindowContent {

    /** Controller callbacks. */
    public interface Actions {
        void open(Path file, int line, int col);

        void refresh();
    }

    /** Opens {@code file} at a 1-based line and column. */
    @FunctionalInterface
    public interface Opener {
        void open(Path file, int line, int col);
    }

    /** {@link Actions} from two lambdas. */
    public static Actions actions(Opener opener, Runnable refresh) {
        return new Actions() {
            @Override
            public void open(Path file, int line, int col) {
                opener.open(file, line, col);
            }

            @Override
            public void refresh() {
                refresh.run();
            }
        };
    }

    /** One file of a project lint: where it is, what to call it, what was found in it. */
    public record FileIssues(Path file, String name, List<MarkdownLint.Diagnostic> issues) {}

    /** What can be done about an issue of the active buffer. */
    public interface IssueActions {
        /** Applies every safe fix to the file. */
        void fixAll();

        void fix(MarkdownLint.Diagnostic d);

        void disableLine(MarkdownLint.Diagnostic d);

        void disableRule(MarkdownLint.Diagnostic d);

        /** Switches the issue's rule off for the whole file (a {@code markdownlint-disable-file} comment). */
        default void disableFile(MarkdownLint.Diagnostic d) {}

        /** Switches the issue's rule off in the project's markdownlint config. */
        default void disableProject(MarkdownLint.Diagnostic d) {}

        /** Opens the rule's documentation. */
        void help(MarkdownLint.Diagnostic d);
    }

    private final Actions actions;
    private final @Nullable IssueActions issues;
    private final Label fileName = new Label();
    private final Label summary = new Label();
    private final Label configNote = new Label();
    private final ListView<MarkdownLint.Diagnostic> list = new ListView<>();
    private @Nullable Button fixBtn;
    private @Nullable Path file;
    /** The rows as they came (document order), so the severity sort can be switched off again. */
    private List<MarkdownLint.Diagnostic> unsorted = List.of();

    private final javafx.scene.control.ToggleButton bySeverity = new javafx.scene.control.ToggleButton();
    /** In a project listing: the file each row belongs to; empty while one file is listed. */
    private final java.util.Map<MarkdownLint.Diagnostic, Path> fileOf = new java.util.IdentityHashMap<>();

    private boolean project;

    public MarkdownLintPanel(Actions actions) {
        this(null, actions);
    }

    public MarkdownLintPanel(@Nullable IssueActions issues, Actions actions) {
        this.actions = actions;
        this.issues = issues;
        getStyleClass().add("markdown-lint-panel");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setSpacing(6);
        setPadding(new Insets(6));
        build();
    }

    private void build() {
        Button refreshBtn = Icons.toolbarButton(Icons.refresh(), tr("markdownLint.refresh"), () -> {
            project = false; // Refresh goes back to the active file
            actions.refresh();
        });
        bySeverity.setGraphic(Icons.arrowDown());
        Icons.name(bySeverity, tr("markdownLint.sortBySeverity"));
        bySeverity.setFocusTraversable(false);
        bySeverity.selectedProperty().addListener((o, was, on) -> showRows());
        fileName.getStyleClass().add("markdown-lint-file");
        summary.getStyleClass().add("markdown-lint-summary");
        configNote.getStyleClass().add("markdown-lint-summary");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox toolbar = new HBox(8, fileName, summary, configNote, gap, bySeverity, refreshBtn);
        if (issues != null) {
            fixBtn = Icons.toolbarButton(Icons.check(), tr("command.markdownLint.fix"), issues::fixAll);
            fixBtn.setDisable(true);
            toolbar.getChildren().add(fixBtn);
        }
        toolbar.setAlignment(Pos.CENTER_LEFT);

        list.setCellFactory(v -> new DiagnosticCell());
        list.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                activateSelected();
            }
        });
        // One menu for the list, rebuilt for the selected row: it also answers the Menu key / Shift+F10.
        ContextMenu menu = new ContextMenu();
        list.setContextMenu(menu);
        // A filter: the control's own handler shows the menu, so the items must be in place before it runs.
        list.addEventFilter(javafx.scene.input.ContextMenuEvent.CONTEXT_MENU_REQUESTED, e -> {
            menu.getItems().setAll(rowMenu(list.getSelectionModel().getSelectedItem()));
            if (menu.getItems().isEmpty()) {
                e.consume(); // no row selected: nothing to offer
            }
        });
        VBox.setVgrow(list, Priority.ALWAYS);
        addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);

        getChildren().addAll(toolbar, list);
    }

    /** The actions on row {@code d}: fix / disable / rule help (when the controller offers them), then Copy. */
    List<MenuItem> rowMenu(MarkdownLint.Diagnostic d) {
        if (d == null || isHeader(d)) {
            return List.of();
        }
        List<MenuItem> items = new java.util.ArrayList<>();
        if (issues != null && file != null && !project) {
            MenuItem fix = new MenuItem(tr("markdownLint.menu.fix"));
            fix.setDisable(!MarkdownLint.fixable(d.code()));
            fix.setOnAction(e -> issues.fix(d));
            MenuItem line = new MenuItem(tr("markdownLint.menu.disableLine"));
            line.setOnAction(e -> issues.disableLine(d));
            MenuItem inFile = new MenuItem(tr("markdownLint.menu.disableFile", d.code()));
            inFile.setOnAction(e -> issues.disableFile(d));
            MenuItem inProject = new MenuItem(tr("markdownLint.menu.disableProject", d.code()));
            inProject.setOnAction(e -> issues.disableProject(d));
            MenuItem rule = new MenuItem(tr("markdownLint.menu.disableRule", d.code()));
            rule.setOnAction(e -> issues.disableRule(d));
            MenuItem help = new MenuItem(tr("markdownLint.menu.help"));
            help.setOnAction(e -> issues.help(d));
            items.addAll(List.of(fix, line, inFile, inProject, rule, help, new SeparatorMenuItem()));
        }
        MenuItem copy = new MenuItem(tr("markdownLint.menu.copy"));
        copy.setOnAction(e -> Clipboard.getSystemClipboard().setContent(Map.of(DataFormat.PLAIN_TEXT, rowText(d))));
        items.add(copy);
        return items;
    }

    private void onKey(KeyEvent e) {
        switch (e.getCode()) {
            case ENTER -> {
                activateSelected();
                e.consume();
            }
            case DOWN -> {
                move(1);
                e.consume();
            }
            case UP -> {
                move(-1);
                e.consume();
            }
            default -> {
                if (!e.isControlDown()) {
                    return;
                }
                switch (e.getCode()) {
                    case N -> {
                        move(1);
                        e.consume();
                    }
                    case P -> {
                        move(-1);
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

    private void move(int delta) {
        int rows = list.getItems().size();
        if (rows == 0) {
            return;
        }
        int i = list.getSelectionModel().getSelectedIndex();
        int next = Math.max(0, Math.min(rows - 1, (i < 0 ? 0 : i) + delta));
        list.getSelectionModel().select(next);
        list.scrollTo(next);
    }

    private void activateSelected() {
        MarkdownLint.Diagnostic d = list.getSelectionModel().getSelectedItem();
        Path target = d == null ? null : project ? fileOf.get(d) : file;
        if (target != null) {
            actions.open(target, Math.max(1, d.line()), Math.max(1, d.column()));
        }
    }

    /** Whether the list shows a project's results (kept until Refresh or the next project lint). */
    public boolean showingProject() {
        return project;
    }

    /** A project lint has started: says so until {@link #setProjectResults} arrives. */
    public void showProjectScanning(String projectName) {
        project = true;
        file = null;
        fileOf.clear();
        unsorted = List.of();
        list.getItems().clear();
        fileName.setText(projectName == null ? "" : projectName);
        summary.setText(tr("markdownLint.projectScanning"));
        configNote.setText("");
        if (fixBtn != null) {
            fixBtn.setDisable(true);
        }
    }

    /**
     * Lists a project's issues grouped by file: one header row per file (its path inside the project), its
     * issues under it. Activating a row opens that file there.
     */
    public void setProjectResults(String projectName, List<FileIssues> files, boolean truncated) {
        project = true;
        file = null;
        fileOf.clear();
        List<MarkdownLint.Diagnostic> rows = new java.util.ArrayList<>();
        int total = 0;
        for (FileIssues f : files) {
            MarkdownLint.Diagnostic header = new MarkdownLint.Diagnostic(0, 0, 1, HEADER, "", f.name());
            fileOf.put(header, f.file());
            rows.add(header);
            for (MarkdownLint.Diagnostic d : f.issues()) {
                fileOf.put(d, f.file());
                rows.add(d);
            }
            total += f.issues().size();
        }
        unsorted = rows;
        list.getItems().setAll(rows);
        fileName.setText(projectName == null ? "" : projectName);
        summary.setText(total == 0 ? tr("markdownLint.none") : tr("markdownLint.projectSummary", total, files.size()));
        configNote.setText(truncated ? tr("markdownLint.projectTruncated") : "");
        if (fixBtn != null) {
            fixBtn.setDisable(true);
        }
    }

    private static final String HEADER = "file";

    private static boolean isHeader(MarkdownLint.Diagnostic d) {
        return HEADER.equals(d.severity());
    }

    /** Fills the list from {@link #unsorted}: as found, or most severe first (a project stays grouped by file). */
    private void showRows() {
        List<MarkdownLint.Diagnostic> rows = new java.util.ArrayList<>(unsorted);
        if (bySeverity.isSelected() && !project) {
            rows.sort(java.util.Comparator.comparingInt(MarkdownLint.Diagnostic::severityRank)); // stable
        }
        list.getItems().setAll(rows);
    }

    /** Populates the list from the active buffer's diagnostics (called on the FX thread by the controller). */
    public void setResults(Path file, List<MarkdownLint.Diagnostic> diagnostics) {
        setResults(file, file == null ? "" : String.valueOf(file.getFileName()), diagnostics, "");
    }

    /**
     * As {@link #setResults(Path, List)}, naming the buffer the rows belong to ({@code name}: an unsaved buffer
     * has no path) and noting the rules its project config turns off ({@code configNote}, "" for none).
     */
    public void setResults(
            @Nullable Path file, String name, List<MarkdownLint.Diagnostic> diagnostics, String configNote) {
        this.file = file;
        project = false;
        fileOf.clear();
        // Keep the selected row across a re-lint (every edit refills the list): same rule on the same line.
        MarkdownLint.Diagnostic was = list.getSelectionModel().getSelectedItem();
        unsorted = diagnostics == null ? List.of() : diagnostics;
        showRows();
        if (was != null) {
            for (MarkdownLint.Diagnostic d : list.getItems()) {
                if (d.line() == was.line() && d.code().equals(was.code())) {
                    list.getSelectionModel().select(d);
                    break;
                }
            }
        }
        int n = list.getItems().size();
        fileName.setText(name == null ? "" : name);
        summary.setText(n == 0 ? tr("markdownLint.none") : tr("markdownLint.summary", n));
        this.configNote.setText(configNote == null ? "" : configNote);
        if (fixBtn != null) {
            fixBtn.setDisable(list.getItems().stream().noneMatch(d -> MarkdownLint.fixable(d.code())));
        }
    }

    /** The active tab is not a lintable Markdown buffer: an empty list that says so, instead of stale rows. */
    public void showNotLinted(@Nullable Path file) {
        setResults(null, file == null ? "" : String.valueOf(file.getFileName()), List.of(), "");
        summary.setText(tr("status.markdownLint.notMarkdown"));
    }

    @Override
    public void focusFirstItem() {
        if (!project) {
            actions.refresh(); // a project's results are not the active file's: opening the window keeps them
        }
        list.requestFocus();
        if (!list.getItems().isEmpty()) {
            list.getSelectionModel().select(0);
        }
    }

    /** "Ln L:C  CODE  message" — a row's text, also what Copy puts on the clipboard. */
    static String rowText(MarkdownLint.Diagnostic d) {
        return tr("markdownLint.row", d.line(), d.column()) + "  " + d.code() + "  " + MarkdownLintText.message(d);
    }

    /** A row: a severity dot + {@link #rowText}. */
    private static final class DiagnosticCell extends ListCell<MarkdownLint.Diagnostic> {
        @Override
        protected void updateItem(MarkdownLint.Diagnostic d, boolean empty) {
            super.updateItem(d, empty);
            if (empty || d == null) {
                setText(null);
                setGraphic(null);
                getStyleClass().remove("markdown-lint-file-row");
                return;
            }
            if (isHeader(d)) {
                setGraphic(null);
                setText(d.message());
                getStyleClass().add("markdown-lint-file-row");
                return;
            }
            getStyleClass().remove("markdown-lint-file-row");
            Region tag = new Region();
            tag.getStyleClass().add("markdown-lint-tag");
            tag.getStyleClass().add(d.isError() ? "lint-error" : d.isHint() ? "lint-hint" : "lint-warning");
            tag.setMinSize(10, 10);
            tag.setPrefSize(10, 10);
            tag.setMaxSize(10, 10);
            setGraphic(tag);
            setText(rowText(d));
        }
    }
}
