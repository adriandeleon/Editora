package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javafx.scene.Scene;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import com.editora.git.GitService;
import com.editora.git.GitServiceRecentChangesTest;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Show recent changes" and the owners in a live Map over a repository with real history (Map explore positions 3,
 * 4): hot rows are tinted and keep a meter when selected, in Editora Light and Dark; tooltips say how many commits
 * and whose was last, and who owns the row; the Overview card lists the most changed folders and the owners; a
 * shallow clone says why it shows nothing.
 */
@ExtendWith(DisposedPanels.class)
@Tag("fx")
class ProjectMapActivityFxTest {

    @TempDir
    Path temp;

    private Path root;
    private final GitService git = new GitService();
    private final List<Stage> stages = new ArrayList<>();
    private final List<ProjectMapView> views = new ArrayList<>();

    @BeforeAll
    static void startToolkit() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @BeforeEach
    void makeRepo() throws Exception {
        root = GitServiceRecentChangesTest.history(temp.resolve("project")).toRealPath();
        GitServiceRecentChangesTest.write(root, "vendor/lib/lib.go", "vendored, updated");
        GitServiceRecentChangesTest.commit(root, 1, "Ada Lovelace", "bump vendor");
    }

    @AfterEach
    void closeViews() throws Exception {
        FxTestSupport.runOnFx(() -> {
            views.forEach(ProjectMapView::dispose);
            stages.forEach(Stage::close);
        });
        git.shutdown();
    }

    @Test
    void hotRowsAreTintedAndTheirTooltipsSayWhoChangedThemAndWhoOwnsThem() throws Exception {
        Fixture map = openMap(root, "Editora Light");
        Path hot = root.resolve("pkg/hot/hot.go");
        Path still = root.resolve("pkg/hot/still.go");
        FxTestSupport.runOnFx(() -> map.view().revealPath(hot));
        waitFor(() -> box(map, still) != null && box(map, hot) != null, "pkg/hot is open");
        ProjectMapActivity activity = FxTestSupport.field(map.view(), "activity");
        FxTestSupport.runOnFx(() -> {
            assertFalse(map.view().isShowRecentChanges(), "off by default");
            assertEquals(0, activity.heatLevel(hot, false));
        });

        FxTestSupport.runOnFx(() -> map.view().setShowRecentChanges(true));
        waitFor(() -> activity.changes().commits() > 0, "the history is read");
        waitFor(() -> activity.owners(hot) != null, "the owners are worked out");
        FxTestSupport.runOnFx(() -> {
            assertTrue(FxTestSupport.<CheckMenuItem>field(map.view(), "activityRecentChangesOption")
                    .isSelected());
            assertEquals(ProjectMapActivity.LEVELS, activity.heatLevel(hot, false), "the busiest file");
            assertEquals(0, activity.heatLevel(still, false), "untouched in 90 days");
            assertTrue(activity.heatLevel(root.resolve("pkg/hot/warm.go"), false) > 0);
            assertTrue(activity.heatLevel(root.resolve("pkg"), true) > 0, "folders aggregate");

            String tip = tooltip(map, hot);
            com.editora.git.GitRecentChanges.Stat stat = activity.stat(hot, false);
            String when =
                    GitBlameCoordinator.relativeTimeLabel(stat.lastEpochSeconds(), System.currentTimeMillis() / 1000);
            assertTrue(tip.contains(tr("project.map.activity.commits", 6, "Grace Hopper", when)), tip);
            assertTrue(tip.contains(tr("project.map.activity.owners", "@hot-team", ".github/CODEOWNERS")), tip);
            assertFalse(tooltip(map, still).contains("Grace Hopper"), "no change: no line");
        });
        waitFor(() -> activity.owners(still) != null, "the quiet file's owners");
        assertTrue(FxTestSupport.callOnFx(() -> tooltip(map, still))
                .contains(tr("project.map.activity.owners", "@hot-team", ".github/CODEOWNERS")));

        // A dimmed (vendored) row keeps its heat: dimming fades it, it does not hide that it changed.
        Path vendored = root.resolve("vendor/lib/lib.go");
        FxTestSupport.runOnFx(() -> map.view().revealPath(vendored));
        waitFor(() -> box(map, vendored) != null && reason(map, vendored) != null, "the vendored row is dimmed");
        FxTestSupport.runOnFx(() -> assertTrue(activity.heatLevel(vendored, false) > 0));

        FxTestSupport.runOnFx(() -> {
            map.view().setShowRecentChanges(false);
            assertEquals(0, activity.heatLevel(hot, false));
            assertFalse(tooltip(map, hot).contains("Grace Hopper"), "off: no commit line");
            assertTrue(tooltip(map, hot).contains("@hot-team"), "the owners stay");
        });
    }

