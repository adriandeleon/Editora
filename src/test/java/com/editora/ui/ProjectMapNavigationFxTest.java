package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javafx.scene.control.Label;
import javafx.scene.layout.Region;

import com.editora.lsp.FakeLanguageServer;
import com.editora.lsp.LspManager;
import com.editora.lsp.LspTestHooks;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.TypeHierarchyItem;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The editor's code navigation on the Map: Go to Definition, Implementation and Type Hierarchy typed in a card open
 * the places as cards beside it — from the running language server, the file lent to it, no editor tab opened — the
 * keyboard follows to a definition's card, Back returns to the card it came from, a definition on the same card only
 * moves its caret, Quick Documentation pops up over the card, and without a server a definition is found among the
 * declarations of that name.
 */
@ExtendWith(DisposedPanels.class)
@Tag("fx")
class ProjectMapNavigationFxTest {

    private static final String SHAPE =
            String.join("\n", "package demo;", "", "public interface Shape {", "    double area();", "}", "");

    private static final String CIRCLE = String.join(
            "\n",
            "package demo;",
            "",
            "public class Circle implements Shape {",
            "    public double area() {",
            "        return 3.14;",
            "    }",
            "}",
            "");

    private static final String USE = String.join(
            "\n",
            "package demo;",
            "",
            "class Use {",
            "    double total(Shape shape) {",
            "        return shape.area() + twice();",
            "    }",
            "",
            "    double twice() {",
            "        return 2;",
            "    }",
            "}",
            "");

    @TempDir
    Path temp;

    private Path root;
    private LspManager manager;
    private final List<Path> editorOpened = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void startToolkit() throws Exception {
        FxTestSupport.bootToolkit();
        com.editora.i18n.Messages.init("en");
    }

    @BeforeEach
    void makeRoot() throws Exception {
        root = Files.createDirectories(temp.resolve("project")).toRealPath();
    }

    @AfterEach
    void stopServers() {
        if (manager != null) {
            manager.shutdownAll();
        }
    }

    @Test
    void goToDefinitionInACardOpensTheDefinitionsCardTheKeyboardFollowsAndBackReturns() throws Exception {
        Path src = Files.createDirectories(root.resolve("src"));
        Path shape = Files.writeString(src.resolve("Shape.java"), SHAPE);
        Path circle = Files.writeString(src.resolve("Circle.java"), CIRCLE);
        Path use = Files.writeString(src.resolve("Use.java"), USE);
        ServerCapabilities capabilities = new ServerCapabilities();
        capabilities.setImplementationProvider(Either.forLeft(true));
        capabilities.setTypeHierarchyProvider(Either.forLeft(true));
        capabilities.setHoverProvider(true);
        FakeLanguageServer fake = startServer(capabilities);
        fake.definitionResponse = List.of(new Location(LspTestHooks.uri(shape), range(3, 11)));
        fake.implementationResponse = List.of(new Location(LspTestHooks.uri(circle), range(3, 18)));

        ProjectPanel panel = openPanel();
        ProjectMapView view = FxTestSupport.field(panel, "mapView");
        MapCards cards = FxTestSupport.field(view, "cards");
        FxTestSupport.runOnFx(() -> view.setLinkSource(new ProjectMapLinkSearch(manager, panel.projectIndex())));
        ProjectMapPreview source = openCard(cards, use);

        // F12 on "area" in total(): the keymap's Go to Definition, typed in the card.
        FxTestSupport.runOnFx(() -> {
            source.editor().moveTo(4, 22);
            assertTrue(source.runMarkerCommand("lsp.gotoDefinition"), "F12 in a card is the card's");
        });
        waitFor(() -> resultCards(cards, shape).size() == 1, "the definition's card");
        ProjectMapPreview definition =
                FxTestSupport.callOnFx(() -> resultCards(cards, shape).getFirst());
        waitFor(() -> "area".equals(definition.editor().getSelectedText()), "the name selected at the definition");
        FxTestSupport.runOnFx(() -> {
            assertEquals(1, fake.definitions.size(), "asked the server");
            assertEquals(4, fake.definitions.getFirst().getPosition().getLine());
            assertEquals(22, fake.definitions.getFirst().getPosition().getCharacter(), "at the card's caret");
            MapCardLinks.EdgeSet set = cards.links.setsForTest(source).get(MapCardLinks.Kind.DEFINITION);
            assertNotNull(set);
            assertEquals(
                    List.of("definition"),
                    set.labelsForTest().stream().map(Label::getText).toList());
            assertTrue(definition.range() != null, "the declaration's own card: " + definition.title());
            assertTrue(definition.isFocusWithin() || definition.editor().isFocused(), "the keyboard followed");
            assertEquals(List.of(source), view.history.cardsBack(), "Back goes to the card it came from");
            assertFalse(manager.isManaged(use), "no editor holds the file");
        });
        assertEquals(List.of(), editorOpened, "nothing opened in the editor");

        // Back (Alt+Left and the like) in the definition's card: to the card it came from, its caret where it was.
        FxTestSupport.runOnFx(() -> {
            assertTrue(definition.runMarkerCommand("nav.back"));
            assertEquals(4, source.editor().getCurrentParagraph());
            assertEquals(22, source.editor().getCaretColumn());
            assertTrue(source.runMarkerCommand("nav.back"), "taken even with nowhere to go");
        });

        // Go to Implementation from the interface's method: the implementing class, the edge from it.
        FxTestSupport.runOnFx(() -> {
            definition.editor().selectRange(0, 0);
            definition
                    .editor()
                    .moveTo(0, definition.editor().getParagraph(0).getText().indexOf("area") + 1);
            assertTrue(definition.runMarkerCommand("lsp.gotoImplementation"));
        });
        waitFor(() -> resultCards(cards, circle).size() == 1, "the implementation's card");
        FxTestSupport.runOnFx(() -> {
            MapCardLinks.EdgeSet set = cards.links.setsForTest(definition).get(MapCardLinks.Kind.IMPLEMENTATIONS);
            assertEquals(
                    List.of("implements"),
                    set.labelsForTest().stream().map(Label::getText).toList());
            assertEquals(1, fake.implementations.size());
        });
    }

