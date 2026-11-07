package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Project Map review 3 layout fixes in a live Map: columns never overlap (B3); a reveal shows the row's path a
 * third of the way down and flashes it, and the zoom bar and overview give way to the rows under them (B7); far out
 * the columns are labelled blocks and Fit reaches them (B8); compact rows with their controls on demand (B15); the
 * Overview card, module badges and folder tooltips (B4); ignored and generated rows dimmed or hidden (A11); crumbs
 * keep their underscores (E4); and the help says how to search (B19).
 */
@ExtendWith(DisposedPanels.class)
class ProjectMapLayoutReview3FxTest {

    @TempDir
    Path temp;

    private Path root;

    @BeforeAll
    static void startToolkit() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private final List<Stage> stages = new ArrayList<>();
    private final List<ProjectMapView> views = new ArrayList<>();

    @BeforeEach
    void makeRoot() throws Exception {
        root = Files.createDirectories(temp.resolve("spring")).toRealPath();
    }

    @AfterEach
    void closeViews() throws Exception {
        FxTestSupport.runOnFx(() -> {
            views.forEach(ProjectMapView::dispose);
            stages.forEach(Stage::close);
        });
    }

    /**
     * A Spring-like tree: modules whose sources hang from compacted {@code java / org / springframework} chains, with
     * names of very different widths in different branches. A search that opens many of them used to lay a wide card
     * of one level over the next level's cards of another branch (B3).
     */
    @Test
    void aMultiBranchSearchInASpringLikeTreeLaysNoColumnOverAnother() throws Exception {
        String[] modules = {"spring-tx", "spring-web", "spring-context", "spring-test", "spring-orm", "spring-jdbc"};
        String[] packages = {"annotation", "interceptor", "support", "config", "testfixture/server", "event"};
        for (int m = 0; m < modules.length; m++) {
            for (String set : List.of("main", "test", "testFixtures")) {
                for (int p = 0; p < packages.length; p++) {
                    if ((m + p) % 2 == 0 && !set.equals("main")) {
                        continue;
                    }
                    Path folder = Files.createDirectories(root.resolve(modules[m] + "/src/" + set
                            + "/java/org/springframework/" + modules[m].substring(7) + "/" + packages[p]));
                    // Short names in some branches, very long ones in others: the widths of a level differ a lot.
                    String stem = (m + p) % 3 == 0 ? "Tx" : "AbstractRefreshableConfigApplicationListenerMethodAdapter";
                    Files.writeString(folder.resolve(stem + "Transactional" + p + ".java"), "class X {}\n");
                    Files.writeString(folder.resolve("Other" + p + ".java"), "class Y {}\n");
                }
            }
        }
        // The case that overlapped every time: a deep compacted chain is one level from the first column but is
        // listed last (the listing is breadth-first on disk), so its wide card came after a shallow branch's next
        // level had been placed against the narrower band.
        Path deep = Files.createDirectories(root.resolve("aaa/src/main/java/org/springframework/deep"));
        for (int index = 0; index < 6; index++) {
            Files.writeString(
                    deep.resolve("AbstractRefreshableConfigApplicationContextTransactional" + index + ".java"), "x\n");
        }
        Path shallow = Files.createDirectories(root.resolve("bbb/c"));
        Files.writeString(root.resolve("bbb/notes.txt"), "x\n"); // so bbb's column is not folded into c's
        for (int index = 0; index < 80; index++) {
            Files.writeString(shallow.resolve("Transactional" + index + ".java"), "x\n");
        }
        Files.writeString(root.resolve("build.gradle"), "// root\n");
        ProjectPanel panel = openPanel();
        ProjectMapView view = FxTestSupport.field(panel, "mapView");
        Region surface = FxTestSupport.field(view, "surface");

        // The map opens the folders of the best 30 matches only (B1); this layout case needs every branch open.
        FxTestSupport.runOnFx(() -> view.setSearchOpenLimitForTest(300));
        search(panel, "Transactional");
        waitFor(() -> view.searchResult().matches().size() > 20, "the search finds every branch");
        waitFor(() -> columnBoxes(surface).size() > 40, "the map opens the branches of the matches");
        for (ProjectMapView.FlowDirection flow : ProjectMapView.FlowDirection.values()) {
            FxTestSupport.runOnFx(() -> {
                FxTestSupport.call(
                        surface, "setFlowDirection", new Class<?>[] {ProjectMapView.FlowDirection.class}, flow);
                FxTestSupport.invoke(surface, "paintNow");
            });
            List<double[]> boxes = FxTestSupport.callOnFx(() -> columnBoxes(surface));
            List<String> overlaps = new ArrayList<>();
            for (int i = 0; i < boxes.size(); i++) {
                for (int j = i + 1; j < boxes.size(); j++) {
                    if (intersect(boxes.get(i), boxes.get(j))) {
                        overlaps.add(i + "×" + j);
                    }
                }
            }
            assertTrue(overlaps.isEmpty(), flow + ": " + boxes.size() + " columns, overlapping pairs: " + overlaps);
        }
    }

