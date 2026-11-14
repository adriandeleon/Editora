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

import javafx.scene.layout.Region;

import com.editora.lsp.FakeLanguageServer;
import com.editora.lsp.LspManager;
import com.editora.lsp.LspTestHooks;
import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.CodeLensOptions;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jspecify.annotations.Nullable;
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
 * Map code browser gap 11: a declaration row on the Map — in the Map's Structure column and a card's — says how many
 * times the declaration is used, as the editor's code lens says it: from the running server's references lens, else
 * its references counted; nothing without a server.
 */
@ExtendWith(DisposedPanels.class)
@Tag("fx")
class MapUsageCountsFxTest {

    private static final String APP = String.join(
            "\n",
            "package demo;",
            "",
            "public class App {",
            "    int add(int a, int b) {",
            "        return a + b;",
            "    }",
            "",
            "    int unused() {",
            "        return 0;",
            "    }",
            "}",
            "");

    @TempDir
    Path temp;

    private Path root;
    private @Nullable LspManager manager;

    @BeforeAll
    static void startToolkit() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @BeforeEach
    void makeRoot() throws Exception {
        root = Files.createDirectories(temp.resolve("project")).toRealPath();
    }

    @AfterEach
    void stopServers() {
        LspManager m = manager;
        if (m != null) {
            m.shutdownAll();
        }
    }

    // --- pure -------------------------------------------------------------------------------------------------

    @Test
    void aLensTitleGivesItsCountAndOnlyReferencesLensesCount() {
        assertEquals(12, MapUsageCounts.leadingNumber("12 references"));
        assertEquals(1, MapUsageCounts.leadingNumber(" 1 reference"));
        assertEquals(0, MapUsageCounts.leadingNumber("0 references"));
        assertNull(MapUsageCounts.leadingNumber("references"));
        assertNull(MapUsageCounts.leadingNumber(null));
        assertNull(MapUsageCounts.leadingNumber("12345678901 references"), "not a count");
        Map<Integer, Integer> counts = MapUsageCounts.fromLenses(List.of(
                new LspManager.CodeLensSpan(3, 8, "2 references", LspManager.CodeLensKind.REFERENCES),
                new LspManager.CodeLensSpan(3, 8, "5 references", LspManager.CodeLensKind.REFERENCES),
                new LspManager.CodeLensSpan(2, 13, "3 implementations", LspManager.CodeLensKind.IMPLEMENTATIONS),
                new LspManager.CodeLensSpan(7, 8, "Run", LspManager.CodeLensKind.REFERENCES)));
        assertEquals(Map.of(3, 2), counts, "the first lens of a line; implementations and titles without a number out");
        assertEquals("no uses", MapUsageCounts.text(0));
        assertEquals("1 use", MapUsageCounts.text(1));
        assertEquals("1,200 uses".replace(",", ""), MapUsageCounts.text(1200).replace(",", ""));
    }

    @Test
    void anOutlinesDeclarationsAreListedDepthFirstByTheirNames() {
        Path file = root.resolve("App.java");
        ProjectMapStructure.Node add = node(file, "add(int a, int b)", 3);
        ProjectMapStructure.Node app =
                new ProjectMapStructure.Node(file.resolve("App"), symbol(file, "App", 2), List.of(add));
        ProjectMapStructure.Node blank = node(file, "  ", 9);
        assertEquals(
                List.of(new MapUsageCounts.Declaration(2, "App"), new MapUsageCounts.Declaration(3, "add")),
                MapUsageCounts.declarations(List.of(app, blank)));
    }

    // --- the counts' own life ------------------------------------------------------------------------------------

