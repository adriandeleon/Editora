package com.editora.ui;

import java.nio.file.Path;

import javafx.beans.value.ChangeListener;
import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;

import com.editora.editor.EditorBuffer;
import com.editora.editor.MarkdownRenderer;
import com.editora.lsp.LspManager;
import org.fxmisc.richtext.CodeArea;

import static com.editora.i18n.Messages.tr;

/**
 * Language Server Protocol integration, extracted from {@link MainController} via the
 * {@link CoordinatorHost} pattern. This first slice owns the on-demand <em>navigation/format</em> flows —
 * go-to-definition, find-references, hover, and document formatting (the {@code lsp.*} palette commands and
 * the editor right-click items) — plus the dismissable hover popup.
 *
 * <p>The {@link LspManager} is <em>not</em> owned here: it stays constructed in {@code MainController} because
 * the Debug (DAP) integration layers on the same jdtls session and the MCP bridge reads its diagnostics, so
 * the manager must remain reachable from both. The coordinator takes it as a constructor argument. Diagnostics
 * routing and the gating/lifecycle wiring move in later phases.
 */
final class LspCoordinator {

    /** Window hooks beyond {@link CoordinatorHost} that the LSP nav/format flows need. */
    interface Ops {
        /** Opens {@code file} (if needed) and moves the caret to a 0-based LSP line/column. */
        void openAndGoto(Path file, int line0, int col0);

        /** Whether the active buffer is editable (formatting is a no-op on a read-only/huge buffer). */
        boolean activeEditable();
    }

    private final CoordinatorHost host;
    private final LspManager lspManager;
    private final Ops ops;

    /** The currently-showing LSP hover popup (dismissable), or null. */
    private Popup hoverPopup;

    LspCoordinator(CoordinatorHost host, LspManager lspManager, Ops ops) {
        this.host = host;
        this.lspManager = lspManager;
        this.ops = ops;
    }