    @Test
    void typeHierarchyInACardOpensItsSubtypesAndQuickDocumentationPopsUp() throws Exception {
        Path src = Files.createDirectories(root.resolve("src"));
        Path shape = Files.writeString(src.resolve("Shape.java"), SHAPE);
        Path circle = Files.writeString(src.resolve("Circle.java"), CIRCLE);
        ServerCapabilities capabilities = new ServerCapabilities();
        capabilities.setTypeHierarchyProvider(Either.forLeft(true));
        capabilities.setHoverProvider(true);
        FakeLanguageServer fake = startServer(capabilities);
        fake.typeHierarchyResponse = List.of(typeItem("Shape", shape, 2, 17));
        fake.subtypesResponse = List.of(typeItem("Circle", circle, 2, 13));
        fake.hoverResponse = new Hover(new MarkupContent("markdown", "**Shape** — a closed figure"));

        ProjectPanel panel = openPanel();
        ProjectMapView view = FxTestSupport.field(panel, "mapView");
        MapCards cards = FxTestSupport.field(view, "cards");
        FxTestSupport.runOnFx(() -> view.setLinkSource(new ProjectMapLinkSearch(manager, panel.projectIndex())));
        ProjectMapPreview source = openCard(cards, shape);

        // Type Hierarchy typed in a card: the subtypes as a tree beside it, not cards.
        int[] prepared = {0};
        FxTestSupport.runOnFx(() -> {
            source.editor().moveTo(2, 18);
            assertTrue(source.runMarkerCommand("lsp.typeHierarchy"));
        });
        waitFor(() -> treeRows(cards, source).stream().anyMatch(row -> row.startsWith("Circle")), "the subtype's row");
        FxTestSupport.runOnFx(() -> {
            MapHierarchyTree tree = cards.links.hierarchy(source);
            assertNotNull(tree);
            assertEquals(MapCardLinks.Kind.SUBTYPES, tree.kind(), "the subtypes first");
            assertEquals(Map.of(), cards.links.setsForTest(source), "no cards of its own");
            assertEquals(
                    tr("project.map.hierarchy.title.subtypes", "Shape"),
                    tree.tree().getAccessibleText());
            tree.tree()
                    .getSelectionModel()
                    .select(tree.tree().getRoot().getChildren().getFirst());
            assertTrue(tree.openSelected(), "Enter on a row: its declaration's card");
        });
        waitFor(() -> resultCards(cards, circle).size() == 1, "the subtype's card");
        waitFor(
                () -> "Circle"
                        .equals(resultCards(cards, circle).getFirst().editor().getSelectedText()),
                "the subtype's own name selected");
        FxTestSupport.runOnFx(() -> {
            assertTrue(view.history.cardsBack().contains(source), "Back returns to the card asked from");
            MapHierarchyTree tree = cards.links.hierarchy(source);
            prepared[0] = fake.typeHierarchyPrepares.size();
            tree.turn();
            assertEquals(MapCardLinks.Kind.SUPERTYPES, tree.kind(), "⇄: the other way");
        });
        waitFor(() -> fake.typeHierarchyPrepares.size() > prepared[0], "asked again, for the supertypes");
        waitFor(
                () -> treeRows(cards, source).size() == 1
                        && treeRows(cards, source)
                                .getFirst()
                                .endsWith(MapCardLinks.noneText(MapCardLinks.Kind.SUPERTYPES, "Shape")),
                "no supertypes, said in the tree");

        FxTestSupport.runOnFx(() -> {
            assertTrue(cards.codeCard(shape) == source, "open before the hover");
            assertTrue(source.runMarkerCommand("lsp.hover"));
        });
        waitFor(source::hoverShown, "Quick Documentation over the card");
        FxTestSupport.runOnFx(() -> {
            assertTrue(source.hoverShown(), "still up when Escape comes");
            source.editor()
                    .fireEvent(new javafx.scene.input.KeyEvent(
                            javafx.scene.input.KeyEvent.KEY_PRESSED,
                            "",
                            "",
                            javafx.scene.input.KeyCode.ESCAPE,
                            false,
                            false,
                            false,
                            false));
            assertFalse(source.hoverShown(), "Escape closes it first");
            assertTrue(cards.codeCard(shape) == source, "and leaves the card open");
        });
    }

