package com.editora.ui;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import javafx.scene.control.Alert;
import javafx.stage.FileChooser;

import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.editor.MermaidImages;
import com.editora.mermaid.MermaidService;
import com.editora.process.ProcessRunner;

import static com.editora.i18n.Messages.tr;

/**
 * Owns the Mermaid feature, extracted from {@code MainController} as a feature coordinator (after
 * {@link LogViewerCoordinator}/{@link HtmlPreviewCoordinator}): the {@code mmdc}/{@code maid} service, the
 * detected-tool availability, and the apply/gating/export logic. It reaches the window only through the
 * narrow {@link Host} interface; {@code MainController} keeps one-line delegations and exposes the few mermaid
 * reads that other features need ({@link #mmdcCommandOrNull()} for PDF/print, {@link #effectiveAutocomplete()}
 * for the completion config).
 */
final class MermaidCoordinator {

    private final CoordinatorHost host;
    private final MermaidService service = new MermaidService();
    private MermaidService.Availability avail = new MermaidService.Availability(false, false);

    MermaidCoordinator(CoordinatorHost host) {
        this.host = host;
    }

    /** The underlying service — exposed only for the Settings → Mermaid detection-status row. */
    MermaidService service() {
        return service;
    }

    /** Whether the Mermaid feature is enabled in Settings (default off). */
    boolean isEnabled() {
        return host.settings().isMermaidSupport();
    }

    /**
     * Reconciles the feature with its setting: pushes the mmdc/maid paths + the preview theme + the enabled
     * flag into the service and the editor preview façade, re-renders open previews so {@code ```mermaid}
     * blocks switch between diagram and code, then detects the tools (cached) and gates linting + autocomplete.
     * Runs at startup and on every settings/theme apply.
     */
    void applySupport() {
        Settings s = host.settings();
        boolean on = s.isMermaidSupport();
        service.setPaths(s.getMmdcPath(), s.getMaidPath());
        MermaidImages.configure(on, service.mmdcCommand(), service.maidCommand(), host.appThemeDark());
        host.forEachBuffer(b -> {
            host.ensurePreviewControls(b);
            if (on && b.isDiagram()) {
                host.restoreMarkdownMode(b);
            }
            b.refreshPreview();
        });
        if (on) {
            service.detect(a -> {
                avail = a;
                gating();
            });
        } else {
            avail = new MermaidService.Availability(false, false);
            gating();
        }
    }

    /** Re-applies the tool-detection-dependent gates: per-buffer maid linting + mermaid autocomplete. */
    private void gating() {
        boolean on = isEnabled();
        host.forEachBuffer(b -> b.setMermaidLintEnabled(on && avail.maid()));
        host.applyAutocomplete();
    }

    /** Wires a freshly opened buffer's live maid validator + initial lint state. */
    void wireBuffer(EditorBuffer buffer) {
        buffer.setMermaidValidator((text, cb) -> service.validate(text, cb));
        buffer.setMermaidLintEnabled(isEnabled() && avail.maid());
    }

    /** Re-applies the lint gate to a buffer (e.g. after a Save As / rename flips it to/from .mmd). */
    void refreshLint(EditorBuffer buffer) {
        buffer.setMermaidLintEnabled(isEnabled() && avail.maid());
    }

    /** Mermaid autocomplete is effective only with the master + own toggle, the feature on, and mmdc detected. */
    boolean effectiveAutocomplete() {
        Settings s = host.settings();
        return s.isAutocomplete() && s.isAutocompleteMermaid() && s.isMermaidSupport() && avail.mmdc();
    }

    /** The mmdc command for rendering a diagram in a PDF/print, or {@code null} when the feature is off. */
    List<String> mmdcCommandOrNull() {
        return isEnabled() ? service.mmdcCommand() : null;
    }

    /** Renders {@code source} to {@code dest} (SVG/PNG/PDF by extension), themed to the app; for PDF export. */
    void exportDiagram(String source, Path dest, Consumer<ProcessRunner.Result> onResult) {
        service.export(source, dest, host.appThemeDark(), onResult);
    }

    /** {@code mermaid.export}: save the active diagram as SVG/PNG/PDF (no-op when the feature is off). */
    void export() {
        ifEnabled(() -> {
            EditorBuffer b = host.activeBuffer();
            if (b == null || !b.isDiagram()) {
                host.setStatus(tr("status.mermaid.notDiagram"));
                return;
            }
            String source = b.getContent();
            FileChooser chooser = new FileChooser();
            chooser.setTitle(tr("dialog.mermaidExport.title"));
            String base = host.bufferBaseName(b);
            int dot = base.lastIndexOf('.');
            chooser.setInitialFileName((dot > 0 ? base.substring(0, dot) : base) + ".svg");
            chooser.getExtensionFilters()
                    .addAll(
                            new FileChooser.ExtensionFilter("SVG", "*.svg"),
                            new FileChooser.ExtensionFilter("PNG", "*.png"),
                            new FileChooser.ExtensionFilter("PDF", "*.pdf"));
            File f = chooser.showSaveDialog(host.window());
            if (f == null) {
                return;
            }
            host.setStatus(tr("status.mermaid.exporting"));
            service.export(source, f.toPath(), host.appThemeDark(), r -> {
                if (r.ok()) {
                    host.setStatus(tr("status.mermaid.exported", f.toString()));
                } else {
                    String msg = r.message();
                    host.setStatus(tr("status.mermaid.exportFailed", msg));
                    Alert err = new Alert(Alert.AlertType.ERROR);
                    err.initOwner(host.window());
                    err.setTitle(tr("dialog.mermaidExport.title"));
                    err.setHeaderText(tr("status.mermaid.exportFailed", ""));
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

    /** Runs {@code action} only when Mermaid is enabled; otherwise reports it (no-op command/key). */
    private void ifEnabled(Runnable action) {
        if (isEnabled()) {
            action.run();
        } else {
            host.setStatus(tr("statusbar.tip.mermaidDisabled"));
        }
    }
}