    @Test
    void theTintAndMeterReadInLightAndDarkAndTheSelectionKeepsItsMeter() throws Exception {
        for (String theme : List.of("Editora Light", "Editora Dark")) {
            Fixture map = openMap(root, theme);
            Path hot = root.resolve("pkg/hot/hot.go");
            Path still = root.resolve("pkg/hot/still.go");
            FxTestSupport.runOnFx(() -> map.view().revealPath(still)); // the quiet row holds the selection
            waitFor(() -> box(map, hot) != null && box(map, still) != null, theme + ": pkg/hot is open");
            ProjectMapActivity activity = FxTestSupport.field(map.view(), "activity");
            FxTestSupport.runOnFx(() -> map.view().setShowRecentChanges(true));
            waitFor(() -> activity.changes().commits() > 0, theme + ": the history is read");
            FxTestSupport.runOnFx(() -> {
                FxTestSupport.invoke(map.surface(), "paintNow");
                WritableImage shot = map.stage().getScene().snapshot(null);
                Color hotFill = fillOf(map, shot, hot);
                Color quietFill = fillOf(map, shot, root.resolve("pkg/hot/quiet.go"));
                assertTrue(
                        warmth(hotFill) > warmth(quietFill) + 0.02,
                        theme + ": the hot row is warmer than a quiet one " + hotFill + " vs " + quietFill);
                Color meterHot = meterOf(map, shot, hot);
                assertTrue(
                        ProjectMapView.contrastRatio(meterHot, hotFill) >= 2.5,
                        theme + ": the meter stands off its row " + meterHot + " on " + hotFill);

                // The selection's fill is its own; the meter still shows the heat.
                map.view().revealPath(hot);
                FxTestSupport.invoke(map.surface(), "paintNow");
                WritableImage selected = map.stage().getScene().snapshot(null);
                Color selectedFill = fillOf(map, selected, hot);
                Color selectedMeter = meterOf(map, selected, hot);
                assertTrue(
                        ProjectMapView.contrastRatio(selectedMeter, selectedFill) >= 2.5,
                        theme + ": a selected hot row keeps its meter " + selectedMeter + " on " + selectedFill);
            });
        }
    }

    @Test
    void theOverviewCardListsTheMostChangedFoldersAndTheOwners() throws Exception {
        Fixture map = openMap(root, "Editora Light");
        FxTestSupport.runOnFx(() -> map.view().openOverview());
        waitFor(
                () -> map.view().overviewCard() != null
                        && map.view().overviewCard().activityTexts().contains("pkg/hot/")
                        && map.view().overviewCard().activityTexts().contains("@core"),
                "the card lists the history and the owners");
        FxTestSupport.runOnFx(() -> {
            ProjectMapOverviewCard card = map.view().overviewCard();
            assertEquals(
                    List.of(tr("project.map.activity.mostChanged"), tr("project.map.activity.ownersHeading")),
                    card.activityHeadings());
            List<String> texts = card.activityTexts();
            assertEquals("pkg/hot/", texts.get(1), "the busiest folder first: " + texts);
            assertTrue(texts.contains(tr("project.map.activity.project")), texts.toString());
            assertFalse(texts.contains("pkg/"), "a folder owned as the project is is not repeated " + texts);
            assertFalse(map.view().isShowRecentChanges(), "the card reads the history without tinting the rows");
        });
    }

