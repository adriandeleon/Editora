package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.layout.Region;

import com.editora.editor.fold.FoldRegions;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Project Map's structure columns: the Structure icon, declaration rows and declaration cards. */
class ProjectMapStructureFxTest {

    private static final String APP = String.join(
            "\n",
            "package demo;", // 0
            "", // 1
            "public class App {", // 2
            "    private int count;", // 3
            "", // 4
            "    /**", // 5
            "     * Adds.", // 6
            "     */", // 7
            "    int add(int a, int b) {", // 8
            "        return a + b;", // 9
            "    }", // 10
            "", // 11
            "    void reset() {", // 12
            "        count = 0;", // 13
            "    }", // 14
            "}", // 15
            "");

    @TempDir
    Path temp;

    private Path root;

    @BeforeAll
    static void startToolkit() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void theStructureIconOpensAColumnOfDeclarationsAndALoneClassOpensItsMembers() throws Exception {
        root = projectRoot();
        Path app = Files.writeString(root.resolve("App.java"), APP);
        Fixture map = openMap(path -> null);
        try {
            waitForRow(map, app);
            FxTestSupport.runOnFx(() -> clickStructureIcon(map.surface(), app));
            Path type = waitForSymbol(map, "App");
            Path add = waitForSymbol(map, "add()");
            Path reset = waitForSymbol(map, "reset()");
            FxTestSupport.runOnFx(() -> {
                assertEquals(app, entry(map.surface(), type).parent(), "the class hangs from its file");
                assertEquals(type, entry(map.surface(), add).parent(), "its members hang from the class");
                assertTrue(add.startsWith(app) && reset.startsWith(app), "declaration paths sit below their file");
                assertTrue(ProjectMapStructure.isSymbolPath(add));
                assertTrue(columnTitles(map.surface()).contains(tr("project.map.column.structure", "App.java")));
                assertTrue(columnTitles(map.surface()).contains("App"), "a class's column is titled by the class");
                assertFalse(Files.exists(add), "nothing is created on disk");
            });
            // The members are ordered as written, not alphabetically.
            FxTestSupport.runOnFx(() -> {
                List<String> members = rows(map.surface()).stream()
                        .filter(row -> type.equals(row.parent()))
                        .map(ProjectMapModel.Entry::name)
                        .toList();
                assertEquals(List.of("add()", "reset()"), members);
            });
        } finally {
            map.close();
        }
    }

