package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javafx.scene.Scene;
import javafx.scene.layout.Region;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A paint of the Project Map reads the rows on screen, not every listed one (PERF-08, Pauta 6.0.1): a pan over a
 * 5,000-file folder made a box for each of its rows per frame.
 */
@Tag("fx")
class MapSurfaceViewportRowsFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void aPanOverAWholeFolderMakesBoxesForTheRowsOnScreenOnly() throws Exception {
        ProjectMapView mapView =
                FxTestSupport.callOnFx(() -> new ProjectMapView(path -> {}, path -> false, path -> false));
        try {
            FxTestSupport.runOnFx(() -> {
                new Scene(mapView, 720, 420);
                mapView.resize(720, 420);
                mapView.layout();
                Region surface = FxTestSupport.field(mapView, "surface");
                surface.resize(720, 340);
                surface.layout();
                FxTestSupport.call(
                        surface,
                        "setFlowDirection",
                        new Class<?>[] {ProjectMapView.FlowDirection.class},
                        ProjectMapView.FlowDirection.LEFT_TO_RIGHT);
                Path root = Path.of("/tmp/map-viewport-rows").toAbsolutePath().normalize();
                Path folder = root.resolve("folder");
                List<ProjectMapModel.Entry> entries = new ArrayList<>();
                entries.add(new ProjectMapModel.Entry(root, null, 0, true));
                entries.add(new ProjectMapModel.Entry(folder, root, 1, true));
                for (int i = 0; i < 5_000; i++) {
                    entries.add(
                            new ProjectMapModel.Entry(folder.resolve(String.format("f%05d.txt", i)), folder, 2, false));
                }
                FxTestSupport.call(
                        surface, "setEntries", new Class<?>[] {List.class, Set.class}, entries, Set.of(root, folder));
                FxTestSupport.invoke(surface, "paintNow");
                MapProjectedRows rows = FxTestSupport.field(surface, "boxes");
                assertEquals(5_001, rows.size(), "every row is listed");
                long first = rows.made;
                assertTrue(first > 0, "the rows on screen are drawn");
                assertTrue(first < 200, "boxes made for the first paint: " + first);
                int connectors = FxTestSupport.field(surface, "lastPaintedConnectorCount");
                assertTrue(connectors > 0, "the rows on screen keep their connectors");

                // Pan down through the folder: each frame makes the boxes of the rows it shows.
                for (int step = 0; step < 10; step++) {
                    double offsetY = FxTestSupport.field(surface, "offsetY");
                    setField(surface, "offsetY", offsetY - 3_000);
                    long before = rows.made;
                    FxTestSupport.invoke(surface, "paintNow");
                    long made = rows.made - before;
                    assertTrue(made < 200, "boxes made for a pan frame: " + made);
                }
                // The hit boxes still answer for any row read, on screen or not.
                MapSurface.NodeBox last = rows.get(rows.size() - 1);
                assertNotNull(last.entry());
                assertTrue(last.entry().path().endsWith("f04999.txt"));
            });
        } finally {
            FxTestSupport.runOnFx(mapView::dispose);
        }
    }

    private static void setField(Object target, String name, double value) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.setDouble(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
