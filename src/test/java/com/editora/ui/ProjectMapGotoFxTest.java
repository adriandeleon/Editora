package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import javafx.scene.Node;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import com.editora.command.KeyDispatcher;
import com.editora.lsp.FakeLanguageServer;
import com.editora.lsp.LspManager;
import com.editora.lsp.LspTestHooks;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Go to Symbol and Go to File typed on the Map: the editor's pickers open over the Map window, and what is picked opens
 * as a card on the Map — a symbol's card with its name selected — the keyboard in it and Back
 * returning to the card it was typed in; nothing opens in the editor. Without a language server, Go to Symbol offers
 * the Map's own {@code @name} search. A command a card does not run reaches the Map window's own handler.
 */
@ExtendWith(DisposedPanels.class)
class ProjectMapGotoFxTest {

    private static final String SHAPE =
            String.join("\n", "package demo;", "", "public interface Shape {", "    double area();", "}", "");

    private static final String USE = String.join(
            "\n",
            "package demo;",
            "",
            "class Use {",
            "    double total(Shape shape) {",
            "        return 1;",
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
    void goToSymbolTypedInACardOpensTheSymbolsCardAndBackReturns() throws Exception {
        Path src = Files.createDirectories(root.resolve("src"));
        Path shape = Files.writeString(src.resolve("Shape.java"), SHAPE);
        Path use = Files.writeString(src.resolve("Use.java"), USE);
        ServerCapabilities capabilities = new ServerCapabilities();
        capabilities.setWorkspaceSymbolProvider(true);
        FakeLanguageServer fake = startServer(capabilities);
        fake.workspaceSymbolResponse = List.of(new WorkspaceSymbol(
                "area()", SymbolKind.Method, Either.forLeft(new Location(LspTestHooks.uri(shape), range(3, 11)))));

        ProjectPanel panel = openPanel();
        setField(panel, "mapServers", manager); // what shareIndexes gives it from the window
        ProjectMapView view = FxTestSupport.field(panel, "mapView");
        MapCards cards = FxTestSupport.field(view, "cards");
        FxTestSupport.runOnFx(() -> view.setLinkSource(new ProjectMapLinkSearch(manager, panel.projectIndex())));
        ProjectMapPreview source = openCard(cards, use);
        FxTestSupport.runOnFx(() -> {
            source.editor().requestFocus();
            source.editor().moveTo(3, 4);
            assertSame(source, view.focusedCard());
            assertFalse(source.runMarkerCommand("lsp.gotoSymbol"), "not the card's: it goes on to the Map window");
            assertTrue(runMapCommand(panel, "lsp.gotoSymbol"), "the Map window's");
        });
        ListView<?> symbols = waitForOverlayList(panel);
        FxTestSupport.runOnFx(() -> overlayField(panel).setText("area"));
        waitFor(() -> !symbols.getItems().isEmpty(), "the server's symbols");
        FxTestSupport.runOnFx(() -> {
            symbols.getSelectionModel().select(0);
            pressEnter(overlayField(panel));
        });
        waitFor(() -> placeCards(cards, shape).size() == 1, "the symbol's card");
        ProjectMapPreview found =
                FxTestSupport.callOnFx(() -> placeCards(cards, shape).getFirst());
        waitFor(() -> "area".equals(found.editor().getSelectedText()), "its name selected");
        FxTestSupport.runOnFx(() -> {
            assertEquals(1, fake.workspaceSymbols.size(), "asked the card's server");
            assertEquals(List.of(source), view.history.cardsBack(), "Back goes to the card it was typed in");
            assertTrue(found.runMarkerCommand("nav.back"));
            assertEquals(3, source.editor().getCurrentParagraph(), "its caret where it was");
        });
        assertEquals(List.of(), editorOpened, "nothing opened in the editor");
    }

    @Test
    void goToFileTypedInACardOpensTheFilesCardOnTheMap() throws Exception {
        Path src = Files.createDirectories(root.resolve("src"));
        Path shape = Files.writeString(src.resolve("Shape.java"), SHAPE);
        Path use = Files.writeString(src.resolve("Use.java"), USE);
        ProjectPanel panel = openPanel();
        ProjectMapView view = FxTestSupport.field(panel, "mapView");
        MapCards cards = FxTestSupport.field(view, "cards");
        ProjectMapPreview source = openCard(cards, use);
        FxTestSupport.runOnFx(() -> {
            source.editor().requestFocus();
            assertTrue(runMapCommand(panel, "file.find"));
        });
        waitForOverlayList(panel);
        FxTestSupport.runOnFx(() -> {
            TextField field = overlayField(panel);
            assertEquals(src.toString(), Path.of(field.getText()).toString(), "starts in the card's folder");
            field.setText(shape.toString());
        });
        FxTestSupport.runOnFx(() -> pressEnter(overlayField(panel)));
        waitFor(() -> cards.codeCard(shape) != null, "the file's card");
        FxTestSupport.runOnFx(() -> {
            assertEquals(List.of(source), view.history.cardsBack());
            assertFalse(panel.mapWindow().overlayHost().isShowing(), "the finder closed");
        });
        assertEquals(List.of(), editorOpened, "nothing opened in the editor");

        // A file outside the Map's root is not the Map's to show.
        Path outside = Files.writeString(temp.resolve("Elsewhere.java"), "class Elsewhere {}\n");
        assertFalse(FxTestSupport.callOnFx(() -> view.showOnMap(null, outside, -1, null)));
    }

    @Test
    void backAndForwardWalkTheMapsPlacesAndItsCardsAsOneHistory() throws Exception {
        Path src = Files.createDirectories(root.resolve("src"));
        Path shape = Files.writeString(src.resolve("Shape.java"), SHAPE);
        Path use = Files.writeString(src.resolve("Use.java"), USE);
        ProjectPanel panel = openPanel();
        ProjectMapView view = FxTestSupport.field(panel, "mapView");
        MapCards cards = FxTestSupport.field(view, "cards");
        MapSurface surface = FxTestSupport.field(view, "surface");
        @SuppressWarnings("unchecked")
        Predicate<String> mapCommands = (Predicate<String>) view.getProperties().get(KeyDispatcher.HANDLED_COMMANDS);
        FxTestSupport.runOnFx(() -> view.revealPath(use));
        waitFor(() -> use.equals(surface.selectedPath()), "Use.java selected on the map");
        ProjectMapPreview source = openCard(cards, use);
        FxTestSupport.runOnFx(() -> {
            source.editor().moveTo(3, 4);
            assertTrue(view.showOnMap(source, shape, -1, null), "Go to File typed in the card");
        });
        waitFor(() -> cards.codeCard(shape) != null, "Shape.java's card");
        ProjectMapPreview target = FxTestSupport.callOnFx(() -> cards.codeCard(shape));
        FxTestSupport.runOnFx(() -> {
            int at = view.history.index();
            assertEquals(List.of(source), view.history.cardsBack());
            assertEquals(use, view.historyPlaces().get(at - 1), "Use.java's card stands on its row's place: one stop");
            assertEquals(shape, view.historyPlaces().get(at));
            String sourceLabel = tr("project.map.history.cardEntry", view.historyLabel(use), "4");
            assertTrue(
                    view.historyMenu().getItems().stream().anyMatch(item -> sourceLabel.equals(item.getText())),
                    "the menu says the line a card's stop is at");

            // Back on the map (Alt+Left, the ‹ button): the card the code came from, its caret where it was.
            target.editor().moveTo(2, 3);
            surface.requestFocus();
            view.moveHistory(-1);
            assertEquals(3, source.editor().getCurrentParagraph());
            assertEquals(4, source.editor().getCaretColumn());
            // Forward typed on the map (nav.forward): the card it went to, its caret kept from when Back left it.
            source.editor().moveTo(1, 0);
            assertTrue(mapCommands.test("nav.forward"), "the map's own Forward, not the editor's");
            assertEquals(2, target.editor().getCurrentParagraph());
            // Back typed in a card walks the same history (a card with no language server leaves it to the map).
            target.editor().requestFocus();
            assertFalse(target.runMarkerCommand("nav.back"), "no server: not the card's own");
            assertTrue(mapCommands.test("nav.back"));
            assertEquals(1, source.editor().getCurrentParagraph(), "where the caret was when Forward left it");
            assertFalse(mapCommands.test("file.find"), "only Back and Forward");

            // A closed card's stop keeps its line: Forward opens the card there again.
            FxTestSupport.invokeWith(cards, "closePreview", ProjectMapPreview.class, target);
            assertEquals(null, cards.codeCard(shape));
            source.editor().requestFocus();
            assertTrue(mapCommands.test("nav.forward"));
            assertTrue(view.history.cardsBack().contains(source));
        });
        waitFor(() -> cards.codeCard(shape) != null, "Shape.java's card opened again");
        ProjectMapPreview reopened = FxTestSupport.callOnFx(() -> cards.codeCard(shape));
        waitFor(() -> reopened.editor().getCurrentParagraph() == 2, "at the line its caret was on");
        FxTestSupport.runOnFx(() -> {
            assertFalse(view.history.canGoForward());
            assertTrue(view.history.canGoBack());
            assertTrue(view.history.stops().get(view.history.index()).card() == reopened, "the stop is on it again");
        });
        assertEquals(List.of(), editorOpened, "nothing opened in the editor");
    }

    @Test
    void goToSymbolFromTheMapOpensTheDeclarationsCardAndALibrarySymbolGoesToTheEditor() throws Exception {
        Path src = Files.createDirectories(root.resolve("src"));
        Path shape = Files.writeString(src.resolve("Shape.java"), SHAPE);
        Path use = Files.writeString(src.resolve("Use.java"), USE);
        ServerCapabilities capabilities = new ServerCapabilities();
        capabilities.setWorkspaceSymbolProvider(true);
        FakeLanguageServer fake = startServer(capabilities);
        fake.workspaceSymbolResponse = List.of(new WorkspaceSymbol(
                "area()", SymbolKind.Method, Either.forLeft(new Location(LspTestHooks.uri(shape), range(3, 11)))));

        ProjectPanel panel = openPanel();
        List<String> handed = handToEditor(panel);
        setField(panel, "mapServers", manager);
        ProjectMapView view = FxTestSupport.field(panel, "mapView");
        MapCards cards = FxTestSupport.field(view, "cards");
        MapSurface surface = FxTestSupport.field(view, "surface");
        FxTestSupport.runOnFx(() -> view.setLinkSource(new ProjectMapLinkSearch(manager, panel.projectIndex())));
        // Shape.java is not outlined yet: the pick waits for its outline, then opens the declaration's card.
        FxTestSupport.runOnFx(() -> view.revealPath(use));
        waitFor(() -> use.equals(view.gotoAnchor(null)), "Use.java selected on the map");

        // Typed on the map, no card: asked of the selected row's file's server.
        FxTestSupport.runOnFx(() -> {
            surface.requestFocus();
            assertTrue(view.focusedCard() == null);
            assertTrue(runMapCommand(panel, "lsp.gotoSymbol"));
        });
        ListView<?> symbols = waitForOverlayList(panel);
        FxTestSupport.runOnFx(() -> overlayField(panel).setText("area"));
        waitFor(() -> !symbols.getItems().isEmpty(), "the server's symbols");
        FxTestSupport.runOnFx(() -> {
            symbols.getSelectionModel().select(0);
            pressEnter(overlayField(panel));
        });
        waitFor(
                () -> placeCards(cards, shape).stream()
                        .anyMatch(card -> "area".equals(card.editor().getSelectedText())),
                "area's card, its name selected");
        ProjectMapPreview declaration =
                FxTestSupport.callOnFx(() -> placeCards(cards, shape).getFirst());
        FxTestSupport.runOnFx(() -> {
            assertNotNull(declaration.range(), "the declaration's own card: " + declaration.title());
            assertEquals(List.of(), view.history.cardsBack(), "picked on the map: no card to go back to");
        });
        // No Structure column holds it, yet the Map's next check for cards whose rows went away keeps it: its file's
        // folder is still open.
        setField(cards, "cardsCheckedAgainst", null);
        FxTestSupport.runOnFx(() -> FxTestSupport.invoke(cards, "repaintPreviewConnectors"));
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> assertTrue(cards.previews.containsValue(declaration), "the picked card stays"));

        // The card's one-line selection is the query, as in the editor.
        ProjectMapPreview source = openCard(cards, use);
        FxTestSupport.runOnFx(() -> {
            source.editor().requestFocus();
            int at = source.editor().getText().indexOf("Shape");
            source.editor().selectRange(at, at + "Shape".length());
            assertTrue(runMapCommand(panel, "lsp.gotoSymbol"));
        });
        waitForOverlayList(panel);
        FxTestSupport.runOnFx(() -> {
            assertEquals("Shape", overlayField(panel).getText());
            panel.mapWindow().overlayHost().hide();
        });

        // A JDK class has no file on the Map: the editor opens its source.
        fake.symbolInformationResponse = List.of(new org.eclipse.lsp4j.SymbolInformation(
                "ArrayList",
                SymbolKind.Class,
                new Location("jdt://contents/java.base/java.util/ArrayList.class?=demo", range(40, 13)),
                "java.util"));
        FxTestSupport.runOnFx(() -> {
            source.editor().selectRange(0, 0);
            assertTrue(runMapCommand(panel, "lsp.gotoSymbol"));
        });
        ListView<?> library = waitForOverlayList(panel);
        FxTestSupport.runOnFx(() -> overlayField(panel).setText("ArrayList"));
        waitFor(() -> !library.getItems().isEmpty(), "the library symbol");
        FxTestSupport.runOnFx(() -> {
            library.getSelectionModel().select(0);
            pressEnter(overlayField(panel));
        });
        waitFor(
                () -> handed.stream().anyMatch(s -> s.startsWith("openWorkspaceSymbol") && s.contains("ArrayList")),
                "handed to the editor");
    }