    @Test
    void clickingADeclarationOpensACardOfOnlyItsLinesNumberedAsTheFileIs() throws Exception {
        root = projectRoot();
        Path app = Files.writeString(root.resolve("App.java"), APP);
        List<Object[]> openedAt = new CopyOnWriteArrayList<>();
        List<Object[]> narrowed = new CopyOnWriteArrayList<>();
        Fixture map = openMap(path -> null);
        try {
            FxTestSupport.runOnFx(() -> map.view()
                    .setOpenActions(
                            (file, line) -> openedAt.add(new Object[] {file, line}),
                            (file, block) -> narrowed.add(new Object[] {file, block})));
            waitForRow(map, app);
            FxTestSupport.runOnFx(() -> clickStructureIcon(map.surface(), app));
            Path add = waitForSymbol(map, "add()");
            Path reset = waitForSymbol(map, "reset()");
            FxTestSupport.runOnFx(() -> clickRow(map.surface(), add));
            ProjectMapPreview card = waitForCard(map, add);
            waitFor(() -> card.editor().getText().contains("return a + b;"), "the card shows the method");
            FxTestSupport.runOnFx(() -> {
                CodeArea editor = card.editor();
                assertEquals(
                        String.join(
                                "\n",
                                "    /**",
                                "     * Adds.",
                                "     */",
                                "    int add(int a, int b) {",
                                "        return a + b;",
                                "    }"),
                        editor.getText(),
                        "its block, with its Javadoc, and nothing else of the file");
                assertTrue(editor.getStyleClass().contains("narrowed"), "the gutter takes the narrowed colour");
                Node gutter = editor.getParagraphGraphicFactory().apply(0);
                assertEquals("6", ((Label) gutter).getText().strip(), "the first line keeps the file's number");
                assertEquals(new ProjectMapPreview.Range("add()", 8, 5, 10), card.range());
                Label title = FxTestSupport.field(card, "title");
                assertEquals("App.java › add()", title.getText());
                Label status = FxTestSupport.field(card, "status");
                assertEquals(tr("project.map.preview.lines", 6, 11, 17), status.getText());

                // Open goes to the declaration's line; Open Narrowed to its block.
                FxTestSupport.<javafx.scene.control.Button>field(card, "open").fire();
                FxTestSupport.call(card, "rebuildEditorContextMenu", new Class<?>[] {int.class}, 0);
                ContextMenu menu = FxTestSupport.field(card, "editorContextMenu");
                MenuItem openNarrowed = menu.getItems().getFirst();
                assertEquals(tr("project.map.preview.openNarrowed"), openNarrowed.getText());
                openNarrowed.fire();
            });
            assertEquals(1, openedAt.size());
            assertEquals(app, openedAt.getFirst()[0]);
            assertEquals(8, openedAt.getFirst()[1]);
            assertEquals(1, narrowed.size());
            assertEquals(new FoldRegions.Region(8, 10), narrowed.getFirst()[1], "the fold block, without the doc");

            // A second declaration of the same file gets a card of its own.
            FxTestSupport.runOnFx(() -> clickRow(map.surface(), reset));
            ProjectMapPreview second = waitForCard(map, reset);
            waitFor(() -> second.editor().getText().contains("count = 0;"), "the second card shows reset()");
            FxTestSupport.runOnFx(() -> assertEquals(2, previews(map.view()).size()));
        } finally {
            map.close();
        }
    }

    @Test
    void closingTheStructureClosesItsColumnsAndTheirCards() throws Exception {
        root = projectRoot();
        Path app = Files.writeString(root.resolve("App.java"), APP);
        Fixture map = openMap(path -> null);
        try {
            waitForRow(map, app);
            FxTestSupport.runOnFx(() -> clickStructureIcon(map.surface(), app));
            Path reset = waitForSymbol(map, "reset()");
            FxTestSupport.runOnFx(() -> clickRow(map.surface(), reset));
            waitForCard(map, reset);
            FxTestSupport.runOnFx(() -> clickStructureIcon(map.surface(), app));
            waitFor(() -> rows(map.surface()).stream().noneMatch(ProjectMapModel.Entry::isSymbol), "rows go");
            waitFor(() -> previews(map.view()).isEmpty(), "the declaration's card closes with its column");
        } finally {
            map.close();
        }
    }

    @Test
    void anOpenBuffersUnsavedTextIsOutlinedAndTheCardFollowsItsDeclaration() throws Exception {
        root = projectRoot();
        Path app = Files.writeString(root.resolve("App.java"), APP);
        AtomicReference<String> buffer = new AtomicReference<>(APP.replace("void reset()", "void clear()"));
        Fixture map = openMap(path -> path.equals(app) ? new ProjectMapPreview.Content(buffer.get(), false) : null);
        try {
            waitForRow(map, app);
            FxTestSupport.runOnFx(() -> clickStructureIcon(map.surface(), app));
            Path clear = waitForSymbol(map, "clear()"); // the buffer's text, not the file on disk
            FxTestSupport.runOnFx(() -> clickRow(map.surface(), clear));
            ProjectMapPreview card = waitForCard(map, clear);
            waitFor(() -> card.editor().getText().contains("count = 0;"), "the card shows clear()");

            // A method is added above it: the card follows the method, not the old line numbers.
            buffer.set(buffer.get()
                    .replace(
                            "    private int count;\n",
                            "    private int count;\n    void extra() {\n        count++;\n    }\n"));
            FxTestSupport.runOnFx(
                    () -> FxTestSupport.call(map.view(), "refreshCards", new Class<?>[] {boolean.class}, true));
            waitFor(() -> card.range() != null && card.range().startLine() == 15, "the card moves with clear()");
            waitFor(() -> card.editor().getText().startsWith("    void clear() {"), "and still shows it");
            waitForSymbol(map, "extra()");

            // The method is renamed away: the card keeps its last lines and says so.
            buffer.set(buffer.get().replace("void clear()", "void wipe()"));
            FxTestSupport.runOnFx(
                    () -> FxTestSupport.call(map.view(), "refreshCards", new Class<?>[] {boolean.class}, true));
            waitForSymbol(map, "wipe()");
            Label status = FxTestSupport.callOnFx(() -> FxTestSupport.field(card, "status"));
            waitFor(() -> tr("project.map.preview.symbolMissing").equals(status.getText()), "the card says it went");
            FxTestSupport.runOnFx(() -> assertTrue(card.editor().getText().contains("count = 0;")));
        } finally {
            map.close();
        }
    }

