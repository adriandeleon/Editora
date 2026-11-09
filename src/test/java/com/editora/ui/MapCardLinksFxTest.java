package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.lsp.FakeLanguageServer;
import com.editora.lsp.LspManager;
import com.editora.lsp.LspTestHooks;
import com.editora.search.ProjectFileList;
import com.editora.ui.ProjectMapView.FlowDirection;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Linked reference cards past their main paths (Map explore position 5), with answers the test hands over: an empty
 * answer, a stale one and one for a card closed meanwhile; places in files with no outline, opened at their place, the
 * "+N more" chip's long list; a question waiting for outlines when its card closes; the card asked from left out of
 * its own results; edges hidden with their card. Then the language-server side of {@link ProjectMapLinkSearch}: a
 * server without the capability, an empty or failed answer, and places with no file on the map.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MapCardLinksFxTest {

    @TempDir
    Path temp;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        com.editora.i18n.Messages.init("en");
    }

    private static final String APP = """
            package demo;

            public class App {
                public void refresh() {
                    helper();
                }

                void helper() {
                    helper();
                }
            }
            """;

    /** A source whose answers the test gives, in the order the questions came. */
    private static final class Held implements MapCardLinks.Source {
        final List<MapCardLinks.Request> asked = new ArrayList<>();
        final List<Consumer<MapCardLinks.Answer>> pending = new ArrayList<>();

        @Override
        public void find(Path root, MapCardLinks.Request request, Consumer<MapCardLinks.Answer> answer) {
            asked.add(request);
            pending.add(answer);
        }

        void answer(int index, MapCardLinks.Answer answer) {
            pending.get(index).accept(answer);
        }
    }

    private record Harness(Stage stage, ProjectMapView view, Path project, List<String> said, Held source)
            implements AutoCloseable {
        MapCards cards() {
            return view.cards;
        }

        MapCardLinks links() {
            return view.cards.links;
        }

        String last() {
            return said.isEmpty() ? "" : said.getLast();
        }

        @Override
        public void close() throws Exception {
            FxTestSupport.runOnFx(() -> {
                view.dispose();
                stage.hide();
            });
        }
    }

    private Harness open(Path project) throws Exception {
        List<String> said = new CopyOnWriteArrayList<>();
        Held source = new Held();
        Harness h = FxTestSupport.callOnFx(() -> {
            ProjectMapView view = new ProjectMapView(path -> {}, path -> false, path -> false);
            view.setOnStatus(said::add);
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(view), 1200, 820));
            stage.show();
            Region surface = FxTestSupport.field(view, "surface");
            FxTestSupport.call(
                    surface, "setFlowDirection", new Class<?>[] {FlowDirection.class}, FlowDirection.LEFT_TO_RIGHT);
            view.setRoot(project);
            view.setLinkSource(source);
            return new Harness(stage, view, project, said, source);
        });
        Region surface = FxTestSupport.callOnFx(() -> FxTestSupport.field(h.view(), "surface"));
        waitForFx(() -> (boolean)
                FxTestSupport.call(surface, "contains", new Class<?>[] {Path.class}, project.resolve("README.md")));
        return h;
    }

    private Path project(int textFiles) throws Exception {
        Path project = Files.createTempDirectory(temp, "project").toRealPath();
        Path many = Files.createDirectory(project.resolve("many"));
        for (int i = 0; i < textFiles; i++) {
            Files.writeString(many.resolve(String.format("t%03d.txt", i)), "one\ntwo\nthree uses go\nfour\n");
        }
        Files.writeString(Files.createDirectory(project.resolve("src")).resolve("App.java"), APP);
        Files.writeString(project.resolve("README.md"), "hello go\n");
        return project;
    }

    private static ProjectMapPreview card(Harness h, Path file) throws Exception {
        ProjectMapPreview card = FxTestSupport.callOnFx(() -> h.cards().openFileCard(file));
        waitForFx(() -> card.editor().getLength() > 0 && card.getWidth() > 0);
        return card;
    }

    private static MapCardLinks.Answer answer(List<MapCardLinks.Hit> hits, boolean partial, @Nullable String note) {
        return new MapCardLinks.Answer(hits, MapCardLinks.Via.NAME, partial, note, "go");
    }

    @Test
    void anEmptyAnswerSaysSoAndAStaleOrOrphanedAnswerIsDropped() throws Exception {
        Path project = project(2);
        try (Harness h = open(project)) {
            ProjectMapPreview readme = card(h, project.resolve("README.md"));
            Path text = project.resolve("many/t000.txt");
            FxTestSupport.runOnFx(() -> {
                assertTrue(h.cards().linksAvailable());
                h.cards().showLinks(readme, MapCardLinks.Kind.CALLERS, 0, 6);
                assertEquals(
                        tr("project.map.links.findingCallersUnnamed"), h.last(), "no name yet, nor a file's (C12)");
                MapCardLinks.Request first = h.source().asked.getFirst();
                assertNull(first.name());
                assertEquals(6, first.column());
                h.cards().showLinks(readme, MapCardLinks.Kind.CALLERS, 0, 6); // asked again: the first is stale
                h.cards().showLinks(readme, MapCardLinks.Kind.REFERENCES, 0, 6);
                assertEquals(tr("project.map.links.findingReferencesUnnamed"), h.last());

                h.source().answer(0, answer(List.of(new MapCardLinks.Hit(text, 2, 11, 1, null)), false, null));
                assertTrue(h.links().setsForTest(readme).isEmpty(), "an answer to an older question is dropped");

                h.source().answer(1, new MapCardLinks.Answer(List.of(), MapCardLinks.Via.SERVER, false, null, null));
                assertEquals(tr("project.map.links.noCallersUnnamed"), h.last(), "no empty name in the text");
                h.source().answer(2, MapCardLinks.Answer.none(MapCardLinks.Via.NAME, "No server ran.", "go"));
                assertEquals("No server ran. " + tr("project.map.links.noReferences", "go"), h.last());

                h.cards().showLinks(readme, MapCardLinks.Kind.CALLERS, 0, 6);
                h.cards().close(readme);
                h.source().answer(3, answer(List.of(new MapCardLinks.Hit(text, 2, 11, 1, null)), false, null));
                assertNull(h.cards().codeCard(text), "the card asked from closed: its answer opens nothing");
                h.cards().showLinks(readme, MapCardLinks.Kind.CALLERS, 0, 6);
                assertEquals(4, h.source().asked.size(), "a closed card asks nothing");

                assertFalse(h.links().isResult(null));
                assertTrue(h.links().attachments(readme).isEmpty());
                assertTrue(h.links().allAttachments().isEmpty());
                assertNull(h.links().besideSource(text));
                h.view().setLinkSource(null);
                assertFalse(h.cards().linksAvailable());
            });
        }
    }

    @Test
    void placesWithNoOutlineOpenTheirFilesAtThePlaceAndTheChipListsEveryPlace() throws Exception {
        int files = 200 + MapCardLinks.MAX_CARDS + 3;
        Path project = project(files);
        try (Harness h = open(project)) {
            Path readmeFile = project.resolve("README.md");
            ProjectMapPreview readme = card(h, readmeFile);
            FxTestSupport.runOnFx(() -> {
                readme.relocate(500, 0); // at the top: the chip goes under the card
                h.cards().showLinks(readme, MapCardLinks.Kind.REFERENCES, 0, 6);
                List<MapCardLinks.Hit> hits = new ArrayList<>();
                hits.add(new MapCardLinks.Hit(readmeFile, 0, 6, 1, null)); // the card asked from: left out
                for (int i = files - 1; i >= 0; i--) {
                    Path file = project.resolve(String.format("many/t%03d.txt", i));
                    hits.add(new MapCardLinks.Hit(file, 2, 11, 1, null));
                    if (i == 0) {
                        hits.add(new MapCardLinks.Hit(file, 3, 0, 1, null)); // a second place in the same file
                    }
                }
                h.source().answer(0, answer(hits, true, "Matched by name."));
            });
            // The first files' outlines are asked for (a text file has none); the edges come once they are in.
            waitForFx(() -> h.links().setsForTest(readme).get(MapCardLinks.Kind.REFERENCES) != null);
            MapCardLinks.EdgeSet set =
                    FxTestSupport.callOnFx(() -> h.links().setsForTest(readme).get(MapCardLinks.Kind.REFERENCES));
            assertNotNull(set, h.said().toString());
            Path first = project.resolve("many/t000.txt");
            waitForFx(() -> {
                ProjectMapPreview card = h.cards().codeCard(first);
                return card != null
                        && card.editor().getLength() > 0
                        && card.editor().getSelection().getLength() > 0;
            });
            FxTestSupport.runOnFx(() -> {
                assertEquals(MapCardLinks.MAX_CARDS, set.labelsForTest().size());
                assertEquals("by name · 2", set.labelsForTest().getFirst().getText(), "two places in t000.txt");
                assertEquals(tr("project.map.links.more", files - MapCardLinks.MAX_CARDS), set.chip.getText());
                assertTrue(h.last().startsWith("Matched by name. "), h.last());
                assertTrue(h.last().endsWith(tr("project.map.links.partial")), "it says it stopped short");
                ProjectMapPreview t0 = h.cards().codeCard(first);
                int line = t0.editor()
                        .offsetToPosition(
                                t0.editor().getSelection().getStart(),
                                org.fxmisc.richtext.model.TwoDimensional.Bias.Forward)
                        .getMajor();
                assertEquals(2, line, "a file card opens at its first place");
                assertTrue(h.links().isResult(t0));
                assertFalse(h.links().isResult(readme));

                h.cards().flushConnectors();
                h.cards().drawConnectors();
                assertTrue(set.chip.isVisible());
                assertTrue(set.chip.getLayoutY() > readme.getLayoutY(), "no room above the card: under it");
                assertNotNull(set.bounds());
                assertTrue(set.contains(set.chip));
                assertFalse(set.contains(readme));
                assertEquals(0, set.reserved(CardAttachment.Side.LEFT));
                assertTrue(set.card() == readme);
                set.raise();

                readme.setVisible(false);
                set.drawConnectors(new Canvas(10, 10).getGraphicsContext2D());
                assertFalse(set.chip.isVisible(), "hidden with its card");
                assertNull(set.bounds());
                assertTrue(set.labelsForTest().stream().noneMatch(Label::isVisible));
                assertTrue(set.drawnForTest().values().stream().allMatch(line2 -> line2.length == 0));
                readme.setVisible(true);
                set.chip.fire();
            });
            // The chip's list: every place, by file, each with its line's text once read.
            waitForFx(() -> set.list != null
                    && set.list.places().stream()
                            .anyMatch(place -> !place.text().isEmpty()));
            FxTestSupport.runOnFx(() -> {
                MapResultsList list = set.list;
                assertNotNull(list);
                assertEquals(files + 1, list.places().size(), "every place, the card asked from left out");
                assertTrue(h.links().allAttachments().contains(list), "placement keeps clear of it");
                List<String> rows = list.rowsForTest();
                assertEquals(files * 2 + 1, rows.size(), "a row per file and per place: " + rows.subList(0, 4));
                list.filter().setText("t006");
                assertEquals(2, list.rowsForTest().size(), "the filter keeps t006.txt's place");
                assertTrue(
                        list.rowsForTest().getFirst().contains("many/t006.txt"),
                        list.rowsForTest().toString());
                list.tree()
                        .getSelectionModel()
                        .select(list.tree()
                                .getRoot()
                                .getChildren()
                                .getFirst()
                                .getChildren()
                                .getFirst());
                assertTrue(list.openSelected());
                assertEquals(tr("project.map.links.more", files - MapCardLinks.MAX_CARDS - 1), set.chip.getText());
                assertEquals(MapCardLinks.MAX_CARDS + 1, set.labelsForTest().size());

                // Asked again: the old set's edges go, its cards stay.
                h.cards().showLinks(readme, MapCardLinks.Kind.REFERENCES, 0, 6);
                h.source().answer(1, answer(List.of(new MapCardLinks.Hit(first, 2, 11, 1, null)), false, null));
                MapCardLinks.EdgeSet again = h.links().setsForTest(readme).get(MapCardLinks.Kind.REFERENCES);
                assertTrue(again != set);
                assertNull(set.list, "the old answer's list goes with it");
                assertEquals(1, again.labelsForTest().size(), "the open card is reused");
                assertFalse(set.chip.isVisible() && set.chip.getParent() != null, "the old chip is gone");
                assertTrue(h.cards().codeCard(project.resolve("many/t001.txt")) != null, "its cards stay");
                h.cards().closeAll();
                assertTrue(h.links().allAttachments().isEmpty());
            });
        }
    }

    @Test
    void theListFiltersOpensByKeyAndGivesWayToATree() throws Exception {
        Path project = project(9);
        Path testFile = Files.writeString(
                Files.createDirectories(project.resolve("src/test")).resolve("GoTest.txt"), "one\ngo here\n");
        try (Harness h = open(project)) {
            Path readmeFile = project.resolve("README.md");
            ProjectMapPreview readme = card(h, readmeFile);
            FxTestSupport.runOnFx(() -> {
                h.cards().showLinks(readme, MapCardLinks.Kind.REFERENCES, 0, 6);
                List<MapCardLinks.Hit> hits = new ArrayList<>();
                for (int i = 0; i < 9; i++) {
                    hits.add(new MapCardLinks.Hit(project.resolve(String.format("many/t%03d.txt", i)), 2, 11, 1, null));
                }
                hits.add(new MapCardLinks.Hit(testFile, 1, 0, 1, null));
                h.source().answer(0, answer(hits, false, null));
            });
            waitForFx(() -> {
                MapCardLinks.EdgeSet set = h.links().setsForTest(readme).get(MapCardLinks.Kind.REFERENCES);
                return set != null && set.labelsForTest().size() == MapCardLinks.MAX_CARDS;
            });
            MapCardLinks.EdgeSet set =
                    FxTestSupport.callOnFx(() -> h.links().setsForTest(readme).get(MapCardLinks.Kind.REFERENCES));
            FxTestSupport.runOnFx(set.chip::fire);
            waitForFx(() -> set.list != null
                    && set.list.places().stream()
                            .allMatch(place -> !place.text().isEmpty()));
            FxTestSupport.drainFx(); // its cells drawn
            FxTestSupport.runOnFx(() -> {
                MapResultsList list = set.list;
                assertEquals(10, list.places().size());
                assertEquals(10, list.groups().size());
                assertTrue(list.places().getLast().file().equals(testFile), "a test's place last");
                assertEquals(tr("project.map.places.title", "go"), list.titleText());

                // Tests cleared, then a filter that keeps nothing, then all again.
                list.tests().setSelected(false);
                assertTrue(list.rowsForTest().stream().noneMatch(row -> row.contains("GoTest")));
                list.filter().setText("no such place");
                assertEquals(List.of(tr("project.map.places.none")), list.rowsForTest());
                list.filter().setText("");
                list.tests().setSelected(true);
                assertEquals(20, list.rowsForTest().size());

                // Down in the filter: the first place, the keyboard in the list.
                list.tree().getSelectionModel().clearSelection();
                assertFalse(list.openSelected(), "nothing selected");
                list.filter().fireEvent(key(KeyCode.DOWN));
                MapResultsList.Row row =
                        list.tree().getSelectionModel().getSelectedItem().getValue();
                assertNotNull(row.place());
                assertEquals(project.resolve("many/t000.txt"), row.place().file());
                list.filter().fireEvent(key(KeyCode.ENTER)); // a place selected already: it stays
                assertEquals(
                        row, list.tree().getSelectionModel().getSelectedItem().getValue());
                // Enter on a place whose card is open: that card, at the place; no card more.
                list.tree().fireEvent(key(KeyCode.ENTER));
                assertEquals(MapCardLinks.MAX_CARDS, set.labelsForTest().size());
                assertEquals(tr("project.map.links.more", 4), set.chip.getText());

                // A filter that keeps the selected place keeps it selected.
                list.filter().setText("t00");
                assertEquals(
                        row.place(),
                        list.tree()
                                .getSelectionModel()
                                .getSelectedItem()
                                .getValue()
                                .place());
                list.filter().setText("");

                // The column's sides and bounds.
                for (CardAttachment.Side side : CardAttachment.Side.values()) {
                    assertEquals(MapSideColumn.WIDTH * readme.mapScale(), list.boundsAt(side)[2], 0.01);
                }
                assertTrue(list.contains(list.tree()));
                assertFalse(list.contains(readme));
                assertEquals(readme, list.card());
                assertNotNull(list.bounds());
                list.raise();
                list.show(false); // shown again: it stays one column
                readme.setVisible(false);
                assertNull(list.bounds(), "hidden with its card");
                readme.setVisible(true);

                // Escape closes it; the chip opens it again; a hierarchy tree takes its place.
                list.tree().fireEvent(key(KeyCode.ESCAPE));
                assertNull(set.list);
                assertTrue(list.disposed());
                assertNull(list.bounds());
                assertEquals(0, list.reserved(list.side()));
                set.chip.fire();
                MapResultsList again = set.list;
                assertNotNull(again);
                h.links()
                        .showHierarchy(
                                readme,
                                new MapCardLinks.Request(MapCardLinks.Kind.CALLERS, readmeFile, 0, 6, "go", null));
                assertNull(set.list, "one column beside a card");
                assertTrue(again.disposed());
                assertNotNull(h.links().hierarchy(readme));
                set.chip.fire();
                assertNull(h.links().hierarchy(readme), "and the list takes the tree's");
                assertNotNull(set.list);
            });
            FxTestSupport.drainFx();
            // Its rows laid out (on a loaded machine a pulse or two later).
            waitForFx(() -> set.list.tree().lookupAll(".tree-cell").stream()
                    .anyMatch(node -> node instanceof javafx.scene.control.TreeCell<?> found
                            && found.getItem() instanceof MapResultsList.Row row
                            && row.place() != null));
            FxTestSupport.runOnFx(() -> {
                // A double-click on a place opens it, as Enter does.
                MapResultsList list = set.list;
                @SuppressWarnings("unchecked")
                javafx.scene.control.TreeCell<MapResultsList.Row> cell = list.tree().lookupAll(".tree-cell").stream()
                        .map(node -> (javafx.scene.control.TreeCell<MapResultsList.Row>) node)
                        .filter(found ->
                                found.getItem() != null && found.getItem().place() != null)
                        .findFirst()
                        .orElseThrow();
                list.tree().getSelectionModel().select(cell.getTreeItem());
                cell.fireEvent(new javafx.scene.input.MouseEvent(
                        javafx.scene.input.MouseEvent.MOUSE_CLICKED,
                        0,
                        0,
                        0,
                        0,
                        javafx.scene.input.MouseButton.PRIMARY,
                        2,
                        false,
                        false,
                        false,
                        false,
                        true,
                        false,
                        false,
                        false,
                        false,
                        false,
                        null));
                assertEquals(MapCardLinks.MAX_CARDS, set.labelsForTest().size());
                // Before its places are known it says it is reading them.
                list.show(List.of(), List.of(), Runnable::run);
                assertEquals(List.of(tr("project.map.places.reading")), list.rowsForTest());
            });
        }
    }

    private static javafx.scene.input.KeyEvent key(KeyCode code) {
        return new javafx.scene.input.KeyEvent(
                javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false);
    }

    @Test
    void aQuestionWaitingForOutlinesGoesWithItsCardAndADeclarationIsNotItsOwnResult() throws Exception {
        Path project = project(0);
        Path src = project.resolve("src");
        for (int i = 0; i < MapCardLinks.MAX_OUTLINED_FILES + 2; i++) {
            Files.writeString(
                    src.resolve("U" + i + ".java"), "class U" + i + " {\n  void run() {\n    helper();\n  }\n}\n");
        }
        try (Harness h = open(project)) {
            Path app = src.resolve("App.java");
            ProjectMapStructure structure = FxTestSupport.field(h.view(), "structure");
            FxTestSupport.runOnFx(() -> structure.ensure(app));
            waitForFx(() -> structure.view(app).problem() == ProjectMapStructure.Problem.NONE);
            Path helper =
                    FxTestSupport.callOnFx(() -> declaration(structure.view(app).roots(), "helper"));
            Path refresh =
                    FxTestSupport.callOnFx(() -> declaration(structure.view(app).roots(), "refresh"));
            assertNotNull(helper);
            assertNotNull(refresh);
            ProjectMapPreview card = FxTestSupport.callOnFx(() -> h.cards().viewsOpenCard(helper));
            waitForFx(() -> card.editor().getLength() > 0 && card.range() != null);

            // A question whose files' outlines are still loading waits; its card closing drops it.
            FxTestSupport.runOnFx(() -> {
                h.cards().showLinks(card, MapCardLinks.Kind.CALLERS, 0, 0);
                MapCardLinks.Request asked = h.source().asked.getFirst();
                assertEquals("helper", asked.name(), "a declaration card asks about its declaration");
                assertEquals(-1, asked.column());
                List<MapCardLinks.Hit> hits = new ArrayList<>();
                for (int i = 0; i < MapCardLinks.MAX_OUTLINED_FILES + 2; i++) {
                    hits.add(new MapCardLinks.Hit(src.resolve("U" + i + ".java"), 2, 4, 1, null));
                }
                h.source().answer(0, answer(hits, false, null));
                assertTrue(
                        h.links().outlineFiles().size() <= MapCardLinks.MAX_OUTLINED_FILES,
                        "only the first twelve files' outlines are waited for");
                assertFalse(h.links().outlineFiles().isEmpty(), "they are loading");
                h.links().cardClosed(card);
                assertTrue(h.links().outlineFiles().isEmpty(), "the question went with its card");
                h.links().outlineChanged(src.resolve("U0.java"));
            });

            // Its own line (a recursive call) is no result; refresh(), which calls it, is.
            ProjectMapPreview again = FxTestSupport.callOnFx(() -> h.cards().viewsOpenCard(helper));
            waitForFx(() -> again.editor().getLength() > 0 && again.range() != null);
            FxTestSupport.runOnFx(() -> {
                h.cards().showLinks(again, MapCardLinks.Kind.CALLERS, 0, 0);
                h.source()
                        .answer(
                                1,
                                new MapCardLinks.Answer(
                                        List.of(
                                                new MapCardLinks.Hit(app, 8, 8, 1, "helper"),
                                                new MapCardLinks.Hit(app, 4, 8, 1, "refresh")),
                                        MapCardLinks.Via.SERVER,
                                        false,
                                        null,
                                        null));
            });
            waitForFx(() -> h.cards().codeCard(refresh) != null);
            FxTestSupport.runOnFx(() -> {
                MapCardLinks.EdgeSet set = h.links().setsForTest(again).get(MapCardLinks.Kind.CALLERS);
                assertEquals(
                        List.of("calls"),
                        set.labelsForTest().stream().map(Label::getText).toList());
                assertTrue(
                        h.said().contains(tr("project.map.links.foundCallersChip", 1, "helper", 1, 0)),
                        h.said().toString());
            });
        }
    }

    // --- the language server side of ProjectMapLinkSearch ------------------------------------------------------

    @Test
    void aServerWithoutTheCapabilityOrWithNothingToSayIsAnsweredForAndPlacesWithNoFileAreLeftOut() throws Exception {
        Path root = Files.createTempDirectory(temp, "lsp").toRealPath();
        Path app = Files.writeString(root.resolve("App.java"), "class App {\n  int add(int a) { return a; }\n}\n");
        Path use = Files.writeString(root.resolve("Use.java"), "class Use {\n  int x = new App().add(1);\n}\n");
        Path script = Files.writeString(root.resolve("tool.py"), "def add():\n  pass\nadd()\n");
        Path open = Files.writeString(root.resolve("Open.java"), "class Open {}\n");
        ServerCapabilities capabilities = new ServerCapabilities();
        capabilities.setReferencesProvider(Either.forLeft(true)); // references, but no call hierarchy
        LspManager manager = new LspManager((f, d) -> {}, (t, m) -> {});
        try {
            List<FakeLanguageServer> fakes = LspTestHooks.useFakeSessions(manager, capabilities);
            manager.configure(true, Map.of("java", "jdtls"));
            manager.openDocument(open, root, "java", "class Open {}\n");
            FakeLanguageServer fake = fakes.getFirst();
            ProjectMapLinkSearch search = new ProjectMapLinkSearch(
                    manager,
                    index(new ProjectFileList.Snapshot(
                            List.of("App.java", "Open.java", "Use.java", "tool.py"), List.of(), false)),
                    Runnable::run,
                    Runnable::run,
                    () -> 0L);

            MapCardLinks.Answer callers = ask(search, root, MapCardLinks.Kind.CALLERS, app, 1, 6);
            assertEquals(MapCardLinks.Via.NAME, callers.via(), "no call hierarchy: matched by name");
            assertEquals(tr("project.map.links.serverCannot"), callers.note());
            assertEquals(
                    List.of(app, use),
                    callers.hits().stream().map(MapCardLinks.Hit::file).toList(),
                    "every add( in Java files: the declaration too, which the grouping leaves out");

            fake.referenceResponse = List.of(
                    new Location(LspTestHooks.uri(use), range(1, 20)),
                    new Location("jdt://contents/rt.jar/java.lang/Object.class", range(0, 0)));
            MapCardLinks.Answer references = ask(search, root, MapCardLinks.Kind.REFERENCES, app, 1, 6);
            assertEquals(MapCardLinks.Via.SERVER, references.via());
            assertEquals(
                    List.of(use),
                    references.hits().stream().map(MapCardLinks.Hit::file).toList(),
                    "a place in a library has no file on the map");

            fake.failEverything = true;
            MapCardLinks.Answer failed = ask(search, root, MapCardLinks.Kind.REFERENCES, app, 1, 6);
            assertEquals(MapCardLinks.Via.NAME, failed.via(), "a server that failed: matched by name (review 4, C6)");
            assertEquals(
                    List.of(app, use),
                    failed.hits().stream().map(MapCardLinks.Hit::file).toList());
            assertTrue(failed.note().startsWith("The language server did not answer ("), failed.note());
            assertTrue(failed.note().endsWith("so the name was matched."), failed.note());
            fake.failEverything = false;

            MapCardLinks.Answer python = ask(search, root, MapCardLinks.Kind.CALLERS, script, 0, 4);
            assertEquals(MapCardLinks.Via.NAME, python.via(), "no server for Python: by name");
            assertEquals(tr("project.map.links.noServer"), python.note());
            assertEquals(2, python.hits().size(), "def add() and the call: only the Python file is read");
        } finally {
            manager.shutdownAll();
        }

        ServerCapabilities hierarchy = new ServerCapabilities();
        hierarchy.setCallHierarchyProvider(Either.forLeft(true));
        LspManager second = new LspManager((f, d) -> {}, (t, m) -> {});
        try {
            List<FakeLanguageServer> fakes = LspTestHooks.useFakeSessions(second, hierarchy);
            second.configure(true, Map.of("java", "jdtls"));
            second.openDocument(open, root, "java", "class Open {}\n");
            FakeLanguageServer fake = fakes.getFirst();
            ProjectMapLinkSearch search =
                    new ProjectMapLinkSearch(second, index(null), Runnable::run, Runnable::run, () -> 0L);

            MapCardLinks.Answer none = ask(search, root, MapCardLinks.Kind.CALLERS, app, 1, 6);
            assertEquals(MapCardLinks.Via.SERVER, none.via());
            assertTrue(none.hits().isEmpty(), "nothing to prepare: no callers");

            fake.callHierarchyResponse = List.of(item("add", LspTestHooks.uri(app), 1));
            fake.incomingCallsResponse = List.of(
                    new CallHierarchyIncomingCall(item("main", LspTestHooks.uri(use), 1), List.of(range(1, 20))),
                    new CallHierarchyIncomingCall(
                            item("lib", "jdt://contents/lib.jar/Lib.class", 0), List.of(range(0, 0))));
            MapCardLinks.Answer some = ask(search, root, MapCardLinks.Kind.CALLERS, app, 1, 6);
            assertEquals(
                    List.of("main"),
                    some.hits().stream().map(MapCardLinks.Hit::name).toList(),
                    "a library's caller has no file on the map");

            MapCardLinks.Answer noReferences = ask(search, root, MapCardLinks.Kind.REFERENCES, app, 1, 6);
            assertEquals(MapCardLinks.Via.NAME, noReferences.via(), "no references capability: by name");
            assertEquals(tr("project.map.links.serverCannot"), noReferences.note());
            assertTrue(noReferences.hits().isEmpty(), "and no file list to match in");
        } finally {
            second.shutdownAll();
        }
    }

    // --- helpers ----------------------------------------------------------------------------------------------

    private static MapCardLinks.Answer ask(
            ProjectMapLinkSearch search, Path root, MapCardLinks.Kind kind, Path file, int line, int column)
            throws Exception {
        List<MapCardLinks.Answer> answers = new CopyOnWriteArrayList<>();
        String text = Files.readString(file);
        FxTestSupport.runOnFx(
                () -> search.find(root, new MapCardLinks.Request(kind, file, line, column, null, text), answers::add));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (answers.isEmpty() && System.nanoTime() < deadline) {
            FxTestSupport.drainFx();
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertEquals(1, answers.size(), "one answer");
        return answers.getFirst();
    }

    private static ProjectIndex index(ProjectFileList.@Nullable Snapshot snapshot) {
        return new ProjectIndex() {
            @Override
            public ProjectMapSearchResult search(
                    Path at,
                    String query,
                    Kinds kinds,
                    int max,
                    Freshness freshness,
                    java.util.function.BooleanSupplier cancelled) {
                return ProjectMapSearchResult.EMPTY;
            }

            @Override
            public ProjectMapSearchResult searchListed(Path at, String query, Kinds kinds, int max) {
                return ProjectMapSearchResult.EMPTY;
            }

            @Override
            public void whenListed(Path at, Runnable then) {
                then.run();
            }

            @Override
            public ProjectFileList.@Nullable Snapshot snapshot(Path at) {
                return snapshot;
            }
        };
    }

    private static CallHierarchyItem item(String name, String uri, int line) {
        CallHierarchyItem item = new CallHierarchyItem();
        item.setName(name);
        item.setKind(SymbolKind.Method);
        item.setUri(uri);
        item.setRange(new Range(new Position(line, 0), new Position(line, 30)));
        item.setSelectionRange(range(line, 6));
        return item;
    }

    private static Range range(int line, int column) {
        return new Range(new Position(line, column), new Position(line, column + 3));
    }

    private static @Nullable Path declaration(List<ProjectMapStructure.Node> nodes, String name) {
        for (ProjectMapStructure.Node node : nodes) {
            if (node.symbol().label().startsWith(name)) {
                return node.path();
            }
            Path found = declaration(node.children(), name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static void waitForFx(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (FxTestSupport.callOnFx(condition::getAsBoolean)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("timed out");
    }
}