    @Test
    void aDefinitionInALibraryOpensItsSourceAsACardWhoseOpenIsTheLibraryTab() throws Exception {
        Path src = Files.createDirectories(root.resolve("src"));
        Path use = Files.writeString(src.resolve("Use.java"), USE);
        FakeLanguageServer fake = startServer(new ServerCapabilities(), true);
        String uri = "jdt://contents/java.base/java.util/ArrayList.class?=demo/%5C/jrt-fs.jar%60java.base";
        String source = String.join(
                "\n",
                "package java.util;",
                "",
                "/**",
                " * Resizable array.",
                " */",
                "public class ArrayList<E> {",
                "    public int size() {",
                "        return 0;",
                "    }",
                "}",
                "");
        fake.definitionResponse = List.of(new Location(uri, range(2, 0))); // jdtls points at the Javadoc
        fake.rawHandler =
                raw -> raw.method().equals("java/classFileContents") ? new com.google.gson.JsonPrimitive(source) : null;

        ProjectPanel panel = openPanel();
        ProjectMapView view = FxTestSupport.field(panel, "mapView");
        MapCards cards = FxTestSupport.field(view, "cards");
        List<MapLibrarySources.Library> opened = new CopyOnWriteArrayList<>();
        List<Integer> openedAt = new CopyOnWriteArrayList<>();
        FxTestSupport.runOnFx(() -> {
            view.setLinkSource(new ProjectMapLinkSearch(manager, panel.projectIndex()));
            view.setOpenLibrary((library, line) -> {
                opened.add(library);
                openedAt.add(line);
            });
        });
        ProjectMapPreview from = openCard(cards, use);
        FxTestSupport.runOnFx(() -> {
            from.editor().moveTo(4, 22);
            assertTrue(from.runMarkerCommand("lsp.gotoDefinition"));
        });
        waitFor(() -> libraryCard(cards) != null, "the library class's card");
        ProjectMapPreview library = FxTestSupport.callOnFx(() -> libraryCard(cards));
        waitFor(() -> library.editor().getLength() > 0, "its source shown");
        FxTestSupport.runOnFx(() -> {
            Path file = library.path();
            assertEquals("ArrayList.java", file.getFileName().toString());
            assertFalse(file.startsWith(root), "outside the project: no folder of the map closes it");
            assertFalse(Files.isWritable(file), "a read-only copy");
            assertTrue(library.editor().getText().contains("public class ArrayList<E>"));
            Label status = FxTestSupport.field(library, "status");
            assertTrue(status.getText().contains(tr("project.map.preview.library", "java.base")), status.getText());
            assertTrue(view.history.cardsBack().contains(from), "Back returns to the card asked from");
            assertEquals(List.of(), editorOpened, "nothing opened in the editor");

            FxTestSupport.<javafx.scene.control.Button>field(library, "open").fire();
            assertEquals(1, opened.size(), "Open: the editor's library tab, not the copy");
            assertEquals(uri, opened.getFirst().uri());
            assertEquals(use, opened.getFirst().anchor(), "asked of the server that gave it");
            assertEquals(List.of(), editorOpened, "and never the copy");
            assertTrue(openedAt.getFirst() >= 0);
        });
    }

    /** The open card of a library class's source, or null. */
    private static @org.jspecify.annotations.Nullable ProjectMapPreview libraryCard(MapCards cards) {
        return cards.previews.values().stream()
                .filter(card -> MapLibrarySources.of(card.path()) != null)
                .findFirst()
                .orElse(null);
    }

