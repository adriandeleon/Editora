package com.editora.ui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.IndexedCell;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import com.editora.editor.EditorBuffer;
import com.editora.editor.fold.NarrowLines;
import com.editora.editor.highlight.GrammarRegistry;
import com.editora.editor.highlight.TextMateHighlighter;
import com.editora.editor.highlight.TextMateHighlighter.Symbol;
import com.editora.lsp.SymbolNode;
import com.editora.search.FuzzyMatch;
import com.editora.text.FoldRegions.Region;
import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Tool window content showing the active editor's foldable-region hierarchy as a collapsible tree,
 * mirroring the code/text folding model ({@link com.editora.text.FoldRegions}). A search box at the
 * top fuzzy-filters the tree, and selecting an entry navigates the editor caret to that line.
 *
 * <p>The panel marks itself with the {@code editora.ownsKeys} property so the scene-level key
 * dispatcher yields, letting it implement Emacs-style navigation locally: {@code C-n}/{@code C-p}
 * move, {@code C-f}/{@code C-b} expand/collapse (or descend/ascend), {@code Enter} jumps to the line,
 * and {@code C-g}/{@code Esc} clears the filter or returns focus to the editor.
 */
public class StructurePanel extends VBox implements ToolWindowContent {

    /** Actions offered for a concrete source row in the outline's right-click menu. */
    interface MarkerActions {
        void addBookmark(EditorBuffer buffer, int line);

        void addPersonalNote(EditorBuffer buffer, int line);

        boolean personalNotesEnabled();
    }

    /** How the outline rows are ordered (session-only, like the filter box). */
    public enum SortMode {
        POSITION,
        NAME,
        KIND
    }

    /**
     * Narrowing, as the editing coordinator does it (status messages included); wired by MainController. Lines
     * are document lines: the outline keeps the whole file's numbering while the buffer is narrowed.
     */
    interface NarrowActions {
        /**
         * Narrows to {@code block} when it holds document line {@code line} (the picked row's own block), else to
         * the innermost foldable region of the whole document holding the line; false when there is none.
         */
        boolean narrowToFoldRegion(EditorBuffer buffer, int line, @Nullable Region block);

        /** Shows the whole file again; a no-op for a buffer that is not narrowed. */
        void widen(EditorBuffer buffer);
    }

    private final TextField filterField = new TextField();
    private final TreeView<StructureNode> tree = new TreeView<>();
    private final ComboBox<SortMode> sortCombo = new ComboBox<>();
    private final MenuButton kindFilter = new MenuButton();

    private @Nullable EditorBuffer buffer;
    private @Nullable MarkerActions markerActions;
    private @Nullable NarrowActions narrowActions;
    /**
     * Whether the attached buffer is in fold mode: picking a symbol narrows the editor to its foldable block. The
     * mode belongs to the buffer it was turned on for ({@link #foldModes} remembers the others' across tab
     * switches) and ends when that buffer is widened by any means.
     */
    private boolean foldMode;

    private final java.util.Set<EditorBuffer> foldModes =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private final ToggleButton narrowFold = new ToggleButton();
    /**
     * The whole file's outline while the buffer is narrowed, in document lines. The fold manager and the
     * highlighter then only know the region the area holds, so this is computed from {@code getContent()} off
     * the FX thread ({@link #refreshNarrowedOutline}); null while the buffer is whole.
     */
    private @Nullable List<StructureNode> narrowedRoots;

    private int narrowedOutlineGeneration;
    /** Whether the selected row was picked by the user, as opposed to left selected by a rebuild. */
    private boolean userPicked;

    private java.util.function.BooleanSupplier simpleMode = () -> false;
    private java.util.function.Function<String, @Nullable String> chordFor = id -> null;
    private static final javafx.css.PseudoClass NARROWED_ELSEWHERE =
            javafx.css.PseudoClass.getPseudoClass("narrowed-elsewhere");
    private static final javafx.css.PseudoClass OUT_OF_REACH = javafx.css.PseudoClass.getPseudoClass("out-of-reach");

    private List<StructureNode> roots = List.of();
    /**
     * The LSP document-symbol outline for the active buffer, or {@code null} to use the TextMate/fold
     * heuristic. {@link #setLspSymbols} pushes it (an empty list means "LSP served, but no symbols").
     */
    private @Nullable List<SymbolNode> lspSymbols;

    private SortMode sortMode = SortMode.POSITION;
    /** Symbol kinds toggled off in the kind filter (session-only). */
    private final Set<String> hiddenKinds = new java.util.HashSet<>();
    /** Suppresses selection-driven navigation while the tree is rebuilt programmatically. */
    private boolean suppressNavigation;
    /** Guards against re-entrant kind-filter menu rebuilds firing the checkbox listeners. */
    private boolean rebuildingKindFilter;

    // rebuild() (an O(n) document split + a full TreeView rebuild) fires on every debounced fold-region change,
    // which happens on every edit whether or not this tool window is open. Skip it while hidden and coalesce to a
    // single rebuild when the window is (re)opened. (#549)
    private boolean pendingRebuild;

    public StructurePanel() {
        getStyleClass().add("structure-panel");
        // Opt out of the global key dispatcher so chords like C-n reach our local handler.
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        build();
        // A closed tool window is removed from the scene (getScene() == null); rebuild once when it's (re)opened
        // if the outline went stale while hidden.
        sceneProperty().addListener((o, was, now) -> {
            if (now != null && pendingRebuild) {
                rebuild();
                if (buffer != null) {
                    buffer.getFoldManager().recomputeAsync(); // one fresh scan per show, not per tab switch
                }
            }
            if (now != null) {
                requestStaleLspSymbols();
            }
        });
    }

    // The server outline is only fetched while something shows it. With the window closed the coordinator
    // reports each skipped refresh here (lspSymbolsStale); the outline is asked for again when the window
    // opens or the Jump to Structure picker reads it.
    private java.util.function.@Nullable Consumer<EditorBuffer> lspSymbolsRequester;
    private boolean lspSymbolsStale;
    private @Nullable Runnable onHiddenOutlineChanged;

    /** Sets how a stale server outline is re-requested (for the attached buffer). */
    void setLspSymbolsRequester(java.util.function.Consumer<EditorBuffer> requester) {
        this.lspSymbolsRequester = requester;
    }

    /** Told when the outline changes while the window is closed — the picker may be showing it. */
    void setOnHiddenOutlineChanged(Runnable callback) {
        this.onHiddenOutlineChanged = callback;
    }

    /** Whether the tool window is open, i.e. the outline is on screen and worth keeping current. */
    boolean showsOutline() {
        return visible();
    }

    /**
     * The server outline for {@code forBuffer} was not refreshed because nothing shows it. The symbols held
     * are dropped — their lines belong to an older text — so the picker falls back to the heuristic until
     * the fresh outline it then asks for arrives.
     */
    void lspSymbolsStale(EditorBuffer forBuffer) {
        if (forBuffer != buffer) {
            return;
        }
        lspSymbolsStale = true;
        if (lspSymbols != null) {
            lspSymbols = null;
            pendingRebuild = true;
        }
    }

    private void requestStaleLspSymbols() {
        if (lspSymbolsStale && lspSymbolsRequester != null && buffer != null) {
            lspSymbolsStale = false;
            lspSymbolsRequester.accept(buffer);
        }
    }

    StructurePanel(BookmarkCoordinator bookmarks, NotesCoordinator notes) {
        this();
        setMarkerActions(markerActions(bookmarks, notes));
    }

    /** The tree is only worth rebuilding while the tool window is open (its node is in the scene). */
    private boolean visible() {
        return getScene() != null;
    }

