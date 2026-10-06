package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import com.editora.ui.ProjectPanel.FsChange;
import com.editora.ui.ProjectPanel.FsKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The watcher's debounce used to carry one bit — "something changed" — and answer it by rebuilding the whole
 * tree and marking the symbol index stale, including for Editora's own saves. It now carries which directory
 * gained or lost an entry, and tells Editora's writes from everyone else's.
 */
class ProjectWatchChangesTest {

    private static final Path ROOT = Path.of("/project").toAbsolutePath();
    private static final Path SRC = ROOT.resolve("src");

    @Test
    void theSiblingFilesOfAnAtomicSaveAreInternal() {
        assertTrue(ProjectWatchChanges.isInternalName(".Main.java.1234567.editora-tmp"));
        assertTrue(ProjectWatchChanges.isInternalName("Main.java.99.editora-backup"));
        assertTrue(ProjectWatchChanges.isInternalName(".editora-typst-abc.typ"));
        assertFalse(ProjectWatchChanges.isInternalName("Main.java"));
        assertFalse(ProjectWatchChanges.isInternalName("editora-tmp.txt"));
        assertFalse(ProjectWatchChanges.isInternalName(null));
        // The #465 gate sees the whole batch of a save: temp created, temp renamed away, target "created".
        assertFalse(
                ProjectPanel.watchEventsWarrantRefresh(List.of(".a.txt.1.editora-tmp", ".a.txt.1.editora-tmp"), false));
    }

    @Test
    void aSaveEditoraMadeItselfIsNeitherExternalNorAReasonToRelist() {
        ProjectWatchChanges changes = new ProjectWatchChanges();
        Path saved = SRC.resolve("Main.java");
        changes.add(SRC, List.of(new FsChange(saved, FsKind.CREATED), new FsChange(saved, FsKind.CHANGED)), false);

        ProjectWatchChanges.Plan plan = changes.drain(saved::equals);

        assertFalse(plan.hasExternal());
        assertFalse(plan.structural());
        assertEquals(List.of(saved), plan.localWrites(), "the panel re-lists only if the row is missing");
    }

    @Test
    void anExternalCreateRelistsOnlyTheDirectoryItHappenedIn() {
        ProjectWatchChanges changes = new ProjectWatchChanges();
        Path created = SRC.resolve("New.java");
        changes.add(SRC, List.of(new FsChange(created, FsKind.CREATED)), false);

        ProjectWatchChanges.Plan plan = changes.drain(p -> false);

        assertFalse(plan.relistAll());
        assertEquals(Set.of(SRC), plan.relistDirs());
        assertEquals(List.of(new FsChange(created, FsKind.CREATED)), plan.external());
        assertFalse(plan.unknown(), "every changed file was named, so the index can patch itself");
    }

    @Test
    void aModifiedFileChangesNoTree() {
        ProjectWatchChanges changes = new ProjectWatchChanges();
        Path log = ROOT.resolve("build.log");
        changes.add(ROOT, List.of(new FsChange(log, FsKind.CHANGED)), false);
        changes.add(ROOT, List.of(new FsChange(log, FsKind.CHANGED)), false);

        ProjectWatchChanges.Plan plan = changes.drain(p -> false);

        assertFalse(plan.structural());
        assertTrue(plan.hasExternal(), "Git and open diffs still hear about it");
        assertEquals(1, plan.external().size(), "repeats of one event coalesce");
    }

    @Test
    void aDeleteIsExternalEvenForAFileJustSaved() {
        ProjectWatchChanges changes = new ProjectWatchChanges();
        Path file = SRC.resolve("Main.java");
        changes.add(SRC, List.of(new FsChange(file, FsKind.DELETED)), false);

        ProjectWatchChanges.Plan plan = changes.drain(file::equals);

        assertEquals(Set.of(SRC), plan.relistDirs());
        assertTrue(plan.hasExternal());
    }

    @Test
    void aSaveReportedAsDeleteThenCreateIsStillOurs() {
        ProjectWatchChanges changes = new ProjectWatchChanges();
        Path file = SRC.resolve("Main.java");
        changes.add(SRC, List.of(new FsChange(file, FsKind.DELETED), new FsChange(file, FsKind.CREATED)), false);

        ProjectWatchChanges.Plan plan = changes.drain(file::equals);

        assertFalse(plan.hasExternal());
        assertFalse(plan.structural());
        assertEquals(List.of(file), plan.localWrites());
    }

    @Test
    void overflowAnEmptyBatchOrNoBatchAtAllFallBackToLookingAgain() {
        ProjectWatchChanges overflowed = new ProjectWatchChanges();
        overflowed.add(SRC, List.of(), true);
        ProjectWatchChanges.Plan plan = overflowed.drain(p -> false);
        assertTrue(plan.relistAll());
        assertTrue(plan.unknown());

        ProjectWatchChanges empty = new ProjectWatchChanges();
        empty.add(SRC, List.of(), false);
        plan = empty.drain(p -> false);
        assertFalse(plan.relistAll());
        assertEquals(Set.of(SRC), plan.relistDirs());
        assertTrue(plan.unknown(), "the index cannot patch what was not named");

        plan = new ProjectWatchChanges().drain(p -> false);
        assertTrue(plan.relistAll(), "a tick with nothing recorded is treated as anything may have changed");
        assertTrue(plan.hasExternal());
    }

    @Test
    void aFloodStopsNamingFilesButStillRelistsTheirDirectories() {
        ProjectWatchChanges changes = new ProjectWatchChanges();
        for (int i = 0; i < ProjectWatchChanges.MAX_NAMED + 10; i++) {
            Path dir = ROOT.resolve(i < ProjectWatchChanges.MAX_NAMED ? "a" : "b");
            changes.add(dir, List.of(new FsChange(dir.resolve("f" + i), FsKind.CREATED)), false);
        }
        ProjectWatchChanges.Plan plan = changes.drain(p -> false);
        assertEquals(ProjectWatchChanges.MAX_NAMED, plan.external().size());
        assertTrue(plan.unknown());
        assertEquals(Set.of(ROOT.resolve("a"), ROOT.resolve("b")), plan.relistDirs());
    }

    @Test
    void drainingEmptiesTheAccumulator() {
        ProjectWatchChanges changes = new ProjectWatchChanges();
        changes.add(SRC, List.of(new FsChange(SRC.resolve("a"), FsKind.CREATED)), false);
        changes.drain(p -> false);
        changes.add(ROOT, List.of(new FsChange(ROOT.resolve("b"), FsKind.CHANGED)), false);
        ProjectWatchChanges.Plan plan = changes.drain(p -> false);
        assertEquals(Set.of(), plan.relistDirs());
        assertEquals(1, plan.external().size());
    }
}