    // --- B7: a reveal is a place ----------------------------------------------------------------------------

    @Test
    void aRevealPutsTheRowAThirdDownWithItsPathAndFlashesIt() throws Exception {
        for (int index = 0; index < 40; index++) {
            Files.writeString(root.resolve(String.format("Top%02d.txt", index)), "x\n");
        }
        Path pkg = Files.createDirectories(root.resolve("pkg"));
        for (int index = 0; index < 20; index++) {
            Files.createDirectories(pkg.resolve(String.format("p%02d", index)));
        }
        Path kubelet = Files.createDirectories(pkg.resolve("p19/kubelet"));
        for (int index = 0; index < 80; index++) {
            Files.writeString(kubelet.resolve(String.format("f%02d.go", index)), "package k\n");
        }
        Fixture map = openMap(1200, 800);
        // Near the top of its column: the folders that lead to it fit, zoomed out to no less than 70%.
        Path near = kubelet.resolve("f05.go");
        FxTestSupport.runOnFx(() -> map.view().revealPath(near));
        waitFor(
                () -> near.equals(FxTestSupport.field(map.surface(), "selected")) && box(map, near) != null,
                "the first target is selected");
        FxTestSupport.runOnFx(() -> {}); // the deferred second pass of revealAfterLoad
        FxTestSupport.runOnFx(() -> {
            double zoom = FxTestSupport.field(map.surface(), "zoom");
            assertTrue(zoom >= 0.7 - 1e-9 && zoom < 1, "zoomed out, never below 70%, to show the path: " + zoom);
            double height = map.surface().getHeight();
            for (Path ancestor : List.of(pkg.resolve("p19"), pkg)) {
                double[] row = box(map, ancestor);
                assertNotNull(row, ancestor.getFileName() + "'s row");
                assertTrue(
                        row[0] >= 0 && row[1] >= 0 && row[1] + row[3] <= height,
                        "the folder " + ancestor.getFileName() + " that leads to it is in view too");
            }
            assertEquals(near, FxTestSupport.field(map.surface(), "layoutFlashPath"), "and it is flashed");
        });
        // Far down a long column: the path cannot be shown with it, and the row sits a third of the way down.
        Path far = kubelet.resolve("f70.go");
        FxTestSupport.runOnFx(() -> map.view().revealPath(far));
        waitFor(() -> far.equals(FxTestSupport.field(map.surface(), "selected")), "the second target is selected");
        FxTestSupport.runOnFx(() -> {
            double[] row = box(map, far);
            double height = map.surface().getHeight();
            double center = row[1] + row[3] / 2;
            assertTrue(
                    center > height * 0.2 && center < height * 0.45, "about a third down: " + center + " of " + height);
            assertEquals(far, FxTestSupport.field(map.surface(), "layoutFlashPath"));
        });
    }