    @Test
    void aGoToFromAPreviewCardOpensWhereItStoodAndBackOpensItAgain() throws Exception {
        Path src = Files.createDirectories(root.resolve("src"));
        Path a = Files.writeString(src.resolve("A.java"), caller("A", "B"));
        Path b = Files.writeString(src.resolve("B.java"), caller("B", "C"));
        Path c = Files.writeString(src.resolve("C.java"), "package demo;\n\nclass C {\n    void go() {\n    }\n}\n");
        FakeLanguageServer fake = startServer(new ServerCapabilities());
        // Go to Definition from A's call: B.go(); from B's: C.go().
        fake.definitionHandler = params -> {
            String uri = params.getTextDocument().getUri();
            Path to = uri.endsWith("A.java") ? b : uri.endsWith("B.java") ? c : null;
            return to == null ? List.of() : List.of(new Location(LspTestHooks.uri(to), range(3, 9)));
        };
        ProjectPanel panel = openPanel();
        ProjectMapView view = FxTestSupport.field(panel, "mapView");
        MapCards cards = FxTestSupport.field(view, "cards");
        FxTestSupport.runOnFx(() -> view.setLinkSource(new ProjectMapLinkSearch(manager, panel.projectIndex())));
        ProjectMapPreview first = openCard(cards, a);
        FxTestSupport.runOnFx(() -> {
            caretOn(first, "go();");
            assertTrue(first.runMarkerCommand("lsp.gotoDefinition"));
        });
        waitFor(() -> openUnder(cards, b) != null, "B's card");
        ProjectMapPreview second = FxTestSupport.callOnFx(() -> openUnder(cards, b));
        waitFor(() -> second.editor().getText().contains("C()"), "B's text");
        double[] stood = FxTestSupport.callOnFx(() -> MapCards.drawnBounds(second));
        FxTestSupport.runOnFx(() -> {
            assertFalse(cards.links.isPreview(first), "a card opened by the reader is no preview");
            assertTrue(cards.links.isPreview(second), "a Go to result is");
            assertTrue(second.getStyleClass().contains("project-map-preview-transient"), "its title in italics");
            caretOn(second, "go();");
            assertTrue(second.runMarkerCommand("lsp.gotoDefinition"));
        });
        waitFor(() -> openUnder(cards, c) != null, "C's card");
        ProjectMapPreview third = FxTestSupport.callOnFx(() -> openUnder(cards, c));
        FxTestSupport.runOnFx(() -> {
            assertFalse(cards.isOpen(second), "the preview gave way");
            double[] at = MapCards.drawnBounds(third);
            assertEquals(stood[0], at[0], 1, "where it stood");
            assertEquals(stood[1], at[1], 1);
            assertTrue(cards.links.isPreview(third));
            assertTrue(cards.links.isResult(third), "the answer of the card the preview was found from");
            assertTrue(cards.isOpen(first));
            assertEquals(List.of(), editorOpened);

            // Back from C: B again, opened where the history left it.
            view.moveHistory(-1);
        });
        waitFor(() -> openUnder(cards, b) != null, "B opened again by Back");
        ProjectMapPreview again = FxTestSupport.callOnFx(() -> openUnder(cards, b));
        waitFor(() -> again.editor().getText().contains("C()"), "B's text again");
        FxTestSupport.runOnFx(() -> {
            assertFalse(cards.links.isPreview(again), "opened by Back: a card of the reader's");
            assertTrue(view.history.cardsBack().contains(first), "and A still behind it");
            // Kept: a Go to from it opens beside it.
            caretOn(again, "go();");
            assertTrue(again.runMarkerCommand("lsp.gotoDefinition"));
        });
        waitFor(() -> fake.definitions.size() == 3, "asked a third time");
        FxTestSupport.runOnFx(() -> assertTrue(cards.isOpen(again), "no preview: it stays"));
    }

    private static String caller(String name, String callee) {
        return "package demo;\n\nclass " + name + " {\n    void go() {\n        new " + callee + "().go();\n    }\n}\n";
    }

    /** Puts {@code card}'s caret on the start of {@code text} in it. */
    private static void caretOn(ProjectMapPreview card, String text) {
        int at = card.editor().getText().indexOf(text);
        assertTrue(at >= 0, text + " in " + card.editor().getText());
        card.editor().moveTo(at);
    }

