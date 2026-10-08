package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.stage.Window;

import com.editora.command.CommandRegistry;
import com.editora.command.KeymapManager;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.plugin.ActiveEditor;
import com.editora.plugin.Plugin;
import com.editora.plugin.PluginContext;
import com.editora.plugin.ToolWindowSide;
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
 * What an enabled plugin gets from the editor: everything on {@link PluginContext} and {@link ActiveEditor},
 * the commands its manifest declares (run as real child processes — here a JVM printing its version), and
 * the isolation that keeps one failing plugin from taking the window, or the other plugins, with it.
 */
@Tag("fx")
class PluginContextFxTest {

    private static final long WAIT_SECONDS = 60;

    /** What the plugins below were asked to do. */
    private static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    private static volatile PluginContext context;

    /** Uses every part of the plugin API. Loaded by name through the plugin class loader's parent. */
    public static final class ProbePlugin implements Plugin {
        @Override
        public void start(PluginContext ctx) {
            context = ctx;
            EVENTS.add("probe start");
            ctx.registerCommand("ping", null, () -> EVENTS.add("ping"));
            ctx.registerCommand("probe.dotted", "Probe: Dotted", () -> EVENTS.add("dotted"));
            ctx.bindKey("C-M-S-f8", "ping");
            ctx.bindKey(null, "ping");
            ctx.bindKey("  ", "ping");
            ctx.bindKey("C-M-S-f7", null);
            ctx.registerToolWindow(
                    "left", "Probe Left", ToolWindowSide.LEFT, new Region(), "showLeft", () -> null, true);
            ctx.registerToolWindow(
                    "right", null, ToolWindowSide.RIGHT, new Region(), " ", () -> new Label("icon"), false);
            ctx.registerToolWindow("bottom", "Probe Bottom", null, new Region(), null);
            ctx.addEditorMenuItem(
                    "Shout", editor -> editor.setText(editor.text().toUpperCase()));
            ctx.addEditorMenuItem("Boom", editor -> {
                throw new IllegalStateException("plugin menu action failed on purpose");
            });
            ctx.addEditorMenuItem(null, editor -> EVENTS.add("never"));
            ctx.addEditorMenuItem("No action", null);
            ctx.addStatusBarSegment("probe-segment", "ping");
            ctx.addStatusBarSegment("probe-plain", null);
        }

        @Override
        public void stop() {
            EVENTS.add("probe stop");
            throw new IllegalStateException("plugin stop failed on purpose");
        }
    }

    /** A plugin that cannot start. */
    public static final class BrokenPlugin implements Plugin {
        @Override
        public void start(PluginContext ctx) {
            EVENTS.add("broken start");
            throw new IllegalStateException("plugin start failed on purpose");
        }

        @Override
        public void stop() {
            EVENTS.add("broken stop");
        }
    }

    /** Started after the broken one: shows the loop carries on. */
    public static final class LatePlugin implements Plugin {
        @Override
        public void start(PluginContext ctx) {
            EVENTS.add("late start");
        }

        @Override
        public void stop() {
            EVENTS.add("late stop");
        }
    }

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static String json(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void plugin(Path configDir, String id, String manifestBody) throws Exception {
        Path dir = Files.createDirectories(configDir.resolve("plugins").resolve(id));
        Files.writeString(
                dir.resolve("plugin.json"), "{\"id\":\"" + id + "\",\"version\":\"1.0\"," + manifestBody + "}");
    }

    private static final class RecordingHost extends CoordinatorHostStub {
        private final CoordinatorHost window;
        final BlockingQueue<String> statuses = new LinkedBlockingQueue<>();
        final List<String> log = new CopyOnWriteArrayList<>();
        final List<String> urls = new CopyOnWriteArrayList<>();
        volatile boolean simple;
        volatile EditorBuffer active;

        RecordingHost(CoordinatorHost window) {
            this.window = window;
        }

        @Override
        public Settings settings() {
            return window.settings();
        }

        @Override
        public boolean simpleModeActive() {
            return simple;
        }

        @Override
        public EditorBuffer activeBuffer() {
            return active;
        }

        @Override
        public void setStatus(String message) {
            log.add(message);
            statuses.add(message);
        }

        @Override
        public void requestSave() {
            window.requestSave();
        }

        @Override
        public void openExternalUrl(String url) {
            urls.add(url); // never a browser
        }

        @Override
        public OverlayHost overlayHost() {
            return window.overlayHost();
        }

        @Override
        public Window window() {
            return window.window();
        }

        String await(java.util.function.Predicate<String> wanted) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
            while (true) {
                String status = statuses.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                assertNotNull(status, "no such status message arrived; seen: " + log);
                if (wanted.test(status)) {
                    return status;
                }
            }
        }
    }

