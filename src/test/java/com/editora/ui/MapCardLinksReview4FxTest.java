package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.command.Command;
import com.editora.command.CommandRegistry;
import com.editora.command.KeyDispatcher;
import com.editora.command.KeymapManager;
import com.editora.lsp.FakeLanguageServer;
import com.editora.lsp.LspManager;
import com.editora.lsp.LspTestHooks;
import com.editora.search.ProjectFileList;
import com.editora.ui.ProjectMapView.FlowDirection;
import org.eclipse.lsp4j.ServerCapabilities;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Project Map review 4, the links group: a result card opens at the call (C2), the "+N more" chip stays above the
 * result cards (C3), pins never make a question lose places (C4), stepping a card drops what no longer applies (C5),
 * result cards close with their source card (C11), pinned cards survive every automatic close (D4, position 3), F3
 * in a card's find field is the field's (C10), and on the server side a failed call hierarchy falls back to the name
 * (C6) while overlapping loans of a file keep it open until the last ends (C13).
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MapCardLinksReview4FxTest {

    @TempDir
    Path temp;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        com.editora.i18n.Messages.init("en");
    }

    private static final String APP = String.join(
            "\n",
            "package demo;",
            "",
            "public class App {",
            "    int add(int a, int b) {",
            "        return a + b;",
            "    }",
            "",
            "    int sub(int a, int b) {",
            "        return a - b;",
            "    }",
            "}",
            "");

    /** The line (0-based) of each caller's call: its method's last line, past a screenful. */
    private static final int CALL_LINE = 34;

    private static String use(int index) {
        StringBuilder text = new StringBuilder("package demo;\n\nclass Use" + index + " {\n");
        text.append("    void run").append(index).append("(App app) {\n");
        text.append("        int x = 0;\n");
        for (int line = 5; line < CALL_LINE; line++) {
            text.append("        x++;\n");
        }
        text.append("        app.add(").append(index).append(", x);\n"); // line 34
        text.append("    }\n\n");
        text.append("    void after").append(index).append("() {\n        return;\n    }\n}\n");
        return text.toString();
    }

    /** A source whose answers the test gives, in the order the questions came. */
    private static final class Held implements MapCardLinks.Source {
        final List<MapCardLinks.Request> asked = new ArrayList<>();
        final List<Consumer<MapCardLinks.Answer>> pending = new ArrayList<>();

        @Override
        public void find(Path root, MapCardLinks.Request request, Consumer<MapCardLinks.Answer> answer) {
            asked.add(request);
            pending.add(answer);
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

        ProjectMapStructure structure() {
            return FxTestSupport.field(view, "structure");
        }

        @Override
        public void close() throws Exception {
            FxTestSupport.runOnFx(() -> {
                view.dispose();
                stage.hide();
            });
        }
    }

    private Path project(int callers) throws Exception {
        Path project = Files.createTempDirectory(temp, "project").toRealPath();
        Path src = Files.createDirectory(project.resolve("src"));
        Files.writeString(src.resolve("App.java"), APP);
        Path use = Files.createDirectory(src.resolve("use"));
        for (int i = 0; i < callers; i++) {
            Files.writeString(use.resolve("Use" + i + ".java"), use(i));
        }
        Path notes = Files.createDirectory(project.resolve("notes"));
        for (int i = 0; i < 8; i++) {
            Files.writeString(notes.resolve("n" + i + ".txt"), "note " + i + "\n");
        }
        Files.writeString(project.resolve("README.md"), "hello\n");
        return project;
    }

    private Harness open(Path project) throws Exception {
        List<String> said = new CopyOnWriteArrayList<>();
        Held source = new Held();
        Harness h = FxTestSupport.callOnFx(() -> {
            ProjectMapView view = new ProjectMapView(path -> {}, path -> false, path -> false);
            view.setOnStatus(said::add);
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(view), 1400, 900));
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

    /** The declaration named {@code name} in {@code file}'s outline, loaded first. */
    private static Path declaration(Harness h, Path file, String name) throws Exception {
        FxTestSupport.runOnFx(() -> h.structure().ensure(file));
        waitForFx(() -> h.structure().view(file).problem() == ProjectMapStructure.Problem.NONE);
        Path found = FxTestSupport.callOnFx(() -> find(h.structure().view(file).roots(), name));
        assertNotNull(found, name);
        return found;
    }

    private static @Nullable Path find(List<ProjectMapStructure.Node> nodes, String name) {
        for (ProjectMapStructure.Node node : nodes) {
            if (ProjectMapView.followName(node.symbol().label()).equals(name)) {
                return node.path();
            }
            Path deeper = find(node.children(), name);
            if (deeper != null) {
                return deeper;
            }
        }
        return null;
    }

    /** The source card: App.add's declaration card, its text shown. */
    private static ProjectMapPreview addCard(Harness h) throws Exception {
        Path add = declaration(h, h.project().resolve("src/App.java"), "add");
        ProjectMapPreview card = FxTestSupport.callOnFx(() -> h.cards().viewsOpenCard(add));
        waitForFx(() -> card.editor().getLength() > 0 && card.range() != null && card.getWidth() > 0);
        return card;
    }

    private static ProjectMapPreview fileCard(Harness h, Path file) throws Exception {
        ProjectMapPreview card = FxTestSupport.callOnFx(() -> h.cards().openFileCard(file));
        waitForFx(() -> card.editor().getLength() > 0 && card.getWidth() > 0);
        return card;
    }

    /** Asks the callers of {@code source} and answers with a call in each of the first {@code callers} Use files. */
    private static void askAndAnswer(Harness h, ProjectMapPreview source, int callers) throws Exception {
        FxTestSupport.runOnFx(() -> {
            h.cards().showLinks(source, MapCardLinks.Kind.CALLERS, 0, 0);
            List<MapCardLinks.Hit> hits = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                hits.add(
                        new MapCardLinks.Hit(h.project().resolve("src/use/Use" + i + ".java"), CALL_LINE, 12, 1, null));
            }
            h.source()
                    .pending
                    .getLast()
                    .accept(new MapCardLinks.Answer(hits, MapCardLinks.Via.NAME, false, null, "add"));
        });
        waitForFx(() -> h.links().setsForTest(source).get(MapCardLinks.Kind.CALLERS) != null);
    }

    private static MapCardLinks.EdgeSet set(Harness h, ProjectMapPreview source) throws Exception {
        return FxTestSupport.callOnFx(() -> h.links().setsForTest(source).get(MapCardLinks.Kind.CALLERS));
    }

    private static @Nullable ProjectMapPreview runCard(Harness h, int index) throws Exception {
        Path run = declaration(h, h.project().resolve("src/use/Use" + index + ".java"), "run" + index);
        return FxTestSupport.callOnFx(() -> h.cards().codeCard(run));
    }

    // --- C2, C3, C11, D4 ------------------------------------------------------------------------------------

    @Test
    void resultCardsOpenAtTheCallUnderTheirChipCloseWithTheirSourceAndPinsAreKept() throws Exception {
        Path project = project(8);
        try (Harness h = open(project)) {
            ProjectMapPreview source = addCard(h);
            askAndAnswer(h, source, 8);
            MapCardLinks.EdgeSet set = set(h, source);
            waitForFx(() -> set.labelsForTest().size() == MapCardLinks.MAX_CARDS);

            // C2: a declaration card opens at the call, the name selected as find selects a match.
            ProjectMapPreview first = runCard(h, 0);
            assertNotNull(first);
            waitForFx(() -> first.editor().getSelection().getLength() > 0);
            FxTestSupport.runOnFx(() -> {
                assertEquals("add", first.editor().getSelectedText(), "the call's name is selected");
                int paragraph = first.editor()
                        .offsetToPosition(
                                first.editor().getSelection().getStart(),
                                org.fxmisc.richtext.model.TwoDimensional.Bias.Forward)
                        .getMajor();
                assertEquals(CALL_LINE, paragraph + first.range().startLine(), "on the call's line");
            });

            // C3: the chip is above the result cards, and stays above one that rises.
            Pane host = FxTestSupport.callOnFx(() -> FxTestSupport.field(h.view(), "canvasHost"));
            FxTestSupport.runOnFx(() -> {
                assertEquals(2, set.more.size());
                int chip = host.getChildren().indexOf(set.chip);
                assertTrue(chip >= 0);
                for (ProjectMapPreview card : set.drawnForTest().keySet()) {
                    assertTrue(host.getChildren().indexOf(card) < chip, "the chip is over every result card");
                }
                FxTestSupport.call(h.cards(), "touchCard", new Class<?>[] {Region.class, boolean.class}, first, true);
                assertTrue(
                        host.getChildren().indexOf(first) < host.getChildren().indexOf(set.chip),
                        "a result card that rises does not cover the chip");
            });

            // D4: a column or Collapse All under the result cards keeps them while their source card is open.
            ProjectMapPreview second = runCard(h, 1);
            assertNotNull(second);
            ProjectMapPreview readme = fileCard(h, project.resolve("README.md")); // the eighth card
            FxTestSupport.runOnFx(() -> {
                second.setPinned(true);
                readme.setPinned(true);
                h.cards().closeUnder(project.resolve("src/use"));
                assertTrue(h.cards().isOpenForTest(first), "a result card stays while its source does");
                assertEquals(MapCardLinks.MAX_CARDS, set.labelsForTest().size());

                // C11: the source closing (here with its column) takes the cards it opened, but not a pinned one.
                h.cards().closeUnder(project.resolve("src"));
                assertFalse(h.cards().isOpenForTest(source));
                assertFalse(h.cards().isOpenForTest(first), "its result cards close with it");
                assertTrue(h.cards().isOpenForTest(second), "a pinned result card stays");
            });

            // Position 3: a pinned card survives a column close, Collapse All and Escape on the map.
            ProjectMapPreview note = fileCard(h, project.resolve("notes/n0.txt"));
            FxTestSupport.runOnFx(() -> {
                h.cards().closeUnder(project);
                assertTrue(h.cards().isOpenForTest(readme), "pinned: kept when its folder closes");
                assertTrue(h.cards().isOpenForTest(second));
                assertFalse(h.cards().isOpenForTest(note), "an unpinned card closes with its folder");
                assertFalse(h.cards().closeVisible(), "Escape on the map leaves pinned cards alone");
                assertTrue(h.cards().isOpenForTest(readme));
            });

            // …and the orphan check, when a folder above it stops being expanded.
            ProjectMapPreview other = fileCard(h, project.resolve("notes/n1.txt"));
            FxTestSupport.runOnFx(() -> {
                setField(h.cards(), "cardsCheckedAgainst", new HashSet<Path>());
                FxTestSupport.call(h.cards(), "closeOrphanedCards", new Class<?>[0]);
                assertFalse(h.cards().isOpenForTest(other), "an unpinned card off the map closes");
                assertTrue(h.cards().isOpenForTest(readme), "a pinned one stays");
                assertTrue(h.cards().isOpenForTest(second));
            });
        }
    }

    // --- C4 -------------------------------------------------------------------------------------------------

    @Test
    void pinnedCardsLeaveTheRestOnTheChipAndTheStatusSaysSoInsteadOfEvictingTheFirstResults() throws Exception {
        Path project = project(8);
        try (Harness h = open(project)) {
            for (int i = 0; i < 2; i++) {
                ProjectMapPreview pinned = fileCard(h, project.resolve("notes/n" + i + ".txt"));
                FxTestSupport.runOnFx(() -> pinned.setPinned(true));
            }
            ProjectMapPreview source = addCard(h);
            askAndAnswer(h, source, 8);
            MapCardLinks.EdgeSet set = set(h, source);
            int fit = MapCards.MAX_OPEN_PREVIEWS - 3; // two pinned cards and the source
            waitForFx(() -> set.labelsForTest().size() == fit);
            FxTestSupport.drainFx();
            ProjectMapPreview first = runCard(h, 0);
            FxTestSupport.runOnFx(() -> {
                assertNotNull(first, "the first result is not evicted by a later one");
                assertEquals(fit, set.labelsForTest().size());
                assertEquals(8 - fit, set.more.size(), "the places that got no card are on the chip");
                assertEquals(tr("project.map.links.more", 8 - fit), set.chip.getText());
                assertEquals(
                        tr("project.map.links.foundCallersChip", 8, "add", 8, 8 - fit) + " "
                                + tr("project.map.links.noRoom"),
                        h.last(),
                        "the status says why");
            });

            // Picking a place without a card in the chip's list, with still no room, keeps it on the chip.
            FxTestSupport.runOnFx(() -> set.chip.fire());
            waitForFx(() -> set.list != null && !set.list.places().isEmpty());
            FxTestSupport.runOnFx(() -> {
                MapResultsList list = set.list;
                MapResultsList.Place waiting = list.places().stream()
                        .filter(place -> set.more.contains(place.group()))
                        .findFirst()
                        .orElseThrow();
                assertFalse(list.openPlaceForTest(waiting), "no room");
                assertEquals(8 - fit, set.more.size(), "no room: it stays listed");
                assertEquals(tr("project.map.links.noRoom"), h.last());
            });
        }
    }

    // --- C5 -------------------------------------------------------------------------------------------------

    @Test
    void steppingAResultCardDropsItsEdgeAndSteppingTheSourceEndsItsQuestions() throws Exception {
        Path project = project(3);
        try (Harness h = open(project)) {
            ProjectMapPreview source = addCard(h);
            askAndAnswer(h, source, 3);
            MapCardLinks.EdgeSet set = set(h, source);
            waitForFx(() -> set.labelsForTest().size() == 3);
            ProjectMapPreview first = runCard(h, 0);
            ProjectMapPreview second = runCard(h, 1);
            assertNotNull(first);
            assertNotNull(second);
            FxTestSupport.drainFx();
            FxTestSupport.runOnFx(() -> {
                assertTrue(first.getAccessibleText().contains("names"), first.getAccessibleText());
                h.cards().cardsStep(first, 1);
                assertTrue(
                        first.range().symbol().startsWith("after0"),
                        first.range().symbol());
                assertEquals(2, set.labelsForTest().size(), "a stepped result card has no edge any more");
                assertFalse(first.getAccessibleText().contains("names"), "nor its link text");
                assertTrue(h.links().isResult(first), "it is still that answer's card");
            });

            // The source stepping ends its question: its edges go, and the result cards it opened close.
            FxTestSupport.runOnFx(() -> {
                h.cards().cardsStep(source, 1);
                assertTrue(
                        source.range().symbol().startsWith("sub"),
                        source.range().symbol());
                assertTrue(h.links().setsForTest(source).isEmpty(), "no edges to sub");
                assertFalse(h.cards().isOpenForTest(first));
                assertFalse(h.cards().isOpenForTest(second));
            });

            // An answer still pending when the source steps opens nothing.
            FxTestSupport.runOnFx(() -> {
                h.cards().showLinks(source, MapCardLinks.Kind.CALLERS, 0, 0);
                h.cards().cardsStep(source, -1);
                h.source()
                        .pending
                        .getLast()
                        .accept(new MapCardLinks.Answer(
                                List.of(new MapCardLinks.Hit(
                                        project.resolve("src/use/Use2.java"), CALL_LINE, 12, 1, null)),
                                MapCardLinks.Via.NAME,
                                false,
                                null,
                                "sub"));
                assertTrue(h.links().setsForTest(source).isEmpty(), "the old declaration's answer is dropped");
            });
        }
    }

    // --- C10 ------------------------------------------------------------------------------------------------

    @Test
    void f3InACardsFindFieldStepsTheMatchesInsteadOfStartingAMacroInEmacs() throws Exception {
        Path project = project(1);
        try (Harness h = open(project)) {
            ProjectMapPreview card = fileCard(h, project.resolve("src/use/Use0.java"));
            List<String> ran = new ArrayList<>();
            KeymapManager keymap = new KeymapManager();
            keymap.loadNamed("emacs");
            assertEquals("macro.startRecording", keymap.commandFor("f3"));
            CommandRegistry registry = new CommandRegistry();
            registry.register(Command.of("macro.startRecording", "Record", () -> ran.add("macro")));
            KeyDispatcher dispatcher = new KeyDispatcher(registry, keymap, status -> {});
            FxTestSupport.runOnFx(() -> {
                assertTrue(card.runMarkerCommand("find.show"));
                card.findField().setText("x++");
                assertEquals(tr("project.map.preview.find.count", 1, 29), card.findCountText());
                KeyEvent press = new KeyEvent(
                        card.findField(),
                        card.findField(),
                        KeyEvent.KEY_PRESSED,
                        "",
                        "",
                        KeyCode.F3,
                        false,
                        false,
                        false,
                        false);
                FxTestSupport.call(dispatcher, "handle", new Class<?>[] {KeyEvent.class}, press);
                assertEquals(List.of(), ran, "F3 in the field is the field's, not the keymap's macro key");
                assertFalse(press.isConsumed());
                card.findField().fireEvent(press.copyFor(card.findField(), card.findField()));
                assertEquals(tr("project.map.preview.find.count", 2, 29), card.findCountText(), "F3 steps");
            });
        }
    }

    // --- C6, C13: the server side ---------------------------------------------------------------------------

    @Test
    void aFailedCallHierarchyIsMatchedByNameAndOverlappingLoansCloseTheFileOnlyAtTheLast() throws Exception {
        Path root = Files.createTempDirectory(temp, "lsp").toRealPath();
        String appText = "class App {\n  int add(int a) { return a; }\n}\n";
        Path app = Files.writeString(root.resolve("App.java"), appText);
        Path use = Files.writeString(root.resolve("Use.java"), "class Use {\n  int x = new App().add(1);\n}\n");
        Path open = Files.writeString(root.resolve("Open.java"), "class Open {}\n");
        ServerCapabilities capabilities = new ServerCapabilities();
        capabilities.setCallHierarchyProvider(Either.forLeft(true));
        capabilities.setReferencesProvider(Either.forLeft(true));
        LspManager manager = new LspManager((f, d) -> {}, (t, m) -> {});
        try {
            List<FakeLanguageServer> fakes = LspTestHooks.useFakeSessions(manager, capabilities);
            manager.configure(true, Map.of("java", "jdtls"));
            manager.openDocument(open, root, "java", "class Open {}\n");
            FakeLanguageServer fake = fakes.getFirst();
            String appUri = LspTestHooks.uri(app);

            // C13: two loans of one file; the first to end leaves it open on the server.
            Runnable[] loans = FxTestSupport.callOnFx(
                    () -> new Runnable[] {manager.lendDocument(app, appText), manager.lendDocument(app, appText)});
            waitFor(() ->
                    count(fake.opened.stream().map(p -> p.getTextDocument().getUri()), appUri) == 1);
            FxTestSupport.runOnFx(() -> loans[0].run());
            Thread.sleep(100);
            assertEquals(
                    0,
                    count(fake.closed.stream().map(p -> p.getTextDocument().getUri()), appUri),
                    "the second loan still holds it");
            assertTrue(FxTestSupport.callOnFx(() -> manager.runsFor(app)));
            FxTestSupport.runOnFx(() -> loans[1].run());
            waitFor(() ->
                    count(fake.closed.stream().map(p -> p.getTextDocument().getUri()), appUri) == 1);
            assertEquals(
                    1, count(fake.opened.stream().map(p -> p.getTextDocument().getUri()), appUri));

            // C6: a call hierarchy that fails says so to a caller that listens, and a question falls back to names.
            fake.failEverything = true;
            List<String> why = new CopyOnWriteArrayList<>();
            List<List<LspManager.HierarchyNode>> nodes = new CopyOnWriteArrayList<>();
            Runnable lent = FxTestSupport.callOnFx(() -> manager.lendDocument(app, appText));
            FxTestSupport.runOnFx(() -> manager.prepareCallHierarchy(app, 1, 6, nodes::add, why::add));
            waitFor(() -> !why.isEmpty() || !nodes.isEmpty());
            assertTrue(nodes.isEmpty(), "a failure is not an empty answer");
            FxTestSupport.runOnFx(lent);

            ProjectMapLinkSearch search = new ProjectMapLinkSearch(
                    manager,
                    index(new ProjectFileList.Snapshot(List.of("App.java", "Open.java", "Use.java"), List.of(), false)),
                    Runnable::run,
                    Runnable::run,
                    () -> 0L);
            List<MapCardLinks.Answer> answers = new CopyOnWriteArrayList<>();
            FxTestSupport.runOnFx(() -> search.find(
                    root, new MapCardLinks.Request(MapCardLinks.Kind.CALLERS, app, 1, 6, null, appText), answers::add));
            waitFor(() -> !answers.isEmpty());
            MapCardLinks.Answer callers = answers.getFirst();
            assertEquals(MapCardLinks.Via.NAME, callers.via(), "not \"no callers\": matched by name");
            assertEquals(
                    List.of(app, use),
                    callers.hits().stream().map(MapCardLinks.Hit::file).toList());
            assertTrue(callers.note().startsWith("The language server did not answer ("), callers.note());
            assertEquals("add", callers.name());
        } finally {
            manager.shutdownAll();
        }
    }

    // --- helpers ----------------------------------------------------------------------------------------------

    private static long count(java.util.stream.Stream<String> uris, String uri) {
        return uris.filter(uri::equals).count();
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

    private static void waitFor(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out");
            }
            FxTestSupport.drainFx();
            Thread.sleep(10);
        }
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
