package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;

import com.editora.editor.EditorBuffer;
import com.editora.install.InstallCatalog;
import com.editora.io.TestArchives;
import com.editora.perf.BlockingSites;
import com.editora.perf.OffFx;
import com.editora.process.ProcessRunner;
import com.editora.template.NewFileCatalog;
import com.editora.typst.TypstRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PERF-01 (Pauta 6.2.0), the file commands, installs and plugins: each test drives a representative set of them on the
 * FX thread, the way a command does, and listens to {@link BlockingSites}. The disk work they do — a file read as it
 * is opened, the admin-save check, Local History's copy before a delete, a pasted image written, New ▸ type, a clone's
 * follow-up look, a workspace edit and its Undo Refactoring, an installed-version check, the Project tree's New Folder,
 * Rename, Move, Undo Move and Delete, a Typst export put in place, the emoji table — names itself there, and none may
 * be heard on the FX thread; and a plugin installed from a local zip and removed.
 *
 * <p>What it cannot see: disk work at a place that does not name itself (it checks the named places, not every file
 * call); the first look at a key no off-thread look filled, which a {@code Looked} memory makes on the FX thread and
 * names (the test fills them as the app does, by opening the file first); the dialogs these commands show (a native
 * chooser checks its start folder itself); a large log's tail, which no command reads on the FX thread any more (only
 * a test path does); Settings sync's apply (kept on the FX thread on purpose); a real Typst, Git clone or plugin
 * registry (stand-ins); and the per-keystroke checks left for later.
 */
@Tag("fx")
class FilesOffFxThreadFxTest {

    /** Every {@link BlockingSites#enter} heard: the site, the thread's name, and whether it was the FX thread. */
    private record Heard(String site, String thread, boolean fx) {}

    private final List<Heard> heard = new CopyOnWriteArrayList<>();
    private FxWindowFixture fx;

    @TempDir
    Path dir;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void tearDown() throws Exception {
        BlockingSites.listenForTest(null);
        OffFx.runnerForTest(null);
        if (fx != null) {
            fx.dispose();
        }
    }

    private void listen() {
        heard.clear();
        BlockingSites.listenForTest(
                (site, thread) -> heard.add(new Heard(site, thread.getName(), Platform.isFxApplicationThread())));
    }

    /** Stops listening and checks that nothing was heard on the FX thread. */
    private void assertNothingOnFx() {
        BlockingSites.listenForTest(null);
        List<String> onFx = new ArrayList<>();
        for (Heard h : heard) {
            if (h.fx()) {
                onFx.add(h.site());
            }
        }
        assertEquals(List.of(), onFx, "file work ran on the FX thread; heard: " + heard);
    }

    private void assertHeard(String site) {
        assertTrue(heard.stream().anyMatch(h -> h.site().equals(site) && !h.fx()), site + " heard off FX: " + heard);
    }

    private void awaitActive(Path file) throws Exception {
        SettingsRig.awaitFx("the tab of " + file.getFileName(), () -> {
            EditorBuffer active = fx.controller.activeBuffer();
            return active != null && file.equals(active.getPath()) && !active.isLoading();
        });
    }

    private static <T> T await(CompletableFuture<T> answer) throws Exception {
        return answer.get(20, TimeUnit.SECONDS);
    }