    /** The open card of {@code file} or of a declaration in it, or null. */
    private static @org.jspecify.annotations.Nullable ProjectMapPreview openUnder(MapCards cards, Path file) {
        return cards.previews.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(file))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    @Test
    void callHierarchyIsATreeOpenedALevelAtATimeThatMarksRecursion() throws Exception {
        Path src = Files.createDirectories(root.resolve("src"));
        Files.writeString(src.resolve("Shape.java"), SHAPE);
        Path use = Files.writeString(src.resolve("Use.java"), USE);
        ServerCapabilities capabilities = new ServerCapabilities();
        capabilities.setCallHierarchyProvider(true);
        FakeLanguageServer fake = startServer(capabilities);
        fake.callHierarchyResponse = List.of(callItem("total", use, 3, 11));
        // Whatever is asked, twice() calls it: one level down twice() is its own caller.
        fake.incomingCallsResponse = List.of(
                new org.eclipse.lsp4j.CallHierarchyIncomingCall(callItem("twice", use, 7, 11), List.of(range(4, 30))));

        ProjectPanel panel = openPanel();
        ProjectMapView view = FxTestSupport.field(panel, "mapView");
        MapCards cards = FxTestSupport.field(view, "cards");
        FxTestSupport.runOnFx(() -> view.setLinkSource(new ProjectMapLinkSearch(manager, panel.projectIndex())));
        ProjectMapPreview source = openCard(cards, use);
        FxTestSupport.runOnFx(() -> {
            source.editor().requestFocus();
            source.editor().moveTo(3, 12);
            assertTrue(source.runMarkerCommand("lsp.callHierarchy"), "typed in a card: the card's");
        });
        waitFor(() -> treeRows(cards, source).stream().anyMatch(row -> row.startsWith("twice")), "the caller's row");
        FxTestSupport.runOnFx(() -> {
            MapHierarchyTree tree = cards.links.hierarchy(source);
            assertEquals(MapCardLinks.Kind.CALLERS, tree.kind());
            assertEquals(1, fake.incomingCallRequests.size(), "one level asked");
            assertTrue(
                    tree.tree().isFocused() || tree.contains(source.getScene().getFocusOwner()), "the keyboard in it");
            assertTrue(cards.links.allAttachments().contains(tree), "placement keeps clear of it");
            assertEquals(
                    tree.side() == CardAttachment.Side.OVER ? 0 : MapHierarchyTree.WIDTH + MapHierarchyTree.GAP,
                    tree.reserved(tree.side()),
                    "what stands beside the card goes past it");
            tree.tree().getRoot().getChildren().getFirst().setExpanded(true);
        });
        waitFor(
                () -> {
                    MapHierarchyTree tree = cards.links.hierarchy(source);
                    var twice = tree.tree().getRoot().getChildren().getFirst();
                    return !twice.getChildren().isEmpty()
                            && twice.getChildren().getFirst().getValue().recursive();
                },
                "twice() under itself, marked");
        FxTestSupport.runOnFx(() -> {
            MapHierarchyTree tree = cards.links.hierarchy(source);
            var again =
                    tree.tree().getRoot().getChildren().getFirst().getChildren().getFirst();
            assertTrue(again.isLeaf(), "not asked again");
            assertEquals(2, fake.incomingCallRequests.size(), "the next level asked when opened");
            assertEquals(
                    tr(
                            "project.map.hierarchy.recursive",
                            MapHierarchyTree.rowText(again.getValue().group())),
                    again.getValue().text());

            tree.tree().getSelectionModel().select(tree.tree().getRoot());
            assertFalse(tree.openSelected(), "the root is the card asked from");
            tree.tree().getSelectionModel().select(again);
            assertTrue(tree.openSelected(), "a recursive row still opens its declaration");
            for (CardAttachment.Side side : CardAttachment.Side.values()) {
                double[] at = tree.boundsAt(side);
                assertEquals(MapHierarchyTree.WIDTH * source.mapScale(), at[2], 0.01, side.name());
            }
            assertTrue(tree.contains(tree.tree()));
            assertFalse(tree.contains(source));
            assertNotNull(tree.bounds());
            assertEquals(source, tree.card());
            tree.raise();
            // Enter on a row opens it, as a double-click does.
            tree.tree()
                    .getSelectionModel()
                    .select(tree.tree().getRoot().getChildren().getFirst());
            tree.tree()
                    .fireEvent(new javafx.scene.input.KeyEvent(
                            javafx.scene.input.KeyEvent.KEY_PRESSED,
                            "",
                            "",
                            javafx.scene.input.KeyCode.ENTER,
                            false,
                            false,
                            false,
                            false));
            assertTrue(cards.previews.keySet().stream()
                    .anyMatch(key -> ProjectMapStructure.isSymbolPath(key)
                            && key.toString().contains("twice")));
            ProjectMapPreview twiceCard = cards.previews.entrySet().stream()
                    .filter(entry -> entry.getKey().toString().contains("twice"))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElseThrow();
            assertTrue(
                    cards.links.isResult(twiceCard),
                    "kept while the card asked from is open, though no folder of the map shows its row");
        });
        FxTestSupport.runOnFx(() -> {
            MapHierarchyTree tree = cards.links.hierarchy(source);
            // Escape closes it, the keyboard back in the card.
            tree.tree()
                    .fireEvent(new javafx.scene.input.KeyEvent(
                            javafx.scene.input.KeyEvent.KEY_PRESSED,
                            "",
                            "",
                            javafx.scene.input.KeyCode.ESCAPE,
                            false,
                            false,
                            false,
                            false));
            assertEquals(null, cards.links.hierarchy(source));
            assertTrue(tree.disposed());
            tree.dispose(); // twice: nothing more
            assertEquals(null, tree.bounds());
            assertEquals(
                    0,
                    tree.reserved(CardAttachment.Side.LEFT) + tree.reserved(CardAttachment.Side.RIGHT),
                    "gone: takes no room");

            // From the card's menu, and closed with its card.
            assertTrue(source.runMarkerCommand("lsp.callHierarchy"));
            assertNotNull(cards.links.hierarchy(source));
            MapHierarchyTree shown = cards.links.hierarchy(source);
            FxTestSupport.invokeWith(cards, "closePreview", ProjectMapPreview.class, source);
            assertTrue(shown.disposed(), "closed with its card");
        });
    }

