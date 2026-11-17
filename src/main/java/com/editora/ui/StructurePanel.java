package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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

import com.editora.editor.ControlNames;
import com.editora.editor.EditorBuffer;
import com.editora.editor.UiZoom;
import com.editora.editor.fold.NarrowLines;
import com.editora.lsp.SymbolNode;
import com.editora.search.FuzzyMatch;
import com.editora.text.FoldRegions.Region;
import com.editora.ui.StructureNode.SyntaxRun;
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
     * The language server's outline of {@code file} when it is this panel's buffer and the server served one —
     * what the Project Map's structure column of an open file shows — else null (the heuristic is used).
     */
    @Nullable
    List<SymbolNode> serverSymbolsFor(Path file) {
        EditorBuffer attached = buffer;
        Path path = attached == null ? null : attached.getPath();
        return path != null
                        && lspSymbols != null
                        && path.toAbsolutePath().normalize().equals(file)
                ? lspSymbols
                : null;
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
        filterField.textProperty().addListener((o, w, n) -> {
            activateWhenFiltered = false; // typed on after Enter: the Enter was for the text before
            applyFilter(n);
        });
        // Down/Enter → into / open the results; Enter typed before the filter's answer opens the first of the latest
        FilterFieldNav.install(filterField, tree, () -> {
            if (filterSearch.pending()) {
                activateWhenFiltered = true;
            } else {
                activateSelected();
            }
        });
        // Trailing clear ("✕") button — visible only while the filter has text (mirrors the Project/Notes panels).
        Button clearFilter = new Button("✕");
        clearFilter.getStyleClass().add("project-filter-clear");
        clearFilter.setFocusTraversable(false);
        Icons.name(clearFilter, tr("project.filterClear"));
        clearFilter.setOnAction(e -> {
            filterField.clear();
            filterField.requestFocus();
        });
        clearFilter.visibleProperty().bind(filterField.textProperty().isEmpty().not());
        ClearableField.command(filterField, clearFilter);
        clearFilter.managedProperty().bind(clearFilter.visibleProperty());
        // Narrow to fold region: one toggle at the end of the sort/kind row (the search field keeps its width). A
        // click turns fold mode on and narrows to the picked symbol's block; a second click widens. It is lit
        // while this panel narrowed the buffer; narrowed some other way (C-x n n), an amber border says so.
        narrowFold.setGraphic(Icons.foldRegion());
        narrowFold.getStyleClass().addAll("structure-narrow-fold", "flat");
        // refreshNarrowTooltips names it: what narrowing does and its keys, one string for the tooltip and a
        // screen reader.
        narrowFold.setFocusTraversable(false);
        narrowFold.setOnAction(e -> toggleFoldMode());
        refreshNarrowTooltips();
        syncNarrowToggle();
        HBox.setHgrow(filterField, Priority.ALWAYS);
        HBox filterBar = UiZoom.spacing(new HBox(filterField, clearFilter), 6);
        filterBar.getStyleClass().add("project-filter-bar");
        filterBar.setAlignment(Pos.CENTER_LEFT);

        // Sort mode (Position / Name / Kind) — re-sorts and re-renders the outline.
        sortCombo.getItems().setAll(SortMode.POSITION, SortMode.NAME, SortMode.KIND);
        sortCombo.setValue(SortMode.POSITION);
        FocusCommands.bind(this, "structure.sortBy", sortCombo::show); // the choices, in the drop-down itself
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
        CommandTips.name(kindFilter, "structure.filterKinds");
        kindFilter.setFocusTraversable(false);

        javafx.scene.layout.Region toolbarSpacer = new javafx.scene.layout.Region();
        HBox.setHgrow(toolbarSpacer, Priority.ALWAYS);
        HBox toolbar = UiZoom.spacing(new HBox(sortCombo, kindFilter, toolbarSpacer, narrowFold), 4);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getStyleClass().add("structure-toolbar");

        tree.setShowRoot(false);
        tree.getStyleClass().add("structure-tree");
        tree.setCellFactory(t -> new StructureCell());
        RowContextMenu.install(tree); // Menu key / Shift+F10 open the selected row's menu (cells are not focusable)
        // The symbol menu's commands (structure.bookmarkSelected…) fire its rows for the selected symbol.
        FocusCommands.menu(tree, () -> {
            TreeItem<StructureNode> item = tree.getSelectionModel().getSelectedItem();
            StructureNode node = item == null ? null : item.getValue();
            ContextMenu menu = node == null || node.kind() == null ? null : markerMenu(node);
            if (menu == null) {
                return null;
            }
            javafx.event.Event.fireEvent(
                    menu, new javafx.stage.WindowEvent(menu, javafx.stage.WindowEvent.WINDOW_SHOWING));
            return menu.getItems();
        });
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

        UiZoom.spacing(this, 4);
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
        // The model is built off the FX thread (PERF-01, Pauta 6.0.1): this answers what is known for the buffer now
        // (nothing at first) and the picker is told to read again when it lands (onHiddenOutlineChanged).
        if (pendingRebuild) {
            EditorBuffer b = buffer;
            if (b != null && (hiddenFor != b || hiddenVersion != b.docVersion())) {
                hiddenFor = b;
                hiddenVersion = b.docVersion();
                hiddenRoots = List.of();
                long version = hiddenVersion;
                boolean[] asking = {true};
                buildOutline(b, ++outlineGeneration, nodes -> {
                    if (hiddenFor == b && hiddenVersion == version) {
                        hiddenRoots = nodes;
                        if (!asking[0] && onHiddenOutlineChanged != null) {
                            onHiddenOutlineChanged.run(); // landed later: the picker reads again
                        }
                    }
                });
                asking[0] = false;
            }
            collectOutline(b == null ? List.of() : hiddenRoots, out);
            return out;
        }
        collectOutline(roots, out);
        return out;
    }

    /** The outline built for the picker while the window is closed, the buffer and version it is of. */
    private List<StructureNode> hiddenRoots = List.of();

    private @Nullable EditorBuffer hiddenFor;
    private long hiddenVersion = -1;

    /** Where the outline is built and its doc comments read: off the FX thread, never on it. */
    static final String OUTLINE_SITE = "StructurePanel.outline";

    /** The latest outline asked for: an answer for an earlier one is dropped. */
    private long outlineGeneration;

    /**
     * Builds the outline of {@code b} off the FX thread (PERF-01, Pauta 6.0.1: the document is split into lines, a
     * Markdown or narrowed document parsed, the doc comments read) from a copy of its text, regions and symbols taken
     * here, and hands it to {@code then} on the FX thread with its docs and syntax colours, sorted — unless a later
     * build was asked for meanwhile. Text changed meanwhile: built again on the new text.
     */
    private void buildOutline(EditorBuffer b, long gen, java.util.function.Consumer<List<StructureNode>> then) {
        long version = b.docVersion();
        String text = b.getContent();
        boolean narrowed = b.isNarrowed();
        List<StructureNode> cached = narrowed ? narrowedRoots : null;
        List<Integer> cachedLines = cached == null ? null : linesOf(cached, new ArrayList<>());
        List<SymbolNode> lsp = lspSymbols;
        boolean python = b.isPython();
        String language = b.getLanguage();
        boolean markdown = b.isMarkdown();
        boolean typst = b.isTypst();
        boolean http = b.isHttpFile();
        boolean pom = b.isPom();
        List<Region> regions = List.copyOf(b.getFoldManager().regions());
        List<com.editora.editor.highlight.TextMateHighlighter.Symbol> symbols = List.copyOf(b.symbols());
        Path path = b.getPath();
        String fileName = path == null || path.getFileName() == null
                ? null
                : path.getFileName().toString();
        com.editora.perf.OffFx.then(
                OUTLINE_SITE,
                () -> {
                    List<String> lines = List.of(text.split("\n", -1));
                    List<StructureNode> built;
                    if (cached != null) {
                        built = cached; // only read on the FX thread: its docs are read below by line
                    } else if (narrowed) {
                        built = StructureOutline.build(
                                StructureOutline.Source.file(fileName == null ? "" : fileName, text)
                                        .analyzedAs(language, markdown, typst, http, pom));
                    } else if (lsp != null) {
                        built = StructureOutline.fromLsp(lsp, language, pom, lines::get, lines.size());
                    } else {
                        built = StructureOutline.build(new StructureOutline.Source(
                                text,
                                lines.size(),
                                lines::get,
                                language,
                                markdown,
                                typst,
                                http,
                                pom,
                                null,
                                regions,
                                symbols,
                                fileName));
                    }
                    java.util.Map<Integer, String> docs = new java.util.HashMap<>();
                    java.util.Map<Integer, String> markers = new java.util.HashMap<>();
                    for (int line : cachedLines != null ? cachedLines : linesOf(built, new ArrayList<>())) {
                        docs.computeIfAbsent(line, l -> StructureDoc.commentAbove(lines, l));
                        if (python && line < lines.size()) {
                            // `async`, `property`, `classmethod` … beside the row, read from the decorators above
                            // its line (review 2 C113): several lines per row, so here, not on the FX thread.
                            markers.computeIfAbsent(line, l -> PythonOutlineSymbols.marker(lines::get, l));
                        }
                    }
                    return new Built(built, docs, markers);
                },
                answer -> {
                    if (gen != outlineGeneration || buffer != b) {
                        return; // another build was asked for since, or another buffer attached
                    }
                    if (b.docVersion() != version) {
                        buildOutline(b, gen, then); // the text moved on: built again on the new text
                        return;
                    }
                    Built done = answer.value();
                    if (done == null) {
                        return; // a failure is the outline's own business: the tree keeps what it shows
                    }
                    if (narrowed && cached == null && b.isNarrowed()) {
                        narrowedRoots = done.nodes();
                    }
                    applyDocs(done.nodes(), done.docs(), done.markers());
                    attachSyntaxStyles(done.nodes());
                    sortNodes(done.nodes());
                    then.accept(done.nodes());
                });
    }

    /** An outline built off the FX thread and the doc comment above each of its lines. */
    private record Built(
            List<StructureNode> nodes, java.util.Map<Integer, String> docs, java.util.Map<Integer, String> markers) {}

    /** The lines of {@code nodes} and their children, in tree order. */
    private static List<Integer> linesOf(List<StructureNode> nodes, List<Integer> out) {
        for (StructureNode n : nodes) {
            if (n.line() >= 0) {
                out.add(n.line());
            }
            linesOf(n.children(), out);
        }
        return out;
    }

    private static void applyDocs(
            List<StructureNode> nodes, java.util.Map<Integer, String> docs, java.util.Map<Integer, String> markers) {
        for (StructureNode n : nodes) {
            if (n.line() >= 0) {
                n.setDoc(docs.getOrDefault(n.line(), ""));
                n.setMarker(markers.getOrDefault(n.line(), ""));
            }
            applyDocs(n.children(), docs, markers);
        }
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
        hiddenFor = null;
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
        com.editora.editor.RowReasons.featureOn(note, actions.personalNotesEnabled(), "view.toggleNotes");
        note.setOnAction(e -> {
            if (buffer != null) {
                actions.addPersonalNote(buffer, areaLine);
            }
        });
        FocusCommands.row(bookmark, "structure.bookmarkSelected");
        FocusCommands.row(note, "structure.noteSelected");
        ContextMenu menu = new ContextMenu(bookmark, note);
        menu.setOnShowing(
                e -> com.editora.editor.RowReasons.featureOn(note, actions.personalNotesEnabled(), "view.toggleNotes"));
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
        if (modelShown) {
            // Only rows in the area borrow its colours: recolour the shown rows now, so the outline computed
            // for this narrowing matches them and is not rebuilt (a rebuild loses the selection for a frame).
            attachSyntaxStyles(roots);
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
        // The text taken here; split, its grammar looked up and analyzed off the FX thread (PERF-01, Pauta 6.0.1).
        String text = b.getContent();
        String language = b.getLanguage();
        boolean markdown = b.isMarkdown();
        boolean typst = b.isTypst();
        boolean http = b.isHttpFile();
        boolean pom = b.isPom();
        Thread.ofVirtual().name("editora-structure-outline").start(() -> {
            com.editora.perf.BlockingSites.enter(OUTLINE_SITE);
            List<StructureNode> nodes = StructureOutline.build(
                    StructureOutline.Source.file("", text).analyzedAs(language, markdown, typst, http, pom));
            Platform.runLater(() -> {
                if (generation != narrowedOutlineGeneration || buffer != b || !b.isNarrowed()) {
                    return;
                }
                narrowedRoots = nodes;
                rebuild(true);
            });
        });
    }

    /**
     * The toggle's name, for its tooltip and a screen reader alike: the tip, then the keys — the panel's own letter
     * (and the command's chord when one is bound), the editor's. Read from the keymap each time it is called.
     */
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
        Icons.name(narrowFold, text.toString());
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
        // Anchor the target near the top of the usable viewport, below any sticky-scroll rows, in this pulse (a
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
        EditorBuffer b = buffer;
        long gen = ++outlineGeneration;
        if (b == null) {
            show(new ArrayList<>(), onlyIfChanged); // mutable: sortNodes sorts roots in place
            return;
        }
        buildOutline(b, gen, next -> show(next, onlyIfChanged));
    }

    /** Shows the outline built for the attached buffer, unless it is the one shown already ({@code onlyIfChanged}). */
    private void show(List<StructureNode> next, boolean onlyIfChanged) {
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
        applyFilter(filterField.getText(), () -> reselect(prevSelection), () -> {
            // Row 0 is selected by default; only a row the user chose (and that survived) is a narrowing target.
            StructureNode now = selectedNodeValue();
            userPicked = wasPicked
                    && prevSelection != null
                    && now != null
                    && now.line() == prevSelection.line()
                    && java.util.Objects.equals(now.label(), prevSelection.label());
        });
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
                    || !x.marker().equals(y.marker())
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
        hiddenFor = null; // the picker's outline is built again from these
        rebuild();
        if (!visible() && onHiddenOutlineChanged != null) {
            onHiddenOutlineChanged.run();
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

    /**
     * Colours each row from its own declaration line in the editor, and only that line: the token classes of the
     * characters the label borrows, a bounded per-row read like painting the row. A Python row's marker, read from the
     * decorators above, comes with the outline from off the FX thread ({@link #buildOutline}).
     */
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
        node.setReturnRuns(List.of()); // a recolour (narrowChanged) must not keep runs a fresh node would not have
        if (line < 0 || line >= area.getParagraphs().size()) {
            node.setNameRuns(List.of(fallbackRun(label, node.kind())));
            return;
        }

        String sourceLine = area.getParagraph(line).getText();
        String stem = callableStem(label);
        int column = nameColumn(sourceLine, stem);
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

    /**
     * A declaration label's runs, each with the token classes its source text has in {@code area} at {@code line}
     * (the area's own line): what a Structure row shows. Labels the area does not have, or whose line it does not
     * colour, take the kind's colour. The Project Map's card Structure columns borrow their file card's colours.
     */
    static List<SyntaxRun> labelRuns(CodeArea area, int line, @Nullable String label, @Nullable String kind) {
        String text = label == null ? "" : label;
        if (line < 0 || line >= area.getParagraphs().size()) {
            return List.of(fallbackRun(text, kind));
        }
        String sourceLine = area.getParagraph(line).getText();
        String stem = callableStem(text);
        int column = stem.isEmpty() ? -1 : sourceLine.indexOf(stem);
        return column < 0
                ? List.of(fallbackRun(text, kind))
                : styledDisplayRuns(area, line, text, sourceLine, column, kind);
    }

    /**
     * Where {@code stem} (a symbol's name) stands in {@code line} as a whole word: {@code def f(} names {@code f}
     * at column 4, not the {@code f} inside {@code def}, whose leftovers then read as a return type "de"
     * (review 2 A105). The first plain occurrence when it never stands alone; -1 when it is not there.
     */
    static int nameColumn(String line, String stem) {
        if (stem.isEmpty()) {
            return -1;
        }
        boolean wordStart = isNameChar(stem.charAt(0));
        boolean wordEnd = isNameChar(stem.charAt(stem.length() - 1));
        for (int at = line.indexOf(stem); at >= 0; at = line.indexOf(stem, at + 1)) {
            int after = at + stem.length();
            boolean startOk = !wordStart || at == 0 || !isNameChar(line.charAt(at - 1));
            boolean endOk = !wordEnd || after >= line.length() || !isNameChar(line.charAt(after));
            if (startOk && endOk) {
                return at;
            }
        }
        return line.indexOf(stem);
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
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
        if (value == null || value.isBlank() || value.endsWith(".")) {
            return false; // `module.exports.formatBytes = …`: a member access, no return type (A106)
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

    /** Structure: Filter by Kind… — shows the button's menu; false when the outline has no kinds to filter. */
    boolean showKindFilter() {
        if (kindFilter.isDisabled()) {
            return false;
        }
        kindFilter.show();
        return true;
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

    private void applyFilter(String query) {
        applyFilter(query, null, null);
    }

    /** Where the filter is matched against the outline's labels: a picker thread, never the FX thread. */
    static final String FILTER_SITE = "StructurePanel.filter";

    /** The filter in flight: a newer keystroke (or rebuild) supersedes it, and only the latest answer is shown. */
    private final LatestOnly filterSearch = PickerSearch.latest();

    /**
     * Shows the outline narrowed to the labels {@code query} matches. An outline can hold thousands of rows, so a
     * typed query is matched off the FX thread (PERF-01, Pauta 6.0.1) on a copy of the labels; the tree shown stays
     * until the latest answer lands. {@code reselect} runs with the tree, navigation held (a rebuild keeping the
     * user's row), and {@code after} once it is shown.
     */
    private void applyFilter(String query, @Nullable Runnable reselect, @Nullable Runnable after) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        List<StructureNode> shown = roots;
        if (q.isEmpty()) {
            filterSearch.cancel();
            showTree(shown, q, null, reselect, after);
            return;
        }
        List<StructureNode> flat = new ArrayList<>();
        flatten(shown, flat);
        List<String> labels = new ArrayList<>(flat.size());
        for (StructureNode n : flat) {
            labels.add(n.label() == null ? "" : n.label());
        }
        filterSearch.submit(
                () -> {
                    com.editora.perf.BlockingSites.enter(FILTER_SITE);
                    java.util.BitSet hit = new java.util.BitSet(labels.size());
                    for (int i = 0; i < labels.size(); i++) {
                        if (FuzzyMatch.of(labels.get(i), q) != null) {
                            hit.set(i);
                        }
                    }
                    return hit;
                },
                hit -> {
                    if (shown != roots) {
                        return; // a rebuild came since: it filters its own outline
                    }
                    java.util.Set<StructureNode> matched =
                            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
                    for (int i = hit.nextSetBit(0); i >= 0; i = hit.nextSetBit(i + 1)) {
                        matched.add(flat.get(i));
                    }
                    showTree(shown, q, matched, reselect, after);
                    if (activateWhenFiltered) {
                        activateWhenFiltered = false;
                        activateSelected();
                    }
                });
    }

    /** Enter was pressed in the filter field while its answer was still coming: open the row once it lands. */
    private boolean activateWhenFiltered;

    /** Whether the tree is not yet filtered for the text typed (a test waits on it). */
    boolean filtering() {
        return filterSearch.pending();
    }

    private static void flatten(List<StructureNode> nodes, List<StructureNode> out) {
        for (StructureNode n : nodes) {
            out.add(n);
            flatten(n.children(), out);
        }
    }

    /** Builds the tree of {@code shown}: every row when {@code matched} is null, else the matched ones and their parents. */
    private void showTree(
            List<StructureNode> shown,
            String q,
            java.util.@Nullable Set<StructureNode> matched,
            @Nullable Runnable reselect,
            @Nullable Runnable after) {
        boolean held = suppressNavigation;
        suppressNavigation = held || reselect != null;
        try {
            buildTree(shown, q, matched);
            if (reselect != null) {
                reselect.run();
            }
        } finally {
            suppressNavigation = held;
        }
        if (after != null) {
            after.run();
        }
    }

    private void buildTree(List<StructureNode> shown, String q, java.util.@Nullable Set<StructureNode> matched) {
        TreeItem<StructureNode> root = new TreeItem<>(null);
        for (StructureNode n : shown) {
            TreeItem<StructureNode> item = toItem(n, matched);
            if (item != null) {
                root.getChildren().add(item);
            }
        }
        if (root.getChildren().isEmpty()) {
            String message = buffer == null
                    ? tr("structure.noFileOpen")
                    : !q.isEmpty()
                            ? tr("structure.noMatches")
                            // say why a large file has none, not just that (XML review C18)
                            : tr(buffer.isLargeFile() ? "structure.largeFile" : "structure.noStructure");
            root.getChildren().add(new TreeItem<>(new StructureNode(null, message, null, -1)));
            tree.setRoot(root);
        } else {
            tree.setRoot(root);
            tree.getSelectionModel().select(0);
        }
    }

    /** Builds a tree item for {@code node}, keeping it if it matches the query or has a matching descendant. */
    private @Nullable TreeItem<StructureNode> toItem(
            StructureNode node, java.util.@Nullable Set<StructureNode> matched) {
        List<TreeItem<StructureNode>> childItems = new ArrayList<>();
        for (StructureNode child : node.children()) {
            TreeItem<StructureNode> ci = toItem(child, matched);
            if (ci != null) {
                childItems.add(ci);
            }
        }
        boolean kindVisible = node.kind() == null || !hiddenKinds.contains(node.kind());
        boolean self = kindVisible && (matched == null || matched.contains(node));
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
                    Tooltip tip = ControlNames.tooltip(item.doc());
                    tip.setWrapText(true);
                    tip.setMaxWidth(UiZoom.px(480));
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
            UiZoom.follow(icon, () -> HBox.setMargin(icon, UiZoom.insets(0, 4, 0, 0)));
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
            if (!item.marker().isEmpty()) {
                javafx.scene.text.Text marker = new javafx.scene.text.Text("  " + item.marker());
                marker.getStyleClass().add("structure-marker");
                box.getChildren().add(marker);
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
            if (d.startsWith(":") && n.label() != null && n.label().indexOf('(') >= 0) {
                // jdtls names a method `add(Shape)` and sends only ` : void`: no "add(Shape)(: void)" (BN5)
                return n.returnRuns().isEmpty() ? " " + d : "";
            }
            return d.startsWith("(") ? d : "(" + d + ")";
        }

        private static boolean isCallable(@Nullable String kind) {
            return "method".equals(kind) || "function".equals(kind) || "constructor".equals(kind);
        }
    }
}
