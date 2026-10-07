package com.editora.ui;

import java.io.File;
import java.nio.file.Path;
import java.util.function.Consumer;

import javafx.scene.control.Alert;
import javafx.stage.FileChooser;

import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.editor.TypstImages;
import com.editora.process.ProcessRunner;
import com.editora.typst.TypstService;

import static com.editora.i18n.Messages.tr;

/**
 * Owns the Typst document feature (3-mode preview + export), a feature coordinator alongside
 * {@link DiagramCoordinator}: the {@code typst} service, the detected-tool availability, and the
 * apply/gating/export logic. Reaches the window only through the narrow {@link CoordinatorHost} interface;
 * {@code MainController} keeps one-line delegations. Deliberately its own seam (not a {@code DiagramKind}),
 * because a Typst document is multi-page — see {@code editor/TypstImages}.
 */
final class TypstCoordinator {

    private final CoordinatorHost host;
    private final TypstService service = new TypstService();
    private final java.util.function.UnaryOperator<Path> rootResolver;

    TypstCoordinator(CoordinatorHost host, java.util.function.UnaryOperator<Path> rootResolver) {
        this.host = host;
        this.rootResolver = rootResolver;
    }

    /** Whether {@code file} is a local (default-filesystem) path — a remote/SFTP file can't be a working dir
     *  for the local typst process, so it renders in an isolated temp root instead. */
    private static boolean isLocal(Path file) {
        return file != null && file.getFileSystem() == java.nio.file.FileSystems.getDefault();
    }

    /** The file's own folder (where the throwaway input is written), or null for a remote/untitled file. */
    private static Path fileDirOf(Path file) {
        return isLocal(file) ? file.getParent() : null;
    }

    /** The {@code --root} sandbox for {@code file} (project root for a multi-file doc), or null when remote. */
    private Path rootOf(Path file) {
        return isLocal(file) ? rootResolver.apply(file) : null;
    }

    /** The underlying service — exposed only for the Settings → Typst detection-status row. */
    TypstService service() {
        return service;
    }

    /** Whether the typst CLI was detected on the last probe (cached) — for the install "already?" pre-check. */
    boolean isTypstCliAvailable() {
        return service.cachedAvailable();
    }

    /** Whether the Typst feature is enabled in Settings (default on). */
    boolean isEnabled() {
        return host.settings().isTypstSupport();
    }

    /** Renders a Typst buffer to {@code dest} (format by extension) — the non-interactive path used by the
     *  preview right-click "Export to PDF" (which already chose the file), mirroring
     *  {@code DiagramCoordinator.exportToPath}. */
    void exportToPath(String source, Path file, Path dest, Consumer<ProcessRunner.Result> onResult) {
        service.export(source, dest, fileDirOf(file), rootOf(file), pending -> {
            ProcessRunner.Result result = pending.result();
            if (result.ok()) {
                try {
                    // A PDF is one file — the destination the caller's Save dialog already confirmed.
                    pending.commit();
                } catch (java.io.IOException e) {
                    result = new ProcessRunner.Result(-1, "", String.valueOf(e.getMessage()));
                }
            }
            onResult.accept(result);
        });
    }

    /**
     * Asks before an export replaces page files the Save dialog never mentioned ({@code report-1.png}, … for
     * a multi-page PNG/SVG). Replaceable so a test can answer without a modal dialog.
     */
    private java.util.function.Predicate<java.util.List<Path>> confirmReplace = this::confirmReplaceDialog;

    /** Test seam for {@link #confirmReplace}. */
    void setConfirmReplaceForTest(java.util.function.Predicate<java.util.List<Path>> confirm) {
        this.confirmReplace = confirm;
    }

    private boolean confirmReplaceDialog(java.util.List<Path> existing) {
        Alert confirm = Dialogs.styled(new Alert(
                Alert.AlertType.CONFIRMATION,
                tr("dialog.typstExport.replaceBody", replaceList(existing)),
                javafx.scene.control.ButtonType.OK,
                javafx.scene.control.ButtonType.CANCEL));
        confirm.initOwner(host.window());
        confirm.setTitle(tr("dialog.typstExport.title"));
        confirm.setHeaderText(null);
        // Replacing files nobody named is the destructive answer: Enter must not give it.
        ((javafx.scene.control.Button) confirm.getDialogPane().lookupButton(javafx.scene.control.ButtonType.OK))
                .setDefaultButton(false);
        ((javafx.scene.control.Button) confirm.getDialogPane().lookupButton(javafx.scene.control.ButtonType.CANCEL))
                .setDefaultButton(true);
        return confirm.showAndWait().orElse(javafx.scene.control.ButtonType.CANCEL)
                == javafx.scene.control.ButtonType.OK;
    }

