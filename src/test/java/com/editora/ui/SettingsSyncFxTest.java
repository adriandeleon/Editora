package com.editora.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javafx.scene.control.Label;

import com.editora.command.CommandRegistry;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.git.QuietGit;
import com.editora.sync.FileSyncTarget;
import com.editora.sync.SyncCategory;
import com.editora.sync.SyncEngine;
import com.editora.sync.SyncReport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Settings sync in a real window: what another computer pushed is usable here without a restart, what is
 * changed here reaches the other computer, and a failing sync is marked in the status bar. The other computer
 * is a plain config directory driven by the engine; the merge itself is covered by {@code SyncEngineTest}.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SettingsSyncFxTest {

    private static final Set<SyncCategory> ALL = EnumSet.allOf(SyncCategory.class);

    private Path tmp;

    private FxWindowFixture fx;
    private SettingsSync sync;
    private Settings settings;
    private Path remote;
    private Path other;

    @BeforeAll
    void setUp() throws Exception {
        assumeTrue(QuietGit.available(), "git is not installed");
        FxTestSupport.bootToolkit();
        tmp = Files.createTempDirectory("editora-sync-fx");
        remote = tmp.resolve("remote.git");
        git(tmp, "init", "-q", "--bare", remote.toString());
        git(remote, "symbolic-ref", "HEAD", "refs/heads/main");
        other = Files.createDirectories(tmp.resolve("other"));
        fx = FxWindowFixture.create();
        settings = fx.shared.getSettings();
        sync = FxTestSupport.callOnFx(() -> fx.windowManager.settingsSync());
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
        if (tmp != null) {
            FileSyncTarget.deleteTree(tmp);
        }
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> argv = new java.util.ArrayList<>(List.of("git"));
        argv.addAll(List.of(args));
        Process p = new ProcessBuilder(argv)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, p.exitValue(), out);
    }

    private SyncReport otherSyncs() {
        QuietGit git = new QuietGit(other.resolve("sync").resolve("repo"), false);
        return new SyncEngine(git, remote.toString(), "main", new FileSyncTarget(other), "other").run(ALL, false);
    }

    private void writeOther(String path, String text) throws Exception {
        Path file = other.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    /** Runs "Sync Now" and waits for the run to end. */
    private SettingsSync.State syncNow() throws Exception {
        FxTestSupport.runOnFx(sync::syncNow);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            SettingsSync.State state = FxTestSupport.callOnFx(sync::state);
            if (state.phase() != SettingsSync.Phase.SYNCING) {
                FxTestSupport.drainFx();
                return state;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("the sync did not finish");
    }

    private boolean problemMarkerShown() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            StatusBar bar = fx.controller.statusBar();
            Label marker = FxTestSupport.field(bar, "syncProblem");
            return marker.isVisible();
        });
    }

    @Test
    @Order(1)
    void nothingRunsUntilSyncIsConnected() throws Exception {
        assertEquals(SettingsSync.Phase.OFF, FxTestSupport.callOnFx(sync::state).phase());
        assertEquals(SettingsSync.Phase.OFF, syncNow().phase(), "Sync Now with no repository only says so");
        assertFalse(Files.exists(fx.configDir.resolve("sync")), "and creates nothing");
    }

    @Test
    @Order(2)
    void whatAnotherComputerPushedIsUsableHereWithoutARestart() throws Exception {
        writeOther("dictionary.txt", "zzqword\n");
        writeOther(
                "abbreviations.json",
                "{\"schemaVersion\":1,\"abbreviations\":[{\"abbreviation\":\"zzq\",\"expansion\":\"synced text\"}]}");
        writeOther("snippets/plaintext.json", "{ \"greet\": { \"prefix\": \"zzgreet\", \"body\": \"hello\" } }");
        writeOther("templates/zznote.json", "{\"name\":\"ZZ Note\",\"fileName\":\"note.txt\",\"body\":\"x\"}");
        assertTrue(otherSyncs().ok());

        // A buffer that is already open when the sync runs: it holds its own copy of the abbreviations.
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent("zzq");
            FxTestSupport.call(fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, b, true);
            b.getArea().moveTo(b.getArea().getLength());
            return b;
        });
        FxTestSupport.runOnFx(() -> {
            settings.setSyncRepoUrl(remote.toString());
            settings.setSyncAuto(false);
            settings.setSyncEnabled(true);
            sync.settingsChanged();
        });

        SettingsSync.State state = syncNow();
        assertEquals(SettingsSync.Phase.IDLE, state.phase(), state.report().detail());
        assertEquals(4, state.report().received().size());
        assertTrue(FxTestSupport.callOnFx(() -> fx.shared.getUserDictionary().contains("zzqword")));
        assertEquals(
                "synced text",
                FxTestSupport.callOnFx(() -> fx.shared.abbreviationMap().get("zzq")));
        assertTrue(Files.isRegularFile(fx.configDir.resolve("snippets/plaintext.json")));
        assertTrue(Files.isRegularFile(fx.configDir.resolve("templates/zznote.json")));

        CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
        FxTestSupport.runOnFx(() -> registry.run("edit.expandAbbrev"));
        assertEquals(
                "synced text", FxTestSupport.callOnFx(() -> buffer.getArea().getText()));
        assertFalse(problemMarkerShown());
    }

    @Test
    @Order(3)
    void whatChangesHereReachesTheOtherComputer() throws Exception {
        FxTestSupport.runOnFx(() -> fx.shared.addUserWord("zzlocal"));
        SettingsSync.State state = syncNow();
        assertEquals(SettingsSync.Phase.IDLE, state.phase(), state.report().detail());
        assertEquals(1, state.report().sent().size());
        assertTrue(otherSyncs().ok());
        assertEquals("zzlocal\nzzqword\n", Files.readString(other.resolve("dictionary.txt")));
    }

    @Test
    @Order(4)
    void aFailingSyncIsMarkedInTheStatusBarAndClearsWhenItWorksAgain() throws Exception {
        FxTestSupport.runOnFx(() -> {
            settings.setSyncRepoUrl(tmp.resolve("gone.git").toString());
            sync.settingsChanged();
        });
        SettingsSync.State failed = syncNow();
        assertEquals(SettingsSync.Phase.PROBLEM, failed.phase());
        assertEquals(SyncReport.Status.FETCH_FAILED, failed.report().status());
        assertTrue(problemMarkerShown());
        assertTrue(FxTestSupport.callOnFx(() -> fx.shared.getUserDictionary().contains("zzqword")), "data stays");

        FxTestSupport.runOnFx(() -> {
            settings.setSyncRepoUrl(remote.toString());
            sync.settingsChanged();
        });
        assertEquals(SettingsSync.Phase.IDLE, syncNow().phase());
        assertFalse(problemMarkerShown());
    }

    @Test
    @Order(5)
    void disconnectingKeepsTheDataAndStopsSyncing() throws Exception {
        FxTestSupport.runOnFx(() -> {
            settings.setSyncEnabled(false);
            sync.disconnected();
        });
        assertEquals(SettingsSync.Phase.OFF, FxTestSupport.callOnFx(sync::state).phase());
        assertTrue(FxTestSupport.callOnFx(() -> fx.shared.getUserDictionary().contains("zzlocal")));
        FxTestSupport.runOnFx(() -> fx.shared.addUserWord("zzafter"));
        assertEquals(SettingsSync.Phase.OFF, syncNow().phase());
        assertTrue(otherSyncs().ok());
        assertFalse(Files.readString(other.resolve("dictionary.txt")).contains("zzafter"));
    }
}