    @Test
    void markdownHeadingsNestAndAHeadingsCardIsItsSection() throws Exception {
        root = projectRoot();
        Path readme = Files.writeString(root.resolve("README.md"), "# Intro\ntext\n\n## Detail\nmore\n\n# End\nbye\n");
        Fixture map = openMap(path -> null);
        try {
            waitForRow(map, readme);
            FxTestSupport.runOnFx(() -> clickStructureIcon(map.surface(), readme));
            Path intro = waitForSymbol(map, "Intro");
            waitForSymbol(map, "End");
            FxTestSupport.runOnFx(() -> {
                assertTrue(
                        rows(map.surface()).stream().noneMatch(row -> "Detail".equals(row.name())),
                        "two top-level headings: neither opens by itself");
                clickChevron(map.surface(), intro);
            });
            waitForSymbol(map, "Detail");
            FxTestSupport.runOnFx(() -> clickRow(map.surface(), intro));
            ProjectMapPreview card = waitForCard(map, intro);
            waitFor(() -> card.editor().getText().equals("# Intro\ntext\n\n## Detail\nmore"), "its whole section");
        } finally {
            map.close();
        }
    }

    @Test
    void theArrowAlongTheFlowOpensTheStructureAndEnterOpensADeclarationsCard() throws Exception {
        root = projectRoot();
        Path app = Files.writeString(root.resolve("App.java"), APP);
        Fixture map = openMap(path -> null);
        try {
            waitForRow(map, app);
            FxTestSupport.runOnFx(() -> {
                FxTestSupport.call(map.surface(), "select", new Class<?>[] {Path.class}, app);
                key(map.surface(), KeyCode.RIGHT);
            });
            Path type = waitForSymbol(map, "App");
            FxTestSupport.runOnFx(() -> key(map.surface(), KeyCode.RIGHT));
            waitFor(() -> type.equals(selected(map.surface())), "the second arrow goes into the structure");
            FxTestSupport.runOnFx(() -> {
                key(map.surface(), KeyCode.RIGHT);
                assertEquals(
                        "add()", entry(map.surface(), selected(map.surface())).name());
                key(map.surface(), KeyCode.DOWN);
                assertEquals(
                        "reset()", entry(map.surface(), selected(map.surface())).name());
                key(map.surface(), KeyCode.ENTER);
            });
            Path reset = waitForSymbol(map, "reset()");
            waitForCard(map, reset);
            FxTestSupport.runOnFx(() -> {
                key(map.surface(), KeyCode.LEFT);
                assertEquals(type, selected(map.surface()), "the arrow against the flow goes back up");
            });
        } finally {
            map.close();
        }
    }

