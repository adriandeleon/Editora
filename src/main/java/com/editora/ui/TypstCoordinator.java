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

    /** Whether the Typst feature is enabled in Settings (default on). */
    boolean isEnabled() {
        return host.settings().isTypstSupport();
    }

    /** Renders a Typst buffer to {@code dest} (format by extension) — the non-interactive path used by the
     *  preview right-click "Export to PDF" (which already chose the file), mirroring
     *  {@code DiagramCoordinator.exportToPath}. */
    void exportToPath(String source, Path file, Path dest, Consumer<ProcessRunner.Result> onResult) {
        service.export(source, dest, fileDirOf(file), rootOf(file), onResult);
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

    /** {@code typst.export}: save the active Typst document as PDF/PNG/SVG (no-op when off). */
    void export() {
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
            chooser.setInitialFileName((dot > 0 ? base.substring(0, dot) : base) + ".pdf");
            chooser.getExtensionFilters()
                    .addAll(
                            new FileChooser.ExtensionFilter("PDF", "*.pdf"),
                            new FileChooser.ExtensionFilter("PNG", "*.png"),
                            new FileChooser.ExtensionFilter("SVG", "*.svg"));
            File f = chooser.showSaveDialog(host.window());
            if (f == null) {
                return;
            }
            host.setStatus(tr("status.typst.exporting"));
            service.export(source, f.toPath(), fileDirOf(file), rootOf(file), r -> {
                if (r.ok()) {
                    host.setStatus(tr("status.typst.exported", f.toString()));
                } else {
                    String msg = r.message();
                    host.setStatus(tr("status.typst.exportFailed", msg));
                    Alert err = new Alert(Alert.AlertType.ERROR);
                    err.initOwner(host.window());
                    err.setTitle(tr("dialog.typstExport.title"));
                    err.setHeaderText(tr("status.typst.exportFailed", ""));
                    err.setContentText(msg);
                    err.showAndWait();
                }
            });
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