    /** The active buffer if it is LSP-managed, reporting + returning null otherwise. */
    private EditorBuffer activeLspBuffer() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || b.getPath() == null || !lspManager.isManaged(b.getPath())) {
            host.setStatus(tr("status.lsp.unavailable"));
            return null;
        }
        return b;
    }

    void gotoDefinition() {
        EditorBuffer b = activeLspBuffer();
        if (b == null) {
            return;
        }
        CodeArea area = b.getFocusedArea();
        lspManager.changeDocument(b.getPath(), b.text()); // sync latest text before the request
        lspManager.definition(b.getPath(), area.getCurrentParagraph(), area.getCaretColumn(), targets -> {
            if (targets.isEmpty()) {
                host.setStatus(tr("status.lsp.noDefinition"));
            } else {
                LspManager.Target t = targets.get(0);
                ops.openAndGoto(t.file(), t.line(), t.character());
            }
        });
    }

    void findReferences() {
        EditorBuffer b = activeLspBuffer();
        if (b == null) {
            return;
        }
        CodeArea area = b.getFocusedArea();
        lspManager.changeDocument(b.getPath(), b.text()); // sync latest text before the request
        lspManager.references(b.getPath(), area.getCurrentParagraph(), area.getCaretColumn(), targets -> {
            if (targets.isEmpty()) {
                host.setStatus(tr("status.lsp.noReferences"));
                return;
            }
            QuickOpen<LspManager.Target> picker = new QuickOpen<>(
                    tr("lsp.references.title"),
                    tr("lsp.references.prompt"),
                    () -> targets,
                    t -> t.file().getFileName() + ":" + (t.line() + 1),
                    t -> t.file().toString(),
                    t -> ops.openAndGoto(t.file(), t.line(), t.character()));
            picker.setOverlayHost(host.overlayHost());
            picker.show(host.window());
        });
    }

    void showHover() {
        EditorBuffer b = activeLspBuffer();
        if (b == null) {
            return;
        }
        CodeArea area = b.getFocusedArea();
        lspManager.changeDocument(b.getPath(), b.text()); // sync latest text before the request
        lspManager.hover(b.getPath(), area.getCurrentParagraph(), area.getCaretColumn(), text -> {
            if (text == null || text.isBlank()) {
                host.setStatus(tr("status.lsp.noHover"));
            } else {
                showHoverPopup(area, text);
            }
        });
    }

    /** Reformats the whole active file via its language server ({@code textDocument/formatting}), if the
     *  server is running and advertises formatting. Edits apply through the undoable buffer. */
    void formatDocument() {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null || buffer.getPath() == null || !ops.activeEditable()) {
            host.setStatus(tr("status.lsp.formatUnavailable"));
            return;
        }
        Path path = buffer.getPath();
        if (!lspManager.isManaged(path) || !lspManager.supportsFormatting(path)) {
            host.setStatus(tr("status.lsp.formatUnavailable"));
            return;
        }
        int tabSize = host.settings().getTabSize();
        host.setStatus(tr("status.lsp.formatting"));
        lspManager.formatDocument(path, tabSize, buffer.detectInsertSpaces(tabSize), edits -> {
            if (buffer != host.activeBuffer()) {
                return; // user switched tabs before the server replied
            }
            if (edits.isEmpty()) {
                host.setStatus(tr("status.lsp.formatNoChange"));
                return;
            }
            buffer.applyLspEdits(edits);
            host.setStatus(tr("status.lsp.formatted"));
        });
    }

    /**
     * Shows LSP hover markdown in a dismissable popup at the caret (rendered via the Markdown renderer).
     * Closes on Escape, a click elsewhere (auto-hide), caret movement, scrolling, or another hover.
     */
    private void showHoverPopup(CodeArea area, String markdown) {
        hideHoverPopup();
        Node content;
        try {
            content = MarkdownRenderer.renderDocument(MarkdownRenderer.parseToDocument(markdown), null);
        } catch (RuntimeException e) {
            Label label = new Label(markdown);
            label.setWrapText(true);
            content = label;
        }
        VBox box = new VBox(content);
        box.getStyleClass().add("lsp-hover-popup");
        box.setMaxWidth(560);
        box.getStylesheets()
                .addAll(
                        getClass().getResource("/com/editora/styles/app.css").toExternalForm(),
                        getClass().getResource("/com/editora/styles/syntax.css").toExternalForm());

        Popup popup = new Popup();
        popup.setAutoHide(true); // click outside / focus loss dismisses it
        popup.setConsumeAutoHidingEvents(false);
        popup.getContent().add(box);
        hoverPopup = popup;

        // Dismiss on Escape, caret movement, or scroll — all detached again when the popup hides.
        EventHandler<KeyEvent> esc = ev -> {
            if (ev.getCode() == KeyCode.ESCAPE) {
                hideHoverPopup();
                ev.consume();
            }
        };
        ChangeListener<Object> dismiss = (o, a, b) -> hideHoverPopup();
        area.addEventFilter(KeyEvent.KEY_PRESSED, esc);
        area.caretPositionProperty().addListener(dismiss);
        area.estimatedScrollYProperty().addListener(dismiss);
        popup.setOnHidden(ev -> {
            area.removeEventFilter(KeyEvent.KEY_PRESSED, esc);
            area.caretPositionProperty().removeListener(dismiss);
            area.estimatedScrollYProperty().removeListener(dismiss);
            if (hoverPopup == popup) {
                hoverPopup = null;
            }
        });

        var bounds = area.getCaretBounds().orElse(null);
        if (bounds != null) {
            popup.show(area, bounds.getMinX(), bounds.getMaxY());
        } else {
            popup.show(area, 0, 0);
        }
    }

    /** Hides the LSP hover popup if one is showing. */
    private void hideHoverPopup() {
        if (hoverPopup != null) {
            hoverPopup.hide();
            hoverPopup = null;
        }
    }
}