    @Test
    void countsAreAskedOnceTheOutlineSettlesAndAnOlderAnswerOrAFileNoLongerShownIsDropped() throws Exception {
        Path file = root.resolve("App.java");
        List<Consumer<@Nullable Map<Integer, Integer>>> asked = new CopyOnWriteArrayList<>();
        List<String> changed = new CopyOnWriteArrayList<>();
        java.util.Set<Path> shown = java.util.concurrent.ConcurrentHashMap.newKeySet();
        shown.add(file);
        MapCardLinks.Source source = new MapCardLinks.Source() {
            @Override
            public void find(Path at, MapCardLinks.Request request, Consumer<MapCardLinks.Answer> answer) {}

            @Override
            public void usages(
                    Path f,
                    List<MapUsageCounts.Declaration> declarations,
                    Consumer<@Nullable Map<Integer, Integer>> answer) {
                asked.add(answer);
            }
        };
        MapUsageCounts[] made = new MapUsageCounts[1];
        List<ProjectMapStructure.Node> outline = List.of(node(file, "add()", 3));
        FxTestSupport.runOnFx(() -> {
            made[0] = new MapUsageCounts(() -> source, shown::contains, () -> changed.add("changed"));
            made[0].outlineChanged(file, outline);
            made[0].outlineChanged(file, outline); // typed again: one ask, a moment after the last
            assertTrue(asked.isEmpty(), "not at once");
        });
        MapUsageCounts counts = made[0];
        waitFor(() -> asked.size() == 1, "asked once the outline settled");
        FxTestSupport.runOnFx(() -> {
            asked.getFirst().accept(Map.of(3, 7));
            assertEquals(7, counts.count(file, 3));
            assertNull(counts.count(file, 4));
            assertTrue(counts.answered(file));
            assertEquals(1, changed.size());

            counts.shown(java.util.Set.of(file), f -> outline); // counted already: not asked again
            counts.shown(java.util.Set.of(), f -> outline); // no longer shown: forgotten
            assertNull(counts.count(file, 3));
            assertFalse(counts.answered(file));
            assertEquals(2, changed.size());

            counts.shown(java.util.Set.of(file), f -> null); // shown, but no outline yet: nothing to ask
            counts.shown(java.util.Set.of(file), f -> outline);
        });
        waitFor(() -> asked.size() == 2, "asked again once shown");
        FxTestSupport.runOnFx(() -> {
            counts.forget(file);
            asked.get(1).accept(Map.of(3, 1)); // the answer of a forgotten ask
            assertNull(counts.count(file, 3), "dropped");
            counts.outlineChanged(file, null); // the outline went: nothing kept
            shown.clear();
            counts.outlineChanged(file, outline); // not shown: forgotten, not asked
        });
        TimeUnit.MILLISECONDS.sleep((long) MapUsageCounts.QUIET.toMillis() + 200);
        FxTestSupport.runOnFx(() -> {
            assertEquals(2, asked.size());
            shown.add(file);
            counts.outlineChanged(file, outline);
        });
        waitFor(() -> asked.size() == 3, "asked");
        FxTestSupport.runOnFx(() -> {
            asked.get(2).accept(null); // no server could say
            assertNull(counts.count(file, 3));
            counts.dispose();
            counts.outlineChanged(file, outline); // after dispose: nothing
            counts.shown(java.util.Set.of(file), f -> outline);
        });
    }

    // --- through a server ---------------------------------------------------------------------------------------

    @Test
    void aStructureColumnsRowsSayTheReferencesLensesCounts() throws Exception {
        ServerCapabilities capabilities = new ServerCapabilities();
        capabilities.setCodeLensProvider(new CodeLensOptions(false));
        capabilities.setReferencesProvider(Either.forLeft(true));
        Fixture f = fixture(capabilities);
        f.fake.codeLensResponse = List.of(
                lens(3, 8, "2 references"),
                lens(7, 8, "0 references"),
                new CodeLens(range(2, 13), new Command("1 implementation", "java.show.implementations"), null));
        openStructure(f);
        waitFor(() -> Integer.valueOf(2).equals(f.view.usages.count(f.app, 3)), "add: 2 uses");
        FxTestSupport.runOnFx(() -> {
            assertEquals(0, f.view.usages.count(f.app, 7), "unused: no uses");
            assertNull(f.view.usages.count(f.app, 2), "no references lens on the class");
            assertTrue(f.fake.references.isEmpty(), "the lens said it: nothing counted one by one");
            assertFalse(f.manager.isManaged(f.app), "lent while it answered, then given back");
            Region surface = FxTestSupport.field(f.view, "surface");
            String said = (String) FxTestSupport.call(
                    surface,
                    "canvasRowText",
                    new Class<?>[] {ProjectMapModel.Entry.class, int.class, int.class, boolean.class, boolean.class},
                    symbolRow(surface, "add"),
                    1,
                    2,
                    false,
                    true);
            assertTrue(said.contains(tr("project.map.usages", 2)), said);
        });

        // A card's Structure column says them too.
        ProjectMapPreview card = FxTestSupport.callOnFx(() -> f.view.cards.openFileCard(f.app));
        FxTestSupport.runOnFx(() -> card.setStructureShown(true));
        waitFor(() -> f.view.cards.structureColumnFiles().contains(f.app), "the card's column");
        waitFor(
                () -> {
                    ProjectMapCardStructure column = f.view.cards.cardStructureForTest(card);
                    return column != null
                            && column.tree().lookupAll(".project-map-usages").stream()
                                    .anyMatch(node -> node.isVisible()
                                            && tr("project.map.usages", 2)
                                                    .equals(((javafx.scene.control.Label) node).getText()));
                },
                "its add row says 2 uses");
    }

