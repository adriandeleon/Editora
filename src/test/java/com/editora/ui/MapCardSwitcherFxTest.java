package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Map's card switcher (Map code browser round 2): Recent Files or Switch File typed on the Map lists its open cards,
 * the most recently used first, starting on the one before the card the keyboard is in.
 */
@Tag("fx")
class MapCardSwitcherFxTest {

    @TempDir
    Path temp;

    private Path root;
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

    @Test
    void recentFilesOnTheMapSwitchesBetweenItsCards() throws Exception {
        Path src = Files.createDirectories(root.resolve("src"));
        Path a = Files.writeString(src.resolve("Alpha.java"), "class Alpha {}\n");
        Path b = Files.writeString(src.resolve("Beta.java"), "class Beta {}\n");
        Path c = Files.writeString(src.resolve("Gamma.java"), "class Gamma {}\n");
        ProjectPanel panel = openPanel();
        ProjectMapView view = FxTestSupport.field(panel, "mapView");
        MapCards cards = FxTestSupport.field(view, "cards");

        // No card: the status line says so, and nothing opens.
        FxTestSupport.runOnFx(() -> {
            assertTrue(runMapCommand(panel, "recent.jump"));
            assertFalse(panel.mapWindow().overlayHost().isShowing());
            assertNull(panel.cardSwitcher());
        });

        ProjectMapPreview alpha = openCard(cards, a);
        ProjectMapPreview beta = openCard(cards, b);
        ProjectMapPreview gamma = openCard(cards, c);
        FxTestSupport.runOnFx(() -> {
            gamma.editor().requestFocus();
            assertTrue(runMapCommand(panel, "buffer.jump"));
        });
        ListView<?> list = waitForOverlayList(panel);
        FxTestSupport.runOnFx(() -> {
            assertEquals(3, list.getItems().size());
            MapCards.OpenCard picked =
                    (MapCards.OpenCard) list.getSelectionModel().getSelectedItem();
            assertSame(beta, picked.node(), "the one before the card the keyboard is in");
            assertEquals("src/Beta.java", view.switcherDetail(picked));
            pressEnter(overlayField(panel));
        });
        waitFor(() -> beta.editor().isFocused() || beta.isFocusWithin(), "Beta's card has the keyboard");

        // Typing filters by name.
        FxTestSupport.runOnFx(() -> assertTrue(runMapCommand(panel, "recent.jump")));
        ListView<?> again = waitForOverlayList(panel);
        FxTestSupport.runOnFx(() -> overlayField(panel).setText("alp"));
        waitFor(() -> again.getItems().size() == 1, "only Alpha");
        FxTestSupport.runOnFx(() -> {
            assertSame(alpha, ((MapCards.OpenCard) again.getItems().get(0)).node());
            pressEnter(overlayField(panel));
        });
        waitFor(() -> alpha.isFocusWithin(), "Alpha's card has the keyboard");
        assertEquals(List.of(), editorOpened, "nothing opened in the editor");

        // ⋯ → Open cards starts with Switch Card….
        FxTestSupport.runOnFx(() -> {
            FxTestSupport.call(view, "fillOpenCardsMenu", new Class<?>[0]);
            Menu menu = FxTestSupport.field(view, "openCardsMenu");
            MenuItem first = menu.getItems().get(0);
            assertEquals(tr("project.map.switch.menu"), first.getText());
            first.fire();
            assertTrue(panel.mapWindow().overlayHost().isShowing());
            panel.mapWindow().overlayHost().hide();
        });
    }

    @Test
    void whereItStartsAndWhatItSaysOfEachCard() throws Exception {
        Region first = new Region();
        Region second = new Region();
        StackPane holder = new StackPane(first);
        List<MapCards.OpenCard> open = List.of(
                new MapCards.OpenCard(root.resolve("A.java"), first, "A.java", false),
                new MapCards.OpenCard(root.resolve("B.java"), second, "B.java", true));
        assertSame(open.get(1), ProjectMapView.switcherStart(open, first), "in the newest: the one before");
        assertSame(
                open.get(1),
                ProjectMapView.switcherStart(open, holder.getChildren().get(0)));
        assertSame(open.get(0), ProjectMapView.switcherStart(open, null), "elsewhere: the newest");
        assertSame(open.get(0), ProjectMapView.switcherStart(open.subList(0, 1), first), "only one: it");
        assertNull(ProjectMapView.switcherStart(List.of(), first));

        ProjectPanel panel = openPanel();
        ProjectMapView view = FxTestSupport.field(panel, "mapView");
        FxTestSupport.runOnFx(() -> {
            assertEquals(tr("project.map.switch.notes", "B.java"), view.switcherDetail(open.get(1)));
            assertNotNull(ProjectMapView.switcherIcon(open.get(0)));
            assertNotNull(ProjectMapView.switcherIcon(open.get(1)));
            for (Region solo : List.of(
                    new ProjectMapOverviewCard(),
                    new ProjectMapHistoryCard(),
                    new ProjectMapDependencyCard(),
                    new ProjectMapMetricsCard())) {
                assertNotNull(ProjectMapView.switcherIcon(new MapCards.OpenCard(root, solo, "x", false)));
            }
            Path outside = temp.resolve("Else.java");
            assertEquals(
                    outside.toString(),
                    view.switcherDetail(new MapCards.OpenCard(outside, first, "Else.java", false)),
                    "outside the Map's folder: the whole path");
        });
    }

    // --- helpers ------------------------------------------------------------------------------------------------

    private static boolean runMapCommand(ProjectPanel panel, String commandId) {
        return (Boolean) FxTestSupport.invokeWith(panel, "resultsFindCommand", String.class, commandId);
    }

    private static StackPane overlayRoot(ProjectPanel panel) {
        return FxTestSupport.field(panel.mapWindow().overlayHost(), "overlayRoot");
    }

    private static ListView<?> waitForOverlayList(ProjectPanel panel) throws Exception {
        waitFor(
                () -> panel.mapWindow().overlayHost().isShowing()
                        && overlayRoot(panel).lookup(".list-view") != null,
                "the picker over the Map");
        return FxTestSupport.callOnFx(() -> (ListView<?>) overlayRoot(panel).lookup(".list-view"));
    }

    private static TextField overlayField(ProjectPanel panel) {
        return (TextField) overlayRoot(panel).lookup(".text-field");
    }

    private static void pressEnter(TextField field) {
        field.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
    }

    private ProjectPanel openPanel() throws Exception {
        ProjectPanel panel = FxTestSupport.callOnFx(() -> {
            ProjectPanel p = DisposedPanels.track(
                    new ProjectPanel(editorOpened::add, (x, y) -> {}, x -> {}, x -> false, x -> false));
            p.setRoot(root);
            p.openMapWindow(false);
            return p;
        });
        ProjectMapView view = FxTestSupport.field(panel, "mapView");
        Region surface = FxTestSupport.field(view, "surface");
        waitFor(() -> !((List<?>) FxTestSupport.field(surface, "entries")).isEmpty(), "the first column");
        return panel;
    }

    private static ProjectMapPreview openCard(MapCards cards, Path file) throws Exception {
        ProjectMapPreview card = FxTestSupport.callOnFx(() -> cards.openFileCard(file));
        waitFor(() -> card.editor().getLength() > 0, "the card's text");
        return card;
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