    @Test
    void aShallowCloneSaysItHasNoHistoryToShow() throws Exception {
        Path shallow = temp.resolve("shallow");
        GitServiceRecentChangesTest.git(
                temp, null, "clone", "-q", "--depth", "1", root.toUri().toString(), shallow.toString());
        Fixture map = openMap(shallow.toRealPath(), "Editora Light");
        FxTestSupport.runOnFx(() -> map.view().openOverview());
        waitFor(
                () -> map.view().overviewCard() != null
                        && map.view().overviewCard().activityTexts().stream()
                                .anyMatch(text -> text.startsWith(tr("project.map.activity.shallowEmpty", "§")
                                        .substring(0, 20))),
                "the card says the clone is shallow");
        ProjectMapActivity activity = FxTestSupport.field(map.view(), "activity");
        FxTestSupport.runOnFx(() -> {
            map.view().setShowRecentChanges(true);
            assertTrue(activity.changes().shallow());
            assertEquals(0, activity.heatLevel(shallow.resolve("pkg/hot/hot.go"), false), "nothing is tinted");
        });
    }

    // --- helpers ----------------------------------------------------------------------------------------------

    private record Fixture(ProjectMapView view, Stage stage, Region surface) {}

    private Fixture openMap(Path project, String theme) throws Exception {
        Fixture fixture = FxTestSupport.callOnFx(() -> {
            ProjectMapView view = new ProjectMapView(path -> {}, path -> false, path -> false, path -> null);
            views.add(view);
            Stage stage = new Stage();
            stages.add(stage);
            Scene scene = new Scene(view, 1300, 820);
            scene.setUserAgentStylesheet(Themes.stylesheetFor(theme));
            scene.getStylesheets()
                    .add(ProjectMapActivityFxTest.class
                            .getResource("/com/editora/styles/app.css")
                            .toExternalForm());
            stage.setScene(scene);
            stage.show();
            view.applyCss();
            view.layout();
            view.setRememberedFlow(ProjectMapView.FlowDirection.LEFT_TO_RIGHT.name(), ignored -> {});
            view.setRememberedNavigation(true, false, (keep, focus) -> {});
            view.setActivityGit(git);
            view.setRoot(project);
            return new Fixture(view, stage, FxTestSupport.field(view, "surface"));
        });
        waitFor(() -> !rows(fixture.surface()).isEmpty(), "the first column");
        return fixture;
    }

    private static String tooltip(Fixture map, Path path) {
        ProjectMapModel.Entry entry = rows(map.surface()).stream()
                .filter(row -> row.path().equals(path))
                .findFirst()
                .orElseThrow();
        return (String)
                FxTestSupport.call(map.surface(), "tooltipText", new Class<?>[] {ProjectMapModel.Entry.class}, entry);
    }

    private static ProjectMapIgnored.@org.jspecify.annotations.Nullable Reason reason(Fixture map, Path path) {
        ProjectMapOrientation orientation = FxTestSupport.field(map.view(), "orientation");
        return orientation.reason(path);
    }

    /** The row's fill, sampled right of its icon and above its label. */
    private static @org.jspecify.annotations.Nullable Color fillOf(Fixture map, WritableImage shot, Path path) {
        return pixel(map, shot, path, 0.62, 4);
    }

    /** The start of the row's heat meter, along its foot. */
    private static Color meterOf(Fixture map, WritableImage shot, Path path) {
        double[] box = box(map, path);
        double zoom = FxTestSupport.field(map.surface(), "zoom");
        return at(map, shot, box[0] + 12 * zoom, box[1] + box[3] - 4 * zoom + Math.max(2, 2.5 * zoom) / 2);
    }

    private static @org.jspecify.annotations.Nullable Color pixel(
            Fixture map, WritableImage shot, Path path, double across, double down) {
        double[] box = box(map, path);
        return box == null ? null : at(map, shot, box[0] + box[2] * across, box[1] + down);
    }

    private static Color at(Fixture map, WritableImage shot, double x, double y) {
        javafx.geometry.Point2D scene = map.surface().localToScene(x, y);
        return shot.getPixelReader().getColor((int) Math.round(scene.getX()), (int) Math.round(scene.getY()));
    }

    private static double warmth(@org.jspecify.annotations.Nullable Color color) {
        return color == null ? 0 : color.getRed() - color.getBlue();
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

    private static List<ProjectMapModel.Entry> rows(Region surface) {
        return FxTestSupport.field(surface, "entries");
    }

    private static void waitFor(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (FxTestSupport.callOnFx(condition::getAsBoolean)) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertNotNull(null, "timed out: " + message);
    }
}
