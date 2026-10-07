package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.editora.process.ProcessRunner;
import com.editora.typst.TypstRenderer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Typst: Export PNG/SVG asks before it replaces page files the Save dialog never named, and a "no" leaves
 * every one of them as it was.
 */
@Tag("fx")
class TypstExportConfirmFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @TempDir
    Path dir;

    private static final class Host extends CoordinatorHostStub {
        String status;

        @Override
        public void setStatus(String message) {
            status = message;
        }
    }

    private TypstRenderer.PendingExport twoPages(Path dest) throws Exception {
        Path staging = Files.createTempDirectory(dir, ".editora-export-");
        Files.writeString(staging.resolve("report-1.png"), "new one");
        Files.writeString(staging.resolve("report-2.png"), "new two");
        return TypstRenderer.pending(new ProcessRunner.Result(0, "", ""), staging, dest);
    }

    @Test
    void decliningKeepsTheExistingPageFilesAndAcceptingReplacesThem() throws Exception {
        Path dest = dir.resolve("report.png");
        Path page1 = Files.writeString(dir.resolve("report-1.png"), "kept one");
        Path page2 = dir.resolve("report-2.png");
        Host host = new Host();
        TypstCoordinator typst = new TypstCoordinator(host, p -> p);
        List<List<Path>> asked = new ArrayList<>();

        TypstRenderer.PendingExport declined = twoPages(dest);
        FxTestSupport.runOnFx(() -> {
            typst.setConfirmReplaceForTest(existing -> {
                asked.add(existing);
                return false;
            });
            typst.finishExport(declined);
        });
        assertEquals(List.of(List.of(page1)), asked, "asked about exactly the file that would be replaced");
        assertEquals("kept one", Files.readString(page1));
        assertFalse(Files.exists(page2), "a declined export writes none of its pages");
        assertEquals(tr("status.typst.exportCancelled"), host.status);

        TypstRenderer.PendingExport accepted = twoPages(dest);
        FxTestSupport.runOnFx(() -> {
            typst.setConfirmReplaceForTest(existing -> true);
            typst.finishExport(accepted);
        });
        assertEquals("new one", Files.readString(page1));
        assertEquals("new two", Files.readString(page2));
        assertEquals(tr("status.typst.exportedPages", 2, dir.toAbsolutePath().toString()), host.status);
    }

    @Test
    void nothingIsAskedWhenNoPageFileExists() throws Exception {
        Path dest = dir.resolve("report.png");
        Host host = new Host();
        TypstCoordinator typst = new TypstCoordinator(host, p -> p);
        TypstRenderer.PendingExport pending = twoPages(dest);

        FxTestSupport.runOnFx(() -> {
            typst.setConfirmReplaceForTest(existing -> {
                throw new AssertionError("no question when nothing is replaced");
            });
            typst.finishExport(pending);
        });

        assertTrue(Files.exists(dir.resolve("report-1.png")) && Files.exists(dir.resolve("report-2.png")));
    }

    @Test
    void theQuestionListsTheFilesAndCapsALongList() {
        List<Path> many = new ArrayList<>();
        for (int i = 1; i <= 11; i++) {
            many.add(Path.of("out", "report-" + i + ".png"));
        }
        String text = TypstCoordinator.replaceList(many);
        assertTrue(text.startsWith("report-1.png\nreport-2.png"), text);
        assertFalse(text.contains("report-9.png"), text);
        assertTrue(text.endsWith(tr("dialog.typstExport.replaceMore", 3)), text);
    }
}