    @Test
    void withoutAServerTheCallersTreeGoesDeeperByName() throws Exception {
        Path src = Files.createDirectories(root.resolve("src"));
        Path chain = Files.writeString(
                src.resolve("Chain.java"),
                String.join(
                        "\n",
                        "package demo;",
                        "",
                        "class Chain {",
                        "    void top() {",
                        "        mid();",
                        "    }",
                        "",
                        "    void mid() {",
                        "        low();",
                        "    }",
                        "",
                        "    void low() {",
                        "        mid();",
                        "    }",
                        "}",
                        ""));
        ProjectPanel panel = openPanel();
        ProjectMapView view = FxTestSupport.field(panel, "mapView");
        MapCards cards = FxTestSupport.field(view, "cards");
        FxTestSupport.runOnFx(() -> view.setLinkSource(new ProjectMapLinkSearch(manager, panel.projectIndex())));
        ProjectMapPreview source = openCard(cards, chain);
        FxTestSupport.runOnFx(() -> {
            source.editor().moveTo(11, 10); // on low's name
            assertTrue(source.runMarkerCommand("lsp.callHierarchy"));
        });
        waitFor(() -> treeRows(cards, source).stream().anyMatch(row -> row.startsWith("mid")), "low's caller, mid()");
        FxTestSupport.runOnFx(() -> {
            MapHierarchyTree tree = cards.links.hierarchy(source);
            assertEquals(1, treeRows(cards, source).size(), "mid() alone calls low()");
            tree.tree().getRoot().getChildren().getFirst().setExpanded(true);
        });
        waitFor(
                () -> {
                    var mid = cards.links
                            .hierarchy(source)
                            .tree()
                            .getRoot()
                            .getChildren()
                            .getFirst();
                    return mid.getChildren().size() == 2
                            && mid.getChildren().stream()
                                    .noneMatch(item -> item.getValue().message());
                },
                "mid()'s own callers, by name");
        FxTestSupport.runOnFx(() -> {
            var mid =
                    cards.links.hierarchy(source).tree().getRoot().getChildren().getFirst();
            List<String> callers = mid.getChildren().stream()
                    .map(item -> item.getValue().text())
                    .toList();
            assertTrue(callers.stream().anyMatch(text -> text.startsWith("top")), callers.toString());
            var low = mid.getChildren().stream()
                    .filter(item -> item.getValue().text().contains("low"))
                    .findFirst()
                    .orElseThrow();
            assertTrue(low.getValue().recursive(), "low() calls mid(), which calls low(): the root again");
        });
    }

