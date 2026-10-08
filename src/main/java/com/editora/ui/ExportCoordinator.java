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
    /** Shows a Save dialog and returns its answer; a test of a whole window puts its own in place. */
    java.util.function.Function<javafx.stage.FileChooser, java.io.File> chooseDestination;

    private final com.editora.pdf.PdfExportService pdfService = new com.editora.pdf.PdfExportService();
    private final com.editora.office.OfficeExportService officeService = new com.editora.office.OfficeExportService();
    private final com.editora.print.PrintService printService = new com.editora.print.PrintService();
    /** Where this window last exported to: the Save dialog starts there for a document that has no folder. */
    private java.io.File lastExportDirectory;
    /** Asks before an export replaces {@code file}, when the Save dialog did not ask (tests replace it). */
    java.util.function.Predicate<java.io.File> confirmReplace = this::confirmReplace;

    /** A print preparation started by this window has not reached {@link #openPrintPreview} yet. */
    private boolean printPreparing;
    /** The Print Preview open for this window, or {@code null}. */
    private PrintPreview openPreview;
    /** Creates the printer job behind a preview ({@code null}: no printer). Tests supply a fake — never a real job. */
    java.util.function.Supplier<PrintPreview.Job> printJobs = () -> {
        javafx.print.PrinterJob job = javafx.print.PrinterJob.createPrinterJob();
        return job == null ? null : PrintPreview.Job.of(job);
    };
    /** Where a print result goes; tests replace it so a failure does not open a modal alert. */
    Consumer<com.editora.print.PrintService.Result> printReporter = r -> reportPrint(r);

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
        java.io.File f = choosePdfDestination(baseName, host.activeBuffer());
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
        if (printBusy()) {
            return;
        }
        org.commonmark.node.Node table = CsvTableDocument.fromCsv(csvText);
        if (table == null) {
            host.setStatus(tr("status.csv.empty"));
            return;
        }
        PrintPreview.Job job = printJobs.get();
        if (job == null) {
            host.setStatus(tr("status.print.noPrinter"));
            return;
        }
        preparePrint(() -> printService.prepareDocument(table, null, prepared -> openPrintPreview(job, prepared)));
    }

    /**
     * Exports the complete Project Map layout—not merely the visible viewport—to a PDF. The map is rendered
     * only after a destination was chosen, on a landscape page when it is wider than tall.
     */
    void exportProjectMapPdf(ProjectMapOutput output, String baseName) {
        java.io.File file = choosePdfDestination(baseName, null);
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
        if (printBusy()) {
            return;
        }
        PrintPreview.Job job = printJobs.get();
        if (job == null) {
            host.setStatus(tr("status.print.noPrinter"));
            return;
        }
        if (output.landscape()) {
            job.useLandscape();
        }
        javafx.print.PageLayout first = job.layout();
        ProjectMapOutput.Rendered preview = output.render(first.getPrintableWidth(), first.getPrintableHeight());
        if (preview == null) {
            job.cancel();
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
        java.io.File f = chooseOfficeDestination(baseName, ext, filter, host.activeBuffer());
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
        java.io.File f = choosePdfDestination(bufferBaseName(b), b);
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
     * native vector PDF, or a Markdown document via the native PDF writer; a CSV grid goes out as the table
     * {@code csv.exportPdf} writes. A buffer whose preview cannot be put on a page is told so here, before
     * the Save dialog — not by an error dialog after a destination was chosen.
     */
    void exportPreviewPdf() {
        EditorBuffer b = host.activeBuffer();
        if (b != null && b.hasCsvPreview()) {
            csvExportPdf(b.getContent(), bufferBaseName(b));
            return;
        }
        if (b == null || !b.hasExportablePreview()) {
            host.setStatus(tr("status.pdf.noPreview"));
            return;
        }
        if (previewUnparsable(b)) {
            host.setStatus(tr("status.pdf.cannotExportUnparsed"));
            return;
        }
        java.io.File f = choosePdfDestination(bufferBaseName(b), b);
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

    /**
     * Whether {@code b}'s preview is a JSON/YAML/TOML or XML tree whose source does not parse, so that
     * {@link EditorBuffer#snapshotPreviewChunks} would have nothing to draw. Follows the order of the export
     * branches and of that method: only the snapshot previews are asked, and a workflow or a pom summary is
     * drawn whatever the parse says. Parses the text, so it is asked when a command runs — never to gate a menu.
     */
    static boolean previewUnparsable(EditorBuffer b) {
        if (b.isTypst() || b.isMarkdown() || b.isDiagram() || b.isRenderedDiagram() || b.isSvg()) {
            return false; // rendered from the source by their own pipeline, not from a snapshot
        }
        if (b.hasGithubActionsPreview()) {
            return false;
        }
        String text = b.getArea().getText();
        if (b.isStructured()) {
            return !com.editora.structured.StructuredParser.parse(text, b.structuredFormat())
                    .ok();
        }
        return !b.hasPomPreview()
                && b.isXml()
                && !com.editora.structured.XmlParser.parse(text).ok();
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
        } else if (b.isSvg()) { // rasterize the SVG source (on the export thread) and embed it as a PDF page
            pdfService.exportSvg(
                    b.getContent().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    tr("status.pdf.noPreview"),
                    pageSize,
                    out,
                    report);
        } else { // Markwhen timeline / JSON-YAML-TOML tree / XML tree — snapshot the rendered preview (light)
            b.snapshotPreviewChunks(Themes.lightUserAgentStylesheet(), this::snapshotProgress, snap -> {
                if (snap == null || snap.images().isEmpty()) {
                    report.accept(new com.editora.pdf.PdfExportService.Result(false, tr("status.pdf.noPreview")));
                    return;
                }
                host.setStatus(tr("status.pdf.exporting"));
                pdfService.exportPageImages(snap.images(), pageSize, out, r -> {
                    report.accept(r);
                    if (r.ok() && snap.truncated()) { // after the plain "exported": the PDF is not the whole tree
                        host.setStatus(tr("status.pdf.exportedTruncated", snap.shownRows(), snap.totalRows()));
                    }
                });
            });
        }
    }

    /** Status while a tree preview is snapshotted chunk by chunk (it takes several pulses of the FX thread). */
    private void snapshotProgress(int done, int total) {
        if (total > 1) {
            host.setStatus(tr("status.preview.snapshotting", Math.round(100f * done / total)));
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
        java.io.File f = chooseHtmlDestination(bufferBaseName(b), b);
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
    private java.io.File chooseHtmlDestination(String base, EditorBuffer source) {
        return chooseExportDestination(tr("dialog.htmlExport.title"), base, "document", "html", "HTML", source);
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
    private java.io.File choosePdfDestination(String base, EditorBuffer source) {
        return chooseExportDestination(tr("dialog.pdfExport.title"), base, "document", "pdf", "PDF", source);
    }

    /**
     * The one Save dialog of every export: named {@code <base-without-ext>.<ext>}, opening beside
     * {@code source} when that is a saved local file and otherwise where this window last exported to (kept
     * for the session only). Returns the chosen file with {@code .<ext>} added when the typed name lacks it —
     * the GTK dialog does not add one, and the export was then written under a name nothing opens — or null
     * when the dialog, or the question about replacing a file, was cancelled.
     *
     * @param title        the dialog title, or null for the platform's
     * @param fallbackBase the file name when {@code base} is null
     * @param source       the buffer being exported, or null when the export is not of a file
     */
    private java.io.File chooseExportDestination(
            String title, String base, String fallbackBase, String ext, String filterName, EditorBuffer source) {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        if (title != null) {
            chooser.setTitle(title);
        }
        int dot = base == null ? -1 : base.lastIndexOf('.');
        chooser.setInitialFileName(
                (dot > 0 ? base.substring(0, dot) : (base == null ? fallbackBase : base)) + "." + ext);
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(filterName, "*." + ext));
        java.io.File directory = lastExportDirectory;
        if (source != null
                && source.getPath() != null
                && source.getPath().getParent() != null
                && host.isLocalBuffer(source)) {
            directory = source.getPath().getParent().toFile();
        }
        // A folder that has since been deleted makes the native dialog throw rather than fall back.
        if (directory != null && directory.isDirectory()) {
            chooser.setInitialDirectory(directory);
        }
        java.io.File chosen = chooseDestination.apply(chooser);
        if (chosen == null) {
            return null;
        }
        java.io.File target = withExtension(chosen, ext);
        // The Save dialog asked about replacing the name as typed. The name with the extension added is a
        // different file, which nobody has agreed to replace yet.
        if (!target.equals(chosen) && target.exists() && !confirmReplace.test(target)) {
            return null;
        }
        if (target.getAbsoluteFile().getParentFile() != null) {
            lastExportDirectory = target.getAbsoluteFile().getParentFile();
        }
        return target;
    }

    /** {@code file}, with {@code .<ext>} appended unless its name already ends in it (in either case). */
    static java.io.File withExtension(java.io.File file, String ext) {
        String suffix = "." + ext;
        String name = file.getName();
        boolean has = name.length() > suffix.length()
                && name.regionMatches(true, name.length() - suffix.length(), suffix, 0, suffix.length());
        return has ? file : new java.io.File(file.getParentFile(), name + suffix);
    }

    /** Asks whether an export may replace {@code file}; the wording is Save As's. */
    private boolean confirmReplace(java.io.File file) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(host.window());
        alert.setTitle(tr("dialog.saveAs.overwrite.title"));
        alert.setHeaderText(tr("dialog.saveAs.overwrite.header", file.getName()));
        alert.setContentText(tr("dialog.saveAs.overwrite.content"));
        return Dialogs.styled(alert)
                .showAndWait()
                .filter(javafx.scene.control.ButtonType.OK::equals)
                .isPresent();
    }

    /** Exports the active Markwhen buffer's parsed timeline to a JSON file (preview menu + palette). */
    void exportMarkwhenJson() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || !b.isMarkwhen()) {
            host.setStatus(tr("status.markwhen.notMarkwhen"));
            return;
        }
        java.io.File f = chooseExportDestination(null, bufferBaseName(b), "timeline", "json", "JSON", b);
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
            String msg = failureDetail(r.message());
            host.setStatus(tr("status.pdf.exportFailed", msg));
            failureAlert(tr("dialog.pdfExport.title"), tr("dialog.pdfExport.failed"), msg)
                    .showAndWait();
        }
    }

    /** What a failure dialog and the status bar say went wrong: the message, or a sentence when there is none. */
    static String failureDetail(String message) {
        return message == null || message.isBlank() ? tr("dialog.export.noDetails") : message;
    }

    /** The error dialog of a failed export or print: what failed as the header, why as the content. */
    Alert failureAlert(String title, String header, String detail) {
        Alert err = new Alert(Alert.AlertType.ERROR);
        err.initOwner(host.window());
        err.setTitle(title);
        err.setHeaderText(header);
        err.setContentText(detail);
        return Dialogs.styled(err);
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
        java.io.File f = chooseOfficeDestination(bufferBaseName(b), ext, filter, b);
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
    private java.io.File chooseOfficeDestination(String base, String ext, String filterName, EditorBuffer source) {
        return chooseExportDestination(tr("dialog.officeExport.title"), base, "document", ext, filterName, source);
    }

    /** Reports an office export result: status + (on failure) an error dialog. */
    private void reportOffice(com.editora.office.OfficeExportService.Result r, java.io.File f) {
        if (r.ok()) {
            host.setStatus(tr("status.office.exported", f.toString()));
        } else {
            String msg = failureDetail(r.message());
            host.setStatus(tr("status.office.exportFailed", msg));
            failureAlert(tr("dialog.officeExport.title"), tr("dialog.officeExport.failed"), msg)
                    .showAndWait();
        }
    }

    /**
     * Prints the active buffer's source code via the native print dialog. Honors the (shared with PDF)
     * "include line numbers" + "syntax highlighting" settings; always light. Off the FX thread.
     */
    void printCode() {
        if (printBusy()) {
            return;
        }
        EditorBuffer b = host.activeBuffer();
        if (b == null) {
            host.setStatus(tr("status.noFileOpen"));
            return;
        }
        PrintPreview.Job job = printJobs.get();
        if (job == null) {
            host.setStatus(tr("status.print.noPrinter"));
            return;
        }
        Settings s = host.settings();
        preparePrint(() -> printService.prepareCode(
                b.getContent(),
                grammarKey(b),
                s.isPdfSyntaxHighlighting(),
                s.isPdfLineNumbers(),
                s.getTabSize(),
                prepared -> openPrintPreview(job, prepared)));
    }

    /**
     * Whether this window is already printing — a preparation is in flight, or its Print Preview is open
     * (which is then brought forward). A print request that finds it busy is dropped: running the command
     * twice used to stack two modal previews, each with its own printer job.
     */
    private boolean printBusy() {
        if (openPreview != null) {
            openPreview.toFront();
            return true;
        }
        return printPreparing;
    }

    /**
     * Starts an off-thread preparation that ends in {@link #openPrintPreview}, which is where the in-flight
     * mark is cleared — on success, on a preparation error and on a failure to open alike.
     */
    private void preparePrint(Runnable start) {
        printPreparing = true;
        host.setStatus(tr("status.print.preparing"));
        try {
            start.run();
        } catch (RuntimeException | Error e) {
            printPreparing = false; // never submitted: nothing will call back to clear it
            throw e;
        }
    }

    /**
     * Prints the active buffer's rendered preview: a Mermaid {@code .mmd} diagram (via mmdc) or a
     * Markdown document (native nodes, block-aware pagination); a CSV grid prints as the table
     * {@code csv.print} does. A buffer whose preview cannot be put on a page is told so before a job is made.
     */
    void printPreview() {
        if (printBusy()) {
            return;
        }
        EditorBuffer b = host.activeBuffer();
        if (b != null && b.hasCsvPreview()) {
            csvPrint(b.getContent());
            return;
        }
        if (b == null || !b.hasExportablePreview()) {
            host.setStatus(tr("status.print.noPreview"));
            return;
        }
        if (previewUnparsable(b)) {
            host.setStatus(tr("status.print.cannotPrintUnparsed"));
            return;
        }
        javafx.print.PrinterJob job = javafx.print.PrinterJob.createPrinterJob();
        if (job == null) {
            host.setStatus(tr("status.print.noPrinter"));
            return;
        }
        preparePrint(() -> preparePreviewPrint(b, job));
    }

    /** Starts the preparation for {@code b}'s kind of preview; every branch ends in {@link #openPrintPreview}. */
    private void preparePreviewPrint(EditorBuffer b, javafx.print.PrinterJob job) {
        java.util.function.Consumer<com.editora.print.PrintService.Prepared> open =
                prepared -> openPrintPreview(job, prepared);
        if (b.isMarkdown()) {
            java.nio.file.Path baseDir =
                    b.getPath() == null ? null : b.getPath().getParent();
            printService.prepareMarkdown(b.getContent(), baseDir, open);
        } else if (b.isDiagram()) { // Mermaid — CLI render
            // Light, like every other printed kind: the app theme must not reach white paper.
            printService.prepareMermaid(b.getContent(), mermaid.mmdcCommandOrNull(), false, open);
        } else if (b.isRenderedDiagram()) { // Graphviz DOT / PlantUML — CLI render to a temp PNG, then paginate
            printDiagramViaImage(b, job);
        } else if (b.isSvg()) { // rasterize the SVG source (on the prepare thread), paginate as image pages
            printService.prepareSvg(
                    b.getContent().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    tr("status.print.noPreview"),
                    open);
        } else if (b.isTypst()) { // Typst — CLI render to page PNGs, paginate as image pages
            typst.renderPages(b.getContent(), b.getPath(), pages -> {
                if (pages == null || pages.isEmpty()) {
                    openPrintPreview(
                            job, new com.editora.print.PrintService.Prepared(null, tr("status.print.noPreview")));
                    return;
                }
                // A page PNG is rendered at renderPpi: that many pixels per inch of paper, 72 points.
                double density = com.editora.typst.TypstRenderer.renderPpi() / 72.0;
                printService.preparePageImages(
                        pages.stream()
                                .filter(java.util.Objects::nonNull)
                                .map(png -> com.editora.pdf.PageImage.of(png, density))
                                .toList(),
                        open);
            });
        } else { // Markwhen timeline / JSON-YAML-TOML tree / XML tree — snapshot the rendered preview (light)
            b.snapshotPreviewChunks(Themes.lightUserAgentStylesheet(), this::snapshotProgress, snap -> {
                if (snap == null || snap.images().isEmpty()) {
                    openPrintPreview(
                            job, new com.editora.print.PrintService.Prepared(null, tr("status.print.noPreview")));
                    return;
                }
                host.setStatus(tr("status.print.preparing"));
                printService.preparePageImages(snap.images(), prepared -> {
                    open.accept(prepared);
                    if (snap.truncated() && openPreview != null) { // the last page says so too
                        host.setStatus(tr("status.print.truncated", snap.shownRows(), snap.totalRows()));
                    }
                });
            });
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
        try {
            diagram.exportToPath(
                    b.diagramKind(),
                    b.getContent(),
                    tmp,
                    false,
                    r -> { // light: this is for paper
                        byte[] png = null;
                        String error = r.message();
                        try {
                            png = takeRenderedPng(tmp, r.ok());
                        } catch (java.io.IOException e) {
                            error = e.getMessage() == null ? e.toString() : e.getMessage();
                        }
                        if (png == null) {
                            openPrintPreview(job, new com.editora.print.PrintService.Prepared(null, error));
                            return;
                        }
                        printService.prepareImages(java.util.List.of(png), prepared -> openPrintPreview(job, prepared));
                    });
        } catch (RuntimeException | Error e) {
            deleteQuietly(tmp); // the render never started: its callback will not run
            throw e;
        }
    }

    /**
     * The PNG a diagram render left at {@code tmp} — {@code null} when it did not render — deleting the
     * temp file on every path: a failed render and a failed read leave nothing behind either.
     */
    static byte[] takeRenderedPng(java.nio.file.Path tmp, boolean rendered) throws java.io.IOException {
        try {
            return rendered ? java.nio.file.Files.readAllBytes(tmp) : null;
        } finally {
            deleteQuietly(tmp);
        }
    }

    private static void deleteQuietly(java.nio.file.Path file) {
        try {
            java.nio.file.Files.deleteIfExists(file);
        } catch (java.io.IOException | RuntimeException e) {
            // a temp file the system will clear; nothing the user can act on
        }
    }

    /** Opens the Print Preview window for a prepared document, or reports a preparation failure. */
    private void openPrintPreview(javafx.print.PrinterJob job, com.editora.print.PrintService.Prepared prepared) {
        openPrintPreview(PrintPreview.Job.of(job), prepared);
    }

    /**
     * {@link #openPrintPreview(javafx.print.PrinterJob, com.editora.print.PrintService.Prepared)} on the
     * preview's own job type. Every way out clears the busy state: a preparation error, a failure to
     * paginate or open (any {@code Throwable} — the pagination runs the whole layout engine here, and an
     * escaped error used to leave "Preparing print preview…" in the status bar with no dialog), and the
     * preview's result and cancel callbacks.
     */
    void openPrintPreview(PrintPreview.Job job, com.editora.print.PrintService.Prepared prepared) {
        printPreparing = false;
        if (openPreview != null) { // a request that was already on its way when the first preview opened
            openPreview.toFront();
            return;
        }
        if (!prepared.ok()) {
            printReporter.accept(new com.editora.print.PrintService.Result(false, prepared.error()));
            return;
        }
        try {
            PrintPreview preview = new PrintPreview(
                    host.window(),
                    job,
                    prepared.paginator(),
                    result -> {
                        openPreview = null;
                        printReporter.accept(result);
                    },
                    () -> host.setStatus(tr("status.print.printing")),
                    () -> {
                        openPreview = null;
                        host.setStatus(tr("status.print.cancelled"));
                    });
            openPreview = preview;
            preview.show();
        } catch (Throwable t) {
            openPreview = null;
            printReporter.accept(new com.editora.print.PrintService.Result(
                    false, t.getMessage() == null ? t.toString() : t.getMessage()));
        }
    }

    /** Reports a print result: status + (on failure) an error dialog. */
    private void reportPrint(com.editora.print.PrintService.Result r) {
        if (r.ok()) {
            host.setStatus(tr("status.print.done"));
        } else {
            String msg = failureDetail(r.message());
            host.setStatus(tr("status.print.failed", msg));
            failureAlert(tr("dialog.print.title"), tr("dialog.print.failed"), msg)
                    .showAndWait();
        }
    }

    /** Writes {@code csv} text to a user-chosen {@code .csv} file (the Markdown-table → CSV file export). */
    void exportCsvTextToFile(String csv, String base) {
        java.io.File f = chooseOfficeDestination(base, "csv", "CSV", host.activeBuffer());
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