    @Test
    void theOverviewGivesWayToARowUnderItWhileThePointerIsOnThatRow() throws Exception {
        for (int index = 0; index < 60; index++) {
            Files.writeString(root.resolve(String.format("AVeryLongFileNameNumber%02d.txt", index)), "x\n");
        }
        Fixture map = openMap(900, 640);
        Region surface = map.surface();
        FxTestSupport.runOnFx(() -> FxTestSupport.invoke(surface, "paintNow"));
        Object overview = FxTestSupport.callOnFx(() -> FxTestSupport.field(surface, "overviewBox"));
        assertNotNull(overview, "sixty rows overflow the map: the overview shows");
        Node canvas = FxTestSupport.field(surface, "overviewCanvas");
        FxTestSupport.runOnFx(() -> {
            double ox = (double) FxTestSupport.call(overview, "x", new Class<?>[0]);
            double oy = (double) FxTestSupport.call(overview, "y", new Class<?>[0]);
            // Bring a row under the overview's left part, its start left of it.
            Path rowPath = root.resolve("AVeryLongFileNameNumber10.txt");
            double[] row = box(map, rowPath);
            setDouble(
                    surface, "offsetX", (double) FxTestSupport.field(surface, "offsetX") + ox + 40 - (row[0] + row[2]));
            setDouble(surface, "offsetY", (double) FxTestSupport.field(surface, "offsetY") + oy + 4 - row[1]);
            FxTestSupport.invoke(surface, "repaint");
            double[] moved = box(map, rowPath);
            assertTrue(moved[0] + moved[2] > ox, "the row reaches under the overview");
            move(surface, moved[0] + 10, moved[1] + moved[3] / 2); // on the part the overview leaves free
            assertTrue(canvas.isMouseTransparent(), "the overview lets the pointer through to the row");
            assertTrue(canvas.getOpacity() < 0.5, "and fades");
            move(surface, moved[0] + moved[2] - 6, moved[1] + moved[3] / 2); // now under it: the eye
            assertTrue(canvas.isMouseTransparent(), "it stays out of the way while the pointer is on that row");
            move(surface, 5, 5); // off the rows
            assertFalse(canvas.isMouseTransparent(), "and comes back once the pointer leaves the row");
            assertEquals(1.0, canvas.getOpacity(), 1e-9);
        });
    }

    // --- B8: zoomed out, labelled blocks ---------------------------------------------------------------------

    @Test
    void fitOnABigMapReachesLabelledBlocksAndTheOverviewIsNoStripe() throws Exception {
        List<Path> folders = new ArrayList<>();
        for (int folder = 0; folder < 24; folder++) {
            Path dir = Files.createDirectories(root.resolve(String.format("module%02d", folder)));
            folders.add(dir);
            for (int file = 0; file < 30; file++) {
                Files.writeString(dir.resolve(String.format("Source%02d.java", file)), "class S {}\n");
            }
        }
        Fixture map = openMap(900, 640);
        for (Path folder : folders) {
            FxTestSupport.runOnFx(() -> map.view().revealPath(folder.resolve("Source00.java")));
        }
        waitFor(() -> columnBoxes(map.surface()).size() >= 25, "every module's column is open");
        FxTestSupport.runOnFx(() -> FxTestSupport.invoke(map.surface(), "fitContent"));
        FxTestSupport.runOnFx(() -> {
            double zoom = FxTestSupport.field(map.surface(), "zoom");
            assertTrue(zoom < 0.4, "Fit goes past the old 40% floor: " + zoom);
            assertTrue(
                    (boolean) FxTestSupport.call(map.surface(), "layoutBlockMode", new Class<?>[0]),
                    "below 60% the columns are blocks");
            FxTestSupport.invoke(map.surface(), "paintNow"); // draws the blocks and their labels
            java.util.Map<?, ?> controls = FxTestSupport.field(map.surface(), "columnControls");
            for (Object value : controls.values()) {
                TextField filter = (TextField) FxTestSupport.call(value, "filter", new Class<?>[0]);
                assertFalse(filter.isVisible(), "no column controls on a block");
            }
        });
        // Zoomed in on one stacked column, the overview frame is at least 90 wide.
        FxTestSupport.runOnFx(() -> {
            FxTestSupport.call(
                    map.surface(), "setZoom", new Class<?>[] {double.class, double.class, double.class}, 1.0, 0.0, 0.0);
            FxTestSupport.invoke(map.surface(), "paintNow");
            Object overview = FxTestSupport.field(map.surface(), "overviewBox");
            assertNotNull(overview);
            assertTrue((double) FxTestSupport.call(overview, "width", new Class<?>[0]) >= 90, "not a 28-px stripe");
        });
    }

    // --- B15: compact rows -----------------------------------------------------------------------------------

