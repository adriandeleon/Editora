package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.layout.Region;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Project Map review 3, the outlines group, on the Map: a file past the card's display cap is outlined whole and
 * its declarations past the cap open (C2), the card says how much of the file it holds and where its text stops,
 * a big buffer's re-outline waits for its text to settle, and the main language is warmed (C16).
 */
@Tag("fx")
class ProjectMapStructureReview3Test {

    @TempDir
    Path temp;

    @BeforeAll
    static void startToolkit() throws Exception {
        FxTestSupport.bootToolkit();
        com.editora.i18n.Messages.init("en");
    }

    /** A Go file of {@code types} structs, then {@code type Tail struct}: about 650 characters a type. */
    private static String bigGo(int types) {
        StringBuilder go = new StringBuilder("package big\n\n");
        for (int i = 0; i < types; i++) {
            go.append("// Item").append(i).append(" is one of many items in this generated file.\n");
            go.append("type Item").append(i).append(" struct {\n");
            for (int f = 0; f < 16; f++) {
                go.append("\t// Field")
                        .append(f)
                        .append(" holds a value.\n\tField")
                        .append(f)
                        .append(" string\n");
            }
            go.append("}\n\n");
        }
        go.append("// Tail is past what a card shows.\ntype Tail struct {\n\tLast int\n}\n");
        return go.toString();
    }

    // ---- C2 ----

    @Test
    void aFilePastTheCardsCapIsOutlinedWholeAndItsCardSaysWhereItStops() throws Exception {
        Path root = Files.createDirectories(temp.resolve("project")).toRealPath();
        String text = bigGo(800);
        assertTrue(text.length() > ProjectMapPreview.MAX_PREVIEW_CHARS + 50_000);
        Path file = Files.writeString(root.resolve("types.go"), text);
        int fileLines = ProjectMapPreview.lineCount(text);
        List<Path> opened = new CopyOnWriteArrayList<>();
        Fixture map = openMap(root, opened::add, path -> ProjectMapPreview.Content.closed(null));
        try {
            waitFor(
                    () -> rows(map.surface()).stream()
                            .anyMatch(row -> row.path().equals(file)),
                    "the file's row");
            FxTestSupport.runOnFx(() -> openPreview(map, file));
            ProjectMapPreview card = waitForCard(map, file);
            Label status = FxTestSupport.callOnFx(() -> FxTestSupport.field(card, "status"));
            waitFor(() -> status.getText().startsWith("Showing lines 1–"), "the card says how much it holds");
            String said = FxTestSupport.callOnFx(status::getText);
            assertTrue(said.contains(String.format(java.util.Locale.getDefault(), "%,d", fileLines)), said);
            Label footer = FxTestSupport.callOnFx(card::cutFooter);
            assertNotNull(footer, "a last line says the text stops");
            assertEquals(tr("project.map.preview.endsHere"), FxTestSupport.callOnFx(footer::getText));
            FxTestSupport.runOnFx(() -> footer.fireEvent(new MouseEvent(
                    MouseEvent.MOUSE_CLICKED,
                    1,
                    1,
                    1,
                    1,
                    MouseButton.PRIMARY,
                    1,
                    false,
                    false,
                    false,
                    false,
                    true,
                    false,
                    false,
                    false,
                    false,
                    true,
                    new PickResult(footer, 1, 1))));
            waitFor(() -> opened.contains(file), "the last line opens the file");

            // The outline reads past the card's cap: the last type is there, and opens a card of its own lines.
            FxTestSupport.runOnFx(() -> clickStructureIcon(map.surface(), file));
            Path tail = waitForSymbol(map, "Tail");
            FxTestSupport.runOnFx(() -> assertFalse(
                    rows(map.surface()).stream()
                            .anyMatch(row -> row.isPlaceholder()
                                    && tr("project.map.structure.partial").equals(row.name())),
                    "no 'start of the file only' row"));
            FxTestSupport.runOnFx(() -> clickRow(map.surface(), tail));
            ProjectMapPreview tailCard = waitForCard(map, tail);
            waitFor(
                    () -> tailCard.editor().getText().contains("type Tail struct {"),
                    "a declaration past the cap reads its own lines");
            FxTestSupport.runOnFx(() -> {
                assertTrue(
                        tailCard.editor().getText().startsWith("// Tail is past"),
                        tailCard.editor().getText());
                assertNull(tailCard.cutFooter(), "a declaration card is whole");
            });
        } finally {
            map.close();
        }
    }

