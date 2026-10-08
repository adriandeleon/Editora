package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javafx.scene.control.ButtonBar;
import javafx.scene.input.KeyCode;

import com.editora.ui.ProjectPanel.DeleteApproval;
import com.editora.ui.ProjectPanel.DeleteResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deleting from the Project tree: the question asked, what Cancel and OK leave on disk, and every way the
 * delete itself can stop short — a preparation that fails or answers twice, a file that changed or vanished
 * while it ran, a file that cannot be deleted, a trash that takes several files or lies about one.
 */
@Tag("fx")
class ProjectPanelDeleteFlowFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** A trash that is a folder in the temp dir; it can refuse, or claim success without moving anything. */
    private static final class FakeTrash extends ProjectPanelRig.Ops {
        final Path bin;
        final Set<Path> refused = new HashSet<>();
        boolean pretends;

        FakeTrash(Path bin) throws IOException {
            this.bin = Files.createDirectories(bin);
        }

        @Override
        public boolean movesToTrash(Path file) {
            return true;
        }

        @Override
        public void moveToTrash(Path file) throws IOException {
            if (refused.contains(file)) {
                throw new IOException("the trash is on another volume");
            }
            if (!pretends) {
                Files.move(file, bin.resolve(file.getFileName()));
            }
        }
    }

    @Test
    void deleteAsksFirstAndCancelKeepsTheFile(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            r.answerDialogs(ButtonBar.ButtonData.CANCEL_CLOSE);

            FxTestSupport.runOnFx(() -> {
                r.select(r.alpha);
                assertTrue(r.press(r.tree, KeyCode.DELETE, false).isConsumed());
            });

            assertEquals(List.of(tr("project.deleteFileBody", "alpha.txt")), List.copyOf(r.dialogs));
            assertTrue(Files.exists(r.alpha));
            assertTrue(r.ops.deleted.isEmpty() && r.deletedCallbacks.isEmpty());
        }
    }

    @Test
    void confirmingDeletesTheFileTellsTheWindowAndDropsTheRow(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            r.answerDialogs(ButtonBar.ButtonData.OK_DONE);

            FxTestSupport.runOnFx(() -> {
                r.select(r.alpha);
                r.press(r.tree, KeyCode.DELETE, false);
            });

            assertEquals(List.of(tr("project.deleteFileBody", "alpha.txt")), List.copyOf(r.dialogs));
            assertFalse(Files.exists(r.alpha));
            assertEquals(List.of(r.alpha), r.deletedCallbacks);
            r.awaitChildren(r.root, 3);
            assertEquals(
                    List.of(dir.getFileName().toString(), "docs", "src", "beta.txt"), FxTestSupport.callOnFx(r::rows));
        }
    }

    @Test
    void deletingAMultiSelectionAsksOnceForTheFilesAndLeavesFoldersAlone(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            r.answerDialogs(ButtonBar.ButtonData.OK_DONE);

            FxTestSupport.runOnFx(() -> {
                r.tree.getSelectionModel().clearSelection();
                r.tree.getSelectionModel().select(r.item(r.docs));
                r.tree.getSelectionModel().select(r.item(r.alpha));
                r.tree.getSelectionModel().select(r.item(r.beta));
                ProjectPanelRig.entry(r.menu(r.item(r.beta), false, false).getItems(), "project.menu.delete")
                        .fire();
            });

            assertEquals(List.of(tr("project.deleteMultiBody", 2)), List.copyOf(r.dialogs));
            assertFalse(Files.exists(r.alpha) || Files.exists(r.beta));
            assertTrue(Files.isDirectory(r.docs) && Files.exists(r.guide), "delete is files-only");
            assertEquals(List.of(r.alpha, r.beta), r.deletedCallbacks);
        }
    }

    @Test
    void cancellingAMultiDeleteKeepsEverythingAndAFolderOnlySelectionAsksNothing(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            r.answerDialogs(ButtonBar.ButtonData.CANCEL_CLOSE);

            FxTestSupport.runOnFx(() -> {
                r.tree.getSelectionModel().clearSelection();
                r.tree.getSelectionModel().select(r.item(r.docs));
                r.tree.getSelectionModel().select(r.item(r.src));
                r.press(r.tree, KeyCode.DELETE, false);
            });
            assertTrue(r.dialogs.isEmpty(), "two folders: nothing to delete, nothing to ask");

            FxTestSupport.runOnFx(() -> {
                r.tree.getSelectionModel().select(r.item(r.alpha));
                r.tree.getSelectionModel().select(r.item(r.beta));
                r.press(r.tree, KeyCode.DELETE, false);
            });
            assertEquals(List.of(tr("project.deleteMultiBody", 2)), List.copyOf(r.dialogs));
            assertTrue(Files.exists(r.alpha) && Files.exists(r.beta));
        }
    }

    @Test
    void aClickedRowOutsideTheSelectionIsTheOnlyOneDeleted(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            r.answerDialogs(ButtonBar.ButtonData.OK_DONE);

            FxTestSupport.runOnFx(() -> {
                r.tree.getSelectionModel().clearSelection();
                r.tree.getSelectionModel().select(r.item(r.docs));
                r.tree.getSelectionModel().select(r.item(r.alpha));
                ProjectPanelRig.entry(r.menu(r.item(r.beta), false, false).getItems(), "project.menu.delete")
                        .fire();
            });

            assertEquals(List.of(tr("project.deleteFileBody", "beta.txt")), List.copyOf(r.dialogs));
            assertFalse(Files.exists(r.beta));
            assertTrue(Files.exists(r.alpha), "the selection the click was not part of is untouched");
        }
    }

    @Test
    void nothingToDeleteCompletesAtOnce(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            DeleteResult none = r.async.await(FxTestSupport.callOnFx(() -> r.panel.deleteConfirmed(null)));
            assertEquals(new DeleteResult(true, 0, List.of()), none);
            DeleteResult nulls = r.async.await(
                    FxTestSupport.callOnFx(() -> r.panel.deleteConfirmed(java.util.Arrays.asList(null, null))));
            assertEquals(new DeleteResult(true, 0, List.of()), nulls);
        }
    }

    @Test
    void aPreparationThatThrowsOrRefusesDeletesNothing(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            FxTestSupport.runOnFx(() -> r.panel.setDeletePreparation((files, completion) -> {
                throw new IllegalStateException("history store is closed");
            }));
            DeleteResult thrown =
                    r.async.await(FxTestSupport.callOnFx(() -> r.panel.deleteConfirmed(List.of(r.alpha))));
            assertEquals(new DeleteResult(false, 0, List.of(r.alpha)), thrown);

            FxTestSupport.runOnFx(() -> r.panel.setDeletePreparation((files, completion) -> completion.accept(null)));
            DeleteResult unanswered =
                    r.async.await(FxTestSupport.callOnFx(() -> r.panel.deleteConfirmed(List.of(r.alpha))));
            assertEquals(new DeleteResult(false, 0, List.of(r.alpha)), unanswered);

            FxTestSupport.runOnFx(() ->
                    r.panel.setDeletePreparation((files, completion) -> completion.accept(DeleteApproval.denied())));
            DeleteResult denied =
                    r.async.await(FxTestSupport.callOnFx(() -> r.panel.deleteConfirmed(List.of(r.alpha))));
            assertEquals(new DeleteResult(false, 0, List.of(r.alpha)), denied);

            assertTrue(Files.exists(r.alpha));
            assertTrue(r.ops.deleted.isEmpty());

            // Back to the default (no preparation): the delete goes straight through.
            FxTestSupport.runOnFx(() -> r.panel.setDeletePreparation(null));
            DeleteResult plain = r.async.await(FxTestSupport.callOnFx(() -> r.panel.deleteConfirmed(List.of(r.alpha))));
            assertEquals(new DeleteResult(true, 1, List.of()), plain);
            assertFalse(Files.exists(r.alpha));
        }
    }

    @Test
    void aPreparationThatAnswersTwiceDeletesOnce(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            FxTestSupport.runOnFx(() -> r.panel.setDeletePreparation((files, completion) -> {
                completion.accept(DeleteApproval.approved());
                completion.accept(DeleteApproval.approved());
            }));

            DeleteResult result =
                    r.async.await(FxTestSupport.callOnFx(() -> r.panel.deleteConfirmed(List.of(r.alpha, r.alpha))));

            assertEquals(new DeleteResult(true, 1, List.of()), result);
            assertEquals(List.of(r.alpha), r.ops.deleted, "one delete, for a file named twice and approved twice");
            assertEquals(List.of(r.alpha), r.deletedCallbacks);
        }
    }

    @Test
    void aFileThatChangedOrVanishedDuringPreparationStopsTheWholeDelete(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            byte[] captured = Files.readAllBytes(r.alpha);
            FxTestSupport.runOnFx(() -> r.panel.setDeletePreparation(
                    (files, completion) -> completion.accept(new DeleteApproval(true, Map.of(r.alpha, captured)))));

            Files.writeString(r.alpha, "edited by another program\n");
            DeleteResult changed =
                    r.async.await(FxTestSupport.callOnFx(() -> r.panel.deleteConfirmed(List.of(r.beta, r.alpha))));
            assertEquals(new DeleteResult(false, 0, List.of(r.beta, r.alpha)), changed);
            assertTrue(Files.exists(r.beta), "not even the unchanged file of the batch is deleted");

            Files.delete(r.alpha);
            DeleteResult vanished =
                    r.async.await(FxTestSupport.callOnFx(() -> r.panel.deleteConfirmed(List.of(r.beta, r.alpha))));
            assertEquals(new DeleteResult(false, 0, List.of(r.beta, r.alpha)), vanished);
            assertTrue(Files.exists(r.beta));

            assertEquals(
                    List.of(tr("project.deleteChanged", "alpha.txt"), tr("project.deleteChanged", "alpha.txt")),
                    r.status);
            assertTrue(r.ops.deleted.isEmpty());
        }
    }

    @Test
    void aFileThatCannotBeDeletedIsReportedAndTheOthersStillGo(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            r.answerDialogs(ButtonBar.ButtonData.OK_DONE);
            r.ops.undeletable.add(r.alpha);

            DeleteResult result =
                    r.async.await(FxTestSupport.callOnFx(() -> r.panel.deleteConfirmed(List.of(r.alpha, r.beta))));

            assertEquals(new DeleteResult(true, 1, List.of(r.alpha)), result);
            assertEquals(
                    List.of(tr("project.deleteError", "alpha.txt", "locked by another program")),
                    List.copyOf(r.dialogs));
            assertTrue(Files.exists(r.alpha));
            assertFalse(Files.exists(r.beta));
            assertEquals(List.of(r.beta), r.deletedCallbacks, "only the file that is gone is reported gone");
        }
    }

    @Test
    void severalTrashedFilesAreSummedUpInTheStatusLine(@TempDir Path dir, @TempDir Path elsewhere) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            FakeTrash trash = new FakeTrash(elsewhere.resolve("bin"));
            FxTestSupport.runOnFx(() -> r.panel.setDeleteOperations(trash));

            DeleteResult result =
                    r.async.await(FxTestSupport.callOnFx(() -> r.panel.deleteConfirmed(List.of(r.alpha, r.beta))));

            assertEquals(new DeleteResult(true, 2, List.of()), result);
            assertEquals(List.of(tr("project.trashedMany", 2)), r.status);
            assertEquals("alpha\n", Files.readString(trash.bin.resolve("alpha.txt")), "recoverable from the trash");
            assertTrue(trash.deleted.isEmpty(), "nothing was deleted for good");
        }
    }

    @Test
    void theOneTrashedFileOfABatchIsNamedEvenWhenItsNeighbourWasRefused(@TempDir Path dir, @TempDir Path elsewhere)
            throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            FakeTrash trash = new FakeTrash(elsewhere.resolve("bin"));
            trash.refused.add(r.alpha);
            FxTestSupport.runOnFx(() -> {
                r.panel.setDeleteOperations(trash);
                r.panel.setConfirmQuestionForTest(question -> false); // keep what the trash would not take
            });

            DeleteResult result =
                    r.async.await(FxTestSupport.callOnFx(() -> r.panel.deleteConfirmed(List.of(r.alpha, r.beta))));

            assertEquals(new DeleteResult(true, 1, List.of(r.alpha)), result);
            assertEquals(List.of(tr("project.trashedOne", "beta.txt")), r.status);
            assertTrue(Files.exists(r.alpha));
        }
    }

    @Test
    void aTrashThatLeavesTheFileInPlaceIsStillReportedByName(@TempDir Path dir, @TempDir Path elsewhere)
            throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            FakeTrash trash = new FakeTrash(elsewhere.resolve("bin"));
            trash.pretends = true;
            FxTestSupport.runOnFx(() -> r.panel.setDeleteOperations(trash));

            DeleteResult result =
                    r.async.await(FxTestSupport.callOnFx(() -> r.panel.deleteConfirmed(List.of(r.alpha))));

            assertEquals(new DeleteResult(true, 1, List.of()), result);
            assertEquals(List.of(tr("project.trashedOne", "alpha.txt")), r.status);
        }
    }

    @Test
    void whenTheTrashRefusesAndPermanentDeletionAlsoFailsTheFileIsKeptAndSaidSo(
            @TempDir Path dir, @TempDir Path elsewhere) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            r.answerDialogs(ButtonBar.ButtonData.OK_DONE);
            FakeTrash trash = new FakeTrash(elsewhere.resolve("bin"));
            trash.refused.addAll(List.of(r.alpha, r.beta));
            trash.undeletable.add(r.alpha);
            List<String> asked = new java.util.ArrayList<>();
            FxTestSupport.runOnFx(() -> {
                r.panel.setDeleteOperations(trash);
                r.panel.setConfirmQuestionForTest(question -> {
                    asked.add(question);
                    return true;
                });
            });

            DeleteResult result =
                    r.async.await(FxTestSupport.callOnFx(() -> r.panel.deleteConfirmed(List.of(r.alpha, r.beta))));

            assertEquals(
                    List.of(tr("project.trashFailedMultiBody", 2, "the trash is on another volume")),
                    asked,
                    "one question for both, giving the trash's own reason");
            assertEquals(new DeleteResult(true, 1, List.of(r.alpha)), result);
            assertEquals(
                    List.of(tr("project.deleteError", "alpha.txt", "locked by another program")),
                    List.copyOf(r.dialogs));
            assertTrue(Files.exists(r.alpha));
            assertFalse(Files.exists(r.beta));
            assertTrue(r.status.isEmpty(), "nothing reached the trash, so nothing claims it did");
        }
    }
}