    @Test
    void compactRowsAreDenserAndOnlyTheColumnInUseShowsItsControls() throws Exception {
        Path a = Files.createDirectories(root.resolve("alpha"));
        Path b = Files.createDirectories(root.resolve("beta"));
        for (int index = 0; index < 12; index++) {
            Files.writeString(a.resolve("A" + index + ".java"), "x\n");
            Files.writeString(b.resolve("B" + index + ".java"), "x\n");
        }
        Fixture map = openMap(1200, 900);
        FxTestSupport.runOnFx(() -> {
            map.view().revealPath(a.resolve("A0.java"));
            map.view().revealPath(b.resolve("B0.java"));
        });
        waitFor(() -> box(map, a.resolve("A1.java")) != null && box(map, b.resolve("B1.java")) != null, "both open");
        double defaultPitch = FxTestSupport.callOnFx(() -> pitch(map, b));
        FxTestSupport.runOnFx(() -> {
            map.view().setCompactRows(true);
            assertTrue(map.view().isCompactRows());
            FxTestSupport.invoke(map.surface(), "paintNow");
            double zoom = FxTestSupport.field(map.surface(), "zoom");
            assertEquals(30 * zoom, pitch(map, b), 0.01, "26 + 4 instead of 32 + 9");
            assertTrue(pitch(map, b) < defaultPitch);
            // The selection is in beta: its controls show; alpha's, hovered by nobody, do not.
            Object alphaControls = controlsOf(map, a);
            Object betaControls = controlsOf(map, b);
            assertTrue(((TextField) FxTestSupport.call(betaControls, "filter", new Class<?>[0])).isVisible());
            assertFalse(((TextField) FxTestSupport.call(alphaControls, "filter", new Class<?>[0])).isVisible());
            // Hovering alpha shows its controls, and its rows stay where they were.
            double[] before = box(map, a.resolve("A3.java"));
            move(map.surface(), before[0] + 10, before[1] + before[3] / 2);
            assertTrue(((TextField) FxTestSupport.call(alphaControls, "filter", new Class<?>[0])).isVisible());
            double[] after = box(map, a.resolve("A3.java"));
            assertEquals(before[1], after[1], 0.01, "the card grows upward: its rows do not move");
            map.view().setCompactRows(false);
            FxTestSupport.invoke(map.surface(), "paintNow");
            assertEquals(defaultPitch, pitch(map, b), 0.01, "off again: the default rows");
        });
    }

    // --- B4: the Overview card, module badges, folder tooltips ----------------------------------------------

    @Test
    void theOverviewCardOpensOnTheFirstOpenOnceAndTheMenuOpensItAgain() throws Exception {
        Files.writeString(root.resolve("README.md"), "# Demo\n\nA demo project for the map.\n");
        Files.writeString(root.resolve("settings.gradle"), "include 'core'\n");
        Path core = Files.createDirectories(root.resolve("core/src/main/java/demo"));
        Files.writeString(root.resolve("core/build.gradle"), "plugins {}\n");
        Files.writeString(core.resolve("DemoApplication.java"), "class DemoApplication {}\n");
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs/guide.md"), "x\n");
        Fixture map = openMap(1200, 800, view -> view.setOverviewOnFirstShow(true));
        waitFor(() -> map.view().overviewCard() != null, "the card opens on the first Map open");
        FxTestSupport.runOnFx(() -> {
            ProjectMapOverviewCard card = map.view().overviewCard();
            assertTrue(card.isVisible());
            assertEquals(tr("project.map.overview.title", "spring"), card.title());
            assertTrue(
                    card.headings()
                            .containsAll(List.of(
                                    tr("project.map.overview.readme"),
                                    tr("project.map.overview.build"),
                                    tr("project.map.overview.entryPoints"),
                                    tr("project.map.overview.docs"))),
                    card.headings().toString());
            assertTrue(card.links().contains("core/"), "the module folder: " + card.links());
            assertTrue(
                    card.links().contains("core/src/main/java/demo/DemoApplication.java"),
                    card.links().toString());
            Set<Path> modules = FxTestSupport.field(map.surface(), "layoutModuleFolders");
            assertTrue(modules.contains(root.resolve("core")), "core holds build.gradle: its row has the badge");
            // The folder's tooltip: where it is in the project and what it holds.
            ProjectMapModel.Entry coreRow = rows(map.surface()).stream()
                    .filter(entry -> entry.path().equals(root.resolve("core")))
                    .findFirst()
                    .orElseThrow();
            String tip = (String) FxTestSupport.call(
                    map.surface(), "tooltipText", new Class<?>[] {ProjectMapModel.Entry.class}, coreRow);
            assertTrue(tip.contains("\ncore/\n") || tip.startsWith("core/"), tip);
            assertTrue(tip.contains(tr("project.map.tooltip.contains", "1", "1", "2")), tip);
            assertTrue(tip.contains(tr("project.map.tooltip.module", "build.gradle")), tip);

            FxTestSupport.<Button>field(card, "close").fire(); // the card's ×
            assertNull(map.view().overviewCard(), "dismissed");
            map.view().setRoot(root); // the same root: nothing reopens by itself
            map.view().openOverview(); // ⋯ → Project overview
        });
        waitFor(() -> map.view().overviewCard() != null, "the menu opens it again");
    }

