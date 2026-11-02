package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.ui.ProjectMapView.FlowDirection;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Map's keyboard on a live surface ({@link MapSurface}): the arrows in each of the four flows (siblings across
 * the flow, parent against it, child along it), Ctrl+N / Ctrl+P, Home and End in a column and Shift+Home to the
 * first row, Page Up / Down, Backspace, type-ahead and {@code /}, and the chords the surface claims (Alt+← →,
 * Ctrl+0, F3, Escape) next to the ones it leaves alone.
 */
@Tag("fx")
class MapSurfaceKeysFxTest {

    @TempDir
    Path temp;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private record Harness(Stage stage, ProjectMapView view, MapSurface surface, Path project, List<KeyCode> passed)
            implements AutoCloseable {
        Path a(int index) {
            return project.resolve("a/a" + index + ".txt");
        }

        @Override
        public void close() throws Exception {
            FxTestSupport.runOnFx(() -> {
                view.dispose();
                stage.hide();
            });
        }
    }

    private Harness open() throws Exception {
        Path project = Files.createTempDirectory(temp, "project").toRealPath();
        Path a = Files.createDirectory(project.resolve("a"));
        for (int i = 1; i <= 3; i++) {
            Files.writeString(a.resolve("a" + i + ".txt"), "a" + i);
        }
        Files.writeString(Files.createDirectory(project.resolve("b")).resolve("b1.txt"), "b");
        Files.writeString(project.resolve("README.md"), "# r");
        List<KeyCode> passed = new CopyOnWriteArrayList<>();
        Harness h = FxTestSupport.callOnFx(() -> {
            ProjectMapView view = new ProjectMapView(path -> {}, path -> false, path -> false);
            StackPane host = new StackPane(view);
            // What the surface did not consume bubbles up to here.
            host.addEventHandler(KeyEvent.KEY_PRESSED, event -> passed.add(event.getCode()));
            Stage stage = new Stage();
            stage.setScene(new Scene(host, 1200, 800));
            stage.show();
            view.setRoot(project);
            return new Harness(stage, view, FxTestSupport.field(view, "surface"), project, passed);
        });
        waitForFx(() -> contains(h, project.resolve("README.md")) && idle(h));
        FxTestSupport.runOnFx(() -> h.view().revealPath(h.a(2)));
        waitForFx(() -> idle(h) && h.a(2).equals(selected(h)) && contains(h, h.a(3)));
        FxTestSupport.drainFx();
        return h;
    }

    @Test
    void theArrowsMoveAcrossAlongAndAgainstEachFlow() throws Exception {
        try (Harness h = open()) {
            Path a = h.project().resolve("a");
            for (FlowDirection flow : FlowDirection.values()) {
                KeyCode previous;
                KeyCode next;
                KeyCode parent;
                KeyCode child;
                switch (flow) {
                    case LEFT_TO_RIGHT -> {
                        previous = KeyCode.UP;
                        next = KeyCode.DOWN;
                        parent = KeyCode.LEFT;
                        child = KeyCode.RIGHT;
                    }
                    case RIGHT_TO_LEFT -> {
                        previous = KeyCode.UP;
                        next = KeyCode.DOWN;
                        parent = KeyCode.RIGHT;
                        child = KeyCode.LEFT;
                    }
                    case TOP_TO_BOTTOM -> {
                        previous = KeyCode.LEFT;
                        next = KeyCode.RIGHT;
                        parent = KeyCode.UP;
                        child = KeyCode.DOWN;
                    }
                    default -> {
                        previous = KeyCode.LEFT;
                        next = KeyCode.RIGHT;
                        parent = KeyCode.DOWN;
                        child = KeyCode.UP;
                    }
                }
                FxTestSupport.runOnFx(() -> {
                    FxTestSupport.call(h.surface(), "setFlowDirection", new Class<?>[] {FlowDirection.class}, flow);
                    FxTestSupport.invoke(h.surface(), "paintNow");
                    select(h, h.a(2));
                    press(h, previous);
                    assertEquals(h.a(1), selected(h), flow + ": the sibling before");
                    press(h, next);
                    press(h, next);
                    assertEquals(h.a(3), selected(h), flow + ": the sibling after");
                    press(h, parent);
                    assertEquals(a, selected(h), flow + ": the folder the column opens from");
                    press(h, child);
                    assertEquals(h.a(1), selected(h), flow + ": into its column, at the first row");
                });
            }
            assertTrue(h.passed().isEmpty(), "every arrow was the map's: " + h.passed());
        }
    }

    @Test
    void theColumnKeysMoveWithinTheSelectedColumn() throws Exception {
        try (Harness h = open()) {
            FxTestSupport.runOnFx(() -> {
                select(h, h.a(1));
                press(h, KeyCode.N, false, true, false);
                assertEquals(h.a(2), selected(h), "Ctrl+N: the next row");
                press(h, KeyCode.P, false, true, false);
                assertEquals(h.a(1), selected(h), "Ctrl+P: the previous row");
                press(h, KeyCode.END);
                assertEquals(h.a(3), selected(h), "End: the column's last row");
                press(h, KeyCode.HOME);
                assertEquals(h.a(1), selected(h), "Home: its first");
                press(h, KeyCode.PAGE_DOWN);
                assertEquals(h.a(3), selected(h), "Page Down: ten rows on, at most the last");
                press(h, KeyCode.PAGE_UP);
                assertEquals(h.a(1), selected(h));
                press(h, KeyCode.BACK_SPACE);
                assertEquals(h.project().resolve("a"), selected(h), "Backspace: the parent");
                press(h, KeyCode.HOME, true, false, false);
                assertEquals(h.project().resolve("a"), selected(h), "Shift+Home: the first column's first row");
                h.passed().clear();

                select(h, h.a(3));
                press(h, KeyCode.HOME, false, true, false);
                press(h, KeyCode.N, false, true, true);
                press(h, KeyCode.Q);
                assertEquals(h.a(3), selected(h), "Ctrl+Home, Ctrl+Alt+N and a plain letter key move nothing");
                assertEquals(List.of(KeyCode.HOME, KeyCode.N, KeyCode.Q), h.passed(), "and are left to others");
            });
        }
    }

