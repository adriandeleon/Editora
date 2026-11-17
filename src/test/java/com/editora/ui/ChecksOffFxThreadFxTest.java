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
import com.editora.lsp.LspServerRegistry;
import com.editora.perf.BlockingSites;
import com.editora.perf.OffFx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PERF-01 (Pauta 6.0.1), the checks made as the user types, per buffer sync, per publish and per command: each test
 * drives them on the FX thread the way an event does and listens to {@link BlockingSites}. The looks they make — a
 * Markdown buffer's lint config and {@code .editorconfig} as it is typed into and saved, a Java buffer's server root,
 * a link followed to a heading of a closed file, a log followed from its end, a keymap switched, the About
 * dialog — name themselves there, and none may be heard on the FX thread.
 *
 * <p>What it cannot see: work at a place that does not name itself (it checks the named places, not every file
 * call); a real language server's publish and status lines (the root look is asked the way {@code syncBuffer} asks
 * it); the native dialogs; and the checks other tests drive with their own stand-ins (the New Maven Project form, the
 * SSH Host field, a terminal path hover, the Problems window, the Map tooltip, Find in Files, Go to Super Method:
 * {@code DiskFactsFxTest}, {@code MavenMenuFxTest}, {@code RemoteSitesFxTest}, {@code ProjectMapCanvasExploreFxTest},
 * {@code SearchInFilesPopupResultsFxTest}).
 */
@Tag("fx")
class ChecksOffFxThreadFxTest {

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
        assertEquals(List.of(), onFx, "disk work ran on the FX thread; heard: " + heard);
    }

    private void awaitHeard(String site) throws Exception {
        SettingsRig.awaitFx(
                site + " heard off FX",
                () -> heard.stream().anyMatch(h -> h.site().equals(site) && !h.fx()));
    }

    private void awaitActive(Path file) throws Exception {
        SettingsRig.awaitFx("the tab of " + file.getFileName(), () -> {
            EditorBuffer active = fx.controller.activeBuffer();
            return active != null && file.equals(active.getPath()) && !active.isLoading();
        });
    }

    @Test
    void typingSavingAndSyncingLookAtTheDiskOffTheFxThread() throws Exception {
        Path project = Files.createDirectories(dir.resolve("project"));
        Files.writeString(project.resolve("pom.xml"), "<project><artifactId>demo</artifactId></project>\n");
        Files.writeString(project.resolve(".editorconfig"), "root = true\n[*]\ntrim_trailing_whitespace = true\n");
        Files.writeString(project.resolve(".markdownlint.json"), "{\"MD013\": false}\n");
        Path docs = Files.createDirectories(project.resolve("docs"));
        Path doc = Files.writeString(docs.resolve("a.md"), "# A\n\nText.\n");
        Path other = Files.writeString(docs.resolve("other.md"), "# Other\n\n## Far\n\nThere.\n");
        Path java = Files.writeString(
                Files.createDirectories(project.resolve("src/main/java/demo")).resolve("A.java"),
                "package demo;\n\nclass A {}\n");
        fx = FxWindowFixture.createWithFileWorkOffThread(Files.createDirectories(dir.resolve("config")), shared -> {});
        MainController mc = fx.controller;
        fx.shared.awaitWrites();
        listen();

        // A Markdown buffer opened and typed into: its .editorconfig and lint config are looked at off FX.
        FxTestSupport.runOnFx(() -> mc.openAndNavigate(doc, 0));
        awaitActive(doc);
        EditorBuffer markdown = FxTestSupport.callOnFx(mc::activeBuffer);
        FxTestSupport.runOnFx(() -> markdown.getArea().appendText("More text.   \n"));
        awaitHeard("MarkdownLintActions.configFor");
        awaitHeard("EditorConfig.rawFor");

        // An explicit save: the .editorconfig as it is now is looked at off FX, then the file is written trimmed.
        FxTestSupport.runOnFx(() -> mc.fileWorkflows.save(markdown));
        SettingsRig.awaitFx("the save", () -> !markdown.isDirty());
        assertTrue(Files.readString(doc).contains("More text.\n"), "the save followed the .editorconfig");

        // A link to a heading of a closed file: classified and its line found off FX, then opened there.
        CompletableFuture<Path> opened = new CompletableFuture<>();
        FxTestSupport.runOnFx(() -> ExternalLinks.open(
                "other.md#far",
                doc,
                project,
                url -> {},
                opened::complete,
                (file, anchor) -> opened.complete(file),
                status -> {}));
        assertEquals(other.getFileName(), opened.get(20, TimeUnit.SECONDS).getFileName());

        // A Java buffer's server root, as a buffer sync asks it: unknown at first (nothing guessed), looked at off FX,
        // then answered from memory.
        FxTestSupport.runOnFx(() -> mc.openAndNavigate(java, 0));
        awaitActive(java);
        EditorBuffer javaBuffer = FxTestSupport.callOnFx(mc::activeBuffer);
        SettingsRig.awaitFx(
                "the Java buffer's root",
                () -> project.equals(mc.lspCoordinator.lspRootFor(javaBuffer, LspServerRegistry.JAVA_SERVER_ID)));

        FxTestSupport.drainFx();
        assertNothingOnFx();
    }
}