    // --- A11: ignored and generated rows ---------------------------------------------------------------------

    @Test
    void ignoredAndGeneratedRowsAreDimmedWithAReasonAndCanBeHidden() throws Exception {
        Files.writeString(root.resolve(".gitignore"), "build/\n");
        Files.writeString(root.resolve(".gitattributes"), "zz_generated.*.go linguist-generated=true\n");
        Path build = Files.createDirectories(root.resolve("build/gen"));
        Files.writeString(build.resolve("output_report.txt"), "x\n");
        Files.writeString(root.resolve("zz_generated.openapi.go"), "package x\n");
        Files.createDirectories(root.resolve("node_modules/react"));
        Files.writeString(root.resolve("main.go"), "package main\n");
        Fixture map = openMap(1200, 800);
        FxTestSupport.runOnFx(() -> map.view().revealPath(build.resolve("output_report.txt")));
        waitFor(() -> reason(map, build.resolve("output_report.txt")) != null, "the rows are classified");
        FxTestSupport.runOnFx(() -> {
            assertEquals(ProjectMapIgnored.Reason.IGNORED, reason(map, root.resolve("build")));
            assertEquals(ProjectMapIgnored.Reason.IGNORED, reason(map, build.resolve("output_report.txt")));
            assertEquals(ProjectMapIgnored.Reason.GENERATED, reason(map, root.resolve("zz_generated.openapi.go")));
            assertEquals(ProjectMapIgnored.Reason.DEPENDENCY, reason(map, root.resolve("node_modules")));
            assertNull(reason(map, root.resolve("main.go")), "the project's own code is not dimmed");
            ProjectMapModel.Entry generated = rows(map.surface()).stream()
                    .filter(entry -> entry.name().equals("zz_generated.openapi.go"))
                    .findFirst()
                    .orElseThrow();
            String tip = (String) FxTestSupport.call(
                    map.surface(), "tooltipText", new Class<?>[] {ProjectMapModel.Entry.class}, generated);
            assertTrue(tip.contains(tr("project.map.ignored.generated")), tip);

            map.view().setHideIgnored(true);
            FxTestSupport.invoke(map.surface(), "paintNow");
            assertNull(box(map, root.resolve("build")), "hidden: the ignored folder");
            assertNull(box(map, build.resolve("output_report.txt")), "and what is in it");
            assertNull(box(map, root.resolve("zz_generated.openapi.go")));
            assertNotNull(box(map, root.resolve("main.go")));
            assertTrue(FxTestSupport.<CheckMenuItem>field(map.view(), "layoutHideIgnoredOption")
                    .isSelected());
            map.view().setHideIgnored(false);
            FxTestSupport.invoke(map.surface(), "paintNow");
            assertNotNull(box(map, root.resolve("build")), "back, dimmed");
        });
    }

    // --- E4 and B19 ------------------------------------------------------------------------------------------

    @Test
    void aCrumbKeepsTheUnderscoreOfItsNameAndTheHelpSaysHowToSearch() throws Exception {
        Path generated = Files.createDirectories(root.resolve("pkg/generated/openapi"));
        Path file = Files.writeString(generated.resolve("zz_generated.openapi.go"), "package openapi\n");
        Files.writeString(root.resolve("a_b.go"), "package x\n");
        Fixture map = openMap(1400, 800);
        FxTestSupport.runOnFx(() -> map.view().revealPath(file));
        waitFor(() -> file.equals(FxTestSupport.field(map.surface(), "selected")), "selected");
        FxTestSupport.runOnFx(() -> {
            HBox crumbs = FxTestSupport.field(map.view(), "breadcrumbs");
            List<String> texts = new ArrayList<>();
            for (Node child : crumbs.getChildren()) {
                if (child instanceof Button crumb) {
                    assertFalse(crumb.isMnemonicParsing(), crumb.getText());
                    texts.add(crumb.getText());
                }
            }
            assertTrue(texts.contains("zz_generated.openapi.go"), texts.toString());

            Node help = (Node) ProjectMapHelp.build();
            List<String> labels = new ArrayList<>();
            collectLabels(help, labels);
            assertTrue(labels.contains(tr("project.map.help.search")), labels.toString());
            assertTrue(labels.contains(tr("project.map.help.searchField")));
            assertTrue(labels.contains(tr("project.map.help.where")));
            assertTrue(labels.contains(tr("project.map.help.reveal")));
            assertTrue(labels.contains(tr("project.map.help.fit")));
        });
    }