    @Test
    void aFileWithNoOutlineHasNoStructureIconAndADeclarationNoFileMenu() throws Exception {
        root = projectRoot();
        Path notes = Files.writeString(root.resolve("notes.txt"), "plain text\n");
        Path app = Files.writeString(root.resolve("App.java"), APP);
        Fixture map = openMap(path -> null);
        try {
            waitForRow(map, notes);
            FxTestSupport.runOnFx(() -> {
                assertFalse((boolean) FxTestSupport.call(
                        map.surface(),
                        "hasStructure",
                        new Class<?>[] {ProjectMapModel.Entry.class},
                        entry(map.surface(), notes)));
                assertTrue((boolean) FxTestSupport.call(
                        map.surface(),
                        "hasStructure",
                        new Class<?>[] {ProjectMapModel.Entry.class},
                        entry(map.surface(), app)));
                clickStructureIcon(map.surface(), app);
            });
            Path add = waitForSymbol(map, "add()");
            FxTestSupport.runOnFx(() -> {
                map.view().setContextMenuFactory(entry -> new ContextMenu(new MenuItem("file menu")));
                Function<ProjectMapModel.Entry, ContextMenu> factory =
                        FxTestSupport.field(map.surface(), "contextMenuFactory");
                ContextMenu menu = factory.apply(entry(map.surface(), add));
                assertNotNull(menu);
                assertEquals(
                        List.of(
                                tr("project.map.structure.preview"),
                                tr("project.map.structure.openAt"),
                                tr("project.map.preview.openNarrowed")),
                        menu.getItems().stream().map(MenuItem::getText).toList());
                // F2 / Delete never reach a declaration.
                FxTestSupport.call(map.surface(), "select", new Class<?>[] {Path.class}, add);
                assertFalse((boolean)
                        FxTestSupport.call(map.surface(), "rowAction", new Class<?>[] {KeyCode.class}, KeyCode.F2));
            });
        } finally {
            map.close();
        }
    }

    @Test
    void aDeletedFilesStructureColumnGoesWithItsRow() throws Exception {
        root = projectRoot();
        Path app = Files.writeString(root.resolve("App.java"), APP);
        Fixture map = openMap(path -> null);
        try {
            waitForRow(map, app);
            FxTestSupport.runOnFx(() -> clickStructureIcon(map.surface(), app));
            waitForSymbol(map, "reset()");
            Files.delete(app);
            FxTestSupport.runOnFx(map.view()::refresh);
            waitFor(() -> rows(map.surface()).stream().noneMatch(ProjectMapModel.Entry::isSymbol), "rows go");
            ProjectMapStructure structure = FxTestSupport.field(map.view(), "structure");
            FxTestSupport.runOnFx(() -> assertFalse(structure.hasOpenColumns()));
        } finally {
            map.close();
        }
    }

    @Test
    void printedOutputIncludesTheOpenStructureColumns() throws Exception {
        root = projectRoot();
        Path app = Files.writeString(root.resolve("App.java"), APP);
        Fixture map = openMap(path -> null);
        try {
            waitForRow(map, app);
            FxTestSupport.runOnFx(() -> clickStructureIcon(map.surface(), app));
            waitForSymbol(map, "reset()");
            FxTestSupport.runOnFx(() -> {
                AtomicReference<ProjectMapOutput> job = new AtomicReference<>();
                map.view().setOutputActions(job::set, ignored -> {});
                FxTestSupport.<MenuItem>field(map.view(), "printButton").fire();
                ProjectMapOutput.Rendered rendered = job.get().render(2_000, 2_000);
                assertNotNull(rendered);
                assertFalse(rendered.pages().isEmpty());
                // Three columns side by side: the project, App.java's structure, and App's members.
                assertTrue(rendered.pages().getFirst().getWidth() > 3 * 164);
            });
        } finally {
            map.close();
        }
    }

    // --- fixture ---------------------------------------------------------------------------------------

    private record Fixture(ProjectMapView view, Scene scene, Region surface) {
        void close() throws Exception {
            FxTestSupport.runOnFx(view::dispose);
        }
    }

    private Path projectRoot() throws Exception {
        return Files.createDirectories(temp.resolve("project")).toRealPath();
    }

