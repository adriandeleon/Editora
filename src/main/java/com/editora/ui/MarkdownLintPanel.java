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

import com.editora.editor.MarkdownLintText;
import com.editora.markdown.MarkdownLint;

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

    /** What can be done about an issue of the active buffer. */
    public interface IssueActions {
        /** Applies every safe fix to the file. */
        void fixAll();

        void fix(MarkdownLint.Diagnostic d);

        void disableLine(MarkdownLint.Diagnostic d);

        void disableRule(MarkdownLint.Diagnostic d);

        /** Opens the rule's documentation. */
        void help(MarkdownLint.Diagnostic d);
    }

    private final Actions actions;
    private final IssueActions issues;
    private final Label fileName = new Label();
    private final Label summary = new Label();
    private final Label configNote = new Label();
    private final ListView<MarkdownLint.Diagnostic> list = new ListView<>();
    private Button fixBtn;
    private Path file;

    public MarkdownLintPanel(Actions actions) {
        this(null, actions);
    }

    public MarkdownLintPanel(IssueActions issues, Actions actions) {
        this.actions = actions;
        this.issues = issues;
        getStyleClass().add("markdown-lint-panel");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setSpacing(6);
        setPadding(new Insets(6));
        build();
    }

    private void build() {
        Button refreshBtn = Icons.toolbarButton(Icons.refresh(), tr("markdownLint.refresh"), actions::refresh);
        fileName.getStyleClass().add("markdown-lint-file");
        summary.getStyleClass().add("markdown-lint-summary");
        configNote.getStyleClass().add("markdown-lint-summary");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox toolbar = new HBox(8, fileName, summary, configNote, gap, refreshBtn);
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
        if (d == null) {
            return List.of();
        }
        List<MenuItem> items = new java.util.ArrayList<>();
        if (issues != null && file != null) {
            MenuItem fix = new MenuItem(tr("markdownLint.menu.fix"));
            fix.setDisable(!MarkdownLint.fixable(d.code()));
            fix.setOnAction(e -> issues.fix(d));
            MenuItem line = new MenuItem(tr("markdownLint.menu.disableLine"));
            line.setOnAction(e -> issues.disableLine(d));
            MenuItem rule = new MenuItem(tr("markdownLint.menu.disableRule", d.code()));
            rule.setOnAction(e -> issues.disableRule(d));
            MenuItem help = new MenuItem(tr("markdownLint.menu.help"));
            help.setOnAction(e -> issues.help(d));
            items.addAll(List.of(fix, line, rule, help, new SeparatorMenuItem()));
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
        if (d != null && file != null) {
            actions.open(file, d.line(), d.column());
        }
    }

    /** Populates the list from the active buffer's diagnostics (called on the FX thread by the controller). */
    public void setResults(Path file, List<MarkdownLint.Diagnostic> diagnostics) {
        setResults(file, file == null ? "" : String.valueOf(file.getFileName()), diagnostics, "");
    }

    /**
     * As {@link #setResults(Path, List)}, naming the buffer the rows belong to ({@code name}: an unsaved buffer
     * has no path) and noting the rules its project config turns off ({@code configNote}, "" for none).
     */
    public void setResults(Path file, String name, List<MarkdownLint.Diagnostic> diagnostics, String configNote) {
        this.file = file;
        // Keep the selected row across a re-lint (every edit refills the list): same rule on the same line.
        MarkdownLint.Diagnostic was = list.getSelectionModel().getSelectedItem();
        list.getItems().setAll(diagnostics == null ? List.of() : diagnostics);
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
    public void showNotLinted(Path file) {
        setResults(null, file == null ? "" : String.valueOf(file.getFileName()), List.of(), "");
        summary.setText(tr("status.markdownLint.notMarkdown"));
    }

    @Override
    public void focusFirstItem() {
        actions.refresh();
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
                return;
            }
            Region tag = new Region();
            tag.getStyleClass().add("markdown-lint-tag");
            tag.getStyleClass().add(d.isError() ? "lint-error" : "lint-warning");
            tag.setMinSize(10, 10);
            tag.setPrefSize(10, 10);
            tag.setMaxSize(10, 10);
            setGraphic(tag);
            setText(rowText(d));
        }
    }
}
