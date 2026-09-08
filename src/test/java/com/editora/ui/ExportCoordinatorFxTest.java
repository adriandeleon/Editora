package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.editora.command.Command;
import com.editora.command.CommandRegistry;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises command routing and real export output without native dialogs or installed renderers. */
@Tag("fx")
class ExportCoordinatorFxTest {
    @TempDir
    Path temp;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static final class Host extends CoordinatorHostStub {
        private final Settings settings = new Settings();
        private EditorBuffer active;
        private String status;

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
            status = message;
        }
    }

    @Test
    void preservesCommandOrderAndNoBufferGuards() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Host host = new Host();
            ExportCoordinator exports =
                    new ExportCoordinator(host, null, null, null, path -> fail("No file should open"), chooser -> {
                        fail("No buffer must not open a save dialog");
                        return null;
                    });
            try {
                CommandRegistry registry = new CommandRegistry();
                exports.registerCommands(registry);
                assertEquals(
                        List.of(
                                "editor.exportPdf",
                                "preview.exportPdf",
                                "preview.exportHtml",
                                "preview.copy",
                                "preview.copyHtml",
                                "preview.exportDocx",
                                "preview.exportOdt",
                                "editor.print",
                                "preview.print",
                                "markwhen.exportJson"),
                        registry.all().stream().map(Command::id).toList());
                List<String> statuses = List.of(
                        "status.noFileOpen",
                        "status.pdf.noPreview",
                        "status.html.notMarkdown",
                        "status.preview.none",
                        "status.html.notMarkdown",
                        "status.office.notMarkdown",
                        "status.office.notMarkdown",
                        "status.noFileOpen",
                        "status.print.noPreview",
                        "status.markwhen.notMarkwhen");
                int i = 0;
                for (Command command : registry.all()) {
                    assertTrue(registry.run(command.id()));
                    assertEquals(tr(statuses.get(i++)), host.status, command.id());
                }
            } finally {
                exports.shutdown();
            }
        });
    }

    @Test
    void htmlExportReadsCurrentBufferAndOpensTheWrittenFile() throws Exception {
        Path output = temp.resolve("export.html");
        FxTestSupport.runOnFx(() -> {
            Host host = new Host();
            EditorBuffer first = new EditorBuffer();
            EditorBuffer second = new EditorBuffer();
            List<Path> opened = new ArrayList<>();
            List<String> suggested = new ArrayList<>();
            first.setDisplayName("first.md");
            first.setContent("# First document");
            second.setDisplayName("second.md");
            second.setContent("# Second document");
            host.active = first;
            ExportCoordinator exports = new ExportCoordinator(host, null, null, null, opened::add, chooser -> {
                suggested.add(chooser.getInitialFileName());
                return output.toFile();
            });
            try {
                CommandRegistry registry = new CommandRegistry();
                exports.registerCommands(registry);
                host.active = second;
                assertTrue(registry.run("preview.exportHtml"));
                assertEquals(List.of("second.html"), suggested);
                assertEquals(List.of(output), opened);
                assertEquals(tr("status.html.exported", output.toString()), host.status);
            } finally {
                exports.shutdown();
                first.dispose();
                second.dispose();
            }
        });
        String html = Files.readString(output);
        assertTrue(html.contains("Second document"));
        assertFalse(html.contains("First document"));
    }

    @Test
    void cancelledExportsLeaveStatusAndDocumentUntouched() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Host host = new Host();
            EditorBuffer buffer = new EditorBuffer();
            buffer.setDisplayName("draft.md");
            buffer.setContent("# Draft");
            host.active = buffer;
            host.status = "unchanged";
            List<String> names = new ArrayList<>();
            ExportCoordinator exports = new ExportCoordinator(
                    host, null, null, null, path -> fail("Cancelled exports must not open files"), chooser -> {
                        names.add(chooser.getInitialFileName());
                        return null;
                    });
            try {
                exports.exportCodePdf();
                exports.exportPreviewHtml();
                exports.exportPreviewDocx();
                exports.exportPreviewOdt();
                exports.csvExportPdf("a,b\n1,2", "table.csv");
                exports.csvExportSpreadsheet(List.of(List.of("a", "b")), true, "table.csv", true);
                exports.csvExportSpreadsheet(List.of(List.of("a", "b")), true, "table.csv", false);
                exports.exportCsvTextToFile("a,b", "table.md");
                exports.exportProjectMapPdf(null, "workspace-map");
                assertEquals(
                        List.of(
                                "draft.pdf",
                                "draft.html",
                                "draft.docx",
                                "draft.odt",
                                "table.pdf",
                                "table.xlsx",
                                "table.ods",
                                "table.csv",
                                "workspace-map.pdf"),
                        names);
                assertEquals("unchanged", host.status);
                assertEquals("# Draft", buffer.getContent());
            } finally {
                exports.shutdown();
                buffer.dispose();
            }
        });
    }

    @Test
    void namingUsesSavedPathAndPreservesPathBasedGrammar() throws Exception {
        FxTestSupport.runOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            try {
                assertEquals("document", ExportCoordinator.bufferBaseName(buffer));
                buffer.setDisplayName("draft.java");
                assertEquals("draft.java", ExportCoordinator.grammarKey(buffer));
                Path saved = temp.resolve(".ssh/config");
                buffer.setPath(saved);
                assertEquals("config", ExportCoordinator.bufferBaseName(buffer));
                assertEquals(saved.toString(), ExportCoordinator.grammarKey(buffer));
            } finally {
                buffer.dispose();
            }
        });
    }
}