    @Test
    void theWindowsFileCommandsTouchNoDiskOnTheFxThread() throws Exception {
        Path project = Files.createDirectories(dir.resolve("project"));
        Path notes = Files.writeString(project.resolve("notes.txt"), "one\n");
        Path doc = Files.writeString(project.resolve("doc.md"), "# Doc\n");
        Path doomed = Files.writeString(project.resolve("doomed.txt"), "keep a copy of me\n");
        fx = FxWindowFixture.createWithFileWorkOffThread(Files.createDirectories(dir.resolve("config")), shared -> {
            shared.getSettings().setLocalHistory(true);
            shared.getSettings().setAdminSave(true);
        });
        MainController mc = fx.controller;
        fx.shared.awaitWrites();
        listen();

        // Open a file: read, and the facts its tab asks for looked at, off the FX thread.
        FxTestSupport.runOnFx(() -> mc.openAndNavigate(notes, 0));
        awaitActive(notes);
        assertHeard("FileWorkflowCoordinator.prepareLoad");

        // Save with the admin check on: the check answers from what the load looked at.
        EditorBuffer buffer = FxTestSupport.callOnFx(mc::activeBuffer);
        FxTestSupport.runOnFx(() -> {
            mc.fileWorkflows.adminToolAvailable = true;
            buffer.getArea().appendText("two\n");
            mc.fileWorkflows.save(buffer);
        });
        SettingsRig.awaitFx("the save", () -> !buffer.isDirty());
        assertEquals("one\ntwo\n", Files.readString(notes));

        // Local File History's copy before a delete: read off the FX thread, recorded from it.
        CompletableFuture<Boolean> captured = new CompletableFuture<>();
        FxTestSupport.runOnFx(() -> mc.historyCoordinator.captureBeforeDeleteDurably(
                doomed, capture -> captured.complete(capture.durable())));
        assertTrue(await(captured), "the copy was made");
        assertHeard("HistoryCoordinator.capture");

        // Paste an image into Markdown: encoded and written off the FX thread, linked where the caret was.
        FxTestSupport.runOnFx(() -> mc.openAndNavigate(doc, 0));
        awaitActive(doc);
        EditorBuffer markdown = FxTestSupport.callOnFx(mc::activeBuffer);
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                mc.editing,
                "savePastedImage",
                new Class<?>[] {EditorBuffer.class, javafx.scene.image.Image.class, String.class},
                markdown,
                new javafx.scene.image.WritableImage(4, 3),
                "dot"));
        SettingsRig.awaitFx("the image link", () -> markdown.getContent().contains("![dot]("));
        assertHeard("EditingCoordinator.writePng");

        // New ▸ type: the name checked and the file written off the FX thread, then opened.
        Path made = project.resolve("hello.txt");
        FxTestSupport.runOnFx(() -> mc.templateActions.createFileOfType(project, NewFileCatalog.byId("text"), "hello"));
        SettingsRig.awaitFx("the new file's tab", () -> {
            EditorBuffer active = mc.activeBuffer();
            return active != null && made.equals(active.getPath());
        });

        // A clone's follow-up: which file it opens at is looked for off the FX thread.
        Path cloned = Files.createDirectories(dir.resolve("cloned"));
        Path readme = Files.writeString(cloned.resolve("README.md"), "# Cloned\n");
        FxTestSupport.runOnFx(() -> FxTestSupport.call(mc.git, "openClonedEntry", new Class<?>[] {Path.class}, cloned));
        awaitActive(readme);
        assertHeard("clone-entry");

        // The emoji table, as the launch reads it: off the FX thread, so a completion on it finds it read.
        Thread warm = Thread.ofVirtual().start(com.editora.markdown.MarkdownEmoji::warm);
        warm.join(10_000);
        FxTestSupport.runOnFx(() -> assertTrue(com.editora.markdown.MarkdownEmoji.lookup("rocket") != null));

        FxTestSupport.drainFx();
        assertNothingOnFx();
    }

    @Test
    void aWorkspaceEditAndItsUndoLookAtTheDiskOffTheFxThread() throws Exception {
        Path project = Files.createDirectories(dir.resolve("project"));
        Path a = Files.writeString(project.resolve("A.txt"), "alpha\n");
        Path b = project.resolve("B.txt");
        Path c = Files.writeString(project.resolve("C.txt"), "uses alpha\n");
        fx = FxWindowFixture.createWithFileWorkOffThread(Files.createDirectories(dir.resolve("config")), shared -> {});
        LspCoordinator lsp = fx.controller.lspCoordinator;
        fx.shared.awaitWrites();
        listen();

        // A server's refactoring, as it asks: a closed file edited, another renamed. The disk is looked at, the
        // rename staged and put in place off the FX thread; the edited file opens in a background tab.
        var textEdit = new org.eclipse.lsp4j.TextDocumentEdit(
                new org.eclipse.lsp4j.VersionedTextDocumentIdentifier(c.toUri().toString(), null),
                List.of(org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(new org.eclipse.lsp4j.TextEdit(
                        new org.eclipse.lsp4j.Range(
                                new org.eclipse.lsp4j.Position(0, 5), new org.eclipse.lsp4j.Position(0, 10)),
                        "beta"))));
        var rename =
                new org.eclipse.lsp4j.RenameFile(a.toUri().toString(), b.toUri().toString());
        var mapped = com.editora.lsp.WorkspaceEditMapper.map(new org.eclipse.lsp4j.WorkspaceEdit(List.of(
                org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(textEdit),
                org.eclipse.lsp4j.jsonrpc.messages.Either.forRight(rename))));
        assertTrue(mapped != null);
        CompletableFuture<Boolean> applied = new CompletableFuture<>();
        FxTestSupport.runOnFx(() -> lsp.applyWorkspaceEditsConfirmed(mapped, applied::complete));
        assertTrue(await(applied), "the refactoring went through");
        SettingsRig.awaitFx("the file moved", () -> Files.exists(b) && !Files.exists(a));
        assertHeard("WorkspaceFileOperations.stat");
        SettingsRig.awaitFx("the refactoring recorded", () -> lsp.lastAppliedEdit() != null);

        // Undo Refactoring: the closed files it would change are read off the FX thread, then it is undone.
        FxTestSupport.runOnFx(lsp::undoRefactoring);
        SettingsRig.awaitFx("the rename undone", () -> Files.exists(a) && !Files.exists(b));
        assertHeard("LspRefactoringUndo.closedFilesIntact");
        FxTestSupport.drainFx();
        assertNothingOnFx();
    }

    @Test
    void anInstalledVersionCheckReadsReceiptsOffTheFxThread() throws Exception {
        Path config = Files.createDirectories(dir.resolve("config"));
        InstallCoordinator.Ops everything = new InstallCoordinator.Ops() {
            @Override
            public Path configDir() {
                return config;
            }

            @Override
            public boolean lspAvailable(String serverId) {
                return true;
            }

            @Override
            public boolean dapAvailable(String language) {
                return true;
            }

            @Override
            public boolean mmdcAvailable() {
                return true;
            }

            @Override
            public boolean typstCliAvailable() {
                return true;
            }

            @Override
            public void reapplyToolSupport() {}
        };
        InstallCoordinator install =
                FxTestSupport.callOnFx(() -> new InstallCoordinator(new CoordinatorHostStub(), everything));
        listen();
        CompletableFuture<Boolean> outcome = new CompletableFuture<>();
        FxTestSupport.runOnFx(() -> install.installSupport(InstallCatalog.Lang.JAVA, outcome::complete));
        assertTrue(await(outcome), "everything is installed already");
        assertHeard("PinnedVersions.installedFreshness");
        // A second press while the first one looks is refused, not run twice.
        CompletableFuture<Boolean> first = new CompletableFuture<>();
        CompletableFuture<Boolean> second = new CompletableFuture<>();
        FxTestSupport.runOnFx(() -> {
            install.installSupport(InstallCatalog.Lang.JAVA, first::complete);
            install.installSupport(InstallCatalog.Lang.JAVA, second::complete);
        });
        assertFalse(await(second), "busy: the second press starts nothing");
        assertTrue(await(first));
        FxTestSupport.runOnFx(install::shutdown);
        FxTestSupport.drainFx();
        assertNothingOnFx();
    }

    @Test
    void theProjectTreesFileOperationsTouchNoDiskOnTheFxThread() throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            OffFx.runnerForTest(null); // the rig runs it in place; here it runs where the app runs it
            r.panel.watchWork = FxTestSupport.field(r.panel, "watchPool");
            FxTestSupport.runOnFx(() -> r.panel.deleteWork = com.editora.process.BlockingTasks.named("project-delete"));
            r.open.add(r.alpha); // an open tab
            listen();

            // New Folder.
            FxTestSupport.runOnFx(() -> ProjectPanelRig.entry(
                            r.menu(r.item(r.root), true, true).getItems(), "project.menu.newFolder")
                    .fire());
            FxTestSupport.runOnFx(() -> r.promptAccept.accept("made"));
            SettingsRig.awaitFx("the new folder", () -> Files.isDirectory(r.root.resolve("made")));

            // Rename, with the open tab following.
            FxTestSupport.runOnFx(() -> ProjectPanelRig.entry(
                            r.menu(r.item(r.alpha), false, false).getItems(), "project.menu.rename")
                    .fire());
            Path renamed = r.root.resolve("gamma.txt");
            FxTestSupport.runOnFx(() -> r.promptAccept.accept("gamma.txt"));
            SettingsRig.awaitFx("the rename", () -> Files.exists(renamed) && !r.renamed.isEmpty());
            assertEquals(List.of(r.alpha, renamed), r.renamed.get(0), "the tab follows, once the file has moved");

            // Move, then Undo Move.
            Path into = r.root.resolve("made");
            FxTestSupport.runOnFx(() -> FxTestSupport.call(
                    r.panel, "moveInto", new Class<?>[] {List.class, Path.class}, List.of(r.beta), into));
            SettingsRig.awaitFx("the move", () -> Files.exists(into.resolve("beta.txt")));
            SettingsRig.awaitFx("the move recorded", () -> r.panel.canUndoMove());
            FxTestSupport.runOnFx(r.panel::undoLastMove);
            SettingsRig.awaitFx(
                    "the move undone", () -> Files.exists(r.beta) && !Files.exists(into.resolve("beta.txt")));

            // Delete (no question asked in the rig: the confirmed delete).
            CompletableFuture<ProjectPanel.DeleteResult> deleted =
                    FxTestSupport.callOnFx(() -> r.panel.deleteConfirmed(List.of(renamed)));
            assertTrue(await(deleted).prepared());
            assertFalse(Files.exists(renamed));
            FxTestSupport.drainFx();
            assertNothingOnFx();
        }
    }

    @Test
    void aTypstExportIsPutInPlaceOffTheFxThread() throws Exception {
        Path dest = dir.resolve("report.png");
        Path staging = Files.createTempDirectory(dir, ".editora-export-");
        Files.writeString(staging.resolve("report-1.png"), "one");
        Files.writeString(staging.resolve("report-2.png"), "two");
        TypstRenderer.PendingExport pending = TypstRenderer.pending(new ProcessRunner.Result(0, "", ""), staging, dest);
        pending.lookAtTargets(); // as the service does before it hands the result over
        TypstCoordinator typst = new TypstCoordinator(new CoordinatorHostStub(), p -> p);
        listen();
        FxTestSupport.runOnFx(() -> typst.finishExport(pending));
        SettingsRig.awaitFx(
                "the pages in place",
                () -> Files.exists(dir.resolve("report-1.png")) && Files.exists(dir.resolve("report-2.png")));
        assertHeard("TypstRenderer.commit");
        FxTestSupport.drainFx();
        assertNothingOnFx();
    }

    @Test
    void aPluginInstallAndItsRemovalTouchNoDiskOnTheFxThread() throws Exception {
        fx = FxWindowFixture.createWithFileWorkOffThread(
                Files.createDirectories(dir.resolve("config")),
                shared -> shared.getSettings().setPluginSupport(true));
        PluginCoordinator plugins = fx.controller.pluginCoordinator;
        Path zip = Files.write(
                dir.resolve("greeter.zip"),
                TestArchives.zip(
                        "plugin.json",
                        "{\"id\":\"greeter\",\"name\":\"Greeter\",\"version\":\"1.0\"}",
                        "snippets/markdown.json",
                        "{}"));
        Path installed = fx.configDir.resolve("plugins").resolve("greeter");
        FxTestSupport.runOnFx(() -> {
            plugins.setConfirmForTest(question -> true); // Enable, then Remove
            plugins.setZipChooserForTest(zip::toFile);
        });
        fx.shared.awaitWrites();
        listen();

        // Install from a local zip: staged, consented to, put in place and listed again, all off the FX thread.
        FxTestSupport.runOnFx(plugins::installFromDisk);
        SettingsRig.awaitFx(
                "the plugin installed and listed",
                () -> Files.isRegularFile(installed.resolve("plugin.json"))
                        && fx.windowManager.pluginManager().descriptors().stream()
                                .anyMatch(d -> d.id().equals("greeter")));
        assertHeard("PluginInstaller.commit");
        assertHeard("PluginManager.discover");

        // Remove it: not running in this session, so its folder is deleted, off the FX thread.
        FxTestSupport.runOnFx(() -> plugins.uninstall("greeter"));
        SettingsRig.awaitFx("the plugin removed", () -> !Files.exists(installed));
        assertHeard("PluginInstaller.deleteInstalled");
        FxTestSupport.drainFx();
        assertNothingOnFx();
    }
}
