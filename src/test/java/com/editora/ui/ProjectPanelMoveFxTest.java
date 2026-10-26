package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
 * Exercises the Project tree's drag-to-move end-to-end (the private {@code moveInto}, invoked via reflection):
 * files actually move on disk into the target folder, the rename callback fires per moved file (so open
 * buffers can follow), and a name conflict in the target is skipped rather than clobbering.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.junit.jupiter.api.extension.ExtendWith(DisposedPanels.class)
class ProjectPanelMoveFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private record Rename(Path from, Path to) {}

    @Test
    void movesFilesIntoAFolderAndSkipsConflicts(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("a.txt"), "a");
        Files.writeString(root.resolve("c.txt"), "c");
        Path sub = Files.createDirectory(root.resolve("sub"));
        Files.writeString(sub.resolve("c.txt"), "existing"); // conflict target for c.txt

        List<Rename> renames = new ArrayList<>();
        ProjectPanel panel = FxTestSupport.callOnFx(() -> {
            ProjectPanel p = DisposedPanels.track(
                    new ProjectPanel(x -> {}, (from, to) -> renames.add(new Rename(from, to)), x -> {}, x -> false));
            p.setRoot(root);
            return p;
        });

        // Move a.txt (no conflict) into sub/.
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                panel, "moveInto", new Class<?>[] {List.class, Path.class}, List.of(root.resolve("a.txt")), sub));
        assertTrue(Files.exists(sub.resolve("a.txt")), "a.txt moved into sub/");
        assertFalse(Files.exists(root.resolve("a.txt")), "a.txt gone from root");
        assertEquals(1, renames.size(), "one rename notified");
        assertEquals(root.resolve("a.txt"), renames.get(0).from());
        assertEquals(sub.resolve("a.txt"), renames.get(0).to());

        // Move c.txt into sub/ where a sub/c.txt already exists → skipped, nothing clobbered.
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                panel, "moveInto", new Class<?>[] {List.class, Path.class}, List.of(root.resolve("c.txt")), sub));
        assertTrue(Files.exists(root.resolve("c.txt")), "c.txt stays in root (target name taken)");
        assertEquals("existing", Files.readString(sub.resolve("c.txt")), "existing sub/c.txt untouched");
        assertEquals(1, renames.size(), "no new rename for the skipped conflict");
    }

    private record Panel(ProjectPanel panel, List<Rename> renames, List<String> asked, boolean[] answer) {}

    private Panel panel(Path root) throws Exception {
        List<Rename> renames = new ArrayList<>();
        List<String> asked = new ArrayList<>();
        boolean[] answer = {true};
        ProjectPanel panel = FxTestSupport.callOnFx(() -> {
            ProjectPanel p = DisposedPanels.track(
                    new ProjectPanel(x -> {}, (from, to) -> renames.add(new Rename(from, to)), x -> {}, x -> false));
            p.setRoot(root);
            p.setConfirmQuestionForTest(question -> {
                asked.add(question);
                return answer[0];
            });
            return p;
        });
        return new Panel(panel, renames, asked, answer);
    }

    private static void move(ProjectPanel panel, List<Path> sources, Path target) throws Exception {
        FxTestSupport.runOnFx(
                () -> FxTestSupport.call(panel, "moveInto", new Class<?>[] {List.class, Path.class}, sources, target));
        settle();
    }

    /** Lets a drag's later moves run: each one follows on the next pulse (fifth review BV1). */
    private static void settle() throws Exception {
        for (int i = 0; i < 12; i++) {
            FxTestSupport.drainFx();
        }
    }

    /**
     * A drag that ends on a folder row relocated a whole source tree at once — an easy slip that silently
     * breaks packages and imports. A folder move now asks first, and "no" moves nothing.
     */
    @Test
    void movingAFolderAsksFirstAndDecliningMovesNothing(@TempDir Path root) throws Exception {
        Path pkg = Files.createDirectories(root.resolve("src/com/acme"));
        Files.writeString(pkg.resolve("Main.java"), "class Main {}");
        Path target = Files.createDirectory(root.resolve("docs"));
        Panel p = panel(root);

        p.answer()[0] = false;
        move(p.panel(), List.of(root.resolve("src")), target);

        assertEquals(1, p.asked().size(), "a folder move is confirmed");
        assertTrue(
                p.asked().get(0).contains("src") && p.asked().get(0).contains("docs"),
                p.asked().get(0));
        assertTrue(Files.exists(pkg.resolve("Main.java")), "declined: the tree is where it was");
        assertFalse(Files.exists(target.resolve("src")));
        assertTrue(p.renames().isEmpty());
        assertFalse(FxTestSupport.callOnFx(p.panel()::canUndoMove));

        p.answer()[0] = true;
        move(p.panel(), List.of(root.resolve("src")), target);
        assertTrue(Files.exists(target.resolve("src/com/acme/Main.java")), "confirmed: the folder moved");
        assertEquals(List.of(new Rename(root.resolve("src"), target.resolve("src"))), p.renames());
    }

    @Test
    void aMultiSelectionAsksOnceAndASingleFileStillMovesWithoutAQuestion(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("a.txt"), "a");
        Files.writeString(root.resolve("b.txt"), "b");
        Files.writeString(root.resolve("c.txt"), "c");
        Path sub = Files.createDirectory(root.resolve("sub"));
        Panel p = panel(root);

        move(p.panel(), List.of(root.resolve("c.txt")), sub);
        assertTrue(p.asked().isEmpty(), "one file: no question, as before");
        assertTrue(Files.exists(sub.resolve("c.txt")));

        move(p.panel(), List.of(root.resolve("a.txt"), root.resolve("b.txt")), sub);
        assertEquals(1, p.asked().size(), "several items: one question for the whole move");
        assertEquals(tr("project.moveMultiBody", 2, "sub"), p.asked().get(0));
        assertTrue(Files.exists(sub.resolve("a.txt")) && Files.exists(sub.resolve("b.txt")));
    }

    /** The reverse of a drag-move had to be done by hand; "Undo Move" takes the last one back in one step. */
    @Test
    void undoMovePutsTheLastMoveBackAndTellsOpenBuffers(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("a.txt"), "a");
        Files.writeString(root.resolve("b.txt"), "b");
        Path sub = Files.createDirectory(root.resolve("sub"));
        Panel p = panel(root);
        move(p.panel(), List.of(root.resolve("a.txt"), root.resolve("b.txt")), sub);
        assertTrue(FxTestSupport.callOnFx(p.panel()::canUndoMove));
        p.renames().clear();

        FxTestSupport.runOnFx(p.panel()::undoLastMove);
        settle();

        assertEquals("a", Files.readString(root.resolve("a.txt")));
        assertEquals("b", Files.readString(root.resolve("b.txt")));
        assertFalse(Files.exists(sub.resolve("a.txt")) || Files.exists(sub.resolve("b.txt")));
        assertEquals(
                List.of(
                        new Rename(sub.resolve("b.txt"), root.resolve("b.txt")),
                        new Rename(sub.resolve("a.txt"), root.resolve("a.txt"))),
                p.renames(),
                "open buffers follow the files back");
        assertFalse(FxTestSupport.callOnFx(p.panel()::canUndoMove), "one step: nothing left to undo");
    }

    @Test
    void undoMoveNeverOverwritesSomethingThatTookTheOldPlace(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("a.txt"), "moved");
        Path sub = Files.createDirectory(root.resolve("sub"));
        Panel p = panel(root);
        move(p.panel(), List.of(root.resolve("a.txt")), sub);
        Files.writeString(root.resolve("a.txt"), "written since the move");

        FxTestSupport.runOnFx(p.panel()::undoLastMove);
        settle();

        assertEquals("written since the move", Files.readString(root.resolve("a.txt")));
        assertEquals("moved", Files.readString(sub.resolve("a.txt")), "the moved file stays where it is");
    }

    /**
     * A drag-move asks the rename participant first, as a tree rename does, so a moved {@code .java} file has
     * its package line and its users' imports edited by jdtls; Undo Move asks again on the way back; a move the
     * participant refuses is not made (Java review 3, BT1).
     */
    @Test
    void aDragMoveAndItsUndoGoThroughTheRenameParticipant(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("A.java"), "package x;");
        Files.writeString(root.resolve("B.java"), "package x;");
        Path sub = Files.createDirectory(root.resolve("sub"));
        Panel p = panel(root);
        List<Rename> asked = new ArrayList<>();
        FxTestSupport.runOnFx(() -> p.panel().setRenameParticipant((from, to, proceed) -> {
            asked.add(new Rename(from, to));
            proceed.accept(!from.getFileName().toString().equals("B.java")); // refuses B
        }));

        move(p.panel(), List.of(root.resolve("A.java"), root.resolve("B.java")), sub);

        assertEquals(
                List.of(
                        new Rename(root.resolve("A.java"), sub.resolve("A.java")),
                        new Rename(root.resolve("B.java"), sub.resolve("B.java"))),
                asked);
        assertTrue(Files.exists(sub.resolve("A.java")));
        assertTrue(Files.exists(root.resolve("B.java")), "the refused move is not made");

        asked.clear();
        FxTestSupport.runOnFx(p.panel()::undoLastMove);
        settle();
        assertEquals(
                List.of(new Rename(sub.resolve("A.java"), root.resolve("A.java"))), asked, "asked on the way back too");
        assertTrue(Files.exists(root.resolve("A.java")));
    }

    /**
     * The drag ends only after the participant's callback for its last move has returned: the participant records
     * what a move did when that callback returns, and a drag that ended first lost its last file's edit, so undoing
     * it left that file in the wrong package (fifth review BV1).
     */
    @Test
    void aDragEndsAfterTheLastMovesCallbackHasReturned(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("A.java"), "package x;");
        Files.writeString(root.resolve("B.java"), "package x;");
        Path sub = Files.createDirectory(root.resolve("sub"));
        Panel p = panel(root);
        List<String> events = new ArrayList<>();
        FxTestSupport.runOnFx(() -> p.panel().setRenameParticipant(new ProjectPanel.RenameParticipant() {
            @Override
            public void willRename(Path from, Path to, java.util.function.Consumer<Boolean> proceed) {
                proceed.accept(true);
                events.add("recorded " + from.getFileName());
            }

            @Override
            public void beginMove(int count) {
                events.add("begin");
            }

            @Override
            public void endMove(boolean undo) {
                events.add("end " + undo);
            }
        }));

        move(p.panel(), List.of(root.resolve("A.java"), root.resolve("B.java")), sub);

        assertEquals(List.of("begin", "recorded A.java", "recorded B.java", "end false"), events);
        events.clear();
        FxTestSupport.runOnFx(p.panel()::undoLastMove);
        settle();
        assertEquals(List.of("begin", "recorded B.java", "recorded A.java", "end true"), events);
    }
}