    @Test
    void goToFileHandsANewFileOrOneOutsideTheMapToTheEditor() throws Exception {
        Path src = Files.createDirectories(root.resolve("src"));
        Files.writeString(src.resolve("Shape.java"), SHAPE);
        Path outside = Files.writeString(temp.resolve("Elsewhere.java"), "class Elsewhere {}\n");
        ProjectPanel panel = openPanel();
        List<String> handed = handToEditor(panel);
        ProjectMapView view = FxTestSupport.field(panel, "mapView");
        MapCards cards = FxTestSupport.field(view, "cards");
        MapSurface surface = FxTestSupport.field(view, "surface");
        waitFor(() -> surface.contains(src), "the src row");
        FxTestSupport.runOnFx(() -> {
            surface.select(src);
            surface.requestFocus();
            assertTrue(runMapCommand(panel, "file.find"));
        });
        waitForOverlayList(panel);
        FxTestSupport.runOnFx(() -> {
            TextField field = overlayField(panel);
            assertEquals(src.toString(), Path.of(field.getText()).toString(), "starts in the selected folder");
            field.setText(src.resolve("New.java").toString());
            pressEnter(field);
        });
        FxTestSupport.runOnFx(() -> {
            assertEquals(List.of("openPicked:" + src.resolve("New.java")), handed, "a new file: the editor's");
            assertTrue(runMapCommand(panel, "file.find"));
        });
        waitForOverlayList(panel);
        FxTestSupport.runOnFx(() -> {
            TextField field = overlayField(panel);
            field.setText(outside.toString());
            pressEnter(field);
            assertEquals("openPicked:" + outside, handed.getLast(), "outside the Map's root: the editor's");
            assertTrue(view.showOnMap(null, src, -1, null), "a folder is revealed");
            assertTrue(cards.previews.isEmpty(), "and opens no card");
        });

        // A panel with no root has no Map to answer on: the editor window's commands run.
        ProjectPanel bare = FxTestSupport.callOnFx(() -> DisposedPanels.track(
                new ProjectPanel(editorOpened::add, (a, b) -> {}, x -> {}, x -> false, x -> false)));
        FxTestSupport.runOnFx(() -> {
            assertFalse(runMapCommand(bare, "file.find"));
            assertFalse(runMapCommand(bare, "lsp.gotoSymbol"));
        });
    }

