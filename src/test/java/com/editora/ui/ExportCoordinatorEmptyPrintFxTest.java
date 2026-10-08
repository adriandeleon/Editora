package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.*;

/**
 * An empty document is not printed: it used to open a preview of one blank "Page 1 of 1" with Print…
 * enabled. Now the request says "Nothing to print" and stops before a printer job exists.
 */
@Tag("fx")
class ExportCoordinatorEmptyPrintFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static final class Host extends CoordinatorHostStub {
        final com.editora.config.Settings settings = new com.editora.config.Settings();
        final List<String> statuses = new ArrayList<>();
        EditorBuffer active;

        @Override
        public com.editora.config.Settings settings() {
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

    @Test
    void anEmptyOrBlankDocumentSaysNothingToPrintAndCreatesNoJob() throws Exception {
        Host host = new Host();
        int[] jobs = {0};
        ExportCoordinator[] exports = new ExportCoordinator[1];
        try {
            FxTestSupport.runOnFx(() -> {
                exports[0] = new ExportCoordinator(host, null, null, null, path -> {});
                exports[0].printJobs = () -> {
                    jobs[0]++;
                    return null;
                };
                for (String[] file :
                        new String[][] {{"Empty.java", ""}, {"blank.txt", " \n\t\n"}, {"notes.md", "\n  \n"}}) {
                    EditorBuffer b = new EditorBuffer();
                    b.setDisplayName(file[0]);
                    b.setContent(file[1]);
                    host.active = b;
                    host.statuses.clear();
                    exports[0].printCode();
                    assertEquals(List.of(tr("status.print.nothing")), host.statuses, file[0] + " as code");
                    if (b.isMarkdown()) {
                        host.statuses.clear();
                        exports[0].printPreview();
                        assertEquals(List.of(tr("status.print.nothing")), host.statuses, file[0] + " as a preview");
                    }
                }
                assertEquals(0, jobs[0], "no printer job is asked for");
                assertFalse(
                        FxTestSupport.<Boolean>field(exports[0], "printPreparing"), "and nothing is left preparing");

                // a document with something in it still goes on to ask for a job
                EditorBuffer b = new EditorBuffer();
                b.setDisplayName("Full.java");
                b.setContent("class Full {}\n");
                host.active = b;
                host.statuses.clear();
                exports[0].printCode();
                assertEquals(1, jobs[0]);
                assertEquals(List.of(tr("status.print.noPrinter")), host.statuses);
            });
        } finally {
            FxTestSupport.runOnFx(() -> {
                if (exports[0] != null) {
                    exports[0].shutdown();
                }
            });
        }
    }
}
