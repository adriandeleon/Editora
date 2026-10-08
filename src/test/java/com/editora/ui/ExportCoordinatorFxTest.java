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

    /** A buffer named {@code name} holding {@code text}, with the previews a window would have attached. */
    private static EditorBuffer buffer(String name, String text) {
        EditorBuffer b = new EditorBuffer();
        b.setDisplayName(name);
        b.setContent(text);
        b.setStructuredPreviewEnabled(true);
        b.setPomPreviewEnabled(true);
        if (b.isCsv()) {
            b.setCsvPreviewNode(new javafx.scene.layout.Region());
        }
        if (b.isHttpFile()) {
            b.setHttpPreviewNode(new javafx.scene.layout.Region());
        }
        return b;
    }

    /**
     * The two preview commands say no <em>before</em> the Save dialog (or a printer job) when the preview
     * cannot be put on a page: an {@code .http} response panel, a JSON or XML file that does not parse, a
     * file with no preview. They used to accept a destination and then fail with "open a Markdown or Mermaid
     * file".
     */
    @Test
    void aPreviewThatCannotBeExportedIsRefusedBeforeTheSaveDialog() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Host host = new Host();
            ExportCoordinator exports =
                    new ExportCoordinator(host, null, null, null, path -> fail("opens nothing"), chooser -> {
                        fail("no Save dialog for a preview that cannot be exported: " + host.active.getDisplayName());
                        return null;
                    });
            record Case(String name, String text, String pdfKey, String printKey) {}
            List<EditorBuffer> buffers = new ArrayList<>();
            try {
                for (Case c : List.of(
                        new Case(
                                "req.http",
                                "GET https://example.com\n",
                                "status.pdf.noPreview",
                                "status.print.noPreview"),
                        new Case("Main.java", "class Main {}\n", "status.pdf.noPreview", "status.print.noPreview"),
                        new Case(
                                "broken.json",
                                "{\"a\": [1, 2,\n",
                                "status.pdf.cannotExportUnparsed",
                                "status.print.cannotPrintUnparsed"),
                        new Case(
                                "broken.xml",
                                "<a><b></a>\n",
                                "status.pdf.cannotExportUnparsed",
                                "status.print.cannotPrintUnparsed"))) {
                    EditorBuffer b = buffer(c.name(), c.text());
                    buffers.add(b);
                    host.active = b;
                    assertEquals(!c.name().equals("Main.java"), b.hasPreview(), c.name());
                    exports.exportPreviewPdf();
                    assertEquals(tr(c.pdfKey()), host.status, c.name());
                    exports.printPreview();
                    assertEquals(tr(c.printKey()), host.status, c.name());
                }
                assertFalse(buffers.get(0).hasExportablePreview(), "an .http response panel is not exportable");
                assertFalse(buffers.get(1).hasExportablePreview(), "no preview at all");
                // Exportable by kind — what a menu item is gated on — and refused only once it is asked for.
                assertTrue(buffers.get(2).hasExportablePreview());
                assertTrue(ExportCoordinator.previewUnparsable(buffers.get(2)));
            } finally {
                exports.shutdown();
                buffers.forEach(EditorBuffer::dispose);
            }
        });
    }

    /** What still parses, or is not rendered from a parse at all, is not refused. */
    @Test
    void onlyAnUnparsableTreePreviewIsReportedAsUnparsable() throws Exception {
        FxTestSupport.runOnFx(() -> {
            List<EditorBuffer> buffers = new ArrayList<>();
            try {
                for (String[] file : new String[][] {
                    {"ok.json", "{\"a\": 1}\n"},
                    {"ok.xml", "<a><b/></a>\n"},
                    {"notes.md", "# Title\n\n{ not json\n"},
                    {"data.csv", "a,b\n1,2\n"},
                    {"broken.svg", "<svg><g></svg>\n"},
                    {"pom.xml", "<project><modelVersion>4.0.0</project>\n"}
                }) {
                    EditorBuffer b = buffer(file[0], file[1]);
                    buffers.add(b);
                    assertFalse(ExportCoordinator.previewUnparsable(b), file[0]);
                }
            } finally {
                buffers.forEach(EditorBuffer::dispose);
            }
        });
    }

    /**
     * On a CSV buffer the preview <em>is</em> the grid, so the two preview commands do what
     * {@code csv.exportPdf} / {@code csv.print} do — the table — rather than failing after the Save dialog.
     */
    @Test
    void thePreviewCommandsExportAndPrintACsvAsItsTable() throws Exception {
        Path output = temp.resolve("data.pdf");
        Host host = new Host();
        java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);
        ExportCoordinator[] exports = new ExportCoordinator[1];
        EditorBuffer[] csv = new EditorBuffer[2];
        List<String> suggested = new ArrayList<>();
        try {
            FxTestSupport.runOnFx(() -> {
                exports[0] = new ExportCoordinator(host, null, null, null, path -> fail("opens nothing"), chooser -> {
                    suggested.add(chooser.getInitialFileName());
                    return output.toFile();
                });
                // An empty CSV has no table: csvPrint says so before it asks for a printer, which is how the
                // routing can be seen without one (the old path answered "no preview to print").
                csv[1] = buffer("empty.csv", "");
                host.active = csv[1];
                exports[0].printPreview();
                assertEquals(tr("status.csv.empty"), host.status);
                exports[0].exportPreviewPdf();
                assertEquals(tr("status.csv.empty"), host.status);
                assertEquals(List.of(), suggested, "an empty CSV opens no Save dialog");

                csv[0] = buffer("data.csv", "name;qty\nalpha;2\nbeta;3\n");
                host.active = csv[0];
                host.onStatus = message -> {
                    if (!tr("status.pdf.exporting").equals(message)) {
                        finished.countDown();
                    }
                };
                exports[0].exportPreviewPdf();
            });
            assertTrue(finished.await(30, java.util.concurrent.TimeUnit.SECONDS), "the export should finish");
            assertEquals(List.of("data.pdf"), suggested);
            assertEquals(tr("status.pdf.exported", output.toString()), host.status);
            String text;
            try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.Loader.loadPDF(output.toFile())) {
                text = new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
            }
            // Columns, not source lines: the semicolon delimiter was detected and is not in the output.
            assertTrue(text.contains("name qty") && text.contains("alpha 2"), text);
            assertFalse(text.contains(";"), text);
        } finally {
            FxTestSupport.runOnFx(() -> {
                exports[0].shutdown();
                for (EditorBuffer b : csv) {
                    if (b != null) {
                        b.dispose();
                    }
                }
            });
        }
    }

    /**
     * Every export's Save dialog opens beside the document when it is a saved local file, and otherwise
     * where this window last exported to. It had no starting folder at all, so it opened wherever the
     * toolkit had last been.
     */
    @Test
    void theSaveDialogStartsBesideTheDocumentOrWhereTheLastExportWent() throws Exception {
        Path docs = Files.createDirectories(temp.resolve("proj/docs"));
        Path elsewhere = Files.createDirectories(temp.resolve("exports"));
        FxTestSupport.runOnFx(() -> {
            Host host = new Host();
            List<java.io.File> initial = new ArrayList<>();
            java.io.File[] answer = new java.io.File[1];
            ExportCoordinator exports = new ExportCoordinator(host, null, null, null, path -> {}, chooser -> {
                initial.add(chooser.getInitialDirectory());
                return answer[0];
            });
            EditorBuffer saved = new EditorBuffer();
            EditorBuffer untitled = new EditorBuffer();
            try {
                saved.setPath(docs.resolve("notes.md"));
                saved.setContent("# Notes");
                untitled.setDisplayName("scratch.md");
                untitled.setContent("# Scratch");

                // Nothing exported yet and no file on disk: the dialog is left to the platform.
                host.active = untitled;
                exports.exportCodePdf();
                exports.exportPreviewHtml();
                assertEquals(java.util.Arrays.asList(null, null), initial);

                host.active = saved;
                exports.exportCodePdf();
                exports.exportPreviewPdf();
                exports.exportPreviewHtml();
                exports.exportPreviewDocx();
                exports.exportPreviewOdt();
                exports.exportCsvTextToFile("a,b", "notes.md");
                exports.exportMarkwhenJson(); // not a Markwhen file: no dialog
                assertEquals(java.util.Collections.nCopies(6, docs.toFile()), initial.subList(2, initial.size()));

                // An export that went somewhere is remembered — for a buffer with no folder of its own...
                answer[0] = elsewhere.resolve("notes.html").toFile();
                exports.exportPreviewHtml();
                answer[0] = null;
                initial.clear();
                host.active = untitled;
                exports.exportCodePdf();
                exports.exportProjectMapPdf(null, "workspace-map");
                // ...while a saved document still opens in its own folder.
                host.active = saved;
                exports.exportCodePdf();
                assertEquals(List.of(elsewhere.toFile(), elsewhere.toFile(), docs.toFile()), initial);

                // A remembered folder that is gone is not handed to the dialog (the native one throws on it).
                Files.delete(elsewhere.resolve("notes.html"));
                Files.delete(elsewhere);
                initial.clear();
                host.active = untitled;
                exports.exportCodePdf();
                assertEquals(java.util.Arrays.asList((java.io.File) null), initial);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            } finally {
                exports.shutdown();
                saved.dispose();
                untitled.dispose();
            }
        });
    }

    /**
     * A name typed without the extension gets it (the GTK dialog adds none, so the PDF was written as
     * {@code report}). When that makes the target a file that already exists, the dialog never asked about
     * it: the export asks, and does nothing when the answer is no.
     */
    @Test
    void aNameTypedWithoutTheExtensionGetsItAndNeverReplacesAFileUnasked() throws Exception {
        assertEquals(
                new java.io.File("/x/report.pdf"),
                ExportCoordinator.withExtension(new java.io.File("/x/report"), "pdf"));
        assertEquals(
                new java.io.File("/x/report.PDF"),
                ExportCoordinator.withExtension(new java.io.File("/x/report.PDF"), "pdf"));
        assertEquals(
                new java.io.File("/x/notes.v2.pdf"),
                ExportCoordinator.withExtension(new java.io.File("/x/notes.v2"), "pdf"));
        assertEquals(
                new java.io.File("/x/.pdf.pdf"), ExportCoordinator.withExtension(new java.io.File("/x/.pdf"), "pdf"));

        Path typed = temp.resolve("report");
        Path existing = Files.writeString(temp.resolve("report.html"), "the report that was already there");
        FxTestSupport.runOnFx(() -> {
            Host host = new Host();
            List<Path> opened = new ArrayList<>();
            List<java.io.File> asked = new ArrayList<>();
            boolean[] agree = {false};
            ExportCoordinator exports =
                    new ExportCoordinator(host, null, null, null, opened::add, chooser -> typed.toFile());
            exports.confirmReplace = file -> {
                asked.add(file);
                return agree[0];
            };
            EditorBuffer b = buffer("draft.md", "# Draft");
            host.active = b;
            try {
                host.status = "unchanged";
                exports.exportPreviewHtml();
                assertEquals(List.of(existing.toFile()), asked, "asked about the file the extension leads to");
                assertEquals("the report that was already there", Files.readString(existing));
                assertFalse(Files.exists(typed), "nothing is written under the bare name either");
                assertEquals("unchanged", host.status);
                assertEquals(List.of(), opened);

                agree[0] = true;
                exports.exportPreviewHtml();
                assertTrue(Files.readString(existing).contains("Draft"));
                assertEquals(List.of(existing), opened);
                assertFalse(Files.exists(typed));

                // No file in the way: no question, and the extension is simply added.
                Files.delete(existing);
                asked.clear();
                exports.exportPreviewHtml();
                assertEquals(List.of(), asked);
                assertTrue(Files.exists(existing));
                assertEquals(tr("status.html.exported", existing.toString()), host.status);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            } finally {
                exports.shutdown();
                b.dispose();
            }
        });
    }

    /**
     * The failure dialogs name what failed in the header and why in the body. The header used to be the
     * status message with an empty argument ("Printing failed: "), the print dialog's title the command
     * title "File: Print…", and a failure with no message read "null".
     */
    @Test
    void failureDialogsHaveAHeaderOfTheirOwnAndNeverSayNull() throws Exception {
        for (String key : List.of(
                "dialog.pdfExport.failed", "dialog.officeExport.failed", "dialog.print.failed", "dialog.print.title")) {
            assertNotEquals(key, tr(key), key + " must be in the catalog");
            assertFalse(tr(key).contains(":"), "a dialog header or title is not a status prefix: " + tr(key));
        }
        assertEquals(tr("dialog.export.noDetails"), ExportCoordinator.failureDetail(null));
        assertEquals(tr("dialog.export.noDetails"), ExportCoordinator.failureDetail("  "));
        assertEquals("mmdc exited with 1", ExportCoordinator.failureDetail("mmdc exited with 1"));
        FxTestSupport.runOnFx(() -> {
            ExportCoordinator exports =
                    new ExportCoordinator(new Host(), null, null, null, path -> {}, chooser -> null);
            try {
                javafx.scene.control.Alert alert = exports.failureAlert(
                        tr("dialog.print.title"), tr("dialog.print.failed"), ExportCoordinator.failureDetail(null));
                assertEquals(javafx.scene.control.Alert.AlertType.ERROR, alert.getAlertType());
                assertEquals(tr("dialog.print.title"), alert.getTitle());
                assertNotEquals(tr("command.editor.print"), alert.getTitle());
                assertEquals(tr("dialog.print.failed"), alert.getHeaderText());
                assertEquals(tr("dialog.export.noDetails"), alert.getContentText());
            } finally {
                exports.shutdown();
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