    /** The file names for the replace question, one per line, capped so the dialog stays readable. Pure. */
    static String replaceList(java.util.List<Path> existing) {
        int shown = Math.min(existing.size(), 8);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < shown; i++) {
            sb.append(i == 0 ? "" : "\n").append(existing.get(i).getFileName());
        }
        if (existing.size() > shown) {
            sb.append('\n').append(tr("dialog.typstExport.replaceMore", existing.size() - shown));
        }
        return sb.toString();
    }

    /**
     * Finishes a staged export on the FX thread: asks before replacing page files that already exist, moves
     * the result into place, and reports the files actually written. Package-visible for tests.
     */
    void finishExport(com.editora.typst.TypstRenderer.PendingExport pending) {
        ProcessRunner.Result r = pending.result();
        if (!r.ok()) {
            pending.close();
            String msg = r.message();
            host.setStatus(tr("status.typst.exportFailed", msg));
            Alert err = new Alert(Alert.AlertType.ERROR);
            err.initOwner(host.window());
            err.setTitle(tr("dialog.typstExport.title"));
            err.setHeaderText(tr("status.typst.exportFailed", ""));
            err.setContentText(msg);
            err.showAndWait();
            return;
        }
        if (!pending.ok()) {
            // "exited 0, wrote nothing" is the failure it is, not a success over a missing file
            pending.close();
            host.setStatus(tr("status.typst.exportFailed", tr("status.typst.noOutput")));
            return;
        }
        java.util.List<Path> existing = pending.existingTargets();
        if (!existing.isEmpty() && !confirmReplace.test(existing)) {
            pending.close();
            host.setStatus(tr("status.typst.exportCancelled"));
            return;
        }
        java.util.List<Path> written;
        try {
            written = pending.commit();
        } catch (java.io.IOException e) {
            host.setStatus(tr("status.typst.exportFailed", String.valueOf(e.getMessage())));
            return;
        }
        if (written.size() == 1) {
            host.setStatus(tr("status.typst.exported", written.get(0).toString()));
        } else {
            host.setStatus(tr(
                    "status.typst.exportedPages",
                    written.size(),
                    String.valueOf(written.get(0).toAbsolutePath().getParent())));
        }
    }

    /** Renders the document to per-page PNG bytes (empty on failure) — the print path paginates them. */
    void renderPages(String source, Path file, Consumer<java.util.List<byte[]>> onResult) {
        service.renderPages(source, fileDirOf(file), rootOf(file), onResult);
    }

    /**
     * Reconciles the feature with its setting: pushes the typst command + enabled flag into the render façade
     * and re-renders open previews (so a {@code .typ} buffer flips between rendered document and plain text as
     * the feature toggles). Runs at startup and on every settings apply — mirrors
     * {@code DiagramCoordinator.applySupport}. Tool detection is on-demand (the Settings → Typst status row +
     * the graceful in-preview error when typst is missing), so there's no runtime availability gate here.
     */
    void applySupport() {
        Settings s = host.settings();
        boolean on = s.isTypstSupport();
        service.setPath(s.getTypstPath());
        TypstImages.configure(on, service.command());
        host.forEachBuffer(b -> {
            host.ensurePreviewControls(b);
            if (on && b.isTypst()) {
                host.restoreMarkdownMode(b);
            }
            b.refreshPreview();
        });
    }

    /** {@code typst.export}: save the active Typst document, PDF pre-selected (no-op when off). */
    void export() {
        export("pdf");
    }

    /** {@code typst.exportPng}: export the active Typst document, PNG pre-selected. */
    void exportPng() {
        export("png");
    }

    /** {@code typst.exportSvg}: export the active Typst document, SVG pre-selected. */
    void exportSvg() {
        export("svg");
    }

    /** Save the active Typst document, with {@code preferredExt} (pdf/png/svg) pre-selected in the chooser. */
    private void export(String preferredExt) {
        ifEnabled(() -> {
            EditorBuffer b = host.activeBuffer();
            if (b == null || !b.isTypst()) {
                host.setStatus(tr("status.typst.notTypst"));
                return;
            }
            String source = b.getContent();
            Path file = b.getPath();
            FileChooser chooser = new FileChooser();
            chooser.setTitle(tr("dialog.typstExport.title"));
            String base = host.bufferBaseName(b);
            int dot = base.lastIndexOf('.');
            chooser.setInitialFileName((dot > 0 ? base.substring(0, dot) : base) + "." + preferredExt);
            FileChooser.ExtensionFilter pdf = new FileChooser.ExtensionFilter("PDF", "*.pdf");
            FileChooser.ExtensionFilter png = new FileChooser.ExtensionFilter("PNG", "*.png");
            FileChooser.ExtensionFilter svg = new FileChooser.ExtensionFilter("SVG", "*.svg");
            chooser.getExtensionFilters().addAll(pdf, png, svg);
            chooser.setSelectedExtensionFilter(
                    switch (preferredExt) {
                        case "png" -> png;
                        case "svg" -> svg;
                        default -> pdf;
                    });
            File f = chooser.showSaveDialog(host.window());
            if (f == null) {
                return;
            }
            host.setStatus(tr("status.typst.exporting"));
            // The export is staged first: a PNG/SVG document is one file per page, so the names that get
            // written (report-1.png, …) are not the one the Save dialog asked about — finishExport asks about
            // those before anything is replaced, and reports the files that really exist afterwards.
            service.export(source, f.toPath(), fileDirOf(file), rootOf(file), this::finishExport);
        });
    }

    /** Stops the service's worker (window dispose). */
    void shutdown() {
        service.shutdown();
    }

    /** Runs {@code action} only when the feature is enabled; otherwise reports it (no-op command/key). */
    private void ifEnabled(Runnable action) {
        if (isEnabled()) {
            action.run();
        } else {
            host.setStatus(tr("statusbar.tip.typstDisabled"));
        }
    }
}