    private Fixture openMap(Function<Path, ProjectMapPreview.Content> content) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            ProjectMapView view = new ProjectMapView(path -> {}, path -> false, path -> false, content);
            Scene scene = new Scene(view, 1400, 900);
            scene.getStylesheets()
                    .add(ProjectMapStructureFxTest.class
                            .getResource("/com/editora/styles/app.css")
                            .toExternalForm());
            view.resize(1400, 900);
            view.applyCss();
            view.layout();
            view.setRememberedFlow(ProjectMapView.FlowDirection.LEFT_TO_RIGHT.name(), ignored -> {});
            view.setRememberedNavigation(true, false, (keep, focus) -> {}); // no panning to new columns
            view.setRoot(root);
            return new Fixture(view, scene, FxTestSupport.field(view, "surface"));
        });
    }

    private static List<ProjectMapModel.Entry> rows(Region surface) {
        List<?> boxes = FxTestSupport.field(surface, "boxes");
        return boxes.stream()
                .map(box -> (ProjectMapModel.Entry) FxTestSupport.call(box, "entry", new Class<?>[0]))
                .toList();
    }

    private static ProjectMapModel.Entry entry(Region surface, Path path) {
        return rows(surface).stream()
                .filter(row -> row.path().equals(path))
                .findFirst()
                .orElseThrow();
    }

    private static Path selected(Region surface) {
        return (Path) FxTestSupport.call(surface, "selectedPath", new Class<?>[0]);
    }

    private static List<String> columnTitles(Region surface) {
        List<?> columns = FxTestSupport.field(surface, "columnBoxes");
        return columns.stream()
                .map(box -> (String) FxTestSupport.call(
                        surface,
                        "columnTitle",
                        new Class<?>[] {ProjectMapModel.Column.class},
                        FxTestSupport.call(box, "column", new Class<?>[0])))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<Path, ProjectMapPreview> previews(ProjectMapView view) {
        return FxTestSupport.field(view, "previews");
    }

    private static void waitForRow(Fixture map, Path path) throws Exception {
        waitFor(() -> rows(map.surface()).stream().anyMatch(row -> row.path().equals(path)), "row " + path);
        FxTestSupport.drainFx();
    }

    private static Path waitForSymbol(Fixture map, String name) throws Exception {
        waitFor(
                () -> rows(map.surface()).stream()
                        .anyMatch(row -> row.isSymbol() && row.name().equals(name)),
                "a declaration row " + name + " among " + names(map));
        FxTestSupport.drainFx();
        return FxTestSupport.callOnFx(() -> rows(map.surface()).stream()
                .filter(row -> row.isSymbol() && row.name().equals(name))
                .findFirst()
                .orElseThrow()
                .path());
    }

    private static String names(Fixture map) {
        try {
            return FxTestSupport.callOnFx(() -> rows(map.surface()).stream()
                    .map(ProjectMapModel.Entry::name)
                    .toList()
                    .toString());
        } catch (Exception e) {
            return e.toString();
        }
    }

    private static ProjectMapPreview waitForCard(Fixture map, Path key) throws Exception {
        waitFor(() -> previews(map.view()).containsKey(key), "a card for " + key.getFileName());
        FxTestSupport.drainFx();
        return FxTestSupport.callOnFx(() -> previews(map.view()).get(key));
    }

    private static void waitFor(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
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
        // The icon's strip sits before the eye: 29 (eye) + 24 / 2 from the trailing edge, in world units.
        click(surface, box(box, "x") + box(box, "width") - 41 * zoom, box(box, "y") + box(box, "height") / 2);
    }

    private static void clickChevron(Region surface, Path path) {
        Object box = boxFor(surface, path);
        click(surface, box(box, "x") + box(box, "width") - 6, box(box, "y") + box(box, "height") / 2);
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

    private static void key(Region surface, KeyCode code) {
        KeyEvent event = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false);
        FxTestSupport.invokeWith(surface, "keyPressed", KeyEvent.class, event);
    }
}