    /** A window with the plugins below on disk and consented to, and a coordinator that has applied them. */
    private static final class Rig {
        final FxWindowFixture fx;
        final RecordingHost host;
        final PluginCoordinator coordinator;
        final List<Path> opened = new CopyOnWriteArrayList<>();
        private final AsyncTestScope scope;

        Rig(AsyncTestScope scope, Path configDir) throws Exception {
            this.scope = scope;
            String java =
                    Path.of(System.getProperty("java.home"), "bin", "java").toString();
            Files.createDirectories(configDir.resolve("plugins/probe/sub"));
            Files.createDirectories(configDir.resolve("plugins/probe/snippets"));
            Files.createDirectories(configDir.resolve("plugins/probe/templates"));
            plugin(
                    configDir,
                    "probe",
                    "\"name\":\"Probe\",\"main\":" + json(ProbePlugin.class.getName())
                            + ",\"keymap\":{\"C-M-S-f9\":\"plugin.probe.ok\"},\"commands\":["
                            + "{\"id\":\"ok\",\"title\":\"Probe: OK\",\"run\":[" + json(java) + ",\"-version\"]},"
                            + "{\"id\":\"fails\",\"title\":\" \",\"dir\":\"sub\",\"run\":[" + json(java)
                            + ",\"--editora-no-such-option\"]},"
                            + "{\"id\":\"missing\",\"run\":[\"editora-no-such-plugin-tool\"]},"
                            + "{\"id\":\"norun\",\"title\":\"Probe: Nothing\"},"
                            + "{\"id\":\" \",\"title\":\"No id\",\"run\":[\"x\"]},"
                            + "null]");
            plugin(configDir, "broken", "\"main\":" + json(BrokenPlugin.class.getName()));
            plugin(configDir, "late", "\"main\":" + json(LatePlugin.class.getName()));
            plugin(configDir, "asleep", "\"main\":" + json(LatePlugin.class.getName()));
            plugin(configDir, "ghost", "\"main\":\"no.such.PluginClass\"");

            // The window's own coordinator finds nothing enabled, so the plugins start once — in the
            // coordinator built here, whose host records instead of opening a browser.
            fx = scope.own(FxWindowFixture.create(
                    configDir, shared -> shared.getSettings().setPluginSupport(true)));
            MainController mc = fx.controller;
            host = new RecordingHost(FxTestSupport.field(mc, "coordinatorHost"));
            PluginCoordinator.Ops ops = new PluginCoordinator.Ops() {
                @Override
                public void openPath(Path file) {
                    opened.add(file);
                }

                @Override
                public void showError(String summary, String detail) {}
            };
            coordinator = FxTestSupport.callOnFx(() -> {
                for (String id : List.of("probe", "broken", "late", "ghost")) {
                    fx.shared.getPluginStore().setEnabled(id, true);
                }
                fx.windowManager.pluginManager().discover();
                return new PluginCoordinator(
                        host,
                        FxTestSupport.field(mc, "registry"),
                        FxTestSupport.field(mc, "keymap"),
                        FxTestSupport.field(mc, "snippets"),
                        FxTestSupport.field(FxTestSupport.field(mc, "templateActions"), "templates"),
                        FxTestSupport.field(mc, "toolWindows"),
                        FxTestSupport.field(mc, "statusBar"),
                        FxTestSupport.field(mc, "settingsWindow"),
                        FxTestSupport.field(mc, "config"),
                        fx.windowManager.pluginManager(),
                        ops);
            });
            scope.onClose(() -> FxTestSupport.runOnFx(coordinator::disposePlugins));
        }

        CommandRegistry commands() {
            return FxTestSupport.field(fx.controller, "registry");
        }

        KeymapManager keymap() {
            return FxTestSupport.field(fx.controller, "keymap");
        }

        ToolWindow toolWindow(String id) throws Exception {
            ToolWindowManager toolWindows = FxTestSupport.field(fx.controller, "toolWindows");
            return FxTestSupport.callOnFx(() -> toolWindows.getRegisteredToolWindows().stream()
                    .filter(tw -> tw.getId().equals(id))
                    .findFirst()
                    .orElse(null));
        }

