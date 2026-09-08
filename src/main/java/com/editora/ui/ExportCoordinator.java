package com.editora.ui;

import java.nio.file.Path;
import java.util.function.Consumer;

import javafx.scene.control.Alert;

import com.editora.command.Command;
import com.editora.command.CommandRegistry;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.markdown.MarkdownTable;

import static com.editora.i18n.Messages.tr;

/**
 * Per-window document output: source/preview exports, clipboard copies, CSV/spreadsheet output,
 * Project Map output, and print preparation. Owns and shuts down the output services; renderer
 * coordinators remain owned by the window. Reads the active buffer and settings at invocation time.
 */
final class ExportCoordinator {
    private final CoordinatorHost host;
    private final MermaidCoordinator mermaid;
    private final DiagramCoordinator diagram;
    private final TypstCoordinator typst;
    private final Consumer<Path> openPath;
    private final java.util.function.Function<javafx.stage.FileChooser, java.io.File> chooseDestination;
    private final com.editora.pdf.PdfExportService pdfService = new com.editora.pdf.PdfExportService();
    private final com.editora.office.OfficeExportService officeService = new com.editora.office.OfficeExportService();
    private final com.editora.print.PrintService printService = new com.editora.print.PrintService();

    ExportCoordinator(
            CoordinatorHost host,
            MermaidCoordinator mermaid,
            DiagramCoordinator diagram,
            TypstCoordinator typst,
            Consumer<Path> openPath) {
        this(host, mermaid, diagram, typst, openPath, chooser -> chooser.showSaveDialog(host.window()));
    }

    /** Allows tests to exercise exports and cancellation without opening a native file dialog. */
    ExportCoordinator(
            CoordinatorHost host,
            MermaidCoordinator mermaid,
            DiagramCoordinator diagram,
            TypstCoordinator typst,
            Consumer<Path> openPath,
            java.util.function.Function<javafx.stage.FileChooser, java.io.File> chooseDestination) {
        this.chooseDestination = chooseDestination;
        this.host = host;
        this.mermaid = mermaid;
        this.diagram = diagram;
        this.typst = typst;
        this.openPath = openPath;
    }

    void registerCommands(CommandRegistry registry) {
        registry.register(Command.of("editor.exportPdf", this::exportCodePdf));
        registry.register(Command.of("preview.exportPdf", this::exportPreviewPdf));
        registry.register(Command.of("preview.exportHtml", this::exportPreviewHtml));
        registry.register(Command.of("preview.copy", this::copyPreview));
        registry.register(Command.of("preview.copyHtml", this::copyPreviewHtml));
        registry.register(Command.of("preview.exportDocx", this::exportPreviewDocx));
        registry.register(Command.of("preview.exportOdt", this::exportPreviewOdt));
        registry.register(Command.of("editor.print", this::printCode));
        registry.register(Command.of("preview.print", this::printPreview));
        registry.register(Command.of("markwhen.exportJson", this::exportMarkwhenJson));
    }

    void shutdown() {
        pdfService.shutdown();
        officeService.shutdown();
        printService.shutdown();
    }

    /** Exports a CSV as a PDF by reusing the Markdown-table → PDF pipeline (the grid's right-click menu). */
    void csvExportPdf(String csvText, String baseName) {
        String md = MarkdownTable.fromCsv(csvText);
        if (md == null) {
            host.setStatus(tr("status.csv.empty"));
            return;
        }
        java.io.File f = choosePdfDestination(baseName);
        if (f == null) {
            return;
        }
        host.setStatus(tr("status.pdf.exporting"));
        pdfService.exportMarkdown(md, null, host.settings().getPdfPageSize(), null, f.toPath(), r -> reportPdf(r, f));
    }

    /** Opens the print preview for a CSV by reusing the Markdown-table → print pipeline. */
    void csvPrint(String csvText) {
        String md = MarkdownTable.fromCsv(csvText);
        if (md == null) {
            host.setStatus(tr("status.csv.empty"));
            return;
        }
        javafx.print.PrinterJob job = javafx.print.PrinterJob.createPrinterJob();
        if (job == null) {
            host.setStatus(tr("status.print.noPrinter"));
            return;
        }
        host.setStatus(tr("status.print.preparing"));
        printService.prepareMarkdown(md, null, prepared -> openPrintPreview(job, prepared));
    }