    @Test
    void anOpenBuffersCardCountsItsLinesAndAShortFileHasNoLastLine() throws Exception {
        Path root = Files.createDirectories(temp.resolve("buffers")).toRealPath();
        String big = bigGo(800);
        Path file = Files.writeString(root.resolve("big.go"), "package big\n");
        Path small = Files.writeString(root.resolve("small.go"), "package small\n\nfunc A() {}\n");
        Fixture map = openMap(
                root,
                path -> {},
                path -> path.equals(file)
                        ? ProjectMapPreview.Content.buffer(() -> big, false, 1)
                        : ProjectMapPreview.Content.closed(null));
        try {
            waitFor(
                    () -> rows(map.surface()).stream()
                            .anyMatch(row -> row.path().equals(small)),
                    "the rows");
            FxTestSupport.runOnFx(() -> openPreview(map, file));
            ProjectMapPreview card = waitForCard(map, file);
            Label status = FxTestSupport.callOnFx(() -> FxTestSupport.field(card, "status"));
            String total = String.format(java.util.Locale.getDefault(), "%,d", ProjectMapPreview.lineCount(big));
            waitFor(
                    () -> status.getText().startsWith("Showing lines 1–")
                            && status.getText().contains(total),
                    "an open buffer's card counts the whole buffer: " + status.getText() + " / "
                            + card.editor().getLength());
            FxTestSupport.runOnFx(() -> assertNotNull(card.cutFooter()));

            FxTestSupport.runOnFx(() -> openPreview(map, small));
            ProjectMapPreview smallCard = waitForCard(map, small);
            waitFor(() -> smallCard.editor().getText().contains("func A()"), "the small file");
            FxTestSupport.runOnFx(() -> assertNull(smallCard.cutFooter(), "nothing is cut"));
        } finally {
            map.close();
        }
    }

    @Test
    void theOutlineReadIsFourMegabytesWhileTheCardsStaysAtItsCap() throws Exception {
        Path file = Files.writeString(temp.resolve("types.go"), bigGo(800));
        ProjectMapPreview.ReadText outline = ProjectMapPreview.readOutlineText(file, null);
        assertNotNull(outline);
        assertFalse(outline.truncated());
        assertTrue(outline.text().endsWith("type Tail struct {\n\tLast int\n}\n"));
        ProjectMapPreview.ReadText card = ProjectMapPreview.readTextCut(file, null);
        assertNotNull(card);
        assertTrue(card.truncated());
        assertEquals(ProjectMapPreview.MAX_PREVIEW_CHARS, card.text().length());
        assertEquals("ab", ProjectMapPreview.capOutlineText("ab", false));
        assertEquals(3, ProjectMapPreview.lineCount("a\nb\nc"));
    }

    // ---- C15 ----

    @Test
    void goLabelsInTheMapShowTheReceiverAndNoParameterNames() {
        StructureNode fromOutline = new StructureNode(null, "(*Kubelet).Run", "method", 0, GoOutline.SOME_PARAMETERS);
        assertEquals(
                "(*Kubelet).Run(…)", ProjectMapStructure.displayLabel(fromOutline, "func (kl *Kubelet) Run(", "go"));
        // gopls names a method the same way and puts the whole signature in its detail: still "(…)"
        StructureNode fromServer = new StructureNode(
                null, "(*Kubelet).Run", "method", 0, "func(ctx context.Context, updates <-chan kubetypes.PodUpdate)");
        assertEquals(
                "(*Kubelet).Run(…)",
                ProjectMapStructure.displayLabel(
                        fromServer, "func (kl *Kubelet) Run(ctx context.Context, updates <-chan PodUpdate) {", "go"));
        StructureNode none = new StructureNode(null, "RootFsStats", "function", 0);
        assertEquals("RootFsStats()", ProjectMapStructure.displayLabel(none, "func RootFsStats() error {", "go"));
        StructureNode long_ = new StructureNode(null, "makePodSourceConfig", "function", 0);
        assertEquals(
                "makePodSourceConfig(…)",
                ProjectMapStructure.displayLabel(
                        long_,
                        "func makePodSourceConfig(ctx context.Context, kubeCfg *KubeletConfiguration, kubeDeps *Dependencies) {",
                        "go"));
    }