        /** A buffer for {@code file}, made the active one as far as the plugin API can tell. */
        EditorBuffer open(Path file, String text) throws Exception {
            Files.writeString(file, text);
            EditorBuffer b = FxTestSupport.callOnFx(() -> {
                EditorBuffer created = new EditorBuffer();
                created.setPath(file);
                created.setContent(text);
                created.markClean();
                return created;
            });
            scope.onClose(() -> FxTestSupport.runOnFx(b::dispose));
            host.active = b;
            return b;
        }

        boolean available(ToolWindow tw) throws Exception {
            ToolWindowManager toolWindows = FxTestSupport.field(fx.controller, "toolWindows");
            java.util.Collection<ToolWindow> unavailable = FxTestSupport.field(toolWindows, "unavailable");
            return FxTestSupport.callOnFx(() -> !unavailable.contains(tw));
        }
    }

    @Test
    void anEnabledPluginGetsItsCommandsKeysToolWindowsMenuItemsAndStatusSegments(@TempDir Path sandbox)
            throws Exception {
        EVENTS.clear();
        try (AsyncTestScope scope = new AsyncTestScope()) {
            Rig rig = new Rig(scope, Files.createDirectories(sandbox.resolve("config")));
            assertEquals(List.of(), EVENTS, "nothing ran in the window that had not consented yet");

            FxTestSupport.runOnFx(rig.coordinator::applyPlugins);

            // One plugin failing to start, or naming a class that does not exist, stops neither the others
            // nor the window; the one that was never enabled does not run.
            assertEquals(
                    List.of("broken start", "late start", "probe start"),
                    EVENTS.stream().filter(e -> e.endsWith(" start")).sorted().toList());
            assertTrue(rig.host.log.contains(tr("status.plugins.failed", "broken")), rig.host.log.toString());
            assertFalse(rig.host.log.contains(tr("status.plugins.failed", "ghost")), "a missing class is skipped");

            // Commands: a bare id is namespaced, a dotted one is taken as written, a missing title is the id.
            CommandRegistry commands = rig.commands();
            assertEquals(
                    "plugin.probe.ping",
                    commands.get("plugin.probe.ping").orElseThrow().title());
            assertEquals(
                    "Probe: Dotted", commands.get("probe.dotted").orElseThrow().title());
            assertEquals(
                    "Probe: OK", commands.get("plugin.probe.ok").orElseThrow().title());
            assertEquals(
                    "plugin.probe.fails",
                    commands.get("plugin.probe.fails").orElseThrow().title());
            assertTrue(commands.get("plugin.probe. ").isEmpty(), "a declared command without an id is dropped");
            FxTestSupport.runOnFx(() -> {
                commands.run("plugin.probe.ping");
                commands.run("probe.dotted");
            });
            assertTrue(EVENTS.containsAll(List.of("ping", "dotted")), EVENTS.toString());

            // Keys: from the manifest and from code; a binding without a chord or a command is ignored.
            assertEquals("plugin.probe.ok", rig.keymap().commandFor("C-M-S-f9"));
            assertEquals("plugin.probe.ping", rig.keymap().commandFor("C-M-S-f8"));
            assertNull(rig.keymap().commandFor("C-M-S-f7"));

            // Tool windows on each side, each with a toggle command; only the buffer-bound one is gated.
            ToolWindow left = rig.toolWindow("plugin.probe.left");
            ToolWindow right = rig.toolWindow("plugin.probe.right");
            ToolWindow bottom = rig.toolWindow("plugin.probe.bottom");
            assertEquals(ToolWindow.Side.LEFT, left.getSide());
            assertEquals(ToolWindow.Side.RIGHT, right.getSide());
            assertEquals(ToolWindow.Side.BOTTOM, bottom.getSide(), "no side given means the bottom");
            assertEquals(
                    "Probe Left",
                    commands.get("plugin.probe.showLeft").orElseThrow().title());
            assertEquals(
                    "plugin.probe.right",
                    commands.get("plugin.probe.right").orElseThrow().title());
            assertTrue(commands.get("plugin.probe.bottom").isPresent());
            assertFalse(rig.available(left), "registered with no editor in front");
            FxTestSupport.runOnFx(() -> rig.coordinator.gateToolWindows(true));
            assertTrue(rig.available(left));
            FxTestSupport.runOnFx(() -> rig.coordinator.gateToolWindows(false));
            assertFalse(rig.available(left));
            assertTrue(rig.available(right), "self-contained: never gated");

            // The editor's right-click items act on the buffer they were built for.
            EditorBuffer buffer = rig.open(sandbox.resolve("note.txt"), "quiet words\n");
            List<MenuItem> items = FxTestSupport.callOnFx(() -> rig.coordinator.editorMenuItems(buffer));
            assertEquals(
                    List.of("Shout", "Boom"),
                    items.stream().map(MenuItem::getText).toList());
            FxTestSupport.runOnFx(() -> {
                items.get(1).fire(); // throws inside the plugin: contained
                items.get(0).fire();
            });
            assertEquals("QUIET WORDS\n", FxTestSupport.callOnFx(buffer::getContent));
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty), "a plugin's edit is the user's to save or undo");

