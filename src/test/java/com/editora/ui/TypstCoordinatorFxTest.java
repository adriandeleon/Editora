package com.editora.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;

import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.process.ProcessRunner;
import com.editora.typst.TypstRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TypstCoordinator} with a stand-in {@code typst} (a shell script — never the real tool): the export
 * the preview menu runs, the pages the print path asks for, and what a finished export tells the user —
 * including the question before page files nobody named are replaced.
 */
@Tag("fx")
@DisabledOnOs(OS.WINDOWS)
class TypstCoordinatorFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @TempDir
    Path dir;

    private static final class Host extends CoordinatorHostStub {
        final Settings settings = new Settings();
        final List<String> statuses = new ArrayList<>();
        EditorBuffer active;

        @Override
        public Settings settings() {
            return settings;
        }

        @Override
        public EditorBuffer activeBuffer() {
            return active;
        }

        @Override
        public void setStatus(String message) {
            statuses.add(message);
        }
    }

    private final Host host = new Host();
    private final List<Path> rootsAsked = new ArrayList<>();
    private TypstCoordinator typst;

    @BeforeEach
    void create() throws Exception {
        FxTestSupport.runOnFx(() -> typst = new TypstCoordinator(host, file -> {
            rootsAsked.add(file);
            return file.getParent();
        }));
    }

    @AfterEach
    void shutdown() throws Exception {
        FxTestSupport.runOnFx(typst::shutdown);
    }

    /** Points the coordinator at a stand-in whose body sees {@code $input} and {@code $output}. */
    private void tool(String body) throws IOException {
        Path script = dir.resolve("fake-typst-" + System.nanoTime() + ".sh");
        Files.writeString(script, "#!/bin/sh\nfor a; do input=\"$output\"; output=\"$a\"; done\n" + body + "\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        typst.service().setPath(script.toString());
    }

    private static final String WRITES_OUTPUT = "case \"$output\" in\n"
            + "  *'{p}'*) for n in 1 2; do printf 'page %s' \"$n\" > \"$(printf '%s' \"$output\" | sed \"s/{p}/$n/\")\"; done ;;\n"
            + "  *) cp \"$input\" \"$output\" ;;\nesac";

    private ProcessRunner.Result export(Path file, Path dest) throws Exception {
        AtomicReference<ProcessRunner.Result> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        FxTestSupport.runOnFx(() -> typst.exportToPath("= Document", file, dest, r -> {
            result.set(r);
            done.countDown();
        }));
        assertTrue(done.await(60, TimeUnit.SECONDS), "the export reported back");
        return result.get();
    }

    private List<byte[]> pages(Path file) throws Exception {
        AtomicReference<List<byte[]>> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        FxTestSupport.runOnFx(() -> typst.renderPages("= Document", file, r -> {
            result.set(r);
            done.countDown();
        }));
        assertTrue(done.await(60, TimeUnit.SECONDS), "the render reported back");
        return result.get();
    }

    private TypstRenderer.PendingExport staged(Path dest, String... pageFiles) throws IOException {
        Path staging = Files.createTempDirectory(dir, ".editora-export-");
        for (String name : pageFiles) {
            Files.writeString(staging.resolve(name), "new " + name);
        }
        return TypstRenderer.pending(new ProcessRunner.Result(0, "", ""), staging, dest);
    }

    // --- the non-interactive export and the print pages -------------------------------------------------

    @Test
    void thePreviewsExportWritesThePdfUsingTheDocumentsFolderAndProjectRoot() throws Exception {
        tool(WRITES_OUTPUT);
        Path doc = Files.createDirectories(dir.resolve("proj")).resolve("paper.typ");
        Path dest = dir.resolve("paper.pdf");

        ProcessRunner.Result result = export(doc, dest);
        assertTrue(result.ok(), result.message());
        assertEquals("= Document", Files.readString(dest));
        assertEquals(List.of(doc), rootsAsked, "the window is asked for the document's project root");
    }

    @Test
    void anUntitledDocumentIsExportedWithoutAskingForARoot() throws Exception {
        tool(WRITES_OUTPUT);
        Path dest = dir.resolve("untitled.pdf");
        assertTrue(export(null, dest).ok());
        assertEquals("= Document", Files.readString(dest));
        assertEquals(List.of(), rootsAsked);
    }

    @Test
    void aFailedExportReportsTheToolsDiagnosticsAndWritesNothing() throws Exception {
        tool("echo 'error: unexpected end of file' >&2\nexit 1");
        Path dest = dir.resolve("paper.pdf");
        ProcessRunner.Result result = export(null, dest);
        assertFalse(result.ok());
        assertTrue(result.message().contains("unexpected end of file"), result.message());
        assertFalse(Files.exists(dest));
    }

    @Test
    void anExportThatProducedNoFileIsAFailureEvenWhenTheToolExitedCleanly() throws Exception {
        tool("exit 0");
        ProcessRunner.Result result = export(null, dir.resolve("paper.pdf"));
        assertFalse(result.ok(), "exit 0 with no file must not be reported as an export");
        assertFalse(result.message().isBlank());
    }

    @Test
    void thePrintPathGetsThePagesInOrderOrNoneWhenTheRenderFails() throws Exception {
        tool("for n in 2 1; do printf 'page %s' \"$n\" > \"$(printf '%s' \"$output\" | sed \"s/{p}/$n/\")\"; done");
        assertEquals(
                List.of("page 1", "page 2"),
                pages(null).stream()
                        .map(p -> new String(p, StandardCharsets.UTF_8))
                        .toList());

        tool("exit 1");
        assertEquals(List.of(), pages(null));
    }

    @Test
    void theToolCountsAsAvailableOnlyOnceItHasBeenDetected() throws Exception {
        tool("exit 0");
        assertFalse(typst.isTypstCliAvailable(), "never probed");
        CountDownLatch done = new CountDownLatch(2);
        List<Boolean> found = new ArrayList<>();
        FxTestSupport.runOnFx(() -> typst.service().detect(present -> {
            found.add(present);
            done.countDown();
            typst.service()
                    .detect(
                            again -> { // answered from the cache
                                found.add(again);
                                done.countDown();
                            });
        }));
        assertTrue(done.await(60, TimeUnit.SECONDS));
        assertEquals(List.of(true, true), found);
        assertTrue(typst.isTypstCliAvailable());

        FxTestSupport.runOnFx(() -> typst.service().setPath(null)); // a changed path forgets the probe
        assertFalse(typst.isTypstCliAvailable());
        assertEquals(List.of("typst"), typst.service().command());
    }

    // --- finishing an interactive export ----------------------------------------------------------------

    @Test
    void aFailedExportIsShownInADialogWithTheToolsMessage() throws Exception {
        TypstRenderer.PendingExport failed = TypstRenderer.pending(
                new ProcessRunner.Result(1, "", "error: cannot read file"), null, dir.resolve("out.pdf"));
        DialogPane dialog = FxDialogs.duringHeader(
                () -> typst.finishExport(failed), tr("status.typst.exportFailed", ""), ButtonBar.ButtonData.OK_DONE);
        assertNotNull(dialog);
        assertEquals("error: cannot read file", dialog.getContentText());
        assertEquals(List.of(tr("status.typst.exportFailed", "error: cannot read file")), host.statuses);
    }

    @Test
    void anExportWithNoOutputSaysSoWithoutADialog() throws Exception {
        TypstRenderer.PendingExport empty = staged(dir.resolve("out.pdf"));
        FxTestSupport.runOnFx(() -> typst.finishExport(empty));
        assertEquals(List.of(tr("status.typst.exportFailed", tr("status.typst.noOutput"))), host.statuses);
    }

    @Test
    void aSingleFileExportNamesTheFileItWrote() throws Exception {
        Path dest = dir.resolve("out.png");
        TypstRenderer.PendingExport one = staged(dest, "out-1.png");
        FxTestSupport.runOnFx(() -> typst.finishExport(one));
        assertEquals("new out-1.png", Files.readString(dest), "one page is written under the chosen name");
        assertEquals(List.of(tr("status.typst.exported", dest.toAbsolutePath().toString())), host.statuses);
    }

    @Test
    void replacingPageFilesNobodyNamedIsAskedAndCancelIsTheDefaultAnswer() throws Exception {
        Path dest = dir.resolve("report.png");
        Path existing = Files.writeString(dir.resolve("report-1.png"), "kept");
        String question = tr("dialog.typstExport.replaceBody", "report-1.png");

        TypstRenderer.PendingExport first = staged(dest, "report-1.png", "report-2.png");
        DialogPane asked =
                FxDialogs.duringContent(() -> typst.finishExport(first), question, ButtonBar.ButtonData.CANCEL_CLOSE);
        assertNotNull(asked);
        assertTrue(((Button) asked.lookupButton(ButtonType.CANCEL)).isDefaultButton(), "Enter must not replace files");
        assertFalse(((Button) asked.lookupButton(ButtonType.OK)).isDefaultButton());
        assertEquals("kept", Files.readString(existing));
        assertFalse(Files.exists(dir.resolve("report-2.png")), "a declined export writes none of its pages");
        assertEquals(List.of(tr("status.typst.exportCancelled")), host.statuses);

        TypstRenderer.PendingExport closed = staged(dest, "report-1.png", "report-2.png");
        FxDialogs.duringContent(() -> typst.finishExport(closed), question, null);
        assertEquals("kept", Files.readString(existing), "closing the question is a no");

        TypstRenderer.PendingExport accepted = staged(dest, "report-1.png", "report-2.png");
        FxDialogs.duringContent(() -> typst.finishExport(accepted), question, ButtonBar.ButtonData.OK_DONE);
        assertEquals("new report-1.png", Files.readString(existing));
        assertEquals("new report-2.png", Files.readString(dir.resolve("report-2.png")));
        assertEquals(tr("status.typst.exportedPages", 2, dir.toAbsolutePath().toString()), host.statuses.getLast());
    }

    @Test
    void anExportThatWasAlreadyFinishedReportsWhyItCannotBeWritten() throws Exception {
        TypstRenderer.PendingExport pending = staged(dir.resolve("out.png"), "out-1.png");
        pending.close(); // the window was closed under it
        FxTestSupport.runOnFx(() -> typst.finishExport(pending));
        assertEquals(1, host.statuses.size());
        assertTrue(
                host.statuses
                        .getFirst()
                        .startsWith(tr("status.typst.exportFailed", "").strip()),
                host.statuses.toString());
        assertFalse(Files.exists(dir.resolve("out.png")));
    }

    // --- the export commands ----------------------------------------------------------------------------

    @Test
    void theExportCommandsNeedTheFeatureAndATypstDocument() throws Exception {
        FxTestSupport.runOnFx(() -> {
            host.settings.setTypstSupport(false);
            typst.export();
            typst.exportPng();
            host.settings.setTypstSupport(true);
            typst.exportSvg(); // no document at all
            EditorBuffer markdown = new EditorBuffer();
            markdown.setDisplayName("notes.md");
            markdown.setContent("# Notes");
            host.active = markdown;
            typst.export();
            markdown.dispose();
        });
        assertEquals(
                List.of(
                        tr("statusbar.tip.typstDisabled"),
                        tr("statusbar.tip.typstDisabled"),
                        tr("status.typst.notTypst"),
                        tr("status.typst.notTypst")),
                host.statuses);
        assertTrue(typst.isEnabled());
    }
}