    // --- B10: siblings drawn apart ---------------------------------------------------------------------------

    @Test
    void longTwinsInAColumnAreDrawnApart() throws Exception {
        Path support = Files.createDirectories(root.resolve("support"));
        Path shorter = Files.writeString(support.resolve("AbstractRefreshableApplicationContextSupportBase.java"), "x");
        Path longer =
                Files.writeString(support.resolve("AbstractRefreshableConfigApplicationContextSupportBase.java"), "x");
        Fixture map = openMap(1200, 800);
        FxTestSupport.runOnFx(() -> map.view().revealPath(shorter));
        waitFor(() -> box(map, longer) != null, "both rows");
        FxTestSupport.runOnFx(() -> {
            String a = drawnLabel(map, shorter);
            String b = drawnLabel(map, longer);
            assertTrue(a.contains("…") && b.contains("…"), "both are cut: " + a + " / " + b);
            assertNotEquals(a, b);
            assertTrue(b.contains("Config"), "the part that tells it apart is kept: " + b);
        });
    }

    // --- helpers ------------------------------------------------------------------------------------

    private static boolean intersect(double[] a, double[] b) {
        return a[0] < b[0] + b[2] - 0.5
                && b[0] < a[0] + a[2] - 0.5
                && a[1] < b[1] + b[3] - 0.5
                && b[1] < a[1] + a[3] - 0.5;
    }

    /** The columns as last projected, {@code {x, y, w, h}} on screen. */
    static List<double[]> columnBoxes(Region surface) {
        List<?> boxes = FxTestSupport.field(surface, "columnBoxes");
        List<double[]> out = new ArrayList<>();
        for (Object box : List.copyOf(boxes)) {
            out.add(new double[] {
                (double) FxTestSupport.call(box, "x", new Class<?>[0]),
                (double) FxTestSupport.call(box, "y", new Class<?>[0]),
                (double) FxTestSupport.call(box, "width", new Class<?>[0]),
                (double) FxTestSupport.call(box, "height", new Class<?>[0])
            });
        }
        return out;
    }

    private record Fixture(ProjectMapView view, Stage stage, Region surface) {}

    private Fixture openMap(double width, double height) throws Exception {
        return openMap(width, height, view -> {});
    }

    private Fixture openMap(double width, double height, java.util.function.Consumer<ProjectMapView> before)
            throws Exception {
        Path project = root;
        Fixture fixture = FxTestSupport.callOnFx(() -> {
            ProjectMapView view = new ProjectMapView(path -> {}, path -> false, path -> false, path -> null);
            views.add(view);
            Stage stage = new Stage();
            stages.add(stage);
            Scene scene = new Scene(view, width, height);
            scene.getStylesheets()
                    .add(ProjectMapLayoutReview3FxTest.class
                            .getResource("/com/editora/styles/app.css")
                            .toExternalForm());
            stage.setScene(scene);
            stage.show();
            view.applyCss();
            view.layout();
            view.setRememberedFlow(ProjectMapView.FlowDirection.LEFT_TO_RIGHT.name(), ignored -> {});
            view.setRememberedNavigation(true, false, (keep, focus) -> {});
            before.accept(view);
            view.setRoot(project);
            return new Fixture(view, stage, FxTestSupport.field(view, "surface"));
        });
        waitFor(() -> !rows(fixture.surface()).isEmpty(), "the first column");
        return fixture;
    }

    /** A row as last projected, {@code {x, y, w, h}}, or null when it is not laid out. */
    private static double @org.jspecify.annotations.Nullable [] box(Fixture map, Path path) {
        List<?> boxes = FxTestSupport.field(map.surface(), "boxes");
        for (Object box : List.copyOf(boxes)) {
            ProjectMapModel.Entry entry = (ProjectMapModel.Entry) FxTestSupport.call(box, "entry", new Class<?>[0]);
            if (entry.path().equals(path)) {
                return new double[] {
                    (double) FxTestSupport.call(box, "x", new Class<?>[0]),
                    (double) FxTestSupport.call(box, "y", new Class<?>[0]),
                    (double) FxTestSupport.call(box, "width", new Class<?>[0]),
                    (double) FxTestSupport.call(box, "height", new Class<?>[0])
                };
            }
        }
        return null;
    }

