package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Markdown: Show Backlinks, in a real window with a real project. */
@Tag("fx")
class MarkdownBacklinksFxTest {

    @TempDir
    Path work;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static List<String> shown(MarkdownBacklinks backlinks) throws Exception {
        FxTestSupport.callOnFx(() -> backlinks.lastRefresh).get(20, TimeUnit.SECONDS);
        return FxTestSupport.callOnFx(() -> backlinks.panel().list().getItems().stream()
                .map(r -> r.isFile() ? r.label() + " (" + r.count() + ")" : (r.line() + 1) + ": " + r.label())
                .toList());
    }

    private static EditorBuffer open(AsyncTestScope async, MainController window, Path file) throws Exception {
        FileWorkflowCoordinator workflows = FxTestSupport.field(window, "fileWorkflows");
        FxTestSupport.runOnFx(() -> workflows.openPath(file));
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (true) {
            EditorBuffer b = FxTestSupport.callOnFx(() -> {
                EditorBuffer active = (EditorBuffer) FxTestSupport.call(window, "activeBuffer", new Class<?>[] {});
                return active != null && file.equals(active.getPath()) && !active.isLoading() ? active : null;
            });
            if (b != null) {
                async.awaitFx();
                return b;
            }
            assertTrue(System.nanoTime() < deadline, "never opened: " + file);
            Thread.sleep(5);
        }
    }

    @Test
    void theWindowListsWhatLinksToTheActiveFileAndFollowsTheTabAndTheIndex() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx =
                    async.own(FxWindowFixture.create(Files.createTempDirectory(work, "config"), shared -> {}));
            Path root = Files.createDirectories(work.resolve("project")).toRealPath();
            Path target = Files.writeString(root.resolve("target.md"), "# Target\n");
            Path a = Files.writeString(
                    root.resolve("a.md"), "intro\n\nSee [the target](target.md#target).\n\n[[target]] again\n");
            Files.writeString(
                    Files.createDirectories(root.resolve("docs")).resolve("b.md"),
                    "<a href=\"../target.md\">t</a>\n\n`[code](../target.md)`\n");
            Files.writeString(root.resolve("unrelated.md"), "[a](a.md)\n");
            MainController window = WindowMcpBridgeFxTest.openProject(async, fx, root);
            CommandRegistry registry = FxTestSupport.field(window, "registry");
            IndexCoordinator index = FxTestSupport.field(window, "indexCoordinator");
            MarkdownBacklinks backlinks = index.backlinks;
            ToolWindowManager toolWindows = FxTestSupport.field(window, "toolWindows");

            open(async, window, target);
            assertFalse(
                    FxTestSupport.callOnFx(() -> toolWindows.isOpen(backlinks.toolWindow(toolWindows))),
                    "closed until asked for");
            assertEquals(0, index.linkGraph.builds, "and a closed window walks nothing");

            FxTestSupport.runOnFx(() -> registry.run("markdown.showBacklinks"));
            assertEquals(
                    List.of(
                            "a.md (2)",
                            "3: See [the target](target.md#target).",
                            "5: [[target]] again",
                            "docs/b.md (1)",
                            "1: <a href=\"../target.md\">t</a>"),
                    shown(backlinks));
            assertTrue(FxTestSupport.callOnFx(() -> toolWindows.isOpen(backlinks.toolWindow(toolWindows))));
            assertEquals(
                    tr("backlinks.title", "target.md", 3),
                    FxTestSupport.callOnFx(() -> backlinks.panel().titleText()));

            // Activating a link row goes to its line in its file.
            FxTestSupport.runOnFx(() -> {
                backlinks.panel().list().getSelectionModel().select(2);
                backlinks.panel().activate();
            });
            EditorBuffer opened = open(async, window, a);
            assertEquals(4, (int) FxTestSupport.callOnFx(() -> opened.getArea().getCurrentParagraph()));

            // The list followed the tab: a.md is linked from unrelated.md only.
            assertEquals(List.of("unrelated.md (1)", "1: [a](a.md)"), shown(backlinks));

            // And it follows the index: a saved edit that drops the link empties it, with no second walk.
            Files.writeString(root.resolve("unrelated.md"), "nothing now\n");
            FxTestSupport.runOnFx(() -> index.onExternalChanges(
                    List.of(new ProjectPanel.FsChange(root.resolve("unrelated.md"), ProjectPanel.FsKind.CHANGED)),
                    true));
            long deadline = System.nanoTime() + 20_000_000_000L;
            while (!shown(backlinks).isEmpty()) {
                assertTrue(System.nanoTime() < deadline, "the list never followed the index");
                Thread.sleep(10);
            }
            assertEquals(1, index.linkGraph.builds);

            FxTestSupport.runOnFx(() -> registry.run("tool.backlinks"));
            assertFalse(FxTestSupport.callOnFx(() -> toolWindows.isOpen(backlinks.toolWindow(toolWindows))));
        }
    }
}
