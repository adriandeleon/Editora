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
        private java.util.function.Consumer<String> onStatus = message -> {};

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
            onStatus.accept(message);
        }
    }

    @Test
    void csvPdfExportKeepsCellsLiteralAndSaysWhatItCouldNotRender() throws Exception {
        Path output = temp.resolve("table.pdf");
        Host host = new Host();
        java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);
        host.onStatus = message -> {
            if (!tr("status.pdf.exporting").equals(message)) {
                finished.countDown();
            }
        };
        ExportCoordinator[] exports = new ExportCoordinator[1];
        try {
            FxTestSupport.runOnFx(() -> {
                exports[0] = new ExportCoordinator(
                        host, null, null, null, path -> fail("PDF export opens nothing"), chooser -> output.toFile());
                // U+0378 is an unassigned code point: no font on any machine has a glyph for it.
                exports[0].csvExportPdf("name|value\n__init__|2*3*4\nmissing|\u0378\u0378\n", "table.csv");
            });
            assertTrue(finished.await(30, java.util.concurrent.TimeUnit.SECONDS), "the export should finish");
            assertEquals(
                    tr("status.pdf.exportedUnrendered", output.toString(), 2),
                    host.status,
                    "an export with '?' substitutions must not just say \"exported\"");
            String text;
            try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.Loader.loadPDF(output.toFile())) {
                text = new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
            }
            assertTrue(text.contains("name value"), text);
            assertTrue(text.contains("__init__ 2*3*4"), "cells are not re-parsed as Markdown: " + text);
            assertTrue(text.contains("missing ??"), text);
        } finally {
            FxTestSupport.runOnFx(() -> exports[0].shutdown());
        }
    }

    /**
     * An asynchronous export (PDF, office, diagram CLI) writes to a staging path: when it reports failure
     * after writing part of its output, the file the Save dialog agreed to replace is still whole.
     */
    @Test
    void aFailedExportDoesNotTruncateTheFileItWasReplacing() throws Exception {
        Path output = Files.writeString(temp.resolve("report.pdf"), "last good export");
        List<String> reported = new ArrayList<>();
        FxTestSupport.runOnFx(() -> {
            ExportCoordinator exports = new ExportCoordinator(
                    new Host(), null, null, null, path -> fail("opens nothing"), chooser -> output.toFile());
            try {
                exports.<String>staged(
                        output.toFile(),
                        (out, done) -> {
                            assertNotEquals(output, out, "the export must not be pointed at the real file");
                            assertEquals("report.pdf", out.getFileName().toString());
                            try {
                                Files.writeString(out, "%PDF-1.7 half a docu");
                            } catch (java.io.IOException e) {
                                throw new java.io.UncheckedIOException(e);
                            }
                            done.accept("failed: disk full");
                        },
                        result -> result.startsWith("ok"),
                        message -> "failed: " + message,
                        reported::add);
                assertEquals("last good export", Files.readString(output));

                exports.<String>staged(
                        output.toFile(),
                        (out, done) -> {
                            try {
                                Files.writeString(out, "%PDF-1.7 complete");
                            } catch (java.io.IOException e) {
                                throw new java.io.UncheckedIOException(e);
                            }
                            done.accept("ok");
                        },
                        result -> result.startsWith("ok"),
                        message -> "failed: " + message,
                        reported::add);
                assertEquals("%PDF-1.7 complete", Files.readString(output));
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            } finally {
                exports.shutdown();
            }
        });
        assertEquals(List.of("failed: disk full", "ok"), reported);
        try (var files = Files.list(temp)) {
            assertEquals(List.of(output), files.toList(), "no staging directory is left beside the export");
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
