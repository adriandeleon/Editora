package com.editora.ui;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Tooltip;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PERF-14 (Pauta 6.0.1): the code that builds the first window makes only what that window shows. No Settings
 * window or page, no dialog, no other window, no picker's card and no closed tool window's panel exists when the
 * window has been built; each is made by the code that first opens it.
 *
 * <p>The test builds a window the way the app does and walks everything the controller and the window manager
 * hold, field by field through Editora's own objects (collections, maps, arrays and the values a lambda captured
 * included). It fails on a {@link Window} other than the main stage and on a {@link Node} that is not in the
 * window's scene: a node built and kept for later. {@link #PART_OF_THE_WINDOW} names what is left out, each
 * with its reason: a right-click menu, a tooltip or a popup of a control the window shows is part of that
 * control, and so is a button the toolbar or the stripe holds but does not place right now.
 *
 * <p>What it cannot see: a node or window reachable only from a static field, from a JavaFX property, listener
 * or handler, or from a node's own properties, since the walk does not enter JavaFX's objects; a window
 * constructed and dropped again; and whatever is in the scene but hidden (the find bar), which counts as shown.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FirstWindowOnlyFxTest {

    /** Paths (from {@code mc}, the controller, or {@code wm}, the window manager) the walk leaves out, and why. */
    private static final Map<Pattern, String> PART_OF_THE_WINDOW = new LinkedHashMap<>();

    static {
        allow("^mc\\.menuBar\\.", "the rows of the menu bar's menus: the menu bar is on screen");
        allow("\\.stripeButtons\\{", "the stripe's button for a tool window that is not on the stripe right now");
        allow("^mc\\.findBar\\.", "the find bar's options, in the bar's own menu");
        allow("\\.toolbarBaseWidgets\\{", "a toolbar button the toolbar layout in use leaves out");
        allow("^mc\\.welcomePane\\.", "the Welcome page a fresh window shows, before its first layout pass");
        allow("\\.expertExitButton$", "the button that leaves Expert mode, placed in the tab strip while it is on");
        // An editor's own parts, when the window opens on a file:
        allow("\\.minimap\\.offscreen$", "the minimap's drawing surface");
        allow("\\.viewLayout\\.(cornerControls|previewCornerRow)$", "the editor's corner buttons, placed on hover");
        allow("\\.valuePopup\\.", "the debugger's value popup of the editor, installed with its hover");
    }

    private static void allow(String path, String reason) {
        PART_OF_THE_WINDOW.put(Pattern.compile(path), reason);
    }

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void aFreshWindowBuildsNothingItDoesNotShow() throws Exception {
        List<Window> before = showing();
        try (FxWindowFixture fx = FxWindowFixture.create()) {
            assertOnlyTheFirstWindow(fx, before);
        }
    }

    @Test
    void aWindowOpenedOnAFileBuildsNothingItDoesNotShow() throws Exception {
        Path dir = Files.createTempDirectory("editora-first-window");
        Path file = dir.resolve("Sample.java");
        Files.writeString(file, "class Sample {\n    // TODO: later\n    int x = 1;\n}\n");
        List<Window> before = showing();
        try (FxWindowFixture fx = FxWindowFixture.create(
                dir, false, false, false, List.of(new MainController.OpenTarget(file, -1, -1)), true, c -> {})) {
            // The file is read off the FX thread: wait for its editor, then for the load's own runLater work.
            FxTestSupport.awaitFxCondition(
                    () -> FxTestSupport.call(fx.controller, "activeBuffer", new Class<?>[0]) != null,
                    "the file to open",
                    java.time.Duration.ofSeconds(10));
            for (int i = 0; i < 3; i++) {
                FxTestSupport.runOnFx(() -> {});
            }
            assertOnlyTheFirstWindow(fx, before);
            // The Welcome page is the first window's content only when it opens on no file.
            assertNull(rawField(fx.controller, "welcomePane"), "the Welcome page is built when its tab first opens");
        }
    }

    /** The windows showing now: another test class in this JVM may have left one open. */
    private static List<Window> showing() throws Exception {
        return FxTestSupport.callOnFx(() -> List.copyOf(Window.getWindows()));
    }

    private static void assertOnlyTheFirstWindow(FxWindowFixture fx, List<Window> before) throws Exception {
        MainController c = fx.controller;
        Stage main = FxTestSupport.field(c, "stage");
        List<String> found = FxTestSupport.callOnFx(() -> {
            List<Window> opened = new ArrayList<>(Window.getWindows());
            opened.removeAll(before);
            assertEquals(List.of(main), opened, "building the first window shows that window and no other");
            return new Walk(main).from(c, "mc").from(fx.windowManager, "wm").found;
        });
        assertTrue(
                found.isEmpty(),
                () -> "built before the first window although it does not show them (" + found.size()
                        + "). Build each on first use (a supplier, or a field filled on first use); if one is part"
                        + " of a control the window shows, add it to PART_OF_THE_WINDOW with the reason:\n"
                        + String.join("\n", found));

        // The same, said of the things the rule names, so a failure reads plainly.
        ToolWindowManager manager = FxTestSupport.field(c, "toolWindows");
        for (ToolWindow tw : manager.getRegisteredToolWindows()) {
            assertTrue(
                    manager.isOpen(tw) || tw.contentIfBuilt() == null, tw.getId() + "'s panel is built while closed");
        }
        assertNull(((BuildOutput) rawField(c, "buildOutputPanel")).panelIfBuilt(), "the Output console");
        Object settings = rawField(c, "settingsWindow");
        assertNull(rawField(settings, "stage"), "the Settings window's stage is made by its first show");
        assertFalse((Boolean) rawField(settings, "built"), "the Settings pages are built by the first show");
        assertNull(rawField(rawField(c, "doctorCoordinator"), "pane"), "the Doctor page");
    }

    /** A field's value as it is: {@link FxTestSupport#field} would build a panel through its accessor. */
    private static Object rawField(Object target, String name) throws Exception {
        for (Class<?> k = target.getClass(); k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                // try the superclass
            }
        }
        throw new NoSuchFieldException(name + " on " + target.getClass());
    }

    /** Breadth-first, so each finding is reported by its shortest path. */
    private static final class Walk {
        private final Stage main;
        private final Map<Object, Boolean> seen = new IdentityHashMap<>();
        private final ArrayDeque<Map.Entry<Object, String>> queue = new ArrayDeque<>();
        final List<String> found = new ArrayList<>();

        Walk(Stage main) {
            this.main = main;
        }

        Walk from(Object root, String name) {
            add(root, name);
            while (!queue.isEmpty()) {
                Map.Entry<Object, String> next = queue.poll();
                visit(next.getKey(), next.getValue());
            }
            return this;
        }

        private void add(Object o, String path) {
            if (o != null && seen.put(o, Boolean.TRUE) == null) {
                queue.add(Map.entry(o, path));
            }
        }

        private void visit(Object o, String path) {
            Class<?> type = o.getClass();
            if (o instanceof Window w) {
                // A right-click menu and a tooltip belong to the control they were made for.
                if (w != main && !(w instanceof ContextMenu) && !(w instanceof Tooltip)) {
                    report("window " + type.getName(), path);
                }
                return;
            }
            if (o instanceof javafx.scene.control.Dialog<?>) {
                report("dialog " + type.getName(), path);
                return;
            }
            if (o instanceof Node n && n.getScene() == null) {
                report("node " + type.getName(), path);
                return; // what it holds is part of it
            }
            if (o instanceof Collection<?> items) {
                int i = 0;
                for (Object item : snapshot(items)) {
                    add(item, path + "[" + i++ + "]");
                }
                return;
            }
            if (o instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> e : snapshot(map.entrySet())) {
                    add(e.getKey(), path + "{key}");
                    add(e.getValue(), path + "{" + label(e.getKey()) + "}");
                }
                return;
            }
            if (o instanceof Object[] array) {
                for (int i = 0; i < array.length; i++) {
                    add(array[i], path + "[" + i + "]");
                }
                return;
            }
            // Editora's own objects only (a lambda's class is in its declaring class's package): JavaFX's and the
            // JDK's are not opened for reflection, and what they hold is not ours to judge.
            for (Class<?> k = type; k != null && k.getName().startsWith("com.editora."); k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())
                            || f.getType().isPrimitive()
                            || f.getType() == String.class) {
                        continue;
                    }
                    if (o instanceof MainController && f.isAnnotationPresent(javafx.fxml.FXML.class)) {
                        continue; // declared in the window's FXML: the window's own, placed or not
                    }
                    try {
                        f.setAccessible(true);
                        add(f.get(o), path + "." + f.getName());
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        // not readable from here: one of what the test cannot see
                    }
                }
            }
        }

        private void report(String what, String path) {
            for (Pattern allowed : PART_OF_THE_WINDOW.keySet()) {
                if (allowed.matcher(path).find()) {
                    return;
                }
            }
            found.add("  " + path + "  (" + what + ")");
        }

        private static <T> List<T> snapshot(Collection<T> items) {
            try {
                return new ArrayList<>(items);
            } catch (RuntimeException e) {
                return List.of(); // a live view that cannot be read here
            }
        }

        private static String label(Object key) {
            return key instanceof String || key instanceof Enum<?>
                    ? key.toString()
                    : key.getClass().getSimpleName();
        }
    }
}