    /** Exports the complete Project Map layout—not merely the visible viewport—to a paginated PDF. */
    void exportProjectMapPdf(javafx.scene.image.Image image, String baseName) {
        java.io.File file = choosePdfDestination(baseName);
        if (file == null) {
            return;
        }
        host.setStatus(tr("status.pdf.exporting"));
        pdfService.exportFxImages(
                java.util.List.of(image),
                host.settings().getPdfPageSize(),
                file.toPath(),
                result -> reportPdf(result, file));
    }

    /** Opens the normal Print Preview flow for the complete Project Map layout. */
    void printProjectMap(javafx.scene.image.Image image) {
        javafx.print.PrinterJob job = javafx.print.PrinterJob.createPrinterJob();
        if (job == null) {
            host.setStatus(tr("status.print.noPrinter"));
            return;
        }
        host.setStatus(tr("status.print.preparing"));
        printService.prepareFxImages(java.util.List.of(image), prepared -> openPrintPreview(job, prepared));
    }

    /** Exports parsed CSV rows to a spreadsheet — {@code xlsx} true → Excel {@code .xlsx}, else ODF {@code .ods}. */
    void csvExportSpreadsheet(
            java.util.List<java.util.List<String>> rows, boolean hasHeader, String baseName, boolean xlsx) {
        if (rows == null || rows.isEmpty()) {
            host.setStatus(tr("status.csv.empty"));
            return;
        }
        String ext = xlsx ? "xlsx" : "ods";
        String filter = xlsx ? "Excel" : "OpenDocument Spreadsheet";
        java.io.File f = chooseOfficeDestination(baseName, ext, filter);
        if (f == null) {
            return;
        }
        host.setStatus(tr("status.office.exporting"));
        java.util.function.Consumer<com.editora.office.OfficeExportService.Result> cb = r -> reportOffice(r, f);
        if (xlsx) {
            officeService.exportXlsx(rows, hasHeader, f.toPath(), cb);
        } else {
            officeService.exportOds(rows, hasHeader, f.toPath(), cb);
        }
    }

    /**
     * Exports the active buffer's source text to a syntax-highlighted, light-themed PDF (any text file).
     * Honors the Settings toggles (line numbers, syntax highlighting) + page size. Runs off the FX thread.
     */
    void exportCodePdf() {
        EditorBuffer b = host.activeBuffer();
        if (b == null) {
            host.setStatus(tr("status.noFileOpen"));
            return;
        }
        String base = bufferBaseName(b);
        java.io.File f = choosePdfDestination(base);
        if (f == null) {
            return;
        }
        Settings s = host.settings();
        host.setStatus(tr("status.pdf.exporting"));
        pdfService.exportCode(
                b.getContent(),
                grammarKey(b),
                s.isPdfSyntaxHighlighting(),
                s.isPdfLineNumbers(),
                s.getTabSize(),
                s.getPdfPageSize(),
                f.toPath(),
                r -> reportPdf(r, f));
    }