    @Test
    void showCalleesInACardOpensWhatItsDeclarationCallsAndTheKeyboardStays() throws Exception {
        Path src = Files.createDirectories(root.resolve("src"));
        Path shape = Files.writeString(src.resolve("Shape.java"), SHAPE);
        Path use = Files.writeString(src.resolve("Use.java"), USE);
        ServerCapabilities capabilities = new ServerCapabilities();
        capabilities.setCallHierarchyProvider(true);
        FakeLanguageServer fake = startServer(capabilities);
        fake.callHierarchyResponse = List.of(callItem("total", use, 3, 11));
        // total() calls area() (in Shape.java) once and twice() (further down its own file) once.
        fake.outgoingCallsResponse = List.of(
                new org.eclipse.lsp4j.CallHierarchyOutgoingCall(callItem("area", shape, 3, 11), List.of(range(4, 21))),
                new org.eclipse.lsp4j.CallHierarchyOutgoingCall(callItem("twice", use, 7, 11), List.of(range(4, 30))));

        ProjectPanel panel = openPanel();
        ProjectMapView view = FxTestSupport.field(panel, "mapView");
        MapCards cards = FxTestSupport.field(view, "cards");
        FxTestSupport.runOnFx(() -> view.setLinkSource(new ProjectMapLinkSearch(manager, panel.projectIndex())));
        ProjectMapPreview source = openCard(cards, use);

        FxTestSupport.runOnFx(() -> {
            source.editor().requestFocus();
            source.editor().moveTo(3, 12);
            assertTrue(source.runMarkerCommand(MapCardLinks.CALLEES_COMMAND), "the Map's command, typed in a card");
        });
        waitFor(() -> resultCards(cards, shape).size() == 1, "area()'s card");
        ProjectMapPreview callee =
                FxTestSupport.callOnFx(() -> resultCards(cards, shape).getFirst());
        waitFor(() -> "area".equals(callee.editor().getSelectedText()), "the callee's own name selected");
        FxTestSupport.runOnFx(() -> {
            assertEquals(1, fake.outgoingCallRequests.size(), "the outgoing calls asked");
            assertEquals(List.of(), fake.incomingCallRequests, "not the callers");
            MapCardLinks.EdgeSet set = cards.links.setsForTest(source).get(MapCardLinks.Kind.CALLEES);
            assertNotNull(set);
            assertEquals(
                    List.of("calls"),
                    set.labelsForTest().stream().map(Label::getText).toList());
            assertEquals(2, cards.previews.size(), "twice() is on the card asked from: no card of its own");
            assertEquals(List.of(), view.history.cardsBack(), "a list of calls: the keyboard stays");
        });

        // From the command palette: asked in the card the Map's keyboard was last in.
        FxTestSupport.runOnFx(() -> assertTrue(panel.mapNavigationCommand(MapCardLinks.CALLEES_COMMAND)));
        waitFor(() -> fake.outgoingCallRequests.size() == 2, "asked again");
    }

    @Test
    void withNoServerADefinitionIsTheDeclarationOfThatNameAndOneOnTheSameCardMovesItsCaret() throws Exception {
        Path src = Files.createDirectories(root.resolve("src"));
        Path shape = Files.writeString(src.resolve("Shape.java"), SHAPE);
        Path use = Files.writeString(src.resolve("Use.java"), USE);
        ProjectPanel panel = openPanel();
        ProjectMapView view = FxTestSupport.field(panel, "mapView");
        MapCards cards = FxTestSupport.field(view, "cards");
        FxTestSupport.runOnFx(() -> view.setLinkSource(new ProjectMapLinkSearch(null, panel.projectIndex())));
        ProjectMapPreview source = openCard(cards, use);

        // twice() is declared further down the same file: the card goes there, no new card opens.
        FxTestSupport.runOnFx(() -> {
            source.editor().moveTo(4, 32);
            assertTrue(source.runMarkerCommand("lsp.gotoDefinition"));
        });
        waitFor(() -> source.editor().getCurrentParagraph() == 7, "the caret on twice()'s declaration");
        FxTestSupport.runOnFx(() -> {
            assertEquals("twice", source.editor().getSelectedText());
            assertEquals(1, cards.previews.size(), "no other card");
            assertTrue(source.runMarkerCommand("nav.back"));
            assertEquals(4, source.editor().getCurrentParagraph(), "Back returns the caret");
        });

        // area() is declared in Shape.java: found by name, the edge says so.
        FxTestSupport.runOnFx(() -> {
            source.editor().moveTo(4, 22);
            assertTrue(source.runMarkerCommand("lsp.gotoDefinition"));
        });
        waitFor(() -> resultCards(cards, shape).size() == 1, "the declaration's card");
        FxTestSupport.runOnFx(() -> {
            MapCardLinks.EdgeSet set = cards.links.setsForTest(source).get(MapCardLinks.Kind.DEFINITION);
            assertEquals(
                    List.of("by name"),
                    set.labelsForTest().stream().map(Label::getText).toList());
        });

        // What only a server answers says so instead of matching the name.
        FxTestSupport.runOnFx(() -> {
            source.editor().moveTo(4, 22);
            assertTrue(source.runMarkerCommand("lsp.gotoImplementation"));
        });
        waitFor(
                () -> cards.links.setsForTest(source).get(MapCardLinks.Kind.IMPLEMENTATIONS) == null
                        && resultCards(cards, src).size() == 1,
                "no implementation cards");
        ProjectMapStatusLine status = FxTestSupport.field(panel, "mapStatusLine");
        FxTestSupport.runOnFx(() -> assertTrue(source.runMarkerCommand(MapCardLinks.CALLEES_COMMAND)));
        waitFor(
                () -> status.text().contains(com.editora.i18n.Messages.tr("project.map.links.needsServer")),
                "callees need a server");
        FxTestSupport.runOnFx(
                () -> assertNull(cards.links.setsForTest(source).get(MapCardLinks.Kind.CALLEES), "no callee cards"));
    }

