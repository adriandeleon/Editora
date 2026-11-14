package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.Region;
import javafx.scene.text.Text;
import javafx.stage.Stage;

import com.editora.git.GitRecentChanges;
import com.editora.git.GitService;
import com.editora.git.GitServiceRecentChangesTest;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Map review 4, the activity group, in a live Map: a commit on a clean tree moves the heat (B1), the Overview names a
 * folder beside a long owner list (B8) and says its counts are of the folder's own files (B10), turning the option on
 * says why nothing is tinted and a capped read says so in the tooltips (B11), and a shallow clone's cut is not a
 * card's last change (C7).
 */
@ExtendWith(DisposedPanels.class)
@Tag("fx")
class ProjectMapActivityReview4FxTest {

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
    void aCommitOnACleanTreeMovesTheHeatOnTheNextRefresh() throws Exception {
        ProjectPanel panel = FxTestSupport.callOnFx(() -> {
            ProjectPanel p = DisposedPanels.track(new ProjectPanel(x -> {}, (a, b) -> {}, x -> {}, x -> false));
            p.setMapGit(git);
            p.setRoot(root);
            p.openMapWindow(false);
            return p;
        });
        try {
            ProjectMapView view = FxTestSupport.field(panel, "mapView");
            FxTestSupport.runOnFx(() -> view.setShowRecentChanges(true));
            waitFor(() -> !view.activity.changes().head().isEmpty() && !view.activity.reading(), "the history");
            FxTestSupport.runOnFx(() -> panel.setGitStatus(Map.of())); // the first push after opening always passes
            waitFor(() -> !view.activity.reading(), "asked again");
            String before = FxTestSupport.callOnFx(() -> view.activity.changes().head());

            // A commit in a terminal on a clean tree: the status is {} before and after.
            GitServiceRecentChangesTest.write(root, "pkg/cold/cold.go", "warmed");
            GitServiceRecentChangesTest.commit(root, 0, "Ada Lovelace", "warm cold");
            FxTestSupport.runOnFx(() -> panel.setGitStatus(Map.of()));
            waitFor(() -> !view.activity.changes().head().equals(before), "the heat follows the new HEAD");
            waitFor(
                    () -> view.activity.heatLevel(root.resolve("pkg/cold/cold.go"), false) > 0,
                    "the new commit warms its file");
        } finally {
            FxTestSupport.runOnFx(() -> panel.mapWindow().closeByUser());
        }
    }

    @Test
    void theOverviewNamesAFolderBesideALongOwnerListAndCountsItsOwnFiles() throws Exception {
        Files.createDirectories(root.resolve("release-engineering"));
        Files.writeString(root.resolve("release-engineering/notes.md"), "n\n");
        Files.writeString(
                root.resolve(".github/CODEOWNERS"),
                "* @core\n/pkg/hot/ @hot-team\n/release-engineering/ @release-engineering-approvers"
                        + " @release-managers @release-team-subproject-leads @satyampsoni-with-a-long-handle"
                        + " @fifth-owner\n");
        Fixture map = openMap(root);
        FxTestSupport.runOnFx(() -> map.view().openOverview());
        waitFor(
                () -> map.view().overviewCard() != null
                        && map.view().overviewCard().activityTexts().contains("release-engineering/")
                        && map.view().overviewCard().activityTexts().contains("pkg/hot/"),
                "the card lists the history and the owners");
        FxTestSupport.runOnFx(() -> {
            ProjectMapOverviewCard card = map.view().overviewCard();
            try { // the narrowest the card goes
                java.lang.reflect.Field width = ProjectMapOverviewCard.class.getDeclaredField("preferredWidth");
                width.setAccessible(true);
                width.setDouble(card, ProjectMapOverviewCard.MIN_WIDTH);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
            card.resize(ProjectMapOverviewCard.MIN_WIDTH, card.getHeight());
        });
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> {
            map.view().overviewCard().applyCss();
            map.view().overviewCard().layout();
        });
        FxTestSupport.runOnFx(() -> {
            ProjectMapOverviewCard card = map.view().overviewCard();
            List<String> texts = card.activityTexts();
            GitRecentChanges.Stat hot = map.view().activity.changes().direct().get("pkg/hot");
            String when =
                    GitBlameCoordinator.relativeTimeLabel(hot.lastEpochSeconds(), System.currentTimeMillis() / 1000);
            assertEquals(
                    tr("project.map.activity.mostChangedOwn", hot.commits(), hot.lastAuthor(), when),
                    texts.get(texts.indexOf("pkg/hot/") + 1),
                    "B10: the count says it is the folder's own files'");
            assertTrue(card.getWidth() < ProjectMapOverviewCard.DEFAULT_WIDTH, "narrowed: " + card.getWidth());
            Button owned = links(card).stream()
                    .filter(link -> link.getText().equals("release-engineering/"))
                    .reduce((first, last) -> last)
                    .orElseThrow();
            String shown = ((Text) owned.lookup(".text")).getText();
            assertEquals("release-engineering/", shown, "B8: the folder's name is not squeezed to an ellipsis");
        });
    }