    /** The distance between the first two rows of {@code folder}'s column. */
    private static double pitch(Fixture map, Path folder) {
        List<Double> tops = new ArrayList<>();
        List<?> boxes = FxTestSupport.field(map.surface(), "boxes");
        for (Object box : boxes) {
            ProjectMapModel.Entry entry = (ProjectMapModel.Entry) FxTestSupport.call(box, "entry", new Class<?>[0]);
            if (folder.equals(entry.parent())) {
                tops.add((double) FxTestSupport.call(box, "y", new Class<?>[0]));
            }
        }
        return tops.get(1) - tops.get(0);
    }

    private static Object controlsOf(Fixture map, Path folder) {
        java.util.Map<?, ?> values = FxTestSupport.field(map.surface(), "columnControls");
        return values.entrySet().stream()
                .filter(entry -> folder.equals(FxTestSupport.call(entry.getKey(), "parent", new Class<?>[0])))
                .map(java.util.Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no controls for " + folder));
    }

    private static ProjectMapIgnored.@org.jspecify.annotations.Nullable Reason reason(Fixture map, Path path) {
        ProjectMapOrientation orientation = FxTestSupport.field(map.view(), "orientation");
        return orientation.reason(path);
    }

    private static String drawnLabel(Fixture map, Path path) {
        ProjectMapModel.Entry entry = rows(map.surface()).stream()
                .filter(row -> row.path().equals(path))
                .findFirst()
                .orElseThrow();
        double[] row = box(map, path);
        double zoom = FxTestSupport.field(map.surface(), "zoom");
        double extra = (double)
                FxTestSupport.call(map.surface(), "rowExtraWidth", new Class<?>[] {ProjectMapModel.Entry.class}, entry);
        return (String) FxTestSupport.call(
                map.surface(),
                "layoutElide",
                new Class<?>[] {ProjectMapModel.Entry.class, String.class, double.class, double.class},
                entry,
                entry.name(),
                12 * zoom,
                row[2] - extra * zoom);
    }

    private static void move(Region surface, double x, double y) {
        javafx.geometry.Point2D scene = surface.localToScene(x, y);
        Event.fireEvent(
                surface,
                new MouseEvent(
                        MouseEvent.MOUSE_MOVED,
                        scene.getX(),
                        scene.getY(),
                        scene.getX(),
                        scene.getY(),
                        MouseButton.NONE,
                        0,
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
                        new javafx.scene.input.PickResult(surface, scene.getX(), scene.getY())));
    }

    private static void setDouble(Region surface, String field, double value) {
        try {
            var declared = surface.getClass().getDeclaredField(field);
            declared.setAccessible(true);
            declared.setDouble(surface, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void collectLabels(Node node, List<String> labels) {
        if (node instanceof javafx.scene.control.Labeled labeled && labeled.getText() != null) {
            labels.add(labeled.getText());
        }
        if (node instanceof javafx.scene.Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> collectLabels(child, labels));
        }
    }

    private ProjectPanel openPanel() throws Exception {
        ProjectPanel panel = FxTestSupport.callOnFx(() -> {
            ProjectPanel p =
                    DisposedPanels.track(new ProjectPanel(x -> {}, (a, b) -> {}, x -> {}, x -> false, x -> false));
            p.setRoot(root);
            p.openMapWindow(false);
            return p;
        });
        ProjectMapView view = FxTestSupport.field(panel, "mapView");
        Region surface = FxTestSupport.field(view, "surface");
        waitFor(() -> !rows(surface).isEmpty(), "the first column");
        return panel;
    }

    private static void search(ProjectPanel panel, String query) throws Exception {
        FxTestSupport.runOnFx(() -> {
            FxTestSupport.<TextField>field(panel, "mapFilterField").setText(query);
            FxTestSupport.<javafx.animation.PauseTransition>field(panel, "mapFilterDebounce")
                    .stop();
            FxTestSupport.invoke(panel, "searchMap");
        });
    }

    private static List<ProjectMapModel.Entry> rows(Region surface) {
        return FxTestSupport.field(surface, "entries");
    }

    static void waitFor(BooleanSupplier condition, String message) throws Exception {
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