    // --- helpers ------------------------------------------------------------------------------------

    private FakeLanguageServer startServer(ServerCapabilities capabilities) throws Exception {
        return startServer(capabilities, false);
    }

    /** {@code raw}: the fake also answers jdtls's own requests ({@code java/classFileContents}, …). */
    private FakeLanguageServer startServer(ServerCapabilities capabilities, boolean raw) throws Exception {
        Path open = Files.writeString(root.resolve("Open.java"), "class Open {}\n");
        manager = new LspManager((f, d) -> {}, (t, m) -> {});
        List<FakeLanguageServer> fakes = raw
                ? LspTestHooks.useFakeSessionsWithRawRequests(manager, capabilities)
                : LspTestHooks.useFakeSessions(manager, capabilities);
        manager.configure(true, Map.of("java", "jdtls"));
        manager.openDocument(open, root, "java", "class Open {}\n"); // an editor tab: the server runs
        return fakes.getFirst();
    }

    private ProjectPanel openPanel() throws Exception {
        ProjectPanel panel = FxTestSupport.callOnFx(() -> {
            ProjectPanel p = DisposedPanels.track(
                    new ProjectPanel(editorOpened::add, (a, b) -> {}, x -> {}, x -> false, x -> false));
            p.setRoot(root);
            p.openMapWindow(false);
            return p;
        });
        ProjectMapView view = FxTestSupport.field(panel, "mapView");
        Region surface = FxTestSupport.field(view, "surface");
        waitFor(() -> !rows(surface).isEmpty(), "the first column");
        return panel;
    }

    private static ProjectMapPreview openCard(MapCards cards, Path file) throws Exception {
        ProjectMapPreview card = FxTestSupport.callOnFx(() -> cards.openFileCard(file));
        waitFor(() -> card.editor().getLength() > 0, "the card's text");
        return card;
    }

    /** The open declaration cards of files at or under {@code under}. */
    private static List<ProjectMapPreview> resultCards(MapCards cards, Path under) {
        return cards.previews.entrySet().stream()
                .filter(entry -> ProjectMapStructure.isSymbolPath(entry.getKey())
                        && entry.getKey().startsWith(under))
                .map(Map.Entry::getValue)
                .toList();
    }

    /** The texts of the first level of {@code card}'s hierarchy tree; none without one. */
    private static List<String> treeRows(MapCards cards, ProjectMapPreview card) {
        MapHierarchyTree tree = cards.links.hierarchy(card);
        if (tree == null || tree.tree().getRoot() == null) {
            return List.of();
        }
        return tree.tree().getRoot().getChildren().stream()
                .map(item -> item.getValue().text())
                .toList();
    }

    private static org.eclipse.lsp4j.CallHierarchyItem callItem(String name, Path file, int line, int column) {
        Range whole = new Range(new Position(line, 0), new Position(line + 2, 5));
        return new org.eclipse.lsp4j.CallHierarchyItem(
                name, SymbolKind.Method, LspTestHooks.uri(file), whole, range(line, column));
    }

    private static TypeHierarchyItem typeItem(String name, Path file, int line, int column) {
        Range whole = new Range(new Position(line, 0), new Position(line + 3, 1));
        TypeHierarchyItem item =
                new TypeHierarchyItem(name, SymbolKind.Class, LspTestHooks.uri(file), whole, range(line, column));
        return item;
    }

    private static Range range(int line, int column) {
        return new Range(new Position(line, column), new Position(line, column + 4));
    }

    private static List<ProjectMapModel.Entry> rows(Region surface) {
        return FxTestSupport.field(surface, "entries");
    }

    private static void waitFor(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (FxTestSupport.callOnFx(condition::getAsBoolean)) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertNotNull(null, "timed out: " + message);
    }
}
