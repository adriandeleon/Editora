package com.editora.ui;

import javafx.application.Platform;
import javafx.scene.control.Tab;

import com.editora.command.Command;

import static com.editora.i18n.Messages.tr;

/**
 * The commands behind the buttons of the smaller tool windows (AI Agent, Structure, Bookmarks, Personal Notes,
 * Markdown Lint) and of the image and PDF viewers, which only the mouse reached. Each runs the method its
 * button runs, on the same target: the window's own panel, or the viewer in the active tab.
 */
final class PanelViewerButtonCommands {

    private PanelViewerButtonCommands() {}

    /** Registers this area's commands on {@code mc}'s registry. */
    static void register(MainController mc) {
        mc.registry.register(Command.of("agent.toggleDetached", () -> {
            if (mc.agentCoordinator.isEnabled()) {
                mc.agentCoordinator.panel().toggleDetached();
            } else {
                mc.setStatus(tr("status.agent.disabled"));
            }
        }));
        mc.registry.register(Command.of("structure.filterKinds", () -> filterStructureKinds(mc)));
        mc.registry.register(Command.of("bookmarks.toggleAllProjects", () -> {
            reveal(mc, mc.bookmarksToolWindow);
            mc.bookmarkCoordinator.panel().toggleAllProjects();
        }));
        mc.registry.register(Command.of(
                "notes.toggleAllProjects",
                () -> mc.notesCoordinator.ifEnabled(() -> {
                    reveal(mc, mc.notesToolWindow);
                    mc.notesCoordinator.panel().toggleAllProjects();
                })));
        mc.registry.register(Command.of("markdownLint.toggleBySeverity", () -> {
            reveal(mc, mc.markdownLintToolWindow);
            mc.markdownLintPanel().toggleBySeverity();
        }));
        mc.registry.register(Command.of("viewer.previousPage", () -> turnPage(mc, false)));
        mc.registry.register(Command.of("viewer.nextPage", () -> turnPage(mc, true)));
        mc.registry.register(Command.of("viewer.fit", () -> {
            Tab tab = mc.activeTab();
            PdfViewerPane pdf = MainController.pdfPaneOf(tab);
            ImageViewerPane image = MainController.imagePaneOf(tab);
            if (pdf != null) {
                pdf.fitToWindow();
            } else if (image != null) {
                image.fitToWindow();
            } else {
                mc.setStatus(tr("status.viewer.notViewer"));
            }
        }));
    }

    /**
     * The content zoom ({@code view.textZoomIn} / {@code Out} / {@code Reset}) for the active tab when it shows an
     * image or a PDF: zooms that viewer as its "+", "−" and "1:1" buttons do and answers true; false when the
     * active tab is not a viewer, so {@code MainController.textZoom} goes on to zoom the text.
     */
    static boolean zoomViewer(MainController mc, int direction) {
        Tab tab = mc.activeTab();
        PdfViewerPane pdf = MainController.pdfPaneOf(tab);
        ImageViewerPane image = MainController.imagePaneOf(tab);
        if (pdf != null) {
            if (direction > 0) {
                pdf.zoomIn();
            } else if (direction < 0) {
                pdf.zoomOut();
            } else {
                pdf.actualSize();
            }
        } else if (image != null) {
            if (direction > 0) {
                image.zoomIn();
            } else if (direction < 0) {
                image.zoomOut();
            } else {
                image.actualSize();
            }
        }
        return pdf != null || image != null;
    }

    /** Opens {@code window} when it is closed, so a toggle run from the keyboard shows what it changed. */
    private static void reveal(MainController mc, ToolWindow window) {
        if (!mc.toolWindows.isOpen(window)) {
            mc.toolWindows.open(window);
        }
    }

    /** Shows the Structure window's menu of symbol kinds, opening the window first; says when there is none. */
    private static void filterStructureKinds(MainController mc) {
        if (mc.activeBuffer() == null) { // the Structure window is for a text buffer
            mc.setStatus(tr("status.structure.noKinds"));
            return;
        }
        Runnable show = () -> {
            if (!mc.structurePanel().showKindFilter()) {
                mc.setStatus(tr("status.structure.noKinds"));
            }
        };
        if (mc.toolWindows.isOpen(mc.structureToolWindow)) {
            show.run();
        } else {
            mc.toolWindows.open(mc.structureToolWindow); // builds the outline, and with it the menu
            Platform.runLater(show); // once the button has been laid out, so its menu has somewhere to open
        }
    }

    /** Turns the page of the PDF in the active tab, or says that the active tab is not one. */
    private static void turnPage(MainController mc, boolean forward) {
        PdfViewerPane pdf = MainController.pdfPaneOf(mc.activeTab());
        if (pdf == null) {
            mc.setStatus(tr("status.viewer.notPdf"));
        } else if (forward) {
            pdf.nextPage();
        } else {
            pdf.previousPage();
        }
    }
}
