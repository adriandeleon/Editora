package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Renaming or moving a file keeps the project's Markdown links pointing at it. */
@Tag("fx")
class MarkdownLinkUpdaterFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** A host with a settings object, some open buffers, and a record of what it was told. */
    private static final class Host extends CoordinatorHostStub {
        final Settings settings = new Settings();
        final List<EditorBuffer> buffers = new ArrayList<>();
        final List<String> status = new ArrayList<>();

        @Override
        public Settings settings() {
            return settings;
        }

        @Override
        public void forEachBuffer(Consumer<EditorBuffer> action) {
            buffers.forEach(action);
        }

        @Override
        public void setStatus(String message) {
            status.add(message);
        }
    }

    private static MarkdownLinkUpdater.Outcome rename(MarkdownLinkUpdater updater, Path from, Path to)
            throws Exception {
        Files.createDirectories(to.getParent());
        Files.move(from, to);
        FxTestSupport.runOnFx(() -> updater.onRenamed(from, to));
        MarkdownLinkUpdater.Outcome outcome =
                FxTestSupport.callOnFx(() -> updater.lastRun).get(20, TimeUnit.SECONDS);
        FxTestSupport.runOnFx(() -> {}); // the status line is written in the same FX turn as the completion
        return outcome;
    }

    private static EditorBuffer open(Host host, Path file) throws Exception {
        String text = Files.readString(file);
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setPath(file);
            buffer.setContent(text);
            buffer.getArea().getUndoManager().forgetHistory();
            host.buffers.add(buffer);
            return buffer;
        });
    }

    @Test
    void alwaysRewritesClosedFilesAndOpenBuffersAndLeavesTheRestAlone(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("project"));
        Path outside = Files.writeString(dir.resolve("outside.md"), "[a](project/old.md)\n");
        Path old = Files.writeString(root.resolve("old.md"), "# Old\n");
        Path closed = Files.writeString(
                root.resolve("closed.md"), "See [old](old.md#old) and [web](https://e.org/old.md).\r\n");
        Path nested = Files.createDirectories(root.resolve("docs")).resolve("nested.md");
        Files.writeString(nested, "![i](../old.md) `[c](../old.md)`\n");
        Path untouched = Files.writeString(root.resolve("untouched.md"), "[x](other.md)\n");
        Path note = Files.writeString(root.resolve("note.txt"), "[a](old.md)\n");
        Path openFile = Files.writeString(root.resolve("open.md"), "intro\n\n[o](old.md)\n\n[ref]: <old.md>\n");
        long untouchedStamp = Files.getLastModifiedTime(untouched).toMillis();

        Host host = new Host();
        host.settings.setMarkdownUpdateLinksOnRename("always");
        EditorBuffer buffer = open(host, openFile);
        FxTestSupport.runOnFx(() -> buffer.getArea().moveTo(3));
        MarkdownLinkUpdater updater = new MarkdownLinkUpdater(host, () -> root, () -> true);
        updater.confirm = plan -> {
            throw new AssertionError("always does not ask");
        };
        try {
            Path renamed = root.resolve("sub/new name.md");
            MarkdownLinkUpdater.Outcome outcome = rename(updater, old, renamed);

            assertEquals(4, outcome.links());
            assertEquals(3, outcome.files());
            assertEquals(List.of(), outcome.failed());
            assertEquals(
                    "See [old](sub/new%20name.md#old) and [web](https://e.org/old.md).\r\n", Files.readString(closed));
            assertEquals("![i](../sub/new%20name.md) `[c](../old.md)`\n", Files.readString(nested));
            assertEquals("[x](other.md)\n", Files.readString(untouched));
            assertEquals(untouchedStamp, Files.getLastModifiedTime(untouched).toMillis(), "not rewritten at all");
            assertEquals("[a](old.md)\n", Files.readString(note), "only Markdown files are read");
            assertEquals("[a](project/old.md)\n", Files.readString(outside), "nothing outside the project");

            // The open buffer was edited, not its file: one undo step, caret where it was.
            assertEquals("intro\n\n[o](old.md)\n\n[ref]: <old.md>\n", Files.readString(openFile));
            assertEquals(
                    "intro\n\n[o](sub/new%20name.md)\n\n[ref]: <sub/new name.md>\n",
                    FxTestSupport.callOnFx(buffer::getContent));
            assertEquals(3, (int) FxTestSupport.callOnFx(() -> buffer.getArea().getCaretPosition()));
            FxTestSupport.runOnFx(() -> buffer.getArea().undo());
            assertEquals("intro\n\n[o](old.md)\n\n[ref]: <old.md>\n", FxTestSupport.callOnFx(buffer::getContent));
            assertFalse(FxTestSupport.callOnFx(() -> buffer.getArea().isUndoAvailable()));
            assertTrue(host.status.stream().anyMatch(s -> s.contains("4") && s.contains("3")), host.status.toString());
        } finally {
            updater.dispose();
        }
    }

    @Test
    void askListsTheFilesAndDoesNothingWhenDeclined(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("project"));
        Path img = Files.createDirectories(root.resolve("img"));
        Files.writeString(img.resolve("a.png"), "x");
        Path readme = Files.writeString(root.resolve("README.md"), "![a](img/a.png) ![b](img/b.png)\n");
        Path guide = Files.createDirectories(root.resolve("docs")).resolve("guide.md");
        Files.writeString(guide, "![a](../img/a.png)\n");

        Host host = new Host();
        assertEquals("ask", host.settings.getMarkdownUpdateLinksOnRename(), "the default asks");
        MarkdownLinkUpdater updater = new MarkdownLinkUpdater(host, () -> root, () -> true);
        List<MarkdownLinkUpdater.Plan> asked = new ArrayList<>();
        boolean[] answer = {false};
        updater.confirm = plan -> {
            asked.add(plan);
            return answer[0];
        };
        try {
            Path media = root.resolve("media");
            MarkdownLinkUpdater.Outcome declined = rename(updater, img, media);
            assertEquals(0, declined.links());
            assertEquals(1, asked.size());
            assertEquals(3, asked.get(0).links());
            String body = MarkdownLinkUpdater.describe(asked.get(0));
            assertTrue(body.contains("README.md") && body.contains("docs/guide.md"), body);
            assertEquals("![a](img/a.png) ![b](img/b.png)\n", Files.readString(readme), "declined: untouched");

            // Moving it back and on again, this time accepted.
            Files.move(media, img);
            answer[0] = true;
            MarkdownLinkUpdater.Outcome accepted = rename(updater, img, media);
            assertEquals(3, accepted.links());
            assertEquals("![a](media/a.png) ![b](media/b.png)\n", Files.readString(readme));
            assertEquals("![a](../media/a.png)\n", Files.readString(guide));
        } finally {
            updater.dispose();
        }
    }

    @Test
    void aMovedDocumentHasItsOwnLinksRecomputed(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("project"));
        Files.writeString(root.resolve("peer.md"), "[d](doc.md)\n");
        Path doc = Files.writeString(root.resolve("doc.md"), "[p](peer.md) [top](#top)\n");

        Host host = new Host();
        host.settings.setMarkdownUpdateLinksOnRename("always");
        MarkdownLinkUpdater updater = new MarkdownLinkUpdater(host, () -> root, () -> true);
        try {
            Path moved = root.resolve("deep/er/doc.md");
            MarkdownLinkUpdater.Outcome outcome = rename(updater, doc, moved);
            assertEquals(2, outcome.links());
            assertEquals("[p](../../peer.md) [top](#top)\n", Files.readString(moved));
            assertEquals("[d](deep/er/doc.md)\n", Files.readString(root.resolve("peer.md")));
        } finally {
            updater.dispose();
        }
    }

    @Test
    void neverDoesNothingAndNeitherDoesARenameOutsideTheProject(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("project"));
        Path old = Files.writeString(root.resolve("old.md"), "");
        Path linking = Files.writeString(root.resolve("a.md"), "[o](old.md)\n");
        Path elsewhere = Files.writeString(dir.resolve("elsewhere.md"), "");

        Host host = new Host();
        host.settings.setMarkdownUpdateLinksOnRename("never");
        MarkdownLinkUpdater updater = new MarkdownLinkUpdater(host, () -> root, () -> true);
        updater.confirm = plan -> {
            throw new AssertionError("never does not ask");
        };
        try {
            assertEquals(0, rename(updater, old, root.resolve("new.md")).links());
            assertEquals("[o](old.md)\n", Files.readString(linking));
            host.settings.setMarkdownUpdateLinksOnRename("always");
            assertEquals(0, rename(updater, elsewhere, dir.resolve("moved.md")).links());
            assertEquals("[o](old.md)\n", Files.readString(linking));
        } finally {
            updater.dispose();
        }
    }

    @Test
    void theRenameHookOfTheWindowReachesTheUpdater(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("project"));
        Path old = Files.writeString(root.resolve("old.md"), "");
        Path linking = Files.writeString(root.resolve("a.md"), "[o](old.md)\n");
        Host host = new Host();
        host.settings.setMarkdownUpdateLinksOnRename("always");
        IndexCoordinator index = FxTestSupport.callOnFx(() -> new IndexCoordinator(host, new IndexCoordinator.Ops() {
            @Override
            public Path projectRoot() {
                return root;
            }

            @Override
            public void openAndGoto(Path file, int line, int column) {}

            @Override
            public boolean respectGitignore() {
                return true;
            }
        }));
        try {
            Path renamed = root.resolve("new.md");
            Files.move(old, renamed);
            FxTestSupport.runOnFx(() -> index.onFileRenamed(old, renamed)); // what MainController calls
            assertEquals(
                    1,
                    FxTestSupport.callOnFx(() -> index.linkUpdater.lastRun)
                            .get(20, TimeUnit.SECONDS)
                            .links());
            assertEquals("[o](new.md)\n", Files.readString(linking));
        } finally {
            FxTestSupport.runOnFx(index::dispose);
        }
    }
}