    @Test
    void turningTheOptionOnSaysWhyNothingIsTintedAndACappedReadSaysSo() throws Exception {
        Path shallow = temp.resolve("shallow");
        GitServiceRecentChangesTest.git(
                temp, null, "clone", "-q", "--depth", "1", root.toUri().toString(), shallow.toString());
        Fixture map = openMap(shallow.toRealPath());
        List<String> said = new CopyOnWriteArrayList<>();
        FxTestSupport.runOnFx(() -> {
            map.view().setOnStatus(said::add);
            map.view().setShowRecentChanges(true);
        });
        waitFor(() -> !said.isEmpty(), "a word on the status line");
        assertTrue(
                said.getFirst()
                        .startsWith(tr("project.map.activity.shallowEmpty", "§").substring(0, 20)),
                said.toString());

        // A read capped at the newest commits is not "in 90 days".
        Fixture capped = openMap(root);
        Path hotFile = root.resolve("pkg/hot/hot.go");
        GitRecentChanges.Stat stat = new GitRecentChanges.Stat(3, System.currentTimeMillis() / 1000, "Ada");
        GitRecentChanges.Result cut = new GitRecentChanges.Result(
                "h",
                false,
                10_000,
                true,
                Map.of("pkg/hot/hot.go", stat),
                Map.of("", stat, "pkg", stat, "pkg/hot", stat),
                Map.of("pkg/hot", stat),
                0,
                "");
        List<String> cappedSaid = new CopyOnWriteArrayList<>();
        FxTestSupport.runOnFx(() -> {
            capped.view().activity.setSourcesForTest((base, then) -> then.accept(cut), null, null);
            capped.view().setOnStatus(cappedSaid::add);
            capped.view().revealPath(hotFile);
            capped.view().setShowRecentChanges(true);
        });
        waitFor(() -> box(capped, hotFile) && capped.view().activity.changes() == cut, "the capped read");
        FxTestSupport.runOnFx(() -> {
            String tip = tooltip(capped, hotFile);
            assertTrue(
                    tip.contains(tr("project.map.activity.commitsNewest", 3, "Ada", "", 10_000)
                            .split(" · ")[0]),
                    tip);
            assertFalse(
                    tip.contains(
                            tr("project.map.activity.commits", 3, "Ada", "").split(" · ")[0]),
                    tip);
        });
        waitFor(() -> !cappedSaid.isEmpty(), "the cut window is told");
        assertEquals(tr("project.map.activity.truncated", ProjectMapOverviewCard.count(10_000)), cappedSaid.getFirst());
    }

    @Test
    void aShallowClonesCutIsNotACardsLastChange() throws Exception {
        FxTestSupport.runOnFx(() -> {
            ProjectMapPreview card = new ProjectMapPreview(ignored -> {});
            card.setLastChange(new GitService.LastChange("aa8de068", "kubernetes-prow[bot]", 1, "Merge", false, true));
            assertEquals(tr("project.map.preview.lastChangeShallow"), card.lastChangeText());
            assertFalse(card.lastChangeText().contains("kubernetes-prow"), "the clone's commit is not named");
            card.setLastChange(new GitService.LastChange("bb", "Ada", 1, "real", false));
            assertNotEquals(tr("project.map.preview.lastChangeShallow"), card.lastChangeText());
        });
    }

    // --- helpers ----------------------------------------------------------------------------------------------

    private record Fixture(ProjectMapView view, Stage stage, Region surface) {}

    private Fixture openMap(Path project) throws Exception {
        Fixture fixture = FxTestSupport.callOnFx(() -> {
            ProjectMapView view = new ProjectMapView(path -> {}, path -> false, path -> false, path -> null);
            views.add(view);
            Stage stage = new Stage();
            stages.add(stage);
            Scene scene = new Scene(view, 1300, 820);
            scene.setUserAgentStylesheet(Themes.stylesheetFor("Editora Light"));
            scene.getStylesheets()
                    .add(ProjectMapActivityReview4FxTest.class
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

    private static List<Button> links(Node node) {
        List<Button> found = new ArrayList<>();
        if (node instanceof Button button) {
            found.add(button);
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                found.addAll(links(child));
            }
        }
        return found;
    }

    private static String tooltip(Fixture map, Path path) {
        ProjectMapModel.Entry entry = rows(map.surface()).stream()
                .filter(row -> row.path().equals(path))
                .findFirst()
                .orElseThrow();
        return (String)
                FxTestSupport.call(map.surface(), "tooltipText", new Class<?>[] {ProjectMapModel.Entry.class}, entry);
    }

    private static boolean box(Fixture map, Path path) {
        return rows(map.surface()).stream().anyMatch(row -> row.path().equals(path));
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
        assertNotNull((@Nullable Object) null, "timed out: " + message);
    }
}