    @Test
    void aServerThatCannotListSymbolsLeavesGoToSymbolToTheMapsNameSearch() throws Exception {
        Path use = Files.writeString(root.resolve("Use.java"), USE);
        startServer(new ServerCapabilities()); // no workspace/symbol
        ProjectPanel panel = openPanel();
        setField(panel, "mapServers", manager);
        ProjectMapView view = FxTestSupport.field(panel, "mapView");
        FxTestSupport.runOnFx(() -> view.revealPath(use));
        waitFor(() -> use.equals(view.gotoAnchor(null)), "Use.java selected on the map");
        FxTestSupport.runOnFx(() -> {
            assertTrue(runMapCommand(panel, "lsp.gotoSymbol"));
            assertFalse(panel.mapWindow().overlayHost().isShowing(), "no picker to ask");
            assertEquals(
                    "@", FxTestSupport.<TextField>field(panel, "mapFilterField").getText());
        });
    }

    @Test
    void withNoServerGoToSymbolOffersTheMapsNameSearch() throws Exception {
        Files.writeString(root.resolve("Shape.java"), SHAPE);
        ProjectPanel panel = openPanel();
        FxTestSupport.runOnFx(() -> {
            assertTrue(runMapCommand(panel, "lsp.gotoSymbol"));
            TextField search = FxTestSupport.field(panel, "mapFilterField");
            assertEquals("@", search.getText(), "a name search, ready for the name");
            ProjectMapStatusLine status = FxTestSupport.field(panel, "mapStatusLine");
            assertEquals(com.editora.i18n.Messages.tr("project.map.goto.symbolSearch"), status.text());
        });
    }

