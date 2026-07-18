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
import javafx.scene.control.IndexedCell;
import javafx.scene.control.MenuButton;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import com.editora.editor.EditorBuffer;
import com.editora.editor.FoldRegions.Region;
import com.editora.editor.TextMateHighlighter.Symbol;
import com.editora.lsp.SymbolNode;
import org.fxmisc.richtext.CodeArea;

import static com.editora.i18n.Messages.tr;

/**
 * Tool window content showing the active editor's foldable-region hierarchy as a collapsible tree,
 * mirroring the code/text folding model ({@link com.editora.editor.FoldRegions}). A search box at the
 * top fuzzy-filters the tree, and selecting an entry navigates the editor caret to that line.
 *
 * <p>The panel marks itself with the {@code editora.ownsKeys} property so the scene-level key
 * dispatcher yields, letting it implement Emacs-style navigation locally: {@code C-n}/{@code C-p}
 * move, {@code C-f}/{@code C-b} expand/collapse (or descend/ascend), {@code Enter} jumps to the line,
 * and {@code C-g}/{@code Esc} clears the filter or returns focus to the editor.
 */
public class StructurePanel extends VBox implements ToolWindowContent {

    /** How the outline rows are ordered (session-only, like the filter box). */
    public enum SortMode {
        POSITION,
        NAME,
        KIND
    }

    private final TextField filterField = new TextField();
    private final TreeView<StructureNode> tree = new TreeView<>();
    private final ComboBox<SortMode> sortCombo = new ComboBox<>();
    private final MenuButton kindFilter = new MenuButton();