    @Test
    void typeAheadSelectsByNameAndSlashFocusesTheColumnsFilter() throws Exception {
        try (Harness h = open()) {
            long[] now = {1_000_000};
            FxTestSupport.runOnFx(() -> {
                h.surface().keysClock = () -> now[0];
                select(h, h.a(1));
                type(h, "a", false);
                type(h, "3", false);
                assertEquals(h.a(3), selected(h), "a3 typed: the row named so");
                now[0] += 5_000; // a pause: a new prefix
                type(h, "a", false);
                assertEquals(h.a(1), selected(h), "the next row with that initial, wrapping round");
                type(h, "\u0007", false);
                type(h, "a", true);
                assertEquals(h.a(1), selected(h), "a control character and Ctrl+A are not type-ahead");
                now[0] += 5_000;
                type(h, " ", false);
                assertEquals(h.a(1), selected(h), "a space never starts a prefix");
                type(h, "/", false);
            });
            waitForFx(() -> h.stage().getScene().getFocusOwner() instanceof TextInputControl);
        }
    }

    @Test
    void theChordsTheSurfaceClaimsAreConsumedAndOthersPassOn() throws Exception {
        try (Harness h = open()) {
            FxTestSupport.runOnFx(() -> {
                select(h, h.project().resolve("README.md"));
                select(h, h.a(1));
                h.passed().clear();
                press(h, KeyCode.LEFT, false, false, true); // Alt+←: Back
                assertNotEquals(h.a(1), selected(h), "Back went to the place before");
                press(h, KeyCode.RIGHT, false, false, true); // Alt+→: Forward
                assertEquals(h.a(1), selected(h), "and Forward came back");
                double zoom = FxTestSupport.field(h.surface(), "zoom");
                FxTestSupport.call(
                        h.surface(),
                        "setZoom",
                        new Class<?>[] {double.class, double.class, double.class},
                        zoom * 2.5,
                        10.0,
                        10.0);
                pressShortcut(h, KeyCode.DIGIT0); // Shortcut+0 (Cmd on macOS, Ctrl elsewhere): fit
                assertNotEquals(zoom * 2.5, (double) FxTestSupport.field(h.surface(), "zoom"), 1e-6, "fitted");
                press(h, KeyCode.NUMPAD0, false, true, true); // with Alt: not Fit
                press(h, KeyCode.F3);
                press(h, KeyCode.F3, true, false, false);
                press(h, KeyCode.ESCAPE);
                assertEquals(List.of(KeyCode.NUMPAD0), h.passed(), "Back, Forward, Fit, F3 and Escape are the map's");
                h.passed().clear();
                press(h, KeyCode.ESCAPE, false, true, false);
                press(h, KeyCode.F3, false, false, true);
                assertEquals(List.of(KeyCode.ESCAPE, KeyCode.F3), h.passed(), "with Ctrl or Alt they are not");
            });
        }
    }

    // --- helpers ----------------------------------------------------------------------------------------------

    private static void press(Harness h, KeyCode code) {
        press(h, code, false, false, false);
    }

    /** {@code code} with the platform's shortcut modifier: Cmd on macOS, Ctrl elsewhere. */
    private static void pressShortcut(Harness h, KeyCode code) {
        boolean mac = com.editora.command.KeymapManager.isMac();
        Event.fireEvent(
                h.surface(),
                new KeyEvent(h.surface(), h.surface(), KeyEvent.KEY_PRESSED, "", "", code, false, !mac, false, mac));
    }

    private static void press(Harness h, KeyCode code, boolean shift, boolean control, boolean alt) {
        Event.fireEvent(
                h.surface(),
                new KeyEvent(h.surface(), h.surface(), KeyEvent.KEY_PRESSED, "", "", code, shift, control, alt, false));
    }

    private static void type(Harness h, String character, boolean control) {
        Event.fireEvent(
                h.surface(),
                new KeyEvent(
                        h.surface(),
                        h.surface(),
                        KeyEvent.KEY_TYPED,
                        character,
                        "",
                        KeyCode.UNDEFINED,
                        false,
                        control,
                        false,
                        false));
    }

    private static void select(Harness h, Path path) {
        FxTestSupport.call(h.surface(), "select", new Class<?>[] {Path.class}, path);
    }

    private static boolean contains(Harness h, Path path) {
        return (boolean) FxTestSupport.call(h.surface(), "contains", new Class<?>[] {Path.class}, path);
    }

    private static @Nullable Path selected(Harness h) {
        @SuppressWarnings("unchecked")
        Optional<ProjectMapModel.Entry> entry =
                (Optional<ProjectMapModel.Entry>) FxTestSupport.call(h.surface(), "selectedEntry", new Class<?>[0]);
        return entry.map(ProjectMapModel.Entry::path).orElse(null);
    }

    private static boolean idle(Harness h) {
        return !FxTestSupport.<Boolean>field(h.view(), "loadInFlight");
    }

    private static void waitForFx(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (FxTestSupport.callOnFx(condition::getAsBoolean)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("timed out");
    }
}
