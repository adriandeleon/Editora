package com.editora.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.application.Platform;
import javafx.stage.FileChooser;

import com.editora.perf.OffFx;
import com.editora.plugin.PluginDescriptor;
import com.editora.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PERF-01 (Pauta 6.0.1), part 4: the pieces the file commands, installs and plugins moved off the FX thread with —
 * {@link OffFx}, the looks they make before they act, and their refusal and failure answers.
 */
@Tag("fx")
class FileWorkOffFxTest {

    @TempDir
    Path dir;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void tearDown() {
        OffFx.runnerForTest(null);
    }

    // --- OffFx -----------------------------------------------------------------------------------------------

    @Test
    void theWorkRunsOffTheFxThreadAndItsAnswerLandsOnIt() throws Exception {
        CompletableFuture<String> workThread = new CompletableFuture<>();
        CompletableFuture<Boolean> landedOnFx = new CompletableFuture<>();
        CompletableFuture<OffFx.Answer<Integer>> answer = new CompletableFuture<>();
        FxTestSupport.runOnFx(() -> OffFx.then(
                "guard-work",
                () -> {
                    workThread.complete(Thread.currentThread().getName());
                    return 42;
                },
                a -> {
                    landedOnFx.complete(Platform.isFxApplicationThread());
                    answer.complete(a);
                }));
        assertEquals("guard-work", workThread.get(10, TimeUnit.SECONDS));
        assertTrue(landedOnFx.get(10, TimeUnit.SECONDS));
        OffFx.Answer<Integer> a = answer.get(10, TimeUnit.SECONDS);
        assertTrue(a.ok());
        assertEquals(42, a.or(0));
        assertEquals("", a.why());
    }

    @Test
    void aFailureComesBackAsTheAnswerWithItsMessage() throws Exception {
        CompletableFuture<OffFx.Answer<String>> answer = new CompletableFuture<>();
        OffFx.then(
                "guard-fail",
                () -> {
                    throw new IOException("disk on fire");
                },
                answer::complete);
        OffFx.Answer<String> a = answer.get(10, TimeUnit.SECONDS);
        assertFalse(a.ok());
        assertNull(a.value());
        assertEquals("fallback", a.or("fallback"));
        assertEquals("disk on fire", a.why());

        CompletableFuture<Throwable> failed = new CompletableFuture<>();
        OffFx.run(
                "guard-run",
                () -> {
                    throw new IOException("no room");
                },
                failed::complete);
        assertEquals("no room", failed.get(10, TimeUnit.SECONDS).getMessage());
        CompletableFuture<Boolean> fine = new CompletableFuture<>();
        OffFx.run("guard-run", () -> {}, t -> fine.complete(t == null));
        assertTrue(fine.get(10, TimeUnit.SECONDS));
    }

    @Test
    void inPlaceTheAnswerLandsBeforeTheCallReturns() throws Exception {
        OffFx.runnerForTest((name, task) -> task.run());
        boolean landed = FxTestSupport.callOnFx(() -> {
            boolean[] seen = {false};
            OffFx.then("guard-in-place", () -> "x", a -> seen[0] = true);
            return seen[0];
        });
        assertTrue(landed);
    }

    // --- plugin removal ---------------------------------------------------------------------------------------

    @Test
    void aPluginIsRemovedOnlyFromItsOwnFolderAndAFailureIsSaid() throws Exception {
        Path plugins = Files.createDirectories(dir.resolve("plugins"));
        Path greeter = Files.createDirectories(plugins.resolve("greeter"));
        Files.writeString(
                greeter.resolve("plugin.json"), "{\"id\":\"greeter\",\"name\":\"Greeter\",\"version\":\"1\"}");
        PluginManager manager = new PluginManager(plugins, id -> false);
        manager.discover();
        PluginDescriptor listed = PluginCoordinator.descriptorFor(manager.descriptors(), "greeter");
        assertTrue(listed != null);

        Path elsewhere = Files.createDirectories(dir.resolve("elsewhere"));
        assertEquals(
                PluginCoordinator.Removal.NOT_REMOVABLE,
                PluginCoordinator.remove(elsewhere, listed, false),
                "never a folder outside the plugins folder");
        assertTrue(Files.exists(greeter));
        assertEquals(
                PluginCoordinator.Removal.DONE,
                PluginCoordinator.remove(plugins, listed, true),
                "a running plugin is marked for the next start, not deleted");
        assertTrue(Files.exists(greeter), "still there until the next start");
        assertTrue(Files.exists(plugins.resolve(".remove-greeter")), "marked");
        assertEquals(PluginCoordinator.Removal.DONE, PluginCoordinator.remove(plugins, listed, false));
        assertFalse(Files.exists(greeter));
    }

    // --- looks before a command acts ---------------------------------------------------------------------------

