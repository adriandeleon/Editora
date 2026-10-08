package com.editora.ui;

import java.nio.file.Path;
import java.util.function.Consumer;

import javafx.scene.control.Alert;

import com.editora.command.Command;
import com.editora.command.CommandRegistry;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.markdown.CsvTableDocument;

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

    /**
     * Exports a CSV as a PDF through the table renderer of the Markdown → PDF pipeline (the grid's right-click
     * menu). The table is built from the parsed rows, not from Markdown text, so cells are never re-parsed as
     * markup and the columns are the ones the grid shows (see {@link CsvTableDocument}).
     */
    void csvExportPdf(String csvText, String baseName) {
        org.commonmark.node.Node table = CsvTableDocument.fromCsv(csvText);
        if (table == null) {
            host.setStatus(tr("status.csv.empty"));
            return;
        }
        java.io.File f = choosePdfDestination(baseName);
        if (f == null) {
            return;
        }
        host.setStatus(tr("status.pdf.exporting"));
        String pageSize = host.settings().getPdfPageSize();
        stagedPdf(f, (out, report) -> pdfService.exportDocument(table, pageSize, out, report));
    }

    /**
     * Runs an asynchronous export against a staging file beside {@code f} and moves the result over
     * {@code f} only when the export succeeded (see {@link com.editora.io.StagedExport}). The Save dialog has
     * usually just confirmed replacing {@code f}; written directly, a failure partway — a full disk, a tool
     * that exits with an error — left that file truncated, with neither the old export nor the new one.
     *
     * @param export  starts the export to the given path and reports to the given callback
     * @param ok      whether a result is a success
     * @param failure builds a failed result from a message (the staging or the final move failed)
     * @param report  receives the final result, as it did when the export wrote to {@code f} itself
     */
    <R> void staged(
            java.io.File f,
            java.util.function.BiConsumer<java.nio.file.Path, java.util.function.Consumer<R>> export,
            java.util.function.Predicate<R> ok,
            java.util.function.Function<String, R> failure,
            java.util.function.Consumer<R> report) {
        com.editora.io.StagedExport stage;
        try {
            stage = com.editora.io.StagedExport.begin(f.toPath());
        } catch (java.io.IOException e) {
            report.accept(failure.apply(e.getMessage() == null ? e.toString() : e.getMessage()));
            return;
        }
        try {
            export.accept(stage.path(), result -> {
                R outcome = result;
                if (ok.test(result)) {
                    try {
                        stage.commit();
                    } catch (java.io.IOException e) {
                        outcome = failure.apply(e.getMessage() == null ? e.toString() : e.getMessage());
                    }
                } else {
                    stage.close();
                }
                report.accept(outcome);
            });
        } catch (RuntimeException e) {
            stage.close();
            throw e;
        }
    }

    /** {@link #staged} for the PDF service: reports through {@link #reportPdf}. */
    private void stagedPdf(
            java.io.File f,
            java.util.function.BiConsumer<
                            java.nio.file.Path, java.util.function.Consumer<com.editora.pdf.PdfExportService.Result>>
                    export) {
        this.<com.editora.pdf.PdfExportService.Result>staged(
                f,
                export,
                com.editora.pdf.PdfExportService.Result::ok,
                message -> new com.editora.pdf.PdfExportService.Result(false, message),
                r -> reportPdf(r, f));
    }

    /** {@link #staged} for the office service: reports through {@link #reportOffice}. */
    private void stagedOffice(
            java.io.File f,
            java.util.function.BiConsumer<
                            java.nio.file.Path,
                            java.util.function.Consumer<com.editora.office.OfficeExportService.Result>>
                    export) {
        this.<com.editora.office.OfficeExportService.Result>staged(
                f,
                export,
                com.editora.office.OfficeExportService.Result::ok,
                message -> new com.editora.office.OfficeExportService.Result(false, message),
                r -> reportOffice(r, f));
    }

    /** Opens the print preview for a CSV through the same directly-built table (see {@link #csvExportPdf}). */
    void csvPrint(String csvText) {
        org.commonmark.node.Node table = CsvTableDocument.fromCsv(csvText);
        if (table == null) {
            host.setStatus(tr("status.csv.empty"));
            return;
        }
        javafx.print.PrinterJob job = javafx.print.PrinterJob.createPrinterJob();
        if (job == null) {
            host.setStatus(tr("status.print.noPrinter"));
            return;
        }
        host.setStatus(tr("status.print.preparing"));
        printService.prepareDocument(table, null, prepared -> openPrintPreview(job, prepared));
    }

    /**
     * Exports the complete Project Map layout—not merely the visible viewport—to a PDF. The map is rendered
     * only after a destination was chosen, on a landscape page when it is wider than tall.
     */
    void exportProjectMapPdf(ProjectMapOutput output, String baseName) {
        java.io.File file = choosePdfDestination(baseName);
        if (file == null) {
            return;
        }
        String pageSize = host.settings().getPdfPageSize();
        boolean landscape = output.landscape();
        double[] printable = com.editora.pdf.ImagePdfWriter.printableSize(pageSize, landscape);
        ProjectMapOutput.Rendered rendered = output.render(printable[0], printable[1]);
        if (rendered == null) {
            host.setStatus(tr("status.projectMap.outputEmpty"));
            return;
        }
        host.setStatus(tr("status.pdf.exporting"));
        String note = projectMapOutputNote(rendered.plan());
        this.<com.editora.pdf.PdfExportService.Result>staged(
                file,
                (out, report) -> pdfService.exportFxPages(
                        rendered.pages(), rendered.pointsPerPixel(), pageSize, landscape, out, report),
                com.editora.pdf.PdfExportService.Result::ok,
                message -> new com.editora.pdf.PdfExportService.Result(false, message),
                result -> {
                    reportPdf(result, file);
                    if (result.ok() && note != null) {
                        host.setStatus(note); // after "exported": how the map was fitted is the part to act on
                    }
                });
    }

    /**
     * Opens the normal Print Preview flow for the complete Project Map layout. The map is rendered for the
     * page layout in use, and again if the print dialog changes it.
     */
    void printProjectMap(ProjectMapOutput output) {
        javafx.print.PrinterJob job = javafx.print.PrinterJob.createPrinterJob();
        if (job == null) {
            host.setStatus(tr("status.print.noPrinter"));
            return;
        }
        if (output.landscape()) {
            try {
                javafx.print.PageLayout current = job.getJobSettings().getPageLayout();
                job.getJobSettings()
                        .setPageLayout(job.getPrinter()
                                .createPageLayout(
                                        current.getPaper(),
                                        javafx.print.PageOrientation.LANDSCAPE,
                                        javafx.print.Printer.MarginType.DEFAULT));
            } catch (RuntimeException unsupported) {
                // The printer offers no landscape layout for this paper: keep its default.
            }
        }
        javafx.print.PageLayout first = job.getJobSettings().getPageLayout();
        ProjectMapOutput.Rendered preview = output.render(first.getPrintableWidth(), first.getPrintableHeight());
        if (preview == null) {
            job.cancelJob();
            host.setStatus(tr("status.projectMap.outputEmpty"));
            return;
        }
        String note = projectMapOutputNote(preview.plan());
        host.setStatus(note == null ? tr("status.print.preparing") : note);
        openPrintPreview(
                job, new com.editora.print.PrintService.Prepared(projectMapPaginator(output, first, preview), null));
    }

    /** Reuses the render made for {@code first}; any other layout (chosen in the print dialog) renders again. */
    private static com.editora.print.PrintService.Paginator projectMapPaginator(
            ProjectMapOutput output, javafx.print.PageLayout first, ProjectMapOutput.Rendered preview) {
        return layout -> {
            ProjectMapOutput.Rendered rendered = layout.getPrintableWidth() == first.getPrintableWidth()
                            && layout.getPrintableHeight() == first.getPrintableHeight()
                    ? preview
                    : output.render(layout.getPrintableWidth(), layout.getPrintableHeight());
            if (rendered == null) {
                rendered = preview;
            }
            return com.editora.print.PrintService.pagedImages(rendered.pages(), rendered.pointsPerPixel(), layout);
        };
    }

    /** What the user should know about how the map met the page, or null when it simply fitted. Pure. */
    static String projectMapOutputNote(ProjectMapOutputPlan.Plan plan) {
        if (plan.detailReduced()) {
            return tr("status.projectMap.outputCannotKeepDetail", plan.pages().size());
        }
        if (plan.tiled()) {
            return tr("status.projectMap.outputTiled", plan.pages().size(), plan.columns(), plan.rows());
        }
        if (plan.scaledDown()) {
            return tr("status.projectMap.outputScaled", (int) Math.round(plan.paperScale() * 100));
        }
        return null;
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
        stagedOffice(f, (out, cb) -> {
            if (xlsx) {
                officeService.exportXlsx(rows, hasHeader, out, cb);
            } else {
                officeService.exportOds(rows, hasHeader, out, cb);
            }
        });
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
        String content = b.getContent();
        String grammar = grammarKey(b);
        stagedPdf(
                f,
                (out, report) -> pdfService.exportCode(
                        content,
                        grammar,
                        s.isPdfSyntaxHighlighting(),
                        s.isPdfLineNumbers(),
                        s.getTabSize(),
                        s.getPdfPageSize(),
                        out,
                        report));
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
        if (b.isTypst()) { // Typst — native CLI render to a (multi-page) PDF; TypstRenderer stages it itself
            typst.exportToPath(
                    b.getContent(),
                    b.getPath(),
                    f.toPath(),
                    r -> reportPdf(new com.editora.pdf.PdfExportService.Result(r.ok(), r.message()), f));
            return;
        }
        stagedPdf(f, (out, report) -> exportPreviewPdfTo(b, pageSize, out, report));
    }

    /** Renders {@code b}'s preview as a PDF at {@code out} (a staging path — see {@link #stagedPdf}). */
    private void exportPreviewPdfTo(
            EditorBuffer b,
            String pageSize,
            java.nio.file.Path out,
            java.util.function.Consumer<com.editora.pdf.PdfExportService.Result> report) {
        if (b.isMarkdown()) {
            java.nio.file.Path baseDir =
                    b.getPath() == null ? null : b.getPath().getParent();
            pdfService.exportMarkdown(b.getContent(), baseDir, pageSize, mermaid.mmdcCommandOrNull(), out, report);
        } else if (b.isDiagram()) { // Mermaid (.mmd) — CLI render to PDF
            mermaid.exportDiagram(
                    b.getContent(),
                    out,
                    r -> report.accept(new com.editora.pdf.PdfExportService.Result(r.ok(), r.message())));
        } else if (b.isRenderedDiagram()) { // Graphviz DOT / PlantUML — CLI render to PDF
            diagram.exportToPath(
                    b.diagramKind(),
                    b.getContent(),
                    out,
                    r -> report.accept(new com.editora.pdf.PdfExportService.Result(r.ok(), r.message())));
        } else if (b.isSvg()) { // rasterize the SVG source and embed it as a PDF page
            byte[] png = com.editora.editor.PreviewImageLoader.svgToPng(
                    b.getContent().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (png == null) {
                report.accept(new com.editora.pdf.PdfExportService.Result(false, tr("status.pdf.noPreview")));
                return;
            }
            pdfService.exportImages(java.util.List.of(png), pageSize, out, report);
        } else { // Markwhen timeline / JSON-YAML-TOML tree / XML tree — snapshot the rendered preview (light)
            java.util.List<byte[]> chunks = b.snapshotPreviewChunks(Themes.lightUserAgentStylesheet());
            if (chunks == null || chunks.isEmpty()) {
                report.accept(new com.editora.pdf.PdfExportService.Result(false, tr("status.pdf.noPreview")));
                return;
            }
            pdfService.exportImages(chunks, pageSize, out, report);
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
            com.editora.io.StagedExport.writeString(f.toPath(), html);
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
            com.editora.io.StagedExport.writeString(f.toPath(), json);
            host.setStatus(tr("status.markwhen.jsonExported", f.getName()));
        } catch (java.io.IOException e) {
            host.setStatus(tr("status.markwhen.exportFailed", e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    /** Reports a PDF export result: status + (on failure) an error dialog. */
    private void reportPdf(com.editora.pdf.PdfExportService.Result r, java.io.File f) {
        if (r.ok()) {
            // Characters no installed font could draw were written as "?": say so rather than a bare "Exported".
            host.setStatus(
                    r.unrendered() > 0
                            ? tr("status.pdf.exportedUnrendered", f.toString(), r.unrendered())
                            : tr("status.pdf.exported", f.toString()));
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
        String markdown = b.getContent();
        stagedOffice(f, (out, cb) -> {
            if (docx) {
                officeService.exportDocx(markdown, baseDir, mmdc, out, cb);
            } else {
                officeService.exportOdt(markdown, baseDir, mmdc, out, cb);
            }
        });
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
            com.editora.io.StagedExport.writeString(f.toPath(), csv);
            host.setStatus(tr("status.csv.exported", f.getName()));
        } catch (java.io.IOException ex) {
            host.setStatus(tr("status.csv.exportFailed", String.valueOf(ex.getMessage())));
        }
    }
}
