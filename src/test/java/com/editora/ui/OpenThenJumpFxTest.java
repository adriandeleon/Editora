package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javafx.scene.control.Tab;

import com.editora.command.CommandRegistry;
import com.editora.config.Bookmark;
import com.editora.config.BookmarkMnemonics;
import com.editora.config.ConfigManager;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Open a file that has no tab, then act on a line of it" must wait for the asynchronous load.
 *
 * <p>{@code openPath} only starts the read; until it lands the tab is an empty, read-only shell. Callers that
 * followed it with a bare {@code Platform.runLater} acted on that shell: a bookmark, mnemonic mark or diff
 * "open at line" left the caret on line 1, a TODO tool-window edit was refused, and {@code openAndGoto}'s
 * deferred jump re-ran after the recording flags had been reset, so the history gained a spurious line-1
 * entry and Back into a closed file threw the forward stack away.
 *
 * <p>Every case first runs its call site once on an already-open file: a call site's very first execution
 * in a JVM can be slow enough for a small cached read to win the race, which would hide the failure.
 */
@Tag("fx")
class OpenThenJumpFxTest {

    private static final int LINES = 200;
    private static final int TARGET0 = 149;

    @TempDir
    static Path work;

    private static FxWindowFixture fx;
    private static MainController c;
    private static FileWorkflowCoordinator workflows;
    private static NavigationCoordinator nav;
    private static CommandRegistry registry;
    private static ConfigManager config;
    private static int seq;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        c = fx.controller;
        workflows = FxTestSupport.field(c, "fileWorkflows");
        nav = FxTestSupport.field(c, "navigation");
        registry = FxTestSupport.field(c, "registry");
        config = FxTestSupport.field(c, "config");
        settle();
    }

    @AfterAll
    static void dispose() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    @Test
    void openAndNavigateIntoAClosedFileLandsOnTheLine() throws Exception {
        assertJumps(file -> c.openAndNavigate(file, TARGET0));
    }

    @Test
    void diffOpenAtLineIntoAClosedFileLandsOnTheLine() throws Exception {
        DiffCoordinator diff = FxTestSupport.field(c, "diffCoordinator");
        DiffCoordinator.Ops ops = FxTestSupport.field(diff, "ops");
        assertJumps(file -> ops.openAt(file, TARGET0 + 1)); // the diff viewer's lines are 1-based
    }

    @Test
    void mnemonicMarkIntoAClosedFileLandsOnTheLine() throws Exception {
        assertJumps(file -> {
            Map<String, List<Bookmark>> seeded = new java.util.LinkedHashMap<>(config.getBookmarks());
            seeded.put(file.toString(), new ArrayList<>(List.of(new Bookmark(TARGET0, "", ""))));
            Map<String, List<Bookmark>> marked =
                    new java.util.LinkedHashMap<>(BookmarkMnemonics.assign(seeded, file.toString(), TARGET0, "1"));
            config.getBookmarks().clear();
            config.getBookmarks().putAll(marked);
            registry.run("bookmarks.gotoMnemonic1");
        });
    }

    @Test
    void todoLineEditOfAClosedFileIsApplied() throws Exception {
        TodoCoordinator todo = FxTestSupport.field(c, "todoCoordinator");
        TodoCoordinator.Ops ops = FxTestSupport.field(todo, "ops");
        for (boolean alreadyOpen : new boolean[] {true, false, false}) {
            Path file = newFile("todo");
            if (alreadyOpen) {
                openPlain(file);
            }
            String expected = Files.readAllLines(file).get(TARGET0);
            String edited = expected + "  [DONE]";
            boolean[] rescanned = {false};
            FxTestSupport.runOnFx(
                    () -> ops.applyLineEdit(file, TARGET0 + 1, expected, edited, () -> rescanned[0] = true));
            awaitLoaded(file);
            String now = FxTestSupport.callOnFx(
                    () -> bufferFor(file).getArea().getParagraph(TARGET0).getText());
            assertEquals(edited, now, "the edit reaches the file's text, open=" + alreadyOpen);
            assertTrue(rescanned[0], "the panel is asked to rescan, open=" + alreadyOpen);
            FxTestSupport.runOnFx(() -> bufferFor(file).markClean());
            closeTab(file);
        }
    }

    @Test
    void openAndGotoIntoAClosedFileRecordsTheJumpOnce() throws Exception {
        for (boolean targetOpen : new boolean[] {true, false, false}) {
            Path a = newFile("A");
            Path b = newFile("B");
            openPlain(a);
            if (targetOpen) {
                openPlain(b);
            }
            select(a, 9);
            resetHistory();
            openAndGoto(b, TARGET0);
            awaitLoaded(b);

            assertEquals(TARGET0, caretLine(b));
            List<NavigationHistory.Location> trail = trail();
            assertEquals(2, trail.size(), "origin + destination only, targetOpen=" + targetOpen + ": " + trail);
            assertEquals(a, trail.get(0).path());
            assertEquals(9, trail.get(0).line());
            assertEquals(b, trail.get(1).path());
            assertEquals(TARGET0, trail.get(1).line());
            assertFalse(trail.get(1).snippet().isEmpty(), "recorded after the load, so the snippet is real");

            FxTestSupport.runOnFx(nav::navBack);
            settle();
            assertEquals(a, FxTestSupport.callOnFx(() -> active().getPath()), "one Back returns to the origin");
            assertEquals(9, caretLine(a));
            closeTab(a);
            closeTab(b);
        }
    }

    @Test
    void backIntoAClosedFileKeepsTheForwardStack() throws Exception {
        for (boolean closeIt : new boolean[] {false, true, true}) {
            Path a = newFile("A");
            Path b = newFile("B");
            Path d = newFile("C");
            openPlain(a);
            openPlain(b);
            openPlain(d);
            select(a, 9);
            resetHistory();
            openAndGoto(b, 49);
            settle();
            openAndGoto(d, 99);
            settle();
            if (closeIt) {
                closeTab(a);
            }
            FxTestSupport.runOnFx(nav::navBack); // -> B:50
            settle();
            FxTestSupport.runOnFx(nav::navBack); // -> A:10, reopening it when closed
            awaitLoaded(a);

            List<NavigationHistory.Location> trail = trail();
            assertEquals(3, trail.size(), "Back is not itself a jump, closed=" + closeIt + ": " + trail);
            assertEquals(0, FxTestSupport.callOnFx(() -> nav.navHistory.index()));
            assertTrue(FxTestSupport.callOnFx(() -> nav.navHistory.canForward()), "closed=" + closeIt);
            assertEquals(9, caretLine(a));
            assertFalse(FxTestSupport.callOnFx(() -> nav.navigating), "the back/forward latch is released");
            closeTab(a);
            closeTab(b);
            closeTab(d);
        }
    }

    @Test
    void previewingARecentLocationOfAClosedFileRecordsNothing() throws Exception {
        for (boolean closeIt : new boolean[] {false, true, true}) {
            Path a = newFile("A");
            Path b = newFile("B");
            openPlain(a);
            openPlain(b);
            select(a, 9);
            resetHistory();
            openAndGoto(b, 49);
            settle();
            if (closeIt) {
                closeTab(b);
            }
            select(a, 19);
            List<NavigationHistory.Location> before = trail();
            FxTestSupport.runOnFx(() -> nav.previewLocation(new NavigationHistory.Location(b, 49, 0)));
            awaitLoaded(b);

            assertEquals(49, caretLine(b), "the preview still shows the line");
            assertEquals(before, trail(), "browsing the picker is not history, closed=" + closeIt);
            closeTab(a);
            closeTab(b);
        }
    }

    // ------------------------------------------------------------------------------------------ helpers

    private interface Trigger {
        void run(Path file) throws Exception;
    }

    /** One warm-up on an open file, then two jumps into files that have no tab. */
    private static void assertJumps(Trigger trigger) throws Exception {
        for (boolean alreadyOpen : new boolean[] {true, false, false}) {
            Path file = newFile("jump");
            if (alreadyOpen) {
                openPlain(file);
            }
            FxTestSupport.runOnFx(() -> {
                try {
                    trigger.run(file);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            awaitLoaded(file);
            assertEquals(TARGET0, caretLine(file), "caret line (0-based), file already open=" + alreadyOpen);
            assertEquals(file, FxTestSupport.callOnFx(() -> active().getPath()));
            closeTab(file);
        }
    }

    private static Path newFile(String tag) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= LINES; i++) {
            sb.append("line ").append(i).append(" of ").append(tag).append('\n');
        }
        Path p = work.resolve(tag + "-" + (seq++) + ".txt").toAbsolutePath().normalize();
        Files.writeString(p, sb.toString());
        return p;
    }

    private static Tab tabFor(Path file) {
        return (Tab) FxTestSupport.call(c, "tabForPath", new Class<?>[] {Path.class}, file);
    }

    private static EditorBuffer bufferFor(Path file) {
        Tab t = tabFor(file);
        return t != null && t.getUserData() instanceof EditorBuffer b ? b : null;
    }

    private static EditorBuffer active() {
        return (EditorBuffer) FxTestSupport.call(c, "activeBuffer", new Class<?>[] {});
    }

    private static int caretLine(Path file) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = bufferFor(file);
            return b == null ? -1 : b.getArea().getCurrentParagraph();
        });
    }

    private static List<NavigationHistory.Location> trail() throws Exception {
        return FxTestSupport.callOnFx(
                () -> List.copyOf(FxTestSupport.<List<NavigationHistory.Location>>field(nav.navHistory, "entries")));
    }

    private static void resetHistory() throws Exception {
        FxTestSupport.runOnFx(() -> {
            FxTestSupport.<List<?>>field(nav.navHistory, "entries").clear();
            try {
                var f = NavigationHistory.class.getDeclaredField("index");
                f.setAccessible(true);
                f.setInt(nav.navHistory, -1);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static void openAndGoto(Path file, int line0) throws Exception {
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                c, "openAndGoto", new Class<?>[] {Path.class, int.class, int.class}, file, line0, 0));
    }

    private static void select(Path file, int line0) throws Exception {
        FxTestSupport.runOnFx(() -> {
            EditorArea area = FxTestSupport.field(c, "editorArea");
            area.select(tabFor(file));
            bufferFor(file).getArea().moveTo(line0, 0);
        });
        settle();
    }

    private static void openPlain(Path file) throws Exception {
        FxTestSupport.runOnFx(() -> workflows.openPath(file));
        awaitLoaded(file);
    }

    private static void closeTab(Path file) throws Exception {
        FxTestSupport.runOnFx(() -> {
            Tab t = tabFor(file);
            if (t != null) {
                FxTestSupport.call(c, "closeTab", new Class<?>[] {Tab.class}, t);
            }
        });
        settle();
    }

    /** Blocks until {@code file} has a tab whose asynchronous load has landed and the FX queue is quiet. */
    private static void awaitLoaded(Path file) throws Exception {
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (true) {
            boolean done = FxTestSupport.callOnFx(() -> {
                EditorBuffer b = bufferFor(file);
                return b != null && workflows.loadingBuffers.isEmpty() && !b.isLoading();
            });
            if (done) {
                break;
            }
            assertTrue(System.nanoTime() < deadline, "load never finished: " + file);
            Thread.sleep(5);
        }
        assertNotNull(FxTestSupport.callOnFx(() -> bufferFor(file)));
        settle();
    }

    /** Lets everything queued behind a load (runLater chains, short timers) run. */
    private static void settle() throws Exception {
        for (int i = 0; i < 6; i++) {
            FxTestSupport.drainFx();
        }
        Thread.sleep(150);
        for (int i = 0; i < 6; i++) {
            FxTestSupport.drainFx();
        }
    }
}
