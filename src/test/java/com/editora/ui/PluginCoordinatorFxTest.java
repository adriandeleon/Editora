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
            String disclosure = first.questions.get(0).body();
            assertTrue(disclosure.contains("Greeter 1.0"), disclosure);
            assertTrue(disclosure.contains(tr("plugins.cap.code")), "it ships code, and says so: " + disclosure);
            assertTrue(disclosure.contains("greeter-tool --hello"), "the command it would run is shown: " + disclosure);
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
            assertTrue(second.questions.get(0).body().contains("greeter"));
            assertTrue(Files.exists(second.pluginsDir().resolve("greeter")), "declined: nothing is removed");
            assertTrue(second.enabled("greeter"));

            FxTestSupport.runOnFx(() -> second.coordinator.uninstall("greeter"));
            assertEquals(List.of(), second.errors);
            assertFalse(Files.exists(second.pluginsDir().resolve("greeter")), "its folder is deleted");
            assertFalse(second.enabled("greeter"), "and so is the consent to run it");
            assertEquals(List.of(), second.installed());
            assertEquals(tr("status.plugins.uninstalled", "greeter"), second.host.last());
        }
        assertEquals(List.of("start", "stop"), PLUGIN_EVENTS, "closing the window stopped the plugin");

        try (AsyncTestScope scope = new AsyncTestScope()) {
            Rig third = new Rig(scope, configDir);

            assertEquals(List.of("start", "stop"), PLUGIN_EVENTS, "nothing of it runs any more");
            assertTrue(third.commands().get("plugin.greeter.hello").isEmpty());
            assertTrue(third.commands().get("plugin.greeter.wave").isEmpty());
            assertFalse(third.toolWindowIds().contains("plugin.greeter.panel"));
            assertEquals(List.of(), third.installed());
        }
    }

    /** Installing is not consenting: a plugin whose capabilities the user turns down stays switched off. */
    @Test
    void decliningTheEnableQuestionLeavesThePluginInstalledButOff() throws Exception {
        try (AsyncTestScope scope = new AsyncTestScope()) {
            Rig rig = new Rig(scope, Files.createDirectories(sandbox.resolve("declined")));
            rig.answers.add(false);

            rig.installFromDisk(greeterZip("1.0", false));

            assertEquals(List.of(Question.Kind.ENABLE), rig.asked());
            assertFalse(rig.questions.get(0).body().contains(tr("plugins.cap.code")), "no jar, no code warning");
            assertFalse(rig.enabled("greeter"));
            assertEquals(tr("status.plugins.notEnabled", "Greeter"), rig.host.last());
            assertTrue(Files.isRegularFile(rig.pluginsDir().resolve("greeter/plugin.json")));
            assertFalse(rig.installed().get(0).enabled());
        }
    }

    /**
     * An update replaces the code on disk before the question is asked. Turning the new version's
     * capabilities down therefore has to switch the plugin off — or the rejected code would run at the next
     * launch under the consent given to the old one.
     */
    @Test
    void decliningAnUpdatesCapabilitiesSwitchesThePluginOff() throws Exception {
        try (AsyncTestScope scope = new AsyncTestScope()) {
            Rig rig = new Rig(scope, Files.createDirectories(sandbox.resolve("update")));
            rig.installFromDisk(greeterZip("1.0", false));
            assertTrue(rig.enabled("greeter"));

            rig.answers.add(false);
            rig.installFromDisk(greeterZip("2.0", true));

            assertEquals(List.of(Question.Kind.ENABLE, Question.Kind.ENABLE), rig.asked());
            String second = rig.questions.get(1).body();
            assertTrue(second.contains("Greeter 2.0"), "the question describes the new version: " + second);
            assertTrue(second.contains(tr("plugins.cap.code")), "and what it newly asks for: " + second);
            assertFalse(rig.enabled("greeter"));
            assertEquals("2.0", rig.installed().get(0).manifest().version);
        }
    }

    @Test
    void aZipThatIsNotAPluginIsReportedAndNothingIsEnabled() throws Exception {
        try (AsyncTestScope scope = new AsyncTestScope()) {
            Rig rig = new Rig(scope, Files.createDirectories(sandbox.resolve("not-a-plugin")));

            rig.installFromDisk(TestArchives.zip("README.md", "hello"));

            assertEquals(
                    List.of(tr("status.plugins.installFailed", "no plugin.json in archive")
                            + " | no plugin.json in archive"),
                    rig.errors);
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
            assertEquals(tr("status.plugins.fetching"), rig.host.last(), "no unsigned-registry warning");

            rig.browseAgain();
            assertEquals(
                    List.of(INDEX_URL, INDEX_URL + ".sig"),
                    rig.web.requests(),
                    "the listing is cached until it changes");

            rig.choose(rig.listed().get(0));

            assertEquals(List.of(Question.Kind.INSTALL, Question.Kind.ENABLE), rig.asked());
            String install = rig.questions.get(0).body();
            assertTrue(install.contains("Greeter 1.0 (Ada)"), "name, version and author are told apart: " + install);
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
            assertEquals(
                    List.of(tr("status.plugins.installFailed", "checksum mismatch") + " | checksum mismatch"),
                    rig.errors);
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

            assertEquals(tr("status.plugins.fetchFailed", "HTTP 404"), rig.host.last());
            assertFalse(FxTestSupport.callOnFx(() -> rig.palette().isShown()));

            rig.browse("{\"plugins\":[]}", true);

            assertEquals(tr("status.plugins.empty"), rig.host.last());
            assertFalse(FxTestSupport.callOnFx(() -> rig.palette().isShown()));
            assertEquals(List.of(), rig.errors);
        }
    }
}
