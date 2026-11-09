package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;

import com.editora.lsp.FakeLanguageServer;
import com.editora.lsp.LspManager;
import com.editora.lsp.LspTestHooks;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.SymbolKind;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Linked reference cards in a live panel and Map (Project Map explore, position 5): a card's Show Callers / Show
 * References as Cards ask the language server that runs for its file — the file lent to it, no editor tab opened —
 * and open the callers as declaration cards beside it, joined by labelled edges; with no server the name is matched,
 * the edges are dashed and say "by name", and the groups past six are listed by a "+N more" chip.
 */
@ExtendWith(DisposedPanels.class)
class ProjectMapLinksFxTest {

    private static final String APP = String.join(
            "\n",
            "package demo;",
            "",
            "public class App {",
            "    int add(int a, int b) {",
            "        return a + b;",
            "    }",
            "}",
            "");

    private static final String CALLER = String.join(
            "\n",
            "package demo;",
            "",
            "class Caller {",
            "    int total(App app) {",
            "        return app.add(1, 2) + app.add(3, 4);",
            "    }",
            "",
            "    void reset(App app) {",
            "        app.add(0, 0);",
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
    void aCardsCallersAndReferencesComeFromTheRunningServerAsLinkedCardsWithNoEditorOpened() throws Exception {
        Path src = Files.createDirectories(root.resolve("src"));
        Path app = Files.writeString(src.resolve("App.java"), APP);
        Path caller = Files.writeString(src.resolve("Caller.java"), CALLER);
        Path open = Files.writeString(src.resolve("Open.java"), "class Open {}\n");
        ServerCapabilities capabilities = new ServerCapabilities();
        capabilities.setCallHierarchyProvider(Either.forLeft(true));
        capabilities.setReferencesProvider(Either.forLeft(true));
        capabilities.setHoverProvider(true);
        manager = new LspManager((f, d) -> {}, (t, m) -> {});
        List<FakeLanguageServer> fakes = LspTestHooks.useFakeSessions(manager, capabilities);
        manager.configure(true, Map.of("java", "jdtls"));
        manager.setOpenDocumentDiagnosticsOnly(true);
        manager.openDocument(open, root, "java", "class Open {}\n"); // an editor tab: the server runs
        FakeLanguageServer fake = fakes.getFirst();
        fake.hoverResponse = new org.eclipse.lsp4j.Hover(
                new org.eclipse.lsp4j.MarkupContent("markdown", "**add** — adds two numbers"));
        fake.callHierarchyResponse = List.of(item("add", app, 3, 8));
        fake.incomingCallsResponse = List.of(
                new CallHierarchyIncomingCall(item("total", caller, 3, 8), List.of(range(4, 19), range(4, 34))),
                new CallHierarchyIncomingCall(item("reset", caller, 7, 9), List.of(range(8, 12))));
        fake.referenceResponse = List.of(
                new Location(LspTestHooks.uri(caller), range(4, 19)),
                new Location(LspTestHooks.uri(caller), range(8, 12)));

        ProjectPanel panel = openPanel();
        ProjectMapView view = FxTestSupport.field(panel, "mapView");
        MapCards cards = FxTestSupport.field(view, "cards");
        FxTestSupport.runOnFx(() -> view.setLinkSource(new ProjectMapLinkSearch(manager, panel.projectIndex())));
        ProjectMapPreview source = openCard(cards, app);

        FxTestSupport.runOnFx(() -> cards.showLinks(source, MapCardLinks.Kind.CALLERS, 3, 8));
        waitFor(() -> resultCards(cards, caller).size() == 2, "a card for each caller");
        MapCardLinks.EdgeSet callers =
                FxTestSupport.callOnFx(() -> cards.links.setsForTest(source).get(MapCardLinks.Kind.CALLERS));
        assertNotNull(callers);
        FxTestSupport.runOnFx(() -> {
            assertEquals(
                    List.of("calls · 2", "calls"),
                    callers.labelsForTest().stream().map(Label::getText).toList(),
                    "total() calls it twice, reset() once");
            assertTrue(callers.labelsForTest().getFirst().getStyleClass().contains("by-server"));
            assertEquals(1, fake.callHierarchyPrepares.size());
            assertEquals(3, fake.callHierarchyPrepares.getFirst().getPosition().getLine());
            assertEquals(8, fake.callHierarchyPrepares.getFirst().getPosition().getCharacter(), "on add's name");
            assertEquals(
                    LspTestHooks.uri(app),
                    fake.callHierarchyPrepares.getFirst().getTextDocument().getUri());
            assertTrue(
                    fake.opened.stream()
                            .anyMatch(p -> p.getTextDocument().getUri().equals(LspTestHooks.uri(app))),
                    "the file was lent to the server");
            assertFalse(
                    fake.closed.stream()
                            .anyMatch(p -> p.getTextDocument().getUri().equals(LspTestHooks.uri(app))),
                    "the question's loan was given back, but the card keeps the file lent for its problems (gap 8)");
            assertTrue(source.watchesProblems());
            assertFalse(manager.isManaged(app), "no editor holds it");
            ProjectMapPreview total = resultCards(cards, caller).getFirst();
            assertTrue(
                    total.getAccessibleText().contains("calls App.java"),
                    "its accessible name says what joins it: " + total.getAccessibleText());
            assertTrue(cards.attachments(source).contains(callers), "the edges are the source card's attachment");
            cards.flushConnectors();
            cards.drawConnectors();
            callers.drawnForTest().values().forEach(line -> assertEquals(4, line.length, "each edge is drawn"));
        });
        assertEquals(List.of(), editorOpened, "nothing opened in the editor");

        FxTestSupport.runOnFx(() -> cards.showLinks(source, MapCardLinks.Kind.REFERENCES, 3, 8));
        waitFor(
                () -> {
                    MapCardLinks.EdgeSet set = cards.links.setsForTest(source).get(MapCardLinks.Kind.REFERENCES);
                    return set != null && set.labelsForTest().size() == 2;
                },
                "the references' edges, to the same two cards");
        FxTestSupport.runOnFx(() -> {
            MapCardLinks.EdgeSet references = cards.links.setsForTest(source).get(MapCardLinks.Kind.REFERENCES);
            assertEquals(
                    List.of("references", "references"),
                    references.labelsForTest().stream().map(Label::getText).toList());
            assertEquals(2, resultCards(cards, caller).size(), "the open cards are reused");
            assertEquals(1, fake.references.size());
        });

        // Gap 8: the card's problems come from the server it keeps its file lent to, and the mouse resting on a name
        // shows its documentation.
        waitFor(source::watchesProblems, "the card watches its file's problems");
        org.eclipse.lsp4j.Diagnostic unused = new org.eclipse.lsp4j.Diagnostic(range(3, 8), "add is never used");
        unused.setSeverity(org.eclipse.lsp4j.DiagnosticSeverity.Warning);
        LspTestHooks.publishDiagnostics(
                manager, open, new org.eclipse.lsp4j.PublishDiagnosticsParams(LspTestHooks.uri(app), List.of(unused)));
        waitFor(() -> source.cardProblems().size() == 1, "the warning marked on the card");
        int onAdd = APP.indexOf("add(");
        FxTestSupport.runOnFx(() -> {
            source.editor().moveTo(0);
            assertTrue(source.runMarkerCommand(ProjectMapPreview.NEXT_PROBLEM_COMMAND));
            assertEquals(3, source.editor().getCurrentParagraph(), "Next Problem: on add");
            source.editor()
                    .fireEvent(org.fxmisc.richtext.event.MouseOverTextEvent.beginAt(
                            new javafx.geometry.Point2D(50, 50), onAdd));
        });
        waitFor(() -> !fake.hovers.isEmpty(), "the server asked for the name's documentation");
        assertEquals(3, fake.hovers.getFirst().getPosition().getLine());
        waitFor(
                () -> {
                    String shown = source.mouseHoverText();
                    return shown != null && shown.contains("add is never used") && shown.contains("adds two");
                },
                "the warning and the documentation under the mouse");

        Pane host = FxTestSupport.field(view, "canvasHost");
        FxTestSupport.runOnFx(() -> {
            List<Label> labels = callers.labelsForTest();
            cards.close(source);
            assertFalse(source.watchesProblems(), "the closed card lets its file go");
            assertTrue(labels.stream().noneMatch(host.getChildren()::contains), "its edges close with it");
            assertEquals(List.of(), resultCards(cards, caller), "and the result cards it opened (review 4, C11)");
        });
        waitFor(
                () -> fake.closed.stream()
                        .anyMatch(p -> p.getTextDocument().getUri().equals(LspTestHooks.uri(app))),
                "given back to the server");
        assertEquals(List.of(), FxTestSupport.callOnFx(() -> manager.diagnosticsOf(app)), "its problems forgotten");
    }

    @Test
    void withNoServerTheNameIsMatchedTheEdgesSayByNameAndAChipListsTheRest() throws Exception {
        Path src = Files.createDirectories(root.resolve("src"));
        Path app = Files.writeString(src.resolve("App.java"), APP);
        Path use = Files.createDirectories(src.resolve("use")); // a folder the map does not open
        for (int index = 0; index < 8; index++) {
            Files.writeString(
                    use.resolve("Use" + index + ".java"),
                    "class Use" + index + " {\n    void run" + index + "(App app) {\n        app.add(" + index
                            + ", 1);\n    }\n}\n");
        }
        Files.writeString(src.resolve("Notes.md"), "add(1)\n");
        ProjectPanel panel = openPanel();
        ProjectMapView view = FxTestSupport.field(panel, "mapView");
        MapCards cards = FxTestSupport.field(view, "cards");
        FxTestSupport.runOnFx(() -> view.setLinkSource(new ProjectMapLinkSearch(null, panel.projectIndex())));
        Region surface = FxTestSupport.field(view, "surface");
        FxTestSupport.runOnFx(() -> view.revealPath(app));
        waitFor(() -> rows(surface).stream().anyMatch(row -> row.path().equals(app)), "the card's row");
        ProjectMapPreview source = openCard(cards, app);

        FxTestSupport.runOnFx(() -> cards.showLinks(source, MapCardLinks.Kind.CALLERS, 3, 8));
        waitFor(() -> resultCards(cards, src).size() == MapCardLinks.MAX_CARDS, "six result cards");
        MapCardLinks.EdgeSet set =
                FxTestSupport.callOnFx(() -> cards.links.setsForTest(source).get(MapCardLinks.Kind.CALLERS));
        FxTestSupport.runOnFx(() -> {
            assertTrue(set.labelsForTest().stream()
                    .allMatch(label -> label.getText().equals("by name")));
            assertTrue(set.labelsForTest().getFirst().getStyleClass().contains("by-name"), "dashed");
            assertEquals("+2 more", set.chip.getText());
            assertTrue(set.chip.getAccessibleText().contains("2 more callers of add"));
            assertTrue(
                    resultCards(cards, src).stream().allMatch(card -> card.range() != null),
                    "each is the declaration that calls add");
            cards.flushConnectors();
            cards.drawConnectors();
            assertTrue(set.chip.isVisible(), "the chip shows under the card");
            set.chip.fire();
        });
        // The chip's list: every caller's call, by file, with its line's text; the ones without a card first unmarked.
        waitFor(
                () -> set.list != null
                        && set.list.places().stream()
                                .anyMatch(place -> place.text().contains("add(")),
                "the list, its lines read");
        FxTestSupport.runOnFx(() -> {
            MapResultsList list = set.list;
            assertEquals(MapCardLinks.MAX_CARDS + 2, list.places().size(), "one call in each caller");
            List<MapResultsList.Place> waiting = list.places().stream()
                    .filter(place -> set.more.contains(place.group()))
                    .toList();
            assertEquals(2, waiting.size(), "the two on the chip");
            assertTrue(
                    waiting.getFirst().group().label().startsWith("run6"),
                    waiting.getFirst().group().label());
            assertTrue(list.openPlaceForTest(waiting.getFirst()));
        });
        waitFor(() -> resultCards(cards, src).size() == MapCardLinks.MAX_CARDS + 1, "the chosen one opens too");
        FxTestSupport.runOnFx(() -> {
            assertEquals("+1 more", set.chip.getText());
            assertEquals(MapCardLinks.MAX_CARDS + 1, set.labelsForTest().size());
            assertTrue(cards.codeCard(app) == source, "the card asked from stays open");
            FxTestSupport.invoke(view, "reload"); // the orphan check: src/use is not open on the map
        });
        TimeUnit.MILLISECONDS.sleep(600);
        FxTestSupport.runOnFx(cards::drawConnectors);
        FxTestSupport.runOnFx(() -> {});
        FxTestSupport.runOnFx(() -> assertEquals(
                MapCardLinks.MAX_CARDS + 1,
                resultCards(cards, src).size(),
                "a result card stays while the card it was found from does, its folder closed or not"));
    }

    // --- helpers ------------------------------------------------------------------------------------

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

    /** The open declaration cards of files at or under {@code under}, in the order they opened. */
    private static List<ProjectMapPreview> resultCards(MapCards cards, Path under) {
        return cards.previews.entrySet().stream()
                .filter(entry -> ProjectMapStructure.isSymbolPath(entry.getKey())
                        && entry.getKey().startsWith(under))
                .map(Map.Entry::getValue)
                .sorted(java.util.Comparator.comparing(card -> String.valueOf(card.path()) + card.range()))
                .toList();
    }

    private static CallHierarchyItem item(String name, Path file, int line, int column) {
        CallHierarchyItem item = new CallHierarchyItem();
        item.setName(name);
        item.setKind(SymbolKind.Method);
        item.setUri(LspTestHooks.uri(file));
        item.setRange(new Range(new Position(line, 0), new Position(line + 2, 5)));
        item.setSelectionRange(range(line, column));
        return item;
    }

    private static Range range(int line, int column) {
        return new Range(new Position(line, column), new Position(line, column + 3));
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