            // A status segment with a command runs it when clicked; one without is only a label.
            StatusBar statusBar = FxTestSupport.field(rig.fx.controller, "statusBar");
            int pingsBefore = (int) EVENTS.stream().filter("ping"::equals).count();
            FxTestSupport.runOnFx(() -> {
                for (Node n : statusBar.getChildren()) {
                    if (n instanceof Label l && l.getText().startsWith("probe-")) {
                        Event.fireEvent(l, click());
                    }
                }
            });
            assertEquals(pingsBefore + 1, EVENTS.stream().filter("ping"::equals).count());
        }
        assertTrue(EVENTS.contains("probe stop"), "closing the window stops the plugin");
        assertTrue(EVENTS.contains("late stop"), "a plugin whose stop() throws does not keep the next from stopping");
        assertFalse(EVENTS.contains("broken stop"), "a plugin that never started is not stopped");
    }

    @Test
    void theContextReachesTheActiveEditorAndThePluginsOwnFolders(@TempDir Path sandbox) throws Exception {
        EVENTS.clear();
        try (AsyncTestScope scope = new AsyncTestScope()) {
            Path configDir = Files.createDirectories(sandbox.resolve("config"));
            Rig rig = new Rig(scope, configDir);
            FxTestSupport.runOnFx(rig.coordinator::applyPlugins);
            PluginContext ctx = context;
            Path pluginDir = configDir.resolve("plugins/probe");

            assertEquals(pluginDir.toRealPath(), ctx.pluginDir().toRealPath());
            assertEquals(configDir.toRealPath(), ctx.configDir().toRealPath());
            assertFalse(Files.exists(pluginDir.resolve("data")));
            Path data = ctx.dataDir();
            assertTrue(Files.isDirectory(data), "created on first use");
            assertEquals(pluginDir.resolve("data").toRealPath(), data.toRealPath());

            ctx.log("a line for the log");
            FxTestSupport.runOnFx(() -> ctx.setStatus("hello from the probe"));
            assertTrue(rig.host.log.contains("hello from the probe"));
            ctx.openUrl(null);
            ctx.openUrl("  ");
            ctx.openUrl("https://example.test/docs");
            assertEquals(List.of("https://example.test/docs"), rig.host.urls);

            // With no editor tab in front, the active editor is empty and edits go nowhere.
            ActiveEditor none = FxTestSupport.callOnFx(ctx::activeEditor);
            FxTestSupport.runOnFx(() -> {
                assertNull(none.filePath());
                assertEquals("", none.text());
                assertEquals("", none.selectedText());
                assertEquals(-1, none.caretLine());
                none.replaceSelection("x");
                none.insertAtCaret("x");
                none.setText("x");
                none.openPath(null);
            });
            assertEquals(List.of(), rig.opened);

            Path file = sandbox.resolve("story.txt");
            EditorBuffer buffer = rig.open(file, "one\ntwo\nthree\n");
            ActiveEditor editor = FxTestSupport.callOnFx(ctx::activeEditor);
            FxTestSupport.runOnFx(() -> {
                assertEquals(toReal(file), toReal(editor.filePath()));
                assertEquals("one\ntwo\nthree\n", editor.text());
                buffer.getArea().selectRange(1, 0, 1, 3);
                assertEquals("two", editor.selectedText());
                assertEquals(2, editor.caretLine(), "1-based");
                editor.replaceSelection("TWO");
                editor.replaceSelection(null);
                buffer.getArea().moveTo(0, 0);
                editor.insertAtCaret(">> ");
                editor.insertAtCaret(null);
            });
            assertEquals(">> one\nTWO\nthree\n", FxTestSupport.callOnFx(buffer::getContent));
            FxTestSupport.runOnFx(() -> {
                editor.setText(null);
                editor.setText("replaced");
                editor.openPath(sandbox.resolve("other.txt"));
            });
            assertEquals("replaced", FxTestSupport.callOnFx(buffer::getContent));
            assertEquals(List.of(sandbox.resolve("other.txt")), rig.opened);

            // A buffer the user cannot edit is not edited by a plugin either.
            FxTestSupport.runOnFx(() -> {
                buffer.setReadOnly(true);
                editor.setText("forced");
                editor.insertAtCaret("forced");
                editor.replaceSelection("forced");
            });
            assertEquals("replaced", FxTestSupport.callOnFx(buffer::getContent));
        }
    }

    private static String toReal(Path p) {
        try {
            return p.toRealPath().toString();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    void aDeclaredCommandRunsItsProgramAndReportsHowItEnded(@TempDir Path sandbox) throws Exception {
        EVENTS.clear();
        try (AsyncTestScope scope = new AsyncTestScope()) {
            Rig rig = new Rig(scope, Files.createDirectories(sandbox.resolve("config")));
            FxTestSupport.runOnFx(rig.coordinator::applyPlugins);
            CommandRegistry commands = rig.commands();

            rig.host.statuses.clear();
            FxTestSupport.runOnFx(() -> commands.run("plugin.probe.ok"));
            assertEquals(tr("status.plugins.running", "Probe: OK"), rig.host.await(s -> true));
            assertEquals(tr("status.plugins.cmdDone", "ok"), rig.host.await(s -> true));

            // No title: the id names it. A non-zero exit is a failure, with the code.
            FxTestSupport.runOnFx(() -> commands.run("plugin.probe.fails"));
            assertEquals(tr("status.plugins.running", "fails"), rig.host.await(s -> true));
            assertEquals(tr("status.plugins.cmdFailed", "exit 1"), rig.host.await(s -> true));

            // A program that is not installed is a failure too, not a silent nothing.
            FxTestSupport.runOnFx(() -> commands.run("plugin.probe.missing"));
            assertEquals(tr("status.plugins.running", "missing"), rig.host.await(s -> true));
            String failed = rig.host.await(s -> true);
            assertTrue(failed.startsWith(tr("status.plugins.cmdFailed", "").strip()), failed);

            // A command that declares nothing to run does nothing.
            FxTestSupport.runOnFx(() -> commands.run("plugin.probe.norun"));
            FxTestSupport.drainFx();
            assertTrue(rig.host.statuses.isEmpty(), rig.host.statuses.toString());
        }
    }

    @Test
    void pluginsStayOffInSimpleModeAndTheMasterSwitchSaysWhatItDid(@TempDir Path sandbox) throws Exception {
        EVENTS.clear();
        try (AsyncTestScope scope = new AsyncTestScope()) {
            Rig rig = new Rig(scope, Files.createDirectories(sandbox.resolve("config")));
            rig.host.simple = true;
            assertFalse(rig.coordinator.isEnabled());
            FxTestSupport.runOnFx(() -> {
                rig.coordinator.applyPlugins();
                rig.coordinator.browse();
                rig.coordinator.installFromDisk();
            });
            assertEquals(List.of(), EVENTS, "nothing was started");
            assertEquals(List.of(tr("status.plugins.disabled"), tr("status.plugins.disabled")), rig.host.log);
            assertEquals(List.of(), FxTestSupport.callOnFx(() -> rig.coordinator.editorMenuItems(null)));

            rig.host.simple = false;
            Settings settings = rig.fx.shared.getSettings();
            FxTestSupport.runOnFx(rig.coordinator::toggleSupport);
            assertFalse(settings.isPluginSupport());
            assertEquals(tr("status.toggle.plugins", tr("common.off")), rig.host.log.get(2));
            assertFalse(rig.coordinator.isEnabled());
            FxTestSupport.runOnFx(rig.coordinator::toggleSupport);
            assertTrue(settings.isPluginSupport());
            assertEquals(tr("status.toggle.plugins", tr("common.on")), rig.host.log.get(3));
        }
    }

    private static MouseEvent click() {
        return new MouseEvent(
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
                false,
                false,
                false,
                false,
                false,
                false,
                null);
    }
}
