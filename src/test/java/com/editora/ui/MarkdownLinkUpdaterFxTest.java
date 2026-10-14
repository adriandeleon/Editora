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

    private final List<MarkdownLinkGraph> graphs = new ArrayList<>();

    /** What Local History was asked to keep, as "file name: text". */
    private final List<String> kept = new java.util.concurrent.CopyOnWriteArrayList<>();

    private boolean historyWorks = true;

    @org.junit.jupiter.api.AfterEach
    void disposeGraphs() {
        graphs.forEach(MarkdownLinkGraph::dispose);
    }

    private MarkdownLinkGraph graph(Host host, Path root) {
        MarkdownLinkGraph graph = new MarkdownLinkGraph(host, () -> root, () -> true);
        graphs.add(graph);
        return graph;
    }

    private MarkdownLinkUpdater updater(Host host, Path root) {
        return new MarkdownLinkUpdater(host, graph(host, root), (file, text, done) -> {
            kept.add(file.getFileName() + ": " + text);
            done.accept(historyWorks);
        });
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
        MarkdownLinkUpdater updater = updater(host, root);
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
        MarkdownLinkUpdater updater = updater(host, root);
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
        MarkdownLinkUpdater updater = updater(host, root);
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
        MarkdownLinkUpdater updater = updater(host, root);
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

    @Test
    void aClosedFilesTextGoesToLocalHistoryFirstAndAFileItCannotKeepIsLeftAlone(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("project"));
        Path old = Files.writeString(root.resolve("old.md"), "");
        Path one = Files.writeString(root.resolve("one.md"), "[o](old.md)\n");
        Host host = new Host();
        host.settings.setMarkdownUpdateLinksOnRename("always");
        MarkdownLinkUpdater updater = updater(host, root);

        Path mid = root.resolve("mid.md");
        assertEquals(1, rename(updater, old, mid).links());
        assertEquals(List.of("one.md: [o](old.md)\n"), kept, "the text as it was, before the rewrite");
        assertEquals("[o](mid.md)\n", Files.readString(one));

        historyWorks = false;
        MarkdownLinkUpdater.Outcome refused = rename(updater, mid, root.resolve("new.md"));
        assertEquals(0, refused.links());
        assertEquals(List.of(one), refused.failed(), "no safety copy, no rewrite");
        assertEquals("[o](mid.md)\n", Files.readString(one));
    }

    @Test
    void aFileOpenInAnotherWindowIsEditedInThatWindowsBuffer(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("project"));
        Path old = Files.writeString(root.resolve("old.md"), "");
        Path linking = Files.writeString(root.resolve("a.md"), "[o](old.md)\n");
        Host here = new Host();
        here.settings.setMarkdownUpdateLinksOnRename("always");
        Host elsewhere = new Host(); // another window on the same project, holding the linking file
        EditorBuffer buffer = open(elsewhere, linking);
        graph(elsewhere, root);
        MarkdownLinkUpdater updater = updater(here, root);

        MarkdownLinkUpdater.Outcome outcome = rename(updater, old, root.resolve("new.md"));
        assertEquals(1, outcome.links());
        assertEquals("[o](new.md)\n", FxTestSupport.callOnFx(buffer::getContent), "edited where it is open");
        assertEquals("[o](old.md)\n", Files.readString(linking), "and not behind that window's back on disk");
        assertEquals(List.of(), kept, "an open buffer has undo; Local History is for closed files");
        FxTestSupport.runOnFx(() -> buffer.getArea().undo());
        assertEquals("[o](old.md)\n", FxTestSupport.callOnFx(buffer::getContent), "one undo step there");
    }

    @Test
    void theIndexIsWalkedOnceAndThenFollowsRenamesSavesAndOutsideChanges(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("project"));
        Path a = Files.writeString(root.resolve("a.md"), "");
        Path linking = Files.writeString(root.resolve("links.md"), "[a](a.md) <img src=\"a.md\"> [[a]]\n");
        Host host = new Host();
        host.settings.setMarkdownUpdateLinksOnRename("always");
        MarkdownLinkGraph graph = graph(host, root);
        MarkdownLinkUpdater updater = new MarkdownLinkUpdater(host, graph, null);

        Path b = root.resolve("b.md");
        assertEquals(3, rename(updater, a, b).links(), "a path, an HTML src and a wiki link");
        assertEquals("[a](b.md) <img src=\"b.md\"> [[b]]\n", Files.readString(linking));
        assertEquals(1, graph.builds);

        // A second rename is answered from the index: no second walk, and the rewritten text is what it knows.
        Path c = root.resolve("sub/c.md");
        assertEquals(3, rename(updater, b, c).links());
        assertEquals("[a](sub/c.md) <img src=\"sub/c.md\"> [[c]]\n", Files.readString(linking));
        assertEquals(1, graph.builds);

        // A file another program wrote is read when the watcher names it; a saved buffer needs no read at all.
        Path fresh = Files.writeString(root.resolve("fresh.md"), "[[c]]\n");
        FxTestSupport.runOnFx(() ->
                graph.onExternalChanges(List.of(new ProjectPanel.FsChange(fresh, ProjectPanel.FsKind.CREATED)), true));
        EditorBuffer saved = open(host, Files.writeString(root.resolve("saved.md"), "nothing yet\n"));
        FxTestSupport.runOnFx(() -> {
            saved.setContent("[c](sub/c.md)\n");
            graph.onSaved(saved);
            host.buffers.remove(saved); // closed again: its file is what counts now
        });
        Files.writeString(root.resolve("saved.md"), "[c](sub/c.md)\n");
        Path d = root.resolve("sub/d.md");
        MarkdownLinkUpdater.Outcome third = rename(updater, c, d);
        assertEquals(5, third.links());
        assertEquals(3, third.files());
        assertEquals("[[d]]\n", Files.readString(fresh));
        assertEquals("[c](sub/d.md)\n", Files.readString(root.resolve("saved.md")));
        assertEquals(1, graph.builds, "kept current, never walked again");

        // "never" rewrites nothing, but the index still learns where the file went.
        host.settings.setMarkdownUpdateLinksOnRename("never");
        Path e = root.resolve("e.md");
        Files.move(d, e);
        FxTestSupport.runOnFx(() -> updater.onRenamed(d, e));
        List<Path>[] known = new List[1];
        FxTestSupport.callOnFx(() -> graph.submit(ix -> known[0] = List.copyOf(ix.documents())))
                .get(20, TimeUnit.SECONDS);
        assertTrue(known[0].contains(e) && !known[0].contains(d), known[0].toString());
        assertEquals(1, graph.builds);
    }

    @Test
    void wikiLinksAreLeftAloneWhenTheSettingIsOff(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("project"));
        Path a = Files.writeString(root.resolve("a.md"), "");
        Path linking = Files.writeString(root.resolve("links.md"), "[a](a.md) [[a]]\n");
        Host host = new Host();
        host.settings.setMarkdownUpdateLinksOnRename("always");
        host.settings.setMarkdownWikiLinks(false);
        MarkdownLinkUpdater updater = updater(host, root);
        assertEquals(1, rename(updater, a, root.resolve("b.md")).links());
        assertEquals("[a](b.md) [[a]]\n", Files.readString(linking));
    }

    @Test
    void theRealPromptListsTheFilesAndItsAnswerDecides(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("project"));
        Path a = Files.writeString(root.resolve("a.md"), "");
        Path linking = Files.writeString(root.resolve("links.md"), "[a](a.md) [again](a.md)\n");
        Host host = new Host();
        MarkdownLinkUpdater updater = updater(host, root); // "ask" is the default, and nothing replaces the dialog

        Path b = root.resolve("b.md");
        javafx.scene.control.DialogPane declined =
                renameAnswering(updater, a, b, javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
        assertTrue(
                declined.getHeaderText().contains("2")
                        && declined.getHeaderText().contains("b.md"),
                declined.getHeaderText());
        assertTrue(declined.getContentText().contains("links.md"), declined.getContentText());
        assertEquals(2, declined.getButtonTypes().size());
        assertEquals("[a](a.md) [again](a.md)\n", Files.readString(linking), "declined: untouched");

        Files.move(b, a);
        javafx.scene.control.DialogPane accepted =
                renameAnswering(updater, a, b, javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        assertTrue(accepted.getContentText().contains("links.md"));
        assertEquals("[a](b.md) [again](b.md)\n", Files.readString(linking));
    }

    /** Renames, and presses the button of kind {@code answer} on the dialog that then appears. */
    private static javafx.scene.control.DialogPane renameAnswering(
            MarkdownLinkUpdater updater, Path from, Path to, javafx.scene.control.ButtonBar.ButtonData answer)
            throws Exception {
        java.util.concurrent.atomic.AtomicReference<javafx.scene.control.DialogPane> seen =
                new java.util.concurrent.atomic.AtomicReference<>();
        javafx.animation.AnimationTimer timer = new javafx.animation.AnimationTimer() {
            @Override
            public void handle(long now) {
                for (javafx.stage.Window window : List.copyOf(javafx.stage.Window.getWindows())) {
                    if (window.isShowing()
                            && window.getScene() != null
                            && window.getScene().getRoot() instanceof javafx.scene.control.DialogPane pane) {
                        stop();
                        seen.set(pane);
                        ((javafx.scene.control.Button) pane.lookupButton(pane.getButtonTypes().stream()
                                        .filter(type -> type.getButtonData() == answer)
                                        .findFirst()
                                        .orElseThrow()))
                                .fire();
                        return;
                    }
                }
            }
        };
        FxTestSupport.runOnFx(timer::start);
        try {
            rename(updater, from, to);
        } finally {
            FxTestSupport.runOnFx(timer::stop);
        }
        return seen.get();
    }
}