    /**
     * Exports the active buffer's rendered preview to PDF: a Mermaid {@code .mmd} diagram via mmdc's
     * native vector PDF, or a Markdown document via the native PDF writer. No-op for non-previewable buffers.
     */
    void exportPreviewPdf() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || !b.hasPreview()) {
            host.setStatus(tr("status.pdf.noPreview"));
            return;
        }
        java.io.File f = choosePdfDestination(bufferBaseName(b));
        if (f == null) {
            return;
        }
        host.setStatus(tr("status.pdf.exporting"));
        String pageSize = host.settings().getPdfPageSize();
        java.util.function.Consumer<com.editora.pdf.PdfExportService.Result> report = r -> reportPdf(r, f);
        if (b.isMarkdown()) {
            java.nio.file.Path baseDir =
                    b.getPath() == null ? null : b.getPath().getParent();
            pdfService.exportMarkdown(
                    b.getContent(), baseDir, pageSize, mermaid.mmdcCommandOrNull(), f.toPath(), report);
        } else if (b.isDiagram()) { // Mermaid (.mmd) — CLI render to PDF
            mermaid.exportDiagram(
                    b.getContent(),
                    f.toPath(),
                    r -> report.accept(new com.editora.pdf.PdfExportService.Result(r.ok(), r.message())));
        } else if (b.isRenderedDiagram()) { // Graphviz DOT / PlantUML — CLI render to PDF
            diagram.exportToPath(
                    b.diagramKind(),
                    b.getContent(),
                    f.toPath(),
                    r -> report.accept(new com.editora.pdf.PdfExportService.Result(r.ok(), r.message())));
        } else if (b.isSvg()) { // rasterize the SVG source and embed it as a PDF page
            byte[] png = com.editora.editor.PreviewImageLoader.svgToPng(
                    b.getContent().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (png == null) {
                report.accept(new com.editora.pdf.PdfExportService.Result(false, tr("status.pdf.noPreview")));
                return;
            }
            pdfService.exportImages(java.util.List.of(png), pageSize, f.toPath(), report);
        } else if (b.isTypst()) { // Typst — native CLI render straight to a (multi-page) PDF
            typst.exportToPath(
                    b.getContent(),
                    b.getPath(),
                    f.toPath(),
                    r -> report.accept(new com.editora.pdf.PdfExportService.Result(r.ok(), r.message())));
        } else { // Markwhen timeline / JSON-YAML-TOML tree / XML tree — snapshot the rendered preview (light)
            java.util.List<byte[]> chunks = b.snapshotPreviewChunks(Themes.lightUserAgentStylesheet());
            if (chunks == null || chunks.isEmpty()) {
                report.accept(new com.editora.pdf.PdfExportService.Result(false, tr("status.pdf.noPreview")));
                return;
            }
            pdfService.exportImages(chunks, pageSize, f.toPath(), report);
        }
    }

    /**
     * Copies the active buffer's rendered preview to the clipboard. Markdown lands as rich text (a
     * {@code text/html} flavor beside the plain text), so it pastes formatted into Word / Teams / Gmail;
     * a diagram copies its source. The palette twin of the preview's right-click Copy.
     */
    void copyPreview() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || !b.hasPreview()) {
            host.setStatus(tr("status.preview.none"));
            return;
        }
        b.copyPreviewToClipboard();
        host.setStatus(tr(b.isMarkdown() ? "status.preview.copiedRich" : "status.preview.copied"));
    }

    /** Copies the active Markdown buffer's preview as HTML <em>markup</em> (for pasting into an HTML file). */
    void copyPreviewHtml() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || !b.copyPreviewHtmlSource()) {
            host.setStatus(tr("status.html.notMarkdown"));
            return;
        }
        host.setStatus(tr("status.preview.copiedHtml"));
    }

    /** Exports the active Markdown buffer's rendered preview to a standalone HTML file. */
    void exportPreviewHtml() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || !b.isMarkdown()) {
            host.setStatus(tr("status.html.notMarkdown"));
            return;
        }
        java.io.File f = chooseHtmlDestination(bufferBaseName(b));
        if (f == null) {
            return;
        }
        try {
            String base = bufferBaseName(b);
            int dot = base.lastIndexOf('.');
            String title = dot > 0 ? base.substring(0, dot) : base;
            String html = com.editora.editor.MarkdownHtmlExport.toHtml(
                    b.getContent(), title, host.settings().isMathSupport());
            java.nio.file.Files.writeString(f.toPath(), html);
            host.setStatus(tr("status.html.exported", f.toString()));
            openPath.accept(f.toPath()); // show the generated HTML in a tab
        } catch (Exception ex) {
            host.setStatus(tr("status.html.exportFailed", String.valueOf(ex.getMessage())));
        }
    }

    /** A Save dialog defaulting to {@code <base-without-ext>.html}. */
    private java.io.File chooseHtmlDestination(String base) {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle(tr("dialog.htmlExport.title"));
        int dot = base == null ? -1 : base.lastIndexOf('.');
        chooser.setInitialFileName((dot > 0 ? base.substring(0, dot) : (base == null ? "document" : base)) + ".html");
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("HTML", "*.html"));
        return chooseDestination.apply(chooser);
    }

    /**
     * The buffer's file name — from its on-disk path when saved, else its suggested display name, else a
     * default. Drives the syntax-highlighting grammar lookup and the default export file name (PDF, Mermaid).
     * ({@code EditorBuffer.getDisplayName()} is null for a saved file, so the path must be consulted.)
     */
    static String bufferBaseName(EditorBuffer b) {
        if (b.getPath() != null) {
            return b.getPath().getFileName().toString();
        }
        String dn = b.getDisplayName();
        return dn == null || dn.isBlank() ? "document" : dn;
    }

    /**
     * The key used to resolve a buffer's grammar for export/print — its <em>full path</em> (so
     * location-based types like {@code ~/.ssh/config} or {@code /etc/hosts} resolve, matching the live
     * editor), or its display base name for an unsaved buffer.
     */
    static String grammarKey(EditorBuffer b) {
        return b.getPath() != null ? b.getPath().toString() : bufferBaseName(b);
    }

    /** A Save dialog defaulting to {@code <base-without-ext>.pdf}. */
    private java.io.File choosePdfDestination(String base) {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle(tr("dialog.pdfExport.title"));
        int dot = base == null ? -1 : base.lastIndexOf('.');
        chooser.setInitialFileName((dot > 0 ? base.substring(0, dot) : (base == null ? "document" : base)) + ".pdf");
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("PDF", "*.pdf"));
        return chooseDestination.apply(chooser);
    }

    /** Exports the active Markwhen buffer's parsed timeline to a JSON file (preview menu + palette). */
    void exportMarkwhenJson() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || !b.isMarkwhen()) {
            host.setStatus(tr("status.markwhen.notMarkwhen"));
            return;
        }
        String base = bufferBaseName(b);
        int dot = base == null ? -1 : base.lastIndexOf('.');
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setInitialFileName((dot > 0 ? base.substring(0, dot) : (base == null ? "timeline" : base)) + ".json");
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("JSON", "*.json"));
        if (b.getPath() != null && b.getPath().getParent() != null && host.isLocalBuffer(b)) {
            chooser.setInitialDirectory(b.getPath().getParent().toFile());
        }
        java.io.File f = chooseDestination.apply(chooser);
        if (f == null) {
            return;
        }
        try {
            String json =
                    com.editora.markwhen.MarkwhenJson.toJson(com.editora.markwhen.MarkwhenParser.parse(b.getContent()));
            java.nio.file.Files.writeString(f.toPath(), json);
            host.setStatus(tr("status.markwhen.jsonExported", f.getName()));
        } catch (java.io.IOException e) {
            host.setStatus(tr("status.markwhen.exportFailed", e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    /** Reports a PDF export result: status + (on failure) an error dialog. */
    private void reportPdf(com.editora.pdf.PdfExportService.Result r, java.io.File f) {
        if (r.ok()) {
            host.setStatus(tr("status.pdf.exported", f.toString()));
        } else {
            String msg = String.valueOf(r.message());
            host.setStatus(tr("status.pdf.exportFailed", msg));
            Alert err = new Alert(Alert.AlertType.ERROR);
            err.initOwner(host.window());
            err.setTitle(tr("dialog.pdfExport.title"));
            err.setHeaderText(tr("status.pdf.exportFailed", ""));
            err.setContentText(msg);
            err.showAndWait();
        }
    }

    /** Exports the active Markdown preview to a MS Word {@code .docx} (Apache POI). */
    void exportPreviewDocx() {
        exportPreviewOffice(true);
    }

    /** Exports the active Markdown preview to an OpenDocument Text {@code .odt} (hand-rolled). */
    void exportPreviewOdt() {
        exportPreviewOffice(false);
    }

    private void exportPreviewOffice(boolean docx) {
        EditorBuffer b = host.activeBuffer();
        // Word/ODT render the Markdown document model — diagrams (.mmd) aren't supported, only Markdown.
        if (b == null || !b.isMarkdown()) {
            host.setStatus(tr("status.office.notMarkdown"));
            return;
        }
        String ext = docx ? "docx" : "odt";
        String filter = docx ? "Word" : "OpenDocument";
        java.io.File f = chooseOfficeDestination(bufferBaseName(b), ext, filter);
        if (f == null) {
            return;
        }
        host.setStatus(tr("status.office.exporting"));
        java.nio.file.Path baseDir = b.getPath() == null ? null : b.getPath().getParent();
        java.util.List<String> mmdc = mermaid.mmdcCommandOrNull(); // ```mermaid blocks → diagram images
        java.util.function.Consumer<com.editora.office.OfficeExportService.Result> cb = r -> reportOffice(r, f);
        if (docx) {
            officeService.exportDocx(b.getContent(), baseDir, mmdc, f.toPath(), cb);
        } else {
            officeService.exportOdt(b.getContent(), baseDir, mmdc, f.toPath(), cb);
        }
    }

    /** A Save dialog defaulting to {@code <base-without-ext>.<ext>}. */
    private java.io.File chooseOfficeDestination(String base, String ext, String filterName) {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle(tr("dialog.officeExport.title"));
        int dot = base == null ? -1 : base.lastIndexOf('.');
        chooser.setInitialFileName((dot > 0 ? base.substring(0, dot) : (base == null ? "document" : base)) + "." + ext);
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(filterName, "*." + ext));
        return chooseDestination.apply(chooser);
    }

    /** Reports an office export result: status + (on failure) an error dialog. */
    private void reportOffice(com.editora.office.OfficeExportService.Result r, java.io.File f) {
        if (r.ok()) {
            host.setStatus(tr("status.office.exported", f.toString()));
        } else {
            String msg = String.valueOf(r.message());
            host.setStatus(tr("status.office.exportFailed", msg));
            Alert err = new Alert(Alert.AlertType.ERROR);
            err.initOwner(host.window());
            err.setTitle(tr("dialog.officeExport.title"));
            err.setHeaderText(tr("status.office.exportFailed", ""));
            err.setContentText(msg);
            err.showAndWait();
        }
    }

    /**
     * Prints the active buffer's source code via the native print dialog. Honors the (shared with PDF)
     * "include line numbers" + "syntax highlighting" settings; always light. Off the FX thread.
     */
    void printCode() {
        EditorBuffer b = host.activeBuffer();
        if (b == null) {
            host.setStatus(tr("status.noFileOpen"));
            return;
        }
        javafx.print.PrinterJob job = javafx.print.PrinterJob.createPrinterJob();
        if (job == null) {
            host.setStatus(tr("status.print.noPrinter"));
            return;
        }
        Settings s = host.settings();
        host.setStatus(tr("status.print.preparing"));
        printService.prepareCode(
                b.getContent(),
                grammarKey(b),
                s.isPdfSyntaxHighlighting(),
                s.isPdfLineNumbers(),
                s.getTabSize(),
                prepared -> openPrintPreview(job, prepared));
    }

    /**
     * Prints the active buffer's rendered preview: a Mermaid {@code .mmd} diagram (via mmdc) or a
     * Markdown document (native nodes, block-aware pagination). No-op for non-previewable buffers.
     */
    void printPreview() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || !b.hasPreview()) {
            host.setStatus(tr("status.print.noPreview"));
            return;
        }
        javafx.print.PrinterJob job = javafx.print.PrinterJob.createPrinterJob();
        if (job == null) {
            host.setStatus(tr("status.print.noPrinter"));
            return;
        }
        host.setStatus(tr("status.print.preparing"));
        java.util.function.Consumer<com.editora.print.PrintService.Prepared> open =
                prepared -> openPrintPreview(job, prepared);
        if (b.isMarkdown()) {
            java.nio.file.Path baseDir =
                    b.getPath() == null ? null : b.getPath().getParent();
            printService.prepareMarkdown(b.getContent(), baseDir, open);
        } else if (b.isDiagram()) { // Mermaid — CLI render
            printService.prepareMermaid(b.getContent(), mermaid.mmdcCommandOrNull(), host.appThemeDark(), open);
        } else if (b.isRenderedDiagram()) { // Graphviz DOT / PlantUML — CLI render to a temp PNG, then paginate
            printDiagramViaImage(b, job);
        } else if (b.isSvg()) { // rasterize the SVG source, paginate as image pages
            byte[] png = com.editora.editor.PreviewImageLoader.svgToPng(
                    b.getContent().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (png == null) {
                openPrintPreview(job, new com.editora.print.PrintService.Prepared(null, tr("status.print.noPreview")));
                return;
            }
            printService.prepareImages(java.util.List.of(png), open);
        } else if (b.isTypst()) { // Typst — CLI render to page PNGs, paginate as image pages
            typst.renderPages(b.getContent(), b.getPath(), pages -> {
                if (pages == null || pages.isEmpty()) {
                    openPrintPreview(
                            job, new com.editora.print.PrintService.Prepared(null, tr("status.print.noPreview")));
                    return;
                }
                printService.prepareImages(pages, open);
            });
        } else { // Markwhen timeline / JSON-YAML-TOML tree / XML tree — snapshot the rendered preview (light)
            java.util.List<byte[]> chunks = b.snapshotPreviewChunks(Themes.lightUserAgentStylesheet());
            if (chunks == null || chunks.isEmpty()) {
                openPrintPreview(job, new com.editora.print.PrintService.Prepared(null, tr("status.print.noPreview")));
                return;
            }
            printService.prepareImages(chunks, open);
        }
    }

    /** Prints a DOT/PlantUML diagram by rendering it to a temporary PNG via its CLI, then paginating the image
     *  (there's no native-vector print path for the diagram tools, unlike Markdown). */
    private void printDiagramViaImage(EditorBuffer b, javafx.print.PrinterJob job) {
        java.nio.file.Path tmp;
        try {
            tmp = java.nio.file.Files.createTempFile("editora-diagram", ".png");
        } catch (java.io.IOException e) {
            openPrintPreview(job, new com.editora.print.PrintService.Prepared(null, e.getMessage()));
            return;
        }
        diagram.exportToPath(b.diagramKind(), b.getContent(), tmp, r -> {
            if (!r.ok()) {
                openPrintPreview(job, new com.editora.print.PrintService.Prepared(null, r.message()));
                return;
            }
            try {
                byte[] png = java.nio.file.Files.readAllBytes(tmp);
                java.nio.file.Files.deleteIfExists(tmp);
                printService.prepareImages(java.util.List.of(png), prepared -> openPrintPreview(job, prepared));
            } catch (java.io.IOException e) {
                openPrintPreview(job, new com.editora.print.PrintService.Prepared(null, e.getMessage()));
            }
        });
    }

    /** Opens the Print Preview window for a prepared document, or reports a preparation failure. */
    private void openPrintPreview(javafx.print.PrinterJob job, com.editora.print.PrintService.Prepared prepared) {
        if (!prepared.ok()) {
            reportPrint(new com.editora.print.PrintService.Result(false, prepared.error()));
            return;
        }
        new PrintPreview(
                        host.window(),
                        job,
                        prepared.paginator(),
                        this::reportPrint,
                        () -> host.setStatus(tr("status.print.printing")),
                        () -> host.setStatus(tr("status.print.cancelled")))
                .show();
    }

    /** Reports a print result: status + (on failure) an error dialog. */
    private void reportPrint(com.editora.print.PrintService.Result r) {
        if (r.ok()) {
            host.setStatus(tr("status.print.done"));
        } else {
            String msg = String.valueOf(r.message());
            host.setStatus(tr("status.print.failed", msg));
            Alert err = new Alert(Alert.AlertType.ERROR);
            err.initOwner(host.window());
            err.setTitle(tr("command.editor.print"));
            err.setHeaderText(tr("status.print.failed", ""));
            err.setContentText(msg);
            err.showAndWait();
        }
    }

    /** Writes {@code csv} text to a user-chosen {@code .csv} file (the Markdown-table → CSV file export). */
    void exportCsvTextToFile(String csv, String base) {
        java.io.File f = chooseOfficeDestination(base, "csv", "CSV");
        if (f == null) {
            return;
        }
        try {
            java.nio.file.Files.writeString(f.toPath(), csv);
            host.setStatus(tr("status.csv.exported", f.getName()));
        } catch (java.io.IOException ex) {
            host.setStatus(tr("status.csv.exportFailed", String.valueOf(ex.getMessage())));
        }
    }
}
