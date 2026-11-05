package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.ui.ProjectMapLayoutReview3FxTest.waitFor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Project Map's Overview card in a live Map: its −/+ grow and shrink its text as a code card's do, and the next
 * Overview opens at the same size; and it opens first on the map, before the project's own column the way the map
 * flows — left of it, right of it, above it or below it.
 */
@ExtendWith(DisposedPanels.class)
class ProjectMapOverviewCardFxTest {

    @TempDir
    Path temp;

    private Path root;
    private final List<Stage> stages = new ArrayList<>();
    private final List<ProjectMapView> views = new ArrayList<>();

    @BeforeAll
    static void startToolkit() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @BeforeEach
    void makeRoot() throws Exception {
        root = Files.createDirectories(temp.resolve("demo")).toRealPath();
        Files.writeString(root.resolve("README.md"), "# Demo\n\nA demo project for the map.\n");
        Files.writeString(root.resolve("pom.xml"), "<project/>\n");
        Files.createDirectories(root.resolve("src/demo"));
        Files.writeString(root.resolve("src/demo/App.java"), "class App {}\n");
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs/guide.md"), "x\n");
    }

    @AfterEach
    void closeViews() throws Exception {
        FxTestSupport.runOnFx(() -> {
            views.forEach(ProjectMapView::dispose);
            stages.forEach(Stage::close);
        });
    }

    @Test
    void minusAndPlusResizeTheSectionsTextAndTheNextOverviewKeepsTheSize() throws Exception {
        ProjectMapView view = openMap(ProjectMapView.FlowDirection.LEFT_TO_RIGHT);
        FxTestSupport.runOnFx(view::openOverview);
        waitFor(() -> view.overviewCard() != null, "the card opens");
        FxTestSupport.runOnFx(() -> {
            ProjectMapOverviewCard card = view.overviewCard();
            card.applyCss();
            Label title = FxTestSupport.field(card, "title");
            Labeled heading = labelled(card, "project-map-overview-heading");
            Labeled link = labelled(card, "project-map-overview-link");
            double titleSize = title.getFont().getSize();
            assertEquals(ProjectMapOverviewCard.TEXT_SIZE, link.getFont().getSize(), 0.01, "a link at 12px");
            assertEquals(11, heading.getFont().getSize(), 0.1, "a heading a point smaller");

            Button plus = FxTestSupport.field(card, "zoomIn");
            plus.fire();
            plus.fire();
            card.applyCss();
            assertEquals(1.21, card.textZoom(), 0.001);
            assertEquals(12 * 1.21, link.getFont().getSize(), 0.05, "+ grows the links");
            assertEquals(11 * 1.21, heading.getFont().getSize(), 0.1, "and the headings with them");
            assertEquals(titleSize, title.getFont().getSize(), 0.01, "the title bar keeps its size");

            Button minus = FxTestSupport.field(card, "zoomOut");
            minus.fire();
            card.applyCss();
            assertEquals(1.1, card.textZoom(), 0.001);
            assertEquals(12 * 1.1, link.getFont().getSize(), 0.05, "− shrinks them again");

            FxTestSupport.<Button>field(card, "close").fire();
            assertNull(view.overviewCard());
            view.openOverview();
        });
        waitFor(() -> view.overviewCard() != null, "the card opens again");
        FxTestSupport.runOnFx(() -> {
            ProjectMapOverviewCard card = view.overviewCard();
            assertEquals(1.1, card.textZoom(), 0.001, "at the size it was last given");
            for (int i = 0; i < 30; i++) {
                FxTestSupport.<Button>field(card, "zoomOut").fire();
            }
            assertEquals(0.5, card.textZoom(), 0.001, "never below half");
        });
    }

    @Test
    void theOverviewOpensBeforeTheProjectsColumnInEveryFlow() throws Exception {
        for (ProjectMapView.FlowDirection flow : ProjectMapView.FlowDirection.values()) {
            ProjectMapView view = openMap(flow);
            FxTestSupport.runOnFx(view::openOverview);
            waitFor(() -> view.overviewCard() != null, "the card opens, " + flow);
            FxTestSupport.runOnFx(() -> {
                ProjectMapOverviewCard card = view.overviewCard();
                MapSurface surface = FxTestSupport.field(view, "surface");
                double[] column = surface.cardRootColumn();
                assertNotNull(column, flow.name());
                double scale = card.mapScale();
                double x = card.getLayoutX();
                double y = card.getLayoutY();
                double right = x + card.getWidth() * scale;
                double bottom = y + card.getHeight() * scale;
                String where = flow + ": card " + x + "," + y + " → " + right + "," + bottom + ", column "
                        + java.util.Arrays.toString(column);
                switch (flow) {
                    case LEFT_TO_RIGHT -> {
                        assertTrue(right <= column[0] + 0.5, "left of the column: " + where);
                        assertEquals(column[1], y, 1, "level with its top: " + where);
                    }
                    case RIGHT_TO_LEFT -> {
                        assertTrue(x >= column[0] + column[2] - 0.5, "right of the column: " + where);
                        assertEquals(column[1], y, 1, "level with its top: " + where);
                    }
                    case TOP_TO_BOTTOM -> assertTrue(bottom <= column[1] + 0.5, "above the column: " + where);
                    case BOTTOM_TO_TOP -> assertTrue(y >= column[1] + column[3] - 0.5, "below the column: " + where);
                }
                assertTrue(
                        x >= 0 && y >= 0 && right <= surface.getWidth() && bottom <= surface.getHeight(),
                        "in view: " + where);
                assertTrue(
                        column[0] >= 0
                                && column[1] >= 0
                                && column[0] + Math.min(column[2], 200) <= surface.getWidth()
                                && column[1] + column[3] <= surface.getHeight(),
                        "the column stays in view: " + where);
            });
        }
    }

    private static Labeled labelled(javafx.scene.Parent parent, String styleClass) {
        Labeled found = find(parent, styleClass);
        assertNotNull(found, styleClass);
        return found;
    }

    private static @org.jspecify.annotations.Nullable Labeled find(javafx.scene.Parent parent, String styleClass) {
        for (Node child : parent.getChildrenUnmodifiable()) {
            if (child instanceof Labeled labeled && child.getStyleClass().contains(styleClass)) {
                return labeled;
            }
            if (child instanceof javafx.scene.Parent inner) {
                Labeled found = find(inner, styleClass);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private ProjectMapView openMap(ProjectMapView.FlowDirection flow) throws Exception {
        Path project = root;
        ProjectMapView opened = FxTestSupport.callOnFx(() -> {
            ProjectMapView view = new ProjectMapView(path -> {}, path -> false, path -> false, path -> null);
            views.add(view);
            Stage stage = new Stage();
            stages.add(stage);
            Scene scene = new Scene(view, 1200, 800);
            scene.getStylesheets()
                    .add(ProjectMapOverviewCardFxTest.class
                            .getResource("/com/editora/styles/app.css")
                            .toExternalForm());
            stage.setScene(scene);
            stage.show();
            view.applyCss();
            view.layout();
            view.setRememberedFlow(flow.name(), ignored -> {});
            view.setRememberedNavigation(true, false, (keep, focus) -> {});
            view.setRoot(project);
            return view;
        });
        Region surface = FxTestSupport.field(opened, "surface");
        waitFor(
                () -> !FxTestSupport.<List<?>>field(surface, "entries").isEmpty()
                        && FxTestSupport.<MapSurface>field(opened, "surface").viewsLaidOut()
                        && FxTestSupport.<MapSurface>field(opened, "surface").cardRootColumn() != null,
                "the first view");
        return opened;
    }
}
