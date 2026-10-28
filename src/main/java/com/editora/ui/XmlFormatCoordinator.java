package com.editora.ui;

import java.nio.file.Path;

import javafx.application.Platform;

import com.editora.editor.EditorBuffer;
import com.editora.format.XmlFormatter;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Format Document on an XML file no language server formats — the XML server off, not installed, or LSP support
 * off altogether (XML review C8): Editora's own {@link XmlFormatter}, indenting with the buffer's indent unit,
 * applied as one undoable edit. A running server that formats keeps the job.
 *
 * <p>The formatting itself runs on a virtual thread: a 6 MB file took over a second, all of it on the FX thread
 * (XML review 2 C10). The result is applied on the FX thread only when the tab is still the active one and its
 * text has not changed meanwhile, and the caret goes back to the same character it was on rather than to the end
 * of the reformatted text (C4).
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
        String indent = buffer.indentUnit();
        int caret = buffer.getArea().getCaretPosition();
        host.setStatus(tr("status.lsp.formatting"));
        Thread.ofVirtual().name("xml-format").start(() -> {
            String after = format(before, indent);
            int newCaret = after == null ? caret : caretAfter(before, after, caret);
            Platform.runLater(() -> apply(buffer, before, after, newCaret));
        });
        return true;
    }

    private void apply(EditorBuffer buffer, String before, @Nullable String after, int caret) {
        if (buffer != host.activeBuffer() || !buffer.getArea().getText().equals(before)) {
            host.setStatus(tr("status.lsp.formatStale")); // edited (or left) mid-format: a re-run formats cleanly
            return;
        }
        if (after == null) {
            host.setError(tr("status.xml.formatNotWellFormed"));
            return;
        }
        int[] span = com.editora.format.PythonFormatter.changedSpan(before, after);
        if (span == null) {
            host.setStatus(tr("status.lsp.formatNoChange"));
            return;
        }
        if (!NoUndoGuard.allow(buffer, tr("noUndo.op.format"))) {
            return;
        }
        buffer.getArea().replaceText(span[0], span[1], after.substring(span[0], span[2]));
        buffer.getArea().moveTo(Math.min(caret, buffer.getArea().getLength()));
        buffer.getArea().requestFollowCaret();
        host.setStatus(tr("status.xml.formatted"));
    }

    /** {@code text} formatted with {@code indent} per level, or null when it does not nest properly. */
    static @Nullable String format(String text, @Nullable String indent) {
        return XmlFormatter.format(text, indent == null || indent.isEmpty() ? "  " : indent);
    }

    /**
     * Where a caret at {@code caret} in {@code before} belongs in {@code after}, a copy that differs only in
     * whitespace: on the same non-whitespace character. A caret just after a character stays just after it (the
     * end of a line stays the end of that line); one in whitespace before a character moves to just before it
     * (into the new indentation, not the end of the previous line).
     */
    static int caretAfter(String before, String after, int caret) {
        int at = Math.max(0, Math.min(caret, before.length()));
        int count = 0; // non-whitespace characters before the caret
        for (int i = 0; i < at; i++) {
            if (!Character.isWhitespace(before.charAt(i))) {
                count++;
            }
        }
        boolean stickToPrevious = at > 0 && !Character.isWhitespace(before.charAt(at - 1));
        int seen = 0;
        for (int i = 0; i < after.length(); i++) {
            if (Character.isWhitespace(after.charAt(i))) {
                continue;
            }
            if (!stickToPrevious && seen == count) {
                return i;
            }
            seen++;
            if (stickToPrevious && seen == count) {
                return i + 1;
            }
        }
        return after.length();
    }
}
