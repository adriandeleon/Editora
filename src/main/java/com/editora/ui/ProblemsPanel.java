package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.editora.config.PathDisplay;
import com.editora.editor.UiZoom;
import com.editora.text.LanguageRegistry;
import com.editora.text.LspDiagnostic;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The "Problems" tool window: LSP diagnostics across open buffers, grouped <b>by programming language →
 * file → diagnostic</b> (Enter / double-click jumps to one; a language/file header toggles). Mirrors
 * {@link SearchPanel} ({@link ToolWindowContent}); jumps are routed back to the controller via
 * {@link Actions}. The controller pushes the current diagnostics map via {@link #setProblems}.
 */
public final class ProblemsPanel extends VBox implements ToolWindowContent {

    /** Controller callbacks: open a file at a diagnostic position (0-based line/col), and change the
     *  scope between open files and the whole project (#743). */
    public interface Actions {
        void open(Path file, int line, int col);

        /** The user picked a scope in the selector. Default no-op keeps older callers compiling. */
        default void setProjectWide(boolean projectWide) {}

        /** Opens a rule's documentation page (a diagnostic's {@code codeDescription.href}) in the browser. */
        default void openUrl(String url) {}
    }

    /**
     * A tree row, one of three kinds: a <b>language header</b> ({@code language != null}, no file/diag),
     * a <b>file header</b> ({@code file != null}, no diag), or a <b>diagnostic</b> ({@code diag != null}).
     */
    private record Row(
            @Nullable String language,
            @Nullable Path file,
            @Nullable LspDiagnostic diag) {}

    private final Actions actions;
    private final Label summary = new Label();
    private final TreeView<Row> tree = new TreeView<>();

    // Cached last diagnostics + the active file, so the tree can be re-sorted (active file first) on a tab
    // switch without new diagnostics arriving. activeFile is the canonical form (matches the LSP keys).
    private final ComboBox<String> scope = new ComboBox<>();
    /** Guards the selector's own action handler while the controller syncs it (#743). */
    private boolean syncingScope;

    private Map<Path, List<LspDiagnostic>> lastByFile = Map.of();
    private @Nullable Path activeFile;

    public ProblemsPanel(Actions actions) {
        this.actions = actions;
        getStyleClass().add("problems-panel");
        tree.setAccessibleText(tr("toolwindow.problems"));
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        UiZoom.spacing(this, 6);
        UiZoom.padding(this, 6);
        build();
    }

    private void build() {
        summary.getStyleClass().add("search-summary");
        tree.setShowRoot(false);
        tree.setRoot(new TreeItem<>());
        tree.setCellFactory(t -> new RowCell());
        RowContextMenu.install(tree); // Menu key / Shift+F10 open the selected row's menu (cells are not focusable)
        // The diagnostic menu's commands (problems.copySelectedCode…) fire its rows for the selected diagnostic.
        FocusCommands.menu(tree, () -> {
            TreeItem<Row> item = tree.getSelectionModel().getSelectedItem();
            LspDiagnostic d = item == null || item.getValue() == null
                    ? null
                    : item.getValue().diag();
            ContextMenu menu = d == null ? null : contextMenuFor(d);
            return menu == null ? null : menu.getItems();
        });
        tree.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                activateSelected();
            }
        });
        VBox.setVgrow(tree, Priority.ALWAYS);
        addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        // Scope selector (#743). Open-files is the default and matches the long-standing behaviour; whole-
        // project is only useful once something has actually built the project, so it stays opt-in.
        scope.getItems().setAll(tr("problems.scope.openFiles"), tr("problems.scope.project"));
        scope.getSelectionModel().select(0);
        scope.setAccessibleText(tr("problems.scope.name"));
        scope.setOnAction(e -> {
            if (!syncingScope) {
                actions.setProjectWide(scope.getSelectionModel().getSelectedIndex() == 1);
            }
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = UiZoom.spacing(new HBox(summary, spacer, scope), 8);
        header.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        getChildren().addAll(header, tree);
        rebuild(); // the empty state, built once so a panel shown before any diagnostics is not blank
        // A closed tool window is out of the scene; catch up once when it is (re)opened.
        sceneProperty().addListener((o, was, now) -> {
            if (now != null && pendingRebuild) {
                pendingRebuild = false;
                refresh();
            }
        });
    }

    // Diagnostics change on every typing pause and with every file a server analyses, whether or not this
    // tool window is open. While it is closed nothing is compared, copied or rebuilt: the latest map is
    // remembered and the tree catches up in one rebuild when the window is shown. (Same idiom as the
    // Structure panel, #549.)
    private boolean pendingRebuild;
    private boolean activeFileChanged;

    /** The tree is only worth rebuilding while the tool window is open (its node is in the scene). */
    private boolean visible() {
        return getScene() != null;
    }

    /** Reflects the current scope in the selector without firing its handler (#743). */
    public void setProjectWide(boolean projectWide) {
        syncingScope = true;
        scope.getSelectionModel().select(projectWide ? 1 : 0);
        syncingScope = false;
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
        if (row.diag() != null) {
            if (row.file() != null) { // a diagnostic row always carries its file
                actions.open(row.file(), row.diag().startLine(), row.diag().startCol());
            }
        } else {
            item.setExpanded(!item.isExpanded()); // language or file header
        }
    }

    /**
     * Shows {@code byFile}, grouped by language → file (called on the FX thread). Only what differs from what the
     * tree shows is touched: a server re-publishing identical diagnostics — which most do on every keystroke for
     * every unaffected file — costs a comparison per file, and a file whose problems changed has its own branch
     * replaced, not the whole tree rebuilt (perf review 2026-11-23 L1). When many files changed at once the tree is
     * built afresh, which is then the cheaper way.
     */
    public void setProblems(Map<Path, List<LspDiagnostic>> byFile) {
        lastByFile = byFile == null ? Map.of() : byFile;
        if (!visible()) {
            pendingRebuild = true;
            return;
        }
        refresh();
    }

    /**
     * What the tree shows, by file: the non-empty lists only, the very lists it was handed (callers hand in their
     * live map, so it cannot be compared to itself; the lists in it are replaced, never changed).
     */
    private @Nullable Map<Path, List<LspDiagnostic>> rendered;

    /** Each file's header row, and the language it is filed under. */
    private final Map<Path, TreeItem<Row>> fileItems = new HashMap<>();

    private final Map<Path, String> fileLanguages = new HashMap<>();

    /** Each language's header row. */
    private final Map<String, TreeItem<Row>> languageItems = new HashMap<>();

    /** The diagnostics the tree lists. */
    private int shownTotal;

    private int rebuilds;
    private int fullRebuilds;

    /** How many times the tree has changed, a file's branch or all of it — a test shows a burst costs one. */
    int rebuildCount() {
        return rebuilds;
    }

    /** How many times the whole tree was built afresh (tests). */
    int fullRebuildCount() {
        return fullRebuilds;
    }

    /** At most this many changed files are always applied branch by branch (more: see {@link #incremental}). */
    static final int INCREMENTAL_FILES = 64;

    /**
     * Pure: whether {@code changed} files' branches are replaced one by one in a tree of {@code shown} files, rather
     * than the tree built afresh — a few, or under a quarter of what it shows.
     */
    static boolean incremental(int changed, int shown) {
        return changed <= INCREMENTAL_FILES || changed * 4 <= shown;
    }

    /**
     * Pure: the files whose problems in {@code latest} differ from {@code shown} (which holds non-empty lists only):
     * a file with a new list of different content, a file gone or emptied, a file new. An unchanged list — the same
     * one, or an equal one — is compared without looking further.
     */
    static <D> List<Path> changedFiles(Map<Path, List<D>> shown, Map<Path, ? extends @Nullable List<D>> latest) {
        List<Path> out = new ArrayList<>();
        int present = 0;
        for (var e : latest.entrySet()) {
            List<D> now = e.getValue();
            List<D> was = shown.get(e.getKey());
            if (was != null) {
                present++;
            }
            boolean empty = now == null || now.isEmpty();
            if (empty ? was != null : was != now && !now.equals(was)) {
                out.add(e.getKey());
            }
        }
        if (present < shown.size()) { // some shown file is no longer in the map at all
            for (Path p : shown.keySet()) {
                if (!latest.containsKey(p)) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    /** Brings the tree to {@link #lastByFile}: nothing when it shows that already, else the changed branches. */
    private void refresh() {
        Map<Path, List<LspDiagnostic>> shown = rendered;
        if (shown == null || activeFileChanged) {
            rebuild();
            return;
        }
        List<Path> changed = changedFiles(shown, lastByFile);
        if (changed.isEmpty()) {
            return;
        }
        if (!incremental(changed.size(), shown.size())) {
            rebuild();
            return;
        }
        rebuilds++;
        for (Path file : changed) {
            replaceFile(shown, file, lastByFile.get(file));
        }
        showSummary();
    }

    /** Replaces {@code file}'s branch with {@code diagnostics} (none: the branch goes, and an emptied language). */
    private void replaceFile(
            Map<Path, List<LspDiagnostic>> shown, Path file, @Nullable List<LspDiagnostic> diagnostics) {
        List<LspDiagnostic> was = shown.remove(file);
        if (was != null) {
            shownTotal -= was.size();
        }
        TreeItem<Row> item = fileItems.get(file);
        String language = diagnostics == null || diagnostics.isEmpty() ? null : languageOf(file);
        if (item != null && !java.util.Objects.equals(language, fileLanguages.get(file))) {
            removeFile(file, item);
            item = null;
        }
        if (diagnostics == null || diagnostics.isEmpty() || language == null) {
            return;
        }
        shown.put(file, diagnostics);
        shownTotal += diagnostics.size();
        TreeItem<Row> languageItem = languageItems.get(language);
        if (languageItem == null) {
            languageItem = new TreeItem<>(new Row(language, null, null));
            languageItem.setExpanded(true);
            var languages = tree.getRoot().getChildren();
            languages.add(insertionPoint(languages, language, Row::language, languageOrder()), languageItem);
            languageItems.put(language, languageItem);
        }
        if (item == null) {
            item = new TreeItem<>(new Row(null, file, null));
            item.setExpanded(true);
            item.getChildren().setAll(diagnosticRows(file, diagnostics));
            var files = languageItem.getChildren();
            files.add(insertionPoint(files, file, Row::file, fileOrder()), item);
            fileItems.put(file, item);
            fileLanguages.put(file, language);
        } else {
            item.getChildren().setAll(diagnosticRows(file, diagnostics));
            item.setValue(new Row(null, file, null)); // a new value: its cell redraws the count
        }
        languageItem.setValue(new Row(language, null, null));
    }

    private void removeFile(Path file, TreeItem<Row> item) {
        fileItems.remove(file);
        String language = fileLanguages.remove(file);
        TreeItem<Row> languageItem = language == null ? null : languageItems.get(language);
        if (languageItem == null) {
            return;
        }
        languageItem.getChildren().remove(item);
        if (languageItem.getChildren().isEmpty()) {
            tree.getRoot().getChildren().remove(languageItem);
            languageItems.remove(language);
        } else {
            languageItem.setValue(new Row(language, null, null));
        }
    }

    /** Where {@code key} goes among {@code items}, sorted by {@code order} on each row's {@code of}. */
    private static <T> int insertionPoint(
            List<TreeItem<Row>> items, T key, java.util.function.Function<Row, @Nullable T> of, Comparator<T> order) {
        int lo = 0;
        int hi = items.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            T at = java.util.Objects.requireNonNull(of.apply(items.get(mid).getValue()));
            if (order.compare(at, key) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** Language headers: the active file's language first, then the rest alphabetically. */
    private Comparator<String> languageOrder() {
        String activeLanguage = activeFile == null ? null : languageOf(activeFile);
        return Comparator.comparingInt((String l) -> l.equals(activeLanguage) ? 0 : 1)
                .thenComparing(Comparator.naturalOrder());
    }

    /** Files of a language: the active file first, then by name (then by path, so the order is the same each time). */
    private Comparator<Path> fileOrder() {
        return ActiveFileOrder.activeFirst(
                p -> p,
                activeFile,
                Comparator.comparing((Path p) -> PathDisplay.fileName(p).toLowerCase(Locale.ROOT))
                        .thenComparing(Path::toString));
    }

    /** One row per diagnostic of {@code file}, by position. */
    private static List<TreeItem<Row>> diagnosticRows(Path file, List<LspDiagnostic> diagnostics) {
        List<LspDiagnostic> sorted = new ArrayList<>(diagnostics);
        sorted.sort((a, b) -> a.startLine() != b.startLine()
                ? Integer.compare(a.startLine(), b.startLine())
                : Integer.compare(a.startCol(), b.startCol()));
        List<TreeItem<Row>> rows = new ArrayList<>(sorted.size());
        for (LspDiagnostic d : sorted) {
            rows.add(new TreeItem<>(new Row(null, file, d)));
        }
        return rows;
    }

    private void showSummary() {
        int files = rendered == null ? 0 : rendered.size();
        summary.setText(shownTotal == 0 ? tr("problems.none") : tr("problems.summary", shownTotal, files));
    }

    /**
     * Sets the active file so its language group (and the file within it) sorts to the top, mirroring the
     * IDE convention of surfacing the file you're looking at first. Pass the <b>canonical</b> path (it must
     * match the LSP diagnostic keys). Re-sorts the existing tree without needing fresh diagnostics.
     */
    public void setActiveFile(@Nullable Path canonicalActive) {
        if (com.editora.config.PathKeys.samePath(activeFile, canonicalActive)) {
            return;
        }
        activeFile = canonicalActive;
        if (!visible()) {
            pendingRebuild = true;
            activeFileChanged = true;
            return;
        }
        rebuild();
    }

    /**
     * Selects {@code diag}'s row of {@code canonicalFile} and scrolls it into view, as Next / Previous Problem
     * (the editor's counter arrows, F2) step through a file, so an open Problems window follows them. It never
     * takes the focus — the caret stays in the editor — and a closed window is left alone. Returns whether the
     * row was found.
     */
    boolean reveal(Path canonicalFile, LspDiagnostic diag) {
        if (!visible()) {
            return false;
        }
        refresh(); // the paced refresh may not have shown the latest diagnostics yet
        for (TreeItem<Row> lang : tree.getRoot().getChildren()) {
            for (TreeItem<Row> file : lang.getChildren()) {
                if (!com.editora.config.PathKeys.samePath(file.getValue().file(), canonicalFile)) {
                    continue;
                }
                for (TreeItem<Row> row : file.getChildren()) {
                    if (diag.equals(row.getValue().diag())) {
                        lang.setExpanded(true); // a header the user folded opens to show where the caret went
                        file.setExpanded(true);
                        tree.getSelectionModel().select(row);
                        ensureVisible(tree.getRow(row));
                        return true;
                    }
                }
                return false;
            }
        }
        return false;
    }

    /** Scrolls the tree only when {@code index} is outside the rows it shows (as the Structure panel does). */
    private void ensureVisible(int index) {
        if (index < 0) {
            return;
        }
        javafx.scene.Node node = tree.lookup(".virtual-flow");
        if (node instanceof javafx.scene.control.skin.VirtualFlow<?> flow) {
            javafx.scene.control.IndexedCell<?> first = flow.getFirstVisibleCell();
            javafx.scene.control.IndexedCell<?> last = flow.getLastVisibleCell();
            if (first != null && last != null && index >= first.getIndex() && index <= last.getIndex()) {
                return;
            }
        }
        tree.scrollTo(index);
    }

    /** Builds the whole tree afresh from {@link #lastByFile}. */
    private void rebuild() {
        rebuilds++;
        fullRebuilds++;
        activeFileChanged = false;
        Map<Path, List<LspDiagnostic>> shown = new HashMap<>();
        rendered = shown;
        fileItems.clear();
        fileLanguages.clear();
        languageItems.clear();
        shownTotal = 0;
        // Bucket the non-empty files by display language name.
        Map<String, List<Path>> byLanguage = new HashMap<>();
        for (var entry : lastByFile.entrySet()) {
            List<LspDiagnostic> diags = entry.getValue();
            if (diags == null || diags.isEmpty()) {
                continue;
            }
            shown.put(entry.getKey(), diags);
            shownTotal += diags.size();
            String language = languageOf(entry.getKey());
            fileLanguages.put(entry.getKey(), language);
            byLanguage.computeIfAbsent(language, k -> new ArrayList<>()).add(entry.getKey());
        }
        List<String> langOrder = new ArrayList<>(byLanguage.keySet());
        langOrder.sort(languageOrder());
        Comparator<Path> fileOrder = fileOrder();
        TreeItem<Row> root = new TreeItem<>();
        for (String language : langOrder) {
            TreeItem<Row> langItem = new TreeItem<>(new Row(language, null, null));
            langItem.setExpanded(true);
            List<Path> paths = java.util.Objects.requireNonNull(byLanguage.get(language)); // its own keys
            paths.sort(fileOrder);
            List<TreeItem<Row>> fileRows = new ArrayList<>(paths.size());
            for (Path file : paths) {
                TreeItem<Row> fileItem = new TreeItem<>(new Row(null, file, null));
                fileItem.setExpanded(true);
                fileItem.getChildren().setAll(diagnosticRows(file, java.util.Objects.requireNonNull(shown.get(file))));
                fileItems.put(file, fileItem);
                fileRows.add(fileItem);
            }
            langItem.getChildren().setAll(fileRows);
            languageItems.put(language, langItem);
            root.getChildren().add(langItem);
        }
        tree.setRoot(root);
        showSummary();
    }

    /**
     * Which paths with problems are folders (a project's own problems): looked at off the FX thread (PERF-01,
     * Pauta 6.2.0). Until the answer lands a path is grouped by its name, and the tree is rebuilt when it lands.
     */
    private final DiskFacts<Path, Boolean> folders = new DiskFacts<Path, Boolean>(
                    "ProblemsPanel.languageOf", java.nio.file.Files::isDirectory)
            .assuming(Boolean.FALSE)
            .onLearned(() -> {
                if (visible()) {
                    rebuild();
                } else {
                    pendingRebuild = true;
                }
            });

    /** Friendly display name for a file's programming language (proper nouns, deliberately untranslated). */
    private String languageOf(Path file) {
        if (Boolean.TRUE.equals(folders.get(file))) {
            return tr("problems.project"); // a project's own problems arrive on its folder: not "Plaintext" (CT8)
        }
        String name = com.editora.text.LanguageNames.display(LanguageRegistry.forFileName(file.toString()));
        return name.isEmpty() ? "Other" : name;
    }

    @Override
    public void focusFirstItem() {
        tree.requestFocus();
        if (tree.getExpandedItemCount() > 0 && tree.getSelectionModel().isEmpty()) {
            tree.getSelectionModel().select(0);
        }
    }

    /**
     * A diagnostic row's text after its glyph: the 1-based line, the message (one line, at most 200 characters)
     * and the rule's code in brackets — {@code 3:  Double quote to prevent globbing…  [SC2086]} — which is what a
     * {@code # shellcheck disable=} line or a search needs. Pure.
     */
    static String rowText(LspDiagnostic d) {
        String msg = d.message() == null ? "" : d.message().replace('\n', ' ').strip();
        if (msg.length() > 200) {
            msg = msg.substring(0, 200) + "…";
        }
        String code = d.code() == null ? "" : d.code().strip();
        if (d.href() == null && code.chars().allMatch(Character::isDigit)) {
            code = ""; // jdtls's numeric problem ids (67108964) link nowhere and read as noise (Java review A18)
        }
        return (d.startLine() + 1) + ":  " + msg + (code.isEmpty() ? "" : "  [" + code + "]");
    }

    /**
     * The context menu of a diagnostic row: "Open Rule Documentation" when the server linked the rule (ShellCheck
     * links every SC code to its wiki page), and "Copy Code" when it has one.
     */
    @Nullable
    ContextMenu contextMenuFor(LspDiagnostic d) {
        ContextMenu menu = new ContextMenu();
        String href = d.href();
        if (href != null) {
            MenuItem docs = new MenuItem(tr("problems.menu.openRuleDocs"));
            docs.setOnAction(e -> actions.openUrl(href));
            FocusCommands.row(docs, "problems.openSelectedRuleDocs");
            menu.getItems().add(docs);
        }
        String code = d.code();
        if (code != null && !code.isBlank()) {
            MenuItem copy = new MenuItem(tr("problems.menu.copyCode"));
            copy.setOnAction(e -> {
                ClipboardContent content = new ClipboardContent();
                content.putString(code.strip());
                Clipboard.getSystemClipboard().setContent(content);
            });
            FocusCommands.row(copy, "problems.copySelectedCode");
            menu.getItems().add(copy);
        }
        return menu.getItems().isEmpty() ? null : menu;
    }

    /** Renders a language header, a file header (name + count), or a diagnostic (severity glyph + line). */
    private final class RowCell extends TreeCell<Row> {
        @Override
        protected void updateItem(Row row, boolean empty) {
            super.updateItem(row, empty);
            getStyleClass()
                    .removeAll(
                            "search-file-row", "problem-lang-row", "problem-error", "problem-warning", "problem-info");
            if (empty || row == null) {
                setText(null);
                setContextMenu(null);
                return;
            }
            setContextMenu(row.diag() == null ? null : contextMenuFor(row.diag()));
            if (row.diag() != null) {
                LspDiagnostic d = row.diag();
                setText(glyph(d.severity()) + "  " + rowText(d));
                getStyleClass()
                        .add(
                                switch (d.severity()) {
                                    case ERROR -> "problem-error";
                                    case WARNING -> "problem-warning";
                                    default -> "problem-info";
                                });
            } else if (row.file() != null) {
                int count =
                        getTreeItem() == null ? 0 : getTreeItem().getChildren().size();
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
                case ERROR -> "✖"; // ✖
                case WARNING -> "⚠"; // ⚠
                default -> "ℹ"; // ℹ
            };
        }
    }
}
