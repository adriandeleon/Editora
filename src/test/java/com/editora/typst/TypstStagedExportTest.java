package com.editora.typst;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import com.editora.process.ProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Typst export is staged, and names its real targets before it replaces anything.
 *
 * <p>Exporting {@code report.png} wrote {@code report-1.png}, {@code report-2.png}, … straight into the chosen
 * folder: the Save dialog had asked about {@code report.png} only, so page files that already existed were
 * replaced without a question, and the file the user confirmed was never written.
 */
class TypstStagedExportTest {

    private static final ProcessRunner.Result OK = new ProcessRunner.Result(0, "", "");

    @TempDir
    Path dir;

    private Path staging(String... pages) throws IOException {
        Path staging = Files.createDirectory(dir.resolve(".editora-export-test"));
        for (String page : pages) {
            Files.writeString(staging.resolve(page), "new " + page);
        }
        return staging;
    }

    @Test
    void aMultiPageExportNamesTheNumberedFilesItWouldReplaceAndTouchesNothingUntilCommitted() throws Exception {
        Path dest = dir.resolve("report.png");
        Path page1 = Files.writeString(dir.resolve("report-1.png"), "my own page one");
        Path page2 = Files.writeString(dir.resolve("report-2.png"), "my own page two");
        Path staging = staging("report-1.png", "report-2.png", "report-10.png");

        TypstRenderer.PendingExport pending = TypstRenderer.pending(OK, staging, dest);

        assertTrue(pending.ok());
        assertEquals(List.of(page1, page2, dir.resolve("report-10.png")), pending.targets(), "page order is numeric");
        assertEquals(List.of(page1, page2), pending.existingTargets(), "the files nobody was asked about");
        assertEquals("my own page one", Files.readString(page1), "staging alone replaces nothing");

        pending.close(); // the user said no
        assertEquals("my own page one", Files.readString(page1));
        assertEquals("my own page two", Files.readString(page2));
        assertFalse(Files.exists(dir.resolve("report-10.png")));
        assertFalse(Files.exists(staging), "a declined export leaves no staging directory behind");
    }

    @Test
    void committingReplacesTheTargetsAndRemovesTheStagingDirectory() throws Exception {
        Path dest = dir.resolve("report.svg");
        Files.writeString(dir.resolve("report-1.svg"), "old");
        Path staging = staging("report-1.svg", "report-2.svg");

        TypstRenderer.PendingExport pending = TypstRenderer.pending(OK, staging, dest);
        List<Path> written = pending.commit();

        assertEquals(List.of(dir.resolve("report-1.svg"), dir.resolve("report-2.svg")), written);
        assertEquals("new report-1.svg", Files.readString(dir.resolve("report-1.svg")));
        assertEquals("new report-2.svg", Files.readString(dir.resolve("report-2.svg")));
        assertFalse(Files.exists(staging));
        assertThrows(IOException.class, pending::commit, "a finished export cannot be committed twice");
    }

    @Test
    void aOnePageImageIsWrittenUnderTheChosenNameSoNothingUnconfirmedIsReplaced() throws Exception {
        Path dest = Files.writeString(dir.resolve("report.png"), "the file the Save dialog asked about");
        Path numbered = Files.writeString(dir.resolve("report-1.png"), "an unrelated file");
        Path staging = staging("report-1.png");

        TypstRenderer.PendingExport pending = TypstRenderer.pending(OK, staging, dest);

        assertEquals(List.of(dest), pending.targets());
        assertTrue(pending.existingTargets().isEmpty(), "the chosen file was confirmed by the Save dialog");
        pending.commit();
        assertEquals("new report-1.png", Files.readString(dest));
        assertEquals("an unrelated file", Files.readString(numbered));
    }

    @Test
    void aPdfIsOneFileAtTheChosenPath() throws Exception {
        Path dest = Files.writeString(dir.resolve("report.pdf"), "previous pdf");
        Path staging = staging("report.pdf");

        TypstRenderer.PendingExport pending = TypstRenderer.pending(OK, staging, dest);

        assertEquals(List.of(dest), pending.targets());
        assertTrue(pending.existingTargets().isEmpty());
        pending.commit();
        assertEquals("new report.pdf", Files.readString(dest));
    }

    @Test
    void aFailedOrEmptyExportHasNoTargetsAndCannotBeCommitted() throws Exception {
        Path dest = Files.writeString(dir.resolve("report.pdf"), "previous pdf");
        Path staging = staging("report.pdf");

        TypstRenderer.PendingExport failed =
                TypstRenderer.pending(new ProcessRunner.Result(1, "", "error: unknown variable"), staging, dest);
        assertFalse(failed.ok());
        assertTrue(failed.targets().isEmpty());
        assertThrows(IOException.class, failed::commit);
        assertEquals("previous pdf", Files.readString(dest), "a failed export leaves the previous file whole");
        assertFalse(Files.exists(staging));

        TypstRenderer.PendingExport empty =
                TypstRenderer.pending(OK, Files.createDirectory(dir.resolve("empty")), dir.resolve("x.png"));
        assertFalse(empty.ok(), "exit 0 with no output is not a success");
    }

    static boolean typstInstalled() {
        try {
            return ProcessRunner.run(null, Duration.ofSeconds(20), List.of("typst", "--version"))
                    .ok();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The reviewer's probe, against the real CLI: two existing page files, a two-page document. */
    @Test
    @EnabledIf("typstInstalled")
    void theRealCliStagesATwoPageDocumentWithoutReplacingExistingPageFiles() throws Exception {
        Path out = Files.createDirectory(dir.resolve("out"));
        Path page1 = Files.writeString(out.resolve("report-1.png"), "plain text one");
        Path page2 = Files.writeString(out.resolve("report-2.png"), "plain text two");
        Path dest = out.resolve("report.png");

        try (TypstRenderer.PendingExport pending =
                TypstRenderer.stageExport(List.of("typst"), "= One\n#pagebreak()\n= Two\n", dest, null, null)) {
            assertTrue(pending.ok(), pending.result().message());
            assertEquals(List.of(page1, page2), pending.existingTargets());
            assertEquals("plain text one", Files.readString(page1), "nothing is replaced before the answer");
            assertEquals("plain text two", Files.readString(page2));
        }
        assertEquals("plain text one", Files.readString(page1), "declining keeps both files");
        try (var files = Files.list(out)) {
            assertEquals(2, files.count(), "no staging directory is left in the chosen folder");
        }

        try (TypstRenderer.PendingExport pending =
                TypstRenderer.stageExport(List.of("typst"), "= One\n#pagebreak()\n= Two\n", dest, null, null)) {
            pending.commit();
        }
        byte[] png = Files.readAllBytes(page1);
        assertTrue(png.length > 8 && png[1] == 'P' && png[2] == 'N' && png[3] == 'G', "accepted: a real PNG now");
    }

    @Test
    @EnabledIf("typstInstalled")
    void theRealCliWritesAOnePageDocumentUnderTheChosenName() throws Exception {
        Path dest = dir.resolve("single.png");
        try (TypstRenderer.PendingExport pending =
                TypstRenderer.stageExport(List.of("typst"), "= One\n", dest, null, null)) {
            assertTrue(pending.ok(), pending.result().message());
            assertEquals(List.of(dest), pending.commit());
        }
        assertTrue(Files.size(dest) > 100);
        assertFalse(Files.exists(dir.resolve("single-1.png")));
    }
}
