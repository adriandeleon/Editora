package com.editora.ui;

import java.io.File;

import javafx.scene.control.Alert;
import javafx.stage.FileChooser;

import com.editora.config.Settings;
import com.editora.diagram.DiagramKind;
import com.editora.diagram.DiagramService;
import com.editora.editor.DiagramImages;
import com.editora.editor.EditorBuffer;

import static com.editora.i18n.Messages.tr;

/**
 * Owns the diagram-as-code feature (Graphviz DOT + PlantUML), a feature coordinator alongside
 * {@link MermaidCoordinator}: the {@code dot}/{@code plantuml} service, the detected-tool availability, and
 * the apply/gating/export logic. Reaches the window only through the narrow {@link CoordinatorHost}
 * interface; {@code MainController} keeps one-line delegations. Deliberately separate from
 * {@code MermaidCoordinator} so Mermaid's shipped path is untouched — the two share only the generic
 * {@code editor/DiagramImages} render-cache convention.
 */
final class DiagramCoordinator {

    private final CoordinatorHost host;
    private final DiagramService service = new DiagramService();

    DiagramCoordinator(CoordinatorHost host) {
        this.host = host;
    }

    /** The underlying service — exposed only for the Settings → Diagrams detection-status rows. */
    DiagramService service() {
        return service;
    }

    /** Renders a DOT/PlantUML buffer to {@code dest} (format by extension) — the non-interactive path used by
     *  the preview right-click "Export to PDF" (which already chose the file), mirroring
     *  {@code MermaidCoordinator.exportDiagram}. */
    void exportToPath(
            com.editora.diagram.DiagramKind kind,
            String source,
            java.nio.file.Path dest,
            java.util.function.Consumer<com.editora.process.ProcessRunner.Result> onResult) {
        service.export(kind, source, dest, host.appThemeDark(), onResult);
    }

    /** Whether the diagram feature is enabled in Settings (default on). */
    boolean isEnabled() {
        return host.settings().isDiagramSupport();
    }

    /**
     * Reconciles the feature with its setting: pushes the dot/plantuml commands + the theme + the enabled
     * flag into the render façade and re-renders open previews (so a .dot/.puml buffer flips between diagram
     * and plain text as the feature toggles). Runs at startup and on every settings/theme apply — mirrors
     * {@code MermaidCoordinator.applySupport}. Tool detection is on-demand (the Settings → Diagrams status row
     * + the graceful in-preview error when a tool is missing), so there's no runtime availability gate here.
     */
    void applySupport() {
        Settings s = host.settings();
        boolean on = s.isDiagramSupport();
        service.setPaths(java.util.Map.of(DiagramKind.DOT, s.getDotPath(), DiagramKind.PLANTUML, s.getPlantumlPath()));
        DiagramImages.configure(on, service.commands(), host.appThemeDark());
        host.forEachBuffer(b -> {
            host.ensurePreviewControls(b);
            if (on && b.isRenderedDiagram()) {
                host.restoreMarkdownMode(b);
            }
            b.refreshPreview();
        });
    }

    /** {@code diagram.export}: save the active DOT/PlantUML diagram as SVG/PNG/PDF (no-op when off). */
    void export() {
        ifEnabled(() -> {
            EditorBuffer b = host.activeBuffer();
            DiagramKind kind = b == null ? null : b.diagramKind();
            if (kind == null) {
                host.setStatus(tr("status.diagram.notDiagram"));
                return;
            }
            String source = b.getContent();
            FileChooser chooser = new FileChooser();
            chooser.setTitle(tr("dialog.diagramExport.title"));
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
            host.setStatus(tr("status.diagram.exporting"));
            service.export(kind, source, f.toPath(), host.appThemeDark(), r -> {
                if (r.ok()) {
                    host.setStatus(tr("status.diagram.exported", f.toString()));
                } else {
                    String msg = r.message();
                    host.setStatus(tr("status.diagram.exportFailed", msg));
                    Alert err = new Alert(Alert.AlertType.ERROR);
                    err.initOwner(host.window());
                    err.setTitle(tr("dialog.diagramExport.title"));
                    err.setHeaderText(tr("status.diagram.exportFailed", ""));
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
            host.setStatus(tr("statusbar.tip.diagramDisabled"));
        }
    }
}