    // ---- C16 ----

    @Test
    void aBigBuffersReOutlineWaitsForItsTextToSettle(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("big.go"), "package big\n");
        AtomicReference<String> text = new AtomicReference<>(bigGo(400));
        AtomicLong version = new AtomicLong(1);
        assertTrue(ProjectMapStructure.debounces(text.get().length()));
        ProjectMapStructure structure = FxTestSupport.callOnFx(() -> new ProjectMapStructure(
                path -> path.equals(file) ? ProjectMapPreview.Content.buffer(text::get, false, version.get()) : null));
        try {
            ProjectMapModel.Entry entry = new ProjectMapModel.Entry(file, dir, 1, false);
            FxTestSupport.runOnFx(() -> assertTrue(structure.toggle(entry)));
            waitFor(() -> labels(structure, file).contains("Item0"), "the first outline comes at once");
            assertFalse(labels(structure, file).contains("Added"));

            text.set(text.get() + "\ntype Added struct {\n}\n");
            version.incrementAndGet();
            FxTestSupport.runOnFx(() -> {
                structure.refresh(Set.of());
                assertTrue(structure.isDebouncing(file), "an edit to a big buffer waits");
            });
            TimeUnit.MILLISECONDS.sleep(ProjectMapStructure.DEBOUNCE_MILLIS / 3);
            FxTestSupport.runOnFx(() -> structure.refresh(Set.of())); // the next poll, same text: the wait runs on
            assertFalse(labels(structure, file).contains("Added"), "not yet: the text may still change");
            waitFor(() -> labels(structure, file).contains("Added"), "then it is outlined");
            FxTestSupport.runOnFx(() -> assertFalse(structure.isDebouncing(file)));
        } finally {
            FxTestSupport.runOnFx(structure::dispose);
        }
    }

    @Test
    void aSmallBuffersOutlineFollowsAtOnce(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("small.go"), "package small\n");
        AtomicReference<String> text = new AtomicReference<>("package small\n\nfunc A() {}\n");
        AtomicLong version = new AtomicLong(1);
        ProjectMapStructure structure = FxTestSupport.callOnFx(() ->
                new ProjectMapStructure(path -> ProjectMapPreview.Content.buffer(text::get, false, version.get())));
        try {
            FxTestSupport.runOnFx(() -> structure.toggle(new ProjectMapModel.Entry(file, dir, 1, false)));
            waitFor(() -> labels(structure, file).contains("A()"), "outlined");
            text.set(text.get() + "\nfunc B() {}\n");
            version.incrementAndGet();
            FxTestSupport.runOnFx(() -> {
                structure.refresh(Set.of());
                assertFalse(structure.isDebouncing(file));
            });
            waitFor(() -> labels(structure, file).contains("B()"), "outlined again at once");
        } finally {
            FxTestSupport.runOnFx(structure::dispose);
        }
    }

    @Test
    void theMainLanguageIsTheOneTheBuildFileNamesElseTheCommonestOutlinedOne() {
        Path root = Path.of("/p");
        assertEquals(
                "main.go",
                ProjectMapStructure.dominantOutlineFile(
                        Set.of(root.resolve("go.mod"), root.resolve("README.md"), root.resolve("Makefile"))));
        assertEquals(
                "Main.java",
                ProjectMapStructure.dominantOutlineFile(Set.of(root.resolve("pom.xml"), root.resolve("a.py"))));
        assertEquals(
                "x.py",
                ProjectMapStructure.dominantOutlineFile(Set.of(
                        root.resolve("a.py"),
                        root.resolve("b.py"),
                        root.resolve("c.rb"),
                        root.resolve("d.md"),
                        root.resolve("e.md"),
                        root.resolve("f.md"))));
        assertNull(ProjectMapStructure.dominantOutlineFile(Set.of(root.resolve("notes.txt"))));
    }

    // ---- helpers ----

    private static List<String> labels(ProjectMapStructure structure, Path file) {
        try {
            return FxTestSupport.callOnFx(() -> {
                List<String> out = new java.util.ArrayList<>();
                collect(structure.view(file).roots(), out);
                return out;
            });
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void collect(List<ProjectMapStructure.Node> nodes, List<String> out) {
        for (ProjectMapStructure.Node node : nodes) {
            out.add(node.symbol().label());
            collect(node.children(), out);
        }
    }

    private record Fixture(ProjectMapView view, Scene scene, Region surface) {
        void close() throws Exception {
            FxTestSupport.runOnFx(view::dispose);
        }
    }

    private static Fixture openMap(
            Path root, java.util.function.Consumer<Path> onOpen, Function<Path, ProjectMapPreview.Content> content)
            throws Exception {
        return FxTestSupport.callOnFx(() -> {
            ProjectMapView view = new ProjectMapView(onOpen, path -> false, path -> false, content);
            Scene scene = new Scene(view, 1400, 900);
            scene.getStylesheets()
                    .add(ProjectMapStructureReview3Test.class
                            .getResource("/com/editora/styles/app.css")
                            .toExternalForm());
            view.resize(1400, 900);
            view.applyCss();
            view.layout();
            view.setRememberedFlow(ProjectMapView.FlowDirection.LEFT_TO_RIGHT.name(), ignored -> {});
            view.setRememberedNavigation(true, false, (keep, focus) -> {});
            view.setRoot(root);
            return new Fixture(view, scene, FxTestSupport.field(view, "surface"));
        });
    }

    private static void openPreview(Fixture map, Path file) {
        FxTestSupport.call(map.surface(), "setSelected", new Class<?>[] {Path.class}, file);
        FxTestSupport.call(map.view(), "previewSelection", new Class<?>[] {Path.class}, file);
    }

    private static List<ProjectMapModel.Entry> rows(Region surface) {
        List<?> boxes = FxTestSupport.field(surface, "boxes");
        return boxes.stream()
                .map(box -> (ProjectMapModel.Entry) FxTestSupport.call(box, "entry", new Class<?>[0]))
                .toList();
    }

    private static Path waitForSymbol(Fixture map, String name) throws Exception {
        waitFor(
                () -> rows(map.surface()).stream()
                        .anyMatch(row -> row.isSymbol() && row.name().equals(name)),
                "a declaration row " + name);
        FxTestSupport.drainFx();
        return FxTestSupport.callOnFx(() -> rows(map.surface()).stream()
                .filter(row -> row.isSymbol() && row.name().equals(name))
                .findFirst()
                .orElseThrow()
                .path());
    }

    @SuppressWarnings("unchecked")
    private static Map<Path, ProjectMapPreview> previews(ProjectMapView view) {
        return FxTestSupport.field(view, "previews");
    }

    private static ProjectMapPreview waitForCard(Fixture map, Path key) throws Exception {
        waitFor(() -> previews(map.view()).containsKey(key), "a card for " + key.getFileName());
        FxTestSupport.drainFx();
        return FxTestSupport.callOnFx(() -> previews(map.view()).get(key));
    }

    private static void waitFor(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (FxTestSupport.callOnFx(condition::getAsBoolean)) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        throw new AssertionError("timed out: " + message);
    }

    private static Object boxFor(Region surface, Path path) {
        List<?> boxes = FxTestSupport.field(surface, "boxes");
        return boxes.stream()
                .filter(box -> ((ProjectMapModel.Entry) FxTestSupport.call(box, "entry", new Class<?>[0]))
                        .path()
                        .equals(path))
                .findFirst()
                .orElseThrow();
    }

    private static double box(Object box, String part) {
        return (double) FxTestSupport.call(box, part, new Class<?>[0]);
    }

    private static void clickStructureIcon(Region surface, Path file) {
        Object box = boxFor(surface, file);
        double zoom = FxTestSupport.field(surface, "zoom");
        click(surface, box(box, "x") + box(box, "width") - 41 * zoom, box(box, "y") + box(box, "height") / 2);
    }

    private static void clickRow(Region surface, Path path) {
        Object box = boxFor(surface, path);
        click(surface, box(box, "x") + 40, box(box, "y") + box(box, "height") / 2);
    }

    private static void click(Region target, double x, double y) {
        MouseEvent event = new MouseEvent(
                MouseEvent.MOUSE_CLICKED,
                x,
                y,
                x,
                y,
                MouseButton.PRIMARY,
                1,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                true,
                new PickResult(target, x, y));
        FxTestSupport.invokeWith(target, "mouseClicked", MouseEvent.class, event);
    }
}
