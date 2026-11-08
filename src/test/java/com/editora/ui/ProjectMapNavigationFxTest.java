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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

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
            assertEquals(List.of(source), cards.backTrailForTest(), "Back goes to the card it came from");
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

        FxTestSupport.runOnFx(() -> {
            source.editor().moveTo(2, 18);
            assertTrue(source.runMarkerCommand("lsp.typeHierarchy"));
        });
        waitFor(() -> resultCards(cards, circle).size() == 1, "the subtype's card");
        waitFor(
                () -> "Circle"
                        .equals(resultCards(cards, circle).getFirst().editor().getSelectedText()),
                "the subtype's own name selected");
        FxTestSupport.runOnFx(() -> {
            MapCardLinks.EdgeSet subtypes = cards.links.setsForTest(source).get(MapCardLinks.Kind.SUBTYPES);
            assertNotNull(subtypes, "Type Hierarchy asks for the subtypes");
            assertEquals(
                    List.of("extends"),
                    subtypes.labelsForTest().stream().map(Label::getText).toList());
            assertTrue(fake.typeHierarchyPrepares.size() >= 2, "and the supertypes, both ways");
        });

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
            assertEquals(List.of(), cards.backTrailForTest(), "a list of calls: the keyboard stays");
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
        Path open = Files.writeString(root.resolve("Open.java"), "class Open {}\n");
        manager = new LspManager((f, d) -> {}, (t, m) -> {});
        List<FakeLanguageServer> fakes = LspTestHooks.useFakeSessions(manager, capabilities);
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
