package com.editora.ui;

import java.nio.file.Path;

import com.editora.editor.EditorBuffer;
import com.editora.format.XmlFormatter;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Format Document on an XML file no language server formats — the XML server off, not installed, or LSP support
 * off altogether (XML review C8): Editora's own {@link XmlFormatter}, indenting with the buffer's indent unit,
 * applied as one undoable edit. A running server that formats keeps the job. FX thread.
 */
final class XmlFormatCoordinator {

    private final PythonFormatCoordinator.Host host;

    XmlFormatCoordinator(PythonFormatCoordinator.Host host) {
        this.host = host;
    }

    /** Formats the active buffer when it is XML a server will not format; true when it took the job. */
    boolean formatActive() {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null || !"xml".equals(buffer.getLanguage())) {
            return false;
        }
        Path path = buffer.getPath();
        if (path != null && host.serverFormats(path)) {
            return false;
        }
        if (!host.activeEditable() || buffer.isNarrowed()) {
            host.setStatus(tr("status.lsp.formatUnavailable"));
            return true;
        }
        String before = buffer.getArea().getText();
        String after = format(before, buffer.indentUnit());
        if (after == null) {
            host.setError(tr("status.xml.formatNotWellFormed"));
            return true;
        }
        int[] span = com.editora.format.PythonFormatter.changedSpan(before, after);
        if (span == null) {
            host.setStatus(tr("status.lsp.formatNoChange"));
            return true;
        }
        if (!NoUndoGuard.allow(buffer, tr("noUndo.op.lsp"))) {
            return true;
        }
        buffer.getArea().replaceText(span[0], span[1], after.substring(span[0], span[2]));
        host.setStatus(tr("status.xml.formatted"));
        return true;
    }

    /** {@code text} formatted with {@code indent} per level, or null when it does not nest properly. */
    static @Nullable String format(String text, String indent) {
        return XmlFormatter.format(text, indent == null || indent.isEmpty() ? "  " : indent);
    }
}