    @Test
    void aCommandAComponentDoesNotRunReachesTheOneAroundIt() throws Exception {
        FxTestSupport.runOnFx(() -> {
            List<String> outerRan = new java.util.ArrayList<>();
            VBox outer = new VBox();
            VBox inner = new VBox();
            Region leaf = new Region();
            inner.getChildren().add(leaf);
            outer.getChildren().add(inner);
            outer.getProperties().put(KeyDispatcher.HANDLED_COMMANDS, (Predicate<String>) id -> outerRan.add(id));
            inner.getProperties().put(KeyDispatcher.HANDLED_COMMANDS, (Predicate<String>) "inner"::equals);
            assertTrue(handled(leaf, "inner"));
            assertEquals(List.of(), outerRan, "the inner one ran it");
            assertTrue(handled(leaf, "file.find"));
            assertEquals(List.of("file.find"), outerRan, "passed on to the one around it");
            assertFalse(handled(new Region(), "file.find"), "nobody claims it: the window runs it");
        });
    }

    // --- helpers ------------------------------------------------------------------------------------

    private static boolean handled(Node target, String commandId) {
        try {
            java.lang.reflect.Method method = KeyDispatcher.class.getDeclaredMethod(
                    "handledByFocusOwner", javafx.event.EventTarget.class, String.class);
            method.setAccessible(true);
            return (Boolean) method.invoke(null, target, commandId);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** What the panel hands to the editor window ({@code openPicked:path}, {@code openWorkspaceSymbol:name}). */
    private static List<String> handToEditor(ProjectPanel panel) throws Exception {
        List<String> handed = new CopyOnWriteArrayList<>();
        ProjectPanel.FileActions actions = (ProjectPanel.FileActions) java.lang.reflect.Proxy.newProxyInstance(
                ProjectPanel.FileActions.class.getClassLoader(),
                new Class<?>[] {ProjectPanel.FileActions.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "openPicked" -> handed.add("openPicked:" + args[0]);
                        case "openWorkspaceSymbol" ->
                            handed.add("openWorkspaceSymbol:" + ((LspManager.WorkspaceSymbolMatch) args[1]).name());
                        default -> {}
                    }
                    Class<?> type = method.getReturnType();
                    return type == boolean.class ? Boolean.FALSE : type == int.class ? 0 : null;
                });
        FxTestSupport.runOnFx(() -> panel.setFileActions(actions));
        return handed;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static boolean runMapCommand(ProjectPanel panel, String commandId) {
        return (Boolean) FxTestSupport.invokeWith(panel, "resultsFindCommand", String.class, commandId);
    }

    private static StackPane overlayRoot(ProjectPanel panel) {
        return FxTestSupport.field(panel.mapWindow().overlayHost(), "overlayRoot");
    }

    private static ListView<?> waitForOverlayList(ProjectPanel panel) throws Exception {
        waitFor(() -> overlayRoot(panel).lookup(".list-view") != null, "the picker over the Map");
        return FxTestSupport.callOnFx(() -> (ListView<?>) overlayRoot(panel).lookup(".list-view"));
    }

    private static TextField overlayField(ProjectPanel panel) {
        return (TextField) overlayRoot(panel).lookup(".text-field");
    }

    private static void pressEnter(TextField field) {
        field.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
    }

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
        waitFor(() -> !((List<?>) FxTestSupport.field(surface, "entries")).isEmpty(), "the first column");
        return panel;
    }

    private static ProjectMapPreview openCard(MapCards cards, Path file) throws Exception {
        ProjectMapPreview card = FxTestSupport.callOnFx(() -> cards.openFileCard(file));
        waitFor(() -> card.editor().getLength() > 0, "the card's text");
        return card;
    }

    /**
     * The open cards of {@code file}: its own, or a declaration's when its outline is held — as a linked card's
     * answer, which one depends on whether the Map has outlined the file yet.
     */
    private static List<ProjectMapPreview> placeCards(MapCards cards, Path file) {
        return cards.previews.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(file))
                .map(Map.Entry::getValue)
                .toList();
    }

    private static Range range(int line, int column) {
        return new Range(new Position(line, column), new Position(line, column + 4));
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