    @Test
    void withoutALensTheReferencesAreCountedAndWithoutAServerNothingIsSaid() throws Exception {
        ServerCapabilities capabilities = new ServerCapabilities();
        capabilities.setReferencesProvider(Either.forLeft(true));
        Fixture f = fixture(capabilities);
        f.fake.referenceResponse = List.of(
                new Location(LspTestHooks.uri(f.root.resolve("Use.java")), range(1, 1)),
                new Location(LspTestHooks.uri(f.root.resolve("Use.java")), range(5, 1)),
                new Location(LspTestHooks.uri(f.root.resolve("Use.java")), range(9, 1)));
        openStructure(f);
        waitFor(() -> Integer.valueOf(3).equals(f.view.usages.count(f.app, 3)), "add's references counted");
        FxTestSupport.runOnFx(() -> {
            assertEquals(3, f.view.usages.count(f.app, 2), "the class too: each declaration asked");
            assertTrue(f.fake.codeLenses.isEmpty(), "no lens asked of a server without them");
            assertTrue(f.fake.references.size() >= 3);
        });

        // No server: nothing said, nothing started.
        ProjectMapLinkSearch none = new ProjectMapLinkSearch(null, f.panel.projectIndex());
        List<@Nullable Map<Integer, Integer>> answers = new CopyOnWriteArrayList<>();
        FxTestSupport.runOnFx(
                () -> none.usages(f.app, List.of(new MapUsageCounts.Declaration(3, "add")), answers::add));
        waitFor(() -> answers.size() == 1, "answered");
        assertNull(answers.getFirst());
    }

    // --- helpers ------------------------------------------------------------------------------------------------

    private record Fixture(
            LspManager manager,
            FakeLanguageServer fake,
            ProjectPanel panel,
            ProjectMapView view,
            Path app,
            Path root) {}

    private Fixture fixture(ServerCapabilities capabilities) throws Exception {
        Path src = Files.createDirectories(root.resolve("src"));
        Path app = Files.writeString(src.resolve("App.java"), APP);
        Path open = Files.writeString(src.resolve("Open.java"), "class Open {}\n");
        LspManager m = new LspManager((file, d) -> {}, (t, s) -> {});
        manager = m;
        List<FakeLanguageServer> fakes = LspTestHooks.useFakeSessions(m, capabilities);
        m.configure(true, Map.of("java", "jdtls"));
        m.openDocument(open, root, "java", "class Open {}\n"); // an editor tab: the server runs
        ProjectPanel panel = FxTestSupport.callOnFx(() -> {
            ProjectPanel p = DisposedPanels.track(new ProjectPanel(x -> {}, (a, b) -> {}, x -> {}, x -> false));
            p.setRoot(root);
            p.openMapWindow(false);
            return p;
        });
        ProjectMapView view = FxTestSupport.field(panel, "mapView");
        Region surface = FxTestSupport.field(view, "surface");
        waitFor(() -> !rows(surface).isEmpty(), "the first column");
        FxTestSupport.runOnFx(() -> view.setLinkSource(new ProjectMapLinkSearch(m, panel.projectIndex())));
        FxTestSupport.runOnFx(() -> view.revealPath(app));
        waitFor(() -> rows(surface).stream().anyMatch(row -> row.path().equals(app)), "the file's row");
        return new Fixture(m, fakes.getFirst(), panel, view, app, root);
    }

    private static void openStructure(Fixture f) throws Exception {
        Region surface = FxTestSupport.field(f.view, "surface");
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                f.view,
                "toggleStructure",
                new Class<?>[] {ProjectMapModel.Entry.class},
                rows(surface).stream()
                        .filter(row -> row.path().equals(f.app))
                        .findFirst()
                        .orElseThrow()));
        waitFor(() -> symbolRow(surface, "add") != null, "the Structure column's rows");
    }

    private static ProjectMapModel.@Nullable Entry symbolRow(Region surface, String name) {
        return rows(surface).stream()
                .filter(row -> row.isSymbol() && row.name().startsWith(name))
                .findFirst()
                .orElse(null);
    }

    private static List<ProjectMapModel.Entry> rows(Region surface) {
        return new ArrayList<>(FxTestSupport.<List<ProjectMapModel.Entry>>field(surface, "entries"));
    }

    private static CodeLens lens(int line, int column, String title) {
        return new CodeLens(range(line, column), new Command(title, "java.show.references"), null);
    }

    private static Range range(int line, int column) {
        return new Range(new Position(line, column), new Position(line, column + 3));
    }

    private static ProjectMapStructure.Node node(Path file, String label, int line) {
        return new ProjectMapStructure.Node(file.resolve(label.strip() + line), symbol(file, label, line), List.of());
    }

    private static ProjectMapModel.Symbol symbol(Path file, String label, int line) {
        return new ProjectMapModel.Symbol(file, label, "method", line, line, line + 2, null, false);
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
