package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.editora.io.Trash;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A project-tree delete moves the file to the trash where the platform has one, says so in its confirmation,
 * and never turns "to the trash" into a permanent delete without asking.
 *
 * <p>The delete used to be {@code Files.delete}: for the files Local History does not record (binary, over
 * 5 MB, history off) there was no way back at all.
 */
@Tag("fx")
class ProjectPanelTrashFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @TempDir
    Path dir;

    /** Delete operations over a trash in the test folder; {@code refusing} makes the trash fail. */
    private static final class TrashOps implements ProjectPanel.DeleteOperations {
        final Trash.Bin bin;
        boolean refusing;
        final List<Path> permanentlyDeleted = new ArrayList<>();

        TrashOps(Path trashDir) {
            this.bin = Trash.freedesktop(trashDir, null);
        }

        @Override
        public byte[] readAllBytes(Path file) throws IOException {
            return Files.readAllBytes(file);
        }

        @Override
        public void delete(Path file) throws IOException {
            permanentlyDeleted.add(file);
            Files.delete(file);
        }

        @Override
        public boolean movesToTrash(Path file) {
            return bin.accepts(file);
        }

        @Override
        public void moveToTrash(Path file) throws IOException {
            if (refusing) {
                throw new IOException("Trashing on system internal mounts is not supported");
            }
            bin.trash(file);
        }
    }

    private record Fixture(ProjectPanel panel, TrashOps ops, List<Path> deleted, List<String> status) {}

    private Fixture fixture(AsyncTestScope async) throws Exception {
        TrashOps ops = new TrashOps(dir.resolve("trash"));
        List<Path> deleted = new ArrayList<>();
        List<String> status = new ArrayList<>();
        ProjectPanel panel = FxTestSupport.callOnFx(() -> {
            ProjectPanel p = new ProjectPanel(x -> {}, (from, to) -> {}, deleted::add, x -> false);
            p.setDeleteOperations(ops);
            p.setOnStatus(status::add);
            return p;
        });
        async.onClose(() -> FxTestSupport.runOnFx(panel::dispose));
        return new Fixture(panel, ops, deleted, status);
    }

    @Test
    void aDeletedFileGoesToTheTrashWholeIncludingOneLocalHistoryWouldNotRecord() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            Fixture fx = fixture(async);
            byte[] binary = {0, 1, 2, 0, (byte) 0xFF}; // a NUL byte: outside Local History's text contract
            Path file = Files.write(dir.resolve("photo.bin"), binary);

            ProjectPanel.DeleteResult result =
                    async.await(FxTestSupport.callOnFx(() -> fx.panel().deleteConfirmed(List.of(file))));

            assertEquals(1, result.deleted());
            assertFalse(Files.exists(file));
            assertEquals(
                    List.of((Object) (byte) 0, (byte) 1, (byte) 2, (byte) 0, (byte) 0xFF),
                    boxed(Files.readAllBytes(dir.resolve("trash/files/photo.bin"))),
                    "the deleted file is recoverable from the trash, byte for byte");
            assertTrue(fx.ops().permanentlyDeleted.isEmpty(), "nothing was deleted for good");
            assertEquals(List.of(file), fx.deleted(), "open tabs are still told the file is gone");
            assertEquals(List.of(tr("project.trashedOne", "photo.bin")), fx.status());
        }
    }

    @Test
    void whenTheTrashRefusesTheFileIsKeptUnlessTheUserChoosesPermanentDeletion() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            Fixture fx = fixture(async);
            Path file = Files.writeString(dir.resolve("keep.txt"), "do not lose me");
            fx.ops().refusing = true;
            List<String> asked = new ArrayList<>();

            FxTestSupport.runOnFx(() -> fx.panel().setConfirmQuestionForTest(question -> {
                asked.add(question);
                return false;
            }));
            ProjectPanel.DeleteResult declined =
                    async.await(FxTestSupport.callOnFx(() -> fx.panel().deleteConfirmed(List.of(file))));

            assertEquals(1, asked.size(), "a refused trash asks; it does not silently delete for good");
            assertTrue(asked.get(0).contains("keep.txt") && asked.get(0).contains("internal mounts"), asked.get(0));
            assertEquals(0, declined.deleted());
            assertEquals(List.of(file), declined.failed());
            assertEquals("do not lose me", Files.readString(file));
            assertTrue(fx.deleted().isEmpty(), "no tab is closed for a file that is still there");

            FxTestSupport.runOnFx(() -> fx.panel().setConfirmQuestionForTest(question -> true));
            ProjectPanel.DeleteResult accepted =
                    async.await(FxTestSupport.callOnFx(() -> fx.panel().deleteConfirmed(List.of(file))));

            assertEquals(1, accepted.deleted());
            assertFalse(Files.exists(file));
            assertEquals(List.of(file), fx.ops().permanentlyDeleted);
        }
    }

    @Test
    void theConfirmationSaysWhetherTheFilesGoToTheTrashOrAreDeletedForGood() {
        List<Path> one = List.of(Path.of("a.txt"));
        List<Path> three = List.of(Path.of("a.txt"), Path.of("b.txt"), Path.of("c.txt"));

        assertEquals(tr("project.trashFileBody", "a.txt"), ProjectPanel.deleteQuestion(one, 1));
        assertEquals(tr("project.trashMultiBody", 3), ProjectPanel.deleteQuestion(three, 3));
        assertEquals(
                tr("project.deleteFileBody", "a.txt"),
                ProjectPanel.deleteQuestion(one, 0),
                "no trash: the long-standing 'cannot be undone' wording");
        assertEquals(tr("project.deleteMultiBody", 3), ProjectPanel.deleteQuestion(three, 0));
        assertEquals(tr("project.deleteMixedBody", 3, 2, 1), ProjectPanel.deleteQuestion(three, 2));
        assertFalse(ProjectPanel.deleteQuestion(one, 1).equals(ProjectPanel.deleteQuestion(one, 0)));
    }

    @Test
    void theDefaultOperationsUseThePlatformTrashWhichTheSuiteSwitchesOff() throws Exception {
        Path file = Files.writeString(dir.resolve("f.txt"), "x");
        assertFalse(ProjectPanel.DeleteOperations.SYSTEM.movesToTrash(file));
        assertEquals(0, ProjectPanel.trashCount(List.of(file), ProjectPanel.DeleteOperations.SYSTEM));
        TrashOps ops = new TrashOps(dir.resolve("trash"));
        assertEquals(1, ProjectPanel.trashCount(List.of(file, dir.resolve("missing")), ops));
    }

    private static List<Object> boxed(byte[] bytes) {
        List<Object> out = new ArrayList<>();
        for (byte b : bytes) {
            out.add(b);
        }
        return out;
    }
}