    @Test
    void aRenameIsRefusedWhenTheNameIsTakenAndMovesOtherwise() throws Exception {
        Path a = Files.writeString(dir.resolve("a.txt"), "a");
        Path b = Files.writeString(dir.resolve("b.txt"), "b");
        assertFalse(RenamedFileState.move(a, b), "taken: nothing moved");
        assertEquals("a", Files.readString(a));
        assertEquals("b", Files.readString(b));
        Path c = dir.resolve("c.txt");
        assertTrue(RenamedFileState.move(a, c));
        assertFalse(Files.exists(a));
        assertEquals("a", Files.readString(c));
    }

    @Test
    void aMovePlanSkipsTakenNamesAndUndoSkipsWhatMovedAgain() throws Exception {
        Path into = Files.createDirectories(dir.resolve("into"));
        Path a = Files.writeString(dir.resolve("a.txt"), "a");
        Path b = Files.writeString(dir.resolve("b.txt"), "b");
        Files.writeString(into.resolve("b.txt"), "already there");
        ProjectPanel.MovePlan plan = ProjectPanel.MovePlan.into(List.of(a, b), into);
        assertTrue(plan.targetIsFolder());
        assertEquals(List.of(new ProjectPanel.Move(a, into.resolve("a.txt"))), plan.moves());
        assertEquals(1, plan.skipped());
        assertFalse(plan.needsConfirmation(), "one file moves at once");
        assertFalse(
                ProjectPanel.MovePlan.into(List.of(a), dir.resolve("missing")).targetIsFolder());

        Files.move(a, into.resolve("a.txt"));
        ProjectPanel.MovePlan back =
                ProjectPanel.MovePlan.back(List.of(new ProjectPanel.Move(a, into.resolve("a.txt"))));
        assertEquals(List.of(new ProjectPanel.Move(into.resolve("a.txt"), a)), back.moves());
        Files.writeString(a, "a new one in the old place");
        assertEquals(
                1,
                ProjectPanel.MovePlan.back(List.of(new ProjectPanel.Move(a, into.resolve("a.txt"))))
                        .skipped());

        ProjectPanel.Moved moved = ProjectPanel.Moved.look(b, into.resolve("a.txt"));
        assertFalse(moved.moved(), "b.txt is still where it was");
        assertTrue(ProjectPanel.Moved.look(dir.resolve("gone"), into).folder());
    }

    @Test
    void aMoveProceedTellsWhoWaitsForItWhenItLandsAndWhoAsksLaterAtOnce() {
        AtomicInteger started = new AtomicInteger();
        ProjectPanel.MoveProceed proceed = new ProjectPanel.MoveProceed(ok -> started.incrementAndGet());
        ProjectPanel.Moved[] early = {null};
        proceed.whenMoved(m -> early[0] = m);
        proceed.accept(true);
        assertEquals(1, started.get());
        assertNull(early[0], "not landed yet");
        ProjectPanel.Moved landed = new ProjectPanel.Moved(true, false);
        proceed.land(landed);
        assertSame(landed, early[0]);
        ProjectPanel.Moved[] late = {null};
        proceed.whenMoved(m -> late[0] = m);
        assertSame(landed, late[0], "asked after it landed: told at once");
    }

    @Test
    void aStagedHistoryCaptureReadsOnlyARegularFileOfAHoldableSize() throws Exception {
        Path text = Files.writeString(dir.resolve("t.txt"), "hello");
        HistoryCoordinator.Captured read = HistoryCoordinator.Captured.read(text, false, p -> "latin1");
        assertTrue(read.regular());
        assertEquals("hello", new String(read.bytes()));
        assertEquals("latin1", read.charsetRule());
        assertFalse(HistoryCoordinator.Captured.read(dir, true, p -> null).regular(), "a folder is no file");
        assertFalse(HistoryCoordinator.Captured.read(dir.resolve("gone"), false, p -> null)
                .regular());
    }

    @Test
    void theNearestFolderIsLookedUpTheTree() throws Exception {
        Path deep = dir.resolve("a/b/c");
        assertEquals(dir.toFile(), TemplateCoordinator.nearestFolder(deep));
        assertEquals(dir.toFile(), TemplateCoordinator.nearestFolder(dir));
        assertNull(TemplateCoordinator.nearestFolder(null));
    }

    @Test
    void aChooserWhoseStartFolderIsRefusedOpensAgainWithoutIt() throws Exception {
        File picked = dir.resolve("picked.txt").toFile();
        FileChooser chooser = FxTestSupport.callOnFx(FileChooser::new);
        ChooserStart.set(chooser, dir.resolve("gone"));
        assertEquals(dir.resolve("gone").toFile(), chooser.getInitialDirectory(), "named as it is, not looked at");
        AtomicInteger shown = new AtomicInteger();
        File chosen = ChooserStart.show(chooser, c -> {
            shown.incrementAndGet();
            if (c.getInitialDirectory() != null) {
                throw new IllegalArgumentException("Folder parameter must be a valid folder");
            }
            return picked;
        });
        assertEquals(picked, chosen);
        assertEquals(2, shown.get());
        assertNull(chooser.getInitialDirectory());
    }
}