    private void build() {
        filterField.setPromptText(tr("structure.filterPrompt"));
        filterField.getStyleClass().add("structure-filter");
        filterField.textProperty().addListener((o, w, n) -> applyFilter(n));
        FilterFieldNav.install(filterField, tree, this::activateSelected); // Down/Enter → into / open the results
        // Trailing clear ("✕") button — visible only while the filter has text (mirrors the Project/Notes panels).
        Button clearFilter = new Button("✕");
        clearFilter.getStyleClass().add("project-filter-clear");
        clearFilter.setFocusTraversable(false);
        clearFilter.setTooltip(new Tooltip(tr("project.filterClear")));
        clearFilter.setOnAction(e -> {
            filterField.clear();
            filterField.requestFocus();
        });
        clearFilter.visibleProperty().bind(filterField.textProperty().isEmpty().not());
        clearFilter.managedProperty().bind(clearFilter.visibleProperty());
        // Narrow to fold region: one toggle at the end of the sort/kind row (the search field keeps its width). A
        // click turns fold mode on and narrows to the picked symbol's block; a second click widens. It is lit
        // while this panel narrowed the buffer; narrowed some other way (C-x n n), an amber border says so.
        narrowFold.setGraphic(Icons.foldRegion());
        narrowFold.getStyleClass().addAll("structure-narrow-fold", "flat");
        narrowFold.setAccessibleText(tr("structure.narrow.fold"));
        narrowFold.setFocusTraversable(false);
        narrowFold.setOnAction(e -> toggleFoldMode());
        refreshNarrowTooltips();
        syncNarrowToggle();
        HBox.setHgrow(filterField, Priority.ALWAYS);
        HBox filterBar = new HBox(6, filterField, clearFilter);
        filterBar.getStyleClass().add("project-filter-bar");
        filterBar.setAlignment(Pos.CENTER_LEFT);

        // Sort mode (Position / Name / Kind) — re-sorts and re-renders the outline.
        sortCombo.getItems().setAll(SortMode.POSITION, SortMode.NAME, SortMode.KIND);
        sortCombo.setValue(SortMode.POSITION);
        sortCombo.getStyleClass().add("structure-sort");
        sortCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(SortMode m) {
                return switch (m == null ? SortMode.POSITION : m) {
                    case NAME -> tr("structure.sort.name");
                    case KIND -> tr("structure.sort.kind");
                    default -> tr("structure.sort.position");
                };
            }

            @Override
            public SortMode fromString(String s) {
                return SortMode.POSITION;
            }
        });
        sortCombo.valueProperty().addListener((o, w, n) -> {
            sortMode = n == null ? SortMode.POSITION : n;
            rebuild();
        });

        // Kind filter: a dropdown of the kinds present in the current outline, each toggleable. Icon only: its
        // label never fit beside the sort combo at the window's default width ("Filter b…"), the tooltip has it.
        kindFilter.getStyleClass().add("structure-kind-filter");
        kindFilter.setGraphic(Icons.find());
        kindFilter.setTooltip(new Tooltip(tr("structure.filter.kinds")));
        kindFilter.setAccessibleText(tr("structure.filter.kinds"));

        javafx.scene.layout.Region toolbarSpacer = new javafx.scene.layout.Region();
        HBox.setHgrow(toolbarSpacer, Priority.ALWAYS);
        HBox toolbar = new HBox(4, sortCombo, kindFilter, toolbarSpacer, narrowFold);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getStyleClass().add("structure-toolbar");

        tree.setShowRoot(false);
        tree.getStyleClass().add("structure-tree");
        tree.setCellFactory(t -> new StructureCell());
        RowContextMenu.install(tree); // Menu key / Shift+F10 open the selected row's menu (cells are not focusable)
        VBox.setVgrow(tree, Priority.ALWAYS);

        // Navigate as soon as the selection changes (keyboard, search, or single click), but only
        // for user-driven changes — not while the tree is rebuilt — and without stealing focus. A selection
        // never narrows or widens: arrow keys, the filter field and a right-click all move it, and a text swap
        // on each of those (the undo history goes with it) is not what browsing an outline should cost.
        tree.getSelectionModel().selectedItemProperty().addListener((obs, old, now) -> {
            if (!suppressNavigation) {
                userPicked = true;
                navigateTo(now, false);
            }
        });
        // A click on a row is an activation: in fold mode it narrows to that row; a double-click also
        // moves focus into the editor.
        tree.setOnMouseClicked(e -> {
            if (e.getButton() != MouseButton.PRIMARY) {
                return;
            }
            if (e.getClickCount() == 2) {
                navigateSelected();
            } else if (e.getClickCount() == 1 && foldMode && rowClick(e)) {
                narrowToSelected();
            }
        });

        setSpacing(4);
        getChildren().addAll(toolbar, filterBar, tree);
        addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
    }

    /** A flat structure entry for the keyboard "Jump to Structure" picker. */
    public record Outline(String label, @Nullable String kind, int line) {}

    /** The current structure as a flat, document-order list (for the Jump-to-Structure picker). */
    public List<Outline> outline() {
        List<Outline> out = new ArrayList<>();
        requestStaleLspSymbols(); // answered later; onHiddenOutlineChanged then lets the picker reload
        // While the tool window is closed the tree is not rebuilt (#549), so {@code roots} is empty or still
        // the outline of whichever file was active when it was last open. The picker is the keyboard "go to
        // symbol in file" and has to work with the window closed: compute the model for this request.
        collectOutline(pendingRebuild ? computeRoots() : roots, out);
        return out;
    }

    /** The structure model for the attached buffer: the LSP outline when there is one, else the heuristic. */
    private List<StructureNode> computeRoots() {
        if (buffer == null) {
            return new ArrayList<>(); // mutable: sortNodes sorts roots in place (a diff/Welcome tab has no buffer)
        }
        if (buffer.isNarrowed()) {
            // The area holds the region, so its regions and symbols describe only that; the outline stays the
            // whole file's (the mode's "pick another symbol" needs the others on screen). The first fill is
            // synchronous, the refreshes after edits are not (refreshNarrowedOutline).
            if (narrowedRoots == null) {
                narrowedRoots = buildNodes(OutlineSource.document(buffer).analyzed());
            }
            return narrowedRoots;
        }
        // LSP-served file: precise hierarchy + kinds; fallback: fold-region nesting + TextMate-scope names.
        return lspSymbols != null ? fromLsp(lspSymbols) : buildNodes(OutlineSource.area(buffer));
    }

    private static void collectOutline(List<StructureNode> nodes, List<Outline> out) {
        for (StructureNode n : nodes) {
            if (n.line() >= 0) { // skip placeholder nodes ("No structure"/"No file open")
                out.add(new Outline(n.label(), n.kind(), n.line()));
            }
            collectOutline(n.children(), out);
        }
    }

    /** Attaches to a buffer (or {@code null}), rebuilding the tree and watching it for region changes. */
    public void attach(@Nullable EditorBuffer buffer) {
        if (this.buffer != null) {
            this.buffer.getFoldManager().setOnRegionsChanged(() -> {});
            this.buffer.setOnSymbolsChanged(() -> {});
        }
        this.buffer = buffer;
        this.lspSymbols = null; // start with the heuristic; MainController pushes LSP symbols if available
        this.lspSymbolsStale = false;
        narrowedRoots = null;
        narrowedOutlineGeneration++;
        userPicked = false;
        foldMode = buffer != null && buffer.isNarrowed() && foldModes.contains(buffer);
        syncNarrowToggle();
        if (buffer == null) {
            rebuild();
            return;
        }
        // Node ranges/nesting come from fold regions; names/kinds come from TextMate symbols — rebuild
        // when either updates (regions on text/fold change, symbols when re-highlighting completes).
        buffer.getFoldManager().setOnRegionsChanged(this::rebuildIfChanged);
        buffer.setOnSymbolsChanged(this::rebuildIfChanged);
        modelShown = false; // the tree still shows the previous buffer: the next build is unconditional
        if (!showsOutline()) {
            // Nothing shows the tree, so a tab switch scans nothing: the manager's own debounced detection
            // keeps its regions current, and opening the window rebuilds from them (sceneProperty listener).
            pendingRebuild = true;
            return;
        }
        // Build at once from the regions the manager already holds (current to within its 250 ms debounce),
        // then refresh them off the FX thread; the result lands through the callback above and is compared
        // against the shown model, so an unchanged outline is not rebuilt twice. The synchronous recompute
        // this replaced scanned the whole document on every tab switch (T1 in the 2026-10 perf review).
        rebuild();
        buffer.getFoldManager().recomputeAsync();
    }

    /** Moves keyboard focus into the panel (the search field), for window-switching. */
    public void focusContent() {
        filterField.requestFocus();
    }

    /** Installs bookmark and Personal Note actions for source rows in the outline. */
    void setMarkerActions(MarkerActions actions) {
        markerActions = actions;
    }

    static MarkerActions markerActions(BookmarkCoordinator bookmarks, NotesCoordinator notes) {
        return new MarkerActions() {
            @Override
            public void addBookmark(EditorBuffer buffer, int line) {
                bookmarks.addBookmark(buffer.getPath(), line);
            }

            @Override
            public void addPersonalNote(EditorBuffer buffer, int line) {
                notes.addPersonalNote(buffer.getPath(), buffer.captureLineNoteDraft(line));
            }

            @Override
            public boolean personalNotesEnabled() {
                return notes.isEnabled();
            }
        };
    }

    private @Nullable ContextMenu markerMenu(StructureNode node) {
        MarkerActions actions = markerActions;
        if (actions == null || buffer == null || buffer.getPath() == null || node == null || node.line() < 0) {
            return null;
        }
        int areaLine = areaLine(node.line());
        if (areaLine < 0) {
            return null; // out of the narrowed region: the marks live in the area's lines
        }
        MenuItem bookmark = new MenuItem(tr("editmenu.addBookmark"));
        bookmark.setGraphic(Icons.bookmark());
        bookmark.setOnAction(e -> {
            if (buffer != null) {
                actions.addBookmark(buffer, areaLine);
            }
        });

        MenuItem note = new MenuItem(tr("editmenu.addNote"));
        note.setGraphic(Icons.notes());
        note.setDisable(!actions.personalNotesEnabled());
        note.setOnAction(e -> {
            if (buffer != null) {
                actions.addPersonalNote(buffer, areaLine);
            }
        });
        ContextMenu menu = new ContextMenu(bookmark, note);
        menu.setOnShowing(e -> note.setDisable(!actions.personalNotesEnabled()));
        return menu;
    }

    @Override
    public void focusFirstItem() {
        // Land on the search field so the user can type to filter immediately; Down/Enter move into / open
        // the results (see FilterFieldNav in build()).
        filterField.requestFocus();
    }

    // --- Keyboard handling (Emacs defaults) ---

    private void onKey(KeyEvent e) {
        switch (e.getCode()) {
            case ESCAPE -> {
                focusEditor();
                e.consume();
            }
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
                    if (tree.isFocused()
                            && e.getCode() == KeyCode.F
                            && !e.isAltDown()
                            && !e.isMetaDown()
                            && !e.isShiftDown()) {
                        toggleFoldMode();
                        e.consume();
                    }
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
                    case F -> {
                        expandOrDescend();
                        e.consume();
                    }
                    case B -> {
                        collapseOrAscend();
                        e.consume();
                    }
                    case M -> {
                        activateSelected();
                        e.consume();
                    }
                    case G -> {
                        clearOrFocusEditor();
                        e.consume();
                    }
                    default -> {}
                }
            }
        }
    }

    private void move(int delta) {
        int rows = tree.getExpandedItemCount();
        if (rows == 0) {
            return;
        }
        int idx = tree.getSelectionModel().getSelectedIndex();
        int next = idx < 0 ? (delta > 0 ? 0 : rows - 1) : Math.floorMod(idx + delta, rows);
        tree.getSelectionModel().select(next);
        ensureVisible(next);
    }

    /** Scrolls the tree only when {@code index} is outside the currently visible rows. */
    private void ensureVisible(int index) {
        VirtualFlow<?> flow = virtualFlow();
        if (flow == null) {
            tree.scrollTo(index);
            return;
        }
        IndexedCell<?> first = flow.getFirstVisibleCell();
        IndexedCell<?> last = flow.getLastVisibleCell();
        if (first == null || last == null || index < first.getIndex() || index > last.getIndex()) {
            tree.scrollTo(index);
        }
    }

    private @Nullable VirtualFlow<?> virtualFlow() {
        Node node = tree.lookup(".virtual-flow");
        return node instanceof VirtualFlow<?> flow ? flow : null;
    }

    private void expandOrDescend() {
        TreeItem<StructureNode> item = tree.getSelectionModel().getSelectedItem();
        if (item != null && !item.isLeaf() && !item.isExpanded()) {
            item.setExpanded(true);
        } else {
            move(1);
        }
    }

    private void collapseOrAscend() {
        TreeItem<StructureNode> item = tree.getSelectionModel().getSelectedItem();
        if (item == null) {
            move(-1);
            return;
        }
        if (!item.isLeaf() && item.isExpanded()) {
            item.setExpanded(false);
        } else if (item.getParent() != null && item.getParent() != tree.getRoot()) {
            tree.getSelectionModel().select(item.getParent());
            ensureVisible(tree.getSelectionModel().getSelectedIndex());
        } else {
            move(-1);
        }
    }

    private void clearOrFocusEditor() {
        if (!filterField.getText().isEmpty()) {
            filterField.clear();
            filterField.requestFocus();
        } else {
            focusEditor();
        }
    }

    private void focusEditor() {
        if (buffer != null) {
            buffer.getArea().requestFocus();
        }
    }

    /**
     * Handles Enter: expands the selected node if it is collapsed (so navigation can reveal its
     * children); otherwise activates it, navigating and moving focus into the editor.
     */
    private void activateSelected() {
        TreeItem<StructureNode> item = tree.getSelectionModel().getSelectedItem();
        if (item != null && !item.isLeaf() && !item.isExpanded()) {
            item.setExpanded(true);
            return;
        }
        navigateSelected();
    }

    /** Activates the current selection: navigates and moves focus into the editor (in fold mode, narrows to it). */
    private void navigateSelected() {
        TreeItem<StructureNode> item = tree.getSelectionModel().getSelectedItem();
        if (foldMode && buffer != null && narrowToSelected()) {
            buffer.getArea().requestFocus();
            return;
        }
        navigateTo(item, true);
    }

    // --- Narrowing ---

    /** Installs the narrowing actions; without them the toggle is disabled. */
    void setNarrowActions(NarrowActions actions) {
        narrowActions = actions;
        syncNarrowToggle();
    }

    /** Whether Simple UI mode is on: the toggle is hidden there (the chords and the banner still work). */
    public void setSimpleMode(java.util.function.BooleanSupplier simpleMode) {
        this.simpleMode = simpleMode;
        syncNarrowToggle();
    }

    /** How the toggle's tooltip learns the chords bound to a command (null when it is unbound). */
    void setChordLookup(java.util.function.Function<String, @Nullable String> chordFor) {
        this.chordFor = chordFor;
        refreshNarrowTooltips();
    }

    /** Re-reads what the toggle depends on besides the buffer (Simple mode, the keymap). */
    public void refreshChrome() {
        refreshNarrowTooltips();
        syncNarrowToggle();
    }

    /** Whether the attached buffer is in fold mode (tests). */
    boolean foldMode() {
        return foldMode;
    }

    /** Structure: Toggle Narrow to Fold Region — as clicking the toggle. */
    public void toggleFoldCommand() {
        toggleFoldMode();
    }

    /**
     * The toggle was clicked. In fold mode it widens and ends the mode; otherwise it narrows to the row the user
     * picked (the caret's line when the selected row was only left there by a rebuild) and turns the mode on. A
     * narrowing that found nothing (the status bar said why) leaves the toggle off.
     */
    private void toggleFoldMode() {
        EditorBuffer b = buffer;
        NarrowActions actions = narrowActions;
        if (b != null && actions != null) {
            if (foldMode && b.isNarrowed()) {
                foldMode = false;
                foldModes.remove(b);
                actions.widen(b);
            } else {
                foldMode = true;
                StructureNode row = userPicked ? selectedNodeValue() : null;
                if (row != null ? narrowTo(row) : narrowAtCaret()) {
                    foldModes.add(b);
                } else {
                    foldMode = false;
                    foldModes.remove(b);
                }
            }
        }
        syncNarrowToggle();
    }

    /** In fold mode, narrows the editor to the selected row; false when there is none or nothing to narrow to. */
    private boolean narrowToSelected() {
        StructureNode row = selectedNodeValue();
        return row != null && narrowTo(row);
    }

    /** Narrows to {@code row}'s block, and puts the caret on the row. */
    private boolean narrowTo(StructureNode row) {
        EditorBuffer b = buffer;
        NarrowActions actions = narrowActions;
        if (b == null || actions == null || row.line() < 0) {
            return false;
        }
        boolean ok = actions.narrowToFoldRegion(b, row.line(), row.region());
        if (ok) {
            int line = areaLine(row.line());
            if (line >= 0) {
                b.getArea().moveTo(line, 0);
                b.getArea().requestFollowCaret();
            }
        }
        return ok;
    }

    private boolean narrowAtCaret() {
        EditorBuffer b = buffer;
        NarrowActions actions = narrowActions;
        if (b == null || actions == null) {
            return false;
        }
        int docLine = b.getArea().getCurrentParagraph() + b.narrowFirstLine();
        return actions.narrowToFoldRegion(b, docLine, null);
    }

    /**
     * The buffer narrowed or widened, by this panel or by anything else: the toggle follows the buffer, and the
     * outline stays the whole file's (document lines) either way. Widening leaves the shown outline alone: it
     * already is the whole file's, and the fold manager's fresh scan rebuilds it shortly if it differs.
     */
    void narrowChanged(EditorBuffer changed) {
        if (changed != buffer) {
            return;
        }
        if (buffer != null && !buffer.isNarrowed()) {
            foldMode = false;
            foldModes.remove(buffer);
            narrowedRoots = null;
            narrowedOutlineGeneration++;
        } else if (buffer != null) {
            if (narrowedRoots == null && modelShown) {
                narrowedRoots = roots; // what the tree shows: the whole file's outline, a moment ago
            }
            refreshNarrowedOutline();
        }
        syncNarrowToggle();
        tree.refresh(); // rows outside the region are muted
    }

    /**
     * Recomputes the whole file's outline off the FX thread and shows it if it changed. Called for every
     * fold/symbol notification while narrowed (one per settled edit): the region's own regions and symbols
     * are no use to a whole-file outline, and a synchronous whole-file scan on each would be the jank the
     * fold manager's own scan avoids.
     */
    private void refreshNarrowedOutline() {
        EditorBuffer b = buffer;
        if (b == null || !b.isNarrowed()) {
            return;
        }
        int generation = ++narrowedOutlineGeneration;
        OutlineSource source = OutlineSource.document(b);
        Thread.ofVirtual().name("editora-structure-outline").start(() -> {
            List<StructureNode> nodes = buildNodes(source.analyzed());
            Platform.runLater(() -> {
                if (generation != narrowedOutlineGeneration || buffer != b || !b.isNarrowed()) {
                    return;
                }
                narrowedRoots = nodes;
                rebuild(true);
            });
        });
    }

    /** The tip, then the keys: the panel's own letter (and the command's chord when one is bound), the editor's. */
    private void refreshNarrowTooltips() {
        String chord = chordFor.apply("structure.narrowFold");
        String keys = chord == null || chord.isBlank() ? "f" : chord + " · f";
        StringBuilder text = new StringBuilder(tr("structure.narrow.fold.tip"))
                .append('\n')
                .append(tr("structure.narrow.keys", keys));
        String editorChord = chordFor.apply("edit.narrowToFoldRegion");
        if (editorChord != null && !editorChord.isBlank()) {
            text.append('\n').append(tr("structure.narrow.editorKeys", editorChord));
        }
        Tooltip tip = new Tooltip(text.toString());
        tip.setShowDelay(javafx.util.Duration.millis(400));
        narrowFold.setTooltip(tip);
    }

    private void syncNarrowToggle() {
        boolean shown = !simpleMode.getAsBoolean();
        narrowFold.setVisible(shown);
        narrowFold.setManaged(shown);
        narrowFold.setDisable(buffer == null || narrowActions == null || !buffer.canNarrow());
        boolean narrowed = buffer != null && buffer.isNarrowed();
        narrowFold.setSelected(narrowed && foldMode);
        narrowFold.pseudoClassStateChanged(NARROWED_ELSEWHERE, narrowed && !foldMode);
    }

    /**
     * The area line of document line {@code docLine}: the line itself while the buffer is whole, its place in
     * the region while narrowed, or -1 when the line is not in the area (out of the region, or past the end).
     */
    private int areaLine(int docLine) {
        if (buffer == null || docLine < 0) {
            return -1;
        }
        int lines = buffer.getArea().getParagraphs().size();
        if (!buffer.isNarrowed()) {
            return docLine < lines ? docLine : -1;
        }
        return NarrowLines.toRegionLine(buffer.narrowFirstLine(), lines, docLine);
    }

    /** Whether a mouse event landed on a row's own cell (not its disclosure arrow, not the empty space below). */
    private boolean rowClick(javafx.scene.input.MouseEvent e) {
        if (!(e.getTarget() instanceof Node target)) {
            return tree.getSelectionModel().getSelectedItem() != null; // a synthetic event: the selected row
        }
        for (Node p = target; p != null; p = p.getParent()) {
            if (p.getStyleClass().contains("tree-disclosure-node")) {
                return false;
            }
            if (p instanceof TreeCell<?> cell) {
                return !cell.isEmpty()
                        && cell.getTreeItem() == tree.getSelectionModel().getSelectedItem();
            }
        }
        return false;
    }

    /**
     * Moves the editor caret to {@code item}'s line, optionally focusing the editor. Rows carry document lines:
     * while narrowed, a selection (arrow keys, the filter) only reaches rows inside the region, and an
     * activation (Enter, a double-click) of a row outside it widens first, as a jump from outside does in Emacs.
     */
    private void navigateTo(TreeItem<StructureNode> item, boolean focusEditor) {
        if (item == null || item.getValue() == null || buffer == null) {
            return;
        }
        CodeArea area = buffer.getArea();
        EditorBuffer targetBuffer = buffer;
        int docLine = item.getValue().line();
        int line = areaLine(docLine);
        boolean widened = false;
        if (line < 0) {
            if (!focusEditor || docLine < 0 || !buffer.isNarrowed()) {
                return;
            }
            if (narrowActions != null) {
                narrowActions.widen(buffer);
            } else {
                buffer.widen();
            }
            widened = true;
            line = areaLine(docLine);
            if (line < 0) {
                return;
            }
        }
        area.moveTo(line, 0);
        // Anchor the target at the top of the usable viewport, below any sticky-scroll rows, in this pulse (a
        // widen just swapped the text in: that one waits for the layout).
        targetBuffer.jumps().reveal(area, line, widened);
        if (focusEditor) {
            area.requestFocus();
        }
    }

    // --- Tree construction ---

    private void rebuild() {
        rebuild(false);
    }

    /**
     * The buffer announced new fold regions or a finished highlight pass. Both arrive after every edit
     * settles — and the regions one whether or not anything changed — so most of them describe the outline
     * that is already showing: the tree, the kind menu and the selection are left alone unless the model
     * really differs.
     */
    private void rebuildIfChanged() {
        syncNarrowToggle(); // a buffer that finished loading since attach() can be narrowed now
        if (buffer != null && buffer.isNarrowed()) {
            refreshNarrowedOutline(); // the area's regions and symbols describe the region, not the file
            return;
        }
        rebuild(true);
    }

    private void rebuild(boolean onlyIfChanged) {
        if (!visible()) {
            pendingRebuild = true; // defer the O(n) split + TreeView rebuild until the window is shown again
            return;
        }
        pendingRebuild = false;
        List<StructureNode> next = computeRoots();
        attachDocs(next);
        attachSyntaxStyles(next);
        sortNodes(next);
        if (onlyIfChanged && modelShown && sameModel(next, roots)) {
            return;
        }
        roots = next;
        modelShown = true;
        treeBuilds++;
        rebuildKindFilter();
        // A rebuild rebuilds the tree (applyFilter selects row 0); that must not move the editor caret,
        // and it should keep the user on the symbol they had selected rather than snapping to the top.
        StructureNode prevSelection = selectedNodeValue();
        boolean wasPicked = userPicked;
        suppressNavigation = true;
        try {
            applyFilter(filterField.getText());
            reselect(prevSelection);
        } finally {
            suppressNavigation = false;
        }
        // Row 0 is selected by default; only a row the user chose (and that survived) is a narrowing target.
        StructureNode now = selectedNodeValue();
        userPicked = wasPicked
                && prevSelection != null
                && now != null
                && now.line() == prevSelection.line()
                && java.util.Objects.equals(now.label(), prevSelection.label());
    }

    /** Whether the tree currently shows {@link #roots} for the attached buffer. */
    private boolean modelShown;
    /** How many times the tree was rebuilt from a model (tests). */
    private int treeBuilds;

    /** Whether two outlines would render the same tree: every node's text, styling, doc and place. */
    private static boolean sameModel(List<StructureNode> a, List<StructureNode> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            StructureNode x = a.get(i);
            StructureNode y = b.get(i);
            if (x.line() != y.line()
                    || !java.util.Objects.equals(x.label(), y.label())
                    || !java.util.Objects.equals(x.kind(), y.kind())
                    || !java.util.Objects.equals(x.region(), y.region())
                    || !x.detail().equals(y.detail())
                    || !x.doc().equals(y.doc())
                    || !x.nameRuns().equals(y.nameRuns())
                    || !x.returnRuns().equals(y.returnRuns())
                    || !sameModel(x.children(), y.children())) {
                return false;
            }
        }
        return true;
    }

    /** The {@link StructureNode} currently selected in the tree (a real symbol with a line), or {@code null}. */
    private @Nullable StructureNode selectedNodeValue() {
        TreeItem<StructureNode> sel = tree.getSelectionModel().getSelectedItem();
        StructureNode v = sel == null ? null : sel.getValue();
        return v != null && v.line() >= 0 ? v : null;
    }

    /** Re-selects the tree row matching {@code target} (by line + label) after a rebuild; no-op if absent. */
    private void reselect(@Nullable StructureNode target) {
        if (target == null || tree.getRoot() == null) {
            return;
        }
        TreeItem<StructureNode> match = findItem(tree.getRoot(), target);
        if (match != null) {
            tree.getSelectionModel().select(match);
        }
    }

    private static @Nullable TreeItem<StructureNode> findItem(TreeItem<StructureNode> node, StructureNode target) {
        StructureNode v = node.getValue();
        if (v != null && v.line() == target.line() && java.util.Objects.equals(v.label(), target.label())) {
            return node;
        }
        for (TreeItem<StructureNode> child : node.getChildren()) {
            TreeItem<StructureNode> found = findItem(child, target);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * Pushes the LSP document-symbol outline for {@code forBuffer} (or {@code null} to use the heuristic). A
     * no-op if the active buffer changed since the request was issued (a stale async result).
     */
    public void setLspSymbols(EditorBuffer forBuffer, @Nullable List<SymbolNode> symbols) {
        if (forBuffer != buffer) {
            return;
        }
        // Skip the rebuild when the outline is unchanged. A server can re-announce readiness repeatedly
        // (jdtls re-sends language/status "ServiceReady" on indexing/workspace events), and each one
        // re-fetches the same document symbols — rebuilding would reset the selection to row 0 and can
        // jump the editor back to the top while the user is navigating. SymbolNode is a record, so this
        // is deep value equality. (attach() already built the initial tree, so this never skips first paint.)
        if (java.util.Objects.equals(symbols, lspSymbols)) {
            return;
        }
        this.lspSymbols = symbols;
        rebuild();
        if (!visible() && onHiddenOutlineChanged != null) {
            onHiddenOutlineChanged.run();
        }
    }

    /** Converts the LSP symbol tree into structure nodes (region-less; navigation uses the symbol line). */
    private static List<StructureNode> fromLsp(List<SymbolNode> symbols) {
        List<StructureNode> out = new ArrayList<>();
        for (SymbolNode s : symbols) {
            if (s == null) {
                continue;
            }
            int line = Math.max(0, s.line());
            Region range = s.endLine() >= line ? new Region(line, s.endLine()) : null;
            StructureNode node = new StructureNode(range, s.name(), s.kind(), line, s.detail());
            node.children().addAll(fromLsp(s.children()));
            out.add(node);
        }
        return out;
    }

    /**
     * Fills each node's doc from the leading comment above its declaration line, read from the buffer text.
     * Works for both the LSP and heuristic outlines (either way a node carries the declaration's line).
     */
    private void attachDocs(List<StructureNode> nodes) {
        if (buffer == null || nodes.isEmpty()) {
            return;
        }
        // Rows carry document lines, and getContent() is the whole file whether or not the buffer is narrowed.
        List<String> lines = List.of(buffer.getContent().split("\n", -1));
        attachDocs(nodes, lines);
    }

    private static void attachDocs(List<StructureNode> nodes, List<String> lines) {
        for (StructureNode n : nodes) {
            if (n.line() >= 0) {
                n.setDoc(StructureDoc.commentAbove(lines, n.line()));
            }
            attachDocs(n.children(), lines);
        }
    }

    /**
     * Gives each symbol label the same token classes as its declaration in the editor. LSP outlines carry a
     * line but not a column, so the callable stem is located within that source line and the remaining label
     * tokens are matched in order. This preserves mixed syntax such as {@code method(List<String>)} instead
     * of painting the whole signature like a method name. Return types are recovered from the declaration
     * when the server omits them from its label.
     */
    private void attachSyntaxStyles(List<StructureNode> nodes) {
        if (buffer == null || nodes.isEmpty()) {
            return;
        }
        if (buffer.isMarkdown()) {
            // A heading's label is its text without the markup, so it has no source tokens to borrow
            // colours from: one heading colour for the whole label. (Borrowing them coloured only the
            // first word of a setext heading, whose source line the grammar leaves unstyled.)
            attachHeadingStyle(nodes);
            return;
        }
        attachSyntaxStyles(buffer.getArea(), buffer.narrowFirstLine(), nodes);
    }

    private static void attachHeadingStyle(List<StructureNode> nodes) {
        for (StructureNode node : nodes) {
            node.setNameRuns(List.of(fallbackRun(node.label() == null ? "" : node.label(), node.kind())));
            attachHeadingStyle(node.children());
        }
    }

    private static void attachSyntaxStyles(CodeArea area, int base, List<StructureNode> nodes) {
        for (StructureNode node : nodes) {
            attachSyntaxStyles(area, base, node);
            attachSyntaxStyles(area, base, node.children());
        }
    }

    /** {@code base}: the document line the area's line 0 is (a narrowed region); rows carry document lines. */
    private static void attachSyntaxStyles(CodeArea area, int base, StructureNode node) {
        String label = node.label() == null ? "" : node.label();
        int line = node.line() - base;
        if (line < 0 || line >= area.getParagraphs().size()) {
            node.setNameRuns(List.of(fallbackRun(label, node.kind())));
            return;
        }

        String sourceLine = area.getParagraph(line).getText();
        String stem = callableStem(label);
        int column = sourceLine.indexOf(stem);
        if (column < 0 || stem.isEmpty()) {
            node.setNameRuns(List.of(fallbackRun(label, node.kind())));
            return;
        }

        node.setNameRuns(styledDisplayRuns(area, line, label, sourceLine, column, node.kind()));
        SourceSlice returnType = returnTypeSource(sourceLine, column, node.kind());
        if (returnType != null) {
            node.setReturnRuns(styledSourceRuns(area, line, returnType));
        }
    }

    private static String callableStem(String label) {
        String value = label == null ? "" : label.strip();
        int signature = value.indexOf('(');
        return signature > 0 ? value.substring(0, signature).strip() : value;
    }

    private static SyntaxRun fallbackRun(String text, @Nullable String kind) {
        String fallback = syntaxStyleForKind(kind);
        return new SyntaxRun(text, fallback == null ? List.of() : List.of(fallback));
    }

    /** Maps the server's compact label back onto source tokens so every run keeps its own syntax classes. */
    private static List<SyntaxRun> styledDisplayRuns(
            CodeArea area, int line, String display, String sourceLine, int sourceStart, @Nullable String kind) {
        List<SyntaxRun> runs = new ArrayList<>();
        int displayAt = 0;
        int sourceAt = sourceStart;
        boolean firstToken = true;
        while (displayAt < display.length()) {
            int end = tokenEnd(display, displayAt);
            String token = display.substring(displayAt, end);
            int found = sourceLine.indexOf(token, sourceAt);
            List<String> styles = found < 0 ? List.of() : syntaxStylesAt(area, line, found);
            if (styles.isEmpty() && firstToken) {
                String fallback = syntaxStyleForKind(kind);
                styles = fallback == null ? List.of() : List.of(fallback);
            }
            addRun(runs, token, styles);
            if (found >= 0) {
                sourceAt = found + token.length();
            }
            displayAt = end;
            firstToken = false;
        }
        return List.copyOf(runs);
    }

    private static int tokenEnd(String text, int start) {
        boolean identifier = Character.isJavaIdentifierPart(text.charAt(start));
        int end = start + 1;
        while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end)) == identifier) {
            if (!identifier && text.charAt(end) != text.charAt(start)) {
                break;
            }
            end++;
        }
        return end;
    }

    private static List<SyntaxRun> styledSourceRuns(CodeArea area, int line, SourceSlice slice) {
        List<SyntaxRun> runs = new ArrayList<>();
        int start = slice.column();
        int runStart = 0;
        List<String> previous = null;
        for (int i = 0; i < slice.text().length(); i++) {
            List<String> styles = syntaxStylesAt(area, line, start + i);
            if (previous != null && !previous.equals(styles)) {
                addRun(runs, slice.text().substring(runStart, i), previous);
                runStart = i;
            }
            previous = styles;
        }
        if (previous == null) {
            return List.of();
        }
        addRun(runs, slice.text().substring(runStart), previous.isEmpty() ? List.of("type") : previous);
        return List.copyOf(runs);
    }

    private static void addRun(List<SyntaxRun> runs, String text, List<String> styles) {
        if (text.isEmpty()) {
            return;
        }
        List<String> safeStyles = styles == null ? List.of() : List.copyOf(styles);
        if (!runs.isEmpty() && runs.getLast().styles().equals(safeStyles)) {
            SyntaxRun previous = runs.removeLast();
            runs.add(new SyntaxRun(previous.text() + text, safeStyles));
        } else {
            runs.add(new SyntaxRun(text, safeStyles));
        }
    }

    private static List<String> syntaxStylesAt(CodeArea area, int line, int column) {
        try {
            int offset = area.getAbsolutePosition(line, column);
            return area.getStyleOfChar(offset).stream()
                    .filter(StructurePanel::isSyntaxNameStyle)
                    .toList();
        } catch (RuntimeException ignored) {
            // A concurrent document replacement can invalidate the line/offset; an unstyled run is safe.
            return List.of();
        }
    }

    /** Finds a Java/C-family prefix return type, or a Kotlin/TypeScript/Rust suffix return type. */
    private static @Nullable SourceSlice returnTypeSource(String sourceLine, int nameColumn, @Nullable String kind) {
        if (!StructureCell.isCallable(kind) || "constructor".equals(kind)) {
            return null;
        }

        int end = nameColumn;
        while (end > 0 && Character.isWhitespace(sourceLine.charAt(end - 1))) {
            end--;
        }
        int start = previousTopLevelTokenStart(sourceLine, end);
        if (start < end) {
            String candidate = sourceLine.substring(start, end).strip();
            if (isReturnTypeCandidate(candidate)) {
                int actualStart = sourceLine.indexOf(candidate, start);
                return new SourceSlice(candidate, actualStart);
            }
        }

        int close = closingParen(sourceLine, nameColumn);
        if (close >= 0) {
            int at = close + 1;
            while (at < sourceLine.length() && Character.isWhitespace(sourceLine.charAt(at))) {
                at++;
            }
            // A Python `def` has only `->`: its `:` opens the body (`def f(x): return x` has no return type).
            boolean pythonDef =
                    PYTHON_DEF.matcher(sourceLine.substring(0, nameColumn)).matches();
            int markerLength =
                    sourceLine.startsWith("->", at) ? 2 : !pythonDef && sourceLine.startsWith(":", at) ? 1 : 0;
            if (markerLength > 0) {
                at += markerLength;
                while (at < sourceLine.length() && Character.isWhitespace(sourceLine.charAt(at))) {
                    at++;
                }
                int suffixEnd = pythonDef ? pythonAnnotationEnd(sourceLine, at) : at;
                while (!pythonDef
                        && suffixEnd < sourceLine.length()
                        && "{=;".indexOf(sourceLine.charAt(suffixEnd)) < 0) {
                    suffixEnd++;
                }
                String candidate = sourceLine.substring(at, suffixEnd).strip();
                if (isReturnTypeCandidate(candidate)) {
                    return new SourceSlice(candidate, at);
                }
            }
        }
        return null;
    }

    private static final java.util.regex.Pattern PYTHON_DEF =
            java.util.regex.Pattern.compile("\\s*(?:async\\s+)?def\\s+");

    /**
     * Where a Python return annotation that starts at {@code from} ends: at the {@code :} that opens the body,
     * outside brackets and quotes ({@code -> dict[str, int]:}, {@code -> "Greeter":}), else at the line end.
     */
    static int pythonAnnotationEnd(String line, int from) {
        int depth = 0;
        char quote = 0;
        for (int i = from; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '[' || c == '(') {
                depth++;
            } else if ((c == ']' || c == ')') && depth > 0) {
                depth--;
            } else if ((c == ':' || c == '#') && depth == 0) {
                return i;
            }
        }
        return line.length();
    }

    private static int previousTopLevelTokenStart(String text, int end) {
        int angleDepth = 0;
        int squareDepth = 0;
        int start = end;
        while (start > 0) {
            char c = text.charAt(start - 1);
            if (c == '>') {
                angleDepth++;
            } else if (c == '<' && angleDepth > 0) {
                angleDepth--;
            } else if (c == ']') {
                squareDepth++;
            } else if (c == '[' && squareDepth > 0) {
                squareDepth--;
            } else if (Character.isWhitespace(c) && angleDepth == 0 && squareDepth == 0) {
                break;
            }
            start--;
        }
        return start;
    }

    private static boolean isReturnTypeCandidate(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return switch (value) {
            case "abstract",
                    "async",
                    "const",
                    "let",
                    "var",
                    "get",
                    "set",
                    "readonly",
                    "override",
                    "declare",
                    "default",
                    "def",
                    "export",
                    "final",
                    "fn",
                    "fun",
                    "function",
                    "native",
                    "private",
                    "protected",
                    "public",
                    "static",
                    "synchronized" -> false;
            default ->
                Character.isJavaIdentifierStart(value.charAt(0))
                        // A quoted (forward-reference) Python annotation: `-> "Greeter"`.
                        || (value.length() > 2
                                && (value.charAt(0) == '"' || value.charAt(0) == '\'')
                                && Character.isJavaIdentifierStart(value.charAt(1)));
        };
    }

    private static int closingParen(String text, int nameColumn) {
        int open = text.indexOf('(', nameColumn);
        if (open < 0) {
            return -1;
        }
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private record SyntaxRun(String text, List<String> styles) {}

    private record SourceSlice(String text, int column) {}

    private static boolean isSyntaxNameStyle(String style) {
        if (style == null) {
            return false;
        }
        if (style.startsWith("sem-")) {
            return true;
        }
        return switch (style) {
            case "annotation",
                    "attribute",
                    "bold",
                    "code",
                    "constant",
                    "function",
                    "heading",
                    "italic",
                    "keyword",
                    "link",
                    "number",
                    "property",
                    "string",
                    "tag",
                    "type",
                    "variable" -> true;
            default -> false;
        };
    }

    /** Syntax-token fallback for an LSP/TextMate outline kind when no styled source character is available. */
    static @Nullable String syntaxStyleForKind(@Nullable String kind) {
        return switch (kind == null ? "" : kind) {
            case "class",
                    "enum",
                    "interface",
                    "module",
                    "namespace",
                    "object",
                    "package",
                    "struct",
                    "type",
                    "typeparameter" -> "type";
            case "constructor", "event", "function", "method", "operator" -> "function";
            case "field", "key", "property" -> "property";
            case "variable" -> "variable";
            case "boolean", "constant", "enummember", "number", "string" -> "constant";
            case "heading", "section" -> "heading";
            case "tag" -> "tag";
            default -> null;
        };
    }

    /** Re-orders {@code nodes} (and their descendants) per the current {@link SortMode}. */
    private void sortNodes(List<StructureNode> nodes) {
        Comparator<StructureNode> cmp = switch (sortMode) {
            case NAME ->
                Comparator.<StructureNode, String>comparing(n -> n.label().toLowerCase(Locale.ROOT));
            case KIND ->
                Comparator.<StructureNode, String>comparing(n -> n.kind() == null ? "" : n.kind())
                        .thenComparing(n -> n.label().toLowerCase(Locale.ROOT));
            default -> Comparator.comparingInt(StructureNode::line);
        };
        // Guard: List.sort throws UnsupportedOperationException on an immutable list (even an empty one),
        // and an empty/singleton list needs no sorting anyway. Lists of 2+ here are always mutable
        // (roots + every node's children are built as ArrayLists), so the in-place sort is safe.
        if (nodes.size() > 1) {
            nodes.sort(cmp);
        }
        for (StructureNode n : nodes) {
            sortNodes(n.children());
        }
    }

    /** Rebuilds the kind-filter dropdown from the kinds present in the outline (preserving existing toggles). */
    private void rebuildKindFilter() {
        Set<String> present = new TreeSet<>();
        collectKinds(roots, present);
        hiddenKinds.retainAll(present); // forget toggles for kinds no longer in the outline
        rebuildingKindFilter = true;
        try {
            kindFilter.getItems().clear();
            kindFilter.setDisable(present.isEmpty());
            for (String kind : present) {
                CheckMenuItem item = new CheckMenuItem(kindLabel(kind));
                item.setSelected(!hiddenKinds.contains(kind));
                item.selectedProperty().addListener((o, w, on) -> {
                    if (rebuildingKindFilter) {
                        return;
                    }
                    if (on) {
                        hiddenKinds.remove(kind);
                    } else {
                        hiddenKinds.add(kind);
                    }
                    applyFilter(filterField.getText());
                });
                kindFilter.getItems().add(item);
            }
        } finally {
            rebuildingKindFilter = false;
        }
    }

    private static void collectKinds(List<StructureNode> nodes, Set<String> out) {
        for (StructureNode n : nodes) {
            if (n.kind() != null && !n.kind().isBlank()) {
                out.add(n.kind());
            }
            collectKinds(n.children(), out);
        }
    }

    /** Display label for a (technical) kind id — capitalized; not localized, like other technical tokens. */
    private static String kindLabel(String kind) {
        if (kind == null || kind.isEmpty()) {
            return "?";
        }
        return Character.toUpperCase(kind.charAt(0)) + kind.substring(1);
    }

    /**
     * What the heuristic outline is built from: the text as lines, the fold regions and the TextMate symbols,
     * with the buffer's kind. {@link #area} reads the editor's own (the whole document while the buffer is
     * whole); {@link #document} snapshots {@code getContent()} for a narrowed buffer, whose regions and symbols
     * are then detected afresh — off the FX thread, see {@link #analyzed()}.
     */
    private record OutlineSource(
            String text,
            int lineCount,
            java.util.function.IntFunction<String> lineAt,
            String language,
            boolean markdown,
            boolean typst,
            boolean http,
            boolean pom,
            org.eclipse.tm4e.core.grammar.@Nullable IGrammar grammar,
            List<Region> regions,
            List<Symbol> symbols) {

        static OutlineSource area(EditorBuffer buffer) {
            CodeArea area = buffer.getArea();
            return new OutlineSource(
                    area.getText(),
                    area.getParagraphs().size(),
                    i -> area.getParagraph(i).getText(),
                    buffer.getLanguage(),
                    buffer.isMarkdown(),
                    buffer.isTypst(),
                    buffer.isHttpFile(),
                    buffer.isPom(),
                    null,
                    buffer.getFoldManager().regions(),
                    buffer.symbols());
        }

        /** The whole document, regions and symbols still to be detected ({@link #analyzed}). FX thread. */
        static OutlineSource document(EditorBuffer buffer) {
            String text = buffer.getContent();
            List<String> lines = List.of(text.split("\n", -1));
            String language = buffer.getLanguage();
            return new OutlineSource(
                    text,
                    lines.size(),
                    lines::get,
                    language,
                    buffer.isMarkdown(),
                    buffer.isTypst(),
                    buffer.isHttpFile(),
                    buffer.isPom(),
                    GrammarRegistry.shared().forLanguageName(language),
                    List.of(),
                    List.of());
        }

        /** This source with its regions and symbols detected from the text. Pure: any thread. */
        OutlineSource analyzed() {
            List<Region> detected =
                    com.editora.editor.fold.FoldManager.detectRegions(text, language, null, List.of(), true);
            TextMateHighlighter.Analysis analysis = grammar == null ? null : TextMateHighlighter.analyze(text, grammar);
            List<Symbol> found = analysis == null ? List.of() : analysis.symbols();
            return new OutlineSource(
                    text, lineCount, lineAt, language, markdown, typst, http, pom, grammar, detected, found);
        }
    }

    private List<StructureNode> buildNodes(OutlineSource src) {
        if (src.markdown()) {
            return markdownHeadingNodes(src.text());
        }
        if (src.typst()) {
            return typstHeadingNodes(src.text());
        }
        if (src.http()) {
            return httpRequestNodes(src.text());
        }
        int paras = src.lineCount();
        List<Region> regions = new ArrayList<>(new LinkedHashSet<>(src.regions()));
        if ("clojure".equals(src.language())) {
            return clojureFormNodes(src.text(), regions);
        }
        // Sort so each region precedes the regions it contains: by start asc, then by end desc.
        regions.sort(Comparator.comparingInt(Region::startLine)
                .thenComparing(Comparator.comparingInt(Region::endLine).reversed()));

        // Name/kind come from the TextMate symbols on (or, for Allman braces, just above) each region's
        // header. Regions with no symbol are control-flow/anonymous blocks and are dropped (declutter).
        Map<Integer, Symbol> symbolByLine = new HashMap<>();
        for (Symbol s : src.symbols()) {
            symbolByLine.putIfAbsent(s.line(), s);
        }
        boolean haveSymbols = !symbolByLine.isEmpty();
        boolean brace = isBraceLanguage(src.language());
        if (haveSymbols && "python".equals(src.language())) {
            // Python nests by indentation, as Python does: a one-line `def f(): ...` folds nothing but is still
            // structure. Each node takes the block its header opens (sorted outermost-first per start line).
            Map<Integer, Region> blockByLine = new HashMap<>();
            for (Region r : regions) {
                blockByLine.putIfAbsent(r.startLine(), r);
            }
            return symbolNodes(src.lineAt(), symbolByLine, paras, blockByLine);
        }
        if (regions.isEmpty() && haveSymbols) {
            // No delimiter folding for this language (python, ruby, shell, lua, yaml, ini …): there are no
            // regions to hang the symbols on, and the outline used to come out empty. Nest by indentation.
            return symbolNodes(src.lineAt(), symbolByLine, paras, Map.of());
        }

        List<StructureNode> rootNodes = new ArrayList<>();
        Deque<StructureNode> stack = new ArrayDeque<>();
        for (Region r : regions) {
            if (r.startLine() < 0 || r.startLine() >= paras) {
                continue;
            }
            StructureNode node = nodeFor(src.lineAt(), r, symbolByLine, haveSymbols, brace);
            if (node == null) {
                continue; // no symbol: skip this region (its kept descendants nest under the nearest kept ancestor)
            }
            while (!stack.isEmpty() && !contains(stack.peek().region(), r)) {
                stack.pop();
            }
            if (stack.isEmpty()) {
                rootNodes.add(node);
            } else {
                stack.peek().children().add(node);
            }
            stack.push(node);
        }
        if (rootNodes.isEmpty() && haveSymbols) {
            // Regions, but none headed by a symbol — say only `# region` markers in a language whose blocks
            // do not fold: the symbols are still the structure (one marker used to empty the outline).
            return symbolNodes(src.lineAt(), symbolByLine, paras, Map.of());
        }
        String language = src.language();
        if (language != null && (language.startsWith("javascript") || language.startsWith("typescript"))) {
            addFoldlessSymbols(rootNodes, symbolByLine, paras);
        }
        if (src.pom()) {
            rootNodes = pomLabelled(src.lineAt(), paras, rootNodes);
        }
        return rootNodes;
    }

    /** A pom's outline names dependencies/plugins by {@code groupId:artifactId} and profiles by id (C16). */
    private static List<StructureNode> pomLabelled(
            java.util.function.IntFunction<String> lineAt, int count, List<StructureNode> nodes) {
        List<StructureNode> out = new ArrayList<>(nodes.size());
        for (StructureNode n : nodes) {
            int end = n.region() == null ? -1 : n.region().endLine();
            String pom = PomOutlineLabels.label(n.label(), n.line(), end, count, lineAt);
            String label = pom == null ? n.label() : pom; // null only for a null element name
            StructureNode copy = new StructureNode(n.region(), label, n.kind(), n.line(), n.detail());
            copy.children().addAll(pomLabelled(lineAt, count, n.children()));
            out.add(copy);
        }
        return out;
    }

    /**
     * Adds the symbols no region is headed by — a one-line method ({@code get(key) { return x; }}), a one-line
     * arrow function ({@code const twice = (x) => x * 2;}) — as leaves under the innermost node whose block holds
     * their line. In JavaScript those are most declarations, and the outline used to leave every one of them out.
     */
    private static void addFoldlessSymbols(
            List<StructureNode> rootNodes, Map<Integer, Symbol> symbolByLine, int paras) {
        java.util.Set<Integer> used = new java.util.HashSet<>();
        collectLines(rootNodes, used);
        boolean added = false;
        for (Map.Entry<Integer, Symbol> e : symbolByLine.entrySet()) {
            int line = e.getKey();
            if (used.contains(line)
                    || line < 0
                    || line >= paras
                    || "tag".equals(e.getValue().kind())) {
                continue; // a JSX element (`<p>`) is markup, not a declaration
            }
            List<StructureNode> siblings = rootNodes;
            for (boolean deeper = true; deeper; ) {
                deeper = false;
                for (StructureNode n : siblings) {
                    if (n.region() != null
                            && n.region().startLine() < line
                            && line <= n.region().endLine()) {
                        siblings = n.children();
                        deeper = true;
                        break;
                    }
                }
            }
            siblings.add(
                    new StructureNode(null, e.getValue().name(), e.getValue().kind(), line));
            added = true;
        }
        if (added) {
            sortByLine(rootNodes);
        }
    }

    private static void collectLines(List<StructureNode> nodes, java.util.Set<Integer> out) {
        for (StructureNode n : nodes) {
            out.add(n.line());
            collectLines(n.children(), out);
        }
    }

    private static void sortByLine(List<StructureNode> nodes) {
        nodes.sort(Comparator.comparingInt(StructureNode::line));
        for (StructureNode n : nodes) {
            sortByLine(n.children());
        }
    }

    /**
     * Builds a node for a region: its label/kind from the TextMate symbol on the header line (or, for
     * brace languages, the signature on the nearest non-blank lines above the {@code &#123;}). Returns
     * {@code null} when no symbol is found and a grammar is active (declutter). Without symbols (no
     * grammar) it falls back to the header-line text, keeping every region.
     */
    private @Nullable StructureNode nodeFor(
            java.util.function.IntFunction<String> lineAt,
            Region r,
            Map<Integer, Symbol> symbolByLine,
            boolean haveSymbols,
            boolean brace) {
        int start = r.startLine();
        if (haveSymbols) {
            Symbol symbol = symbolByLine.get(start);
            int line = start;
            // Only look above when the delimiter is on its own line (Allman style). Looking above every
            // brace-backed fold lets an inner `if (...) {` reuse its enclosing method's symbol and lets
            // `} else {` pick up the preceding call expression, producing outlines such as
            // ifLsp() -> ifLsp() -> run(). A non-standalone opening brace belongs to its own header line;
            // without a definition symbol there, the region is control flow/anonymous and is not structure.
            if (symbol == null && brace && standaloneOpeningBrace(lineAt.apply(start))) {
                int probe = start - 1;
                int examined = 0;
                while (probe >= 0 && examined < 3) {
                    String t = lineAt.apply(probe).trim();
                    if (!t.isEmpty()) {
                        examined++;
                        Symbol up = symbolByLine.get(probe);
                        if (up != null) {
                            symbol = up;
                            line = probe;
                            break;
                        }
                    }
                    probe--;
                }
            } else if (symbol == null && brace) {
                // A signature wrapped over several lines: the name is on the line that opened the
                // parameter list, the region starts on the line that closes it (`String language) {`).
                int opener = signatureStartLine(lineAt, start);
                if (opener >= 0 && symbolByLine.get(opener) != null) {
                    symbol = symbolByLine.get(opener);
                    line = opener;
                }
            }
            if (symbol == null) {
                return null;
            }
            return new StructureNode(r, symbol.name(), symbol.kind(), line);
        }
        // No grammar/symbols: keep the old header-text label and show every region.
        String text = lineAt.apply(start).trim();
        return new StructureNode(r, text.isEmpty() ? tr("structure.line", start + 1) : text, null, start);
    }

    /** How far above a block's opening line the start of its wrapped signature is looked for. */
    private static final int MAX_SIGNATURE_LINES = 40;

    /**
     * The line that opened the parameter list which {@code braceLine} closes, or -1 when {@code braceLine}
     * does not close one. Only a header with more {@code )} than {@code (} qualifies, so an ordinary
     * {@code if (x) &#123;} or a lambda argument never borrows the symbol of a line above it.
     */
    static int signatureStartLine(java.util.function.IntFunction<String> lineText, int braceLine) {
        String header = lineText.apply(braceLine);
        int balance = parenBalance(header);
        if (balance >= 0 || header.strip().startsWith("}")) {
            return -1;
        }
        for (int line = braceLine - 1; line >= 0 && braceLine - line <= MAX_SIGNATURE_LINES; line--) {
            balance += parenBalance(lineText.apply(line));
            if (balance >= 0) {
                return line;
            }
        }
        return -1;
    }

    private static int parenBalance(String line) {
        int balance = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '(') {
                balance++;
            } else if (c == ')') {
                balance--;
            }
        }
        return balance;
    }

    /** An outline built from the symbols alone, nested by each symbol line's indentation. */
    private static List<StructureNode> symbolNodes(
            java.util.function.IntFunction<String> lineAt,
            Map<Integer, Symbol> symbolByLine,
            int paras,
            Map<Integer, Region> blockByLine) {
        List<StructureNode> rootNodes = new ArrayList<>();
        Deque<StructureNode> stack = new ArrayDeque<>();
        Deque<Integer> indents = new ArrayDeque<>();
        for (int line : new java.util.TreeSet<>(symbolByLine.keySet())) {
            if (line < 0 || line >= paras) {
                continue;
            }
            Symbol symbol = symbolByLine.get(line);
            if (symbol == null) {
                continue; // a key of the map itself: never null
            }
            String text = lineAt.apply(line);
            int indent = text.length() - text.stripLeading().length();
            while (!indents.isEmpty() && indents.peek() >= indent) {
                indents.pop();
                stack.pop();
            }
            StructureNode node = new StructureNode(blockByLine.get(line), symbol.name(), symbol.kind(), line);
            if (stack.isEmpty()) {
                rootNodes.add(node);
            } else {
                stack.peek().children().add(node);
            }
            stack.push(node);
            indents.push(indent);
        }
        return rootNodes;
    }

    /** Whether an Allman-style opening delimiter may take its declaration symbol from a preceding line. */
    static boolean standaloneOpeningBrace(String line) {
        if (line == null) {
            return false;
        }
        String stripped = line.strip();
        return stripped.equals("{") || stripped.startsWith("{ //") || stripped.startsWith("{/*");
    }

    /** Builds the heading outline for a Markdown buffer (ATX/Setext), nested by heading level. */
    private static List<StructureNode> markdownHeadingNodes(String text) {
        List<StructureNode> roots = new ArrayList<>();
        Deque<StructureNode> stack = new ArrayDeque<>();
        Deque<Integer> levels = new ArrayDeque<>();
        for (com.editora.markdown.MarkdownOutline.Heading h : com.editora.markdown.MarkdownOutline.headings(text)) {
            String title = com.editora.markdown.MarkdownText.plain(h.title()) // "**Bold** `x`" reads "Bold x"
                    .replaceAll("\\s+", " ");
            StructureNode node =
                    new StructureNode(null, title.isBlank() ? tr("common.untitled") : title, "heading", h.line());
            while (!levels.isEmpty() && levels.peek() >= h.level()) {
                stack.pop();
                levels.pop();
            }
            if (stack.isEmpty()) {
                roots.add(node);
            } else {
                stack.peek().children().add(node);
            }
            stack.push(node);
            levels.push(h.level());
        }
        return roots;
    }

    /**
     * One node per request of a {@code .http} file: its name (when it has one) and method + URL. Its own
     * "request" kind: as a "method" it got the function icon and a trailing {@code ()} ({@code GET /slow()}).
     */
    private static List<StructureNode> httpRequestNodes(String text) {
        List<StructureNode> roots = new ArrayList<>();
        for (com.editora.http.HttpFile.Request r : com.editora.http.HttpFile.parse(text)) {
            String call = r.method() + " " + r.url();
            String label = r.name() == null || r.name().isBlank() ? call : r.name() + " — " + call;
            roots.add(new StructureNode(null, label, HTTP_REQUEST_KIND, r.startLine()));
        }
        return roots;
    }

    /**
     * One node per top-level Clojure definition — {@code ns}, {@code def}/{@code defonce}, {@code defn},
     * {@code defmacro}, {@code defmulti}, {@code deftest}, {@code defprotocol}, {@code defrecord}/{@code deftype}
     * — read by the server-free declaration scanner. The Clojure grammar names none of them (it emits no
     * {@code entity.name.*} scope), so the TextMate path found no structure in a Clojure file at all. Each node
     * takes the fold region its form opens, when the form spans lines.
     */
    static List<StructureNode> clojureFormNodes(String text, List<Region> regions) {
        Map<Integer, Region> formByLine = new HashMap<>();
        for (Region r : regions) {
            formByLine.merge(r.startLine(), r, (a, b) -> a.endLine() >= b.endLine() ? a : b); // the outermost
        }
        List<StructureNode> roots = new ArrayList<>();
        List<Integer> lineStarts = new ArrayList<>(List.of(0));
        for (int i = text.indexOf('\n'); i >= 0; i = text.indexOf('\n', i + 1)) {
            lineStarts.add(i + 1);
        }
        for (com.editora.index.Symbol s : com.editora.index.DeclarationScanner.scan(text, "clojure")) {
            String kind = switch (s.kind()) {
                case MODULE -> "namespace";
                case INTERFACE -> "interface";
                case TYPE, ENUM -> "struct";
                case VARIABLE, FIELD -> "variable";
                case FUNCTION, METHOD -> "function";
            };
            String params = "function".equals(kind) && s.line() < lineStarts.size()
                    ? clojureParams(
                            text,
                            lineStarts.get(s.line()) + s.column() + s.name().length())
                    : "";
            roots.add(new StructureNode(formByLine.get(s.line()), s.name(), kind, s.line(), params));
        }
        return roots;
    }

    /** How far past a Clojure definition's name its parameter vector is looked for. */
    private static final int MAX_CLOJURE_PARAMS_REACH = 400;

    /**
     * The parameter vector of the Clojure {@code defn}/{@code defmacro} whose name ends at {@code from} —
     * {@code [x y]}, past a docstring and an attribute map — whitespace collapsed; {@code ""} for a multi-arity
     * definition or none within reach. Shown in place of the {@code ()} every other function gets.
     */
    static String clojureParams(String text, int from) {
        int n = Math.min(text.length(), from + MAX_CLOJURE_PARAMS_REACH);
        int i = skipClojureSpace(text, from, n);
        if (i < n && text.charAt(i) == '"') { // the docstring
            for (i++; i < n && text.charAt(i) != '"'; i++) {
                if (text.charAt(i) == '\\') {
                    i++;
                }
            }
            i = skipClojureSpace(text, i + 1, n);
        }
        if (i < n && text.charAt(i) == '{') { // an attribute map
            int depth = 0;
            for (; i < n; i++) {
                char c = text.charAt(i);
                depth += c == '{' ? 1 : c == '}' ? -1 : 0;
                if (depth == 0) {
                    break;
                }
            }
            i = skipClojureSpace(text, i + 1, n);
        }
        if (i >= n || text.charAt(i) != '[') {
            return "";
        }
        int depth = 0;
        for (int j = i; j < n; j++) {
            char c = text.charAt(j);
            if (c == '[' || c == '{' || c == '(') {
                depth++;
            } else if (c == ']' || c == '}' || c == ')') {
                if (--depth == 0) {
                    return text.substring(i, j + 1).replaceAll("\\s+", " ");
                }
            }
        }
        return "";
    }

    private static int skipClojureSpace(String text, int i, int n) {
        while (i < n && (Character.isWhitespace(text.charAt(i)) || text.charAt(i) == ',')) {
            i++;
        }
        return i;
    }

    /** The Structure kind of a {@code .http} request (its icon: {@link StructureIcons}). */
    static final String HTTP_REQUEST_KIND = "request";

    /**
     * Builds the heading outline for a Typst buffer, nested by level — the Markdown branch's twin.
     *
     * <p>Without it a {@code .typ} file fell through to the generic fold-region path, which for Typst meant
     * its brace pairs labelled by whatever TextMate symbol sat on the header line: a report with three
     * sections showed one entry reading {@code #align()}. Sections are what a Typst document is navigated
     * by, and nothing else in the outline was worth keeping alongside them.
     */
    private static List<StructureNode> typstHeadingNodes(String text) {
        List<StructureNode> roots = new ArrayList<>();
        Deque<StructureNode> stack = new ArrayDeque<>();
        Deque<Integer> levels = new ArrayDeque<>();
        var outline = com.editora.typst.TypstOutline.scan(text);

        // Headings and bindings are merged by line so the outline reads in document order, and each
        // binding nests under whichever section it is written in — a #let below "= Setup" belongs to it.
        // A binding above the first heading stays at the root, which is where such definitions live.
        record Entry(int line, int level, String label, String kind) {}
        List<Entry> entries = new ArrayList<>();
        for (var h : outline.headings()) {
            entries.add(
                    new Entry(h.line(), h.level(), h.title().isBlank() ? tr("common.untitled") : h.title(), "heading"));
        }
        for (var b : outline.bindings()) {
            // Level 0 = "not a section": it never becomes a parent and never closes one.
            entries.add(new Entry(b.line(), 0, b.name(), "let".equals(b.kind()) ? "function" : "property"));
        }
        entries.sort(java.util.Comparator.comparingInt(Entry::line));

        for (Entry e : entries) {
            StructureNode node = new StructureNode(null, e.label(), e.kind(), e.line());
            if (e.level() > 0) {
                while (!levels.isEmpty() && levels.peek() >= e.level()) {
                    stack.pop();
                    levels.pop();
                }
            }
            if (stack.isEmpty()) {
                roots.add(node);
            } else {
                stack.peek().children().add(node);
            }
            if (e.level() > 0) {
                stack.push(node);
                levels.push(e.level());
            }
        }
        return roots;
    }

    /** Languages whose folds are brace-delimited (Allman braces put the signature above the start line). */
    private static boolean isBraceLanguage(String language) {
        return switch (language == null ? "" : language) {
            case "java", "json", "c", "cpp", "rust", "go", "kotlin", "groovy", "scala", "csharp", "css" -> true;
            default -> false;
        };
    }

    private static boolean contains(@Nullable Region outer, Region inner) {
        return outer != null && outer.startLine() <= inner.startLine() && outer.endLine() >= inner.endLine();
    }

    private void applyFilter(String query) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        TreeItem<StructureNode> root = new TreeItem<>(null);
        for (StructureNode n : roots) {
            TreeItem<StructureNode> item = toItem(n, q);
            if (item != null) {
                root.getChildren().add(item);
            }
        }
        if (root.getChildren().isEmpty()) {
            String message = buffer == null
                    ? tr("structure.noFileOpen")
                    : (q.isEmpty() ? tr("structure.noStructure") : tr("structure.noMatches"));
            root.getChildren().add(new TreeItem<>(new StructureNode(null, message, null, -1)));
            tree.setRoot(root);
        } else {
            tree.setRoot(root);
            tree.getSelectionModel().select(0);
        }
    }

    /** Builds a tree item for {@code node}, keeping it if it matches the query or has a matching descendant. */
    private @Nullable TreeItem<StructureNode> toItem(StructureNode node, String q) {
        List<TreeItem<StructureNode>> childItems = new ArrayList<>();
        for (StructureNode child : node.children()) {
            TreeItem<StructureNode> ci = toItem(child, q);
            if (ci != null) {
                childItems.add(ci);
            }
        }
        boolean kindVisible = node.kind() == null || !hiddenKinds.contains(node.kind());
        boolean self = kindVisible && (q.isEmpty() || FuzzyMatch.of(node.label(), q) != null);
        if (!self && childItems.isEmpty()) {
            return null;
        }
        TreeItem<StructureNode> item = new TreeItem<>(node);
        item.getChildren().setAll(childItems);
        item.setExpanded(true); // expand by default so the whole outline is visible at a glance
        return item;
    }

    private final class StructureCell extends TreeCell<StructureNode> {
        /**
         * The item whose tooltip and graphic this cell holds. A selection change makes JavaFX run
         * {@code updateItem} again for the same item, from the cell's layout — after that pulse's CSS pass — so
         * a graphic rebuilt there painted its text unstyled, in black, for one frame: the row clicked and the
         * row left both blinked on every click.
         */
        private @Nullable StructureNode shown;

        @Override
        protected void updateItem(StructureNode item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                shown = null;
                setText(null);
                setGraphic(null);
                setTooltip(null);
                setContextMenu(null);
                setOnContextMenuRequested(null);
                return;
            }
            boolean same = item == shown;
            shown = item;
            // Show the cleaned leading doc comment (if any) as a hover tooltip.
            if (!same) {
                if (item.doc() != null && !item.doc().isEmpty()) {
                    Tooltip tip = new Tooltip(item.doc());
                    tip.setWrapText(true);
                    tip.setMaxWidth(480);
                    tip.setShowDelay(javafx.util.Duration.millis(400));
                    setTooltip(tip);
                } else {
                    setTooltip(null);
                }
            }
            boolean real = item.kind() != null && item.line() >= 0;
            pseudoClassStateChanged(
                    OUT_OF_REACH, real && buffer != null && buffer.isNarrowed() && areaLine(item.line()) < 0);
            setContextMenu(real ? markerMenu(item) : null);
            setOnContextMenuRequested(
                    real
                            ? e -> {
                                tree.getSelectionModel().select(getTreeItem());
                            }
                            : null);
            if (!real) {
                setText(item.label());
                setGraphic(null);
                return;
            }
            if (same && getGraphic() != null) {
                return;
            }
            String sig = signature(item);
            // Render every real symbol as icon + syntax-colored label/return type + 1-based source line.
            // Keeping the line as its own Text node lets themes mute it without muting the symbol signature.
            setText(null);
            HBox box = new HBox();
            box.setAlignment(Pos.CENTER_LEFT);
            Node icon = StructureIcons.forKind(item.kind());
            HBox.setMargin(icon, new javafx.geometry.Insets(0, 4, 0, 0));
            box.getChildren().add(icon);
            for (SyntaxRun run : item.nameRuns()) {
                box.getChildren().add(styledText(run, "structure-name"));
            }
            if (!sig.isEmpty()) {
                javafx.scene.text.Text detail = new javafx.scene.text.Text(sig);
                detail.getStyleClass().add("structure-detail");
                box.getChildren().add(detail);
            }
            if (!item.returnRuns().isEmpty()) {
                javafx.scene.text.Text separator = new javafx.scene.text.Text(" : ");
                separator.getStyleClass().add("structure-detail");
                box.getChildren().add(separator);
                for (SyntaxRun run : item.returnRuns()) {
                    box.getChildren().add(styledText(run, "structure-return-type"));
                }
            }
            javafx.scene.text.Text line = new javafx.scene.text.Text("  " + tr("structure.line", item.line() + 1));
            line.getStyleClass().add("structure-line-number");
            box.getChildren().add(line);
            setGraphic(box);
        }

        private static javafx.scene.text.Text styledText(SyntaxRun run, String role) {
            javafx.scene.text.Text text = new javafx.scene.text.Text(run.text());
            // The shared `text` + syntax classes are exactly what syntax.css and every editor-theme
            // override target, so changing the editor color theme recolors this run in the same pulse.
            text.getStyleClass().addAll("structure-name", "text");
            if (!"structure-name".equals(role)) {
                text.getStyleClass().add(role);
            }
            text.getStyleClass().addAll(run.styles());
            return text;
        }

        /**
         * The signature to show after a callable symbol's name: the server's detail (e.g. {@code (String x) :
         * void}, wrapped in parens if it isn't already), or {@code "()"} when there's no detail. Empty for
         * non-callable kinds, so fields/classes show just their name — and also empty when the label already
         * carries its own parameter list (jdtls names methods like {@code setX(Foo)}), so we don't append a
         * redundant {@code ()} and end up with {@code setX(Foo)()}.
         */
        private static String signature(StructureNode n) {
            if (!isCallable(n.kind())) {
                return "";
            }
            String d = n.detail() == null ? "" : n.detail().strip();
            if (d.isEmpty()) {
                return n.label() != null && n.label().indexOf('(') >= 0 ? "" : "()";
            }
            if (d.startsWith("[")) {
                return " " + d; // a Clojure parameter vector: `add! [item]`, not `add!([item])`
            }
            return d.startsWith("(") ? d : "(" + d + ")";
        }

        private static boolean isCallable(@Nullable String kind) {
            return "method".equals(kind) || "function".equals(kind) || "constructor".equals(kind);
        }
    }

    /** A node in the structure tree: a foldable region, its symbol label/kind, the line to navigate to, an
     *  optional detail (the LSP signature, e.g. {@code (String x) : void}), syntax-styled label and return
     *  runs, an optional doc comment (the cleaned leading comment above the declaration), and children. */
    private static final class StructureNode {
        private final @Nullable Region region;
        private final String label;
        private final @Nullable String kind;
        private final int line;
        private final String detail;
        private List<SyntaxRun> nameRuns = List.of();
        private List<SyntaxRun> returnRuns = List.of();
        private String doc = ""; // filled by attachDocs() from the buffer text; "" when none
        private final List<StructureNode> children = new ArrayList<>();

        StructureNode(@Nullable Region region, String label, @Nullable String kind, int line) {
            this(region, label, kind, line, "");
        }

        StructureNode(@Nullable Region region, String label, @Nullable String kind, int line, String detail) {
            this.region = region;
            this.label = label;
            this.kind = kind;
            this.line = line;
            this.detail = detail == null ? "" : detail;
        }

        @Nullable
        Region region() {
            return region;
        }

        String label() {
            return label;
        }

        @Nullable
        String kind() {
            return kind;
        }

        int line() {
            return line;
        }

        String detail() {
            return detail;
        }

        List<SyntaxRun> nameRuns() {
            return nameRuns;
        }

        void setNameRuns(List<SyntaxRun> runs) {
            nameRuns = runs == null ? List.of() : List.copyOf(runs);
        }

        List<SyntaxRun> returnRuns() {
            return returnRuns;
        }

        void setReturnRuns(List<SyntaxRun> runs) {
            returnRuns = runs == null ? List.of() : List.copyOf(runs);
        }

        String doc() {
            return doc;
        }

        void setDoc(String doc) {
            this.doc = doc == null ? "" : doc;
        }

        List<StructureNode> children() {
            return children;
        }
    }
}