    private EditorBuffer buffer;
    private List<StructureNode> roots = List.of();
    /**
     * The LSP document-symbol outline for the active buffer, or {@code null} to use the TextMate/fold
     * heuristic. {@link #setLspSymbols} pushes it (an empty list means "LSP served, but no symbols").
     */
    private List<SymbolNode> lspSymbols;

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
            }
        });
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

        // Kind filter: a dropdown of the kinds present in the current outline, each toggleable.
        kindFilter.setText(tr("structure.filter.kinds"));
        kindFilter.getStyleClass().add("structure-kind-filter");
        kindFilter.setGraphic(Icons.find());

        HBox toolbar = new HBox(4, sortCombo, kindFilter);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getStyleClass().add("structure-toolbar");

        tree.setShowRoot(false);
        tree.getStyleClass().add("structure-tree");
        tree.setCellFactory(t -> new StructureCell());
        VBox.setVgrow(tree, Priority.ALWAYS);

        // Navigate as soon as the selection changes (keyboard, search, or single click), but only
        // for user-driven changes — not while the tree is rebuilt — and without stealing focus.
        tree.getSelectionModel().selectedItemProperty().addListener((obs, old, now) -> {
            if (!suppressNavigation) {
                navigateTo(now, false);
            }
        });
        // A double-click activates: navigate and move focus into the editor.
        tree.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                navigateSelected();
            }
        });

        setSpacing(4);
        getChildren().addAll(toolbar, filterBar, tree);
        addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
    }

    /** A flat structure entry for the keyboard "Jump to Structure" picker. */
    public record Outline(String label, String kind, int line) {}

    /** The current structure as a flat, document-order list (for the Jump-to-Structure picker). */
    public List<Outline> outline() {
        List<Outline> out = new ArrayList<>();
        collectOutline(roots, out);
        return out;
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
    public void attach(EditorBuffer buffer) {
        if (this.buffer != null) {
            this.buffer.getFoldManager().setOnRegionsChanged(null);
            this.buffer.setOnSymbolsChanged(null);
        }
        this.buffer = buffer;
        this.lspSymbols = null; // start with the heuristic; MainController pushes LSP symbols if available
        if (buffer == null) {
            rebuild();
            return;
        }
        // Node ranges/nesting come from fold regions; names/kinds come from TextMate symbols — rebuild
        // when either updates (regions on text/fold change, symbols when re-highlighting completes).
        buffer.getFoldManager().setOnRegionsChanged(this::rebuild);
        buffer.setOnSymbolsChanged(this::rebuild);
        // Force a recompute so the tree reflects the current text immediately; the callbacks above
        // then drive live updates as the document changes.
        buffer.getFoldManager().recompute();
    }

    /** Moves keyboard focus into the panel (the search field), for window-switching. */
    public void focusContent() {
        filterField.requestFocus();
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

    private VirtualFlow<?> virtualFlow() {
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

    /** Activates the current selection: navigates and moves focus into the editor. */
    private void navigateSelected() {
        navigateTo(tree.getSelectionModel().getSelectedItem(), true);
    }

    /** Moves the editor caret to {@code item}'s region, optionally focusing the editor. */
    private void navigateTo(TreeItem<StructureNode> item, boolean focusEditor) {
        if (item == null || item.getValue() == null || buffer == null) {
            return;
        }
        CodeArea area = buffer.getArea();
        int line = item.getValue().line();
        if (line < 0 || line >= area.getParagraphs().size()) {
            return;
        }
        area.moveTo(line, 0);
        // Anchor the target line at the top of the viewport (deferred until layout is ready).
        Platform.runLater(() -> {
            try {
                area.showParagraphAtTop(line);
            } catch (RuntimeException ignored) {
                // Viewport not ready; ignore.
            }
        });
        if (focusEditor) {
            area.requestFocus();
        }
    }

    // --- Tree construction ---

    private void rebuild() {
        if (!visible()) {
            pendingRebuild = true; // defer the O(n) split + TreeView rebuild until the window is shown again
            return;
        }
        pendingRebuild = false;
        if (buffer == null) {
            roots = new ArrayList<>(); // mutable: sortNodes sorts roots in place (a diff/Welcome tab has no buffer)
        } else if (lspSymbols != null) {
            roots = fromLsp(lspSymbols); // LSP-served file: precise hierarchy + kinds
        } else {
            roots = buildNodes(); // fallback: fold-region nesting + TextMate-scope names
        }
        attachDocs(roots);
        sortNodes(roots);
        rebuildKindFilter();
        // A rebuild rebuilds the tree (applyFilter selects row 0); that must not move the editor caret,
        // and it should keep the user on the symbol they had selected rather than snapping to the top.
        StructureNode prevSelection = selectedNodeValue();
        suppressNavigation = true;
        try {
            applyFilter(filterField.getText());
            reselect(prevSelection);
        } finally {
            suppressNavigation = false;
        }
    }

    /** The {@link StructureNode} currently selected in the tree (a real symbol with a line), or {@code null}. */
    private StructureNode selectedNodeValue() {
        TreeItem<StructureNode> sel = tree.getSelectionModel().getSelectedItem();
        StructureNode v = sel == null ? null : sel.getValue();
        return v != null && v.line() >= 0 ? v : null;
    }

    /** Re-selects the tree row matching {@code target} (by line + label) after a rebuild; no-op if absent. */
    private void reselect(StructureNode target) {
        if (target == null || tree.getRoot() == null) {
            return;
        }
        TreeItem<StructureNode> match = findItem(tree.getRoot(), target);
        if (match != null) {
            tree.getSelectionModel().select(match);
        }
    }

    private static TreeItem<StructureNode> findItem(TreeItem<StructureNode> node, StructureNode target) {
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
    public void setLspSymbols(EditorBuffer forBuffer, List<SymbolNode> symbols) {
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
    }

    /** Converts the LSP symbol tree into structure nodes (region-less; navigation uses the symbol line). */
    private static List<StructureNode> fromLsp(List<SymbolNode> symbols) {
        List<StructureNode> out = new ArrayList<>();
        for (SymbolNode s : symbols) {
            if (s == null) {
                continue;
            }
            StructureNode node = new StructureNode(null, s.name(), s.kind(), Math.max(0, s.line()), s.detail());
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

    /** Re-orders {@code nodes} (and their descendants) per the current {@link SortMode}. */
    private void sortNodes(List<StructureNode> nodes) {
        Comparator<StructureNode> cmp =
                switch (sortMode) {
                    case NAME ->
                        Comparator.<StructureNode, String>comparing(
                                n -> n.label().toLowerCase(Locale.ROOT));
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

    private List<StructureNode> buildNodes() {
        CodeArea area = buffer.getArea();
        if (buffer.isMarkdown()) {
            return markdownHeadingNodes(area.getText());
        }
        int paras = area.getParagraphs().size();
        List<Region> regions =
                new ArrayList<>(new LinkedHashSet<>(buffer.getFoldManager().regions()));
        // Sort so each region precedes the regions it contains: by start asc, then by end desc.
        regions.sort(Comparator.comparingInt(Region::startLine)
                .thenComparing(Comparator.comparingInt(Region::endLine).reversed()));

        // Name/kind come from the TextMate symbols on (or, for Allman braces, just above) each region's
        // header. Regions with no symbol are control-flow/anonymous blocks and are dropped (declutter).
        Map<Integer, Symbol> symbolByLine = new HashMap<>();
        for (Symbol s : buffer.symbols()) {
            symbolByLine.putIfAbsent(s.line(), s);
        }
        boolean haveSymbols = !symbolByLine.isEmpty();
        boolean brace = isBraceLanguage(buffer.getLanguage());

        List<StructureNode> rootNodes = new ArrayList<>();
        Deque<StructureNode> stack = new ArrayDeque<>();
        for (Region r : regions) {
            if (r.startLine() < 0 || r.startLine() >= paras) {
                continue;
            }
            StructureNode node = nodeFor(area, r, symbolByLine, haveSymbols, brace);
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
        return rootNodes;
    }

    /**
     * Builds a node for a region: its label/kind from the TextMate symbol on the header line (or, for
     * brace languages, the signature on the nearest non-blank lines above the {@code &#123;}). Returns
     * {@code null} when no symbol is found and a grammar is active (declutter). Without symbols (no
     * grammar) it falls back to the header-line text, keeping every region.
     */
    private StructureNode nodeFor(
            CodeArea area, Region r, Map<Integer, Symbol> symbolByLine, boolean haveSymbols, boolean brace) {
        int start = r.startLine();
        if (haveSymbols) {
            Symbol symbol = symbolByLine.get(start);
            int line = start;
            if (symbol == null && brace) {
                int probe = start - 1;
                int examined = 0;
                while (probe >= 0 && examined < 3) {
                    String t = area.getParagraph(probe).getText().trim();
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
            }
            if (symbol == null) {
                return null;
            }
            return new StructureNode(r, symbol.name(), symbol.kind(), line);
        }
        // No grammar/symbols: keep the old header-text label and show every region.
        String text = area.getParagraph(start).getText().trim();
        return new StructureNode(r, text.isEmpty() ? "line " + (start + 1) : text, null, start);
    }

    /** Builds the heading outline for a Markdown buffer (ATX/Setext), nested by heading level. */
    private static List<StructureNode> markdownHeadingNodes(String text) {
        List<StructureNode> roots = new ArrayList<>();
        Deque<StructureNode> stack = new ArrayDeque<>();
        Deque<Integer> levels = new ArrayDeque<>();
        for (com.editora.markdown.MarkdownOutline.Heading h : com.editora.markdown.MarkdownOutline.headings(text)) {
            StructureNode node =
                    new StructureNode(null, h.title().isBlank() ? "(untitled)" : h.title(), "heading", h.line());
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

    /** Languages whose folds are brace-delimited (Allman braces put the signature above the start line). */
    private static boolean isBraceLanguage(String language) {
        return switch (language == null ? "" : language) {
            case "java", "json", "c", "cpp", "rust", "go", "kotlin", "groovy", "csharp", "css" -> true;
            default -> false;
        };
    }

    private static boolean contains(Region outer, Region inner) {
        return outer.startLine() <= inner.startLine() && outer.endLine() >= inner.endLine();
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
    private TreeItem<StructureNode> toItem(StructureNode node, String q) {
        List<TreeItem<StructureNode>> childItems = new ArrayList<>();
        for (StructureNode child : node.children()) {
            TreeItem<StructureNode> ci = toItem(child, q);
            if (ci != null) {
                childItems.add(ci);
            }
        }
        boolean kindVisible = node.kind() == null || !hiddenKinds.contains(node.kind());
        boolean self = kindVisible
                && (q.isEmpty() || CommandPalette.isSubsequence(q, node.label().toLowerCase(Locale.ROOT)));
        if (!self && childItems.isEmpty()) {
            return null;
        }
        TreeItem<StructureNode> item = new TreeItem<>(node);
        item.getChildren().setAll(childItems);
        item.setExpanded(true); // expand by default so the whole outline is visible at a glance
        return item;
    }

    private static final class StructureCell extends TreeCell<StructureNode> {
        @Override
        protected void updateItem(StructureNode item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                setTooltip(null);
                return;
            }
            // Show the cleaned leading doc comment (if any) as a hover tooltip.
            if (item.doc() != null && !item.doc().isEmpty()) {
                Tooltip tip = new Tooltip(item.doc());
                tip.setWrapText(true);
                tip.setMaxWidth(480);
                tip.setShowDelay(javafx.util.Duration.millis(400));
                setTooltip(tip);
            } else {
                setTooltip(null);
            }
            boolean real = item.kind() != null && item.line() >= 0;
            String sig = signature(item);
            if (sig.isEmpty()) {
                // No signature to append: plain text + (for real rows) a kind icon.
                setText(item.label());
                setGraphic(real ? StructureIcons.forKind(item.kind()) : null);
                return;
            }
            // Method/function: render "name" then the signature "(params) : ret" in a muted colour, abutting
            // the name (no inter-node gap), with the kind icon to the left.
            setText(null);
            javafx.scene.text.Text name = new javafx.scene.text.Text(item.label());
            name.getStyleClass().add("structure-name");
            javafx.scene.text.Text detail = new javafx.scene.text.Text(sig);
            detail.getStyleClass().add("structure-detail");
            HBox box = new HBox(name, detail);
            box.setAlignment(Pos.CENTER_LEFT);
            if (real) {
                Node icon = StructureIcons.forKind(item.kind());
                HBox.setMargin(icon, new javafx.geometry.Insets(0, 4, 0, 0));
                box.getChildren().add(0, icon);
            }
            setGraphic(box);
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
            return d.startsWith("(") ? d : "(" + d + ")";
        }

        private static boolean isCallable(String kind) {
            return "method".equals(kind) || "function".equals(kind) || "constructor".equals(kind);
        }
    }

    /** A node in the structure tree: a foldable region, its symbol label/kind, the line to navigate to, an
     *  optional detail (the LSP signature, e.g. {@code (String x) : void}), an optional doc comment (the
     *  cleaned leading comment above the declaration, shown as a tooltip), and children. */
    private static final class StructureNode {
        private final Region region;
        private final String label;
        private final String kind;
        private final int line;
        private final String detail;
        private String doc = ""; // filled by attachDocs() from the buffer text; "" when none
        private final List<StructureNode> children = new ArrayList<>();

        StructureNode(Region region, String label, String kind, int line) {
            this(region, label, kind, line, "");
        }

        StructureNode(Region region, String label, String kind, int line, String detail) {
            this.region = region;
            this.label = label;
            this.kind = kind;
            this.line = line;
            this.detail = detail == null ? "" : detail;
        }

        Region region() {
            return region;
        }

        String label() {
            return label;
        }

        String kind() {
            return kind;
        }

        int line() {
            return line;
        }

        String detail() {
            return detail;
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
