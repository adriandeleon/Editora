package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;

import com.editora.config.Bookmark;
import com.editora.config.Breakpoint;
import com.editora.config.ConfigManager;
import com.editora.config.MapTrails;
import com.editora.config.NoteScope;
import com.editora.config.PersonalNote;
import com.editora.config.Project;
import com.editora.config.ProjectSettingsFile;
import com.editora.perf.BlockingSites;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PERF-01 (Pauta 6.0.1), the stores: a window's commands change the config and project stores on the FX thread,
 * and every read, write, listing and serialization of their files runs on a thread of its own. The test builds a
 * window that does its file work where the app does ({@link FxWindowFixture#createWithFileWorkOffThread}), drives
 * a representative set of store paths on the FX thread — a settings change, a tool window shown, a bookmark, a
 * breakpoint, a note, a macro, a dictionary word, a project switch that opens a window by command, a snippet in a
 * language not loaded yet, New from Template, a Map trail saved, a project setting written, settings.json saved in
 * a tab, the agent's session history — and listens to {@link BlockingSites}, which each such place names as it
 * touches a file or serializes one. None may be heard on the FX thread; each must be heard on its own thread; and
 * each file must hold the change once the writes are waited for.
 *
 * <p>What it cannot see: file work or JSON at a place that does not name itself (it checks the named places, not
 * every file call); a wait for a monitor held by another thread (the stores' locks are held only for what is in
 * memory, which this test does not measure); the cost of the copies the FX thread takes of a store before it hands
 * it over (copies are allowed; their size grows with the store); and the paths outside this change (process starts,
 * file commands, Settings sync's apply, the editor's per-keystroke checks).
 */
@Tag("fx")
class StoresOffFxThreadFxTest {

    /** Every {@link BlockingSites#enter} heard: the site, the thread's name, and whether it was the FX thread. */
    private record Heard(String site, String thread, boolean fx) {}

    private final List<Heard> heard = new CopyOnWriteArrayList<>();
    private FxWindowFixture fx;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void tearDown() throws Exception {
        BlockingSites.listenForTest(null);
        if (fx != null) {
            fx.dispose();
        }
    }

    @Test
    void theStorePathsDoNoFileWorkOrJsonOnTheFxThread() throws Exception {
        Path dir = Files.createTempDirectory("editora-stores-offfx");
        Path root = Files.createDirectories(dir.resolve("project"));
        Files.createDirectories(root.resolve(".editora"));
        Files.writeString(root.resolve(".editora/settings.json"), "{\"editor\":{\"tabSize\":3}}");
        fx = FxWindowFixture.createWithFileWorkOffThread(
                dir, shared -> shared.getSettings().setProjectSupport(true));
        ConfigManager config = fx.controller.config;
        Project project = FxTestSupport.callOnFx(() -> fx.shared.projects().createOrGet("project", root));
        fx.shared.awaitWrites(); // what the window's own set-up queued, before the test listens

        BlockingSites.listenForTest(
                (site, thread) -> heard.add(new Heard(site, thread.getName(), Platform.isFxApplicationThread())));
        AtomicReference<String> reloadStatus = new AtomicReference<>();
        AtomicBoolean trailWritten = new AtomicBoolean();
        AtomicBoolean projectFileWritten = new AtomicBoolean();
        AtomicBoolean remembered = new AtomicBoolean();
        FxTestSupport.runOnFx(() -> {
            // A settings change, saved the way a command saves it, and a tool window shown (which saves too).
            config.getSettings().setTabSize(5);
            config.save();
            fx.controller.toolWindows.setVisible(fx.controller.bookmarksToolWindow, true);
            // A bookmark, a breakpoint, a note, a macro.
            config.getBookmarks()
                    .computeIfAbsent("/x/guard.txt", k -> new ArrayList<>())
                    .add(new Bookmark(3, "guard-bookmark", "line"));
            config.saveBookmarks();
            config.getBreakpoints()
                    .computeIfAbsent("/x/Guard.java", k -> new ArrayList<>())
                    .add(Breakpoint.plain(1, "guard();"));
            config.saveBreakpoints();
            config.getNotes()
                    .computeIfAbsent("/x/guard.txt", k -> new ArrayList<>())
                    .add(PersonalNote.create(null, NoteScope.LINE, null, "guard-note", List.of()));
            config.saveNotes();
            config.getMacroStore().put(new com.editora.macro.Macro("guard-macro", "Guard", List.of()));
            config.saveMacros();
            // A personal dictionary word: in the shared set at once, written later.
            config.addUserWord("zzqguardword");
            assertTrue(config.getUserDictionary().contains("zzqguardword"), "the word applies at once");
            // A project switch: its window is opened by command, its session read off the FX thread.
            fx.windowManager.openOrFocus(project);
            // A snippet in a language this window has not loaded: nothing yet, and its load starts.
            assertNull(
                    fx.controller.snippetCoordinator.manager().byTabTrigger("python", "def"),
                    "a language not loaded answers from what is loaded");
            // New from Template: the picker waits for the templates, read on their own thread.
            fx.controller.templateActions.newFromTemplate(null);
            // A Map trail saved.
            ProjectMapView view = new ProjectMapView(path -> {}, path -> false, path -> false);
            view.setRoot(root);
            view.trails.write("Guard", new MapTrails.Trail("Guard", List.of()), trailWritten::set);
            // A project-scoped setting written, and settings.json saved in a tab.
            ProjectSettingsFile.setLater(
                    root, "editor", "tabSize", 2, failure -> projectFileWritten.set(failure == null));
            fx.windowManager.settingsFileSaved(reloadStatus::set);
            // The agent's session history, read for the first time.
            fx.shared.agentSessions(history -> {
                history.remember("guard-session", root.toString(), "Guard", 1L, "claude");
                remembered.set(true);
            });
        });

        // Everything lands.
        SettingsRig.awaitFx("the project window", () -> fx.windowManager.controllerOf(project) != null);
        SettingsRig.awaitFx("the trail", trailWritten::get);
        SettingsRig.awaitFx("the project file", projectFileWritten::get);
        SettingsRig.awaitFx("settings.json re-read", () -> reloadStatus.get() != null);
        SettingsRig.awaitFx("the agent history", remembered::get);
        SettingsRig.awaitFx(
                "the python snippets",
                () -> fx.controller.snippetCoordinator.manager().byTabTrigger("python", "def") != null);
        SettingsRig.awaitFx(
                "the template picker",
                () -> heard.stream().anyMatch(h -> h.site().equals("templates list")));
        fx.shared.awaitWrites();
        FxTestSupport.drainFx();
        BlockingSites.listenForTest(null);

        List<String> onFx = new ArrayList<>();
        for (Heard h : heard) {
            if (h.fx()) {
                onFx.add(h.site());
            }
        }
        assertEquals(List.of(), onFx, "file work or JSON on the FX thread; all heard: " + heard);

        // ...and each ran on the thread that owns it.
        for (String[] where : new String[][] {
            {"json settings.json", "config-writer"},
            {"config write settings.json", "config-writer"},
            {"json bookmarks.json", "config-writer"},
            {"config write bookmarks.json", "config-writer"},
            {"config write breakpoints.json", "config-writer"},
            {"config write notes.json", "config-writer"},
            {"config write macros.json", "config-writer"},
            {"dictionary write", "config-writer"},
            {"json projects.json", "config-writer"},
            {"readVersioned " + project.id() + ".json", "window-session-read"},
            {"snippets load python", "snippet-files"},
            {"templates list", "template-files"},
            {"MapTrails.save", "map-trails-io"},
            {"ProjectSettingsFile.set", "project-settings-write"},
            {"reloadSettings", "config-writer"},
            {"readVersioned agent-sessions.json", "agent-sessions-read"},
        }) {
            assertTrue(
                    heard.stream()
                            .anyMatch(
                                    h -> h.site().equals(where[0]) && h.thread().equals(where[1])),
                    where[0] + " ran on " + where[1] + "; heard: " + heard);
        }

        // Each store has the change on disk.
        assertTrue(Files.readString(dir.resolve("bookmarks.json")).contains("guard-bookmark"));
        assertTrue(Files.readString(dir.resolve("breakpoints.json")).contains("guard();"));
        assertTrue(Files.readString(dir.resolve("notes.json")).contains("guard-note"));
        assertTrue(Files.readString(dir.resolve("macros.json")).contains("guard-macro"));
        assertTrue(Files.readString(dir.resolve("dictionary.txt")).contains("zzqguardword"));
        assertTrue(Files.readString(dir.resolve("projects.json")).contains(project.id()));
        assertTrue(Files.readString(dir.resolve("agent-sessions.json")).contains("guard-session"));
        assertTrue(Files.exists(MapTrails.fileFor(root, "Guard")));
        assertTrue(Files.readString(root.resolve(".editora/settings.json")).contains("\"tabSize\" : 2"));
        assertEquals(5, FxTestSupport.callOnFx(() -> config.getSettings().getTabSize()));
        assertTrue(Files.readString(dir.resolve("settings.json")).contains("\"tabSize\" : 5"));
    }

    @Test
    void aWaitForTheWriterOnTheFxThreadIsABugUntilTheAppQuits() throws Exception {
        fx = FxWindowFixture.createWithFileWorkOffThread(Files.createTempDirectory("editora-stores-wait"), s -> {});
        // Outside the quit: said loudly (an assertion error in a test run with assertions on).
        if (StoresOffFxThreadFxTest.class.desiredAssertionStatus()) {
            Throwable thrown = FxTestSupport.callOnFx(() -> {
                try {
                    fx.shared.flushWrites();
                    return null;
                } catch (AssertionError expected) {
                    return expected;
                }
            });
            assertNotNull(thrown, "a wait on the FX thread outside the quit fails an assertion");
        }
        // The quit's last save may wait: no assertion, and the write is on disk when it returns.
        ConfigManager config = fx.controller.config;
        boolean durable = FxTestSupport.callOnFx(() -> {
            config.getSettings().setTabSize(6);
            config.save(); // queued, as on the FX thread
            fx.shared.quitting();
            return fx.shared.flushWrites();
        });
        assertTrue(durable);
        assertTrue(Files.readString(config.getSettingsFile()).contains("\"tabSize\" : 6"));
    }

    @Test
    void aSecondAskForAWindowWhoseSessionIsBeingReadJoinsIt() throws Exception {
        Path dir = Files.createTempDirectory("editora-stores-join");
        Path root = Files.createDirectories(dir.resolve("joined"));
        fx = FxWindowFixture.createWithFileWorkOffThread(
                dir, shared -> shared.getSettings().setProjectSupport(true));
        List<Runnable> reads = new CopyOnWriteArrayList<>();
        Project project = FxTestSupport.callOnFx(() -> {
            fx.windowManager.sessionReads = reads::add; // held: the read lands when the test says
            return fx.shared.projects().createOrGet("joined", root);
        });
        FxTestSupport.runOnFx(() -> {
            assertNull(fx.windowManager.openOrFocus(project), "the command returns before the window exists");
            assertNull(fx.windowManager.openOrFocus(project), "a second ask joins the read under way");
            assertNull(fx.windowManager.openOrFocus(project));
            assertTrue(fx.windowManager.openingWindows());
            assertNull(fx.windowManager.controllerOf(project), "no window until its session is read");
        });
        assertEquals(1, reads.size(), "one read for the three asks");
        reads.get(0).run(); // the read, on this (test) thread; the build lands on the FX thread
        SettingsRig.awaitFx("the window", () -> fx.windowManager.controllerOf(project) != null);
        FxTestSupport.runOnFx(() -> {
            assertFalse(fx.windowManager.openingWindows());
            long windows =
                    FxTestSupport.<List<?>>field(fx.windowManager, "windows").size();
            assertEquals(2, windows, "the first window and the project's: not two of it");
            assertEquals(project.id(), fx.shared.projects().active().id(), "the project is the active one");
            assertTrue(fx.shared.projects().isOpen(project.id()));
        });
    }

    @Test
    void theTrailsMenuSaysLoadingUntilItsListingLands() throws Exception {
        Path root = Files.createTempDirectory("editora-trails-loading");
        MapTrails.save(root, "Tour", new MapTrails.Trail("Tour", List.of()));
        List<Runnable> work = new CopyOnWriteArrayList<>();
        ProjectMapView view = FxTestSupport.callOnFx(() -> {
            ProjectMapView v = new ProjectMapView(path -> {}, path -> false, path -> false);
            v.trails.io = work::add;
            v.setRoot(root);
            v.trails.fill();
            return v;
        });
        FxTestSupport.runOnFx(() -> assertTrue(
                view.trails.menu().getItems().stream()
                        .anyMatch(i -> com.editora.i18n.Messages.tr("project.map.preview.loading")
                                .equals(i.getText())),
                "a placeholder row while the trails are listed"));
        // Two listings asked; only the newest one's result is kept.
        FxTestSupport.runOnFx(() -> view.trails.fill());
        assertEquals(2, work.size());
        work.get(1).run();
        work.get(0).run(); // an older listing landing late changes nothing
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> {
            assertTrue(view.trails.menu().getItems().stream()
                    .anyMatch(i -> com.editora.i18n.Messages.tr("project.map.trails.play", "Tour")
                            .equals(i.getText())));
            assertFalse(view.trails.menu().getItems().stream()
                    .anyMatch(i -> com.editora.i18n.Messages.tr("project.map.preview.loading")
                            .equals(i.getText())));
        });
        assertEquals(2, work.size(), "a landed listing asks for no other");
    }

    @Test
    void aProjectScopeAnswersWhatItKnewUntilTheNewestReadLands() throws Exception {
        Path root = Files.createTempDirectory("editora-scope-reads");
        Files.createDirectories(root.resolve(".editora"));
        Files.writeString(root.resolve(".editora/settings.json"), "{\"editor\":{\"tabSize\":3}}");
        List<Runnable> reads = new CopyOnWriteArrayList<>();
        int[] told = {0};
        ProjectScope scope = FxTestSupport.callOnFx(() -> {
            ProjectScope s = new ProjectScope(() -> root, () -> null);
            s.reads = reads::add;
            s.addChangeListener(() -> told[0]++);
            return s;
        });
        FxTestSupport.runOnFx(() -> {
            assertTrue(scope.settings().isEmpty(), "never read here: the user's values, nothing of the project's");
            assertFalse(scope.known());
        });
        assertEquals(1, reads.size());
        reads.get(0).run();
        SettingsRig.awaitFx("the first read", scope::known);
        FxTestSupport.runOnFx(() -> assertEquals(3, scope.settings().editor().getTabSize()));

        Files.writeString(root.resolve(".editora/settings.json"), "{\"editor\":{\"tabSize\":5}}");
        FxTestSupport.runOnFx(scope::reload);
        Files.writeString(root.resolve(".editora/settings.json"), "{\"editor\":{\"tabSize\":7}}");
        FxTestSupport.runOnFx(scope::reload);
        assertEquals(3, reads.size());
        FxTestSupport.runOnFx(() -> assertEquals(3, scope.settings().editor().getTabSize(), "the last known value"));
        reads.get(2).run(); // the newest read lands first
        SettingsRig.awaitFx("the newest read", () -> scope.settings().editor().getTabSize() == 7);
        int toldBefore = told[0];
        reads.get(1).run(); // an older read landing late is dropped
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> assertEquals(7, scope.settings().editor().getTabSize()));
        assertEquals(toldBefore, told[0], "and tells nobody");
    }
}
