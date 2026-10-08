package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Stage;

import com.editora.lsp.LspManager;
import com.editora.lsp.LspManager.WorkspaceSymbolMatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four views the LSP navigation commands end in — the References and Hierarchy tool windows, the
 * workspace-symbol picker and the peek popup — each shown in a real scene and driven by the keys and clicks
 * a user gives it.
 *
 * <p>They share one job: turn a list the server answered with into rows, and turn the row the user picks
 * back into a file and a position. What is asserted is therefore what each row says and where each pick
 * goes, including the picks that must go nowhere (a file header, a type inside a library, a row whose
 * children have not arrived).
 */
@Tag("fx")
class LspPanelsFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private Stage stage;

    @AfterEach
    void closeStage() throws Exception {
        if (stage != null) {
            FxTestSupport.runOnFx(stage::close);
        }
    }

    /** Puts {@code content} in a shown window and lays it out, so list and tree cells exist. */
    private void show(Region content) throws Exception {
        stage = FxTestSupport.callOnFx(() -> {
            Stage s = new Stage();
            s.setScene(new Scene(content, 700, 500));
            s.show();
            return s;
        });
        layout(content);
    }

    private static void layout(Region content) throws Exception {
        FxTestSupport.runOnFx(() -> {
            content.applyCss();
            content.layout();
        });
    }

    private static KeyEvent key(KeyCode code, boolean ctrl) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, ctrl, false, false);
    }

    private static MouseEvent click(MouseButton button, int count) {
        return new MouseEvent(
                MouseEvent.MOUSE_CLICKED,
                0,
                0,
                0,
                0,
                button,
                count,
                false,
                false,
                false,
                false,
                true,
                false,
                false,
                true,
                false,
                false,
                null);
    }

    /** The text of every non-empty cell under {@code root}, top to bottom. */
    private static List<String> cellTexts(Node root, String selector) {
        return filledCells(root, selector).stream()
                .map(javafx.scene.control.IndexedCell::getText)
                .toList();
    }

    /** The non-empty cells under {@code root} in row order (a lookup returns them in no order at all). */
    private static List<javafx.scene.control.IndexedCell<?>> filledCells(Node root, String selector) {
        List<javafx.scene.control.IndexedCell<?>> out = new ArrayList<>();
        for (Node node : root.lookupAll(selector)) {
            javafx.scene.control.IndexedCell<?> cell = (javafx.scene.control.IndexedCell<?>) node;
            if (!cell.isEmpty()) {
                out.add(cell);
            }
        }
        out.sort(java.util.Comparator.comparingInt(javafx.scene.control.IndexedCell::getIndex));
        return out;
    }

    private record Opened(Path file, int line, int col) {}

    // --- References ----------------------------------------------------------------------------------

    private static final Path ALPHA = Path.of("/proj/src/Alpha.java");
    private static final Path BETA = Path.of("/proj/src/beta.java");

    private static List<ReferencesPanel.Reference> references() {
        return List.of(
                new ReferencesPanel.Reference(BETA, 9, 4, "  beta.call();  "),
                new ReferencesPanel.Reference(ALPHA, 20, 2, null),
                new ReferencesPanel.Reference(ALPHA, 3, 7, "int x = y;"),
                new ReferencesPanel.Reference(ALPHA, 3, 1, ""));
    }

    @Test
    void referencesAreGroupedByFileAndOrderedByPosition() throws Exception {
        List<Opened> opened = new ArrayList<>();
        ReferencesPanel panel = FxTestSupport.callOnFx(
                () -> new ReferencesPanel((file, line, col) -> opened.add(new Opened(file, line, col))));
        show(panel);
        FxTestSupport.runOnFx(() -> panel.setReferences(references()));
        layout(panel);

        Label summary = FxTestSupport.field(panel, "summary");
        assertEquals(tr("references.summary", 4, 2), FxTestSupport.callOnFx(summary::getText));
        assertEquals(
                List.of("Alpha.java  (3)", "4:2", "4:8  int x = y;", "21:3", "beta.java  (1)", "10:5  beta.call();"),
                FxTestSupport.callOnFx(() -> cellTexts(panel, ".tree-cell")),
                "files sort by name ignoring case; hits by line then column; positions are 1-based");
    }

    @Test
    void enterOnAReferenceOpensItAndOnAFileHeaderFoldsIt() throws Exception {
        List<Opened> opened = new ArrayList<>();
        ReferencesPanel panel = FxTestSupport.callOnFx(
                () -> new ReferencesPanel((file, line, col) -> opened.add(new Opened(file, line, col))));
        show(panel);
        TreeView<?> tree = FxTestSupport.field(panel, "tree");
        FxTestSupport.runOnFx(() -> {
            panel.setReferences(references());
            panel.focusFirstItem();
            assertEquals(0, tree.getSelectionModel().getSelectedIndex(), "the first row is selected");

            panel.fireEvent(key(KeyCode.ENTER, false)); // on the Alpha.java header
            assertTrue(opened.isEmpty(), "a file header is not a place to go");
            assertEquals(3, tree.getExpandedItemCount(), "Enter folds the file's three hits away");
            panel.fireEvent(key(KeyCode.M, true)); // C-m is Enter
            assertEquals(6, tree.getExpandedItemCount());

            panel.fireEvent(key(KeyCode.DOWN, false));
            panel.fireEvent(key(KeyCode.N, true));
            assertEquals(2, tree.getSelectionModel().getSelectedIndex());
            panel.fireEvent(key(KeyCode.UP, false));
            assertEquals(1, tree.getSelectionModel().getSelectedIndex());
            panel.fireEvent(key(KeyCode.P, true));
            panel.fireEvent(key(KeyCode.P, true));
            assertEquals(0, tree.getSelectionModel().getSelectedIndex(), "selection stops at the first row");
            for (int i = 0; i < 9; i++) {
                panel.fireEvent(key(KeyCode.N, true));
            }
            assertEquals(5, tree.getSelectionModel().getSelectedIndex(), "…and at the last");

            panel.fireEvent(key(KeyCode.ENTER, false));
            assertEquals(List.of(new Opened(BETA, 9, 4)), opened);

            KeyEvent unrelated = key(KeyCode.A, true);
            panel.fireEvent(unrelated);
            KeyEvent plain = key(KeyCode.A, false);
            panel.fireEvent(plain);
            assertEquals(1, opened.size(), "other keys are not the panel's");

            tree.getSelectionModel().select(1);
            tree.fireEvent(click(MouseButton.PRIMARY, 1));
            assertEquals(1, opened.size(), "a single click only selects");
            tree.fireEvent(click(MouseButton.SECONDARY, 2));
            assertEquals(1, opened.size());
            tree.fireEvent(click(MouseButton.PRIMARY, 2));
            assertEquals(new Opened(ALPHA, 3, 1), opened.get(1));
        });
    }

    @Test
    void anEmptyReferenceListSaysSoAndIgnoresKeys() throws Exception {
        List<Opened> opened = new ArrayList<>();
        ReferencesPanel panel = FxTestSupport.callOnFx(
                () -> new ReferencesPanel((file, line, col) -> opened.add(new Opened(file, line, col))));
        show(panel);
        Label summary = FxTestSupport.field(panel, "summary");
        TreeView<?> tree = FxTestSupport.field(panel, "tree");
        FxTestSupport.runOnFx(() -> {
            panel.setReferences(references());
            panel.setReferences(List.of());
            assertEquals(tr("references.none"), summary.getText());
            panel.focusFirstItem();
            panel.fireEvent(key(KeyCode.DOWN, false));
            panel.fireEvent(key(KeyCode.ENTER, false));
            assertTrue(tree.getSelectionModel().isEmpty());
            assertTrue(opened.isEmpty());
        });
        layout(panel);
        assertEquals(List.of(), FxTestSupport.callOnFx(() -> cellTexts(panel, ".tree-cell")), "no stale rows remain");
    }

    // --- Hierarchy -----------------------------------------------------------------------------------

    private static LspManager.HierarchyNode node(String name, String detail, Path file, int line) {
        return new LspManager.HierarchyNode(name, detail, "method", file, line, 4, name);
    }

    /** Records each expansion and answers with whatever the test queued for that node. */
    private static final class RecordingLoader implements HierarchyPanel.Loader {
        final List<String> asked = new ArrayList<>();
        final List<Opened> opened = new ArrayList<>();
        final java.util.Map<String, List<LspManager.HierarchyNode>> children = new java.util.HashMap<>();
        /** When set, replies are kept here instead of delivered, so a test can deliver them late. */
        List<Runnable> held;

        @Override
        public void children(
                LspManager.HierarchyNode node, boolean primary, Consumer<List<LspManager.HierarchyNode>> cb) {
            asked.add(node.name() + (primary ? ":primary" : ":secondary"));
            List<LspManager.HierarchyNode> answer = children.getOrDefault(node.name(), List.of());
            if (held != null) {
                held.add(() -> cb.accept(answer));
            } else {
                cb.accept(answer);
            }
        }

        @Override
        public void open(Path file, int line, int col) {
            opened.add(new Opened(file, line, col));
        }
    }

    @Test
    void theHierarchyAnchorIsExpandedAtOnceAndDeeperNodesOnDemand() throws Exception {
        RecordingLoader loader = new RecordingLoader();
        loader.children.put("go", List.of(node("caller", "demo.B", BETA, 11), node("library", "", null, 0)));
        loader.children.put("caller", List.of(node("main", "demo.Main", ALPHA, 1)));
        HierarchyPanel panel = FxTestSupport.callOnFx(() -> new HierarchyPanel(loader));
        show(panel);
        TreeView<LspManager.HierarchyNode> tree = FxTestSupport.field(panel, "tree");
        Label summary = FxTestSupport.field(panel, "summary");

        FxTestSupport.runOnFx(() -> panel.setRoots(HierarchyPanel.Mode.CALLS, List.of(node("go", "demo.A", ALPHA, 2))));
        layout(panel);

        assertEquals(List.of("go:primary"), loader.asked, "only the anchor's children are fetched up front");
        assertEquals(tr("hierarchy.summary", 1), FxTestSupport.callOnFx(summary::getText));
        assertEquals(
                List.of("go  —  demo.A  ·  Alpha.java:3", "caller  —  demo.B  ·  beta.java:12", "library"),
                FxTestSupport.callOnFx(() -> cellTexts(panel, ".tree-cell")),
                "a node shows its container and 1-based place; one inside a library has no place");

        FxTestSupport.runOnFx(() -> {
            TreeItem<LspManager.HierarchyNode> caller =
                    tree.getRoot().getChildren().get(0).getChildren().get(0);
            caller.setExpanded(true);
            caller.setExpanded(false);
            caller.setExpanded(true);
        });
        assertEquals(List.of("go:primary", "caller:primary"), loader.asked, "a loaded node is not asked again");
    }

    @Test
    void enterOpensANodeThatHasAFile() throws Exception {
        RecordingLoader loader = new RecordingLoader();
        loader.children.put("go", List.of(node("library", "", null, 0)));
        HierarchyPanel panel = FxTestSupport.callOnFx(() -> new HierarchyPanel(loader));
        show(panel);
        TreeView<LspManager.HierarchyNode> tree = FxTestSupport.field(panel, "tree");
        FxTestSupport.runOnFx(() -> {
            panel.focusFirstItem(); // nothing to select yet, and nothing to throw
            tree.fireEvent(key(KeyCode.ENTER, false));
            assertTrue(loader.opened.isEmpty());

            panel.setRoots(HierarchyPanel.Mode.CALLS, List.of(node("go", "demo.A", ALPHA, 2)));
            panel.focusFirstItem();
            tree.fireEvent(key(KeyCode.ENTER, false));
            assertEquals(List.of(new Opened(ALPHA, 2, 4)), loader.opened);

            tree.getSelectionModel().select(1); // the library node: no file
            tree.fireEvent(key(KeyCode.ENTER, false));
            tree.fireEvent(click(MouseButton.PRIMARY, 2));
            assertEquals(1, loader.opened.size(), "a type inside a library cannot be opened as a file");

            tree.getSelectionModel().select(0);
            tree.fireEvent(click(MouseButton.PRIMARY, 1));
            assertEquals(1, loader.opened.size(), "a single click only selects");
            tree.fireEvent(click(MouseButton.PRIMARY, 2));
            assertEquals(2, loader.opened.size());
            tree.fireEvent(key(KeyCode.A, false));
            assertEquals(2, loader.opened.size());
        });
    }

    /** The placeholder shown while a node's children are in flight is a row like any other to the tree,
     *  but it is not a place: Enter on it must do nothing. */
    @Test
    void aPendingRowIsNotNavigable() throws Exception {
        RecordingLoader loader = new RecordingLoader();
        loader.held = new ArrayList<>();
        HierarchyPanel panel = FxTestSupport.callOnFx(() -> new HierarchyPanel(loader));
        show(panel);
        TreeView<LspManager.HierarchyNode> tree = FxTestSupport.field(panel, "tree");
        FxTestSupport.runOnFx(() -> {
            panel.setRoots(HierarchyPanel.Mode.CALLS, List.of(node("go", "demo.A", ALPHA, 2)));
            tree.getSelectionModel().select(1);
            assertEquals(
                    "…", tree.getSelectionModel().getSelectedItem().getValue().name());
            tree.fireEvent(key(KeyCode.ENTER, false));
            assertTrue(loader.opened.isEmpty());
        });
    }

    @Test
    void flippingTheDirectionReRootsAndDropsTheStaleAnswer() throws Exception {
        RecordingLoader loader = new RecordingLoader();
        loader.held = new ArrayList<>();
        loader.children.put("A", List.of(node("Base", "", ALPHA, 0)));
        HierarchyPanel panel = FxTestSupport.callOnFx(() -> new HierarchyPanel(loader));
        show(panel);
        TreeView<LspManager.HierarchyNode> tree = FxTestSupport.field(panel, "tree");
        ToggleButton primary = FxTestSupport.field(panel, "primaryToggle");
        ToggleButton secondary = FxTestSupport.field(panel, "secondaryToggle");

        FxTestSupport.runOnFx(() -> {
            panel.setRoots(HierarchyPanel.Mode.TYPES, List.of(node("A", "", ALPHA, 0)));
            assertEquals(tr("hierarchy.supertypes"), primary.getText());
            assertEquals(tr("hierarchy.subtypes"), secondary.getText());
            assertEquals(List.of("A:primary"), loader.asked);

            secondary.setSelected(true); // now asking for subtypes
            assertEquals(List.of("A:primary", "A:secondary"), loader.asked);
            loader.held.get(0).run(); // the supertypes answer arrives late
            TreeItem<LspManager.HierarchyNode> anchor =
                    tree.getRoot().getChildren().get(0);
            assertEquals("…", anchor.getChildren().get(0).getValue().name(), "a stale answer must not fill the tree");
            loader.held.get(1).run();
            assertEquals("Base", anchor.getChildren().get(0).getValue().name());

            secondary.setSelected(false); // un-pressing the only pressed toggle
            assertTrue(secondary.isSelected(), "one direction is always on");

            panel.setRoots(HierarchyPanel.Mode.CALLS, null);
            assertEquals(tr("hierarchy.callers"), primary.getText());
            assertTrue(primary.isSelected(), "a fresh hierarchy starts in the primary direction");
            assertTrue(tree.getRoot().getChildren().isEmpty());
        });
    }

    // --- Go to Symbol in Workspace -------------------------------------------------------------------

    private static final class SymbolOps implements WorkspaceSymbolPopup.Ops {
        final List<String> queries = new ArrayList<>();
        final List<Consumer<List<WorkspaceSymbolMatch>>> replies = new ArrayList<>();
        final List<Opened> opened = new ArrayList<>();

        @Override
        public void query(String text, Consumer<List<WorkspaceSymbolMatch>> cb) {
            queries.add(text);
            replies.add(cb);
        }

        @Override
        public void open(Path file, int line, int character) {
            opened.add(new Opened(file, line, character));
        }
    }

    private record SymbolPopupUnderTest(
            WorkspaceSymbolPopup popup, OverlayHost overlay, SymbolOps ops, StackPane root) {
        TextField query() {
            return FxTestSupport.field(popup, "query");
        }

        ListView<WorkspaceSymbolMatch> list() {
            return FxTestSupport.field(popup, "list");
        }

        Label status() {
            return FxTestSupport.field(popup, "status");
        }

        /** Stands in for the typing pause: the debounce is a timer, and a test does not wait on a clock. */
        void typingPaused() {
            javafx.animation.PauseTransition debounce = FxTestSupport.field(popup, "debounce");
            debounce.stop();
            debounce.getOnFinished().handle(null);
        }
    }

    private SymbolPopupUnderTest symbolPopup() throws Exception {
        SymbolOps ops = new SymbolOps();
        StackPane root = FxTestSupport.callOnFx(StackPane::new);
        show(root);
        return FxTestSupport.callOnFx(() -> {
            OverlayHost overlay = new OverlayHost();
            overlay.install(root);
            return new SymbolPopupUnderTest(new WorkspaceSymbolPopup(overlay, ops), overlay, ops, root);
        });
    }

    private static WorkspaceSymbolMatch match(String name, String container, Path file, int line) {
        return new WorkspaceSymbolMatch(name, container, "class", file, line, 6);
    }

    @Test
    void theSeedIsQueriedAndTheBestNameMatchIsSelected() throws Exception {
        SymbolPopupUnderTest t = symbolPopup();
        FxTestSupport.runOnFx(() -> t.popup().show("Parser"));
        FxTestSupport.drainFx(); // the query runs once the card is on screen

        assertEquals(List.of("Parser"), t.ops().queries);
        assertEquals(
                tr("lsp.workspaceSymbols.searching"),
                FxTestSupport.callOnFx(() -> t.status().getText()));
        FxTestSupport.runOnFx(() -> t.ops()
                .replies
                .get(0)
                .accept(List.of(
                        match("XmlParserFactory", "demo.xml", ALPHA, 4),
                        match("Parser", null, BETA, 9),
                        match("Unrelated", " ", ALPHA, 30))));
        layout(t.root());

        FxTestSupport.runOnFx(() -> {
            assertEquals(tr("lsp.workspaceSymbols.count", 3), t.status().getText());
            assertEquals(
                    List.of("Parser", "XmlParserFactory", "Unrelated"),
                    t.list().getItems().stream().map(WorkspaceSymbolMatch::name).toList(),
                    "an exact name leads; what the server matched some other way keeps its place at the end");
            assertEquals(0, t.list().getSelectionModel().getSelectedIndex());
            List<String> shown = new ArrayList<>();
            for (javafx.scene.control.IndexedCell<?> c : filledCells(t.list(), ".list-cell")) {
                javafx.scene.layout.HBox row = (javafx.scene.layout.HBox) c.getGraphic();
                shown.add(((Label) row.getChildren().get(0)).getText() + "|"
                        + ((Label) row.getChildren().get(1)).getText() + "|"
                        + ((Label) row.getChildren().get(3)).getText());
            }
            assertEquals(
                    List.of("Parser||beta.java", "XmlParserFactory|demo.xml|Alpha.java", "Unrelated||Alpha.java"),
                    shown);

            t.query().fireEvent(key(KeyCode.DOWN, false));
            assertEquals(1, t.list().getSelectionModel().getSelectedIndex());
            t.query().fireEvent(key(KeyCode.ENTER, false));
            assertEquals(List.of(new Opened(ALPHA, 4, 6)), t.ops().opened);
            assertFalse(t.overlay().isShowing(), "choosing a symbol closes the picker");
        });
    }

    @Test
    void aNewerQuerySupersedesTheAnswerToAnOlderOne() throws Exception {
        SymbolPopupUnderTest t = symbolPopup();
        FxTestSupport.runOnFx(() -> t.popup().show(null));
        FxTestSupport.drainFx();
        assertTrue(t.ops().queries.isEmpty(), "an empty query asks the server nothing");

        FxTestSupport.runOnFx(() -> {
            t.query().setText("Pa");
            t.typingPaused();
            t.query().setText("  Parser  ");
            t.typingPaused();
            assertEquals(List.of("Pa", "Parser"), t.ops().queries, "the query is sent trimmed");

            t.ops().replies.get(0).accept(List.of(match("Pad", "", ALPHA, 1)));
            assertTrue(t.list().getItems().isEmpty(), "the answer to 'Pa' must not appear under 'Parser'");
            t.ops().replies.get(1).accept(List.of());
            assertEquals(tr("lsp.workspaceSymbols.none"), t.status().getText());

            t.query().fireEvent(key(KeyCode.ENTER, false));
            assertTrue(t.ops().opened.isEmpty(), "Enter with nothing selected goes nowhere");
            assertTrue(t.overlay().isShowing());

            t.query().setText("");
            t.typingPaused();
            assertEquals("", t.status().getText(), "clearing the query clears the status with the rows");
            assertEquals(2, t.ops().queries.size());
        });
    }

    @Test
    void clickingASymbolOpensIt() throws Exception {
        SymbolPopupUnderTest t = symbolPopup();
        FxTestSupport.runOnFx(() -> t.popup().show("B"));
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> t.ops().replies.get(0).accept(List.of(match("B", "demo", BETA, 2))));
        layout(t.root());

        FxTestSupport.runOnFx(() -> {
            ListCell<?> filled = null;
            ListCell<?> empty = null;
            for (Node cell : t.list().lookupAll(".list-cell")) {
                ListCell<?> c = (ListCell<?>) cell;
                if (c.isEmpty()) {
                    empty = c;
                } else {
                    filled = c;
                }
            }
            assertNotNull(filled, "the match has a row");
            if (empty != null) {
                empty.fireEvent(click(MouseButton.PRIMARY, 1));
            }
            filled.fireEvent(click(MouseButton.SECONDARY, 1));
            assertTrue(t.ops().opened.isEmpty(), "only a primary click on a real row opens");
            filled.fireEvent(click(MouseButton.PRIMARY, 1));
            assertEquals(List.of(new Opened(BETA, 2, 6)), t.ops().opened);
        });
    }

    @Test
    void rankingOnlyReordersAndLeavesTrivialListsAlone() {
        List<WorkspaceSymbolMatch> one = List.of(match("Parser", "", ALPHA, 1));
        List<WorkspaceSymbolMatch> two = List.of(match("zzz", "", ALPHA, 1), match("Parser", "", ALPHA, 2));

        assertEquals(one, WorkspaceSymbolPopup.rankByName(one, "Parser"));
        assertEquals(two, WorkspaceSymbolPopup.rankByName(two, " "));
        assertEquals(two, WorkspaceSymbolPopup.rankByName(two, null));
        assertEquals(
                List.of("Parser", "zzz"),
                WorkspaceSymbolPopup.rankByName(two, "Parser").stream()
                        .map(WorkspaceSymbolMatch::name)
                        .toList());
    }

    // --- Peek Definition -----------------------------------------------------------------------------

    private static List<String> peekRows(PeekPopup popup) {
        VBox rows = FxTestSupport.field(popup, "rows");
        List<String> out = new ArrayList<>();
        for (Node n : rows.getChildren()) {
            StringBuilder line = new StringBuilder(n.getStyleClass().contains("peek-row-focus") ? ">" : " ");
            for (Node run : ((TextFlow) n).getChildren()) {
                line.append(((Text) run).getText());
            }
            out.add(line.toString());
        }
        return out;
    }

    /** Lets the popup's off-thread tokenizing finish and its result reach the FX thread. */
    private static void awaitHighlight() throws Exception {
        java.lang.reflect.Field f = PeekPopup.class.getDeclaredField("HIGHLIGHT");
        f.setAccessible(true);
        ((ExecutorService) f.get(null)).submit(() -> {}).get(60, TimeUnit.SECONDS);
        FxTestSupport.drainFx();
    }

    @Test
    void peekShowsTheNumberedWindowWithTheDefinitionMarked() throws Exception {
        StackPane root = FxTestSupport.callOnFx(StackPane::new);
        show(root);
        List<String> jumps = new ArrayList<>();
        OverlayHost overlay = FxTestSupport.callOnFx(() -> {
            OverlayHost o = new OverlayHost();
            o.install(root);
            return o;
        });
        PeekPopup popup = FxTestSupport.callOnFx(() -> new PeekPopup(overlay));
        String text = "package demo;\n\nclass A {\n    void go() {}\n}\n";
        PeekPopup.Snippet snippet = PeekPopup.build("Definition — A.java:4", text, 3, "java");

        FxTestSupport.runOnFx(() -> popup.show(snippet, "Monospaced", 13, () -> jumps.add("jumped")));
        List<String> plain = FxTestSupport.callOnFx(() -> peekRows(popup));
        assertEquals(
                List.of("    2  ", "    3  class A {", ">   4      void go() {}", "    5  }", "    6  "),
                plain,
                "readable at once, with real line numbers and the definition's row marked");
        Label title = FxTestSupport.field(popup, "title");
        assertEquals("Definition — A.java:4", FxTestSupport.callOnFx(title::getText));

        awaitHighlight();
        assertEquals(plain, FxTestSupport.callOnFx(() -> peekRows(popup)), "highlighting changes styles, never text");
        boolean styled = FxTestSupport.callOnFx(() -> {
            VBox rows = FxTestSupport.field(popup, "rows");
            TextFlow classLine = (TextFlow) rows.getChildren().get(1);
            return classLine.getChildren().size() > 2; // number + more than one styled run
        });
        assertTrue(styled, "the Java line was split into token runs");

        FxTestSupport.runOnFx(() -> {
            VBox card = FxTestSupport.field(popup, "card");
            card.fireEvent(key(KeyCode.A, false));
            assertTrue(jumps.isEmpty());
            assertTrue(overlay.isShowing());
            card.fireEvent(key(KeyCode.ENTER, false));
            assertEquals(List.of("jumped"), jumps, "Enter commits to the real navigation");
            assertFalse(overlay.isShowing());
        });
    }

    @Test
    void aPeekWithNoGrammarStaysPlainAndDismissingItForgetsTheJump() throws Exception {
        StackPane root = FxTestSupport.callOnFx(StackPane::new);
        show(root);
        List<String> jumps = new ArrayList<>();
        OverlayHost overlay = FxTestSupport.callOnFx(() -> {
            OverlayHost o = new OverlayHost();
            o.install(root);
            return o;
        });
        PeekPopup popup = FxTestSupport.callOnFx(() -> new PeekPopup(overlay));

        for (String language : new String[] {null, " ", "no-such-language"}) {
            PeekPopup.Snippet snippet = PeekPopup.build("t", "alpha\nbeta\n", 0, language);
            FxTestSupport.runOnFx(() -> popup.show(snippet, "Monospaced", 12, () -> jumps.add("jumped")));
            awaitHighlight();
            assertEquals(
                    List.of(">   1  alpha", "    2  beta", "    3  "), FxTestSupport.callOnFx(() -> peekRows(popup)));
        }
        PeekPopup.Snippet blank = PeekPopup.build("t", "\n\n", 0, "java");
        FxTestSupport.runOnFx(() -> popup.show(blank, "Monospaced", 12, () -> jumps.add("jumped")));
        awaitHighlight();
        assertEquals(3, FxTestSupport.callOnFx(() -> peekRows(popup)).size());

        FxTestSupport.runOnFx(() -> {
            overlay.hide(); // Escape
            VBox card = FxTestSupport.field(popup, "card");
            card.fireEvent(key(KeyCode.ENTER, false));
            assertTrue(jumps.isEmpty(), "a dismissed peek must not jump on a later Enter");
            assertNull(FxTestSupport.<Runnable>field(popup, "onJump"));
        });
    }
}
